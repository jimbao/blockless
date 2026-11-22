package unselected;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExternalFactories {
  public static ExecutorService fixed() {
    return Executors.newFixedThreadPool(1);
  }
}
