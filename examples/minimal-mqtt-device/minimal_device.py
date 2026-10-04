"""ThingsLink 直连设备最小 MQTT 示例。

本文件刻意把协议生成与网络适配分离，使契约测试无需 Broker 或第三方包即可运行。
生产固件必须补充非易失上行队列和命令结果存储，不能把本示例的内存缓存视为完整 SDK。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import secrets
import ssl
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


KEY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
UUID_V7_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}


def utc_now() -> str:
    """返回平台契约接受的 RFC3339 UTC 秒级时间。"""

    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def uuid7(timestamp_ms: int | None = None) -> str:
    """使用系统安全随机源生成 RFC 9562 UUIDv7。"""

    current_ms = int(time.time() * 1000) if timestamp_ms is None else timestamp_ms
    if not 0 <= current_ms < 1 << 48:
        raise ValueError("UUIDv7 时间戳超出 48 位范围")
    random_a = secrets.randbits(12)
    random_b = secrets.randbits(62)
    value = current_ms << 80
    value |= 0x7 << 76
    value |= random_a << 64
    value |= 0b10 << 62
    value |= random_b
    return str(uuid.UUID(int=value))


def json_bytes(value: dict[str, Any]) -> bytes:
    """以稳定紧凑 JSON 编码报文，便于重试复用同一字节。"""

    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


@dataclass(frozen=True)
class OutboundMessage:
    """待发布 MQTT 消息；ThingsLink 设备侧固定 QoS 1 且禁止 retained。"""

    topic: str
    payload: bytes
    qos: int = 1
    retain: bool = False


@dataclass(frozen=True)
class Settings:
    """已校验运行参数；token 不参与 repr 输出。"""

    host: str
    port: int
    project_key: str
    device_key: str
    token: str
    ca_file: Path | None
    report_interval: float
    plaintext_local: bool

    def __repr__(self) -> str:
        return (
            "Settings(host={!r}, port={!r}, project_key={!r}, device_key={!r}, "
            "token='<redacted>', ca_file={!r}, report_interval={!r}, plaintext_local={!r})"
        ).format(
            self.host,
            self.port,
            self.project_key,
            self.device_key,
            self.ca_file,
            self.report_interval,
            self.plaintext_local,
        )


class DeviceProtocol:
    """生成上行并处理最小下行；重复请求复用首次生成的回复。"""

    def __init__(self, project_key: str, device_key: str) -> None:
        validate_key("projectKey", project_key)
        validate_key("deviceKey", device_key)
        self.project_key = project_key
        self.device_key = device_key
        self.base_topic = f"tc/v1/{project_key}/{device_key}"
        self.properties: dict[str, Any] = {"samplingInterval": 10}
        self._reply_cache: dict[str, OutboundMessage] = {}

    @property
    def downlink_filter(self) -> str:
        """只订阅已认证设备自己的下行树。"""

        return f"{self.base_topic}/down/#"

    def property_report(self, temperature: float) -> OutboundMessage:
        """生成一条属性上报；网络重试必须复用返回对象。"""

        payload = {
            "messageId": uuid7(),
            "occurredAt": utc_now(),
            "payload": {"temperature": round(temperature, 2)},
        }
        return OutboundMessage(f"{self.base_topic}/up/property/report", json_bytes(payload))

    def handle_downlink(self, topic: str, raw_payload: bytes) -> OutboundMessage | None:
        """处理命令或属性设置；非法、越权或未知形状消息 fail-closed。"""

        prefix = f"{self.base_topic}/down/"
        if not topic.startswith(prefix):
            return None
        try:
            payload = json.loads(raw_payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        if not isinstance(payload, dict) or payload.get("targetDeviceKey") != self.device_key:
            return None

        suffix = topic[len(prefix) :]
        if suffix == "property/set":
            return self._handle_property_set(payload)
        if suffix.startswith("command/"):
            return self._handle_command(suffix, payload)
        return None

    def _handle_command(self, suffix: str, payload: dict[str, Any]) -> OutboundMessage | None:
        parts = suffix.split("/")
        if len(parts) != 2 or parts[0] != "command" or not is_uuid7(parts[1]):
            return None
        command_id = parts[1]
        cache_key = f"command:{command_id}"
        if cache_key in self._reply_cache:
            return self._reply_cache[cache_key]

        command_key = payload.get("commandKey")
        command_input = payload.get("input")
        if not isinstance(command_input, dict):
            return None
        if command_key == "ping":
            reply_payload = self._reply("SUCCESS", {"sequence": command_input.get("sequence")})
        else:
            reply_payload = self._reply(
                "FAILED", {}, error_code="UNSUPPORTED_COMMAND", message="示例设备不支持该命令"
            )
        message = OutboundMessage(
            f"{self.base_topic}/up/command/{command_id}/reply", json_bytes(reply_payload)
        )
        self._reply_cache[cache_key] = message
        return message

    def _handle_property_set(self, payload: dict[str, Any]) -> OutboundMessage | None:
        request_id = payload.get("requestId")
        properties = payload.get("properties")
        if not isinstance(request_id, str) or not is_uuid7(request_id) or not isinstance(properties, dict):
            return None
        cache_key = f"property:{request_id}"
        if cache_key in self._reply_cache:
            return self._reply_cache[cache_key]

        self.properties.update(properties)
        reply_payload = self._reply("SUCCESS", dict(self.properties), request_id=request_id)
        message = OutboundMessage(
            f"{self.base_topic}/up/property/set/reply", json_bytes(reply_payload)
        )
        self._reply_cache[cache_key] = message
        return message

    @staticmethod
    def _reply(
        status: str,
        output: dict[str, Any],
        *,
        request_id: str | None = None,
        error_code: str | None = None,
        message: str | None = None,
    ) -> dict[str, Any]:
        reply: dict[str, Any] = {
            "messageId": uuid7(),
            "occurredAt": utc_now(),
            "status": status,
            "output": output,
        }
        if request_id is not None:
            reply["requestId"] = request_id
        if error_code is not None:
            reply["errorCode"] = error_code
        if message is not None:
            reply["message"] = message
        return reply


def is_uuid7(value: str) -> bool:
    """校验文本同时满足 UUID 语法、版本 7 和 RFC variant。"""

    if not UUID_V7_PATTERN.fullmatch(value.lower()):
        return False
    parsed = uuid.UUID(value)
    return parsed.version == 7 and parsed.variant == uuid.RFC_4122


def validate_key(name: str, value: str) -> None:
    """按 ADR 0002 校验 Topic 标识符。"""

    if not KEY_PATTERN.fullmatch(value):
        raise ValueError(f"{name} 不符合 Topic 标识符规则")


def load_settings(args: argparse.Namespace) -> Settings:
    """读取并 fail-closed 校验环境参数。"""

    host = os.getenv("TC_MQTT_HOST", "")
    project_key = require_env("TC_PROJECT_KEY")
    device_key = require_env("TC_DEVICE_KEY")
    token = require_env("TC_DEVICE_TOKEN")
    validate_key("projectKey", project_key)
    validate_key("deviceKey", device_key)
    try:
        port = int(os.getenv("TC_MQTT_PORT", "8883"))
        report_interval = float(os.getenv("TC_REPORT_INTERVAL", "10"))
    except ValueError as exception:
        raise ValueError("端口和上报间隔必须是数字") from exception
    if not 1 <= port <= 65535 or report_interval <= 0:
        raise ValueError("端口必须在 1..65535，上报间隔必须大于 0")
    if not args.dry_run and not host:
        raise ValueError("缺少环境变量 TC_MQTT_HOST")
    if args.plaintext_local and host not in LOOPBACK_HOSTS:
        raise ValueError("明文模式只允许本机回环地址")

    ca_value = os.getenv("TC_MQTT_CA_FILE")
    ca_file = Path(ca_value) if ca_value else None
    if not args.dry_run and not args.plaintext_local:
        if ca_file is None or not ca_file.is_file():
            raise ValueError("TLS 模式要求 TC_MQTT_CA_FILE 指向可读 CA 文件")
    return Settings(
        host,
        port,
        project_key,
        device_key,
        token,
        ca_file,
        report_interval,
        args.plaintext_local,
    )


def require_env(name: str) -> str:
    """读取非空环境变量，但不把值写进异常。"""

    value = os.getenv(name)
    if value is None or not value.strip():
        raise ValueError(f"缺少环境变量 {name}")
    return value.strip()


def publish(client: Any, message: OutboundMessage) -> None:
    """固定以 QoS 1、非 retained 发布，并对立即失败进行显式报错。"""

    result = client.publish(message.topic, message.payload, qos=message.qos, retain=message.retain)
    if result.rc != 0:
        raise RuntimeError(f"MQTT 发布入队失败 rc={result.rc}")


def run(settings: Settings) -> None:
    """建立 MQTT 会话并持续上报；paho 只在实际运行时导入。"""

    try:
        import paho.mqtt.client as mqtt
    except ImportError as exception:
        raise RuntimeError("缺少 paho-mqtt，请先安装 requirements.txt") from exception

    protocol = DeviceProtocol(settings.project_key, settings.device_key)
    connected = threading.Event()
    # 项目身份已由用户名承载；Client ID 只保留设备键，避免两个 64 字符键拼接后超过常见 Broker 上限。
    client_id = f"sample-{settings.device_key}"
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=client_id, protocol=mqtt.MQTTv311)
    client.username_pw_set(f"{settings.project_key}/{settings.device_key}", settings.token)
    client.reconnect_delay_set(min_delay=1, max_delay=32)
    if not settings.plaintext_local:
        client.tls_set(ca_certs=str(settings.ca_file), tls_version=ssl.PROTOCOL_TLS_CLIENT)
        client.tls_insecure_set(False)

    def on_connect(active_client: Any, _userdata: Any, _flags: Any, reason_code: Any, _properties: Any) -> None:
        if reason_code == 0:
            active_client.subscribe(protocol.downlink_filter, qos=1)
            connected.set()
            print(f"已连接并订阅 {protocol.downlink_filter}")
        else:
            connected.clear()
            print(f"连接被拒绝 reason={reason_code}", file=sys.stderr)

    def on_disconnect(
        _active_client: Any,
        _userdata: Any,
        _disconnect_flags: Any,
        reason_code: Any,
        _properties: Any,
    ) -> None:
        connected.clear()
        print(f"连接断开 reason={reason_code}，等待有界退避重连", file=sys.stderr)

    def on_message(active_client: Any, _userdata: Any, message: Any) -> None:
        reply = protocol.handle_downlink(message.topic, message.payload)
        if reply is None:
            print(f"忽略不符合最小契约的下行 topic={message.topic}", file=sys.stderr)
            return
        publish(active_client, reply)
        print(f"已回复 topic={reply.topic}")

    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message
    client.connect(settings.host, settings.port, keepalive=60)
    client.loop_start()
    next_report_at = time.monotonic()
    try:
        while True:
            if not connected.wait(timeout=1.0):
                continue
            now = time.monotonic()
            if now < next_report_at:
                time.sleep(min(1.0, next_report_at - now))
                continue
            # 示例温度只用于验证协议链路，不代表真实传感器读数。
            report = protocol.property_report(20.0 + random.SystemRandom().random() * 10.0)
            publish(client, report)
            print(f"已上报 topic={report.topic}")
            next_report_at = now + settings.report_interval
    except KeyboardInterrupt:
        print("收到退出信号")
    finally:
        client.disconnect()
        client.loop_stop()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="ThingsLink 最小 MQTT 设备")
    parser.add_argument("--dry-run", action="store_true", help="生成一条报文但不连接 Broker")
    parser.add_argument(
        "--plaintext-local", action="store_true", help="仅允许本机回环地址使用明文 MQTT"
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        settings = load_settings(args)
        protocol = DeviceProtocol(settings.project_key, settings.device_key)
        if args.dry_run:
            message = protocol.property_report(23.5)
            print(
                json.dumps(
                    {
                        "settings": repr(settings),
                        "topic": message.topic,
                        "qos": message.qos,
                        "retain": message.retain,
                        "payload": json.loads(message.payload),
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        run(settings)
        return 0
    except (ValueError, RuntimeError, OSError) as exception:
        print(f"启动失败：{exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
