package io.github.pjlabs.blockless.continuations;

import io.github.pjlabs.blockless.AsyncSupplier;
import io.github.pjlabs.blockless.Blockless;
import io.github.pjlabs.blockless.CallableContext;
import io.github.pjlabs.blockless.ContextPropagator;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/** Research only: no native waiter; pending reads still require a virtual-thread caller. */
public final class Continuations {
  private static final ThreadLocal<ArrayDeque<Runnable>> DRAINS = new ThreadLocal<>();

  private Continuations() {}

  private static void runDrains(Runnable task) {
    final var active = DRAINS.get();
    if (active != null) {
      active.addLast(task);
      return;
    }
    final var queue = new ArrayDeque<Runnable>();
    DRAINS.set(queue);
    try {
      queue.addLast(task);
      for (var next = queue.pollFirst(); next != null; next = queue.pollFirst()) next.run();
    } finally {
      DRAINS.remove();
    }
  }

  public static <T> AsyncSupplier<T> supplier(Callable<T> task, ContextPropagator... propagators) {
    return supplier(ForkJoinPool.commonPool(), task, propagators);
  }

  public static <T> AsyncSupplier<T> supplier(
      Executor executor, Callable<T> task, ContextPropagator... propagators) {
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(task, "task");
    final var runner = new Runner<T>(executor, task, propagators);
    runner.signal();
    return new ContinuationResult<>((AsyncSupplier<T>) Blockless.supplier(runner.completion));
  }

  private record ContinuationResult<T>(AsyncSupplier<T> result) implements AsyncSupplier<T> {
    @Override
    public T get() {
      if (!result.isDone() && !Thread.currentThread().isVirtual()) {
        throw new IllegalStateException(
            "Pending continuation results require a virtual-thread caller");
      }
      return result.get();
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public void onComplete(Runnable action) {
      result.onComplete(action);
    }
  }

  private static final class Runner<T> {
    private final Executor executor;
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private final AtomicInteger signals = new AtomicInteger();
    private final Runnable drain;
    private final Thread producer;
    private boolean started;
    private volatile boolean bodyDone;
    private T value;
    private Throwable bodyFailure;

    private Runner(Executor executor, Callable<T> task, ContextPropagator[] propagators) {
      this.executor = executor;
      final var work = CallableContext.wrap(task, propagators);
      // Capture the caller VT's scheduler now; starting later on a dispatch PT does not change it.
      producer =
          Thread.ofVirtual()
              .unstarted(
                  () -> {
                    try {
                      value = work.call();
                    } catch (Throwable failure) {
                      if (failure instanceof InterruptedException)
                        Thread.currentThread().interrupt();
                      bodyFailure = failure;
                    }
                    // Publish only after the user's finally blocks and context restoration have
                    // finished.
                    bodyDone = true;
                    signal();
                  });
      final var captured = CallableContext.wrap(this::advance, propagators);
      drain =
          () -> {
            try {
              if (captured.call()) {
                if (bodyFailure == null) completion.complete(value);
                else completion.completeExceptionally(bodyFailure);
              }
            } catch (Throwable failure) {
              completion.completeExceptionally(failure);
            }
          };
    }

    private void signal() {
      if (signals.getAndIncrement() != 0) return;
      try {
        executor.execute(() -> runDrains(drain));
      } catch (Throwable failure) {
        if (!bodyDone) {
          // Initial rejection: the unstarted producer has not acquired any body resources.
          completion.completeExceptionally(failure);
        } else {
          // The body, its finally blocks, and context restoration are already complete.
          runDrains(drain);
        }
      }
    }

    private boolean advance() {
      do {
        if (!started) {
          started = true;
          producer.start();
        }
        if (bodyDone) return true;
      } while (signals.decrementAndGet() != 0);
      return false;
    }
  }
}
