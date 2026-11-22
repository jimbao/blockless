package automaticentry;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;

/** Moves unstructured descendants onto shared carriers after their owner's roots exit. */
final class RetiringScheduler implements Executor {
  private final Executor carriers;
  private volatile boolean retired;

  RetiringScheduler(Executor carriers) {
    this.carriers = carriers;
  }

  void retire() {
    retired = true;
  }

  @Override
  public void execute(Runnable task) {
    if (retired) {
      ForkJoinPool.commonPool().execute(task);
      return;
    }
    try {
      carriers.execute(task);
    } catch (RejectedExecutionException rejection) {
      // Retirement may race the initial check. A genuine rejection from an active owner
      // retains its normal failure behavior; only the closing-carrier race is redirected.
      if (!retired) throw rejection;
      ForkJoinPool.commonPool().execute(task);
    }
  }
}
