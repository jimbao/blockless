#!/usr/bin/env bash
set -euo pipefail
experiment_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd -- "$experiment_dir/../.." && pwd)"
task_java="${JAVA_HOME:?Set JAVA_HOME to Java 21 or newer}/bin/java"
blockless_classes="${BLOCKLESS_CLASSPATH:-$repo_dir/blockless/target/classes:$repo_dir/blockless-continuations/target/classes}"
output_dir="$experiment_dir/target/virtual-owner"
mkdir -p "$output_dir"
"$JAVA_HOME/bin/javac" -cp "$blockless_classes" -d "$output_dir" \
  "$experiment_dir"/virtual-owner/*.java
"$task_java" --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED \
  -cp "$output_dir:$blockless_classes" PublicOwnerProbe
for probe in VirtualOwnerProbe VirtualOwnerReviewProbe; do
  "$task_java" --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-exports=java.base/jdk.internal.vm=ALL-UNNAMED \
    -cp "$output_dir:$blockless_classes" "$probe"
done
