"""Real temporary-PKI/TLS/HTTP tests; no business credentials or external services."""
from __future__ import annotations

import asyncio
import json
import logging
import socket
import ssl
import threading
from contextlib import contextmanager
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
import uvicorn
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from agent.internal_server import build_server_config, main
from agent.mtls import MAX_ALLOWLIST_BYTES, ServerSettings, TLSConfigurationError, create_server_context


class TemporaryPKI:
    def __init__(self, directory: Path):
        self.directory = directory
        self.ca_key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Synthetic test CA")])
        now = datetime.now(UTC)
        self.ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                   .public_key(self.ca_key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=1))
                   .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                   .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, None, None), critical=True)
                   .add_extension(x509.SubjectKeyIdentifier.from_public_key(self.ca_key.public_key()), False)
                   .sign(self.ca_key, hashes.SHA256()))
        self.ca_path = directory / "ca.pem"
        self.ca_path.write_bytes(self.ca.public_bytes(serialization.Encoding.PEM))
        self.server = self.issue("server", purpose="server")
        self.client = self.issue("client")
        self.other = self.issue("other")

    def issue(self, name, *, purpose="client", ca=False, expired=False, future=False,
              constraints=True, usage=True, password=None):
        key = ec.generate_private_key(ec.SECP256R1())
        now = datetime.now(UTC)
        start = now - timedelta(hours=1)
        end = now + timedelta(hours=1)
        if expired:
            start, end = now - timedelta(days=2), now - timedelta(days=1)
        if future:
            start, end = now + timedelta(days=1), now + timedelta(days=2)
        certificate = (x509.CertificateBuilder()
                       .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)]))
                       .issuer_name(self.ca.subject).public_key(key.public_key())
                       .serial_number(x509.random_serial_number()).not_valid_before(start).not_valid_after(end)
                       .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), False)
                       .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(self.ca_key.public_key()), False))
        if constraints:
            certificate = certificate.add_extension(x509.BasicConstraints(ca=ca, path_length=None), True)
        if usage:
            certificate = certificate.add_extension(
                x509.KeyUsage(True, False, False, False, False, ca, ca, None, None), True)
        if purpose:
            purposes = ([ExtendedKeyUsageOID.CLIENT_AUTH, ExtendedKeyUsageOID.SERVER_AUTH] if purpose == "both"
                        else [ExtendedKeyUsageOID.SERVER_AUTH if purpose == "server" else ExtendedKeyUsageOID.CLIENT_AUTH])
            certificate = certificate.add_extension(x509.ExtendedKeyUsage(purposes), False)
        if purpose in ("server", "both"):
            certificate = certificate.add_extension(x509.SubjectAlternativeName([x509.DNSName("agent.test"), x509.DNSName("localhost")]), False)
        certificate = certificate.sign(self.ca_key, hashes.SHA256())
        cert_path, key_path = self.directory / (name + ".pem"), self.directory / (name + ".key")
        cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
        key_path.write_bytes(key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.BestAvailableEncryption(password.encode()) if password else serialization.NoEncryption()))
        return cert_path, key_path

    def settings(self, clients=None):
        allowed = self.directory / "allowed.pem"
        allowed.write_bytes(b"".join(pair[0].read_bytes() for pair in (clients or [self.client])))
        return ServerSettings("127.0.0.1", 8443, *self.server, allowed)

    def client_context(self, client=None):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.load_verify_locations(self.ca_path)
        if client:
            context.load_cert_chain(*client)
        return context


@pytest.fixture
def pki(tmp_path):
    return TemporaryPKI(tmp_path)


@contextmanager
def running_server(settings):
    config = settings if isinstance(settings, uvicorn.Config) else build_server_config(settings)
    visits = []

    @config.app.middleware("http")
    async def record_entry(request, call_next):
        visits.append(request.url.path)
        return await call_next(request)

    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
    server = uvicorn.Server(config)
    ready = threading.Event()
    errors = []

    async def serve():
        task = asyncio.create_task(server.serve(sockets=[listener]))
        try:
            async with asyncio.timeout(5):
                while not server.started and not task.done():
                    await asyncio.sleep(0.01)
            if not server.started:
                await task
                raise RuntimeError("test server did not start")
            ready.set()
            await task
        finally:
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)

    def run():
        try:
            asyncio.run(serve())
        except BaseException as error:
            errors.append(error)
            ready.set()

    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    try:
        assert ready.wait(7), "test server startup timeout"
        assert not errors
        yield port, visits
    finally:
        server.should_exit = True
        thread.join(7)
        listener.close()
        assert not thread.is_alive(), "test server did not stop"
        assert not errors


