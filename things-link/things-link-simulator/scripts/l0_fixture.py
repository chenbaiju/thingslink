#!/usr/bin/env python3
"""G1-C3 切片 L0 的真实 API 夹具生成与 messageId 数据库对账。"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


# switch-project 与 refresh 都要求登录响应写入的 HttpOnly tc_refresh；共享 opener 保持真实 Cookie 契约。
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


class AuthSession:
    """持有项目会话；长时间准备夹具时按服务端契约轮换 HttpOnly 刷新 Cookie。"""

    def __init__(self, base_url: str, access_token: str) -> None:
        self.base_url = base_url
        self.access_token = access_token

    def refresh(self) -> None:
        refreshed = request_json("POST", f"{self.base_url}/api/v1/auth/refresh")
        self.access_token = refreshed["accessToken"]


def parse_args() -> argparse.Namespace:
    """解析 prepare/verify 两阶段参数，凭据只允许写入构建输出目录。"""
    parser = argparse.ArgumentParser(description="G1-C3 L0 夹具与事实对账")
    sub = parser.add_subparsers(dest="command", required=True)
    prepare = sub.add_parser("prepare")
    prepare.add_argument("--base-url", required=True)
    prepare.add_argument("--email", required=True)
    prepare.add_argument("--password", required=True)
    prepare.add_argument("--project-id", required=True)
    prepare.add_argument("--project-key", required=True)
    prepare.add_argument("--device-count", type=int, default=100)
    prepare.add_argument("--profile-prefix", default="l0")
    prepare.add_argument("--command-key")
    prepare.add_argument("--output", type=Path, required=True)
    verify = sub.add_parser("verify")
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--properties-per-report", type=int, default=10)
    verify.add_argument("--postgres-container", default="tc-postgres")
    verify.add_argument("--postgres-user", default="thingslink")
    verify.add_argument("--postgres-db", default="thingslink")
    args = parser.parse_args()
    if getattr(args, "device_count", 1) < 1:
        parser.error("device-count 必须大于零")
    if getattr(args, "properties_per_report", 1) < 1:
        parser.error("properties-per-report 必须大于零")
    return args


def request_json(method: str, url: str, body: Any = None,
                 token: str | AuthSession | None = None, expected: tuple[int, ...] = (200,)) -> Any:
    """调用本地测试后端并严格校验状态；控制面 429 按 Retry-After 有界退避。"""
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    headers = {"Content-Type": "application/json"}
    refreshed = False
    for attempt in range(7):
        if token:
            access_token = token.access_token if isinstance(token, AuthSession) else token
            headers["Authorization"] = f"Bearer {access_token}"
        request = urllib.request.Request(url, data=payload, method=method, headers=headers)
        try:
            with OPENER.open(request, timeout=15) as response:
                if response.status not in expected:
                    raise RuntimeError(f"HTTP {response.status} {url}")
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as exception:
            diagnostic = exception.read().decode(errors="replace")[:800]
            exception.close()
            if exception.code == 401 and isinstance(token, AuthSession) and not refreshed:
                token.refresh()
                refreshed = True
                continue
            if exception.code == 429 and attempt < 5:
                # FREE 套餐写入桶是 10/s；L0 只顺序准备夹具，不通过改配置绕过生产保护。
                retry_after = exception.headers.get("Retry-After", "1")
                try:
                    delay = max(1, min(5, int(retry_after)))
                except ValueError:
                    delay = 1
                time.sleep(delay)
                continue
            raise RuntimeError(f"HTTP {exception.code} {url}: {diagnostic}") from exception
    raise AssertionError("有界重试循环不应到达此处")


def prepare(args: argparse.Namespace) -> None:
    """经生产 API 建物模型、100 台设备和一次性 MQTT 凭据。"""
    base = args.base_url.rstrip("/")
    login = request_json("POST", f"{base}/api/v1/auth/login",
                         {"email": args.email, "password": args.password})
    token = login["accessToken"]
    switched = request_json("POST", f"{base}/api/v1/auth/switch-project",
                            {"projectId": args.project_id}, token)
    token = AuthSession(base, switched["accessToken"])
    type_key = args.profile_prefix + "_numeric_" + args.project_id.replace("-", "")[:12]
    device_type = request_json(
        "POST", f"{base}/api/v1/projects/{args.project_id}/device-types",
        {"typeKey": type_key, "name": f"{args.profile_prefix.upper()} 十属性设备类型", "deviceKind": "DIRECT",
         "payloadProtocol": "STANDARD", "networkType": "WIFI"}, token, (201,))
    type_id = device_type["id"]
    property_keys = ["temperature", *(f"metric_{index:02d}" for index in range(2, 11))]
    for index, property_key in enumerate(property_keys):
        request_json(
            "POST", f"{base}/api/v1/projects/{args.project_id}/device-types/{type_id}/properties",
            {"propertyKey": property_key, "name": f"属性 {index + 1}",
             "accessType": "REPORT", "dataType": "NUMBER", "unit": "unit",
             "decimalPlaces": 2, "minimumValue": -100000, "maximumValue": 100000,
             "enumOptions": [], "onLabel": None, "offLabel": None, "sortOrder": index},
            token, (201,))
    if args.command_key:
        request_json(
            "POST", f"{base}/api/v1/projects/{args.project_id}/device-types/{type_id}/commands",
            {"commandKey": args.command_key, "name": "Nightly 探针命令",
             "description": "G1-C3d 受理延迟和下行闭环探针",
             "inputSchema": '{"type":"object"}', "outputSchema": '{"type":"object"}',
             "timeoutSeconds": 60, "sortOrder": 0}, token, (201,))
    request_json("POST", f"{base}/api/v1/projects/{args.project_id}/device-types/{type_id}/publish",
                 None, token)

    def create(index: int) -> dict[str, Any]:
        """创建单台设备并立即取得仅此一次返回的明文密钥。"""
        key = f"{args.profile_prefix}_device_{index:04d}"
        device = request_json(
            "POST", f"{base}/api/v1/projects/{args.project_id}/devices",
            {"deviceTypeId": type_id, "deviceKey": key,
             "name": f"{args.profile_prefix.upper()} 设备 {index:04d}",
             "description": f"G1-C3 {args.profile_prefix.upper()} 自动夹具", "location": "local"},
            token, (201,))
        credential = request_json(
            "POST", f"{base}/api/v1/projects/{args.project_id}/devices/{device['id']}/credentials",
            None, token, (201,))
        return {"deviceId": device["id"], "deviceKey": key,
                "accessToken": credential["plainSecret"], "gateway": False}

    # 夹具准备不是负载模型；顺序写入可防止准备阶段先打满控制面秒桶。
    devices = [create(index) for index in range(args.device_count)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"projectId": args.project_id, "projectKey": args.project_key,
                                      "devices": devices}), encoding="utf-8")
    os.chmod(args.output, 0o600)
    print(json.dumps({"projectKey": args.project_key, "deviceCount": len(devices)}))


def verify(args: argparse.Namespace) -> None:
    """按 PUBACK manifest 的唯一 messageId 对账 inbox 与十属性时序点，差异非零即失败。"""
    message_ids = sorted({line.strip() for line in args.manifest.read_text(encoding="utf-8").splitlines()
                          if line.strip()})
    if not message_ids:
        raise RuntimeError("property-report manifest 为空，L0 无有效 PUBACK 集合")
    values = ",".join(f"('{message_id}'::uuid)" for message_id in message_ids)
    sql = f"""
