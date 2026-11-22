import io.github.pjlabs.blockless.continuations.Continuations;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

/** Research only: compare Thread.join and the actual production supplier/get wait. */
public final class JoinContractProbe {
  public static void main(String[] args) throws Exception {
    virtualCallerJoinAndActualGetBothReleaseCarrier(false);
    virtualCallerJoinAndActualGetBothReleaseCarrier(true);
    platformCallerJoiningVirtualTargetKeepsWorkerOccupied();
    nativePlatformCallerJoiningVirtualTargetStillKeepsWorkerOccupied();
    rawPlatformCallerActualGetStillRejectsPendingRead();
    interruptAffectsWaitingCallerNotTarget(false);
    interruptAffectsWaitingCallerNotTarget(true);
    joinTimeoutAndCompletionPublicationRace();
    actualSupplierValueReadinessPrecedesThreadTermination();
    System.out.println("ALL join/actual-get contract probes passed");
  }

  static void virtualCallerJoinAndActualGetBothReleaseCarrier(boolean useSupplier)
      throws Exception {
    var bodyStarted = new CountDownLatch(1);
    var finishBody = new CountDownLatch(1);
    var bodyCleanup = new AtomicInteger();
    var callerCleanup = new AtomicInteger();
    var createdCarriers = new AtomicInteger();
    var context = new ThreadLocal<String>();
    var lock = new ReentrantLock();
    try (var carriers =
        Executors.newSingleThreadExecutor(
            r -> {
              createdCarriers.incrementAndGet();
              return Thread.ofPlatform().name("join-contract-carrier").unstarted(r);
            })) {
      var physicalCarrier = carriers.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
      var targetFactory = virtualFactory(carriers);
      try (var owner = BorrowedCarrierThreads.newLogicalExecutor(carriers)) {
        try {
          var request =
              owner.submit(
                  () -> {
                    var caller = Thread.currentThread();
                    require(caller.isVirtual(), "waiting caller must already be a virtual thread");
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalCarrier,
                        "initial carrier mismatch");
                    context.set("request-A");
                    lock.lock();
                    try {
                      var savedLocal = "saved";
                      Payload payload;
                      if (useSupplier) {
                        var user =
                            Continuations.supplier(
                                () -> {
                                  var value = new Payload();
                                  value.answer = 42;
                                  try {
                                    bodyStarted.countDown();
                                    finishBody.await();
                                    return value;
                                  } finally {
                                    value.cleanupPublished = 84;
                                    bodyCleanup.incrementAndGet();
                                  }
                                });
                        payload = user.get();
                      } else {
                        var result = new Payload[1];
                        var target =
                            targetFactory.newThread(
                                () -> {
                                  var value = new Payload();
                                  value.answer = 42;
                                  try {
                                    bodyStarted.countDown();
                                    finishBody.await();
                                    result[0] = value;
                                  } catch (InterruptedException failure) {
                                    throw new AssertionError(failure);
                                  } finally {
                                    value.cleanupPublished = 84;
                                    bodyCleanup.incrementAndGet();
                                  }
                                });
                        target.start();
                        target.join();
                        require(!target.isAlive(), "join returned before target terminated");
                        payload = result[0];
                      }
                      require(
                          payload.answer == 42 && payload.cleanupPublished == 84,
                          "result/finally state not published");
                      require(Thread.currentThread() == caller, "logical caller identity changed");
                      require(
                          BorrowedCarrierThreads.currentCarrier() == physicalCarrier,
                          "resume carrier mismatch");
                      require(context.get().equals("request-A"), "caller ThreadLocal lost");
                      require(lock.isHeldByCurrentThread(), "caller lock ownership lost");
                      return savedLocal + ":" + payload.answer;
                    } finally {
                      context.remove();
                      lock.unlock();
                      callerCleanup.incrementAndGet();
                    }
                  });
          require(bodyStarted.await(5, TimeUnit.SECONDS), "body never started");
          owner
              .submit(
                  () -> {
                    require(
                        BorrowedCarrierThreads.currentCarrier() == physicalCarrier,
                        "original platform carrier not freed");
                    require(context.get() == null, "unrelated task inherited caller ThreadLocal");
                    var acquired = lock.tryLock();
                    if (acquired) lock.unlock();
                    require(!acquired, "unrelated task inherited caller lock ownership");
                  })
              .get(5, TimeUnit.SECONDS);
          require(createdCarriers.get() == 1, "replacement platform worker hid blocking");
          require(!request.isDone(), "caller returned before body ready");
          require(
              callerCleanup.get() == 0 && bodyCleanup.get() == 0,
              "finally ran during ordinary suspension");
          finishBody.countDown();
          require(request.get(5, TimeUnit.SECONDS).equals("saved:42"), "caller locals/value lost");
          require(
              callerCleanup.get() == 1 && bodyCleanup.get() == 1, "cleanup missing or repeated");
        } finally {
          finishBody.countDown();
        }
      }
    } finally {
      finishBody.countDown();
      context.remove();
    }
    System.out.println(
        "PASS "
            + (useSupplier ? "actual supplier.get" : "Thread.join")
            + ": VT caller, same single physical carrier free, locals/TLS/lock isolation and"
            + " finally publication");
  }

  static void platformCallerJoiningVirtualTargetKeepsWorkerOccupied() throws Exception {
    var finishTarget = new CountDownLatch(1);
    var targetStarted = new CountDownLatch(1);
    var callerJoining = new CountDownLatch(1);
    var target =
        Thread.ofVirtual()
            .start(
                () -> {
                  targetStarted.countDown();
                  try {
                    finishTarget.await();
                  } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                  }
                });
    try (var worker = Executors.newSingleThreadExecutor()) {
      try {
        require(targetStarted.await(5, TimeUnit.SECONDS), "target not started");
        var caller =
            worker.submit(
                () -> {
                  require(
                      !Thread.currentThread().isVirtual(),
                      "control caller must be platform thread");
                  callerJoining.countDown();
                  target.join();
                  return "joined";
                });
        require(callerJoining.await(5, TimeUnit.SECONDS), "caller not joining");
        var marker = worker.submit(() -> "worker free");
        try {
          marker.get(50, TimeUnit.MILLISECONDS);
          throw new AssertionError("platform join unexpectedly released worker");
        } catch (TimeoutException expected) {
        }
        require(!caller.isDone(), "join returned before target ready");
        finishTarget.countDown();
        require(caller.get(5, TimeUnit.SECONDS).equals("joined"), "join did not resume");
        require(
            marker.get(5, TimeUnit.SECONDS).equals("worker free"),
            "worker did not become free after join");
      } finally {
        finishTarget.countDown();
      }
    } finally {
      finishTarget.countDown();
      target.join();
    }
    System.out.println(
        "PASS negative control: joining a virtual TARGET from platform CALLER occupies original"
            + " worker; target type creates no caller continuation");
  }

  static void nativePlatformCallerJoiningVirtualTargetStillKeepsWorkerOccupied() throws Exception {
    var finishTarget = new CountDownLatch(1);
    var joining = new CountDownLatch(1);
    var scope = new ContinuationScope("join-native-platform-caller");
    var target =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    finishTarget.await();
                  } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                  }
                });
    try (var worker = Executors.newSingleThreadExecutor()) {
      try {
        var caller =
            worker.submit(
                () -> {
                  var continuation =
                      new Continuation(
                          scope,
                          () -> {
                            require(
                                Continuation.getCurrentContinuation(scope) != null,
                                "native caller scope missing");
                            require(
                                !Thread.currentThread().isVirtual(),
                                "native caller should still have platform identity");
                            joining.countDown();
                            try {
                              target.join();
                            } catch (InterruptedException failure) {
                              throw new AssertionError(failure);
                            }
                          });
                  continuation.run();
                  require(continuation.isDone(), "join unexpectedly yielded native scope");
                });
        require(joining.await(5, TimeUnit.SECONDS), "native caller did not join");
        var marker = worker.submit(() -> 42);
        try {
          marker.get(50, TimeUnit.MILLISECONDS);
          throw new AssertionError("native caller join unexpectedly freed carrier");
        } catch (TimeoutException expected) {
        }
        require(!caller.isDone(), "native caller returned before target exit");
        finishTarget.countDown();
        caller.get(5, TimeUnit.SECONDS);
        require(marker.get(5, TimeUnit.SECONDS) == 42, "carrier did not resume");
      } finally {
        finishTarget.countDown();
      }
    } finally {
      finishTarget.countDown();
      target.join();
    }
    System.out.println(
        "PASS native negative control: active native scope alone does not make join yield a"
            + " platform caller; current caller Thread identity controls parking");
  }

  static void rawPlatformCallerActualGetStillRejectsPendingRead() throws Exception {
    var finish = new CountDownLatch(1);
    try (var worker = Executors.newSingleThreadExecutor()) {
      try {
        var caller =
            worker.submit(
                () -> {
                  var user =
                      Continuations.supplier(
                          () -> {
                            finish.await();
                            return "hello";
                          });
                  try {
                    user.get();
                    throw new AssertionError("expected production restriction");
                  } catch (IllegalStateException expected) {
                    return expected.getMessage();
                  }
                });
        require(
            caller.get(5, TimeUnit.SECONDS).contains("virtual-thread caller"),
            "unexpected get failure");
      } finally {
        finish.countDown();
      }
    } finally {
      finish.countDown();
    }
    System.out.println(
        "PASS current-contract diagnostic: actual pending get on raw platform caller rejects;"
            + " replacing it with join would wait on that platform caller");
  }

  static void interruptAffectsWaitingCallerNotTarget(boolean useSupplier) throws Exception {
    var finishTarget = new CountDownLatch(1);
    var targetStarted = new CountDownLatch(1);
    var waiting = new CountDownLatch(1);
    var targetInterrupted = new AtomicBoolean();
    var caller = new AtomicReference<Thread>();
    try (var carriers = Executors.newSingleThreadExecutor()) {
      try (var owner = BorrowedCarrierThreads.newLogicalExecutor(carriers)) {
        try {
          var root =
              owner.submit(
                  () -> {
                    caller.set(Thread.currentThread());
                    if (useSupplier) {
                      var user =
                          Continuations.supplier(
                              () -> {
                                targetStarted.countDown();
                                try {
                                  finishTarget.await();
                                } catch (InterruptedException failure) {
                                  targetInterrupted.set(true);
                                  throw failure;
                                }
                                return "hello";
                              });
                      waiting.countDown();
                      try {
                        user.get();
                        throw new AssertionError("get ignored interruption");
                      } catch (RuntimeException expected) {
                        require(
                            expected.getCause() instanceof InterruptedException,
                            "get must wrap interruption");
                        require(
                            Thread.currentThread().isInterrupted(),
                            "get should reassert interrupt status");
                        Thread.interrupted();
                      }
                    } else {
                      var target =
                          virtualFactory(carriers)
                              .newThread(
                                  () -> {
                                    targetStarted.countDown();
                                    try {
                                      finishTarget.await();
                                    } catch (InterruptedException failure) {
                                      targetInterrupted.set(true);
                                    }
                                  });
                      target.start();
                      waiting.countDown();
                      try {
                        target.join();
                        throw new AssertionError("join ignored interruption");
                      } catch (InterruptedException expected) {
                        require(
                            !Thread.currentThread().isInterrupted(),
                            "join should clear interrupted status");
                      }
                      require(target.isAlive(), "interrupting joiner terminated target");
                    }
                    return "interrupted caller";
                  });
          require(
              waiting.await(5, TimeUnit.SECONDS) && targetStarted.await(5, TimeUnit.SECONDS),
              "wait did not start");
          caller.get().interrupt();
          require(
              root.get(5, TimeUnit.SECONDS).equals("interrupted caller"),
              "caller did not react to interrupt");
          require(!targetInterrupted.get(), "caller interrupt propagated to target/body");
          owner
              .submit(
                  () ->
                      require(
                          !Thread.currentThread().isInterrupted(),
                          "interrupt leaked to unrelated root"))
              .get(5, TimeUnit.SECONDS);
        } finally {
          finishTarget.countDown();
        }
      }
    } finally {
      finishTarget.countDown();
    }
    System.out.println(
        "PASS "
            + (useSupplier ? "actual get" : "join")
            + " interruption: caller interrupted, target continues; caller flag "
            + (useSupplier ? "reasserted" : "cleared"));
  }

  static void joinTimeoutAndCompletionPublicationRace() throws Exception {
    var finish = new CountDownLatch(1);
    var target =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    finish.await();
                  } catch (InterruptedException failure) {
                    throw new AssertionError(failure);
                  }
                });
    try {
      require(!target.join(Duration.ofMillis(1)), "timed join completed with target held");
      require(target.isAlive(), "timeout terminated target");
    } finally {
      finish.countDown();
      target.join();
    }
    require(target.join(Duration.ZERO), "already-terminated target not detected");
    for (int i = 0; i < 1_000; i++) {
      var data = new Payload[1];
      var next =
          Thread.ofVirtual()
              .start(
                  () -> {
                    var value = new Payload();
                    value.answer = 42;
                    value.cleanupPublished = 84;
                    data[0] = value;
                  });
      if (!next.join(Duration.ZERO)) next.join();
      require(
          data[0].answer == 42 && data[0].cleanupPublished == 84,
          "join lost plain-field publication in readiness race");
    }
    System.out.println(
        "PASS join timeout/readiness: false leaves target running; completed join observes plain"
            + " result fields across 1000 races");
  }

  static void actualSupplierValueReadinessPrecedesThreadTermination() throws Exception {
    var finishBody = new CountDownLatch(1);
    var ready = new CountDownLatch(1);
    var bodyThread = new AtomicReference<Thread>();
    var aliveAtReadiness = new AtomicBoolean();
    var valueAtReadiness = new AtomicInteger();
    var callbackIsProducer = new AtomicBoolean();
    var user =
        Continuations.supplier(
            Runnable::run,
            () -> {
              bodyThread.set(Thread.currentThread());
              finishBody.await();
              return 42;
            });
    user.onComplete(
        () -> {
          valueAtReadiness.set(user.get());
          aliveAtReadiness.set(bodyThread.get().isAlive());
          callbackIsProducer.set(Thread.currentThread() == bodyThread.get());
          ready.countDown();
        });
    finishBody.countDown();
    require(ready.await(5, TimeUnit.SECONDS), "result callback did not run");
    require(
        valueAtReadiness.get() == 42 && aliveAtReadiness.get(),
        "expected value publication before producer thread exit");
    require(callbackIsProducer.get(), "direct-executor callback should run on producer VT");
    bodyThread.get().join();
    require(user.get() == 42, "cached value changed after producer exit");
    System.out.println(
        "PASS readiness distinction: onComplete reads actual value on producer VT before it exits;"
            + " unconditional producer.join in get would self-deadlock");
  }

  static ThreadFactory virtualFactory(Executor carriers) throws ReflectiveOperationException {
    var type = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder");
    var constructor = type.getDeclaredConstructor(Executor.class);
    constructor.setAccessible(true);
    return ((Thread.Builder.OfVirtual) constructor.newInstance(carriers))
        .inheritInheritableThreadLocals(false)
        .factory();
  }

  static final class Payload {
    int answer;
    int cleanupPublished;
  }

  static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
