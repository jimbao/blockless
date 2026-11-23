package publicentry;

import io.github.pjlabs.blockless.Blockless;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Standalone acceptance; all waits on Futures/latches outside the business method are harness. */
public final class PublicEntryProbe {
  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

  public static void main(String[] args) throws Exception {
    if (args.length != 0 && args[0].equals("direct-jdk")) {
      cancellationWaitsForActualFinally(true);
      return;
    }
    immediateGetReturnsActualValueAndOtherRootProgresses();
    originalFailureSurvivesFinally();
    cancellationWaitsForActualFinally(false);
    System.out.println("PASS public entry: stable Blockless API, no JVM flags or private access");
  }

  private static void immediateGetReturnsActualValueAndOtherRootProgresses() throws Exception {
    final var bodyStarted = new CountDownLatch(1);
    final var releaseBody = new CountDownLatch(1);
    final var cleanupStarted = new CountDownLatch(1);
    final var releaseCleanup = new CountDownLatch(1);
    final var bodyEntries = new AtomicInteger();
    final var bodyCleanups = new AtomicInteger();
    final var callerEntries = new AtomicInteger();
    final var callerCleanups = new AtomicInteger();
    final var logicalCaller = new AtomicReference<Thread>();
    final var lock = new ReentrantLock();
    try (final var requests = PublicTaskExecutor.newExecutor()) {
      try {
        final var request =
            requests.submit(
                () -> {
                  final var caller = Thread.currentThread();
                  logicalCaller.set(caller);
                  callerEntries.incrementAndGet();
                  CONTEXT.set("request context");
                  lock.lock();
                  try {
                    final var savedLocal = "local:42";
                    // Business shape is unchanged: stable Blockless supplier and immediate get.
                    final var user =
                        Blockless.supplier(
                            () -> {
                              bodyEntries.incrementAndGet();
                              try {
                                require(
                                    Thread.currentThread().isVirtual(),
                                    "query body is not virtual");
                                bodyStarted.countDown();
                                releaseBody.await();
                                return new User("details:42", "read");
                              } finally {
                                cleanupStarted.countDown();
                                releaseCleanup.await();
                                bodyCleanups.incrementAndGet();
                              }
                            });
                    final var result = user.get();
                    require(caller == Thread.currentThread(), "logical caller identity changed");
                    require(savedLocal.equals("local:42"), "saved local lost");
                    require("request context".equals(CONTEXT.get()), "caller TLS lost");
                    require(lock.isHeldByCurrentThread(), "caller lock ownership lost");
                    return result;
                  } finally {
                    CONTEXT.remove();
                    lock.unlock();
                    callerCleanups.incrementAndGet();
                  }
                });
        require(bodyStarted.await(5, TimeUnit.SECONDS), "query never started");
        final var marker =
            requests.submit(
                () -> {
                  require(Thread.currentThread().isVirtual(), "marker root is not virtual");
                  require(Thread.currentThread() != logicalCaller.get(), "roots share identity");
                  require(CONTEXT.get() == null, "unrelated root inherited caller TLS");
                  final var acquired = lock.tryLock();
                  if (acquired) lock.unlock();
                  require(!acquired, "unrelated root inherited lock ownership");
                  return 7;
                });
        require(marker.get(5, TimeUnit.SECONDS) == 7, "other task did not progress");
        require(logicalCaller.get().isVirtual(), "entry did not create a virtual caller");
        require(!request.isDone(), "held get returned early");
        require(callerCleanups.get() == 0 && bodyCleanups.get() == 0, "cleanup ran at suspension");
        requests.shutdown();
        require(
            !requests.awaitTermination(30, TimeUnit.MILLISECONDS), "owner dropped pending root");
        releaseBody.countDown();
        require(cleanupStarted.await(5, TimeUnit.SECONDS), "query cleanup never started");
        require(!request.isDone(), "get returned before query finally completed");
        releaseCleanup.countDown();
        require(
            new User("details:42", "read").equals(request.get(5, TimeUnit.SECONDS)), "wrong value");
        require(requests.awaitTermination(5, TimeUnit.SECONDS), "owner failed to terminate");
        require(bodyEntries.get() == 1 && callerEntries.get() == 1, "body/caller replayed");
        require(bodyCleanups.get() == 1 && callerCleanups.get() == 1, "finally skipped/repeated");
        final var bean = ManagementFactory.getThreadMXBean();
        final var workers =
            Arrays.stream(bean.getAllThreadIds())
                .mapToObj(bean::getThreadInfo)
                .filter(info -> info != null && info.getThreadName().startsWith("ForkJoinPool-"))
                .map(info -> info.getThreadName())
                .sorted()
                .toList();
        System.out.println("Observed physical pool workers after proof: " + workers);
      } finally {
        releaseBody.countDown();
        releaseCleanup.countDown();
      }
    }
    System.out.println(
        "PASS immediate get: real User, independent root progress, logical VT identity,"
            + " locals/TLS/locks and finally once");
  }

