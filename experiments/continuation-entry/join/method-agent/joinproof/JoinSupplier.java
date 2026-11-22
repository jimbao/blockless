package joinproof;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Minimal proof runtime: outcome readiness precedes producer-thread termination. */
public final class JoinSupplier<T> implements Supplier<T> {
  private Thread producer;
  private volatile boolean ready;
  private T value;
  private Throwable failure;

  public static <T> JoinSupplier<T> supplier(Callable<T> body) {
    return supplier(body, () -> {});
  }

  public static <T> JoinSupplier<T> supplier(Callable<T> body, Runnable afterPublication) {
    var result = new JoinSupplier<T>();
    result.producer =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  try {
                    result.value = body.call();
                  } catch (Throwable failure) {
                    result.failure = failure;
                  }
                  result.ready = true;
                  afterPublication.run();
                });
    result.producer.start();
    return result;
  }

  @Override
  public T get() {
    if (!ready) {
      if (Thread.currentThread() == producer) throw new IllegalStateException("Self dependency");
      try {
        producer.join();
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(failure);
      }
    }
    if (failure != null) JoinSupplier.<RuntimeException>rethrow(failure);
    return value;
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void rethrow(Throwable failure) throws E {
    throw (E) failure;
  }
}
