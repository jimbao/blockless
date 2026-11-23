package entryprobe;

import io.github.pjlabs.blockless.Blockless;
import io.grpc.Context;
import io.grpc.internal.SerializingExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Exercises actual grpc-core SerializingExecutor with the stable Blockless API. */
public final class SerializingEntryProbe {
  private static final Context.Key<String> REQUEST = Context.key("entry-request");
  private static final ThreadLocal<String> LOCAL = new ThreadLocal<>();

  public static void main(String[] args) throws Exception {
    strictWholeCallbackLoopEntry();
    originalPoolTerminationDoesNotJoinLogicalRequests();
  }

  private static void strictWholeCallbackLoopEntry() throws Exception {
    final var gates = new Gates();
    final var physical = new AtomicReference<Thread>();
    final var created = new AtomicInteger();
    final var caller = new AtomicReference<Thread>();
    final var afterGet = new AtomicInteger();
    final var cleanup = new AtomicInteger();
    final var nextSameStreamEvent = new AtomicInteger();
    final var requestExited = new CountDownLatch(1);
    final var nextEventFinished = new CountDownLatch(1);
    final var response = new CompletableFuture<User>();
    final var lock = new ReentrantLock();
    try (final var platform =
        Executors.newFixedThreadPool(
            1,
            task -> {
              created.incrementAndGet();
              final var thread = Thread.ofPlatform().name("callback-physical").unstarted(task);
              physical.set(thread);
              return thread;
            })) {
      try {
        final var requestLoop = new SerializingExecutor(platform);
        requestLoop.execute(
            Context.current()
                .withValue(REQUEST, "request-42")
                .wrap(
                    () -> {
                      // Models framework/user advice INSIDE the logical callback-loop entry.
                      caller.set(Thread.currentThread());
                      LOCAL.set("request scope");
                      lock.lock();
                      try {
                        final var savedLocal = "request:" + 42;
                        require(
                            REQUEST.get().equals("request-42"), "grpc Context missing at entry");
                        final var value = loadUser(gates);
                        require(savedLocal.equals("request:42"), "caller local was lost");
                        require(
                            Thread.currentThread() == caller.get(),
                            "logical caller identity changed");
                        require(LOCAL.get().equals("request scope"), "caller ThreadLocal was lost");
                        require(
                            REQUEST.get().equals("request-42"), "grpc Context was lost across get");
                        require(lock.isHeldByCurrentThread(), "lock ownership was lost");
                        afterGet.incrementAndGet();
                        response.complete(
                            value); // Framework response only AFTER the direct get returns.
                      } catch (Throwable failure) {
                        response.completeExceptionally(failure);
                      } finally {
                        LOCAL.remove();
                        lock.unlock();
                        cleanup.incrementAndGet();
                        requestExited.countDown();
                      }
                    }));
        require(gates.atGet.await(5, TimeUnit.SECONDS), "caller did not reach get");
        require(gates.bodyStarted.await(5, TimeUnit.SECONDS), "blocking body never started");
        requestLoop.execute(
            () -> {
              nextSameStreamEvent.incrementAndGet();
              require(LOCAL.get() == null, "request-local scope leaked to next event");
              nextEventFinished.countDown();
            });
        final Thread marker;
        try {
          marker = platform.submit(Thread::currentThread).get(1, TimeUnit.SECONDS);
        } catch (TimeoutException occupied) {
          gates.releaseBody.countDown();
          gates.releaseCleanup.countDown();
          require(
              response.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
              "wrong control value");
          throw new AssertionError("platform request worker remained occupied by get", occupied);
        }
        require(
            marker == physical.get() && !marker.isVirtual(),
            "original physical worker was not reused");
        require(created.get() == 1, "replacement request worker disguised blocking");
        require(caller.get().isVirtual(), "callback loop did not establish a logical VT caller");
        require(
            !response.isDone() && afterGet.get() == 0, "request completed before its real result");
        require(
            cleanup.get() == 0 && gates.bodyCleanups.get() == 0,
            "scope/finally closed during suspension");
        require(nextSameStreamEvent.get() == 0, "same-stream event overtook pending user callback");

        final var otherResponse = new CompletableFuture<Boolean>();
        new SerializingExecutor(platform)
            .execute(
                () -> {
                  final var acquired = lock.tryLock();
                  if (acquired) lock.unlock();
                  otherResponse.complete(
                      Thread.currentThread().isVirtual()
                          && LOCAL.get() == null
                          && REQUEST.get() == null
                          && !acquired);
                });
        require(
            otherResponse.get(5, TimeUnit.SECONDS),
            "independent callback inherited context/lock ownership");
        require(nextSameStreamEvent.get() == 0, "same-stream ordering changed while body pending");

        gates.releaseBody.countDown();
        require(gates.cleanupStarted.await(5, TimeUnit.SECONDS), "body cleanup did not begin");
        require(
            !response.isDone() && cleanup.get() == 0,
            "response or advice closed before body finally");
        gates.releaseCleanup.countDown();
        require(
            response.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
            "wrong actual User");
        require(requestExited.await(5, TimeUnit.SECONDS), "request finally did not finish");
        require(
            nextEventFinished.await(5, TimeUnit.SECONDS), "serialized next event did not resume");
        require(
            afterGet.get() == 1 && cleanup.get() == 1 && gates.bodyCleanups.get() == 1,
            "caller/body replay or skipped finally");
        require(gates.entries.get() == 1 && nextSameStreamEvent.get() == 1, "body/event replay");
      } finally {
        gates.releaseBody.countDown();
        gates.releaseCleanup.countDown();
      }
    }
    System.out.println(
        "PASS actual grpc SerializingExecutor: direct stable Blockless.supplier/get returns User;"
            + " original one PT freed; caller VT; caller scope/context/locks/finally and per-stream"
            + " ordering retained");
  }

