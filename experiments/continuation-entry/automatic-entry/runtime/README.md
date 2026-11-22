# Automatic entry runtime (research)

`automaticentry.EntryRuntime` is the target of the startup agent's selected
`Executors.newFixedThreadPool` and `Executors.newCachedThreadPool` call-site rewrites.
Business code keeps its existing `ExecutorService` and supplier/get calls.

The fixed size bounds physical platform carriers. Logical virtual-thread roots are
unbounded. The cached variant uses a configurable carrier cap (default 4) and queues
carrier work. Carriers remain alive until owner shutdown: expiring every idle
carrier could otherwise let the JVM exit while daemon virtual roots are parked.
A supplied `ThreadFactory` creates the physical workers and must return platform
threads.

The runtime uses the internal `ThreadBuilders$VirtualThreadBuilder(Executor)`
constructor to place logical roots on those carriers. The agent opens `java.lang`
to the runtime's module before use. Runtime classes have no Blockless or framework
dependencies, so the same factory target can serve applications with child loaders.

Lifecycle is owned in two layers:

- Inherited `AbstractExecutorService.submit` creates a FutureTask, dispatched using
  the JDK logical owner's `execute`. This tracks actual task exit on Java 21 and 25,
  including when cancellation marks a Future done before user cleanup ends.
- `shutdown` rejects new logical roots and keeps carriers available until accepted
  roots finish. An independent virtual closer then shuts down the carriers; users
  do not have to call `awaitTermination` to avoid leaking physical workers.
- `shutdownNow` interrupts logical roots rather than unrelated work sharing their
  physical carriers. Carriers remain alive until root cleanup has finished.
- `awaitTermination` covers logical roots and physical carrier closure.

This opt-in changes the original pool's task concurrency: a one-carrier owner can
run another logical root while a previous root parks. It does not preserve a serial
application executor's task-ordering guarantee. The returned type is
`ExecutorService`; concrete ThreadPoolExecutor casts, direct executor constructors,
and unsupported factory APIs are outside the factory-rewrite experiment.

Only roots submitted to this owner are owned. Independently started child threads
are not included in termination, and root cancellation does not cancel their work.
Such children inherit their parent's scheduler. Once all owned roots exit, that
scheduler retires before the physical carriers close: later child scheduling uses
the common pool's live platform carriers. Accepted carrier work drains normally,
and a dispatch racing retirement falls back only after retirement is confirmed.
The child's logical identity, context and finally blocks survive this carrier
change; independently spawned work can finish after the owner terminates.

`runtime/probes/automaticentry/RetiringSchedulerProbe.java` verifies this lifetime
case and the rejection/retirement race on Java 21 and 25.
