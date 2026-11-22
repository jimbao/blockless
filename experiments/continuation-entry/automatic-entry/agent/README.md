# Automatic executor entry experiment

The startup agent replaces selected calls to `Executors.newFixedThreadPool`
and `newCachedThreadPool`, including their `ThreadFactory` overloads and method
references. Their tasks start on virtual threads before business code executes,
so pending `Continuations.supplier(...).get()` calls park those virtual threads.
There is no injected `get()` hook or completion callback in business code.

```sh
JAVA_HOME=/path/to/jdk21 ASM_JAR=/path/to/asm-9.8.jar \
  ASM_COMMONS_JAR=/path/to/asm-commons-9.8.jar ./build.sh
java "-javaagent:../target/automatic-entry-agent.jar=include=example.,io.grpc.internal.;cachedCarriers=4" \
  -jar application.jar
```

`include` is a comma-separated list of class or package prefixes. Only factory
call sites in those classes are changed as the classes load. `io.grpc.internal.`
covers gRPC's shared cached executor factory. Keep the generated agent and
`automatic-entry-runtime.jar` together: the manifest installs that JDK-only helper
on the bootstrap search path, including for isolated application loaders.
Instrumentation supplies named-module reads without additional add-reads flags.
No application classpath change or core dependency is required.

ASM is relocated into the agent's private package using ASM's standard
ClassRemapper at build time. asm-commons and the relocation tool are build-only;
the agent does not use an application's potentially incompatible ASM classes.
The bundled [ASM BSD notice](ASM-LICENSE.txt) is retained in its META-INF directory.

The opt-in changes executor semantics:

- Fixed pool sizes bound **platform carriers**, with a virtual thread per task;
  they no longer bound the number of logical tasks in progress.
- Cached pools use `cachedCarriers` platform carriers, defaulting to four.
  Created carriers stay alive until owner shutdown; the original idle timeout
  is not retained. The provided `ThreadFactory` must create platform threads
  for the carriers.
- Logical futures and executor termination track task exit, including `finally`.
  The executor result is not a `ThreadPoolExecutor`; casts and its tuning methods
  are unsupported.

Single-thread and scheduled factories, constructors, raw threads, already-loaded
classes, and calls outside the configured prefixes are outside this experiment.
Independently spawned virtual children are not executor roots. Their later
resumes move to the shared pool after the owner retires; executor termination
does not join their lifetimes. Accepted carrier work still drains before the
physical workers terminate.

The runtime uses a private JDK virtual-thread builder to select its carrier
executor. The agent opens `java.base/java.lang` to the bootstrap helper and agent
modules programmatically (the latter also permits the carrier diagnostic);
no user `--add-opens` flag is needed. This remains experimental JVM access.
It is separate from the current PR37 library's
`--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED` requirement. The native-less
supplier research variant removes that separate export requirement.

Run `./run-smoke.sh` to compare the same factory-call bytecode with and without
the agent, including unchanged uncovered factories, a parent-null class loader,
an incompatible application ASM class, and a named module.
