import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Diagnostic: callback A depends on progress after the source await returns. */
public class JoinProgressCheck {
  static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  static void check(boolean blockless) throws Exception {
    final var source = new CompletableFuture<String>();
    final var callerAdvanced = new CountDownLatch(1);
    final var callbackEntered = new CountDownLatch(1);
    final var callbackThread = new CompletableFuture<String>();
    final var producerInB = new CountDownLatch(1);
    final var releaseProducer = new CountDownLatch(1);
    final var carrierHeld = new CountDownLatch(1);
    final var releaseCarrier = new CountDownLatch(1);
    final var lock = new Object();
    final var a =
        source.thenRun(
            () -> {
              callbackThread.complete(Thread.currentThread().getName());
              callbackEntered.countDown();
              await(callerAdvanced);
            });
    final var b =
        source.thenRun(
            () -> {
              producerInB.countDown();
              await(releaseProducer);
            });
    final Supplier<String> supplier = blockless ? Blockless.supplier(source) : source::join;
    final var result =
        new FutureTask<String>(
            () -> {
              final var value = supplier.get();
              callerAdvanced.countDown();
              return value;
            });
    final var caller = Thread.ofVirtual().name("request-caller").start(result);
    Thread control = null;
    Thread producer = null;
    try {
      final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (caller.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
        LockSupport.parkNanos(100_000);
      require(caller.getState() == Thread.State.WAITING, "Request caller did not wait");
      // Temporarily pin the only carrier so the producer reaches B before the waiter resumes.
      control =
          Thread.ofVirtual()
              .start(
                  () -> {
                    synchronized (lock) {
                      carrierHeld.countDown();
                      await(releaseCarrier);
                    }
                  });
      require(carrierHeld.await(2, TimeUnit.SECONDS), "Scheduling gate failed");
      producer =
          Thread.ofPlatform()
              .daemon()
              .name("client-completer")
              .start(() -> source.complete("done"));
      require(producerInB.await(2, TimeUnit.SECONDS), "Producer did not reach callback B");
      releaseCarrier.countDown();
      if (blockless) {
        require("done".equals(result.get(2, TimeUnit.SECONDS)), "Wrong value");
        require(callerAdvanced.getCount() == 0, "Caller did not advance");
        require(!callbackThread.isDone(), "Blockless waiter ran callback A");
        System.out.println(
            "Blockless: caller advanced immediately after await; no self-dependency");
      } else {
        require(callbackEntered.await(2, TimeUnit.SECONDS), "Callback A did not run");
        require("request-caller".equals(callbackThread.get()), "A did not run on joining caller");
        require(source.isDone(), "Source result is not ready");
        try {
          result.get(100, TimeUnit.MILLISECONDS);
          throw new AssertionError("Caller unexpectedly advanced");
        } catch (TimeoutException expected) {
          require(callerAdvanced.getCount() == 1, "Caller advanced despite callback dependency");
          System.out.println(
              "join: source is complete, but caller is stuck in callback A waiting for its own"
                  + " post-join code");
        }
      }
    } finally {
      releaseCarrier.countDown();
      callerAdvanced.countDown(); // Watchdog rescue for the intentional join dependency.
      releaseProducer.countDown();
      caller.join(2000);
      if (control != null) control.join(2000);
      if (producer != null) producer.join(2000);
    }
    require("done".equals(result.get(2, TimeUnit.SECONDS)), "Cleanup failed");
    a.get(2, TimeUnit.SECONDS);
    b.get(2, TimeUnit.SECONDS);
  }

  public static void main(String[] args) throws Exception {
    for (int i = 0; i < 3; i++) {
      check(false);
      check(true);
    }
  }
}
