#!/usr/bin/env bash
set -euo pipefail
experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_java="${JAVA_HOME:?Set JAVA_HOME to Java 21 or newer}/bin/java"
output_dir="$experiment_dir/target/hazards"
mkdir -p "$output_dir/classes" "$output_dir/replacement"
task_exports="--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED"

"$JAVA_HOME/bin/javac" "$task_exports" -d "$output_dir/classes" \
  "$experiment_dir"/hazards/*.java \
  "$experiment_dir"/hazards/redefinition/original/*.java
"$JAVA_HOME/bin/javac" -d "$output_dir/replacement" \
  "$experiment_dir/hazards/redefinition/replacement/Target.java"
cat > "$output_dir/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Premain-Class: Agent
Can-Redefine-Classes: true

MANIFEST
"$JAVA_HOME/bin/jar" --create --file "$output_dir/redefinition-agent.jar" \
  --manifest "$output_dir/manifest.mf" -C "$output_dir/classes" Agent.class

for probe in ManagedContinuationProbe NativeFutureCancellationProbe NativeOwnershipProbe CompensationProbe; do
  "$task_java" "$task_exports" -cp "$output_dir/classes" "$probe"
done
"$task_java" "-javaagent:$output_dir/redefinition-agent.jar" \
  -cp "$output_dir/classes" RedefineActiveFrameProbe "$output_dir/replacement/Target.class"
