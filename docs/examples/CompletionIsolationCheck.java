import io.github.pjlabs.blockless.Blockless;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** Forces the producer to remain in one callback while a waiter resumes. */
public class CompletionIsolationCheck {
  static void parked(Thread thread) {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
    while(thread.getState()!=Thread.State.WAITING && System.nanoTime()<deadline)
      LockSupport.parkNanos(100_000);
    if(thread.getState()!=Thread.State.WAITING) throw new AssertionError("Caller did not wait");
  }
  static void check(boolean blockless) throws Exception {
    var stage=new CompletableFuture<String>();
    var callbackThread=new CompletableFuture<String>();
    var producerInCallback=new CountDownLatch(1);
    var releaseProducer=new CountDownLatch(1);
    var carrierHeld=new CountDownLatch(1);
    var releaseCarrier=new CountDownLatch(1);
    var lock=new Object();
    // The completion stack is LIFO: A, then B, then the waiter/Blockless handler.
    var callbackA=stage.thenRun(() -> callbackThread.complete(Thread.currentThread().getName()));
    var callbackB=stage.thenRun(() -> {
      producerInCallback.countDown();
      try { releaseProducer.await(); }
      catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    });
    Supplier<String> wait=blockless ? Blockless.supplier(stage) : stage::join;
    var result=new FutureTask<String>(wait::get);
    var caller=Thread.ofVirtual().name("application-waiter").start(result);
    Thread hog=null;
    Thread producer=null;
    try {
      parked(caller);
      // A deliberately pinned control thread temporarily holds the only carrier.
      // This orders the experiment; it is not a claim that join caused this pinning.
      hog=Thread.ofVirtual().name("scheduling-control").start(() -> {
        synchronized(lock) {
          carrierHeld.countDown();
          try { releaseCarrier.await(); }
          catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
      });
      if(!carrierHeld.await(2,TimeUnit.SECONDS)) throw new AssertionError("Control failed");
      producer=Thread.ofPlatform().daemon().name("client-completer").start(() -> stage.complete("done"));
      if(!producerInCallback.await(2,TimeUnit.SECONDS)) throw new AssertionError("Producer did not reach B");
      releaseCarrier.countDown();
      if(!"done".equals(result.get(2,TimeUnit.SECONDS))) throw new AssertionError("Wrong result");
      if(blockless) {
        if(callbackThread.isDone()) throw new AssertionError("Blockless caller drained source callbacks");
        System.out.println("Blockless: caller returned while callback A remained pending on the producer");
      } else {
        if(!"application-waiter".equals(callbackThread.get(2,TimeUnit.SECONDS))) throw new AssertionError("join did not help callbacks");
        System.out.println("join: caller executed pending callback A on the same future before returning");
      }
    } finally {
      releaseCarrier.countDown();
      releaseProducer.countDown();
      caller.interrupt();
      caller.join(2000);
      if(hog!=null) hog.join(2000);
      if(producer!=null) producer.join(2000);
    }
    callbackA.get(2,TimeUnit.SECONDS);
    callbackB.get(2,TimeUnit.SECONDS);
    if(blockless && !"client-completer".equals(callbackThread.get())) throw new AssertionError("Unexpected callback thread");
  }
  public static void main(String[] args) throws Exception {
    for(int i=0;i<3;i++) { check(false);check(true); }
  }
}
