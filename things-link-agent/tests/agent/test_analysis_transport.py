import json
import socket
import ssl
import threading
import time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace

import pytest

import agent.analysis_transport as module
from agent.analysis_transport import AnalysisTransportError, GuardedSingleAnalysisTransport
from agent.internal_analysis import prepare_internal_analysis
from test_analysis_response import response
from test_internal_analysis import WALL, envelope, preparer
from test_model_request import raw
from test_mtls import TemporaryPKI
from test_probe_sender import supplier

SECRET = "synthetic-probe-credential"


class Clock:
    def wall_millis(self): return WALL
    def monotonic(self): return time.monotonic()


def candidate(preparer, remaining=60_000):
    clock = Clock()
    call = prepare_internal_analysis(envelope(deadlineEpochMillis=WALL + remaining), preparer, clock=clock)
    return call, GuardedSingleAnalysisTransport(call, preparer, clock=clock)


def synthetic_qualification(monkeypatch):
    # 显式测试桩，不修改固定准入材料，也不证明真实供应商资格。
    monkeypatch.undo()
    monkeypatch.setattr(module, "audit_admission_evidence", lambda _: SimpleNamespace(business_available=True))


def local_connection(monkeypatch, pki, port):
    def connect(self):
        phase = self._phase(5)
        peer = socket.create_connection(("127.0.0.1", port), timeout=phase.remaining(self._clock))
        return pki.client_context().wrap_socket(peer, server_hostname="localhost")
    monkeypatch.setattr(GuardedSingleAnalysisTransport, "_connect", connect)


def assert_failure(sender, code, key=None):
    key = bytearray(SECRET.encode()) if key is None else key
    with pytest.raises(AnalysisTransportError, match=f"^{code}$") as failure:
        sender.send(key)
    assert failure.value.__context__ is None and failure.value.__cause__ is None
    if type(key) is bytearray: assert key == bytearray(len(key))
    return failure


def test_real_material_rejects_before_connection_and_clears_credentials(preparer, monkeypatch, caplog):
    _, sender = candidate(preparer)
    monkeypatch.setattr(sender, "_connect", lambda: pytest.fail("unqualified egress"))
    assert_failure(sender, "MODEL_BUSINESS_UNAVAILABLE")
    assert_failure(sender, "ANALYSIS_TRANSPORT_ALREADY_USED")
    assert not caplog.records and SECRET not in repr(sender)


@pytest.mark.parametrize("key", ["immutable-secret", bytearray(), bytearray(b"private\r\nsecret"), bytearray(b"x" * 4097)],
                         ids=["immutable", "empty", "header-injection", "oversized"])
def test_invalid_credentials_consume_object_without_network(preparer, monkeypatch, key):
    _, sender = candidate(preparer)
    monkeypatch.setattr(sender, "_connect", lambda: pytest.fail("invalid credential egress"))
    assert_failure(sender, "INVALID_MODEL_CREDENTIAL", key)
    assert_failure(sender, "ANALYSIS_TRANSPORT_ALREADY_USED")


def test_local_https_validates_one_response_and_no_internal_metadata(preparer, tmp_path, monkeypatch, caplog):
    synthetic_qualification(monkeypatch)
    call, sender = candidate(preparer)
    pki = TemporaryPKI(tmp_path)
    with supplier(pki, raw(response(call.prepared))) as (port, visits):
        local_connection(monkeypatch, pki, port)
        key = bytearray(SECRET.encode())
        result = sender.send(key)
        assert result.status == "VALIDATED" and result.usage.prompt_tokens == call.prepared.input_tokens
        assert result.qualification == "OFFLINE_UNQUALIFIED"
        assert key == bytearray(len(key))
        assert visits == [("/chat/completions", call.prepared.draft.body)]
        assert_failure(sender, "ANALYSIS_TRANSPORT_ALREADY_USED")
        assert len(visits) == 1
    assert SECRET not in repr(result) + repr(sender) + caplog.text


