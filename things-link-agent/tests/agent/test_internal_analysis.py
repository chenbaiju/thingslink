import base64
import hashlib
import json
import os
from dataclasses import FrozenInstanceError, replace
from pathlib import Path
from uuid import uuid4

import pytest

from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.internal_analysis import InternalAnalysisError, MAX_INTERNAL_BYTES, VERSION, prepare_internal_analysis
from test_model_request import input_document, raw

WALL = 1791100800000
CALL = "019c1234-5678-7890-8123-456789abcdef"


class TestClock:
    __test__ = False
    def __init__(self):
        self.wall, self.elapsed = WALL, 100.0
    def wall_millis(self): return self.wall
    def monotonic(self): return self.elapsed


def envelope(source=None, **changes):
    source = raw(input_document()) if source is None else source
    value = dict(version=VERSION, callId=CALL, configurationRevision=2, deadlineEpochMillis=WALL + 60_000,
                 inputSha256=hashlib.sha256(source).hexdigest(), inputBase64=base64.b64encode(source).decode())
    value.update(changes)
    return raw(value)


@pytest.fixture
def preparer(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("preparation attempted network"))
    path = os.environ.get("AGENT_V41_COUNTER_ASSETS")
    if not path: pytest.skip("pinned V4.1 resource not provisioned")
    return OfflineAnalysisPreparer(Path(path))


def test_exact_evidence_and_internal_metadata_stay_separate(preparer):
    source = b" \n" + raw(input_document()).replace(b'"value":23.5', b'"value":0.12345678901234567890123456789')
    timer = TestClock()
    call = prepare_internal_analysis(envelope(source), preparer, clock=timer)
    assert call.call_id == CALL and call.configuration_revision == 2
    assert call.prepared.draft.messages[1][1].encode() == source
    assert call.input_sha256 == hashlib.sha256(source).hexdigest()
    assert call.deadline.remaining(timer) == 60
    supplier = call.prepared.draft.body.decode()
    assert CALL not in supplier and "configurationRevision" not in supplier and "inputBase64" not in supplier
    assert call.prepared.qualification == "OFFLINE_UNQUALIFIED"
    assert source.decode() not in repr(call)
    with pytest.raises(FrozenInstanceError): call.configuration_revision = 3
    with pytest.raises(FrozenInstanceError): call.deadline.expires = 999
    with pytest.raises(TypeError): replace(call, credential="synthetic-secret")


@pytest.mark.parametrize("changes", [
    dict(version="other-version"), dict(callId=str(uuid4())), dict(callId=CALL.upper()),
    dict(callId="019c1234-5678-7890-0123-456789abcdef"), dict(callId=True),
    dict(configurationRevision=True), dict(configurationRevision=0), dict(configurationRevision=1 << 63),
    dict(deadlineEpochMillis=True), dict(deadlineEpochMillis=WALL + 60001),
    dict(inputSha256="f" * 64), dict(inputSha256="F" * 64), dict(inputBase64="invalid-base64"),
    dict(inputBase64="YR=="), dict(inputBase64=""), dict(inputBase64=42),
    dict(apiKey="synthetic-secret"), dict(projectId="private-project"), dict(model="other-model"),
    dict(qualification=True), dict(clock=100),
])
def test_forged_or_extended_internal_envelopes_fail_without_data_context(preparer, changes, caplog):
    with pytest.raises(InternalAnalysisError, match="^INVALID_INTERNAL_ANALYSIS$") as failure:
        prepare_internal_analysis(envelope(**changes), preparer, clock=TestClock())
    assert failure.value.__context__ is None and failure.value.__cause__ is None
    assert not caplog.records and "synthetic-secret" not in repr(failure.value)


@pytest.mark.parametrize("source", [b"null", b"\xff", b'{"version":1,"version":2}', b'{"x":NaN}', b"x" * (MAX_INTERNAL_BYTES+1)])
def test_malformed_or_oversized_raw_envelopes_are_payload_free(preparer, source):
    with pytest.raises(InternalAnalysisError, match="^INVALID_INTERNAL_ANALYSIS$") as failure:
        prepare_internal_analysis(source, preparer, clock=TestClock())
    assert failure.value.__context__ is None


