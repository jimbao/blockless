package experiment;

import io.github.pjlabs.blockless.AsyncSupplier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

/** Single-root research scheduler. It is not a general executor or context-isolation mechanism. */
public final class MainRuntime {
  private static final ContinuationScope SCOPE = new ContinuationScope("automatic-main-entry");
  private static final ThreadLocal<Flow> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<Flow> MAIN = new ThreadLocal<>();

  private MainRuntime() {}

  // The agent injects this around the original main body before its first invocation.
  public static void runMain(Runnable body) {
    if (CURRENT.get() != null) {
      body.run();
      return;
    }
    final var flow = new Flow(body, Thread.interrupted());
    MAIN.set(flow);
    try {
      flow.signal();
      while (!flow.done) {
        try {
          flow.commands.take().run();
        } catch (InterruptedException interrupted) {
          // Resume the suspended body so its get() and finally see the interruption.
          flow.interrupted = true;
          flow.signal();
        }
      }
      if (flow.failure != null) MainRuntime.<RuntimeException>rethrow(flow.failure);
    } finally {
      MAIN.remove();
    }
  }

  // Injected into the actual PR37 ContinuationResult.get(), before its pending-read guard.
  public static void awaitResult(AsyncSupplier<?> result) {
    final var flow = CURRENT.get();
    if (flow == null) {
      ManagedOwner.awaitResult(result);
      return;
    }
    while (true) {
      if (flow.interrupted || Thread.currentThread().isInterrupted()) {
        flow.interrupted = false;
        Thread.currentThread().interrupt();
        if (!result.isDone()) {
          throw new RuntimeException(new InterruptedException("Managed main interrupted"));
        }
      }
      if (result.isDone()) return;
      flow.pending = result;
      if (!Continuation.yield(SCOPE))
        throw new IllegalStateException("Caller continuation is pinned");
    }
  }

  /** Test harness only: queue a command on exactly the same platform worker. */
  public static void probeWorker(Runnable command) {
    final var flow = MAIN.get();
    if (flow == null) throw new IllegalStateException("Main has no automatic entry");
    flow.commands.add(
        () -> {
          try {
            command.run();
          } catch (Throwable failure) {
            // Do not discard the suspended caller and its finally when a harness command fails.
            flow.commandFailure = failure;
            flow.interrupted = true;
            flow.signal();
          }
        });
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> void rethrow(Throwable failure) throws E {
    throw (E) failure;
  }

  private static final class Flow {
    private final LinkedBlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final AtomicInteger signals = new AtomicInteger();
    private final Continuation continuation;
    private AsyncSupplier<?> pending;
    private boolean done;
    private boolean interrupted;
    private Throwable failure;
    private Throwable commandFailure;
    private boolean started;
    private final boolean initialInterrupt;

    private Flow(Runnable body, boolean initialInterrupt) {
      continuation = new Continuation(SCOPE, body);
      this.initialInterrupt = initialInterrupt;
    }

    private void signal() {
      if (signals.getAndIncrement() == 0) commands.add(this::drain);
    }

    private void drain() {
      CURRENT.set(this);
      try {
        if (!started) {
          started = true;
          if (initialInterrupt || interrupted) {
            interrupted = false;
            Thread.currentThread().interrupt();
          }
        }
        do {
          pending = null;
          continuation.run();
          if (continuation.isDone()) {
            failure = commandFailure;
            done = true;
            return;
          }
          if (pending == null) throw new IllegalStateException("Unexpected suspension");
          // Register only after the native stack has unwound, including for inline completion.
          pending.onComplete(this::signal);
        } while (signals.decrementAndGet() != 0);
      } catch (Throwable thrown) {
        failure = commandFailure == null ? thrown : commandFailure;
        done = true;
      } finally {
        CURRENT.remove();
      }
    }
  }
}
