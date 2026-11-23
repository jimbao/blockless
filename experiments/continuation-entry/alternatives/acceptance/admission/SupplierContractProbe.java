package admission;

import io.github.pjlabs.blockless.Blockless;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Public-API acceptance of immediate stable supplier/get, independent of executor implementation.
 */
public final class SupplierContractProbe {
  private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
  private static final InheritableThreadLocal<String> INHERITED = new InheritableThreadLocal<>();

  public static void main(String[] args) throws Exception {
    final var factory = args.length > 0 ? args[0] : "fixed";
    final var expectedVirtual = args.length > 1 && args[1].equals("virtual");
    check(factory, expectedVirtual, false);
    check(factory, expectedVirtual, true);
  }

  private static void check(String factory, boolean expectedVirtual, boolean fails)
      throws Exception {
    final var physical = new TrackedPlatformFactory();
    final var gates = new Gates();
    final var callerThread = new AtomicReference<Thread>();
    final var callerEntries = new AtomicInteger();
    final var callerCleanups = new AtomicInteger();
    final var afterGet = new AtomicInteger();
    final var lock = new ReentrantLock();
    final var originalFailure = new IllegalArgumentException("original query failure");
    final var threads = ManagementFactory.getThreadMXBean();
    threads.resetPeakThreadCount();
    final var before = platformThreads();
    INHERITED.set("outside");
    try (final var executor = newExecutor(factory, physical)) {
      try {
        final var caller =
            executor.submit(
                () -> {
                  callerThread.set(Thread.currentThread());
                  callerEntries.incrementAndGet();
                  CONTEXT.set("request");
                  INHERITED.set("request");
                  lock.lock();
                  try {
                    final var saved = "local:42";
                    final var value = loadUser(gates, fails, originalFailure);
                    require(saved.equals("local:42"), "Caller local changed");
                    require(CONTEXT.get().equals("request"), "Caller context changed");
                    require(INHERITED.get().equals("request"), "Caller inherited state changed");
                    require(
                        Thread.currentThread() == callerThread.get(), "Caller identity changed");
                    require(lock.isHeldByCurrentThread(), "Caller lock ownership changed");
                    afterGet.incrementAndGet();
                    return value;
                  } finally {
                    CONTEXT.remove();
                    INHERITED.set("outside");
                    lock.unlock();
                    callerCleanups.incrementAndGet();
                  }
                });
        require(gates.atGet.await(5, TimeUnit.SECONDS), "Caller did not reach get");
        require(gates.bodyStarted.await(5, TimeUnit.SECONDS), "Body did not start");
        if (caller.isDone()) caller.get(5, TimeUnit.SECONDS);
        final var marker =
            executor.submit(
                () -> {
                  final var acquired = lock.tryLock();
                  if (acquired) lock.unlock();
                  return new Marker(
                      Thread.currentThread(), CONTEXT.get(), INHERITED.get(), acquired);
                });
        final Marker observed;
        try {
          observed = marker.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException occupied) {
          // Finish the real pending call before reporting failed capacity acceptance.
          gates.finishIo.countDown();
          gates.finishCleanup.countDown();
          require(
              caller.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
              "Compatibility diagnosis lost the actual value");
          executor.shutdown();
          require(executor.awaitTermination(5, TimeUnit.SECONDS), "Diagnostic owner leaked");
          require(
              callerCleanups.get() == 1 && gates.bodyCleanups.get() == 1,
              "Diagnostic cleanup was skipped");
          throw new AssertionError(
              "DISPATCH_STALLED: pending get occupied the sole request worker; actual value and"
                  + " cleanup succeeded after release",
              occupied);
        }
        require(!caller.isDone(), "Caller finished while the body was deliberately pending");
        require(
            callerThread.get().isVirtual() == expectedVirtual, "Unexpected caller logical type");
        require(observed.thread() != callerThread.get(), "Marker reused caller Thread identity");
        require(observed.context() == null, "Request ThreadLocal leaked into unrelated task");
        require(
            !"request".equals(observed.inherited()), "Marker inherited another request's state");
        require(!observed.acquired(), "Marker entered caller's reentrant lock");
        require(callerEntries.get() == 1 && gates.bodyEntries.get() == 1, "Caller/body replayed");
        require(
            afterGet.get() == 0 && callerCleanups.get() == 0 && gates.bodyCleanups.get() == 0,
            "Caller advanced or cleanup ran during pending I/O");
        require(
            physical.created.get() <= 1,
            "TRACKED_PLATFORM_EXPANSION: replacement request workers were created");
        final var pendingThreads = platformThreads();
        pendingThreads.keySet().removeAll(before.keySet());
        executor.shutdown();
        require(
            !executor.awaitTermination(20, TimeUnit.MILLISECONDS),
            "Shutdown forgot pending caller");
        gates.finishIo.countDown();
        require(gates.cleanupStarted.await(5, TimeUnit.SECONDS), "Body cleanup never started");
        require(!caller.isDone(), "Outcome published before body cleanup finished");
        require(
            !executor.awaitTermination(20, TimeUnit.MILLISECONDS), "Shutdown skipped body cleanup");
        gates.finishCleanup.countDown();
        if (fails) {
          try {
            caller.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Original query failure was lost");
          } catch (ExecutionException failure) {
            require(failure.getCause() == originalFailure, "Failure identity changed");
          }
        } else {
          require(
              caller.get(5, TimeUnit.SECONDS).equals(new User("details:42", "read")),
              "Wrong actual User");
        }
        require(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor did not terminate");
        require(callerEntries.get() == 1 && gates.bodyEntries.get() == 1, "Work replayed");
        require(
            callerCleanups.get() == 1 && gates.bodyCleanups.get() == 1,
            "Cleanup skipped or repeated");
        require(afterGet.get() == (fails ? 0 : 1), "Unexpected post-get execution");
        System.out.println(
            "FUNCTIONAL_PASS outcome="
                + (fails ? "original-failure" : "actual-User")
                + "; caller_virtual="
                + callerThread.get().isVirtual()
                + "; marker_virtual="
                + observed.thread().isVirtual()
                + "; marker_inherited="
                + observed.inherited()
                + "; tracked_platform_created="
                + physical.created.get()
                + "; new_platform_snapshot="
                + pendingThreads.values()
                + "; peak_platform_total="
                + threads.getPeakThreadCount()
                + "; factory="
                + factory);
      } finally {
        gates.finishIo.countDown();
        gates.finishCleanup.countDown();
      }
    } finally {
      INHERITED.remove();
    }
  }

  /** The business call shape is unchanged for every candidate and the negative control. */
  private static User loadUser(Gates gates, boolean fails, RuntimeException originalFailure) {
    final var user =
        Blockless.supplier(
            () -> {
              gates.bodyEntries.incrementAndGet();
              try {
                gates.bodyStarted.countDown();
                gates.finishIo.await();
                require(Thread.currentThread().isVirtual(), "I/O body is not a virtual thread");
                require("request".equals(INHERITED.get()), "Body inherited context changed");
                if (fails) throw originalFailure;
                return new User("details:42", "read");
              } finally {
                gates.cleanupStarted.countDown();
                gates.finishCleanup.await();
                gates.bodyCleanups.incrementAndGet();
              }
            });
    gates.atGet.countDown();
    return user.get();
  }

  public static ExecutorService newExecutor(String factory, ThreadFactory physical)
      throws Exception {
    if (factory.equals("fixed")) return Executors.newFixedThreadPool(1, physical);
    if (factory.equals("virtual")) return Executors.newVirtualThreadPerTaskExecutor();
    final var separator = factory.indexOf('#');
    require(separator > 0, "Factory must be fixed, virtual, or class#method");
    final var type = Class.forName(factory.substring(0, separator));
    final var method = factory.substring(separator + 1);
    try {
      try {
        return (ExecutorService)
            type.getMethod(method, int.class, ThreadFactory.class).invoke(null, 1, physical);
      } catch (NoSuchMethodException absent) {
        return (ExecutorService) type.getMethod(method).invoke(null);
      }
    } catch (InvocationTargetException failure) {
      if (failure.getCause() instanceof Exception cause) throw cause;
      if (failure.getCause() instanceof Error cause) throw cause;
      throw failure;
    }
  }

  private static Map<Long, String> platformThreads() {
    final var threads = ManagementFactory.getThreadMXBean();
    final var result = new LinkedHashMap<Long, String>();
    for (final var info : threads.getThreadInfo(threads.getAllThreadIds())) {
      if (info != null) result.put(info.getThreadId(), info.getThreadName());
    }
    return result;
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private record User(String details, String permissions) {}

  private record Marker(Thread thread, String context, String inherited, boolean acquired) {}

  private static final class Gates {
    final CountDownLatch atGet = new CountDownLatch(1);
    final CountDownLatch bodyStarted = new CountDownLatch(1);
    final CountDownLatch finishIo = new CountDownLatch(1);
    final CountDownLatch cleanupStarted = new CountDownLatch(1);
    final CountDownLatch finishCleanup = new CountDownLatch(1);
    final AtomicInteger bodyEntries = new AtomicInteger();
    final AtomicInteger bodyCleanups = new AtomicInteger();
  }

  private static final class TrackedPlatformFactory implements ThreadFactory {
    final AtomicInteger created = new AtomicInteger();

    @Override
    public Thread newThread(Runnable task) {
      return Thread.ofPlatform()
          .name("acceptance-request-" + created.incrementAndGet())
          .unstarted(task);
    }
  }
}
