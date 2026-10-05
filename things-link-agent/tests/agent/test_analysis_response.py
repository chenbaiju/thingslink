import copy
import hashlib
import json
import os
from dataclasses import FrozenInstanceError, replace
from pathlib import Path

import pytest

from agent.analysis_preparation import AnalysisPreparationError, OfflineAnalysisPreparer
from agent.analysis_response import MAX_RESPONSE_BYTES, parse_analysis_response
from test_model_request import input_document, raw


@pytest.fixture
def context(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("parser attempted network"))
    path = os.environ.get("AGENT_V41_COUNTER_ASSETS")
    if not path:
        pytest.skip("pinned V4.1 resource not provisioned")
    preparer = OfflineAnalysisPreparer(Path(path))
    return preparer, preparer.prepare(raw(input_document()))


def response(prepared):
    content = {"summary": "当前ONLINE，告警ACTIVE。", "findings": [
        {"kind": "FACT", "statement": "设备状态ONLINE。", "evidenceIds": ["e-device"]},
        {"kind": "FACT", "statement": "告警状态ACTIVE。", "evidenceIds": ["e-alarm"]},
    ], "limitations": ["不能仅凭状态推定告警原因。"]}
    return {"object": "chat.completion", "model": "deepseek-flash", "system_fingerprint": "fixed-synthetic-fp",
            "id": "private-provider-request-id", "choices": [{"index": 0, "finish_reason": "stop",
                "message": {"role": "assistant", "content": json.dumps(content, ensure_ascii=False)}}],
            "usage": {"prompt_tokens": prepared.input_tokens, "completion_tokens": 100,
                "total_tokens": prepared.input_tokens + 100, "prompt_cache_hit_tokens": 10,
                "prompt_cache_miss_tokens": prepared.input_tokens - 10,
                "prompt_tokens_details": {"cached_tokens": 10}, "completion_tokens_details": {"reasoning_tokens": 0}}}


def parse(context, value):
    preparer, prepared = context
    return parse_analysis_response(raw(value), preparer=preparer, prepared=prepared)


def test_fixed_one_response_composes_preparation_usage_and_reference_validation(context, caplog):
    preparer, prepared = context
    value = response(prepared)
    outcome = parse(context, value)
    assert outcome.status == "VALIDATED" and outcome.rejection_code is None
    assert outcome.qualification == "OFFLINE_UNQUALIFIED"
    assert outcome.model == prepared.draft.model
    assert outcome.prompt_version == prepared.draft.prompt_version
    assert outcome.request_sha256 == prepared.request_sha256
    assert outcome.counter_sha256 == prepared.counter_sha256
    assert outcome.usage.prompt_tokens == prepared.input_tokens
    assert outcome.usage.cache_hit_tokens + outcome.usage.cache_miss_tokens == outcome.usage.prompt_tokens
    assert outcome.content.findings[0].evidence_ids == ("e-device",)
    assert outcome.provider_fingerprint_sha256 == hashlib.sha256(b"fixed-synthetic-fp").hexdigest()
    assert not hasattr(outcome, "provider_request_id")
    assert "fixed-synthetic-fp" not in repr(outcome)
    assert "private-provider" not in repr(outcome)
    assert "ONLINE" not in repr(outcome)
    assert not caplog.records
    with pytest.raises(FrozenInstanceError):
        outcome.usage.prompt_tokens = 1
    with pytest.raises(ValueError, match="INVALID_ANALYSIS_OUTCOME"):
        replace(outcome, content=None)
    with pytest.raises(ValueError, match="INVALID_ANALYSIS_OUTCOME"):
        replace(outcome, rejection_code="unreviewed-error")


