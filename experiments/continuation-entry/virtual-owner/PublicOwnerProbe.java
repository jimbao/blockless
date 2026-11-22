import io.github.pjlabs.blockless.continuations.Continuations;
import java.util.concurrent.TimeUnit;

/** Public factory control: deliberately run without the custom scheduler's java.lang opens. */
public final class PublicOwnerProbe {
  public static void main(String[] args) throws Exception {
    try (final var tasks = LogicalTaskExecutor.virtualThreads()) {
      final var result =
          tasks.submit(
              () -> {
                if (!Thread.currentThread().isVirtual())
                  throw new AssertionError("Caller is not virtual");
                final var user = Continuations.supplier(() -> "hello");
                return user.get();
              });
      if (!"hello".equals(result.get(5, TimeUnit.SECONDS))) throw new AssertionError("Wrong value");
    }
    System.out.println(
        "PASS: public logical-thread factory, actual supplier/get, no extra java.lang opens");
  }
}