@pytest.mark.parametrize("status,code", [(302, "MODEL_SUPPLIER_REJECTED"), (401, "MODEL_SUPPLIER_AUTH"),
    (402, "MODEL_SUPPLIER_BALANCE"), (429, "MODEL_SUPPLIER_RATE_LIMIT"), (503, "MODEL_SUPPLIER_REJECTED")])
def test_http_errors_never_follow_redirects_or_retry(preparer, tmp_path, monkeypatch, status, code):
    synthetic_qualification(monkeypatch)
    _, sender = candidate(preparer)
    pki = TemporaryPKI(tmp_path)
    with supplier(pki, b"private-supplier-error", status=status) as (port, visits):
        local_connection(monkeypatch, pki, port)
        assert_failure(sender, code)
        assert len(visits) == 1


def test_https_body_limit_and_original_deadline_close_without_retry(preparer, tmp_path, monkeypatch):
    synthetic_qualification(monkeypatch)
    pki = TemporaryPKI(tmp_path)
    _, sender = candidate(preparer)
    with supplier(pki, b"x" * 16385) as (port, visits):
        local_connection(monkeypatch, pki, port)
        assert_failure(sender, "MODEL_RESPONSE_TOO_LARGE")
        assert len(visits) == 1
    call, sender = candidate(preparer, remaining=250)
    with supplier(pki, raw(response(call.prepared)), delay=.5) as (port, visits):
        local_connection(monkeypatch, pki, port)
        started = time.monotonic()
        assert_failure(sender, "MODEL_TRANSPORT_UNKNOWN")
        assert time.monotonic() - started < .5
        assert len(visits) == 1


def test_bad_usage_is_rejected_content_not_a_retry(preparer, tmp_path, monkeypatch):
    synthetic_qualification(monkeypatch)
    call, sender = candidate(preparer)
    value = response(call.prepared)
    value["usage"]["prompt_tokens"] = True
    pki = TemporaryPKI(tmp_path)
    with supplier(pki, raw(value)) as (port, visits):
        local_connection(monkeypatch, pki, port)
        result = sender.send(bytearray(SECRET.encode()))
        assert result.status == "REJECTED" and result.usage is None
        assert result.rejection_code == "INVALID_MODEL_RESPONSE" and len(visits) == 1


def test_dns_work_is_bounded_after_timeout(preparer, monkeypatch):
    # 只模拟解析阻塞，不连接任何真实地址。
    synthetic_qualification(monkeypatch)
    release = threading.Event()
    started = threading.Event()
    entered = []
    def lookup(*args, **kwargs):
        entered.append(1)
        started.set()
        release.wait(2)
        return []
    class TimeoutAfterLookup(module.queue.Queue):
        def get(self, *args, **kwargs):
            # 该用例检查超时后的槽所有权，不能让机器负载在DNS启动前耗尽50毫秒。
            assert started.wait(1), "DNS worker did not start"
            raise module.queue.Empty()
    monkeypatch.setattr(module.socket, "getaddrinfo", lookup)
    monkeypatch.setattr(module.queue, "Queue", TimeoutAfterLookup)
    try:
        for _ in range(2):
            started.clear()
            _, sender = candidate(preparer)
            assert_failure(sender, "MODEL_TRANSPORT_UNKNOWN")
        assert len(entered) == 2
        _, sender = candidate(preparer)
        assert_failure(sender, "MODEL_DNS_BUSY")
        assert len(entered) == 2
    finally:
        release.set()
        for _ in range(100):
            if module._DNS_SLOTS.acquire(blocking=False):
                if module._DNS_SLOTS.acquire(blocking=False):
                    module._DNS_SLOTS.release(); module._DNS_SLOTS.release()
                    break
                module._DNS_SLOTS.release()
            time.sleep(.005)


