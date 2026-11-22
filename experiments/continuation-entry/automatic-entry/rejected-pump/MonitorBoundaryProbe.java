import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Checks whether mounting B as a VT isolates monitors held by the raw host A. */
public class MonitorBoundaryProbe {
  public static void main(String[] args) throws Exception {
    var queue = new LinkedBlockingQueue<Runnable>();
    var constructor =
        Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder")
            .getDeclaredConstructor(Executor.class);
    constructor.setAccessible(true);
    var builder = (Thread.Builder.OfVirtual) constructor.newInstance((Executor) queue::add);
    var monitor = new Object();
    var hostHolding = new AtomicBoolean();
    var bEnteredWhileHostHolding = new AtomicBoolean();
    var child =
        builder.unstarted(
            () -> {
              synchronized (monitor) {
                bEnteredWhileHostHolding.set(hostHolding.get());
              }
            });
    synchronized (monitor) {
      hostHolding.set(true);
      child.start();
      queue.remove().run();
      System.out.println(
          "While raw A holds monitor: B finished="
              + !child.isAlive()
              + "; B entered A critical section="
              + bEnteredWhileHostHolding.get());
      hostHolding.set(false);
    }
    while (child.isAlive()) {
      var resume = queue.poll(5, TimeUnit.SECONDS);
      if (resume == null) throw new AssertionError("B did not resume");
      resume.run();
    }
    System.out.println("Final forbidden overlap=" + bEnteredWhileHostHolding.get());
    if (!bEnteredWhileHostHolding.get())
      throw new AssertionError("Monitor hazard no longer reproduced; review this JDK");
  }
}
