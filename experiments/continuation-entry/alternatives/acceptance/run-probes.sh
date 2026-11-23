#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${CORE_CLASSES:-$probe_dir/../target/stable-core}"
candidate_classes="${CANDIDATE_CLASSES:-}"
mkdir -p "$probe_dir/target/classes"
mapfile -t sources < <(rg --files "$probe_dir/admission" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes" \
  -d "$probe_dir/target/classes" "${sources[@]}"
classpath="$probe_dir/target/classes:$core_classes"
if [[ -n "$candidate_classes" ]]; then
  classpath="$candidate_classes:$classpath"
fi
timeout 20s "$task_jdk/bin/java" -cp "$classpath" admission.SupplierContractProbe "$@"
