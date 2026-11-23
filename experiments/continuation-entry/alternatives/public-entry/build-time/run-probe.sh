#!/usr/bin/env bash
set -euo pipefail
weaver_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
public_dir="$(cd -- "$weaver_dir/.." && pwd)"
alternatives_dir="$(cd -- "$public_dir/.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
asm_jar="${ASM_JAR:-/workspace/.tools/blockless/asm-9.8.jar}"
core_classes="${BLOCKLESS_CLASSPATH:-$alternatives_dir/target/stable-core}"
output_dir="$public_dir/target/build-time"
runtime_jar="${PUBLIC_ENTRY_RUNTIME_JAR:-$output_dir/public-entry-runtime.jar}"
rm -rf -- "$output_dir/tools" "$output_dir/input" "$output_dir/woven"
mkdir -p "$output_dir/tools" "$output_dir/input"
if [[ -z "${PUBLIC_ENTRY_RUNTIME_JAR:-}" ]]; then
  mkdir -p "$output_dir/runtime-classes"
  "$task_jdk/bin/javac" --release 21 -d "$output_dir/runtime-classes" \
    "$public_dir/publicentry/PublicTaskExecutor.java" "$public_dir/publicentry/EntryRuntime.java"
  "$task_jdk/bin/jar" --create --file "$runtime_jar" -C "$output_dir/runtime-classes" .
fi
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar" -d "$output_dir/tools" \
  "$weaver_dir/OfflineEntryWeaver.java"
mapfile -t application_sources < <(rg --files "$public_dir/agent/tests" "$weaver_dir/tests" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes" -d "$output_dir/input" "${application_sources[@]}"

if timeout 30s "$task_jdk/bin/java" -cp "$output_dir/input:$core_classes" \
    example.publicentry.AutomaticEntryProbe > "$output_dir/baseline.txt" 2>&1; then
  baseline_exit=0
else
  baseline_exit=$?
fi
cat "$output_dir/baseline.txt"
if [[ "$baseline_exit" -eq 0 || "$baseline_exit" -eq 124 ]] || ! rg -Fq \
    'native fixed worker stays occupied by pending get' "$output_dir/baseline.txt"; then
  echo "Wrong native baseline result: $baseline_exit" >&2
  exit 1
fi

# App/core classes are deliberately ABSENT from the weaver's classpath. ASM reads
# their bytes, including InitializationTrap, without loading or initializing them.
"$task_jdk/bin/java" -cp "$output_dir/tools:$asm_jar" OfflineEntryWeaver \
  "$output_dir/input" "$output_dir/woven" example.publicentry.

# Application execution needs only stable core + the JDK-only helper. No agent,
# ASM, export/open/preview flags or altered business source is on this command.
timeout 30s "$task_jdk/bin/java" -cp "$output_dir/woven:$core_classes:$runtime_jar" \
  example.publicentry.AutomaticEntryProbe automatic
echo "PASS offline public factory entry: baseline_exit=$baseline_exit woven_exit=0; plain Java application launch"
