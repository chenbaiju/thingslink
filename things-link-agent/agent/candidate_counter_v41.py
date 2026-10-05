"""AG-T03：V4.1 固定合成样本的完整消息离线计数，不授予发送或业务资格。"""
from __future__ import annotations

import hashlib
import json
from importlib.metadata import version
from pathlib import Path

from tokenizers import Tokenizer

from agent.token_probe import CounterMeasurement, ProbeError, ProbeRequest, synthetic_probe_requests

RECIPE_COMMIT = "8cadfede7063c896b944e7bae05daa3549ae97ea"
MODEL_VERSION = "DeepSeek-V4.1-Flash"
TOKENIZER_SHA = "81f64d1248a68ce3663e07ab3ee48b851e5df0e32d27cb98e4c9a268151e8d99"
# 固定审阅的官方源码；只转写文本、双消息、JSON对象、非思考分支。
SOURCE_SHAS = {
    "deepseek-recipe-encoding/src/v4/mod.rs": "0a4577a216bcbddabd5bcf969cabd75060deb79e643548f8a04bda1d76e4a982",
    "deepseek-recipe-encoding/src/v4/dsv41.rs": "3408554e8a4ade05e034231cab8f87efcee7459d7fdd28fbb926f652e3b56786",
    "deepseek-recipe/src/protocol/openai/chat_completion/request/convert.rs": "9def89996cb111db3fd411e352c37c3e693e0bdcd04a5e7d50e97ef276100fec",
    "deepseek-recipe-core/src/util/json_formatter.rs": "729102bc38ce4d312dc42f00be43f0b212d7410761cd313d5e85a2c9006223ed",
}
BOS = "<｜begin▁of▁sentence｜>"
SYSTEM = "<｜System｜>"
USER = "<｜User｜>"
ASSISTANT = "<｜Assistant｜>"
THINKING_END = "</think>"
FORMAT_INSTRUCTION = "\n\n## Response Format:\n\nYou MUST strictly adhere to the following schema to reply:\n"
JSON_OBJECT = json.dumps({"type": "json_object"}, ensure_ascii=False, separators=(", ", ": "))
CONTRACT_SHA = hashlib.sha256(json.dumps({
    "recipe_commit": RECIPE_COMMIT, "sources": SOURCE_SHAS, "model_version": MODEL_VERSION,
    "branch": "system-user/text/json_object/thinking-disabled/no-tools/v1",
    "render": [BOS, SYSTEM, FORMAT_INSTRUCTION, JSON_OBJECT, USER, ASSISTANT, THINKING_END],
}, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()
ASSETS_SHA = hashlib.sha256((TOKENIZER_SHA + CONTRACT_SHA).encode("ascii")).hexdigest()


def _load_tokenizer(assets: Path) -> Tokenizer:
    raw = None
    try:
        with (assets / "tokenizer.json").open("rb") as source:
            candidate = source.read(7 * 1024 * 1024 + 1)
        if len(candidate) <= 7 * 1024 * 1024 and hashlib.sha256(candidate).hexdigest() == TOKENIZER_SHA:
            raw = candidate
    except OSError:
        pass
    # 不能让文件路径、读取错误链或资源内容进入错误回执。
    if raw is None:
        raise ProbeError("INVALID_COUNTER_ASSETS")
    return Tokenizer.from_str(raw.decode("utf-8"))


class V41CandidateCounter:
    """仅接受原三个精确请求；不接凭据、不接任意草稿、不注册到服务发送链。"""

    def __init__(self, assets: Path):
        if version("tokenizers") != "0.23.2":
            raise ProbeError("INVALID_COUNTER_LIBRARY")
        self._tokenizer = _load_tokenizer(assets)
        self.implementation_sha256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()

    def measure(self, probe: ProbeRequest) -> CounterMeasurement:
        if type(probe) is not ProbeRequest or probe not in synthetic_probe_requests():
            raise ProbeError("INVALID_PROBE_BINDING")
        messages = json.loads(probe.body)["messages"]
        # 完整渲染后一次编码，边界合并由分词器处理，不拼加各段计数或观察差值。
        text = (BOS + SYSTEM + messages[0]["content"] + FORMAT_INSTRUCTION + JSON_OBJECT
                + USER + messages[1]["content"] + ASSISTANT + THINKING_END)
        count = len(self._tokenizer.encode(text, add_special_tokens=False).ids)
        return CounterMeasurement(probe.messages_sha256, self.implementation_sha256, ASSETS_SHA, count)

    def manifest_sha256(self) -> str:
        # 这是独立离线候选摘要；不更新旧台账、原授权摘要或可用次数。
        material = ["offline-v41-v1", MODEL_VERSION, CONTRACT_SHA, self.implementation_sha256, ASSETS_SHA,
                    *[p.request_sha256 for p in synthetic_probe_requests()]]
        return hashlib.sha256("\n".join(material).encode("ascii")).hexdigest()

    def __repr__(self) -> str:
        return "V41CandidateCounter[OFFLINE,UNQUALIFIED]"
