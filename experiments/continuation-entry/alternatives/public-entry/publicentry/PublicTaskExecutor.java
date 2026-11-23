package publicentry;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Public-API task entry for applications which already own the JVM's lifetime. */
public final class PublicTaskExecutor extends AbstractExecutorService {
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

  private PublicTaskExecutor() {}

  public static ExecutorService newExecutor() {
    return new PublicTaskExecutor();
  }

  @Override
  public void execute(Runnable command) {
    // Inherit submit: its FutureTask runs inside the delegate's TaskRunner. On Java 21 this
    // retains actual task-lifetime tracking after cancellation finishes the Future early.
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
}
