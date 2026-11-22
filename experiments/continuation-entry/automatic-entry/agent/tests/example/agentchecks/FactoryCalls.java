package example.agentchecks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import unselected.ExternalFactories;

/** Tests factory rewriting, not business get() or the executor implementation's full contract. */
public final class FactoryCalls {
  public static void main(String[] args) throws Exception {
    final var expectedVirtual = args.length != 0 && args[0].equals("agent");
    final var carriers = new CopyOnWriteArrayList<Thread>();
    final ThreadFactory factory =
        task -> {
          final var thread =
              Thread.ofPlatform().name("provided-carrier-" + carriers.size()).unstarted(task);
          carriers.add(thread);
          return thread;
        };
    check(Executors.newFixedThreadPool(1), expectedVirtual);
    check(Executors.newFixedThreadPool(1, factory), expectedVirtual);
    check(Executors.newCachedThreadPool(), expectedVirtual);
    check(Executors.newCachedThreadPool(factory), expectedVirtual);

    final IntFunction<ExecutorService> fixedReference = Executors::newFixedThreadPool;
    final BiFunction<Integer, ThreadFactory, ExecutorService> fixedFactoryReference =
        Executors::newFixedThreadPool;
    final Supplier<ExecutorService> cachedReference = Executors::newCachedThreadPool;
    final Function<ThreadFactory, ExecutorService> cachedFactoryReference =
        Executors::newCachedThreadPool;
    for (final var executor :
        List.of(
            fixedReference.apply(1), fixedFactoryReference.apply(1, factory),
            cachedReference.get(), cachedFactoryReference.apply(factory))) {
      check(executor, expectedVirtual);
    }

    check(Executors.newSingleThreadExecutor(), false);
    check(Executors.newScheduledThreadPool(1), false);
    check(new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()), false);
    check(ExternalFactories.fixed(), false);
    if (carriers.isEmpty() || carriers.stream().anyMatch(Thread::isVirtual)) {
      throw new AssertionError("Provided carrier ThreadFactory was not honored");
    }
    System.out.println(
        "PASS factory calls + method references; single/scheduled/constructor/excluded caller"
            + " unchanged: "
            + (expectedVirtual ? "agent" : "baseline"));
  }

  private static void check(ExecutorService executor, boolean expectedVirtual) throws Exception {
    try (executor) {
      final var virtual =
          executor.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS);
      if (virtual != expectedVirtual) {
        throw new AssertionError("Unexpected task kind for " + executor.getClass().getName());
      }
    }
  }
}
