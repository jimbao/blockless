# Portable bytecode continuations: bounded proof

This alternative captures an enhanced Java caller into a heap-backed state machine
while that caller remains a **platform thread** (`isVirtual() == false`). It differs
from the automatic virtual-thread-entry prototype and does not call JDK private
continuation APIs or select a private virtual-thread scheduler.

The source business call remains:

```java
var user = Blockless.supplier(() -> blockingWork());
return user.get();
```

The underlying Blockless producer is the ordinary public virtual-thread supplier.
Build-time enhancement makes the *caller* suspend at its pending get, unwinding
its platform carrier. Completion schedules a continuation resume, and get returns
the actual value with saved locals and caller finally intact.

## What actually ran

`run-probes.sh` uses the maintained **portable** Tascalate JavaFlow 2.9.0 API,
not its experimental `api-jvmc` artifact. It:

1. Compiles the caller and a research copy of core OutcomeSupplier.
2. Rewrites the ordinary `Supplier.get()` call site to a continuable static bridge.
   This is needed because the JDK Supplier interface carries no continuation metadata.
3. Enhances the annotated call chain using JavaFlow's standalone build-time tool.
4. Launches the enhanced program with a plain `java -cp ...` command: **no agent,
   exports, opens, preview flags or private scheduler constructor at runtime**.

The core research change exposes a detached copy of its completion future through
an internal metadata interface. Its normal get/latch implementation is preserved.
The public Blockless API and the business get source are unchanged. The new bridge
suspends only inside the owner's enhanced execution slices; normal callers retain
normal Supplier waiting semantics.

Runtime dependencies: JavaFlow's portable API and SLF4J API (the proof uses its
no-op logging fallback). The shaded JavaFlow rewriter and ASM are build tools.
The mainline JavaFlow API has supported multiple Java releases; its provider and
state-machine machinery are substantial third-party code, not the small source
bridge in this directory.

## Results and important ownership limits

The positive bounded case verifies:

- A pending ordinary Blockless get yields the enhanced caller; another task runs
  on the **same sole physical platform carrier** before the I/O gate is released.
- The resumed get returns the actual string, saved locals survive, the caller and
  producer are not replayed, and their finally blocks run once.
- The caller stays platform-threaded before and after get. The blocking producer
  remains an ordinary JDK virtual thread.
- Explicit termination of a suspended continuation executes caller finally once.

These do **not** establish a safe general executor replacement:

- On the same carrier, another logical task sees the suspended caller's ordinary
  ThreadLocal value and can acquire its ReentrantLock reentrantly. There is no
  separate logical Thread identity.
- On a different resume carrier, that lock's unlock throws
  `IllegalMonitorStateException` during caller finally. Subsequent cleanup in that
  finally does not execute; the harness releases the original lock explicitly.
- `Owner` is a small logical-flow driver. General ExecutorService Future semantics,
  interruption, accepted-task termination and arbitrary framework callback ownership
  are not implemented by this proof.
- The complete suspending call chain must be enhanced. The prototype explicitly
  marks participating methods with `@continuable` and supplies an owner before
  they execute. It cannot retroactively capture arbitrary unenhanced active frames.

Turning off the get-call rewrite yields a bounded negative control: the get returns
its real value after harness cleanup, but nonblocking acceptance fails with
`call stack did not suspend`. Simply adding a dependency or annotations does not
change the JDK Supplier/get wait.

**Verdict:** a working, portable platform-caller stack-saving mechanism, suitable
for further work under explicitly non-thread-affine caller restrictions. It fails
the full transparent context/lock contract. No 900-rps performance result is
claimed here; root owns any load comparison and must retain those limits.

## Reproduce

Compile the upstream Blockless core first, then:

```sh
JAVA_HOME=/path/to/jdk21 \
CORE_CLASSES=/path/to/upstream/core/classes \
ASM_JAR=/path/to/asm-9.8.jar \
  bash experiments/continuation-entry/alternatives/stackful/run-probes.sh
```

Repeat with Java 25. The script downloads pinned Maven Central artifacts and checks
their published digests. Set `CPS_REWRITE_GET=0` for the expected negative control.
The script builds only its own target directory. It does not modify PR37, PR40 or
the original core checkout. The observed runs used core from upstream main
`3d070a36f85f68e91d360bfef7f232f4aa1dabad`.

Sources:

- [JavaFlow maintained portable API versus experimental native backend](https://github.com/vsilaev/tascalate-javaflow/tree/2.9.0)
- [Published portable API 2.9.0](https://repo.maven.apache.org/maven2/net/tascalate/javaflow/net.tascalate.javaflow.api/2.9.0/)
- [Published build-time enhancer 2.9.0](https://repo.maven.apache.org/maven2/net/tascalate/javaflow/net.tascalate.javaflow.tools.jar/2.9.0/)

Kotlin-style CPS explains the mechanism, but Kotlin's runtime alone does not weave
ordinary Java Supplier callers. This probe validates a concrete Java bytecode
provider rather than claiming Kotlin interoperability or wider adoption.
