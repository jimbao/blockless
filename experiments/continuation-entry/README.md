# Automatic continuation entry: research

The latest [automatic executor-entry prototype](automatic-entry/README.md) runs
the unchanged immediate supplier/get code through selected standard executor
factories and the real Spring gRPC handler. It uses JDK logical virtual threads,
does not rewrite get, and passes with both the current and native-free suppliers.
Its scoped startup configuration and measured results are documented there.

## Earlier main and HTTP prototypes

The results below were recorded before PR37 removed its pending-status exception.
They describe the earlier raw main/HTTP prototypes and their original revision;
the latest executor-entry suite above tests the current contract.

The follow-up [virtual-thread join investigation](join/README.md) traces the JDK
wait path and tests it against the actual supplier, including automatic child
scheduler inheritance and a method-entry counterexample.

The exact `Continuations.supplier(...); user.get();` source now works in these
experiments with a managed caller entry. A pending `get()` releases the caller's
platform carrier and later returns the real value. The tests use the production
PR #37 classes; an experimental startup agent adds the managed wait to `get()`
in memory. Production source and PR #37 are unchanged.

This is a working prototype, not a completed implementation for arbitrary
already-running platform callers. Entry must be established before caller code
starts. The main example installs it with a startup agent; the HTTP example also
configures a request executor which owns complete logical tasks.

```java
var user = Continuations.supplier(() -> {
    var details = loadDetails(id);
    var permissions = loadPermissions(id);
    return buildUser(details, permissions);
});
var value = user.get();
```

No callback, completion-stage API, annotation, or explicit continuation wrapper
appears in this business code. See [UserExample.java](src/experiment/UserExample.java).
The deployment configuration is essential; copying that code into an unconfigured
platform callback still encounters the existing PR's pending-read exception.

## Stronger ownership proof

`run-virtual-owner-probes.sh` verifies the safer approach using the production
library unchanged, with no agent or get-method rewrite. An executor establishes
each request as a JDK-owned logical virtual thread before its code starts. The
test gives those logical threads one borrowed platform carrier. While one root
waits in `user.get()`, that same physical carrier runs another root.

Unlike raw native roots, the JDK supplies separate Thread identity, ThreadLocal
state, interrupt state and lock ownership. Cancellation and `shutdownNow` target
the intended logical roots. Shutdown waits for their actual `finally` blocks.
The independent review additionally tests a foreign FutureTask hidden in a
Runnable, which defeats the raw-native owner's cancellation guard.

`LogicalTaskExecutor` wraps the JDK's task-per-thread executor through
`AbstractExecutorService.execute`, so inherited `submit` also
tracks actual thread lifetime on Java 21. Direct Java 21 `ThreadPerTaskExecutor.submit`
can remove a cancelled task before its `finally` finishes; Java 25 changes that
implementation. The adapter avoids this difference.

The custom scheduler uses an internal constructor and needs
`--add-opens=java.base/java.lang=ALL-UNNAMED`. That is needed to prove use of the
same chosen physical carrier. A production default can use the public
`Thread.ofVirtual().factory()` with the same small lifetime adapter and let the
JDK choose carriers, avoiding this additional internal dependency. The public
factory control test runs without that extra opens flag and without an agent.

The logical caller reports `isVirtual() == true`; its physical execution is still
on the borrowed platform thread. This is an automatically established execution
boundary, not conversion of already-active unwrapped caller frames.

## What runs

`MainAgent` renames the selected main body and inserts a wrapper before its first
execution. `MainRuntime` runs that body in a native continuation on the original
JVM main thread. A queue serves scheduler commands while the body is suspended.
Idle scheduler waiting is distinct from waiting for one supplier: a command can
run on the same worker before that supplier completes. Root failures escape the
original JVM main after cleanup; the runtime does not call `System.exit`.

The agent also prepends a managed-wait hook to the actual
`Continuations$ContinuationResult.get()`. A pending read inside a managed flow
yields. After unwind, its owner registers readiness and schedules a serialized
resume. The existing implementation then reads its completed outcome. Outside
managed entries, the hook leaves the production behavior intact.

`ManagedOwner` separates logical requests from borrowed physical carriers. Its
shutdown rejects new requests but retains suspended requests and accepts their
resumptions. This fixes the early completion and lost cleanup found with a naive
executor wrapper. It intentionally differs from a serial application executor:
one carrier can serve several overlapping logical requests.

