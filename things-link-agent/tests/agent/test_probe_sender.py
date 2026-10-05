import http.client
import json
import socket
import ssl
import threading
import time
from contextlib import contextmanager
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from uuid import uuid4

import pytest

from agent.internal_server import build_server_config
from agent.probe_sender import DeepSeekProbeSender, execute_probe
from agent.token_probe import ProbeError, synthetic_probe_requests
from test_probe_counter import counter
from test_mtls import TemporaryPKI, running_server

SECRET = 'synthetic-probe-credential'


def document(counter, index=1, **changes):
    p = dict(attemptId=str(uuid4()), sampleIndex=index, manifestSha256=counter.manifest_sha256(),
             deadlineEpochMillis=int(datetime.now(UTC).timestamp()*1000)+59000)
    p.update(changes)
    return json.dumps(p).encode()


def response(counter, index=1):
    count = counter.measure(synthetic_probe_requests()[index-1]).input_tokens + 1
    return json.dumps(dict(object='chat.completion', model='deepseek-flash', system_fingerprint='synthetic-fingerprint',
        choices=[dict(index=0, message=dict(role='assistant', content='PRIVATE_GENERATED_CONTENT'), finish_reason='length')],
        usage=dict(prompt_tokens=count, completion_tokens=128, total_tokens=count+128,
                   prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=count))).encode()


class FakeSender:
    def __init__(self, counter, status=200, error=None):
        self.counter, self.status, self.error, self.visits = counter, status, error, []
    def send(self, body, key, deadline):
        self.visits.append(body)
        assert key == SECRET
        assert len(body) < 16384 and json.loads(body)['max_tokens'] == 128
        if self.error:
            raise self.error
        index = [p.body for p in synthetic_probe_requests()].index(body) + 1
        return self.status, response(self.counter, index)


@pytest.mark.parametrize('index', [1, 2, 3])
def test_fixed_usage_only_no_response_or_credential_retained(counter, index, caplog):
    sender = FakeSender(counter)
    result = execute_probe(document(counter,index), SECRET, counter, sender)
    assert result['outcome'] == 'SUCCEEDED' and result['category'] == 'COUNT_MISMATCH'
    assert result['usage']['delta'] == 1 and result['usage']['completionTokens'] == 128
    assert len(sender.visits) == 1
    assert all(v not in json.dumps(result)+caplog.text for v in (SECRET, 'PRIVATE_GENERATED_CONTENT', 'synthetic-fingerprint'))


@pytest.mark.parametrize('change', [dict(sampleIndex=True),dict(sampleIndex=4),dict(manifestSha256='f'*64),
    dict(attemptId='bad'),dict(deadlineEpochMillis=0),dict(deadlineEpochMillis=10**20),dict(extra='raw-device')])
def test_invalid_internal_material_never_sends(counter, change):
    sender = FakeSender(counter)
    with pytest.raises(ProbeError, match='INVALID_INTERNAL_PROBE') as error:
        execute_probe(document(counter,**change),SECRET,counter,sender)
    assert error.value.__context__ is None and not sender.visits


@pytest.mark.parametrize('status,category', [(401,'SUPPLIER_AUTH'),(402,'SUPPLIER_BALANCE'),(429,'SUPPLIER_RATE_LIMIT'),(302,'SUPPLIER_REJECTED'),(503,'SUPPLIER_REJECTED')])
def test_rejected_supplier_not_retried(counter,status,category):
    sender=FakeSender(counter,status)
    result=execute_probe(document(counter),SECRET,counter,sender)
    assert result['outcome']=='FAILED' and result['category']==category and result['usage'] is None
    assert len(sender.visits)==1


def test_network_unknown_and_bad_usage_are_consumed_without_echo(counter):
    sender=FakeSender(counter,error=OSError(SECRET))
    result=execute_probe(document(counter),SECRET,counter,sender)
    assert result['outcome']=='UNKNOWN' and len(sender.visits)==1 and SECRET not in str(result)
    sender.send=lambda *a:(200,b'{"secret":"PRIVATE_GENERATED_CONTENT"}')
    result=execute_probe(document(counter),SECRET,counter,sender)
    assert result['outcome']=='FAILED' and result['category']=='INVALID_USAGE'


@contextmanager
def supplier(pki, body, status=200, delay=0):
    visits=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):
            pass
        def do_POST(self):
            payload=self.rfile.read(int(self.headers['Content-Length']))
            assert self.headers['Authorization']=='Bearer '+SECRET
            visits.append((self.path,payload))
            time.sleep(delay)
            self.send_response(status)
            self.send_header('Content-Length',str(len(body)))
            self.end_headers()
            try:self.wfile.write(body)
            except OSError:pass
    server=HTTPServer(('127.0.0.1',0),Handler)
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(*pki.server)
    server.socket=context.wrap_socket(server.socket,server_side=True)
    thread=threading.Thread(target=server.serve_forever,kwargs={'poll_interval':0.01},daemon=True);thread.start()
    try:yield server.server_port,visits
    finally:server.shutdown();server.server_close();thread.join(2)


def route_supplier(monkeypatch,pki,port):
    def connection(self,deadline):
        raw=socket.create_connection(('127.0.0.1',port),timeout=min(5,deadline-time.monotonic()))
        return pki.client_context().wrap_socket(raw,server_hostname='localhost')
    monkeypatch.setattr(DeepSeekProbeSender,'_connect',connection)



