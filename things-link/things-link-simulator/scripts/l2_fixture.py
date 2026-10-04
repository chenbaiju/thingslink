#!/usr/bin/env python3
"""G1-C3e L2 十租户配额预检与隔离 fixture 生成器。"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from types import ModuleType, SimpleNamespace
from typing import Any


TENANT_COUNT = 10
DEVICES_PER_TENANT = 1000
POLICY_CODE = "L2_CAPACITY"
POLICY_VERSION = 1
DEVICE_COUNT_LIMIT = 2000
TIME_SERIES_POINT_DAILY_LIMIT = 10_000_000
ASSIGNMENT_VERSION = 2
IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SECRET_KEYS = {"password", "accessToken", "plainSecret", "authorization", "token"}
SECRET_KEYS_LOWER = {key.lower() for key in SECRET_KEYS}


SCOPE_SQL = r"""
-- 若执行角色不能越过 RLS，这一设置会让 dev_* 空项目核验直接报错，而不是把不可见行误计为零。
SET row_security = off;

WITH requested AS (
    SELECT tenant_index, lower(email) AS email
      FROM jsonb_to_recordset(:'owners_json'::jsonb)
           AS owner(tenant_index integer, email text)
), active_project_count AS (
    SELECT tenant_id, count(*) AS project_count
      FROM sys_project
     WHERE deleted_at IS NULL AND status = 'ACTIVE'
     GROUP BY tenant_id
), device_row_count AS (
    SELECT project_id, count(*) AS device_count
      FROM dev_device
     GROUP BY project_id
), device_type_row_count AS (
    SELECT project_id, count(*) AS device_type_count
      FROM dev_type
     GROUP BY project_id
), resolved AS (
    SELECT requested.tenant_index AS "tenantIndex",
           tenant.id AS "tenantId",
           project.id AS "projectId",
           project.project_key AS "projectKey",
           active_project_count.project_count AS "activeProjectCount",
           coalesce(device_row_count.device_count, 0) AS "deviceRowCount",
           coalesce(device_type_row_count.device_type_count, 0) AS "deviceTypeRowCount"
      FROM requested
      JOIN sys_account account
        ON lower(account.email) = requested.email AND account.deleted_at IS NULL
      JOIN sys_project_member member
        ON member.account_id = account.id
       AND member.role = 'OWNER'
       AND member.status = 'ACTIVE'
      JOIN sys_project project
        ON project.id = member.project_id
       AND project.deleted_at IS NULL
       AND project.status = 'ACTIVE'
      JOIN sys_tenant tenant
        ON tenant.id = project.tenant_id AND tenant.status = 'ACTIVE'
      JOIN active_project_count ON active_project_count.tenant_id = tenant.id
      LEFT JOIN device_row_count ON device_row_count.project_id = project.id
      LEFT JOIN device_type_row_count ON device_type_row_count.project_id = project.id
)
SELECT json_build_object(
           'requestedCount', (SELECT count(*) FROM requested),
           'bindings', coalesce((SELECT json_agg(resolved ORDER BY "tenantIndex") FROM resolved), '[]'::json)
       )::text;
"""


QUOTA_SQL = r"""
BEGIN;

WITH source AS (
    SELECT * FROM sys_quota_policy WHERE code = 'FREE'
)
INSERT INTO sys_quota_policy
SELECT (jsonb_populate_record(
    NULL::sys_quota_policy,
    to_jsonb(source) || jsonb_build_object(
        'id', :'policy_id',
        'code', 'L2_CAPACITY',
        'version', 1,
        'device_count_limit', 2000,
        'time_series_point_daily_limit', 10000000,
        'created_at', now(),
        'updated_at', now()
    )
)).* FROM source
ON CONFLICT (code) DO NOTHING;

WITH requested AS (
    SELECT tenant_index, tenant_id::uuid AS tenant_id, project_id::uuid AS project_id
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text)
), eligible AS (
    SELECT requested.tenant_id
      FROM requested
      JOIN sys_tenant tenant ON tenant.id = requested.tenant_id
      JOIN sys_quota_policy current_policy ON current_policy.id = tenant.quota_policy_id
     WHERE (tenant.quota_policy_assignment_version = 1 AND current_policy.code = 'FREE')
        OR (tenant.quota_policy_id = :'policy_id'::uuid
            AND tenant.quota_policy_assignment_version = 2)
)
UPDATE sys_tenant tenant
   SET quota_policy_id = :'policy_id'::uuid,
       quota_policy_assignment_version = 2,
       updated_at = now()
  FROM eligible
 WHERE tenant.id = eligible.tenant_id
   AND tenant.quota_policy_assignment_version = 1;

WITH requested AS (
    SELECT tenant_index, tenant_id::uuid AS tenant_id, project_id::uuid AS project_id
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text)
), verified AS (
    SELECT requested.tenant_index, project.id AS project_id, tenant.id AS tenant_id
      FROM requested
      JOIN sys_project project
        ON project.id = requested.project_id
       AND project.tenant_id = requested.tenant_id
       AND project.deleted_at IS NULL
       AND project.status = 'ACTIVE'
      JOIN sys_tenant tenant
        ON tenant.id = requested.tenant_id AND tenant.status = 'ACTIVE'
      JOIN sys_quota_policy policy ON policy.id = tenant.quota_policy_id
     WHERE policy.id = :'policy_id'::uuid
       AND policy.code = 'L2_CAPACITY'
       AND policy.version = 1
       AND policy.device_count_limit = 2000
       AND policy.time_series_point_daily_limit = 10000000
       AND tenant.quota_policy_assignment_version = 2
)
SELECT CASE WHEN count(*) = 10
                  AND count(DISTINCT tenant_index) = 10
                  AND min(tenant_index) = 0
                  AND max(tenant_index) = 9
                  AND count(DISTINCT tenant_id) = 10
                  AND count(DISTINCT project_id) = 10
            THEN 'true' ELSE 'false' END AS binding_valid
  FROM verified
\gset
\if :binding_valid
\else
    \echo 'L2_CAPACITY 十租户配额绑定后置条件不满足'
    ROLLBACK;
    \quit 3
