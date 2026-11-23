# Public-API virtual task entry

This alternative uses the existing stable `Blockless.supplier`. A virtual caller
is established before business code runs. The business method still reads:

```java
var user = Blockless.supplier(() -> loadUser(id));
return user.get();
```

`publicentry.PublicTaskExecutor.newExecutor()` is a 50-line `ExecutorService`
adapter around the public JDK virtual-thread-per-task executor. It inherits
`AbstractExecutorService.submit` and delegates `execute`, so cancelled Futures
do not cause Java 21 to forget a task before its `finally` exits.

## Installation choices

Configure the application's request/task executor once with
`PublicTaskExecutor.newExecutor()`. This requires no agent or JVM arguments.
The framework and its process lifetime remain application-owned.

For automatic entry at selected existing factory calls, build the optional agent:

```sh
JAVA_HOME=/path/to/jdk21 ASM_JAR=/path/to/asm-9.8.jar \
  ASM_COMMONS_JAR=/path/to/asm-commons-9.8.jar bash agent/build.sh
java "-javaagent:target/agent/public-entry-agent.jar=include=example.,io.grpc.internal." \
  -jar application.jar
```

Keep `public-entry-runtime.jar` beside the agent jar. The JDK-only helper is
installed through the manifest bootstrap search path. The agent transforms the
four fixed/cached `Executors` factory overloads and method references in selected
classes before they load. It does not change `Blockless` or `get()`.

Unlike the private-carrier prototype, this agent has no private JDK classes,
reflective constructor access, module opens, or `redefineModule` call. It still
requires the explicit `-javaagent` startup argument. ASM is privately relocated;
asm-commons is build-only, and the BSD notice is retained in the agent jar.

The optional [build-time weaver](build-time/README.md) performs the same selected
factory rewrite on compiled class directories before launch. It runs the result
with the helper on an ordinary classpath, without a startup agent or JVM flags.
This bounded proof is not yet a Maven/Gradle plugin or dependency-JAR rewriter.

## Execution-model changes and limits

- At `get()`, the logical caller is a **virtual thread**, because entry happened
  earlier. This does not capture a currently executing raw platform caller.
- The JDK selects shared carriers. A rewritten fixed size is validated positive
  but does not bound either logical concurrency or a private carrier pool.
  Supplied physical `ThreadFactory` arguments are validated non-null but are
  intentionally unused. The agent rejects carrier-count options.
- Single-thread/scheduled factories, direct constructors, already-loaded calls,
  and excluded prefixes remain unchanged. Concrete `ThreadPoolExecutor` casts,
  metrics, custom physical factories and serial-execution guarantees are not
  preserved by an opted-in fixed/cached factory rewrite.
- All roots and default carriers are daemon threads. No keeper or extra dedicated
  platform thread is created. An existing server or explicit application lifecycle
  must keep the JVM alive. A fire-and-forget `main` can exit with unfinished work;
  the subprocess probe demonstrates this limit.
- Independently spawned supplier bodies are not owned or cancelled by the request
  executor. Cancellation interrupts the logical caller, while shutdown waits for
  the actual owned caller cleanup. Applications still own child/resource lifetime.
- Public factories can inherit a submitting virtual parent's scheduler. Java 21
  and 25 source select the parent's scheduler when no scheduler is specified.
  Creating this owner at startup does not force all later submissions onto a
  specific global scheduler. External private schedulers must outlive their
  descendants; this alternative does not choose or own those schedulers.

The runtime itself is 82 lines of Java (owner plus four factory targets); the
agent is 147 lines. Shading/build support is another 85 lines, excluding tests and
documentation. The bootstrap runtime depends only on `java.base`.

## Verified behavior

Both scripts compile for Java 21. Neither passes exports, opens, preview arguments
or private-scheduler options. `run-probes.sh` tests explicit public entry;
`agent/run-smoke.sh` compares the same compiled standard-factory business code
without and with automatic entry.

```sh
JAVA_HOME=/path/to/jdk21 bash run-probes.sh
JAVA_HOME=/path/to/jdk21 bash agent/run-smoke.sh
```

`BLOCKLESS_CLASSPATH` can select precompiled stable core classes. The default is
the parent investigation's `target/stable-core`, built from upstream main
`3d070a3`. No continuation module or newly introduced supplier API is involved.

| Runtime | Public owner | Automatic factory entry | Direct JDK cancellation/finally control |
|---|---|---|---|
| Corretto 21.0.13.1+2 | Pass | Pass | Fails: termination precedes cancelled caller cleanup |
| Corretto 25.0.5.1+2 | Pass | Pass | Pass |
| Oracle 21.0.12.1+1 | Pass | Pass | Fails: termination precedes cancelled caller cleanup |
| Oracle 25.0.5.1+4 | Pass | Pass | Pass |

Positive probes hold query I/O and cleanup pending, verify actual values and
original failures, other-root progress, preserved caller locals/TLS/lock ownership,
and cancellation isolation. They explicitly observe virtual caller identity.
Two physical JDK pool workers were observed in each isolated public-owner run;
this is a measurement, not a portable per-owner worker-count contract.

The automatic probe's uninstrumented fixed-worker process exits nonzero after a
bounded occupied-worker diagnosis and value-preserving cleanup. It is not counted
as a successful nonblocking implementation. Automatic entry passes, including
isolated parent-null class loaders, incompatible application ASM and named modules.
`jdeps --jdk-internals` reports no internal dependency for the runtime helper.
