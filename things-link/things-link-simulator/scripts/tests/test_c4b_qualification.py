"""G1-C4b 资格编排器的十场景完整性与注入边界测试。"""

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "c4b_qualification.py"
FORMAL_PLAN = Path(__file__).parents[1] / "c4b-scenario-plan.json"
SPEC = importlib.util.spec_from_file_location("c4b_qualification", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
RUN_ID = "019d2c58-7c6d-7000-8000-000000000001"


def notification_metric_sample(now: str, success: float | None,
                               failure: float | None, pid: int = 101,
                               group: str = "rule-notification",
                               topic: str = "tc.rule.notification") -> dict:
    """构造资格编排器可引用的当前 SUT actuator 指标样本。"""
    def result(name: str, value: float | None) -> dict:
        if value is None:
            return {"present": False, "value": None, "rawSamples": []}
        line = (f"thingslink_kafka_consumer_result_total{{group=\"{group}\","
                f"topic=\"{topic}\",result=\"{name}\"}} {value}")
        return {"present": True, "value": value, "rawSamples": [line]}

    return {"schemaVersion": 1, "source": "SUT_ACTUATOR",
            "endpointPath": "/actuator/prometheus",
            "processIdentity": {"pid": pid, "created": "process-start",
                                "commandSha256": "c" * 64, "jarSha256": "d" * 64},
            "results": {"success": result("success", success),
                        "failure": result("failure", failure)},
            "capturedAt": now}


def complete_plan():
    """构造覆盖十场景和五阶段的最小合法计划。"""
    return {"schemaVersion": 2,
            "phaseTimeoutSeconds": {
                "default": 180, "DB-01/afterProbe": 390,
                "RW-01/afterProbe": 300, "RW-02/afterProbe": 300,
                "RW-03/afterProbe": 300, "RW-04/afterProbe": 300,
                "RW-05/afterProbe": 300,
            },
            "scenarios": {scenario: {
        "fixture": ["probe", "fixture", "{scenario}"],
        "workloadBeforeCheckpoint": [], "beforeProbe": ["probe", "before"],
        "workloadAfterInjection": [], "afterProbe": ["probe", "after"],
    } for scenario in MODULE.RUNNER.SCENARIOS}}


def complete_metric_plan():
    """补齐所有直读当前 SUT 的阶段依赖，供计划加载合同使用。"""
    value = complete_plan()
    for scenario, phase in (("DB-01", "beforeProbe"), ("DB-01", "afterProbe"),
                            ("RW-04", "afterProbe")):
        value["scenarios"][scenario][phase].extend(
            ["--sut-metrics-url", "{sutMetricsUrl}"])
    return value


class FakeProcess:
    """只暴露编排器所需的进程存活接口。"""

    def __init__(self, pid):
        self.pid = pid
        self.alive = True

    def poll(self):
        return None if self.alive else 1


class FakeSut:
    """记录启动、强杀和 release，不执行真实 Java。"""

    def __init__(self):
        self.process = None
        self.started = []
        self.kills = 0
        self.releases = []

    def start(self, _java, arguments, _environment):
        self.process = FakeProcess(100 + len(self.started))
        self.started.append(arguments)
        return {"pid": self.process.pid}

    def kill(self):
        self.process.alive = False
        self.kills += 1

    def release(self, scenario):
        self.releases.append(scenario)


class FakeCompose:
    """记录数据库故障动作，确保 DB-01 不误杀 SUT。"""

    def __init__(self):
        self.actions = []

    def stop_database(self):
        self.actions.append("stop")

    def start_database(self):
        self.actions.append("start")


class FakeReadiness:
    """落真实可哈希引用，证明 fixture 前执行了消费组屏障。"""

    def __init__(self, root):
        self.root = Path(root)
        self.calls = []
        self.references = {}

    def wait(self, scenario, purpose="fixture", **_kwargs):
        self.calls.append((scenario, purpose))
        path = self.root / "readiness" / f"{scenario}-{purpose}.json"
        groups = {}
        for group, (topic, partitions) in MODULE.CONSUMER_REQUIREMENTS.items():
            groups[group] = {"returnCode": 0, "parsed": {
                "topic": topic, "state": "Stable", "totalLag": 0,
                "assignedPartitions": list(range(partitions)),
                "expectedPartitions": list(range(partitions)),
                "memberIds": ["member-1"], "ready": True}}
        MODULE.RUNNER.write_json(path, {
            "schemaVersion": 1, "scenario": scenario, "purpose": purpose,
            "verdict": "PASS", "groups": groups, "attempts": 1,
            "durationMillis": 1, "completedAt": "2026-08-28T00:00:00Z"})
        reference = MODULE.RUNNER.evidence_reference(self.root, path)
        self.references[(scenario, purpose)] = reference
        return {"verdict": "PASS", "groups": groups, "evidence": reference}

    def evidence_reference(self, scenario, purpose="fixture"):
        return self.references.get((scenario, purpose))


class C4bQualificationTests(unittest.TestCase):
    """验证资格计划 fail-closed 以及两类注入生命周期。"""

    def test_plan_requires_all_scenarios_and_phases(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            value = complete_metric_plan()
            value["scenarios"].pop("RW-05")
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(MODULE.RUNNER.C4bError):
                MODULE.load_plan(path)

    def test_plan_freezes_phase_budget_and_adapter_applies_recovery_overrides(self):
        """外层必须分别覆盖 DB 双预算与 RW 内层恢复加归因落盘余量。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            value = complete_metric_plan()
            value["phaseTimeoutSeconds"]["DB-01/afterProbe"] = 360
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(MODULE.RUNNER.C4bError):
                MODULE.load_plan(path)
            value = complete_metric_plan()
            value["scenarios"]["OB-01"].pop("afterProbe")
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(MODULE.RUNNER.C4bError):
                MODULE.load_plan(path)

        calls = []

        def fake_run(command, **kwargs):
            calls.append(kwargs["timeout"])
            return subprocess.CompletedProcess(command, 0, "{}", "")

        adapter = MODULE.CommandAdapter(complete_plan(), {}, fake_run)
        adapter.invoke("DB-01", "afterProbe")
        adapter.invoke("RW-01", "afterProbe")
        adapter.invoke("RW-05", "afterProbe")
        adapter.invoke("OB-01", "afterProbe")
        self.assertEqual([390, 300, 300, 180], calls)

    def test_plan_rejects_rw05_outer_timeout_equal_to_inner_recovery_budget(self):
        """RW-05 内外同为 180 秒会由父进程先杀探针，必须在启动环境前拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            value = complete_metric_plan()
            value["phaseTimeoutSeconds"]["RW-05/afterProbe"] = 180
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(MODULE.RUNNER.C4bError, "RW-01～05"):
                MODULE.load_plan(path)

    def test_adapter_uses_argv_and_requires_probe_json(self):
        calls = []

        def fake_run(command, **_kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, '{"fixtureId":"one"}', "")

        adapter = MODULE.CommandAdapter(complete_plan(), {"runId": RUN_ID}, fake_run)
        self.assertEqual("one", adapter.invoke("OB-01", "fixture")["fixtureId"])
        self.assertEqual(["probe", "fixture", "OB-01"], calls[0])

    def test_adapter_persists_bounded_failure_diagnostic_and_exception_chain(self):
        """阶段失败必须留下脱敏 argv、两条流和异常链，而不是只保留 C4bError。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def fake_run(command, **_kwargs):
                return subprocess.CompletedProcess(command, 9, "partial", "token=raw-secret")

            adapter = MODULE.CommandAdapter(complete_plan(), {}, fake_run, root)
            with self.assertRaises(MODULE.RUNNER.C4bError):
                adapter.invoke("DB-01", "fixture")
            reference = adapter.evidence_references("DB-01")["fixture"]
            evidence = json.loads((root / reference["path"]).read_text(encoding="utf-8"))
            self.assertEqual("FAIL", evidence["status"])
            self.assertEqual(9, evidence["returnCode"])
            self.assertNotIn("raw-secret", evidence["stderr"]["text"])
            self.assertEqual("C4bError", evidence["exception"]["chain"][0]["type"])
            self.assertNotIn("--root", json.dumps(evidence["argvSummary"]))

    def test_formal_plan_uses_existing_repository_driver_for_every_phase(self):
        """正式计划不得回退到空命令、shell 片段或测试替身。"""
        plan = MODULE.load_plan(FORMAL_PLAN)
        driver = Path(__file__).parents[1] / "c4b_scenario_driver.py"
        variables = {"python": "python", "driver": str(driver), "root": "C:/evidence",
                     "project": "c4b-019d2c587c6d",
                     "sutMetricsUrl": "http://127.0.0.1:47012/actuator/prometheus"}
        adapter = MODULE.CommandAdapter(plan, variables)
        self.assertTrue(driver.is_file())
        for scenario in MODULE.RUNNER.SCENARIOS:
            for phase in MODULE.PHASES:
                command = adapter.command(scenario, phase)
                self.assertEqual("python", command[0])
                self.assertEqual(str(driver), command[1])
                self.assertIn(scenario, command)
        self.assertIn("--sut-metrics-url", adapter.command("DB-01", "beforeProbe"))
        self.assertIn("--sut-metrics-url", adapter.command("RW-04", "afterProbe"))

    def test_plan_rejects_missing_current_sut_metrics_dependency(self):
        """DB-01 与 RW-04 的直读阶段缺 URL 时必须在启动环境前拒绝。"""
        for scenario, phase in (("DB-01", "beforeProbe"), ("DB-01", "afterProbe"),
                                ("RW-04", "afterProbe")):
            with self.subTest(scenario=scenario, phase=phase), \
                    tempfile.TemporaryDirectory() as directory:
                value = complete_metric_plan()
                value["scenarios"][scenario][phase] = ["probe", "after"]
                path = Path(directory) / "plan.json"
                path.write_text(json.dumps(value), encoding="utf-8")
                with self.assertRaisesRegex(MODULE.RUNNER.C4bError, "metrics URL"):
                    MODULE.load_plan(path)

    def test_health_target_and_prometheus_config_use_isolated_free_port(self):
        """C4b 不得为了固定 8080 停止开发后端，隔离配置必须抓取本轮端口。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            deploy = root / "deploy"
            (deploy / "prometheus").mkdir(parents=True)
            compose = deploy / "docker-compose.yml"
            compose.write_text("services: {}\n", encoding="utf-8")
            (deploy / "prometheus" / "prometheus.yml").write_text(
                'targets: ["host.docker.internal:8080"]\n', encoding="utf-8")
            with patch.object(MODULE.RUNNER, "port_is_bindable", return_value=True):
                self.assertEqual(18080, MODULE.validate_health_target(
                    "http://127.0.0.1:18080/actuator/health"))
            rendered = MODULE.render_prometheus_config(root, compose, 18080)
            self.assertIn("host.docker.internal:18080", rendered.read_text(encoding="utf-8"))
            self.assertNotIn("host.docker.internal:8080", rendered.read_text(encoding="utf-8"))

    def test_cleanup_scope_excludes_sut_health_port_from_bind_receipt(self):
        """SUT 由 PID 身份关闭；健康端口的 TIME_WAIT 不能冒充仍有监听进程。"""
        source = Path(MODULE.__file__).read_text(encoding="utf-8")
        self.assertIn("shared_before, list(ports.values()), pid", source)
        self.assertNotIn("[*ports.values(), backend_port]", source)

    def test_smoke_and_formal_run_use_distinct_success_verdicts(self):
        """冒烟 PASS 退出零，但正式矩阵必须保持 VALID_PASS 的更高门槛。"""
        self.assertEqual(0, MODULE.qualified_exit_code({"verdict": "PASS"}, True))
        self.assertEqual(1, MODULE.qualified_exit_code({"verdict": "PASS"}, False))
        self.assertEqual(0, MODULE.qualified_exit_code({"verdict": "VALID_PASS"}, False))

    def test_single_scenario_qualification_is_non_formal_and_mutually_exclusive(self):
        """单场故障资格只能返回 PASS，且 CLI 不能与无故障预检叠加。"""
        source = Path(MODULE.__file__).read_text(encoding="utf-8")
        self.assertIn('"qualification": "SINGLE_SCENARIO"', source)
        self.assertIn('or bool(args.scenario_qualification)', source)
        self.assertIn('choices=tuple(RUNNER.SCENARIOS)', source)
        self.assertNotIn('scenario_qualification["verdict"] = "VALID_PASS"', source)

    def test_orchestrator_records_fault_injection_before_recovery_probe(self):
        """单场失败回执必须能区分注入前失败与注入后的恢复失败。"""
        source = Path(MODULE.__file__).read_text(encoding="utf-8")
        worker_kill = source.index("self.sut.kill()", source.index("def execute("))
        worker_mark = source.index("self.injected_scenarios.add(scenario)", worker_kill)
        worker_after_probe = source.index('adapter.invoke(scenario, "afterProbe")', worker_mark)
        self.assertLess(worker_kill, worker_mark)
        self.assertLess(worker_mark, worker_after_probe)

    def test_formal_setup_exception_still_writes_full_failure_closure(self):
        """prepare 后即使 Compose 启动抛错，也必须有十场景、cleanup、verdict 和 checksum。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "evidence"
            plan = Path(directory) / "plan.json"
            jar = Path(directory) / "app.jar"
            compose_file = Path(directory) / "compose.yml"
            for path in (plan, jar, compose_file):
                path.write_text("{}\n", encoding="utf-8")
            ports = [f"{name}={20000 + index}" for index, name in enumerate(sorted(MODULE.PORT_NAMES))]
            args = SimpleNamespace(
                plan=plan, evidence_root=root, compose_file=[compose_file], port=ports,
                health_url="http://127.0.0.1:28080/actuator/health", run_id=RUN_ID,
                git_commit="a" * 40, jar=jar, env_file=None, java="java", sut_argument=[],
                before_probe_preflight=False, checkpoint_preflight=False, smoke_only=False)

            def fake_prepare(target, run_id, _commit, _jar, _compose, _runner, mapped_ports):
                target.mkdir(parents=True)
                (target / "scenarios").mkdir()
                manifest = {"schemaVersion": 1, "runId": run_id,
                            "project": MODULE.RUNNER.project_for(run_id), "ports": mapped_ports,
                            "artifacts": {"jar": "a" * 64, "runner": "a" * 64,
                                          "compose": {"compose.yml": "a" * 64}},
                            "startedAt": "2020-01-01T00:00:00Z"}
                MODULE.RUNNER.write_json(target / "run-manifest.json", manifest)
                MODULE.RUNNER.append_event(target, "PREPARED", runId=run_id,
                                           project=manifest["project"])
                return manifest

            class FailingCompose:
                def __init__(self, *_args, **_kwargs):
                    pass

                def start(self):
                    raise RuntimeError("compose start failed")

            def fake_render(target, _compose, _port):
                rendered = target / "prometheus-c4b.yml"
                rendered.write_text("global: {}\n", encoding="utf-8")
                return rendered

            def fake_cleanup(target, manifest, *_args, **_kwargs):
                return MODULE.RUNNER.write_cleanup_failure(
                    target, manifest, RuntimeError("cleanup unavailable"))

            with patch.object(MODULE, "load_plan", return_value=complete_plan()), \
                    patch.object(MODULE, "validate_health_target", return_value=28080), \
                    patch.object(MODULE, "render_prometheus_config", side_effect=fake_render), \
                    patch.object(MODULE.RUNNER, "prepare", side_effect=fake_prepare), \
                    patch.object(MODULE.RUNNER, "ComposeController", FailingCompose), \
                    patch.object(MODULE.RUNNER, "snapshot_shared_resources", return_value={}), \
                    patch.object(MODULE.RUNNER, "cleanup", side_effect=fake_cleanup):
                result = MODULE.execute_full(args)

            self.assertEqual("FAIL", result["verdict"])
            self.assertEqual("FAIL", json.loads(
                (root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))["verdict"])
            self.assertEqual("NOT_RUN", json.loads(
                (root / "scenarios" / "OB-01.json").read_text(encoding="utf-8"))["verdict"])
            for name in ("cleanup.json", "verdict.json", "sha256sums.txt"):
                self.assertTrue((root / name).is_file(), name)
            MODULE.RUNNER.verify_checksum_closure(root)

    def test_worker_scenario_kills_exact_sut_and_restarts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sut = FakeSut()
            compose = FakeCompose()
            adapter = self._adapter_for("OB-04", {
                "targetKafkaRecords": 2, "targetKafkaRecordsBefore": 1,
                "targetStatusBefore": "PUBLISHED", "businessFactCount": 1,
                "targetAndFollowerPublished": True, "laneOrderPreserved": True,
                "noDanglingLease": True, "targetLeaseClaimCount": 1,
                "targetPublishedAtUnchanged": True, "targetReclaimed": False}, root)
            readiness = FakeReadiness(root)
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, adapter, compose, sut, "java", {}, "http://health",
                readiness=readiness)
            with patch.object(MODULE, "wait_health"), patch.object(
                    MODULE.RUNNER, "wait_checkpoint", return_value={}):
                receipt = orchestrator.execute("OB-04", MODULE.RUNNER.SCENARIOS["OB-04"])
            self.assertEqual("PASS", receipt["verdict"])
            self.assertEqual(3, len(sut.started))
            expected_control = str((root / "control" / "OB-04").resolve())
            self.assertEqual("--things-link.fault-injection.enabled=false", sut.started[0][-1])
            for arguments in sut.started[1:]:
                self.assertIn(
                    "--things-link.fault-injection.checkpoint="
                    "OUTBOX_AFTER_MARK_PUBLISHED_COMMIT", arguments)
                self.assertIn(
                    f"--things-link.fault-injection.control-dir={expected_control}", arguments)
                self.assertFalse(any("control-root" in item for item in arguments))
            self.assertEqual(3, sut.kills)
            self.assertEqual([], compose.actions)
            self.assertEqual([("OB-04", "fixture"), ("OB-04", "settled")], readiness.calls)
            self.assertEqual(set(MODULE.PHASES), set(receipt["phaseEvidence"]))

    def test_bootstrap_migrates_with_fault_injection_disabled(self):
        """fixture 前启动只允许禁用屏障，不能携带残留场景参数。"""
        with tempfile.TemporaryDirectory() as directory:
            sut = FakeSut()
            readiness = FakeReadiness(Path(directory))
            orchestrator = MODULE.QualificationOrchestrator(
                Path(directory), RUN_ID, self._adapter_for("OB-01", {}),
                FakeCompose(), sut, "java", {}, "http://health", ["--server.port=8080"],
                readiness)
            with patch.object(MODULE, "wait_health"):
                orchestrator.bootstrap()
            self.assertEqual(1, len(sut.started))
            self.assertEqual(["--server.port=8080",
                              "--things-link.fault-injection.enabled=false"], sut.started[0])
            self.assertEqual(1, sut.kills)
            self.assertEqual([("BOOTSTRAP", "before-kill")], readiness.calls)

    def test_database_scenario_stops_database_and_releases_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "events.jsonl").touch()
            sut = FakeSut()
            compose = FakeCompose()
            adapter = self._adapter_for("DB-01", {"sameDatabaseVolume": True,
                "databaseVolumeBefore": "pg-volume", "databaseVolumeAfter": "pg-volume",
                "databaseUnavailableObserved": True, "databaseUnavailableAlertFiring": True,
                "databaseUnavailableAlertResolved": True, "targetKafkaRecords": 1,
                "businessFactCount": 1, "targetAndFollowerPublished": True,
                "laneOrderPreserved": True, "noDanglingLease": True}, root)
            readiness = FakeReadiness(root)
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, adapter, compose, sut, "java", {}, "http://health",
                readiness=readiness)
            with patch.object(MODULE, "wait_health"), patch.object(
                    MODULE.RUNNER, "wait_checkpoint", return_value={}):
                receipt = orchestrator.execute("DB-01", MODULE.RUNNER.SCENARIOS["DB-01"])
            self.assertEqual(["stop", "start"], compose.actions)
            self.assertEqual(["DB-01"], sut.releases)
            self.assertNotIn("exactPidKilled", receipt["facts"])
            self.assertTrue(receipt["facts"]["databaseStopped"])

    def test_checkpoint_preflight_stops_before_fault_injection(self):
        """F5 真实资格只能到达 DB-01 checkpoint，不能停止数据库或生成场景 PASS。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sut = FakeSut()
            compose = FakeCompose()
            adapter = self._adapter_for("DB-01", {}, root)
            readiness = FakeReadiness(root)
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, adapter, compose, sut, "java", {}, "http://health",
                readiness=readiness)
            checkpoint = {"scenario": "DB-01", "checkpoint": "OUTBOX_BEFORE_PUBLISH"}
            with patch.object(MODULE, "wait_health"), patch.object(
                    MODULE.RUNNER, "wait_checkpoint", return_value=checkpoint):
                receipt = orchestrator.preflight_checkpoint()

            self.assertEqual("PASS", receipt["verdict"])
            self.assertEqual("CHECKPOINT_PREFLIGHT", receipt["qualification"])
            self.assertFalse(receipt["faultInjected"])
            self.assertTrue(receipt["checkpointReached"])
            self.assertEqual([], compose.actions)
            self.assertEqual([], sut.releases)
            self.assertEqual(2, sut.kills)
            self.assertEqual([("DB-01", "fixture")], readiness.calls)
            self.assertEqual(
                {"fixture", "workloadBeforeCheckpoint"}, set(receipt["phaseEvidence"]))
            self.assertNotIn("facts", receipt)
            self.assertTrue((root / "checkpoint-preflight.json").is_file())

    def test_before_probe_preflight_executes_probe_but_not_fault_injection(self):
        """F6 资格必须穿过 beforeProbe，但仍不得停止数据库或生成正式场景 PASS。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sut = FakeSut()
            compose = FakeCompose()
            adapter = self._adapter_for("DB-01", {}, root)
            readiness = FakeReadiness(root)
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, adapter, compose, sut, "java", {}, "http://health",
                readiness=readiness)
            checkpoint = {"scenario": "DB-01", "checkpoint": "OUTBOX_BEFORE_PUBLISH"}
            with patch.object(MODULE, "wait_health"), patch.object(
                    MODULE.RUNNER, "wait_checkpoint", return_value=checkpoint):
                receipt = orchestrator.preflight_checkpoint(include_before_probe=True)

            self.assertEqual("PASS", receipt["verdict"])
            self.assertEqual("BEFORE_PROBE_PREFLIGHT", receipt["qualification"])
            self.assertFalse(receipt["faultInjected"])
            self.assertEqual({"fixtureId": "DB-01"}, receipt["beforeProbe"])
            self.assertEqual([], compose.actions)
            self.assertEqual([], sut.releases)
            self.assertEqual(2, sut.kills)
            self.assertEqual(
                {"fixture", "workloadBeforeCheckpoint", "beforeProbe"},
                set(receipt["phaseEvidence"]))
            self.assertNotIn("facts", receipt)
            self.assertTrue((root / "before-probe-preflight.json").is_file())

    def test_failed_after_probe_alert_is_not_written_to_success_snapshot(self):
        """firing 可进入告警证据，但失败 afterProbe 不能凭部分事实占据 database-after。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            firing = {"queriedAt": "2026-08-29T00:00:00Z",
                      "result": [{"metric": {"alertstate": "firing"}}]}
            state = root / "driver" / "DB-01.json"
            state.parent.mkdir(parents=True)
            state.write_text(json.dumps({"alertFiring": firing}), encoding="utf-8")
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, self._adapter_for("DB-01", {}, root),
                FakeCompose(), FakeSut(), "java", {}, "http://health")

            orchestrator.failure_context("DB-01", RuntimeError("after probe failed"))

            self.assertEqual({}, orchestrator.after)
            self.assertEqual((firing, None), orchestrator.database_alert_queries())

    def test_failed_ob01_after_probe_keeps_lane_evidence_reference(self):
        """OB-01 最终断言失败时仍须把精确 lane 文件纳入失败 receipt。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lane = root / "attribution" / "OB-01" / "outbox-lane.json"
            lane.parent.mkdir(parents=True)
            lane.write_text("{}\n", encoding="utf-8")
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, self._adapter_for("OB-01", {}, root),
                FakeCompose(), FakeSut(), "java", {}, "http://health")

            context = orchestrator.failure_context("OB-01", RuntimeError("order failed"))

            self.assertEqual(MODULE.RUNNER.evidence_reference(root, lane),
                             context["outboxLaneEvidence"])

    def test_failed_ob02_after_probe_keeps_lease_takeover_reference(self):
        """OB-02 最终断言失败时仍须保留原租约与接管租约的摘要证据。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lease = root / "attribution" / "OB-02" / "lease-takeover.json"
            lease.parent.mkdir(parents=True)
            lease.write_text("{}\n", encoding="utf-8")
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, self._adapter_for("OB-02", {}, root),
                FakeCompose(), FakeSut(), "java", {}, "http://health")

            context = orchestrator.failure_context("OB-02", RuntimeError("takeover failed"))

            self.assertEqual(MODULE.RUNNER.evidence_reference(root, lease),
                             context["leaseTakeoverEvidence"])

    def test_failed_ob04_after_probe_keeps_terminal_no_reclaim_reference(self):
        """OB-04 最终断言失败时仍须保留 target 唯一领取与终态证据。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "attribution" / "OB-04" / "terminal-no-reclaim.json"
            evidence.parent.mkdir(parents=True)
            evidence.write_text("{}\n", encoding="utf-8")
            orchestrator = MODULE.QualificationOrchestrator(
                root, RUN_ID, self._adapter_for("OB-04", {}, root),
                FakeCompose(), FakeSut(), "java", {}, "http://health")

            context = orchestrator.failure_context("OB-04", RuntimeError("reclaim failed"))

            self.assertEqual(MODULE.RUNNER.evidence_reference(root, evidence),
                             context["terminalNoReclaimEvidence"])

    def test_successful_after_probe_alert_remains_in_success_snapshot(self):
        """成功路径仍从完整 after 事实输出 firing/resolved，不能被失败诊断字段覆盖。"""
        with tempfile.TemporaryDirectory() as directory:
            orchestrator = MODULE.QualificationOrchestrator(
                Path(directory), RUN_ID, self._adapter_for("DB-01", {}),
                FakeCompose(), FakeSut(), "java", {}, "http://health")
            firing = {"queriedAt": "2026-08-29T00:00:00Z", "result": [{}]}
            resolved = {"queriedAt": "2026-08-29T00:01:00Z", "result": []}
            orchestrator.after["DB-01"] = {
                "alertFiring": firing, "alertResolved": resolved,
                "finalAssertionsPassed": True}
            orchestrator.failed_after_probe_alert_firing = {
                "queriedAt": "2026-08-29T00:02:00Z", "result": []}

            self.assertEqual((firing, resolved), orchestrator.database_alert_queries())

    def test_consumer_readiness_requires_stable_zero_lag_and_all_partitions(self):
        """健康端点不能替代真实 group assignment；少分区或非零 lag 均不通过。"""
        rows = "\n".join(
            f"tc.device.uplink.normalized {partition} 0 0 0 member-{partition % 4} client host"
            for partition in range(12))
        output = ("STATE Stable\nBALANCER range\nMEMBERS 4\nTOTAL-LAG 0\n\n"
                  "TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG MEMBER-ID CLIENT-ID HOST\n"
                  + rows)
        parsed = MODULE.KafkaConsumerReadiness.parse_group_description(
            output, "tc.device.uplink.normalized", 12)
        self.assertTrue(parsed["ready"])
        self.assertEqual("range", parsed["assignor"])
        self.assertEqual(
            [{"partition": partition, "memberId": f"member-{partition % 4}"}
             for partition in range(12)],
            parsed["partitionAssignments"])
        self.assertFalse(MODULE.KafkaConsumerReadiness.parse_group_description(
            output.replace("TOTAL-LAG 0", "TOTAL-LAG 1"),
            "tc.device.uplink.normalized", 12)["ready"])
        self.assertFalse(MODULE.KafkaConsumerReadiness.parse_group_description(
            output.rsplit("\n", 1)[0], "tc.device.uplink.normalized", 12)["ready"])

    def test_consumer_readiness_accepts_assigned_empty_partition_only_at_zero_high_watermark(self):
        """兼容真实 rpk 的空分区“-”，但高水位非零时仍封闭失败。"""
        rows = []
        for partition in range(12):
            current, high, lag = ("-", "0", "-") if partition == 1 else ("0", "0", "0")
            rows.append(f"tc.device.uplink.normalized {partition} {current} 0 {high} {lag} "
                        f"member-{partition % 4} client host")
        output = ("STATE Stable\nMEMBERS 4\nTOTAL-LAG 0\n"
                  "TOPIC PARTITION CURRENT-OFFSET LOG-START-OFFSET LOG-END-OFFSET LAG "
                  "MEMBER-ID CLIENT-ID HOST\n" + "\n".join(rows))
        self.assertTrue(MODULE.KafkaConsumerReadiness.parse_group_description(
            output, "tc.device.uplink.normalized", 12)["ready"])
        self.assertFalse(MODULE.KafkaConsumerReadiness.parse_group_description(
            output.replace("1 - 0 0 -", "1 - 0 1 -"),
            "tc.device.uplink.normalized", 12)["ready"])

    def test_consumer_readiness_retries_until_all_production_groups_are_assigned(self):
        """屏障允许真实重平衡收敛，但不能用固定睡眠直接放行。"""
        with tempfile.TemporaryDirectory() as directory:
            calls = []

            def fake_run(command, **_kwargs):
                group = command[6]
                calls.append(group)
                attempt = (len(calls) - 1) // len(MODULE.CONSUMER_REQUIREMENTS)
                topic, partitions = MODULE.CONSUMER_REQUIREMENTS[group]
                lag = 1 if attempt == 0 else 0
                rows = "\n".join(
                    f"{topic} {partition} 0 0 {lag if partition == 0 else 0} member client host"
                    for partition in range(partitions))
                output = (f"STATE Stable\nMEMBERS 1\nTOTAL-LAG {lag}\n"
                          "TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG MEMBER-ID CLIENT-ID HOST\n"
                          + rows)
                return subprocess.CompletedProcess(command, 0, output, "")

            probe = MODULE.KafkaConsumerReadiness(
                Path(directory), "c4b-redpanda", fake_run, timeout=1, sleep=lambda _value: None)
            result = probe.wait("DB-01")
            self.assertEqual("PASS", result["verdict"])
            self.assertEqual(2 * len(MODULE.CONSUMER_REQUIREMENTS), len(calls))
            self.assertTrue((Path(directory) / result["evidence"]["path"]).is_file())

    def test_consumer_readiness_rejects_previous_sut_member_generation(self):
        """旧进程仍显示 Stable 时不得假绿，必须等新 member identity 接管全部分区。"""
        with tempfile.TemporaryDirectory() as directory:
            generation = {"value": "old"}

            def fake_run(command, **_kwargs):
                topic, partitions = MODULE.CONSUMER_REQUIREMENTS[command[6]]
                rows = "\n".join(
                    f"{topic} {partition} 0 0 0 {generation['value']}-{partition % 4} client host"
                    for partition in range(partitions))
                output = ("STATE Stable\nMEMBERS 4\nTOTAL-LAG 0\n"
                          "TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG MEMBER-ID CLIENT-ID HOST\n"
                          + rows)
                return subprocess.CompletedProcess(command, 0, output, "")

            probe = MODULE.KafkaConsumerReadiness(
                Path(directory), "c4b-redpanda", fake_run, timeout=0.01, sleep=lambda _value: None)
            probe.wait("BOOTSTRAP", "before-kill", require_member_turnover=False)
            with self.assertRaises(MODULE.RUNNER.C4bError):
                probe.wait("DB-01")
            generation["value"] = "new"
            self.assertEqual("PASS", probe.wait("DB-01")["verdict"])

    def test_consumer_readiness_uses_container_internal_broker(self):
        """docker exec 内不得复用指向宿主发布端口的 rpk 配置。"""
        commands = []

        def fake_run(command, **_kwargs):
            commands.append(command)
            topic, partitions = MODULE.CONSUMER_REQUIREMENTS[command[6]]
            rows = "\n".join(
                f"{topic} {partition} 0 0 0 member-{partition % 4} client host"
                for partition in range(partitions))
            output = ("STATE Stable\nMEMBERS 4\nTOTAL-LAG 0\n"
                      "TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG MEMBER-ID CLIENT-ID HOST\n"
                      + rows)
            return subprocess.CompletedProcess(command, 0, output, "")

        with tempfile.TemporaryDirectory() as directory:
            probe = MODULE.KafkaConsumerReadiness(Path(directory), "isolated-redpanda", fake_run,
                                                   timeout=1, sleep=lambda _value: None)
            probe.wait("BOOTSTRAP", "before-kill", require_member_turnover=False)

        self.assertEqual(len(MODULE.CONSUMER_REQUIREMENTS), len(commands))
        for command in commands:
            self.assertEqual(["-X", "brokers=localhost:9092"], command[-2:])

    @staticmethod
    def _adapter_for(scenario, final_facts, evidence_root=None):
        plan = complete_plan()

        def fake_run(command, **_kwargs):
            phase = command[1]
            if phase == "after":
                value = {**final_facts, "finalAssertionsPassed": True}
                if scenario == "DB-01" and evidence_root is not None:
                    now = "2026-08-28T00:00:00Z"
                    references = {}
                    recovery_observed = {
                        "outbox-terminal": 2, "notification-delivery": 1,
                        "inbox-persisted": 1,
                        "outbox-integrity": {
                            "targetAndFollowerPublished": True, "laneOrderPreserved": True,
                            "noDanglingLease": True, "businessFactCount": 1,
                            "targetKafkaRecords": 1, "finalAssertionsPassed": True,
                            "targetState": {"id": "target-1", "status": "PUBLISHED", "attemptCount": 1,
                                            "createdAt": "2026-08-29T00:00:00Z",
                                            "publishedAt": "2026-08-29T00:01:00Z", "leasePresent": False},
                            "followerState": {"id": "follower-1", "status": "PUBLISHED", "attemptCount": 0,
                                              "createdAt": "2026-08-29T00:00:01Z",
                                              "publishedAt": "2026-08-29T00:01:01Z", "leasePresent": False}},
                        "alert-resolved": {"queriedAt": now, "result": []},
                        "database-volume": {"before": "pg-volume", "after": "pg-volume",
                                            "same": True, "nonEmpty": True},
                    }
                    for step, (budget_group, budget_seconds) in \
                            MODULE.RUNNER.DATABASE_RECOVERY_EVIDENCE.items():
                        path = Path(evidence_root) / "recovery" / scenario / f"{step}.json"
                        MODULE.RUNNER.write_json(path, {
                            "schemaVersion": 1, "scenario": scenario, "step": step,
                            "budgetGroup": budget_group,
                            "groupBudgetSeconds": budget_seconds,
                            "remainingBudgetMillisAtStart": budget_seconds * 1000,
                            "startedAt": now, "completedAt": now, "durationMillis": 0,
                            "attempts": 1, "expected": True,
                            "lastObserved": recovery_observed[step],
                            "verdict": "PASS"})
                        references[step] = MODULE.RUNNER.evidence_reference(
                            Path(evidence_root), path)
                    value["recoveryEvidence"] = references
                    attribution = Path(evidence_root) / "attribution" / scenario / \
                        "notification-chain.json"
                    target_id = "target-1"
                    follower_id = "follower-1"
                    outbox_identity = Path(evidence_root) / "identity" / scenario / \
                        "notification-outbox.json"
                    MODULE.RUNNER.write_json(outbox_identity, {
                        "schemaVersion": 1, "scenario": scenario,
                        "topic": "tc.rule.notification",
                        "identities": {
                            label: {"outboxId": outbox_id, "deliveryEventId": target_id,
                                    "partitionKeySha256": hashlib.sha256(
                                        target_id.encode()).hexdigest(),
                                    "payloadEventIdSha256": hashlib.sha256(
                                        target_id.encode()).hexdigest(),
                                    "identityMatch": True}
                            for label, outbox_id in (("target", target_id),
                                                    ("follower", follower_id))},
                        "capturedAt": now})
                    MODULE.RUNNER.write_json(attribution, {
                        "schemaVersion": 3, "scenario": scenario,
                        "topic": "tc.rule.notification",
                        "group": "things-link-rule-notification", "targetId": target_id,
                        "followerId": follower_id,
                        "outboxIdentityEvidence": MODULE.RUNNER.evidence_reference(
                            Path(evidence_root), outbox_identity),
                        "sourceIdentity": {"eventId": target_id, "records": [{
                                "partition": 0, "offset": offset,
                                "keySha256": hashlib.sha256(target_id.encode()).hexdigest(),
                                "payloadEventIdSha256": hashlib.sha256(
                                    target_id.encode()).hexdigest(),
                                "keyMatchesPayloadEventId": True,
                            } for offset in (0, 1)]},
                        "groupSnapshot": {
                            "group": "things-link-rule-notification",
                            "topic": "tc.rule.notification", "state": "Stable",
                            "members": 1, "totalLag": 0,
                            "partitions": [{"partition": partition, "logStartOffset": 0,
                                            "logEndOffset": 2 if partition == 0 else 0,
                                            "currentOffset": 2 if partition == 0 else 0,
                                            "lag": 0, "memberId": "member-1"}
                                           for partition in range(6)]},
                        "metricsBefore": notification_metric_sample(now, None, None),
                        "metricsAfter": notification_metric_sample(now, 1, None),
                        "metricDelta": {"success": 1, "failure": 0},
                        "deliveryFactCount": 1,
                        "dlq": {topic: {"beforeTotal": 0, "afterTotal": 0,
                                         "totalDelta": 0, "targetRecords": []}
                                for topic in ("tc.dlq", "tc.rule.dlq")},
                        "targetOffsetsCommitted": True, "offsetOutcome": "DURABLE_FACT",
                        "probeErrors": {}, "complete": True, "capturedAt": now})
                    uplink_attribution = Path(evidence_root) / "attribution" / scenario / \
                        "normalized-chain.json"
                    MODULE.RUNNER.write_json(uplink_attribution, {
                        "schemaVersion": 2, "scenario": scenario,
                        "topic": "tc.device.uplink.normalized",
                        "group": "things-link-ingestion-normalized",
                        "targetMessageId": "message-1",
                        "sourceLocations": [{"partition": 3, "offset": 7}],
                        "groupSnapshot": {
                            "group": "things-link-ingestion-normalized",
                            "topic": "tc.device.uplink.normalized", "state": "Stable",
                            "members": 4, "totalLag": 0,
                            "partitions": [{"partition": partition, "logStartOffset": 0,
                                            "logEndOffset": 8 if partition == 3 else 0,
                                            "currentOffset": 8 if partition == 3 else 0,
                                            "lag": 0, "memberId": "member-1"}
                                           for partition in range(12)]},
                        "metricsBefore": notification_metric_sample(
                            now, None, None, group="ingestion-normalized",
                            topic="tc.device.uplink.normalized"),
                        "metricsAfter": notification_metric_sample(
                            now, 1, 1, group="ingestion-normalized",
                            topic="tc.device.uplink.normalized"),
                        "metricDelta": {"success": 1, "failure": 1},
                        "inboxFactCount": 1,
                        "dlq": {"topic": "tc.dlq", "beforeTotal": 0, "afterTotal": 0,
                                 "totalDelta": 0, "targetRecords": []},
                        "targetOffsetsCommitted": True, "offsetOutcome": "DURABLE_FACT",
                        "probeErrors": {}, "complete": True, "capturedAt": now})
                if scenario == "OB-04" and evidence_root is not None:
                    target_id = "target-ob-04"
                    terminal = Path(evidence_root) / "attribution" / scenario / \
                        "terminal-no-reclaim.json"
                    MODULE.RUNNER.write_json(terminal, {
                        "schemaVersion": 1, "scenario": scenario, "targetId": target_id,
                        "before": {"status": "PUBLISHED",
                                   "publishedAt": "2026-08-28T00:00:01Z",
                                   "kafkaRecords": 1},
                        "claims": [{"leaseTokenSha256": hashlib.sha256(
                            b"lease-only").hexdigest(),
                                    "observedAt": "2026-08-28T00:00:00Z"}],
                        "targetState": {"id": target_id, "status": "PUBLISHED",
                                        "attemptCount": 0,
                                        "publishedAt": "2026-08-28T00:00:01Z",
                                        "leasePresent": False},
                        "publishedAtUnchanged": True, "targetReclaimed": False,
                        "capturedAt": "2026-08-28T00:00:02Z"})
            else:
                value = {"fixtureId": scenario}
            return subprocess.CompletedProcess(command, 0, json.dumps(value), "")

        return MODULE.CommandAdapter(plan, {}, fake_run, evidence_root)


if __name__ == "__main__":
    unittest.main()