@contextmanager
def header_supplier(pki, header, *, payload=b"{}", declared_length=2):
    visits = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            self.rfile.read(int(self.headers["Content-Length"]))
            visits.append(self.path)
            self.send_response(200)
            self.send_header(*header)
            if declared_length is not None:
                self.send_header("Content-Length", str(declared_length))
            self.end_headers()
            try: self.wfile.write(payload)
            except OSError: pass
    server = HTTPServer(("127.0.0.1", 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(*pki.server)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    worker = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
    worker.start()
    try: yield server.server_port, visits
    finally:
        server.shutdown(); server.server_close(); worker.join(2)


@pytest.mark.parametrize("header,code", [(("X-Padding", "x" * 21000), "MODEL_RESPONSE_TOO_LARGE"),
    (("Content-Encoding", "gzip"), "INVALID_MODEL_RESPONSE")], ids=["raw-header-budget", "compressed-response"])
def test_actual_https_raw_headers_and_encoding_are_bounded(preparer, tmp_path, monkeypatch, header, code):
    synthetic_qualification(monkeypatch)
    _, sender = candidate(preparer)
    pki = TemporaryPKI(tmp_path)
    with header_supplier(pki, header) as (port, visits):
        local_connection(monkeypatch, pki, port)
        assert_failure(sender, code)
        assert visits == ["/chat/completions"]


def test_concurrent_use_of_one_object_does_not_start_a_second_exchange(preparer, monkeypatch):
    synthetic_qualification(monkeypatch)
    call, sender = candidate(preparer)
    entered, release = threading.Event(), threading.Event()
    results, failures = [], []
    first_key = bytearray(SECRET.encode())
    def exchange(key):
        entered.set()
        assert release.wait(2)
        assert key == bytearray(SECRET.encode())
        return 200, raw(response(call.prepared))
    monkeypatch.setattr(sender, "_exchange", exchange)
    def first():
        try: results.append(sender.send(first_key))
        except Exception as error: failures.append(error)
    worker = threading.Thread(target=first)
    worker.start()
    try:
        assert entered.wait(2)
        assert_failure(sender, "ANALYSIS_TRANSPORT_ALREADY_USED")
    finally:
        release.set(); worker.join(2)
    assert not worker.is_alive() and not failures
    assert len(results) == 1 and results[0].status == "VALIDATED"
    assert first_key == bytearray(len(first_key))


@pytest.mark.parametrize("mode", ["truncated", "duplicate-length", "conflicting-framing"])
def test_incomplete_or_ambiguous_http_completion_is_not_accepted(preparer, tmp_path, monkeypatch, mode):
    synthetic_qualification(monkeypatch)
    call, sender = candidate(preparer)
    pki = TemporaryPKI(tmp_path)
    payload = raw(response(call.prepared))
    header = {"truncated": ("X-Mode", "close"), "duplicate-length": ("Content-Length", str(len(payload))),
              "conflicting-framing": ("Transfer-Encoding", "chunked")}[mode]
    length = len(payload) + (5 if mode == "truncated" else 0)
    with header_supplier(pki, header, payload=payload, declared_length=length) as (port, visits):
        local_connection(monkeypatch, pki, port)
        assert_failure(sender, "INVALID_MODEL_RESPONSE")
        assert visits == ["/chat/completions"]


def test_complete_chunked_response_is_bounded_and_validated(preparer, tmp_path, monkeypatch):
    synthetic_qualification(monkeypatch)
    call, sender = candidate(preparer)
    pki = TemporaryPKI(tmp_path)
    payload = raw(response(call.prepared))
    chunked = f"{len(payload):x}\r\n".encode() + payload + b"\r\n0\r\n\r\n"
    with header_supplier(pki, ("Transfer-Encoding", "chunked"), payload=chunked, declared_length=None) as (port, visits):
        local_connection(monkeypatch, pki, port)
        assert sender.send(bytearray(SECRET.encode())).status == "VALIDATED"
        assert visits == ["/chat/completions"]
