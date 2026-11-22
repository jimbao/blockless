#!/usr/bin/env bash
set -euo pipefail
agent_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
automatic_dir="$(cd -- "$agent_dir/.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
output_dir="$automatic_dir/target"
"$agent_dir/build.sh"
rm -rf -- "$output_dir/agent-test-classes" "$output_dir/agent-packaging-classes" \
  "$output_dir/agent-conflict-classes" "$output_dir/agent-module-classes"
mkdir -p "$output_dir/agent-test-classes" "$output_dir/agent-packaging-classes" \
  "$output_dir/agent-conflict-classes" "$output_dir/agent-module-classes"
mapfile -t smoke_sources < <(rg --files "$agent_dir/tests" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -d "$output_dir/agent-test-classes" "${smoke_sources[@]}"
"$task_jdk/bin/java" -cp "$output_dir/agent-test-classes" example.agentchecks.FactoryCalls baseline
"$task_jdk/bin/java" \
  "-javaagent:$output_dir/automatic-entry-agent.jar=include=example.agentchecks.;cachedCarriers=2" \
  -cp "$output_dir/agent-test-classes" example.agentchecks.FactoryCalls agent

"$task_jdk/bin/javac" --release 21 -d "$output_dir/agent-packaging-classes" \
  "$agent_dir/packaging/host/IsolatedLoaderProbe.java"
"$task_jdk/bin/javac" --release 21 -d "$output_dir/agent-conflict-classes" \
  "$agent_dir/packaging/conflicting-asm/org/objectweb/asm/ClassReader.java"
"$task_jdk/bin/java" \
  "-javaagent:$output_dir/automatic-entry-agent.jar=include=example.agentchecks.;cachedCarriers=2" \
  -cp "$output_dir/agent-packaging-classes:$output_dir/agent-conflict-classes" \
  IsolatedLoaderProbe "$output_dir/agent-test-classes"
"$task_jdk/bin/javac" --release 21 --module-source-path "$agent_dir/packaging/modules" \
  -d "$output_dir/agent-module-classes" -m packaging.probe
"$task_jdk/bin/java" \
  "-javaagent:$output_dir/automatic-entry-agent.jar=include=packaging.probe.;cachedCarriers=2" \
  --module-path "$output_dir/agent-module-classes" -m packaging.probe/packaging.probe.NamedEntry
