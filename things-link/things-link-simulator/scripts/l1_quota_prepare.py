#!/usr/bin/env python3
"""为 G1-C3d L1 建立并核验一次性租户配额策略。"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path
from typing import Any


ACCOUNT_SCOPE_SQL = """
SELECT json_build_object(
           'projectId', p.id,
           'projectKey', p.project_key,
           'tenantId', p.tenant_id
       )::text
  FROM sys_project p
  JOIN sys_project_member pm ON pm.project_id = p.id
  JOIN sys_account a ON a.id = pm.account_id
 WHERE a.email = :'email'
   AND a.deleted_at IS NULL
   AND pm.role = 'OWNER'
   AND pm.status = 'ACTIVE'
   AND p.deleted_at IS NULL
   AND p.status = 'ACTIVE';
"""


QUOTA_BINDING_SQL = r"""
BEGIN;

WITH source AS (
    SELECT policy.*
      FROM sys_tenant tenant
      JOIN sys_quota_policy policy ON policy.id = tenant.quota_policy_id
     WHERE tenant.id = :'tenant_id'::uuid
       AND tenant.status = 'ACTIVE'
       AND tenant.quota_policy_assignment_version = 2
       AND policy.code = 'PLAN_R1_FREE'
       AND policy.version = 1
)
INSERT INTO sys_quota_policy
SELECT (jsonb_populate_record(
    NULL::sys_quota_policy,
    to_jsonb(source) || jsonb_build_object(
        'id', :'policy_id',
        'code', 'L1_NIGHTLY',
        'version', 1,
        'device_count_limit', 2000,
        'uplink_message_daily_limit', 20000,
        'time_series_point_daily_limit', 200000,
        'downlink_message_daily_limit', 1200,
        'rest_api_rate_per_minute', 1200,
        'rest_api_read_rate_per_minute', 1200,
        'created_at', now(),
        'updated_at', now()
    )
)).* FROM source;

UPDATE sys_tenant tenant
   SET quota_policy_id = :'policy_id'::uuid,
       quota_policy_assignment_version = tenant.quota_policy_assignment_version + 1,
       updated_at = now()
 WHERE tenant.id = :'tenant_id'::uuid
   AND tenant.status = 'ACTIVE'
   AND tenant.quota_policy_assignment_version = 2
   AND tenant.quota_policy_id IN (
       SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_FREE' AND version = 1
   )
   AND EXISTS (
       SELECT 1
         FROM sys_project project
        WHERE project.id = :'project_id'::uuid
          AND project.tenant_id = tenant.id
          AND project.deleted_at IS NULL
          AND project.status = 'ACTIVE'
   );

SELECT CASE WHEN count(*) = 1 THEN 'true' ELSE 'false' END AS binding_valid
  FROM sys_project project
  JOIN sys_tenant tenant ON tenant.id = project.tenant_id
  JOIN sys_quota_policy policy ON policy.id = tenant.quota_policy_id
 WHERE project.id = :'project_id'::uuid
   AND tenant.id = :'tenant_id'::uuid
   AND policy.id = :'policy_id'::uuid
   AND policy.code = 'L1_NIGHTLY'
   AND policy.version = 1
   AND policy.device_count_limit = 2000
   AND policy.uplink_message_daily_limit = 20000
   AND policy.time_series_point_daily_limit = 200000
   AND policy.downlink_message_daily_limit = 1200
   AND policy.rest_api_rate_per_minute = 1200
   AND policy.rest_api_read_rate_per_minute = 1200
   AND tenant.quota_policy_assignment_version = 3
\gset
\if :binding_valid
\else
    \warn L1_NIGHTLY 配额绑定后置条件不满足
    SELECT 1 / 0;
\endif

