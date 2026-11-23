#!/usr/bin/env bash
set -euo pipefail
agent_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
public_dir="$(cd -- "$agent_dir/.." && pwd)"
alternatives_dir="$(cd -- "$public_dir/.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${BLOCKLESS_CLASSPATH:-$alternatives_dir/target/stable-core}"
output_dir="$public_dir/target/agent"
"$agent_dir/build.sh"
mkdir -p "$output_dir/tests" "$output_dir/host" "$output_dir/conflict" \
  "$output_dir/module-lib" "$output_dir/modules"
mapfile -t test_sources < <(rg --files "$agent_dir/tests" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes" -d "$output_dir/tests" "${test_sources[@]}"
classpath="$output_dir/tests:$core_classes"
if timeout 30s "$task_jdk/bin/java" -cp "$classpath" example.publicentry.AutomaticEntryProbe \
    > "$output_dir/baseline.txt" 2>&1; then
  baseline_exit=0
else
  baseline_exit=$?
fi
cat "$output_dir/baseline.txt"
printf 'Native fixed-worker acceptance exit=%s\n' "$baseline_exit"
if [[ "$baseline_exit" -eq 0 || "$baseline_exit" -eq 124 ]] || ! rg -Fq \
    'native fixed worker stays occupied by pending get' "$output_dir/baseline.txt"; then
  echo 'Expected a bounded native-worker progress failure, not an API error or process hang' >&2
  exit 1
fi
timeout 30s "$task_jdk/bin/java" \
  "-javaagent:$output_dir/public-entry-agent.jar=include=example.publicentry." \
  -cp "$classpath" example.publicentry.AutomaticEntryProbe automatic

"$task_jdk/bin/javac" --release 21 -d "$output_dir/host" \
  "$agent_dir/packaging/host/PublicIsolatedProbe.java"
"$task_jdk/bin/javac" --release 21 -d "$output_dir/conflict" \
  "$agent_dir/packaging/conflicting-asm/org/objectweb/asm/ClassReader.java"
timeout 30s "$task_jdk/bin/java" \
  "-javaagent:$output_dir/public-entry-agent.jar=include=example.publicentry." \
  -cp "$output_dir/host:$output_dir/conflict" PublicIsolatedProbe "$output_dir/tests" "$core_classes"

"$task_jdk/bin/jar" --create --file "$output_dir/module-lib/blockless.jar" -C "$core_classes" .
"$task_jdk/bin/javac" --release 21 --module-source-path "$agent_dir/packaging/modules" \
  --module-path "$output_dir/module-lib" -d "$output_dir/modules" -m entry.publicproof
timeout 30s "$task_jdk/bin/java" \
  "-javaagent:$output_dir/public-entry-agent.jar=include=example.moduleentry." \
  --module-path "$output_dir/module-lib:$output_dir/modules" \
  -m entry.publicproof/example.moduleentry.NamedPublicEntry

# Stable Blockless is only needed by application tests, not by the bootstrap runtime.
"$task_jdk/bin/jdeps" --jdk-internals "$output_dir/public-entry-runtime.jar" \
  > "$output_dir/jdeps-runtime.txt"
if [[ -s "$output_dir/jdeps-runtime.txt" ]]; then
  cat "$output_dir/jdeps-runtime.txt"
  exit 1
fi
echo 'PASS public runtime jdeps: no JDK internal dependencies'
