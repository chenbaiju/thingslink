import json
import threading
from types import SimpleNamespace

import pytest

import agent.analysis_execution as module
from agent.analysis_execution import AnalysisExecutionError, SingleInternalAnalysisExecution
from agent.analysis_transport import AnalysisTransportError, GuardedSingleAnalysisTransport
from agent.internal_analysis import prepare_internal_analysis
from test_analysis_response import response
from test_analysis_transport import Clock, SECRET, local_connection, synthetic_qualification
from test_internal_analysis import TestClock, envelope, preparer
from test_model_request import raw
from test_mtls import TemporaryPKI
from test_probe_sender import supplier


def fail(execution, code, credential=None):
    key = bytearray(SECRET.encode()) if credential is None else credential
    with pytest.raises(AnalysisExecutionError, match=f"^{code}$") as error:
        execution.execute(key)
    assert error.value.__cause__ is None and error.value.__context__ is None
    if type(key) is bytearray:
        assert key == bytearray(len(key))
    return error


def test_default_material_blocks_egress_and_consumes_execution(preparer, monkeypatch, caplog):
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=TestClock())
    monkeypatch.setattr(GuardedSingleAnalysisTransport, "_connect", lambda _: pytest.fail("unqualified network"))
    assert 'unused' in repr(execution)
    fail(execution, "MODEL_BUSINESS_UNAVAILABLE")
    fail(execution, "ANALYSIS_EXECUTION_ALREADY_USED")
    assert 'used' in repr(execution) and SECRET not in repr(execution)
    assert not caplog.records
    assert all(SECRET not in repr(value) for value in vars(execution).values())


@pytest.mark.parametrize('credential', [bytearray(), bytearray(b'private\r\nsecret'), bytearray(b'x' * 4097), 'immutable-secret'])
def test_invalid_credentials_still_consume_and_clear_mutable_input(preparer, monkeypatch, credential):
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=TestClock())
    monkeypatch.setattr(GuardedSingleAnalysisTransport, "_connect", lambda _: pytest.fail("invalid egress"))
    fail(execution, 'INVALID_MODEL_CREDENTIAL', credential)
    fail(execution, 'ANALYSIS_EXECUTION_ALREADY_USED')


@pytest.mark.parametrize('mutation,code', [('valid',None),('bad-json','INVALID_MODEL_RESPONSE'),
                                         ('count','INPUT_COUNT_MISMATCH'),('truncated','OUTPUT_TRUNCATED')])
def test_local_tls_complete_pipeline_preserves_binding_and_unknown_usage(preparer, monkeypatch, tmp_path, caplog, mutation, code):
    synthetic_qualification(monkeypatch)
    clock = Clock()
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=clock)
    call = prepare_internal_analysis(envelope(), preparer, clock=clock)
    value = response(call.prepared)
    if mutation == 'count':
        for key in ('prompt_tokens','total_tokens','prompt_cache_miss_tokens'):
            value['usage'][key] += 1
    if mutation == 'truncated': value['choices'][0]['finish_reason'] = 'length'
    body = b'private supplier error' if mutation == 'bad-json' else raw(value)
    pki = TemporaryPKI(tmp_path)
    with supplier(pki, body) as (port, visits):
        local_connection(monkeypatch, pki, port)
        secret = bytearray(SECRET.encode())
        encoded = execution.execute(secret)
        result = json.loads(encoded)
        assert secret == bytearray(len(secret))
        assert result['callId'] == call.call_id and result['inputSha256'] == call.input_sha256
        assert result['requestSha256'] == call.prepared.request_sha256
        assert result['counterSha256'] == call.prepared.counter_sha256
        assert result['qualification'] == 'OFFLINE_UNQUALIFIED'
        assert result['rejectionCode'] == code
        assert result['status'] == ('VALIDATED' if code is None else 'REJECTED')
        if code is None: assert result['content']['findings'][0]['evidenceIds'] == ['e-device']
        else: assert result['content'] is None
        assert (result['usage'] is None) == (mutation == 'bad-json')
        fail(execution, 'ANALYSIS_EXECUTION_ALREADY_USED')
        assert visits == [('/chat/completions', call.prepared.draft.body)]
    assert SECRET not in repr(execution) + encoded.decode() + caplog.text
    assert 'private supplier error' not in encoded.decode()


@pytest.mark.parametrize('status,code', [(302,'MODEL_SUPPLIER_REJECTED'),(401,'MODEL_SUPPLIER_AUTH'),
                                       (402,'MODEL_SUPPLIER_BALANCE'),(429,'MODEL_SUPPLIER_RATE_LIMIT')])
def test_local_tls_supplier_failure_never_retries_or_exposes_body(preparer, monkeypatch, tmp_path, status, code):
    synthetic_qualification(monkeypatch)
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=Clock())
    pki = TemporaryPKI(tmp_path)
    with supplier(pki, b'private supplier error',status=status) as (port,visits):
        local_connection(monkeypatch,pki,port)
        fail(execution,code)
        fail(execution,'ANALYSIS_EXECUTION_ALREADY_USED')
        assert len(visits)==1


