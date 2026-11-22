import java.util.concurrent.CountDownLatch;

public class Target {
  public static final CountDownLatch entered = new CountDownLatch(1);
  public static final CountDownLatch release = new CountDownLatch(1);

  public static String awaitValue() throws InterruptedException {
    entered.countDown();
    release.await();
    return "original active method";
  }
}
