#!/usr/bin/env python3
"""Explicit, reversible test-only EMQX TLS material; never edits deployment defaults.

Install in a fresh private directory. Authentication credentials are read only from
EMQX_DASHBOARD_USER/PASSWORD. The caller must serialize all broker users and call
restore even after failure. This tool retains auth/ACL/mountpoint and refuses mTLS.
"""
import argparse
import copy
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import subprocess
import urllib.error
import urllib.request


class FixtureError(RuntimeError):
    """Fixed diagnostic code and deliberately non-secret message."""
    def __init__(self, code, message, **facts):
        super().__init__(message)
        self.code = code
        self.facts = facts


def safe_error(error):
    # Never store arbitrary exception messages, HTTP response bodies, argv, URLs or SSL paths.
    if isinstance(error, FixtureError):
        return {'code': error.code, **error.facts}
    if isinstance(error, ssl.SSLCertVerificationError):
        return {'code': 'TLS_CERTIFICATE_REJECTED', 'verifyCode': error.verify_code}
    if isinstance(error, ssl.SSLError):
        return {'code': 'TLS_HANDSHAKE_FAILED'}
    if isinstance(error, subprocess.TimeoutExpired):
        return {'code': 'SUBPROCESS_TIMEOUT'}
    if isinstance(error, TimeoutError):
        return {'code': 'NETWORK_TIMEOUT'}
    if isinstance(error, (ConnectionError, urllib.error.URLError)):
        return {'code': 'NETWORK_CONNECTION_FAILED'}
    return {'code': 'OPERATION_FAILED'}


class Diagnostic:
    def __init__(self, path, operation):
        self.path = path
        self.value = {'version': 1, 'operation': operation, 'result': 'RUNNING',
                      'phase': 'preflight', 'completedPhases': [], 'failure': None}
        save(self.path, self.value)

    @contextmanager
    def step(self, name):
        self.value['phase'] = name
        save(self.path, self.value)  # A durable checkpoint before the next mutation.
        try:
            yield
        except Exception as error:
            self.value.update(result='FAIL', failure=safe_error(error))
            save(self.path, self.value)
            raise
        else:
            self.value['completedPhases'].append(name)
            save(self.path, self.value)

    def success(self):
        self.value.update(result='PASS', phase='completed')
        save(self.path, self.value)


def run(*args):
    result = subprocess.run(args, capture_output=True, timeout=30)
    if result.returncode:
        raise FixtureError('SUBPROCESS_FAILED', 'Test TLS fixture subprocess failed: ' + args[0],
                           tool=args[0], exitCode=result.returncode)
    return result.stdout


def save(path, value):
    temporary = path.with_suffix('.pending')
    with temporary.open('w', encoding='utf-8') as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, indent=2)
    temporary.replace(path)


def generate(directory, *, create_directory=True):
    """Strict clients require a CA with explicit CA/keyCertSign constraints."""
    if create_directory:
        directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    run('openssl', 'req', '-sha256', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-pkeyopt', 'ec_param_enc:named_curve',
        '-nodes', '-days', '2', '-subj', '/CN=ThingsLink controlled test CA',
        '-addext', 'basicConstraints=critical,CA:TRUE,pathlen:0',
        '-addext', 'keyUsage=critical,keyCertSign,cRLSign',
        '-addext', 'subjectKeyIdentifier=hash',
        '-keyout', str(directory / 'ca.key'), '-out', str(directory / 'ca.pem'))
    run('openssl', 'req', '-sha256', '-new', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-pkeyopt', 'ec_param_enc:named_curve',
        '-nodes', '-subj', '/CN=localhost', '-keyout', str(directory / 'server.key'),
        '-out', str(directory / 'server.csr'))
    (directory / 'server.ext').write_text('basicConstraints=critical,CA:FALSE\n'
        'keyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n'
        'subjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n'
        'subjectAltName=DNS:localhost,IP:127.0.0.1\n', encoding='ascii')
    run('openssl', 'x509', '-sha256', '-req', '-in', str(directory / 'server.csr'),
        '-CA', str(directory / 'ca.pem'), '-CAkey', str(directory / 'ca.key'),
        '-set_serial', str(secrets.randbits(120)), '-days', '2',
        '-extfile', str(directory / 'server.ext'), '-out', str(directory / 'server.pem'))
    for path in directory.iterdir():
        path.chmod(0o600)
    run('openssl', 'verify', '-x509_strict', '-CAfile', str(directory / 'ca.pem'),
        '-purpose', 'sslserver', str(directory / 'server.pem'))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        raise FixtureError('BROKER_REDIRECT_REFUSED', 'Test TLS broker redirect refused')


