# Platform-caller alternatives

Research for synchronous Java using the existing stable API:

```java
var user = Blockless.supplier(() -> loadUser(id));
return user.get();
```

The real gRPC handler calls `get()` immediately and sends its response afterward.
Pending results never become placeholders or pending-result exceptions. PR37 and
PR40 are unchanged; these experiments live on a separate research branch.

## Real gRPC results

Corretto 21, four requested dispatch workers, two active processors, ten database
connections, `pg_sleep(0.01)`, 32 concurrent requests. Each fresh server received
ten seconds of initial warmup, then five samples with one second warmup and
three seconds measurement. Values below are the median of those five samples.

| Approach | Requests/s | Caller at get | Request platform workers observed | Integration |
|---|---:|---|---:|---|
| Ordinary fixed pool | 366 | Platform | 4 | Baseline |
| Public factory-entry agent | 933 | Virtual | 0 dedicated | Selected factory calls rewritten at startup |
| Build-time factory entry | 940 | Virtual | 0 dedicated | Same rewrite during build; no runtime agent |
| Whole gRPC callback entry | 946 | Virtual | 4 dispatch workers | Framework-specific startup agent |
| Lazy queue helping | 944 | Original callers platform; assisted callers virtual | 4 | Helping executor and two core wait hooks |
| Platform compensation | 920 | Platform | 10 | Managed blocking adds replacement workers |
| Standard virtual-thread executor | 944 | Virtual | 0 dedicated | Control configured at server entry |

All seven variants observed **two shared JDK virtual-thread carriers** in the
before/after snapshots, in addition to the request workers listed. These are
snapshots, not a global peak limit. Netty, HTTP control and JVM service threads
also exist. Compensation's ten platform workers are separate from its two
observed shared carriers.

**90,703 measured RPCs completed with zero errors.** A separate held-I/O check
verified real database values, caller context and cleanup. With all four initial
requests pending, a health RPC progressed for each alternative; the fixed-pool
negative control timed out until I/O release. All seven held-request checks also
passed on Corretto 25. These are local comparisons, not portable throughput
guarantees or evidence of a ranking among the approximately 940-rps approaches.

See [network results and reproduction](network/README.md), including the earlier
cold runs and unsuccessful private-scheduler reference runs.

## What the alternatives buy

**[Public entry](public-entry/README.md)** is the smallest general task-entry
implementation: an 82-line JDK-only runtime, plus either a startup agent or a
128-line [build-time weaver](public-entry/build-time/README.md). Ordinary blocking
inside the supplier runs on its existing Blockless virtual thread; the caller
also parks virtually. No private JDK APIs or exports/opens are needed. Changing
the application executor explicitly needs neither weaving nor a startup flag.
Automatic rewriting is opt-in: fixed sizes and physical thread factories no
longer control execution. The application must own the lifetime of daemon tasks.

**[Lazy helping](lazy-help/README.md)** addresses an already-running platform
caller. Its native frames, ordinary locals, context and lock ownership stay in
place. While its get is pending, it dequeues other admitted work and starts that
work on public virtual threads. The original four workers remain available to
dispatch queued work; the original caller is not converted into a virtual thread.
The 944-rps load includes both kinds of caller: 3,359 platform and 23,801 virtual
query entries across warmup and measurement. This needs a known helping executor
and two core wait/completion hooks, not a new supplier API. The runtime is 370
lines plus those hooks. It does not help arbitrary blocking outside Blockless get.

**[Callback entry](entry-interception/README.md)** moves the entire existing gRPC
drain loop into a virtual thread before user advice executes. It preserves stream
ordering in the tested gRPC version, but request/transport lifetime belongs to the
framework. The original executor can terminate before its logical request ends;
this is not a generic ExecutorService replacement.

**[Compensation](compensation/README.md)** reaches the target with public APIs
and platform callers by spending more platform threads. It does not satisfy a
fixed-four-worker resource constraint.

**[Portable bytecode continuations](stackful/README.md)** really save and resume
enhanced platform callers without private JVM APIs. The proof retains locals and
finally, but ordinary ThreadLocal and lock ownership are unsafe when other flows
share or change carriers. It fails the transparent contract and was not benchmarked.

The practical small entry solution is public virtual-thread task entry, installed
once at the application executor or selected factory calls. Lazy helping is the
candidate when existing platform caller frames must remain intact; its extra core
and executor lifecycle integration needs production review. None of these makes
an arbitrary active platform stack a virtual-thread stack at an ordinary method
call.

## Independent validation

[Acceptance probes](acceptance/README.md) cover actual values/original failures,
context and lock isolation, cancellation and actual finally/termination ownership.
Public entry and lazy helping passed on Corretto and Oracle 21/25. Two reproduced
lazy-help shutdown defects have explicit red/green probes. The final v3 value,
failure, foreign-cancellation and both shutdown-regression probes passed on all
four runtimes. The real gRPC v3 check passed on Corretto 21/25.
