package example.strictentry;

import io.github.pjlabs.blockless.AsyncSupplier;
import io.github.pjlabs.blockless.continuations.Continuations;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The same compiled business code is run without and with the startup agent. Without the agent,
 * pending get returns its value but occupies the platform worker; nonblocking acceptance fails
 * after bounded diagnosis and cleanup. With the agent, selected executor factories establish
 * logical VT entry before user code starts.
 */
public final class StrictSupplierEntryProbe {
  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--supplier-compatibility")) {
      unconfiguredPlatformGetReturnsActualValue();
      return;
    }
    immediateGetReturnsValueAndPreservesCaller();
    originalBusinessFailurePropagatesAfterCleanup();
    cancellationInterruptsOnlyTheLogicalCaller();
    independentBodyFinishesAfterItsOriginalExecutorTerminates();
    System.out.println("PASS: strict direct supplier/get contract and managed executor lifecycle");
  }

  private static void unconfiguredPlatformGetReturnsActualValue() throws Exception {
    final var gates = new QueryGates();
    final var carriers = new CountingPlatformFactory();
    try (final var executor = Executors.newFixedThreadPool(1, carriers)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  require(
                      !Thread.currentThread().isVirtual(),
                      "compatibility control must use an unconfigured platform caller");
                  return loadUser(gates);
                });
        require(
            gates.atGet.await(5, TimeUnit.SECONDS) && gates.bodyStarted.await(5, TimeUnit.SECONDS),
            "compatibility get did not reach a pending body");
        if (caller.isDone()) caller.get(5, TimeUnit.SECONDS);
        require(!caller.isDone(), "get returned before the held I/O result existed");
        gates.finishQuery.countDown();
        gates.finishCleanup.countDown();
        require(
            caller.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
            "unconfigured platform get did not return the actual User");
        executor.shutdown();
        require(
            executor.awaitTermination(5, TimeUnit.SECONDS),
            "compatibility executor did not terminate");
        require(
            gates.bodyEntries.get() == 1 && gates.bodyCleanup.get() == 1,
            "compatibility body replayed or skipped finally");
      } finally {
        gates.finishQuery.countDown();
        gates.finishCleanup.countDown();
      }
    }
    System.out.println(
        "PASS Supplier compatibility only: unconfigured platform get returns actual User without a"
            + " pending exception; this does not demonstrate carrier release");
  }

  private static void immediateGetReturnsValueAndPreservesCaller() throws Exception {
    final var gates = new QueryGates();
    final var carriers = new CountingPlatformFactory();
    final var callerCleanup = new AtomicInteger();
    final var callerEntries = new AtomicInteger();
    final var afterGet = new AtomicInteger();
    final var logicalCaller = new AtomicReference<Thread>();
    final var lock = new ReentrantLock();
    CONTEXT.set("outside");

    // Source is deliberately a standard platform factory, unchanged between both runs.
    try (final var executor = Executors.newFixedThreadPool(1, carriers)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  logicalCaller.set(Thread.currentThread());
                  callerEntries.incrementAndGet();
                  CONTEXT.set("request");
                  lock.lock();
                  try {
                    final var savedLocal = "scope:" + 42;
                    final var result = loadUser(gates);
                    require(savedLocal.equals("scope:42"), "caller local lost across get");
                    require(CONTEXT.get().equals("request"), "caller context lost across get");
                    require(
                        Thread.currentThread() == logicalCaller.get(),
                        "logical caller identity changed");
                    require(lock.isHeldByCurrentThread(), "caller lock ownership lost");
                    afterGet.incrementAndGet();
                    return result;
                  } finally {
                    CONTEXT.remove();
                    lock.unlock();
                    callerCleanup.incrementAndGet();
                  }
                });

        require(gates.atGet.await(5, TimeUnit.SECONDS), "caller never reached immediate get");
        require(gates.bodyStarted.await(5, TimeUnit.SECONDS), "I/O body never started");
        final var marker =
            executor.submit(
                () -> {
                  final var thread = Thread.currentThread();
                  final var acquired = lock.tryLock();
                  if (acquired) lock.unlock();
                  // Inspect identity without assuming startup instrumentation is active.
                  return new Marker(thread, currentCarrier(thread), CONTEXT.get(), acquired);
                });
        final Marker observed;
        try {
          observed = marker.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException occupiedWorker) {
          // Diagnose ordinary platform waiting without stranding the query or its finally.
          gates.finishQuery.countDown();
          gates.finishCleanup.countDown();
          require(
              caller.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
              "unconfigured get did not return the real value after release");
          executor.shutdown();
          require(
              executor.awaitTermination(5, TimeUnit.SECONDS),
              "diagnostic cleanup did not terminate the platform executor");
          require(
              callerCleanup.get() == 1 && gates.bodyCleanup.get() == 1,
              "diagnostic cleanup skipped caller or body finally");
          throw new AssertionError(
              "single platform carrier remained occupied by pending get", occupiedWorker);
        }

        if (caller.isDone()) caller.get(5, TimeUnit.SECONDS);
        require(!caller.isDone(), "caller returned before the held I/O result existed");
        require(
            logicalCaller.get().isVirtual(),
            "automatic entry did not establish a logical VT caller");
        require(observed.logicalThread().isVirtual(), "marker did not get its own logical VT");
        require(
            observed.logicalThread() != logicalCaller.get(),
            "logical roots share a Thread identity");
        require(
            observed.carrier() == carriers.first.get(),
            "original physical carrier was not freed for marker");
        require(carriers.created.get() == 1, "replacement platform carrier disguised blocked get");
        require(observed.context() == null, "unrelated root inherited request context");
        require(!observed.acquiredCallerLock(), "unrelated root inherited caller lock ownership");
        require(
            callerCleanup.get() == 0 && gates.bodyCleanup.get() == 0,
            "finally ran during ordinary suspension");
        require(
            afterGet.get() == 0 && callerEntries.get() == 1,
            "caller advanced or replayed while pending");
        require(CONTEXT.get().equals("outside"), "logical root changed submitting thread context");

        executor.shutdown();
        require(
            !executor.awaitTermination(30, TimeUnit.MILLISECONDS),
            "shutdown abandoned pending logical caller");
        gates.finishQuery.countDown();
        require(gates.cleanupStarted.await(5, TimeUnit.SECONDS), "body finally did not start");
        require(!caller.isDone(), "get returned before blocking body finally completed");
        require(
            !executor.awaitTermination(30, TimeUnit.MILLISECONDS),
            "termination skipped blocking body cleanup");
        gates.finishCleanup.countDown();

        require(
            caller.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
            "wrong actual User value");
        require(
            executor.awaitTermination(5, TimeUnit.SECONDS),
            "shutdown did not finish after caller finally");
        require(
            callerEntries.get() == 1 && gates.bodyEntries.get() == 1,
            "caller or query body replayed");
        require(afterGet.get() == 1, "code after get did not execute exactly once");
        require(
            callerCleanup.get() == 1 && gates.bodyCleanup.get() == 1,
            "finally skipped or repeated");
        require(carriers.created.get() == 1, "worker count changed during completion");
      } finally {
        // Gates must open BEFORE ExecutorService.close waits for logical tasks.
        gates.finishQuery.countDown();
        gates.finishCleanup.countDown();
      }
    } finally {
      CONTEXT.remove();
    }
    System.out.println(
        "PASS: immediate user.get returns actual User; automatic VT entry, same physical carrier"
            + " progress, locals/TLS/locks, no replay and complete finally before shutdown");
  }

  /** Business call shape: ordinary blocking code in one supplier, followed by direct get. */
  private static User loadUser(QueryGates gates) {
    final var user =
        Continuations.supplier(
            () -> {
              gates.bodyEntries.incrementAndGet();
              try {
                final var details = loadDetails(42, gates);
                final var permissions = loadPermissions(42);
                return buildUser(details, permissions);
              } finally {
                gates.cleanupStarted.countDown();
                gates.finishCleanup.await();
                gates.bodyCleanup.incrementAndGet();
              }
            });
    gates.atGet.countDown();
    return user.get();
  }

  private static String loadDetails(int id, QueryGates gates) throws InterruptedException {
    gates.bodyStarted.countDown();
    gates.finishQuery.await();
    return "details:" + id;
  }

  private static String loadPermissions(int id) {
    return "read";
  }

  private static User buildUser(String details, String permissions) {
    return new User(details, permissions);
  }

  private static void originalBusinessFailurePropagatesAfterCleanup() throws Exception {
    final var bodyStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var cleanup = new AtomicInteger();
    final var callerCleanup = new AtomicInteger();
    final var originalFailure = new IllegalArgumentException("original query failure");
    final var carriers = new CountingPlatformFactory();
    try (final var executor = Executors.newFixedThreadPool(1, carriers)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  try {
                    final var user =
                        Continuations.supplier(
                            () -> {
                              try {
                                bodyStarted.countDown();
                                finishBody.await();
                                throw originalFailure;
                              } finally {
                                cleanup.incrementAndGet();
                              }
                            });
                    return user.get();
                  } finally {
                    callerCleanup.incrementAndGet();
                  }
                });
        require(bodyStarted.await(5, TimeUnit.SECONDS), "failing query not started");
        executor.submit(() -> currentCarrier(Thread.currentThread())).get(5, TimeUnit.SECONDS);
        require(!caller.isDone(), "failure published before failing query was released");
        finishBody.countDown();
        try {
          caller.get(5, TimeUnit.SECONDS);
          throw new AssertionError("business failure did not propagate");
        } catch (ExecutionException reported) {
          require(
              reported.getCause() == originalFailure, "original business failure identity changed");
        }
        executor.shutdown();
        require(executor.awaitTermination(5, TimeUnit.SECONDS), "failed caller did not finish");
        require(
            cleanup.get() == 1 && callerCleanup.get() == 1, "failure skipped or repeated finally");
      } finally {
        finishBody.countDown();
      }
    }
    System.out.println(
        "PASS: original business exception propagates through direct get after query and caller"
            + " cleanup");
  }

  private static void cancellationInterruptsOnlyTheLogicalCaller() throws Exception {
    final var bodyStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var otherStarted = new CountDownLatch(1);
    final var finishOther = new CountDownLatch(1);
    final var cleanupStarted = new CountDownLatch(1);
    final var finishCleanup = new CountDownLatch(1);
    final var cleanupFinished = new AtomicInteger();
    final var otherInterrupted = new AtomicBoolean();
    final var callerInterrupted = new AtomicBoolean();
    final var carriers = new CountingPlatformFactory();
    try (final var executor = Executors.newFixedThreadPool(1, carriers)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  try {
                    final var user =
                        Continuations.supplier(
                            () -> {
                              bodyStarted.countDown();
                              finishBody.await();
                              return "hello";
                            });
                    return user.get();
                  } finally {
                    callerInterrupted.set(Thread.interrupted());
                    cleanupStarted.countDown();
                    try {
                      finishCleanup.await();
                    } finally {
                      cleanupFinished.incrementAndGet();
                    }
                  }
                });
        require(bodyStarted.await(5, TimeUnit.SECONDS), "cancellable caller query not started");
        final var unrelated =
            executor.submit(
                () -> {
                  require(
                      currentCarrier(Thread.currentThread()) == carriers.first.get(),
                      "unrelated task did not share physical carrier");
                  otherStarted.countDown();
                  try {
                    finishOther.await();
                  } catch (InterruptedException interrupted) {
                    otherInterrupted.set(true);
                  }
                });
        require(otherStarted.await(5, TimeUnit.SECONDS), "unrelated root did not start");
        require(caller.cancel(true), "logical caller cancellation declined");
        require(
            cleanupStarted.await(5, TimeUnit.SECONDS), "cancelled caller did not enter finally");
        require(callerInterrupted.get(), "cancellation did not interrupt logical caller");
        executor
            .submit(
                () -> {
                  require(
                      currentCarrier(Thread.currentThread()) == carriers.first.get(),
                      "carrier not reusable after cancel");
                  require(
                      !Thread.currentThread().isInterrupted(),
                      "cancel leaked interrupt to unrelated root");
                })
            .get(5, TimeUnit.SECONDS);
        require(!unrelated.isDone() && !otherInterrupted.get(), "cancellation hit unrelated root");
        try {
          caller.get();
          throw new AssertionError("cancelled Future returned a value");
        } catch (CancellationException expectedCancellation) {
        }
        executor.shutdown();
        require(
            !executor.awaitTermination(30, TimeUnit.MILLISECONDS),
            "cancellation caused early executor termination");
        finishOther.countDown();
        finishBody.countDown(); // Production supplier cancellation is unstructured; release its
        // independent body.
        finishCleanup.countDown();
        unrelated.get(5, TimeUnit.SECONDS);
        require(
            executor.awaitTermination(5, TimeUnit.SECONDS),
            "cancelled logical caller cleanup not joined");
        require(
            cleanupFinished.get() == 1 && carriers.created.get() == 1,
            "cleanup or carrier lifetime wrong");
      } finally {
        finishBody.countDown();
        finishOther.countDown();
        finishCleanup.countDown();
      }
    }
    System.out.println(
        "PASS: cancel targets logical caller, keeps shared physical carrier/unrelated root clean,"
            + " and shutdown waits actual caller finally");
  }

  private static void independentBodyFinishesAfterItsOriginalExecutorTerminates() throws Exception {
    final var bodyStarted = new CountDownLatch(1);
    final var atGet = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var callerFinished = new CountDownLatch(1);
    final var outcomeReadable = new CountDownLatch(1);
    final var bodyContext = new ThreadLocal<String>();
    final var bodyThread = new AtomicReference<Thread>();
    final var resumedCarrier = new AtomicReference<Thread>();
    final var result = new AtomicReference<AsyncSupplier<String>>();
    final var bodyCleanup = new AtomicInteger();
    final var callerCleanup = new AtomicInteger();
    final var carriers = new CountingPlatformFactory();

    try (final var executor = Executors.newFixedThreadPool(1, carriers)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  try {
                    final var user =
                        Continuations.supplier(
                            Runnable::run,
                            () -> {
                              bodyThread.set(Thread.currentThread());
                              bodyContext.set("body context");
                              final var savedLocal = "saved";
                              try {
                                require(
                                    currentCarrier(Thread.currentThread()) == carriers.first.get(),
                                    "direct supplier did not initially inherit the owned carrier");
                                bodyStarted.countDown();
                                finishBody.await();
                                resumedCarrier.set(currentCarrier(Thread.currentThread()));
                                require(
                                    savedLocal.equals("saved"), "independent body local was lost");
                                require(
                                    Thread.currentThread() == bodyThread.get(),
                                    "independent body identity changed");
                                require(
                                    bodyContext.get().equals("body context"),
                                    "independent body context was lost");
                                return "saved:ready";
                              } finally {
                                bodyContext.remove();
                                bodyCleanup.incrementAndGet();
                              }
                            });
                    result.set(user);
                    atGet.countDown();
                    return user.get();
                  } finally {
                    Thread.interrupted();
                    callerCleanup.incrementAndGet();
                    callerFinished.countDown();
                  }
                });
        require(
            atGet.await(5, TimeUnit.SECONDS) && bodyStarted.await(5, TimeUnit.SECONDS),
            "direct get and inherited producer did not start");
        executor
            .submit(
                () -> {
                  require(
                      currentCarrier(Thread.currentThread()) == carriers.first.get(),
                      "direct get kept its original carrier occupied");
                })
            .get(5, TimeUnit.SECONDS);
        require(!caller.isDone() && !result.get().isDone(), "held direct get returned early");
        require(caller.cancel(true), "logical caller cancellation declined");
        require(
            callerFinished.await(5, TimeUnit.SECONDS), "cancelled caller did not finish finally");
        executor.shutdown();
        require(
            executor.awaitTermination(5, TimeUnit.SECONDS),
            "executor did not fully terminate after logical caller exit");
        require(executor.isTerminated(), "executor is still live before independent body release");
        require(
            bodyCleanup.get() == 0 && !result.get().isDone(),
            "independent body was not held through owner termination");

        // Harness notification only: business code above still uses immediate user.get().
        result.get().onComplete(outcomeReadable::countDown);
        finishBody.countDown();
        require(
            outcomeReadable.await(5, TimeUnit.SECONDS),
            "retired scheduler stranded the independent producer or its outcome");
        require(result.get().get().equals("saved:ready"), "actual cached body value was lost");
        require(
            bodyCleanup.get() == 1 && callerCleanup.get() == 1,
            "independent body or caller finally skipped or repeated");
        require(
            resumedCarrier.get() != carriers.first.get(),
            "independent body unexpectedly resumed on the closed original carrier");
        bodyThread.get().join(5_000);
        require(!bodyThread.get().isAlive(), "independent producer never terminated");
        require(
            carriers.created.get() == 1, "retirement recreated a supposedly closed owned carrier");
      } finally {
        finishBody.countDown();
      }
    }
    System.out.println(
        "PASS: caller cancelled and owner fully terminated before independent body release; actual"
            + " body finally, context, identity and cached outcome survive carrier retirement");
  }

  private static Thread currentCarrier(Thread current) {
    if (!current.isVirtual()) return current;
    try {
      // Diagnostic only. Startup agent opens java.lang; business supplier/get does not use this.
      final var lookup = MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup());
      final var method =
          lookup.findStatic(
              Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
      return (Thread) method.invokeExact();
    } catch (Throwable failure) {
      throw new AssertionError("cannot inspect physical carrier", failure);
    }
  }

  private record User(String details, String permissions) {}

  private record Marker(
      Thread logicalThread, Thread carrier, String context, boolean acquiredCallerLock) {}

  private static final class QueryGates {
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    final CountDownLatch atGet = new CountDownLatch(1);
    final CountDownLatch finishQuery = new CountDownLatch(1);
    final CountDownLatch cleanupStarted = new CountDownLatch(1);
    final CountDownLatch finishCleanup = new CountDownLatch(1);
    final AtomicInteger bodyEntries = new AtomicInteger();
    final AtomicInteger bodyCleanup = new AtomicInteger();
  }

  private static final class CountingPlatformFactory implements ThreadFactory {
    final AtomicInteger created = new AtomicInteger();
    final AtomicReference<Thread> first = new AtomicReference<>();

    public Thread newThread(Runnable task) {
      final var index = created.incrementAndGet();
      final var thread = Thread.ofPlatform().name("strict-physical-" + index).unstarted(task);
      first.compareAndSet(null, thread);
      return thread;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
