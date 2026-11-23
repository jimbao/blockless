package compensation;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/** Research adapter: declares each complete task managed-blocking before running it. */
public final class CompensatingTaskExecutor extends AbstractExecutorService {
  private final ForkJoinPool workers;
  private final boolean managedWholeTask;

  public CompensatingTaskExecutor(ForkJoinPool workers) {
    this(workers, true);
  }

  CompensatingTaskExecutor(ForkJoinPool workers, boolean managedWholeTask) {
    this.workers = workers;
    this.managedWholeTask = managedWholeTask;
  }

  public static CompensatingTaskExecutor newExecutor(int parallelism, int maximumWorkers) {
    return new CompensatingTaskExecutor(
        newPool(parallelism, maximumWorkers, ForkJoinPool.defaultForkJoinWorkerThreadFactory));
  }

  public static ForkJoinPool newPool(
      int parallelism, int maximumWorkers, ForkJoinPool.ForkJoinWorkerThreadFactory factory) {
    return new ForkJoinPool(
        parallelism,
        factory,
        null,
        true,
        parallelism,
        maximumWorkers,
        1,
        pool -> true,
        60,
        TimeUnit.SECONDS);
  }

  @Override
  public void execute(Runnable command) {
    workers.execute(
        () -> {
          // FutureTask cancellation interrupts this platform worker. Clear its flag at task
          // boundaries, as an ordinary thread-pool worker does, without hiding it inside the task.
          Thread.interrupted();
          try {
            if (managedWholeTask) ForkJoinPool.managedBlock(new TaskBlocker(command));
            else command.run();
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(failure);
          } finally {
            Thread.interrupted();
          }
        });
  }

  @Override
  public void shutdown() {
    workers.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return workers.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return workers.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return workers.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return workers.awaitTermination(timeout, unit);
  }

  private static final class TaskBlocker implements ForkJoinPool.ManagedBlocker {
    private final Runnable command;
    private boolean done;

    private TaskBlocker(Runnable command) {
      this.command = command;
    }

    @Override
    public boolean isReleasable() {
      return done;
    }

    @Override
    public boolean block() {
      if (!done) {
        try {
          command.run();
        } finally {
          done = true;
        }
      }
      return true;
    }
  }
}
