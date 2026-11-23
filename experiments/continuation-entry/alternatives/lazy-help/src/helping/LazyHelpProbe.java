package helping;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Standalone acceptance probe. The get inside business callables has no callback or wrapper. */
public final class LazyHelpProbe {
  private static final ThreadLocal<String> LOCAL = new ThreadLocal<>();
  private static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();

  public static void main(String[] args) throws Exception {
    platformCallerHelpsThenReturnsActualValue();
    helpedVirtualTaskCannotEnterTheHostsMonitor();
    cancellationAndShutdownRetainTheOriginalWorkersLifetime();
    shutdownNowCancelsNativeAndHelpedRootsAfterTheirCleanup();
    System.out.println(
        "PASS all lazy-help probes; no agent/export/open flags or private JDK classes");
  }

  private static void platformCallerHelpsThenReturnsActualValue() throws Exception {
    final var factory = new CountingFactory();
    final var entered = new CountDownLatch(1);
    final var bodyStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var bodyCleanup = new AtomicInteger();
    final var callerCleanup = new AtomicInteger();
    final var callerIdentity = new AtomicReference<Thread>();
    final var lock = new ReentrantLock();
    final var outsideLoader = Thread.currentThread().getContextClassLoader();
    INHERITED.set("outside");
    try (final var pool = new LazyHelpingExecutor(1, factory)) {
      try {
        final var caller =
            pool.submit(
                () -> {
                  final var original = Thread.currentThread();
                  require(!original.isVirtual(), "Original caller must stay a platform thread");
                  callerIdentity.set(original);
                  LOCAL.set("request");
                  INHERITED.set("request");
                  lock.lock();
                  try {
                    final var saved = "saved-local";
                    final var user =
                        Blockless.supplier(
                            () -> {
                              bodyStarted.countDown();
                              try {
                                finishBody.await();
                                return "actual-user";
                              } finally {
                                bodyCleanup.incrementAndGet();
                              }
                            });
                    entered.countDown();
                    final var value = user.get();
                    require(
                        saved.equals("saved-local") && LOCAL.get().equals("request"),
                        "Caller context lost");
                    require(
                        original == Thread.currentThread() && lock.isHeldByCurrentThread(),
                        "Caller identity/lock changed");
                    return value;
                  } finally {
                    lock.unlock();
                    LOCAL.remove();
                    INHERITED.remove();
                    callerCleanup.incrementAndGet();
                  }
                });
        require(
            entered.await(5, TimeUnit.SECONDS) && bodyStarted.await(5, TimeUnit.SECONDS),
            "Caller not pending");
        final var other =
            pool.submit(
                () -> {
                  require(
                      Thread.currentThread().isVirtual(),
                      "Queued task was not dispatched virtually");
                  require(LOCAL.get() == null, "Ordinary caller TLS leaked");
                  require(INHERITED.get().equals("outside"), "Wrong submitter inherited TLS");
                  require(
                      Thread.currentThread().getContextClassLoader() == outsideLoader,
                      "Wrong submitter CCL");
                  final var acquired = lock.tryLock();
                  if (acquired) lock.unlock();
                  require(!acquired, "Other task inherited caller's lock ownership");
                  return "other-result";
                });
        require(
            other.get(5, TimeUnit.SECONDS).equals("other-result"), "Queued task did not progress");
        require(
            !caller.isDone() && callerCleanup.get() == 0 && bodyCleanup.get() == 0,
            "Original get returned early");
        require(
            factory.created.get() == 1 && callerIdentity.get() == factory.first.get(),
            "Original worker replaced");
        pool.shutdown();
        require(
            !pool.awaitTermination(30, TimeUnit.MILLISECONDS),
            "Owner terminated during native get");
        finishBody.countDown();
        require(caller.get(5, TimeUnit.SECONDS).equals("actual-user"), "Wrong real supplier value");
        require(pool.awaitTermination(5, TimeUnit.SECONDS), "Owner did not finish");
        require(callerCleanup.get() == 1 && bodyCleanup.get() == 1, "Wrong finally count");
      } finally {
        finishBody.countDown();
      }
    } finally {
      INHERITED.remove();
    }
    System.out.println(
        "PASS: A stays on the sole original platform worker, dispatches B as a global VT, then"
            + " returns actual T with locals/context/locks/finally intact");
  }

