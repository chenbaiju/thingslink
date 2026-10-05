"""Validate untrusted model content without logging or retaining failed payloads.

This module does not call models, acquire evidence, or authorize users. A trusted
caller supplies the evidence identifiers for this invocation. Reference membership
is a structural check and cannot prove that a statement is supported by evidence.
"""

from __future__ import annotations

import json
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, ValidationError

MAX_RESULT_BYTES = 16 * 1024
Text = Annotated[str, StringConstraints(min_length=1)]


class ResultErrorCode(StrEnum):
    TOO_LARGE = "RESULT_TOO_LARGE"
    INVALID_JSON = "INVALID_RESULT_JSON"
    INVALID_STRUCTURE = "INVALID_RESULT_STRUCTURE"
    INVALID_REFERENCES = "INVALID_EVIDENCE_REFERENCES"


class AnalysisResultError(ValueError):
    """Only the fixed error code crosses the validation boundary."""

    def __init__(self, code: ResultErrorCode) -> None:
        self.code = code
        super().__init__(code.value)


class _ClosedContent(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        strict=True,
        frozen=True,
        hide_input_in_errors=True,
        validate_by_alias=True,
        validate_by_name=False,
    )


class Finding(_ClosedContent):
    kind: Literal["FACT", "HYPOTHESIS", "RECOMMENDATION"]
    statement: Text = Field(repr=False)
    evidence_ids: tuple[Text, ...] = Field(alias="evidenceIds", min_length=1, repr=False)


class AnalysisContent(_ClosedContent):
    summary: Text = Field(repr=False)
    findings: tuple[Finding, ...] = Field(repr=False)
    limitations: tuple[Text, ...] = Field(repr=False)


def _unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON member")
        result[key] = value
    return result


def _reject_constant(_value: str) -> None:
    raise ValueError("non-standard JSON constant")


def validate_analysis_result(
    raw: str | bytes, *, evidence_ids: frozenset[str]
) -> AnalysisContent:
    """Return immutable validated content, or a payload-free error.

    Call this boundary rather than exposing Pydantic ValidationError to callers.
    Evidence IDs must come from this run's platform evidence, never from the model.
    Do not use the byte limit as an input/output token counter.
    """
    if type(evidence_ids) is not frozenset or any(
        type(identifier) is not str or not identifier for identifier in evidence_ids
    ):
        raise ValueError("invalid trusted evidence scope")

    error: ResultErrorCode | None = None
    encoded = b""
    try:
        if type(raw) is str:
            encoded = raw.encode("utf-8", errors="strict")
        elif type(raw) is bytes:
            encoded = raw
        else:
            error = ResultErrorCode.INVALID_JSON
    except UnicodeError:
        error = ResultErrorCode.INVALID_JSON
    # Raise outside except blocks: even __context__ must not retain raw input.
    if error is not None:
        raise AnalysisResultError(error)
    if len(encoded) > MAX_RESULT_BYTES:
        raise AnalysisResultError(ResultErrorCode.TOO_LARGE)

    try:
        decoded = encoded.decode("utf-8", errors="strict")
        # Pydantic's JSON reader alone accepts duplicate members. Check the
        # complete bounded document before strict schema validation.
        document = json.loads(decoded, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
    except (ValueError, UnicodeError, RecursionError):
        error = ResultErrorCode.INVALID_JSON
    if error is not None:
        raise AnalysisResultError(error)

    # Explicit wire names also reject an internal Python field name alongside
    # its JSON alias; schema configuration alone must not define this boundary.
    if not isinstance(document, dict) or set(document) != {"summary", "findings", "limitations"}:
        raise AnalysisResultError(ResultErrorCode.INVALID_STRUCTURE)
    if not isinstance(document["findings"], list) or any(
        not isinstance(finding, dict)
        or set(finding) != {"kind", "statement", "evidenceIds"}
        for finding in document["findings"]
    ):
        raise AnalysisResultError(ResultErrorCode.INVALID_STRUCTURE)

    content: AnalysisContent | None = None
    try:
        # JSON arrays become tuples without allowing Python type coercions;
        # nested result containers cannot later be edited to change references.
        content = AnalysisContent.model_validate_json(decoded)
    except (ValidationError, ValueError, RecursionError):
        error = ResultErrorCode.INVALID_STRUCTURE
    if error is not None:
        raise AnalysisResultError(error)
    assert content is not None
    if any(
        identifier not in evidence_ids
        for finding in content.findings
        for identifier in finding.evidence_ids
    ):
        raise AnalysisResultError(ResultErrorCode.INVALID_REFERENCES)
    return content
