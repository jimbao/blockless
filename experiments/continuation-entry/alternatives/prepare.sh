#!/usr/bin/env bash
set -euo pipefail
alternatives_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(git -C "$alternatives_dir" rev-parse --show-toplevel)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_revision=3d070a36f85f68e91d360bfef7f232f4aa1dabad
output_dir="$alternatives_dir/target"
mkdir -p "$output_dir/upstream-main" "$output_dir/stable-core" \
  "$alternatives_dir/compensation/target/classes"
git -C "$repository_dir" archive --format=tar --output="$output_dir/upstream-core.tar" \
  "$core_revision" blockless/src/main/java
tar -xf "$output_dir/upstream-core.tar" -C "$output_dir/upstream-main"
mapfile -t core_sources < <(rg --files "$output_dir/upstream-main/blockless/src/main/java" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -d "$output_dir/stable-core" "${core_sources[@]}"
export JAVA_HOME="$task_jdk"
bash "$alternatives_dir/public-entry/agent/build.sh"
bash "$alternatives_dir/entry-interception/build.sh"
CORE_CLASSES="$output_dir/stable-core" bash "$alternatives_dir/lazy-help/build.sh"
"$task_jdk/bin/javac" --release 21 -d "$alternatives_dir/compensation/target/classes" \
  "$alternatives_dir/compensation/src/compensation/CompensatingTaskExecutor.java"
