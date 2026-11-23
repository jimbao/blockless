package admission;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises foreign FutureTask cancellation and both orders of actual root cleanup. */
public final class ForeignTaskCancellationProbe {
  public static void main(String[] args) throws Exception {
    final var factory = args.length > 0 ? args[0] : "virtual";
    final var callerVirtual = args.length < 2 || args[1].equals("virtual");
    check(factory, callerVirtual, true);
    check(factory, callerVirtual, false);
  }

  private static void check(String factory, boolean callerVirtual, boolean nativeCleanupFirst)
      throws Exception {
    final var atGet = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var bodyFinished = new CountDownLatch(1);
    final var callerCleanupEntered = new CountDownLatch(1);
    final var finishCallerCleanup = new CountDownLatch(1);
    final var callerCleanups = new AtomicInteger();
    final var callerInterrupted = new AtomicBoolean();
    final var foreignStarted = new CountDownLatch(1);
    final var finishForeign = new CountDownLatch(1);
    final var foreignCleanupEntered = new CountDownLatch(1);
    final var finishForeignCleanup = new CountDownLatch(1);
    final var foreignCleanups = new AtomicInteger();
    final var foreignInterrupted = new AtomicBoolean();
    final var physicalCreated = new AtomicInteger();
    final ThreadFactory physical =
        task ->
            Thread.ofPlatform()
                .name("foreign-physical-" + physicalCreated.incrementAndGet())
                .unstarted(task);
    try (final var owner = SupplierContractProbe.newExecutor(factory, physical)) {
      try {
        final var caller =
            owner.submit(
                () -> {
                  require(Thread.currentThread().isVirtual() == callerVirtual, "Wrong caller type");
                  try {
                    final var user =
                        Blockless.supplier(
                            () -> {
                              try {
                                finishBody.await();
                                return 42;
                              } finally {
                                bodyFinished.countDown();
                              }
                            });
                    atGet.countDown();
                    return user.get();
                  } finally {
                    callerInterrupted.set(Thread.currentThread().isInterrupted());
                    callerCleanupEntered.countDown();
                    awaitUninterruptibly(finishCallerCleanup);
                    callerCleanups.incrementAndGet();
                  }
                });
        require(atGet.await(5, TimeUnit.SECONDS), "Caller never reached get");
        final var foreign =
            new FutureTask<Integer>(
                () -> {
                  require(
                      Thread.currentThread().isVirtual(), "Foreign helped root must be virtual");
                  try {
                    foreignStarted.countDown();
                    finishForeign.await();
                    return 7;
                  } catch (InterruptedException interruption) {
                    foreignInterrupted.set(true);
                    throw interruption;
                  } finally {
                    foreignCleanupEntered.countDown();
                    awaitUninterruptibly(finishForeignCleanup);
                    foreignCleanups.incrementAndGet();
                  }
                });
        // The executor sees an ordinary Runnable, not the cancellable FutureTask inside it.
        owner.execute(() -> foreign.run());
        require(foreignStarted.await(5, TimeUnit.SECONDS), "Foreign task did not progress");
        require(caller.cancel(true), "Caller cancellation failed");
        require(callerCleanupEntered.await(5, TimeUnit.SECONDS), "Caller cancellation lost");
        require(callerInterrupted.get(), "Caller interrupt flag was lost");
        require(
            !foreign.isDone() && !foreignInterrupted.get(), "Caller cancel reached foreign root");
        owner.shutdown();
        require(foreign.cancel(true), "Foreign cancellation failed");
        require(foreignCleanupEntered.await(5, TimeUnit.SECONDS), "Foreign finally did not start");
        require(foreignInterrupted.get(), "Foreign task did not receive its own interruption");
        require(
            !owner.awaitTermination(30, TimeUnit.MILLISECONDS),
            "Owner skipped both finally blocks");
        if (nativeCleanupFirst) {
          finishCallerCleanup.countDown();
        } else {
          finishForeignCleanup.countDown();
        }
        require(
            !owner.awaitTermination(80, TimeUnit.MILLISECONDS),
            "Owner terminated before the remaining actual finally finished");
        finishCallerCleanup.countDown();
        finishForeignCleanup.countDown();
        require(owner.awaitTermination(5, TimeUnit.SECONDS), "Owner failed to join actual cleanup");
        require(
            callerCleanups.get() == 1 && foreignCleanups.get() == 1, "Cleanup skipped or replayed");
        require(physicalCreated.get() <= 1, "Replacement platform request worker created");
        // Stable Blockless bodies are unstructured. Prove this body can finish after owner exit.
        finishBody.countDown();
        require(bodyFinished.await(5, TimeUnit.SECONDS), "Independent producer stranded");
        System.out.println(
            "LIFECYCLE_PASS caller_virtual="
                + callerVirtual
                + "; foreign_virtual=true; caller_cleanup_first="
                + nativeCleanupFirst
                + "; tracked_platform_created="
                + physicalCreated.get()
                + "; factory="
                + factory);
      } finally {
        finishBody.countDown();
        finishForeign.countDown();
        finishCallerCleanup.countDown();
        finishForeignCleanup.countDown();
      }
    }
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

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
