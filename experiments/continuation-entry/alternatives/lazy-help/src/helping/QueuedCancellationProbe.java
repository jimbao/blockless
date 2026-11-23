package helping;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks that cancellation invokes foreign FutureTask hooks outside executor state locks. */
public final class QueuedCancellationProbe {
  public static void main(String[] args) throws Exception {
    final var expectBlocked = args.length != 0 && args[0].equals("expect-blocked");
    final var occupied = new CountDownLatch(1);
    final var finish = new CountDownLatch(1);
    final var inspected = new CountDownLatch(1);
    final var blocked = new AtomicBoolean();
    try (final var owner = new LazyHelpingExecutor(1, Thread.ofPlatform().factory())) {
      try {
        owner.submit(
            () -> {
              occupied.countDown();
              finish.await();
              return null;
            });
        if (!occupied.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker not occupied");
        final var queued =
            new FutureTask<Void>(() -> null) {
              @Override
              protected void done() {
                Thread.startVirtualThread(
                    () -> {
                      owner.isShutdown();
                      inspected.countDown();
                    });
                try {
                  blocked.set(!inspected.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException interruption) {
                  throw new AssertionError(interruption);
                }
              }
            };
        owner.execute(queued);
        owner.shutdownNow();
        if (!queued.isCancelled()) throw new AssertionError("Queued future was not cancelled");
        if (!owner.awaitTermination(5, TimeUnit.SECONDS))
          throw new AssertionError("Owner not terminated");
        if (!inspected.await(5, TimeUnit.SECONDS))
          throw new AssertionError("Observer never completed");
        if (blocked.get() != expectBlocked)
          throw new AssertionError("done hook blocked=" + blocked.get());
      } finally {
        finish.countDown();
      }
    }
    System.out.println(
        expectBlocked
            ? "REPRODUCED: queued FutureTask.done callback could not inspect owner from another"
                + " thread while shutdownNow held owner lock"
            : "PASS: queued FutureTask.done callback inspects owner from another thread during"
                + " shutdownNow; no owner lock held");
  }
}
