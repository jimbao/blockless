#!/usr/bin/env python3
"""Exercise immediate supplier/get over real gRPC, then compare successful modes."""

import argparse
import json
from pathlib import Path
import socket
from statistics import median
import subprocess
import time
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--boot-jar', type=Path, required=True)
    parser.add_argument('--blockless-classpath', required=True)
    parser.add_argument('--agent-jar', type=Path, required=True)
    parser.add_argument('--proto', type=Path, required=True)
    parser.add_argument('--ghz', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, default=5)
    parser.add_argument('--functional-only', action='store_true')
    parser.add_argument('--native-free', action='store_true',
                        help='Omit the native library add-exports flag')
    parser.add_argument('--default-grpc-proof', action='store_true',
                        help='Also test the original gRPC cached factory with only framework instrumentation')
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error('repetitions must be positive')
    args.output.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).resolve().parent / 'src'
    extracted = args.output / 'boot'
    extracted.mkdir(exist_ok=True)
    with zipfile.ZipFile(args.boot_jar) as boot:
        for entry in boot.namelist():
            if entry.startswith(('BOOT-INF/lib/', 'BOOT-INF/classes/')):
                boot.extract(entry, extracted)
    fixture_classes = args.output / 'fixture-classes'
    fixture_classes.mkdir(exist_ok=True)
    dependencies = f'{extracted}/BOOT-INF/classes:{extracted}/BOOT-INF/lib/*'
    subprocess.run([str(args.java_home / 'bin/javac'), '--release', '21', '-cp',
                    f'{args.blockless_classpath}:{dependencies}', '-d', str(fixture_classes),
                    *(str(path) for path in sorted(source.rglob('*.java')))], check=True)
    java = str(args.java_home / 'bin/java')
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
    summary = dict(jvm=version.stderr.strip(), settings=dict(
        physical_workers=4, active_processors=2, heap_mb=512, db_pool_size=10,
        sleep_seconds=0.01, concurrency=32, connections=4, native_free=args.native_free,
        warmup_seconds=1, measure_seconds=3, repetitions=args.repetitions),
        functional={}, scenarios={})
    launch_classpath = f'{fixture_classes}:{args.blockless_classpath}:{dependencies}'
    client_classpath = f'{fixture_classes}:{dependencies}'

    variants = [
            ('platform', 'platform', False),
            ('automatic', 'platform', True),
            ('virtual', 'virtual', False)]
    if args.default_grpc_proof:
        variants.append(('grpc-default', 'platform', True))
    for variant, request_executor, agent in variants:
        output = args.output / variant
        output.mkdir(exist_ok=True)
        command = [java, '-Xms512m', '-Xmx512m', '-XX:ActiveProcessorCount=2']
        if not args.native_free:
            command += ['--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED']
        if agent:
            includes = 'io.grpc.internal.' if variant == 'grpc-default' else 'org.pjlabs.example.,io.grpc.internal.'
            command += [f'-javaagent:{args.agent_jar}=include={includes};cachedCarriers=4']
        command += ['-cp', launch_classpath, 'org.pjlabs.example.Application',
                    f'--research.grpc.executor={request_executor}', '--research.grpc.workers=4']
        if variant == 'grpc-default':
            command += ['--research.grpc.configure-executor=false']
        (output / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
        with (output / 'app.log').open('w') as app_log:
            app = subprocess.Popen(command, stdout=app_log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if app.poll() is not None:
                        raise RuntimeError(f'{variant} app exited: {output / "app.log"}')
                    try:
                        with socket.create_connection(('localhost', 9090), timeout=0.1):
                            break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise TimeoutError(f'{variant} app did not start')
                        time.sleep(0.1)
                functional = subprocess.run(
                    [java, '-cp', client_classpath, 'research.StrictGrpcContractClient',
                     'localhost:9090', 'baseline' if variant == 'platform' else 'automatic'],
                    capture_output=True, text=True, timeout=30)
                (output / 'functional.txt').write_text(functional.stdout + functional.stderr)
                print(f'{variant}: {functional.stdout.strip()}', flush=True)
                functional.check_returncode()
                summary['functional'][variant] = functional.stdout.strip()
                if args.functional_only or variant == 'grpc-default':
                    continue
                modes = ('join', 'continuation')
                samples = {mode: [] for mode in modes}
                for repetition in range(1, args.repetitions + 1):
                    order = modes if repetition % 2 else tuple(reversed(modes))
                    for mode in order:
                        print(f'{variant}/{mode}: sample {repetition}/{args.repetitions}', flush=True)
                        for phase, duration in (('warmup', '1s'), ('measure', '3s')):
                            result = subprocess.run([
                                str(args.ghz), '--insecure', '--proto', str(args.proto),
                                '--call', 'org.pjlabs.example.BenchmarkService/Query',
                                '--concurrency', '32', '--connections', '4', '--timeout', '10s',
                                '--duration-stop', 'wait', '--duration', duration,
                                '--data', json.dumps(dict(mode=mode, country='SE',
                                    sleep_seconds=0.01, db_pool_size=10)),
                                '--format', 'json', 'localhost:9090'],
                                capture_output=True, text=True, timeout=30)
                            stem = output / f'{repetition}-{mode}-{phase}'
                            stem.with_suffix('.json').write_text(result.stdout)
                            stem.with_suffix('.stderr').write_text(result.stderr)
                            result.check_returncode()
                            data = json.loads(result.stdout)
                            ok = data.get('statusCodeDistribution', {}).get('OK', 0)
                            if data['count'] <= 0 or ok != data['count']:
                                raise RuntimeError(f'{variant}/{mode}: {data["count"] - ok} RPC errors')
                            if phase == 'measure':
                                p95 = next(row['latency'] / 1e6 for row in data['latencyDistribution']
                                           if row['percentage'] == 95)
                                samples[mode].append(dict(rps=data['rps'], p95_ms=p95,
                                                          count=data['count']))
                for mode, rows in samples.items():
                    summary['scenarios'][f'{variant}-{mode}'] = dict(
                        median_rps=median(row['rps'] for row in rows),
                        median_p95_ms=median(row['p95_ms'] for row in rows),
                        min_rps=min(row['rps'] for row in rows),
                        max_rps=max(row['rps'] for row in rows),
                        count=sum(row['count'] for row in rows), errors=0)
                (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
            finally:
                app.terminate()
                try:
                    app.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait()
        print(f'{variant}: app stopped', flush=True)
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == '__main__':
    main()
