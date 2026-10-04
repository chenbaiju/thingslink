#!/usr/bin/env python3
"""独占EMQX6.2.3的存量订阅/动态挂载点机制探针；受控认证夹具不代表Java或产品放行。"""
import argparse
import base64
import hashlib
import http.server
import importlib.util
import json
import pathlib
import re
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('identity_wire', ROOT / 'scripts/verify-mqtt-authenticated-identity.py')
WIRE = importlib.util.module_from_spec(SPEC)
sys.dont_write_bytecode = True
SPEC.loader.exec_module(WIRE)
DEVICE = str(uuid.uuid4())
TOPIC = 'tc/v1/p/device/down/command'
UPLINK = 'tc/v1/p/device/up/property'
TOKEN = uuid.uuid4().hex
API_KEY, API_SECRET = 'subscription-probe', uuid.uuid4().hex


class Callback(http.server.BaseHTTPRequestHandler):
    """仅模拟服务器属性签发与当前代次ACL；固定真实Broker负责路由。"""
    epoch = '0'
    session_isolation = False
    observations = []

    def log_message(self, *_):
        """不记录认证正文。"""

    def do_POST(self):
        """旧属性只能保留旧值，不能由ACL补查升级。"""
        if self.headers.get('X-Broker-Callback-Token') != TOKEN:
            self.send_error(401)
            return
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path.endswith('/auth'):
            result = {'result': 'allow', 'is_superuser': False, 'client_attrs': {
                'tc_auth_tenant_id': WIRE.IDENTITY['tenantId'], 'tc_auth_project_id': WIRE.IDENTITY['projectId'],
                'tc_auth_device_id': DEVICE, 'tc_auth_credential_version': '1',
                'tc_auth_config_version': self.epoch, 'tc_auth_mountpoint': prefix(self.epoch)}}
            if self.session_isolation and body.get('username') != 'ingress':
                material = '\n'.join((WIRE.IDENTITY['tenantId'], WIRE.IDENTITY['projectId'],
                                      DEVICE, self.epoch, body['clientid']))
                result['clientid_override'] = 'tc-device-' + hashlib.sha256(material.encode()).hexdigest()
                result['client_attrs']['tc_auth_wire_client_id'] = body['clientid']
            if body.get('username') == 'ingress':
                result['client_attrs'] = {'tc_auth_mountpoint': ''}
        else:
            type(self).observations.append(body)
            allowed = (body.get('username') == 'p/device' and body.get('topic') == TOPIC
                       and body.get('access') == 'subscribe' and body.get('tc_auth_config_version') == self.epoch)
            allowed = allowed or (body.get('username') == 'ingress' and body.get('topic') == WIRE.INTERNAL
                                  and body.get('access') == 'subscribe')
            allowed = allowed or (body.get('username') == 'p/device' and body.get('topic') == UPLINK
                                  and body.get('access') == 'publish'
                                  and body.get('tc_auth_config_version') == self.epoch)
            result = {'result': 'allow' if allowed else 'deny'}
        encoded = json.dumps(result).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def prefix(epoch):
    """候选内部命名空间，不改变设备所见Topic。"""
    return f'tc/private/device/{DEVICE}/{epoch}/'


def publish(port, topic, payload):
    """真实管理API发布，仅到本探针拥有的Broker。"""
    auth = base64.b64encode(f'{API_KEY}:{API_SECRET}'.encode()).decode()
    request = urllib.request.Request(f'http://127.0.0.1:{port}/api/v5/publish', method='POST',
        data=json.dumps({'topic': topic, 'payload': payload, 'qos': 0, 'retain': False}).encode(),
        headers={'Content-Type': 'application/json', 'Authorization': 'Basic ' + auth})
    with urllib.request.urlopen(request, timeout=5) as response:
        assert response.status in (200, 202), response.status


def receive(client, expected):
    """检查真实PUBLISH的设备所见主题及字节，证明挂载点被透明移除。"""
    kind, payload = client.receive()
    assert kind >> 4 == 3 and (kind & 6) == 0, (kind, payload.hex())
    length = struct.unpack('!H', payload[:2])[0]
    topic = payload[2:2 + length].decode()
    # 探针qos0/MQTT5无发布属性；如Broker改变默认编码则明确失败。
    assert payload[2 + length] == 0, payload.hex()
    assert topic == TOPIC and payload[3 + length:].decode() == expected, (topic, payload.hex())


