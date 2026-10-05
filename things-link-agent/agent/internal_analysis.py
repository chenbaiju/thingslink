"""内部业务报文与单调期限；只准备受控输入，不持凭据或发送模型请求。"""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import math
import re
import time
from dataclasses import dataclass
from typing import Protocol
from uuid import RFC_4122, UUID

from agent.analysis_preparation import AnalysisPreparationError, OfflineAnalysisPreparer, PreparedAnalysis
from agent.model_request import MAX_REQUEST_BYTES, ModelRequestError

VERSION = "agent-internal-analysis-v1"
MAX_INTERNAL_BYTES = 24 * 1024
MAX_LONG = (1 << 63) - 1
FIELDS = frozenset({"version", "callId", "configurationRevision", "deadlineEpochMillis", "inputSha256", "inputBase64"})


class InternalAnalysisError(ValueError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class Clock(Protocol):
    def wall_millis(self) -> int: ...
    def monotonic(self) -> float: ...


class SystemClock:
    def wall_millis(self) -> int:
        return time.time_ns() // 1_000_000

    def monotonic(self) -> float:
        return time.monotonic()


@dataclass(frozen=True, slots=True)
class AnalysisArrival:
    """服务端收到请求时捕获的时钟；读正文和线程调度都消耗原期限。"""
    started: float
    wall_millis: int

    @classmethod
    def capture(cls, clock: Clock) -> AnalysisArrival:
        return cls(_monotonic(clock), clock.wall_millis())


def _monotonic(clock: Clock) -> float:
    value = clock.monotonic()
    valid = False
    try:
        valid = type(value) in (int, float) and math.isfinite(value) and value >= 0
    except OverflowError:
        pass
    if not valid:
        raise InternalAnalysisError("INVALID_ANALYSIS_CLOCK")
    return float(value)


@dataclass(frozen=True, slots=True)
class AnalysisDeadline:
    started: float
    expires: float

    def remaining(self, clock: Clock) -> float:
        current = _monotonic(clock)
        if current < self.started:
            raise InternalAnalysisError("INVALID_ANALYSIS_CLOCK")
        remaining = self.expires - current
        if remaining <= 0:
            raise InternalAnalysisError("ANALYSIS_DEADLINE_EXCEEDED")
        return remaining


@dataclass(frozen=True, slots=True, repr=False)
class InternalAnalysisCall:
    call_id: str
    configuration_revision: int
    deadline_epoch_millis: int
    input_sha256: str
    prepared: PreparedAnalysis
    deadline: AnalysisDeadline

    def __repr__(self):
        return "InternalAnalysisCall[OFFLINE_UNQUALIFIED]"


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


def _constant(_value):
    raise ValueError("non-standard JSON constant")


def prepare_internal_analysis(raw: bytes, preparer: OfflineAnalysisPreparer,
                              *, clock: Clock | None = None,
                              arrival: AnalysisArrival | None = None) -> InternalAnalysisCall:
    """期限只在入口建一次；解析及计数之后复核，禁止重建完整60秒窗口。"""
    timer = SystemClock() if clock is None else clock
    received = AnalysisArrival.capture(timer) if arrival is None else arrival
    if type(received) is not AnalysisArrival:
        raise InternalAnalysisError("INVALID_ANALYSIS_CLOCK")
    started, wall = received.started, received.wall_millis
    if (type(started) is not float or not math.isfinite(started) or started < 0
            or _monotonic(timer) < started):
        raise InternalAnalysisError("INVALID_ANALYSIS_CLOCK")
    if type(wall) is not int or not 0 <= wall <= MAX_LONG:
        raise InternalAnalysisError("INVALID_ANALYSIS_CLOCK")
    if type(raw) is not bytes or len(raw) > MAX_INTERNAL_BYTES:
        raise InternalAnalysisError("INVALID_INTERNAL_ANALYSIS")
    value = None
    try:
        document = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=_object, parse_constant=_constant)
        if type(document) is not dict or set(document) != FIELDS or document["version"] != VERSION:
            raise ValueError("invalid envelope")
        call_id = document["callId"]
        if type(call_id) is not str:
            raise ValueError("invalid call identifier")
        identifier = UUID(call_id)
        if str(identifier) != call_id or identifier.version != 7 or identifier.variant != RFC_4122:
            raise ValueError("invalid call identifier")
        revision, end = document["configurationRevision"], document["deadlineEpochMillis"]
        if (type(revision) is not int or not 1 <= revision <= MAX_LONG
                or type(end) is not int or not 1 <= end <= MAX_LONG or end - wall > 60_000):
            raise ValueError("invalid revision or deadline")
        digest, encoded = document["inputSha256"], document["inputBase64"]
        if (type(digest) is not str or re.fullmatch(r"[0-9a-f]{64}", digest) is None
                or type(encoded) is not str or len(encoded) > ((MAX_REQUEST_BYTES + 2) // 3) * 4):
            raise ValueError("invalid input binding")
        source = base64.b64decode(encoded.encode("ascii", errors="strict"), validate=True)
        if (not 0 < len(source) <= MAX_REQUEST_BYTES or base64.b64encode(source).decode("ascii") != encoded
                or hashlib.sha256(source).hexdigest() != digest):
            raise ValueError("invalid input binding")
        value = (call_id, revision, end, digest, source)
    except (ValueError, TypeError, UnicodeError, binascii.Error, RecursionError):
        pass
    if value is None:
        raise InternalAnalysisError("INVALID_INTERNAL_ANALYSIS")
    call_id, revision, end, digest, source = value
    deadline = AnalysisDeadline(started, started + (end - wall) / 1000)
    deadline.remaining(timer)
    if type(preparer) is not OfflineAnalysisPreparer:
        raise InternalAnalysisError("INVALID_INTERNAL_ANALYSIS_INPUT")
    prepared = None
    try:
        prepared = preparer.prepare(source)
    except (AnalysisPreparationError, ModelRequestError):
        pass
    if prepared is None:
        raise InternalAnalysisError("INVALID_INTERNAL_ANALYSIS_INPUT")
    deadline.remaining(timer)
    return InternalAnalysisCall(call_id, revision, end, digest, prepared, deadline)
