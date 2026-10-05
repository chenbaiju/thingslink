"""Pinned, local-only candidate counter for the three authorized synthetic probes.

Never qualifies business input. No vendor Python, Jinja evaluation, Hub, or downloads.
"""
from __future__ import annotations

import hashlib
import json
from importlib.metadata import version
from pathlib import Path

from tokenizers import Tokenizer

from agent.token_probe import CounterMeasurement, ProbeError, ProbeRequest, synthetic_probe_requests

TOKENIZER_SHA = "89085f12ef79460ac5f66d1119325ddfc694b4ab209d80bbd81d35f081dc9614"
CONFIG_SHA = "841f8cf146e3f0ad1082594a31f68ecf7608c20467ef355081333d85bbaeb1cb"
ASSETS_SHA = hashlib.sha256((TOKENIZER_SHA + CONFIG_SHA).encode("ascii")).hexdigest()


def _bounded(path: Path, limit: int, digest: str) -> bytes:
    result = None
    try:
        with path.open("rb") as source:
            raw = source.read(limit + 1)
        if len(raw) <= limit and hashlib.sha256(raw).hexdigest() == digest:
            result = raw
    except OSError:
        pass
    if result is None:
        raise ProbeError("INVALID_COUNTER_ASSETS")
    return result


class SyntheticProbeCounter:
    def __init__(self, assets: Path):
        if version("tokenizers") != "0.23.2":
            raise ProbeError("INVALID_COUNTER_LIBRARY")
        raw = _bounded(assets / "tokenizer.json", 7 * 1024 * 1024, TOKENIZER_SHA)
        config = _bounded(assets / "tokenizer_config.json", 4096, CONFIG_SHA)
        # Only the reviewed two-message branch of the pinned static template.
        self._bos = json.loads(config)["bos_token"]["content"]
        self._tokenizer = Tokenizer.from_str(raw.decode("utf-8"))
        self.implementation_sha256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()

    def measure(self, probe: ProbeRequest) -> CounterMeasurement:
        if type(probe) is not ProbeRequest or probe not in synthetic_probe_requests():
            raise ProbeError("INVALID_PROBE_BINDING")
        messages = json.loads(probe.body)["messages"]
        text = self._bos + messages[0]["content"] + "<｜User｜>" + messages[1]["content"] + "<｜Assistant｜>"
        # BOS and role delimiters are already present; never add them a second time.
        count = len(self._tokenizer.encode(text, add_special_tokens=False).ids)
        return CounterMeasurement(probe.messages_sha256, self.implementation_sha256, ASSETS_SHA, count)

    def manifest_sha256(self) -> str:
        # One authorization binds all exact request bytes plus counter resources/implementation.
        material = [self.implementation_sha256, ASSETS_SHA,
                    *[p.request_sha256 for p in synthetic_probe_requests()]]
        return hashlib.sha256("\n".join(material).encode("ascii")).hexdigest()

    def __repr__(self) -> str:
        return "SyntheticProbeCounter[UNQUALIFIED]"
