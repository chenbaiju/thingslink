import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from agent.admission_evidence import candidate_fingerprint
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12

from agent.analysis_transport import GuardedSingleAnalysisTransport
from agent.internal_server import build_server_config
from test_analysis_endpoint import ready
from test_analysis_response import response
from test_analysis_transport import synthetic_qualification
from test_model_request import raw
from test_mtls import TemporaryPKI, running_server


@pytest.mark.skipif(not os.environ.get('AGENT_JAVA_TEST_REPORT'), reason='fresh Java classpath required')
@pytest.mark.parametrize('producer', ['AnalysisHttpProbe.java','AnalysisConfiguredHttpProbe.java'])
@pytest.mark.parametrize('configured,qualified', [(False,False),(True,False),(True,True)])
def test_actual_java_python_analysis_mtls_single_send(ready,tmp_path,monkeypatch,configured,qualified,producer):
    report=Path(os.environ['AGENT_JAVA_TEST_REPORT'])
    props=ET.parse(report).getroot().find('properties')
    classpath=next(p.attrib['value'] for p in props if p.attrib['name']=='java.class.path')
    pki=TemporaryPKI(tmp_path); password=b'synthetic-test-password'
    def identity(pair,name):
        path=tmp_path/(name+'.p12')
        path.write_bytes(pkcs12.serialize_key_and_certificates(b'identity',
            serialization.load_pem_private_key(pair[1].read_bytes(),None),
            x509.load_pem_x509_certificate(pair[0].read_bytes()),[pki.ca],serialization.BestAvailableEncryption(password)))
        return path
    client=identity(pki.client,'client'); other=identity(pki.other,'other')
    trust=tmp_path/'trust.p12'
    trust.write_bytes(pkcs12.serialize_java_truststore([pkcs12.PKCS12Certificate(pki.ca,b'ca')],serialization.BestAvailableEncryption(password)))
    exchanges=[]
    if qualified:
        synthetic_qualification(monkeypatch)
    def exchange(sender,credential):
        assert qualified
        assert credential == bytearray(b'synthetic-probe-credential')
        exchanges.append(sender._call.prepared)
        return 200,raw(response(sender._call.prepared))
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'_exchange',exchange)
    monkeypatch.setattr(GuardedSingleAnalysisTransport,'_connect',lambda *a: pytest.fail('vendor network'))
    config=build_server_config(pki.settings(),analysis_preparer=ready if configured else None)
    fixture=Path(__file__).resolve().parents[1]/'fixtures'/producer
    with running_server(config) as (port,visits):
        for host,key,success in [('localhost',client,qualified),('localhost',other,False),('127.0.0.1',client,False)]:
            result=subprocess.run([shutil.which('java'),'--class-path',classpath,str(fixture),f'https://{host}:{port}',
                str(key),str(trust),ready.counter_sha256,candidate_fingerprint(ready)],capture_output=True,text=True,timeout=15)
            assert result.returncode == (0 if success else 2),result.stderr
            assert result.stdout.strip() == ('ANALYSIS_OK' if success else 'ANALYSIS_REJECTED')
        assert visits == ['/internal/analysis']
    assert len(exchanges) == int(qualified)
