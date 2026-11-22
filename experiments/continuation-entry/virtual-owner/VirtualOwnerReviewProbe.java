import io.github.pjlabs.blockless.continuations.Continuations;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VirtualOwnerReviewProbe {
  public static void main(String[] args) throws Exception {
    foreignFutureTaskCancellationDoesNotInterruptSharedCarrier();
    shutdownNowInterruptsLogicalRootsAndWaitsForActualCleanup();
    System.out.println("ALL independent VT-owner review checks passed");
  }

  static void foreignFutureTaskCancellationDoesNotInterruptSharedCarrier() throws Exception {
    var releaseBody = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    var bodyStarted = new CountDownLatch(1);
    var cleanupStarted = new CountDownLatch(1);
    var cleanupFinished = new CountDownLatch(1);
    var rootInterrupted = new AtomicBoolean();
    try (var carriers =
        Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("foreign-task-carrier").factory())) {
      var physicalWorker = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      try (var owner = BorrowedCarrierThreads.newLogicalExecutor(carriers)) {
        try {
          // This is the foreign FutureTask that native-root cancellation guards cannot police.
          var foreign =
              new FutureTask<>(
                  () -> {
                    try {
                      var user =
                          Continuations.supplier(
                              () -> {
                                bodyStarted.countDown();
                                releaseBody.await();
                                return "hello";
                              });
                      return user.get();
                    } finally {
                      rootInterrupted.set(Thread.interrupted());
                      cleanupStarted.countDown();
                      try {
                        releaseCleanup.await();
                      } finally {
                        cleanupFinished.countDown();
                      }
                    }
                  });
          // Hide the FutureTask inside an ordinary Runnable, matching the native-owner blind spot.
          owner.execute(() -> foreign.run());
          require(bodyStarted.await(5, TimeUnit.SECONDS), "supplier did not start");
          require(foreign.cancel(true), "foreign task cancellation declined");
          require(
              cleanupStarted.await(5, TimeUnit.SECONDS) && rootInterrupted.get(),
              "foreign cancel did not target logical root");
          owner
              .submit(
                  () -> {
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalWorker, "wrong carrier");
                    require(
                        !Thread.currentThread().isInterrupted(),
                        "foreign cancellation interrupted unrelated root");
                  })
              .get(5, TimeUnit.SECONDS);
          require(
              !carriers.submit(Thread::interrupted).get(5, TimeUnit.SECONDS),
              "carrier retained foreign-task interrupt");
          owner.shutdown();
          require(
              !owner.awaitTermination(20, TimeUnit.MILLISECONDS),
              "foreign cancelled finally was removed early");
          releaseCleanup.countDown();
          require(cleanupFinished.await(5, TimeUnit.SECONDS), "foreign task finally missing");
          require(
              owner.awaitTermination(5, TimeUnit.SECONDS), "foreign task owner did not terminate");
          try {
            foreign.get();
            throw new AssertionError("cancelled foreign task returned");
          } catch (CancellationException expected) {
          }
        } finally {
          releaseBody.countDown();
          releaseCleanup.countDown();
        }
      }
    } finally {
      releaseBody.countDown();
      releaseCleanup.countDown();
    }
    System.out.println(
        "PASS: hidden foreign FutureTask.cancel(true) targets its logical VT, keeps unrelated"
            + " shared carrier clean, and termination waits actual finally");
  }

  static void shutdownNowInterruptsLogicalRootsAndWaitsForActualCleanup() throws Exception {
    var rootsStarted = new CountDownLatch(2);
    var cleanupStarted = new CountDownLatch(2);
    var releaseCleanup = new CountDownLatch(1);
    var cleanupFinished = new AtomicInteger();
    var logicalInterruptions = new AtomicInteger();
    var neverNormallyReleased = new CountDownLatch(1);
    try (var carriers =
        Executors.newSingleThreadExecutor(Thread.ofPlatform().name("shutdown-carrier").factory())) {
      var physicalWorker = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      var owner = BorrowedCarrierThreads.newLogicalExecutor(carriers);
      try {
        for (int i = 0; i < 2; i++) {
          owner.submit(
              () -> {
                try {
                  rootsStarted.countDown();
                  neverNormallyReleased.await();
                } catch (InterruptedException interrupted) {
                  logicalInterruptions.incrementAndGet();
                } finally {
                  Thread.interrupted();
                  cleanupStarted.countDown();
                  try {
                    releaseCleanup.await();
                  } finally {
                    cleanupFinished.incrementAndGet();
                  }
                }
                return null;
              });
        }
        require(rootsStarted.await(5, TimeUnit.SECONDS), "roots did not start");
        owner.shutdownNow();
        require(
            cleanupStarted.await(5, TimeUnit.SECONDS),
            "shutdownNow did not interrupt both logical roots");
        require(logicalInterruptions.get() == 2, "wrong interrupt ownership");
        require(
            !owner.awaitTermination(20, TimeUnit.MILLISECONDS),
            "shutdownNow terminated before actual finally");
        require(
            carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS) == physicalWorker,
            "shutdownNow replaced/destroyed carrier");
        require(
            !carriers.submit(Thread::interrupted).get(5, TimeUnit.SECONDS),
            "logical shutdown leaked interrupt onto carrier");
        releaseCleanup.countDown();
        require(
            owner.awaitTermination(5, TimeUnit.SECONDS),
            "logical roots did not terminate after cleanup");
        require(cleanupFinished.get() == 2, "cleanup missing or duplicated");
        require(!carriers.isShutdown(), "owner shutdownNow closed borrowed carrier pool");
      } finally {
        releaseCleanup.countDown();
        neverNormallyReleased.countDown();
        owner.close();
      }
    } finally {
      releaseCleanup.countDown();
      neverNormallyReleased.countDown();
    }
    System.out.println(
        "PASS: shutdownNow interrupts owned VT roots, waits their actual finally, and preserves"
            + " borrowed physical carrier");
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
