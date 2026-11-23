package portablecps;

import org.apache.commons.javaflow.api.Continuation;
import org.apache.commons.javaflow.api.continuable;

/** Isolates the portable library's basic stack saving from Supplier integration. */
public final class BasicWeaveProbe {
  public static void main(String[] args) {
    final var work = new Work();
    final var suspended = Continuation.startWith(work, true);
    if (suspended == null || work.result != null || work.cleanups != 0) {
      throw new AssertionError("Did not suspend with finally pending");
    }
    if (suspended.resume() != null || !"value:42".equals(work.result) || work.cleanups != 1) {
      throw new AssertionError("Did not restore locals and finally");
    }
    System.out.println("PASS basic portable CPS: actual T/local/finally saved on platform thread");
  }

  public static final class Work implements Runnable {
    String result;
    int cleanups;

    @Override
    @continuable
    public void run() {
      final var id = 42;
      try {
        Continuation.suspend("pending");
        result = "value:" + id;
      } finally {
        cleanups++;
      }
    }
  }
}
