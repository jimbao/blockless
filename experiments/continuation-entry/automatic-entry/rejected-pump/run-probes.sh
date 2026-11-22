#!/usr/bin/env bash
set -euo pipefail
probe_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
probe_jdk="${JAVA_HOME:-/workspace/.tools/blockless/jdk21}"
mkdir -p "$probe_dir/target"
"$probe_jdk/bin/javac" -d "$probe_dir/target" "$probe_dir"/*.java
"$probe_jdk/bin/java" --add-opens=java.base/java.lang=ALL-UNNAMED -cp "$probe_dir/target" DynamicGetPumpProbe
"$probe_jdk/bin/java" --add-opens=java.base/java.lang=ALL-UNNAMED -cp "$probe_dir/target" DynamicGetPumpProbe cancel
"$probe_jdk/bin/java" --add-opens=java.base/java.lang=ALL-UNNAMED -cp "$probe_dir/target" MonitorBoundaryProbe
python3 - "$probe_jdk/bin/java" "$probe_dir/target" <<'PY'
import select
import subprocess
import sys
for mode in ['platform', 'virtual']:
    child = subprocess.Popen([sys.argv[1], '-cp', sys.argv[2], 'PublicSchedulerLifetimeProbe', mode],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        if not select.select([child.stdout], [], [], 5)[0]: raise AssertionError('main output timeout')
        if child.stdout.readline().strip() != 'MAIN_RETURN': raise AssertionError('unexpected main output')
        if mode == 'platform':
            try:
                child.wait(timeout=0.2)
                raise AssertionError('platform owner failed to keep root alive')
            except subprocess.TimeoutExpired:
                pass
            stdout, stderr = child.communicate('x', timeout=5)
            if child.returncode != 0 or 'ROOT_FINISHED' not in stdout: raise AssertionError(stderr)
            print('PUBLIC LIFETIME: platform owner keeps JVM alive after main until root finishes')
        else:
            if child.wait(timeout=5) != 0: raise AssertionError(child.stderr.read())
            if 'ROOT_FINISHED' in child.stdout.read(): raise AssertionError('root finished without input')
            print('PUBLIC LIFETIME: daemon VT owner permits JVM exit with an unfinished root')
    finally:
        if child.poll() is None:
            child.kill()
            child.wait(timeout=5)
PY
