"""单次非流式业务响应的纯解析边界；不发送请求、不授予模型质量或出站资格。"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from agent.analysis_preparation import OfflineAnalysisPreparer, PreparedAnalysis
from agent.model_request import MODEL, PROMPT_VERSION
from agent.result_validation import AnalysisContent, AnalysisResultError, ResultErrorCode, validate_analysis_result

MAX_RESPONSE_BYTES = 16 * 1024
MAX_OUTPUT_TOKENS = 1024
REJECTION_CODES = frozenset({"INVALID_MODEL_RESPONSE", "MODEL_RESPONSE_TOO_LARGE", "INPUT_COUNT_MISMATCH",
                             "OUTPUT_TRUNCATED", *[code.value for code in ResultErrorCode]})


@dataclass(frozen=True, slots=True)
class ModelUsage:
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int
    cache_hit_tokens: int
    cache_miss_tokens: int


@dataclass(frozen=True, slots=True, repr=False)
class OfflineAnalysisResult:
    request_sha256: str
    counter_sha256: str
    content: AnalysisContent | None
    usage: ModelUsage | None
    rejection_code: str | None
    provider_fingerprint_sha256: str | None

    def __post_init__(self):
        # 装配者也不能构造无内容的成功状态或把未核验用量写成成功。
        if (any(type(value) is not str or re.fullmatch(r"[0-9a-f]{64}", value) is None
                for value in (self.request_sha256, self.counter_sha256))
                or (self.rejection_code is None and (type(self.content) is not AnalysisContent or self.usage is None))
                or (self.rejection_code is not None and
                    (self.rejection_code not in REJECTION_CODES or self.content is not None))
                or (self.usage is not None and (type(self.usage) is not ModelUsage
                    or type(self.provider_fingerprint_sha256) is not str
                    or re.fullmatch(r"[0-9a-f]{64}", self.provider_fingerprint_sha256) is None))):
            raise ValueError("INVALID_ANALYSIS_OUTCOME")

    @property
    def status(self) -> str:
        return "REJECTED" if self.rejection_code is not None else "VALIDATED"

    @property
    def qualification(self) -> str:
        return "OFFLINE_UNQUALIFIED"

    @property
    def model(self) -> str:
        return MODEL

    @property
    def prompt_version(self) -> str:
        return PROMPT_VERSION

    def __repr__(self) -> str:
        return f"OfflineAnalysisResult[OFFLINE_UNQUALIFIED,status={self.status}]"


def _object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON member")
        result[key] = value
    return result


def _constant(_value: str):
    raise ValueError("non-standard JSON constant")


def _count(document: dict, key: str) -> int:
    value = document.get(key)
    if type(value) is not int or value < 0:
        raise ValueError("invalid usage")
    return value


def _envelope(raw: bytes) -> tuple[str, str, ModelUsage, str]:
    document = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_object, parse_constant=_constant)
    if type(document) is not dict or document.get("object") != "chat.completion" or document.get("model") != MODEL:
        raise ValueError("invalid identity")
    fingerprint = document.get("system_fingerprint")
    if type(fingerprint) is not str or re.fullmatch(r"[!-~]{1,128}", fingerprint) is None:
        raise ValueError("invalid fingerprint")
    choices = document.get("choices")
    if type(choices) is not list or len(choices) != 1 or type(choices[0]) is not dict:
        raise ValueError("invalid choice")
    choice = choices[0]
    message = choice.get("message")
    if (type(choice.get("index")) is not int or choice["index"] != 0
            or choice.get("finish_reason") not in ("stop", "length") or type(message) is not dict
            or message.get("role") != "assistant" or type(message.get("content")) is not str
            or message.get("tool_calls") not in (None, []) or message.get("reasoning_content") not in (None, "")):
        raise ValueError("invalid generation")
    usage = document.get("usage")
    if type(usage) is not dict:
        raise ValueError("missing usage")
    prompt, completion, total = (_count(usage, key) for key in ("prompt_tokens", "completion_tokens", "total_tokens"))
    hit, miss = _count(usage, "prompt_cache_hit_tokens"), _count(usage, "prompt_cache_miss_tokens")
    if not 1 <= prompt <= 4096 or completion > MAX_OUTPUT_TOKENS or total != prompt + completion or prompt != hit + miss:
        raise ValueError("inconsistent usage")
    for key, field, expected in (("prompt_tokens_details", "cached_tokens", hit),
                                 ("completion_tokens_details", "reasoning_tokens", 0)):
        if key in usage:
            details = usage[key]
            if type(details) is not dict or (field in details and _count(details, field) != expected):
                raise ValueError("inconsistent usage detail")
    return message["content"], choice["finish_reason"], ModelUsage(prompt, completion, total, hit, miss), \
        hashlib.sha256(fingerprint.encode("ascii")).hexdigest()


def parse_analysis_response(
    raw: bytes, *, prepared: PreparedAnalysis, preparer: OfflineAnalysisPreparer,
) -> OfflineAnalysisResult:
    """重验准备结果并组装离线回执；失败正文和解析异常均不进入返回对象或日志。"""
    preparer.validate(prepared)

    def result(content=None, usage=None, code=None, fingerprint=None):
        return OfflineAnalysisResult(prepared.request_sha256, prepared.counter_sha256,
                                     content, usage, code, fingerprint)

    if type(raw) is not bytes:
        return result(code="INVALID_MODEL_RESPONSE")
    if len(raw) > MAX_RESPONSE_BYTES:
        return result(code="MODEL_RESPONSE_TOO_LARGE")
    envelope = None
    try:
        envelope = _envelope(raw)
    except (ValueError, UnicodeError, RecursionError):
        pass
    if envelope is None:
        # 未核验的用量为空，不把供应商失败或坏响应写成零消费。
        return result(code="INVALID_MODEL_RESPONSE")
    text, finish, usage, fingerprint = envelope
    if usage.prompt_tokens != prepared.input_tokens:
        return result(usage=usage, code="INPUT_COUNT_MISMATCH", fingerprint=fingerprint)
    if finish == "length":
        return result(usage=usage, code="OUTPUT_TRUNCATED", fingerprint=fingerprint)
    content = None
    rejection = None
    try:
        content = validate_analysis_result(text, evidence_ids=prepared.draft.evidence_ids)
    except AnalysisResultError as failure:
        rejection = failure.code.value
    return result(content, usage, rejection, fingerprint)
