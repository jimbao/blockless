import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

public class ManagedContinuationProbe {
  static final ContinuationScope SCOPE = new ContinuationScope("managed-caller");
  static final ThreadLocal<Flow> ACTIVE = new ThreadLocal<>();

  static final class Value<T> implements Supplier<T> {
    final CompletableFuture<T> result = new CompletableFuture<>();

    Value(Callable<T> work) {
      Thread.startVirtualThread(
          () -> {
            try {
              result.complete(work.call());
            } catch (Throwable failure) {
              result.completeExceptionally(failure);
            }
          });
    }

    public T get() {
      while (!result.isDone()) {
        Flow flow = ACTIVE.get();
        if (flow == null) throw new IllegalStateException("Caller has no continuation entry");
        flow.pending = result;
        Continuation.yield(SCOPE);
      }
      return result.join();
    }
  }

  static final class Flow {
    final Executor executor;
    final Continuation continuation;
    final CompletableFuture<Void> finished = new CompletableFuture<>();
    CompletableFuture<?> pending;

    Flow(Executor executor, Runnable task) {
      this.executor = executor;
      continuation = new Continuation(SCOPE, task);
    }

    void start() {
      executor.execute(this::advance);
    }

    void advance() {
      ACTIVE.set(this);
      try {
        continuation.run();
        if (continuation.isDone()) finished.complete(null);
        else pending.whenComplete((value, failure) -> executor.execute(this::advance));
      } catch (Throwable failure) {
        finished.completeExceptionally(failure);
      } finally {
        ACTIVE.remove();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var bodyCalls = new AtomicInteger();
    var finallyCalls = new AtomicInteger();
    var events = new ArrayList<String>();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var flow =
          new Flow(
              worker,
              () -> {
                if (Thread.currentThread().isVirtual())
                  throw new AssertionError("Expected platform flow");
                int local = 37;
                try {
                  var user =
                      new Value<>(
                          () -> {
                            bodyCalls.incrementAndGet();
                            started.countDown();
                            release.await();
                            return "hello";
                          });
                  events.add("before get");
                  String value = user.get();
                  if (!value.equals("hello") || local != 37)
                    throw new AssertionError("Bad restored state");
                  events.add("after get: " + value + ", local=" + local);
                } finally {
                  finallyCalls.incrementAndGet();
                }
              });
      flow.start();
      if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Body did not start");
      worker
          .submit(() -> events.add("same worker handles unrelated work"))
          .get(5, TimeUnit.SECONDS);
      if (flow.finished.isDone()) throw new AssertionError("Flow completed before body");
      if (finallyCalls.get() != 0) throw new AssertionError("Finally executed on suspension");
      release.countDown();
      flow.finished.get(5, TimeUnit.SECONDS);
      if (bodyCalls.get() != 1 || finallyCalls.get() != 1)
        throw new AssertionError("Body/finally replayed");
      if (!events.equals(
          List.of(
              "before get", "same worker handles unrelated work", "after get: hello, local=37"))) {
        throw new AssertionError(events);
      }
      events.forEach(System.out::println);
      System.out.println("bodyCalls=" + bodyCalls.get() + ", finallyCalls=" + finallyCalls.get());
    } finally {
      release.countDown();
    }
    try {
      new Continuation(SCOPE, () -> {}).tryPreempt(Thread.currentThread());
      throw new AssertionError("Unexpected preemption support");
    } catch (UnsupportedOperationException expected) {
      System.out.println("tryPreempt: " + expected.getMessage());
    }
  }
}
