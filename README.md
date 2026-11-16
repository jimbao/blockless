<p align="center">
  <img src="docs/blockless-logo.png" alt="blockless" width="200">
</p>

<h1 align="center">blockless</h1>

<p align="center"><em>Launch async work. Run tasks in parallel. Wait for results.</em></p>

Blockless is a small Java 21 library that makes it easy to launch asynchronous
work, run tasks in parallel, and wait for completion using straightforward Java code.

- **`Blockless`** — run callables on virtual threads or wait for existing CompletionStages.
- **`Parallel`** — launch tasks, collect results, and propagate configured trace context.

Zero dependencies in core. Pluggable context propagation for MDC, gRPC, and OpenTelemetry.

## Quick start

```java
// Start a Callable on a virtual thread now; get its result when you need it
var pending = Blockless.supplier(() -> fetchData());
doOtherWork();
var data = pending.get();

// Run a Callable on a virtual thread and wait for its result immediately
var answer = Blockless.get(() -> expensiveComputation());

// Wait for an existing CompletionStage
var result = Blockless.get(client.fetchAsync());
```

## Launch work and wait for completion

`Blockless.supplier(callable)` starts work immediately on a virtual thread and
returns a supplier for its result. Call `get()` when you need the value; repeated
calls return the same result or rethrow the task's failure. Use
`Blockless.get(callable)` when you want to start the work and wait in one call.

For an existing CompletionStage, `Blockless.supplier(stage)` registers for its
completion and returns a supplier you can wait on later. `Blockless.get(stage)`
waits immediately. The stage's producer and executor continue to control where
its work runs.

Both forms wait on the calling thread. A virtual-thread caller normally parks
and releases its carrier while waiting; a platform-thread caller remains blocked.

## Why `Parallel`

Services often need to fan out N calls and collect the results. The typical
pattern looks like this:

```java
// Before: manual executor + supplyAsync + semaphore + join
var executor = Executors.newVirtualThreadPerTaskExecutor();
var semaphore = new Semaphore(10);
var futures = ids.stream()
    .map(id -> CompletableFuture.supplyAsync(() -> {
        semaphore.acquireUninterruptibly();
        try { return fetchById(id); }
        finally { semaphore.release(); }
    }, executor))
    .toList();
var results = futures.stream().map(CompletableFuture::join).toList();
```

With blockless:

```java
// Run up to ten tasks at a time and collect results in input order
var results = parallel.withMaxConcurrency(10).map(ids, this::fetchById);
```

Each task runs on its own virtual thread. Results stay in input order.
MDC and trace context survive the hop.

### Usage

```java
var parallel = Parallel.create(new Slf4jMdcContextPropagator());

// Map in parallel, results stay in order
List<String> names = parallel.map(userIds, id -> fetchName(id));

// Fire off an async task, get result later
Supplier<String> data = parallel.async(() -> fetchData());

// Build a map in parallel
Map<String, Profile> profiles = parallel.asMap(userIds, id -> loadProfile(id));

// Limit concurrent tasks (remaining tasks wait to be launched)
var boundedParallel = parallel.withMaxConcurrency(10);
List<String> names = boundedParallel.map(userIds, id -> fetchName(id));

// Per-task timeout — result fails and worker is interrupted at the deadline
var timedParallel = parallel.withTimeout(Duration.ofSeconds(5));
List<String> names = timedParallel.map(userIds, id -> fetchName(id));

// Combines with withMaxConcurrency
var safeParallel = parallel.withMaxConcurrency(10).withTimeout(Duration.ofSeconds(5));

// Collect results without failing fast — failed tasks return Either.fail()
List<Either<String, Throwable>> results = parallel.toEither(ids, id -> riskyFetch(id));
```

Timeouts apply to each task after it is launched. Time queued for a concurrency slot does not count,
and the task timeout is not a deadline for the batch. `map()` throws a wrapped `TimeoutException`,
while `toEither()` collects it as `Either.fail()` and continues processing the other items.

On failure, `map()` interrupts the other active workers without waiting for their cleanup. A task
that ignores interruption may keep running after its result has timed out or the batch has returned.
Callers own resource allocation and cleanup. Submitted code must handle interruption and resources
being closed after the batch returns or throws.

In bounded batches, timed-out calculations retain their slots until they finish. Queued tasks start
only when a slot becomes available; their progress therefore depends on running calculations
responding to cancellation.

## Context propagation

When you hop to a new thread, thread-local context (MDC trace IDs, gRPC context,
OpenTelemetry spans) is lost. Logs become uncorrelated. Traces break.

`Parallel` handles this automatically — context is captured when you call `map()`/`async()`
and restored in each worker thread. You choose which propagators to wire up:

```java
var parallel = Parallel.create(
    new Slf4jMdcContextPropagator(),
    new GrpcContextPropagator()
);
```

For lower-level use, you can wrap individual tasks:

```java
// Wrap a Callable — MDC comes along
Callable<String> wrapped = CallableContext.wrap(task, new Slf4jMdcContextPropagator());

// Wrap a Runnable
Runnable wrapped = RunnableContext.wrap(task, new Slf4jMdcContextPropagator());

// Wrap an entire ExecutorService
ExecutorService executor = PropagatingExecutorService.wrap(
    Executors.newVirtualThreadPerTaskExecutor(),
    new Slf4jMdcContextPropagator(),
    new GrpcContextPropagator()
);
```

### Available propagators

| Module | Propagates |
|---|---|
| `blockless-context-slf4j` | SLF4J MDC |
| `blockless-context-grpc` | gRPC `Context` |
| `blockless-context-opentelemetry` | OpenTelemetry `Context` |

Framework dependencies are `provided` scope — blockless uses whatever version
your service already has. No version conflicts.

## Modules

| Module | What it does |
|---|---|
| `blockless` | Core utilities: `Blockless`, `Parallel`, context SPI |
| `blockless-context-slf4j` | SLF4J MDC propagation |
| `blockless-context-grpc` | gRPC context propagation |
| `blockless-context-opentelemetry` | OpenTelemetry context propagation |
| `blockless-tests` | Integration tests |

## Requirements

- Java 21+

## License

Apache 2.0
