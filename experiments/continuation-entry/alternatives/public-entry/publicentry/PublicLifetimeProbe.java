package publicentry;

import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CountDownLatch;

/** The controller sends input only after main returns; roots are intentionally left pending. */
public final class PublicLifetimeProbe {
  public static void main(String[] args) throws Exception {
    final var started = new CountDownLatch(1);
    final var requests = PublicTaskExecutor.newExecutor();
    requests.execute(
        () -> {
          final var result =
              Blockless.supplier(
                  () -> {
                    started.countDown();
                    if (System.in.read() != 'x') throw new AssertionError("No release input");
                    return "finished";
                  });
          System.out.println("ROOT_" + result.get());
          requests.shutdown();
        });
    started.await();
    System.out.println("MAIN_RETURN");
  }
}
