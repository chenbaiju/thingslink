import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path

import pytest

from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.internal_analysis import prepare_internal_analysis


@pytest.mark.skipif(not os.environ.get("AGENT_JAVA_TEST_REPORT") or not os.environ.get("AGENT_V41_COUNTER_ASSETS"),
                   reason="requires explicit fresh Java build and pinned V4.1 resources")
def test_actual_java_encoder_feeds_python_without_rounding_or_metadata_leak(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("interop attempted network"))
    properties = ET.parse(Path(os.environ["AGENT_JAVA_TEST_REPORT"])).getroot().find("properties")
    classpath = next(p.attrib["value"] for p in properties if p.attrib.get("name") == "java.class.path")
    java = shutil.which("java")
    assert java is not None
    fixture = Path(__file__).resolve().parents[1] / "fixtures" / "InternalAnalysisEnvelopeProbe.java"
    result = subprocess.run([java, "--class-path", classpath, str(fixture)], capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, result.stderr
    lines = result.stdout.splitlines()
    assert len(lines) == 2
    preparer = OfflineAnalysisPreparer(Path(os.environ["AGENT_V41_COUNTER_ASSETS"]))
    class Clock:
        def monotonic(self): return 100.0
        def wall_millis(self): return 1791072000123
    templates = set()
    for line in lines:
        call = prepare_internal_analysis(line.encode(), preparer, clock=Clock())
        assert call.configuration_revision == 2 and call.deadline.remaining(Clock()) == 60
        body = call.prepared.draft.body.decode()
        assert call.call_id not in body and "configurationRevision" not in body
        assert "private" not in line and "00000000" not in line
        evidence = json.loads(call.prepared.draft.messages[1][1], parse_float=Decimal)
        templates.add(evidence["template"])
        assert evidence["collectionStartedAt"] == "2026-10-04T00:00:00.123456789Z"
        assert evidence["readings"][0]["value"] == Decimal("0.12345678901234567890123456789")
        assert evidence["readings"][1]["value"] is False
        assert call.prepared.qualification == "OFFLINE_UNQUALIFIED"
    assert templates == {"STATUS_SUMMARY", "ALARM_EXPLANATION"}
