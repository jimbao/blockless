import io.github.pjlabs.blockless.AsyncSupplier;
import io.github.pjlabs.blockless.continuations.Continuations;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ClosedCarrierPublicationProbe {
  public static void main(String[] args) throws Exception {
    var bodyEntered = new CountDownLatch(1);
    var finishBody = new AtomicBoolean();
    var cleanups = new AtomicInteger();
    var producerFailure = new AtomicReference<Throwable>();
    var callerResult = new CompletableFuture<AsyncSupplier<String>>();
    var ready = new CompletableFuture<Void>();
    var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> producerFailure.set(failure));
    var carrier = Executors.newSingleThreadExecutor();
    try {
      var builderClass = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
      var constructor = builderClass.getDeclaredConstructor(Executor.class);
      constructor.setAccessible(true);
      var builder = (Thread.Builder.OfVirtual) constructor.newInstance(carrier);
      builder.start(
          () -> {
            try {
              var result =
                  Continuations.supplier(
                      carrier,
                      () -> {
                        try {
                          bodyEntered.countDown();
                          // Stay mounted on the carrier until AFTER that carrier stops accepting
                          // work.
                          while (!finishBody.get()) Thread.onSpinWait();
                          return "hello";
                        } finally {
                          cleanups.incrementAndGet();
                        }
                      });
              callerResult.complete(result);
            } catch (Throwable failure) {
              callerResult.completeExceptionally(failure);
            }
          });
      var result = callerResult.get(5, TimeUnit.SECONDS);
      result.onComplete(() -> ready.complete(null));
      if (!bodyEntered.await(5, TimeUnit.SECONDS))
        throw new AssertionError("Producer never mounted");
      carrier.shutdown();
      finishBody.set(true);
      try {
        ready.get(1, TimeUnit.SECONDS);
      } catch (TimeoutException failure) {
        System.out.println("body/finally complete=" + (cleanups.get() == 1));
        System.out.println("uncaught fallback failure=" + producerFailure.get());
        throw new AssertionError(
            "Body finished but result publication was lost on closed inherited scheduler", failure);
      }
      if (!result.get().equals("hello")) throw new AssertionError("Wrong result");
      if (cleanups.get() != 1) throw new AssertionError("Lost or repeated finally");
      if (producerFailure.get() != null)
        throw new AssertionError("Uncaught producer failure", producerFailure.get());
      System.out.println(
          "PASS closed custom carrier: real outcome published after body/finally once");
    } finally {
      finishBody.set(true);
      if (!carrier.isShutdown()) carrier.shutdown();
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
  }
}
