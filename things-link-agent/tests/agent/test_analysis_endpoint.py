import asyncio
import http.client
import json
import os
import socket
import threading
import time
from pathlib import Path

import pytest
from starlette.requests import Request

import agent.analysis_endpoint as module
from agent.analysis_endpoint import InternalAnalysisEndpoint
from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.internal_analysis import AnalysisArrival, InternalAnalysisError, prepare_internal_analysis
from agent.internal_server import build_server_config, load_analysis_preparer
from test_internal_analysis import TestClock, WALL, envelope
from test_mtls import TemporaryPKI, running_server

SECRET = b'synthetic-endpoint-secret'


@pytest.fixture
def ready():
    path = os.environ.get('AGENT_V41_COUNTER_ASSETS')
    if not path:
        pytest.skip('pinned V4.1 resource not provisioned')
    return OfflineAnalysisPreparer(Path(path))


def request(body=b'{}', *, headers=None, query=b'', chunks=None):
    parts = list(chunks or [body])
    async def receive():
        part = parts.pop(0)
        return {'type': 'http.request', 'body': part, 'more_body': bool(parts)}
    scope = {'type': 'http', 'method': 'POST', 'path': '/internal/analysis', 'query_string': query,
             'headers': headers if headers is not None else [
                 (b'content-type', b'application/json'), (b'x-model-credential', SECRET)]}
    return Request(scope, receive=receive)


def invoke(endpoint, req):
    return asyncio.run(endpoint.handle(req))


def assert_error(response, status, code):
    assert response.status_code == status
    assert json.loads(response.body) == {'error': code}
    assert response.headers['cache-control'] == 'no-store'
    assert SECRET not in response.body


def test_unconfigured_entry_is_closed_without_reading_body(caplog):
    req = request()
    async def forbidden():
        pytest.fail('closed endpoint consumed body')
    req._receive = forbidden
    assert_error(invoke(InternalAnalysisEndpoint(None), req), 503, 'MODEL_BUSINESS_UNAVAILABLE')
    assert all(name != b'x-model-credential' for name, _ in req.scope['headers'])
    assert not caplog.records


