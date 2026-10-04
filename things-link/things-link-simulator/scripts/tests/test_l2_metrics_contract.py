"""G1-C3e 固定 profile、拓扑派生 source 与 typed metrics 合同。"""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1]


def load(name: str):
    """加载非 package 脚本。"""
    specification = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(specification)
    assert specification.loader is not None
    sys.modules[name] = module
    specification.loader.exec_module(module)
    return module


METRICS = load("l2_metrics")
ENVIRONMENT = load("l2_environment_inventory")


def digest(character: str) -> str:
    """生成合法测试摘要。"""
    return character * 64


def disk() -> dict:
    """生成完整云盘事实。"""
    return {"type": "cloud-ssd", "capacityBytes": 107_374_182_400,
            "baselineIops": 3000, "baselineThroughputBytesPerSecond": 131_072_000}


def host(host_id: str, peer: str) -> dict:
    """生成完整公共主机事实。"""
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


def inventory() -> dict:
    """生成一台 SUT 和两台发生器的有效规范化清单。"""
    sut = host("sut-0", "generator-0")
    sut["hardware"] = {"cpuModel": "Example 8C CPU", "vcpus": 8,
                       "memoryBytes": 16 * 1024 * 1024 * 1024}
    sut["images"] = {"timescaledb": f"sha256:{digest('1')}",
                     "redis": f"sha256:{digest('2')}", "redpanda": f"sha256:{digest('3')}",
                     "emqx": f"sha256:{digest('4')}", "minio": f"sha256:{digest('5')}"}
    sut.update({
        "hikari": {"control": {"maximumPoolSize": 6, "connectionTimeoutMillis": 500},
                   "data": {"maximumPoolSize": 10, "connectionTimeoutMillis": 500}},
        "postgresql": {"version": "17.6", "parameters": {"max_connections": "200"}},
        "timescale": {"version": "2.21.1", "parameters": {"max_background_workers": "16"}},
        "redpanda": {"version": "25.2.5", "smp": 1, "memoryBytes": 2_147_483_648},
        "redis": {"version": "8.2.0"}, "emqx": {"version": "5.8.8"},
        "minio": {"version": "RELEASE.2025-07-23"},
    })
    return ENVIRONMENT.normalize({
        "schemaVersion": 1, "capturedAt": "2026-08-24T10:00:00Z",
        "repository": {"commit": "a" * 40},
        "artifacts": {"backendJarSha256": digest("6"), "simulatorJarSha256": digest("7"),
                      "scriptsSha256": {name: digest("8")
                                        for name in ENVIRONMENT.REQUIRED_SCRIPT_NAMES}},
        "sut": sut, "generators": [host("generator-0", "sut-0"), host("generator-1", "sut-0")],
    })


def timer(count: int) -> dict:
    """生成含有限桶和 +Inf 的累计 Timer。"""
    return {"buckets": [{"le": 1.0, "count": count}, {"le": "+Inf", "count": count}],
            "count": count, "sum": float(count) / 10}


def metrics_for(source_type: str, sequence: int = 0) -> dict:
    """生成每种固定 source type 的合法最小指标。"""
    host_metrics = {
        "cpuPercent": 10.0, "memoryUsedBytes": 100, "memoryTotalBytes": 1000,
        "swapUsedBytes": 0, "rootDiskFreeBytes": 500, "rootDiskTotalBytes": 1000,
        "dataDiskFreeBytes": 500, "dataDiskTotalBytes": 1000, "dataDiskReadOnly": False,
        "oomKillCount": 0, "networkRxBytes": sequence, "networkTxBytes": sequence,
    }
    if source_type == "sut-host":
        return host_metrics
    if source_type == "generator-host":
        return {**host_metrics, "threadCount": 10, "fdCount": 20, "tickLatenessMillis": 1,
                "scheduledOperations": sequence, "submittedOperations": sequence,
                "pubackCount": sequence}
    if source_type == "container-runtime":
        item = {"cpuPercent": 1.0, "rssBytes": 10, "memoryLimitBytes": 100,
                "restartCount": 0, "running": True, "oomKilled": False,
                "networkRxBytes": sequence, "networkTxBytes": sequence,
                "blockReadBytes": sequence, "blockWriteBytes": sequence}
        return {"containers": {name: copy.deepcopy(item) for name in METRICS.CONTAINERS}}
    if source_type == "backend":
        pools = {name: {"active": 1, "idle": 1, "pending": 0, "max": 10,
                        "timeoutTotal": sequence} for name in ("CONTROL", "DATA")}
        return {"uplinkLatencySeconds": timer(sequence),
                "commandAcceptanceSeconds": timer(sequence),
                "hikariAcquireSeconds": {"CONTROL": timer(sequence), "DATA": timer(sequence)},
                "hikari": pools, "http5xxTotal": sequence,
                "timeSeriesSuccessTotal": sequence, "timeSeriesFailureTotal": sequence,
                "dlqTotal": sequence, "outboxPending": 0,
                "workerFailuresTotal": sequence, "schedulerFailuresTotal": sequence}
    if source_type == "postgresql-timescale":
        return {"connectionsUsed": 2, "connectionsMax": 200,
                "pgStatStatementsAvailable": True, "pgStatStatementsResetEpochMillis": 1000}
    if source_type == "redpanda":
        return {"groupLag": {group: 0 for group in METRICS.GROUPS}, "healthy": True}
    if source_type == "redis":
        return {"evictedKeys": sequence, "healthy": True}
    if source_type == "emqx":
        return {"connectedClients": 10_000, "actionFailedTotal": sequence,
                "actionDroppedTotal": sequence, "ruleActionsFailedTotal": sequence, "healthy": True}
    if source_type == "minio":
        return {"healthy": True}
    raise AssertionError(source_type)