SELECT json_build_object(
           'projectId', project.id,
           'tenantId', tenant.id,
           'policyId', policy.id,
           'policyCode', policy.code,
           'policyVersion', policy.version,
           'deviceCountLimit', policy.device_count_limit,
           'uplinkMessageDailyLimit', policy.uplink_message_daily_limit,
           'timeSeriesPointDailyLimit', policy.time_series_point_daily_limit,
           'downlinkMessageDailyLimit', policy.downlink_message_daily_limit,
           'restApiRatePerMinute', policy.rest_api_rate_per_minute,
           'restApiReadRatePerMinute', policy.rest_api_read_rate_per_minute,
           'assignmentVersion', tenant.quota_policy_assignment_version
       )::text
  FROM sys_project project
  JOIN sys_tenant tenant ON tenant.id = project.tenant_id
  JOIN sys_quota_policy policy ON policy.id = tenant.quota_policy_id
 WHERE project.id = :'project_id'::uuid;

COMMIT;
"""


def parse_args() -> argparse.Namespace:
    """解析隔离数据库连接、账号身份和证据输出参数。"""
    parser = argparse.ArgumentParser(description="G1-C3d L1 一次性配额准备")
    parser.add_argument("--postgres-container", default="tc-postgres")
    parser.add_argument("--postgres-user", required=True)
    parser.add_argument("--postgres-db", required=True)
    parser.add_argument("--email", required=True)
    parser.add_argument("--policy-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def run_psql(args: argparse.Namespace, sql: str, variables: dict[str, str]) -> dict[str, Any]:
    """执行只返回一份 JSON 的 psql 事务；零行、多行与 SQL 错误均拒绝继续。"""
    command = ["docker", "exec", "-i", args.postgres_container, "psql",
               "-U", args.postgres_user, "-d", args.postgres_db,
               "--no-psqlrc", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"]
    for name, value in variables.items():
        command.extend(["-v", f"{name}={value}"])
    completed = subprocess.run(command, input=sql, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError("PostgreSQL L1 配额准备失败: " + completed.stderr[-1200:])
    lines = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        raise RuntimeError(f"PostgreSQL L1 配额准备应返回唯一 JSON，实际为 {len(lines)} 行")
    try:
        document = json.loads(lines[0])
    except json.JSONDecodeError as exception:
        raise RuntimeError("PostgreSQL L1 配额准备返回了非法 JSON") from exception
    if not isinstance(document, dict):
        raise RuntimeError("PostgreSQL L1 配额准备 JSON 必须为对象")
    return document


def prepare(args: argparse.Namespace) -> dict[str, Any]:
    """解析唯一 OWNER 范围、绑定 2,000 台策略并写入非秘密审计证据。"""
    scope = run_psql(args, ACCOUNT_SCOPE_SQL, {"email": args.email})
    required_scope = ("projectId", "projectKey", "tenantId")
    if any(not isinstance(scope.get(key), str) or not scope[key] for key in required_scope):
        raise RuntimeError("OWNER 范围缺少 projectId/projectKey/tenantId")

    evidence = run_psql(args, QUOTA_BINDING_SQL, {
        "project_id": scope["projectId"],
        "tenant_id": scope["tenantId"],
        "policy_id": args.policy_id,
    })
    expected = {
        "projectId": scope["projectId"],
        "tenantId": scope["tenantId"],
        "policyId": args.policy_id,
        "policyCode": "L1_NIGHTLY",
        "policyVersion": 1,
        "deviceCountLimit": 2000,
        "uplinkMessageDailyLimit": 20000,
        "timeSeriesPointDailyLimit": 200000,
        "downlinkMessageDailyLimit": 1200,
        "restApiRatePerMinute": 1200,
        "restApiReadRatePerMinute": 1200,
        "assignmentVersion": 3,
    }
    if evidence != expected:
        raise RuntimeError(f"L1_NIGHTLY 配额证据与冻结值不一致: {evidence!r}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {**evidence, "projectKey": scope["projectKey"]}


def main() -> None:
    """执行配额准备并只向 shell 输出无空白的项目 ID 与项目 Key。"""
    result = prepare(parse_args())
    print(result["projectId"], result["projectKey"])


if __name__ == "__main__":
    main()
