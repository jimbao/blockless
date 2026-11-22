#!/usr/bin/env bash
set -euo pipefail

experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$experiment_dir/../.." && pwd)"
task_java="${JAVA_HOME:?Set JAVA_HOME to Java 21 or newer}/bin/java"
task_javac="$JAVA_HOME/bin/javac"
blockless_classes="${BLOCKLESS_CLASSPATH:-$repo_dir/blockless/target/classes:$repo_dir/blockless-continuations/target/classes}"
asm_jar="${ASM_JAR:?Set ASM_JAR to an ASM 9.8+ jar}"
output_dir="$experiment_dir/target"
mkdir -p "$output_dir/classes"

"$task_javac" \
  --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED \
  -cp "$asm_jar:$blockless_classes" -d "$output_dir/classes" \
  "$experiment_dir"/src/experiment/*.java
cat > "$output_dir/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Premain-Class: experiment.MainAgent

MANIFEST
"$JAVA_HOME/bin/jar" --create --file "$output_dir/agent.jar" \
  --manifest "$output_dir/manifest.mf" -C "$output_dir/classes" experiment
task_classpath="$output_dir/classes:$asm_jar:$blockless_classes"
task_exports="--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED"
task_agent="-javaagent:$output_dir/agent.jar=experiment.MainContractProbe"

"$task_java" "$task_exports" \
  "-javaagent:$output_dir/agent.jar=experiment.UserExample" \
  -cp "$task_classpath" experiment.UserExample

for mode in success failure null parallel races interrupt interrupt-race foreign; do
  "$task_java" "$task_exports" "$task_agent" -cp "$task_classpath" \
    experiment.MainContractProbe "$mode"
done

"$task_java" "$task_exports" "$task_agent" -Dexperiment.initialInterrupt=true \
  -cp "$task_classpath" experiment.MainContractProbe initial-interrupt

"$task_java" "$task_exports" \
  "-javaagent:$output_dir/agent.jar=experiment.NetworkContractProbe" \
  -cp "$task_classpath" experiment.NetworkContractProbe

"$task_java" "$task_exports" \
  "-javaagent:$output_dir/agent.jar=experiment.ManagedOwnerProbe" \
  -cp "$task_classpath" experiment.ManagedOwnerProbe

# An uncaught failure must escape original main after finally, with a nonzero exit,
# while unrelated non-daemon threads retain the ordinary JVM lifetime behavior.
set +e
"$task_java" "$task_exports" "$task_agent" -cp "$task_classpath" \
  experiment.MainContractProbe uncaught > "$output_dir/uncaught.log" 2>&1
failure_status=$?
set -e
if [[ "$failure_status" -eq 0 ]]; then
  cat "$output_dir/uncaught.log"
  exit 1
fi
rg -q 'MAIN_FINALLY_FINISHED' "$output_dir/uncaught.log"
rg -q 'BACKGROUND_FINISHED' "$output_dir/uncaught.log"
rg -q 'Exception in thread "main" java.lang.IllegalStateException: uncaught original failure' \
  "$output_dir/uncaught.log"
echo 'PASS uncaught failure, finally, and background lifetime'
