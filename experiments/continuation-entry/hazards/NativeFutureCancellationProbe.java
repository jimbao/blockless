import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class NativeFutureCancellationProbe {
  public static void main(String[] args) throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var finishBody = new CountDownLatch(1);
    var unrelatedRunning = new CountDownLatch(1);
    var finishUnrelated = new CountDownLatch(1);
    var unrelatedInterrupted = new AtomicBoolean();
    try (var carrier = Executors.newSingleThreadExecutor()) {
      var original =
          new FutureTask<String>(
              () -> {
                var user =
                    new ManagedContinuationProbe.Value<>(
                        () -> {
                          bodyStarted.countDown();
                          finishBody.await();
                          return "hello";
                        });
                return user.get();
              });
      var flow = new ManagedContinuationProbe.Flow(carrier, original);
      flow.start();
      if (!bodyStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Body did not start");
      var unrelated =
          carrier.submit(
              () -> {
                unrelatedRunning.countDown();
                try {
                  finishUnrelated.await();
                } catch (InterruptedException expected) {
                  unrelatedInterrupted.set(true);
                }
              });
      if (!unrelatedRunning.await(5, TimeUnit.SECONDS))
        throw new AssertionError("Carrier not free");
      if (!original.cancel(true)) throw new AssertionError("Cancellation failed");
      unrelated.get(5, TimeUnit.SECONDS);
      if (!unrelatedInterrupted.get()) throw new AssertionError("Expected carrier interruption");
      System.out.println("Cancelling suspended FutureTask interrupted unrelated carrier task=true");
      finishBody.countDown();
      flow.finished.get(5, TimeUnit.SECONDS);
    } finally {
      finishBody.countDown();
      finishUnrelated.countDown();
    }
  }
}