class MetricsContractTests(unittest.TestCase):
    """验证 profile、source、字段、tick 与计数器都不能由 plan 自报。"""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.raw = self.root / "metric-sources"
        self.raw.mkdir()
        value = inventory()
        self.inventory_path = self.root / "environment-inventory.json"
        self.inventory_path.write_bytes(ENVIRONMENT.canonical_bytes(value))
        self.fingerprint = hashlib.sha256(ENVIRONMENT.canonical_bytes(
            ENVIRONMENT.fingerprint_document(value))).hexdigest()
        self.distributed_path = self.root / "distributed-plan.json"
        hosts = []
        for host_id in ("generator-0", "generator-1"):
            output_dir = f"a4-{host_id}"
            hosts.append({
                "hostId": host_id, "deviceCount": 5000,
                "shardIds": [f"{host_id}-shard-{index:03d}" for index in range(5)],
                "a4Argv": [sys.executable, "a4_qualification.py", "--run-id", "run-1",
                           "--shard-id-prefix", host_id, "--device-count", "5000",
                           "--shard-size", "1000", "--output-dir", output_dir],
                "reportPath": f"{output_dir}/qualification-report.json",
                "manifestRoot": f"{output_dir}/manifests", "a4TimeoutSeconds": 600,
            })
        self.distributed_path.write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": self.fingerprint,
            "targetDeviceCount": 10_000,
            "artifacts": {"simulatorJarSha256": value["artifacts"]["simulatorJarSha256"],
                          "a4RunnerSha256": value["artifacts"]["scriptsSha256"]["a4_qualification.py"]},
            "hosts": hosts}), encoding="utf-8")
        expected = METRICS.derive_expected_sources(value, json.loads(
            self.distributed_path.read_text(encoding="utf-8")))
        self.sources = [{**item, "argv": [sys.executable, "collector.py"],
                         "timeoutMillis": 1000, "outputFile": f"{item['sourceId']}.jsonl"}
                        for item in expected]
        self.plan_path = self.root / "metrics-source-plan.json"
        self.write_plan("C3E0_TOOL_QUALIFICATION")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_plan(self, profile: str) -> None:
        """按代码冻结值写计划，便于单项篡改测试。"""
        contract = METRICS.PROFILE_CONTRACTS[profile]
        duration = contract["durationMillis"]
        self.plan_path.write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": self.fingerprint,
            "profile": profile, "phase": contract["phase"],
            "intervalMillis": 5000, "durationMillis": duration,
            "exactSampleCount": duration // 5000 + 1, "maxTickLatenessMillis": 500,
            "evaluatorImplemented": contract["evaluatorImplemented"],
            "inventoryFile": self.inventory_path.name,
            "inventorySha256": METRICS.sha256_file(self.inventory_path),
            "distributedPlanFile": self.distributed_path.name,
            "distributedPlanSha256": METRICS.sha256_file(self.distributed_path),
            "sources": self.sources}), encoding="utf-8")

    def write_sources(self) -> None:
        """写 13 行且跨源 tick 对齐的 TOOL 原始证据。"""
        plan = METRICS.validate_plan(self.plan_path)
        for source in self.sources:
            rows = []
            for sequence in range(plan["exactSampleCount"]):
                rows.append({"schemaVersion": 1, "runId": "run-1",
                             "environmentFingerprint": self.fingerprint,
                             "profile": plan["profile"], "phase": plan["phase"],
                             "source": {key: source[key] for key in
                                        ("sourceId", "sourceType", "hostId", "role", "component")},
                             "sequence": sequence, "scheduledMonotonicMillis": 1000 + sequence * 5000,
                             "observedMonotonicMillis": 1010 + sequence * 5000,
                             "epochMillis": 10_000 + sequence * 5000,
                             "metrics": metrics_for(source["sourceType"], sequence)})
            (self.raw / source["outputFile"]).write_text(
                "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")

    def test_profile_windows_are_fixed_without_waiting(self) -> None:
        """纯计划校验覆盖 30/65 分钟，但运行入口只放行 C3e-0 工具资格。"""
        expected = {"C3E0_TOOL_QUALIFICATION": 13, "S": 361, "H": 361, "M": 361,
                    "B": 361, "E": 361, "R": 781}
        for profile, samples in expected.items():
            self.write_plan(profile)
            self.assertEqual(samples, METRICS.validate_plan(self.plan_path)["exactSampleCount"])
            if profile != "C3E0_TOOL_QUALIFICATION":
                with self.assertRaisesRegex(METRICS.MetricsError, "evaluator 尚未实现"):
                    METRICS.validate_plan(self.plan_path, require_implemented=True)

    def test_source_set_is_derived_from_inventory_and_distributed_plan(self) -> None:
        """漏 generator 或把 Timescale 冒充独立容器均不能通过。"""
        plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        plan["sources"].pop()
        self.plan_path.write_text(json.dumps(plan), encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "source 数量"):
            METRICS.validate_plan(self.plan_path)
        self.write_plan("C3E0_TOOL_QUALIFICATION")
        plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        plan["sources"][3]["hostId"] = "generator-0"
        self.plan_path.write_text(json.dumps(plan), encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "最终拓扑派生"):
            METRICS.validate_plan(self.plan_path)

    def test_typed_metrics_reject_unknown_missing_nan_and_bad_timer(self) -> None:
        """任意 JSON、缺字段、NaN 与无 +Inf 的 Timer 都 fail-closed。"""
        with self.assertRaisesRegex(METRICS.MetricsError, "字段集合不一致"):
            METRICS.validate_source_metrics("redis", {"healthy": True, "ok": 1}, "redis")
        bad = metrics_for("sut-host")
        bad["cpuPercent"] = float("nan")
        with self.assertRaisesRegex(METRICS.MetricsError, "有限数值"):
            METRICS.validate_source_metrics("sut-host", bad, "sut")
        backend = metrics_for("backend")
        backend["uplinkLatencySeconds"]["buckets"].pop()
        with self.assertRaisesRegex(METRICS.MetricsError, "至少包含"):
            METRICS.validate_source_metrics("backend", backend, "backend")

    def test_typed_metrics_reject_impossible_resource_relationships(self) -> None:
        """字段各自非负仍不足以证明资源事实自洽。"""
        host_value = metrics_for("sut-host")
        host_value["memoryUsedBytes"] = host_value["memoryTotalBytes"] + 1
        with self.assertRaisesRegex(METRICS.MetricsError, "不得超过 memoryTotalBytes"):
            METRICS.validate_source_metrics("sut-host", host_value, "sut")
        generator = metrics_for("generator-host")
        generator["pubackCount"] = 2
        generator["submittedOperations"] = 1
        with self.assertRaisesRegex(METRICS.MetricsError, "pubackCount <="):
            METRICS.validate_source_metrics("generator-host", generator, "generator")
        backend = metrics_for("backend")
        backend["hikari"]["CONTROL"].update({"active": 7, "idle": 4, "max": 10})
        with self.assertRaisesRegex(METRICS.MetricsError, r"active\+idle"):
            METRICS.validate_source_metrics("backend", backend, "backend")
        database = metrics_for("postgresql-timescale")
        database.update({"connectionsUsed": 201, "connectionsMax": 200})
        with self.assertRaisesRegex(METRICS.MetricsError, "connectionsUsed"):
            METRICS.validate_source_metrics("postgresql-timescale", database, "database")

    def test_run_source_and_jsonl_reject_duplicate_keys(self) -> None:
        """采集 stdout 与归档 JSONL 都不能由重复键的后值静默覆盖前值。"""
        source = {"sourceId": "sut-minio", "sourceType": "minio",
                  "argv": [sys.executable, "collector.py"], "timeoutMillis": 1000}
        completed = subprocess.CompletedProcess([], 0, '{"healthy":true,"healthy":false}', "")
        with mock.patch.object(METRICS.subprocess, "run", return_value=completed):
            with self.assertRaisesRegex(METRICS.MetricsError, "重复键"):
                METRICS.run_source(source)
        duplicate = self.raw / "duplicate.jsonl"
        duplicate.write_text('{"schemaVersion":1,"schemaVersion":1}\n', encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "重复键"):
            METRICS.read_jsonl(duplicate)

    def test_exact_rows_counter_monotonicity_and_cross_source_tick(self) -> None:
        """多行、少行、Counter 回退和跨源 tick 漂移分别拒绝。"""
        self.write_sources()
        plan = METRICS.validate_plan(self.plan_path)
        source = self.sources[-1]
        path = self.raw / source["outputFile"]
        rows = path.read_text(encoding="utf-8").splitlines()
        path.write_text("\n".join(rows[:-1]) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "行数必须精确"):
            METRICS.normalize_source(path, plan, source)
        self.write_sources()
        rows = path.read_text(encoding="utf-8").splitlines()
        second = json.loads(rows[1])
        second["metrics"]["networkRxBytes"] = 0
        first = json.loads(rows[0])
        first["metrics"]["networkRxBytes"] = 2
        rows[0], rows[1] = json.dumps(first), json.dumps(second)
        path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "计数器回退"):
            METRICS.normalize_source(path, plan, source)
        self.write_sources()
        path = self.raw / self.sources[0]["outputFile"]
        rows = path.read_text(encoding="utf-8").splitlines()
        changed = json.loads(rows[2]); changed["epochMillis"] += 1; rows[2] = json.dumps(changed)
        path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "跨源 sequence=2"):
            METRICS.recompute(self.plan_path, self.raw, "unified-metrics.jsonl")

    def test_backend_timer_boundaries_cannot_change_mid_run(self) -> None:
        """桶计数虽单调但边界改变时不能按数组下标继续比较。"""
        self.write_sources()
        plan = METRICS.validate_plan(self.plan_path)
        source = next(item for item in self.sources if item["sourceType"] == "backend")
        path = self.raw / source["outputFile"]
        rows = path.read_text(encoding="utf-8").splitlines()
        changed = json.loads(rows[1])
        changed["metrics"]["uplinkLatencySeconds"]["buckets"][0]["le"] = 2.0
        rows[1] = json.dumps(changed)
        path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "Timer 桶边界漂移"):
            METRICS.normalize_source(path, plan, source)

    def test_sampler_uses_injected_clock_instead_of_waiting_sixty_seconds(self) -> None:
        """调度器在虚拟时钟下仍产生 13 个精确 tick，单测不真实等待。"""
        class Clock:
            now = 10.0

            def monotonic(self):
                return self.now

            def sleep(self, seconds):
                self.now += seconds

            def epoch(self):
                return round(self.now * 1000)

        clock = Clock()
        with mock.patch.object(METRICS, "run_source",
                               side_effect=lambda source: metrics_for(source["sourceType"])):
            paths = METRICS.sample(self.plan_path, self.raw, monotonic=clock.monotonic,
                                   epoch_millis=clock.epoch, sleeper=clock.sleep)
        self.assertEqual(len(self.sources), len(paths))
        self.assertTrue(all(len(path.read_text(encoding="utf-8").splitlines()) == 13 for path in paths))

    def test_plan_rejects_shell_secret_and_self_selected_interval(self) -> None:
        """shell、秘密 argv 与 plan 自选 1 秒采样均不能绕过合同。"""
        plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        plan["intervalMillis"] = 1000
        self.plan_path.write_text(json.dumps(plan), encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "5s tick"):
            METRICS.validate_plan(self.plan_path)
        self.write_plan("C3E0_TOOL_QUALIFICATION")
        plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        plan["sources"][0]["argv"] = ["bash", "collector.sh"]
        self.plan_path.write_text(json.dumps(plan), encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "禁止 shell"):
            METRICS.validate_plan(self.plan_path)
        self.write_plan("C3E0_TOOL_QUALIFICATION")
        plan = json.loads(self.plan_path.read_text(encoding="utf-8"))
        plan["sources"][0]["argv"] = [sys.executable, "--token=secret"]
        self.plan_path.write_text(json.dumps(plan), encoding="utf-8")
        with self.assertRaisesRegex(METRICS.MetricsError, "疑似包含秘密"):
            METRICS.validate_plan(self.plan_path)


if __name__ == "__main__":
    unittest.main()
