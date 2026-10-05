import copy
import json
from dataclasses import FrozenInstanceError
from decimal import Decimal

import pytest

from agent.model_request import MAX_REQUEST_BYTES, ModelRequestError, build_model_request


def input_document():
    time = "2026-10-03T12:00:00.123456789Z"
    return {
        "template": "STATUS_SUMMARY", "deviceAlias": "device-1",
        "collectionStartedAt": time, "collectionFinishedAt": time,
        "device": {"evidenceId": "e-device", "status": "ONLINE", "lastOnlineAt": None, "readAt": time},
        "alarm": {"evidenceId": "e-alarm", "state": "ACTIVE", "observedAt": time},
        "readings": [{"evidenceId": "e-property-2", "semantic": "TEMPERATURE", "unit": "CELSIUS", "value": 23.5, "occurredAt": time, "readAt": time}],
    }


def raw(document):
    return json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode()


def test_fixed_complete_request_contains_only_closed_observations_and_fixed_parameters():
    source = raw(input_document())
    draft = build_model_request(source)
    body = json.loads(draft.body)
    assert set(body) == {"model", "thinking", "stream", "max_tokens", "response_format", "messages"}
    assert body["model"] == "deepseek-flash"
    assert body["thinking"] == {"type": "disabled"}
    assert body["stream"] is False
    assert body["max_tokens"] == 1024
    assert body["response_format"] == {"type": "json_object"}
    assert [m["role"] for m in body["messages"]] == ["system", "user"]
    assert body["messages"][1]["content"].encode() == source
    assert tuple((m["role"], m["content"]) for m in body["messages"]) == draft.messages
    assert draft.evidence_ids == frozenset({"e-device", "e-alarm", "e-property-2"})
    for term in ("FACT", "HYPOTHESIS", "RECOMMENDATION", "evidenceIds", "limitations", "JSON", "additionalProperties"):
        assert term in body["messages"][0]["content"]
    assert "23.5" not in repr(draft)
    assert "UNQUALIFIED" in repr(draft)


def test_high_precision_numbers_are_not_float_rewritten_and_false_is_not_missing():
    document = input_document()
    binary = copy.deepcopy(document["readings"][0])
    binary.update(evidenceId="e-property-10", semantic="BINARY_STATE", unit="UNITLESS", value=False)
    document["readings"].append(binary)
    number = "0.123456789012345678901234567890123456789"
    source = raw(document).replace(b'"value":23.5', b'"value":' + number.encode())
    draft = build_model_request(source)
    forwarded = json.loads(draft.body)["messages"][1]["content"]
    assert number in forwarded
    parsed = json.loads(forwarded, parse_float=Decimal)
    assert parsed["readings"][0]["value"] == Decimal(number)
    assert parsed["readings"][1]["value"] is False


@pytest.mark.parametrize("change", [
    lambda d: d.update(apiKey="synthetic-secret"),
    lambda d: d.update(projectId="private-project"),
    lambda d: d.update(model="other-model"),
    lambda d: d.update(template="CONTROL"),
    lambda d: d.update(deviceAlias="private-name"),
    lambda d: d["device"].update(name="private-name"),
    lambda d: d["device"].update(location="private-location"),
    lambda d: d["alarm"].update(description="private-description"),
    lambda d: d["readings"][0].update(propertyKey="private-property"),
    lambda d: d["readings"][0].update(value="23.5"),
    lambda d: d["readings"][0].update(value=True),
    lambda d: d["readings"][0].update(unit="PASCAL"),
    lambda d: d["readings"][0].update(semantic="UNKNOWN"),
    lambda d: d["readings"][0].update(evidenceId="e-property-11"),
    lambda d: d["readings"][0].update(evidenceId="business-uuid"),
    lambda d: d["readings"].append(copy.deepcopy(d["readings"][0])),
    lambda d: d.update(readings=d["readings"] * 11),
    lambda d: d["readings"][0].update(semantic="BINARY_STATE", unit="UNITLESS", value=0),
    lambda d: d["readings"][0].update(occurredAt=None),
    lambda d: d["device"].update(readAt="2026-02-30T12:00:00Z"),
    lambda d: d["device"].update(lastOnlineAt="2026-10-03T12:00:00+08:00"),
    lambda d: d["alarm"].update(observedAt="not-a-date"),
])
def test_unknown_fields_semantics_types_ids_and_times_fail_closed(change, caplog):
    document = input_document()
    change(document)
    with pytest.raises(ModelRequestError) as failure:
        build_model_request(raw(document))
    assert str(failure.value) == "INVALID_MODEL_INPUT"
    assert failure.value.__context__ is None
    assert failure.value.__cause__ is None
    assert "private" not in str(failure.value)
    assert "synthetic-secret" not in caplog.text


@pytest.mark.parametrize("source", [b"\xff", b"{} {}", b'{"apiKey":"synthetic-secret",', b"{\"template\":1,\"template\":2}"])
def test_invalid_json_has_no_payload_exception_chain(source):
    with pytest.raises(ModelRequestError) as failure:
        build_model_request(source)
    assert str(failure.value) == "INVALID_MODEL_INPUT"
    assert failure.value.__context__ is None
    assert failure.value.__cause__ is None


@pytest.mark.parametrize("literal", [b"NaN", b"Infinity", b"-Infinity", b"1e10000"])
def test_non_finite_numbers_are_never_forwarded(literal):
    source = raw(input_document()).replace(b'"value":23.5', b'"value":' + literal)
    with pytest.raises(ModelRequestError, match="INVALID_MODEL_INPUT"):
        build_model_request(source)


def test_nested_duplicate_members_and_full_internal_projection_are_rejected():
    source = raw(input_document()).replace(b'"value":23.5', b'"value":23.5,"value":1')
    with pytest.raises(ModelRequestError):
        build_model_request(source)
    with pytest.raises(ModelRequestError):
        build_model_request(raw({"input": input_document(), "policyFingerprint": "internal", "omissions": [], "propertyPositions": {}}))


def test_input_and_complete_request_have_independent_byte_limits():
    source = raw(input_document())
    base = build_model_request(source)
    padding = MAX_REQUEST_BYTES - len(base.body)
    at_limit = build_model_request(b" " * padding + source)
    assert len(at_limit.body) == MAX_REQUEST_BYTES
    assert len(at_limit.body.decode()) < len(at_limit.body)  # Chinese fixed prompt must be measured in UTF8 bytes.
    with pytest.raises(ModelRequestError, match="MODEL_REQUEST_TOO_LARGE"):
        build_model_request(b" " * (padding + 1) + source)
    with pytest.raises(ModelRequestError, match="MODEL_INPUT_TOO_LARGE"):
        build_model_request(b" " * (MAX_REQUEST_BYTES + 1))


def test_empty_readings_and_both_fixed_templates_still_carry_device_and_alarm():
    document = input_document()
    document.update(template="ALARM_EXPLANATION", readings=[])
    draft = build_model_request(raw(document))
    assert "ALARM_EXPLANATION" in draft.messages[0][1]
    assert draft.evidence_ids == frozenset({"e-device", "e-alarm"})


def test_draft_containers_are_immutable():
    draft = build_model_request(raw(input_document()))
    with pytest.raises(FrozenInstanceError):
        draft.body = b"changed"
    with pytest.raises(TypeError):
        draft.messages[0] = ("user", "changed")
    with pytest.raises(AttributeError):
        draft.evidence_ids.add("new")
