#!/usr/bin/env python3
"""Compare caller-entry mechanisms using the same stable supplier/get gRPC handler."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import socket
from statistics import median
import subprocess
import time
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=Path('/workspace/.tools/blockless/jdk21'))
    parser.add_argument('--boot-jar', type=Path, default=Path('/workspace/blockless-spring-grpc-example/target/blockless-spring-grpc-example-0.1.0-SNAPSHOT.jar'))
    parser.add_argument('--ghz', type=Path, default=Path('/workspace/.tools/blockless/ghz/ghz'))
    parser.add_argument('--proto', type=Path, default=Path('/workspace/blockless-spring-grpc-example/src/main/proto/greeting.proto'))
    parser.add_argument('--asm-jar', type=Path, default=Path('/workspace/.tools/blockless/asm-9.8.jar'))
    parser.add_argument('--core-classes', type=Path)
    parser.add_argument('--public-agent', type=Path)
    parser.add_argument('--public-runtime', type=Path)
    parser.add_argument('--lazy-runtime', type=Path)
    parser.add_argument('--private-agent', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--variants', nargs='+', choices=['fixed', 'public', 'public-agent', 'offline', 'callback', 'lazy', 'compensated', 'private-agent', 'virtual'], default=['fixed', 'public-agent', 'offline', 'callback', 'lazy', 'compensated', 'virtual'])
    parser.add_argument('--repetitions', type=int, default=5)
    parser.add_argument('--initial-warmup', type=int, default=0)
    parser.add_argument('--functional-only', action='store_true')
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error('repetitions must be positive')
    if args.initial_warmup < 0:
        parser.error('initial warmup must be nonnegative')
    if 'private-agent' in args.variants and args.private_agent is None:
        parser.error('private-agent requires an explicit --private-agent path')
    network = Path(__file__).resolve().parent
    alternatives = network.parent
    args.output = args.output.resolve()
    if args.output.exists() and any(args.output.iterdir()):
        parser.error('output must be a fresh or empty directory')
    args.output.mkdir(parents=True, exist_ok=True)
    boot = network / 'target' / 'boot'
    if boot.exists():
        shutil.rmtree(boot)
    boot.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.boot_jar) as archive:
        for name in archive.namelist():
            if name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                archive.extract(name, boot)
    core = args.core_classes or alternatives / 'target' / 'stable-core'
    dependencies = f'{boot}/BOOT-INF/classes:{boot}/BOOT-INF/lib/*'
    fixtures = network / 'target' / 'classes'
    if fixtures.exists():
        shutil.rmtree(fixtures)
    fixtures.mkdir(exist_ok=True)
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '21', '-cp', f'{core}:{dependencies}', '-d', str(fixtures), *(str(p) for p in sorted((network / 'src').rglob('*.java')))], check=True)
    java = str(args.java_home / 'bin/java')
    woven = network / 'target' / 'woven-classes'
    if 'offline' in args.variants:
        if woven.exists():
            shutil.rmtree(woven)
        asm = args.asm_jar
        tools = network / 'target' / 'weaver-tools'
        tools.mkdir(exist_ok=True)
        subprocess.run([str(args.java_home / 'bin/javac'), '--release', '21', '-cp', str(asm), '-d', str(tools), str(alternatives / 'public-entry/build-time/OfflineEntryWeaver.java')], check=True)
        subprocess.run([java, '-cp', f'{tools}:{asm}', 'OfflineEntryWeaver', str(fixtures), str(woven), 'org.pjlabs.example.AlternativeConfiguration'], check=True)
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
    public_jar = args.public_runtime or alternatives / 'public-entry/target/agent/public-entry-runtime.jar'
    extra = f'{public_jar}:{alternatives}/compensation/target/classes'
    public_agent = args.public_agent or alternatives / 'public-entry/target/agent/public-entry-agent.jar'
    callback_agent = alternatives / 'entry-interception/target/callback-entry-agent.jar'
    private_agent = args.private_agent
    summary = dict(jvm=version.stderr.strip(), core_source='origin/main@3d070a36f85f68e91d360bfef7f232f4aa1dabad', boot_sha256=hashlib.sha256(args.boot_jar.read_bytes()).hexdigest(), settings=dict(request_workers=4, compensated_max_workers=10, active_processors=2, heap_mb=512, db_pool_size=10, sleep_seconds=0.01, concurrency=32, connections=4, repetitions=args.repetitions, initial_warmup_seconds=args.initial_warmup, warmup_seconds=1, measure_seconds=3), variants={})
    control = 'http://localhost:18106'
    address = 'localhost:19090'

    def state():
        with urllib.request.urlopen(control + '/research/entry/state', timeout=5) as response:
            return json.load(response)

    for name in args.variants:
        output = args.output / name
        output.mkdir(exist_ok=True)
        entry = name if name in ('public', 'lazy', 'compensated', 'virtual') else 'fixed'
        classpath = f'{fixtures}:{core}:{extra}:{dependencies}'
        if name == 'offline':
            classpath = f'{woven}:{core}:{extra}:{dependencies}'
        if name == 'lazy':
            shadow = args.lazy_runtime or alternatives / 'lazy-help/target/lazy-help-runtime.jar'
            if not shadow.is_file():
                raise FileNotFoundError('Full lazy-help runtime bundle is not ready')
            classpath = f'{shadow}:{classpath}'
        command = [java, '-Xms512m', '-Xmx512m', '-XX:ActiveProcessorCount=2', f'-Xlog:class+load=info:file={output}/classes.log']
        if name == 'public-agent':
            command += [f'-javaagent:{public_agent}=include=org.pjlabs.example.AlternativeConfiguration']
        elif name == 'callback':
            command += [f'-javaagent:{callback_agent}=class=io.grpc.internal.SerializingExecutor;method=run']
        elif name == 'private-agent':
            command += [f'-javaagent:{private_agent}=include=org.pjlabs.example.AlternativeConfiguration;cachedCarriers=4']
        command += ['-cp', classpath, 'org.pjlabs.example.Application', f'--research.entry={entry}', '--grpc.server.port=19090', '--server.port=18106', '--spring.datasource.url=jdbc:postgresql://localhost:15433/benchmark']
        (output / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
        print(f'START {name}', flush=True)
        with (output / 'app.log').open('w') as app_log:
            app = subprocess.Popen(command, stdout=app_log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if app.poll() is not None:
                        raise RuntimeError(f'{name} exited: {output / "app.log"}')
                    try:
                        with socket.create_connection(('localhost', 19090), timeout=0.1):
                            log = (output / 'app.log').read_text()
                            if 'Seeded ' in log or 'Data already seeded' in log:
                                break
                    except OSError:
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(f'{name} startup timed out')
                    time.sleep(0.1)
                functional = subprocess.run([java, '-cp', f'{fixtures}:{dependencies}', 'research.AlternativeContractClient', address, control, str(name != 'fixed').lower(), name], capture_output=True, text=True, timeout=35)
                (output / 'functional.txt').write_text(functional.stdout + functional.stderr)
                functional.check_returncode()
                print(f'{name}: four held requests, actual values and saturated health check passed', flush=True)
                row = dict(functional=functional.stdout.strip(), before=state(), samples=[])
                if not args.functional_only:
                    data = json.dumps(dict(mode='blockless', country='SE', sleep_seconds=0.01, db_pool_size=10))
                    base = [str(args.ghz), '--insecure', '--proto', str(args.proto), '--call', 'org.pjlabs.example.BenchmarkService/Query', '--concurrency', '32', '--connections', '4', '--timeout', '10s', '--duration-stop', 'wait', '--data', data, '--format', 'json']
                    if args.initial_warmup:
                        print(f'{name}: initial warmup {args.initial_warmup}s', flush=True)
                        warm = subprocess.run(base + ['--duration', f'{args.initial_warmup}s', address], capture_output=True, text=True, timeout=args.initial_warmup + 30)
                        (output / 'initial-warmup.json').write_text(warm.stdout)
                        (output / 'initial-warmup.stderr').write_text(warm.stderr)
                        warm.check_returncode()
                        warmed = json.loads(warm.stdout)
                        if warmed['count'] <= 0 or warmed.get('statusCodeDistribution', {}).get('OK', 0) != warmed['count']:
                            raise RuntimeError(f'{name}: failed initial warmup RPCs')
                    for repetition in range(1, args.repetitions + 1):
                        print(f'{name}: sample {repetition}/{args.repetitions}', flush=True)
                        for phase, duration in [('warmup', '1s'), ('measure', '3s')]:
                            run = subprocess.run(base + ['--duration', duration, address], capture_output=True, text=True, timeout=30)
                            stem = output / f'{repetition}-{phase}'
                            stem.with_suffix('.json').write_text(run.stdout)
                            stem.with_suffix('.stderr').write_text(run.stderr)
                            run.check_returncode()
                            result = json.loads(run.stdout)
                            if result['count'] <= 0 or result.get('statusCodeDistribution', {}).get('OK', 0) != result['count']:
                                raise RuntimeError(f'{name}: failed RPCs, see {stem}')
                            if phase == 'measure':
                                p95 = next(item['latency'] / 1e6 for item in result['latencyDistribution'] if item['percentage'] == 95)
                                row['samples'].append(dict(rps=result['rps'], count=result['count'], p95_ms=p95))
                    row.update(median_rps=median(r['rps'] for r in row['samples']), median_p95_ms=median(r['p95_ms'] for r in row['samples']), min_rps=min(r['rps'] for r in row['samples']), max_rps=max(r['rps'] for r in row['samples']), count=sum(r['count'] for r in row['samples']), errors=0)
                row['after'] = state()
                row['caller_delta'] = {key: row['after'][key] - row['before'][key] for key in ['queryVirtualCallers', 'queryPlatformCallers']}
                summary['variants'][name] = row
                (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
                print(f'{name}: {row.get("median_rps", "functional-only")} req/s; callers={row["caller_delta"]}', flush=True)
            except Exception as failure:
                (output / 'failure.json').write_text(json.dumps(dict(type=type(failure).__name__, message=str(failure)), indent=2) + '\n')
                try:
                    (output / 'failure-state.json').write_text(json.dumps(state(), indent=2) + '\n')
                except Exception:
                    pass
                if app.poll() is None:
                    for diagnostic, command_args in [('threads.txt', ['Thread.print', '-l']), ('virtual-threads-command.txt', ['Thread.dump_to_file', '-format=json', str(output / 'virtual-threads.json')])]:
                        try:
                            diagnostic_run = subprocess.run([str(args.java_home / 'bin/jcmd'), str(app.pid), *command_args], capture_output=True, text=True, timeout=10)
                            (output / diagnostic).write_text(diagnostic_run.stdout + diagnostic_run.stderr)
                        except Exception as diagnostic_failure:
                            (output / diagnostic).write_text(str(diagnostic_failure))
                raise
            finally:
                app.terminate()
                try:
                    app.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait()
        print(f'STOP {name}', flush=True)


if __name__ == '__main__':
    main()
