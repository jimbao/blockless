# Compensation: more platform workers, without caller conversion

This alternative keeps the business caller as a **platform thread**. It can
increase throughput by adding platform workers while that original caller waits.
It does not free the original worker or pass the strict four-worker constraint.
No new supplier API, JVM internals, agent, exports, or opens are used.

## Whole-task adapter

One-time executor configuration:

```java
var requests = CompensatingTaskExecutor.newExecutor(4, 10);
```

Existing request code remains:

```java
var user = Blockless.supplier(() -> blockingIo());
return user.get();
```

The adapter declares each complete request managed-blocking to a ForkJoinPool.
Its target parallelism is four, but it can create **ten platform request workers**.
The original caller's frames and Thread identity remain on its original worker
through get. Queued work runs on additional workers. Supplier bodies still run
on the standard JDK virtual-thread scheduler.

This is deliberately coarse: compensation starts before running the entire task,
even if a particular task never waits. The maximum-worker cap limits that cost.
At the cap, the saturate predicate permits waiting without trying to create an
eleventh worker; other queued tasks wait until a worker becomes available.

Inherited AbstractExecutorService.submit creates FutureTasks, dispatched through
the pool's execute. A bare ForkJoinPool.submit instead creates ForkJoinTasks whose
cancel(true) does not interrupt the worker. The adapter uses FutureTask semantics
and clears the physical worker's interrupt flag at task boundaries, preserving
the flag inside the cancelled task while avoiding leakage to its successor.
Shutdown/termination track the real executing task through its finally block.

This requires choosing the executor once or having instrumentation install it.
It cannot compensate an arbitrary existing fixed ThreadPoolExecutor from inside
an unconfigured get. FJP workers are daemon threads, so the application still
owns JVM lifetime. Concrete ThreadPoolExecutor casts/hooks are not preserved.

## A more selective core hook

The isolated `shadow/OutcomeSupplier.java` changes only the internal wait to call
ForkJoinPool.managedBlock when the caller is a ForkJoinWorkerThread. It uses the
same cached outcome and preserves the existing public Supplier API. The producer
and ordinary virtual callers are unchanged.

This would require a small core implementation change, plus a request executor
configured to allow compensating workers. It does nothing for a plain fixed
ThreadPoolExecutor caller. When the pool refuses another spare, the shadow falls
back to ordinary latch waiting; a full compensation budget never becomes a
pending-result exception. It also only accounts for that get wait: the probe
reproduces starvation when the interrupted caller then blocks separately in its
finally at parallelism one. Whole-task compensation covers that later wait;
get-only compensation does not.

## Evidence

Corretto 21.0.13.1+2 and 25.0.5.1+2 pass the bounded correctness probes:

- A deliberately held get returns its actual value after release, preserving
  caller Thread identity and its thread-local value; caller/body finally run once.
- The original platform worker remains WAITING. The marker uses a **different**
  worker; compensation creates additional workers, not a continuation yield.
- Cancellation interrupts the intended caller. Shutdown waits its actual finally.
- The exact cancelled worker handles its next task with a clean interrupt flag.
- The selective-hook probe explicitly records the unmanaged-finally limitation.
- A one-worker pool that refuses compensation still returns the real pending
  value after release, with no extra worker and no pending-result exception.

Exploratory synthetic Java 21 results: 32 closed-loop clients, ten backend slots,
10 ms sleeping I/O, 1 s warmup and 3 s measurement, two active processors:

| Configuration | Requests/s | Peak request platform workers | Peak I/O bodies |
|---|---:|---:|---:|
| Fixed four | 389 | 4 | 4 |
| Plain FJP, parallelism four | 392 | 4 | 4 |
| Whole-task compensation, parallelism four/max ten | 983 | 10 | 10 |
| Fixed ten | 983 | 10 | 10 |

The shared VT scheduler had two platform carriers in every mode, in addition to
the request-worker counts above. These are synthetic results, not the real gRPC
benchmark. They show that 900+ is possible with platform callers by paying for
six extra request workers. They do not establish a four-thread nonblocking result.
The load capture preceded the interrupt-boundary cleanup regression; final-code
throughput is to be measured by the coordinated real-request comparison.

## Run

```sh
JAVA_HOME=/path/to/jdk21 bash run-probes.sh
JAVA_HOME=/path/to/jdk25 bash run-probes.sh
```

`BLOCKLESS_CLASSPATH` defaults to the parent investigation's compiled stable core
from upstream main. Point it at an ordinary core build when reproducing elsewhere.
`--load` additionally runs the synthetic comparison and the selective-hook FJP
case. No special runtime flags are needed; ActiveProcessorCount is only a common
resource setting for this fixture. Coordinate load runs with other benchmarks.
