"""比较真实Java完整字节与Python生产构造器；全部输入均为本地合成数据。"""
import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from agent.model_request import build_model_request
from scripts.export_java_model_request_contract import TARGET, contract_bytes
from test_model_request import input_document, raw


def test_committed_java_prompt_resource_matches_current_python_schema_and_templates():
    assert TARGET.read_bytes() == contract_bytes()


@pytest.mark.skipif(not os.environ.get('AGENT_JAVA_TEST_REPORT'), reason='fresh Java classpath required')
@pytest.mark.parametrize('template', ['STATUS_SUMMARY', 'ALARM_EXPLANATION'])
@pytest.mark.parametrize('shape', ['precise', 'zero', 'escaped'])
def test_actual_java_request_bytes_equal_python_without_rewriting_evidence(template, shape, tmp_path):
    props = ET.parse(Path(os.environ['AGENT_JAVA_TEST_REPORT'])).getroot().find('properties')
    classpath = next(p.attrib['value'] for p in props if p.attrib['name'] == 'java.class.path')
    document = input_document()
    document['template'] = template
    document['collectionStartedAt'] = '2026-10-04T00:00:00.123456789Z'
    source = raw(document)
    if shape == 'precise':
        # 固定合成的高精度值保留在原用户消息中。
        source = source.replace(b'23.5', b'0.12345678901234567890123456789')
    elif shape == 'zero':
        document['readings'][0]['value'] = 0
        document['readings'].append(dict(evidenceId='e-property-3', semantic='BINARY_STATE', unit='UNITLESS',
                                         value=False, occurredAt=document['collectionStartedAt'],
                                         readAt=document['collectionStartedAt']))
        source = raw(document)
    else:
        source = b'\n  ' + source.replace(b'device-1', b'device-\\u0031') + b'\n'
    expected = build_model_request(source).body
    fixture = Path(__file__).resolve().parents[1] / 'fixtures/ModelRequestBindingProbe.java'
    java = shutil.which('java')
    compiled = subprocess.run([str(Path(java).with_name('javac')), '--class-path', classpath,
                               '-d', str(tmp_path), str(fixture)], capture_output=True, timeout=15)
    assert compiled.returncode == 0, compiled.stderr
    completed = subprocess.run([java, '--class-path', str(tmp_path) + os.pathsep + classpath,
                                'com.things.link.assistant.infrastructure.transport.ModelRequestBindingProbe'],
                               input=source, capture_output=True, timeout=15)
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout == expected
    assert json.loads(completed.stdout)['messages'][1]['content'].encode() == source