def scenario(candidate, output, session_isolation=False):
    """基线复现和候选验证均使用独占容器，finally只清理本次资源。"""
    Callback.epoch = '0'
    Callback.session_isolation = session_isolation
    Callback.observations = []
    name = 'tc-subscription-boundary-' + uuid.uuid4().hex[:12]
    server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), Callback)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    clients = []
    mode = 'mountpoint-session' if session_isolation else ('mountpoint' if candidate else 'baseline')
    evidence = {'mode': mode, 'image': WIRE.IMAGE, 'limits': 'Broker mechanism only; controlled HTTP auth/ACL. Not Java, TLS, cloud or device qualification.'}
    try:
        with tempfile.TemporaryDirectory(prefix='tc-subscription-boundary-') as directory:
            original = (ROOT / 'deploy/emqx/base.hocon').read_text()
            config = original[:original.index('# S3-11F 连接事件')]
            config = config.replace('http://host.docker.internal:8080', f'http://host.docker.internal:{server.server_port}')
            config = config.replace('dev-only-broker-callback-secret-do-not-use-in-production', TOKEN)
            # 历史反例必须显式还原旧配置，不能让新的仓库base污染基线。
            config = re.sub(r'^listeners\.(tcp|ssl|ws|wss)\.default\.mountpoint.*\n', '', config, flags=re.M)
            config = config.replace('username, substr(topic, strlen(client_attrs.tc_auth_mountpoint)) AS topic, base64_encode',
                                    'username, topic, base64_encode')
            config = config.replace('client_attrs.tc_auth_wire_client_id AS clientId', 'clientid AS clientId')
            config = config.replace('FROM "tc/private/device/+/+/tc/v1/+/+/up/#"', 'FROM "tc/v1/+/+/up/#"')
            config += '\napi_key.bootstrap_file="/opt/emqx/etc/probe-api-keys"\n'
            if candidate:
                config += '\n' + '\n'.join('listeners.' + family + '.default.mountpoint="${client_attrs.tc_auth_mountpoint}"' for family in ('tcp','ssl','ws','wss')) + '\n'
                config = config.replace('username, topic, base64_encode',
                    'username, substr(topic, strlen(client_attrs.tc_auth_mountpoint)) AS topic, base64_encode')
                config = config.replace('FROM "tc/v1/+/+/up/#"',
                    'FROM "tc/private/device/+/+/tc/v1/+/+/up/#"')
            if session_isolation:
                config = config.replace('clientid AS clientId', 'client_attrs.tc_auth_wire_client_id AS clientId')
            config_file, keys = pathlib.Path(directory) / 'base.hocon', pathlib.Path(directory) / 'api-keys'
            config_file.write_text(config)
            keys.write_text(f'{API_KEY}:{API_SECRET}:administrator\n')
            container = WIRE.run('docker', 'run', '-d', '--name', name, '--add-host', 'host.docker.internal:host-gateway',
                '-p', '127.0.0.1::1883', '-p', '127.0.0.1::18083', '-v', f'{config_file}:/opt/emqx/etc/base.hocon:ro',
                '-v', f'{keys}:/opt/emqx/etc/probe-api-keys:ro', WIRE.IMAGE)
            port = int(WIRE.run('docker', 'port', name, '1883/tcp').rsplit(':', 1)[1])
            api_port = int(WIRE.run('docker', 'port', name, '18083/tcp').rsplit(':', 1)[1])
            deadline = time.monotonic() + 75
            while True:
                try:
                    old = WIRE.Mqtt.__new__(WIRE.Mqtt)
                    old.__init__(port, 'old-' + mode, 'p/device')
                    clients.append(old)
                    break
                except (OSError, EOFError, AssertionError):
                    if hasattr(old, 'sock'):
                        old.sock.close()
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(.5)
            while old.subscribe(TOPIC) != 1:
                if time.monotonic() >= deadline:
                    raise AssertionError(('ACL not ready', Callback.observations))
                time.sleep(.5)
            assert Callback.observations, 'baseline must reach real HTTP ACL'
            Callback.epoch = '2'
            fresh = WIRE.Mqtt(port, 'new-' + mode, 'p/device')
            clients.append(fresh)
            assert fresh.subscribe(TOPIC) == 1
            assert old.subscribe(TOPIC) >= 128
            publish(api_port, prefix('2') + TOPIC if candidate else TOPIC, 'new-generation')
            receive(fresh, 'new-generation')
            if candidate:
                old.sock.settimeout(1)
                try:
                    packet = old.receive()
                    raise AssertionError(('old subscriber received new generation', packet))
                except socket.timeout:
                    pass
                finally:
                    old.sock.settimeout(15)
                publish(api_port, prefix('0') + TOPIC, 'old-admitted-generation')
                receive(old, 'old-admitted-generation')
                ingress = WIRE.Mqtt(port, 'ingress', 'ingress')
                clients.append(ingress)
                assert ingress.subscribe(WIRE.INTERNAL) == 1
                raw = b'\x00\xff\x01original-payload'
                assert fresh.publish(UPLINK, raw) < 128
                envelope = ingress.envelope()
                assert envelope['topic'] == UPLINK, envelope
                assert base64.b64decode(envelope['payloadBase64']) == raw
                assert envelope['authenticatedIdentity']['deviceId'] == DEVICE
                assert envelope['schemaVersion'] == 2
                assert envelope['clientId'] == 'new-' + mode, envelope
                evidence['mountedUplinkAndEmptyInternalMountpoint'] = True
                old.close()
                resumed = WIRE.Mqtt(port, 'old-' + mode, 'p/device')
                clients.append(resumed)
                assert resumed.subscribe(TOPIC) == 1
                publish(api_port, prefix('2') + TOPIC, 'resumed-new-generation')
                receive(resumed, 'resumed-new-generation')
                evidence['resumedResubscribeNewGeneration'] = True
                publish(api_port, prefix('0') + TOPIC, 'resumed-old-generation')
                resumed.sock.settimeout(2)
                try:
                    kind, payload = resumed.receive()
                    length = struct.unpack('!H', payload[:2])[0]
                    evidence['resumedOldGenerationTopic'] = payload[2:2 + length].decode()
                except socket.timeout:
                    evidence['resumedOldGenerationTopic'] = None
                finally:
                    resumed.sock.settimeout(15)
                if session_isolation:
                    assert evidence['resumedOldGenerationTopic'] is None, evidence
                    fresh.close()
                    same_epoch = WIRE.Mqtt(port, 'new-' + mode, 'p/device')
                    clients.append(same_epoch)
                    publish(api_port, prefix('2') + TOPIC, 'same-epoch-without-resubscribe')
                    receive(same_epoch, 'same-epoch-without-resubscribe')
                    evidence['sameEpochPersistentSubscriptionRetained'] = True
                else:
                    assert evidence['resumedOldGenerationTopic'] == prefix('0') + TOPIC, evidence
            else:
                receive(old, 'new-generation')
            if session_isolation:
                evidence['effectiveIsolation'] = json.loads(WIRE.run(sys.executable,
                    str(ROOT / 'deploy/scripts/emqx-environment-qualification.py'), 'mqtt-isolation', '--container', name))
            evidence.update({'result': 'PASS', 'containerId': container, 'oldResubscribeDenied': True,
                'oldReceivesNewGeneration': not candidate, 'freshReceivesNewGeneration': True,
                'wireTopicUnchanged': True, 'imageId': WIRE.run('docker', 'image', 'inspect', WIRE.IMAGE, '--format', '{{.Id}}'),
                'baseHoconSha256': hashlib.sha256(original.encode()).hexdigest()})
    except Exception:
        evidence['result'] = 'FAIL'
        try:
            output.with_name(mode + '.broker.log').write_text(WIRE.run('docker', 'logs', name))
        except Exception:
            pass
        raise
    finally:
        for client in clients:
            try:
                client.close()
            except OSError:
                pass
        removed = subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True, timeout=45)
        server.shutdown()
        server.server_close()
        evidence['containerRemoved'] = removed.returncode == 0
        evidence['aclObservations'] = Callback.observations
        if not evidence['containerRemoved']:
            evidence['result'] = 'FAIL'
        output.write_text(json.dumps(evidence, indent=2) + '\n')
        if not evidence['containerRemoved']:
            raise RuntimeError('Owned container cleanup failed: ' + name)
    print('PASS', mode, output)


def main():
    """显式执行并保留基线/候选两个独立证据，失败不变更共享部署。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', required=True, type=pathlib.Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    scenario(False, args.output_dir / 'baseline.json')
    scenario(True, args.output_dir / 'mountpoint.json')
    scenario(True, args.output_dir / 'mountpoint-session.json', True)


if __name__ == '__main__':
    main()
