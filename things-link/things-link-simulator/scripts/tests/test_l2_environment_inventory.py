"""G1-C3e 最终环境清单校验、脱敏和稳定指纹回归。"""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import math
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]
SCRIPT = SCRIPTS / "l2_environment_inventory.py"
TEMPLATE = SCRIPTS / "l2-environment-inventory.template.json"
SPEC = importlib.util.spec_from_file_location("l2_environment_inventory", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
VERDICT_SPEC = importlib.util.spec_from_file_location(
    "l2_machine_verdict_identity", SCRIPTS / "l2_machine_verdict.py")
VERDICT = importlib.util.module_from_spec(VERDICT_SPEC)
assert VERDICT_SPEC.loader is not None
VERDICT_SPEC.loader.exec_module(VERDICT)


def digest(character: str = "a") -> str:
    """生成合法但不携带任何认证信息的固定摘要。"""
    return character * 64


def disk() -> dict:
    """生成显式包含性能承诺的数据盘证据。"""
    return {
        "type": "cloud-ssd", "capacityBytes": 107_374_182_400,
        "baselineIops": 3000, "baselineThroughputBytesPerSecond": 131_072_000,
    }


def common_host(host_id: str, peer: str) -> dict:
    """生成 SUT/发生器共用的完整主机事实。"""
    return {
        "hostId": host_id,
        "cloud": {"provider": "cloud-a", "region": "cn-test-1",
                  "availabilityZone": "cn-test-1a", "instanceType": "fixed-type"},
        "hardware": {"cpuModel": "Example CPU", "vcpus": 4,
                     "memoryBytes": 8 * 1024 * 1024 * 1024},
        "os": {"name": "Ubuntu", "version": "24.04", "imageId": "img-20260824",
               "kernel": "6.8.0-test", "architecture": "x86_64"},
        "disks": {"root": disk(), "data": disk()},
        "runtime": {"dockerClient": "28.3.3", "dockerServer": "28.3.3",
                    "jdk": "21.0.8", "python": "3.12.3"},
        "jvm": {"heapInitialBytes": 268_435_456, "heapMaxBytes": 1_073_741_824},
        "network": {"bandwidthMbps": 1000, "privateRttPeer": peer, "privateRttMillis": 0.42},
        "ntp": {"service": "chrony", "synchronized": True, "offsetMillis": 5.2,
                "checkedAt": "2026-08-24T10:00:00Z"},
        "images": {"simulator": f"sha256:{digest('b')}"},
        "configSha256": {"simulator": digest("c")},
    }


def inventory(generator_count: int = 2) -> dict:
    """生成能通过全部冻结字段的最小清单。"""
    generators = []
    for index in range(generator_count):
        generator = common_host(f"generator-{index}", "sut-0")
        generators.append(generator)
    sut = common_host("sut-0", "generator-0")
    sut["hardware"] = {"cpuModel": "Example 8C CPU", "vcpus": 8,
                       "memoryBytes": 16 * 1024 * 1024 * 1024}
    sut["images"] = {
        "timescaledb": f"sha256:{digest('1')}", "redis": f"sha256:{digest('2')}",
        "redpanda": f"sha256:{digest('3')}", "emqx": f"sha256:{digest('4')}",
        "minio": f"sha256:{digest('5')}",
    }
    sut.update({
        "hikari": {
            "control": {"maximumPoolSize": 6, "connectionTimeoutMillis": 500},
            "data": {"maximumPoolSize": 10, "connectionTimeoutMillis": 500},
        },
        "postgresql": {"version": "17.6", "parameters": {"max_connections": "200"}},
        "timescale": {"version": "2.21.1", "parameters": {"max_background_workers": "16"}},
        "redpanda": {"version": "25.2.5", "smp": 1, "memoryBytes": 2_147_483_648},
        "redis": {"version": "8.2.0"}, "emqx": {"version": "5.8.8"},
        "minio": {"version": "RELEASE.2025-07-23"},
    })
    return {
        "schemaVersion": 1, "capturedAt": "2026-08-24T10:00:00Z",
        "repository": {"commit": "a" * 40},
        "artifacts": {"backendJarSha256": digest("6"), "simulatorJarSha256": digest("7"),
                      "scriptsSha256": {
                          name: digest("8") for name in MODULE.REQUIRED_SCRIPT_NAMES
                      }},
        "sut": sut, "generators": generators,
    }


class InventoryValidationTests(unittest.TestCase):
    """验证缺证据、模板、秘密和跨主机边界全部 fail-closed。"""

    def test_cloud_transport_scripts_are_required_for_new_inventory(self):
        for name in ("l2_atomic_receive.py", "l2_ssh_transfer.py", "l2_ssh_phase_bridge.py"):
            value = inventory()
            del value["artifacts"]["scriptsSha256"][name]
            report = MODULE.validate_inventory(value)
            self.assertTrue(any(name in error for error in report), name)

    def test_complete_inventory_passes(self) -> None:
        value = inventory()
        self.assertEqual([], MODULE.validate_inventory(value))

    def test_one_generator_host_is_a_valid_inventory_topology(self) -> None:
        # 分片并发资格属于 coordinator plan/A4 报告，不能循环写回环境指纹。
        self.assertEqual([], MODULE.validate_inventory(inventory(1)))

    def test_missing_field_wrong_zone_and_clock_fail(self) -> None:
        value = inventory()
        value["schemaVersion"] = True
        del value["sut"]["hikari"]["data"]
        value["generators"][0]["cloud"]["availabilityZone"] = "cn-test-1b"
        value["generators"][1]["ntp"]["offsetMillis"] = 100.1
        errors = MODULE.validate_inventory(value)
        self.assertTrue(any("schemaVersion" in item for item in errors))
        self.assertTrue(any("hikari.data" in item for item in errors))
        self.assertTrue(any("availabilityZone" in item for item in errors))
        self.assertTrue(any("offsetMillis" in item for item in errors))

    def test_missing_critical_script_hash_fails(self) -> None:
        """旧 A4 或裁决器不能只靠调用方声明当前环境指纹混入整组证据。"""
        value = inventory()
        del value["artifacts"]["scriptsSha256"]["a4_qualification.py"]
        errors = MODULE.validate_inventory(value)
        self.assertTrue(any("a4_qualification.py 必填" in item for item in errors))

    def test_placeholder_and_zero_digest_fail(self) -> None:
        value = inventory()
        value["sut"]["cloud"]["instanceType"] = "TBD"
        value["artifacts"]["backendJarSha256"] = "0" * 64
        errors = MODULE.validate_inventory(value)
        self.assertTrue(any("占位值" in item for item in errors))

    def test_empty_null_and_non_finite_values_fail(self) -> None:
        value = inventory()
        value["sut"]["extraEmpty"] = "  "
        value["sut"]["extraNull"] = None
        value["sut"]["extraNan"] = math.nan
        errors = MODULE.validate_inventory(value)
        self.assertTrue(any("extraEmpty" in item for item in errors))
        self.assertTrue(any("extraNull" in item for item in errors))
        self.assertTrue(any("extraNan" in item for item in errors))

    def test_normalization_rejects_trimmed_duplicate_keys(self) -> None:
        with self.assertRaisesRegex(MODULE.InventoryError, "规范化后含重复键"):
            MODULE.normalize({"hostId": "one", " hostId ": "two"})

    def test_secret_key_and_secret_values_fail_without_echoing_value(self) -> None:
        value = inventory()
        value["sut"]["apiSecret"] = "do-not-print-this"
        value["generators"][0]["brokerUri"] = "mqtt://user:do-not-print-this@broker:1883"
        errors = MODULE.validate_inventory(value)
        rendered = "\n".join(errors)
        self.assertIn("apiSecret", rendered)
        self.assertIn("brokerUri", rendered)
        self.assertNotIn("do-not-print-this", rendered)

    def test_template_is_intentionally_invalid(self) -> None:
        value = json.loads(TEMPLATE.read_text(encoding="utf-8"))
        errors = MODULE.validate_inventory(value)
        self.assertTrue(errors)
        self.assertTrue(any("占位值" in item for item in errors))


class InventoryFingerprintTests(unittest.TestCase):
    """验证归档 SHA、比较指纹及生成器排序具有稳定机器语义。"""

    def test_generator_input_order_does_not_change_outputs(self) -> None:
        first = MODULE.normalize(inventory())
        second_input = inventory()
        second_input["generators"].reverse()
        second = MODULE.normalize(second_input)
        self.assertEqual(MODULE.canonical_bytes(first), MODULE.canonical_bytes(second))

    def test_capture_time_and_ntp_observation_only_change_inventory_sha(self) -> None:
        first = MODULE.normalize(inventory())
        second = copy.deepcopy(first)
        second["capturedAt"] = "2026-08-24T11:00:00Z"
        for host in [second["sut"], *second["generators"]]:
            host["ntp"]["checkedAt"] = "2026-08-24T11:00:00Z"
            host["ntp"]["offsetMillis"] = -4.1
        self.assertNotEqual(hashlib.sha256(MODULE.canonical_bytes(first)).hexdigest(),
                            hashlib.sha256(MODULE.canonical_bytes(second)).hexdigest())
        self.assertEqual(MODULE.fingerprint_document(first), MODULE.fingerprint_document(second))

    def test_baseline_field_change_splits_comparison_fingerprint(self) -> None:
        first = MODULE.normalize(inventory())
        second = copy.deepcopy(first)
        second["sut"]["hikari"]["data"]["maximumPoolSize"] = 11
        self.assertNotEqual(
            hashlib.sha256(MODULE.canonical_bytes(MODULE.fingerprint_document(first))).hexdigest(),
            hashlib.sha256(MODULE.canonical_bytes(MODULE.fingerprint_document(second))).hexdigest())

    def test_cli_clears_stale_normalized_output_and_writes_error_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "input.json"
            normalized = base / "normalized.json"
            report = base / "report.json"
            source.write_text(json.dumps({"schemaVersion": 1}), encoding="utf-8")
            normalized.write_text("stale-pass", encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--input", str(source),
                 "--normalized-output", str(normalized), "--report-output", str(report)],
                check=False, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertFalse(normalized.exists())
            machine_report = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("ERROR", machine_report["status"])
            self.assertIsNone(machine_report["comparisonFingerprint"])

    def test_cli_pass_report_hashes_exact_normalized_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "input.json"
            normalized = base / "normalized.json"
            report = base / "report.json"
            source.write_text(json.dumps(inventory(), ensure_ascii=False), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "--input", str(source),
                 "--normalized-output", str(normalized), "--report-output", str(report)],
                check=False, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            machine_report = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual("PASS", machine_report["status"])
            self.assertEqual(hashlib.sha256(normalized.read_bytes()).hexdigest(),
                             machine_report["inventorySha256"])
            self.assertEqual(["generator-0", "generator-1"], machine_report["generatorHostIds"])


class InventoryVerdictIdentityTests(unittest.TestCase):
    """验证最终裁决必须重算环境纯函数并精确绑定发生器主机集合。"""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.evidence = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_valid_environment(self) -> tuple[dict, str]:
        """写入与正式 CLI 字节/报告完全一致的环境证据。"""
        value = MODULE.normalize(inventory())
        normalized = MODULE.canonical_bytes(value)
        fingerprint = hashlib.sha256(MODULE.canonical_bytes(
            MODULE.fingerprint_document(value))).hexdigest()
        report = {
            "schemaVersion": 1, "status": "PASS", "errors": [],
            "inventorySha256": hashlib.sha256(normalized).hexdigest(),
            "comparisonFingerprint": fingerprint,
            "sutHostId": value["sut"]["hostId"],
            "generatorHostIds": [item["hostId"] for item in value["generators"]],
        }
        (self.evidence / "environment-inventory.json").write_bytes(normalized)
        (self.evidence / "environment-inventory-report.json").write_bytes(
            MODULE.canonical_bytes(report))
        return value, fingerprint

    def test_verdict_recomputes_valid_environment(self) -> None:
        value, fingerprint = self.write_valid_environment()
        self.assertEqual(value, VERDICT.validate_environment(self.evidence, fingerprint))

    def test_self_authored_pass_report_cannot_bless_invalid_inventory(self) -> None:
        raw = MODULE.canonical_bytes({"artifacts": {"simulatorJarSha256": "a" * 64}})
        (self.evidence / "environment-inventory.json").write_bytes(raw)
        forged = {
            "schemaVersion": 1, "status": "PASS", "errors": [],
            "inventorySha256": hashlib.sha256(raw).hexdigest(),
            "comparisonFingerprint": "b" * 64,
            "sutHostId": "sut-forged", "generatorHostIds": ["generator-forged"],
        }
        (self.evidence / "environment-inventory-report.json").write_bytes(
            MODULE.canonical_bytes(forged))
        with self.assertRaisesRegex(VERDICT.EvidenceError, "校验失败"):
            VERDICT.validate_environment(self.evidence, "b" * 64)

    def test_inventory_tamper_and_noncanonical_bytes_are_rejected(self) -> None:
        value, fingerprint = self.write_valid_environment()
        value["sut"]["cloud"]["instanceType"] = "tampered-type"
        (self.evidence / "environment-inventory.json").write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(VERDICT.EvidenceError, "重算结果不一致|不是纯函数规范化字节"):
            VERDICT.validate_environment(self.evidence, fingerprint)

    def test_generator_host_set_must_match_distributed_plan(self) -> None:
        value, _ = self.write_valid_environment()
        VERDICT.validate_generator_hosts(value, {
            "hosts": [{"hostId": "generator-1"}, {"hostId": "generator-0"}]})
        with self.assertRaisesRegex(VERDICT.EvidenceError, "hostId 集不一致"):
            VERDICT.validate_generator_hosts(value, {
                "hosts": [{"hostId": "generator-0"}, {"hostId": "other-host"}]})


if __name__ == "__main__":
    unittest.main()