def exchange(port, context, *, hostname="agent.test", path="/internal/health", headers=b"", plaintext=False):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=2) as raw:
            connection = raw if plaintext else context.wrap_socket(raw, server_hostname=hostname)
            with connection:
                connection.sendall(b"GET " + path.encode() + b" HTTP/1.1\r\nHost: agent.test\r\nConnection: close\r\n" + headers + b"\r\n")
                response = b""
                while True:
                    block = connection.recv(4096)
                    if not block:
                        return response
                    response += block
    except OSError:
        return b""


def test_allowed_leaf_reaches_actual_http_and_unknown_same_ca_does_not(pki):
    with running_server(pki.settings()) as (port, visits):
        accepted = exchange(port, pki.client_context(pki.client))
        assert accepted.startswith(b"HTTP/1.1 200")
        assert json.loads(accepted.split(b"\r\n\r\n", 1)[1]) == {"status": "ok", "analysisAvailable": False}
        assert exchange(port, pki.client_context(pki.other)) == b""
        assert visits == ["/internal/health"]


@pytest.mark.parametrize("case", ["missing", "other-ca", "expired", "server-purpose", "plaintext", "fake-header"])
def test_rejected_peers_never_enter_asgi(pki, tmp_path, case, caplog):
    settings = pki.settings()
    client = pki.client
    if case in ("missing", "fake-header"):
        client = None
    elif case == "other-ca":
        other_path = tmp_path / "other-pki"
        other_path.mkdir()
        client = TemporaryPKI(other_path).client
    elif case == "expired":
        client = pki.issue("expired-peer", expired=True)
    elif case == "server-purpose":
        client = pki.server
    with running_server(settings) as (port, visits):
        with caplog.at_level(logging.DEBUG):
            assert exchange(port, pki.client_context(client), plaintext=case == "plaintext",
                            headers=b"X-Forwarded-Client-Cert: SYNTHETIC_SECRET\r\nAuthorization: SYNTHETIC_SECRET\r\n") == b""
        assert visits == []
    assert "SYNTHETIC_SECRET" not in caplog.text


@pytest.mark.parametrize("case", ["hostname", "untrusted-ca"])
def test_client_must_verify_server_ca_and_hostname(pki, tmp_path, case):
    context = pki.client_context(pki.client)
    if case == "untrusted-ca":
        other_path = tmp_path / "other-pki"
        other_path.mkdir()
        other = TemporaryPKI(other_path)
        context = other.client_context(pki.client)
    with running_server(pki.settings()) as (port, visits):
        assert exchange(port, context, hostname="wrong.test" if case == "hostname" else "agent.test") == b""
        assert visits == []


def test_rotation_accepts_two_then_removes_old_after_restart(pki):
    with running_server(pki.settings([pki.client, pki.other])) as (port, visits):
        assert exchange(port, pki.client_context(pki.client)).startswith(b"HTTP/1.1 200")
        assert exchange(port, pki.client_context(pki.other)).startswith(b"HTTP/1.1 200")
        assert len(visits) == 2
    with running_server(pki.settings([pki.other])) as (port, visits):
        assert exchange(port, pki.client_context(pki.client)) == b""
        assert exchange(port, pki.client_context(pki.other)).startswith(b"HTTP/1.1 200")
        assert len(visits) == 1


@pytest.mark.parametrize("path", ["/internal/health/", "/docs", "/openapi.json", "/internal/analyze"])
def test_no_redirect_documentation_or_analysis_route(pki, path):
    with running_server(pki.settings()) as (port, visits):
        response = exchange(port, pki.client_context(pki.client), path=path)
        assert response.startswith(b"HTTP/1.1 404")
        assert b"location:" not in response.lower()


