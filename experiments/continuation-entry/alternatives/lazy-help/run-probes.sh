#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${CORE_CLASSES:-$probe_dir/../target/stable-core}"
mkdir -p "$probe_dir/target/classes"
mapfile -t sources < <(rg --files "$probe_dir/src" "$probe_dir/shadow" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes" -d "$probe_dir/target/classes" "${sources[@]}"
timeout 30s "$task_jdk/bin/java" -XX:ActiveProcessorCount=2 \
  -cp "$probe_dir/target/classes:$core_classes" helping.LazyHelpProbe
timeout 30s "$task_jdk/bin/java" -XX:ActiveProcessorCount=2 \
  -cp "$probe_dir/target/classes:$core_classes" helping.LazyHelpRaceProbe
timeout 30s "$task_jdk/bin/java" -XX:ActiveProcessorCount=2 \
  -cp "$probe_dir/target/classes:$core_classes" helping.QueuedCancellationProbe
python3 "$probe_dir/check-lifetime.py" "$task_jdk/bin/java" "$probe_dir/target/classes:$core_classes"