def test_valid_body_default_denies_before_network(ready, monkeypatch, caplog):
    monkeypatch.setattr(socket, 'getaddrinfo', lambda *a, **k: pytest.fail('external DNS'))
    req = request(envelope(deadlineEpochMillis=time.time_ns() // 1_000_000 + 50_000))
    assert_error(invoke(InternalAnalysisEndpoint(ready), req), 503, 'MODEL_BUSINESS_UNAVAILABLE')
    assert not caplog.records


@pytest.mark.parametrize('headers', [
    [], [(b'content-type',b'application/json')],
    [(b'content-type',b'application/json'),(b'x-model-credential',SECRET),(b'x-model-credential',SECRET)],
    [(b'content-type',b'text/plain'),(b'x-model-credential',SECRET)],
    [(b'content-type',b'application/json'),(b'x-model-credential',b'x\nprivate')],
    [(b'content-type',b'application/json'),(b'x-model-credential',SECRET),(b'content-encoding',b'gzip')],
    [(b'content-type',b'application/json'),(b'x-model-credential',SECRET),(b'content-length',b'24577')],
    [(b'content-type',b'application/json'),(b'x-model-credential',SECRET),(b'content-length',b'1')],
])
def test_invalid_headers_and_length_never_execute(ready, monkeypatch, headers):
    monkeypatch.setattr(module, 'SingleInternalAnalysisExecution', lambda *a, **k: pytest.fail('executed'))
    assert_error(invoke(InternalAnalysisEndpoint(ready), request(headers=headers)),400,'INVALID_INTERNAL_ANALYSIS')


def test_query_and_streamed_body_limit(ready, monkeypatch):
    monkeypatch.setattr(module, 'SingleInternalAnalysisExecution', lambda *a, **k: pytest.fail('executed'))
    endpoint = InternalAnalysisEndpoint(ready)
    assert_error(invoke(endpoint, request(query=b'private=secret')),400,'INVALID_INTERNAL_ANALYSIS')
    assert_error(invoke(endpoint, request(chunks=[b'a'*16000,b'b'*9000])),413,'INTERNAL_ANALYSIS_TOO_LARGE')
    assert endpoint._slots.acquire(False) and endpoint._slots.acquire(False)
    assert not endpoint._slots.acquire(False)


@pytest.mark.parametrize('body', [b'',b'private-invalid-json',envelope(deadlineEpochMillis=1)])
def test_bad_body_is_fixed_error(ready, body, caplog):
    result = invoke(InternalAnalysisEndpoint(ready), request(body))
    assert result.status_code in (400,504)
    assert b'private' not in result.body and SECRET not in result.body and not caplog.records


def test_arrival_consumes_body_time_even_if_wall_clock_moves_back(ready):
    clock = TestClock()
    arrival = AnalysisArrival.capture(clock)
    clock.elapsed += 5
    clock.wall -= 100_000
    call = prepare_internal_analysis(envelope(), ready, clock=clock, arrival=arrival)
    assert call.deadline.remaining(clock) == 55
    clock.elapsed += 55
    with pytest.raises(InternalAnalysisError, match='ANALYSIS_DEADLINE_EXCEEDED'):
        prepare_internal_analysis(envelope(), ready, clock=clock, arrival=arrival)


def test_cancelled_waiter_does_not_release_running_slots_or_clear_key(ready, monkeypatch):
    release = threading.Event()
    observed = []
    class Blocked:
        def __init__(self, *a, **k): pass
        def execute(self, credential):
            observed.append(credential)
            assert release.wait(3)
            assert bytes(credential) == SECRET
            return b'{"qualification":"OFFLINE_UNQUALIFIED"}'
    monkeypatch.setattr(module,'SingleInternalAnalysisExecution',Blocked)
    endpoint = InternalAnalysisEndpoint(ready)
    async def scenario():
        tasks = [asyncio.create_task(endpoint.handle(request())) for _ in range(2)]
        try:
            async with asyncio.timeout(2):
                while len(observed) < 2: await asyncio.sleep(.005)
            for task in tasks: task.cancel()
            await asyncio.gather(*tasks,return_exceptions=True)
            assert_error(await endpoint.handle(request()),429,'INTERNAL_ANALYSIS_BUSY')
            assert all(bytes(value)==SECRET for value in observed)
        finally:
            release.set()
            async with asyncio.timeout(2):
                while any(any(value) for value in observed): await asyncio.sleep(.005)
        result = await endpoint.handle(request())
        assert result.status_code == 200 and result.headers['cache-control']=='no-store'
    asyncio.run(scenario())
    assert len(observed)==3 and all(not any(value) for value in observed)


def test_worker_start_failure_returns_slot(ready, monkeypatch):
    endpoint = InternalAnalysisEndpoint(ready)
    def broken(*a, **k): raise RuntimeError('private startup error')
    monkeypatch.setattr(threading.Thread,'start',broken)
    for _ in range(3):
        assert_error(invoke(endpoint,request()),400,'INVALID_INTERNAL_ANALYSIS')


def test_body_timeout_and_disconnect_release_slot(ready):
    endpoint = InternalAnalysisEndpoint(ready)
    async def scenario():
        req = request()
        async def slow(): await asyncio.sleep(6)
        req._receive = slow
        assert_error(await endpoint.handle(req),408,'INTERNAL_ANALYSIS_BODY_TIMEOUT')
        req = request()
        async def disconnect(): return {'type':'http.disconnect'}
        req._receive = disconnect
        assert_error(await endpoint.handle(req),400,'INVALID_INTERNAL_ANALYSIS')
        assert endpoint._slots.acquire(False) and endpoint._slots.acquire(False)
    asyncio.run(scenario())


def test_actual_mtls_analysis_route_and_closed_health(ready, tmp_path):
    pki = TemporaryPKI(tmp_path)
    config = build_server_config(pki.settings(),analysis_preparer=ready)
    with running_server(config) as (port,visits):
        for client in (None,pki.other):
            connection = http.client.HTTPSConnection('localhost',port,context=pki.client_context(client),timeout=2)
            try:
                with pytest.raises(OSError):
                    connection.request('POST','/internal/analysis',body=b'{}')
                    connection.getresponse()
            finally: connection.close()
        assert visits == []
        connection = http.client.HTTPSConnection('localhost',port,context=pki.client_context(pki.client),timeout=2)
        try:
            connection.request('POST','/internal/analysis',
                               body=envelope(deadlineEpochMillis=time.time_ns()//1_000_000+50_000),
                               headers={'Content-Type':'application/json','X-Model-Credential':SECRET.decode()})
            response=connection.getresponse()
            assert response.status==503 and response.getheader('Cache-Control')=='no-store'
            assert json.loads(response.read())=={'error':'MODEL_BUSINESS_UNAVAILABLE'}
            connection.request('GET','/internal/health')
            response=connection.getresponse()
            assert json.loads(response.read())['analysisAvailable'] is False
        finally: connection.close()


def test_counter_configuration_is_explicit_and_never_activates_probe(ready):
    from agent.internal_server import load_probe_counter
    environment = {'AGENT_V41_COUNTER_ASSETS':os.environ['AGENT_V41_COUNTER_ASSETS']}
    assert load_analysis_preparer(environment) is None
    environment['AGENT_ANALYSIS_COUNTER_SHA256']=ready.counter_sha256
    selected=load_analysis_preparer(environment)
    assert selected.counter_sha256==ready.counter_sha256
    assert load_probe_counter(environment) is None


def test_actual_mtls_to_local_supplier_returns_bound_unqualified_result(ready, tmp_path, monkeypatch):
    from test_analysis_transport import local_connection, synthetic_qualification
    from test_analysis_response import response
    from test_model_request import raw, input_document
    from test_probe_sender import supplier, SECRET as supplier_secret
    synthetic_qualification(monkeypatch)
    pki = TemporaryPKI(tmp_path)
    source = raw(input_document())
    prepared = ready.prepare(source)
    with supplier(pki, raw(response(prepared))) as (supplier_port, supplier_visits):
        local_connection(monkeypatch,pki,supplier_port)
        with running_server(build_server_config(pki.settings(),analysis_preparer=ready)) as (port,visits):
            connection=http.client.HTTPSConnection('localhost',port,context=pki.client_context(pki.client),timeout=3)
            try:
                body=envelope(source,deadlineEpochMillis=time.time_ns()//1_000_000+50_000)
                connection.request('POST','/internal/analysis',body=body,
                    headers={'Content-Type':'application/json','X-Model-Credential':supplier_secret})
                result=connection.getresponse()
                assert result.status==200
                value=json.loads(result.read())
                assert value['qualification']=='OFFLINE_UNQUALIFIED' and value['status']=='VALIDATED'
                assert value['inputSha256']==json.loads(body)['inputSha256']
                assert value['content']['findings'][0]['evidenceIds']==['e-device']
            finally: connection.close()
        assert supplier_visits==[('/chat/completions',prepared.draft.body)]


@pytest.mark.parametrize('environment', [
    {'AGENT_ANALYSIS_COUNTER_SHA256':''},
    {'AGENT_ANALYSIS_COUNTER_SHA256':'0'*64},
    {'AGENT_ANALYSIS_COUNTER_SHA256':'0'*64,'AGENT_V41_COUNTER_ASSETS':'/not/provisioned'},
])
def test_invalid_counter_configuration_has_closed_error(environment):
    from agent.analysis_preparation import AnalysisPreparationError
    with pytest.raises(AnalysisPreparationError,match='^INVALID_ANALYSIS_PREPARER_CONFIGURATION$') as error:
        load_analysis_preparer(environment)
    assert error.value.__context__ is None and error.value.__cause__ is None


@pytest.mark.parametrize('reviews', [[b'bad'], [b'a'*64,b'a'*64], [b'A'*64]])
def test_review_header_is_closed_and_never_executes(ready, monkeypatch, reviews):
    monkeypatch.setattr(module,'SingleInternalAnalysisExecution',lambda *a,**k:pytest.fail('executed'))
    req=request(headers=[(b'content-type',b'application/json'),(b'x-model-credential',SECRET)]+
                        [(b'x-analysis-review',value) for value in reviews])
    assert_error(invoke(InternalAnalysisEndpoint(ready),req),400,'INVALID_INTERNAL_ANALYSIS')
    assert all(name!=b'x-model-credential' for name,_ in req.scope['headers'])


def test_packaged_review_header_is_not_permission(ready, monkeypatch):
    from agent.release_review import REVIEW_SHA256
    monkeypatch.setattr(socket,'getaddrinfo',lambda *a,**k:pytest.fail('external DNS'))
    req=request(envelope(deadlineEpochMillis=time.time_ns()//1_000_000+50_000),headers=[
        (b'content-type',b'application/json'),(b'x-model-credential',SECRET),(b'x-analysis-review',REVIEW_SHA256.encode())])
    assert_error(invoke(InternalAnalysisEndpoint(ready),req),503,'MODEL_BUSINESS_UNAVAILABLE')


def test_actual_reviewed_mtls_to_local_supplier_uses_same_fixed_review(ready, tmp_path, monkeypatch):
    from datetime import datetime, timezone
    from test_release_review import setup_review
    from test_analysis_transport import local_connection
    from test_analysis_response import response
    from test_model_request import raw, input_document
    from test_probe_sender import supplier, SECRET as supplier_secret
    digest,_=setup_review(monkeypatch,tmp_path,ready,now=datetime.now(timezone.utc))
    pki=TemporaryPKI(tmp_path)
    source=raw(input_document())
    prepared=ready.prepare(source)
    with supplier(pki,raw(response(prepared))) as (supplier_port,supplier_visits):
        local_connection(monkeypatch,pki,supplier_port)
        with running_server(build_server_config(pki.settings(),analysis_preparer=ready)) as (port,visits):
            connection=http.client.HTTPSConnection('localhost',port,context=pki.client_context(pki.client),timeout=3)
            try:
                body=envelope(source,deadlineEpochMillis=time.time_ns()//1_000_000+50_000)
                connection.request('POST','/internal/analysis',body=body,headers={
                    'Content-Type':'application/json','X-Model-Credential':supplier_secret,'X-Analysis-Review':digest})
                result=connection.getresponse()
                assert result.status==200
                value=json.loads(result.read())
                assert value['qualification']=='REVIEWED_EXECUTION' and value['releaseReviewSha256']==digest
                assert value['inputSha256']==json.loads(body)['inputSha256'] and value['status']=='VALIDATED'
            finally: connection.close()
        assert supplier_visits==[('/chat/completions',prepared.draft.body)]
