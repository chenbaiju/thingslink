"""核验受审发布材料的完整性与候选绑定；不授予业务发送或付费许可。"""
from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, ValidationError

from agent import analysis_preparation, analysis_response, candidate_counter_v41, model_request, result_validation
from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.model_request import MODEL, PROMPT_VERSION

VERSION = "business-admission-review-v1"
MANIFEST_SHA256 = "a1469ccb2412beda70e938852bb4eafd431f3efed91684be467a29b79361444a"
CHECK_FILES = {
    "ONLINE_COUNTER_CONTRACT": "online_counter_contract.receipt",
    "SUPPLIER_DATA_HANDLING": "supplier_data_handling.receipt",
}
MAX_MANIFEST_BYTES = 16 * 1024
MAX_RECEIPT_BYTES = 64 * 1024
MAX_REVIEW_WINDOW = timedelta(days=7)
CANDIDATE_BINDING_VERSION = "analysis-execution-candidate-v2"
_PACKAGE_ROOT = Path(analysis_preparation.__file__).resolve().parent
_CONTRACT_ROOT = (_PACKAGE_ROOT / "_runtime_contracts" if (_PACKAGE_ROOT / "_runtime_contracts").is_dir()
                  else _PACKAGE_ROOT.parent)
EXECUTION_FILES = (
    "internal_analysis.py", "internal_result.py", "analysis_execution.py", "analysis_transport.py",
    "analysis_endpoint.py", "release_review.py", "internal_server.py", "mtls.py", "admission_evidence.py",
    "__init__.py", "token_probe.py", "probe_counter.py", "probe_sender.py", "v41_probe_counter.py",
    "../pyproject.toml", "../uv.lock",
)


class AdmissionEvidenceError(ValueError):
    """只包含固定错误代码，不保留文件路径、材料正文或解码异常。"""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class _StrictRecord(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True, hide_input_in_errors=True)


class _Check(_StrictRecord):
    checkId: Literal["ONLINE_COUNTER_CONTRACT", "SUPPLIER_DATA_HANDLING"]
    status: Literal["PENDING", "VERIFIED"]
    evidenceSha256: str | None


class _Manifest(_StrictRecord):
    version: Literal["business-admission-review-v1"]
    model: str
    modelVersion: str
    promptVersion: str
    candidateSha256: str
    counterSha256: str
    reviewedAt: str
    expiresAt: str
    checks: list[_Check]


@dataclass(frozen=True, slots=True)
class CheckEvidence:
    check_id: str
    status: str
    evidence_sha256: str | None


@dataclass(frozen=True, slots=True)
class AdmissionAudit:
    candidate_sha256: str
    manifest_sha256: str
    expires_at: datetime
    checks: tuple[CheckEvidence, ...]

    @property
    def missing_checks(self) -> tuple[str, ...]:
        return tuple(check.check_id for check in self.checks if check.status != "VERIFIED")

    @property
    def status(self) -> str:
        return "REVIEW_PENDING" if self.missing_checks else "REVIEW_COMPLETE"

    @property
    def business_available(self) -> bool:
        # 即使人工声明附件完整，也不能替代真实准入、付费授权或Java当前权限。
        return False


def _digest(value: object) -> bool:
    return type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def _source_digest(name: str, path: Path) -> str:
    raw = path.read_bytes()
    if name == "admission_evidence.py":
        # 清单摘要已由独立原字节校验；只归一该常量以消除候选与清单的递归依赖。
        raw, count = re.subn(rb'^MANIFEST_SHA256 = "[0-9a-f]{64}"$',
                             b'MANIFEST_SHA256 = "<bound-separately>"', raw, flags=re.MULTILINE)
        if count != 1:
            raise AdmissionEvidenceError("INVALID_ADMISSION_CANDIDATE")
    if name == "release_review.py":
        raw, count = re.subn(rb'^REVIEW_SHA256 = "[0-9a-f]{64}"$',
                            b'REVIEW_SHA256 = "<bound-separately>"', raw, flags=re.MULTILINE)
        if count != 1:
            raise AdmissionEvidenceError("INVALID_ADMISSION_CANDIDATE")
    return hashlib.sha256(raw).hexdigest()


def candidate_fingerprint(preparer: OfflineAnalysisPreparer) -> str:
    """绑定请求/结果与完整Python执行链、依赖锁；旧准备器不能沿用漂移后的评审。"""
    if type(preparer) is not OfflineAnalysisPreparer or not _digest(preparer.counter_sha256):
        raise AdmissionEvidenceError("INVALID_ADMISSION_CANDIDATE")
    hashes = None
    try:
        modules = (analysis_preparation, candidate_counter_v41, model_request, result_validation, analysis_response)
        sources = [(module.__name__.rsplit('.', 1)[-1] + '.py', Path(module.__file__)) for module in modules]
        sources.extend((name, _CONTRACT_ROOT / Path(name).name if name.startswith('../') else _PACKAGE_ROOT / name)
                       for name in EXECUTION_FILES)
        hashes = tuple(name + ':' + _source_digest(name, path) for name, path in sources)
    except OSError:
        pass
    if hashes is None:
        raise AdmissionEvidenceError("INVALID_ADMISSION_CANDIDATE")
    binding = (VERSION, CANDIDATE_BINDING_VERSION, MODEL, candidate_counter_v41.MODEL_VERSION, PROMPT_VERSION,
               candidate_counter_v41.CONTRACT_SHA, candidate_counter_v41.ASSETS_SHA,
               preparer.counter_sha256, *hashes)
    return hashlib.sha256("\n".join(binding).encode("ascii")).hexdigest()


