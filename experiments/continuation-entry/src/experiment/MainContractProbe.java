package experiment;

import io.github.pjlabs.blockless.continuations.Continuations;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Business calls use the actual library. Only probeWorker() is test harness plumbing. */
public final class MainContractProbe {
  private static final Thread INITIAL_THREAD = Thread.currentThread();
  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

  static {
    CONTEXT.set("set during class initialization");
    if (Boolean.getBoolean("experiment.initialInterrupt")) Thread.currentThread().interrupt();
  }

  public static void main(String[] args) throws Exception {
    check(Thread.currentThread() == INITIAL_THREAD, "main moved to another thread");
    check(!Thread.currentThread().isVirtual(), "expected a platform-thread caller");
    check(CONTEXT.get().equals("set during class initialization"), "lost initial context");
    final var mode = args.length == 0 ? "success" : args[0];
    switch (mode) {
      case "success", "failure", "null" -> delayedValue(mode);
      case "parallel" -> parallel();
      case "races" -> completionRaces();
      case "interrupt" -> interruptedWait();
      case "interrupt-race" -> interruptedCompletion();
      case "initial-interrupt" -> check(Thread.interrupted(), "initial interrupt lost");
      case "foreign" -> foreignThread();
      case "uncaught" -> uncaughtFailure();
      default -> throw new IllegalArgumentException(mode);
    }
    System.out.println("PASS " + mode);
  }

  private static void delayedValue(String mode) throws Exception {
    final var workerAvailable = new CountDownLatch(1);
    final var bodies = new AtomicInteger();
    final var cleanups = new AtomicInteger();
    final var expectedFailure = new IllegalArgumentException("original business failure");
    final var savedLocal = "request:42";
    CONTEXT.set("changed before suspension");
    MainRuntime.probeWorker(
        () -> {
          check(
              Thread.currentThread() == INITIAL_THREAD, "replacement worker hid a blocked caller");
          check(cleanups.get() == 0, "finally ran on suspension");
          workerAvailable.countDown();
        });
    try {
      final var user =
          Continuations.supplier(
              () -> {
                check(Thread.currentThread().isVirtual(), "blocking body is not virtual");
                bodies.incrementAndGet();
                check(workerAvailable.await(5, TimeUnit.SECONDS), "caller blocked the worker");
                final var details = "Alice";
                final var permissions = "admin";
                if (mode.equals("failure")) throw expectedFailure;
                return mode.equals("null") ? null : details + ": " + permissions;
              });
      try {
        final var value = readFromNestedMethod(user::get, savedLocal);
        check(!mode.equals("failure"), "failure disappeared");
        check(mode.equals("null") ? value == null : value.equals("Alice: admin"), "wrong value");
        check(user.get() == value, "cached read changed");
      } catch (IllegalArgumentException failure) {
        check(mode.equals("failure") && failure == expectedFailure, "exception identity changed");
      }
      check(bodies.get() == 1, "body replayed");
      check(CONTEXT.get().equals("changed before suspension"), "main context changed");
    } finally {
      cleanups.incrementAndGet();
    }
    check(cleanups.get() == 1, "finally duplicated");
  }

  private static <T> T readFromNestedMethod(Supplier<T> supplier, String saved) {
    final var local = saved + ":nested";
    final var value = supplier.get();
    check(local.equals("request:42:nested"), "caller local lost");
    return value;
  }

  private static void parallel() throws Exception {
    final var started = new CountDownLatch(2);
    final var workerAvailable = new CountDownLatch(1);
    MainRuntime.probeWorker(workerAvailable::countDown);
    final var details =
        Continuations.supplier(
            () -> {
              started.countDown();
              check(started.await(5, TimeUnit.SECONDS), "suppliers did not start in parallel");
              check(workerAvailable.await(5, TimeUnit.SECONDS), "worker unavailable");
              return "Alice";
            });
    final var permissions =
        Continuations.supplier(
            () -> {
              started.countDown();
              check(started.await(5, TimeUnit.SECONDS), "suppliers did not start in parallel");
              check(workerAvailable.await(5, TimeUnit.SECONDS), "worker unavailable");
              return "admin";
            });
    check((details.get() + ": " + permissions.get()).equals("Alice: admin"), "wrong user");
  }

  private static void completionRaces() {
    final var bodies = new AtomicInteger();
    for (int index = 0; index < 5_000; index++) {
      final var result = Continuations.supplier(bodies::incrementAndGet);
      check(result.get() == index + 1, "lost or duplicated completion");
    }
    check(bodies.get() == 5_000, "body count changed");
  }

  private static void interruptedWait() {
    final var releaseBody = new CountDownLatch(1);
    final var cleanups = new AtomicInteger();
    MainRuntime.probeWorker(INITIAL_THREAD::interrupt);
    try {
      final var result =
          Continuations.supplier(
              () -> {
                releaseBody.await();
                return "hello";
              });
      try {
        result.get();
        throw new AssertionError("interruption disappeared");
      } catch (RuntimeException failure) {
        check(failure.getCause() instanceof InterruptedException, "wrong interruption failure");
        check(Thread.interrupted(), "interruption status lost");
      }
    } finally {
      cleanups.incrementAndGet();
      releaseBody.countDown();
    }
    check(cleanups.get() == 1, "interruption skipped finally");
  }

  private static void foreignThread() {
    final var outcome = new CompletableFuture<Boolean>();
    final var release = new CountDownLatch(1);
    Thread.ofPlatform()
        .start(
            () -> {
              try {
                final var user =
                    Continuations.supplier(
                        () -> {
                          release.await();
                          return "hello";
                        });
                user.get();
                outcome.complete(false);
              } catch (IllegalStateException expected) {
                outcome.complete(true);
              } catch (Throwable failure) {
                outcome.completeExceptionally(failure);
              } finally {
                release.countDown();
              }
            });
    check(
        Continuations.supplier(() -> outcome.get(5, TimeUnit.SECONDS)).get(),
        "unmanaged callback was silently allowed to block");
    System.out.println("LIMIT: callbacks on unmanaged platform threads still reject pending get");
  }

  private static void interruptedCompletion() {
    final var release = new CountDownLatch(1);
    final var value =
        Continuations.supplier(
            () -> {
              release.await();
              return "hello";
            });
    MainRuntime.probeWorker(
        () -> {
          INITIAL_THREAD.interrupt();
          release.countDown();
          final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!value.isDone() && System.nanoTime() < deadline) Thread.onSpinWait();
          check(value.isDone(), "result did not complete");
        });
    check(value.get().equals("hello"), "completed result was lost to interruption");
    check(Thread.interrupted(), "completion race lost interruption status");
  }

  private static void uncaughtFailure() {
    final var ready = new CountDownLatch(1);
    MainRuntime.probeWorker(ready::countDown);
    Thread.ofPlatform()
        .name("background-lifetime-probe")
        .start(
            () -> {
              try {
                Thread.sleep(300);
                System.out.println("BACKGROUND_FINISHED");
              } catch (InterruptedException failure) {
                throw new AssertionError(failure);
              }
            });
    try {
      check(
          Continuations.supplier(
                  () -> {
                    ready.await();
                    return "hello";
                  })
              .get()
              .equals("hello"),
          "wrong value");
      throw new IllegalStateException("uncaught original failure");
    } finally {
      System.out.println("MAIN_FINALLY_FINISHED");
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
