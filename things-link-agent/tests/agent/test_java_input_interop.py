"""Opt-in actual Java projection/serialization -> Python request validation, offline."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path

import pytest

from agent.model_request import build_model_request


@pytest.mark.skipif(not os.environ.get("AGENT_JAVA_TEST_REPORT"), reason="requires explicit freshly built Java test report")
def test_java_projection_wire_preserves_precision_times_and_omissions():
    properties = ET.parse(Path(os.environ["AGENT_JAVA_TEST_REPORT"])).getroot().find("properties")
    assert properties is not None
    classpath = next(p.attrib["value"] for p in properties if p.attrib.get("name") == "java.class.path")
    java = shutil.which("java")
    assert java is not None, "JDK 21 required for producer proof"
    fixture = Path(__file__).resolve().parents[1] / "fixtures" / "OutboundEvidenceProbe.java"
    result = subprocess.run([java, "--class-path", classpath, str(fixture)],
                            capture_output=True, text=True, timeout=15, check=False)
    assert result.returncode == 0, result.stderr
    lines = result.stdout.splitlines()
    assert len(lines) == 2
    templates = set()
    for line in lines:
        draft = build_model_request(line.encode("utf-8"))
        document = json.loads(draft.messages[1][1], parse_float=Decimal)
        templates.add(document["template"])
        assert "private" not in line and "00000000" not in line
        assert document["device"]["lastOnlineAt"] is None
        assert document["collectionStartedAt"] == "2026-10-03T12:00:00.123456789Z"
        assert document["readings"][0]["value"] == Decimal("0.12345678901234567890123456789")
        assert document["readings"][1]["value"] is False
        assert draft.evidence_ids == frozenset({"e-device", "e-alarm", "e-property-2", "e-property-3"})
    assert templates == {"STATUS_SUMMARY", "ALARM_EXPLANATION"}