def _read_checked(path: Path, digest: str, maximum: int, code: str) -> bytes:
    encoded = None
    try:
        with path.open("rb") as source:
            raw = source.read(maximum + 1)
        if 0 < len(raw) <= maximum and hashlib.sha256(raw).hexdigest() == digest:
            encoded = raw
    except OSError:
        pass
    if encoded is None:
        raise AdmissionEvidenceError(code)
    return encoded


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def _constant(_value):
    raise ValueError("non-standard JSON constant")


def _utc(value: str) -> datetime:
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value) is None:
        raise ValueError("invalid UTC time")
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def audit_admission_evidence(preparer: OfflineAnalysisPreparer, *, now: datetime | None = None) -> AdmissionAudit:
    """仅读取固定包内材料；没有调用方材料、路径、开关或Key入口。"""
    current = datetime.now(timezone.utc) if now is None else now
    if (type(current) is not datetime or current.tzinfo is None
            or current.utcoffset() != timedelta(0)):
        raise AdmissionEvidenceError("INVALID_ADMISSION_CLOCK")
    root = Path(__file__).parent
    encoded = _read_checked(root / "admission_review.json", MANIFEST_SHA256, MAX_MANIFEST_BYTES,
                            "INVALID_ADMISSION_MANIFEST")
    parsed = None
    try:
        parsed = _Manifest.model_validate(json.loads(encoded.decode("utf-8", errors="strict"),
                                                   object_pairs_hook=_object, parse_constant=_constant))
        if (not _digest(parsed.candidateSha256) or not _digest(parsed.counterSha256)
                or len(parsed.checks) != len(CHECK_FILES)
                or {item.checkId for item in parsed.checks} != set(CHECK_FILES)):
            raise ValueError("invalid binding or checks")
        for item in parsed.checks:
            if ((item.status == "PENDING" and item.evidenceSha256 is not None)
                    or (item.status == "VERIFIED" and not _digest(item.evidenceSha256))):
                raise ValueError("invalid review evidence")
        reviewed_at, expires_at = _utc(parsed.reviewedAt), _utc(parsed.expiresAt)
        if not timedelta(0) < expires_at - reviewed_at <= MAX_REVIEW_WINDOW:
            raise ValueError("invalid validity window")
    except (ValueError, UnicodeError, ValidationError, RecursionError):
        parsed = None
    if parsed is None:
        raise AdmissionEvidenceError("INVALID_ADMISSION_MANIFEST")
    if not reviewed_at <= current < expires_at:
        raise AdmissionEvidenceError("ADMISSION_REVIEW_OUTSIDE_WINDOW")
    candidate = candidate_fingerprint(preparer)
    if ((parsed.model, parsed.modelVersion, parsed.promptVersion, parsed.candidateSha256, parsed.counterSha256)
            != (MODEL, candidate_counter_v41.MODEL_VERSION, PROMPT_VERSION, candidate, preparer.counter_sha256)):
        raise AdmissionEvidenceError("ADMISSION_CANDIDATE_MISMATCH")
    checks = {item.checkId: item for item in parsed.checks}
    result = []
    for identity, filename in CHECK_FILES.items():
        item = checks[identity]
        if item.status == "VERIFIED":
            # 固定附件仅证明受审材料未变；不自动解释条款或推断计数合同适用范围。
            _read_checked(root / filename, item.evidenceSha256, MAX_RECEIPT_BYTES, "INVALID_ADMISSION_RECEIPT")
        result.append(CheckEvidence(identity, item.status, item.evidenceSha256))
    return AdmissionAudit(candidate, MANIFEST_SHA256, expires_at, tuple(result))


def main() -> int:
    report = None
    try:
        # 此变量仅定位已经核对摘要的公开静态资源，不能注入材料或供应商凭据。
        preparer = OfflineAnalysisPreparer(Path(os.environ["AGENT_V41_COUNTER_ASSETS"]))
        audit = audit_admission_evidence(preparer)
        report = {"status": audit.status, "businessAvailable": audit.business_available,
                  "candidateSha256": audit.candidate_sha256, "manifestSha256": audit.manifest_sha256,
                  "missingChecks": audit.missing_checks}
    except Exception:
        # 本地诊断失败不打印任何异常链或环境路径。
        pass
    if report is None:
        print('{"status":"ADMISSION_AUDIT_FAILED","businessAvailable":false}')
        return 2
    print(json.dumps(report, separators=(",", ":")))
    return 2 if audit.missing_checks else 0


if __name__ == "__main__":
    sys.exit(main())
