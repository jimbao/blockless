#!/usr/bin/env bash
set -euo pipefail
agent_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
public_dir="$(cd -- "$agent_dir/.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
asm_jar="${ASM_JAR:-/workspace/.tools/blockless/asm-9.8.jar}"
asm_commons_jar="${ASM_COMMONS_JAR:-/workspace/.tools/blockless/asm-commons-9.8.jar}"
output_dir="$public_dir/target/agent"
rm -rf -- "$output_dir/runtime-classes" "$output_dir/agent-classes" \
  "$output_dir/build-tools" "$output_dir/stage" "$output_dir/relocated"
mkdir -p "$output_dir/runtime-classes" "$output_dir/agent-classes" \
  "$output_dir/build-tools" "$output_dir/stage" "$output_dir/relocated"
"$task_jdk/bin/javac" --release 21 -d "$output_dir/runtime-classes" \
  "$public_dir/publicentry/PublicTaskExecutor.java" "$public_dir/publicentry/EntryRuntime.java"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar:$output_dir/runtime-classes" \
  -d "$output_dir/agent-classes" "$agent_dir/publicentry/agent/EntryAgent.java"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar:$asm_commons_jar" \
  -d "$output_dir/build-tools" "$agent_dir/build-tools/RelocateAgent.java"
(cd -- "$output_dir/stage" && "$task_jdk/bin/jar" --extract --file "$asm_jar")
rm -f -- "$output_dir/stage/module-info.class" "$output_dir/stage/META-INF/MANIFEST.MF"
cp -- "$agent_dir/ASM-LICENSE.txt" "$output_dir/stage/META-INF/ASM-LICENSE.txt"
cp -R -- "$output_dir/agent-classes/." "$output_dir/stage/"
"$task_jdk/bin/java" -cp "$output_dir/build-tools:$asm_jar:$asm_commons_jar" \
  RelocateAgent "$output_dir/stage" "$output_dir/relocated"
cat > "$output_dir/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Premain-Class: publicentry.agent.EntryAgent
Boot-Class-Path: public-entry-runtime.jar

MANIFEST
"$task_jdk/bin/jar" --create --file "$output_dir/public-entry-runtime.jar.tmp" \
  -C "$output_dir/runtime-classes" .
"$task_jdk/bin/jar" --create --file "$output_dir/public-entry-agent.jar.tmp" \
  --manifest "$output_dir/manifest.mf" -C "$output_dir/relocated" .
mv -f -- "$output_dir/public-entry-runtime.jar.tmp" "$output_dir/public-entry-runtime.jar"
mv -f -- "$output_dir/public-entry-agent.jar.tmp" "$output_dir/public-entry-agent.jar"
echo "$output_dir/public-entry-agent.jar"
