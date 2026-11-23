package entryprobe;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reproduces why returning void alone is NOT enough to justify an asynchronous handoff. */
public final class VoidBoundaryNegativeProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Use future or advice");
    if (args[0].equals("future")) futureCanFinishBeforeItsRunnable();
    else adviceCanCloseBeforeTheWovenServiceBody();
  }

  private static void futureCanFinishBeforeItsRunnable() throws Exception {
    final var started = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var finallyDone = new CountDownLatch(1);
    final var afterGet = new AtomicInteger();
    try (final var platform = Executors.newFixedThreadPool(1)) {
      try {
        final var submitted =
            platform.submit(new SynchronousTask(started, release, finallyDone, afterGet));
        require(started.await(5, TimeUnit.SECONDS), "woven task did not start");
        submitted.get(5, TimeUnit.SECONDS);
        platform.shutdown();
        require(platform.awaitTermination(5, TimeUnit.SECONDS), "dispatch pool did not terminate");
        require(
            finallyDone.getCount() == 1 && afterGet.get() == 0,
            "generic Future early-completion hazard was not reproduced");
      } finally {
        release.countDown();
        require(finallyDone.await(5, TimeUnit.SECONDS), "test cleanup did not finish");
      }
    }
    System.out.println(
        "REPRODUCED UNSAFE generic Runnable weaving: Future.get and executor termination finish"
            + " before its real get/result/finally");
  }

  public record SynchronousTask(
      CountDownLatch started,
      CountDownLatch release,
      CountDownLatch finallyDone,
      AtomicInteger afterGet)
      implements Runnable {
    @Override
    public void run() {
      try {
        final var user =
            Blockless.supplier(
                () -> {
                  started.countDown();
                  release.await();
                  return "actual user";
                });
        require(user.get().equals("actual user"), "wrong result");
        afterGet.incrementAndGet();
      } finally {
        finallyDone.countDown();
      }
    }
  }

  private static void adviceCanCloseBeforeTheWovenServiceBody() throws Exception {
    final var started = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var scopeClosed = new AtomicBoolean();
    final var reply = new CompletableFuture<String>();
    try {
      // Models an outer interceptor/transaction proxy around the woven service method.
      try {
        new Service().query(started, release, scopeClosed, reply);
      } finally {
        scopeClosed.set(true);
      }
      require(started.await(5, TimeUnit.SECONDS), "query did not start");
      require(scopeClosed.get() && !reply.isDone(), "outer advice was not closed before the body");
      release.countDown();
      require(
          reply.get(5, TimeUnit.SECONDS).equals("scope already closed"),
          "scope lifetime hazard was not reproduced");
    } finally {
      release.countDown();
    }
    System.out.println(
        "REPRODUCED UNSAFE service-method weaving: outer interceptor/transaction scope closes"
            + " before actual query; weave before user advice instead");
  }

  public static final class Service {
    public void query(
        CountDownLatch started,
        CountDownLatch release,
        AtomicBoolean scopeClosed,
        CompletableFuture<String> reply) {
      final var user =
          Blockless.supplier(
              () -> {
                started.countDown();
                release.await();
                return scopeClosed.get() ? "scope already closed" : "scope still open";
              });
      reply.complete(user.get());
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