The HTTP experiment configures the standard `HttpServer.setExecutor` entry point.
Its platform-thread handler uses the code above through a nested method. While
the query is gated, a second real HTTP request runs on the exact same sole
platform carrier. The first request remains pending, then resumes and sends the
correct response after release. Both caller and body `finally` blocks run once.

## Reproduce

Use Java 21 or 25 and Maven. From the repository root:

```sh
mvn -pl blockless-continuations -am -DskipTests package
mvn dependency:get -Dartifact=org.ow2.asm:asm:9.8
export ASM_JAR="$HOME/.m2/repository/org/ow2/asm/asm/9.8/asm-9.8.jar"
bash experiments/continuation-entry/run-agent-probes.sh
bash experiments/continuation-entry/run-hazard-probes.sh
bash experiments/continuation-entry/run-virtual-owner-probes.sh
```

Set `JAVA_HOME` to the JDK being tested. The scripts compile with that JDK's
default target: the Java 25 run tests Java 25 application bytecode, not just
Java 21 bytecode on a newer runtime. ASM 9.8 supports that class-file version.
`BLOCKLESS_CLASSPATH` can point at previously built core and continuation classes.
The HTTP probe requires local loopback networking. ASM is an experiment build
dependency; no dependency was added to any production module.

The logical-VT ownership proof passed on all six cached runtimes:

| JVM | Java 21 | Java 25 |
|---|---|---|
| Eclipse Temurin / HotSpot | Pass | Pass |
| Oracle JDK / HotSpot | Pass | Pass |
| Amazon Corretto / HotSpot | Pass | Pass |

Borrowed carriers must outlive their logical executor. Cancelling the caller does
not cancel independently spawned supplier-body I/O; the current supplier API has
no child-task cancellation contract.

## Confirmed results

| Check | Java 21 | Java 25 |
|---|---|---|
| Actual supplier/get, delayed value, null and original failure identity | Pass | Pass |
| Nested caller frames/locals, post-get execution and finally once | Pass | Pass |
| Same original platform worker available while get is pending | Pass | Pass |
| Main thread identity, class-initializer context and initial interrupt | Pass | Pass |
| Parallel suppliers and 5,000 main-root completion races | Pass | Pass |
| Interrupted wait and interrupt/completion race | Pass | Pass |
| Uncaught main failure, cleanup and background-thread lifetime | Pass | Pass |
| Real HTTP request, same-carrier health request and graceful shutdown | Pass | Pass |
| Logical Future stays pending; shutdown waits for caller/body cleanup | Pass | Pass |
| 2,000 logical-task races and resume after borrowed carrier closes | Pass | Pass |
| Unmanaged platform callback remains outside the boundary | Confirmed limit | Confirmed limit |

## Problems reproduced, rather than inferred

`run-hazard-probes.sh` demonstrates why a positive throughput result is
insufficient evidence of the complete contract:

| Approach | Observed result |
|---|---|
| Raw continuation around an ordinary `FutureTask.run()` | Cancelling the suspended Future interrupts unrelated work now on its old carrier. |
| Several native logical jobs sharing one Thread identity | ThreadLocal mutations leak; unrelated work is treated as the reentrant lock owner. |
| ForkJoin compensation | Another task progresses by creating a replacement platform worker; the original remains waiting. |
| Redefining an active caller method | Existing frames continue original bytecode; only subsequent invocations use the replacement. |

The agent experiments additionally caught and fixed lost initial interrupt state
and a lost interrupt when completion won a suspension race.

## Limits of the raw native prototypes

- The agent supplies selected main entry; the request example explicitly supplies
  its logical executor entry. It does not automatically cover every callback,
  executor, class initializer, or arbitrary active stack in every framework.
- Raw native roots do not supply logical Thread identities. ThreadLocals,
  interrupts, saved Thread references, and thread-owned locks need proper
  ownership. Configured body context propagation does not solve caller ownership.
- To avoid a known stale-carrier interruption hazard, `ManagedOwner` declines
  Future cancellation, rejects obvious foreign cancellable tasks, and does not
  implement `shutdownNow`. Hiding a foreign FutureTask in a Runnable can still
  bypass that limited protection. This is not a safe general executor replacement.
- Native pinning, named modules, custom class loaders, unjoined children and
  arbitrary existing executor hooks are not covered by these proofs.

The strongest production direction is an automatically installed task boundary
whose logical threads are owned by the JDK's virtual-thread machinery. That
retains synchronous business code while supplying proper context, lock,
interruption and lifecycle ownership. A supplier call by itself does not install
an entry around frames which are already executing outside one.
