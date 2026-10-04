#!/usr/bin/env python3
"""独占EMQX 6.2.3真实MQTT专项；纯标准库，不连接共享Broker或证明应用认证缓存。"""
import argparse
import base64
import hashlib
import http.server
import json
import pathlib
import re
import socket
import struct
import subprocess
import tempfile
import threading
import time
import uuid

IMAGE = 'emqx/emqx:6.2.3'
INTERNAL = 'tc/internal/v1/ingress/uplink'
ATTRS = {'tenantId': 'tc_auth_tenant_id', 'projectId': 'tc_auth_project_id',
         'deviceId': 'tc_auth_device_id', 'credentialVersion': 'tc_auth_credential_version'}
IDENTITY = {'tenantId': str(uuid.uuid4()), 'projectId': str(uuid.uuid4()),
            'deviceId': str(uuid.uuid4()), 'credentialVersion': '1'}


def run(*args):
    """有界子进程，失败不降级且不复用外部容器。"""
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=120).stdout.strip()


def varint(value):
    """MQTT剩余长度编码。"""
    out = bytearray()
    while True:
        part = value % 128
        value //= 128
        out.append(part | (128 if value else 0))
        if not value:
            return bytes(out)


def text(value):
    """MQTT UTF8长度前缀。"""
    value = value.encode()
    return struct.pack('!H', len(value)) + value


class Mqtt:
    """最小MQTT5真实TCP客户端；测试所需QoS1与持久会话均走线协议。"""
    def __init__(self, port, name, username, properties=None):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=15)
        self.sock.settimeout(15)
        props = b'\x11' + struct.pack('!I', 172800)
        for key, value in (properties or {}).items():
            props += b'\x26' + text(key) + text(value)
        body = text('MQTT') + b'\x05\xc0\x00\x3c' + varint(len(props)) + props
        self.send(0x10, body + text(name) + text(username) + text('fixture-only'))
        kind, payload = self.receive()
        assert kind == 0x20 and payload[1] == 0, ('CONNACK', kind, payload.hex())

    def send(self, kind, payload):
        """写完整控制包。"""
        self.sock.sendall(bytes([kind]) + varint(len(payload)) + payload)

    def exact(self, length):
        """拒绝截断的TCP控制包。"""
        out = b''
        while len(out) < length:
            part = self.sock.recv(length - len(out))
            if not part:
                raise EOFError('MQTT closed')
            out += part
        return out

    def receive(self):
        """读一个完整MQTT包。"""
        kind = self.exact(1)[0]
        length, factor = 0, 1
        for _ in range(4):
            byte = self.exact(1)[0]
            length += (byte & 127) * factor
            if not byte & 128:
                return kind, self.exact(length)
            factor *= 128
        raise AssertionError('invalid MQTT remaining length')

    def subscribe(self, topic):
        """同步确认订阅的真实Broker返回码。"""
        self.send(0x82, b'\x00\x01\x00' + text(topic) + b'\x01')
        kind, payload = self.receive()
        assert kind == 0x90, (kind, payload.hex())
        return payload[-1]

    def publish(self, topic, payload):
        """实际QoS1发布并读取PUBACK；返回拒绝码不作成功。"""
        self.send(0x32, text(topic) + b'\x00\x02\x00' + payload)
        kind, answer = self.receive()
        assert kind == 0x40, (kind, answer.hex())
        return answer[2] if len(answer) > 2 else 0

    def envelope(self):
        """读取内部republish，保留真实payload并ACK。"""
        kind, payload = self.receive()
        assert kind >> 4 == 3, (kind, payload.hex())
        size = struct.unpack('!H', payload[:2])[0]
        assert payload[2:2 + size].decode() == INTERNAL
        offset = 2 + size
        packet_id = payload[offset:offset + 2]
        offset += 2
        length, factor = 0, 1
        while True:
            byte = payload[offset]
            offset += 1
            length += (byte & 127) * factor
            if not byte & 128:
                break
            factor *= 128
        body = payload[offset + length:]
        self.send(0x40, packet_id)
        return json.loads(body)

    def close(self):
        """关闭网络但保留Broker会话。"""
        try:
            self.send(0xe0, b'')
        except OSError:
            pass
        self.sock.close()


