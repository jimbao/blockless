import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

public final class JoinOwnershipProbe {
  private static final ContinuationScope VT_SCOPE;
  private static final Field VT_CONTINUATION;
  private static final MethodHandle CURRENT_CARRIER;

  static {
    try {
      var virtualType = Class.forName("java.lang.VirtualThread");
      var scope = virtualType.getDeclaredField("VTHREAD_SCOPE");
      scope.setAccessible(true);
      VT_SCOPE = (ContinuationScope) scope.get(null);
      VT_CONTINUATION = virtualType.getDeclaredField("cont");
      VT_CONTINUATION.setAccessible(true);
      CURRENT_CARRIER =
          MethodHandles.privateLookupIn(Thread.class, MethodHandles.lookup())
              .findStatic(
                  Thread.class, "currentCarrierThread", MethodType.methodType(Thread.class));
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  public static void main(String[] args) throws Exception {
    rawPlatformCaller();
    virtualCallerOnOneCarrier();
  }

  private static void rawPlatformCaller() throws Exception {
    var releaseChild = new CountDownLatch(1);
    var enteringJoin = new CountDownLatch(1);
    var callerThread = new AtomicReference<Thread>();
    var child = Thread.startVirtualThread(() -> await(releaseChild));
    try (var worker = Executors.newSingleThreadExecutor()) {
      var caller =
          worker.submit(
              () -> {
                callerThread.set(Thread.currentThread());
                check(
                    currentContinuation() == null, "raw caller unexpectedly has a VT continuation");
                check(continuationOf(child) != null, "child has no continuation");
                enteringJoin.countDown();
                child.join();
                return "hello";
              });
      check(enteringJoin.await(5, TimeUnit.SECONDS), "raw caller did not start");
      var marker = worker.submit(Thread::currentThread);
      waitFor(() -> callerThread.get().getState() == Thread.State.WAITING);
      check(!marker.isDone(), "raw join unexpectedly released single worker");
      var joinFrameFound =
          java.util.Arrays.stream(callerThread.get().getStackTrace())
              .anyMatch(
                  frame ->
                      frame.getClassName().equals("java.lang.VirtualThread")
                          && frame.getMethodName().equals("joinNanos"));
      check(joinFrameFound, "raw caller is not waiting in actual VT join");
      System.out.println(
          "RAW PT: target has continuation; caller has none; original worker waits in joinNanos;"
              + " marker cannot run");
      releaseChild.countDown();
      check(caller.get(5, TimeUnit.SECONDS).equals("hello"), "wrong raw result");
      check(marker.get(5, TimeUnit.SECONDS) == callerThread.get(), "raw worker replaced");
    } finally {
      releaseChild.countDown();
      child.join();
    }
  }

  private static void virtualCallerOnOneCarrier() throws Exception {
    var workersCreated = new AtomicInteger();
    var carrierIdentity = new AtomicReference<Thread>();
    var releaseChild = new CountDownLatch(1);
    var childStarted = new CountDownLatch(1);
    var enteringJoin = new CountDownLatch(1);
    var callerContinuation = new AtomicReference<Continuation>();
    var childContinuation = new AtomicReference<Continuation>();
    var logicalResult = new CompletableFuture<String>();
    var context = new ThreadLocal<String>();
    try (var carriers =
        Executors.newSingleThreadExecutor(
            command -> {
              workersCreated.incrementAndGet();
              var worker = Thread.ofPlatform().name("only-physical-carrier").unstarted(command);
              carrierIdentity.set(worker);
              return worker;
            })) {
      var factory =
          virtualBuilder(carriers)
              .name("logical-", 0)
              .inheritInheritableThreadLocals(false)
              .factory();
      var child =
          factory.newThread(
              () -> {
                childContinuation.set(currentContinuation());
                context.set("child context");
                childStarted.countDown();
                await(releaseChild);
              });
      var caller =
          factory.newThread(
              () -> {
                try {
                  var logicalIdentity = Thread.currentThread();
                  callerContinuation.set(currentContinuation());
                  check(
                      callerContinuation.get() == continuationOf(logicalIdentity),
                      "wrong caller continuation");
                  context.set("caller context");
                  check(currentCarrier() == carrierIdentity.get(), "wrong carrier before join");
                  enteringJoin.countDown();
                  child.join();
                  check(
                      Thread.currentThread() == logicalIdentity, "logical caller identity changed");
                  check(context.get().equals("caller context"), "caller context changed");
                  check(currentCarrier() == carrierIdentity.get(), "wrong carrier after join");
                  logicalResult.complete("hello");
                } catch (Throwable failure) {
                  logicalResult.completeExceptionally(failure);
                }
              });
      child.start();
      check(childStarted.await(5, TimeUnit.SECONDS), "child did not start");
      caller.start();
      try {
        check(enteringJoin.await(5, TimeUnit.SECONDS), "VT caller did not start");
        waitFor(() -> caller.getState() == Thread.State.WAITING);
        check(callerContinuation.get() != null, "caller lacks continuation");
        check(
            callerContinuation.get() != childContinuation.get(),
            "child continuation is being used as caller stack");
        var marker =
            carriers.submit(
                () -> {
                  check(
                      Thread.currentThread() == carrierIdentity.get(),
                      "replacement physical carrier");
                  check(context.get() == null, "logical caller TLS leaked into carrier");
                  context.set("unrelated carrier context");
                  return Thread.currentThread();
                });
        check(
            marker.get(5, TimeUnit.SECONDS) == carrierIdentity.get(),
            "marker did not reuse original carrier");
        check(!logicalResult.isDone(), "caller completed before child release");
        check(workersCreated.get() == 1, "compensation created more physical carriers");
        System.out.println(
            "VT CALLER: own continuation differs from child; same sole physical carrier runs marker"
                + " during join; TLS preserved");
        releaseChild.countDown();
        check(logicalResult.get(5, TimeUnit.SECONDS).equals("hello"), "wrong VT result");
      } finally {
        releaseChild.countDown();
        caller.join();
        child.join();
      }
    }
  }

  private static Thread.Builder.OfVirtual virtualBuilder(Executor scheduler) throws Exception {
    var type = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
    var constructor = type.getDeclaredConstructor(Executor.class);
    constructor.setAccessible(true);
    return (Thread.Builder.OfVirtual) constructor.newInstance(scheduler);
  }

  private static Continuation continuationOf(Thread thread) {
    try {
      return (Continuation) VT_CONTINUATION.get(thread);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Continuation currentContinuation() {
    return Continuation.getCurrentContinuation(VT_SCOPE);
  }

  private static Thread currentCarrier() {
    try {
      return (Thread) CURRENT_CARRIER.invokeExact();
    } catch (Throwable failure) {
      throw new AssertionError(failure);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException failure) {
      throw new AssertionError(failure);
    }
  }

  private static void waitFor(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() >= deadline) throw new AssertionError("condition timed out");
      Thread.onSpinWait();
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
