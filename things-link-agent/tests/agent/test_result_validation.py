"""Synthetic contract tests; no project credential, network, DB, or MQTT."""

import json
import traceback
from concurrent.futures import ThreadPoolExecutor

import pytest
from pydantic import ValidationError

from agent.result_validation import (
    MAX_RESULT_BYTES,
    AnalysisResultError,
    ResultErrorCode,
    validate_analysis_result,
)


def result(identifier="e-status"):
    return {
        "summary": "设备离线，原因尚未确定。",
        "findings": [
            {"kind": "FACT", "statement": "本次状态为离线。", "evidenceIds": [identifier]},
            {"kind": "HYPOTHESIS", "statement": "可能存在网络故障。", "evidenceIds": [identifier]},
            {"kind": "RECOMMENDATION", "statement": "核对设备网络。", "evidenceIds": [identifier]},
        ],
        "limitations": ["没有连接日志，不能判断离线原因。"],
    }


def validate(value, scope=frozenset({"e-status"})):
    return validate_analysis_result(json.dumps(value, ensure_ascii=False), evidence_ids=scope)


def test_valid_content_preserves_categories_and_aliases():
    content = validate(result())
    assert content.model_dump(mode="json", by_alias=True) == result()
    assert [f.kind for f in content.findings] == ["FACT", "HYPOTHESIS", "RECOMMENDATION"]


def test_no_evidence_can_return_only_summary_and_limitations():
    content = validate({"summary": "证据不足。", "findings": [], "limitations": ["尚未采集证据。"]}, frozenset())
    assert content.findings == ()


@pytest.mark.parametrize("location", ["top", "finding", "metadata"])
def test_unknown_fields_and_model_declared_usage_are_rejected(location):
    value = result()
    if location == "top":
        value["apiKey"] = "synthetic-never-display"
    elif location == "finding":
        value["findings"][0]["toolCall"] = "mqtt_publish"
    else:
        value["usage"] = {"inputTokens": 1, "outputTokens": 1}
    with pytest.raises(AnalysisResultError, match=ResultErrorCode.INVALID_STRUCTURE):
        validate(value)


@pytest.mark.parametrize("mutation", [
    lambda v: v.update(summary=23),
    lambda v: v.update(summary=None),
    lambda v: v.update(summary=""),
    lambda v: v.update(limitations="没有日志"),
    lambda v: v["findings"][0].update(kind="CONTROL"),
    lambda v: v["findings"][0].update(statement=False),
    lambda v: v["findings"][0].update(evidenceIds=[]),
    lambda v: v["findings"][0].update(evidenceIds=[1]),
    lambda v: v["findings"][0].update(evidence_ids=["e-status"]),
    lambda v: v.pop("limitations"),
])
def test_malformed_structure_is_not_coerced(mutation):
    value = result()
    mutation(value)
    with pytest.raises(AnalysisResultError, match=ResultErrorCode.INVALID_STRUCTURE):
        validate(value)


@pytest.mark.parametrize("raw", [
    '{"summary":"one","summary":"two","findings":[],"limitations":[]}',
    '{"summary":"one","findings":[{"kind":"FACT","kind":"HYPOTHESIS"}],"limitations":[]}',
    '{"summary":"ok","findings":[],"limitations":[]} {}',
    '```json\n{"summary":"ok","findings":[],"limitations":[]}\n```',
    '{"summary":NaN,"findings":[],"limitations":[]}',
    '{"summary":Infinity,"findings":[],"limitations":[]}',
    b'\xff',
    '\ud800',
])
def test_non_json_duplicate_and_encoding_inputs_are_rejected(raw):
    with pytest.raises(AnalysisResultError, match=ResultErrorCode.INVALID_JSON):
        validate_analysis_result(raw, evidence_ids=frozenset())


def test_deeply_nested_document_is_rejected():
    # Parser recursion capacities vary by Python version; neither failure may
    # escape as an unbounded parser error or become a successful result.
    with pytest.raises(AnalysisResultError) as captured:
        validate_analysis_result('[' * 2000 + ']' * 2000, evidence_ids=frozenset())
    assert captured.value.code in {ResultErrorCode.INVALID_JSON, ResultErrorCode.INVALID_STRUCTURE}


def test_cross_run_reference_is_rejected_without_echoing_identifier():
    with pytest.raises(AnalysisResultError) as captured:
        validate(result("other-project-private-id"))
    assert captured.value.code == ResultErrorCode.INVALID_REFERENCES
    assert "other-project" not in str(captured.value)


def test_invocation_scopes_are_not_shared_between_threads():
    def run(index):
        identifier = f"e-{index}"
        validate(result(identifier), frozenset({identifier}))
        with pytest.raises(AnalysisResultError):
            validate(result(identifier), frozenset({f"e-{index + 1}"}))
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(run, range(20)))


def test_exact_utf8_byte_limit_and_one_byte_over():
    value = {"summary": "农业水务园区", "findings": [], "limitations": []}
    raw = json.dumps(value, ensure_ascii=False).encode()
    padded = raw + b" " * (MAX_RESULT_BYTES - len(raw))
    validate_analysis_result(padded, evidence_ids=frozenset())
    with pytest.raises(AnalysisResultError, match=ResultErrorCode.TOO_LARGE):
        validate_analysis_result(padded + b" ", evidence_ids=frozenset())


def test_result_and_nested_reference_collections_are_immutable():
    content = validate(result())
    with pytest.raises(ValidationError):
        content.summary = "edited"
    with pytest.raises(ValidationError):
        content.findings[0].statement = "edited"
    assert isinstance(content.findings, tuple)
    assert isinstance(content.findings[0].evidence_ids, tuple)
    with pytest.raises(TypeError):
        content.findings[0].evidence_ids[0] = "foreign"


@pytest.mark.parametrize("raw", [
    '{"summary":"synthetic-secret",',
    '{"summary":"synthetic-secret","findings":false,"limitations":[]}',
    '{"summary":"ok","findings":[],"limitations":[],"apiKey":"synthetic-secret"}',
])
def test_errors_have_no_payload_exception_context_or_log_output(raw, caplog):
    with pytest.raises(AnalysisResultError) as captured:
        validate_analysis_result(raw, evidence_ids=frozenset())
    error = captured.value
    assert error.__context__ is None
    assert error.__cause__ is None
    assert "synthetic-secret" not in "".join(traceback.format_exception(error))
    assert "synthetic-secret" not in repr(error)
    assert not caplog.records


def test_default_content_representation_does_not_include_payload():
    content = validate(result())
    assert "设备离线" not in repr(content)
    assert "设备离线" not in str(content)
    assert "本次状态" not in repr(content.findings[0])


def test_schema_is_closed_including_nested_findings():
    # Provider-side JSON-schema hints must not authorize extra fields locally.
    from agent.result_validation import AnalysisContent
    schema = AnalysisContent.model_json_schema()
    assert schema["additionalProperties"] is False
    assert schema["$defs"]["Finding"]["additionalProperties"] is False
