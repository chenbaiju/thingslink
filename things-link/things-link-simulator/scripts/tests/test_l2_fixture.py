"""G1-C3e 十租户 fixture 的 fail-closed 契约测试。"""

from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script():
    """从非 package 的 scripts 目录加载 L2 fixture。"""
    specification = importlib.util.spec_from_file_location("l2_fixture", SCRIPTS / "l2_fixture.py")
    module = importlib.util.module_from_spec(specification)
    assert specification.loader is not None
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


def owner_document() -> dict:
    """构造只用于临时目录的十 OWNER 秘密输入。"""
    return {"tenants": [
        {"tenantIndex": index, "email": f"l2-{index}@example.com", "password": f"secret-{index}"}
        for index in range(10)
    ]}


def scope_bindings() -> list[dict]:
    """构造十租户唯一 OWNER 范围。"""
    return [{"tenantIndex": index, "tenantId": f"tenant-{index}",
             "projectId": f"project-{index}", "projectKey": f"project-key-{index}",
             "activeProjectCount": 1, "deviceRowCount": 0,
             "deviceTypeRowCount": 0} for index in range(10)]


def quota_bindings(policy_id: str = "policy-l2") -> list[dict]:
    """构造冻结值完全一致的配额后置条件。"""
    return [{"tenantIndex": index, "tenantId": f"tenant-{index}",
             "projectId": f"project-{index}", "projectKey": f"project-key-{index}",
             "policyId": policy_id, "policyCode": "L2_CAPACITY", "policyVersion": 1,
             "deviceCountLimit": 2000, "timeSeriesPointDailyLimit": 10_000_000,
             "assignmentVersion": 2} for index in range(10)]


def public_fixture(run_id: str = "c3e-run-001") -> dict:
    """构造 cleanup 的完整无秘密权威清单。"""
    tenants = []
    for binding in quota_bindings():
        index = binding["tenantIndex"]
        tenants.append({**binding, "devices": [
            {"deviceId": f"device-{index}-{device}", "deviceKey": f"key-{index}-{device}",
             "hostId": f"generator-{index % 2}", "shardId": f"tenant-{index}-shard-0"}
            for device in range(1000)
        ]})
    return {"schemaVersion": 1, "runId": run_id, "tenantCount": 10, "projectCount": 10,
            "deviceCount": 10_000, "devicesPerTenant": 1000, "tenants": tenants,
            "qualification": {"outcome": "PASS"}}


