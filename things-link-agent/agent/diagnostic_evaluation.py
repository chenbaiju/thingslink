"""固定合成案例与人工声明的评审完整性；不自动判断语义、不授予线上资格。"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

from agent import analysis_response
from agent.analysis_preparation import OfflineAnalysisPreparer, PreparedAnalysis
from agent.analysis_response import OfflineAnalysisResult
from agent.model_request import build_model_request
from agent.result_validation import validate_analysis_result

VERSION = "diagnosis-quality-v1"
CATALOG_SHA = "4fa2fdb2244dc41056cfb0de2bb2709f64f3ddb878728bca445a08b18cad346b"


class EvaluationError(ValueError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, slots=True)
class Criterion:
    criterion_id: str
    kind: str
    instruction: str


@dataclass(frozen=True, slots=True, repr=False)
class DiagnosticCase:
    case_id: str
    input: bytes
    criteria: tuple[Criterion, ...]
    example_output: bytes

    def __repr__(self):
        return f"DiagnosticCase[synthetic,case={self.case_id}]"


@lru_cache(maxsize=1)
def diagnostic_cases() -> tuple[DiagnosticCase, ...]:
    encoded = None
    try:
        with Path(__file__).with_name("diagnostic_cases.json").open("rb") as source:
            raw = source.read(32769)
        if len(raw) <= 32768 and hashlib.sha256(raw).hexdigest() == CATALOG_SHA:
            encoded = raw
    except OSError:
        pass
    if encoded is None:
        raise EvaluationError("INVALID_CASE_CATALOG")
    document = json.loads(encoded)
    assert document["version"] == VERSION
    cases = []
    for item in document["cases"]:
        evidence = json.dumps(item["input"], ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode()
        draft = build_model_request(evidence)
        example = json.dumps(item["exampleOutput"], ensure_ascii=False, separators=(",", ":")).encode()
        validate_analysis_result(example, evidence_ids=draft.evidence_ids)
        cases.append(DiagnosticCase(item["id"], evidence, tuple(
            Criterion(rule["id"], rule["kind"], rule["instruction"]) for rule in item["criteria"]), example))
    return tuple(cases)


@dataclass(frozen=True, slots=True)
class EvaluationTarget:
    case_id: str
    candidate_sha256: str
    request_sha256: str
    output_sha256: str


def evaluation_target(case: DiagnosticCase, *, prepared: PreparedAnalysis,
                      result: OfflineAnalysisResult, preparer: OfflineAnalysisPreparer) -> EvaluationTarget:
    preparer.validate(prepared)
    if (type(case) is not DiagnosticCase or case not in diagnostic_cases()
            or prepared.draft.messages[1][1].encode() != case.input
            or type(result) is not OfflineAnalysisResult or result.status != "VALIDATED"
            or result.content is None or result.request_sha256 != prepared.request_sha256
            or result.counter_sha256 != prepared.counter_sha256
            or result.usage is None or result.usage.prompt_tokens != prepared.input_tokens):
        raise EvaluationError("INVALID_EVALUATION_BINDING")
    output = json.dumps(result.content.model_dump(mode="json", by_alias=True),
                        ensure_ascii=False, separators=(",", ":")).encode()
    # 候选绑定代码、模板、计数资源及供应商指纹摘要，不把别名当不可变在线版本。
    binding = [VERSION, CATALOG_SHA, prepared.counter_sha256, result.model, result.prompt_version,
               result.provider_fingerprint_sha256,
               hashlib.sha256(Path(analysis_response.__file__).read_bytes()).hexdigest(),
               hashlib.sha256(Path(__file__).read_bytes()).hexdigest()]
    candidate = hashlib.sha256("\n".join(binding).encode("ascii")).hexdigest()
    return EvaluationTarget(case.case_id, candidate, prepared.request_sha256, hashlib.sha256(output).hexdigest())


def _case(target: EvaluationTarget) -> DiagnosticCase:
    if (type(target) is not EvaluationTarget or any(type(value) is not str or
            re.fullmatch(r"[0-9a-f]{64}", value) is None for value in
            (target.candidate_sha256, target.request_sha256, target.output_sha256))):
        raise EvaluationError("INVALID_EVALUATION_BINDING")
    case = next((case for case in diagnostic_cases() if case.case_id == target.case_id), None)
    if case is None:
        raise EvaluationError("INVALID_EVALUATION_BINDING")
    return case


def review_template(target: EvaluationTarget) -> dict:
    case = _case(target)
    return {"version": VERSION, "caseId": target.case_id, "candidateSha256": target.candidate_sha256,
            "requestSha256": target.request_sha256, "outputSha256": target.output_sha256, "reviewer": "",
            "checks": [{"criterionId": rule.criterion_id, "verdict": "UNREVIEWED", "note": ""}
                       for rule in case.criteria]}


class _Closed(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True, hide_input_in_errors=True,
                             validate_by_alias=True, validate_by_name=False)


class _Check(_Closed):
    criterion_id: str = Field(alias="criterionId")
    verdict: Literal["PASS", "FAIL", "UNREVIEWED"]
    note: Annotated[str, StringConstraints(max_length=500)] = Field(repr=False)


Sha = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


class _Review(_Closed):
    version: Literal["diagnosis-quality-v1"]
    case_id: str = Field(alias="caseId")
    candidate_sha256: Sha = Field(alias="candidateSha256")
    request_sha256: Sha = Field(alias="requestSha256")
    output_sha256: Sha = Field(alias="outputSha256")
    reviewer: Annotated[str, StringConstraints(max_length=64)] = Field(repr=False)
    checks: tuple[_Check, ...] = Field(min_length=1, max_length=16, repr=False)


@dataclass(frozen=True, slots=True, repr=False)
class CaseReview:
    target: EvaluationTarget
    record: _Review

    @property
    def status(self) -> str:
        if any(check.verdict == "FAIL" for check in self.record.checks):
            return "REVIEW_FAILED"
        if not self.record.reviewer.strip() or any(
                check.verdict != "PASS" or not check.note.strip() for check in self.record.checks):
            return "REVIEW_PENDING"
        return "REVIEW_ACCEPTED"

    def __repr__(self):
        return f"CaseReview[human-declared,case={self.target.case_id},status={self.status}]"


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate review member")
        result[key] = value
    return result


def _constant(_value):
    raise ValueError("nonstandard review value")


def record_review(raw: bytes, *, target: EvaluationTarget) -> CaseReview:
    case = _case(target)
    if type(raw) is not bytes or len(raw) > 16384:
        raise EvaluationError("INVALID_REVIEW")
    review = None
    try:
        json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_object, parse_constant=_constant)
        review = _Review.model_validate_json(raw)
    except (ValueError, UnicodeError, RecursionError, ValidationError):
        pass
    if review is None:
        raise EvaluationError("INVALID_REVIEW")
    if (review.case_id != target.case_id or review.candidate_sha256 != target.candidate_sha256
            or review.request_sha256 != target.request_sha256 or review.output_sha256 != target.output_sha256
            or len(review.checks) != len(case.criteria)
            or {check.criterion_id for check in review.checks} != {rule.criterion_id for rule in case.criteria}):
        raise EvaluationError("INVALID_REVIEW_BINDING")
    return CaseReview(target, review)


def suite_status(reviews: tuple[CaseReview, ...]) -> str:
    if (type(reviews) is not tuple or len(reviews) > 4 or any(type(review) is not CaseReview for review in reviews)
            or len({review.target.case_id for review in reviews}) != len(reviews)
            or len({review.target.candidate_sha256 for review in reviews}) > 1):
        raise EvaluationError("INVALID_REVIEW_SUITE")
    # 汇总仍重验静态范围与记录绑定，不能只拼接调用方构造的状态字符串。
    for review in reviews:
        record_review(review.record.model_dump_json(by_alias=True).encode(), target=review.target)
    if any(review.status == "REVIEW_FAILED" for review in reviews):
        return "REVIEW_FAILED"
    if len(reviews) < 4 or any(review.status != "REVIEW_ACCEPTED" for review in reviews):
        return "REVIEW_PENDING"
    return "REVIEW_ACCEPTED"
