import hashlib
import json
import os
from dataclasses import replace
from pathlib import Path

import pytest

from agent.candidate_counter_v41 import ASSETS_SHA, CONTRACT_SHA, TOKENIZER_SHA, V41CandidateCounter
from agent.token_probe import ProbeError, synthetic_probe_requests


# 从2026-10-04已有封闭回执提取；不含项目、用户、凭据或生成正文。
HISTORICAL_USAGE = (
    ("missing-status", "0476ae1a39ac8f48db61e410f225ce25137c6165cdd9ab996f133dd84023a700",
     "b6cdcbc17d309293187f8bb2e72ce73f16f0d2afd9b76c80d9a6c73c490a323c", 502),
    ("alarm-scalars", "636819db4927d9f0da652eeebd680bd201a5efb410252c30f2671ddece537c29",
     "0bf6b736d5d2c68e1c7993f2883be4cfbbdd8a3a3a647c4a51b4e9f344ed708b", 647),
    ("ten-readings", "f9a91251d14296fe4b92339c86a02a72017f54becc802412ac437165780d6c0f",
     "233b3fd0dcc7c258528b3427f7f21d246bafa7a3429992eb392b64100c4b1124", 1230),
)


@pytest.fixture
def counter(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("counter attempted network"))
    path = os.environ.get("AGENT_V41_COUNTER_ASSETS")
    if not path:
        pytest.skip("pinned official V4.1 static resources not provisioned")
    return V41CandidateCounter(Path(path))


def test_complete_v41_counts_match_exact_historical_requests_offline(counter):
    samples = synthetic_probe_requests()
    measurements = [counter.measure(sample) for sample in samples]
    for sample, measurement, (sample_id, request_sha, messages_sha, prompt_tokens) in zip(
            samples, measurements, HISTORICAL_USAGE, strict=True):
        assert sample.sample_id == sample_id
        assert hashlib.sha256(sample.body).hexdigest() == sample.request_sha256 == request_sha
        assert measurement.messages_sha256 == sample.messages_sha256 == messages_sha
        assert measurement.input_tokens == prompt_tokens
        assert measurement.assets_sha256 == ASSETS_SHA
    assert measurements == [counter.measure(sample) for sample in samples]
    assert counter.manifest_sha256() == counter.manifest_sha256()
    assert len({measurement.implementation_sha256 for measurement in measurements}) == 1
    assert counter.manifest_sha256() != "78998d4d1e70d5e67fadedd8e3dfe8f7f565808f2fa376ff0413cb797d3129f1"
    assert "OFFLINE,UNQUALIFIED" in repr(counter)


def test_official_json_and_nonthinking_framing_is_encoded_as_one_prompt(counter):
    sample = synthetic_probe_requests()[0]
    messages = json.loads(sample.body)["messages"]
    oracle = (
        "<｜begin▁of▁sentence｜><｜System｜>" + messages[0]["content"]
        + '\n\n## Response Format:\n\nYou MUST strictly adhere to the following schema to reply:\n{"type": "json_object"}'
        + "<｜User｜>" + messages[1]["content"] + "<｜Assistant｜></think>"
    )
    tokenizer = counter._tokenizer
    calls = []

    class RecordingTokenizer:
        def encode(self, text, **kwargs):
            calls.append((text, kwargs))
            return tokenizer.encode(text, **kwargs)

    counter._tokenizer = RecordingTokenizer()
    assert counter.measure(sample).input_tokens == 502
    assert calls == [(oracle, {"add_special_tokens": False})]
    # 每个完整分支使用独立控制Token；不把它们当普通文本或二次加BOS。
    assert tokenizer.encode("<｜System｜>", add_special_tokens=False).ids == [128799]
    assert tokenizer.encode("</think>", add_special_tokens=False).ids == [128822]


@pytest.mark.parametrize("change", [
    {"model": "deepseek-chat"}, {"prompt_version": "future-template"}, {"sample_id": "business"},
    {"request_sha256": "f" * 64}, {"messages_sha256": "f" * 64}, {"analysis_request_sha256": "f" * 64},
])
def test_mismatched_binding_cannot_be_counted(counter, change):
    with pytest.raises(ProbeError, match="INVALID_PROBE_BINDING"):
        counter.measure(replace(synthetic_probe_requests()[0], **change))


@pytest.mark.parametrize("mutation", ["thinking", "tools", "roles", "format", "output", "history"])
def test_changed_protocol_branch_is_rejected_even_if_hashes_are_recomputed(counter, mutation):
    sample = synthetic_probe_requests()[0]
    body = json.loads(sample.body)
    if mutation == "thinking":
        body["thinking"] = {"type": "enabled"}
    elif mutation == "tools":
        body["tools"] = []
    elif mutation == "roles":
        body["messages"][0]["role"] = "user"
    elif mutation == "format":
        body["response_format"] = {"type": "text"}
    elif mutation == "output":
        body["max_tokens"] = 1024
    else:
        body["messages"].append({"role": "assistant", "content": "historical text"})
    raw = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
    messages = json.dumps(body["messages"], ensure_ascii=False, separators=(",", ":")).encode()
    changed = replace(sample, body=raw, request_sha256=hashlib.sha256(raw).hexdigest(),
                      messages_sha256=hashlib.sha256(messages).hexdigest())
    with pytest.raises(ProbeError, match="INVALID_PROBE_BINDING"):
        counter.measure(changed)


@pytest.mark.parametrize("asset", [None, b"private-text", b"x" * (7 * 1024 * 1024 + 1)],
                         ids=["missing", "untrusted", "oversize"])
def test_missing_untrusted_or_oversize_assets_fail_closed_without_error_context(tmp_path, asset):
    if asset is not None:
        (tmp_path / "tokenizer.json").write_bytes(asset)
    with pytest.raises(ProbeError, match="INVALID_COUNTER_ASSETS") as failure:
        V41CandidateCounter(tmp_path)
    assert str(tmp_path) not in str(failure.value)
    assert "private-text" not in str(failure.value)
    assert failure.value.__context__ is None


def test_unreviewed_library_is_rejected_before_asset_loading(monkeypatch, tmp_path):
    monkeypatch.setattr("agent.candidate_counter_v41.version", lambda name: "future-version")
    with pytest.raises(ProbeError, match="INVALID_COUNTER_LIBRARY"):
        V41CandidateCounter(tmp_path)


def test_public_resources_and_contract_are_bound_separately(counter):
    raw = (Path(os.environ["AGENT_V41_COUNTER_ASSETS"]) / "tokenizer.json").read_bytes()
    assert hashlib.sha256(raw).hexdigest() == TOKENIZER_SHA
    assert ASSETS_SHA == hashlib.sha256((TOKENIZER_SHA + CONTRACT_SHA).encode("ascii")).hexdigest()
    assert counter.implementation_sha256 == hashlib.sha256(
        Path(__file__).parents[2].joinpath("agent/candidate_counter_v41.py").read_bytes()).hexdigest()