@pytest.mark.parametrize('status', [200,302,401])
def test_actual_https_one_fixed_post_no_redirect_or_retry(counter,tmp_path,monkeypatch,status):
    pki=TemporaryPKI(tmp_path)
    with supplier(pki,response(counter),status) as (port,visits):
        route_supplier(monkeypatch,pki,port)
        result=execute_probe(document(counter),SECRET,counter,DeepSeekProbeSender())
        assert result['outcome']==('SUCCEEDED' if status==200 else 'FAILED')
        assert visits==[('/chat/completions',synthetic_probe_requests()[0].body)]


def test_actual_https_response_limit_and_deadline(counter,tmp_path,monkeypatch):
    pki=TemporaryPKI(tmp_path)
    with supplier(pki,b'x'*16385) as (port,visits):
        route_supplier(monkeypatch,pki,port)
        with pytest.raises(ProbeError):
            DeepSeekProbeSender().send(synthetic_probe_requests()[0].body,SECRET,time.monotonic()+1)
        assert len(visits)==1


    with supplier(pki,response(counter),delay=.15) as (port,visits):
        route_supplier(monkeypatch,pki,port)
        with pytest.raises(TimeoutError):
            DeepSeekProbeSender().send(synthetic_probe_requests()[0].body,SECRET,time.monotonic()+.05)
        assert len(visits)==1


@pytest.mark.parametrize('expired', [True, False])
@pytest.mark.parametrize('failure', [BrokenPipeError, ValueError, http.client.RemoteDisconnected])
def test_deadline_close_normalizes_only_errors_after_expiry(monkeypatch, expired, failure):
    class Peer:
        def settimeout(self, value): pass
        def sendall(self, value): pass
        def shutdown(self, value): pass
        def close(self): self.closed = True
    peer = Peer()
    peer.closed = False
    connects = []
    monkeypatch.setattr(DeepSeekProbeSender, '_connect', lambda self, deadline: connects.append(deadline) or peer)
    class Timer:
        def __init__(self, delay, callback): self.callback = callback
        def start(self):
            if expired: self.callback()
        def cancel(self): pass
    error = failure('synthetic-error')
    class Response:
        fp = None
        def __init__(self, socket): pass
        def begin(self): raise error
        def close(self): pass
    monkeypatch.setattr(threading, 'Timer', Timer)
    monkeypatch.setattr(http.client, 'HTTPResponse', Response)
    with pytest.raises(TimeoutError if expired else failure) as caught:
        DeepSeekProbeSender().send(b'{}', SECRET, time.monotonic()+10)
    if expired:
        assert str(caught.value) == '' and caught.value.__suppress_context__
    else:
        assert caught.value is error
    assert len(connects) == 1 and peer.closed


def test_deadline_exhausted_after_connect_still_closes_peer(monkeypatch):
    class Peer:
        closed = False
        def close(self): self.closed = True
    peer = Peer()
    monkeypatch.setattr(DeepSeekProbeSender, '_connect', lambda self, deadline: peer)
    with pytest.raises(TimeoutError):
        DeepSeekProbeSender().send(b'{}', SECRET, time.monotonic()-1)
    assert peer.closed


def test_deadline_exhausted_after_tls_wrap_closes_transferred_peer(monkeypatch):
    class Raw:
        def settimeout(self, value): pass
        def connect(self, target): pass
        def close(self): pass
    class Peer:
        closed = False
        def close(self): self.closed = True
    peer = Peer()
    class Context:
        def load_default_certs(self): pass
        def wrap_socket(self, raw, server_hostname): return peer
    monkeypatch.setattr(ssl, 'SSLContext', lambda *args: Context())
    monkeypatch.setattr(socket, 'getaddrinfo', lambda *args, **kwargs: [(socket.AF_INET, socket.SOCK_STREAM, 0, '', ('127.0.0.1', 443))])
    monkeypatch.setattr(socket, 'socket', lambda *args: Raw())
    remaining_calls = []
    def remaining(deadline):
        remaining_calls.append(deadline)
        if len(remaining_calls) == 4: raise TimeoutError()
        return 1
    monkeypatch.setattr('agent.probe_sender._remaining', remaining)
    with pytest.raises(TimeoutError):
        DeepSeekProbeSender()._connect(time.monotonic()+10)
    assert peer.closed and len(remaining_calls) == 4


def post(port,context,body,credential=SECRET):
    connection=http.client.HTTPSConnection('localhost',port,context=context,timeout=5)
    try:
        connection.request('POST','/internal/model-probe',body=body,headers={'X-Model-Credential':credential,'Content-Type':'application/json'})
        response=connection.getresponse();return response.status,json.loads(response.read())
    finally:connection.close()


def test_actual_internal_mtls_replay_and_peer_rejection(counter,tmp_path,caplog):
    pki=TemporaryPKI(tmp_path);sender=FakeSender(counter)
    config=build_server_config(pki.settings(),counter,sender)
    with running_server(config) as (port,visits):
        body=document(counter)
        assert post(port,pki.client_context(pki.client),body)[0]==200
        assert post(port,pki.client_context(pki.client),body)[0]==400
        with pytest.raises(OSError):post(port,pki.client_context(pki.other),document(counter))
        assert len(sender.visits)==1
        status,result=post(port,pki.client_context(pki.client),document(counter),SECRET+' invalid')
        assert status==400 and result=={'error':'INTERNAL_PROBE_REJECTED'}
    assert SECRET not in caplog.text