@pytest.mark.parametrize("invalid", ["ca", "duplicate", "expired", "future", "server", "both", "no-purpose", "no-constraints", "no-usage", "malformed", "mixed", "empty", "oversize", "too-many"])
def test_invalid_allowlist_cannot_start_and_error_does_not_retain_input(pki, invalid):
    settings = pki.settings()
    if invalid == "ca":
        data = pki.ca_path.read_bytes()
    elif invalid == "duplicate":
        data = pki.client[0].read_bytes() * 2
    elif invalid in ("expired", "future"):
        data = pki.issue("bad", **{invalid: True})[0].read_bytes()
    elif invalid in ("server", "both", "no-purpose"):
        data = pki.issue("bad", purpose=None if invalid == "no-purpose" else invalid)[0].read_bytes()
    elif invalid in ("no-constraints", "no-usage"):
        data = pki.issue("bad", **{invalid.removeprefix("no-"): False})[0].read_bytes()
    elif invalid == "malformed":
        data = b"-----BEGIN CERTIFICATE-----\nSYNTHETIC_SECRET\n-----END CERTIFICATE-----"
    elif invalid == "mixed":
        data = pki.client[0].read_bytes() + b"SYNTHETIC_SECRET"
    elif invalid == "oversize":
        data = b"SYNTHETIC_SECRET" * MAX_ALLOWLIST_BYTES
    elif invalid == "too-many":
        data = b"".join(pki.issue("client-" + str(i))[0].read_bytes() for i in range(17))
    else:
        data = b""
    settings.allowed_clients.write_bytes(data)
    with pytest.raises(TLSConfigurationError) as rejected:
        build_server_config(settings)
    assert str(rejected.value) == "INVALID_INTERNAL_TLS_CONFIGURATION"
    assert rejected.value.__context__ is None and rejected.value.__cause__ is None


def test_server_context_is_frozen_no_system_ca_no_keylog_and_strict(pki, monkeypatch, tmp_path):
    keylog = tmp_path / "tls-secrets.log"
    monkeypatch.setenv("SSLKEYLOGFILE", str(keylog))
    settings = pki.settings()
    config = build_server_config(settings)
    settings.allowed_clients.write_bytes(pki.other[0].read_bytes())
    config.load()
    context = config.ssl
    assert context is not None and config.is_ssl
    assert context.verify_mode == ssl.CERT_REQUIRED
    assert context.verify_flags & ssl.VERIFY_X509_STRICT
    assert context.verify_flags & ssl.VERIFY_X509_PARTIAL_CHAIN
    assert context.minimum_version == ssl.TLSVersion.TLSv1_2
    assert context.num_tickets == 0 and context.options & ssl.OP_NO_TICKET
    assert context.keylog_filename is None and not keylog.exists()
    assert context.cert_store_stats() == {"x509": 1, "crl": 0, "x509_ca": 0}
    assert not config.proxy_headers and not config.access_log and not config.reload and config.workers == 1
    with running_server(config) as (port, visits):
        assert exchange(port, pki.client_context(pki.client)).startswith(b"HTTP/1.1 200")
        assert exchange(port, pki.client_context(pki.other)) == b""
        assert len(visits) == 1


@pytest.mark.parametrize("case", ["missing-file", "wrong-key", "wrong-password"])
def test_key_configuration_fails_without_password_prompt_or_error_chain(pki, case):
    settings = pki.settings()
    if case == "missing-file":
        settings = replace(settings, private_key=settings.private_key.parent / "SYNTHETIC_SECRET")
    elif case == "wrong-key":
        settings = replace(settings, private_key=pki.client[1])
    else:
        encrypted = pki.issue("encrypted-server", purpose="server", password="SYNTHETIC_SECRET")
        settings = replace(settings, certificate=encrypted[0], private_key=encrypted[1], key_password="wrong")
    with pytest.raises(TLSConfigurationError) as rejected:
        create_server_context(settings)
    assert rejected.value.__context__ is None and rejected.value.__cause__ is None
    assert "SYNTHETIC_SECRET" not in str(rejected.value)


