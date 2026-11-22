#!/usr/bin/env bash
set -euo pipefail

tests_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
automatic_dir="$(cd -- "$tests_dir/.." && pwd)"
repo_dir="$(cd -- "$automatic_dir/../../.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
blockless_classes="${BLOCKLESS_CLASSPATH:-$repo_dir/blockless/target/classes:$repo_dir/blockless-continuations/target/classes}"
agent_jar="${AUTOMATIC_ENTRY_AGENT_JAR:-$automatic_dir/target/automatic-entry-agent.jar}"
output_dir="${STRICT_OUTPUT_DIR:-$automatic_dir/target/strict-tests}"
mkdir -p "$output_dir/classes"

"$task_jdk/bin/javac" --release 21 -cp "$blockless_classes" -d "$output_dir/classes" \
  "$tests_dir/example/strictentry/StrictSupplierEntryProbe.java"
if [[ ! -f "$agent_jar" ]]; then
  JAVA_HOME="$task_jdk" bash "$automatic_dir/agent/build.sh"
fi
classpath="$output_dir/classes:$blockless_classes"
main_class=example.strictentry.StrictSupplierEntryProbe
native_export=()
if [[ "${STRICT_NATIVE_EXPORT:-1}" != "0" ]]; then
  native_export=(--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED)
fi

# This separate unconfigured platform test checks Supplier compatibility only.
# It releases the producer from the harness; it does not claim the carrier was freed.
if timeout 30s "$task_jdk/bin/java" "${native_export[@]}" \
    -cp "$classpath" "$main_class" --supplier-compatibility > "$output_dir/compatibility.log" 2>&1; then
  compatibility_exit=0
else
  compatibility_exit=$?
fi
printf '%s\n' "$compatibility_exit" > "$output_dir/compatibility.exitcode"
cat "$output_dir/compatibility.log"
if [[ "$compatibility_exit" -ne 0 ]]; then
  exit "$compatibility_exit"
fi

# The baseline process FAILS nonblocking acceptance after a bounded marker wait
# and complete cleanup. Its pending get returns the real value without throwing.
if timeout 30s "$task_jdk/bin/java" "${native_export[@]}" \
    -cp "$classpath" "$main_class" > "$output_dir/baseline.log" 2>&1; then
  baseline_exit=0
else
  baseline_exit=$?
fi
printf '%s\n' "$baseline_exit" > "$output_dir/baseline.exitcode"
cat "$output_dir/baseline.log"
if [[ "$baseline_exit" -eq 0 || "$baseline_exit" -eq 124 ]]; then
  echo "Nonblocking baseline must fail after diagnosis, not pass or time out (exit=$baseline_exit)." >&2
  exit 1
fi
if ! rg -Fq 'single platform carrier remained occupied by pending get' "$output_dir/baseline.log"; then
  echo 'Baseline did not report the expected occupied-platform-worker acceptance failure.' >&2
  exit 1
fi

if timeout 30s "$task_jdk/bin/java" \
    "-javaagent:$agent_jar=include=example.strictentry.;cachedCarriers=4" \
    "${native_export[@]}" \
    -cp "$classpath" "$main_class" > "$output_dir/agent.log" 2>&1; then
  agent_exit=0
else
  agent_exit=$?
fi
printf '%s\n' "$agent_exit" > "$output_dir/agent.exitcode"
cat "$output_dir/agent.log"
printf 'compatibility_exit=%s baseline_exit=%s agent_exit=%s\n' "$compatibility_exit" "$baseline_exit" "$agent_exit"
exit "$agent_exit"
