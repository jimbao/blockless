#!/usr/bin/env bash
set -euo pipefail
experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$experiment_dir/../.." && pwd)"
task_java="${JAVA_HOME:?Set JAVA_HOME to Java 21 or newer}/bin/java"
junit_jar="${JUNIT_JAR:?Set JUNIT_JAR to junit-platform-console-standalone 1.11.4}"
source_dir="$experiment_dir/join/native-free"
output_dir="$experiment_dir/target/native-free"
mkdir -p "$output_dir/classes" "$output_dir/tests" "$output_dir/probes"

mapfile -t task_sources < <(rg --files "$repo_dir/blockless/src/main/java" -g '*.java')
"$JAVA_HOME/bin/javac" -d "$output_dir/classes" "${task_sources[@]}" \
  "$source_dir/source/io/github/pjlabs/blockless/continuations/Continuations.java"

mapfile -t task_tests < <(rg --files "$repo_dir/blockless/src/test/java" \
  "$repo_dir/blockless-continuations/src/test/java" -g '*.java')
"$JAVA_HOME/bin/javac" -cp "$output_dir/classes:$junit_jar" \
  -d "$output_dir/tests" "${task_tests[@]}" "$source_dir/NativeFreeSuite.java"

# Neither compilation nor the actual library test run exports/opens JVM internals.
"$task_java" -cp "$output_dir/classes:$output_dir/tests:$junit_jar" \
  NativeFreeSuite "$output_dir/tests"

"$JAVA_HOME/bin/javac" -cp "$output_dir/classes" -d "$output_dir/probes" \
  "$experiment_dir/virtual-owner/LogicalTaskExecutor.java" \
  "$experiment_dir/virtual-owner/BorrowedCarrierThreads.java" "$source_dir"/probes/*.java
for probe in UnstartedInheritanceProbe ClosedCarrierPublicationProbe; do
  # Only the test's custom carrier constructor/diagnostic needs java.lang opens.
  "$task_java" --add-opens=java.base/java.lang=ALL-UNNAMED \
    -cp "$output_dir/probes:$output_dir/classes" "$probe"
done
