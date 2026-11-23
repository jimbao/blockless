# Same-handler gRPC comparison

Every variant uses [BenchmarkGrpcService](src/org/pjlabs/example/BenchmarkGrpcService.java):
ordinary stable `Blockless.supplier`, immediate `get()`, then the response. The
body performs the same JPA/PostgreSQL query. A harness callback is never used to
defer the business get or response. The helper `Continuations` API is not loaded.

The fixture overrides classes from the existing Spring gRPC example on a flat
classpath. Core comes from upstream `3d070a3`; lazy helping alone shadows
`OutcomeSupplier` to add its two wait hooks. HTTP control only observes counters
and releases the functional I/O gate; it does not execute business queries.

## Recorded results

- [Steady Java 21 comparison](results/steady-jdk21.json): primary seven-way run,
  10-second initial warmup and five 3-second measured samples per variant.
- [Java 25 functional comparison](results/functional-jdk25.json): all seven
  variants, four held requests, actual values, cleanup and saturated health check.
- [Final Java 21 functional comparison](results/final-functional-jdk21.json):
  rebuilt artifacts plus explicit gated, result and health-caller type assertions.
- [Earlier cold comparisons](results/exploratory-jdk21.json): retained separately;
  rising cold/JIT results motivated the common initial warmup for the primary run.
- [Private-scheduler reference failures](results/private-reference.json): two
  runs had 32 deadline errors each. A separate shorter rerun passed. These are
  unsuccessful/inconsistent reference results, not entries in the successful
  seven-way table. Captured stacks are summarized without asserting a root cause.

The summaries include each sample, counts, caller identity and before/after
resource snapshots. Query-caller deltas include all warmups as well as measured
requests; they are not measured-only counts. A carrier snapshot is not a peak
resource guarantee. Large raw ghz results, commands, class-loading logs and thread
dumps remain under ignored `target/` in the research workspace.

Oracle 21/25 standalone functional/lifecycle results are recorded in the individual
candidate directories. Real application throughput was measured on Corretto 21;
no equal-throughput claim is made for other runtimes.

## Reproduce

Use Java 21+, ASM/asm-commons 9.8, ghz 0.120.0 and the Spring gRPC example Boot jar.
The recorded Boot jar SHA-256 is in each summary. Default tool paths match the
research workspace; override them below for another machine. The example's
`greeting.proto` must match the Boot jar.

From this repository root, first prepare the runtimes:

```sh
# Ensure the pinned upstream core commit is present if cloning only the fork.
git fetch https://github.com/pjlabs/blockless.git main
JAVA_HOME=/path/to/jdk21 ASM_JAR=/path/to/asm-9.8.jar \
  ASM_COMMONS_JAR=/path/to/asm-commons-9.8.jar \
  bash experiments/continuation-entry/alternatives/prepare.sh
```

Start an isolated PostgreSQL with database `benchmark`, user/password
`postgres`/`postgres`, reachable on localhost port 15433. This example uses no
host directory or pre-existing database:

```sh
docker run --rm --name blockless-entry-reproduction \
  -e POSTGRES_DB=benchmark -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -p 127.0.0.1:15433:5432 -d postgres:18.1
```

Wait for PostgreSQL readiness, then run:

```sh
python3 experiments/continuation-entry/alternatives/network/run-comparison.py \
  --java-home /path/to/jdk21 --asm-jar /path/to/asm-9.8.jar \
  --boot-jar /path/to/example.jar --proto /path/to/greeting.proto \
  --ghz /path/to/ghz --output /absolute/path/to/results \
  --initial-warmup 10 --repetitions 5
docker stop blockless-entry-reproduction
```

The driver compiles fixtures for Java 21, weaves the offline variant, waits for
database seeding and runs servers sequentially on gRPC 19090 / HTTP 18106. It
rejects any non-OK warmup or measured RPC and stops each server in `finally`.
Use a fresh output directory. Repeat with `--java-home /path/to/jdk25
--functional-only` for the recorded Java 25 scope. `--variants` selects candidates.

The original measurements used frozen copies of the public runtime/agent and the
full lazy-help v3 jar. `--public-agent`, `--public-runtime` and `--lazy-runtime`
can select such copies; defaults use freshly built outputs. The unsupported old
private reference is excluded by default and requires an explicit `--private-agent`
path if requested. Startup agents are used only for the agent and callback modes;
offline, fixed, lazy, compensated and standard virtual modes run without one.
