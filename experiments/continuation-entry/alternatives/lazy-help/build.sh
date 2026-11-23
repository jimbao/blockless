#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${CORE_CLASSES:-$probe_dir/../target/stable-core}"
classes="$probe_dir/target/build-classes"
rm -rf -- "$classes"
mkdir -p "$classes"
"$task_jdk/bin/javac" --release 21 -cp "$core_classes" -d "$classes" \
  "$probe_dir/src/helping/LazyHelpingExecutor.java" "$probe_dir/src/helping/WaitSupport.java" \
  "$probe_dir/shadow/io/github/pjlabs/blockless/OutcomeSupplier.java"
"$task_jdk/bin/jar" --create --file "$probe_dir/target/lazy-help-runtime.jar" -C "$classes" .
echo "$probe_dir/target/lazy-help-runtime.jar"
