# Real gRPC immediate-get test

This fixture reuses the Spring gRPC example's application, generated messages,
repository and dependencies. Only its benchmark handler and test-server executor
configuration are overlaid. The example repository remains unchanged.

The query handler executes:

```java
var user = Continuations.supplier(
    () -> repository.countByCountryWithDelay(country, sleep));
var result = user.get();
// Construct and send the response afterward.
```

`virtual_thread` reports the caller before get. Request fields that manually
selected threads in the old benchmark are unused. The server executor owns
dispatch:

| Server configuration | Actual entry |
|---|---|
| `research.grpc.executor=platform`, four workers, no agent | Four platform tasks; pending get waits on its worker |
| Same configuration with agent including `org.pjlabs.example.` | Four physical carriers, virtual logical tasks |
| `research.grpc.executor=virtual`, no agent | Public virtual-thread-per-task executor |
| `research.grpc.configure-executor=false` | Original gRPC cached executor |

The original gRPC factory is also a supported call site:
`io.grpc.internal.GrpcUtil$3.create()` invokes
`Executors.newCachedThreadPool(ThreadFactory)`. See the adjacent disassembly files.
Its scheduled factory is deliberately unchanged.

The functional client holds the supplier body until a separate release request.
It verifies that health requests progress, get has not returned early, the actual
database value matches the direct query, and caller/body entry and finally counters
are exactly one after release. Future stubs exist only in the test harness.
The baseline also returns the actual value without a pending exception. Health
uses its spare platform workers; that result establishes Supplier compatibility,
not release of the blocked worker. The strict one-carrier test diagnoses that
distinction separately.

## Reproduce

Build the agent and library classes first. Start the example's PostgreSQL database
at localhost:5433 (`benchmark`, postgres/postgres), then run:

```sh
python3 experiments/continuation-entry/automatic-entry/network/run-grpc.py \
  --java-home /path/to/jdk21 \
  --boot-jar /path/to/blockless-spring-grpc-example-0.1.0-SNAPSHOT.jar \
  --blockless-classpath /path/to/native-free/classes:/path/to/core/classes \
  --agent-jar experiments/continuation-entry/automatic-entry/target/automatic-entry-agent.jar \
  --proto /path/to/blockless-spring-grpc-example/src/main/proto/greeting.proto \
  --ghz /path/to/ghz \
  --output experiments/continuation-entry/automatic-entry/target/grpc-results \
  --native-free --default-grpc-proof
```

Omit `--native-free` and supply the current PR's classes to test the native
implementation. Add `--functional-only` for the held-query test without load.
`--default-grpc-proof` also tests the original gRPC cached factory with only
`io.grpc.internal.` instrumented and the research server-executor bean disabled.
That functional case uses no application-specific executor configuration.
Use a separate output directory for each runtime/implementation. The runner
extracts the executable jar's dependencies, compiles the fixture for Java 21,
and runs the application with that fixture first on the flat classpath. It
starts and stops each owned application sequentially; PostgreSQL is external.

Load settings: five repetitions, 1 s warmup and 3 s measured per mode, 32 clients,
four connections, 10 Hikari connections, 10 ms database sleep, 512 MB heap and
two active JVM processors. Mode order alternates within each application.
Raw ghz JSON, stderr, application logs, exact commands and functional traces
are retained under the chosen output directory. Any failed measured RPC aborts.

## Saved evidence

- [Current Java 21 results](results/native-free-jdk21.json): 67,326 successful measured
  RPCs, including both waiting-platform and automatic-entry immediate get.
- [Current Java 25 functional results](results/native-free-jdk25-functional.json).
- [Current held-query traces](results/native-free-jdk21-functional.txt).
- Files marked `legacy` preceded pending-check removal and are historical evidence.

The reused example jar was built from example commit `d7e6ffa` and has SHA-256
`377d3d788e616885eb8c517e82a29af3961c38704d7e79dd46903f89ea7c3d0c`.
Current PR source is `d6f4fef` (legacy runs used `d1137b8`); the native-free source is in this research
branch. ghz was 0.120.0 and PostgreSQL was 18.1. This is an I/O-bound comparison;
database limits and short-run variation do not establish CPU/allocation parity.

Earlier callback-based example results measured offloading only: that handler
sent its response inside the supplier and read get from onComplete. Those results
do not prove the immediate pending-get contract and are not used in this table.