\endif

WITH requested AS (
    SELECT tenant_index, tenant_id::uuid AS tenant_id, project_id::uuid AS project_id
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text)
), verified AS (
    SELECT requested.tenant_index AS "tenantIndex",
           project.id AS "projectId",
           project.project_key AS "projectKey",
           tenant.id AS "tenantId",
           policy.id AS "policyId",
           policy.code AS "policyCode",
           policy.version AS "policyVersion",
           policy.device_count_limit AS "deviceCountLimit",
           policy.time_series_point_daily_limit AS "timeSeriesPointDailyLimit",
           tenant.quota_policy_assignment_version AS "assignmentVersion"
      FROM requested
      JOIN sys_project project
        ON project.id = requested.project_id
       AND project.tenant_id = requested.tenant_id
       AND project.deleted_at IS NULL
       AND project.status = 'ACTIVE'
      JOIN sys_tenant tenant
        ON tenant.id = requested.tenant_id AND tenant.status = 'ACTIVE'
      JOIN sys_quota_policy policy ON policy.id = tenant.quota_policy_id
     WHERE policy.id = :'policy_id'::uuid
       AND policy.code = 'L2_CAPACITY'
       AND policy.version = 1
       AND policy.device_count_limit = 2000
       AND policy.time_series_point_daily_limit = 10000000
       AND tenant.quota_policy_assignment_version = 2
)
SELECT json_build_object(
           'bindings', coalesce((SELECT json_agg(verified ORDER BY "tenantIndex") FROM verified), '[]'::json)
       )::text;

COMMIT;
"""


CLEANUP_QUOTA_SQL = r"""
BEGIN;

WITH requested AS (
    SELECT tenant_index, tenant_id::uuid AS tenant_id, project_id::uuid AS project_id, lower(email) AS email
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text, email text)
), active_project_count AS (
    SELECT tenant_id, count(*) AS project_count
      FROM sys_project
     WHERE deleted_at IS NULL AND status = 'ACTIVE'
     GROUP BY tenant_id
), scope AS (
    SELECT requested.tenant_index, tenant.id AS tenant_id, project.id AS project_id,
           tenant.quota_policy_id, tenant.quota_policy_assignment_version,
           active_project_count.project_count
      FROM requested
      JOIN sys_tenant tenant ON tenant.id = requested.tenant_id AND tenant.status = 'ACTIVE'
      JOIN sys_project project
        ON project.id = requested.project_id
       AND project.tenant_id = requested.tenant_id
       AND project.deleted_at IS NULL
       AND project.status = 'ACTIVE'
      JOIN sys_project_member member
        ON member.project_id = project.id
       AND member.role = 'OWNER'
       AND member.status = 'ACTIVE'
      JOIN sys_account account
        ON account.id = member.account_id
       AND lower(account.email) = requested.email
       AND account.deleted_at IS NULL
      JOIN active_project_count ON active_project_count.tenant_id = tenant.id
), state AS (
    SELECT count(*) AS scope_count,
           count(DISTINCT tenant_index) AS index_count,
           count(DISTINCT tenant_id) AS tenant_count,
           count(DISTINCT project_id) AS project_count,
           count(*) FILTER (WHERE project_count = 1) AS isolated_project_count,
           count(*) FILTER (
               WHERE quota_policy_id = :'policy_id'::uuid
                 AND quota_policy_assignment_version = 2
           ) AS l2_count,
           count(*) FILTER (
               WHERE quota_policy_id = (SELECT id FROM sys_quota_policy WHERE code = 'FREE')
                 AND quota_policy_assignment_version = 3
           ) AS cleaned_count
      FROM scope
), policy_state AS (
    SELECT count(*) FILTER (
               WHERE id = :'policy_id'::uuid
                 AND code = 'L2_CAPACITY'
                 AND version = 1
                 AND device_count_limit = 2000
                 AND time_series_point_daily_limit = 10000000
           ) AS exact_policy_count,
           count(*) FILTER (WHERE code = 'L2_CAPACITY') AS l2_code_count,
           (SELECT count(*) FROM sys_tenant WHERE quota_policy_id = :'policy_id'::uuid) AS reference_count
      FROM sys_quota_policy
)
SELECT CASE
         WHEN state.scope_count = 10
          AND state.index_count = 10
          AND state.tenant_count = 10
          AND state.project_count = 10
          AND state.isolated_project_count = 10
          AND ((state.l2_count = 10
                AND policy_state.exact_policy_count = 1
                AND policy_state.l2_code_count = 1
                AND policy_state.reference_count = 10)
               OR
               (state.cleaned_count = 10
                AND policy_state.exact_policy_count = 0
                AND policy_state.l2_code_count = 0
                AND policy_state.reference_count = 0))
         THEN 'true' ELSE 'false'
       END AS cleanup_precondition
  FROM state CROSS JOIN policy_state
\gset
\if :cleanup_precondition
\else
    \echo 'L2_CAPACITY 清理前置条件不满足或策略被其他租户引用'
    ROLLBACK;
    \quit 4
\endif

WITH requested AS (
    SELECT tenant_id::uuid AS tenant_id
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text)
), free_policy AS (
    SELECT id FROM sys_quota_policy WHERE code = 'FREE'
)
UPDATE sys_tenant tenant
   SET quota_policy_id = free_policy.id,
       quota_policy_assignment_version = 3,
       updated_at = now()
  FROM requested CROSS JOIN free_policy
 WHERE tenant.id = requested.tenant_id
   AND tenant.quota_policy_id = :'policy_id'::uuid
   AND tenant.quota_policy_assignment_version = 2;

DELETE FROM sys_quota_policy policy
 WHERE policy.id = :'policy_id'::uuid
   AND policy.code = 'L2_CAPACITY'
   AND policy.version = 1
   AND policy.device_count_limit = 2000
   AND policy.time_series_point_daily_limit = 10000000
   AND NOT EXISTS (
       SELECT 1 FROM sys_tenant tenant WHERE tenant.quota_policy_id = policy.id
   );