  private static void helpedVirtualTaskCannotEnterTheHostsMonitor() throws Exception {
    final var monitor = new Object();
    final var callerReady = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var aboutToEnter = new CountDownLatch(1);
    final var entered = new AtomicBoolean();
    try (final var pool = new LazyHelpingExecutor(1, new CountingFactory())) {
      try {
        final var caller =
            pool.submit(
                () -> {
                  // Deliberate monitor probe: B must not reenter the raw host's JVM monitor.
                  synchronized (monitor) {
                    final var result =
                        Blockless.supplier(
                            () -> {
                              finishBody.await();
                              return 42;
                            });
                    callerReady.countDown();
                    return result.get();
                  }
                });
        require(callerReady.await(5, TimeUnit.SECONDS), "Monitor owner not ready");
        final var other =
            pool.submit(
                () -> {
                  aboutToEnter.countDown();
                  synchronized (monitor) {
                    entered.set(true);
                  }
                  return 7;
                });
        require(aboutToEnter.await(5, TimeUnit.SECONDS), "Other task not dispatched");
        try {
          other.get(80, TimeUnit.MILLISECONDS);
          throw new AssertionError("Monitor overlap");
        } catch (TimeoutException expected) {
        }
        require(!entered.get() && !caller.isDone(), "Other task entered raw owner's monitor");
        finishBody.countDown();
        require(
            caller.get(5, TimeUnit.SECONDS) == 42 && other.get(5, TimeUnit.SECONDS) == 7,
            "Monitor tasks lost results");
      } finally {
        finishBody.countDown();
      }
    }
    System.out.println(
        "PASS: helped B cannot enter A's synchronized monitor until A exits it; no nested-carrier"
            + " monitor overlap");
  }

  private static void cancellationAndShutdownRetainTheOriginalWorkersLifetime() throws Exception {
    final var callerReady = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var bodyFinished = new CountDownLatch(1);
    final var cleanupStarted = new CountDownLatch(1);
    final var finishCleanup = new CountDownLatch(1);
    final var otherReady = new CountDownLatch(1);
    final var finishOther = new CountDownLatch(1);
    final var callerInterrupted = new AtomicBoolean();
    final var otherInterrupted = new AtomicBoolean();
    final var cleanups = new AtomicInteger();
    final var factory = new CountingFactory();
    try (final var pool = new LazyHelpingExecutor(1, factory)) {
      try {
        final var caller =
            pool.submit(
                () -> {
                  try {
                    final var value =
                        Blockless.supplier(
                            () -> {
                              try {
                                finishBody.await();
                                return 42;
                              } finally {
                                bodyFinished.countDown();
                              }
                            });
                    callerReady.countDown();
                    return value.get();
                  } finally {
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                    cleanupStarted.countDown();
                    awaitUninterruptibly(finishCleanup);
                    cleanups.incrementAndGet();
                  }
                });
        require(callerReady.await(5, TimeUnit.SECONDS), "Caller not ready");
        final var other =
            pool.submit(
                () -> {
                  otherReady.countDown();
                  finishOther.await();
                  otherInterrupted.set(Thread.currentThread().isInterrupted());
                  return 7;
                });
        require(otherReady.await(5, TimeUnit.SECONDS), "Helped task not running");
        require(caller.cancel(true), "Native cancellation failed");
        require(
            cleanupStarted.await(5, TimeUnit.SECONDS) && callerInterrupted.get(),
            "Native interrupt lost");
        pool.shutdown();
        finishCleanup.countDown();
        require(
            !pool.awaitTermination(80, TimeUnit.MILLISECONDS),
            "Shutdown skipped independent helped task");
        require(!other.isDone(), "Native cancellation interrupted unrelated helped task");
        finishOther.countDown();
        require(
            other.get(5, TimeUnit.SECONDS) == 7 && !otherInterrupted.get(),
            "Helped task poisoned by native cancellation");
        require(pool.awaitTermination(5, TimeUnit.SECONDS), "Owner did not terminate");
        try {
          caller.get();
          throw new AssertionError("Cancelled caller returned normally");
        } catch (CancellationException expected) {
        }
        require(
            cleanups.get() == 1 && factory.created.get() == 1,
            "Cleanup or original-worker lifetime wrong");
        // The body is unstructured as in stable Blockless; global carriers remain usable.
        finishBody.countDown();
        require(
            bodyFinished.await(5, TimeUnit.SECONDS), "Independent body stranded after owner close");
      } finally {
        finishBody.countDown();
        finishCleanup.countDown();
        finishOther.countDown();
      }
    }
    System.out.println(
        "PASS: native cancellation interrupt survives, B stays untouched, original worker joins"
            + " helped lifetime, independent body finishes after owner termination");
  }

