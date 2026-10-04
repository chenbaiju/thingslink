#!/usr/bin/env python3
"""独占EMQX6.2.3连接身份机制探针；受控认证源，不替代Java持久化与乱序验收。"""
import argparse
import hashlib
import http.server
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import threading
import time
import uuid

SPEC = importlib.util.spec_from_file_location('identity_probe', pathlib.Path(__file__).with_name('verify-mqtt-authenticated-identity.py'))
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
ATTRIBUTE = 'tc_auth_connection_id'


class Wire(BASE.Mqtt):
    """保留真实CONNACK，验证连接身份变化不破坏持久会话。"""
    def receive(self):
        kind, payload = super().receive()
        if kind == 0x20:
            self.connack = payload
        return kind, payload


class Fixture(http.server.BaseHTTPRequestHandler):
    """只为本轮探针签发随机身份并捕获真实生命周期JSON。"""
    secret = uuid.uuid4().hex
    issued = []
    events = []
    lock = threading.Lock()

    def log_message(self, *_):
        """不打印认证正文。"""

    def do_POST(self):
        """模拟认证/授权；不改项目生产端点或持久事实。"""
        if self.headers.get('X-Broker-Callback-Token') != self.secret:
            self.send_error(401)
            return
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.endswith('/auth'):
            nonce = str(uuid.uuid4())
            attrs = {BASE.ATTRS[k]: v for k, v in BASE.IDENTITY.items()}
            attrs.update(tc_auth_config_version='0', tc_auth_connection_id=nonce,
                         tc_auth_mountpoint='tc/private/device/' + BASE.IDENTITY['deviceId'] + '/0/',
                         tc_auth_wire_client_id=body['clientid'])
            material = '\n'.join((BASE.IDENTITY['tenantId'], BASE.IDENTITY['projectId'], BASE.IDENTITY['deviceId'], '0', body['clientid']))
            result = {'result': 'allow', 'is_superuser': False, 'client_attrs': attrs,
                      'clientid_override': 'tc-device-' + hashlib.sha256(material.encode()).hexdigest()}
            with self.lock:
                self.issued.append(nonce)
        elif '/events/' in self.path:
            with self.lock:
                self.events.append((self.path.rsplit('/', 1)[1], body))
            result = {'accepted': True}
        else:
            result = {'result': 'allow'}
        data = json.dumps(result).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def event(kind, nonce):
    """有界等待实际Broker回调，不按接收先后猜测原连接。"""
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        with Fixture.lock:
            matches = [body for name, body in Fixture.events if name == kind and body.get(ATTRIBUTE) == nonce]
        if matches:
            return matches[0]
        time.sleep(.05)
    raise AssertionError('missing ' + kind + ' original identity')


def main():
    """仅创建/回收自己命名的容器，生产HOCON不修改。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[1]
    original = (repo / 'deploy/emqx/base.hocon').read_text()
    server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Fixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    name = 'tc-connection-identity-' + uuid.uuid4().hex[:12]
    clients, checks = [], []
    try:
        with tempfile.TemporaryDirectory(prefix='tc-connection-identity-') as directory:
            config = original.replace('host.docker.internal:8080', f'host.docker.internal:{server.server_port}')
            config = config.replace('dev-only-broker-callback-secret-do-not-use-in-production', Fixture.secret)
            # 实验性投影只加在独占副本，不冒充生产接线。
            extra = 'connected_at, ' if ' AS tc_auth_connection_id' in config else 'connected_at, client_attrs.tc_auth_connection_id AS tc_auth_connection_id, '
            config = config.replace('SELECT username, clientid,', 'SELECT ' + extra + 'username, clientid,')
            config += '\nmqtt.client_attrs_init = ' + json.dumps([
                {'expression': 'user_property.' + ATTRIBUTE, 'set_as_attr': ATTRIBUTE}]) + '\n'
            path = pathlib.Path(directory) / 'base.hocon'
            path.write_text(config)
            BASE.run('docker', 'run', '-d', '--name', name, '--add-host', 'host.docker.internal:host-gateway',
                     '-p', '127.0.0.1::1883', '-v', f'{path}:/opt/emqx/etc/base.hocon:ro', BASE.IMAGE)
            port = int(BASE.run('docker', 'port', name, '1883/tcp').rsplit(':', 1)[1])
            deadline = time.monotonic() + 90
            while True:
                first = Wire.__new__(Wire)
                try:
                    first.__init__(port, 'same-client', 'p/device', {ATTRIBUTE: 'spoof'})
                    break
                except (OSError, EOFError, AssertionError):
                    if hasattr(first, 'sock'):
                        first.sock.close()
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(.5)
            clients.append(first)
            with Fixture.lock:
                one = Fixture.issued[-1]
            first_event = event('connected', one)
            assert first_event[ATTRIBUTE] != 'spoof'
            assert first.subscribe('tc/v1/p/device/down/command') == 1
            checks.append('server_nonce_overrides_connect_property')
            second = Wire(port, 'same-client', 'p/device')
            clients.append(second)
            with Fixture.lock:
                two = Fixture.issued[-1]
            second_event = event('connected', two)
            old_disconnect = event('disconnected', one)
            assert one != two
            assert first_event['clientid'] == second_event['clientid'] == old_disconnect['clientid']
            checks.append('takeover_disconnect_keeps_original_nonce')
            assert second.connack[0] == 1
            checks.append('new_connection_nonce_preserves_session_present')
            assert old_disconnect['connected_at'] == first_event['connected_at']
            assert isinstance(first_event['connected_at'], int)
            checks.append('disconnect_contains_original_connected_at')
            second.close()
            clients.remove(second)
            event('disconnected', two)
            third = Wire(port, 'same-client', 'p/device')
            clients.append(third)
            with Fixture.lock:
                three = Fixture.issued[-1]
            third_event = event('connected', three)
            assert three not in (one, two) and third.connack[0] == 1
            assert third_event['clientid'] == first_event['clientid']
            checks.append('offline_resume_reauthenticates_without_changing_session_namespace')
            third.close()
            clients.remove(third)
            event('disconnected', three)
            result = {'result': 'PASS', 'image': BASE.IMAGE,
                      'imageId': BASE.run('docker', 'image', 'inspect', BASE.IMAGE, '--format', '{{.Id}}'),
                      'sourceConfigSha256': hashlib.sha256(original.encode()).hexdigest(), 'checks': checks,
                      'limits': 'MQTT5 TCP experimental lifecycle projection and HTTP fixture only; not production Java persistence, ordering, TLS, cluster or hardware qualification.'}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + '\n')
            print(f'PASS {len(checks)} checks: {args.output}')
    except Exception:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'result': 'FAIL', 'completedChecks': checks}, indent=2) + '\n')
        try:
            args.output.with_suffix('.broker.log').write_text(BASE.run('docker', 'logs', name))
        except Exception:
            pass
        raise
    finally:
        for client in clients:
            try:
                client.close()
            except OSError:
                pass
        subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True, timeout=45)
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
