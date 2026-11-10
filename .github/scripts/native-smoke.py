#!/usr/bin/env python3
"""Exercise the hello example; optionally report approximate Linux process measurements."""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

measure = sys.argv[1] == '--measure'
command = sys.argv[2:] if measure else sys.argv[1:]
env = os.environ.copy()
env['PORT'] = '18080'
url = 'http://127.0.0.1:18080/greetings'
start = time.monotonic()
process = subprocess.Popen(command, env=env)


def request(method, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=2) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, error.read()


try:
    deadline = time.monotonic() + 60
    while True:
        if process.poll() is not None:
            raise RuntimeError(f'application exited with status {process.returncode}')
        try:
            status, initial = request('GET')
            break
        except (urllib.error.URLError, TimeoutError):
            if time.monotonic() >= deadline:
                raise RuntimeError('application did not become ready within 60 seconds')
            time.sleep(0.1)
    startup_ms = (time.monotonic() - start) * 1000
    assert status == 200 and initial == [], (status, initial)
    if measure:
        # Measurements are informational: unavailable values never fail the smoke test.
        try:
            size = f'{os.path.getsize(command[0]):,} bytes'
        except OSError:
            size = 'unavailable'
        try:
            with open(f'/proc/{process.pid}/status', encoding='utf-8') as status_file:
                rss = next(line.split(':', 1)[1].strip() for line in status_file if line.startswith('VmRSS:'))
        except (OSError, StopIteration):
            rss = 'unavailable'
        report = (f'## Native Image smoke measurements\n\n'
                  f'- Executable size: {size}\n'
                  f'- Time to first successful HTTP response: approximately {startup_ms:.0f} ms\n'
                  f'- RSS after readiness, before write requests: {rss}\n')
        print(report, flush=True)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            try:
                with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
                    summary.write(report)
            except OSError as error:
                print(f'Could not write job summary: {error}', file=sys.stderr)
    with urllib.request.urlopen('http://127.0.0.1:18080/', timeout=2) as response:
        assert response.status == 200 and b'Hello CoilBase' in response.read()
        assert response.headers.get_content_type() == 'text/html'
    head = urllib.request.Request('http://127.0.0.1:18080/', method='HEAD')
    with urllib.request.urlopen(head, timeout=2) as response:
        assert response.status == 200 and response.read() == b''
    status, created = request('POST', {'name': 'Ada'})
    assert status == 201 and created['id'] and created['message'] == 'Hello, Ada!', (status, created)
    status, _ = request('POST', {'name': ' '})
    assert status == 400, status
    status, stored = request('GET')
    assert status == 200 and stored == [created], (status, stored)
    print('Native smoke HTTP checks passed', flush=True)
finally:
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()