  private static void shutdownNowCancelsNativeAndHelpedRootsAfterTheirCleanup() throws Exception {
    final var callerReady = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var otherReady = new CountDownLatch(1);
    final var finishOther = new CountDownLatch(1);
    final var nativeCleanup = new CountDownLatch(1);
    final var helpedCleanup = new CountDownLatch(1);
    final var nativeCleanupEntered = new CountDownLatch(1);
    final var helpedCleanupEntered = new CountDownLatch(1);
    final var nativeInterrupted = new AtomicBoolean();
    final var helpedInterrupted = new AtomicBoolean();
    try (final var pool = new LazyHelpingExecutor(1, new CountingFactory())) {
      try {
        pool.submit(
            () -> {
              try {
                final var value =
                    Blockless.supplier(
                        () -> {
                          finishBody.await();
                          return 42;
                        });
                callerReady.countDown();
                return value.get();
              } finally {
                nativeInterrupted.set(Thread.currentThread().isInterrupted());
                nativeCleanupEntered.countDown();
                awaitUninterruptibly(nativeCleanup);
              }
            });
        require(callerReady.await(5, TimeUnit.SECONDS), "Caller not ready");
        pool.submit(
            () -> {
              try {
                otherReady.countDown();
                finishOther.await();
                return 7;
              } catch (InterruptedException interruption) {
                helpedInterrupted.set(true);
                throw interruption;
              } finally {
                helpedCleanupEntered.countDown();
                awaitUninterruptibly(helpedCleanup);
              }
            });
        require(otherReady.await(5, TimeUnit.SECONDS), "Helped task not running");
        pool.shutdownNow();
        require(
            nativeCleanupEntered.await(5, TimeUnit.SECONDS)
                && helpedCleanupEntered.await(5, TimeUnit.SECONDS),
            "Cleanup not entered");
        require(
            nativeInterrupted.get() && helpedInterrupted.get(),
            "ShutdownNow failed to interrupt both roots");
        require(!pool.awaitTermination(50, TimeUnit.MILLISECONDS), "Termination skipped cleanup");
        nativeCleanup.countDown();
        require(
            !pool.awaitTermination(50, TimeUnit.MILLISECONDS),
            "Termination skipped helped cleanup");
        helpedCleanup.countDown();
        require(pool.awaitTermination(5, TimeUnit.SECONDS), "ShutdownNow did not terminate");
      } finally {
        finishBody.countDown();
        finishOther.countDown();
        nativeCleanup.countDown();
        helpedCleanup.countDown();
      }
    }
    System.out.println(
        "PASS: shutdownNow interrupts native and helped logical roots, and waits for both finally"
            + " blocks");
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    var interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static final class CountingFactory implements ThreadFactory {
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicReference<Thread> first = new AtomicReference<>();

    @Override
    public Thread newThread(Runnable task) {
      final var thread =
          Thread.ofPlatform().name("lazy-native-" + created.incrementAndGet()).unstarted(task);
      first.compareAndSet(null, thread);
      return thread;
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
