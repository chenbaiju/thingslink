#!/usr/bin/env python3
"""G1-C3e-0 跨主机协调、统一指标与四态错误矩阵回归。"""

import importlib.util
import copy
import hashlib
import json
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1]


def load_module(name: str):
    """从相邻脚本加载待测 CLI，避免要求 scripts 成为 Python package。"""
    specification = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(specification)
    sys.modules[name] = module
    specification.loader.exec_module(module)
    return module


COORDINATOR = load_module("l2_distributed_coordinator")
METRICS = load_module("l2_metrics")
VERDICT = load_module("l2_machine_verdict")
EVIDENCE = load_module("l2_evidence_manifest")
ENVIRONMENT = load_module("l2_environment_inventory")


def environment_host(host_id, peer, *, sut=False):
    """生成满足冻结字段的环境主机；测试只使用摘要，不携带凭据。"""
    disk = {"type": "cloud-ssd", "capacityBytes": 107_374_182_400,
            "baselineIops": 3000, "baselineThroughputBytesPerSecond": 131_072_000}
    host = {
        "hostId": host_id,
        "cloud": {"provider": "cloud-a", "region": "cn-test-1",
                  "availabilityZone": "cn-test-1a", "instanceType": "fixed-type"},
        "hardware": {"cpuModel": "Example CPU", "vcpus": 4,
                     "memoryBytes": 8 * 1024 * 1024 * 1024},
        "os": {"name": "Ubuntu", "version": "24.04", "imageId": "img-20260824",
               "kernel": "6.8.0-test", "architecture": "x86_64"},
        "disks": {"root": dict(disk), "data": dict(disk)},
        "runtime": {"dockerClient": "28.3.3", "dockerServer": "28.3.3",
                    "jdk": "21.0.8", "python": "3.12.3"},
        "jvm": {"heapInitialBytes": 268_435_456, "heapMaxBytes": 1_073_741_824},
        "network": {"bandwidthMbps": 1000, "privateRttPeer": peer,
                    "privateRttMillis": 0.42},
        "ntp": {"service": "chrony", "synchronized": True, "offsetMillis": 5.2,
                "checkedAt": "2026-08-24T10:00:00Z"},
        "images": {"simulator": f"sha256:{'d' * 64}"},
        "configSha256": {"simulator": "e" * 64},
    }
    if sut:
        host["hardware"] = {"cpuModel": "Example 8C CPU", "vcpus": 8,
                            "memoryBytes": 16 * 1024 * 1024 * 1024}
        host["images"] = {name: f"sha256:{character * 64}" for name, character in
                          (("timescaledb", "1"), ("redis", "2"), ("redpanda", "3"),
                           ("emqx", "4"), ("minio", "5"))}
        host.update({
            "hikari": {"control": {"maximumPoolSize": 6, "connectionTimeoutMillis": 500},
                       "data": {"maximumPoolSize": 10, "connectionTimeoutMillis": 500}},
            "postgresql": {"version": "17.6", "parameters": {"max_connections": "200"}},
            "timescale": {"version": "2.21.1", "parameters": {"max_background_workers": "16"}},
            "redpanda": {"version": "25.2.5", "smp": 1, "memoryBytes": 2_147_483_648},
            "redis": {"version": "8.2.0"}, "emqx": {"version": "5.8.8"},
            "minio": {"version": "RELEASE.2025-07-23"},
        })
    return host


def environment_inventory():
    """生成与 distributed plan 主机、JAR 和 A4 runner 摘要精确绑定的清单。"""
    return {
        "schemaVersion": 1, "capturedAt": "2026-08-24T10:00:00Z",
        "repository": {"commit": "f" * 40},
        "artifacts": {"backendJarSha256": "9" * 64, "simulatorJarSha256": "b" * 64,
                      "scriptsSha256": {name: ("c" * 64 if name == "a4_qualification.py"
                                                else "8" * 64)
                                         for name in ENVIRONMENT.REQUIRED_SCRIPT_NAMES}},
        "sut": environment_host("sut-0", "host-a", sut=True),
        "generators": [environment_host("host-a", "sut-0"),
                       environment_host("host-b", "sut-0")],
    }


NORMALIZED_INVENTORY = ENVIRONMENT.normalize(environment_inventory())
FINGERPRINT = hashlib.sha256(ENVIRONMENT.canonical_bytes(
    ENVIRONMENT.fingerprint_document(NORMALIZED_INVENTORY))).hexdigest()


def timer(count):
    """生成含有限桶与 +Inf 的累计 Timer。"""
    return {"buckets": [{"le": 1.0, "count": count}, {"le": "+Inf", "count": count}],
            "count": count, "sum": float(count) / 10}


