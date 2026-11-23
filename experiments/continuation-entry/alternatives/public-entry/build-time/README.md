# Build-time public factory entry

This alternative moves the factory-call transformation from a startup agent to
a build step. The application source still contains ordinary `Executors` calls
and stable `Blockless.supplier(...).get()`. The woven program runs with the
JDK-only `publicentry.EntryRuntime` helper on its normal classpath.

No Java agent, exports, opens, preview arguments, ASM dependency or private JDK
API is required by the running application. ASM is used by the build tool only.
The factory/runtime semantics are exactly the public entry mode described in the
[parent README](../README.md): virtual logical roots, shared JDK carriers,
intentionally unused physical factories/pool sizes, and application-owned JVM
lifetime. The raw platform stack is not captured at get.

## Prove it

```sh
JAVA_HOME=/path/to/jdk21 ASM_JAR=/path/to/asm-9.8.jar bash run-probe.sh
JAVA_HOME=/path/to/jdk25 ASM_JAR=/path/to/asm-9.8.jar bash run-probe.sh
```

The script compiles the same `AutomaticEntryProbe` used for the startup-agent
proof, plus an initialization trap. The original native-worker process fails
carrier-release acceptance with exit 1, after reading its real value and cleaning
up. `OfflineEntryWeaver` then reads the class files as bytes and rewrites selected
fixed/cached factory instructions and method-reference handles. It never loads
or initializes the application classes: they are absent from its own classpath,
and a selected input class has an intentionally throwing static initializer.

The final launch is plain Java:

```sh
java -cp woven-classes:stable-core-classes:public-entry-runtime.jar \
  example.publicentry.AutomaticEntryProbe automatic
```

That process returns the actual value from immediate stable supplier/get while
another logical task progresses before I/O release. Four factory overloads and
method references are changed; single/scheduled and excluded factories remain
unchanged. All body finally checks pass. The test harness changes no business
supplier/get source and uses no completion callbacks in it.

The tool can also be invoked directly:

```sh
java -cp weaver-classes:asm-9.8.jar OfflineEntryWeaver \
  input-classes woven-classes example.,my.application.
```

An empty output directory separate from the input is required. Nonclass resources and
unselected classes are copied unchanged. The demonstration script builds the
82-line public runtime itself; no agent build or asm-commons dependency is needed.

## Bounds of this proof

- This is a class-directory tool, not a production Maven/Gradle plugin or JAR
  rewriter. Framework factories inside dependency JARs must be transformed and
  repackaged by an appropriate build integration; weaving only application
  classes does not automatically cover those dependencies.
- The helper must be visible to transformed callers. This proof covers a normal
  classpath application. Named-module readability and isolated application
  loaders are not claimed for this mode; the startup agent's automatic module
  reads/bootstrap installation do not happen here.
- There is no live attach or already-running-frame transformation. The build must
  opt into changed fixed/cached execution semantics before application startup.

The standalone offline tool is 128 lines of Java; the reproduction driver adds
46 lines of shell. It reuses the existing 82-line public runtime with no new
runtime machinery.
