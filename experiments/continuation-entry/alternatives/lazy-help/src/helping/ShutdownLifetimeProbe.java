package helping;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Main returns before a helped root; shutdown must retain an original non-daemon worker. */
public final class ShutdownLifetimeProbe {
  public static void main(String[] args) throws Exception {
    final var entered = new CountDownLatch(1);
    final var otherStarted = new CountDownLatch(1);
    final var finishBody = new CountDownLatch(1);
    final var workers = new AtomicInteger();
    final var owner =
        new LazyHelpingExecutor(
            1,
            task -> {
              workers.incrementAndGet();
              return Thread.ofPlatform().name("original-lifetime-worker").unstarted(task);
            });
    final var caller =
        owner.submit(
            () -> {
              final var result =
                  Blockless.supplier(
                      () -> {
                        finishBody.await();
                        return 42;
                      });
              entered.countDown();
              return result.get();
            });
    if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("Native caller not pending");
    owner.submit(
        () -> {
          if (!Thread.currentThread().isVirtual())
            throw new AssertionError("Other root must be helped");
          otherStarted.countDown();
          if (System.in.read() != 'x') throw new AssertionError("No controller release");
          System.out.println("HELPED_FINISHED");
          return null;
        });
    if (!otherStarted.await(5, TimeUnit.SECONDS))
      throw new AssertionError("Other root not dispatched");
    owner.shutdown();
    finishBody.countDown();
    if (caller.get(5, TimeUnit.SECONDS) != 42 || workers.get() != 1)
      throw new AssertionError("Native result/worker count wrong");
    System.out.println("MAIN_RETURN");
  }
}
