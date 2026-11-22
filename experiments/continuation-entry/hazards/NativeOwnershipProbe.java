import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

public class NativeOwnershipProbe {
  public static void main(String[] args) {
    var scope = new ContinuationScope("main-flow");
    var queue = new LinkedBlockingQueue<Runnable>();
    var context = new ThreadLocal<String>();
    var lock = new ReentrantLock();
    var flow =
        new Continuation(
            scope,
            () -> {
              context.set("suspended-flow");
              lock.lock();
              try {
                queue.add(
                    () -> {
                      if (!"suspended-flow".equals(context.get()))
                        throw new AssertionError("Context not shared");
                      if (!lock.isHeldByCurrentThread())
                        throw new AssertionError("Lock ownership not shared");
                      System.out.println("Unrelated task sees flow context: " + context.get());
                      System.out.println(
                          "Unrelated task is treated as lock owner: "
                              + lock.isHeldByCurrentThread());
                      if (!lock.tryLock())
                        throw new AssertionError("Expected same-thread reentrant acquisition");
                      try {
                        context.set("unrelated-task");
                      } finally {
                        lock.unlock();
                      }
                    });
                Continuation.yield(scope);
                if (!"unrelated-task".equals(context.get()))
                  throw new AssertionError("Expected leaked mutation");
                System.out.println("Resumed flow sees context: " + context.get());
              } finally {
                lock.unlock();
              }
            });
    flow.run();
    queue.remove().run();
    flow.run();
    if (!flow.isDone()) throw new AssertionError("Not completed");
  }
}
