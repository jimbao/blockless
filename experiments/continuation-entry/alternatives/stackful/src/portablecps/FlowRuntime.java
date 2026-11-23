package portablecps;

import java.util.function.Supplier;
import org.apache.commons.javaflow.api.Continuation;
import org.apache.commons.javaflow.api.continuable;

/** The owner marks only the carrier slice currently executing a managed flow. */
public final class FlowRuntime {
  public static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private FlowRuntime() {}

  public static boolean managed() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  @continuable
  public static <T> T get(Supplier<T> supplier) {
    if (managed() && supplier instanceof PendingOutcome pending) {
      final var future = pending.outcomeFuture();
      while (!future.isDone()) Continuation.suspend(future);
    }
    return supplier.get();
  }
}
