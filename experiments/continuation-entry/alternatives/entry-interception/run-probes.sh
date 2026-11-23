#!/usr/bin/env bash
set -euo pipefail
experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${CORE_CLASSPATH:-$experiment_dir/../target/stable-core}"
grpc_libs="${GRPC_LIBS:-/workspace/blockless-continuation-research/automatic-entry/network/boot-extracted/BOOT-INF/lib}"
output_dir="${PROBE_OUTPUT_DIR:-$experiment_dir/target/probes}"
agent_jar="$experiment_dir/target/callback-entry-agent.jar"
if [[ ! -f "$agent_jar" ]]; then bash "$experiment_dir/build.sh"; fi
mkdir -p "$output_dir/classes"
mapfile -t probe_sources < <(rg --files "$experiment_dir/probe" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes:$grpc_libs/*" \
  -d "$output_dir/classes" "${probe_sources[@]}"
classpath="$output_dir/classes:$core_classes:$grpc_libs/*"

if timeout 20s "$task_jdk/bin/java" -cp "$classpath" entryprobe.SerializingEntryProbe \
    > "$output_dir/baseline.txt" 2>&1; then
  baseline_exit=0
else
  baseline_exit=$?
fi
if [[ "$baseline_exit" -eq 0 || "$baseline_exit" -eq 124 ]] \
    || ! rg -Fq 'platform request worker remained occupied by get' "$output_dir/baseline.txt"; then
  cat "$output_dir/baseline.txt"
  echo "Baseline must diagnose occupied caller worker, exit=$baseline_exit" >&2
  exit 1
fi
printf '%s\n' "$baseline_exit" > "$output_dir/baseline.exitcode"
timeout 20s "$task_jdk/bin/java" \
  "-javaagent:$agent_jar=class=io.grpc.internal.SerializingExecutor;method=run" \
  -cp "$classpath" entryprobe.SerializingEntryProbe > "$output_dir/agent.txt" 2>&1
cat "$output_dir/agent.txt"

timeout 20s "$task_jdk/bin/java" \
  "-javaagent:$agent_jar=class=entryprobe.VoidBoundaryNegativeProbe\$SynchronousTask;method=run" \
  -cp "$classpath" entryprobe.VoidBoundaryNegativeProbe future > "$output_dir/future-negative.txt" 2>&1
cat "$output_dir/future-negative.txt"
timeout 20s "$task_jdk/bin/java" \
  "-javaagent:$agent_jar=class=entryprobe.VoidBoundaryNegativeProbe\$Service;method=query" \
  -cp "$classpath" entryprobe.VoidBoundaryNegativeProbe advice > "$output_dir/advice-negative.txt" 2>&1
cat "$output_dir/advice-negative.txt"
printf 'baseline_exit=%s callback_entry_exit=0 generic/advice hazards reproduced\n' "$baseline_exit"
