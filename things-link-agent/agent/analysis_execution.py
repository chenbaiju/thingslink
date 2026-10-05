"""固定内部调用的一次执行对象；离线组合不授予业务出站、质量或持久幂等资格。"""
from __future__ import annotations

import threading

from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.analysis_transport import AnalysisTransportError, GuardedSingleAnalysisTransport
from agent.internal_analysis import AnalysisArrival, Clock, InternalAnalysisError, SystemClock, prepare_internal_analysis
from agent.release_review import load_reviewed_execution, ReleaseReviewError
from agent.internal_result import encode_internal_result

_TRANSPORT_CODES = frozenset({
    "ANALYSIS_TRANSPORT_ALREADY_USED", "INVALID_MODEL_CREDENTIAL", "MODEL_BUSINESS_UNAVAILABLE",
    "MODEL_DNS_BUSY", "MODEL_CONNECT_FAILED", "MODEL_RESPONSE_TOO_LARGE", "INVALID_MODEL_RESPONSE",
    "MODEL_SUPPLIER_AUTH", "MODEL_SUPPLIER_BALANCE", "MODEL_SUPPLIER_RATE_LIMIT",
    "MODEL_SUPPLIER_REJECTED", "MODEL_TRANSPORT_UNKNOWN",
})
_ENTRY_CODES = frozenset({"INVALID_INTERNAL_ANALYSIS", "INVALID_INTERNAL_ANALYSIS_INPUT",
                          "INVALID_ANALYSIS_CLOCK", "ANALYSIS_DEADLINE_EXCEEDED"})
_INTERNAL_CODES = frozenset({"ANALYSIS_DEADLINE_EXCEEDED", "INVALID_ANALYSIS_CLOCK",
                             "INVALID_INTERNAL_ANALYSIS_RESULT"})


class AnalysisExecutionError(ValueError):
    """仅含固定执行错误，不保留原始证据、异常链或供应商正文。"""
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class SingleInternalAnalysisExecution:
    """只持本次受控准备与原期限；Java持久认领是跨对象/进程执行权威。"""
    def __init__(self, raw: bytes, preparer: OfflineAnalysisPreparer, *, clock: Clock | None = None,
                 arrival: AnalysisArrival | None = None, review_sha256: str | None = None):
        failure = None
        try:
            timer = SystemClock() if clock is None else clock
            call = prepare_internal_analysis(raw, preparer, clock=timer, arrival=arrival)
            approval = None if review_sha256 is None else load_reviewed_execution(call, preparer, review_sha256, timer)
            transport = GuardedSingleAnalysisTransport(call, preparer, clock=timer) if approval is None else \
                GuardedSingleAnalysisTransport(call, preparer, clock=timer, approval=approval)
            call.deadline.remaining(timer)
        except ReleaseReviewError:
            failure = "MODEL_BUSINESS_UNAVAILABLE"
        except InternalAnalysisError as error:
            failure = error.code if error.code in _ENTRY_CODES else "ANALYSIS_EXECUTION_UNKNOWN"
        except Exception:
            failure = "ANALYSIS_EXECUTION_UNKNOWN"
        if failure is not None:
            raise AnalysisExecutionError(failure)
        self._clock, self._call, self._preparer = timer, call, preparer
        self._transport, self._approval = transport, approval
        self._lock, self._used = threading.Lock(), False

    def __repr__(self):
        return "SingleInternalAnalysisExecution[内容已隐藏,used]" if self._used else \
            "SingleInternalAnalysisExecution[内容已隐藏,unused]"

    def execute(self, credential: bytearray) -> bytes:
        """执行后清零凭据，任何退出都消费对象；不重试、不续期、不注册服务路由。"""
        encoded, failure = None, None
        try:
            with self._lock:
                if self._used:
                    raise AnalysisExecutionError("ANALYSIS_EXECUTION_ALREADY_USED")
                self._used = True
            self._call.deadline.remaining(self._clock)
            result = self._transport.send(credential)
            self._call.deadline.remaining(self._clock)
            encoded = encode_internal_result(self._call, result, self._preparer) if self._approval is None else \
                encode_internal_result(self._call, result, self._preparer, approval=self._approval, clock=self._clock)
            self._call.deadline.remaining(self._clock)
        except AnalysisExecutionError:
            failure = "ANALYSIS_EXECUTION_ALREADY_USED"
        except AnalysisTransportError as error:
            failure = error.code if error.code in _TRANSPORT_CODES else "ANALYSIS_EXECUTION_UNKNOWN"
        except InternalAnalysisError as error:
            failure = error.code if error.code in _INTERNAL_CODES else "ANALYSIS_EXECUTION_UNKNOWN"
        except Exception:
            failure = "ANALYSIS_EXECUTION_UNKNOWN"
        finally:
            if type(credential) is bytearray:
                credential[:] = b"\0" * len(credential)
            credential = None
        if failure is not None:
            raise AnalysisExecutionError(failure)
        return encoded
