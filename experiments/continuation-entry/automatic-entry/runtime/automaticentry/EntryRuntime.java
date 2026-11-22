package automaticentry;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Research factory targets for startup instrumentation. Pool sizes bound physical carriers; logical
 * virtual-thread tasks are unbounded. Requires java.lang opened to this module.
 */
public final class EntryRuntime {
  private static volatile int cachedCarriers = 4;

  private EntryRuntime() {}

  public static void configureCachedCarriers(int count) {
    if (count <= 0) throw new IllegalArgumentException("cachedCarriers must be positive");
    cachedCarriers = count;
  }

  public static ExecutorService newFixedThreadPool(int count) {
    return newFixedThreadPool(count, Executors.defaultThreadFactory());
  }

  public static ExecutorService newFixedThreadPool(int count, ThreadFactory factory) {
    return own(Executors.newFixedThreadPool(count, physicalFactory(factory)));
  }

  public static ExecutorService newCachedThreadPool() {
    return newCachedThreadPool(Executors.defaultThreadFactory());
  }

  public static ExecutorService newCachedThreadPool(ThreadFactory factory) {
    final var cap = cachedCarriers;
    final var carriers =
        new ThreadPoolExecutor(
            cap, cap, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), physicalFactory(factory));
    // Parked logical roots are daemon threads. Retain physical carriers until shutdown so
    // an otherwise idle process cannot exit while those roots still await their results.
    return own(carriers);
  }

  private static ThreadFactory physicalFactory(ThreadFactory factory) {
    Objects.requireNonNull(factory, "factory");
    return task -> {
      final var thread = Objects.requireNonNull(factory.newThread(task), "carrier thread");
      if (thread.isVirtual()) {
        throw new IllegalArgumentException("Carrier factory must create platform threads");
      }
      return thread;
    };
  }

  private static ExecutorService own(ExecutorService carriers) {
    try {
      final var scheduler = new RetiringScheduler(carriers);
      final var roots =
          Executors.newThreadPerTaskExecutor(virtualFactory(scheduler, "automatic-entry-", true));
      // A closer must not borrow a caller's potentially closing scheduler. It is outside roots,
      // and never waits for carrier termination while occupying one of those carriers.
      final var closers =
          virtualFactory(ForkJoinPool.commonPool(), "automatic-entry-close-", false);
      return new OwnedTaskExecutor(roots, carriers, scheduler, closers);
    } catch (RuntimeException | Error failure) {
      carriers.shutdownNow();
      throw failure;
    }
  }

  private static ThreadFactory virtualFactory(Executor scheduler, String name, boolean inherit) {
    try {
      final var type = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
      final var constructor = type.getDeclaredConstructor(Executor.class);
      constructor.setAccessible(true);
      final var builder = (Thread.Builder.OfVirtual) constructor.newInstance(scheduler);
      return builder.name(name, 0).inheritInheritableThreadLocals(inherit).factory();
    } catch (InvocationTargetException failure) {
      throw new IllegalStateException("Cannot create virtual-thread scheduler", failure.getCause());
    } catch (ReflectiveOperationException failure) {
      throw new IllegalStateException("JDK does not support the experimental scheduler", failure);
    }
  }
}