def test_internal_body_boundary_and_controlled_input_validation(preparer):
    value = envelope()
    padded = value + b" " * (MAX_INTERNAL_BYTES - len(value))
    assert prepare_internal_analysis(padded, preparer, clock=TestClock()).configuration_revision == 2
    with pytest.raises(InternalAnalysisError, match="^INVALID_INTERNAL_ANALYSIS$"):
        prepare_internal_analysis(padded+b" ", preparer, clock=TestClock())
    document = input_document()
    document["rawName"] = "private-device"
    with pytest.raises(InternalAnalysisError, match="^INVALID_INTERNAL_ANALYSIS_INPUT$") as failure:
        prepare_internal_analysis(envelope(raw(document)), preparer, clock=TestClock())
    assert failure.value.__context__ is None
    with pytest.raises(InternalAnalysisError, match="^INVALID_INTERNAL_ANALYSIS_INPUT$"):
        prepare_internal_analysis(envelope(b" " * 16384), preparer, clock=TestClock())


@pytest.mark.parametrize("remaining", [-1, 0, 1, 60000])
def test_original_deadline_boundaries(preparer, remaining):
    timer = TestClock()
    if remaining <= 0:
        with pytest.raises(InternalAnalysisError, match="^ANALYSIS_DEADLINE_EXCEEDED$"):
            prepare_internal_analysis(envelope(deadlineEpochMillis=WALL+remaining), preparer, clock=timer)
    else:
        call = prepare_internal_analysis(envelope(deadlineEpochMillis=WALL+remaining), preparer, clock=timer)
        assert call.deadline.remaining(timer) == pytest.approx(remaining/1000)


def test_preparation_cost_is_deducted_and_wall_clock_change_does_not_extend(preparer, monkeypatch):
    timer = TestClock()
    prepare = preparer.prepare
    def slow(source):
        timer.elapsed += 20
        return prepare(source)
    monkeypatch.setattr(preparer, "prepare", slow)
    call = prepare_internal_analysis(envelope(), preparer, clock=timer)
    assert call.deadline.remaining(timer) == 40
    timer.wall -= 1000000000
    timer.elapsed += 10
    assert call.deadline.remaining(timer) == 30
    timer.elapsed = 160
    with pytest.raises(InternalAnalysisError, match="^ANALYSIS_DEADLINE_EXCEEDED$"):
        call.deadline.remaining(timer)


def test_expiry_during_counting_cannot_return_prepared_call(preparer, monkeypatch):
    timer = TestClock()
    prepare = preparer.prepare
    def slow(source):
        timer.elapsed += 60
        return prepare(source)
    monkeypatch.setattr(preparer, "prepare", slow)
    with pytest.raises(InternalAnalysisError, match="^ANALYSIS_DEADLINE_EXCEEDED$"):
        prepare_internal_analysis(envelope(), preparer, clock=timer)


@pytest.mark.parametrize("clock_field,value", [("elapsed", float("nan")), ("elapsed", float("inf")),
    ("elapsed", True), ("elapsed", 10**1000), ("wall", True), ("wall", -1)],
    ids=["nan", "infinity", "boolean-monotonic", "overflow-monotonic", "boolean-wall", "negative-wall"])
def test_invalid_clock_is_fixed_rejection(preparer, clock_field, value):
    timer = TestClock()
    setattr(timer, clock_field, value)
    with pytest.raises(InternalAnalysisError, match="^INVALID_ANALYSIS_CLOCK$"):
        prepare_internal_analysis(envelope(), preparer, clock=timer)


def test_monotonic_regression_does_not_restore_a_window(preparer):
    timer = TestClock()
    call = prepare_internal_analysis(envelope(), preparer, clock=timer)
    timer.elapsed = 99
    with pytest.raises(InternalAnalysisError, match="^INVALID_ANALYSIS_CLOCK$"):
        call.deadline.remaining(timer)
