package publicentry;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * Explicit agent targets: pool sizes and physical factories do not control the shared JDK carrier
 * scheduler. These methods intentionally change the selected factories' execution model.
 */
public final class EntryRuntime {
  private EntryRuntime() {}

  public static ExecutorService newFixedThreadPool(int count) {
    if (count <= 0) throw new IllegalArgumentException("Pool size must be positive");
    return PublicTaskExecutor.newExecutor();
  }

  public static ExecutorService newFixedThreadPool(int count, ThreadFactory factory) {
    Objects.requireNonNull(factory, "factory");
    return newFixedThreadPool(count);
  }

  public static ExecutorService newCachedThreadPool() {
    return PublicTaskExecutor.newExecutor();
  }

  public static ExecutorService newCachedThreadPool(ThreadFactory factory) {
    Objects.requireNonNull(factory, "factory");
    return newCachedThreadPool();
  }
}
