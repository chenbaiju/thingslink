import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from uuid import uuid4

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12

from agent.internal_server import build_server_config
from test_mtls import TemporaryPKI, running_server
from test_probe_counter import counter
from test_probe_sender import FakeSender


@pytest.mark.skipif(not os.environ.get('AGENT_JAVA_TEST_REPORT'),reason='fresh Java classpath required')
def test_actual_java_single_send_mtls_and_replay_contract(counter,tmp_path):
    report=Path(os.environ['AGENT_JAVA_TEST_REPORT'])
    props=ET.parse(report).getroot().find('properties')
    classpath=next(p.attrib['value'] for p in props if p.attrib['name']=='java.class.path')
    pki=TemporaryPKI(tmp_path);password=b'synthetic-test-password'
    def identity(pair,name):
        path=tmp_path/(name+'.p12')
        path.write_bytes(pkcs12.serialize_key_and_certificates(b'identity',
            serialization.load_pem_private_key(pair[1].read_bytes(),None),
            x509.load_pem_x509_certificate(pair[0].read_bytes()),[pki.ca],serialization.BestAvailableEncryption(password)))
        return path
    client=identity(pki.client,'client');other=identity(pki.other,'other')
    trust=tmp_path/'trust.p12'
    trust.write_bytes(pkcs12.serialize_java_truststore([pkcs12.PKCS12Certificate(pki.ca,b'ca')],serialization.BestAvailableEncryption(password)))
    sender=FakeSender(counter);config=build_server_config(pki.settings(),counter,sender)
    fixture=Path(__file__).resolve().parents[1]/'fixtures'/'ProbeHttpProbe.java'
    with running_server(config) as (port,visits):
        attempt=str(uuid4())
        for host,key,id,expected in [('localhost',client,attempt,0),('localhost',client,attempt,2),
            ('localhost',other,str(uuid4()),2),('127.0.0.1',client,str(uuid4()),2)]:
            r=subprocess.run([shutil.which('java'),'--class-path',classpath,str(fixture),f'https://{host}:{port}',
                str(key),str(trust),counter.manifest_sha256(),id],capture_output=True,text=True,timeout=15)
            assert r.returncode==expected,r.stderr
            assert r.stdout.strip()==('PROBE_OK' if expected==0 else 'PROBE_REJECTED')
        assert len(sender.visits)==1 and visits==['/internal/model-probe','/internal/model-probe']
