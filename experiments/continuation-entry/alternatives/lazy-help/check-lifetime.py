#!/usr/bin/env python3
"""Release the helped root only after Java main returns, proving non-daemon ownership."""
import select
import subprocess
import sys

child = subprocess.Popen(
    [sys.argv[1], '-XX:ActiveProcessorCount=2', '-cp', sys.argv[2],
     'helping.ShutdownLifetimeProbe'],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
try:
    if not select.select([child.stdout], [], [], 5)[0]:
        raise AssertionError('main output timeout')
    if child.stdout.readline().strip() != 'MAIN_RETURN':
        raise AssertionError('unexpected main output')
    try:
        child.wait(timeout=0.2)
        raise AssertionError('JVM exited before helped task completion')
    except subprocess.TimeoutExpired:
        pass
    stdout, stderr = child.communicate('x', timeout=5)
    if child.returncode != 0 or 'HELPED_FINISHED' not in stdout:
        raise AssertionError(stderr or stdout)
    print('PASS: shutdown retains an original platform worker after main returns until helped root finishes; no extra keeper thread')
finally:
    if child.poll() is None:
        child.kill()
        child.wait(timeout=5)
