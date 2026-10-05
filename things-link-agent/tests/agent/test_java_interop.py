"""Opt-in same-worktree Java client -> actual Python mTLS server proof."""
from __future__ import annotations

import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.serialization import pkcs12

from test_mtls import TemporaryPKI, running_server


@pytest.mark.skipif(not os.environ.get("AGENT_JAVA_TEST_REPORT"), reason="requires explicit freshly built Java test report")
def test_actual_java_client_and_python_server_share_certificate_contract(tmp_path):
    report = Path(os.environ["AGENT_JAVA_TEST_REPORT"])
    properties = ET.parse(report).getroot().find("properties")
    assert properties is not None
    classpath = next(prop.attrib["value"] for prop in properties if prop.attrib.get("name") == "java.class.path")
    java = shutil.which("java")
    assert java is not None, "JDK 21 must be available for the source-file test launcher"
    pki = TemporaryPKI(tmp_path)
    password = b"synthetic-test-password"

    def identity(pair, name):
        key = serialization.load_pem_private_key(pair[1].read_bytes(), password=None)
        cert = x509.load_pem_x509_certificate(pair[0].read_bytes())
        path = tmp_path / (name + ".p12")
        path.write_bytes(pkcs12.serialize_key_and_certificates(b"identity", key, cert, [pki.ca],
                                                             serialization.BestAvailableEncryption(password)))
        return path

    client = identity(pki.client, "client")
    other = identity(pki.other, "other")
    trust = tmp_path / "service-ca.p12"
    trust.write_bytes(pkcs12.serialize_java_truststore([pkcs12.PKCS12Certificate(pki.ca, b"service-ca")],
                                                      serialization.BestAvailableEncryption(password)))
    launcher = Path(__file__).resolve().parents[1] / "fixtures" / "InternalHealthProbe.java"
    with running_server(pki.settings()) as (port, visits):
        for host, key, expected in [("localhost", client, 0), ("localhost", other, 2), ("127.0.0.1", client, 2)]:
            result = subprocess.run([java, "--class-path", classpath, str(launcher),
                                     f"https://{host}:{port}", str(key), str(trust)],
                                    capture_output=True, text=True, timeout=15, check=False)
            assert result.returncode == expected, result.stderr
            assert result.stdout.strip() == ("HEALTH_OK" if expected == 0 else "HEALTH_REJECTED")
        assert visits == ["/internal/health"]
