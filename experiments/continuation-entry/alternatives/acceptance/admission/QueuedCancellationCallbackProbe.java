package admission;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded regression for lazy-help's synchronous queued-Future cancellation callbacks. */
public final class QueuedCancellationCallbackProbe {
  public static void main(String[] args) throws Exception {
    final var factory =
        args.length > 0 ? args[0] : "helping.LazyHelpingExecutor#newFixedThreadPool";
    final var nativeStarted = new CountDownLatch(1);
    final var holdNative = new CountDownLatch(1);
    final var nativeCleanupObserved = new CountDownLatch(1);
    final var callbackRan = new AtomicBoolean();
    final var callbackObservedCleanup = new AtomicBoolean();
    final var forbiddenBodyRan = new AtomicBoolean();
    final ThreadFactory physical = Thread.ofPlatform().name("queued-cancel-native-", 0).factory();
    try (final var owner = SupplierContractProbe.newExecutor(factory, physical)) {
      try {
        owner.submit(
            () -> {
              try {
                nativeStarted.countDown();
                // Ordinary latch wait deliberately keeps the next task queued, not helped.
                holdNative.await();
              } finally {
                if (owner.isShutdown()) nativeCleanupObserved.countDown();
              }
              return null;
            });
        require(nativeStarted.await(5, TimeUnit.SECONDS), "Native task did not start");
        final var queued =
            new FutureTask<Void>(
                () -> {
                  forbiddenBodyRan.set(true);
                  return null;
                }) {
              @Override
              protected void done() {
                callbackRan.set(true);
                try {
                  callbackObservedCleanup.set(
                      nativeCleanupObserved.await(500, TimeUnit.MILLISECONDS));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
            };
        owner.execute(queued);
        owner.shutdownNow();
        require(owner.awaitTermination(5, TimeUnit.SECONDS), "Owner did not terminate");
        require(queued.isCancelled() && callbackRan.get(), "Queued task was not cancelled");
        require(!forbiddenBodyRan.get(), "Cancelled queued task ran");
        require(
            callbackObservedCleanup.get(),
            "QUEUED_CALLBACK_STALLED: cancellation callback could not observe interrupted native"
                + " cleanup calling owner.isShutdown");
        System.out.println(
            "LIFECYCLE_PASS queued FutureTask.done observed native interruption/cleanup without"
                + " owner-lock deadlock");
      } finally {
        holdNative.countDown();
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