class L2QuotaTests(unittest.TestCase):
    """验证建机前配额事务精确覆盖十个唯一租户。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = load_script()

    def test_prepare_quota_writes_sanitized_ten_tenant_evidence(self) -> None:
        """两次数据库回读均正确时才写不含 email/password 的公开证据。"""
        completed = [
            subprocess.CompletedProcess([], 0, json.dumps({
                "requestedCount": 10, "bindings": scope_bindings()}) + "\n", ""),
            subprocess.CompletedProcess([], 0, json.dumps({
                "bindings": quota_bindings()}) + "\n", ""),
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owners = root / "secrets" / "owners.json"
            output = root / "evidence" / "quota-policy.json"
            owners.parent.mkdir()
            owners.write_text(json.dumps(owner_document()), encoding="utf-8")
            args = argparse.Namespace(owner_secrets=owners, policy_id="policy-l2",
                                      postgres_container="postgres", postgres_user="user",
                                      postgres_db="database", output=output)
            with mock.patch.object(self.fixture.subprocess, "run", side_effect=completed) as runner:
                result = self.fixture.prepare_quota(args)
            raw = output.read_text(encoding="utf-8")
            document = json.loads(raw)

        self.assertEqual(result["tenantCount"], 10)
        self.assertEqual(len(document["bindings"]), 10)
        self.assertNotIn("email", raw.lower())
        self.assertNotIn("password", raw.lower())
        self.assertIn("L2_CAPACITY", runner.call_args_list[1].kwargs["input"])
        self.assertIn("time_series_point_daily_limit', 10000000",
                      runner.call_args_list[1].kwargs["input"])
        self.assertIn("ON CONFLICT (code) DO NOTHING", runner.call_args_list[1].kwargs["input"])
        self.assertIn("\\quit 3", runner.call_args_list[1].kwargs["input"])
        self.assertTrue(any(argument.startswith("owners_json=")
                            for argument in runner.call_args_list[0].args[0]))

    def test_rejects_owner_scope_with_second_active_project(self) -> None:
        """账号 JOIN 有十行也不能掩盖某租户拥有两个活跃项目。"""
        bindings = scope_bindings()
        bindings[4]["activeProjectCount"] = 2
        with self.assertRaisesRegex(RuntimeError, "精确拥有一个活跃 project"):
            self.fixture.validate_scope({"requestedCount": 10, "bindings": bindings})

    def test_rejects_project_with_existing_device_or_type(self) -> None:
        """额外设备或模型会污染万级分母，不能由新建的 10,000 台清单掩盖。"""
        bindings = scope_bindings()
        bindings[2]["deviceRowCount"] = 1
        with self.assertRaisesRegex(RuntimeError, "从未创建过设备"):
            self.fixture.validate_scope({"requestedCount": 10, "bindings": bindings})
        bindings = scope_bindings()
        bindings[6]["deviceTypeRowCount"] = 1
        with self.assertRaisesRegex(RuntimeError, "从未创建过 device type"):
            self.fixture.validate_scope({"requestedCount": 10, "bindings": bindings})

    def test_rejects_non_contiguous_tenant_indexes_before_database(self) -> None:
        """重复 tenantIndex 会破坏公平性聚合，必须在调用数据库前失败。"""
        document = owner_document()
        document["tenants"][9]["tenantIndex"] = 8
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owners.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "0..9"):
                self.fixture.load_owners(path)

    def test_rejects_quota_assignment_version_drift(self) -> None:
        """任一租户 assignmentVersion 不是 2 都阻止后续设备创建。"""
        bindings = quota_bindings()
        bindings[7]["assignmentVersion"] = 3
        with self.assertRaisesRegex(RuntimeError, "冻结值漂移"):
            self.fixture.validate_quota_bindings({"bindings": bindings}, "policy-l2")

    def test_rejects_owner_secret_inside_public_evidence_tree(self) -> None:
        """整目录上传证据时不能把 OWNER 口令文件一起带入 artifact。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / "evidence"
            evidence.mkdir()
            owners = evidence / "owners.json"
            owners.write_text(json.dumps(owner_document()), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "公开证据目录之外"):
                self.fixture.require_outside_evidence(
                    owners, evidence / "quota-policy.json", "OWNER 秘密清单")


