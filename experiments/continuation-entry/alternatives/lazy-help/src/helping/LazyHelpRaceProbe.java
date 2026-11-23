package helping;

import io.github.pjlabs.blockless.Blockless;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Completion/registration/cancellation churn, separate from any throughput measurement. */
public final class LazyHelpRaceProbe {
  public static void main(String[] args) throws Exception {
    final var started = new AtomicInteger();
    final var cleaned = new AtomicInteger();
    final var futures = new ArrayList<Future<Integer>>();
    try (final var owner = new LazyHelpingExecutor(4, Thread.ofPlatform().factory())) {
      for (int index = 0; index < 2000; index++) {
        final var expected = index;
        final var result =
            owner.submit(
                () -> {
                  started.incrementAndGet();
                  try {
                    final var value =
                        Blockless.supplier(
                            () -> {
                              Thread.yield();
                              return expected;
                            });
                    return value.get();
                  } finally {
                    cleaned.incrementAndGet();
                  }
                });
        futures.add(result);
        if (index % 7 == 0) result.cancel(true);
      }
      owner.shutdown();
      for (int index = 0; index < futures.size(); index++) {
        try {
          if (futures.get(index).get(10, TimeUnit.SECONDS) != index)
            throw new AssertionError("Wrong value");
        } catch (CancellationException expectedCancellation) {
        }
      }
      if (!owner.awaitTermination(10, TimeUnit.SECONDS))
        throw new AssertionError("Churn owner not terminated");
      if (started.get() != cleaned.get()) throw new AssertionError("Root cleanup skipped");
      if (WaitSupport.registeredLatches() != 0)
        throw new AssertionError("Wait registration leaked");
    }
    System.out.println(
        "PASS: 2,000 immediate supplier/get races with cancellation; all actual root cleanup"
            + " joined; no wait registration retained");
  }
}
