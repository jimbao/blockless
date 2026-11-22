package packaging.probe;

import java.util.concurrent.Executors;

public final class NamedEntry {
  public static void main(String[] args) throws Exception {
    try (final var executor = Executors.newCachedThreadPool()) {
      if (!executor.submit(() -> Thread.currentThread().isVirtual()).get()) {
        throw new AssertionError("Named module's factory was not transformed");
      }
      final var runtime = Class.forName("automaticentry.EntryRuntime", false, null);
      if (!NamedEntry.class.getModule().canRead(runtime.getModule())) {
        throw new AssertionError("Instrumentation did not add the helper read edge");
      }
    }
    System.out.println("PASS named module reads bootstrap runtime without add-reads flags");
  }
}