BEGIN;
CREATE TEMP TABLE l0_manifest(message_id uuid PRIMARY KEY) ON COMMIT DROP;
INSERT INTO l0_manifest(message_id) VALUES {values};
SELECT set_config(
    'app.project_id',
    (SELECT CASE WHEN count(DISTINCT project_id) = 1 THEN min(project_id::text) ELSE NULL END
       FROM sys_inbox_message JOIN l0_manifest USING(message_id)),
    true
);
SELECT json_build_object(
  'manifest', (SELECT count(*) FROM l0_manifest),
  'projects', (SELECT count(DISTINCT project_id) FROM sys_inbox_message i JOIN l0_manifest m USING(message_id)),
  'inbox', (SELECT count(*) FROM sys_inbox_message i JOIN l0_manifest m USING(message_id)),
  -- V20260808_1000 后 ts_property_point 是按 app_current_project() 过滤的安全视图；
  -- 先从 manifest/inbox 推导唯一项目再查询，既证明生产可见性，也不绕过视图读内部表。
  'points', (SELECT count(*) FROM ts_property_point p JOIN l0_manifest m USING(message_id))
)::text;
COMMIT;
"""
    command = ["docker", "exec", "-i", args.postgres_container, "psql", "-U", args.postgres_user,
               "-d", args.postgres_db, "--no-psqlrc", "-t", "-A", "-v", "ON_ERROR_STOP=1"]
    completed = subprocess.run(command, input=sql, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError("PostgreSQL L0 对账失败: " + completed.stderr[-800:])
    json_lines = [line for line in completed.stdout.splitlines() if line.startswith("{")]
    if len(json_lines) != 1:
        raise RuntimeError("PostgreSQL L0 对账未返回唯一 JSON 结果")
    result = json.loads(json_lines[0])
    expected_points = result["manifest"] * args.properties_per_report
    result["expectedPoints"] = expected_points
    result["passed"] = (result["projects"] == 1
                        and result["manifest"] == result["inbox"]
                        and result["points"] == expected_points)
    print(json.dumps(result, ensure_ascii=False))
    if not result["passed"]:
        raise RuntimeError("L0 PUBACK/inbox/时序点对账不一致")


def main() -> None:
    """执行所选阶段。"""
    args = parse_args()
    if args.command == "prepare":
        prepare(args)
    else:
        verify(args)


if __name__ == "__main__":
    main()