class L2FixturePrepareTests(unittest.TestCase):
    """验证设备、分片与秘密目录形成完整且可上传边界明确的 fixture。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = load_script()

    def test_assignment_requires_exactly_one_thousand_devices_per_tenant(self) -> None:
        """某租户少一台时即使全局容量看似足够也必须失败。"""
        shards = [{"tenantIndex": index, "hostId": f"generator-{index % 2}",
                   "shardId": f"tenant-{index}-shard-0",
                   "deviceCount": 999 if index == 3 else 1000}
                  for index in range(10)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(json.dumps({"shards": shards}), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "精确为 1000"):
                self.fixture.load_assignment_plan(path)

    def test_local_shard_id_may_repeat_on_different_hosts(self) -> None:
        """现有 A4 每台主机都从 shard-000 编号，全局身份必须使用 host/shard 二元组。"""
        shards = [{"tenantIndex": index, "hostId": f"generator-{index % 2}",
                   "shardId": f"shard-{index // 2:03d}", "deviceCount": 1000}
                  for index in range(10)]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(json.dumps({"shards": shards}), encoding="utf-8")
            normalized = self.fixture.load_assignment_plan(path)
        self.assertEqual(10, len(normalized))
        self.assertEqual(2, sum(item["shardId"] == "shard-000" for item in normalized))

    def test_prepare_separates_credentials_from_public_fixture(self) -> None:
        """公开报告只留稳定标识，十份分片文件在独立秘密目录保留一次性凭据。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            owners = root / "secrets" / "owners.json"
            quota = root / "evidence" / "quota.json"
            plan = root / "inputs" / "plan.json"
            secret_dir = root / "secrets"
            output = root / "evidence" / "fixture-tenants.json"
            owners.parent.mkdir()
            quota.parent.mkdir()
            plan.parent.mkdir()
            owners.write_text(json.dumps(owner_document()), encoding="utf-8")
            quota.write_text(json.dumps({"schemaVersion": 1, "tenantCount": 10,
                                         "devicesPerTenant": 1000,
                                         "bindings": quota_bindings(),
                                         "qualification": {"outcome": "PASS"}}), encoding="utf-8")
            plan.write_text(json.dumps({"shards": [
                {"tenantIndex": index, "hostId": f"generator-{index % 2}",
                 "shardId": f"tenant-{index}-shard-0", "deviceCount": 1000}
                for index in range(10)
            ]}), encoding="utf-8")

            def fake_prepare(args) -> None:
                devices = [{"deviceId": f"{args.project_id}-device-{index}",
                            "deviceKey": f"device-{index}", "accessToken": f"token-{index}",
                            "gateway": False} for index in range(1000)]
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps({"projectId": args.project_id,
                                                   "projectKey": args.project_key,
                                                   "devices": devices}), encoding="utf-8")

            args = argparse.Namespace(base_url="https://sut.internal", owner_secrets=owners,
                                      quota_evidence=quota, assignment_plan=plan,
                                      secret_output_dir=secret_dir, output=output,
                                      run_id="c3e-run-001", environment_fingerprint="a" * 64)
            with mock.patch.object(self.fixture, "load_l0_fixture",
                                   return_value=SimpleNamespace(prepare=fake_prepare)):
                result = self.fixture.prepare_fixture(args)
            public_raw = output.read_text(encoding="utf-8")
            public_document = json.loads(public_raw)
            secret_files = sorted(secret_dir.glob("*/*.json"))
            first_secret = json.loads(secret_files[0].read_text(encoding="utf-8"))

        self.assertEqual(result["deviceCount"], 10_000)
        self.assertEqual(len(public_document["tenants"]), 10)
        self.assertEqual(len(public_document["tenants"][0]["devices"]), 1000)
        self.assertNotIn("accessToken", public_raw)
        self.assertNotIn("password", public_raw.lower())
        self.assertEqual(len(secret_files), 10)
        self.assertEqual(len(first_secret["devices"]), 1000)
        self.assertIn("accessToken", first_secret["devices"][0])


