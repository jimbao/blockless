#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
alternatives_dir="$(cd -- "$probe_dir/.." && pwd)"
task_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
core_classes="${BLOCKLESS_CLASSPATH:-$alternatives_dir/target/stable-core}"
mkdir -p "$probe_dir/target/classes"
mapfile -t sources < <(rg --files "$probe_dir/publicentry" -g '*.java' | sort)
"$task_jdk/bin/javac" --release 21 -cp "$core_classes" \
  -d "$probe_dir/target/classes" "${sources[@]}"
classpath="$probe_dir/target/classes:$core_classes"
timeout 30s "$task_jdk/bin/java" -cp "$classpath" publicentry.PublicEntryProbe

# Preserve the direct JDK executor's actual result. A Java-21 early-termination
# failure is an explicit negative control, not a successful acceptance result.
if timeout 30s "$task_jdk/bin/java" -cp "$classpath" publicentry.PublicEntryProbe direct-jdk \
    > "$probe_dir/target/direct-jdk.txt" 2>&1; then
  direct_exit=0
else
  direct_exit=$?
fi
cat "$probe_dir/target/direct-jdk.txt"
printf 'Direct JDK cancellation/finally acceptance exit=%s\n' "$direct_exit"
if [[ "$direct_exit" -ne 0 ]]; then
  if [[ "$direct_exit" -eq 124 ]] || ! rg -Fq \
      'owner terminated before cancelled caller finally finished' "$probe_dir/target/direct-jdk.txt"; then
    exit "$direct_exit"
  fi
fi

python3 - "$task_jdk/bin/java" "$classpath" <<'PY'
import select
import subprocess
import sys
child = subprocess.Popen([sys.argv[1], '-cp', sys.argv[2], 'publicentry.PublicLifetimeProbe'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, text=True)
try:
    assert select.select([child.stdout], [], [], 5)[0], 'main output timeout'
    assert child.stdout.readline().strip() == 'MAIN_RETURN', 'unexpected main output'
    assert child.wait(timeout=5) == 0, child.stderr.read()
    assert 'ROOT_finished' not in child.stdout.read(), 'root finished without release input'
    print('CONFIRMED LIMIT: public daemon roots alone do not keep the JVM alive after main returns')
finally:
    if child.poll() is None:
        child.kill()
        child.wait(timeout=5)
PY
