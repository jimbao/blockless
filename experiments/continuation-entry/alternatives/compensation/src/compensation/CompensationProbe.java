package compensation;

import io.github.pjlabs.blockless.Blockless;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Synthetic I/O comparison; these measurements are not a real gRPC/PostgreSQL benchmark. */
public final class CompensationProbe {
  public static void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("load")) {
      final var modes =
          args.length > 1
              ? new String[] {args[1]}
              : new String[] {"fixed4", "forkjoin4", "compensated10", "fixed10"};
      for (final var mode : modes) {
        measure(mode);
      }
      return;
    }
    final var wholeTask = args.length == 0 || !args[0].equals("selective");
    gatedProgressUsesAReplacementWorker(wholeTask);
    cancellationWaitsForRealFinally(wholeTask);
    reusedWorkerDoesNotKeepCancellationInterrupt();
    if (!wholeTask) compensationLimitDoesNotTurnPendingIntoFailure();
  }

  private static void gatedProgressUsesAReplacementWorker(boolean wholeTask) throws Exception {
    final var stats = new WorkerStats("gated");
    final var pool = CompensatingTaskExecutor.newPool(1, 4, stats::forkJoinWorker);
    final var started = new CountDownLatch(1);
    final var finish = new CountDownLatch(1);
    final var original = new AtomicReference<Thread>();
    final var bodyCleanup = new AtomicInteger();
    final var callerCleanup = new AtomicInteger();
    final var local = new ThreadLocal<String>();
    final ExecutorService executor = new CompensatingTaskExecutor(pool, wholeTask);
    try (executor) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  original.set(Thread.currentThread());
                  require(!Thread.currentThread().isVirtual(), "caller must remain platform");
                  local.set("request");
                  try {
                    final var value =
                        Blockless.supplier(
                            () -> {
                              require(
                                  Thread.currentThread().isVirtual(), "I/O body must be virtual");
                              started.countDown();
                              try {
                                finish.await();
                                return "hello";
                              } finally {
                                bodyCleanup.incrementAndGet();
                              }
                            });
                    final var actual = value.get();
                    require(Thread.currentThread() == original.get(), "caller identity changed");
                    require("request".equals(local.get()), "caller ThreadLocal lost");
                    return actual;
                  } finally {
                    local.remove();
                    callerCleanup.incrementAndGet();
                  }
                });
        require(started.await(5, TimeUnit.SECONDS), "body did not start");
        final var marker = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        require(!marker.isVirtual(), "marker is not a platform worker");
        require(marker != original.get(), "marker must use a replacement worker");
        require(stats.created.get() >= 2, "compensation did not create another worker");
        require(!caller.isDone(), "caller returned before I/O finished");
        System.out.printf(
            "GATED: original=%s state=%s; marker=%s; created_request_workers=%d;"
                + " original_released=false%n",
            original.get().getName(),
            original.get().getState(),
            marker.getName(),
            stats.created.get());
        finish.countDown();
        require("hello".equals(caller.get(5, TimeUnit.SECONDS)), "wrong actual result");
        require(bodyCleanup.get() == 1 && callerCleanup.get() == 1, "finally skipped or repeated");
      } finally {
        finish.countDown();
      }
    }
  }

  private static void cancellationWaitsForRealFinally(boolean wholeTask) throws Exception {
    final var stats = new WorkerStats("cancel");
    final var pool = CompensatingTaskExecutor.newPool(1, 4, stats::forkJoinWorker);
    final var bodyStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var cleanupStarted = new CountDownLatch(1);
    final var finishCleanup = new CountDownLatch(1);
    final var cleanupCount = new AtomicInteger();
    final var failure = new AtomicReference<Throwable>();
    final ExecutorService executor = new CompensatingTaskExecutor(pool, wholeTask);
    try (executor) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  try {
                    final var user =
                        Blockless.supplier(
                            () -> {
                              bodyStarted.countDown();
                              finishBody.await();
                              return "value";
                            });
                    return user.get();
                  } catch (RuntimeException interrupted) {
                    failure.set(interrupted);
                    require(
                        Thread.currentThread().isInterrupted(), "caller interrupt flag missing");
                    throw interrupted;
                  } finally {
                    cleanupStarted.countDown();
                    awaitIgnoringInterrupt(finishCleanup);
                    cleanupCount.incrementAndGet();
                  }
                });
        require(bodyStarted.await(5, TimeUnit.SECONDS), "body did not start");
        require(caller.cancel(true), "cancel failed");
        require(cleanupStarted.await(5, TimeUnit.SECONDS), "caller finally did not run");
        require(failure.get() != null, "interrupted get did not report interruption");
        try {
          caller.get();
          throw new AssertionError("cancelled Future returned");
        } catch (CancellationException expected) {
        }
        final var markerInterrupted = executor.submit(() -> Thread.currentThread().isInterrupted());
        if (wholeTask) {
          require(!markerInterrupted.get(5, TimeUnit.SECONDS), "cancel leaked to unrelated worker");
        } else {
          try {
            markerInterrupted.get(30, TimeUnit.MILLISECONDS);
            throw new AssertionError("Expected separately blocking finally to remain unmanaged");
          } catch (TimeoutException expectedLimit) {
            System.out.println(
                "SELECTIVE LIMIT: get-only compensation ends before separately blocking caller"
                    + " finally; queued marker cannot progress until cleanup releases");
          }
        }
        executor.shutdown();
        require(
            !executor.awaitTermination(30, TimeUnit.MILLISECONDS),
            "executor terminated before actual caller finally finished");
        finishCleanup.countDown();
        finishBody.countDown();
        require(!markerInterrupted.get(5, TimeUnit.SECONDS), "cancel flag reached later task");
        require(executor.awaitTermination(5, TimeUnit.SECONDS), "executor did not terminate");
        require(cleanupCount.get() == 1, "caller finally was skipped");
      } finally {
        finishBody.countDown();
        finishCleanup.countDown();
      }
    }
    System.out.println(
        "CANCEL: original platform caller interrupted; its finally completes once; unrelated worker"
            + " clean; termination waits cleanup");
  }

  private static void reusedWorkerDoesNotKeepCancellationInterrupt() throws Exception {
    final var stats = new WorkerStats("reuse");
    final var pool = CompensatingTaskExecutor.newPool(1, 1, stats::forkJoinWorker);
    final var bodyStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var cleanupStarted = new CountDownLatch(1);
    final var finishCleanup = new CountDownLatch(1);
    final var original = new AtomicReference<Thread>();
    try (final var executor = new CompensatingTaskExecutor(pool)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  original.set(Thread.currentThread());
                  try {
                    final var result =
                        Blockless.supplier(
                            () -> {
                              bodyStarted.countDown();
                              finishBody.await();
                              return 42;
                            });
                    return result.get();
                  } finally {
                    cleanupStarted.countDown();
                    awaitIgnoringInterrupt(finishCleanup);
                  }
                });
        require(bodyStarted.await(5, TimeUnit.SECONDS), "reuse body did not start");
        require(caller.cancel(true), "reuse cancellation failed");
        require(cleanupStarted.await(5, TimeUnit.SECONDS), "reuse cleanup did not start");
        final var marker =
            executor.submit(
                () -> {
                  require(Thread.currentThread() == original.get(), "worker was replaced");
                  return Thread.currentThread().isInterrupted();
                });
        finishCleanup.countDown();
        finishBody.countDown();
        require(!marker.get(5, TimeUnit.SECONDS), "cancel flag leaked to next task on same worker");
        require(stats.created.get() == 1, "reuse test created a replacement worker");
      } finally {
        finishBody.countDown();
        finishCleanup.countDown();
      }
    }
    System.out.println(
        "REUSE: cancelled caller's exact platform worker runs the next task with a clean interrupt"
            + " flag");
  }

  private static void compensationLimitDoesNotTurnPendingIntoFailure() throws Exception {
    final var stats = new WorkerStats("limit");
    final var pool =
        new ForkJoinPool(1, stats::forkJoinWorker, null, true, 1, 1, 1, null, 60, TimeUnit.SECONDS);
    final var bodyStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    try (final var executor = new CompensatingTaskExecutor(pool, false)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  final var result =
                      Blockless.supplier(
                          () -> {
                            bodyStarted.countDown();
                            finishBody.await();
                            return 42;
                          });
                  return result.get();
                });
        require(bodyStarted.await(5, TimeUnit.SECONDS), "limit body did not start");
        try {
          caller.get(30, TimeUnit.MILLISECONDS);
          throw new AssertionError("pending result returned before release");
        } catch (TimeoutException expectedPending) {
        }
        finishBody.countDown();
        require(caller.get(5, TimeUnit.SECONDS) == 42, "compensation limit replaced real value");
        require(stats.created.get() == 1, "compensation exceeded its worker limit");
      } finally {
        finishBody.countDown();
      }
    }
    System.out.println(
        "LIMIT: rejecting an extra compensating worker falls back to ordinary waiting, never a"
            + " pending-result exception");
  }

  private static void measure(String mode) throws Exception {
    final var stats = new WorkerStats(mode);
    final var backend = new Semaphore(10);
    final var calls = new AtomicLong();
    final var failures = new AtomicInteger();
    final var callerThreads = ConcurrentHashMap.<Thread>newKeySet();
    final var bodies = new AtomicInteger();
    final var peakBodies = new AtomicInteger();
    final ExecutorService executor =
        switch (mode) {
          case "fixed4" -> Executors.newFixedThreadPool(4, stats);
          case "fixed10" -> Executors.newFixedThreadPool(10, stats);
          case "forkjoin4" ->
              new CompensatingTaskExecutor(
                  CompensatingTaskExecutor.newPool(4, 10, stats::forkJoinWorker), false);
          case "compensated10" ->
              new CompensatingTaskExecutor(
                  CompensatingTaskExecutor.newPool(4, 10, stats::forkJoinWorker));
          default -> throw new IllegalArgumentException(mode);
        };
    final var start = System.nanoTime();
    final var warmupEnd = start + TimeUnit.SECONDS.toNanos(1);
    final var measuredEnd = warmupEnd + TimeUnit.SECONDS.toNanos(3);
    try (executor;
        final var generators = Executors.newVirtualThreadPerTaskExecutor()) {
      final var clients = new ArrayList<Future<?>>();
      for (var index = 0; index < 32; index++) {
        clients.add(
            generators.submit(
                () -> {
                  while (System.nanoTime() < measuredEnd) {
                    try {
                      final var value =
                          executor
                              .submit(
                                  () -> {
                                    final var caller = Thread.currentThread();
                                    require(!caller.isVirtual(), "load caller became virtual");
                                    callerThreads.add(caller);
                                    final var user =
                                        Blockless.supplier(
                                            () -> {
                                              backend.acquire();
                                              final var active = bodies.incrementAndGet();
                                              peakBodies.accumulateAndGet(active, Math::max);
                                              try {
                                                Thread.sleep(10);
                                                return 42;
                                              } finally {
                                                bodies.decrementAndGet();
                                                backend.release();
                                              }
                                            });
                                    final var result = user.get();
                                    require(
                                        Thread.currentThread() == caller, "load caller migrated");
                                    return result;
                                  })
                              .get(5, TimeUnit.SECONDS);
                      require(value == 42, "load wrong value");
                      final var now = System.nanoTime();
                      if (now >= warmupEnd && now < measuredEnd) calls.incrementAndGet();
                    } catch (Throwable failure) {
                      failures.incrementAndGet();
                      throw new RuntimeException(failure);
                    }
                  }
                }));
      }
      for (final var client : clients) client.get(15, TimeUnit.SECONDS);
      require(failures.get() == 0, "load had failures");
      final Set<String> sharedWorkers = ConcurrentHashMap.newKeySet();
      for (final var thread : Thread.getAllStackTraces().keySet()) {
        if (thread.getName().startsWith("ForkJoinPool") && !thread.getName().contains("request")) {
          sharedWorkers.add(thread.getName());
        }
      }
      System.out.printf(
          "LOAD mode=%s rps=%.2f measured_count=%d errors=%d created_request_workers=%d"
              + " peak_live_request_workers=%d distinct_callers=%d peak_io_bodies=%d"
              + " shared_worker_snapshot=%s%n",
          mode,
          calls.get() / 3.0,
          calls.get(),
          failures.get(),
          stats.created.get(),
          stats.peak.get(),
          callerThreads.size(),
          peakBodies.get(),
          sharedWorkers);
    }
  }

  private static void awaitIgnoringInterrupt(CountDownLatch gate) {
    var interrupted = Thread.interrupted();
    while (true) {
      try {
        gate.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class WorkerStats implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger created = new AtomicInteger();
    private final AtomicInteger live = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    private WorkerStats(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
      final var index = created.incrementAndGet();
      return new Thread(
          () -> {
            started();
            try {
              runnable.run();
            } finally {
              live.decrementAndGet();
            }
          },
          prefix + "-request-" + index);
    }

    private ForkJoinWorkerThread forkJoinWorker(ForkJoinPool pool) {
      final var index = created.incrementAndGet();
      return new ForkJoinWorkerThread(pool) {
        {
          setName(prefix + "-request-" + index);
        }

        @Override
        protected void onStart() {
          started();
        }

        @Override
        protected void onTermination(Throwable failure) {
          live.decrementAndGet();
        }
      };
    }

    private void started() {
      peak.accumulateAndGet(live.incrementAndGet(), Math::max);
    }
  }
}
