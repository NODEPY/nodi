#!/usr/bin/env python3
"""Integration checks for the JSON/stdin interface used by the Mac app."""
import base64
import hashlib
import json
import os
from pathlib import Path
import select
import socket
import ssl
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def event(process):
    if not select.select([process.stdout], [], [], 8)[0]:
        raise AssertionError('No core event within 8 seconds')
    line = process.stdout.readline()
    assert line, 'Core exited before event'
    return json.loads(line)

def start(core, inbox):
    proc = subprocess.Popen([str(core), 'receive', '-json', '-listen', '127.0.0.1:0', '-out', str(inbox)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
    ready = event(proc)
    assert ready['type'] == 'ready'
    return proc, ready['invites'][0]['code']

def send(core, code, file):
    result = subprocess.run([str(core), 'send', '-json', '-code-stdin', '-file', str(file)],
                            input=code, capture_output=True, text=True, timeout=15)
    return result, [json.loads(line) for line in result.stdout.splitlines()]

def stop(proc):
    proc.terminate()
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()
        raise AssertionError('Receiver did not stop gracefully')
    proc.stdout.close()
    proc.stderr.close()

with tempfile.TemporaryDirectory(prefix='nodi-check-') as directory:
    root = Path(directory)
    core = root/'nodi-core'
    subprocess.run(['go','build','-buildvcs=false','-o',str(core),'./cmd/nodi'],cwd=ROOT,check=True)
    inbox = root/'received'
    source = root/'тест.bin'
    source.write_bytes(os.urandom(1024*1024))
    receiver, code = start(core,inbox)
    try:
        sent, events = send(core,code,source)
        assert sent.returncode == 0, sent.stderr
        assert [e['type'] for e in events] == ['sending','sent']
        assert event(receiver)['type'] == 'received'
        assert (inbox/source.name).read_bytes() == source.read_bytes()
        print('PASS: JSON/stdin round trip, 1 MiB, Unicode filename')
        sent, events = send(core,code,source)
        assert sent.returncode != 0 and events[-1]['type'] == 'fatal'
        assert event(receiver)['type'] == 'error'
        print('PASS: existing file rejected and UI error emitted')
        sent, events = send(core,'nodi1-invalid',source)
        assert sent.returncode != 0 and events[-1]['type'] == 'fatal'
        print('PASS: invalid code produces a structured error')
        # Interrupt an upload: Stop must clean its temporary file.
        data = code.removeprefix('nodi1-')
        invite = json.loads(base64.urlsafe_b64decode(data + '=' * (-len(data) % 4)))
        host, port = invite['address'].rsplit(':',1)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with context.wrap_socket(socket.create_connection((host,int(port)),timeout=5),server_hostname=host) as connection:
            assert hashlib.sha256(connection.getpeercert(binary_form=True)).hexdigest() == invite['fingerprint']
            header = {'token':invite['token'],'name':'partial.bin','size':1000000,'sha256':'0'*64}
            connection.sendall(json.dumps(header).encode()+b'\n')
            with connection.makefile('rb') as stream:
                assert json.loads(stream.readline())['ok']
                connection.sendall(b'only a small part')
                receiver.terminate()
                receiver.wait(timeout=8)
                assert receiver.returncode == 0
                assert not list(inbox.glob('.nodi-*.part'))
                assert not (inbox/'partial.bin').exists()
        print('PASS: SIGTERM closes active connection and removes partial data')
    finally:
        stop(receiver)
print('All desktop core integration checks passed.')
