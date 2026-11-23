package example.moduleentry;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class NamedPublicEntry {
  public static void main(String[] args) throws Exception {
    try (final var requests = Executors.newFixedThreadPool(1)) {
      final var value =
          requests
              .submit(
                  () -> {
                    if (!Thread.currentThread().isVirtual())
                      throw new AssertionError("Missing automatic entry");
                    final var user = Blockless.supplier(() -> "named actual user");
                    return user.get();
                  })
              .get(5, TimeUnit.SECONDS);
      if (!"named actual user".equals(value)) throw new AssertionError("Wrong result");
    }
    System.out.println("PASS named-module entry without add-reads or opens");
  }
}
