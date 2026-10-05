import copy
import hashlib
import json
import os
from dataclasses import FrozenInstanceError, replace
from pathlib import Path
from types import SimpleNamespace

import pytest

from agent.analysis_preparation import AnalysisPreparationError, OfflineAnalysisPreparer
from agent.model_request import MAX_REQUEST_BYTES, ModelRequestError
from agent.token_probe import synthetic_probe_requests
from test_model_request import input_document, raw


@pytest.fixture
def preparer(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("preparer attempted network"))
    path = os.environ.get("AGENT_V41_COUNTER_ASSETS")
    if not path:
        pytest.skip("pinned V4.1 resource not provisioned")
    return OfflineAnalysisPreparer(Path(path))


def test_business_preparation_preserves_exact_numeric_and_nanosecond_input(preparer):
    document = input_document()
    reading = copy.deepcopy(document["readings"][0])
    reading.update(evidenceId="e-property-10", semantic="BINARY_STATE", unit="UNITLESS", value=False)
    document["readings"].append(reading)
    source = raw(document).replace(b'"value":23.5', b'"value":0.12345678901234567890123456789')
    prepared = preparer.prepare(source)
    body = json.loads(prepared.draft.body)
    assert body["messages"][1]["content"].encode() == source
    assert body["max_tokens"] == 1024
    assert body["thinking"] == {"type": "disabled"}
    assert body["stream"] is False
    assert prepared.draft.evidence_ids == frozenset({"e-device", "e-alarm", "e-property-2", "e-property-10"})
    assert prepared.request_sha256 == hashlib.sha256(prepared.draft.body).hexdigest()
    assert prepared.qualification == "OFFLINE_UNQUALIFIED"
    assert "0.123456789" not in repr(prepared)
    assert "0.123456789" not in repr(preparer)
    preparer.validate(prepared)
    assert prepared == preparer.prepare(source)


def test_business_and_probe_share_input_rendering_without_sharing_execution_permit(preparer):
    values = []
    for sample in synthetic_probe_requests():
        source = json.loads(sample.body)["messages"][1]["content"].encode()
        prepared = preparer.prepare(source)
        values.append(prepared.input_tokens)
        assert prepared.request_sha256 == sample.analysis_request_sha256
        assert prepared.request_sha256 != sample.request_sha256
    assert values == [502, 647, 1230]


@pytest.mark.parametrize("count,accepted", [(0, False), (4096, True), (4097, False)])
def test_complete_input_token_budget_boundary(preparer, count, accepted):
    calls = []

    class BudgetTokenizer:
        def encode(self, text, **kwargs):
            calls.append((text, kwargs))
            return SimpleNamespace(ids=[0] * count)

    preparer._tokenizer = BudgetTokenizer()
    if accepted:
        assert preparer.prepare(raw(input_document())).input_tokens == count
    else:
        with pytest.raises(AnalysisPreparationError, match="INPUT_TOKEN_LIMIT_EXCEEDED"):
            preparer.prepare(raw(input_document()))
    assert len(calls) == 1
    assert calls[0][1] == {"add_special_tokens": False}
    assert calls[0][0].startswith("<｜begin▁of▁sentence｜><｜System｜>")
    assert calls[0][0].endswith("<｜Assistant｜></think>")


@pytest.mark.parametrize("key,value", [("apiKey", "synthetic-secret"), ("inputTokens", 1),
                                        ("model", "other-model"), ("qualification", True)])
def test_client_metadata_and_credentials_do_not_enter_preparation(preparer, key, value, caplog):
    document = input_document()
    document[key] = value
    with pytest.raises(ModelRequestError, match="INVALID_MODEL_INPUT") as failure:
        preparer.prepare(raw(document))
    assert failure.value.__context__ is None
    assert "synthetic-secret" not in caplog.text


def test_complete_body_byte_limit_is_preserved_before_counting(preparer):
    source = raw(input_document())
    base = preparer.prepare(source)
    padding = MAX_REQUEST_BYTES - len(base.draft.body)
    draft = preparer.prepare(b" " * padding + source)
    assert len(draft.draft.body) == MAX_REQUEST_BYTES
    with pytest.raises(ModelRequestError, match="MODEL_REQUEST_TOO_LARGE"):
        preparer.prepare(b" " * (padding + 1) + source)


@pytest.mark.parametrize("field", ["tokens", "counter", "body", "references", "messages"])
def test_tampered_preparation_is_rejected_before_result_assembly(preparer, field):
    prepared = preparer.prepare(raw(input_document()))
    if field == "tokens":
        modified = replace(prepared, input_tokens=1)
    elif field == "counter":
        modified = replace(prepared, counter_sha256="f" * 64)
    elif field == "body":
        modified = replace(prepared, draft=replace(prepared.draft, body=b"{}"))
    elif field == "references":
        modified = replace(prepared, draft=replace(prepared.draft, evidence_ids=frozenset({"foreign"})))
    else:
        modified = replace(prepared, draft=replace(prepared.draft, messages=(("user", "private"),)))
    with pytest.raises(AnalysisPreparationError, match="INVALID_PREPARED_ANALYSIS") as failure:
        preparer.validate(modified)
    assert failure.value.__context__ is None
    assert "private" not in str(failure.value)


def test_preparation_is_immutable_and_cannot_receive_qualification_flag(preparer):
    prepared = preparer.prepare(raw(input_document()))
    with pytest.raises(FrozenInstanceError):
        prepared.input_tokens = 1
    with pytest.raises(TypeError):
        replace(prepared, qualification="QUALIFIED")
    assert preparer.validate(prepared) is None


def test_unreviewed_counter_library_is_rejected(monkeypatch, tmp_path):
    monkeypatch.setattr("agent.analysis_preparation.version", lambda name: "future-version")
    with pytest.raises(AnalysisPreparationError, match="INVALID_COUNTER_LIBRARY"):
        OfflineAnalysisPreparer(tmp_path)
