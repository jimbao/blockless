package admission;

import java.util.concurrent.ExecutorService;

/** Fixes a one-worker/16-maximum resource configuration for the compensation counterexample. */
public final class CompensationFactory {
  public static ExecutorService newExecutor() throws Exception {
    return (ExecutorService)
        Class.forName("compensation.CompensatingTaskExecutor")
            .getMethod("newExecutor", int.class, int.class)
            .invoke(null, 1, 16);
  }
}
