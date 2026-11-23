package admission;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A queued FutureTask.done exception must not strand later accepted queued Futures. */
public final class FailingCancellationCallbackProbe {
  public static void main(String[] args) throws Exception {
    final var factory =
        args.length > 0 ? args[0] : "helping.LazyHelpingExecutor#newFixedThreadPool";
    final var started = new CountDownLatch(1);
    final var hold = new CountDownLatch(1);
    final var originalFailure = new IllegalStateException("foreign done failed");
    final var secondCleanup = new AtomicInteger();
    final ThreadFactory physical = Thread.ofPlatform().name("throwing-done-native-", 0).factory();
    try (final var owner = SupplierContractProbe.newExecutor(factory, physical)) {
      try {
        owner.submit(
            () -> {
              started.countDown();
              hold.await();
              return null;
            });
        require(started.await(5, TimeUnit.SECONDS), "Native task did not start");
        final var first =
            new FutureTask<Void>(() -> null) {
              @Override
              protected void done() {
                throw originalFailure;
              }
            };
        final var second =
            new FutureTask<Void>(() -> null) {
              @Override
              protected void done() {
                secondCleanup.incrementAndGet();
              }
            };
        owner.execute(first);
        owner.execute(second);
        try {
          owner.shutdownNow();
        } catch (RuntimeException failure) {
          require(failure == originalFailure, "Unexpected shutdown failure");
        }
        require(owner.awaitTermination(5, TimeUnit.SECONDS), "Owner did not terminate");
        final var allSettled =
            first.isCancelled() && second.isCancelled() && secondCleanup.get() == 1;
        // Ensure the negative diagnostic does not itself leave a pending Future around.
        second.cancel(false);
        require(
            allSettled,
            "QUEUED_FUTURE_STRANDED: first FutureTask.done exception skipped cancellation of a"
                + " later accepted Future");
        System.out.println(
            "LIFECYCLE_PASS foreign done exception does not strand later accepted Futures");
      } finally {
        hold.countDown();
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
