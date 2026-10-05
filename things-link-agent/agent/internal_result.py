"""闭合的内部结果编码；结构有效不等于业务准入或诊断质量通过。"""
from __future__ import annotations

import hashlib
import json
import re

from agent.admission_evidence import candidate_fingerprint
from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.analysis_response import OfflineAnalysisResult, ModelUsage
from agent.internal_analysis import InternalAnalysisCall, InternalAnalysisError, MAX_LONG
from agent.release_review import ReviewedExecution
from agent.result_validation import validate_analysis_result

VERSION = "agent-internal-analysis-result-v2"
MAX_RESULT_BYTES = 24 * 1024


def encode_internal_result(call: InternalAnalysisCall, result: OfflineAnalysisResult,
                           preparer: OfflineAnalysisPreparer, *, approval: ReviewedExecution | None = None, clock=None) -> bytes:
    """重验本次准备/摘要/用量，显式编码，无效对象只传出固定错误。"""
    encoded = None
    try:
        if type(call) is not InternalAnalysisCall or type(result) is not OfflineAnalysisResult:
            raise ValueError("invalid objects")
        if type(preparer) is not OfflineAnalysisPreparer:
            raise ValueError("invalid preparer")
        preparer.validate(call.prepared)
        from uuid import UUID, RFC_4122
        identity = UUID(call.call_id)
        if (str(identity) != call.call_id or identity.version != 7 or identity.variant != RFC_4122
                or type(call.configuration_revision) is not int or not 1 <= call.configuration_revision <= MAX_LONG
                or re.fullmatch(r"[0-9a-f]{64}", call.input_sha256) is None
                or call.input_sha256 != hashlib.sha256(call.prepared.draft.messages[1][1].encode("utf-8")).hexdigest()
                or result.request_sha256 != call.prepared.request_sha256
                or result.counter_sha256 != call.prepared.counter_sha256):
            raise ValueError("invalid binding")
        # 防止调用方绕过解析器手工构造自相矛盾的用量对象。
        usage = None
        if result.usage is not None:
            value = result.usage
            if (type(value) is not ModelUsage
                    or any(type(count) is not int or count < 0 for count in (
                        value.prompt_tokens, value.completion_tokens, value.total_tokens,
                        value.cache_hit_tokens, value.cache_miss_tokens))
                    or not 1 <= value.prompt_tokens <= 4096 or value.completion_tokens > 1024
                    or value.total_tokens != value.prompt_tokens + value.completion_tokens
                    or value.prompt_tokens != value.cache_hit_tokens + value.cache_miss_tokens
                    or (result.rejection_code != "INPUT_COUNT_MISMATCH"
                        and value.prompt_tokens != call.prepared.input_tokens)):
                raise ValueError("invalid usage")
            usage = dict(promptTokens=value.prompt_tokens, completionTokens=value.completion_tokens,
                         totalTokens=value.total_tokens, cacheHitTokens=value.cache_hit_tokens,
                         cacheMissTokens=value.cache_miss_tokens)
        elif result.provider_fingerprint_sha256 is not None:
            raise ValueError("unverified fingerprint")
        content = None
        if result.content is not None:
            content = result.content.model_dump(mode="json", by_alias=True)
            validate_analysis_result(json.dumps(content, ensure_ascii=False),
                                     evidence_ids=call.prepared.draft.evidence_ids)
        body = dict(version=VERSION, callId=call.call_id, configurationRevision=call.configuration_revision,
                    inputSha256=call.input_sha256, requestSha256=result.request_sha256,
                    counterSha256=result.counter_sha256, executionCandidateSha256=candidate_fingerprint(preparer),
                    model=result.model, promptVersion=result.prompt_version,
                    qualification=result.qualification, status=result.status, rejectionCode=result.rejection_code,
                    providerFingerprintSha256=result.provider_fingerprint_sha256, usage=usage, content=content)
        if approval is not None:
            if type(approval) is not ReviewedExecution:
                raise ValueError("invalid review")
            approval.verify(call, clock, result)
            body.update(version="agent-internal-analysis-result-v3", qualification="REVIEWED_EXECUTION",
                        releaseReviewSha256=approval.review_sha256)
        candidate = json.dumps(body, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(candidate) > MAX_RESULT_BYTES:
            raise ValueError("result too large")
        encoded = candidate
    except (ValueError, TypeError, AttributeError, UnicodeError, RecursionError):
        pass
    if encoded is None:
        raise InternalAnalysisError("INVALID_INTERNAL_ANALYSIS_RESULT")
    return encoded