def test_encrypted_key_supported_without_storing_password_in_uvicorn_config(pki):
    encrypted = pki.issue("encrypted-server", purpose="server", password="SYNTHETIC_SECRET")
    settings = replace(pki.settings(), certificate=encrypted[0], private_key=encrypted[1], key_password="SYNTHETIC_SECRET")
    config = build_server_config(settings)
    assert config.ssl_keyfile_password is None
    assert "SYNTHETIC_SECRET" not in repr(settings)
    with running_server(settings) as (port, _):
        assert exchange(port, pki.client_context(pki.client)).startswith(b"HTTP/1.1 200")


@pytest.mark.parametrize("bad", [{}, {"AGENT_BIND_HOST": "", "AGENT_BIND_PORT": "8443"},
    {"AGENT_BIND_HOST": "127.0.0.1", "AGENT_BIND_PORT": "0"},
    {"AGENT_BIND_HOST": "127.0.0.1", "AGENT_BIND_PORT": "SYNTHETIC_SECRET"}])
def test_environment_requires_explicit_tls_and_binding(bad):
    with pytest.raises(TLSConfigurationError) as rejected:
        ServerSettings.from_environment(bad)
    assert rejected.value.__context__ is None
    assert "SYNTHETIC_SECRET" not in repr(rejected.value)


def test_environment_and_main_failure_are_sanitized(pki, monkeypatch, capsys):
    settings = pki.settings()
    environment = {"AGENT_BIND_HOST": "127.0.0.1", "AGENT_BIND_PORT": "8443",
                   "AGENT_SERVER_CERT": str(settings.certificate), "AGENT_SERVER_KEY": str(settings.private_key),
                   "AGENT_ALLOWED_CLIENT_CERTS": str(settings.allowed_clients),
                   "AGENT_SERVER_KEY_PASSWORD": "SYNTHETIC_SECRET"}
    parsed = ServerSettings.from_environment(environment)
    assert parsed.host == "127.0.0.1" and parsed.port == 8443
    assert "SYNTHETIC_SECRET" not in repr(parsed)
    monkeypatch.delenv("AGENT_SERVER_CERT", raising=False)
    with pytest.raises(SystemExit) as rejected:
        main()
    assert rejected.value.code == "INVALID_INTERNAL_TLS_CONFIGURATION"
    assert "SYNTHETIC_SECRET" not in str(rejected.value)
    assert not capsys.readouterr().out


def test_allowed_client_leaf_cannot_delegate_identity_to_child_certificate(pki):
    allowed = x509.load_pem_x509_certificate(pki.client[0].read_bytes())
    issuer_key = serialization.load_pem_private_key(pki.client[1].read_bytes(), password=None)
    child_key = ec.generate_private_key(ec.SECP256R1())
    now = datetime.now(UTC)
    child = (x509.CertificateBuilder()
             .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "delegation attempt")]))
             .issuer_name(allowed.subject).public_key(child_key.public_key())
             .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(hours=1))
             .not_valid_after(now + timedelta(hours=1))
             .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
             .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, None, None), True)
             .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.CLIENT_AUTH]), False)
             .add_extension(x509.SubjectKeyIdentifier.from_public_key(child_key.public_key()), False)
             .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(issuer_key.public_key()), False)
             .sign(issuer_key, hashes.SHA256()))
    cert_path, key_path = pki.directory / "child.pem", pki.directory / "child.key"
    cert_path.write_bytes(child.public_bytes(serialization.Encoding.PEM) + pki.client[0].read_bytes())
    key_path.write_bytes(child_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                serialization.NoEncryption()))
    with running_server(pki.settings()) as (port, visits):
        assert exchange(port, pki.client_context((cert_path, key_path))) == b""
        assert visits == []


def test_ambient_trace_setting_cannot_enable_asgi_message_logging(pki):
    logger = logging.getLogger("uvicorn.error")
    old_level = logger.level
    try:
        logger.setLevel(5)
        config = build_server_config(pki.settings())
        config.load()
        from uvicorn.middleware.message_logger import MessageLoggerMiddleware
        assert logger.getEffectiveLevel() > 5
        assert not isinstance(config.loaded_app, MessageLoggerMiddleware)
    finally:
        logger.setLevel(old_level)