WITH requested AS (
    SELECT tenant_index, tenant_id::uuid AS tenant_id, project_id::uuid AS project_id
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text)
), cleaned AS (
    SELECT requested.tenant_index AS "tenantIndex",
           tenant.id AS "tenantId", project.id AS "projectId",
           policy.id AS "policyId", policy.code AS "policyCode",
           policy.version AS "policyVersion",
           policy.device_count_limit AS "deviceCountLimit",
           tenant.quota_policy_assignment_version AS "assignmentVersion"
      FROM requested
      JOIN sys_tenant tenant ON tenant.id = requested.tenant_id AND tenant.status = 'ACTIVE'
      JOIN sys_project project
        ON project.id = requested.project_id
       AND project.tenant_id = requested.tenant_id
       AND project.deleted_at IS NULL
       AND project.status = 'ACTIVE'
      JOIN sys_quota_policy policy ON policy.id = tenant.quota_policy_id
     WHERE policy.code = 'FREE'
       AND tenant.quota_policy_assignment_version = 3
)
SELECT json_build_object(
           'bindings', coalesce((SELECT json_agg(cleaned ORDER BY "tenantIndex") FROM cleaned), '[]'::json),
           'remainingPolicyRows', (SELECT count(*) FROM sys_quota_policy WHERE id = :'policy_id'::uuid),
           'remainingPolicyReferences', (SELECT count(*) FROM sys_tenant WHERE quota_policy_id = :'policy_id'::uuid)
       )::text;

COMMIT;
"""


CLEANUP_PREFLIGHT_SQL = r"""
WITH requested AS (
    SELECT tenant_index, tenant_id::uuid AS tenant_id, project_id::uuid AS project_id, lower(email) AS email
      FROM jsonb_to_recordset(:'bindings_json'::jsonb)
           AS binding(tenant_index integer, tenant_id text, project_id text, email text)
), active_project_count AS (
    SELECT tenant_id, count(*) AS project_count
      FROM sys_project
     WHERE deleted_at IS NULL AND status = 'ACTIVE'
     GROUP BY tenant_id
), scope AS (
    SELECT requested.tenant_index, tenant.id AS tenant_id, project.id AS project_id,
           tenant.quota_policy_id, tenant.quota_policy_assignment_version,
           active_project_count.project_count
      FROM requested
      JOIN sys_tenant tenant ON tenant.id = requested.tenant_id AND tenant.status = 'ACTIVE'
      JOIN sys_project project
        ON project.id = requested.project_id
       AND project.tenant_id = requested.tenant_id
       AND project.deleted_at IS NULL
       AND project.status = 'ACTIVE'
      JOIN sys_project_member member
        ON member.project_id = project.id
       AND member.role = 'OWNER'
       AND member.status = 'ACTIVE'
      JOIN sys_account account
        ON account.id = member.account_id
       AND lower(account.email) = requested.email
       AND account.deleted_at IS NULL
      JOIN active_project_count ON active_project_count.tenant_id = tenant.id
), state AS (
    SELECT count(*) AS scope_count,
           count(DISTINCT tenant_index) AS index_count,
           count(DISTINCT tenant_id) AS tenant_count,
           count(DISTINCT project_id) AS project_count,
           count(*) FILTER (WHERE project_count = 1) AS isolated_project_count,
           count(*) FILTER (
               WHERE quota_policy_id = :'policy_id'::uuid
                 AND quota_policy_assignment_version = 2
           ) AS l2_count,
           count(*) FILTER (
               WHERE quota_policy_id = (SELECT id FROM sys_quota_policy WHERE code = 'FREE')
                 AND quota_policy_assignment_version = 3
           ) AS cleaned_count
      FROM scope
), policy_state AS (
    SELECT count(*) FILTER (
               WHERE id = :'policy_id'::uuid
                 AND code = 'L2_CAPACITY'
                 AND version = 1
                 AND device_count_limit = 2000
                 AND time_series_point_daily_limit = 10000000
           ) AS exact_policy_count,
           count(*) FILTER (WHERE code = 'L2_CAPACITY') AS l2_code_count,
           (SELECT count(*) FROM sys_tenant WHERE quota_policy_id = :'policy_id'::uuid) AS reference_count
      FROM sys_quota_policy
)
SELECT json_build_object(
           'scopeCount', state.scope_count,
           'indexCount', state.index_count,
           'tenantCount', state.tenant_count,
           'projectCount', state.project_count,
           'isolatedProjectCount', state.isolated_project_count,
           'l2BindingCount', state.l2_count,
           'cleanedBindingCount', state.cleaned_count,
           'exactPolicyCount', policy_state.exact_policy_count,
           'l2CodeCount', policy_state.l2_code_count,
           'policyReferenceCount', policy_state.reference_count
       )::text
  FROM state CROSS JOIN policy_state;