class Broker:
    def __init__(self, port):
        self.base = f'http://127.0.0.1:{port}/api/v5'
        self.token = None
        self.token = self.call('POST', '/login', {
            'username': os.environ['EMQX_DASHBOARD_USER'],
            'password': os.environ['EMQX_DASHBOARD_PASSWORD']})['token']

    def call(self, method, path, body=None, allow_missing=False):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
            data=None if body is None else json.dumps(body).encode())
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            with opener.open(request, timeout=10) as response:
                raw = response.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise FixtureError('BROKER_RESPONSE_TOO_LARGE', 'Broker response exceeds bound')
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            if allow_missing and error.code == 404:
                return None
            raise FixtureError('BROKER_HTTP_REFUSED', f'Test TLS broker request failed ({method}, HTTP {error.code})',
                               method=method, httpStatus=error.code) from None

    def identity(self):
        return {'authentication': self.call('GET', '/authentication'),
                'authorization': self.call('GET', '/authorization/sources')}


def listener_projection(value):
    return {key: value[key] for key in ('bind', 'enable', 'ssl_options') if key in value}


def listener_update(value):
    # EMQX 6.2 listener PUT selects its schema by body.type and requires body.id.
    # Keep the equality/ownership projection separate from the API's discriminators.
    return {**listener_projection(value), 'id': 'ssl:default', 'type': 'ssl'}


