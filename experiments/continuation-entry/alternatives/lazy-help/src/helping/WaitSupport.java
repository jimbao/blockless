package helping;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** Research wait hook. Normal callers, including helped virtual tasks, use the original latch. */
public final class WaitSupport {
  static final ThreadLocal<LazyHelpingExecutor> CURRENT = new ThreadLocal<>();
  private static final ConcurrentHashMap<CountDownLatch, CopyOnWriteArrayList<Registration>>
      WAITERS = new ConcurrentHashMap<>();

  private WaitSupport() {}

  static int registeredLatches() {
    return WAITERS.size();
  }

  public static void await(CountDownLatch latch) throws InterruptedException {
    final var owner = CURRENT.get();
    if (owner == null || Thread.currentThread().isVirtual()) {
      latch.await();
      return;
    }
    final var registration = new Registration(owner);
    WAITERS.compute(
        latch,
        (ignored, registrations) -> {
          final var result =
              registrations == null ? new CopyOnWriteArrayList<Registration>() : registrations;
          result.add(registration);
          return result;
        });
    try {
      owner.helpUntil(latch);
    } finally {
      WAITERS.computeIfPresent(
          latch,
          (ignored, registrations) -> {
            registrations.remove(registration);
            return registrations.isEmpty() ? null : registrations;
          });
    }
  }

  public static void signal(CountDownLatch latch) {
    final var registrations = WAITERS.get(latch);
    if (registrations != null) {
      for (final var registration : registrations) registration.owner.signalProgress();
    }
  }

  private record Registration(LazyHelpingExecutor owner) {}
}
