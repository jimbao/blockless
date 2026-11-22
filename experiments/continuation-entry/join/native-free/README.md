# Native-free supplier runtime: isolated alternative

This research implementation preserves PR #37's current pending-platform-read
restriction. It does **not** solve automatic entry for an unmanaged platform
caller. Production source and PR #37 remain unchanged.

The join investigation showed that the core result wait already uses the right
JDK primitive. A library-owned native continuation around an internal waiting
task is therefore unnecessary for this implementation's existing behavior.

This alternative:

1. Captures configured body context and constructs an unstarted virtual producer
   where supplier is invoked. The JDK captures the caller VT's scheduler there.
2. Starts that producer only after the supplied executor accepts initial dispatch.
3. Publishes a cached value or failure after the callable, its finally blocks, and
   context restoration finish. Small serialized drains preserve inline-executor
   support and the shared cross-runner trampoline.
4. Reuses core OutcomeSupplier's ready fast path and CountDownLatch wait. It
   neither calls producer.join nor invokes private Continuation APIs.

The executor workers never wait for the body. If completion dispatch is rejected,
the already-running producer VT publishes through the trampoline. Launching a new
fallback VT there was tested and rejected: it can inherit a closed custom
scheduler and leave the actual outcome unpublished. Inline publication avoids
that additional scheduling dependency after user cleanup is already complete.

## Verified behavior

- All 34 applicable existing core and continuation tests pass on Java 21 and 25,
  without add-exports or add-opens in the library test run. The original test
  requiring failure without native exports is intentionally excluded; no test
  source is copied or modified.
- Deferred start retains the constructing VT's single-carrier scheduler,
  InheritableThreadLocal snapshot and context class loader. Ordinary ThreadLocal
  propagation still requires configured propagators.
- Initial rejection leaves the producer unstarted and its body unexecuted.
- Closing the producer's custom carrier while the body is still mounted does
  not lose publication of its completed value or execute finally twice.

Construction-time inheritance differs from PR #37's current implementation,
which constructs the body on a dispatch worker. ScopedValue/container inheritance
is a separate start-time mechanism; no new ScopedValue guarantee is claimed.
This is a candidate simplification, not a finished replacement feature.

## Reproduce

```sh
JAVA_HOME=/path/to/jdk21 \
JUNIT_JAR=/path/to/junit-platform-console-standalone-1.11.4.jar \
  bash experiments/continuation-entry/run-native-free-probes.sh
```

Repeat with Java 25. JUnit is only a test dependency. The runtime uses public Java
APIs and adds no dependency to core. The separate inheritance probes open
java.lang only to configure and inspect a single chosen carrier for the test.