class L2FixtureCleanupTests(unittest.TestCase):
    """验证 cleanup 只软删清单设备并原子释放本轮策略。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = load_script()

    def test_cleanup_uses_official_device_api_and_exact_quota_transaction(self) -> None:
        """10,000 台全部预检后逐项软删，策略仅在无外部引用时恢复。"""
        document = public_fixture()
        active = {device["deviceId"]: device["deviceKey"]
                  for tenant in document["tenants"] for device in tenant["devices"]}
        # 模拟上次 cleanup 在进程中断前已成功软删五台；重跑必须安全收敛而非因 404 卡死。
        for device in document["tenants"][0]["devices"][:5]:
            active.pop(device["deviceId"])
        calls: list[tuple[str, str]] = []

        def fake_request(method, url, body=None, token=None, expected=(200,)):
            calls.append((method, url))
            if url.endswith("/auth/login"):
                return {"accessToken": "login-token"}
            if url.endswith("/auth/switch-project"):
                return {"accessToken": "project-token"}
            device_id = url.rsplit("/", 1)[-1]
            if method == "GET":
                if device_id not in active:
                    raise RuntimeError(f"HTTP 404 {url}: not found")
                return {"id": device_id, "deviceKey": active[device_id]}
            if method == "DELETE":
                active.pop(device_id)
                return None
            raise AssertionError((method, url))

        cleaned = [{"tenantIndex": index, "tenantId": f"tenant-{index}",
                    "projectId": f"project-{index}", "policyId": "free-policy",
                    "policyCode": "FREE", "policyVersion": 1,
                    "deviceCountLimit": 100, "assignmentVersion": 3}
                   for index in range(10)]
        preflight = subprocess.CompletedProcess([], 0, json.dumps({
            "scopeCount": 10, "indexCount": 10, "tenantCount": 10, "projectCount": 10,
            "isolatedProjectCount": 10, "l2BindingCount": 10, "cleanedBindingCount": 0,
            "exactPolicyCount": 1, "l2CodeCount": 1, "policyReferenceCount": 10}) + "\n", "")
        completed = subprocess.CompletedProcess([], 0, json.dumps({
            "bindings": cleaned, "remainingPolicyRows": 0,
            "remainingPolicyReferences": 0}) + "\n", "")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            secrets = root / "secrets"
            evidence = root / "evidence"
            secrets.mkdir()
            evidence.mkdir()
            owners = secrets / "owners.json"
            fixture_path = evidence / "fixture-tenants.json"
            output = evidence / "cleanup-report.json"
            owners.write_text(json.dumps(owner_document()), encoding="utf-8")
            fixture_path.write_text(json.dumps(document), encoding="utf-8")
            args = argparse.Namespace(base_url="https://sut.internal", owner_secrets=owners,
                                      fixture=fixture_path, run_id="c3e-run-001",
                                      postgres_container="postgres", postgres_user="user",
                                      postgres_db="database", output=output,
                                      confirm_isolated_capacity_environment=True)
            fake_l0 = SimpleNamespace(request_json=fake_request)
            with mock.patch.object(self.fixture, "load_l0_fixture", return_value=fake_l0), \
                    mock.patch.object(self.fixture.subprocess, "run",
                                      side_effect=[preflight, completed]) as runner:
                result = self.fixture.cleanup_fixture(args)
            report = json.loads(output.read_text(encoding="utf-8"))

        self.assertFalse(active)
        self.assertEqual(sum(method == "DELETE" for method, _ in calls), 9_995)
        self.assertEqual(result["deviceCleanup"]["alreadyDeleted"], 5)
        self.assertEqual(result["deviceCleanup"]["remainingActive"], 0)
        self.assertEqual(report["quotaCleanup"]["policyRowsRemaining"], 0)
        self.assertEqual(runner.call_count, 2)
        preflight_sql = runner.call_args_list[0].kwargs["input"]
        sql = runner.call_args_list[1].kwargs["input"]
        self.assertNotIn("UPDATE sys_tenant", preflight_sql)
        self.assertNotIn("DELETE FROM sys_quota_policy", preflight_sql)
        self.assertIn("reference_count = 10", sql)
        self.assertIn("state.isolated_project_count = 10", sql)
        self.assertIn("policy_state.l2_code_count = 0", sql)
        self.assertIn("member.role = 'OWNER'", sql)
        self.assertIn("\\quit 4", sql)
        self.assertIn("quota_policy_assignment_version = 3", sql)
        self.assertFalse(report["tenantReuse"]["allowedAcrossProfiles"])
        self.assertFalse(report["deviceCleanup"]["physicalRowsDeleted"])

    def test_cleanup_preflight_mismatch_does_not_delete_any_device(self) -> None:
        """任一实际 deviceKey 漂移时必须在第一条 DELETE 之前停止。"""
        document = public_fixture()
        calls: list[str] = []

        def fake_request(method, url, body=None, token=None, expected=(200,)):
            calls.append(method)
            if url.endswith("/auth/login"):
                return {"accessToken": "login-token"}
            if url.endswith("/auth/switch-project"):
                return {"accessToken": "project-token"}
            device_id = url.rsplit("/", 1)[-1]
            return {"id": device_id, "deviceKey": "wrong-key"}

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "secrets").mkdir()
            (root / "evidence").mkdir()
            owners = root / "secrets" / "owners.json"
            fixture_path = root / "evidence" / "fixture.json"
            owners.write_text(json.dumps(owner_document()), encoding="utf-8")
            fixture_path.write_text(json.dumps(document), encoding="utf-8")
            args = argparse.Namespace(base_url="https://sut.internal", owner_secrets=owners,
                                      fixture=fixture_path, run_id="c3e-run-001",
                                      postgres_container="postgres", postgres_user="user",
                                      postgres_db="database",
                                      output=root / "evidence" / "cleanup.json",
                                      confirm_isolated_capacity_environment=True)
            preflight = subprocess.CompletedProcess([], 0, json.dumps({
                "scopeCount": 10, "indexCount": 10, "tenantCount": 10, "projectCount": 10,
                "isolatedProjectCount": 10, "l2BindingCount": 10, "cleanedBindingCount": 0,
                "exactPolicyCount": 1, "l2CodeCount": 1,
                "policyReferenceCount": 10}) + "\n", "")
            with mock.patch.object(self.fixture, "load_l0_fixture",
                                   return_value=SimpleNamespace(request_json=fake_request)), \
                    mock.patch.object(self.fixture.subprocess, "run", return_value=preflight):
                with self.assertRaisesRegex(RuntimeError, "设备详情与公开 fixture 漂移"):
                    self.fixture.cleanup_fixture(args)

        self.assertNotIn("DELETE", calls)

    def test_cleanup_rejects_duplicate_device_id(self) -> None:
        """被篡改为跨租户重复 ID 的公开清单不能扩大或模糊删除范围。"""
        document = public_fixture()
        document["tenants"][1]["devices"][0]["deviceId"] = \
            document["tenants"][0]["devices"][0]["deviceId"]
        with self.assertRaisesRegex(RuntimeError, "唯一性不成立"):
            self.fixture.validate_cleanup_fixture(document, "c3e-run-001")

    def test_database_preflight_rejects_external_policy_reference(self) -> None:
        """即使十个目标绑定正确，第十一个引用也必须在设备 DELETE 前阻断。"""
        document = {"scopeCount": 10, "indexCount": 10, "tenantCount": 10,
                    "projectCount": 10, "isolatedProjectCount": 10,
                    "l2BindingCount": 10, "cleanedBindingCount": 0,
                    "exactPolicyCount": 1, "l2CodeCount": 1, "policyReferenceCount": 11}
        with self.assertRaisesRegex(RuntimeError, "清单外引用"):
            self.fixture.validate_cleanup_db_preflight(document)

    def test_cleanup_database_preflight_fails_before_loading_device_api(self) -> None:
        """策略外部引用必须在登录或设备预检前阻断，因而更不可能先发生 DELETE。"""
        document = public_fixture()
        bad_preflight = subprocess.CompletedProcess([], 0, json.dumps({
            "scopeCount": 10, "indexCount": 10, "tenantCount": 10, "projectCount": 10,
            "isolatedProjectCount": 10, "l2BindingCount": 10, "cleanedBindingCount": 0,
            "exactPolicyCount": 1, "l2CodeCount": 1,
            "policyReferenceCount": 11}) + "\n", "")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "secrets").mkdir()
            (root / "evidence").mkdir()
            owners = root / "secrets" / "owners.json"
            fixture_path = root / "evidence" / "fixture.json"
            owners.write_text(json.dumps(owner_document()), encoding="utf-8")
            fixture_path.write_text(json.dumps(document), encoding="utf-8")
            args = argparse.Namespace(base_url="https://sut.internal", owner_secrets=owners,
                                      fixture=fixture_path, run_id="c3e-run-001",
                                      postgres_container="postgres", postgres_user="user",
                                      postgres_db="database",
                                      output=root / "evidence" / "cleanup.json",
                                      confirm_isolated_capacity_environment=True)
            with mock.patch.object(self.fixture.subprocess, "run", return_value=bad_preflight), \
                    mock.patch.object(self.fixture, "load_l0_fixture") as loader:
                with self.assertRaisesRegex(RuntimeError, "清单外引用"):
                    self.fixture.cleanup_fixture(args)
            loader.assert_not_called()


if __name__ == "__main__":
    unittest.main()
