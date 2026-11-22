#!/usr/bin/env bash
set -euo pipefail

agent_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
automatic_dir="$(cd -- "$agent_dir/.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
asm_jar="${ASM_JAR:-/workspace/.tools/blockless/asm-9.8.jar}"
asm_commons_jar="${ASM_COMMONS_JAR:-/workspace/.tools/blockless/asm-commons-9.8.jar}"
output_dir="$automatic_dir/target"
rm -rf -- "$output_dir/agent-classes" "$output_dir/agent-stage" \
  "$output_dir/agent-relocated" "$output_dir/agent-build-tools" "$output_dir/runtime-classes"
mkdir -p "$output_dir/agent-classes" "$output_dir/agent-stage" \
  "$output_dir/agent-relocated" "$output_dir/agent-build-tools" "$output_dir/runtime-classes"
mapfile -t runtime_sources < <(rg --files "$automatic_dir/runtime/automaticentry" -g '*.java' | sort)
if [[ "${#runtime_sources[@]}" -eq 0 ]]; then
  echo 'The automatic-entry runtime sources are missing.' >&2
  exit 1
fi
# The bootstrap helper has JDK-only dependencies. Keep agent tooling and ASM out of it.
"$task_jdk/bin/javac" --release 21 -d "$output_dir/runtime-classes" "${runtime_sources[@]}"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar:$output_dir/runtime-classes" \
  -d "$output_dir/agent-classes" "$agent_dir/automaticentry/EntryAgent.java"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar:$asm_commons_jar" \
  -d "$output_dir/agent-build-tools" "$agent_dir/build-tools/RelocateAgent.java"

# Use ASM's standard remapper at build time, not a custom constant-pool rewriter.
(cd -- "$output_dir/agent-stage" && "$task_jdk/bin/jar" --extract --file "$asm_jar")
rm -f -- "$output_dir/agent-stage/module-info.class" "$output_dir/agent-stage/META-INF/MANIFEST.MF"
cp -- "$agent_dir/ASM-LICENSE.txt" "$output_dir/agent-stage/META-INF/ASM-LICENSE.txt"
cp -R -- "$output_dir/agent-classes/." "$output_dir/agent-stage/"
"$task_jdk/bin/java" -cp "$output_dir/agent-build-tools:$asm_jar:$asm_commons_jar" \
  RelocateAgent "$output_dir/agent-stage" "$output_dir/agent-relocated"
cat > "$output_dir/agent-manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Premain-Class: automaticentry.EntryAgent
Boot-Class-Path: automatic-entry-runtime.jar

MANIFEST
"$task_jdk/bin/jar" --create --file "$output_dir/automatic-entry-runtime.jar.tmp" \
  -C "$output_dir/runtime-classes" .
"$task_jdk/bin/jar" --create --file "$output_dir/automatic-entry-agent.jar.tmp" \
  --manifest "$output_dir/agent-manifest.mf" -C "$output_dir/agent-relocated" .
# Existing JVMs retain their original open jars while each new artifact is installed atomically.
mv -f -- "$output_dir/automatic-entry-runtime.jar.tmp" "$output_dir/automatic-entry-runtime.jar"
mv -f -- "$output_dir/automatic-entry-agent.jar.tmp" "$output_dir/automatic-entry-agent.jar"
echo "$output_dir/automatic-entry-agent.jar"
