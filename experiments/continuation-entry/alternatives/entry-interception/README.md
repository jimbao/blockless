# Framework callback-loop entry

This alternative weaves the existing **whole callback drain loop** into a public
JDK virtual-thread entry. It does not replace executor factories or the stable
`Blockless.supplier` API, and it does not intercept get.

For the cached grpc-core 1.63.0 implementation:

```sh
JAVA_HOME=/path/to/jdk21 ASM_JAR=/path/to/asm-9.8.jar \
  ASM_COMMONS_JAR=/path/to/asm-commons-9.8.jar ./build.sh
java '-javaagent:/path/callback-entry-agent.jar=class=io.grpc.internal.SerializingExecutor;method=run' ...
```

The agent renames the selected void method's body and inserts a wrapper which
starts a public `Thread.startVirtualThread` task containing that whole body. No
runtime helper, private JDK constructor, exports, opens or preview flags are used.
The startup agent itself is required. Its ASM dependency is privately relocated,
with the upstream BSD notice retained. This is implementation-specific gRPC
weaving, not a claim of framework-independent entry coverage.

The original platform executor worker returns from its handoff and can dispatch
other requests. Business code enters as a **logical virtual thread** before it
calls `Blockless.supplier(...).get()`. The JDK default scheduler supplies additional
shared carrier threads; this does not mount user work on the original chosen
platform worker or retroactively capture a raw platform caller's active frames.

`SerializingExecutor.run` retains its existing run-state and queue processing
until the virtual drain actually finishes. Consequently, callbacks queued for
the same stream do not overtake a callback suspended in get. Existing queued
gRPC ContextRunnable wrappers attach context inside the virtual drain, before
user advice/service invocation. Scope created inside that entry survives normal
suspension. Ordinary state established outside the selected entry still needs
appropriate capture; this is not automatic transaction-context transfer into
independent supplier bodies.

## Verified boundaries

`run-probes.sh` uses the actual cached grpc-core SerializingExecutor and the
unchanged stable core from upstream main `3d070a3`. Override `CORE_CLASSPATH` and
`GRPC_LIBS` to point at those classes and the cached application dependencies.

On Corretto and Oracle, Java 21 and 25, the positive proof returns the actual User
from immediate get while the **same sole original platform worker** runs a marker.
It checks logical VT identity, caller locals/ThreadLocal/locks, gRPC Context,
body/caller finally once, a held body-cleanup phase, unrelated-request isolation,
and same-stream ordering. The identical no-agent process exits 1 after diagnosing
the occupied worker, releasing the body, and verifying its real result.

These are asynchronous callback/request semantics, not generic executor semantics:

- The original platform pool can terminate while the logical request is pending.
  A callback queued into that live drain still finishes; a new drain rejects after
  dispatcher shutdown. A framework must own request completion and keep the
  application and dispatcher alive until required requests finish. Public virtual
  threads are daemon; this experiment adds no non-daemon lifetime keeper.
- Selecting arbitrary `Runnable.run` is unsafe. The negative proof shows its
  surrounding Future and executor finish before the actual get/body finally.
- Selecting a service method inside an outer advice/transaction scope is unsafe.
  The second negative proof shows that outer scope closes before the real query.
  Select an earlier framework entry so user advice runs inside the virtual task.
- The prototype does not preserve method annotations on its generated wrapper,
  rewrite active/already-loaded methods, cancel independent producer threads, or
  establish universal custom-executor lifecycle semantics. The target grpc loop
  has no method annotations. Current framework hook exceptions, callback queue
  state and shutdown/version coupling need review before production release.

Run the probe with `JAVA_HOME=/path/to/jdk21 ./run-probes.sh`; repeat with Java 25.
Raw logs and exit codes are under `target/`. No application load was launched by
this advisor, so no new requests-per-second result is claimed here. Root owns the
real gRPC benchmark and its worker/carrier accounting. Neither PR37 nor PR40 was
modified.
