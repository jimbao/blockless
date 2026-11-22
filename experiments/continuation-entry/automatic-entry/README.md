# Automatic executor entry: working prototype

Business code uses one supplier and reads it immediately:

```java
var user = Continuations.supplier(() -> {
    var details = loadDetails(id);
    var permissions = loadPermissions(id);
    return buildUser(details, permissions);
});
var value = user.get();
```

Opt in at startup, before the selected executor factories run:

```sh
java "-javaagent:/path/automatic-entry-agent.jar=include=example.,io.grpc.internal.;cachedCarriers=4" ...
```

The agent changes selected fixed/cached executor factories to establish a JDK
virtual thread for each task. Pending `get()` parks that logical caller; its
platform carrier can run other tasks. No business callback, `get()` rewrite,
explicit continuation wrapper, or completion-stage API is needed.

This is an entry configuration. It does not capture arbitrary already-running
platform frames. Uninstrumented platform callers wait for the actual value on
their calling thread. Pending status never throws. Pool sizes now bound physical carriers rather than
logical concurrency; this is an experimental execution-model change.

## Verified

| Runtime | Current PR supplier | Native-free supplier | Real strict gRPC handler |
|---|---|---|---|
| Amazon Corretto 21.0.13.1+2 | Pass | Pass | Pass |
| Amazon Corretto 25.0.5.1+2 | Pass | Pass | Pass with native-free supplier |

The identical compiled strict test fails carrier-release acceptance without the
agent and succeeds with it. The unconfigured get still returns its actual value
after the harness releases the body; the failure reports its occupied worker.
The positive case holds I/O pending while the **same sole physical carrier**
executes another task, then returns the actual User. Locals, caller ThreadLocal,
lock ownership, interruption, failure identity, and finally/termination behavior
are checked. Independent supplier bodies can finish after a cancelled caller's
executor closes; a retiring scheduler keeps their later resumptions runnable.

The real gRPC handler sends its response **after immediate `get()` returns**.
Five measured runs on Java 21, with PostgreSQL, 10 connections, 10 ms query delay,
32 concurrent RPCs, and four platform workers/carriers gave:

| Query execution | Median requests/s | Failed measured requests |
|---|---:|---:|
| Four platform workers, supplier + immediate get | 368 | 0 / 5,611 |
| Automatic entry, supplier + immediate get | 945 | 0 / 13,998 |
| Standard virtual-thread executor, supplier + immediate get | 942 | 0 / 14,258 |
| Standard virtual-thread executor, direct query control | 945 | 0 / 13,885 |

These are the native-free supplier results: about 2.57 times the waiting platform
control, and comparable to the virtual-thread controls in this I/O-bound test.
The JVM had two active processors; the standard VT controls use its shared JDK
scheduler. Database saturation and run-to-run variation limit CPU/overhead claims.
The unconfigured RPC also waits and returns its value; its worker remains
occupied. Zero errors in the managed path does not come from moving get into a
completion callback. A separate held-query case passes with the original gRPC
cached executor and only its framework factory instrumented, without the
application executor configuration used for the bounded performance controls.

## Build, evidence, and scope

- [Agent build and selection](agent/README.md)
- [Runtime ownership and carrier retirement](runtime/README.md)
- [Strict supplier/get acceptance](tests/README.md)
- [Real gRPC fixture, runner and results](network/README.md)
- [Native-free supplier source](../join/native-free/README.md)
- [Rejected raw-caller helping loop: reproduced cancellation and monitor failures](rejected-pump/README.md)

The native-free supplier needs no exports. The agent still opens java.lang
programmatically for its private scheduler constructor. Current PR37 needs its
existing native export when that implementation is selected.

Only the selected four factory overloads are covered. Direct executor
constructors, raw threads, scheduled/single-thread factories, and already-loaded
call sites remain outside this prototype. Ordinary JDK virtual-thread pinning
limits apply. The bootstrap helper supports isolated class loaders and named
modules; private ASM relocation avoids an application's conflicting ASM version.
PR37 contains the pending-check removal. The entry agent and native-free runtime
stay on the research branch.
