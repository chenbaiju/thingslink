"""固定发布裁决下的单次内部执行资格；摘要头和材料齐全均不能自行授权。"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from agent.admission_evidence import audit_admission_evidence, candidate_fingerprint, _object, _constant, _utc
from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.analysis_response import OfflineAnalysisResult
from agent.internal_analysis import AnalysisDeadline, Clock, InternalAnalysisCall, _monotonic

REVIEW_SHA256 = "c945bc81b430484ba0aa5bff877b7f6cb178e7b264f5095d03c51bc59a30ce6b"
REQUEST_CONTRACT_SHA256 = "10f0d15475b1beb590311b2d0c0ed3da883226419353360256e22df74243c4e2"
_FIELDS = frozenset({"version", "decision", "model", "modelVersion", "promptVersion", "executionCandidateSha256",
                     "counterSha256", "requestContractSha256", "evidenceManifestSha256", "providerFingerprintSha256",
                     "reviewedAt", "expiresAt", "checks"})
_SEAL = object()


class ReleaseReviewError(ValueError):
    def __init__(self):
        super().__init__("MODEL_BUSINESS_UNAVAILABLE")


@dataclass(frozen=True, slots=True, repr=False)
class ReviewedExecution:
    review_sha256: str
    call_id: str
    revision: int
    input_sha256: str
    request_sha256: str
    counter_sha256: str
    provider_sha256: str
    reviewed_millis: int
    expires_millis: int
    deadline: AnalysisDeadline
    _seal: object

    def __repr__(self):
        return "ReviewedExecution[内容已隐藏]"

    def verify(self, call: InternalAnalysisCall, clock: Clock, result: OfflineAnalysisResult | None = None):
        """期限不重建，原输入/请求与供应商指纹必须匹配，不以模型自报建立资格。"""
        valid = False
        try:
            wall = clock.wall_millis()
            valid = (type(self) is ReviewedExecution and self._seal is _SEAL
                     and type(call) is InternalAnalysisCall and type(wall) is int
                     and self.reviewed_millis <= wall < self.expires_millis
                     and (call.call_id, call.configuration_revision, call.input_sha256,
                          call.prepared.request_sha256, call.prepared.counter_sha256)
                     == (self.call_id, self.revision, self.input_sha256, self.request_sha256, self.counter_sha256)
                     and self.review_sha256 == REVIEW_SHA256)
            self.deadline.remaining(clock)
            call.deadline.remaining(clock)
            if result is not None:
                valid = (valid and type(result) is OfflineAnalysisResult
                         and result.request_sha256 == self.request_sha256
                         and result.counter_sha256 == self.counter_sha256
                         and result.provider_fingerprint_sha256 == self.provider_sha256)
        except Exception:
            valid = False
        if not valid:
            raise ReleaseReviewError()


def load_reviewed_execution(call: InternalAnalysisCall, preparer: OfflineAnalysisPreparer,
                            review_sha256: str, clock: Clock) -> ReviewedExecution:
    """仅固定包内裁决明确批准才返回资格；不读取调用方文件或联网补材料。"""
    approval = None
    try:
        if (type(call) is not InternalAnalysisCall or type(preparer) is not OfflineAnalysisPreparer
                or type(review_sha256) is not str or review_sha256 != REVIEW_SHA256):
            raise ValueError()
        started, wall = _monotonic(clock), clock.wall_millis()
        if type(wall) is not int:
            raise ValueError()
        with Path(__file__).with_name("release_review.json").open("rb") as stream:
            raw = stream.read(16 * 1024 + 1)
        if not 0 < len(raw) <= 16 * 1024 or hashlib.sha256(raw).hexdigest() != REVIEW_SHA256:
            raise ValueError()
        document = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_object, parse_constant=_constant)
        if type(document) is not dict or set(document) != _FIELDS or document["version"] != "agent-release-review-v1":
            raise ValueError()
        now = datetime.fromtimestamp(wall / 1000, timezone.utc)
        audit = audit_admission_evidence(preparer, now=now)
        if (document["decision"] != "APPROVED" or audit.status != "REVIEW_COMPLETE"
                or document["model"] != "deepseek-flash" or document["modelVersion"] != "DeepSeek-V4.1-Flash"
                or document["promptVersion"] != "thingslink-agent-single-analysis-v1"
                or document["executionCandidateSha256"] != candidate_fingerprint(preparer)
                or document["counterSha256"] != call.prepared.counter_sha256
                or document["evidenceManifestSha256"] != audit.manifest_sha256
                or document["requestContractSha256"] != REQUEST_CONTRACT_SHA256
                or type(document["providerFingerprintSha256"]) is not str
                or re.fullmatch(r"[0-9a-f]{64}", document["providerFingerprintSha256"]) is None):
            raise ValueError()
        begin, end = _utc(document["reviewedAt"]), _utc(document["expiresAt"])
        if not 0 < (end - begin).total_seconds() <= 604800 or not begin <= now < end or end > audit.expires_at:
            raise ValueError()
        checks = document["checks"]
        if type(checks) is not list or len(checks) != 2:
            raise ValueError()
        seen = set()
        expected = {check.check_id: check.evidence_sha256 for check in audit.checks}
        for check in checks:
            if (type(check) is not dict or set(check) != {"checkId", "decision", "evidenceSha256"}
                    or type(check["checkId"]) is not str or check["checkId"] in seen
                    or check["checkId"] not in expected or check["decision"] != "ACCEPTED"
                    or not isinstance(expected[check["checkId"]], str)
                    or check["evidenceSha256"] != expected[check["checkId"]]):
                raise ValueError()
            seen.add(check["checkId"])
        expiry = int(end.timestamp() * 1000)
        deadline = AnalysisDeadline(call.deadline.started, min(call.deadline.expires, started + (expiry - wall) / 1000))
        approval = ReviewedExecution(review_sha256, call.call_id, call.configuration_revision, call.input_sha256,
                                     call.prepared.request_sha256, call.prepared.counter_sha256,
                                     document["providerFingerprintSha256"], int(begin.timestamp() * 1000), expiry, deadline, _SEAL)
        approval.verify(call, clock)
    except Exception:
        approval = None
    if approval is None:
        raise ReleaseReviewError()
    return approval
