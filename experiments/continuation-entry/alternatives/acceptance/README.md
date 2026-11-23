# Acceptance rules for platform-caller alternatives

The business method must keep this shape, using the unchanged stable API:

```java
var user = Blockless.supplier(() -> blockingIo());
return user.get();
```

Harness Futures/latches are allowed to hold work and inspect it. Moving the
business response into a callback, reading get only after a readiness callback,
returning a placeholder, or treating a pending-read exception as success does
not satisfy this contract.

## Required evidence

1. Hold the I/O result while the caller reaches get. Get must stay pending and
   another admitted task must progress before that result is released.
2. Return the real value, or the original task failure, after the body and its
   finally blocks finish. Preserve caller identity, locals, context and locks;
   do not replay caller/body code or run caller finally during suspension.
3. State the caller's logical Thread type before get. Eager VT entry, a raw
   platform caller helping other work, and platform compensation are distinct.
4. Count request workers separately from shared VT carriers and JVM service
   threads. A public global scheduler does not prove reuse of an original
   specified carrier. More compensating platform workers do not release the
   original waiting workers or satisfy a fixed-four-worker resource claim.
5. Verify cancellation isolation and actual cleanup/termination, including a
   foreign FutureTask hidden inside a Runnable. A Future being done is not
   proof that its executing task and finally have exited.
6. Measure throughput separately over the real handler, with immediate get and
   response afterward. Functional progress alone is not evidence of 900 rps.

## Reusable probes

`SupplierContractProbe` checks the held direct get, actual User and original
failure, state isolation and graceful shutdown. Its marker rejects inherited
request state; the provider may legitimately give unrelated work an outside
snapshot or no inherited value. `ForeignTaskCancellationProbe` independently
checks cancellation of caller A and a foreign helped task B, then releases
their finally blocks in both orders. Their independent producer may finish
after the owner exits, as the existing Blockless contract permits.

Run with `JAVA_HOME` selecting a JDK and `CANDIDATE_CLASSES` before stable core:

```sh
CANDIDATE_CLASSES=/path/to/public-entry/target/classes ./run-probes.sh \
  'publicentry.PublicTaskExecutor#newExecutor' virtual
CANDIDATE_CLASSES=/path/to/lazy-help/target/classes ./run-probes.sh \
  'helping.LazyHelpingExecutor#newFixedThreadPool' platform
```

The fixed-one-thread negative control is `./run-probes.sh fixed platform`.
It verifies the actual value and cleanup after release, then exits **nonzero**
with `DISPATCH_STALLED`: the original platform worker remained occupied.
The public JDK control is `./run-probes.sh virtual virtual`.

Success is labeled `FUNCTIONAL_PASS` or `LIFECYCLE_PASS`. Neither asserts a
throughput result or a global platform-thread budget. The tracked factory count
is separate from new native-thread snapshots and the process's total peak
platform count. Global counts include JVM service/compiler threads. `isVirtual`
observations are semantic facts; carrier identity is not inferred from them.

## Independent results

On Corretto 21/25 and Oracle 21/25, public entry and lazy help passed both the
functional and foreign-cancellation probes. Public entry reported virtual
caller/marker and no owned platform factory threads; global carriers were
observed. Lazy help reported a platform caller, virtual marker and one original
tracked platform worker, plus shared global carriers.

Two concrete lazy-help shutdown flaws were reproduced with bounded diagnostics:

- `QueuedCancellationCallbackProbe`: v1 cancellation callbacks ran before native
  interruption and under the owner lock. A callback could not observe native
  cleanup querying owner state. v1 fails; v2 passes on all four SDKs.
- `FailingCancellationCallbackProbe`: v2 stops cancelling its drained queue if a
  foreign FutureTask.done throws, leaving later accepted Futures pending after
  termination. The Java 21 reproducer fails with `QUEUED_FUTURE_STRANDED`.
  Frozen v3 attempts every drained cancellation before propagating the original
  callback failure. Both queued-callback regressions pass on Corretto and Oracle
  21 and 25 against v3 SHA-256
  `8031bd954ba19c7e16cf09f49fc9ef9d54f09416e3316685eb2c2c343849b4b6`.
  The exact frozen v3 bundle also passes the functional and foreign-cancellation
  probes on both Oracle releases; see the
  [eight-run Oracle record](../lazy-help/results/v3-oracle-pass.txt).
  The negative test settles its orphaned Future before reporting failure, so the
  diagnostic itself leaks no work.

These are ordinary supported FutureTask extension hooks, not claims that every
arbitrary user callback must finish. The owner must avoid holding its lock while
calling user code and must settle its drained tasks even if one callback fails.

## Scope matrix

| Alternative | Caller while get waits | Capacity mechanism | Main boundary |
|---|---|---|---|
| Existing raw fixed pool | Platform | Worker remains occupied | Fails progress gate |
| Public eager entry | Virtual | JDK parks caller on shared carriers | Task/executor entry changes; application owns daemon lifetime |
| Lazy help | Original platform | Caller dispatches queued work onto global VTs | Known helping executor and core wait/completion hook; native frames remain |
| Platform compensation | Platform | Adds replacement platform workers | Resource tradeoff, not four-worker release |
| Exact callback entry | Virtual inside selected callback loop | Hands complete loop to a public VT | Framework response/transport must own lifetime; outer pool/Future ownership is not preserved |
| Portable CPS/stack rewriting | Depends on owner | Saves instrumented frames | Requires instrumented call chain and separate identity/context/lifecycle evidence |

Callback-agent source and its explicit negative probes were reviewed with its
advisor: generic Runnable/Future handoff completes the outer owner too early,
and service-method handoff closes outer advice scope before query completion.
The narrower gRPC SerializingExecutor entry relies on framework request lifetime;
it does not inherit a generic ExecutorService shutdown guarantee.

Current evidence is a research acceptance result, not certification of all
frameworks, custom executor hooks, ordinary blocking outside get, or all future
JDK implementations. Root owns the real request load tests and resource accounting.
