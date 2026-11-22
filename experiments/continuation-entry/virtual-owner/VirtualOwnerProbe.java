import io.github.pjlabs.blockless.continuations.Continuations;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class VirtualOwnerProbe {
  public static void main(String[] args) throws Exception {
    supplierReturnsAndContextIsIsolated();
    cancellingOneRootDoesNotInterruptAnotherAndTerminationWaitsForFinally();
  }

  private static void supplierReturnsAndContextIsIsolated() throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var finishBody = new CountDownLatch(1);
    var cleanups = new AtomicInteger();
    var context = new ThreadLocal<String>();
    var logicalLock = new ReentrantLock();
    try (var carriers =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("borrowed-carrier").factory())) {
      var physicalWorker = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      try (var owner = BorrowedCarrierThreads.newLogicalExecutor(carriers)) {
        try {
          var request =
              owner.submit(
                  () -> {
                    require(Thread.currentThread().isVirtual(), "Root identity must be logical VT");
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalWorker,
                        "Wrong physical carrier");
                    var logicalThread = Thread.currentThread();
                    context.set("request-A");
                    logicalLock.lock();
                    try {
                      var user =
                          Continuations.supplier(
                              () -> {
                                require(
                                    Thread.currentThread().isVirtual(),
                                    "Supplier body must be virtual");
                                bodyStarted.countDown();
                                finishBody.await();
                                return "hello";
                              });
                      var value = user.get();
                      require(value.equals("hello"), "Wrong supplier value");
                      require(Thread.currentThread() == logicalThread, "Logical identity changed");
                      require(context.get().equals("request-A"), "Caller context lost");
                      require(
                          BorrowedCarrierThreads.currentCarrier() == physicalWorker,
                          "Resume used wrong carrier");
                      return value;
                    } finally {
                      context.remove();
                      logicalLock.unlock();
                      cleanups.incrementAndGet();
                    }
                  });
          require(bodyStarted.await(5, TimeUnit.SECONDS), "Supplier body did not start");
          owner
              .submit(
                  () -> {
                    require(
                        Thread.currentThread().isVirtual(), "Marker must have logical VT identity");
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalWorker,
                        "Carrier not reused");
                    require(context.get() == null, "Another logical root saw request-A context");
                    var acquired = logicalLock.tryLock();
                    if (acquired) logicalLock.unlock();
                    require(!acquired, "Unrelated root inherited request-A lock ownership");
                  })
              .get(5, TimeUnit.SECONDS);
          require(!request.isDone(), "Request completed before body result");
          require(cleanups.get() == 0, "Finally ran on ordinary suspension");
          finishBody.countDown();
          require(request.get(5, TimeUnit.SECONDS).equals("hello"), "Request result lost");
          require(cleanups.get() == 1, "Finally did not run exactly once");
        } finally {
          finishBody.countDown();
        }
      }
      require(
          carriers.submit(() -> 42).get(5, TimeUnit.SECONDS) == 42,
          "Owner closed borrowed carriers");
    } finally {
      finishBody.countDown();
      context.remove();
    }
    System.out.println(
        "PASS: actual Continuations.supplier/get, same single physical carrier, logical"
            + " identity/TLS/lock isolation, finally exactly once");
  }

  private static void cancellingOneRootDoesNotInterruptAnotherAndTerminationWaitsForFinally()
      throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var finishBody = new CountDownLatch(1);
    var unrelatedStarted = new CountDownLatch(1);
    var finishUnrelated = new CountDownLatch(1);
    var cleanupStarted = new CountDownLatch(1);
    var finishCleanup = new CountDownLatch(1);
    var cleanupFinished = new CountDownLatch(1);
    var unrelatedInterrupted = new AtomicBoolean();
    var interruptedRoot = new AtomicBoolean();
    try (var carriers =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("cancel-carrier").factory())) {
      var physicalWorker = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      try (var owner = BorrowedCarrierThreads.newLogicalExecutor(carriers)) {
        try {
          var request =
              owner.submit(
                  () -> {
                    try {
                      var user =
                          Continuations.supplier(
                              () -> {
                                bodyStarted.countDown();
                                finishBody.await();
                                return "hello";
                              });
                      return user.get();
                    } finally {
                      interruptedRoot.set(Thread.interrupted());
                      cleanupStarted.countDown();
                      try {
                        finishCleanup.await();
                      } finally {
                        cleanupFinished.countDown();
                      }
                    }
                  });
          require(bodyStarted.await(5, TimeUnit.SECONDS), "Supplier body did not start");
          var unrelated =
              owner.submit(
                  () -> {
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalWorker,
                        "Unrelated root used wrong carrier");
                    unrelatedStarted.countDown();
                    try {
                      finishUnrelated.await();
                    } catch (InterruptedException failure) {
                      unrelatedInterrupted.set(true);
                    }
                  });
          require(unrelatedStarted.await(5, TimeUnit.SECONDS), "Unrelated root did not start");
          require(request.cancel(true), "Logical request cancellation declined");
          require(
              cleanupStarted.await(5, TimeUnit.SECONDS), "Interrupted root did not enter finally");
          require(interruptedRoot.get(), "Cancellation did not interrupt logical request");
          owner
              .submit(
                  () -> {
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalWorker,
                        "Carrier was not reusable after cancellation");
                    require(
                        !Thread.currentThread().isInterrupted(),
                        "Cancellation leaked interrupt status");
                  })
              .get(5, TimeUnit.SECONDS);
          require(!unrelated.isDone(), "Cancellation terminated unrelated root");
          require(!unrelatedInterrupted.get(), "Cancellation interrupted unrelated root");
          try {
            request.get();
            throw new AssertionError("Cancelled request returned normally");
          } catch (CancellationException expected) {
            // Future cancellation is distinct from completion of the task's finally block.
          }
          finishUnrelated.countDown();
          unrelated.get(5, TimeUnit.SECONDS);
          owner.shutdown();
          require(
              !owner.awaitTermination(50, TimeUnit.MILLISECONDS),
              "Executor terminated before finally finished");
          try {
            owner.submit(() -> 1);
            throw new AssertionError("Shutdown admitted a new task");
          } catch (RejectedExecutionException expected) {
          }
          finishCleanup.countDown();
          require(cleanupFinished.await(5, TimeUnit.SECONDS), "Finally did not finish");
          require(
              owner.awaitTermination(5, TimeUnit.SECONDS),
              "Executor did not terminate after finally");
        } finally {
          finishBody.countDown();
          finishUnrelated.countDown();
          finishCleanup.countDown();
        }
      }
      require(
          carriers.submit(() -> 42).get(5, TimeUnit.SECONDS) == 42,
          "Owner closed carrier executor");
    } finally {
      finishBody.countDown();
      finishUnrelated.countDown();
      finishCleanup.countDown();
    }
    System.out.println(
        "PASS: cancel(true) interrupts only logical root; unrelated root/carrier preserved;"
            + " shutdown rejects new work and termination waits finally");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
