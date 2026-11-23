#!/usr/bin/env bash
set -euo pipefail
experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
asm_jar="${ASM_JAR:-/workspace/.tools/blockless/asm-9.8.jar}"
asm_commons="${ASM_COMMONS_JAR:-/workspace/.tools/blockless/asm-commons-9.8.jar}"
output_dir="$experiment_dir/target"
rm -rf -- "$output_dir/agent-classes" "$output_dir/stage" "$output_dir/shaded" "$output_dir/tools"
mkdir -p "$output_dir/agent-classes" "$output_dir/stage" "$output_dir/shaded" "$output_dir/tools"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar" -d "$output_dir/agent-classes" \
  "$experiment_dir/agent/callbackentry/CallbackEntryAgent.java"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar:$asm_commons" -d "$output_dir/tools" \
  "$experiment_dir/build-tools/RelocateAgent.java"
(cd -- "$output_dir/stage" && "$task_jdk/bin/jar" --extract --file "$asm_jar")
rm -f -- "$output_dir/stage/module-info.class" "$output_dir/stage/META-INF/MANIFEST.MF"
cp "$experiment_dir/ASM-LICENSE.txt" "$output_dir/stage/META-INF/ASM-LICENSE.txt"
cp -R "$output_dir/agent-classes/." "$output_dir/stage/"
"$task_jdk/bin/java" -cp "$output_dir/tools:$asm_jar:$asm_commons" RelocateAgent \
  "$output_dir/stage" "$output_dir/shaded"
cat > "$output_dir/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Premain-Class: callbackentry.CallbackEntryAgent

MANIFEST
"$task_jdk/bin/jar" --create --file "$output_dir/callback-entry-agent.jar" \
  --manifest "$output_dir/manifest.mf" -C "$output_dir/shaded" .
echo "$output_dir/callback-entry-agent.jar"
