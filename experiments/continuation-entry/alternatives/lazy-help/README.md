# Helping from a pending platform caller

This research route keeps the original caller A on its platform thread. While
A's `Blockless.supplier(...).get()` waits, that worker launches queued tasks B as
public JDK virtual threads. B uses the default shared virtual-thread scheduler;
it is never mounted on A's raw host stack. A later returns its actual supplier
value with its native frames, locals, context and lock ownership retained.

This reuses request-dispatch capacity. It does **not** unmount A's stack or make
A a virtual thread. Later helped requests B do enter virtual threads. The fixed
platform workers and the JDK's global carrier threads must both be counted.

## Required integration

Use `helping.LazyHelpingExecutor.newFixedThreadPool(4, platformThreadFactory)` as
the request/task executor. Business code remains:

```java
var user = Blockless.supplier(() -> loadUser(id));
return user.get();
```

The executor alone is insufficient. The proof shadows stable core's
`OutcomeSupplier` with two hooks: wait calls `WaitSupport.await(latch)`, and
publication calls `WaitSupport.signal(latch)` after countdown. The stock core
without those hooks still occupies all waiting request workers. No new supplier
API, private JDK class, reflection, agent, or export/open flag is used.

A production integration needs a wait-strategy hook in core or startup weaving
of these calls. The shadow's direct dependency on this research helper is not a
finished core integration. No PR37/PR40 source is changed by this experiment.

## Ownership and behavior

- Only platform workers belonging to this executor help its queue. Virtual and
  unmanaged callers keep the original latch wait. Arbitrary executor queues are
  not discovered automatically; other blocking APIs are not intercepted.
- Helped B threads are constructed on their original submitter and started later
  through a deferred Runnable plus the JDK task-per-thread owner's `execute`.
  This retains B's inheritable thread-local and class-loader snapshot, and tracks
  actual execution exit on Java 21 even when its Future finishes cancellation early.
- Ordinary caller TLS does not leak into B. Original native tasks retain normal
  platform-pool semantics; this is not automatic cleanup of all caller TLS.
- Cancellation interrupts A's actual platform thread or B's actual virtual thread.
  B never runs on A's raw host, avoiding the earlier nested-mount interrupt loss
  and monitor-ownership overlap.
- Shutdown uses one original pool worker as a finisher to join helped roots.
  No extra platform keeper is created. The VM stays alive after main returns
  until accepted helped work exits, when default non-daemon platform workers are used.
- `shutdownNow` interrupts roots before cancelling drained Futures, with all user
  cancellation hooks outside the executor's lock. One throwing `FutureTask.done`
  hook is collected and rethrown only after the remaining Futures have been cancelled.
- Independently spawned Blockless bodies remain unstructured, as in stable core.
  They are not cancelled or joined with a cancelled caller; the default shared
  scheduler remains available after this executor terminates.

The exposed type is `ExecutorService`, not an existing `ThreadPoolExecutor`.
Its concrete tuning APIs/hooks and serial task-concurrency assumptions are not
preserved. The helper does not limit virtual task concurrency. Start-time
ScopedValue inheritance and external private custom-scheduler interoperation are
outside this proof; standard platform/default-virtual submitters use global carriers.

## Build and test

```sh
JAVA_HOME=/path/to/jdk21 CORE_CLASSES=/path/to/stable-core/classes ./build.sh
JAVA_HOME=/path/to/jdk21 CORE_CLASSES=/path/to/stable-core/classes ./run-probes.sh
```

Put the produced runtime jar **before** stable core on the classpath, so its
`io.github.pjlabs.blockless.OutcomeSupplier` is selected. Tests set two active
processors to keep the default scheduler budget comparable; that option is not
required by the implementation. Repeat with Java 25.

The probes cover actual direct get, queued progress, context/lock isolation,
monitor exclusion, cancellation and actual cleanup, shutdownNow, a 2,000-task
registration/completion race, queued cancellation callbacks, and shutdown-only
VM lifetime. See [validation](VALIDATION.txt) for exact versions and the two
reproduced/fixed shutdown bugs. The independent throwing-callback regression is
in [the acceptance suite](../acceptance/admission/FailingCancellationCallbackProbe.java).
The exact frozen v3 artifact also passes supplier value/failure, foreign-task
cancellation, queued-callback ordering and throwing-callback acceptance on Oracle
21 and 25; the [exact-version log](results/v3-oracle-pass.txt) records all eight
successful invocations. The earlier broader probe matrices are identified
separately in validation rather than treated as exact-v3 results.

Root coordinates all real gRPC throughput measurements. No throughput claim is
made by these functional probes. The frozen v3 runtime is packaged with both
helper classes **and** its matching shadow OutcomeSupplier; helper classes alone
would silently exercise the old blocking wait.
