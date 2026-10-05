import copy
import hashlib
import json
from dataclasses import FrozenInstanceError, replace
from decimal import Decimal

import pytest

from agent.model_request import build_model_request
from agent.token_probe import (CounterMeasurement, MAX_RESPONSE_BYTES, ProbeError,
                               compare_probe_response, synthetic_probe_requests)


def raw(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def measurement(probe, count=100):
    return CounterMeasurement(probe.messages_sha256, "a" * 64, "b" * 64, count)


def response():
    return {"object": "chat.completion", "model": "deepseek-flash", "system_fingerprint": "synthetic-backend",
            "id": "synthetic-private-id", "choices": [{"index": 0, "finish_reason": "stop",
                "message": {"role": "assistant", "content": "synthetic-private-content"}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 10, "total_tokens": 110,
                      "prompt_cache_hit_tokens": 40, "prompt_cache_miss_tokens": 60,
                      "prompt_tokens_details": {"cached_tokens": 40},
                      "completion_tokens_details": {"reasoning_tokens": 0}}}


def test_exact_three_deterministic_synthetic_requests_reuse_entire_analysis_messages():
    probes = synthetic_probe_requests()
    assert probes == synthetic_probe_requests()
    assert len(probes) == 3 and len({p.sample_id for p in probes}) == 3
    for probe in probes:
        body = json.loads(probe.body)
        original = build_model_request(body["messages"][1]["content"].encode("utf-8"))
        expected = json.loads(original.body)
        expected["max_tokens"] = 128
        assert body == expected
        assert len(probe.body) <= 16 * 1024
        assert probe.request_sha256 == hashlib.sha256(probe.body).hexdigest()
        assert probe.analysis_request_sha256 == hashlib.sha256(original.body).hexdigest()
        assert probe.messages_sha256 == hashlib.sha256(raw(body["messages"])).hexdigest()
        assert "UNEXECUTED" in repr(probe) and '"readings":' not in repr(probe)
        assert "apiKey" not in probe.body.decode() and "projectId" not in probe.body.decode()
    full = json.loads(json.loads(probes[2].body)["messages"][1]["content"], parse_float=Decimal)
    assert len(full["readings"]) == 10
    assert full["readings"][-1]["value"] == Decimal("0.12345678901234567890123456789")
    assert full["readings"][8]["value"] is False
    assert full["device"]["lastOnlineAt"] is None
    assert full["collectionStartedAt"].endswith(".123456789Z")


@pytest.mark.parametrize("count,delta,matched", [(99, 1, False), (100, 0, True), (101, -1, False)])
def test_observation_compares_full_input_without_subtracting_cache_or_granting_qualification(count, delta, matched):
    probe = synthetic_probe_requests()[0]
    observation = compare_probe_response(probe, raw(response()), measurement(probe, count))
    assert observation.prompt_tokens == 100
    assert observation.cache_hit_tokens == 40 and observation.cache_miss_tokens == 60
    assert observation.delta == delta and observation.matched is matched
    assert observation.measurement.input_tokens == count
    assert "synthetic-private" not in repr(observation)
    assert "synthetic-backend" not in repr(observation)
    assert observation.backend_fingerprint_sha256 == hashlib.sha256(b"synthetic-backend").hexdigest()
    assert not hasattr(observation, "qualified")
    with pytest.raises(FrozenInstanceError):
        observation.delta = 0


def test_truncated_generation_is_count_evidence_without_report_quality_claim():
    probe = synthetic_probe_requests()[1]
    data = response()
    data["choices"][0]["finish_reason"] = "length"
    data["choices"][0]["message"]["content"] = '{"unfinished":'
    data["usage"].update(completion_tokens=128, total_tokens=228)
    observation = compare_probe_response(probe, raw(data), measurement(probe))
    assert observation.finish_reason == "length" and observation.completion_tokens == 128


@pytest.mark.parametrize("change", [
    lambda d: d.update(model="other-model"),
    lambda d: d.update(object="chat.completion.chunk"),
    lambda d: d.pop("system_fingerprint"),
    lambda d: d.update(system_fingerprint="bad\nvalue"),
    lambda d: d.pop("usage"),
    lambda d: d["usage"].pop("prompt_cache_hit_tokens"),
    lambda d: d["usage"].update(prompt_tokens="100"),
    lambda d: d["usage"].update(prompt_tokens=True),
    lambda d: d["usage"].update(prompt_tokens=100.0),
    lambda d: d["usage"].update(total_tokens=111),
    lambda d: d["usage"].update(prompt_cache_miss_tokens=0),
    lambda d: d["usage"].update(prompt_cache_hit_tokens=-1),
    lambda d: d["usage"].update(completion_tokens=129, total_tokens=229),
    lambda d: d["usage"].update(prompt_tokens=4097, prompt_cache_miss_tokens=4057, total_tokens=4107),
    lambda d: d["usage"].update(prompt_tokens=0, prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=0, total_tokens=10),
    lambda d: d["usage"]["prompt_tokens_details"].update(cached_tokens=39),
    lambda d: d["usage"].update(prompt_tokens_details=None),
    lambda d: d["usage"]["completion_tokens_details"].update(reasoning_tokens=1),
    lambda d: d["usage"]["completion_tokens_details"].update(reasoning_tokens=False),
    lambda d: d["choices"].append(copy.deepcopy(d["choices"][0])),
    lambda d: d["choices"][0].update(index=True),
    lambda d: d["choices"][0].update(finish_reason="tool_calls"),
    lambda d: d["choices"][0]["message"].update(tool_calls=[{}]),
    lambda d: d["choices"][0]["message"].update(reasoning_content="synthetic-private-reasoning"),
    lambda d: d["choices"][0]["message"].update(content=123),
])
def test_invalid_identity_usage_limits_and_generation_fail_without_payload(change, caplog):
    probe = synthetic_probe_requests()[0]
    data = response()
    change(data)
    with pytest.raises(ProbeError) as failure:
        compare_probe_response(probe, raw(data), measurement(probe))
    assert str(failure.value) == "INVALID_PROBE_RESPONSE"
    assert failure.value.__context__ is None and failure.value.__cause__ is None
    assert "synthetic-private" not in caplog.text


@pytest.mark.parametrize("data", [b"\xff", b"{} {}", b'{"secret":"synthetic-private",',
                                       b'{"usage":{},"usage":{}}', b'{"usage":NaN}', b"[" * 1500 + b"]" * 1500])
def test_invalid_bounded_json_never_retains_decoder_context(data):
    probe = synthetic_probe_requests()[0]
    with pytest.raises(ProbeError) as failure:
        compare_probe_response(probe, data, measurement(probe))
    assert failure.value.__context__ is None and failure.value.__cause__ is None


def test_response_byte_boundary_and_input_measurement_boundary():
    probe = synthetic_probe_requests()[0]
    data = raw(response())
    assert compare_probe_response(probe, b" " * (MAX_RESPONSE_BYTES - len(data)) + data, measurement(probe)).matched
    with pytest.raises(ProbeError, match="PROBE_RESPONSE_TOO_LARGE"):
        compare_probe_response(probe, b" " * (MAX_RESPONSE_BYTES + 1), measurement(probe))
    for count in (True, "100", 100.0, 0, -1, 4097):
        with pytest.raises(ProbeError, match="INVALID_COUNTER_MEASUREMENT"):
            measurement(probe, count)
    for count in (1, 4096):
        assert measurement(probe, count).input_tokens == count


def test_messages_counter_assets_and_exact_synthetic_request_are_bound():
    probe = synthetic_probe_requests()[0]
    with pytest.raises(ProbeError, match="INVALID_PROBE_BINDING"):
        compare_probe_response(probe, raw(response()), measurement(synthetic_probe_requests()[1]))
    for forged in (replace(probe, body=b"private"), replace(probe, model="other"),
                   replace(probe, sample_id="other"), replace(probe, prompt_version="other"),
                   replace(probe, analysis_request_sha256="c" * 64)):
        with pytest.raises(ProbeError, match="INVALID_PROBE_BINDING"):
            compare_probe_response(forged, raw(response()), measurement(probe))
    with pytest.raises(ProbeError, match="INVALID_COUNTER_MEASUREMENT"):
        CounterMeasurement(probe.messages_sha256, "unbound", "b" * 64, 100)
    with pytest.raises(FrozenInstanceError):
        probe.body = b"edited"
