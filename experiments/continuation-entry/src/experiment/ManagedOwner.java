package experiment;

import io.github.pjlabs.blockless.AsyncSupplier;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

/**
 * Research proof only: logical jobs own native continuations; carriers are borrowed. Cancellation
 * is deliberately declined, and shutdownNow is unsupported. This avoids FutureTask.cancel(true)
 * interrupting an unrelated job using a suspended task's carrier. ThreadLocal/interrupt isolation
 * across native jobs is NOT implemented by this proof.
 */
public final class ManagedOwner extends AbstractExecutorService {
  private static final ContinuationScope SCOPE = new ContinuationScope("managed-proof");
  private static final ThreadLocal<Flow> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<ArrayDeque<Runnable>> DRAINS = new ThreadLocal<>();

  private final Executor carriers;
  private final Consumer<Throwable> uncaught;
  private final Runnable afterLogicalTask;
  private final ReentrantLock lifecycle = new ReentrantLock();
  private final Condition terminated = lifecycle.newCondition();
  private final Set<Flow> active = new HashSet<>();
  private boolean shutdown;

  public ManagedOwner(Executor carriers, Consumer<Throwable> uncaught, Runnable afterLogicalTask) {
    this.carriers = Objects.requireNonNull(carriers);
    this.uncaught = Objects.requireNonNull(uncaught);
    this.afterLogicalTask = Objects.requireNonNull(afterLogicalTask);
  }

  @Override
  public void execute(Runnable command) {
    Objects.requireNonNull(command);
    if (command instanceof RunnableFuture<?> && !(command instanceof NonCancellingFutureTask<?>)) {
      throw new IllegalArgumentException("foreign cancellable RunnableFuture is unsupported");
    }
    var flow = new Flow(this, command);
    lifecycle.lock();
    try {
      if (shutdown) throw new RejectedExecutionException("logical executor shut down");
      active.add(flow);
    } finally {
      lifecycle.unlock();
    }
    flow.signal();
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Callable<T> task) {
    return new NonCancellingFutureTask<>(task);
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Runnable task, T result) {
    return new NonCancellingFutureTask<>(task, result);
  }

  @Override
  public void shutdown() {
    lifecycle.lock();
    try {
      shutdown = true;
      if (active.isEmpty()) terminated.signalAll();
    } finally {
      lifecycle.unlock();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    throw new UnsupportedOperationException(
        "native task cancellation is outside this research proof");
  }

  @Override
  public boolean isShutdown() {
    lifecycle.lock();
    try {
      return shutdown;
    } finally {
      lifecycle.unlock();
    }
  }

  @Override
  public boolean isTerminated() {
    lifecycle.lock();
    try {
      return shutdown && active.isEmpty();
    } finally {
      lifecycle.unlock();
    }
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    var remaining = unit.toNanos(timeout);
    lifecycle.lockInterruptibly();
    try {
      while (!shutdown || !active.isEmpty()) {
        if (remaining <= 0) return false;
        remaining = terminated.awaitNanos(remaining);
      }
      return true;
    } finally {
      lifecycle.unlock();
    }
  }

  private void finished(Flow flow) {
    try {
      afterLogicalTask.run();
    } catch (Throwable callbackFailure) {
      uncaught.accept(callbackFailure);
    } finally {
      lifecycle.lock();
      try {
        active.remove(flow);
        if (shutdown && active.isEmpty()) terminated.signalAll();
      } finally {
        lifecycle.unlock();
      }
    }
  }

  /** Called by the agent's production-API get hook; no-op outside this owner. */
  public static void awaitResult(AsyncSupplier<?> result) {
    var flow = CURRENT.get();
    if (flow == null) return;
    while (!result.isDone()) {
      flow.pending = result;
      if (!Continuation.yield(SCOPE)) throw new IllegalStateException("native caller pinned");
    }
  }

  private static void runDrains(Runnable command) {
    var queue = DRAINS.get();
    if (queue != null) {
      queue.addLast(command);
      return;
    }
    queue = new ArrayDeque<>();
    DRAINS.set(queue);
    try {
      queue.addLast(command);
      for (var next = queue.pollFirst(); next != null; next = queue.pollFirst()) next.run();
    } finally {
      DRAINS.remove();
    }
  }

  private static final class Flow {
    final ManagedOwner owner;
    final Continuation continuation;
    final AtomicInteger signals = new AtomicInteger();
    AsyncSupplier<?> pending;
    volatile boolean started;
    volatile boolean done;

    Flow(ManagedOwner owner, Runnable body) {
      this.owner = owner;
      continuation = new Continuation(SCOPE, body);
    }

    void signal() {
      if (done || signals.getAndIncrement() != 0) return;
      try {
        owner.carriers.execute(() -> runDrains(this::drain));
      } catch (RejectedExecutionException rejected) {
        if (!started) {
          done = true;
          owner.finished(this);
          throw rejected;
        }
        // Borrowed carriers may close independently. Existing logical jobs still own their finally.
        Thread.ofVirtual()
            .inheritInheritableThreadLocals(false)
            .start(() -> runDrains(this::drain));
      }
    }

    void drain() {
      started = true;
      try {
        do {
          var previous = CURRENT.get();
          CURRENT.set(this);
          pending = null;
          try {
            continuation.run();
          } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
          }
          if (continuation.isDone()) {
            finish();
            return;
          }
          if (pending == null) throw new IllegalStateException("unexpected owner suspension");
          // Completion can happen inline: register only after the caller stack is unmounted.
          pending.onComplete(this::signal);
        } while (signals.decrementAndGet() != 0);
      } catch (Throwable failure) {
        try {
          owner.uncaught.accept(failure);
        } finally {
          finish();
        }
      }
    }

    void finish() {
      if (done) return;
      done = true;
      owner.finished(this);
    }
  }

  private static final class NonCancellingFutureTask<T> extends FutureTask<T> {
    NonCancellingFutureTask(Callable<T> body) {
      super(body);
    }

    NonCancellingFutureTask(Runnable body, T result) {
      super(body, result);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }
  }
}