class AuthFixture(http.server.BaseHTTPRequestHandler):
    """受控测试认证HTTP源；缓存模拟只测Broker传播，不能冒称Java认证实现验收。"""
    version = '1'
    cache = {}
    token = str(uuid.uuid4())

    def log_message(self, *_):
        """禁止记录认证正文或临时口令。"""

    def do_POST(self):
        """返回认证属性或ACL判定。"""
        if self.headers.get('X-Broker-Callback-Token') != self.token:
            self.send_error(401)
            return
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        user = body.get('username')
        if self.path.endswith('/auth'):
            result = {'result': 'allow', 'is_superuser': False}
            if user == 'p/device':
                identity = dict(IDENTITY, credentialVersion=self.cache.setdefault(user, self.version))
                result['client_attrs'] = {ATTRS[key]: value for key, value in identity.items()}
            elif user == 'p/partial':
                result['client_attrs'] = {ATTRS['tenantId']: IDENTITY['tenantId']}
            elif user not in ('ingress', 'p/legacy'):
                result['result'] = 'deny'
            if result['result'] == 'allow':
                attrs = result.setdefault('client_attrs', {})
                if user == 'ingress':
                    attrs['tc_auth_mountpoint'] = ''
                else:
                    # legacy/partial仅为受控畸形投影反例，不代表Java认证允许这种身份。
                    attrs['tc_auth_mountpoint'] = 'tc/private/device/' + IDENTITY['deviceId'] + '/0/'
                    attrs['tc_auth_wire_client_id'] = body['clientid']
                    attrs['tc_auth_config_version'] = '0'
                    material = '\n'.join((IDENTITY['tenantId'],IDENTITY['projectId'],IDENTITY['deviceId'],'0',body['clientid']))
                    result['clientid_override'] = 'tc-device-' + hashlib.sha256(material.encode()).hexdigest()
        else:
            topic, action = body.get('topic', ''), body.get('access')
            allowed = (user == 'ingress' and action == 'subscribe' and topic == INTERNAL) or (
                user != 'ingress' and action == 'publish' and topic.startswith('tc/v1/p/') and '/up/' in topic)
            result = {'result': 'allow' if allowed else 'deny'}
        encoded = json.dumps(result).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main():
    """显式执行才创建独占容器，finally只删除本脚本创建的容器和临时配置。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[1]
    original = (repo / 'deploy/emqx/base.hocon').read_text()
    name = 'tc-auth-identity-test-' + uuid.uuid4().hex[:12]
    server = http.server.ThreadingHTTPServer(('0.0.0.0', 0), AuthFixture)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    clients, checks = [], []
    try:
        with tempfile.TemporaryDirectory(prefix='tc-auth-identity-') as directory:
            # 原样复用仓库durable规则/auth/ACL结构，仅隔离回调端点及秘密并省略无关生命周期action。
            config = original[:original.index('# S3-11F 连接事件')]
            config = config.replace('http://host.docker.internal:8080', f'http://host.docker.internal:{server.server_port}')
            config = config.replace('dev-only-broker-callback-secret-do-not-use-in-production', AuthFixture.token)
            # 攻击测试专用：把CONNECT User Properties映射为同名属性，认证器应覆盖它们。
            config += '\nmqtt.client_attrs_init = ' + json.dumps([
                {'expression': 'user_property.' + attr, 'set_as_attr': attr} for attr in ATTRS.values()]) + '\n'
            file = pathlib.Path(directory) / 'base.hocon'
            file.write_text(config)
            run('docker', 'run', '-d', '--name', name, '--add-host', 'host.docker.internal:host-gateway',
                '-p', '127.0.0.1::1883', '-v', f'{file}:/opt/emqx/etc/base.hocon:ro', IMAGE)
            port = int(run('docker', 'port', name, '1883/tcp').rsplit(':', 1)[1])
            deadline = time.monotonic() + 90
            while True:
                try:
                    ingress = Mqtt.__new__(Mqtt)
                    ingress.__init__(port, 'identity-ingress', 'ingress')
                    clients.append(ingress)
                    break
                except (OSError, EOFError, AssertionError):
                    if hasattr(ingress, 'sock'):
                        ingress.sock.close()
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(.5)
            while ingress.subscribe(INTERNAL) != 1:
                if time.monotonic() >= deadline:
                    raise AssertionError('ingress ACL did not become ready')
                time.sleep(.5)
            device = Mqtt(port, 'identity-device', 'p/device', {a: 'spoof' for a in ATTRS.values()})
            clients.append(device)
            raw = b'{"credentialVersion":"999","authenticatedIdentity":{"deviceId":"spoof"}}'
            assert device.publish('tc/v1/p/device/up/property/report', raw) < 128
            envelope = ingress.envelope()
            assert set(envelope) == {'schemaVersion', 'handoffId', 'username', 'topic', 'payloadBase64', 'qos',
                                     'retained', 'clientId', 'publishedAtMs', 'brokerNode', 'authenticatedIdentity'}
            assert envelope['schemaVersion'] == 2 and envelope['authenticatedIdentity'] == IDENTITY
            assert base64.b64decode(envelope['payloadBase64']) == raw
            checks.append('auth_attrs_override_connect_properties_and_payload')
            AuthFixture.version = '2'
            assert device.publish('tc/v1/p/device/up/property/report', b'old-connection') < 128
            assert ingress.envelope()['authenticatedIdentity']['credentialVersion'] == '1'
            cached = Mqtt(port, 'identity-cached', 'p/device')
            clients.append(cached)
            cached.publish('tc/v1/p/device/up/property/report', b'cached-auth')
            assert ingress.envelope()['authenticatedIdentity']['credentialVersion'] == '1'
            checks.append('old_connection_and_cached_auth_keep_original_generation')
            AuthFixture.cache.clear()
            renewed = Mqtt(port, 'identity-renewed', 'p/device')
            clients.append(renewed)
            renewed.publish('tc/v1/p/device/up/property/report', b'new-auth')
            assert ingress.envelope()['authenticatedIdentity']['credentialVersion'] == '2'
            checks.append('fresh_auth_receives_new_generation')
            legacy = Mqtt(port, 'identity-legacy', 'p/legacy')
            clients.append(legacy)
            legacy.publish('tc/v1/p/legacy/up/property/report', raw)
            assert ingress.envelope()['authenticatedIdentity'] is None
            checks.append('missing_attributes_are_explicit_null')
            partial = Mqtt(port, 'identity-partial', 'p/partial')
            clients.append(partial)
            partial.publish('tc/v1/p/partial/up/property/report', b'partial')
            incomplete = ingress.envelope()['authenticatedIdentity']
            assert incomplete is not None and incomplete['tenantId'] == IDENTITY['tenantId']
            assert incomplete['credentialVersion'] == '' and incomplete['deviceId'] == ''
            checks.append('partial_attributes_remain_invalid_for_strict_consumer')
            ingress.close()
            clients.remove(ingress)
            device.publish('tc/v1/p/device/up/property/report', b'offline-durable')
            run('docker', 'restart', name)
            port = int(run('docker', 'port', name, '1883/tcp').rsplit(':', 1)[1])
            time.sleep(3)
            deadline = time.monotonic() + 90
            while True:
                try:
                    ingress = Mqtt.__new__(Mqtt)
                    ingress.__init__(port, 'identity-ingress', 'ingress')
                    clients.append(ingress)
                    break
                except (OSError, EOFError, AssertionError):
                    if hasattr(ingress, 'sock'):
                        ingress.sock.close()
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(.5)
            persisted = ingress.envelope()
            assert base64.b64decode(persisted['payloadBase64']) == b'offline-durable'
            assert persisted['authenticatedIdentity']['credentialVersion'] == '1'
            checks.append('broker_restart_retains_original_identity_in_durable_message')
            attacker = Mqtt(port, 'identity-attacker', 'p/device')
            clients.append(attacker)
            assert attacker.subscribe(INTERNAL) >= 128
            assert attacker.publish(INTERNAL, b'forged') >= 128
            checks.append('device_cannot_subscribe_or_publish_internal_topic')
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps({'result': 'PASS', 'image': IMAGE,
                'imageId': run('docker', 'image', 'inspect', IMAGE, '--format', '{{.Id}}'),
                'ruleSha256': hashlib.sha256(re.search(r'rule_engine.rules.tc_durable_uplink = .*?\n}\n', original, re.S).group().encode()).hexdigest(),
                'checks': checks, 'limits': 'HTTP fixture simulates authentication/cache; Java PG/cache and Kafka are verified separately. No device or production qualification.'}, indent=2) + '\n')
            print(f'PASS {len(checks)} checks: {args.output}')
    except Exception:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({'result': 'FAIL', 'completedChecks': checks}, indent=2) + '\n')
        try:
            args.output.with_suffix('.broker.log').write_text(run('docker', 'logs', name))
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
