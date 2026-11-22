#!/usr/bin/env bash
set -euo pipefail
experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$experiment_dir/../.." && pwd)"
task_java="${JAVA_HOME:?Set JAVA_HOME to Java 21 or newer}/bin/java"
output_dir="$experiment_dir/target/join"
mkdir -p "$output_dir/classes" "$output_dir/method"
task_exports="--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED"

# Compile the actual checked-out implementation, not the optional native-free shadow.
mapfile -t task_sources < <(rg --files "$repo_dir/blockless/src/main/java" \
  "$repo_dir/blockless-continuations/src/main/java" -g '*.java')
"$JAVA_HOME/bin/javac" "$task_exports" -d "$output_dir/classes" \
  "${task_sources[@]}" \
  "$experiment_dir/virtual-owner/LogicalTaskExecutor.java" \
  "$experiment_dir/virtual-owner/BorrowedCarrierThreads.java" \
  "$experiment_dir"/join/*.java

for probe in JoinContractProbe JoinOwnershipProbe ChildSchedulerInheritanceProbe; do
  "$task_java" --add-opens=java.base/java.lang=ALL-UNNAMED "$task_exports" \
    -cp "$output_dir/classes" "$probe"
done

# Optional independent counterexample: automatic wrapping of a String-returning
# method still leaves its outer platform caller waiting for that String.
if [[ -n "${ASM_JAR:-}" ]]; then
  "$JAVA_HOME/bin/javac" -cp "$ASM_JAR" -d "$output_dir/method" \
    "$experiment_dir"/join/method-agent/joinproof/*.java
  cat > "$output_dir/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Premain-Class: joinproof.MethodAgent

MANIFEST
  "$JAVA_HOME/bin/jar" --create --file "$output_dir/method-agent.jar" \
    --manifest "$output_dir/manifest.mf" -C "$output_dir/method" joinproof
  "$task_java" -Djdk.virtualThreadScheduler.parallelism=1 \
    -Djdk.virtualThreadScheduler.maxPoolSize=1 \
    "-javaagent:$output_dir/method-agent.jar" -cp "$output_dir/method:$ASM_JAR" \
    joinproof.MethodProbe
else
  echo 'SKIP optional method-agent counterexample: set ASM_JAR to ASM 9.8+'
fi
