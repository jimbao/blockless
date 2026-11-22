import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/** Research only. Requires --add-opens=java.base/java.lang=ALL-UNNAMED. */
public final class BorrowedCarrierThreads {
  private static final MethodHandle CURRENT_CARRIER;

  static {
    try {
      CURRENT_CARRIER =
          MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
              .findStatic(
                  Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  public static ExecutorService newLogicalExecutor(Executor carriers)
      throws ReflectiveOperationException {
    var type = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
    var constructor = type.getDeclaredConstructor(Executor.class);
    constructor.setAccessible(true);
    var builder = (Thread.Builder.OfVirtual) constructor.newInstance(carriers);
    return LogicalTaskExecutor.using(
        builder.name("logical-root-", 0).inheritInheritableThreadLocals(false).factory());
  }

  public static Thread currentCarrier() {
    try {
      return (Thread) CURRENT_CARRIER.invokeExact();
    } catch (Throwable failure) {
      throw new AssertionError(failure);
    }
  }

  private BorrowedCarrierThreads() {}
}
