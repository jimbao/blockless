package automaticentry;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Run with java.lang open when testing without the agent's automatic module opening. */
public final class RetiringSchedulerProbe {
  public static void main(String[] args) throws Exception {
    childFinishesAfterItsRootAndOwnedCarriersExit();
    rejectionIsRedirectedOnlyAfterRetirement();
  }

  private static void childFinishesAfterItsRootAndOwnedCarriersExit() throws Exception {
    final var childStarted = new CountDownLatch(1);
    final var finishChild = new CountDownLatch(1);
    final var finishRoot = new CountDownLatch(1);
    final var childResult = new CompletableFuture<String>();
    final var childCleanups = new AtomicInteger();
    final var rootCleanups = new AtomicInteger();
    final var physicalThread = new AtomicReference<Thread>();
    final var local = new ThreadLocal<String>();
    final var currentCarrier = Thread.class.getDeclaredMethod("currentCarrierThread");
    currentCarrier.setAccessible(true);
    try (final var owner =
        EntryRuntime.newFixedThreadPool(
            1,
            task -> {
              final var thread = Thread.ofPlatform().name("retiring-carrier").unstarted(task);
              physicalThread.set(thread);
              return thread;
            })) {
      try {
        final var root =
            owner.submit(
                () -> {
                  Thread.startVirtualThread(
                      () -> {
                        final var identity = Thread.currentThread();
                        local.set("child context");
                        String value = null;
                        Throwable failure = null;
                        try {
                          require(
                              carrier(currentCarrier) == physicalThread.get(),
                              "Wrong initial carrier");
                          childStarted.countDown();
                          finishChild.await();
                          require(
                              carrier(currentCarrier) != physicalThread.get(),
                              "Closed carrier reused");
                          require(
                              Thread.currentThread() == identity, "Logical child identity changed");
                          require("child context".equals(local.get()), "Child thread context lost");
                          value = "hello";
                        } catch (Throwable thrown) {
                          failure = thrown;
                        } finally {
                          childCleanups.incrementAndGet();
                          local.remove();
                        }
                        if (failure == null) childResult.complete(value);
                        else childResult.completeExceptionally(failure);
                      });
                  try {
                    finishRoot.await();
                    return null;
                  } finally {
                    rootCleanups.incrementAndGet();
                  }
                });
        require(childStarted.await(5, TimeUnit.SECONDS), "Child did not start");
        require(root.cancel(true), "Root cancellation failed");
        owner.shutdown();
        require(owner.awaitTermination(5, TimeUnit.SECONDS), "Owned layers did not terminate");
        require(rootCleanups.get() == 1, "Root cleanup was skipped");
        require(childCleanups.get() == 0, "Child unexpectedly finished before release");
        finishChild.countDown();
        require("hello".equals(childResult.get(5, TimeUnit.SECONDS)), "Child outcome was lost");
        require(childCleanups.get() == 1, "Child cleanup was skipped or repeated");
      } finally {
        finishRoot.countDown();
        finishChild.countDown();
      }
    }
    System.out.println(
        "PASS: unstructured child resumes after root cancellation and owned-carrier termination,"
            + " preserving identity/context/finally/outcome");
  }

  private static void rejectionIsRedirectedOnlyAfterRetirement() throws Exception {
    final var rejection = new RejectedExecutionException("active scheduler rejected");
    final var active =
        new RetiringScheduler(
            task -> {
              throw rejection;
            });
    try {
      active.execute(() -> {});
      throw new AssertionError("Active rejection was hidden");
    } catch (RejectedExecutionException expected) {
      require(expected == rejection, "Original rejection identity changed");
    }

    final var scheduler = new AtomicReference<RetiringScheduler>();
    scheduler.set(
        new RetiringScheduler(
            task -> {
              scheduler.get().retire();
              throw rejection;
            }));
    final var redirected = new CountDownLatch(1);
    scheduler.get().execute(redirected::countDown);
    require(redirected.await(5, TimeUnit.SECONDS), "Retirement/rejection race lost work");
    System.out.println(
        "PASS: active rejection preserved; retirement racing dispatch redirects to a live shared"
            + " carrier");
  }

  private static Thread carrier(Method method) {
    try {
      return (Thread) method.invoke(null);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
