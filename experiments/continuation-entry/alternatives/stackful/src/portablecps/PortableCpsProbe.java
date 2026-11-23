package portablecps;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.javaflow.api.Continuation;
import org.apache.commons.javaflow.api.continuable;

/** Mechanism proof only: the owner is deliberately not a general ExecutorService replacement. */
public final class PortableCpsProbe {
  static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

  public static void main(String[] args) throws Exception {
    run(false, false);
    run(true, false);
    run(false, true);
    resumingOnAnotherCarrierBreaksCallerLockOwnership();
  }

  private static void run(boolean affinity, boolean terminate) throws Exception {
    final var physical = new AtomicReference<Thread>();
    final var work = new BusinessTask(affinity);
    try (final var carrier =
        Executors.newFixedThreadPool(
            1,
            command -> {
              final var thread =
                  Thread.ofPlatform().name("portable-cps-carrier").unstarted(command);
              physical.set(thread);
              return thread;
            })) {
      try {
        final var owner = new Owner(carrier, work);
        carrier.execute(owner::advance);
        require(work.bodyStarted.await(5, TimeUnit.SECONDS), "body did not start");
        if (!owner.suspended.await(5, TimeUnit.SECONDS)) {
          if (work.failure != null)
            throw new AssertionError("flow failed before suspension", work.failure);
          throw new AssertionError("call stack did not suspend");
        }
        require(
            work.cleanups.get() == 0 && work.afterGet.get() == 0,
            "flow advanced during suspension");
        final var observation =
            carrier
                .submit(
                    () -> {
                      final var acquired = work.lock.tryLock();
                      if (acquired) work.lock.unlock();
                      return new Observation(Thread.currentThread(), CONTEXT.get(), acquired);
                    })
                .get(5, TimeUnit.SECONDS);
        require(observation.thread() == physical.get(), "a replacement carrier hid the wait");
        require(!observation.thread().isVirtual(), "this proof requires a raw platform carrier");
        if (affinity) {
          require(
              "request-context".equals(observation.context()),
              "expected TLS hazard not reproduced");
          require(observation.acquired(), "expected reentrant owner hazard not reproduced");
          System.out.println(
              "HAZARD reproduced: unrelated task sees suspended request TLS and reentrant lock"
                  + " ownership");
        }
        if (terminate) {
          carrier.submit(owner::terminate).get(5, TimeUnit.SECONDS);
          require(work.finished.await(5, TimeUnit.SECONDS), "termination did not finish");
          require(
              work.afterGet.get() == 0 && work.cleanups.get() == 1,
              "termination skipped or replayed finally");
          System.out.println(
              "PASS bounded termination: suspended stack unwinds caller finally once");
        } else {
          work.finishBody.countDown();
          require(work.finished.await(5, TimeUnit.SECONDS), "get did not return its value");
          if (work.failure != null) throw new AssertionError("flow failed", work.failure);
          require("user:42".equals(work.value), "wrong real value");
          require(work.entries.get() == 1 && work.afterGet.get() == 1, "flow replayed");
          require(
              work.cleanups.get() == 1 && work.bodyCleanups.get() == 1,
              "finally skipped or repeated");
          require(!work.caller.isVirtual(), "logical caller unexpectedly became virtual");
          System.out.println(
              "PASS: instrumented platform caller get returned actualT; same sole carrier"
                  + " progressed; locals/finally preserved");
        }
      } finally {
        work.finishBody.countDown();
      }
    }
  }

  private record Observation(Thread thread, String context, boolean acquired) {}

  private static void resumingOnAnotherCarrierBreaksCallerLockOwnership() throws Exception {
    final var work = new BusinessTask(true);
    final var slices = new AtomicInteger();
    try (final var first = Executors.newSingleThreadExecutor();
        final var second = Executors.newSingleThreadExecutor()) {
      try {
        final var owner =
            new Owner(
                command -> (slices.getAndIncrement() == 0 ? first : second).execute(command), work);
        owner.carriers.execute(owner::advance);
        require(work.bodyStarted.await(5, TimeUnit.SECONDS), "migrating body did not start");
        require(owner.suspended.await(5, TimeUnit.SECONDS), "migrating caller did not suspend");
        work.finishBody.countDown();
        require(work.finished.await(5, TimeUnit.SECONDS), "migrating caller did not resume");
        require(
            work.failure instanceof IllegalMonitorStateException,
            "lock ownership failure not reproduced");
        require(work.cleanups.get() == 0, "cleanup unexpectedly continued past failed unlock");
        System.out.println(
            "HAZARD reproduced: resume on another platform carrier throws"
                + " IllegalMonitorStateException during caller finally");
      } finally {
        work.finishBody.countDown();
        first
            .submit(
                () -> {
                  if (work.lock.isHeldByCurrentThread()) work.lock.unlock();
                  CONTEXT.remove();
                })
            .get(5, TimeUnit.SECONDS);
      }
    }
  }

  public static final class BusinessTask implements Runnable {
    final boolean affinity;
    final ReentrantLock lock = new ReentrantLock();
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    final CountDownLatch finishBody = new CountDownLatch(1);
    final CountDownLatch finished = new CountDownLatch(1);
    final AtomicInteger entries = new AtomicInteger();
    final AtomicInteger afterGet = new AtomicInteger();
    final AtomicInteger cleanups = new AtomicInteger();
    final AtomicInteger bodyCleanups = new AtomicInteger();
    volatile String value;
    volatile Throwable failure;
    Thread caller;

    BusinessTask(boolean affinity) {
      this.affinity = affinity;
    }

    @Override
    @continuable
    public void run() {
      caller = Thread.currentThread();
      entries.incrementAndGet();
      if (affinity) {
        CONTEXT.set("request-context");
        lock.lock();
      }
      try {
        final var savedLocal = "saved:" + 42;
        value = loadUser();
        require(savedLocal.equals("saved:42"), "local lost");
        afterGet.incrementAndGet();
      } finally {
        if (affinity) {
          lock.unlock();
          CONTEXT.remove();
        }
        cleanups.incrementAndGet();
      }
    }

    @continuable
    public String loadUser() {
      final var user =
          Blockless.supplier(
              () -> {
                require(Thread.currentThread().isVirtual(), "I/O body is not virtual");
                bodyStarted.countDown();
                try {
                  finishBody.await();
                  return "user:42";
                } finally {
                  bodyCleanups.incrementAndGet();
                }
              });
      return user.get();
    }
  }

  private static final class Owner {
    final Executor carriers;
    final BusinessTask work;
    final CountDownLatch suspended = new CountDownLatch(1);
    Continuation continuation;
    volatile boolean done;

    Owner(Executor carriers, BusinessTask work) {
      this.carriers = carriers;
      this.work = work;
      continuation = Continuation.startSuspendedWith(work, true);
    }

    void advance() {
      if (done) return;
      FlowRuntime.ACTIVE.set(true);
      try {
        continuation = continuation.resume();
      } catch (Throwable failure) {
        work.failure = failure;
        continuation = null;
      } finally {
        FlowRuntime.ACTIVE.remove();
      }
      if (continuation == null) {
        done = true;
        work.finished.countDown();
      } else {
        final var pending = (CompletableFuture<?>) continuation.value();
        pending.whenComplete(
            (value, failure) -> {
              if (!done) carriers.execute(this::advance);
            });
        suspended.countDown();
      }
    }

    void terminate() {
      if (done) return;
      done = true;
      FlowRuntime.ACTIVE.set(true);
      try {
        continuation.terminate();
      } finally {
        FlowRuntime.ACTIVE.remove();
        work.finished.countDown();
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
