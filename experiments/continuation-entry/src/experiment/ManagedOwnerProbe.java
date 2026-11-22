package experiment;

import io.github.pjlabs.blockless.continuations.Continuations;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ManagedOwnerProbe {
  public static void main(String[] args) throws Exception {
    managedNativeLifecycle(false);
    managedNativeLifecycle(true);
    fastCompletionsDoNotReplayOrLoseJobs();
    closedBorrowedCarrierDoesNotAbandonLogicalFinally();
    executeFailureIsReportedAfterFinally();
    System.out.println("ALL managed native executor proof checks passed");
  }

  static void managedNativeLifecycle(boolean fails) throws Exception {
    var releaseBody = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    var enteredGet = new CountDownLatch(1);
    var cleanupStarted = new CountDownLatch(1);
    var entries = new AtomicInteger();
    var bodyEntries = new AtomicInteger();
    var afterGet = new AtomicInteger();
    var callerCleanups = new AtomicInteger();
    var bodyCleanups = new AtomicInteger();
    var logicalCompletions = new AtomicInteger();
    var createdCarriers = new AtomicInteger();
    var callerCarrier = new AtomicReference<Thread>();
    var uncaught = new CompletableFuture<Throwable>();
    var failure = new IllegalArgumentException("original body failure");
    try (var carriers =
        Executors.newSingleThreadExecutor(
            r -> {
              createdCarriers.incrementAndGet();
              return Thread.ofPlatform().name("one-carrier").unstarted(r);
            })) {
      var owner =
          new ManagedOwner(carriers, uncaught::complete, logicalCompletions::incrementAndGet);
      var future =
          owner.submit(
              () -> {
                check(
                    !Thread.currentThread().isVirtual(),
                    "caller should start as an actual platform thread");
                callerCarrier.set(Thread.currentThread());
                entries.incrementAndGet();
                var savedLocal = "request:" + 42;
                try {
                  var user =
                      Continuations.supplier(
                          () -> {
                            check(
                                Thread.currentThread().isVirtual(),
                                "blocking body should run on VT");
                            bodyEntries.incrementAndGet();
                            try {
                              releaseBody.await();
                              if (fails) throw failure;
                              return "hello";
                            } finally {
                              cleanupStarted.countDown();
                              releaseCleanup.await();
                              bodyCleanups.incrementAndGet();
                            }
                          });
                  enteredGet.countDown();
                  var value = user.get();
                  check(
                      Thread.currentThread() == callerCarrier.get(),
                      "ordinary resume should use the same carrier");
                  afterGet.incrementAndGet();
                  return value + ":" + savedLocal;
                } finally {
                  callerCleanups.incrementAndGet();
                }
              });
      try {
        check(enteredGet.await(5, TimeUnit.SECONDS), "caller did not enter get");
        check(
            carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS) == callerCarrier.get(),
            "original carrier was not freed");
        check(createdCarriers.get() == 1, "replacement platform thread hid blocked carrier");
        check(!future.isDone(), "Future completed at first native yield");
        check(
            logicalCompletions.get() == 0 && callerCleanups.get() == 0,
            "logical completion/finally ran early");
        check(!future.cancel(true), "prototype must decline unsafe native cancellation");
        check(
            !carriers.submit(Thread::interrupted).get(5, TimeUnit.SECONDS),
            "declined cancel interrupted carrier");
        check(!future.isDone(), "declined cancellation changed completion");
        owner.shutdown();
        check(owner.isShutdown() && !owner.isTerminated(), "shutdown lost logical active job");
        check(!owner.awaitTermination(20, TimeUnit.MILLISECONDS), "terminated before body/finally");
        expect(RejectedExecutionException.class, () -> owner.execute(() -> {}));
        expect(UnsupportedOperationException.class, owner::shutdownNow);
        releaseBody.countDown();
        check(cleanupStarted.await(5, TimeUnit.SECONDS), "body finally not reached");
        check(!future.isDone() && !owner.isTerminated(), "body cleanup is still pending");
        check(logicalCompletions.get() == 0, "logical hook fired before body cleanup");
        check(
            carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS) == callerCarrier.get(),
            "body finally tied up carrier");
        releaseCleanup.countDown();
        if (fails) {
          try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("missing original failure");
          } catch (ExecutionException thrown) {
            check(thrown.getCause() == failure, "original failure identity changed");
          }
        } else {
          check(
              future.get(5, TimeUnit.SECONDS).equals("hello:request:42"),
              "value or caller local lost");
        }
        check(
            owner.awaitTermination(5, TimeUnit.SECONDS),
            "logical job did not resume after shutdown");
        check(!carriers.isShutdown(), "owner shut down borrowed carriers");
        check(logicalCompletions.get() == 1, "logical completion should fire exactly once");
        check(entries.get() == 1 && bodyEntries.get() == 1, "caller/body replayed");
        check(
            callerCleanups.get() == 1 && bodyCleanups.get() == 1, "finally skipped or duplicated");
        check(afterGet.get() == (fails ? 0 : 1), "code after get executed incorrectly");
        check(!uncaught.isDone(), "submit failure escaped FutureTask");
        System.out.println(
            "Managed lifecycle fails="
                + fails
                + ": PASS pending Future, same-carrier progress, original outcome, locals/finally,"
                + " graceful shutdown and declined cancellation");
      } finally {
        releaseBody.countDown();
        releaseCleanup.countDown();
        owner.shutdown();
      }
    }
  }

  static void fastCompletionsDoNotReplayOrLoseJobs() throws Exception {
    var completed = new AtomicInteger();
    var cleanup = new AtomicInteger();
    var bodies = new AtomicInteger();
    var uncaught = new CompletableFuture<Throwable>();
    try (var carriers = Executors.newSingleThreadExecutor()) {
      var owner = new ManagedOwner(carriers, uncaught::complete, completed::incrementAndGet);
      var tasks = new ArrayList<Future<Integer>>();
      for (int i = 0; i < 2_000; i++) {
        var value = i;
        tasks.add(
            owner.submit(
                () -> {
                  try {
                    var user =
                        Continuations.supplier(
                            () -> {
                              bodies.incrementAndGet();
                              return value;
                            });
                    var firstRead = user.get();
                    check(user.get().equals(firstRead), "cached get changed");
                    return firstRead;
                  } finally {
                    cleanup.incrementAndGet();
                  }
                }));
      }
      owner.shutdown();
      for (int i = 0; i < tasks.size(); i++)
        check(tasks.get(i).get(5, TimeUnit.SECONDS) == i, "wrong result");
      check(owner.awaitTermination(5, TimeUnit.SECONDS), "racing flows not drained");
      check(
          completed.get() == tasks.size()
              && cleanup.get() == tasks.size()
              && bodies.get() == tasks.size(),
          "duplicate/missing work or lifecycle event");
      check(!uncaught.isDone(), "unexpected native resume failure");
      System.out.println(
          "Fast/inline completion races: PASS 2000 logical jobs, exact outcomes and single"
              + " cleanup/completion");
    }
  }

  static void closedBorrowedCarrierDoesNotAbandonLogicalFinally() throws Exception {
    var release = new CountDownLatch(1);
    var insideGet = new CountDownLatch(1);
    var cleanup = new AtomicInteger();
    var uncaught = new CompletableFuture<Throwable>();
    var carriers = Executors.newSingleThreadExecutor();
    var owner = new ManagedOwner(carriers, uncaught::complete, () -> {});
    try {
      var future =
          owner.submit(
              () -> {
                try {
                  var user =
                      Continuations.supplier(
                          () -> {
                            release.await();
                            return "hello";
                          });
                  insideGet.countDown();
                  return user.get();
                } finally {
                  cleanup.incrementAndGet();
                }
              });
      check(insideGet.await(5, TimeUnit.SECONDS), "caller not running");
      carriers.submit(() -> {}).get(5, TimeUnit.SECONDS);
      owner.shutdown();
      carriers.shutdown();
      check(carriers.awaitTermination(5, TimeUnit.SECONDS), "borrowed carrier still busy");
      check(
          !owner.isTerminated() && !future.isDone(), "logical flow abandoned on carrier shutdown");
      release.countDown();
      check(future.get(5, TimeUnit.SECONDS).equals("hello"), "fallback lost actual outcome");
      check(
          owner.awaitTermination(5, TimeUnit.SECONDS) && cleanup.get() == 1,
          "fallback did not finish caller finally");
      check(!uncaught.isDone(), "fallback failed");
      System.out.println(
          "Borrowed carrier closes first: PASS fallback resume preserves outcome and finally");
    } finally {
      release.countDown();
      owner.shutdown();
      carriers.shutdownNow();
    }
  }

  static void executeFailureIsReportedAfterFinally() throws Exception {
    var uncaught = new CompletableFuture<Throwable>();
    var hooks = new AtomicInteger();
    var cleanup = new AtomicInteger();
    var failure = new IllegalStateException("execute failure");
    try (var carriers = Executors.newSingleThreadExecutor()) {
      var owner = new ManagedOwner(carriers, uncaught::complete, hooks::incrementAndGet);
      owner.execute(
          () -> {
            try {
              Continuations.supplier(
                      () -> {
                        throw failure;
                      })
                  .get();
            } finally {
              cleanup.incrementAndGet();
            }
          });
      owner.shutdown();
      check(uncaught.get(5, TimeUnit.SECONDS) == failure, "execute failure identity lost");
      check(owner.awaitTermination(5, TimeUnit.SECONDS), "failed flow not removed");
      check(cleanup.get() == 1 && hooks.get() == 1, "failure cleanup/hook wrong");
      expect(IllegalArgumentException.class, () -> owner.execute(new FutureTask<>(() -> 42)));
      System.out.println(
          "execute failure: PASS original uncaught failure after caller finally; foreign"
              + " cancellable tasks rejected");
    }
  }

  static void expect(Class<? extends Throwable> type, Runnable action) {
    try {
      action.run();
      throw new AssertionError("expected " + type);
    } catch (Throwable thrown) {
      if (!type.isInstance(thrown)) throw new AssertionError("wrong failure", thrown);
    }
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
