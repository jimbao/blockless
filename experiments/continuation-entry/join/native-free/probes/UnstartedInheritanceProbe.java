import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** JDK-only proof: construction captures parent VT scheduler, inheritable locals and CCL. */
public class UnstartedInheritanceProbe {
  public static void main(String[] args) throws Exception {
    var inherited = new InheritableThreadLocal<String>();
    var ordinary = new ThreadLocal<String>();
    var bodyCalls = new AtomicInteger();
    var bodyResult = new CompletableFuture<String>();
    var sourceLoader = new ClassLoader() {};
    var starterLoader = new ClassLoader() {};
    try (var carriers =
            Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("construction-carrier").factory());
        var parents = BorrowedCarrierThreads.newLogicalExecutor(carriers)) {
      var physicalWorker = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      var producer =
          parents
              .submit(
                  () -> {
                    inherited.set("construction-time");
                    ordinary.set("not-inherited");
                    Thread.currentThread().setContextClassLoader(sourceLoader);
                    var child =
                        Thread.ofVirtual()
                            .unstarted(
                                () -> {
                                  try {
                                    bodyCalls.incrementAndGet();
                                    require(
                                        BorrowedCarrierThreads.currentCarrier() == physicalWorker,
                                        "Child did not retain parent scheduler after being started"
                                            + " by a platform thread");
                                    require(
                                        "construction-time".equals(inherited.get()),
                                        "Inheritable local captured at wrong time");
                                    require(
                                        ordinary.get() == null,
                                        "Ordinary ThreadLocal unexpectedly inherited");
                                    require(
                                        Thread.currentThread().getContextClassLoader()
                                            == sourceLoader,
                                        "Context class loader captured at wrong time");
                                    bodyResult.complete("hello");
                                  } catch (Throwable failure) {
                                    bodyResult.completeExceptionally(failure);
                                  }
                                });
                    inherited.set("parent-mutated-after-construction");
                    Thread.currentThread().setContextClassLoader(starterLoader);
                    return child;
                  })
              .get(5, TimeUnit.SECONDS);
      require(
          producer.getState() == Thread.State.NEW && bodyCalls.get() == 0,
          "Producer started before dispatch accepted it");
      require(!Thread.currentThread().isVirtual(), "Starter must be a platform thread");
      inherited.set("platform-starter-context");
      var oldLoader = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(starterLoader);
        producer.start();
      } finally {
        Thread.currentThread().setContextClassLoader(oldLoader);
      }
      require("hello".equals(bodyResult.get(5, TimeUnit.SECONDS)), "Producer failed");
      producer.join(5_000);
      require(!producer.isAlive() && bodyCalls.get() == 1, "Producer did not finish once");

      var rejectedProducer =
          parents
              .submit(() -> Thread.ofVirtual().unstarted(bodyCalls::incrementAndGet))
              .get(5, TimeUnit.SECONDS);
      try {
        reject(rejectedProducer::start);
        throw new AssertionError("Expected rejection");
      } catch (RejectedExecutionException expected) {
      }
      require(
          rejectedProducer.getState() == Thread.State.NEW && bodyCalls.get() == 1,
          "Rejected dispatch started the producer body");
    } finally {
      inherited.remove();
      ordinary.remove();
    }
    System.out.println(
        "PASS: deferred PT start retains constructing VT's single-carrier scheduler, ITL and CCL;"
            + " ordinary TLS is separate; rejected producer stays NEW");
  }

  private static void reject(Runnable command) {
    throw new RejectedExecutionException("closed dispatch");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