def install(args):
    directory = args.directory.resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    diagnostic = Diagnostic(directory / 'install-diagnostics.json', 'install')
    with diagnostic.step('dashboard-login'):
        broker = Broker(args.dashboard_port)
    with diagnostic.step('listener-preflight'):
        before = broker.call('GET', '/listeners/ssl%3Adefault')
        if before.get('mountpoint') != '${client_attrs.tc_auth_mountpoint}':
            raise FixtureError('MOUNTPOINT_REQUIRED', 'Refusing test TLS without platform authentication mountpoint')
        if before.get('ssl_options', {}).get('verify', 'verify_none') != 'verify_none':
            raise FixtureError('MUTUAL_TLS_REFUSED', 'Refusing to replace a mutual-TLS listener contract')
        original = listener_projection(before)
        if '******' in json.dumps(original):
            raise FixtureError('LISTENER_SECRETS_REDACTED', 'Listener contains unrecoverable redacted settings')
    with diagnostic.step('certificate-generation-and-strict-verification'):
        generate(directory, create_directory=False)
    with diagnostic.step('identity-snapshot'):
        state = {'status': 'PREPARING', 'container': args.container,
                 'containerDirectory': '/tmp/tc-test-tls-' + secrets.token_hex(12),
                 'dashboardPort': args.dashboard_port, 'original': original,
                 'identity': broker.identity(), 'mountpoint': before['mountpoint'],
                 'ca': str(directory / 'ca.pem'), 'host': 'localhost',
                 'connectHost': '127.0.0.1', 'port': args.mqtt_port}
        save(directory / 'state.json', state)  # Recovery exists before any broker mutation.
    target = state['containerDirectory']
    with diagnostic.step('container-material-directory'):
        run('docker', 'exec', '--user', 'root', args.container, 'mkdir', '-m', '700', target)
        owner = run('docker', 'exec', args.container, 'id', '-u').decode().strip()
        if not re.fullmatch(r'[0-9]+', owner):
            raise FixtureError('CONTAINER_OWNER_INVALID', 'Invalid certificate file owner')
    for name in ('ca.pem', 'server.pem', 'server.key'):
        with diagnostic.step('container-material-copy-' + name):
            run('docker', 'cp', str(directory / name), args.container + ':' + target + '/' + name)
    with diagnostic.step('container-material-permissions'):
        run('docker', 'exec', '--user', 'root', args.container, 'chown', '-R', owner, target)
        run('docker', 'exec', '--user', 'root', args.container, 'chmod', '-R', 'u=rwX,go=', target)
    options = copy.deepcopy(before.get('ssl_options', {}))
    options.update({'certfile': target + '/server.pem', 'keyfile': target + '/server.key',
                    'cacertfile': target + '/ca.pem', 'versions': ['tlsv1.3', 'tlsv1.2']})
    state['installed'] = {**original, 'enable': True, 'ssl_options': options}
    save(directory / 'state.json', state)
    with diagnostic.step('listener-update'):
        broker.call('PUT', '/listeners/ssl%3Adefault', listener_update(state['installed']))
    with diagnostic.step('listener-readback'):
        after = broker.call('GET', '/listeners/ssl%3Adefault')
        state['installed'] = listener_projection(after)
        save(directory / 'state.json', state)
    with diagnostic.step('identity-readback'):
        if after.get('mountpoint') != state['mountpoint'] or broker.identity() != state['identity']:
            raise FixtureError('PLATFORM_IDENTITY_CHANGED', 'Test TLS changed authentication/authorization/mountpoint')
    with diagnostic.step('tls-context'):
        context = ssl.create_default_context(cafile=state['ca'])
        context.minimum_version = ssl.TLSVersion.TLSv1_2
    with diagnostic.step('tls-connect'):
        raw = socket.create_connection(('127.0.0.1', args.mqtt_port), timeout=5)
    with raw:
        with diagnostic.step('tls-handshake-and-hostname-verification'):
            with context.wrap_socket(raw, server_hostname='localhost') as connection:
                state['tlsVersion'] = connection.version()
    if args.metrics_key:
        name = 'tc-tls-metrics-' + secrets.token_hex(12)
        state['metricsKeyName'] = name
        save(directory / 'state.json', state)  # Unknown create outcomes are also cleaned by exact name.
        with diagnostic.step('metrics-key-create'):
            created = broker.call('POST', '/api_key', {'name': name, 'enable': True, 'role': 'viewer',
                'expired_at': (datetime.now(timezone.utc) + timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'),
                'desc': 'controlled test aggregate metrics only'})
        with diagnostic.step('metrics-key-readback'):
            verified = broker.call('GET', '/api_key/' + name)
            if verified.get('role') != 'viewer' or not created.get('api_key') or not created.get('api_secret'):
                raise FixtureError('METRICS_IDENTITY_INVALID', 'Metrics key is not a verified read-only identity')
        with diagnostic.step('metrics-context-persist'):
            save(directory / 'metrics-context.json', {'apiBaseUrl': f'http://127.0.0.1:{args.dashboard_port}',
                 'apiKey': created['api_key'], 'apiSecret': created['api_secret'], 'role': 'viewer'})
    with diagnostic.step('ready-state-persist'):
        state['status'] = 'READY'
        state['caSha256'] = hashlib.sha256((directory / 'ca.pem').read_bytes()).hexdigest()
        save(directory / 'state.json', state)
    diagnostic.success()
    print('Controlled EMQX TLS fixture READY')


def restore(args):
    path = args.directory.resolve() / 'state.json'
    if not path.exists():
        return  # Generation failed before any broker mutation.
    state = json.loads(path.read_text())
    if state['status'] == 'RESTORED':
        return
    broker = Broker(state['dashboardPort'])
    errors = []
    try:
        current = broker.call('GET', '/listeners/ssl%3Adefault')
        if listener_projection(current) not in (state['original'], state.get('installed')):
            raise RuntimeError('Refusing to overwrite an independently changed TLS listener')
        if listener_projection(current) != state['original']:
            broker.call('PUT', '/listeners/ssl%3Adefault', listener_update(state['original']))
        after = broker.call('GET', '/listeners/ssl%3Adefault')
        if listener_projection(after) != state['original'] or after.get('mountpoint') != state['mountpoint']:
            raise RuntimeError('Test TLS listener restore readback differs')
        if broker.identity() != state['identity']:
            raise RuntimeError('Test TLS auth/ACL restore readback differs')
    except Exception as error:
        errors.append('listener: ' + str(error))
    # Credential revocation is independent of listener ownership/restoration. A listener conflict
    # must preserve its TLS files, but must not leave our temporary API credential enabled.
    if state.get('metricsKeyName'):
        try:
            name = state['metricsKeyName']
            if not re.fullmatch(r'tc-tls-metrics-[a-f0-9]{24}', name):
                raise RuntimeError('Invalid owned metrics key')
            broker.call('DELETE', '/api_key/' + name, allow_missing=True)
            if broker.call('GET', '/api_key/' + name, allow_missing=True) is not None:
                raise RuntimeError('Owned metrics key deletion not confirmed')
            (args.directory.resolve() / 'metrics-context.json').unlink(missing_ok=True)
        except Exception as error:
            errors.append('metrics key: ' + str(error))
    if errors:
        state['status'] = 'RESTORE_FAILED'
        state['restoreErrors'] = errors
        save(path, state)
        raise RuntimeError('; '.join(errors))
    target = state['containerDirectory']
    if not re.fullmatch(r'/tmp/tc-test-tls-[a-f0-9]{24}', target):
        raise RuntimeError('Invalid owned TLS directory')
    run('docker', 'exec', '--user', 'root', state['container'], 'rm', '-rf', '--', target)
    state['status'] = 'RESTORED'
    state.pop('restoreErrors', None)
    save(path, state)
    print('Controlled EMQX TLS fixture RESTORED')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['generate', 'install', 'restore'])
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--container', default='tc-emqx')
    parser.add_argument('--metrics-key', action='store_true', help='Create a private temporary viewer key for SDK metrics')
    parser.add_argument('--dashboard-port', type=int, default=18083)
    parser.add_argument('--mqtt-port', type=int, default=8883)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', args.container):
        parser.error('Invalid container identity')
    if not all(1024 < port < 65536 for port in (args.dashboard_port, args.mqtt_port)):
        parser.error('Invalid loopback port')
    if args.operation == 'generate':
        generate(args.directory.resolve())
    elif args.operation == 'install':
        install(args)
    else:
        restore(args)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit('Controlled test TLS failed: ' + json.dumps(safe_error(error), sort_keys=True)) from None