def test_original_deadline_rechecked_after_result_encoding(preparer, monkeypatch):
    clock = TestClock()
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=clock)
    encode = module.encode_internal_result
    call = prepare_internal_analysis(envelope(), preparer, clock=clock)
    from agent.analysis_response import parse_analysis_response
    result = parse_analysis_response(raw(response(call.prepared)),prepared=call.prepared,preparer=preparer)
    # 合成传输桩不连接；检查编码耗时不能被重新建立的期限掩盖。
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'send',lambda *_:result)
    def delayed(*args):
        encoded = encode(*args)
        clock.elapsed += 60
        return encoded
    monkeypatch.setattr(module,'encode_internal_result',delayed)
    fail(execution,'ANALYSIS_DEADLINE_EXCEEDED')
    fail(execution,'ANALYSIS_EXECUTION_ALREADY_USED')


@pytest.mark.parametrize('failure', [RuntimeError('private exception'), AnalysisTransportError('private-secret-code')])
def test_unexpected_errors_are_closed_codes_without_original_context(preparer, monkeypatch, caplog, failure):
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=TestClock())
    def broken(*_): raise failure
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'send',broken)
    observed=fail(execution,'ANALYSIS_EXECUTION_UNKNOWN')
    assert 'private' not in repr(observed.value) and not caplog.records
    fail(execution,'ANALYSIS_EXECUTION_ALREADY_USED')


def test_concurrent_duplicate_does_not_create_second_transport_or_send(preparer, monkeypatch):
    execution = SingleInternalAnalysisExecution(envelope(),preparer,clock=TestClock())
    entered, release=threading.Event(),threading.Event()
    counts=[]
    def blocked(*_):
        counts.append('send'); entered.set()
        assert release.wait(2)
        raise AnalysisTransportError('MODEL_BUSINESS_UNAVAILABLE')
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'send',blocked)
    failures=[]
    def first():
        try: fail(execution,'MODEL_BUSINESS_UNAVAILABLE')
        except BaseException as error: failures.append(error)
    worker=threading.Thread(target=first)
    worker.start()
    try:
        assert entered.wait(2)
        fail(execution,'ANALYSIS_EXECUTION_ALREADY_USED')
        assert counts==['send']
    finally:
        release.set();worker.join(timeout=3)
    assert not worker.is_alive() and not failures


def test_credential_cleared_when_reencoding_rejects_invalid_outcome(preparer, monkeypatch):
    execution=SingleInternalAnalysisExecution(envelope(),preparer,clock=TestClock())
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'send',lambda *_:SimpleNamespace(content='private-invalid'))
    fail(execution,'INVALID_INTERNAL_ANALYSIS_RESULT')
    fail(execution,'ANALYSIS_EXECUTION_ALREADY_USED')


@pytest.mark.parametrize('document', [b'private-invalid', b'null', b'x' * 24577],
                         ids=['invalid','null','oversized'])
def test_constructor_malformed_input_is_fixed_error_without_payload(preparer, caplog, document):
    with pytest.raises(AnalysisExecutionError, match='^INVALID_INTERNAL_ANALYSIS$') as error:
        SingleInternalAnalysisExecution(document, preparer, clock=TestClock())
    assert error.value.__context__ is None and error.value.__cause__ is None
    assert 'private' not in repr(error.value) and not caplog.records


def test_unexpected_constructor_failure_is_sanitized(preparer, monkeypatch):
    def broken(*args, **kwargs): raise OSError('private-ca-path')
    monkeypatch.setattr(module,'GuardedSingleAnalysisTransport',broken)
    with pytest.raises(AnalysisExecutionError, match='^ANALYSIS_EXECUTION_UNKNOWN$') as error:
        SingleInternalAnalysisExecution(envelope(),preparer,clock=TestClock())
    assert error.value.__cause__ is None and error.value.__context__ is None


def test_expiry_after_construction_consumes_without_invoking_transport(preparer, monkeypatch):
    clock=TestClock()
    execution=SingleInternalAnalysisExecution(envelope(),preparer,clock=clock)
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'send',lambda *_:pytest.fail('expired send'))
    clock.elapsed+=60
    fail(execution,'ANALYSIS_DEADLINE_EXCEEDED')
    fail(execution,'ANALYSIS_EXECUTION_ALREADY_USED')


def test_constructor_cost_does_not_renew_original_deadline(preparer, monkeypatch):
    clock=TestClock()
    transport=module.GuardedSingleAnalysisTransport
    def slow(*args,**kwargs):
        result=transport(*args,**kwargs)
        clock.elapsed+=60
        return result
    monkeypatch.setattr(module,'GuardedSingleAnalysisTransport',slow)
    with pytest.raises(AnalysisExecutionError,match='^ANALYSIS_DEADLINE_EXCEEDED$') as error:
        SingleInternalAnalysisExecution(envelope(),preparer,clock=clock)
    assert error.value.__cause__ is None and error.value.__context__ is None
