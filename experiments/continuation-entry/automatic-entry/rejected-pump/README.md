# Bounded review: helping from an unmanaged get

These are standalone mechanism probes, not an implementation of the production
Blockless API. They passed on Corretto 21.0.13.1+2 and 25.0.5.1+2. No production
classes are changed. Run with either JDK:

```
JAVA_HOME=/workspace/.tools/blockless/jdk21 ./run-probes.sh
JAVA_HOME=/workspace/.tools/blockless/jdk25 ./run-probes.sh
```

## Positive result

A raw platform task A can retain its caller frames inside get while servicing
queued task B as a real JDK virtual-thread root on the same physical worker.
The helper then returns A's actual value. B has isolated ThreadLocal state. This
is a genuine helping-loop mechanism; it does not capture or migrate A's stack.
The probe needs direct access to its original executor queue and tags internal
VT resume commands separately from queued user tasks.

## Why this cannot be treated as a transparent get fix

- `DynamicGetPumpProbe cancel`: A's FutureTask still records the physical worker
  as its runner. cancel(true) while B is mounted interrupts that physical
  worker, while B's logical interruption flag remains false. B's normal JDK
  unmount clears the carrier flag. A resumes without seeing its interruption.
- Pulling B from the queue skips the original ThreadPoolExecutor runWorker
  lifecycle: B's beforeExecute hook count is zero. Recreating logical task,
  queue, shutdown, rejection and hook ownership requires an executor adapter.
- `MonitorBoundaryProbe`: when raw A holds synchronized(monitor), B mounted as
  a VT on that same worker can enter the same monitor while A still holds it.
  Both tested JDKs report forbidden overlap=true. Switching B to a real VT fixes
  B's ordinary thread-local and ReentrantLock identity, but does not safely
  repurpose every raw caller stack with existing host-owned JVM monitors.

Expected cancellation evidence includes physical interrupted=true during B,
then physical interrupted=false and A after get interrupted=false after B
unmount. The cancellation probe exits successfully when this hazard is
reproduced; it does not certify that behavior as safe.

## Separate deployment finding: public scheduler lifetime

Using owned logical roots on the public default virtual-thread scheduler removes
private scheduler-constructor and java.lang-open dependencies. The inherited
AbstractExecutorService submit / delegate.execute TaskRunner ownership pattern
should remain to preserve actual finally accounting on JDK21.

`PublicSchedulerLifetimeProbe` proves a further execution-model difference:
standard default platform-pool tasks keep the JVM alive after original main
returns; public VT tasks and global carriers are daemon and do not. The
controller releases a blocked root only after main returns. The platform owner
stays alive and finishes; the VT process exits with the root unfinished.
Application lifecycle ownership or a deliberate liveness strategy is required
before treating a public-scheduler factory rewrite as a deployment replacement.

Such an opt-in rewrite also changes pool-size and ThreadFactory semantics. A
public scheduler does not honor the requested per-pool physical worker cap or
custom physical ThreadFactory. Current PR37 supplier internals independently
still need their export flag; a public owner alone does not remove that
production dependency.
