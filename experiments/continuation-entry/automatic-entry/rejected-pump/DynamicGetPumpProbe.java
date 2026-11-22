import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Mechanism probe, not the production Blockless API and not a general executor adapter. */
public class DynamicGetPumpProbe {
  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
  private static final AtomicReference<Thread> PHYSICAL = new AtomicReference<>();
  private static final AtomicReference<Runnable> ORIGINAL_B = new AtomicReference<>();
  private static final AtomicInteger B_HOOKS = new AtomicInteger();
  private static final CompletableFuture<Boolean> AFTER_B_UNMOUNT = new CompletableFuture<>();
  private static final AtomicBoolean B_MOUNTED = new AtomicBoolean();

  private static final class Pool extends ThreadPoolExecutor {
    Pool() {
      super(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<>(),
          command -> {
            var worker = Thread.ofPlatform().name("original-raw-worker").unstarted(command);
            if (!PHYSICAL.compareAndSet(null, worker))
              throw new AssertionError("replacement worker");
            return worker;
          });
    }

    @Override
    protected void beforeExecute(Thread worker, Runnable command) {
      if (command == ORIGINAL_B.get()) B_HOOKS.incrementAndGet();
    }
  }

  private record CarrierResume(Runnable body) implements Runnable {
    public void run() {
      body.run();
      if (B_MOUNTED.get()) AFTER_B_UNMOUNT.complete(Thread.currentThread().isInterrupted());
    }
  }

  private static final class PumpSupplier<T> implements Supplier<T> {
    final CompletableFuture<T> value;
    final Pool pool;

    PumpSupplier(CompletableFuture<T> value, Pool pool) {
      this.value = value;
      this.pool = pool;
    }

    public T get() {
      try {
        var builderType = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
        var constructor = builderType.getDeclaredConstructor(Executor.class);
        constructor.setAccessible(true);
        Executor scheduler = resume -> pool.execute(new CarrierResume(resume));
        var builder = (Thread.Builder.OfVirtual) constructor.newInstance(scheduler);
        while (!value.isDone()) {
          var command = pool.getQueue().poll(10, TimeUnit.MILLISECONDS);
          if (command == null) continue;
          if (command instanceof CarrierResume) command.run();
          else builder.unstarted(command).start();
        }
        return value.join();
      } catch (Exception failure) {
        throw new RuntimeException(failure);
      }
    }
  }

  public static void main(String[] args) throws Exception {
    boolean cancel = args.length > 0 && args[0].equals("cancel");
    var sourceA = new CompletableFuture<String>();
    var aEntered = new CountDownLatch(1);
    var aActuallyFinished = new CompletableFuture<Boolean>();
    var bMounted = new CountDownLatch(1);
    var releaseB = new AtomicBoolean();
    var bLogicalInterrupted = new AtomicBoolean();
    var bPhysicalInterrupted = new AtomicBoolean();
    var bSawAContext = new AtomicReference<String>();
    var physicalIdentity = new AtomicReference<Thread>();
    var callerCleanups = new AtomicInteger();
    try (var pool = new Pool()) {
      var callerA =
          pool.submit(
              () -> {
                CONTEXT.set("raw caller A");
                physicalIdentity.set(Thread.currentThread());
                aEntered.countDown();
                try {
                  var result = new PumpSupplier<>(sourceA, pool).get();
                  if (!result.equals("hello")) throw new AssertionError("bad A result");
                  if (!CONTEXT.get().equals("raw caller A"))
                    throw new AssertionError("A context corrupted");
                  aActuallyFinished.complete(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                  aActuallyFinished.completeExceptionally(failure);
                } finally {
                  callerCleanups.incrementAndGet();
                }
              });
      check(aEntered.await(5, TimeUnit.SECONDS), "A not running");
      var callerB =
          new FutureTask<Void>(
              () -> {
                check(Thread.currentThread().isVirtual(), "B not logical VT");
                bSawAContext.set(CONTEXT.get());
                CONTEXT.set("logical B");
                B_MOUNTED.set(true);
                bMounted.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!releaseB.get()) {
                  if (System.nanoTime() > deadline) throw new AssertionError("B timed out");
                  Thread.onSpinWait();
                }
                bLogicalInterrupted.set(Thread.currentThread().isInterrupted());
                bPhysicalInterrupted.set(PHYSICAL.get().isInterrupted());
                return null;
              });
      ORIGINAL_B.set(callerB);
      pool.execute(callerB);
      try {
        check(bMounted.await(5, TimeUnit.SECONDS), "B not serviced by A get");
        check(!aActuallyFinished.isDone(), "A already returned");
        check(physicalIdentity.get() == PHYSICAL.get(), "different A worker");
        check(bSawAContext.get() == null, "B inherited raw A context");
        if (cancel) check(callerA.cancel(true), "A cancellation rejected");
        releaseB.set(true);
        callerB.get(5, TimeUnit.SECONDS);
        var interruptAfterUnmount = AFTER_B_UNMOUNT.get(5, TimeUnit.SECONDS);
        sourceA.complete("hello");
        var aInterrupted = aActuallyFinished.get(5, TimeUnit.SECONDS);
        System.out.println(
            "A pending get serviced queued B as VT on same worker, then returned actual value");
        check(B_HOOKS.get() == 0, "missing original B hook hazard was not reproduced");
        System.out.println(
            "B saw A ThreadLocal: "
                + bSawAContext.get()
                + "; original B task beforeExecute hooks: "
                + B_HOOKS.get());
        if (cancel) {
          System.out.println("A Future cancelled: " + callerA.isCancelled());
          System.out.println(
              "During mounted B, physical A worker interrupted: "
                  + bPhysicalInterrupted.get()
                  + "; B logical interrupted: "
                  + bLogicalInterrupted.get());
          System.out.println(
              "After B unmount, physical A interrupted: "
                  + interruptAfterUnmount
                  + "; A after get observed interrupt: "
                  + aInterrupted);
          check(bPhysicalInterrupted.get(), "physical A interrupt not delivered");
          check(!interruptAfterUnmount && !aInterrupted, "expected loss was not reproduced");
        } else callerA.get(5, TimeUnit.SECONDS);
      } finally {
        releaseB.set(true);
        sourceA.complete("hello");
      }
    }
    check(callerCleanups.get() == 1, "A finally count wrong");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
