import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ChildSchedulerInheritanceProbe {
  public static void main(String[] args) throws Exception {
    var getter =
        MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
            .findStatic(Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
    var physical = new AtomicReference<Thread>();
    var childPhysical = new AtomicReference<Thread>();
    var parentPhysical = new AtomicReference<Thread>();
    var workersCreated = new AtomicInteger();
    var releaseChild = new CountDownLatch(1);
    var enteringJoin = new CountDownLatch(1);
    var completed = new CompletableFuture<String>();
    try (var carriers =
        Executors.newSingleThreadExecutor(
            command -> {
              workersCreated.incrementAndGet();
              var worker = Thread.ofPlatform().name("inherited-carrier").unstarted(command);
              physical.set(worker);
              return worker;
            })) {
      var constructor =
          Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder")
              .getDeclaredConstructor(Executor.class);
      constructor.setAccessible(true);
      var builder = (Thread.Builder.OfVirtual) constructor.newInstance(carriers);
      var parent =
          builder.unstarted(
              () -> {
                try {
                  parentPhysical.set((Thread) getter.invokeExact());
                  // This child uses only the PUBLIC API, no explicit executor or scheduler.
                  var child =
                      Thread.startVirtualThread(
                          () -> {
                            try {
                              childPhysical.set((Thread) getter.invokeExact());
                              releaseChild.await();
                            } catch (Throwable failure) {
                              completed.completeExceptionally(failure);
                            }
                          });
                  enteringJoin.countDown();
                  child.join();
                  completed.complete("hello");
                } catch (Throwable failure) {
                  completed.completeExceptionally(failure);
                }
              });
      parent.start();
      try {
        if (!enteringJoin.await(5, TimeUnit.SECONDS)) throw new AssertionError("parent missing");
        var marker = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
        if (marker != physical.get()
            || parentPhysical.get() != physical.get()
            || childPhysical.get() != physical.get()
            || workersCreated.get() != 1)
          throw new AssertionError("child did not inherit same sole scheduler carrier");
        if (completed.isDone()) throw new AssertionError("child finished before marker");
        releaseChild.countDown();
        if (!completed.get(5, TimeUnit.SECONDS).equals("hello"))
          throw new AssertionError("bad result");
        System.out.println(
            "PASS public Thread.startVirtualThread child inherits parent's custom scheduler; same"
                + " sole carrier reusable during join");
      } finally {
        releaseChild.countDown();
        parent.join();
      }
    }
  }
}
