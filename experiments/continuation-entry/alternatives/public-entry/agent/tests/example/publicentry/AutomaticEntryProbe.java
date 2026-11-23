package example.publicentry;

import io.github.pjlabs.blockless.Blockless;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import unselected.PublicExternalFactories;

/**
 * The same compiled factory call sites and business supplier/get run without and with the agent.
 */
public final class AutomaticEntryProbe {
  public static void main(String[] args) throws Exception {
    final var automatic = args.length != 0 && args[0].equals("automatic");
    factoryCallsAndMethodReferences(automatic);
    pendingGetAllowsAnotherLogicalRootToProgress();
  }

  private static void factoryCallsAndMethodReferences(boolean automatic) throws Exception {
    final var physicalFactoryCalls = new AtomicInteger();
    final ThreadFactory physicalFactory =
        task -> {
          physicalFactoryCalls.incrementAndGet();
          return Thread.ofPlatform().unstarted(task);
        };
    final IntFunction<ExecutorService> fixed = Executors::newFixedThreadPool;
    final BiFunction<Integer, ThreadFactory, ExecutorService> fixedWithFactory =
        Executors::newFixedThreadPool;
    final Supplier<ExecutorService> cached = Executors::newCachedThreadPool;
    final Function<ThreadFactory, ExecutorService> cachedWithFactory =
        Executors::newCachedThreadPool;
    for (final var owner :
        List.of(
            Executors.newFixedThreadPool(1),
            Executors.newFixedThreadPool(1, physicalFactory),
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool(physicalFactory),
            fixed.apply(1),
            fixedWithFactory.apply(1, physicalFactory),
            cached.get(),
            cachedWithFactory.apply(physicalFactory))) {
      checkRootKind(owner, automatic);
    }
    require(
        (physicalFactoryCalls.get() == 0) == automatic,
        "public scheduler mode must explicitly stop using physical ThreadFactory");
    checkRootKind(Executors.newSingleThreadExecutor(), false);
    checkRootKind(Executors.newScheduledThreadPool(1), false);
    checkRootKind(PublicExternalFactories.fixed(), false);
    System.out.println(
        "PASS all four factory overloads and method references; physical factory "
            + (automatic ? "intentionally unused" : "used by native baseline")
            + "; single/scheduled/excluded factories unchanged");
  }

  private static void checkRootKind(ExecutorService owner, boolean expectedVirtual)
      throws Exception {
    try (owner) {
      require(
          owner.submit(() -> Thread.currentThread().isVirtual()).get(5, TimeUnit.SECONDS)
              == expectedVirtual,
          "wrong logical root kind");
    }
  }

  private static void pendingGetAllowsAnotherLogicalRootToProgress() throws Exception {
    final var started = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var cleanups = new AtomicInteger();
    try (final var requests = Executors.newFixedThreadPool(1)) {
      try {
        final var request =
            requests.submit(
                () -> {
                  final var user =
                      Blockless.supplier(
                          () -> {
                            try {
                              started.countDown();
                              release.await();
                              return "actual user";
                            } finally {
                              cleanups.incrementAndGet();
                            }
                          });
                  return user.get();
                });
        require(started.await(5, TimeUnit.SECONDS), "body never started");
        final var marker = requests.submit(() -> Thread.currentThread().isVirtual());
        try {
          require(marker.get(1, TimeUnit.SECONDS), "marker did not receive logical VT entry");
        } catch (TimeoutException occupiedWorker) {
          release.countDown();
          require("actual user".equals(request.get(5, TimeUnit.SECONDS)), "baseline value lost");
          throw new AssertionError(
              "native fixed worker stays occupied by pending get", occupiedWorker);
        }
        require(!request.isDone() && cleanups.get() == 0, "pending body returned or cleaned early");
        release.countDown();
        require("actual user".equals(request.get(5, TimeUnit.SECONDS)), "actual value lost");
        require(cleanups.get() == 1, "body finally skipped/repeated");
      } finally {
        release.countDown();
      }
    }
    System.out.println(
        "PASS automatic stable Blockless supplier/get: real value, pending request, independent"
            + " logical progress, finally once");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
