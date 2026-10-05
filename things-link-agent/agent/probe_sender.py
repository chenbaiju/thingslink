"""One fixed synthetic request per call; never accept business data or a target URL."""
from __future__ import annotations

import http.client
import socket
import queue
import threading
import json
import re
import ssl
import time
from datetime import UTC, datetime
from uuid import UUID

from agent.probe_counter import SyntheticProbeCounter
from agent.v41_probe_counter import V41ProbeCounter
from agent.token_probe import ProbeError, compare_probe_response, synthetic_probe_requests


class DeepSeekProbeSender:
    def _connect(self, deadline: float):
        until = min(deadline, time.monotonic() + 5)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_default_certs()
        resolved = queue.Queue(maxsize=1)
        def lookup():
            try:
                resolved.put(socket.getaddrinfo('api.deepseek.com',443,type=socket.SOCK_STREAM)[0])
            except OSError:
                resolved.put(None)
        worker = threading.Thread(target=lookup,daemon=True)
        worker.start()
        try:
            address = resolved.get(timeout=_remaining(until))
        except queue.Empty:
            raise TimeoutError() from None
        if address is None:
            raise OSError('SUPPLIER_CONNECT_FAILED')
        family, kind, protocol, _, target = address
        raw = socket.socket(family,kind,protocol)
        peer = None
        try:
            raw.settimeout(_remaining(until))
            raw.connect(target)
            raw.settimeout(_remaining(until))
            peer = context.wrap_socket(raw,server_hostname='api.deepseek.com')
            _remaining(until)
            return peer
        except Exception:
            # SSL包装后连接归peer持有；不能只关闭已转移的原始对象。
            if peer is not None:
                peer.close()
            else:
                raw.close()
            raise

    def send(self, body: bytes, credential: str, deadline: float) -> tuple[int, bytes]:
        peer = self._connect(deadline)
        response = None
        timer = None
        expired = threading.Event()
        until = min(deadline, time.monotonic() + 45)
        def expire():
            expired.set()
            try:
                peer.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            peer.close()
        try:
            timer = threading.Timer(_remaining(until),expire)
            timer.daemon = True
            timer.start()
            model_deadline = until
            peer.settimeout(_remaining(model_deadline))
            headers = ('POST /chat/completions HTTP/1.1\r\nHost: api.deepseek.com\r\n'
                       'Content-Type: application/json\r\nAccept: application/json\r\n'
                       'Accept-Encoding: identity\r\nConnection: close\r\nContent-Length: ' + str(len(body)) +
                       '\r\nAuthorization: Bearer ' + credential + '\r\n\r\n').encode('ascii')
            peer.sendall(headers)
            peer.sendall(body)
            del headers
            # HTTPResponse parses framing only; this object cannot retry or open another connection.
            response = http.client.HTTPResponse(peer)
            response.fp = _BoundedResponseFile(response.fp)
            response.begin()
            if response.status != 200:
                _remaining(model_deadline)
                return response.status, b''
            if response.getheader('Content-Encoding', 'identity') != 'identity':
                raise ProbeError('INVALID_PROBE_RESPONSE')
            result = bytearray()
            while True:
                peer.settimeout(_remaining(model_deadline))
                chunk = response.read1(min(4096, 16385 - len(result)))
                if not chunk:
                    break
                result.extend(chunk)
                if len(result) > 16384:
                    raise ProbeError('PROBE_RESPONSE_TOO_LARGE')
            _remaining(model_deadline)
            return response.status, bytes(result)
        except (OSError, http.client.HTTPException, ValueError):
            # 截止主动关闭可能呈现不同TLS异常；截止前错误不被改写。
            if expired.is_set() or time.monotonic() >= until:
                raise TimeoutError() from None
            raise
        finally:
            if timer is not None:
                timer.cancel()
            if response is not None:
                response.close()
            peer.close()


