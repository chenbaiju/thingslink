"""默认拒绝的单次业务传输组件；独立裁决和本次绑定均通过才允许发送。"""
from __future__ import annotations

from dataclasses import replace
import http.client
import ipaddress
import queue
import re
import socket
import ssl
import threading

from agent.admission_evidence import AdmissionEvidenceError, audit_admission_evidence
from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.analysis_response import OfflineAnalysisResult, parse_analysis_response
from agent.release_review import ReviewedExecution, ReleaseReviewError
from agent.internal_analysis import AnalysisDeadline, Clock, InternalAnalysisCall, SystemClock, _monotonic

_DNS_SLOTS = threading.BoundedSemaphore(2)


class AnalysisTransportError(ValueError):
    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


class _BoundedFile:
    """同时限制HTTP原始头、分帧和正文；不依赖响应Content-Length声明。"""
    def __init__(self, source):
        self.source, self.remaining = source, 20 * 1024

    def _read(self, method, size=-1):
        limit = self.remaining + 1 if size < 0 else min(size, self.remaining + 1)
        raw = getattr(self.source, method)(limit)
        self.remaining -= len(raw)
        if self.remaining < 0:
            raise AnalysisTransportError("MODEL_RESPONSE_TOO_LARGE")
        return raw

    def readline(self, size=-1): return self._read("readline", size)
    def read(self, size=-1): return self._read("read", size)
    def read1(self, size=-1): return self._read("read1", size)
    def readinto(self, target):
        raw = self.read(len(target))
        target[:len(raw)] = raw
        return len(raw)
    def close(self): self.source.close()
    def __getattr__(self, name): return getattr(self.source, name)


