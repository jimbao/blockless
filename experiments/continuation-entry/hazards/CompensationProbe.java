import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CompensationProbe {
  public static void main(String[] args) throws Exception {
    var workers = new AtomicInteger();
    var originalWorker = new AtomicReference<Thread>();
    ForkJoinPool.ForkJoinWorkerThreadFactory factory =
        pool -> {
          workers.incrementAndGet();
          return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        };
    var input = new CompletableFuture<String>();
    var entered = new CountDownLatch(1);
    var result = new CompletableFuture<String>();
    try (var pool = new ForkJoinPool(1, factory, null, false)) {
      pool.execute(
          () -> {
            originalWorker.set(Thread.currentThread());
            entered.countDown();
            result.complete(input.join());
          });
      if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("entry timeout");
      var markerWorker = pool.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      if (workers.get() < 2 || markerWorker == originalWorker.get())
        throw new AssertionError("Expected compensation, not original-worker release");
      System.out.println("marker executed: true");
      System.out.println("platform workers created: " + workers.get());
      System.out.println("original worker state: " + originalWorker.get().getState());
      System.out.println("marker used original worker: " + (markerWorker == originalWorker.get()));
      input.complete("hello");
      if (!result.get(5, TimeUnit.SECONDS).equals("hello")) throw new AssertionError();
    } finally {
      input.complete("release");
    }
  }
}
