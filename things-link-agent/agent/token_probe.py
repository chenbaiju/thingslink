"""Offline synthetic probe materials and usage comparison; never an execution permit.

No credentials, I/O, counter implementation or automatic qualification exists here.
Matching three samples does not prove a tokenizer for all future messages.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from agent.model_request import MODEL, PROMPT_VERSION, MAX_REQUEST_BYTES, build_model_request

MAX_PROBE_OUTPUT = 128
MAX_INPUT_TOKENS = 4096
MAX_RESPONSE_BYTES = 16 * 1024


class ProbeError(ValueError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _json_bytes(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")


@dataclass(frozen=True, slots=True, repr=False)
class ProbeRequest:
    sample_id: str
    body: bytes
    request_sha256: str
    analysis_request_sha256: str
    messages_sha256: str
    model: str = MODEL
    prompt_version: str = PROMPT_VERSION

    def __repr__(self) -> str:
        return f"ProbeRequest[UNEXECUTED,sample={self.sample_id},bytes={len(self.body)}]"


def synthetic_probe_requests() -> tuple[ProbeRequest, ...]:
    """Exactly three fixed fixtures, no external data/prompt/configuration accepted."""
    time = "2026-10-04T00:00:00.123456789Z"
    cases = (
        ("missing-status", "STATUS_SUMMARY", "INACTIVE", "NORMAL", ()),
        ("alarm-scalars", "ALARM_EXPLANATION", "ONLINE", "ACTIVE",
         (("TEMPERATURE", "CELSIUS", 23.5), ("BINARY_STATE", "UNITLESS", False))),
        ("ten-readings", "STATUS_SUMMARY", "OFFLINE", "ACTIVE",
         (("TEMPERATURE", "KELVIN", 273.15), ("RELATIVE_HUMIDITY", "PERCENT", 0),
          ("PRESSURE", "KILOPASCAL", 101.325), ("VOLUMETRIC_FLOW", "LITRE_PER_MINUTE", -0.25),
          ("LEVEL", "MILLIMETRE", 100), ("POWER", "WATT", 123), ("POWER", "KILOWATT", 0.123),
          ("ENERGY", "KILOWATT_HOUR", 10), ("BINARY_STATE", "UNITLESS", False),
          ("LEVEL", "METRE", 0.123456789))),
    )
    requests = []
    for sample, template, status, alarm, readings in cases:
        document = {
            "template": template, "deviceAlias": "device-1", "collectionStartedAt": time,
            "collectionFinishedAt": time,
            "device": {"evidenceId": "e-device", "status": status, "lastOnlineAt": None, "readAt": time},
            "alarm": {"evidenceId": "e-alarm", "state": alarm, "observedAt": time},
            "readings": [{"evidenceId": f"e-property-{i}", "semantic": semantic, "unit": unit,
                          "value": value, "occurredAt": time, "readAt": time}
                         for i, (semantic, unit, value) in enumerate(readings, start=1)],
        }
        raw = _json_bytes(document)
        if sample == "ten-readings":
            raw = raw.replace(b'"value":0.123456789', b'"value":0.12345678901234567890123456789')
        draft = build_model_request(raw)
        body = json.loads(draft.body)
        body["max_tokens"] = MAX_PROBE_OUTPUT
        encoded = _json_bytes(body)
        if len(encoded) > MAX_REQUEST_BYTES:
            raise ProbeError("PROBE_REQUEST_TOO_LARGE")
        requests.append(ProbeRequest(sample, encoded, _sha(encoded), _sha(draft.body),
                                     _sha(_json_bytes(body["messages"]))))
    return tuple(requests)


@dataclass(frozen=True, slots=True)
class CounterMeasurement:
    messages_sha256: str
    implementation_sha256: str
    assets_sha256: str
    input_tokens: int

    def __post_init__(self):
        if (any(type(digest) is not str or re.fullmatch(r"[0-9a-f]{64}", digest) is None for digest in
                (self.messages_sha256, self.implementation_sha256, self.assets_sha256))
                or type(self.input_tokens) is not int or not 1 <= self.input_tokens <= MAX_INPUT_TOKENS):
            raise ProbeError("INVALID_COUNTER_MEASUREMENT")


@dataclass(frozen=True, slots=True)
class ProbeObservation:
    sample_id: str
    model: str
    prompt_version: str
    request_sha256: str
    analysis_request_sha256: str
    measurement: CounterMeasurement
    backend_fingerprint_sha256: str
    prompt_tokens: int
    completion_tokens: int
    cache_hit_tokens: int
    cache_miss_tokens: int
    total_tokens: int
    delta: int  # provider prompt - local complete-message count
    finish_reason: str

    @property
    def matched(self) -> bool:
        """Only this sample; no method/property granting business qualification."""
        return self.delta == 0


def _object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def _constant(_value: str):
    raise ValueError("non-standard JSON constant")


def _count(document: dict, key: str) -> int:
    value = document.get(key)
    if type(value) is not int or value < 0:
        raise ValueError("invalid count")
    return value


def compare_probe_response(probe: ProbeRequest, raw: bytes, measurement: CounterMeasurement) -> ProbeObservation:
    """Compare bounded nonstreaming API usage, discarding all generated text."""
    if type(probe) is not ProbeRequest or type(measurement) is not CounterMeasurement:
        raise ProbeError("INVALID_PROBE_BINDING")
    if probe not in synthetic_probe_requests() or measurement.messages_sha256 != probe.messages_sha256:
        raise ProbeError("INVALID_PROBE_BINDING")
    if type(raw) is not bytes:
        raise ProbeError("INVALID_PROBE_RESPONSE")
    if len(raw) > MAX_RESPONSE_BYTES:
        raise ProbeError("PROBE_RESPONSE_TOO_LARGE")
    observation = None
    try:
        response = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_object, parse_constant=_constant)
        if type(response) is not dict or response.get("object") != "chat.completion" or response.get("model") != MODEL:
            raise ValueError("wrong response identity")
        fingerprint = response.get("system_fingerprint")
        if type(fingerprint) is not str or re.fullmatch(r"[!-~]{1,128}", fingerprint) is None:
            raise ValueError("missing backend fingerprint")
        choices = response.get("choices")
        if type(choices) is not list or len(choices) != 1 or type(choices[0]) is not dict:
            raise ValueError("invalid choices")
        choice = choices[0]
        message = choice.get("message")
        if (type(choice.get("index")) is not int or choice["index"] != 0 or choice.get("finish_reason") not in ("stop", "length")
                or type(message) is not dict or message.get("role") != "assistant"
                or "content" not in message or (message["content"] is not None and type(message["content"]) is not str)
                or message.get("tool_calls") not in (None, []) or message.get("reasoning_content") not in (None, "")):
            raise ValueError("unexpected generation")
        usage = response.get("usage")
        if type(usage) is not dict:
            raise ValueError("missing usage")
        prompt = _count(usage, "prompt_tokens")
        completion = _count(usage, "completion_tokens")
        total = _count(usage, "total_tokens")
        hit, miss = _count(usage, "prompt_cache_hit_tokens"), _count(usage, "prompt_cache_miss_tokens")
        if not 1 <= prompt <= MAX_INPUT_TOKENS or completion > MAX_PROBE_OUTPUT or total != prompt + completion or prompt != hit + miss:
            raise ValueError("inconsistent usage or limit exceeded")
        if "prompt_tokens_details" in usage:
            details = usage["prompt_tokens_details"]
            if type(details) is not dict or ("cached_tokens" in details and _count(details, "cached_tokens") != hit):
                raise ValueError("inconsistent cache detail")
        if "completion_tokens_details" in usage:
            details = usage["completion_tokens_details"]
            if type(details) is not dict or ("reasoning_tokens" in details and _count(details, "reasoning_tokens") != 0):
                raise ValueError("unexpected reasoning usage")
        observation = ProbeObservation(probe.sample_id, MODEL, PROMPT_VERSION, probe.request_sha256,
                                       probe.analysis_request_sha256, measurement, _sha(fingerprint.encode()),
                                       prompt, completion, hit, miss, total, prompt - measurement.input_tokens,
                                       choice["finish_reason"])
    except (ValueError, UnicodeError, RecursionError, OverflowError):
        pass
    # No provider decoder/validation exception retaining credentials or model content.
    if observation is None:
        raise ProbeError("INVALID_PROBE_RESPONSE")
    return observation
