import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Task ownership through public Java APIs; no custom scheduler or agent is required here. */
public final class LogicalTaskExecutor {
  private LogicalTaskExecutor() {}

  public static ExecutorService virtualThreads() {
    return using(Thread.ofVirtual().factory());
  }

  public static ExecutorService using(ThreadFactory factory) {
    final var threads = Executors.newThreadPerTaskExecutor(factory);
    // Inherit AbstractExecutorService.submit so task lifetime uses the delegate's TaskRunner,
    // including on Java 21 when a Future becomes cancelled before its finally has finished.
    return new AbstractExecutorService() {
      @Override
      public void execute(Runnable command) {
        threads.execute(command);
      }

      @Override
      public void shutdown() {
        threads.shutdown();
      }

      @Override
      public List<Runnable> shutdownNow() {
        return threads.shutdownNow();
      }

      @Override
      public boolean isShutdown() {
        return threads.isShutdown();
      }

      @Override
      public boolean isTerminated() {
        return threads.isTerminated();
      }

      @Override
      public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return threads.awaitTermination(timeout, unit);
      }
    };
  }
}
