package automaticentry;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns logical roots and their private carrier pool. Independently spawned threads are not roots.
 */
final class OwnedTaskExecutor extends AbstractExecutorService {
  private final ExecutorService roots;
  private final ExecutorService carriers;
  private final RetiringScheduler scheduler;
  private final ThreadFactory closers;
  private final AtomicBoolean closerStarted = new AtomicBoolean();

  OwnedTaskExecutor(
      ExecutorService roots,
      ExecutorService carriers,
      RetiringScheduler scheduler,
      ThreadFactory closers) {
    this.roots = roots;
    this.carriers = carriers;
    this.scheduler = scheduler;
    this.closers = closers;
  }

  @Override
  public void execute(Runnable task) {
    // Inherit AbstractExecutorService.submit: its FutureTask runs inside roots.execute's
    // TaskRunner, which tracks actual exit even on JDK 21 when Future.cancel finishes early.
    roots.execute(task);
  }

  @Override
  public void shutdown() {
    roots.shutdown();
    startCloser();
  }

  @Override
  public List<Runnable> shutdownNow() {
    try {
      // Interrupt logical roots, never an unrelated task on their shared carrier.
      return roots.shutdownNow();
    } finally {
      startCloser();
    }
  }

  @Override
  public boolean isShutdown() {
    return roots.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return roots.isTerminated() && carriers.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    final var nanos = unit.toNanos(timeout);
    final var start = System.nanoTime();
    if (!roots.awaitTermination(nanos, TimeUnit.NANOSECONDS)) return false;
    closeCarriers();
    final var remaining = nanos > 0 ? Math.max(0, nanos - (System.nanoTime() - start)) : 0;
    return carriers.awaitTermination(remaining, TimeUnit.NANOSECONDS);
  }

  private void startCloser() {
    if (roots.isTerminated()) {
      closeCarriers();
      return;
    }
    if (!closerStarted.compareAndSet(false, true)) return;
    try {
      closers.newThread(this::finishShutdown).start();
    } catch (RuntimeException | Error failure) {
      closerStarted.set(false);
      throw failure;
    }
  }

  private void finishShutdown() {
    var interrupted = false;
    while (true) {
      try {
        if (roots.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    // Do not await carrier termination here: this closer must be able to return from its slice.
    closeCarriers();
    if (interrupted) Thread.currentThread().interrupt();
  }

  private void closeCarriers() {
    // Unstructured descendants may retain this scheduler after their submitting root exits.
    // Retire before shutting down so their later resumption uses a live platform carrier.
    scheduler.retire();
    carriers.shutdown();
  }
}
