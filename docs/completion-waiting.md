# Predictable waiting with Blockless

`CompletableFuture.join()` can do more than wait for a result. In OpenJDK 21.0.13, its execution depends on completion timing and the calling thread.

| Condition | `CompletableFuture.join()` behavior |
|---|---|
| Result already available at the initial check | Returns the result or throws its failure directly |
| Enters the waiting path | Observes completion, then calls `postComplete()` before returning; this can execute pending non-async callbacks on the same future |
| Waiting caller is a compatible ForkJoinPool worker | Can also help execute queued asynchronous completion tasks before blocking |

A callback that runs on the joining thread can delay `join()` returning even after the result is available. Which thread runs a non-async callback depends on scheduling.

## Conclusion

`join()` combines waiting for a result with helping execute completion work. This makes the caller's progress depend on callback execution and completion timing, even after its requested result is available.

The controlled example demonstrates a correctness consequence: the joining caller executes a callback that waits for code after `join()` to run. Neither can advance. The Blockless supplier wait avoids that dependency by returning the stored outcome without taking over the callback.

**Separating result observation from completion processing is a concrete benefit of Blockless's waiting model.** It avoids the waiting-path coupling reproduced here. Registration timing and ForkJoinPool compensation remain separate concerns, described below.

## Example: joining can execute another callback

```java
CompletableFuture<Response> response = client.fetchAsync().toCompletableFuture();
response.thenAccept(this::recordMetrics);

Response value = response.join();
```

If `join()` enters its waiting path, the joining thread can execute the pending `recordMetrics` callback before returning. It does not do this on the already-completed fast path. Neither path guarantees that every callback has finished when `join()` returns.

## Blockless uses a separate outcome

```java
CompletionStage<Response> response = client.fetchAsync();
Supplier<Response> pending = Blockless.supplier(response);

doOtherWork();
Response value = pending.get();
processResponse(value);
```

In the OutcomeSupplier refactor:

1. A completion callback stores the value or failure and releases a private latch.
2. `pending.get()` awaits that latch, then returns the stored value or throws the failure.
3. If the latch is already released, awaiting returns immediately. Otherwise it waits interruptibly.

**The supplier uses the same waiting mechanism whether completion happens before or after `get()`. Its waiting path does not help execute the source future's callbacks.**

`processResponse` runs explicitly on the caller after the wait. The stage's producer and executor remain unchanged.

## Callback helping and starvation

A result can be ready while the joining caller remains occupied running callbacks. A slow callback delays the caller's next statement and can hold a request-executor slot longer. Callback cost and completion timing therefore affect how quickly that executor accepts more work.

A stronger case is a callback that depends on the caller advancing:

```java
source.thenRun(() -> waitForCallerToContinue());

Response value = source.join();
signalCallerContinued();
```

If the joining caller picks up that callback, it cannot reach the signal until the callback finishes, and the callback cannot finish until it receives that signal. This is a self-dependency despite the source result already being available. ForkJoinPool compensation cannot resolve it. The controlled Blockless waiter does not pick up that callback and reaches the signal.

Scheduling the original operation on an executor does not assign every continuation to that executor. Non-async callbacks have flexible placement. `thenApplyAsync(action, executor)` explicitly submits its action to that executor.

Helping is also a progress mechanism, so the tradeoff depends on where the bottleneck is:

| Situation | `join()` | Plain Blockless latch wait |
|---|---|---|
| Waiter resumes with source callbacks still pending | Waiting caller may execute them before returning | Supplier wait does not drain them |
| ForkJoinPool workers waiting for external completion | Managed blocking can activate replacement workers | Does not request pool compensation |
| Pool workers occupied by the producer's own blocking I/O | Changing only the consumer's wait does not relocate that I/O | Same limitation |

Blockless callables can move the producer's blocking work onto virtual threads. That is separate from adapting an existing stage. A latch-based `ManagedBlocker` could add pool compensation without adding source-callback helping to the supplier's wait; the current OutcomeSupplier refactor does not include that addition.

## Registration still depends on timing

`Blockless.supplier(stage)` registers through `whenComplete`. Registration can execute callback work inline if the stage completes before or during registration. Therefore the consistent waiting behavior belongs to the supplier's `get()`; it is not a claim that the entire stage pipeline has one execution path.

## Source and runnable example

Inspected runtime: Temurin OpenJDK 21.0.13+9. The source links below are pinned to the corresponding OpenJDK release tag.

- [join(): initial result check](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#L2114)
- [waitingGet(): helping, waiting, and postComplete()](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#L1877)
- [postComplete(): executing pending completions](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#L492)
- [ForkJoinPool compensation](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/java.base/share/classes/java/util/concurrent/ForkJoinPool.java#L3718)
- [whenComplete registration](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13%2B9/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java#L879)

[CompletionIsolationCheck.java](examples/CompletionIsolationCheck.java) controls scheduling and checks both cases: the `join()` caller executes a pending callback before returning; the Blockless waiter returns while that callback remains pending. It uses a deliberately pinned scheduling-control thread on Java 21.

[JoinProgressCheck.java](examples/JoinProgressCheck.java) uses the same scheduling control to assert the self-dependency above. The source is complete, but the joining caller waits inside a callback for its own next statement. A watchdog releases the dependency for cleanup. The Blockless case advances without that rescue.

Run the standalone diagnostics from the repository root with JDK 21:

```sh
mvn -pl blockless -am package -DskipTests
javac -cp blockless/target/blockless-0.1.0-SNAPSHOT.jar \
  -d /tmp/blockless-completion-check docs/examples/CompletionIsolationCheck.java \
  docs/examples/JoinProgressCheck.java
java -Djdk.virtualThreadScheduler.parallelism=1 \
  -Djdk.virtualThreadScheduler.maxPoolSize=1 \
  -cp /tmp/blockless-completion-check:blockless/target/blockless-0.1.0-SNAPSHOT.jar \
  CompletionIsolationCheck
java -Djdk.virtualThreadScheduler.parallelism=1 \
  -Djdk.virtualThreadScheduler.maxPoolSize=1 \
  -cp /tmp/blockless-completion-check:blockless/target/blockless-0.1.0-SNAPSHOT.jar \
  JoinProgressCheck
```

Each diagnostic repeats both cases three times and asserts callback placement and returned values. Their monitor-based scheduling control targets JDK 21; JDK 24+ requires a different scheduling gate.