"""


def parse_args() -> argparse.Namespace:
    """解析配额预检与设备构建两个显式阶段，避免半成品被误当作完整 fixture。"""
    parser = argparse.ArgumentParser(description="G1-C3e L2 十租户 fixture")
    sub = parser.add_subparsers(dest="command", required=True)
    quota = sub.add_parser("quota")
    quota.add_argument("--owner-secrets", type=Path, required=True)
    quota.add_argument("--policy-id", required=True)
    quota.add_argument("--postgres-container", default="tc-postgres")
    quota.add_argument("--postgres-user", required=True)
    quota.add_argument("--postgres-db", required=True)
    quota.add_argument("--output", type=Path, required=True)
    prepare = sub.add_parser("prepare")
    prepare.add_argument("--base-url", required=True)
    prepare.add_argument("--owner-secrets", type=Path, required=True)
    prepare.add_argument("--quota-evidence", type=Path, required=True)
    prepare.add_argument("--assignment-plan", type=Path, required=True)
    prepare.add_argument("--secret-output-dir", type=Path, required=True)
    prepare.add_argument("--output", type=Path, required=True)
    prepare.add_argument("--run-id", required=True)
    prepare.add_argument("--environment-fingerprint", required=True)
    cleanup = sub.add_parser("cleanup")
    cleanup.add_argument("--base-url", required=True)
    cleanup.add_argument("--owner-secrets", type=Path, required=True)
    cleanup.add_argument("--fixture", type=Path, required=True)
    cleanup.add_argument("--run-id", required=True)
    cleanup.add_argument("--postgres-container", default="tc-postgres")
    cleanup.add_argument("--postgres-user", required=True)
    cleanup.add_argument("--postgres-db", required=True)
    cleanup.add_argument("--output", type=Path, required=True)
    cleanup.add_argument("--confirm-isolated-capacity-environment", action="store_true", required=True)
    return parser.parse_args()


def read_object(path: Path, description: str) -> dict[str, Any]:
    """读取唯一 JSON 对象；缺文件、非法 JSON 或错误顶层类型全部 fail-closed。"""
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise RuntimeError(f"{description} 不可读或不是合法 JSON: {path}") from exception
    if not isinstance(document, dict):
        raise RuntimeError(f"{description} 顶层必须是 JSON 对象")
    return document


def load_owners(path: Path) -> list[dict[str, Any]]:
    """读取十个 OWNER 的秘密输入，仅把 tenantIndex/email 送往数据库预检。"""
    tenants = read_object(path, "OWNER 秘密清单").get("tenants")
    if not isinstance(tenants, list) or len(tenants) != TENANT_COUNT:
        raise RuntimeError("OWNER 秘密清单必须精确包含 10 个 tenants")
    indexes: list[int] = []
    emails: set[str] = set()
    normalized: list[dict[str, Any]] = []
    for tenant in tenants:
        if not isinstance(tenant, dict):
            raise RuntimeError("OWNER 秘密清单中的 tenant 必须是对象")
        index = tenant.get("tenantIndex")
        email = tenant.get("email")
        password = tenant.get("password")
        if not isinstance(index, int) or isinstance(index, bool):
            raise RuntimeError("tenantIndex 必须是整数")
        if not isinstance(email, str) or not email.strip() or email.lower() in emails:
            raise RuntimeError("十租户 email 必须非空且唯一")
        if not isinstance(password, str) or not password:
            raise RuntimeError("每个租户必须在秘密清单中提供 password")
        indexes.append(index)
        emails.add(email.lower())
        normalized.append({"tenantIndex": index, "email": email, "password": password})
    if sorted(indexes) != list(range(TENANT_COUNT)):
        raise RuntimeError("tenantIndex 必须精确覆盖 0..9")
    return sorted(normalized, key=lambda item: item["tenantIndex"])


def run_psql(args: argparse.Namespace, sql: str, variables: dict[str, str]) -> dict[str, Any]:
    """通过 argv 传递 psql 变量并要求唯一 JSON 行，拒绝拼接未转义业务值。"""
    command = ["docker", "exec", "-i", args.postgres_container, "psql",
               "-U", args.postgres_user, "-d", args.postgres_db,
               "--no-psqlrc", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1"]
    for name, value in variables.items():
        command.extend(["-v", f"{name}={value}"])
    completed = subprocess.run(command, input=sql, text=True, capture_output=True, check=False)
    if completed.returncode != 0:
        raise RuntimeError("PostgreSQL L2 fixture 事务失败: " + completed.stderr[-1200:])
    lines = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        raise RuntimeError(f"PostgreSQL L2 fixture 应返回唯一 JSON，实际为 {len(lines)} 行")
    try:
        document = json.loads(lines[0])
    except json.JSONDecodeError as exception:
        raise RuntimeError("PostgreSQL L2 fixture 返回了非法 JSON") from exception
    if not isinstance(document, dict):
        raise RuntimeError("PostgreSQL L2 fixture JSON 必须是对象")
    return document


def validate_scope(document: dict[str, Any]) -> list[dict[str, Any]]:
    """确认十个账号各自唯一映射到一个活跃 tenant/project，而非把 JOIN 重复当成功。"""
    bindings = document.get("bindings")
    if document.get("requestedCount") != TENANT_COUNT or not isinstance(bindings, list):
        raise RuntimeError("OWNER 范围未返回精确十个请求")
    if len(bindings) != TENANT_COUNT:
        raise RuntimeError("OWNER 范围不是精确 10 tenant/10 project")
    indexes = [item.get("tenantIndex") for item in bindings if isinstance(item, dict)]
    if indexes != list(range(TENANT_COUNT)):
        raise RuntimeError("OWNER 范围 tenantIndex 未精确覆盖 0..9")
    for key in ("tenantId", "projectId", "projectKey"):
        values = [item.get(key) for item in bindings]
        if len(set(values)) != TENANT_COUNT or any(not isinstance(value, str) or not value for value in values):
            raise RuntimeError(f"OWNER 范围的 {key} 必须十项非空且唯一")
    if any(item.get("activeProjectCount") != 1 for item in bindings):
        raise RuntimeError("每个 fixture tenant 必须精确拥有一个活跃 project")
    if any(item.get("deviceRowCount") != 0 for item in bindings):
        raise RuntimeError("L2 fixture 只允许在从未创建过设备的空 project 建立")
    if any(item.get("deviceTypeRowCount") != 0 for item in bindings):
        # cleanup 的正式设备 API 只软删 device，不物理删除模型与历史事实；禁止跨 profile 复用是更诚实的边界。
        raise RuntimeError("L2 fixture 只允许在从未创建过 device type 的空 project 建立")
    return bindings


def validate_quota_bindings(document: dict[str, Any], policy_id: str) -> list[dict[str, Any]]:
    """核验数据库后置条件；任一租户漂移都阻止建机。"""
    bindings = document.get("bindings")
    if not isinstance(bindings, list) or len(bindings) != TENANT_COUNT:
        raise RuntimeError("L2_CAPACITY 配额后置条件没有精确覆盖十租户")
    expected_indexes = list(range(TENANT_COUNT))
    if [item.get("tenantIndex") for item in bindings if isinstance(item, dict)] != expected_indexes:
        raise RuntimeError("L2_CAPACITY 配额证据 tenantIndex 不连续")
    for key in ("tenantId", "projectId", "projectKey"):
        values = [item.get(key) for item in bindings if isinstance(item, dict)]
        if len(values) != TENANT_COUNT or len(set(values)) != TENANT_COUNT \
                or any(not isinstance(value, str) or not value for value in values):
            raise RuntimeError(f"L2_CAPACITY 配额证据的 {key} 必须十项非空且唯一")
    for binding in bindings:
        expected = {
            "policyId": policy_id,
            "policyCode": POLICY_CODE,
            "policyVersion": POLICY_VERSION,
            "deviceCountLimit": DEVICE_COUNT_LIMIT,
            "timeSeriesPointDailyLimit": TIME_SERIES_POINT_DAILY_LIMIT,
            "assignmentVersion": ASSIGNMENT_VERSION,
        }
        if any(binding.get(key) != value for key, value in expected.items()):
            raise RuntimeError(f"L2_CAPACITY 配额冻结值漂移: tenantIndex={binding.get('tenantIndex')}")
    return bindings


def reject_secret_fields(document: dict[str, Any]) -> None:
    """递归拒绝常见凭据键，防止公开清单被偷换成秘密输入。"""
    def inspect(value: Any) -> None:
        if isinstance(value, dict):
            forbidden = {key for key in value if isinstance(key, str) and key.lower() in SECRET_KEYS_LOWER}
            if forbidden:
                raise RuntimeError(f"公开 fixture 证据包含秘密字段: {sorted(forbidden)}")
            for nested in value.values():
                inspect(nested)
        elif isinstance(value, list):
            for nested in value:
                inspect(nested)

    inspect(document)


def write_public_json(path: Path, document: dict[str, Any]) -> None:
    """检查并写入公开 JSON 证据。"""
    reject_secret_fields(document)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def require_outside_evidence(secret_path: Path, public_output: Path, description: str) -> None:
    """阻止秘密输入/输出落入通常会被整目录上传的证据目录。"""
    evidence_directory = public_output.resolve().parent
    resolved_secret = secret_path.resolve()
    if resolved_secret == evidence_directory or resolved_secret.is_relative_to(evidence_directory):
        raise RuntimeError(f"{description} 必须位于公开证据目录之外")


def prepare_quota(args: argparse.Namespace) -> dict[str, Any]:
    """在单事务内创建/复用严格相同的策略并绑定十个已隔离租户。"""
    require_outside_evidence(args.owner_secrets, args.output, "OWNER 秘密清单")
    owners = load_owners(args.owner_secrets)
    owners_json = json.dumps(
        [{"tenant_index": owner["tenantIndex"], "email": owner["email"]} for owner in owners],
        separators=(",", ":"), ensure_ascii=True)
    scope = validate_scope(run_psql(args, SCOPE_SQL, {"owners_json": owners_json}))
    bindings_json = json.dumps([
        {"tenant_index": item["tenantIndex"], "tenant_id": item["tenantId"],
         "project_id": item["projectId"]}
        for item in scope
    ], separators=(",", ":"), ensure_ascii=True)
    evidence = run_psql(args, QUOTA_SQL, {"policy_id": args.policy_id, "bindings_json": bindings_json})
    bindings = validate_quota_bindings(evidence, args.policy_id)
    result = {"schemaVersion": 1, "tenantCount": TENANT_COUNT,
              "devicesPerTenant": DEVICES_PER_TENANT, "bindings": bindings,
              "qualification": {
                  "outcome": "PASS", "tenantIndexes": list(range(TENANT_COUNT)),
                  "oneActiveProjectPerTenant": True, "quotaBindingExact": True,
              }}
    write_public_json(args.output, result)
    return result


def load_assignment_plan(path: Path) -> list[dict[str, Any]]:
    """要求每个分片只服务一个租户，避免现有模拟器的单 projectKey 请求被错误混租。"""
    shards = read_object(path, "发生器分配计划").get("shards")
    if not isinstance(shards, list) or not shards:
        raise RuntimeError("发生器分配计划必须包含非空 shards")
    normalized: list[dict[str, Any]] = []
    global_shard_ids: set[tuple[str, str]] = set()
    totals = {index: 0 for index in range(TENANT_COUNT)}
    for shard in shards:
        if not isinstance(shard, dict):
            raise RuntimeError("发生器 shard 必须是对象")
        tenant_index = shard.get("tenantIndex")
        host_id = shard.get("hostId")
        shard_id = shard.get("shardId")
        device_count = shard.get("deviceCount")
        if tenant_index not in totals:
            raise RuntimeError("shard tenantIndex 必须位于 0..9")
        if not isinstance(host_id, str) or not IDENTIFIER.fullmatch(host_id):
            raise RuntimeError("hostId 必须是稳定的小写字母/数字/连字符标识")
        global_shard_id = (host_id, shard_id)
        if (not isinstance(shard_id, str) or not IDENTIFIER.fullmatch(shard_id)
                or global_shard_id in global_shard_ids):
            raise RuntimeError("hostId/shardId 必须全局唯一且格式稳定")
        if not isinstance(device_count, int) or isinstance(device_count, bool) or not 1 <= device_count <= 1000:
            raise RuntimeError("单 shard deviceCount 必须位于 1..1000")
        global_shard_ids.add(global_shard_id)
        totals[tenant_index] += device_count
        normalized.append({"tenantIndex": tenant_index, "hostId": host_id,
                           "shardId": shard_id, "deviceCount": device_count})
    if any(total != DEVICES_PER_TENANT for total in totals.values()):
        raise RuntimeError("每个 tenant 的 shard deviceCount 合计必须精确为 1000")
    return normalized


def load_l0_fixture() -> ModuleType:
    """从同目录加载既有生产 API fixture，复用物模型与一次性凭据合同。"""
    path = Path(__file__).with_name("l0_fixture.py")
    specification = importlib.util.spec_from_file_location("l2_l0_fixture", path)
    if specification is None or specification.loader is None:
        raise RuntimeError("无法加载 l0_fixture.py")
    module = importlib.util.module_from_spec(specification)
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


def prepare_fixture(args: argparse.Namespace) -> dict[str, Any]:
    """配额后置条件通过后，经真实 API 创建十属性模型和每租户一千台设备。"""
    require_outside_evidence(args.owner_secrets, args.output, "OWNER 秘密清单")
    require_outside_evidence(args.secret_output_dir, args.output, "设备秘密输出目录")
    owners = {item["tenantIndex"]: item for item in load_owners(args.owner_secrets)}
    quota = read_object(args.quota_evidence, "L2 配额证据")
    qualification = quota.get("qualification")
    if quota.get("schemaVersion") != 1 or not isinstance(qualification, dict) \
            or qualification.get("outcome") != "PASS":
        raise RuntimeError("L2 配额证据 schema 或资格结论无效")
    raw_bindings = quota.get("bindings")
    if not isinstance(raw_bindings, list) or not raw_bindings or not isinstance(raw_bindings[0], dict):
        raise RuntimeError("L2 配额证据缺少 bindings")
    policy_ids = {binding.get("policyId") for binding in raw_bindings if isinstance(binding, dict)}
    if len(policy_ids) != 1 or not isinstance(next(iter(policy_ids)), str):
        raise RuntimeError("L2 配额证据必须只有一个非空 policyId")
    bindings = validate_quota_bindings(quota, next(iter(policy_ids)))
    if quota.get("tenantCount") != TENANT_COUNT or quota.get("devicesPerTenant") != DEVICES_PER_TENANT:
        raise RuntimeError("L2 配额证据的租户或设备规模漂移")
    shards = load_assignment_plan(args.assignment_plan)
    if not isinstance(args.run_id, str) or not IDENTIFIER.fullmatch(args.run_id):
        raise RuntimeError("run-id 必须是稳定的小写字母/数字/连字符标识")
    if not isinstance(args.environment_fingerprint, str) \
            or SHA256.fullmatch(args.environment_fingerprint) is None:
        raise RuntimeError("environment-fingerprint 必须是 64 位小写 SHA-256")
    args.secret_output_dir.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(args.secret_output_dir, 0o700)
    except OSError:
        # Windows ACL 不等价于 POSIX mode；最终上传边界仍由独立秘密目录和 workflow 排除负责。
        pass
    l0 = load_l0_fixture()
    public_tenants: list[dict[str, Any]] = []
    bindings_by_index = {item["tenantIndex"]: item for item in bindings}
    shards_by_tenant = {
        index: [shard for shard in shards if shard["tenantIndex"] == index]
        for index in range(TENANT_COUNT)
    }
    for tenant_index in range(TENANT_COUNT):
        binding = bindings_by_index[tenant_index]
        owner = owners[tenant_index]
        with tempfile.TemporaryDirectory(dir=args.secret_output_dir) as temporary:
            tenant_secret = Path(temporary) / "credentials.json"
            l0.prepare(SimpleNamespace(
                base_url=args.base_url, email=owner["email"], password=owner["password"],
                project_id=binding["projectId"], project_key=binding["projectKey"],
                device_count=DEVICES_PER_TENANT, profile_prefix=f"l2_t{tenant_index}",
                command_key="l2_probe", output=tenant_secret))
            devices = read_object(tenant_secret, "L0 秘密设备清单").get("devices")
            if not isinstance(devices, list) or len(devices) != DEVICES_PER_TENANT:
                raise RuntimeError(f"tenantIndex={tenant_index} 未生成精确 1000 台设备")
            offset = 0
            public_devices: list[dict[str, Any]] = []
            for shard in shards_by_tenant[tenant_index]:
                shard_devices = devices[offset:offset + shard["deviceCount"]]
                if len(shard_devices) != shard["deviceCount"]:
                    raise RuntimeError("分片计划超出实际设备清单")
                secret_document = {
                    "runId": args.run_id, "tenantIndex": tenant_index,
                    "projectId": binding["projectId"], "projectKey": binding["projectKey"],
                    "hostId": shard["hostId"], "shardId": shard["shardId"],
                    "devices": shard_devices,
                }
                secret_path = args.secret_output_dir / shard["hostId"] / f"{shard['shardId']}.json"
                secret_path.parent.mkdir(parents=True, exist_ok=True)
                secret_path.write_text(json.dumps(secret_document, separators=(",", ":")), encoding="utf-8")
                try:
                    os.chmod(secret_path, 0o600)
                except OSError:
                    pass
                for device in shard_devices:
                    if not isinstance(device, dict) or not device.get("deviceId") or not device.get("deviceKey"):
                        raise RuntimeError("L0 设备结果缺少 deviceId/deviceKey")
                    public_devices.append({"deviceId": device["deviceId"], "deviceKey": device["deviceKey"],
                                           "hostId": shard["hostId"], "shardId": shard["shardId"]})
                offset += shard["deviceCount"]
            if offset != DEVICES_PER_TENANT:
                raise RuntimeError("租户设备未被分片计划精确消费")
        public_tenants.append({
            "tenantIndex": tenant_index, "tenantId": binding["tenantId"],
            "projectId": binding["projectId"], "projectKey": binding["projectKey"],
            "policyId": binding["policyId"], "policyCode": POLICY_CODE,
            "policyVersion": POLICY_VERSION, "deviceCountLimit": DEVICE_COUNT_LIMIT,
            "timeSeriesPointDailyLimit": TIME_SERIES_POINT_DAILY_LIMIT,
            "assignmentVersion": ASSIGNMENT_VERSION, "devices": public_devices,
        })
    result = {"schemaVersion": 1, "runId": args.run_id,
              "environmentFingerprint": args.environment_fingerprint,
              "tenantCount": TENANT_COUNT,
              "projectCount": TENANT_COUNT, "deviceCount": TENANT_COUNT * DEVICES_PER_TENANT,
              "devicesPerTenant": DEVICES_PER_TENANT, "tenants": public_tenants,
              "qualification": {
                  "outcome": "PASS", "tenantIndexes": list(range(TENANT_COUNT)),
                  "projects": TENANT_COUNT, "devices": TENANT_COUNT * DEVICES_PER_TENANT,
                  "credentialsSeparated": True, "assignmentPlanExact": True,
              }}
    write_public_json(args.output, result)
    return result


def validate_cleanup_fixture(document: dict[str, Any], run_id: str) -> list[dict[str, Any]]:
    """把公开 fixture 收窄为唯一允许删除的 10,000 台设备集合。"""
    reject_secret_fields(document)
    if document.get("schemaVersion") != 1 or document.get("runId") != run_id:
        raise RuntimeError("cleanup 的 run-id 与公开 fixture 不一致")
    qualification = document.get("qualification")
    if not isinstance(qualification, dict) or qualification.get("outcome") != "PASS":
        raise RuntimeError("cleanup 只接受资格为 PASS 的公开 fixture")
    if (document.get("tenantCount"), document.get("projectCount"), document.get("deviceCount"),
            document.get("devicesPerTenant")) != (10, 10, 10_000, 1000):
        raise RuntimeError("公开 fixture 的十租户规模字段不一致")
    tenants = document.get("tenants")
    if not isinstance(tenants, list) or len(tenants) != TENANT_COUNT:
        raise RuntimeError("公开 fixture 必须精确包含十个 tenants")
    if [tenant.get("tenantIndex") for tenant in tenants if isinstance(tenant, dict)] \
            != list(range(TENANT_COUNT)):
        raise RuntimeError("公开 fixture tenantIndex 未精确覆盖 0..9")
    all_device_ids: set[str] = set()
    project_ids: set[str] = set()
    tenant_ids: set[str] = set()
    policy_ids: set[str] = set()
    for tenant in tenants:
        if not isinstance(tenant, dict):
            raise RuntimeError("公开 fixture tenant 必须是对象")
        expected = {
            "policyCode": POLICY_CODE, "policyVersion": POLICY_VERSION,
            "deviceCountLimit": DEVICE_COUNT_LIMIT,
            "timeSeriesPointDailyLimit": TIME_SERIES_POINT_DAILY_LIMIT,
            "assignmentVersion": ASSIGNMENT_VERSION,
        }
        if any(tenant.get(key) != value for key, value in expected.items()):
            raise RuntimeError(f"公开 fixture 配额冻结值漂移: tenantIndex={tenant.get('tenantIndex')}")
        for field, collection in (("projectId", project_ids), ("tenantId", tenant_ids),
                                  ("policyId", policy_ids)):
            value = tenant.get(field)
            if not isinstance(value, str) or not value:
                raise RuntimeError(f"公开 fixture 缺少 {field}")
            collection.add(value)
        devices = tenant.get("devices")
        if not isinstance(devices, list) or len(devices) != DEVICES_PER_TENANT:
            raise RuntimeError("公开 fixture 每租户必须精确包含 1000 台设备")
        tenant_device_ids: set[str] = set()
        tenant_device_keys: set[str] = set()
        for device in devices:
            if not isinstance(device, dict):
                raise RuntimeError("公开 fixture device 必须是对象")
            device_id = device.get("deviceId")
            device_key = device.get("deviceKey")
            if not isinstance(device_id, str) or not device_id \
                    or not isinstance(device_key, str) or not device_key:
                raise RuntimeError("公开 fixture 设备缺少 deviceId/deviceKey")
            tenant_device_ids.add(device_id)
            tenant_device_keys.add(device_key)
            all_device_ids.add(device_id)
        if len(tenant_device_ids) != DEVICES_PER_TENANT \
                or len(tenant_device_keys) != DEVICES_PER_TENANT:
            raise RuntimeError("公开 fixture 的租户设备 ID/Key 必须各自唯一")
    if len(project_ids) != TENANT_COUNT or len(tenant_ids) != TENANT_COUNT \
            or len(policy_ids) != 1 or len(all_device_ids) != TENANT_COUNT * DEVICES_PER_TENANT:
        raise RuntimeError("公开 fixture 的 tenant/project/policy/device 唯一性不成立")
    return tenants


def login_project(l0: ModuleType, base_url: str, owner: dict[str, Any], project_id: str) -> str:
    """重新登录并切换项目，避免长清理阶段复用可能过期的访问令牌。"""
    base = base_url.rstrip("/")
    login = l0.request_json("POST", f"{base}/api/v1/auth/login",
                            {"email": owner["email"], "password": owner["password"]})
    switched = l0.request_json("POST", f"{base}/api/v1/auth/switch-project",
                               {"projectId": project_id}, login["accessToken"])
    token = switched.get("accessToken") if isinstance(switched, dict) else None
    if not isinstance(token, str) or not token:
        raise RuntimeError("项目切换未返回 accessToken")
    return token


def device_detail_or_deleted(l0: ModuleType, url: str, token: str) -> dict[str, Any] | None:
    """区分活跃设备与正式 API 的 404；其他状态或网络错误不得当作已删除。"""
    try:
        detail = l0.request_json("GET", url, None, token)
    except RuntimeError as exception:
        if str(exception).startswith("HTTP 404 "):
            return None
        raise
    if not isinstance(detail, dict):
        raise RuntimeError("设备详情不是 JSON 对象")
    return detail


def validate_cleanup_db_preflight(document: dict[str, Any]) -> str:
    """接受待清理或已原子清理两种精确状态，拒绝混合状态和任何外部策略引用。"""
    common = {
        "scopeCount": 10, "indexCount": 10, "tenantCount": 10,
        "projectCount": 10, "isolatedProjectCount": 10,
    }
    if any(document.get(key) != value for key, value in common.items()):
        raise RuntimeError("cleanup 数据库预检的 OWNER/tenant/project 隔离范围不成立")
    ready = {
        "l2BindingCount": 10, "cleanedBindingCount": 0,
        "exactPolicyCount": 1, "l2CodeCount": 1, "policyReferenceCount": 10,
    }
    cleaned = {
        "l2BindingCount": 0, "cleanedBindingCount": 10,
        "exactPolicyCount": 0, "l2CodeCount": 0, "policyReferenceCount": 0,
    }
    if all(document.get(key) == value for key, value in ready.items()):
        return "READY"
    if all(document.get(key) == value for key, value in cleaned.items()):
        return "ALREADY_CLEANED"
    raise RuntimeError("cleanup 数据库预检发现策略漂移、混合绑定或清单外引用")


def cleanup_fixture(args: argparse.Namespace) -> dict[str, Any]:
    """经正式 API 精确软删清单设备，再以隔离数据库事务解除测试策略。"""
    if not args.confirm_isolated_capacity_environment:
        raise RuntimeError("cleanup 只允许在已确认的隔离容量环境执行")
    require_outside_evidence(args.owner_secrets, args.output, "OWNER 秘密清单")
    fixture_raw = args.fixture.read_bytes()
    try:
        fixture = json.loads(fixture_raw)
    except json.JSONDecodeError as exception:
        raise RuntimeError("公开 fixture 不是合法 JSON") from exception
    if not isinstance(fixture, dict):
        raise RuntimeError("公开 fixture 顶层必须是对象")
    tenants = validate_cleanup_fixture(fixture, args.run_id)
    owners = {owner["tenantIndex"]: owner for owner in load_owners(args.owner_secrets)}
    policy_ids = {tenant["policyId"] for tenant in tenants}
    policy_id = next(iter(policy_ids))
    bindings_json = json.dumps([
        {"tenant_index": tenant["tenantIndex"], "tenant_id": tenant["tenantId"],
         "project_id": tenant["projectId"], "email": owners[tenant["tenantIndex"]]["email"]}
        for tenant in tenants
    ], separators=(",", ":"), ensure_ascii=True)

    # 此只读门禁必须早于任何 DELETE；最终写事务会重复同一范围和引用检查，封住两者之间的 TOCTOU。
    preflight = run_psql(args, CLEANUP_PREFLIGHT_SQL,
                         {"policy_id": policy_id, "bindings_json": bindings_json})
    preflight_state = validate_cleanup_db_preflight(preflight)
    l0 = load_l0_fixture()
    base = args.base_url.rstrip("/")
    active_by_tenant: dict[int, set[str]] = {}

    # 先无副作用预检完整 10,000 台；任何 ID/Key 漂移都发生在第一条 DELETE 之前。
    for tenant in tenants:
        index = tenant["tenantIndex"]
        token = login_project(l0, base, owners[index], tenant["projectId"])
        active: set[str] = set()
        for device in tenant["devices"]:
            url = f"{base}/api/v1/projects/{tenant['projectId']}/devices/{device['deviceId']}"
            detail = device_detail_or_deleted(l0, url, token)
            if detail is None:
                continue
            if detail.get("id") != device["deviceId"] or detail.get("deviceKey") != device["deviceKey"]:
                raise RuntimeError(f"设备详情与公开 fixture 漂移: tenantIndex={index}")
            active.add(device["deviceId"])
        active_by_tenant[index] = active

    deleted_count = 0
    already_deleted_count = 0
    for tenant in tenants:
        index = tenant["tenantIndex"]
        token = login_project(l0, base, owners[index], tenant["projectId"])
        active = active_by_tenant[index]
        already_deleted_count += DEVICES_PER_TENANT - len(active)
        for device in tenant["devices"]:
            url = f"{base}/api/v1/projects/{tenant['projectId']}/devices/{device['deviceId']}"
            if device["deviceId"] in active:
                l0.request_json("DELETE", url, None, token, (204,))
                deleted_count += 1
            if device_detail_or_deleted(l0, url, token) is not None:
                raise RuntimeError(f"设备软删除后仍可见: tenantIndex={index}, deviceId={device['deviceId']}")

    quota_result = run_psql(args, CLEANUP_QUOTA_SQL,
                            {"policy_id": policy_id, "bindings_json": bindings_json})
    cleaned_bindings = quota_result.get("bindings")
    if not isinstance(cleaned_bindings, list) or len(cleaned_bindings) != TENANT_COUNT \
            or quota_result.get("remainingPolicyRows") != 0 \
            or quota_result.get("remainingPolicyReferences") != 0:
        raise RuntimeError("L2_CAPACITY 清理后置条件不满足")
    if [binding.get("tenantIndex") for binding in cleaned_bindings] != list(range(TENANT_COUNT)) \
            or any(binding.get("policyCode") != "FREE"
                   or binding.get("assignmentVersion") != 3 for binding in cleaned_bindings):
        raise RuntimeError("十租户未精确恢复为 FREE/assignmentVersion=3")
    result = {
        "schemaVersion": 1, "runId": args.run_id,
        "fixtureSha256": hashlib.sha256(fixture_raw).hexdigest(),
        "deviceCleanup": {"listed": 10_000, "deletedNow": deleted_count,
                          "alreadyDeleted": already_deleted_count, "remainingActive": 0,
                          "mode": "official-soft-delete-api",
                          "physicalRowsDeleted": False,
                          "historicalFactsDeleted": False},
        "quotaCleanup": {"bindingsRestored": TENANT_COUNT, "policyCode": POLICY_CODE,
                         "policyId": policy_id, "policyRowsRemaining": 0,
                         "policyReferencesRemaining": 0,
                         "mode": "isolated-database-transaction",
                         "requiresSutRestartBeforeEnvironmentReuse": True},
        "databasePreflightState": preflight_state,
        "tenantReuse": {"allowedAcrossProfiles": False,
                        "reason": "设备仅软删，device type 与历史事实仍保留"},
        "qualification": {"outcome": "PASS", "scopeExact": True,
                          "unrelatedPolicyReferenceCount": 0},
    }
    write_public_json(args.output, result)
    return result


def main() -> None:
    """执行配额或设备阶段，并只输出无秘密的规模摘要。"""
    args = parse_args()
    if args.command == "quota":
        result = prepare_quota(args)
    elif args.command == "prepare":
        result = prepare_fixture(args)
    else:
        result = cleanup_fixture(args)
    print(json.dumps({"tenantCount": TENANT_COUNT,
                      "deviceCount": result.get("deviceCount", TENANT_COUNT * DEVICES_PER_TENANT)},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
