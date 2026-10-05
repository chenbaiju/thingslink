import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from agent.admission_evidence import candidate_fingerprint

from agent.analysis_response import parse_analysis_response
from agent.internal_result import encode_internal_result
from test_analysis_response import response
from test_internal_analysis import preparer
from test_internal_result import context
from test_model_request import raw


@pytest.mark.skipif(not os.environ.get('AGENT_JAVA_TEST_REPORT'), reason='requires explicit fresh Java build')
@pytest.mark.parametrize('scenario', ['VALIDATED', 'TRUNCATED', 'UNKNOWN_USAGE', 'WRONG_CANDIDATE'])
def test_actual_python_encoder_feeds_production_java_decoder(preparer, scenario):
    properties = ET.parse(Path(os.environ['AGENT_JAVA_TEST_REPORT'])).getroot().find('properties')
    classpath = next(item.attrib['value'] for item in properties if item.attrib.get('name') == 'java.class.path')
    call, outcome = context(preparer)
    if scenario == 'UNKNOWN_USAGE':
        outcome = parse_analysis_response(b'private-supplier-error', prepared=call.prepared, preparer=preparer)
    elif scenario == 'TRUNCATED':
        supplier = response(call.prepared)
        supplier['choices'][0]['finish_reason'] = 'length'
        outcome = parse_analysis_response(raw(supplier), prepared=call.prepared, preparer=preparer)
    fixture = Path(__file__).resolve().parents[1] / 'fixtures' / 'InternalAnalysisResultProbe.java'
    java = shutil.which('java')
    assert java is not None
    expected = '0' * 64 if scenario == 'WRONG_CANDIDATE' else candidate_fingerprint(preparer)
    completed = subprocess.run([java, '--class-path', classpath, str(fixture), call.input_sha256,
                                outcome.counter_sha256, expected, outcome.request_sha256], input=encode_internal_result(call, outcome, preparer),
                               capture_output=True, timeout=15)
    if scenario == 'WRONG_CANDIDATE':
        assert completed.returncode != 0
        assert completed.stdout == b''
        assert b'INVALID_INTERNAL_ANALYSIS_RESULT' in completed.stderr
        assert b'private' not in completed.stderr
        return
    assert completed.returncode == 0, completed.stderr
    usage = 'UNKNOWN_USAGE' if outcome.usage is None else str(outcome.usage.total_tokens)
    assert completed.stdout.decode().strip() == f'OFFLINE_UNQUALIFIED:{outcome.status}:{usage}'


@pytest.mark.skipif(not os.environ.get('AGENT_JAVA_TEST_REPORT'), reason='requires explicit fresh Java build')
def test_reviewed_python_encoder_feeds_production_java_decoder(preparer, monkeypatch, tmp_path):
    from test_release_review import setup_review
    from test_internal_analysis import TestClock
    from agent.release_review import load_reviewed_execution
    properties=ET.parse(Path(os.environ['AGENT_JAVA_TEST_REPORT'])).getroot().find('properties')
    classpath=next(item.attrib['value'] for item in properties if item.attrib.get('name')=='java.class.path')
    digest,_=setup_review(monkeypatch,tmp_path,preparer)
    call,outcome=context(preparer)
    approval=load_reviewed_execution(call,preparer,digest,TestClock())
    body=encode_internal_result(call,outcome,preparer,approval=approval,clock=TestClock())
    fixture=Path(__file__).resolve().parents[1]/'fixtures'/'InternalAnalysisResultProbe.java'
    for review in (digest,'0'*64):
        completed=subprocess.run([shutil.which('java'),'--class-path',classpath,str(fixture),call.input_sha256,
            outcome.counter_sha256,candidate_fingerprint(preparer),outcome.request_sha256,review],input=body,
            capture_output=True,timeout=15)
        if review==digest:
            assert completed.returncode==0,completed.stderr
            assert completed.stdout.decode().strip()==f'REVIEWED_EXECUTION:VALIDATED:{outcome.usage.total_tokens}'
        else:
            assert completed.returncode!=0 and completed.stdout==b''
            assert b'INVALID_INTERNAL_ANALYSIS_RESULT' in completed.stderr and b'private' not in completed.stderr