  /** This is a framework-loop handoff, NOT a replacement ExecutorService lifecycle contract. */
  private static void originalPoolTerminationDoesNotJoinLogicalRequests() throws Exception {
    final var gates = new Gates();
    final var requestExited = new CountDownLatch(1);
    final var response = new CompletableFuture<User>();
    final var callbackThread = new AtomicReference<Thread>();
    final var lateEvent = new CountDownLatch(1);
    try (final var platform = Executors.newFixedThreadPool(1)) {
      try {
        final var requestLoop = new SerializingExecutor(platform);
        requestLoop.execute(
            () -> {
              callbackThread.set(Thread.currentThread());
              try {
                response.complete(loadUser(gates));
              } catch (Throwable failure) {
                response.completeExceptionally(failure);
              } finally {
                requestExited.countDown();
              }
            });
        require(
            gates.atGet.await(5, TimeUnit.SECONDS) && gates.bodyStarted.await(5, TimeUnit.SECONDS),
            "request did not start");
        platform.shutdown();
        require(
            platform.awaitTermination(5, TimeUnit.SECONDS),
            "physical dispatch pool did not finish its handoff");
        require(
            !response.isDone() && requestExited.getCount() == 1,
            "lifetime limit was not exercised");
        requestLoop.execute(lateEvent::countDown);
        gates.releaseBody.countDown();
        gates.releaseCleanup.countDown();
        require(
            response.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
            "unowned request lost outcome");
        require(requestExited.await(5, TimeUnit.SECONDS), "unowned request lost finally");
        require(lateEvent.await(5, TimeUnit.SECONDS), "current drain lost its queued late event");
        callbackThread.get().join(5_000);
        require(!callbackThread.get().isAlive(), "callback drain did not terminate");
        try {
          requestLoop.execute(() -> {});
          throw new AssertionError(
              "new callback drain unexpectedly accepted after pool termination");
        } catch (RejectedExecutionException expectedAfterClosedDispatcher) {
          // A callback queued into a live drain can finish; a new drain needs its dispatcher.
        }
      } finally {
        gates.releaseBody.countDown();
        gates.releaseCleanup.countDown();
      }
    }
    System.out.println(
        "LIMIT confirmed: original PT pool can terminate while logical request is pending;"
            + " current drain finishes queued late events, but new drain rejects after shutdown;"
            + " framework must own request completion/lifetime");
  }

  private static User loadUser(Gates gates) {
    final var user =
        Blockless.supplier(
            () -> {
              gates.entries.incrementAndGet();
              require(Thread.currentThread().isVirtual(), "producer was not virtual");
              gates.bodyStarted.countDown();
              try {
                gates.releaseBody.await();
                return new User("details:42", "read");
              } finally {
                gates.cleanupStarted.countDown();
                gates.releaseCleanup.await();
                gates.bodyCleanups.incrementAndGet();
              }
            });
    gates.atGet.countDown();
    return user.get();
  }

  private record User(String details, String permissions) {}

  private static final class Gates {
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    final CountDownLatch atGet = new CountDownLatch(1);
    final CountDownLatch releaseBody = new CountDownLatch(1);
    final CountDownLatch cleanupStarted = new CountDownLatch(1);
    final CountDownLatch releaseCleanup = new CountDownLatch(1);
    final AtomicInteger entries = new AtomicInteger();
    final AtomicInteger bodyCleanups = new AtomicInteger();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