def typed_metrics(source_type, sequence):
    """为 verdict fixture 生成固定 source type 的有效原始样本。"""
    host_metrics = {
        "cpuPercent": 10.0, "memoryUsedBytes": 100, "memoryTotalBytes": 1000,
        "swapUsedBytes": 0, "rootDiskFreeBytes": 500, "rootDiskTotalBytes": 1000,
        "dataDiskFreeBytes": 500, "dataDiskTotalBytes": 1000, "dataDiskReadOnly": False,
        "oomKillCount": 0, "networkRxBytes": sequence, "networkTxBytes": sequence,
    }
    if source_type == "sut-host":
        return host_metrics
    if source_type == "generator-host":
        return {**host_metrics, "threadCount": 10, "fdCount": 20,
                "tickLatenessMillis": 1, "scheduledOperations": sequence,
                "submittedOperations": sequence, "pubackCount": sequence}
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


class L2CoordinationTests(unittest.TestCase):
    """锁住整组 A4 身份、manifest 唯一性与单向阶段状态机。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.plan = self.base / "plan.json"
        self.shard_ids = lambda host_id, count=5: [f"{host_id}-shard-{index:03d}" for index in range(count)]
        self.host_plan = lambda host_id, count=5000: {
                                          "hostId": host_id, "shardIds": self.shard_ids(host_id, count // 1000),
                                          "deviceCount": count,
                                          "a4Argv": [sys.executable, "-c", "raise SystemExit(0)",
                                                     "--run-id", "run-1", "--device-count", str(count),
                                                     "--shard-size", "1000", "--shard-id-prefix", host_id,
                                                     "--output-dir", host_id],
                                          "a4TimeoutSeconds": 10,
                                          "reportPath": f"{host_id}/qualification-report.json",
                                          "manifestRoot": f"{host_id}/manifests"}
        self.write(self.plan, {"schemaVersion": 1, "runId": "run-1",
                               "environmentFingerprint": FINGERPRINT, "targetDeviceCount": 10000,
                               "artifacts": {"simulatorJarSha256": "b" * 64,
                                             "a4RunnerSha256": "c" * 64},
                               "hosts": [self.host_plan("host-a"), self.host_plan("host-b")]})
        self.envelopes = self.base / "envelopes"
        self.envelopes.mkdir()
        self.write_envelope("host-a", "PASS", ["a-1", "a-2"])
        self.write_envelope("host-b", "PASS", ["b-1", "b-2"])

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def write_envelope(self, host_id, result, messages):
        embedded = {"schemaVersion": 1, "runId": "run-1", "result": result,
                    "fingerprint": {"jarSha256": "b" * 64, "runnerSha256": "c" * 64},
                    "checks": [{"name": "qualification", "passed": result == "PASS"}]}
        self.write(self.envelopes / f"{host_id}.host-envelope.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "hostId": host_id, "result": result,
            "distributedPlanSha256": COORDINATOR.sha256_file(self.plan),
            "artifacts": {"simulatorJarSha256": "b" * 64, "a4RunnerSha256": "c" * 64},
            "a4Report": embedded,
            "qualifiedShardSize": 1000, "deviceCount": 5000,
            "shards": [{"shardId": shard_id, "globalShardId": f"{host_id}/{shard_id}",
                        "deviceCount": 1000} for shard_id in self.shard_ids(host_id)],
            "propertyMessageIds": messages,
        })

    def test_complete_group_passes_with_global_identity(self):
        report = COORDINATOR.aggregate_group(self.plan, self.envelopes)
        self.assertEqual("PASS", report["result"])
        self.assertEqual(2, report["hostCount"])
        self.assertEqual(10, report["shardCount"])
        self.assertEqual(4, report["propertyMessageCount"])

    def test_missing_or_duplicate_host_fails_closed(self):
        (self.envelopes / "host-b.host-envelope.json").unlink()
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "host envelope 集合不匹配"):
            COORDINATOR.aggregate_group(self.plan, self.envelopes)
        self.write(self.plan, {"schemaVersion": 1, "runId": "run-1",
                               "environmentFingerprint": FINGERPRINT, "targetDeviceCount": 10000,
                               "artifacts": {"simulatorJarSha256": "b" * 64,
                                             "a4RunnerSha256": "c" * 64},
                               "hosts": [self.host_plan("host-a"), self.host_plan("host-a")]})
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "hostId 重复"):
            COORDINATOR.aggregate_group(self.plan, self.envelopes)

    def test_run_environment_global_shard_and_message_drift_are_errors(self):
        path = self.envelopes / "host-b.host-envelope.json"
        envelope = json.loads(path.read_text(encoding="utf-8"))
        envelope["runId"] = "other"
        self.write(path, envelope)
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "漂移"):
            COORDINATOR.aggregate_group(self.plan, self.envelopes)
        envelope["runId"] = "run-1"
        duplicate = self.shard_ids("host-b")[0]
        envelope["shards"].append({"shardId": duplicate, "globalShardId": f"host-b/{duplicate}"})
        self.write(path, envelope)
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "globalShardId 重复"):
            COORDINATOR.aggregate_group(self.plan, self.envelopes)
        envelope["shards"] = envelope["shards"][:-1]
        envelope["propertyMessageIds"] = ["a-1", "b-2"]
        self.write(path, envelope)
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "messageId 重复"):
            COORDINATOR.aggregate_group(self.plan, self.envelopes)

    def test_any_host_fail_makes_group_fail(self):
        # 宿主资源可在首次 PUBACK 前越线；真实资格 FAIL 不因空 manifest 被误归为工具 ERROR。
        self.write_envelope("host-b", "FAIL", [])
        self.assertEqual("FAIL", COORDINATOR.aggregate_group(self.plan, self.envelopes)["result"])

    def test_mixed_qualified_shard_sizes_are_rejected(self):
        path = self.envelopes / "host-b.host-envelope.json"
        envelope = json.loads(path.read_text(encoding="utf-8")); envelope["qualifiedShardSize"] = 500
        self.write(path, envelope)
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "qualifiedShardSize 不一致"):
            COORDINATOR.aggregate_group(self.plan, self.envelopes)

    def test_host_envelope_rejects_old_jar_or_runner(self):
        report = self.base / "qualification-report.json"; manifests = self.base / "manifests"
        shards = []
        for shard_id in self.shard_ids("host-a"):
            folder = manifests / "run-1" / shard_id; folder.mkdir(parents=True)
            (folder / "property_report.log").write_text(f"message-{shard_id}\n", encoding="utf-8")
            shards.append({"shardId": shard_id, "deviceCount": 1000, "propertyManifestLines": 1})
        self.write(report, {"schemaVersion": 1, "runId": "run-1", "result": "PASS",
                            "configuration": {"shardSize": 1000, "deviceCount": 5000},
                            "fingerprint": {"jarSha256": "d" * 64, "runnerSha256": "c" * 64},
                            "shards": shards})
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "JAR/runner SHA"):
            COORDINATOR.build_host_envelope(report, manifests, "host-a", plan_path=self.plan)

    def test_single_host_with_multiple_concurrent_shards_is_allowed(self):
        shard_ids = self.shard_ids("host-a", 10)
        self.write(self.plan, {"schemaVersion": 1, "runId": "run-1",
                               "environmentFingerprint": FINGERPRINT, "targetDeviceCount": 10000,
                               "artifacts": {"simulatorJarSha256": "b" * 64,
                                             "a4RunnerSha256": "c" * 64},
                               "hosts": [self.host_plan("host-a", 10000)]})
        self.write(self.envelopes / "host-a.host-envelope.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "distributedPlanSha256": COORDINATOR.sha256_file(self.plan),
            "artifacts": {"simulatorJarSha256": "b" * 64, "a4RunnerSha256": "c" * 64},
            "a4Report": {"schemaVersion": 1, "runId": "run-1", "result": "PASS",
                         "fingerprint": {"jarSha256": "b" * 64, "runnerSha256": "c" * 64},
                         "checks": [{"name": "qualification", "passed": True}]},
            "hostId": "host-a", "result": "PASS", "qualifiedShardSize": 1000,
            "deviceCount": 10000,
            "shards": [{"shardId": shard_id, "globalShardId": f"host-a/{shard_id}",
                        "deviceCount": 1000} for shard_id in shard_ids],
            "propertyMessageIds": ["a-1", "a-2"],
        })
        (self.envelopes / "host-b.host-envelope.json").unlink()
        report = COORDINATOR.aggregate_group(self.plan, self.envelopes)
        self.assertEqual(1, report["hostCount"])
        self.assertEqual(10, report["shardCount"])

    def test_ready_start_complete_is_single_direction_and_bounded(self):
        signals = self.base / "signals"
        signals.mkdir()
        for host_id in ("host-a", "host-b"):
            common = {"schemaVersion": 1, "runId": "run-1",
                      "environmentFingerprint": FINGERPRINT, "hostId": host_id}
            self.write(signals / f"{host_id}.ready.json", {**common, "phase": "READY"})
            self.write(signals / f"{host_id}.complete.json",
                       {**common, "phase": "COMPLETE", "outcome": "PASS"})
        result = COORDINATOR.coordinate(self.plan, signals, 0.1, 0.1, 0.01)
        self.assertEqual("PASS", result["outcome"])
        self.assertTrue((signals / "controller.start.json").is_file())
        (signals / "host-b.complete.json").unlink()
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "等待 COMPLETE 超时"):
            COORDINATOR.coordinate(self.plan, signals, 0.1, 0.01, 0.005)

    def prepare_worker_report(self, host_id, result):
        host_root = self.base / host_id; manifest_root = host_root / "manifests"
        (host_root / "logs").mkdir(parents=True, exist_ok=True)
        shards = []
        for shard_id in self.shard_ids(host_id):
            folder = manifest_root / "run-1" / shard_id; folder.mkdir(parents=True, exist_ok=True)
            (folder / "property_report.log").write_text(f"{host_id}-{shard_id}\n", encoding="utf-8")
            (host_root / "logs" / f"{shard_id}.log").write_text(
                f"resource-{host_id}-{shard_id}\n", encoding="utf-8")
            shards.append({"shardId": shard_id, "deviceCount": 1000, "propertyManifestLines": 1})
        self.write(host_root / "qualification-report.json", {
            "schemaVersion": 1, "runId": "run-1", "result": result,
            "configuration": {"shardSize": 1000, "deviceCount": 5000},
            "fingerprint": {"jarSha256": "b" * 64, "runnerSha256": "c" * 64},
            "checks": [{"name": "all", "passed": result == "PASS"}], "shards": shards})
        (host_root / "qualification-report.md").write_text("# qualification\n", encoding="utf-8")

    def write_start(self, signals, fingerprint=FINGERPRINT):
        self.write(signals / "controller.start.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": fingerprint,
            "phase": "START", "readyHosts": ["host-a", "host-b"], "coordinationVersion": 1})

    def test_host_worker_pass_and_a4_fail(self):
        for result, exit_value in (("PASS", 0), ("FAIL", 1)):
            with self.subTest(result=result):
                shutil.rmtree(self.base / "host-a", ignore_errors=True)
                signals = self.base / f"signals-{result.lower()}"; signals.mkdir()
                evidence = self.base / f"evidence-{result.lower()}"
                plan = json.loads(self.plan.read_text(encoding="utf-8"))
                plan["hosts"][0]["a4Argv"][2] = f"raise SystemExit({exit_value})"
                self.write(self.plan, plan); self.write_start(signals)
                def run_a4(*_args, **_kwargs):
                    self.prepare_worker_report("host-a", result)
                    return SimpleNamespace(returncode=exit_value)
                with mock.patch.object(COORDINATOR.subprocess, "run", side_effect=run_a4):
                    signal, code = COORDINATOR.host_worker(
                        self.plan, "host-a", signals, evidence, 0.1, 0.01)
                self.assertEqual(exit_value, code); self.assertEqual(result, signal["outcome"])
                self.assertTrue((evidence / "host-envelopes" / "host-a.host-envelope.json").is_file())

    def test_host_worker_rejects_stale_same_run_output(self):
        signals = self.base / "signals-stale"; signals.mkdir(); self.write_start(signals)
        self.prepare_worker_report("host-a", "PASS")
        with mock.patch.object(COORDINATOR.subprocess, "run") as run_a4:
            signal, code = COORDINATOR.host_worker(
                self.plan, "host-a", signals, self.base / "ev-stale", 0.1, 0.01)
        self.assertEqual(2, code); self.assertEqual("ERROR", signal["outcome"])
        self.assertRegex(signal["error"], "启动前已存在")
        run_a4.assert_not_called()

    def test_host_worker_timeout_and_start_identity_drift_publish_error(self):
        plan = json.loads(self.plan.read_text(encoding="utf-8"))
        plan["hosts"][0]["a4Argv"][2] = "import time; time.sleep(2)"
        plan["hosts"][0]["a4TimeoutSeconds"] = 1; self.write(self.plan, plan)
        signals = self.base / "signals-timeout"; signals.mkdir(); self.write_start(signals)
        signal, code = COORDINATOR.host_worker(self.plan, "host-a", signals, self.base / "ev-timeout", 0.1, 0.01)
        self.assertEqual(2, code); self.assertEqual("ERROR", signal["outcome"])
        self.assertRegex(signal["error"], "超时")
        plan["hosts"][0]["a4Argv"][2] = "raise SystemExit(0)"
        self.write(self.plan, plan)
        signals = self.base / "signals-drift"; signals.mkdir(); self.write_start(signals, "d" * 64)
        signal, code = COORDINATOR.host_worker(self.plan, "host-a", signals, self.base / "ev-drift", 0.1, 0.01)
        self.assertEqual(2, code); self.assertRegex(signal["error"], "START 身份")

    def test_controller_stops_immediately_on_worker_error(self):
        plan, hosts = COORDINATOR.load_plan(self.plan)
        signals = self.base / "signals-error"; signals.mkdir()
        self.write(signals / "host-a.complete.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "hostId": "host-a", "phase": "COMPLETE", "outcome": "ERROR", "error": "boom"})
        started = time.monotonic()
        with self.assertRaisesRegex(COORDINATOR.CoordinationError, "提前报告 ERROR"):
            COORDINATOR.wait_host_phase(signals, plan, hosts, "COMPLETE", started + 5, 0.5)
        self.assertLess(time.monotonic() - started, 0.5)


class L2MetricsAndVerdictTests(unittest.TestCase):
    """锁住 NaN/空窗/hash 与 VALID_PASS/FAIL/INVALID/ERROR 四态。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.evidence = self.base / "evidence"
        self.evidence.mkdir()
        self.raw = self.evidence / "metric-sources"
        self.raw.mkdir()
        self.write_json("run-metadata.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "scope": "C3E0", "profile": "TOOL_QUALIFICATION"})
        inventory_bytes = ENVIRONMENT.canonical_bytes(NORMALIZED_INVENTORY)
        self.write_json("environment-inventory.input.json", environment_inventory())
        (self.evidence / "environment-inventory.json").write_bytes(inventory_bytes)
        self.write_json("environment-inventory-report.json", {
            "schemaVersion": 1, "status": "PASS", "errors": [],
            "comparisonFingerprint": FINGERPRINT,
            "inventorySha256": hashlib.sha256(inventory_bytes).hexdigest(),
            "sutHostId": "sut-0", "generatorHostIds": ["host-a", "host-b"]})
        shard_ids_by_host = {host_id: [f"{host_id}-shard-{index:03d}" for index in range(5)]
                             for host_id in ("host-a", "host-b")}
        self.write_json("distributed-plan.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "artifacts": {"simulatorJarSha256": "b" * 64, "a4RunnerSha256": "c" * 64},
            "targetDeviceCount": 10000,
            "hosts": [{"hostId": host_id, "shardIds": shard_ids_by_host[host_id],
                       "deviceCount": 5000,
                       "a4Argv": [sys.executable, "-c", "raise SystemExit(0)",
                                  "--run-id", "run-1", "--device-count", "5000",
                                  "--shard-size", "1000", "--shard-id-prefix", host_id,
                                  "--output-dir", host_id],
                       "a4TimeoutSeconds": 10,
                       "reportPath": f"{host_id}/qualification-report.json",
                       "manifestRoot": f"{host_id}/manifests"}
                      for host_id in ("host-a", "host-b")]})
        host_envelopes = self.evidence / "host-envelopes"; host_envelopes.mkdir()
        distributed_plan_path = self.evidence / "distributed-plan.json"
        coordination = self.evidence / "coordination"; coordination.mkdir()
        self.write_json("external-runbook.json", {"schemaVersion": 1, "name": "test-runbook"})
        self.write_json("coordination/controller.start.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "phase": "START", "readyHosts": ["host-a", "host-b"], "coordinationVersion": 1})
        self.write_json("coordination/coordination-result.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "phase": "COMPLETE", "outcome": "PASS",
            "hosts": [["host-a", "PASS"], ["host-b", "PASS"]]})
        for host_id in ("host-a", "host-b"):
            host_root = self.evidence / host_id
            report_path = host_root / "qualification-report.json"
            manifest_root = host_root / "manifests"
            (host_root / "logs").mkdir(parents=True)
            shards = []
            for shard_id in shard_ids_by_host[host_id]:
                manifest = manifest_root / "run-1" / shard_id / "property_report.log"
                manifest.parent.mkdir(parents=True)
                manifest.write_text(f"{host_id}-{shard_id}-message\n", encoding="utf-8")
                (host_root / "logs" / f"{shard_id}.log").write_text(
                    f"resource-{host_id}-{shard_id}\n", encoding="utf-8")
                shards.append({"shardId": shard_id, "deviceCount": 1000,
                               "propertyManifestLines": 1})
            report = {"schemaVersion": 1, "runId": "run-1", "result": "PASS",
                      "configuration": {"shardSize": 1000, "deviceCount": 5000},
                      "fingerprint": {"jarSha256": "b" * 64, "runnerSha256": "c" * 64},
                      "checks": [{"name": "all", "passed": True}], "shards": shards}
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.write_text(json.dumps(report), encoding="utf-8")
            report_path.with_suffix(".md").write_text("# qualification\n", encoding="utf-8")
            envelope = COORDINATOR.build_host_envelope(
                report_path, manifest_root, host_id, plan_path=distributed_plan_path)
            envelope_path = host_envelopes / f"{host_id}.host-envelope.json"
            envelope_path.write_text(json.dumps(envelope), encoding="utf-8")
            for phase in ("ready", "complete"):
                signal = {
                    "schemaVersion": 1, "runId": "run-1",
                    "environmentFingerprint": FINGERPRINT, "hostId": host_id,
                    "phase": phase.upper(), "outcome": "PASS"}
                if phase == "complete":
                    signal["hostEnvelopeSha256"] = hashlib.sha256(envelope_path.read_bytes()).hexdigest()
                self.write_json(f"coordination/{host_id}.{phase}.json", signal)
        qualification = COORDINATOR.aggregate_group(distributed_plan_path, host_envelopes)
        self.write_json("distributed-qualification.json", qualification)
        tenants = []
        for index in range(10):
            tenants.append({"tenantIndex": index, "tenantId": f"tenant-{index}",
                            "projectId": f"project-{index}", "policyId": "policy-l2",
                            "policyCode": "L2_CAPACITY", "policyVersion": 1,
                            "deviceCountLimit": 2000, "timeSeriesPointDailyLimit": 10_000_000,
                            "assignmentVersion": 2,
                            "devices": [{"deviceId": f"device-{index}-{device}",
                                         "deviceKey": f"key-{index}-{device}",
                                         "hostId": "host-a" if index < 5 else "host-b",
                                         "shardId": ("host-a" if index < 5 else "host-b")
                                                    + f"-shard-{index % 5:03d}"}
                                        for device in range(1000)]})
        self.write_json("fixture-tenants.json", {"schemaVersion": 1, "runId": "run-1",
                                                  "environmentFingerprint": FINGERPRINT,
                                                  "tenantCount": 10, "projectCount": 10,
                                                  "deviceCount": 10000, "tenants": tenants,
                                                  "qualification": {"outcome": "PASS"}})
        distributed_plan = json.loads(distributed_plan_path.read_text(encoding="utf-8"))
        self.metric_sources = [
            {**identity, "argv": [sys.executable, "collector.py"], "timeoutMillis": 1000,
             "outputFile": f"{identity['sourceId']}.jsonl"}
            for identity in METRICS.derive_expected_sources(NORMALIZED_INVENTORY, distributed_plan)]
        self.metrics_plan = self.evidence / "metrics-source-plan.json"
        self.write_json("metrics-source-plan.json", {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "profile": "C3E0_TOOL_QUALIFICATION", "phase": "TOOL_QUALIFICATION",
            "intervalMillis": 5000, "durationMillis": 60_000, "exactSampleCount": 13,
            "maxTickLatenessMillis": 500, "evaluatorImplemented": True,
            "inventoryFile": "environment-inventory.json",
            "inventorySha256": hashlib.sha256(inventory_bytes).hexdigest(),
            "distributedPlanFile": "distributed-plan.json",
            "distributedPlanSha256": hashlib.sha256(distributed_plan_path.read_bytes()).hexdigest(),
            "sources": self.metric_sources})
        for source in self.metric_sources:
            self.write_metric_source(source)
        METRICS.collect(self.metrics_plan, self.raw, self.evidence / "unified-metrics.jsonl",
                        self.evidence / "metrics-summary.json")
        self.write_json("fixture-quota.json", {"schemaVersion": 1, "result": "PASS"})
        self.write_json("fixture-assignment-plan.json", {"schemaVersion": 1, "result": "PASS"})
        (self.evidence / "tool-error-matrix.json").write_bytes(
            VERDICT.canonical_json_bytes(VERDICT.build_error_matrix()))
        self.rebuild_manifest("C3E0_QUALIFICATION_INPUT")
        self.rebuild_manifest("C3E0_FULL_INPUT")

    def tearDown(self):
        self.temporary.cleanup()

    def test_verdict_json_reader_rejects_duplicate_keys(self):
        duplicate = self.evidence / "duplicate.json"
        duplicate.write_text('{"schemaVersion":1,"schemaVersion":2}', encoding="utf-8")
        with self.assertRaisesRegex(VERDICT.EvidenceError, "重复 JSON 键"):
            VERDICT.read_object(duplicate)

    def write_json(self, name, value):
        (self.evidence / name).write_text(json.dumps(value), encoding="utf-8")

    def rebuild_manifest(self, stage):
        output = self.evidence / EVIDENCE.OUTPUT_NAMES[stage]
        EVIDENCE.build_registered(self.evidence, stage, output)

    def write_metric_source(self, source):
        rows = []
        for sequence in range(13):
            rows.append({"schemaVersion": 1, "runId": "run-1",
                         "environmentFingerprint": FINGERPRINT,
                         "profile": "C3E0_TOOL_QUALIFICATION", "phase": "TOOL_QUALIFICATION",
                         "source": {key: source[key] for key in
                                    ("sourceId", "sourceType", "hostId", "role", "component")},
                         "sequence": sequence,
                         "scheduledMonotonicMillis": 1000 + sequence * 5000,
                         "observedMonotonicMillis": 1010 + sequence * 5000,
                         "epochMillis": 10_000 + sequence * 5000,
                         "metrics": typed_metrics(source["sourceType"], sequence)})
        (self.raw / source["outputFile"]).write_text(
            "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")

    def set_host_result(self, host_id, result):
        """同步改写 A4 原件与 envelope，避免测试制造不可能的嵌入报告。"""
        report_path = self.evidence / host_id / "qualification-report.json"
        report = json.loads(report_path.read_text(encoding="utf-8"))
        report["result"] = result
        report["checks"][0]["passed"] = result == "PASS"
        report_path.write_text(json.dumps(report), encoding="utf-8")
        envelope = COORDINATOR.build_host_envelope(
            report_path, self.evidence / host_id / "manifests", host_id,
            plan_path=self.evidence / "distributed-plan.json")
        envelope_path = self.evidence / "host-envelopes" / f"{host_id}.host-envelope.json"
        envelope_path.write_text(json.dumps(envelope), encoding="utf-8")
        complete_path = self.evidence / "coordination" / f"{host_id}.complete.json"
        complete = json.loads(complete_path.read_text(encoding="utf-8"))
        complete["outcome"] = result
        complete["hostEnvelopeSha256"] = hashlib.sha256(envelope_path.read_bytes()).hexdigest()
        complete_path.write_text(json.dumps(complete), encoding="utf-8")
        result_path = self.evidence / "coordination" / "coordination-result.json"
        coordination_result = json.loads(result_path.read_text(encoding="utf-8"))
        outcomes = {item[0]: item[1] for item in coordination_result["hosts"]}
        outcomes[host_id] = result
        coordination_result["hosts"] = sorted([key, value] for key, value in outcomes.items())
        coordination_result["outcome"] = (
            "PASS" if all(value == "PASS" for value in outcomes.values()) else "FAIL")
        result_path.write_text(json.dumps(coordination_result), encoding="utf-8")
        complete_documents = [json.loads(path.read_text(encoding="utf-8")) for path in
                              sorted((self.evidence / "coordination").glob("host-*.complete.json"))]
        coordination_result = {
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "phase": "COMPLETE",
            "outcome": ("PASS" if all(item["outcome"] == "PASS" for item in complete_documents)
                        else "FAIL"),
            "hosts": [[item["hostId"], item["outcome"]]
                      for item in sorted(complete_documents, key=lambda item: item["hostId"])]}
        self.write_json("coordination/coordination-result.json", coordination_result)

    def test_all_four_machine_states(self):
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("VALID_PASS", report["validityStatus"])
        self.assertEqual("C3E0_TOOL_QUALIFICATION", report["evaluatorKey"])
        self.assertIn("不证明 L2-S", report["claimBoundary"])
        self.assertFalse((self.evidence / "sut-verdict-input.json").exists())
        self.assertFalse(any(check["name"].startswith("sut.") for check in report["checks"]))
        self.assertEqual("ERROR", VERDICT.classify(["broken"], "FAIL", [{"passed": False}]))
        self.assertEqual("INVALID_GENERATOR", VERDICT.classify([], "FAIL", [{"passed": False}]))
        self.assertEqual("VALID_FAIL", VERDICT.classify([], "PASS", [{"passed": False}]))
        self.assertEqual("VALID_PASS", VERDICT.classify([], "PASS", [{"passed": True}]))
        matrix = VERDICT.build_error_matrix()
        self.assertEqual("PASS", matrix["result"])
        self.assertEqual(["ERROR", "INVALID_GENERATOR", "VALID_FAIL", "VALID_PASS"],
                         [case["actual"] for case in matrix["cases"]])
        self.set_host_result("host-b", "FAIL")
        qualification = COORDINATOR.aggregate_group(self.evidence / "distributed-plan.json",
                                                    self.evidence / "host-envelopes")
        self.write_json("distributed-qualification.json", qualification)
        self.rebuild_manifest("C3E0_QUALIFICATION_INPUT")
        self.assertEqual("INVALID_GENERATOR", VERDICT.evaluate(self.evidence, "failure")["validityStatus"])
        self.set_host_result("host-b", "PASS")
        qualification = COORDINATOR.aggregate_group(self.evidence / "distributed-plan.json",
                                                    self.evidence / "host-envelopes")
        self.write_json("distributed-qualification.json", qualification)
        self.rebuild_manifest("C3E0_QUALIFICATION_INPUT")
        self.rebuild_manifest("C3E0_FULL_INPUT")
        (self.evidence / "fixture-tenants.json").unlink()
        self.assertEqual("ERROR", VERDICT.evaluate(self.evidence, "failure")["validityStatus"])

    def test_scope_profile_registry_is_fail_closed(self):
        """缺注册身份或请求未实现的 L2 profile 都不能借用工具 PASS。"""
        metadata_path = self.evidence / "run-metadata.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata.pop("scope")
        self.write_json("run-metadata.json", metadata)
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertRegex(" ".join(report["errors"]), "scope/profile")
        for profile in ("S", "H", "M", "R", "B", "E"):
            self.write_json("run-metadata.json", {
                **metadata, "scope": "L2", "profile": profile})
            report = VERDICT.evaluate(self.evidence, "success")
            self.assertEqual("ERROR", report["validityStatus"])
            self.assertEqual(profile, report["evaluatorKey"])
            self.assertFalse(report["evaluatorImplemented"])
            self.assertRegex(" ".join(report["errors"]), "evaluator 尚未实现")

    def test_runner_failure_is_tool_error_not_fake_capacity_failure(self):
        """A4 PASS 后 Runner 失败是工具 ERROR，C3e-0 不伪造 VALID_FAIL。"""
        report = VERDICT.evaluate(self.evidence, "failure")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertRegex(" ".join(report["errors"]), "Runner 未成功")

    def test_forged_tool_error_matrix_is_error_even_after_manifest_rebuild(self):
        """哈希一致不能使任意错误矩阵获得裁决器背书。"""
        matrix = VERDICT.build_error_matrix()
        matrix["cases"][0]["expected"] = "VALID_PASS"
        (self.evidence / "tool-error-matrix.json").write_bytes(
            VERDICT.canonical_json_bytes(matrix))
        self.rebuild_manifest("C3E0_FULL_INPUT")
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertIn("tool-error-matrix.json", " ".join(report["errors"]))

    def test_hash_mismatch_is_error(self):
        with (self.evidence / "unified-metrics.jsonl").open("a", encoding="utf-8") as output:
            output.write("{}\n")
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertRegex(" ".join(report["errors"]), "summary|source")

    def test_non_metrics_evidence_hash_mismatch_is_error(self):
        fixture = json.loads((self.evidence / "fixture-tenants.json").read_text(encoding="utf-8"))
        fixture["tenants"][0]["projectId"] = "tampered-project"
        self.write_json("fixture-tenants.json", fixture)
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertIn("fixture-tenants.json", " ".join(report["errors"]))

    def test_fixture_environment_fingerprint_drift_is_error_after_rehash(self):
        fixture = json.loads((self.evidence / "fixture-tenants.json").read_text(encoding="utf-8"))
        fixture["environmentFingerprint"] = "0" * 64
        self.write_json("fixture-tenants.json", fixture)
        self.rebuild_manifest("C3E0_FULL_INPUT")
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertRegex(" ".join(report["errors"]), "fixture-tenants.json.*不一致")

    def test_inventory_generator_host_set_drift_is_error_in_full_evaluate(self):
        inventory = json.loads((self.evidence / "environment-inventory.json").read_text(
            encoding="utf-8"))
        inventory["generators"][1]["hostId"] = "host-c"
        normalized = ENVIRONMENT.normalize(inventory)
        new_fingerprint = hashlib.sha256(ENVIRONMENT.canonical_bytes(
            ENVIRONMENT.fingerprint_document(normalized))).hexdigest()
        # 除环境静态证据外，将同 run 后续证据整体切到新指纹，使裁决必须抵达 host 集核对。
        for path in self.evidence.rglob("*"):
            if (not path.is_file() or path.name in {"environment-inventory.json",
                                                    "environment-inventory-report.json",
                                                    *EVIDENCE.OUTPUT_NAMES.values()}):
                continue
            content = path.read_text(encoding="utf-8")
            path.write_text(content.replace(FINGERPRINT, new_fingerprint), encoding="utf-8")
        canonical = ENVIRONMENT.canonical_bytes(normalized)
        (self.evidence / "environment-inventory.json").write_bytes(canonical)
        self.write_json("environment-inventory-report.json", {
            "schemaVersion": 1, "status": "PASS", "errors": [],
            "inventorySha256": hashlib.sha256(canonical).hexdigest(),
            "comparisonFingerprint": new_fingerprint, "sutHostId": "sut-0",
            "generatorHostIds": ["host-a", "host-c"]})
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertRegex(" ".join(report["errors"]), "generator hostId 集不一致")

    def test_tampered_distributed_plan_cannot_pass_after_rebuilding_manifest(self):
        plan_path = self.evidence / "distributed-plan.json"
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        plan["hosts"][0]["shardIds"][0] = "tampered-shard"
        plan_path.write_text(json.dumps(plan), encoding="utf-8")
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertRegex(" ".join(report["errors"]), "跨主机资格重算失败|重算结果不一致")

    def test_tampered_raw_metrics_and_forged_hashes_still_recompute_to_error(self):
        raw_path = self.raw / "sut-backend.jsonl"
        rows = raw_path.read_text(encoding="utf-8").splitlines()
        first = json.loads(rows[0]); first["metrics"]["http5xxTotal"] = 99
        rows[0] = json.dumps(first)
        raw_path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        unified_path = self.evidence / "unified-metrics.jsonl"
        unified_path.write_text(unified_path.read_text(encoding="utf-8") + "{}\n", encoding="utf-8")
        summary_path = self.evidence / "metrics-summary.json"
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        summary["unifiedMetricsSha256"] = hashlib.sha256(unified_path.read_bytes()).hexdigest()
        for source in summary["sources"]:
            if source["sourceId"] == "sut-backend":
                source["sourceSha256"] = hashlib.sha256(raw_path.read_bytes()).hexdigest()
        summary_path.write_text(json.dumps(summary), encoding="utf-8")
        self.rebuild_manifest("C3E0_FULL_INPUT")
        report = VERDICT.evaluate(self.evidence, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertIn("sut-backend.jsonl", " ".join(report["errors"]))


if __name__ == "__main__":
    unittest.main()
