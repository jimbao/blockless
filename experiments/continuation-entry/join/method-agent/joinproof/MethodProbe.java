package joinproof;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MethodProbe {
  private static final CountDownLatch bodyStarted = new CountDownLatch(1);
  private static final CountDownLatch releaseBody = new CountDownLatch(1);
  private static final AtomicInteger cleanups = new AtomicInteger();

  // Unchanged business source. Agent moves THIS method to VT and leaves caller signature String.
  private static String loadUser() {
    if (!Thread.currentThread().isVirtual())
      throw new AssertionError("Method agent did not run body on VT");
    try {
      var user =
          JoinSupplier.supplier(
              () -> {
                bodyStarted.countDown();
                releaseBody.await();
                return "hello";
              });
      return "saved:" + user.get();
    } finally {
      cleanups.incrementAndGet();
    }
  }

  public static void main(String[] args) throws Exception {
    try (var executor = Executors.newSingleThreadExecutor()) {
      try {
        var result = executor.submit(MethodProbe::loadUser);
        if (!bodyStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Body not entered");
        var marker = executor.submit(() -> "worker free");
        try {
          marker.get(200, TimeUnit.MILLISECONDS);
          throw new AssertionError("Unexpected free PT worker");
        } catch (TimeoutException expected) {
          System.out.println(
              "METHOD-ONLY: VT method/body joins park, but its PT caller still blocks awaiting"
                  + " String return");
        }
        if (cleanups.get() != 0) throw new AssertionError("Premature finally");
        releaseBody.countDown();
        if (!result.get(5, TimeUnit.SECONDS).equals("saved:hello"))
          throw new AssertionError("Lost value");
        if (!marker.get(5, TimeUnit.SECONDS).equals("worker free"))
          throw new AssertionError("Lost queued work");
        if (cleanups.get() != 1) throw new AssertionError("Lost or duplicated finally");
        System.out.println(
            "String return and finally preserved; platform-worker release requires caller/root"
                + " boundary");
      } finally {
        releaseBody.countDown();
      }
    }
  }
}