class GuardedSingleAnalysisTransport:
    """每个对象只执行一次；真正跨进程认领仍由Java持久记录提供。"""
    def __init__(self, call: InternalAnalysisCall, preparer: OfflineAnalysisPreparer, *, clock: Clock | None = None, approval: ReviewedExecution | None = None):
        if type(call) is not InternalAnalysisCall or type(preparer) is not OfflineAnalysisPreparer:
            raise AnalysisTransportError("INVALID_ANALYSIS_TRANSPORT")
        if approval is not None and type(approval) is not ReviewedExecution:
            raise AnalysisTransportError("INVALID_ANALYSIS_TRANSPORT")
        self._approval = approval
        self._call, self._preparer = call, preparer
        self._clock = SystemClock() if clock is None else clock
        self._lock, self._used = threading.Lock(), False
        self._context = ssl.create_default_context()
        self._context.minimum_version = ssl.TLSVersion.TLSv1_2

    def __repr__(self):
        return "GuardedSingleAnalysisTransport[used]" if self._used else "GuardedSingleAnalysisTransport[unused]"

    def _phase(self, seconds: float) -> AnalysisDeadline:
        self._call.deadline.remaining(self._clock)
        return AnalysisDeadline(self._call.deadline.started,
                                min(self._call.deadline.expires, _monotonic(self._clock) + seconds))

    def _connect(self):
        phase = self._phase(5)
        if not _DNS_SLOTS.acquire(blocking=False):
            raise AnalysisTransportError("MODEL_DNS_BUSY")
        resolved = queue.Queue(maxsize=1)
        def lookup():
            address = None
            try:
                for candidate in socket.getaddrinfo("api.deepseek.com", 443, type=socket.SOCK_STREAM):
                    address_ip = ipaddress.ip_address(candidate[4][0])
                    if address_ip.is_global and not address_ip.is_multicast and not address_ip.is_reserved:
                        address = candidate
                        break
            except Exception:
                pass
            finally:
                resolved.put(address)
                _DNS_SLOTS.release()
        worker = threading.Thread(target=lookup, daemon=True)
        try:
            worker.start()
        except Exception:
            _DNS_SLOTS.release()
            raise
        address = resolved.get(timeout=phase.remaining(self._clock))
        if address is None:
            raise AnalysisTransportError("MODEL_CONNECT_FAILED")
        family, kind, protocol, _, target = address
        raw = socket.socket(family, kind, protocol)
        peer = None
        try:
            raw.settimeout(phase.remaining(self._clock))
            raw.connect(target)
            raw.settimeout(phase.remaining(self._clock))
            peer = self._context.wrap_socket(raw, server_hostname="api.deepseek.com")
            phase.remaining(self._clock)
            return peer
        except Exception:
            if peer is not None: peer.close()
            else: raw.close()
            raise

    def _exchange(self, credential: bytearray) -> tuple[int, bytes]:
        peer = self._connect()
        response = timer = None
        try:
            phase = self._phase(45)
            def expire():
                try: peer.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                peer.close()
            timer = threading.Timer(phase.remaining(self._clock), expire)
            timer.daemon = True
            timer.start()
            peer.settimeout(phase.remaining(self._clock))
            body = self._call.prepared.draft.body
            headers = ("POST /chat/completions HTTP/1.1\r\nHost: api.deepseek.com\r\n"
                       "Content-Type: application/json\r\nAccept: application/json\r\nAccept-Encoding: identity\r\n"
                       "Connection: close\r\nContent-Length: " + str(len(body)) + "\r\nAuthorization: Bearer ").encode("ascii")
            peer.sendall(headers)
            peer.sendall(memoryview(credential))
            peer.sendall(b"\r\n\r\n")
            peer.sendall(body)
            response = http.client.HTTPResponse(peer)
            response.fp = _BoundedFile(response.fp)
            response.begin()
            if response.status != 200:
                return response.status, b""
            if any(len(response.headers.get_all(name, [])) > 1
                   for name in ("Content-Length", "Content-Encoding", "Transfer-Encoding")):
                raise AnalysisTransportError("INVALID_MODEL_RESPONSE")
            length = response.getheader("Content-Length")
            framing = response.getheader("Transfer-Encoding")
            if (response.getheader("Content-Encoding", "identity").strip().lower() != "identity"
                    or (framing is not None and (framing.strip().lower() != "chunked" or length is not None))
                    or (length is not None and re.fullmatch(r"[0-9]{1,6}", length) is None)):
                raise AnalysisTransportError("INVALID_MODEL_RESPONSE")
            if length is not None and int(length) > 16384:
                raise AnalysisTransportError("MODEL_RESPONSE_TOO_LARGE")
            result = bytearray()
            while True:
                peer.settimeout(phase.remaining(self._clock))
                chunk = response.read1(min(4096, 16385 - len(result)))
                if not chunk: break
                result.extend(chunk)
                if len(result) > 16384:
                    raise AnalysisTransportError("MODEL_RESPONSE_TOO_LARGE")
            if response.length not in (None, 0):
                raise AnalysisTransportError("INVALID_MODEL_RESPONSE")
            phase.remaining(self._clock)
            return response.status, bytes(result)
        finally:
            try:
                if response is not None: response.close()
            finally:
                try:
                    peer.close()
                finally:
                    if timer is not None:
                        timer.cancel()
                        timer.join(timeout=1)
                        if timer.is_alive():
                            raise AnalysisTransportError("MODEL_TRANSPORT_UNKNOWN")

    def send(self, credential: bytearray) -> OfflineAnalysisResult:
        """核对后只发送一次，所有退出路径清除传入数组；固定裁决待审时拒绝真实发送。"""
        failure, result, response = None, None, None
        try:
            with self._lock:
                if self._used:
                    raise AnalysisTransportError("ANALYSIS_TRANSPORT_ALREADY_USED")
                self._used = True
            if type(credential) is not bytearray or re.fullmatch(rb"[!-~]{1,4096}", credential) is None:
                raise AnalysisTransportError("INVALID_MODEL_CREDENTIAL")
            self._call.deadline.remaining(self._clock)
            self._preparer.validate(self._call.prepared)
            # 只有独立固定裁决对象可进入受审路径；无裁决的旧路径保持拒绝。
            if self._approval is not None:
                self._approval.verify(self._call, self._clock)
                self._call = replace(self._call, deadline=self._approval.deadline)
            elif not audit_admission_evidence(self._preparer).business_available:
                raise AnalysisTransportError("MODEL_BUSINESS_UNAVAILABLE")
            self._call.deadline.remaining(self._clock)
            status, response = self._exchange(credential)
            self._call.deadline.remaining(self._clock)
            if status != 200:
                raise AnalysisTransportError({401: "MODEL_SUPPLIER_AUTH", 402: "MODEL_SUPPLIER_BALANCE",
                    429: "MODEL_SUPPLIER_RATE_LIMIT"}.get(status, "MODEL_SUPPLIER_REJECTED"))
            result = parse_analysis_response(response, prepared=self._call.prepared, preparer=self._preparer)
            if self._approval is not None:
                self._approval.verify(self._call, self._clock, result)
            self._call.deadline.remaining(self._clock)
        except AnalysisTransportError as error:
            failure = error.code
        except (AdmissionEvidenceError, ReleaseReviewError):
            failure = "MODEL_BUSINESS_UNAVAILABLE"
        except Exception:
            failure = "MODEL_TRANSPORT_UNKNOWN"
        finally:
            if type(credential) is bytearray: credential[:] = b"\0" * len(credential)
            credential, response = None, None
        if failure is not None:
            raise AnalysisTransportError(failure)
        return result
