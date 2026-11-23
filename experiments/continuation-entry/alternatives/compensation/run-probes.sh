#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
alternatives_dir="$(cd -- "$probe_dir/.." && pwd)"
probe_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${BLOCKLESS_CLASSPATH:-$alternatives_dir/target/stable-core}"
output_dir="$probe_dir/target"
mkdir -p "$output_dir/classes" "$output_dir/shadow"

"$probe_jdk/bin/javac" --release 21 -cp "$core_classes" -d "$output_dir/classes" \
  "$probe_dir"/src/compensation/*.java
"$probe_jdk/bin/javac" --release 21 -cp "$core_classes" -d "$output_dir/shadow" \
  "$probe_dir/shadow/io/github/pjlabs/blockless/OutcomeSupplier.java"

"$probe_jdk/bin/java" -XX:ActiveProcessorCount=2 \
  -cp "$output_dir/classes:$core_classes" compensation.CompensationProbe
"$probe_jdk/bin/java" -XX:ActiveProcessorCount=2 \
  -cp "$output_dir/classes:$output_dir/shadow:$core_classes" \
  compensation.CompensationProbe selective

if [[ "${1:-}" == "--load" ]]; then
  "$probe_jdk/bin/java" -XX:ActiveProcessorCount=2 \
    -cp "$output_dir/classes:$core_classes" compensation.CompensationProbe load
  "$probe_jdk/bin/java" -XX:ActiveProcessorCount=2 \
    -cp "$output_dir/classes:$output_dir/shadow:$core_classes" \
    compensation.CompensationProbe load forkjoin4
fi
