"""Build a server context whose trust anchors are dedicated client leaf certificates.

No operating-system CAs or peer-supplied identity headers are trusted. The OpenSSL
handshake, not an HTTP handler, enforces the frozen certificate allowlist.
"""

from __future__ import annotations

import re
import ssl
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Mapping

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.x509.oid import ExtendedKeyUsageOID

MAX_ALLOWLIST_BYTES = 128 * 1024
MAX_CLIENT_CERTIFICATES = 16
_CERTIFICATE = re.compile(rb"-----BEGIN CERTIFICATE-----[\sA-Za-z0-9+/=]+-----END CERTIFICATE-----")


class TLSConfigurationError(ValueError):
    def __init__(self) -> None:
        super().__init__("INVALID_INTERNAL_TLS_CONFIGURATION")


@dataclass(frozen=True, repr=False)
class ServerSettings:
    host: str
    port: int
    certificate: Path
    private_key: Path
    allowed_clients: Path
    key_password: str = field(default="", repr=False)

    def __repr__(self) -> str:
        return "ServerSettings[REDACTED]"

    @classmethod
    def from_environment(cls, environment: Mapping[str, str]) -> ServerSettings:
        failed = False
        settings = None
        try:
            host = environment["AGENT_BIND_HOST"]
            port = int(environment["AGENT_BIND_PORT"])
            paths = [environment[name] for name in (
                "AGENT_SERVER_CERT", "AGENT_SERVER_KEY", "AGENT_ALLOWED_CLIENT_CERTS"
            )]
            if (not host or host != host.strip() or any(c.isspace() for c in host)
                    or not 1 <= port <= 65535 or any(not path.strip() for path in paths)):
                failed = True
            else:
                settings = cls(host, port, *(Path(path) for path in paths),
                               environment.get("AGENT_SERVER_KEY_PASSWORD", ""))
        except (KeyError, TypeError, ValueError):
            failed = True
        if failed:
            raise TLSConfigurationError()
        assert settings is not None
        return settings


def _allowed_leaves(path: Path) -> bytes:
    """Require a bounded, certificate-only bundle of unique dedicated client leaves."""
    failed = False
    canonical = b""
    try:
        with path.open("rb") as source:
            pem = source.read(MAX_ALLOWLIST_BYTES + 1)
        blocks = _CERTIFICATE.findall(pem)
        if (len(pem) > MAX_ALLOWLIST_BYTES or not 1 <= len(blocks) <= MAX_CLIENT_CERTIFICATES
                or _CERTIFICATE.sub(b"", pem).strip()):
            raise ValueError("invalid certificate bundle")
        now = datetime.now(UTC)
        fingerprints = set()
        for block in blocks:
            certificate = x509.load_pem_x509_certificate(block)
            constraints = certificate.extensions.get_extension_for_class(x509.BasicConstraints)
            usage = certificate.extensions.get_extension_for_class(x509.KeyUsage).value
            purposes = certificate.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
            fingerprint = certificate.fingerprint(hashes.SHA256())
            if (constraints.value.ca or not constraints.critical or not usage.digital_signature
                    or usage.key_cert_sign or usage.crl_sign
                    or set(purposes) != {ExtendedKeyUsageOID.CLIENT_AUTH}
                    or not certificate.not_valid_before_utc <= now < certificate.not_valid_after_utc
                    or fingerprint in fingerprints):
                raise ValueError("invalid dedicated client certificate")
            fingerprints.add(fingerprint)
            canonical += certificate.public_bytes(serialization.Encoding.PEM)
    except (OSError, ValueError, x509.ExtensionNotFound, x509.DuplicateExtension):
        failed = True
    if failed:
        # No certificate, filename, password or underlying exception in the error chain.
        raise TLSConfigurationError()
    return canonical


def create_server_context(settings: ServerSettings) -> ssl.SSLContext:
    allowed = _allowed_leaves(settings.allowed_clients)
    failed = False
    context = None
    try:
        # Explicit constructor: do not load system CAs or honor SSLKEYLOGFILE.
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.verify_mode = ssl.CERT_REQUIRED
        context.verify_flags = ssl.VERIFY_X509_STRICT | ssl.VERIFY_X509_PARTIAL_CHAIN
        context.options |= ssl.OP_NO_TICKET
        context.num_tickets = 0
        context.set_alpn_protocols(["http/1.1"])
        context.load_verify_locations(cadata=allowed.decode("ascii"))
        # A callback avoids OpenSSL's interactive password prompt for encrypted keys.
        context.load_cert_chain(settings.certificate, settings.private_key,
                                password=lambda: settings.key_password)
    except (OSError, ValueError, UnicodeError):
        failed = True
    if failed:
        raise TLSConfigurationError()
    assert context is not None
    return context
