#!/usr/bin/env python3
"""校验 E2E 所连接 EMQX 已加载 ADR 0046 的 durable republish 合同。"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any


EXPECTED_TOPIC = "tc/internal/v1/ingress/uplink"
EXPECTED_FROM = "tc/private/device/+/+/tc/v1/+/+/up/#"
# ADR0194冻结的完整上行投影，运行规则不能用旧schema或服务器内部身份替代。
EXPECTED_SQL = 'SELECT 2 AS schemaVersion, id AS handoffId, username, substr(topic, strlen(client_attrs.tc_auth_mountpoint)) AS topic, base64_encode(payload) AS payloadBase64, qos, flags.retain AS retained, client_attrs.tc_auth_wire_client_id AS clientId, publish_received_at AS publishedAtMs, node AS brokerNode, CASE WHEN is_null(client_attrs.tc_auth_tenant_id) AND is_null(client_attrs.tc_auth_project_id) AND is_null(client_attrs.tc_auth_device_id) AND is_null(client_attrs.tc_auth_credential_version) THEN json_decode(\'null\') ELSE map_put(\'credentialVersion\', map_get(\'tc_auth_credential_version\', client_attrs, \'\'), map_put(\'deviceId\', map_get(\'tc_auth_device_id\', client_attrs, \'\'), map_put(\'projectId\', map_get(\'tc_auth_project_id\', client_attrs, \'\'), map_put(\'tenantId\', map_get(\'tc_auth_tenant_id\', client_attrs, \'\'), json_decode(\'{}\'))))) END AS authenticatedIdentity FROM "tc/private/device/+/+/tc/v1/+/+/up/#" WHERE qos = 1 AND flags.retain = false'



def verify(rule: dict[str, Any], legacy_raw_status: int, legacy_command_status: int) -> list[str]:
    """返回合同漂移列表；空列表表示当前规则与零旧消息入口同时成立。"""
    failures: list[str] = []
    if not isinstance(rule, dict):
        return ["durable rule 不是对象"]
    actual_sql = rule.get("sql")
    if not isinstance(actual_sql,str) or " ".join(actual_sql.split()) != " ".join(EXPECTED_SQL.split()):
        failures.append("durable SQL 不满足原Topic/Client ID还原及v2身份合同")
    if rule.get("from") != [EXPECTED_FROM]:
        failures.append("durable 来源必须是配置隔离后的唯一设备上行范围")
    if rule.get("id") != "tc_durable_uplink":
        failures.append("durable rule id 不匹配")
    if rule.get("enable") is not True:
        failures.append("durable rule 未启用")
    actions = rule.get("actions")
    if not isinstance(actions, list) or len(actions) != 1:
        failures.append("durable rule 必须恰有一个 action")
    else:
        action = actions[0]
        args = action.get("args") if isinstance(action, dict) else None
        if not isinstance(action, dict) or action.get("function") != "republish":
            failures.append("唯一 action 不是 republish")
        elif not isinstance(args, dict):
            failures.append("republish args 缺失")
        else:
            if args.get("topic") != EXPECTED_TOPIC:
                failures.append("republish topic 不匹配")
            if type(args.get("qos")) is not int or args.get("qos") != 1:
                failures.append("republish qos 不是 1")
            if args.get("payload") != "${.}":
                failures.append("republish payload 必须安全编码完整原投影")
            if args.get("retain") is not False:
                failures.append("republish retain 不是 false")
            if args.get("direct_dispatch") is not False:
                failures.append("republish direct_dispatch 不是 false")
    if legacy_raw_status != 404:
        failures.append(f"旧 tc_raw_uplink rule 仍可读（HTTP {legacy_raw_status}）")
    if legacy_command_status != 404:
        failures.append(f"旧 tc_command_reply bridge 仍可读（HTTP {legacy_command_status}）")
    return failures


def main() -> int:
    """读取 stdin 中的规则 JSON，并以退出码向 shell 返回资格结果。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--legacy-raw-status", required=True, type=int)
    parser.add_argument("--legacy-command-status", required=True, type=int)
    args = parser.parse_args()
    try:
        rule = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError) as exception:
        print(f"EMQX durable rule 响应不可读：{exception}", file=sys.stderr)
        return 1
    failures = verify(rule, args.legacy_raw_status, args.legacy_command_status)
    if failures:
        print("；".join(failures), file=sys.stderr)
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
