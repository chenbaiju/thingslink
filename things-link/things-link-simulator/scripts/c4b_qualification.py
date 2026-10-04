#!/usr/bin/env python3
"""G1-C4b 十场景资格编排器：把外部 fixture/工作负载/探针接入统一故障生命周期。"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.parse
from pathlib import Path
from typing import Any, Callable


RUNNER_PATH = Path(__file__).with_name("c4b_matrix_runner.py")
DRIVER_PATH = Path(__file__).with_name("c4b_scenario_driver.py")
SPEC = importlib.util.spec_from_file_location("c4b_matrix_runner", RUNNER_PATH)
RUNNER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(RUNNER)

PHASES = ("fixture", "workloadBeforeCheckpoint", "beforeProbe",
          "workloadAfterInjection", "afterProbe")
PHASE_TIMEOUT_KEYS = {
    "default", "DB-01/afterProbe",
    "RW-01/afterProbe", "RW-02/afterProbe", "RW-03/afterProbe", "RW-04/afterProbe",
    "RW-05/afterProbe",
}
PORT_NAMES = {"POSTGRES", "REDIS", "REDPANDA", "REDPANDA_ADMIN", "EMQX_MQTT",
              "EMQX_TLS", "EMQX_DASHBOARD", "MINIO", "MINIO_CONSOLE", "PROMETHEUS", "GRAFANA"}
CONSUMER_REQUIREMENTS = {
    "things-link-ingestion-normalized": ("tc.device.uplink.normalized", 12),
    "things-link-ingestion-processed": ("tc.device.uplink.processed", 12),
    # DB-01 的目标 Outbox 会进入规则通知入口；只证明 ingestion 两组就绪会把
    # 通知组尚未 assignment 的工具前置问题误判成数据库恢复失败。
    "things-link-rule-notification": ("tc.rule.notification", 6),
}


def load_plan(path: Path) -> dict[str, Any]:
    """读取并严格验证十场景命令计划，缺一阶段都不得开始正式运行。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise RUNNER.C4bError("资格计划不是有效 JSON") from exception
    if value.get("schemaVersion") != 2 or set(value.get("scenarios", {})) != set(RUNNER.SCENARIOS):
        raise RUNNER.C4bError("资格计划必须完整声明十个冻结场景")
    timeouts = value.get("phaseTimeoutSeconds")
    if (not isinstance(timeouts, dict) or set(timeouts) != PHASE_TIMEOUT_KEYS
            or any(not isinstance(seconds, int) or isinstance(seconds, bool) or seconds <= 0
                   for seconds in timeouts.values())
            or timeouts.get("default") != 180
            or timeouts.get("DB-01/afterProbe") != 390
            or any(timeouts.get(f"{scenario}/afterProbe") != 300
                   for scenario in ("RW-01", "RW-02", "RW-03", "RW-04", "RW-05"))):
        # C4b-0 的两个 180 秒内部预算不能改变；390 秒只增加 30 秒命令与落盘余量，
        # 避免外层先行终止而丢失首个未收敛谓词。
        raise RUNNER.C4bError(
            "资格计划 phase 预算必须为 default=180、DB-01/afterProbe=390、"
            "RW-01～05/afterProbe=300")
    for scenario, phases in value["scenarios"].items():
        if set(phases) != set(PHASES):
            raise RUNNER.C4bError(f"{scenario} 必须完整声明五个执行阶段")
        for phase, command in phases.items():
            if not isinstance(command, list) or any(not isinstance(part, str) for part in command):
                raise RUNNER.C4bError(f"{scenario}/{phase} 必须是 argv 数组")
            if phase in ("fixture", "beforeProbe", "afterProbe") and not command:
                raise RUNNER.C4bError(f"{scenario}/{phase} 不允许为空")
    # F21 与 F27 都要求直读本轮 SUT，而非有抓取延迟的 Prometheus。计划若漏掉依赖，
    # 必须在启动十场景前拒绝，不能到 DB-01 或第九场才以驱动参数错误消耗正式 attempt。
    for scenario, phase in (("DB-01", "beforeProbe"), ("DB-01", "afterProbe"),
                            ("RW-04", "afterProbe")):
        command = value["scenarios"][scenario][phase]
        pairs = list(zip(command, command[1:]))
        if ("--sut-metrics-url", "{sutMetricsUrl}") not in pairs:
            raise RUNNER.C4bError(
                f"{scenario}/{phase} 必须显式传递当前 SUT metrics URL")
    return value


