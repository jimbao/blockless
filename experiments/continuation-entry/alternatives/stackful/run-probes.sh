#!/usr/bin/env bash
set -euo pipefail

probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
task_jdk="${JAVA_HOME:?Set JAVA_HOME to Java 21 or newer}"
core_classes="${CORE_CLASSES:?Set CORE_CLASSES to compiled upstream Blockless core}"
asm_jar="${ASM_JAR:?Set ASM_JAR to asm-9.8.jar}"
output_dir="$probe_dir/target"
mkdir -p "$output_dir"

fetch() {
  local artifact="$1" filename="$2" digest="${3:-sha256}"
  local url="https://repo.maven.apache.org/maven2/$artifact"
  if [[ ! -f "$output_dir/$filename" ]]; then
    curl -fsSL --retry 2 --max-time 60 "$url" -o "$output_dir/$filename"
  fi
  if [[ ! -f "$output_dir/$filename.$digest" ]]; then
    curl -fsSL --retry 2 --max-time 30 "$url.$digest" -o "$output_dir/$filename.$digest"
  fi
  python3 - "$output_dir/$filename" "$digest" <<'PY'
from pathlib import Path
from hashlib import new
import sys
path = Path(sys.argv[1])
digest = sys.argv[2]
expected = Path(str(path) + '.' + digest).read_text().strip().split()[0]
assert new(digest, path.read_bytes()).hexdigest() == expected, path
PY
}

fetch net/tascalate/javaflow/net.tascalate.javaflow.api/2.9.0/net.tascalate.javaflow.api-2.9.0.jar javaflow-api.jar
fetch net/tascalate/javaflow/net.tascalate.javaflow.tools.jar/2.9.0/net.tascalate.javaflow.tools.jar-2.9.0-shaded.jar javaflow-rewrite.jar
fetch org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar slf4j-api.jar sha1

rm -rf -- "$output_dir/classes" "$output_dir/build-tools"
mkdir -p "$output_dir/classes" "$output_dir/build-tools"
mapfile -t probe_sources < <(rg --files "$probe_dir/src" "$probe_dir/shadow" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes:$output_dir/javaflow-api.jar" \
  -d "$output_dir/classes" "${probe_sources[@]}"
"$task_jdk/bin/javac" --release 21 -cp "$asm_jar" -d "$output_dir/build-tools" \
  "$probe_dir/build-tools/RewriteGetCalls.java"
if [[ "${CPS_REWRITE_GET:-1}" != "0" ]]; then
  "$task_jdk/bin/java" -cp "$output_dir/build-tools:$asm_jar" portablecps.RewriteGetCalls \
    "$output_dir/classes/portablecps/PortableCpsProbe\$BusinessTask.class"
fi
"$task_jdk/bin/jar" --create --file "$output_dir/input.jar" -C "$output_dir/classes" .
"$task_jdk/bin/java" \
  -cp "$output_dir/javaflow-rewrite.jar:$output_dir/javaflow-api.jar:$core_classes" \
  org.apache.commons.javaflow.tools.jar.RewritingUtils \
  "$output_dir/input.jar" "$output_dir/enhanced.jar" > "$output_dir/enhance.txt" 2>&1

# No javaagent, opens, exports, preview flag, or private JDK scheduler at runtime.
runtime_classpath="$output_dir/enhanced.jar:$core_classes:$output_dir/javaflow-api.jar:$output_dir/slf4j-api.jar"
"$task_jdk/bin/java" -cp "$runtime_classpath" portablecps.BasicWeaveProbe
timeout 30s "$task_jdk/bin/java" -cp "$runtime_classpath" portablecps.PortableCpsProbe