class _BoundedResponseFile:
    """Bound raw HTTP headers/framing/body as well as the decoded JSON body."""
    def __init__(self, source):
        self.source, self.remaining = source, 20 * 1024
    def _read(self, method, size=-1):
        limit = self.remaining + 1 if size < 0 else min(size,self.remaining+1)
        raw = getattr(self.source,method)(limit)
        self.remaining -= len(raw)
        if self.remaining < 0:
            raise ProbeError('PROBE_RESPONSE_TOO_LARGE')
        return raw
    def readline(self,size=-1):return self._read('readline',size)
    def read(self,size=-1):return self._read('read',size)
    def read1(self,size=-1):return self._read('read1',size)
    def readinto(self,buffer):
        raw=self.read(len(buffer));buffer[:len(raw)]=raw;return len(raw)
    def close(self):self.source.close()
    def __getattr__(self,name):return getattr(self.source,name)


def _remaining(deadline: float) -> float:
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError()
    return remaining


def execute_probe(document: bytes, credential: str, counter: SyntheticProbeCounter | V41ProbeCounter,
                  sender: DeepSeekProbeSender) -> dict:
    """Called only behind dedicated mTLS. All exceptions/content become fixed metadata."""
    entered_monotonic = time.monotonic()
    entered_utc = datetime.now(UTC).timestamp()
    failure = None
    parameters = None
    try:
        if len(document) > 1024 or type(credential) is not str or re.fullmatch(r'[!-~]{1,4096}', credential) is None:
            raise ValueError()
        def closed(pairs):
            result = {}
            for k, v in pairs:
                if k in result:
                    raise ValueError()
                result[k] = v
            return result
        parameters = json.loads(document, object_pairs_hook=closed)
        if type(parameters) is not dict or set(parameters) != {'attemptId', 'sampleIndex', 'manifestSha256', 'deadlineEpochMillis'}:
            raise ValueError()
        if type(parameters['sampleIndex']) is not int or parameters['sampleIndex'] not in (1, 2, 3):
            raise ValueError()
        if str(UUID(parameters['attemptId'])) != parameters['attemptId'] or parameters['manifestSha256'] != counter.manifest_sha256():
            raise ValueError()
        if type(parameters['deadlineEpochMillis']) is not int:
            raise ValueError()
        remaining = parameters['deadlineEpochMillis'] / 1000 - entered_utc
        if not 0 < remaining <= 60:
            raise ValueError()
    except (ValueError, TypeError, KeyError, RecursionError, UnicodeError):
        failure = 'INVALID_INTERNAL_PROBE'
    if failure:
        raise ProbeError(failure)
    assert parameters is not None
    deadline = entered_monotonic + remaining
    probe = synthetic_probe_requests()[parameters['sampleIndex'] - 1]
    measurement = counter.measure(probe)
    if time.monotonic() >= deadline:
        raise ProbeError('INTERNAL_PROBE_DEADLINE_EXCEEDED')
    result = {'sampleIndex': parameters['sampleIndex'], 'manifestSha256': counter.manifest_sha256(),
              'outcome': 'UNKNOWN', 'category': 'TRANSPORT_UNKNOWN', 'usage': None}
    try:
        status, raw = sender.send(probe.body, credential, deadline)
        if status != 200:
            result.update(outcome='FAILED', category=('SUPPLIER_AUTH' if status == 401 else
                          'SUPPLIER_BALANCE' if status == 402 else 'SUPPLIER_RATE_LIMIT' if status == 429 else 'SUPPLIER_REJECTED'))
        else:
            observation = compare_probe_response(probe, raw, measurement)
            result.update(outcome='SUCCEEDED', category='COUNT_MATCH' if observation.matched else 'COUNT_MISMATCH', usage={
                'localInputTokens': measurement.input_tokens, 'promptTokens': observation.prompt_tokens,
                'completionTokens': observation.completion_tokens, 'totalTokens': observation.total_tokens,
                'cacheHitTokens': observation.cache_hit_tokens, 'cacheMissTokens': observation.cache_miss_tokens,
                'delta': observation.delta, 'finishReason': observation.finish_reason,
                'implementationSha256': measurement.implementation_sha256, 'assetsSha256': measurement.assets_sha256,
                'requestSha256': probe.request_sha256, 'messagesSha256': probe.messages_sha256,
                'backendFingerprintSha256': observation.backend_fingerprint_sha256,
            })
    except ProbeError:
        result.update(outcome='FAILED', category='INVALID_USAGE')
    except (OSError, TimeoutError, http.client.HTTPException, ssl.SSLError):
        pass
    return result