  private static void originalFailureSurvivesFinally() throws Exception {
    final var original = new IllegalArgumentException("query failed");
    final var bodyFinally = new AtomicInteger();
    final var callerFinally = new AtomicInteger();
    try (final var requests = PublicTaskExecutor.newExecutor()) {
      final var failed =
          requests.submit(
              () -> {
                try {
                  final var user =
                      Blockless.supplier(
                          () -> {
                            try {
                              throw original;
                            } finally {
                              bodyFinally.incrementAndGet();
                            }
                          });
                  return user.get();
                } finally {
                  callerFinally.incrementAndGet();
                }
              });
      try {
        failed.get(5, TimeUnit.SECONDS);
        throw new AssertionError("business failure disappeared");
      } catch (ExecutionException expected) {
        require(expected.getCause() == original, "original failure identity changed");
      }
      require(bodyFinally.get() == 1 && callerFinally.get() == 1, "failure skipped cleanup");
    }
    System.out.println("PASS original failure and finally semantics");
  }

  private static void cancellationWaitsForActualFinally(boolean directJdk) throws Exception {
    final var bodyStarted = new CountDownLatch(1);
    final var releaseBody = new CountDownLatch(1);
    final var cleanupStarted = new CountDownLatch(1);
    final var releaseCleanup = new CountDownLatch(1);
    final var otherStarted = new CountDownLatch(1);
    final var releaseOther = new CountDownLatch(1);
    final var otherInterrupted = new AtomicBoolean();
    final var callerCleanup = new AtomicInteger();
    final var bodyCleanup = new AtomicInteger();
    final var requests =
        directJdk ? Executors.newVirtualThreadPerTaskExecutor() : PublicTaskExecutor.newExecutor();
    try (requests) {
      try {
        final var request =
            requests.submit(
                () -> {
                  try {
                    final var user =
                        Blockless.supplier(
                            () -> {
                              try {
                                bodyStarted.countDown();
                                releaseBody.await();
                                return "done";
                              } finally {
                                bodyCleanup.incrementAndGet();
                              }
                            });
                    return user.get();
                  } finally {
                    // get restores the interrupt flag; cleanup deliberately consumes it before
                    // awaiting.
                    require(
                        Thread.interrupted(),
                        "cancel did not interrupt the intended logical caller");
                    cleanupStarted.countDown();
                    releaseCleanup.await();
                    callerCleanup.incrementAndGet();
                  }
                });
        final var other =
            requests.submit(
                () -> {
                  otherStarted.countDown();
                  try {
                    releaseOther.await();
                  } catch (InterruptedException failure) {
                    otherInterrupted.set(true);
                    throw failure;
                  }
                  return 1;
                });
        require(
            bodyStarted.await(5, TimeUnit.SECONDS) && otherStarted.await(5, TimeUnit.SECONDS),
            "roots did not start");
        require(request.cancel(true), "root cancellation failed");
        require(cleanupStarted.await(5, TimeUnit.SECONDS), "cancel skipped caller finally");
        requests
            .submit(
                () ->
                    require(!Thread.currentThread().isInterrupted(), "cancel leaked interruption"))
            .get(5, TimeUnit.SECONDS);
        require(!other.isDone() && !otherInterrupted.get(), "cancel reached unrelated root");
        releaseOther.countDown();
        other.get(5, TimeUnit.SECONDS);
        releaseBody.countDown();
        // Supplier bodies are independent: this harness releases and observes its body separately.
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bodyCleanup.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
        require(bodyCleanup.get() == 1, "independent query body failed to finish");
        requests.shutdown();
        require(
            !requests.awaitTermination(50, TimeUnit.MILLISECONDS),
            "owner terminated before cancelled caller finally finished");
        try {
          request.get();
          throw new AssertionError("cancelled Future returned a value");
        } catch (CancellationException expected) {
        }
        releaseCleanup.countDown();
        require(
            requests.awaitTermination(5, TimeUnit.SECONDS), "owner failed to join actual cleanup");
        require(callerCleanup.get() == 1, "caller cleanup skipped/repeated");
      } finally {
        releaseBody.countDown();
        releaseOther.countDown();
        releaseCleanup.countDown();
      }
    }
    System.out.println(
        "PASS cancellation isolation and actual cleanup termination: "
            + (directJdk ? "direct JDK executor" : "public task owner"));
  }

  private record User(String details, String permissions) {}

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
