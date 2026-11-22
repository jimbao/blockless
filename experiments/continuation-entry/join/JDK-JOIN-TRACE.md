# JDK join and supplier waiting: reusable mechanism

Reviewed the source bundled with Corretto 21.0.13.1+2 and 25.0.5.1+2, as well as
the corresponding OpenJDK update repositories. The source locations below refer to those builds. No production code changed.

## What the user's instinct gets right

Joining a virtual thread from another virtual thread uses the same scalable wait
mechanism already used by Blockless's `OutcomeSupplier.get()`:

```text
target.join()
  target VirtualThread.joinNanos(...)
    target termination CountDownLatch.await()
      AQS records Thread.currentThread() as the waiter
        LockSupport.park()
```

`joinNanos` is a method on the target thread object, but executes in the caller's
thread. The latch and AQS belong to the completion condition; the saved waiter
identity belongs to the caller.

Current `OutcomeSupplier.get()` also calls `CountDownLatch.await()`. Its cached
outcome fast path skips waiting. Therefore it already obtains the join machinery's
virtual-thread parking whenever its *caller* has logical virtual-thread identity.
No new native-yield hook or joining helper thread is required in that case.

## Where the physical worker is released

`LockSupport.park` branches on `Thread.currentThread().isVirtual()`:

| Caller identity | Waiting operation |
|---|---|
| Platform thread | `Unsafe.park(false, 0)` disables that platform thread |
| Virtual thread, JDK 21 | `VirtualThreads.park()` forwards through `JavaLangAccess` to the current virtual thread's `park()` |
| Virtual thread, JDK 25 | `JavaLangAccess.parkVirtualThread()` calls the current virtual thread's `park()` directly |

The current virtual thread changes state to PARKING and invokes
`Continuation.yield(VTHREAD_SCOPE)`. This saves the caller's stack, including the
`join`/`await` frames. `VirtualThread.runContinuation` returns from `cont.run()`,
unmounts the virtual thread, and returns to its carrier scheduler. The carrier can
run another logical thread.

Mount/unmount explicitly changes the thread identity returned by
`Thread.currentThread()`. This is what gives separate logical callers their own
ThreadLocal state, interrupt state and ReentrantLock ownership while sharing one
physical platform carrier.

When the target finishes its body and its finally blocks, `afterDone` sets its state
to TERMINATED and counts down its termination latch. AQS unparks the recorded
logical caller. Unparking schedules that caller's `runContinuation`; mounting then
restores its logical thread identity before resuming its saved wait stack.

The platform-caller join path does not mount or create a logical caller. Wrapping
the target's join in a helper VT merely moves that helper's wait; the original
platform caller would still need to wait for the helper's result.

## Components to reuse in Continuations

1. Establish a logical caller task automatically at an existing dispatch entry,
   before its body starts. A thread-per-task owner with a VT factory provides this
   using public JDK APIs; a startup agent can hide the entry from business source.
2. Keep `Continuations.supplier(() -> ...); user.get()` inside that task unchanged.
   Existing `OutcomeSupplier.get` already has the JDK-friendly wait primitive.
3. Let the JDK own logical thread identity, mounting, interrupts and rescheduling.
   This avoids the stale carrier identity and lock ownership issues demonstrated
   by the raw native-continuation experiments.
4. Track task lifetime through actual thread/task exit, rather than Future.isDone
   alone: a cancelled Future can be done while the task still executes finally.

The existing logical-VT-owner experiment can use a custom scheduler backed by one
borrowed platform executor to prove exact carrier reuse. For production, a public
VT factory avoids depending on that additional internal scheduler constructor.

## Completion, timeout and interrupt details worth preserving

- `joinNanos` first checks termination, lazily installs its termination latch with
  CAS, then checks termination again before awaiting. This prevents lost wakeups
  when completion races registration. AQS park permits and condition rechecks also
  handle completion racing the actual park operation.
- A timed join returning false does not interrupt or cancel its target. An
  interrupted join interrupts the caller's wait, not the target's work.
- Joining an already-terminated VT returns on its fast path without clearing the
  caller's interrupt flag. Blockless's completed-outcome fast path has the same
  desirable property.
- Pending join/await is interruptible and clears the interrupt flag when throwing
  InterruptedException. Blockless adapts that checked exception to Supplier.get by
  restoring the flag and wrapping the exception.
- `join()` and `join(0)` wait indefinitely. `join(Duration.ZERO)` is a readiness
  check that returns immediately; it must not be used to demonstrate yielding.

## Source locations

| Component | JDK 21 | JDK 25 |
|---|---|---|
| Thread.join(long) | Thread.java:2055 | Thread.java:1863 |
| VirtualThread.joinNanos | VirtualThread.java:827 | VirtualThread.java:1003 |
| Termination latch creation | VirtualThread.java:1061 | VirtualThread.java:1319 |
| Termination notification | VirtualThread.java:512 | VirtualThread.java:661 |
| LockSupport caller dispatch | LockSupport.java:214 | LockSupport.java:216 |
| Current-VT park validation | System.java:2641 | System.java:2282 |
| VirtualThread.runContinuation | VirtualThread.java:221 | VirtualThread.java:267 |
| VirtualThread.mount/unmount | VirtualThread.java:357/387 | VirtualThread.java:482/512 |
| VirtualThread.park | VirtualThread.java:585 | VirtualThread.java:734 |

OpenJDK sources:

- https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/lang/VirtualThread.java#L827
- https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/util/concurrent/locks/LockSupport.java#L214
- https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/java/util/concurrent/locks/AbstractQueuedSynchronizer.java#L773
- https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/java/lang/VirtualThread.java#L1003
- https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/java/util/concurrent/locks/LockSupport.java#L216

No missed automatic-entry mechanism was found in join, joinNanos, CountDownLatch,
AQS, LockSupport, VirtualThreads or JavaLangAccess. The constructive component is
the JDK-owned logical caller, supplied at task entry; the wait primitive is already
present in Blockless.
