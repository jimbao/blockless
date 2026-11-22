import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Controller supplies input only after original main returns. */
public class PublicSchedulerLifetimeProbe {
  public static void main(String[] args) throws Exception {
    var owner =
        args[0].equals("platform")
            ? Executors.newFixedThreadPool(1)
            : Executors.newVirtualThreadPerTaskExecutor();
    var entered = new CountDownLatch(1);
    owner.execute(
        () -> {
          entered.countDown();
          try {
            if (System.in.read() != 'x')
              throw new AssertionError("Controller did not release root");
            System.out.println("ROOT_FINISHED");
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          } finally {
            owner.shutdown();
          }
        });
    entered.await();
    System.out.println("MAIN_RETURN");
  }
}