@pytest.mark.parametrize("mutation", [
    lambda v: v.update(model="different"),
    lambda v: v.update(object="chat.completion.chunk"),
    lambda v: v.update(system_fingerprint=""),
    lambda v: v["choices"].append(copy.deepcopy(v["choices"][0])),
    lambda v: v["choices"][0].update(index=True),
    lambda v: v["choices"][0].update(finish_reason="tool_calls"),
    lambda v: v["choices"][0]["message"].update(role="user"),
    lambda v: v["choices"][0]["message"].update(tool_calls=[{"name": "control"}]),
    lambda v: v["choices"][0]["message"].update(reasoning_content="private-reasoning"),
    lambda v: v["usage"].update(prompt_tokens=True),
    lambda v: v["usage"].update(completion_tokens=-1),
    lambda v: v["usage"].update(total_tokens=1),
    lambda v: v["usage"].update(prompt_cache_miss_tokens=0),
    lambda v: v["usage"]["prompt_tokens_details"].update(cached_tokens=0),
    lambda v: v["usage"]["completion_tokens_details"].update(reasoning_tokens=1),
    lambda v: v.pop("usage"),
])
def test_unverified_protocol_or_usage_is_rejected_without_zero_usage_claim(context, mutation):
    value = response(context[1])
    mutation(value)
    outcome = parse(context, value)
    assert outcome.rejection_code == "INVALID_MODEL_RESPONSE"
    assert outcome.content is None and outcome.usage is None


def test_input_count_mismatch_retains_verified_usage_but_no_content(context):
    value = response(context[1])
    value["usage"]["prompt_tokens"] += 1
    value["usage"]["prompt_cache_miss_tokens"] += 1
    value["usage"]["total_tokens"] += 1
    outcome = parse(context, value)
    assert outcome.rejection_code == "INPUT_COUNT_MISMATCH"
    assert outcome.usage.prompt_tokens == context[1].input_tokens + 1
    assert outcome.content is None


def test_truncation_cannot_become_success_even_when_content_is_valid_json(context):
    value = response(context[1])
    value["choices"][0]["finish_reason"] = "length"
    outcome = parse(context, value)
    assert outcome.rejection_code == "OUTPUT_TRUNCATED" and outcome.content is None
    assert outcome.usage.completion_tokens == 100


@pytest.mark.parametrize("foreign_content,code", [
    ('{"summary":"private-secret",', "INVALID_RESULT_JSON"),
    ('{"summary":"ok","findings":[],"limitations":[],"usage":{}}', "INVALID_RESULT_STRUCTURE"),
    ('{"summary":"ok","findings":[{"kind":"FACT","statement":"private-secret","evidenceIds":["foreign"]}],"limitations":[]}',
     "INVALID_EVIDENCE_REFERENCES"),
])
def test_rejected_content_preserves_usage_and_discards_payload(context, foreign_content, code, caplog):
    value = response(context[1])
    value["choices"][0]["message"]["content"] = foreign_content
    outcome = parse(context, value)
    assert outcome.rejection_code == code and outcome.content is None
    assert outcome.usage.completion_tokens == 100
    assert "private-secret" not in repr(outcome)
    assert not caplog.records


@pytest.mark.parametrize("tokens,code", [(1024, None), (1025, "INVALID_MODEL_RESPONSE")])
def test_output_budget_is_the_business_limit_not_probe_limit(context, tokens, code):
    value = response(context[1])
    value["usage"]["completion_tokens"] = tokens
    value["usage"]["total_tokens"] = value["usage"]["prompt_tokens"] + tokens
    outcome = parse(context, value)
    assert outcome.rejection_code == code


@pytest.mark.parametrize("invalid", [b"\xff", b'{"private-secret":',
                                       b'{"usage":{},"usage":{}}', b'{"usage":NaN}', b"[]", "not-bytes"])
def test_invalid_wire_values_are_fixed_failures_without_exception_or_logging(context, invalid, caplog):
    outcome = parse_analysis_response(invalid, prepared=context[1], preparer=context[0])
    assert outcome.rejection_code == "INVALID_MODEL_RESPONSE"
    assert outcome.usage is None and outcome.content is None
    assert "private-secret" not in repr(outcome) and not caplog.records


def test_response_bytes_boundary_and_invalid_preparation(context):
    value = raw(response(context[1]))
    exact = value + b" " * (MAX_RESPONSE_BYTES - len(value))
    assert parse_analysis_response(exact, prepared=context[1], preparer=context[0]).status == "VALIDATED"
    outcome = parse_analysis_response(exact + b" ", prepared=context[1], preparer=context[0])
    assert outcome.rejection_code == "MODEL_RESPONSE_TOO_LARGE" and outcome.usage is None
    with pytest.raises(AnalysisPreparationError, match="INVALID_PREPARED_ANALYSIS"):
        parse_analysis_response(value, prepared=replace(context[1], input_tokens=1), preparer=context[0])
