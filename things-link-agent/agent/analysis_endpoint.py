"""专用内部分析入口；生产传输仍默认拒绝，无供应商放行开关。"""
from __future__ import annotations

import asyncio
import re
import threading

from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from agent.analysis_execution import AnalysisExecutionError, SingleInternalAnalysisExecution
from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.internal_analysis import AnalysisArrival, MAX_INTERNAL_BYTES, SystemClock


class InternalAnalysisEndpoint:
    """两槽无等待队列；进入工作线程后由线程持有凭据和槽直到真实退出。"""
    def __init__(self, preparer: OfflineAnalysisPreparer | None):
        if preparer is not None and type(preparer) is not OfflineAnalysisPreparer:
            raise ValueError("INVALID_ANALYSIS_PREPARER")
        self._preparer = preparer
        self._slots = threading.BoundedSemaphore(2)

    @staticmethod
    def _error(status: int, code: str) -> JSONResponse:
        return JSONResponse({"error": code}, status_code=status,
                            headers={"Cache-Control": "no-store"})

    async def handle(self, request: Request) -> Response:
        credential = bytearray()
        document = bytearray()
        held, transferred = False, False
        try:
            clock = SystemClock()
            arrival = AnalysisArrival.capture(clock)
            # 先移出ASGI作用域，避免后续框架错误携带专用凭据头；不接收用户Token。
            headers = request.scope["headers"]
            keys = [value for name, value in headers if name.lower() == b"x-model-credential"]
            request.scope["headers"] = [(name, value) for name, value in headers
                                        if name.lower() != b"x-model-credential"]
            if len(keys) == 1:
                credential.extend(keys[0])
            keys.clear()
            del headers
            if self._preparer is None:
                return self._error(503, "MODEL_BUSINESS_UNAVAILABLE")
            if (request.scope.get("query_string") or len(credential) == 0
                    or re.fullmatch(rb"[!-~]{1,4096}", credential) is None
                    or request.headers.getlist("content-type") != ["application/json"]
                    or request.headers.getlist("content-encoding") not in ([], ["identity"])):
                return self._error(400, "INVALID_INTERNAL_ANALYSIS")
            reviews = request.headers.getlist("x-analysis-review")
            if len(reviews) > 1 or (reviews and re.fullmatch(r"[0-9a-f]{64}", reviews[0]) is None):
                return self._error(400, "INVALID_INTERNAL_ANALYSIS")
            review = reviews[0] if reviews else None
            lengths = request.headers.getlist("content-length")
            if (len(lengths) > 1 or (lengths and (re.fullmatch(r"[0-9]{1,5}", lengths[0]) is None
                                                 or int(lengths[0]) > MAX_INTERNAL_BYTES))):
                return self._error(400, "INVALID_INTERNAL_ANALYSIS")
            if not self._slots.acquire(blocking=False):
                return self._error(429, "INTERNAL_ANALYSIS_BUSY")
            held = True
            async with asyncio.timeout(5):
                async for chunk in request.stream():
                    if len(document) + len(chunk) > MAX_INTERNAL_BYTES:
                        return self._error(413, "INTERNAL_ANALYSIS_TOO_LARGE")
                    document.extend(chunk)
            if not document or (lengths and len(document) != int(lengths[0])):
                return self._error(400, "INVALID_INTERNAL_ANALYSIS")
            loop = asyncio.get_running_loop()
            future = loop.create_future()

            def finish(response):
                if not future.done():
                    future.set_result(response)

            def execute():
                try:
                    options = dict(clock=clock, arrival=arrival)
                    if review is not None:
                        options["review_sha256"] = review
                    execution = SingleInternalAnalysisExecution(bytes(document), self._preparer, **options)
                    response = Response(execution.execute(credential), media_type="application/json",
                                        headers={"Cache-Control": "no-store"})
                except AnalysisExecutionError as error:
                    status, code = (503, "MODEL_BUSINESS_UNAVAILABLE") if error.code == "MODEL_BUSINESS_UNAVAILABLE" \
                        else (400, "INVALID_INTERNAL_ANALYSIS") if error.code.startswith("INVALID_INTERNAL_ANALYSIS") \
                        else (504, "ANALYSIS_DEADLINE_EXCEEDED") if error.code == "ANALYSIS_DEADLINE_EXCEEDED" \
                        else (502, "INTERNAL_ANALYSIS_FAILED")
                    response = self._error(status, code)
                except Exception:
                    response = self._error(502, "INTERNAL_ANALYSIS_FAILED")
                finally:
                    credential[:] = b"\0" * len(credential)
                    document.clear()
                    self._slots.release()
                try:
                    loop.call_soon_threadsafe(finish, response)
                except RuntimeError:
                    pass

            # start成功后所有清理归线程；取消HTTP等待不能释放仍在执行的槽。
            worker = threading.Thread(target=execute, daemon=True, name="agent-single-analysis")
            worker.start()
            transferred = True
            try:
                return await asyncio.shield(future)
            except asyncio.CancelledError:
                future.cancel()
                raise
        except TimeoutError:
            return self._error(408, "INTERNAL_ANALYSIS_BODY_TIMEOUT")
        except Exception:
            return self._error(400, "INVALID_INTERNAL_ANALYSIS")
        finally:
            if not transferred:
                credential[:] = b"\0" * len(credential)
                document.clear()
                if held:
                    self._slots.release()
