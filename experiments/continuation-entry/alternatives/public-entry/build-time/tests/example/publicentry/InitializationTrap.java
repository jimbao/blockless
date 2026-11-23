package example.publicentry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Selected bytecode must be transformed without running this static initializer. */
public final class InitializationTrap {
  static {
    if (Boolean.parseBoolean("true")) throw new AssertionError("Application class was initialized");
  }

  public static ExecutorService executor() {
    return Executors.newFixedThreadPool(1);
  }
}