class CommandAdapter:
    """以无 shell argv 执行仓库拥有的 fixture、工作负载与 JSON 探针。"""

    def __init__(self, plan: dict[str, Any], variables: dict[str, str],
                 run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
                 evidence_root: Path | None = None) -> None:
        self.plan = plan
        self.variables = variables
        self.run = run
        self.evidence_root = evidence_root.resolve() if evidence_root is not None else None
        self._references: dict[str, dict[str, dict[str, str]]] = {}

    def command(self, scenario: str, phase: str) -> list[str]:
        """展开冻结变量；未知占位符直接失败，防止调用错误环境。"""
        command = self.plan["scenarios"][scenario][phase]
        try:
            return [part.format_map(self.variables | {"scenario": scenario}) for part in command]
        except KeyError as exception:
            raise RUNNER.C4bError(f"命令含未知占位符: {exception.args[0]}") from exception

    def timeout_seconds(self, scenario: str, phase: str) -> int:
        """读取已冻结的阶段预算；DB/RW 恢复阶段必须覆盖内部时限及归因落盘余量。"""
        return int(self.plan["phaseTimeoutSeconds"].get(
            f"{scenario}/{phase}", self.plan["phaseTimeoutSeconds"]["default"]))

    def invoke(self, scenario: str, phase: str) -> dict[str, Any]:
        """运行一个阶段；fixture/探针必须返回 JSON，工作负载只要求零退出码。"""
        command = self.command(scenario, phase)
        started_at = RUNNER.utc_now()
        started = time.monotonic()
        diagnostic: dict[str, Any] = {
            "schemaVersion": 1, "scenario": scenario, "phase": phase,
            "startedAt": started_at, "argvSummary": self._argv_summary(command),
            "timeoutSeconds": self.timeout_seconds(scenario, phase),
        }
        if not command:
            diagnostic.update({"status": "SKIPPED_EMPTY", "returnCode": 0,
                               "stdout": RUNNER.bounded_diagnostic(""),
                               "stderr": RUNNER.bounded_diagnostic(""),
                               "completedAt": RUNNER.utc_now(), "durationMillis": 0})
            self._persist_diagnostic(scenario, phase, diagnostic)
            return {}
        completed: subprocess.CompletedProcess[str] | None = None
        try:
            completed = self.run(command, capture_output=True, text=True, encoding="utf-8",
                                 errors="replace", timeout=self.timeout_seconds(scenario, phase),
                                 check=False)
            diagnostic.update({"returnCode": completed.returncode,
                               "stdout": RUNNER.bounded_diagnostic(completed.stdout),
                               "stderr": RUNNER.bounded_diagnostic(completed.stderr)})
            if completed.returncode != 0:
                raise RUNNER.C4bError(f"{scenario}/{phase} 失败 rc={completed.returncode}")
            if phase.startswith("workload"):
                value: dict[str, Any] = {}
            else:
                try:
                    value = json.loads(completed.stdout)
                except json.JSONDecodeError as exception:
                    raise RUNNER.C4bError(f"{scenario}/{phase} 未返回严格 JSON") from exception
                if not isinstance(value, dict):
                    raise RUNNER.C4bError(f"{scenario}/{phase} 必须返回 JSON 对象")
            diagnostic["status"] = "PASS"
            return value
        except BaseException as exception:
            diagnostic["status"] = "FAIL"
            diagnostic["exception"] = RUNNER.exception_diagnostic(exception)
            if completed is None:
                diagnostic.update({"returnCode": None,
                                   "stdout": RUNNER.bounded_diagnostic(""),
                                   "stderr": RUNNER.bounded_diagnostic("")})
            raise
        finally:
            diagnostic.update({"completedAt": RUNNER.utc_now(),
                               "durationMillis": int((time.monotonic() - started) * 1000)})
            self._persist_diagnostic(scenario, phase, diagnostic)

    @staticmethod
    def _argv_summary(command: list[str]) -> dict[str, Any]:
        """只保存可执行文件名、参数个数和摘要，避免路径、稳定 ID 或未来凭据进入 receipt。"""
        encoded = json.dumps(command, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        executable = command[0].replace("\\", "/").rsplit("/", 1)[-1] if command else ""
        return {"executable": executable, "argumentCount": max(0, len(command) - 1),
                "argvSha256": hashlib.sha256(encoded).hexdigest()}

    def _persist_diagnostic(self, scenario: str, phase: str, value: dict[str, Any]) -> None:
        """把每个阶段的成功或失败现场写入独立文件并保存不可漂移引用。"""
        if self.evidence_root is None:
            return
        path = self.evidence_root / "diagnostics" / scenario / f"{phase}.json"
        RUNNER.write_json(path, value)
        self._references.setdefault(scenario, {})[phase] = RUNNER.evidence_reference(
            self.evidence_root, path)

    def evidence_references(self, scenario: str) -> dict[str, dict[str, str]]:
        """返回场景 receipt 可直接引用的阶段证据副本。"""
        return dict(self._references.get(scenario, {}))


class KafkaConsumerReadiness:
    """用真实 group assignment/lag 建立健康端点之后的生产消费就绪屏障。"""

    def __init__(self, root: Path, container: str,
                 run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
                 timeout: float = 120.0, sleep: Callable[[float], None] = time.sleep) -> None:
        self.root = root.resolve()
        self.container = container
        self.run = run
        self.timeout = timeout
        self.sleep = sleep
        self._references: dict[tuple[str, str], dict[str, str]] = {}
        self._previous_members: dict[str, set[str]] = {}

    @staticmethod
    def parse_group_description(output: str, topic: str, partition_count: int) -> dict[str, Any]:
        """解析 rpk 文本表格，要求稳定组、非零成员和目标 topic 全分区均有 member。"""
        metadata: dict[str, str] = {}
        rows: list[dict[str, Any]] = []
        header: dict[str, int] | None = None
        for raw_line in output.splitlines():
            fields = raw_line.split()
            if not fields:
                continue
            if fields[0] in {"STATE", "BALANCER", "MEMBERS", "TOTAL-LAG"} and len(fields) >= 2:
                metadata[fields[0]] = fields[1]
                continue
            if fields[:2] == ["TOPIC", "PARTITION"]:
                header = {name: index for index, name in enumerate(fields)}
                continue
            if header is None or fields[0] != topic or len(fields) <= header.get("MEMBER-ID", 10**6):
                continue
            try:
                partition = int(fields[header["PARTITION"]])
                lag_raw = fields[header["LAG"]]
                if lag_raw == "-":
                    # rpk 对从未写入的已分配分区输出 CURRENT-OFFSET/LAG 为“-”；
                    # 只有 broker 高水位也为 0 才能等价为零 lag，防止吞掉真实积压。
                    if int(fields[header["LOG-END-OFFSET"]]) != 0:
                        continue
                    lag = 0
                else:
                    lag = int(lag_raw)
            except (KeyError, ValueError, IndexError):
                continue
            rows.append({"partition": partition, "lag": lag,
                         "memberId": fields[header["MEMBER-ID"]]})
        assigned_rows = [row for row in rows if row["memberId"] not in ("-", "")]
        assigned = sorted(row["partition"] for row in assigned_rows)
        member_ids = sorted({row["memberId"] for row in assigned_rows})
        try:
            members = int(metadata.get("MEMBERS", "-1"))
            total_lag = int(metadata.get("TOTAL-LAG", "-1"))
        except ValueError:
            members, total_lag = -1, -1
        expected = list(range(partition_count))
        ready = (metadata.get("STATE", "").lower() == "stable" and members > 0
                 and total_lag == 0 and assigned == expected
                 and all(row["lag"] == 0 for row in rows))
        return {"topic": topic, "state": metadata.get("STATE"),
                "assignor": metadata.get("BALANCER"), "members": members,
                "totalLag": total_lag, "assignedPartitions": assigned,
                "expectedPartitions": expected, "memberIds": member_ids,
                "partitionAssignments": [
                    {"partition": row["partition"], "memberId": row["memberId"]}
                    for row in sorted(assigned_rows, key=lambda item: item["partition"])
                ],
                "ready": ready}

    def wait(self, scenario: str, purpose: str = "fixture", *,
             require_member_turnover: bool = True) -> dict[str, Any]:
        """轮询真实 assignment；重启后还必须排除上一 SUT 代际的旧成员身份。"""
        started = time.monotonic()
        attempts = 0
        groups: dict[str, Any] = {}
        deadline = started + self.timeout
        while time.monotonic() < deadline:
            attempts += 1
            groups = {}
            all_ready = True
            for group, (topic, partitions) in CONSUMER_REQUIREMENTS.items():
                # rpk 容器配置声明的是宿主发布端口；docker exec 后必须显式使用容器内监听地址，
                # 否则它会在容器里回连 host localhost 并把真实已分配消费组误判为不可达。
                command = ["docker", "exec", self.container, "rpk", "group", "describe", group,
                           "-X", "brokers=localhost:9092"]
                completed = self.run(command, capture_output=True, text=True, encoding="utf-8",
                                     errors="replace", timeout=10, check=False)
                parsed = (self.parse_group_description(completed.stdout, topic, partitions)
                          if completed.returncode == 0 else
                          {"topic": topic, "expectedPartitions": list(range(partitions)),
                           "ready": False})
                previous = self._previous_members.get(group, set())
                current = set(parsed.get("memberIds", []))
                turnover = not previous or current.isdisjoint(previous)
                parsed["memberTurnover"] = turnover
                parsed["previousMemberCount"] = len(previous)
                parsed["ready"] = (parsed.get("ready") is True
                                   and (turnover or not require_member_turnover))
                groups[group] = {"returnCode": completed.returncode, "parsed": parsed,
                                 "stdout": RUNNER.bounded_diagnostic(completed.stdout, 8192),
                                 "stderr": RUNNER.bounded_diagnostic(completed.stderr, 2048)}
                all_ready = all_ready and parsed.get("ready") is True
            if all_ready:
                result = self._persist(scenario, purpose, "PASS", attempts, started, groups)
                for group, item in groups.items():
                    self._previous_members[group] = set(item["parsed"].get("memberIds", []))
                return result
            self.sleep(0.5)
        result = self._persist(scenario, purpose, "FAIL", attempts, started, groups)
        raise RUNNER.C4bError(
            f"{scenario} Kafka 生产消费组未在 {self.timeout:g} 秒内取得全部分区且 lag 归零; "
            f"evidence={result['evidence']['path']}")

    def _persist(self, scenario: str, purpose: str, verdict: str, attempts: int, started: float,
                 groups: dict[str, Any]) -> dict[str, Any]:
        path = self.root / "readiness" / f"{scenario}-{purpose}.json"
        value = {"schemaVersion": 1, "scenario": scenario, "purpose": purpose, "verdict": verdict,
                 "attempts": attempts, "durationMillis": int((time.monotonic() - started) * 1000),
                 "groups": groups, "completedAt": RUNNER.utc_now()}
        RUNNER.write_json(path, value)
        reference = RUNNER.evidence_reference(self.root, path)
        self._references[(scenario, purpose)] = reference
        return {"verdict": verdict, "groups": {group: item["parsed"] for group, item in groups.items()},
                "evidence": reference}

    def evidence_reference(self, scenario: str, purpose: str = "fixture") -> dict[str, str] | None:
        """返回当前场景最后一次就绪证据引用。"""
        return self._references.get((scenario, purpose))


class QualificationOrchestrator:
    """实施统一场景状态机；业务探针只能提供事实，不能控制注入或自报最终 PASS。"""

    def __init__(self, root: Path, run_id: str, adapter: CommandAdapter,
                 compose: Any, sut: Any, java: str, sut_environment: dict[str, str],
                 health_url: str, sut_arguments: list[str] | None = None,
                 readiness: KafkaConsumerReadiness | None = None) -> None:
        self.root = root
        self.run_id = run_id
        self.adapter = adapter
        self.compose = compose
        self.sut = sut
        self.java = java
        self.sut_environment = sut_environment
        self.health_url = health_url
        self.sut_arguments = sut_arguments or []
        self.readiness = readiness
        self.before: dict[str, Any] = {}
        self.after: dict[str, Any] = {}
        # 失败 afterProbe 可能已经取得 firing 查询，但这只是失败诊断，不是成功数据库快照。
        # 单独保存可让 prometheus-alerts 继续归档真实告警，同时不违反 F13 的 PASS 快照前缀合同。
        self.failed_after_probe_alert_firing: dict[str, Any] | None = None
        self.consumer_readiness: dict[str, dict[str, Any]] = {}
        # 单场资格失败也必须区分“注入前失败”和“已注入后未恢复”，不能凭异常阶段猜测。
        self.injected_scenarios: set[str] = set()

    def _start_sut(self, scenario: str, checkpoint: str) -> int:
        """以当前场景的一次性屏障启动同一构件并等待真实健康端点。"""
        control_directory = (self.root / "control" / scenario).resolve()
        arguments = [*self.sut_arguments,
                     "--things-link.fault-injection.enabled=true",
                     f"--things-link.fault-injection.run-id={self.run_id}",
                     f"--things-link.fault-injection.scenario={scenario}",
                     f"--things-link.fault-injection.checkpoint={checkpoint}",
                     f"--things-link.fault-injection.control-dir={control_directory}"]
        identity = self.sut.start(self.java, arguments, self.sut_environment)
        wait_health(self.health_url)
        return int(identity["pid"])

    def bootstrap(self) -> None:
        """迁移后先记录旧消费成员再强杀，使下一启动必须证明成员代际切换。"""
        arguments = [*self.sut_arguments,
                     "--things-link.fault-injection.enabled=false"]
        self.sut.start(self.java, arguments, self.sut_environment)
        try:
            wait_health(self.health_url)
            if self.readiness is None:
                raise RUNNER.C4bError("缺少 Kafka 生产消费就绪屏障")
            self.readiness.wait("BOOTSTRAP", "before-kill", require_member_turnover=False)
        finally:
            if self.sut.process is not None and self.sut.process.poll() is None:
                self.sut.kill()

    def _prepare_fixture(self, scenario: str) -> dict[str, Any]:
        """健康后先证明生产组全分区就绪，再发布 fixture 并停机预置目标消息。"""
        arguments = [*self.sut_arguments,
                     "--things-link.fault-injection.enabled=false"]
        self.sut.start(self.java, arguments, self.sut_environment)
        try:
            wait_health(self.health_url)
            if self.readiness is None:
                raise RUNNER.C4bError("缺少 Kafka 生产消费就绪屏障")
            readiness = self.readiness.wait(scenario)
            self.consumer_readiness[scenario] = readiness
            fixture = self.adapter.invoke(scenario, "fixture")
            return {**fixture, "consumerReadinessPassed": readiness.get("verdict") == "PASS"}
        finally:
            if self.sut.process is not None and self.sut.process.poll() is None:
                self.sut.kill()

    def execute(self, scenario: str, checkpoint: str) -> dict[str, Any]:
        """执行单场景并返回由机器事实构造、可再次验证的 receipt。"""
        fixture = self._prepare_fixture(scenario)
        # 目标消息必须先进入持久入口再启用屏障；否则领取前 checkpoint 会阻塞空轮询，
        # 编排器也就永远执行不到本阶段，形成工具自身制造的死锁。
        self.adapter.invoke(scenario, "workloadBeforeCheckpoint")
        pid = self._start_sut(scenario, checkpoint)
        try:
            reached = RUNNER.wait_checkpoint(self.root, scenario, pid)
            RUNNER.append_event(self.root, "CHECKPOINT_REACHED", scenario=scenario,
                                checkpoint=checkpoint, pid=pid,
                                checkpointAt=reached.get("at"))
            before = self.adapter.invoke(scenario, "beforeProbe")
            self.before[scenario] = before
            if scenario == "DB-01":
                self.compose.stop_database()
                self.injected_scenarios.add(scenario)
                RUNNER.append_event(self.root, "DATABASE_STOPPED", scenario=scenario)
                self.sut.release(scenario)
                self.adapter.invoke(scenario, "workloadAfterInjection")
                self.compose.start_database()
                RUNNER.append_event(self.root, "DATABASE_RECOVERED", scenario=scenario)
            else:
                self.sut.kill()
                self.injected_scenarios.add(scenario)
                self.adapter.invoke(scenario, "workloadAfterInjection")
                self._start_sut(scenario, checkpoint)
            after = self.adapter.invoke(scenario, "afterProbe")
            self.after[scenario] = after
            assert self.readiness is not None
            settled = self.readiness.wait(scenario, "settled", require_member_turnover=False)
            facts = {**fixture, **before, **after, "checkpointReachedCount": 1,
                     "finalAssertionsPassed": after.get("finalAssertionsPassed") is True}
            if scenario == "DB-01":
                facts.update({"databaseStopped": True, "databaseRecovered": True})
            else:
                facts["exactPidKilled"] = True
            receipt = {"scenario": scenario, "checkpoint": checkpoint, "verdict": "PASS",
                       "facts": facts,
                       "phaseEvidence": self.adapter.evidence_references(scenario),
                       "consumerReadinessEvidence": self.consumer_readiness[scenario]["evidence"],
                       "consumerSettledEvidence": settled["evidence"],
                       "completedAt": RUNNER.utc_now()}
            attribution = self._notification_attribution_reference(scenario)
            if attribution is not None:
                receipt["notificationAttributionEvidence"] = attribution
            uplink_attribution = self._uplink_attribution_reference(scenario)
            if uplink_attribution is not None:
                receipt["uplinkAttributionEvidence"] = uplink_attribution
            outbox_lane = self._outbox_lane_reference(scenario)
            if outbox_lane is not None:
                receipt["outboxLaneEvidence"] = outbox_lane
            lease_takeover = self._lease_takeover_reference(scenario)
            if lease_takeover is not None:
                receipt["leaseTakeoverEvidence"] = lease_takeover
            terminal_no_reclaim = self._terminal_no_reclaim_reference(scenario)
            if terminal_no_reclaim is not None:
                receipt["terminalNoReclaimEvidence"] = terminal_no_reclaim
            rule_chain = self._rule_chain_reference(scenario)
            if rule_chain is not None:
                receipt["ruleChainEvidence"] = rule_chain
            RUNNER.validate_scenario_receipt(receipt, self.root)
            return receipt
        finally:
            # DB-01 进程仍存活；其他场景的重启进程也必须在下一场前退出。
            if self.sut.process is not None and self.sut.process.poll() is None:
                self.sut.kill()

    def preflight_checkpoint(self, scenario: str = "DB-01",
                             include_before_probe: bool = False) -> dict[str, Any]:
        """证明 checkpoint 或 beforeProbe 可达；两种资格均不执行任何故障动作。"""
        checkpoint = RUNNER.SCENARIOS[scenario]
        fixture = self._prepare_fixture(scenario)
        # F5 只需穿过此前失败的 Outbox 预置点并验证消费就绪屏障；继续执行 beforeProbe
        # 或数据库停止会把修复资格误写成正式场景证据，破坏 C4b-2 的十场景原子性。
        self.adapter.invoke(scenario, "workloadBeforeCheckpoint")
        pid = self._start_sut(scenario, checkpoint)
        try:
            checkpoint_evidence = RUNNER.wait_checkpoint(self.root, scenario, pid)
            before_probe = (self.adapter.invoke(scenario, "beforeProbe")
                            if include_before_probe else None)
            qualification = ("BEFORE_PROBE_PREFLIGHT" if include_before_probe
                             else "CHECKPOINT_PREFLIGHT")
            receipt = {
                "schemaVersion": 1,
                "qualification": qualification,
                "scenario": scenario,
                "checkpoint": checkpoint,
                "verdict": "PASS",
                "faultInjected": False,
                "fixture": fixture,
                "checkpointReached": True,
                "checkpointEvidence": checkpoint_evidence,
                "phaseEvidence": self.adapter.evidence_references(scenario),
                "consumerReadinessEvidence": self.consumer_readiness[scenario]["evidence"],
                "completedAt": RUNNER.utc_now(),
            }
            if include_before_probe:
                receipt["beforeProbe"] = before_probe
            filename = ("before-probe-preflight.json" if include_before_probe
                        else "checkpoint-preflight.json")
            RUNNER.write_json(self.root / filename, receipt)
            return receipt
        finally:
            if self.sut.process is not None and self.sut.process.poll() is None:
                self.sut.kill()

    def failure_context(self, scenario: str, _exception: BaseException) -> dict[str, Any]:
        """让失败 receipt 引用已落盘的阶段与消费就绪证据。"""
        context: dict[str, Any] = {"phaseEvidence": self.adapter.evidence_references(scenario)}
        if self.readiness is not None:
            reference = self.readiness.evidence_reference(scenario)
            if reference is not None:
                context["consumerReadinessEvidence"] = reference
        attribution = self._notification_attribution_reference(scenario)
        if attribution is not None:
            context["notificationAttributionEvidence"] = attribution
        uplink_attribution = self._uplink_attribution_reference(scenario)
        if uplink_attribution is not None:
            context["uplinkAttributionEvidence"] = uplink_attribution
        outbox_lane = self._outbox_lane_reference(scenario)
        if outbox_lane is not None:
            context["outboxLaneEvidence"] = outbox_lane
        lease_takeover = self._lease_takeover_reference(scenario)
        if lease_takeover is not None:
            context["leaseTakeoverEvidence"] = lease_takeover
        terminal_no_reclaim = self._terminal_no_reclaim_reference(scenario)
        if terminal_no_reclaim is not None:
            context["terminalNoReclaimEvidence"] = terminal_no_reclaim
        rule_chain = self._rule_chain_reference(scenario)
        if rule_chain is not None:
            context["ruleChainEvidence"] = rule_chain
        if scenario == "DB-01":
            # afterProbe 失败时仍保留已完成的 firing 查询；resolved 只能由恢复阶段真实写入。
            state_path = self.root / "driver" / f"{scenario}.json"
            if state_path.is_file():
                try:
                    state = RUNNER.read_json_object(state_path, "DB-01 driver state")
                    firing = state.get("alertFiring")
                    if isinstance(firing, dict) and isinstance(firing.get("result"), list):
                        self.failed_after_probe_alert_firing = firing
                except RUNNER.C4bError:
                    pass
        return context

    def database_alert_queries(self) -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
        """返回告警专用证据；失败诊断永不进入成功数据库快照集合。"""
        db_after = self.after.get("DB-01", {})
        firing = db_after.get("alertFiring")
        if not isinstance(firing, dict):
            firing = self.failed_after_probe_alert_firing
        resolved = db_after.get("alertResolved")
        return firing if isinstance(firing, dict) else None, \
            resolved if isinstance(resolved, dict) else None

    def _notification_attribution_reference(self, scenario: str) -> dict[str, str] | None:
        """发现驱动已原子落盘的通知链证据；不从 stdout 或日志文本重建事实。"""
        if scenario != "DB-01":
            return None
        path = self.root / "attribution" / scenario / "notification-chain.json"
        return RUNNER.evidence_reference(self.root, path) if path.is_file() else None

    def _uplink_attribution_reference(self, scenario: str) -> dict[str, str] | None:
        """发现 normalized 入口已原子落盘的目标 offset/inbox/DLQ 归因。"""
        if scenario != "DB-01":
            return None
        path = self.root / "attribution" / scenario / "normalized-chain.json"
        return RUNNER.evidence_reference(self.root, path) if path.is_file() else None

    def _outbox_lane_reference(self, scenario: str) -> dict[str, str] | None:
        """发现 OB-01 精确状态与租约转换证据；失败 receipt 同样必须引用。"""
        if scenario != "OB-01":
            return None
        path = self.root / "attribution" / scenario / "outbox-lane.json"
        return RUNNER.evidence_reference(self.root, path) if path.is_file() else None

    def _lease_takeover_reference(self, scenario: str) -> dict[str, str] | None:
        """发现 OB-02 租约接管证据；成功和失败 receipt 都不得只保留自报事实。"""
        if scenario != "OB-02":
            return None
        path = self.root / "attribution" / scenario / "lease-takeover.json"
        return RUNNER.evidence_reference(self.root, path) if path.is_file() else None

    def _terminal_no_reclaim_reference(self, scenario: str) -> dict[str, str] | None:
        """发现 OB-04 终态不重领证据；失败 receipt 也保留直接归因。"""
        if scenario != "OB-04":
            return None
        path = self.root / "attribution" / scenario / "terminal-no-reclaim.json"
        return RUNNER.evidence_reference(self.root, path) if path.is_file() else None

    def _rule_chain_reference(self, scenario: str) -> dict[str, str] | None:
        """发现 RW-01～04 的规则恢复链证据；失败也必须保留最后一次稳定身份快照。"""
        if scenario not in {"RW-01", "RW-02", "RW-03", "RW-04"}:
            return None
        path = self.root / "attribution" / scenario / "rule-chain.json"
        return RUNNER.evidence_reference(self.root, path) if path.is_file() else None


def wait_health(url: str, timeout: float = 120.0,
                sleep: Callable[[float], None] = time.sleep) -> None:
    """有界等待 SUT 健康；只接受 HTTP 200 和 UP，不以端口建立代替应用就绪。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                value = json.loads(response.read())
            if response.status == 200 and value.get("status") == "UP":
                return
        except Exception:
            pass
        sleep(0.25)
    raise RUNNER.C4bError("120 秒内 SUT 未健康")


def validate_health_target(url: str) -> int:
    """只接受本机未占用端口；实际端口会写入本轮隔离 Prometheus 配置。"""
    parsed = urllib.parse.urlparse(url)
    if (parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost")
            or parsed.port is None or parsed.port < 1024 or parsed.port > 65535
            or parsed.path != "/actuator/health"):
        raise RUNNER.C4bError("--health-url 必须为本机 1024..65535 端口的 /actuator/health")
    if not RUNNER.port_is_bindable(parsed.port):
        raise RUNNER.C4bError(f"SUT/Prometheus 端口 {parsed.port} 已被占用")
    return parsed.port


def render_prometheus_config(root: Path, base_compose: Path, backend_port: int) -> Path:
    """从仓库基线生成本轮专属抓取配置，避免改写共享文件或占用开发后端端口。"""
    source = base_compose.resolve().parent / "prometheus" / "prometheus.yml"
    try:
        content = source.read_text(encoding="utf-8")
    except OSError as exception:
        raise RUNNER.C4bError("无法读取 Prometheus 基线配置") from exception
    frozen_target = "host.docker.internal:8080"
    if content.count(frozen_target) != 1:
        raise RUNNER.C4bError("Prometheus 后端抓取目标基线漂移")
    destination = root / "prometheus-c4b.yml"
    destination.write_text(content.replace(
        frozen_target, f"host.docker.internal:{backend_port}"), encoding="utf-8")
    return destination


def load_environment_file(path: Path | None) -> dict[str, str]:
    """只把 SUT 所需凭据载入子进程环境；值不进入 manifest、argv 或错误输出。"""
    if path is None:
        return {}
    try:
        lines = path.resolve().read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise RUNNER.C4bError("无法读取 --env-file") from exception
    values: dict[str, str] = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def execute_full(args: argparse.Namespace) -> dict[str, Any]:
    """建立隔离环境、执行矩阵、清理并生成机器裁决；任一异常仍写失败闭包。"""
    plan = load_plan(args.plan.resolve())
    root = args.evidence_root.resolve()
    compose_files = [item.resolve() for item in args.compose_file]
    ports = {key.upper(): int(value) for key, value in (item.split("=", 1) for item in args.port)}
    if set(ports) != PORT_NAMES:
        raise RUNNER.C4bError("--port 必须完整且仅声明十一项 C4b overlay 端口")
    backend_port = validate_health_target(args.health_url)
    manifest = RUNNER.prepare(root, args.run_id, args.git_commit, args.jar.resolve(),
                              compose_files, RUNNER_PATH, ports)
    environment = dict(os.environ)
    compose: RUNNER.ComposeController | None = None
    sut: RUNNER.SutController | None = None
    orchestrator: QualificationOrchestrator | None = None
    shared_before: dict[str, Any] | None = None
    smoke: dict[str, Any] | None = None
    checkpoint_preflight: dict[str, Any] | None = None
    before_probe_preflight: dict[str, Any] | None = None
    scenario_qualification_name = getattr(args, "scenario_qualification", None)
    scenario_qualification: dict[str, Any] | None = None
    cleanup_receipt: dict[str, Any] | None = None
    lifecycle_error: BaseException | None = None
    lifecycle_stage = "setup"
    try:
        prometheus_config = render_prometheus_config(root, compose_files[0], backend_port)
        # 外置计划决定全部业务 fixture/探针，必须与编排器一起进入不可变构件指纹。
        manifest["artifacts"].update({"qualification": RUNNER.sha256(Path(__file__)),
                                      "scenarioDriver": RUNNER.sha256(DRIVER_PATH),
                                      "plan": RUNNER.sha256(args.plan.resolve()),
                                      "prometheusConfig": RUNNER.sha256(prometheus_config)})
        RUNNER.write_json(root / "run-manifest.json", manifest)
        file_environment = load_environment_file(args.env_file)
        environment.update({"C4B_PROJECT": manifest["project"],
                            "C4B_PROMETHEUS_CONFIG": str(prometheus_config),
                            **{f"C4B_{name}_PORT": str(port) for name, port in ports.items()},
                            "SPRING_DATASOURCE_URL":
                                f"jdbc:postgresql://127.0.0.1:{ports['POSTGRES']}/thingslink",
                            "SPRING_DATASOURCE_USERNAME": "thingslink_app",
                            "SPRING_DATASOURCE_PASSWORD":
                                file_environment.get("APP_ROLE_PASSWORD", "thingslink"),
                            "SPRING_FLYWAY_URL":
                                f"jdbc:postgresql://127.0.0.1:{ports['POSTGRES']}/thingslink",
                            "SPRING_FLYWAY_USER": file_environment.get("POSTGRES_USER", "thingslink"),
                            "SPRING_FLYWAY_PASSWORD": file_environment.get("POSTGRES_PASSWORD", "thingslink"),
                            "SPRING_DATA_REDIS_HOST": "127.0.0.1",
                            "SPRING_DATA_REDIS_PORT": str(ports["REDIS"]),
                            "REDIS_PASSWORD": file_environment.get("REDIS_PASSWORD", "thingslink"),
                            "SPRING_KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{ports['REDPANDA']}"})
        variables = {"root": str(root), "runId": args.run_id,
                     "project": manifest["project"], "python": sys.executable,
                     "driver": str(DRIVER_PATH.resolve()),
                     "sutMetricsUrl": f"http://127.0.0.1:{backend_port}/actuator/prometheus",
                     **{f"{name}_PORT": str(port) for name, port in ports.items()}}
        adapter = CommandAdapter(plan, variables, evidence_root=root)
        compose = RUNNER.ComposeController(manifest["project"], compose_files, environment,
                                           env_file=args.env_file)
        sut = RUNNER.SutController(args.jar, root)
        sut_arguments = [f"--server.port={backend_port}",
                         "--things-link.rule.sandbox.pool-size=1",
                         "--things-link.rule.sandbox.queue-capacity=1",
                         *args.sut_argument]
        orchestrator = QualificationOrchestrator(
            root, args.run_id, adapter, compose, sut, args.java, environment,
            args.health_url, sut_arguments,
            KafkaConsumerReadiness(root, f"{manifest['project']}-redpanda"))
        shared_before = RUNNER.snapshot_shared_resources()
        lifecycle_stage = "compose-start"
        compose.start()
        lifecycle_stage = "environment-capture"
        RUNNER.capture_environment(root, manifest["project"], java_command=args.java)
        # 空卷必须先由同一 JAR 完成 Flyway；否则 fixture 会在业务表不存在时必然失败。
        lifecycle_stage = "bootstrap"
        orchestrator.bootstrap()
        lifecycle_stage = "qualification" if (
            args.before_probe_preflight or args.checkpoint_preflight or args.smoke_only
            or scenario_qualification_name) else "matrix"
        if args.before_probe_preflight:
            before_probe_preflight = orchestrator.preflight_checkpoint(include_before_probe=True)
        elif args.checkpoint_preflight:
            checkpoint_preflight = orchestrator.preflight_checkpoint()
        elif args.smoke_only:
            fixture = orchestrator._prepare_fixture("DB-01")
            smoke = {"verdict": "PASS", "faultInjected": False, "fixture": fixture,
                     "completedAt": RUNNER.utc_now()}
            RUNNER.write_json(root / "no-fault-smoke.json", smoke)
        elif scenario_qualification_name:
            checkpoint = RUNNER.SCENARIOS[scenario_qualification_name]
            receipt = orchestrator.execute(scenario_qualification_name, checkpoint)
            RUNNER.write_json(root / "scenarios" / f"{scenario_qualification_name}.json", receipt)
            scenario_qualification = {
                "schemaVersion": 1,
                "qualification": "SINGLE_SCENARIO",
                "scenario": scenario_qualification_name,
                "checkpoint": checkpoint,
                "verdict": "PASS",
                "faultInjected": True,
                "scenarioEvidence": RUNNER.evidence_reference(
                    root, root / "scenarios" / f"{scenario_qualification_name}.json"),
                "completedAt": RUNNER.utc_now(),
            }
        else:
            RUNNER.run_matrix(root, orchestrator.execute, orchestrator.failure_context)
    except BaseException as exception:
        lifecycle_error = exception
    finally:
        pid = sut.process.pid if sut is not None and sut.process is not None else None
        try:
            if sut is not None and sut.process is not None and sut.process.poll() is None:
                sut.kill()
        except BaseException as exception:
            if lifecycle_error is None:
                lifecycle_error, lifecycle_stage = exception, "sut-stop"
        try:
            cleanup_receipt = RUNNER.cleanup(
                # Compose 发布端口用重新 bind 证明 Docker 转发已撤销；SUT 端口可能因 macOS
                # TIME_WAIT 暂时不可 bind，其关闭由上面的精确进程身份与 sutPidGone 证明。
                root, manifest, compose_files, shared_before, list(ports.values()), pid,
                environment=environment, env_file=args.env_file)
        except BaseException as exception:
            cleanup_receipt = RUNNER.write_cleanup_failure(root, manifest, exception)
            if lifecycle_error is None:
                lifecycle_error, lifecycle_stage = exception, "cleanup"

    formal_run = not (args.before_probe_preflight or args.checkpoint_preflight or args.smoke_only
                      or scenario_qualification_name)
    if formal_run:
        if lifecycle_error is not None:
            RUNNER.close_scenarios_after_failure(root, lifecycle_error, lifecycle_stage)
        before = orchestrator.before if orchestrator is not None else {}
        after = orchestrator.after if orchestrator is not None else {}
        RUNNER.write_probe_snapshot(root, "database-before", {"scenarios": before})
        RUNNER.write_probe_snapshot(root, "database-after", {"scenarios": after})
        alert_firing, alert_resolved = (orchestrator.database_alert_queries()
                                        if orchestrator is not None else (None, None))
        empty_query = {"queriedAt": RUNNER.utc_now(), "result": []}
        RUNNER.write_prometheus_alerts(root, alert_firing or empty_query,
                                       alert_resolved or empty_query)
        RUNNER.finalize_manifest(root, manifest)
        return RUNNER.verdict(root)

    RUNNER.finalize_manifest(root, manifest)
    if scenario_qualification_name:
        checkpoint = RUNNER.SCENARIOS[scenario_qualification_name]
        if scenario_qualification is None:
            exception = lifecycle_error or RUNNER.C4bError("单场资格结果缺失")
            receipt = {
                "scenario": scenario_qualification_name,
                "checkpoint": checkpoint,
                "verdict": "FAIL",
                "directFailure": type(exception).__name__,
                "lifecycleStage": lifecycle_stage,
                "exception": RUNNER.exception_diagnostic(exception),
                "completedAt": RUNNER.utc_now(),
            }
            if orchestrator is not None:
                receipt.update(orchestrator.failure_context(scenario_qualification_name, exception))
            RUNNER.write_json(root / "scenarios" / f"{scenario_qualification_name}.json", receipt)
            scenario_qualification = {
                "schemaVersion": 1,
                "qualification": "SINGLE_SCENARIO",
                "scenario": scenario_qualification_name,
                "checkpoint": checkpoint,
                "verdict": "FAIL",
                "faultInjected": (orchestrator is not None
                                  and scenario_qualification_name in orchestrator.injected_scenarios),
                "exception": RUNNER.exception_diagnostic(exception),
                "scenarioEvidence": RUNNER.evidence_reference(
                    root, root / "scenarios" / f"{scenario_qualification_name}.json"),
                "completedAt": RUNNER.utc_now(),
            }
        assert cleanup_receipt is not None
        scenario_qualification["cleanupPassed"] = cleanup_receipt.get("verdict") == "PASS"
        scenario_qualification["verdict"] = "PASS" if (
            lifecycle_error is None and scenario_qualification["cleanupPassed"]
            and scenario_qualification["verdict"] == "PASS") else "FAIL"
        RUNNER.write_json(root / "scenario-qualification.json", scenario_qualification)
        RUNNER.write_checksum_closure(root)
        return scenario_qualification
    if args.before_probe_preflight:
        if before_probe_preflight is None:
            before_probe_preflight = {"schemaVersion": 1, "qualification": "BEFORE_PROBE_PREFLIGHT",
                                      "verdict": "FAIL", "faultInjected": False,
                                      "exception": RUNNER.exception_diagnostic(
                                          lifecycle_error or RUNNER.C4bError("资格结果缺失")),
                                      "completedAt": RUNNER.utc_now()}
        assert cleanup_receipt is not None
        before_probe_preflight["cleanupPassed"] = cleanup_receipt.get("verdict") == "PASS"
        before_probe_preflight["verdict"] = "PASS" if (
            lifecycle_error is None and before_probe_preflight["cleanupPassed"]) else "FAIL"
        RUNNER.write_json(root / "before-probe-preflight.json", before_probe_preflight)
        RUNNER.write_checksum_closure(root)
        return before_probe_preflight
    if args.checkpoint_preflight:
        if checkpoint_preflight is None:
            checkpoint_preflight = {"schemaVersion": 1, "qualification": "CHECKPOINT_PREFLIGHT",
                                    "verdict": "FAIL", "faultInjected": False,
                                    "exception": RUNNER.exception_diagnostic(
                                        lifecycle_error or RUNNER.C4bError("资格结果缺失")),
                                    "completedAt": RUNNER.utc_now()}
        assert cleanup_receipt is not None
        checkpoint_preflight["cleanupPassed"] = cleanup_receipt.get("verdict") == "PASS"
        checkpoint_preflight["verdict"] = "PASS" if (
            lifecycle_error is None and checkpoint_preflight["cleanupPassed"]) else "FAIL"
        RUNNER.write_json(root / "checkpoint-preflight.json", checkpoint_preflight)
        RUNNER.write_checksum_closure(root)
        return checkpoint_preflight
    if args.smoke_only:
        if smoke is None:
            smoke = {"schemaVersion": 1, "verdict": "FAIL", "faultInjected": False,
                     "exception": RUNNER.exception_diagnostic(
                         lifecycle_error or RUNNER.C4bError("资格结果缺失")),
                     "completedAt": RUNNER.utc_now()}
        assert cleanup_receipt is not None
        smoke["cleanupPassed"] = cleanup_receipt.get("verdict") == "PASS"
        smoke["verdict"] = "PASS" if (
            lifecycle_error is None and smoke["cleanupPassed"]) else "FAIL"
        RUNNER.write_json(root / "no-fault-smoke.json", smoke)
        RUNNER.write_checksum_closure(root)
        return smoke
    raise AssertionError("资格模式分支不完整")


def qualified_exit_code(result: dict[str, Any], qualification_only: bool) -> int:
    """资格运行接受 PASS；正式十场景仍只接受完整 VALID_PASS。"""
    expected = "PASS" if qualification_only else "VALID_PASS"
    return 0 if result.get("verdict") == expected else 1


def main() -> int:
    """解析正式运行参数；单场资格与正式十场景使用不同回执，禁止把资格 PASS 冒充放行。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--git-commit", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--compose-file", type=Path, action="append", required=True)
    parser.add_argument("--env-file", type=Path)
    parser.add_argument("--port", action="append", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--health-url", required=True)
    parser.add_argument("--sut-argument", action="append", default=[])
    qualification = parser.add_mutually_exclusive_group()
    qualification.add_argument("--smoke-only", action="store_true",
                               help="只执行迁移、DB-01 无故障基线与精确清理，不注入故障")
    qualification.add_argument(
        "--checkpoint-preflight", action="store_true",
        help="只证明 DB-01 fixture、Outbox 预置和 checkpoint 可达，不执行故障注入")
    qualification.add_argument(
        "--before-probe-preflight", action="store_true",
        help="只证明 DB-01 fixture、Outbox 预置、checkpoint 和 beforeProbe 可达，不执行故障注入")
    qualification.add_argument(
        "--scenario-qualification", choices=tuple(RUNNER.SCENARIOS),
        help="只执行指定场景的完整故障注入闭环；结果仅为资格 PASS，不生成正式 VALID_PASS")
    args = parser.parse_args()
    if len(args.compose_file) != 2:
        parser.error("--compose-file 必须且只能出现两次")
    result = execute_full(args)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return qualified_exit_code(
        result, args.smoke_only or args.checkpoint_preflight or args.before_probe_preflight
        or bool(args.scenario_qualification))


if __name__ == "__main__":
    sys.exit(main())
