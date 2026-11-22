# Strict immediate supplier/get acceptance

The same compiled `StrictSupplierEntryProbe` runs in three configurations. Its
business method creates one `Continuations.supplier`, performs blocking details and
permissions calls inside it, and immediately returns `user.get()`.
There is no `onComplete`, CompletionStage API, `awaitResult`, or explicit
continuation/task wrapper in that business method.

The I/O result is deliberately held while get is reached. A compatibility control
proves ordinary platform get returns the actual User without a pending exception.
Carrier-release acceptance without instrumentation fails after a bounded marker
wait, releases the query, verifies its value and cleanup, then reports the occupied
worker. With instrumentation, another task uses that same worker while get stays
pending. No business source is changed between launches.

## Deployment precondition proved here

The test source creates `Executors.newFixedThreadPool(1, countingPlatformFactory)`.
The startup agent rewrites that selected factory call site into an owner which
runs each logical task on a virtual thread, sharing the same one physical
platform carrier. Business code is entered as a logical VT before supplier/get;
an already-running uninstrumented platform frame is not captured retroactively.
There is no pending-status guard in get.

This is explicit startup opt-in and class-prefix selection. It changes the
selected factory's concurrency contract: its size bounds physical carriers,
while unfinished logical tasks may overlap. It is not a transparent preservation
of a serial executor's non-overlap guarantee or every ThreadPoolExecutor-specific
API. Do not describe it as arbitrary raw platform callers becoming suspendable.

## Run

Build the actual library classes and the startup-agent jar first, then:

```sh
JAVA_HOME=/path/to/jdk21 \
BLOCKLESS_CLASSPATH=/path/to/core/classes:/path/to/continuations/classes \
  bash experiments/continuation-entry/automatic-entry/tests/run-strict.sh
```

The script compiles the test once with `--release 21`. Each launch uses those
exact classes and classpath; the agent flag is the additional positive-run
configuration. The native production library still requires its existing
`--add-exports`. The agent opens java.lang for its scheduler and the physical
carrier diagnostic; no separate add-opens flag is passed by the user.

For the native-free candidate, set its classes in `BLOCKLESS_CLASSPATH` and
`STRICT_NATIVE_EXPORT=0`. That option omits add-exports entirely from both Java
launches. The test source and business get remain unchanged.

A 30-second process limit prevents a failed diagnostic from hanging the suite.
Raw compatibility/baseline/agent logs and their actual exit codes are preserved under
`automatic-entry/target/strict-tests` (override `STRICT_OUTPUT_DIR`). A baseline
zero exit, process timeout, or missing occupied-worker diagnosis fails the comparison.
Expected outcome: `compatibility_exit=0 baseline_exit=1 agent_exit=0`.

## Assertions

- The first get is pending while a different root runs on the exact same original
  carrier; no replacement physical worker is created to disguise blocking.
- The positive business caller explicitly has virtual-thread identity due to
  automatic entry. The unrelated root has a different logical identity.
- Saved locals, caller ThreadLocal state and ReentrantLock ownership survive.
  The unrelated root inherits neither context nor lock ownership.
- The body and caller are not replayed. Body and caller finally run exactly once.
- The Future remains pending through blocking query cleanup, and shutdown waits
  for the logical caller and its physical carrier to terminate.
- An explicitly failing query propagates its original exception instance.
- Cancellation interrupts only the logical caller; another root sharing its
  physical carrier stays untouched, and termination waits for caller finally.
- The final regression cancels a caller, fully terminates its executor while an
  independent direct-supplier body remains held, then releases that body. Its
  finally, logical identity, context, and cached outcome survive carrier retirement.
  Only the harness uses a bounded completion notification for this last check.

Supplier bodies remain independent: the owner does not join or cancel them.
The retiring scheduler keeps their later resumptions possible on a live carrier.
