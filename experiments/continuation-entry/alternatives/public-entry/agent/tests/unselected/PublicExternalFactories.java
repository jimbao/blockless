package unselected;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PublicExternalFactories {
  public static ExecutorService fixed() {
    return Executors.newFixedThreadPool(1);
  }
}
