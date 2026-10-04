#!/usr/bin/env python3
"""G1-C4b 十场景的隔离生命周期、强杀、清理与机器裁决工具。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import re
import signal
import socket
import subprocess
import sys
import time
import traceback
import uuid
import urllib.parse
import urllib.request
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Callable


SCENARIOS = {
    "DB-01": "OUTBOX_AFTER_CLAIM_COMMIT",
    "OB-01": "OUTBOX_BEFORE_CLAIM",
    "OB-02": "OUTBOX_AFTER_CLAIM_COMMIT",
    "OB-03": "OUTBOX_AFTER_KAFKA_ACK",
    "OB-04": "OUTBOX_AFTER_MARK_PUBLISHED_COMMIT",
    "RW-01": "RULE_BEFORE_RECEIPT_CLAIM",
    "RW-02": "RULE_AFTER_RECEIPT_CLAIM",
    "RW-03": "RULE_AFTER_CONTINUATION_ACK",
    "RW-04": "RULE_AFTER_RECEIPT_COMMIT",
    "RW-05": "RULE_AFTER_RETRY_ACK",
}
PROJECT_PATTERN = re.compile(r"c4b-[0-9a-f]{12}\Z")
REQUIRED_EVIDENCE = (
    "run-manifest.json", "environment.json", "events.jsonl", "database-before.json",
    "database-after.json", "prometheus-alerts.json", "cleanup.json",
)
PHASES = ("fixture", "workloadBeforeCheckpoint", "beforeProbe",
          "workloadAfterInjection", "afterProbe")
# beforeProbe 由编排器在 CHECKPOINT_REACHED 落盘后才会调用；后续两阶段同理。
# 失败 receipt 不携带 preflight 专用 checkpointEvidence，因此必须用这些持久阶段引用恢复执行边界。
POST_CHECKPOINT_PHASES = frozenset({"beforeProbe", "workloadAfterInjection", "afterProbe"})
CONSUMER_REQUIREMENTS = {
    "things-link-ingestion-normalized": ("tc.device.uplink.normalized", 12),
    "things-link-ingestion-processed": ("tc.device.uplink.processed", 12),
    "things-link-rule-notification": ("tc.rule.notification", 6),
}
DATABASE_RECOVERY_EVIDENCE = {
    "outbox-terminal": ("business-recovery", 180),
    "notification-delivery": ("business-recovery", 180),
    "inbox-persisted": ("business-recovery", 180),
    "outbox-integrity": ("business-recovery", 180),
    "alert-resolved": ("alert-resolution", 180),
    "database-volume": ("identity", 0),
}
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
GIT_COMMIT_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
SECRET_PATTERN = re.compile(
    r"(?i)(password|token|secret|authorization)(\s*[:=]\s*)([^\s,;]+)")


class C4bError(RuntimeError):
    """输入、环境或机器证据违反冻结合同时的 fail-closed 错误。"""


def utc_now() -> str:
    """返回 UTC ISO-8601 时间。"""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def require_uuid_v7(value: str) -> uuid.UUID:
    """只接受 UUIDv7，防止复用人为短名形成资源碰撞。"""
    parsed = uuid.UUID(value)
    if parsed.version != 7:
        raise C4bError("runId 必须是 UUIDv7")
    return parsed


def project_for(run_id: str) -> str:
    """从 UUIDv7 派生冻结格式的 Compose project。"""
    parsed = require_uuid_v7(run_id)
    return "c4b-" + parsed.hex[:12]


def write_json(path: Path, value: Any) -> None:
    """原子写 JSON，避免中断留下可被误判为完整的 receipt。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def append_event(root: Path, event: str, **fields: Any) -> None:
    """追加并 fsync 一条不含凭据的运行事件。"""
    record = {"event": event, "at": utc_now(), **fields}
    with (root / "events.jsonl").open("a", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True,
                                separators=(",", ":")) + "\n")
        stream.flush()
        os.fsync(stream.fileno())


def sha256(path: Path) -> str:
    """流式计算文件 SHA-256。"""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_utc(value: Any, field: str) -> datetime:
    """只接受带 Z 的 UTC 时间，避免本地时区或无偏移时间混入机器裁决。"""
    if not isinstance(value, str) or not value.endswith("Z"):
        raise C4bError(f"{field} 必须是 UTC Z 时间")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exception:
        raise C4bError(f"{field} 不是有效 UTC 时间") from exception
    if parsed.tzinfo != timezone.utc:
        raise C4bError(f"{field} 必须是 UTC 时间")
    return parsed


def git_source_identity(expected_commit: str, anchor: Path,
                        run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run
                        ) -> dict[str, Any]:
    """读取真实 HEAD 与工作区状态；资格运行不得信任调用方自报提交。"""
    if not GIT_COMMIT_PATTERN.fullmatch(expected_commit):
        raise C4bError("--git-commit 必须是完整 40 位小写提交")

    def git(*arguments: str) -> str:
        command = ["git", "-C", str(anchor.resolve().parent), *arguments]
        completed = run(command, capture_output=True, text=True, encoding="utf-8",
                        errors="replace", check=False)
        if completed.returncode != 0:
            raise C4bError(f"Git 身份读取失败: {arguments[0]}")
        return completed.stdout.strip()

    repository = Path(git("rev-parse", "--show-toplevel")).resolve()
    head = git("rev-parse", "HEAD")
    if not GIT_COMMIT_PATTERN.fullmatch(head) or head != expected_commit:
        raise C4bError("调用参数与真实 Git HEAD 不一致")
    status = git("status", "--porcelain=v1", "--untracked-files=all")
    if status:
        raise C4bError("资格运行要求 clean Git 工作区")
    return {"qualificationCommit": head, "evidenceArchiveParentCommit": head,
            "gitClean": True, "repository": repository.as_posix(),
            "verifiedAt": utc_now()}


def bounded_diagnostic(value: str | None, limit: int = 4096) -> dict[str, Any]:
    """脱敏并按 UTF-8 字节截断外部输出，既保留失败原因又不扩散凭据。"""
    raw = value or ""
    redacted = SECRET_PATTERN.sub(r"\1\2<redacted>", raw)
    encoded = redacted.encode("utf-8", "replace")
    truncated = len(encoded) > limit
    if truncated:
        encoded = encoded[:limit]
        while True:
            try:
                redacted = encoded.decode("utf-8")
                break
            except UnicodeDecodeError:
                encoded = encoded[:-1]
    return {"text": redacted, "utf8Bytes": len(raw.encode("utf-8", "replace")),
            "truncated": truncated}


def exception_diagnostic(exception: BaseException) -> dict[str, Any]:
    """保存完整 cause/context 链和有界堆栈，不把异常类名当作唯一失败证据。"""
    chain: list[dict[str, Any]] = []
    current: BaseException | None = exception
    visited: set[int] = set()
    while current is not None and id(current) not in visited:
        visited.add(id(current))
        chain.append({"type": type(current).__name__,
                      "message": bounded_diagnostic(str(current), 2048)})
        current = current.__cause__ if current.__cause__ is not None else current.__context__
    stack = "".join(traceback.format_exception(type(exception), exception, exception.__traceback__))
    return {"chain": chain, "stackTrace": bounded_diagnostic(stack, 8192)}


def evidence_reference(root: Path, path: Path) -> dict[str, str]:
    """返回证据根内的相对路径与摘要，拒绝引用根目录之外的文件。"""
    resolved_root = root.resolve()
    resolved = path.resolve()
    try:
        relative = resolved.relative_to(resolved_root)
    except ValueError as exception:
        raise C4bError("证据引用越出运行根目录") from exception
    return {"path": relative.as_posix(), "sha256": sha256(resolved)}


def run_json_probe(command: list[str], timeout: int = 60) -> dict[str, Any]:
    """无 shell 执行冻结 fixture/数据库探针，并只接受单个 JSON 对象。"""
    if not command or any(not isinstance(part, str) or not part for part in command):
        raise C4bError("探针命令必须是非空 argv")
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                               errors="replace", timeout=timeout, check=False)
    if completed.returncode != 0:
        raise C4bError(f"探针失败 rc={completed.returncode} executable={command[0]}")
    try:
        value = json.loads(completed.stdout)
    except json.JSONDecodeError as exception:
        raise C4bError("探针未返回严格 JSON") from exception
    if not isinstance(value, dict):
        raise C4bError("探针结果必须是 JSON 对象")
    return value


def process_identity(pid: int) -> dict[str, Any]:
    """读取操作系统进程创建身份与完整命令摘要，拒绝 PID 复用。"""
    if pid <= 0:
        raise C4bError("PID 必须为正整数")
    if os.name == "nt":
        script = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}';"
                  "if($null -eq $p){exit 3};"
                  "$p|Select-Object ProcessId,CreationDate,CommandLine|ConvertTo-Json -Compress")
        completed = subprocess.run(["pwsh", "-NoProfile", "-Command", script], capture_output=True,
                                   text=True, encoding="utf-8", errors="replace", check=False)
        if completed.returncode != 0:
            raise C4bError("目标进程不存在")
        raw = json.loads(completed.stdout)
        command = raw.get("CommandLine") or ""
        return {"pid": int(raw["ProcessId"]), "created": raw.get("CreationDate"),
                "commandSha256": hashlib.sha256(command.encode("utf-8")).hexdigest()}
    if sys.platform == "darwin":
        # macOS 不提供 Linux /proc；分别读取创建时间与完整命令，避免表头或空格拆分污染身份。
        created = subprocess.run(["ps", "-p", str(pid), "-o", "lstart="], capture_output=True,
                                 text=True, encoding="utf-8", errors="replace", check=False)
        command = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True,
                                 text=True, encoding="utf-8", errors="replace", check=False)
        if created.returncode != 0 or command.returncode != 0:
            raise C4bError("目标进程不存在")
        created_value = created.stdout.strip()
        command_value = command.stdout.strip()
        if not created_value or not command_value:
            raise C4bError("目标进程身份不完整")
        return {"pid": pid, "created": created_value,
                "commandSha256": hashlib.sha256(command_value.encode("utf-8")).hexdigest()}
    proc = Path("/proc") / str(pid)
    try:
        fields = (proc / "stat").read_text(encoding="utf-8").split()
        command = (proc / "cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
    except OSError as exception:
        raise C4bError("目标进程不存在") from exception
    return {"pid": pid, "created": fields[21],
            "commandSha256": hashlib.sha256(command.encode("utf-8")).hexdigest()}


class SutController:
    """只管理 runner 自己启动且四项指纹一致的单一 SUT 子进程。"""

    def __init__(self, jar: Path, root: Path) -> None:
        self.jar = jar.resolve()
        self.root = root.resolve()
        self.process: subprocess.Popen[bytes] | None = None
        self.fingerprint: dict[str, Any] | None = None
        self.log_stream: Any = None

    def start(self, java: str, arguments: list[str], environment: dict[str, str]) -> dict[str, Any]:
        """启动本片 JAR 并记录 PID、创建时间、命令摘要与 JAR SHA-256。"""
        if self.process is not None and self.process.poll() is None:
            raise C4bError("SUT 已在运行")
        if not self.jar.is_file():
            raise C4bError("SUT JAR 不存在")
        command = [java, "-jar", str(self.jar), *arguments]
        self.log_stream = (self.root / "sut.log").open("ab")
        self.process = subprocess.Popen(command, env=environment, stdout=self.log_stream,
                                        stderr=subprocess.STDOUT)
        try:
            identity = process_identity(self.process.pid)
        except Exception:
            self.process.kill()
            self.process.wait(timeout=15)
            self.log_stream.close()
            raise
        self.fingerprint = {**identity, "jarSha256": sha256(self.jar)}
        write_json(self.root / "sut-process.json", self.fingerprint)
        append_event(self.root, "SUT_STARTED", **self.fingerprint)
        return dict(self.fingerprint)

    def kill(self) -> None:
        """四项复核一致后强杀；任何漂移都拒绝按 PID 操作。"""
        if self.process is None or self.fingerprint is None or self.process.poll() is not None:
            raise C4bError("没有可强杀的活动 SUT")
        actual = process_identity(self.process.pid)
        for key in ("pid", "created", "commandSha256"):
            if actual.get(key) != self.fingerprint.get(key):
                raise C4bError("SUT 进程身份漂移，拒绝强杀")
        if sha256(self.jar) != self.fingerprint["jarSha256"]:
            raise C4bError("SUT JAR 指纹漂移，拒绝强杀")
        pid = self.process.pid
        if os.name == "nt":
            completed = subprocess.run(["taskkill", "/PID", str(pid), "/F", "/T"],
                                       capture_output=True, text=True, check=False)
            if completed.returncode != 0 and self.process.poll() is None:
                raise C4bError("精确强杀失败")
        else:
            os.kill(pid, signal.SIGKILL)
        self.process.wait(timeout=15)
        self.log_stream.close()
        append_event(self.root, "SUT_KILLED", **self.fingerprint)

    def release(self, scenario: str) -> None:
        """显式创建一次性 release；只用于 DB-01 让 Worker 在数据库停机后继续。"""
        if scenario not in SCENARIOS:
            raise C4bError("未知场景")
        release = self.root / "control" / scenario / "release" / (SCENARIOS[scenario].lower() + ".release")
        release.touch(exist_ok=False)


class ComposeController:
    """以 manifest 中唯一 project 编排隔离栈，故障操作只允许 PostgreSQL。"""

    def __init__(self, project: str, compose_files: list[Path], environment: dict[str, str],
                 run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run, *,
                 env_file: Path | None = None,
                 profiles: tuple[str, ...] = ("obs", "init")) -> None:
        if not PROJECT_PATTERN.fullmatch(project):
            raise C4bError("非法 Compose project")
        if len(compose_files) != 2 or any(not item.is_file() for item in compose_files):
            raise C4bError("必须提供基础 Compose 与 C4b overlay")
        self.project = project
        self.compose_files = [item.resolve() for item in compose_files]
        self.environment = environment
        self.run = run
        self.env_file = env_file.resolve() if env_file else None
        self.profiles = profiles

    def command(self, *arguments: str, timeout: int = 300) -> subprocess.CompletedProcess[str]:
        """始终携带两份固定 Compose、精确 project 和显式环境执行。"""
        command = ["docker", "compose"]
        for item in self.compose_files:
            command.extend(["-f", str(item)])
        if self.env_file:
            command.extend(["--env-file", str(self.env_file)])
        for profile in self.profiles:
            command.extend(["--profile", profile])
        command.extend(["-p", self.project, *arguments])
        completed = self.run(command, env=self.environment, capture_output=True, text=True,
                             timeout=timeout, check=False)
        if completed.returncode != 0:
            raise C4bError(f"Compose 失败: {' '.join(arguments)}")
        return completed

    def start(self) -> None:
        """先验证解析配置，再启动本 project 的中间件与初始化任务。"""
        self.command("config", "--quiet", timeout=60)
        self.command("up", "-d", "--wait", "postgres", "redis",
                     "redpanda", "emqx", "minio", "prometheus")
        self.command("run", "--rm", "redpanda-init", timeout=180)
        self.command("run", "--rm", "minio-init", timeout=180)

    def stop_database(self) -> None:
        """DB-01 只停止本 project PostgreSQL 容器，不删除其卷。"""
        self.command("stop", "postgres", timeout=60)

    def start_database(self) -> None:
        """DB-01 启动同一 PostgreSQL 容器和原卷，并等待健康。"""
        self.command("start", "postgres", timeout=60)
        self.command("up", "-d", "--wait", "postgres", timeout=60)


def prepare(root: Path, run_id: str, git_commit: str, jar: Path,
            compose_files: list[Path], runner: Path, ports: dict[str, int], *,
            git_run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run) -> dict[str, Any]:
    """建立不可复用证据根、控制目录和初始 manifest。"""
    project = project_for(run_id)
    source_identity = git_source_identity(git_commit, runner, git_run)
    if root.exists():
        raise C4bError("证据根已存在，禁止覆盖或续写旧运行")
    if not jar.is_file() or not runner.is_file() or any(not item.is_file() for item in compose_files):
        raise C4bError("JAR、runner 或 Compose 工件缺失")
    if len(set(ports.values())) != len(ports) or any(value < 1024 or value > 65535 for value in ports.values()):
        raise C4bError("宿主端口必须唯一且位于 1024..65535")
    for name, port in ports.items():
        with socket.socket() as probe:
            try:
                probe.bind(("127.0.0.1", port))
            except OSError as exception:
                raise C4bError(f"宿主端口已占用: {name}") from exception
    (root / "scenarios").mkdir(parents=True)
    for scenario, checkpoint in SCENARIOS.items():
        control = root / "control" / scenario
        (control / "once").mkdir(parents=True)
        (control / "release").mkdir()
        write_json(control / "contract.json", {"runId": run_id, "scenario": scenario,
                                                "checkpoint": checkpoint})
    manifest = {
        "schemaVersion": 1, "runId": run_id, "project": project,
        "gitCommit": source_identity["qualificationCommit"], "gitClean": True,
        "sourceIdentity": source_identity,
        "startedAt": utc_now(), "scenarios": list(SCENARIOS), "ports": ports,
        "artifacts": {"jar": sha256(jar), "runner": sha256(runner),
                      "compose": {item.name: sha256(item) for item in compose_files}},
        "leaseSeconds": {"outbox": 30, "ruleReceipt": 60},
    }
    write_json(root / "run-manifest.json", manifest)
    append_event(root, "PREPARED", runId=run_id, project=project)
    return manifest


def finalize_manifest(root: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    """在所有运行与清理动作结束后封闭 UTC 终止时间，之后只允许生成 verdict/checksum。"""
    completed = utc_now()
    if parse_utc(completed, "manifest.completedAt") < parse_utc(
            manifest.get("startedAt"), "manifest.startedAt"):
        raise C4bError("manifest 终止时间早于开始时间")
    manifest["completedAt"] = completed
    write_json(root / "run-manifest.json", manifest)
    return manifest


def run_matrix(root: Path, execute: Callable[[str, str], dict[str, Any]],
               failure_context: Callable[[str, BaseException], dict[str, Any]] | None = None
               ) -> dict[str, str]:
    """按冻结顺序运行十场景；首个失败后其余场景明确标记 NOT_RUN。"""
    results: dict[str, str] = {}
    failed = False
    for scenario, checkpoint in SCENARIOS.items():
        receipt_path = root / "scenarios" / f"{scenario}.json"
        if failed:
            receipt = {"scenario": scenario, "checkpoint": checkpoint, "verdict": "NOT_RUN",
                       "reason": "前序场景直接失败", "completedAt": utc_now()}
        else:
            try:
                receipt = execute(scenario, checkpoint)
                if receipt.get("scenario") != scenario or receipt.get("checkpoint") != checkpoint:
                    raise C4bError("场景执行器返回了错误身份")
                if receipt.get("verdict") not in ("PASS", "FAIL"):
                    raise C4bError("场景执行器必须返回 PASS 或 FAIL")
                validate_scenario_receipt(receipt)
            except Exception as exception:
                receipt = {"scenario": scenario, "checkpoint": checkpoint, "verdict": "FAIL",
                           "directFailure": type(exception).__name__,
                           "exception": exception_diagnostic(exception), "completedAt": utc_now()}
                if failure_context is not None:
                    receipt.update(failure_context(scenario, exception))
                validate_phase_evidence(root, receipt)
            failed = receipt["verdict"] != "PASS"
        write_json(receipt_path, receipt)
        results[scenario] = receipt["verdict"]
    return results


def validate_scenario_receipt(receipt: dict[str, Any], root: Path | None = None) -> None:
    """把冻结矩阵的关键事实转为机器硬判据，禁止仅凭执行器自报 PASS。"""
    if root is not None:
        validate_phase_evidence(root, receipt)
    if receipt.get("verdict") != "PASS":
        return
    scenario = receipt.get("scenario")
    facts = receipt.get("facts", {})
    if root is not None and scenario == "DB-01":
        validate_database_recovery_evidence(root, facts)
    if root is not None and scenario == "RW-05":
        validate_rw05_saturation_evidence(root, facts)
    injection_proved = (facts.get("databaseStopped") is True if scenario == "DB-01"
                        else facts.get("exactPidKilled") is True)
    common = (facts.get("checkpointReachedCount") == 1
              and injection_proved
              and facts.get("finalAssertionsPassed") is True)
    if not common:
        raise C4bError("PASS receipt 缺少 checkpoint、精确强杀或最终事实")
    predicates = {
        "DB-01": isinstance(facts.get("databaseVolumeBefore"), str)
                 and bool(facts.get("databaseVolumeBefore"))
                 and facts.get("databaseVolumeBefore") == facts.get("databaseVolumeAfter")
                 and facts.get("sameDatabaseVolume") is True
                 and facts.get("databaseUnavailableObserved") is True
                 and facts.get("databaseRecovered") is True
                 and facts.get("databaseUnavailableAlertFiring") is True
                 and facts.get("databaseUnavailableAlertResolved") is True
                 and facts.get("targetKafkaRecords", 0) >= 1
                 and facts.get("businessFactCount") == 1
                 and facts.get("targetAndFollowerPublished") is True
                 and facts.get("laneOrderPreserved") is True
                 and facts.get("noDanglingLease") is True,
        "OB-01": facts.get("targetKafkaRecords") == 2
                 and facts.get("businessFactCount") == 1,
        # F12 后 target/follower 共用 deliveryId；该计数是 lane 总传输数，不是 target 单行数。
        "OB-02": facts.get("targetKafkaRecords") == 2
                 and facts.get("businessFactCount") == 1
                 and facts.get("targetAndFollowerPublished") is True
                 and facts.get("laneOrderPreserved") is True
                 and facts.get("noDanglingLease") is True
                 and facts.get("leaseClaimCount") == 2
                 and 30 <= facts.get("leaseTakeoverSeconds", 0) <= 60
                 and facts.get("leaseTokenChanged") is True,
        "OB-03": facts.get("targetKafkaRecords", 0) >= 2 and facts.get("businessFactCount") == 1,
        "OB-04": facts.get("targetKafkaRecords") == 2
                 and facts.get("targetKafkaRecordsBefore") == 1
                 and facts.get("businessFactCount") == 1
                 and facts.get("targetAndFollowerPublished") is True
                 and facts.get("laneOrderPreserved") is True
                 and facts.get("noDanglingLease") is True
                 and facts.get("targetLeaseClaimCount") == 1
                 and facts.get("targetPublishedAtUnchanged") is True
                 and facts.get("targetReclaimed") is False,
        "RW-01": facts.get("scriptSuccessCount") == 1,
        "RW-02": 60 <= facts.get("leaseTakeoverSeconds", 0) <= 90
                 and facts.get("scriptSuccessCount") == 1,
        "RW-03": facts.get("processedInboxCount") == 1 and facts.get("sideEffectCount") == 1,
        "RW-04": facts.get("replayed") is True and facts.get("scriptSuccessCount") == 1
                 and facts.get("continuationAddedAfterRestart") == 0,
        "RW-05": facts.get("nextAttemptTerminalCount") == 1
                 and facts.get("oldAttemptSideEffectCount") == 0
                 and facts.get("saturationDistinctMemberCount") == 3
                 and isinstance(facts.get("saturationPartitions"), list)
                 and len(facts["saturationPartitions"]) == 3
                 and len(set(facts["saturationPartitions"])) == 3
                 and isinstance(facts.get("saturationQualificationEvidence"), dict),
    }
    if scenario not in predicates or not predicates[scenario]:
        raise C4bError("PASS receipt 不满足冻结场景硬判据")


def validate_rw05_saturation_evidence(root: Path, facts: dict[str, Any]) -> None:
    """复算 RW-05 三个目标分区确由 readiness 中三个不同消费成员负责。"""
    qualification = referenced_json(
        root, facts.get("saturationQualificationEvidence"), "RW-05 跨成员饱和资格")
    selections = qualification.get("selections")
    if (qualification.get("schemaVersion") != 1 or qualification.get("scenario") != "RW-05"
            or qualification.get("topic") != "tc.device.uplink.normalized"
            or qualification.get("requiredDistinctMembers") != 3
            or qualification.get("distinctMemberCount") != 3
            or not isinstance(selections, list) or len(selections) != 3):
        raise C4bError("RW-05 跨成员饱和资格结构无效")
    try:
        partitions = [item["partition"] for item in selections]
        members = [item["memberId"] for item in selections]
    except (KeyError, TypeError) as exception:
        raise C4bError("RW-05 跨成员饱和选择格式无效") from exception
    if (not all(isinstance(item, int) for item in partitions)
            or not all(isinstance(item, str) and item for item in members)
            or len(set(partitions)) != 3 or len(set(members)) != 3
            or facts.get("saturationPartitions") != partitions
            or facts.get("saturationDistinctMemberCount") != len(set(members))):
        raise C4bError("RW-05 跨成员饱和事实与资格不一致")
    readiness = referenced_json(root, qualification.get("readinessEvidence"), "RW-05 fixture readiness")
    try:
        group = readiness["groups"]["things-link-ingestion-normalized"]["parsed"]
        assignments = group["partitionAssignments"]
    except (KeyError, TypeError) as exception:
        raise C4bError("RW-05 fixture readiness 缺 normalized assignment") from exception
    assignment_pairs = {(item.get("partition"), item.get("memberId"))
                        for item in assignments if isinstance(item, dict)}
    if (readiness.get("scenario") != "RW-05" or readiness.get("purpose") != "fixture"
            or readiness.get("verdict") != "PASS" or group.get("ready") is not True
            or group.get("assignor") != "range"
            or any((partition, member) not in assignment_pairs
                   for partition, member in zip(partitions, members))):
        raise C4bError("RW-05 跨成员选择无法由 fixture readiness 复算")


def validate_database_recovery_evidence(root: Path, facts: dict[str, Any]) -> None:
    """硬判 DB-01 六段恢复证据，拒绝自报布尔值、缺段、错预算或摘要漂移。"""
    references = facts.get("recoveryEvidence")
    if not isinstance(references, dict) or set(references) != set(DATABASE_RECOVERY_EVIDENCE):
        raise C4bError("DB-01 PASS 必须精确引用六段恢复证据")
    segments: dict[str, dict[str, Any]] = {}
    for step, (budget_group, budget_seconds) in DATABASE_RECOVERY_EVIDENCE.items():
        value = referenced_json(root, references.get(step), f"DB-01 恢复步骤 {step}")
        segments[step] = value
        if (value.get("schemaVersion") != 1 or value.get("scenario") != "DB-01"
                or value.get("step") != step or value.get("budgetGroup") != budget_group
                or value.get("groupBudgetSeconds") != budget_seconds
                or value.get("verdict") != "PASS"):
            raise C4bError(f"DB-01 恢复步骤身份、预算或裁决不匹配: {step}")
        attempts = value.get("attempts")
        remaining = value.get("remainingBudgetMillisAtStart")
        duration = value.get("durationMillis")
        if (not isinstance(attempts, int) or isinstance(attempts, bool) or attempts < 1
                or not isinstance(remaining, int) or isinstance(remaining, bool) or remaining < 0
                or remaining > budget_seconds * 1000
                or not isinstance(duration, int) or isinstance(duration, bool) or duration < 0):
            raise C4bError(f"DB-01 恢复步骤计时或尝试次数非法: {step}")
        if budget_seconds > 0 and duration > remaining + 30_000:
            # 单步 duration 可以略过共享期限，只允许外部命令/原子落盘的 30 秒余量；
            # 更大的值代表内部等待没有服从分组绝对期限。
            raise C4bError(f"DB-01 恢复步骤超出共享预算与命令余量: {step}")
        started = parse_utc(value.get("startedAt"), f"DB-01 {step}.startedAt")
        completed = parse_utc(value.get("completedAt"), f"DB-01 {step}.completedAt")
        if completed < started or "expected" not in value or "lastObserved" not in value:
            raise C4bError(f"DB-01 恢复步骤缺少可归因观测: {step}")
    integrity = segments["outbox-integrity"].get("lastObserved")
    alert = segments["alert-resolved"].get("lastObserved")
    volume = segments["database-volume"].get("lastObserved")
    target_state = integrity.get("targetState") if isinstance(integrity, dict) else None
    follower_state = integrity.get("followerState") if isinstance(integrity, dict) else None
    state_semantics = False
    if isinstance(target_state, dict) and isinstance(follower_state, dict):
        try:
            target_created = parse_utc(target_state.get("createdAt"), "DB-01 target.createdAt")
            follower_created = parse_utc(follower_state.get("createdAt"), "DB-01 follower.createdAt")
            target_published = parse_utc(target_state.get("publishedAt"), "DB-01 target.publishedAt")
            follower_published = parse_utc(follower_state.get("publishedAt"), "DB-01 follower.publishedAt")
            state_semantics = (
                isinstance(target_state.get("id"), str) and bool(target_state.get("id"))
                and isinstance(follower_state.get("id"), str) and bool(follower_state.get("id"))
                and target_state.get("id") != follower_state.get("id")
                and target_state.get("status") == "PUBLISHED"
                and follower_state.get("status") == "PUBLISHED"
                and isinstance(target_state.get("attemptCount"), int)
                and not isinstance(target_state.get("attemptCount"), bool)
                and target_state.get("attemptCount") >= 0
                and isinstance(follower_state.get("attemptCount"), int)
                and not isinstance(follower_state.get("attemptCount"), bool)
                and follower_state.get("attemptCount") >= 0
                and target_state.get("leasePresent") is False
                and follower_state.get("leasePresent") is False
                and target_created < follower_created
                and target_published <= follower_published)
        except C4bError:
            state_semantics = False
    semantics = (
        segments["outbox-terminal"].get("lastObserved") == 2
        and segments["notification-delivery"].get("lastObserved") == 1
        and segments["inbox-persisted"].get("lastObserved") == 1
        and isinstance(integrity, dict)
        and integrity.get("targetAndFollowerPublished") is True
        and integrity.get("laneOrderPreserved") is True
        and integrity.get("noDanglingLease") is True
        and integrity.get("businessFactCount") == 1
        and integrity.get("targetKafkaRecords", 0) >= 1
        and state_semantics
        and isinstance(alert, dict) and alert.get("result") == []
        and isinstance(volume, dict)
        and isinstance(volume.get("before"), str) and bool(volume.get("before"))
        and volume.get("before") == volume.get("after")
        and volume.get("same") is True and volume.get("nonEmpty") is True
        and volume.get("before") == facts.get("databaseVolumeBefore")
        and volume.get("after") == facts.get("databaseVolumeAfter")
    )
    if not semantics:
        raise C4bError("DB-01 六段恢复证据未满足冻结观测语义")


def validate_outbox_lane_evidence(
        root: Path, reference: dict[str, Any], require_success: bool) -> None:
    """硬判 OB-01 精确行状态和转换序列，拒绝仅凭顺序布尔值形成结论。"""
    value = referenced_json(root, reference, "OB-01 Outbox lane 证据")
    target_id = value.get("targetId")
    follower_id = value.get("followerId")
    target = value.get("targetState")
    follower = value.get("followerState")
    transitions = value.get("transitions")
    if (value.get("schemaVersion") != 1 or value.get("scenario") != "OB-01"
            or not isinstance(target_id, str) or not target_id
            or not isinstance(follower_id, str) or not follower_id or target_id == follower_id
            or not isinstance(target, dict) or not isinstance(follower, dict)
            or target.get("id") != target_id or follower.get("id") != follower_id
            or not isinstance(transitions, list) or not transitions):
        raise C4bError("OB-01 Outbox lane 证据身份或结构无效")
    previous_sequence = 0
    normalized: list[dict[str, Any]] = []
    for transition in transitions:
        if not isinstance(transition, dict):
            raise C4bError("OB-01 Outbox 转换不是对象")
        sequence = transition.get("sequence")
        event_id = transition.get("eventId")
        old_digest = transition.get("oldLeaseTokenSha256")
        new_digest = transition.get("newLeaseTokenSha256")
        if (not isinstance(sequence, int) or isinstance(sequence, bool)
                or sequence <= previous_sequence or event_id not in (target_id, follower_id)
                or (old_digest is not None and (
                    not isinstance(old_digest, str) or not SHA256_PATTERN.fullmatch(old_digest)))
                or (new_digest is not None and (
                    not isinstance(new_digest, str) or not SHA256_PATTERN.fullmatch(new_digest)))
                or transition.get("oldStatus") not in ("PENDING", "PUBLISHED")
                or transition.get("newStatus") not in ("PENDING", "PUBLISHED")):
            raise C4bError("OB-01 Outbox 转换顺序、租约摘要或状态无效")
        parse_utc(transition.get("availableAt"), "OB-01 transition.availableAt")
        parse_utc(transition.get("observedAt"), "OB-01 transition.observedAt")
        if transition.get("leasedUntil") is not None:
            parse_utc(transition.get("leasedUntil"), "OB-01 transition.leasedUntil")
        if transition.get("publishedAt") is not None:
            parse_utc(transition.get("publishedAt"), "OB-01 transition.publishedAt")
        previous_sequence = sequence
        normalized.append(transition)
    parse_utc(value.get("capturedAt"), "OB-01 capturedAt")
    if not require_success:
        return
    try:
        target_created = parse_utc(target.get("createdAt"), "OB-01 target.createdAt")
        follower_created = parse_utc(follower.get("createdAt"), "OB-01 follower.createdAt")
        target_available = parse_utc(target.get("availableAt"), "OB-01 target.availableAt")
        follower_available = parse_utc(follower.get("availableAt"), "OB-01 follower.availableAt")
        target_published = parse_utc(target.get("publishedAt"), "OB-01 target.publishedAt")
        follower_published = parse_utc(follower.get("publishedAt"), "OB-01 follower.publishedAt")
    except C4bError as exception:
        raise C4bError("OB-01 PASS 的精确行时间无效") from exception
    target_published_steps = [item["sequence"] for item in normalized
                              if item["eventId"] == target_id
                              and item["newStatus"] == "PUBLISHED"
                              and item["publishedAt"] is not None]
    follower_claim_steps = [item["sequence"] for item in normalized
                            if item["eventId"] == follower_id
                            and item["newLeaseTokenSha256"] is not None]
    semantics = (
        target.get("status") == "PUBLISHED" and follower.get("status") == "PUBLISHED"
        and target.get("leasePresent") is False and follower.get("leasePresent") is False
        and isinstance(target.get("attemptCount"), int)
        and not isinstance(target.get("attemptCount"), bool) and target.get("attemptCount") >= 0
        and isinstance(follower.get("attemptCount"), int)
        and not isinstance(follower.get("attemptCount"), bool) and follower.get("attemptCount") >= 0
        and target_created < follower_created and target_available < follower_available
        and target_published <= follower_published
        and value.get("laneOrderPreserved") is True
        and len(target_published_steps) == 1 and bool(follower_claim_steps)
        and target_published_steps[0] < min(follower_claim_steps)
    )
    if not semantics:
        raise C4bError("OB-01 精确状态或领取/发布转换顺序未满足冻结语义")


def validate_lease_takeover_evidence(
        root: Path, reference: dict[str, Any], facts: dict[str, Any],
        require_success: bool) -> None:
    """硬判 OB-02 原租约到期后的不同 token 接管，拒绝自报布尔值和时长。"""
    value = referenced_json(root, reference, "OB-02 租约接管证据")
    target_id = value.get("targetId")
    before_digest = value.get("beforeLeaseTokenSha256")
    claims = value.get("claims")
    target = value.get("targetState")
    if (value.get("schemaVersion") != 1 or value.get("scenario") != "OB-02"
            or not isinstance(target_id, str) or not target_id
            or value.get("leaseSeconds") != 30
            or not isinstance(value.get("leaseUntilEpoch"), int)
            or isinstance(value.get("leaseUntilEpoch"), bool)
            or value.get("leaseUntilEpoch") <= 0
            or not isinstance(before_digest, str)
            or not SHA256_PATTERN.fullmatch(before_digest)
            or not isinstance(claims, list) or not isinstance(target, dict)
            or target.get("id") != target_id):
        raise C4bError("OB-02 租约接管证据身份或结构无效")
    normalized: list[tuple[str, datetime]] = []
    for claim in claims:
        digest = claim.get("leaseTokenSha256") if isinstance(claim, dict) else None
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise C4bError("OB-02 租约接管 token 摘要无效")
        normalized.append((digest, parse_utc(claim.get("observedAt"),
                                             "OB-02 claim.observedAt")))
    captured_at = parse_utc(value.get("capturedAt"), "OB-02 capturedAt")
    if not require_success:
        return
    if len(normalized) != 2:
        raise C4bError("OB-02 PASS 必须精确记录原租约与一次接管租约")
    first_digest, first_at = normalized[0]
    second_digest, second_at = normalized[1]
    takeover_seconds = int((second_at - first_at).total_seconds())
    lease_expiry = datetime.fromtimestamp(value["leaseUntilEpoch"], timezone.utc)
    lease_duration = (lease_expiry - first_at).total_seconds()
    semantics = (
        first_digest == before_digest and second_digest != before_digest
        and first_at < second_at
        # beforeProbe 用 `extract(epoch from leased_until)::bigint` 投影 epoch，而 PostgreSQL
        # `float::bigint` 是四舍五入（ROUND 而非 TRUNC），会引入 ±0.5 秒投影误差，导致
        # leaseUntilEpoch 最多比真实到期早/晚 1 秒。故对 second_at >= lease_expiry 施加 ±1 秒
        # 容差（与下方 lease_duration 的 29-31 容差一致，见注释「允许数据库小数秒取整造成的 ±1 秒边界」）。
        and second_at >= lease_expiry - timedelta(seconds=1)
        and 29 <= lease_duration <= 31 and captured_at >= second_at
        and 30 <= takeover_seconds <= 60
        and value.get("takeoverSeconds") == takeover_seconds
        and value.get("tokenChanged") is True
        and target.get("status") == "PUBLISHED"
        and target.get("leasePresent") is False
        and facts.get("leaseClaimCount") == len(normalized)
        and facts.get("leaseTakeoverSeconds") == takeover_seconds
        and facts.get("leaseTokenChanged") is True
    )
    if not semantics:
        raise C4bError("OB-02 租约到期、token 变化或最终状态未满足冻结语义")


def validate_terminal_no_reclaim_evidence(
        root: Path, reference: dict[str, Any], facts: dict[str, Any],
        require_success: bool) -> None:
    """硬判 OB-04 target 在故障前已发布且重启后没有第二次领取或终态改写。"""
    value = referenced_json(root, reference, "OB-04 终态不重领证据")
    target_id = value.get("targetId")
    before = value.get("before")
    claims = value.get("claims")
    target = value.get("targetState")
    if (value.get("schemaVersion") != 1 or value.get("scenario") != "OB-04"
            or not isinstance(target_id, str) or not target_id
            or not isinstance(before, dict) or not isinstance(claims, list)
            or not isinstance(target, dict) or target.get("id") != target_id):
        raise C4bError("OB-04 终态不重领证据身份或结构无效")
    normalized: list[tuple[str, datetime]] = []
    for claim in claims:
        digest = claim.get("leaseTokenSha256") if isinstance(claim, dict) else None
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise C4bError("OB-04 target 领取 token 摘要无效")
        normalized.append((digest, parse_utc(claim.get("observedAt"),
                                             "OB-04 claim.observedAt")))
    captured_at = parse_utc(value.get("capturedAt"), "OB-04 capturedAt")
    if not require_success:
        return
    if len(normalized) != 1:
        raise C4bError("OB-04 PASS 必须精确证明 target 只领取一次")
    before_published = parse_utc(before.get("publishedAt"), "OB-04 before.publishedAt")
    final_published = parse_utc(target.get("publishedAt"), "OB-04 target.publishedAt")
    claim_at = normalized[0][1]
    semantics = (
        before.get("status") == "PUBLISHED" and before.get("kafkaRecords") == 1
        and target.get("status") == "PUBLISHED" and target.get("leasePresent") is False
        and target.get("attemptCount") == 0
        and claim_at <= before_published == final_published <= captured_at
        and value.get("publishedAtUnchanged") is True
        and value.get("targetReclaimed") is False
        and facts.get("targetStatusBefore") == before.get("status")
        and facts.get("targetKafkaRecordsBefore") == before.get("kafkaRecords")
        and facts.get("targetLeaseClaimCount") == len(normalized)
        and facts.get("targetPublishedAtUnchanged") is True
        and facts.get("targetReclaimed") is False
    )
    if not semantics:
        raise C4bError("OB-04 target 故障前终态、唯一领取或发布时点未满足冻结语义")


def read_json_object(path: Path, label: str) -> dict[str, Any]:
    """读取严格 JSON 对象；基础证据不能用数组、空文件或宽松解析替代。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise C4bError(f"{label} 不是有效 JSON") from exception
    if not isinstance(value, dict):
        raise C4bError(f"{label} 必须是 JSON 对象")
    return value


def referenced_json(root: Path, reference: Any, label: str) -> dict[str, Any]:
    """解析根目录内的带摘要 JSON 引用，并拒绝路径逃逸或摘要漂移。"""
    if not isinstance(reference, dict):
        raise C4bError(f"{label}引用必须是对象")
    relative = reference.get("path")
    expected = reference.get("sha256")
    if not isinstance(relative, str) or not SHA256_PATTERN.fullmatch(str(expected)):
        raise C4bError(f"{label}引用缺路径或摘要")
    candidate = (root / relative).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError as exception:
        raise C4bError(f"{label}引用越出运行根目录") from exception
    if not candidate.is_file() or sha256(candidate) != expected:
        raise C4bError(f"{label}缺失或摘要漂移")
    return read_json_object(candidate, label)


def validate_consumer_evidence(root: Path, reference: Any, scenario: str,
                               purpose: str) -> None:
    """读取并硬判生产消费组身份、全分区 assignment、零 lag 与场景用途。"""
    value = referenced_json(root, reference, "Kafka 消费就绪证据")
    if (value.get("schemaVersion") != 1 or value.get("scenario") != scenario
            or value.get("purpose") != purpose or value.get("verdict") != "PASS"):
        raise C4bError("Kafka 消费就绪证据身份、用途或裁决不匹配")
    groups = value.get("groups")
    if not isinstance(groups, dict) or set(groups) != set(CONSUMER_REQUIREMENTS):
        raise C4bError("Kafka 消费就绪证据缺少冻结生产组")
    for group, (topic, partitions) in CONSUMER_REQUIREMENTS.items():
        item = groups.get(group)
        parsed = item.get("parsed") if isinstance(item, dict) else None
        if (not isinstance(parsed, dict) or item.get("returnCode") != 0
                or parsed.get("topic") != topic
                or str(parsed.get("state", "")).lower() != "stable"
                or parsed.get("totalLag") != 0
                or parsed.get("assignedPartitions") != list(range(partitions))
                or parsed.get("expectedPartitions") != list(range(partitions))
                or not isinstance(parsed.get("memberIds"), list)
                or not parsed.get("memberIds") or parsed.get("ready") is not True):
            raise C4bError(f"Kafka 消费组未满足冻结就绪语义: {group}")
    if not isinstance(value.get("attempts"), int) or value["attempts"] < 1:
        raise C4bError("Kafka 消费就绪证据缺少有效尝试次数")
    duration = value.get("durationMillis")
    if not isinstance(duration, int) or duration < 0 or duration > 120_000:
        raise C4bError("Kafka 消费就绪证据超出冻结时限")
    parse_utc(value.get("completedAt"), "Kafka 消费就绪 completedAt")


def validate_notification_metric_sample(
        sample: Any, label: str, expected_group: str = "rule-notification",
        expected_topic: str = "tc.rule.notification") -> tuple[dict[str, Any],
                                                                 dict[str, float], datetime]:
    """验证当前 SUT actuator 原始样本，并保留懒注册缺席与真实零值的区别。"""
    if (not isinstance(sample, dict) or set(sample) != {
            "schemaVersion", "source", "endpointPath", "processIdentity", "results", "capturedAt"}
            or sample.get("schemaVersion") != 1 or sample.get("source") != "SUT_ACTUATOR"
            or sample.get("endpointPath") != "/actuator/prometheus"):
        raise C4bError(f"DB-01 通知消费指标 {label} 样本身份无效")
    identity = sample.get("processIdentity")
    if (not isinstance(identity, dict) or set(identity) != {
            "pid", "created", "commandSha256", "jarSha256"}
            or not isinstance(identity.get("pid"), int) or isinstance(identity.get("pid"), bool)
            or identity["pid"] <= 0
            or not isinstance(identity.get("created"), str) or not identity["created"]
            or not isinstance(identity.get("commandSha256"), str)
            or not SHA256_PATTERN.fullmatch(identity["commandSha256"])
            or not isinstance(identity.get("jarSha256"), str)
            or not SHA256_PATTERN.fullmatch(identity["jarSha256"])):
        raise C4bError(f"DB-01 通知消费指标 {label} SUT 进程身份无效")
    results = sample.get("results")
    if not isinstance(results, dict) or set(results) != {"success", "failure"}:
        raise C4bError(f"DB-01 通知消费指标 {label} 结果集合无效")
    numeric: dict[str, float] = {}
    for result, item in results.items():
        raw_samples = item.get("rawSamples") if isinstance(item, dict) else None
        if (not isinstance(item, dict) or set(item) != {"present", "value", "rawSamples"}
                or not isinstance(item.get("present"), bool) or not isinstance(raw_samples, list)
                or any(not isinstance(line, str) or not line for line in raw_samples)):
            raise C4bError(f"DB-01 通知消费指标 {label}/{result} 样本无效")
        value = item.get("value")
        if item["present"]:
            if (len(raw_samples) != 1
                    or not isinstance(value, (int, float)) or isinstance(value, bool)
                    or not math.isfinite(value) or value < 0
                    or not raw_samples[0].startswith(
                        "thingslink_kafka_consumer_result_total{")
                    or f'group="{expected_group}"' not in raw_samples[0]
                    or f'topic="{expected_topic}"' not in raw_samples[0]
                    or f'result="{result}"' not in raw_samples[0]):
                raise C4bError(f"DB-01 通知消费指标 {label}/{result} 原始样本无效")
            raw_value = re.search(r"\}\s+([^\s]+)(?:\s+\d+)?\Z", raw_samples[0])
            try:
                parsed_raw_value = float(raw_value.group(1)) if raw_value is not None else math.nan
            except ValueError as exception:
                raise C4bError(
                    f"DB-01 通知消费指标 {label}/{result} 原始值无效") from exception
            if not math.isfinite(parsed_raw_value) or abs(parsed_raw_value - value) > 1e-9:
                raise C4bError(f"DB-01 通知消费指标 {label}/{result} 原始值不闭合")
            numeric[result] = float(value)
        else:
            if value is not None or raw_samples:
                raise C4bError(f"DB-01 通知消费指标 {label}/{result} 缺席语义无效")
            numeric[result] = 0.0
    captured = parse_utc(sample.get("capturedAt"), f"DB-01 通知消费指标 {label} capturedAt")
    return identity, numeric, captured


def validate_notification_attribution_evidence(
        root: Path, reference: Any, *, require_success: bool) -> None:
    """硬判 DB-01 通知链的目标 offset、消费结果指标与两类冻结 DLQ 增量。"""
    value = referenced_json(root, reference, "DB-01 通知链归因证据")
    if (value.get("schemaVersion") != 3 or value.get("scenario") != "DB-01"
            or value.get("topic") != "tc.rule.notification"
            or value.get("group") != "things-link-rule-notification"
            or value.get("complete") is not True or value.get("probeErrors") != {}):
        raise C4bError("DB-01 通知链归因证据身份或完整性无效")
    source_identity = value.get("sourceIdentity")
    expected_outbox_ids = {"target": value.get("targetId"), "follower": value.get("followerId")}
    delivery_id = expected_outbox_ids["target"]
    if (any(not isinstance(outbox_id, str) or not outbox_id
            for outbox_id in expected_outbox_ids.values())
            or len(set(expected_outbox_ids.values())) != 2):
        raise C4bError("DB-01 通知 target/follower Outbox 身份集合无效")
    outbox_identity = referenced_json(
        root, value.get("outboxIdentityEvidence"), "DB-01 通知 Outbox 身份资格")
    qualified = outbox_identity.get("identities")
    if (outbox_identity.get("schemaVersion") != 1
            or outbox_identity.get("scenario") != "DB-01"
            or outbox_identity.get("topic") != "tc.rule.notification"
            or not isinstance(qualified, dict) or set(qualified) != set(expected_outbox_ids)):
        raise C4bError("DB-01 通知 Outbox 身份资格无效")
    delivery_digest = hashlib.sha256(delivery_id.encode("utf-8")).hexdigest()
    for label, expected_outbox_id in expected_outbox_ids.items():
        identity = qualified.get(label)
        if (not isinstance(identity, dict) or identity.get("outboxId") != expected_outbox_id
                or identity.get("deliveryEventId") != delivery_id
                or identity.get("partitionKeySha256") != delivery_digest
                or identity.get("payloadEventIdSha256") != delivery_digest
                or identity.get("identityMatch") is not True):
            raise C4bError(f"DB-01 通知 {label} Outbox 身份资格不闭合")
    parse_utc(outbox_identity.get("capturedAt"), "DB-01 通知 Outbox 身份 capturedAt")
    records = source_identity.get("records") if isinstance(source_identity, dict) else None
    if (not isinstance(source_identity, dict) or source_identity.get("eventId") != delivery_id
            or not isinstance(records, list) or len(records) < 2
            or any(not isinstance(item, dict)
                   or set(item) != {"partition", "offset", "keySha256",
                                   "payloadEventIdSha256", "keyMatchesPayloadEventId"}
                   or not isinstance(item.get("partition"), int)
                   or isinstance(item.get("partition"), bool)
                   or not 0 <= item["partition"] < 6
                   or not isinstance(item.get("offset"), int)
                   or isinstance(item.get("offset"), bool) or item["offset"] < 0
                   or item.get("keySha256") != delivery_digest
                   or item.get("payloadEventIdSha256") != delivery_digest
                   or item.get("keyMatchesPayloadEventId") is not True
                   for item in records)
            or len({(item["partition"], item["offset"]) for item in records}) != len(records)
            or len({item["partition"] for item in records}) != 1):
        raise C4bError("DB-01 通知同 lane 入口身份或记录位置无效")
    snapshot = value.get("groupSnapshot")
    partitions = snapshot.get("partitions") if isinstance(snapshot, dict) else None
    if (not isinstance(snapshot, dict)
            or snapshot.get("group") != "things-link-rule-notification"
            or snapshot.get("topic") != "tc.rule.notification"
            or str(snapshot.get("state", "")).lower() != "stable"
            or not isinstance(snapshot.get("members"), int) or snapshot["members"] <= 0
            or not isinstance(snapshot.get("totalLag"), int) or snapshot["totalLag"] < 0
            or not isinstance(partitions, list) or len(partitions) != 6
            or sorted(item.get("partition") for item in partitions
                      if isinstance(item, dict)) != list(range(6))):
        raise C4bError("DB-01 通知消费组 offset 快照无效")
    for item in partitions:
        if (not isinstance(item.get("logStartOffset"), int)
                or not isinstance(item.get("logEndOffset"), int)
                or item["logStartOffset"] < 0
                or item["logEndOffset"] < item["logStartOffset"]
                or (item.get("currentOffset") is not None
                    and (not isinstance(item["currentOffset"], int)
                         or item["currentOffset"] < 0))
                or (item.get("lag") is not None
                    and (not isinstance(item["lag"], int) or item["lag"] < 0))
                or not isinstance(item.get("memberId"), str)
                or item["memberId"] in ("", "-")):
            raise C4bError("DB-01 通知消费组分区 offset 语义无效")
    partition_offsets = {item["partition"]: item for item in partitions}
    for record in records:
        partition = partition_offsets[record["partition"]]
        if not partition["logStartOffset"] <= record["offset"] < partition["logEndOffset"]:
            raise C4bError("DB-01 通知身份记录超出消费组 Topic 水位")
    target_committed_from_snapshot = all(
        isinstance(partition_offsets[record["partition"]]["currentOffset"], int)
        and partition_offsets[record["partition"]]["currentOffset"] > record["offset"]
        for record in records)
    before = value.get("metricsBefore")
    after = value.get("metricsAfter")
    delta = value.get("metricDelta")
    before_identity, before_values, before_at = validate_notification_metric_sample(
        before, "before")
    after_identity, after_values, after_at = validate_notification_metric_sample(after, "after")
    if before_identity != after_identity:
        raise C4bError("DB-01 通知消费指标 before/after 不属于同一 SUT 进程")
    if after_at < before_at:
        raise C4bError("DB-01 通知消费指标采样时间倒序")
    if (not isinstance(delta, dict) or set(delta) != {"success", "failure"}
            or any(not isinstance(number, (int, float)) or isinstance(number, bool)
                   or not math.isfinite(number) or number < 0 for number in delta.values())):
        raise C4bError("DB-01 通知消费指标 delta 无效")
    for name in ("success", "failure"):
        before_item = before["results"][name]
        after_item = after["results"][name]
        if before_item["present"] and not after_item["present"]:
            raise C4bError(f"DB-01 通知消费指标 {name} 已注册序列不得消失")
        if after_values[name] < before_values[name]:
            raise C4bError(f"DB-01 通知消费指标 {name} 非单调")
    if any(abs((after_values[name] - before_values[name]) - delta[name]) > 1e-9
           for name in ("success", "failure")):
        raise C4bError("DB-01 通知消费指标差值不闭合")
    dlq = value.get("dlq")
    if not isinstance(dlq, dict) or set(dlq) != {"tc.dlq", "tc.rule.dlq"}:
        raise C4bError("DB-01 通知链缺少冻结 DLQ 集合")
    target_dlq_count = 0
    for topic, item in dlq.items():
        target_records = item.get("targetRecords") if isinstance(item, dict) else None
        if (not isinstance(item, dict)
                or any(not isinstance(item.get(name), int) or isinstance(item.get(name), bool)
                       or item[name] < 0 for name in ("beforeTotal", "afterTotal"))
                or item.get("totalDelta") != item["afterTotal"] - item["beforeTotal"]
                or item["totalDelta"] < 0 or not isinstance(target_records, list)
                or any(not isinstance(record, dict)
                       or not isinstance(record.get("partition"), int)
                       or not isinstance(record.get("offset"), int)
                       for record in target_records)):
            raise C4bError(f"DB-01 通知链 DLQ 证据无效: {topic}")
        target_dlq_count += len(target_records)
    delivery = value.get("deliveryFactCount")
    committed = value.get("targetOffsetsCommitted")
    if (not isinstance(delivery, int) or isinstance(delivery, bool) or delivery not in (0, 1)
            or not isinstance(committed, bool)
            or committed is not target_committed_from_snapshot):
        raise C4bError("DB-01 通知链业务事实或 committed 判定无效")
    expected_outcome = ("DURABLE_FACT" if delivery == 1 else "DLQ" if target_dlq_count > 0
                        else "COMMITTED_WITHOUT_DURABLE_FACT" if committed else "UNCOMMITTED")
    if value.get("offsetOutcome") != expected_outcome:
        raise C4bError("DB-01 通知链 offset/recoverer 结果推导不闭合")
    if require_success and (delivery != 1 or committed is not True
                            or target_dlq_count != 0
                            or any(item["totalDelta"] != 0 for item in dlq.values())):
        raise C4bError("DB-01 PASS 的通知事实、offset 或 DLQ 语义未通过")
    parse_utc(value.get("capturedAt"), "DB-01 通知链 capturedAt")


def validate_uplink_attribution_evidence(
        root: Path, reference: Any, *, require_success: bool) -> None:
    """硬判 DB-01 normalized 目标 source offset、committed、inbox 与统一 DLQ 闭包。"""
    value = referenced_json(root, reference, "DB-01 normalized 归因证据")
    message_id = value.get("targetMessageId")
    if (value.get("schemaVersion") != 2 or value.get("scenario") != "DB-01"
            or value.get("topic") != "tc.device.uplink.normalized"
            or value.get("group") != "things-link-ingestion-normalized"
            or not isinstance(message_id, str) or not message_id
            or value.get("complete") is not True or value.get("probeErrors") != {}):
        raise C4bError("DB-01 normalized 归因证据身份或完整性无效")
    locations = value.get("sourceLocations")
    if (not isinstance(locations, list) or len(locations) != 1
            or any(not isinstance(item, dict)
                   or set(item) != {"partition", "offset"}
                   or not isinstance(item.get("partition"), int)
                   or isinstance(item.get("partition"), bool)
                   or not 0 <= item["partition"] < 12
                   or not isinstance(item.get("offset"), int)
                   or isinstance(item.get("offset"), bool) or item["offset"] < 0
                   for item in locations)):
        raise C4bError("DB-01 normalized 目标记录位置无效")
    snapshot = value.get("groupSnapshot")
    partitions = snapshot.get("partitions") if isinstance(snapshot, dict) else None
    if (not isinstance(snapshot, dict)
            or snapshot.get("group") != "things-link-ingestion-normalized"
            or snapshot.get("topic") != "tc.device.uplink.normalized"
            or str(snapshot.get("state", "")).lower() != "stable"
            or not isinstance(snapshot.get("members"), int) or snapshot["members"] <= 0
            or not isinstance(snapshot.get("totalLag"), int) or snapshot["totalLag"] < 0
            or not isinstance(partitions, list) or len(partitions) != 12
            or sorted(item.get("partition") for item in partitions
                      if isinstance(item, dict)) != list(range(12))):
        raise C4bError("DB-01 normalized 消费组 offset 快照无效")
    for item in partitions:
        if (not isinstance(item.get("logStartOffset"), int)
                or not isinstance(item.get("logEndOffset"), int)
                or item["logStartOffset"] < 0
                or item["logEndOffset"] < item["logStartOffset"]
                or (item.get("currentOffset") is not None
                    and (not isinstance(item["currentOffset"], int)
                         or item["currentOffset"] < 0))
                or (item.get("lag") is not None
                    and (not isinstance(item["lag"], int) or item["lag"] < 0))
                or not isinstance(item.get("memberId"), str)
                or item["memberId"] in ("", "-")):
            raise C4bError("DB-01 normalized 消费组分区 offset 语义无效")
    partition_offsets = {item["partition"]: item for item in partitions}
    location = locations[0]
    partition = partition_offsets[location["partition"]]
    if not partition["logStartOffset"] <= location["offset"] < partition["logEndOffset"]:
        raise C4bError("DB-01 normalized 目标记录超出消费组 Topic 水位")
    committed_from_snapshot = (
        isinstance(partition["currentOffset"], int)
        and partition["currentOffset"] > location["offset"])
    before = value.get("metricsBefore")
    after = value.get("metricsAfter")
    delta = value.get("metricDelta")
    before_identity, before_values, before_at = validate_notification_metric_sample(
        before, "normalized-before", "ingestion-normalized", "tc.device.uplink.normalized")
    after_identity, after_values, after_at = validate_notification_metric_sample(
        after, "normalized-after", "ingestion-normalized", "tc.device.uplink.normalized")
    if before_identity != after_identity or after_at < before_at:
        raise C4bError("DB-01 normalized 指标跨进程或采样时间倒退")
    if (not isinstance(delta, dict) or set(delta) != {"success", "failure"}
            or any(not isinstance(number, (int, float)) or isinstance(number, bool)
                   or not math.isfinite(number) or number < 0 for number in delta.values())):
        raise C4bError("DB-01 normalized 消费指标 delta 无效")
    if any(after_values[name] < before_values[name]
           or abs((after_values[name] - before_values[name]) - delta[name]) > 1e-9
           for name in ("success", "failure")):
        raise C4bError("DB-01 normalized 消费指标不单调或差值不闭合")
    dlq = value.get("dlq")
    target_records = dlq.get("targetRecords") if isinstance(dlq, dict) else None
    if (not isinstance(dlq, dict) or dlq.get("topic") != "tc.dlq"
            or any(not isinstance(dlq.get(name), int) or isinstance(dlq.get(name), bool)
                   or dlq[name] < 0 for name in ("beforeTotal", "afterTotal"))
            or dlq.get("totalDelta") != dlq["afterTotal"] - dlq["beforeTotal"]
            or dlq["totalDelta"] < 0 or not isinstance(target_records, list)
            or any(not isinstance(item, dict)
                   or set(item) != {"partition", "offset"}
                   or not isinstance(item.get("partition"), int)
                   or not isinstance(item.get("offset"), int)
                   for item in target_records)):
        raise C4bError("DB-01 normalized DLQ 证据无效")
    inbox_count = value.get("inboxFactCount")
    committed = value.get("targetOffsetsCommitted")
    if (not isinstance(inbox_count, int) or isinstance(inbox_count, bool)
            or inbox_count not in (0, 1) or not isinstance(committed, bool)
            or committed is not committed_from_snapshot):
        raise C4bError("DB-01 normalized inbox 或 committed 判定无效")
    expected_outcome = ("DURABLE_FACT" if inbox_count == 1 else "DLQ" if target_records
                        else "COMMITTED_WITHOUT_DURABLE_FACT" if committed else "UNCOMMITTED")
    if value.get("offsetOutcome") != expected_outcome:
        raise C4bError("DB-01 normalized offset/recoverer 结果推导不闭合")
    if require_success and (inbox_count != 1 or committed is not True or target_records):
        raise C4bError("DB-01 PASS 的 normalized inbox、offset 或 DLQ 语义未通过")
    parse_utc(value.get("capturedAt"), "DB-01 normalized capturedAt")


def validate_rule_chain_evidence(
        root: Path, reference: Any, facts: dict[str, Any], *, require_success: bool) -> None:
    """硬判 RW-01～04 的冻结身份、预算包含关系、降级归因与 receipt 计数一致。"""
    value = referenced_json(root, reference, "RW 规则恢复链证据")
    scenario = value.get("scenario")
    observed = value.get("lastObserved")
    budget = value.get("budget")
    identity_names = ("messageId", "projectId", "deviceId", "ruleId", "ruleVersionId")
    if (value.get("schemaVersion") != 2 or scenario not in {"RW-01", "RW-02", "RW-03", "RW-04"}
            or any(not isinstance(value.get(name), str) or not value[name]
                   for name in identity_names)
            or not SHA256_PATTERN.fullmatch(str(value.get("sourceSha256")))
            or not isinstance(value.get("attempts"), int) or value["attempts"] <= 0
            or value.get("verdict") not in ("PASS", "FAIL", "IN_PROGRESS")
            or not isinstance(budget, dict)
            or budget.get("innerRecoverySeconds") != 180
            or budget.get("outerAfterProbeSeconds") != 300
            or budget.get("attributionReserveSeconds") != 120
            or budget["outerAfterProbeSeconds"]
            != budget["innerRecoverySeconds"] + budget["attributionReserveSeconds"]
            or not isinstance(observed, dict)):
        raise C4bError("RW 规则恢复链证据身份或结构无效")
    parse_utc(value.get("startedAt"), "RW 规则恢复链 startedAt")
    parse_utc(value.get("completedAt"), "RW 规则恢复链 completedAt")
    parse_utc(observed.get("capturedAt"), "RW 规则恢复链 capturedAt")
    count_names = (
        "receiptCount", "scriptSuccessCount", "processedInboxCount", "propertyPointCount",
        "notificationOutboxCount", "sideEffectCount", "qualifiedActionVersionCount",
        "shadowTemperaturePresentCount", "messageLogCount")
    if (any(not isinstance(observed.get(name), int) or isinstance(observed.get(name), bool)
            or observed[name] < 0 for name in count_names)
            or not isinstance(observed.get("receiptStatus"), str)):
        raise C4bError("RW 规则恢复链计数或回执状态无效")
    complete = all((
        observed["receiptCount"] == 1, observed["receiptStatus"] == "COMPLETED",
        observed["scriptSuccessCount"] == 1, observed["processedInboxCount"] == 1,
        observed["propertyPointCount"] == 1, observed["notificationOutboxCount"] == 1,
        observed["sideEffectCount"] == 1, observed["qualifiedActionVersionCount"] == 1,
        observed["shadowTemperaturePresentCount"] == 1, observed["messageLogCount"] == 1))
    for name in ("scriptSuccessCount", "processedInboxCount", "propertyPointCount",
                 "notificationOutboxCount", "sideEffectCount"):
        if name in facts and facts[name] != observed[name]:
            raise C4bError("RW receipt 与规则恢复链计数不一致")
    if require_success and (value.get("verdict") != "PASS" or not complete):
        raise C4bError("RW PASS 的规则恢复链没有完整收敛")
    degradation = value.get("degradationAttribution")
    # 正常 PASS 可能在第一次轮询即闭合，不强制制造降级诊断；只要出现 inbox 已接管但点位缺失，
    # 周期证据就必须把额度与 processed offset 闭合，失败时不能再依赖人工 SQL。
    needs_degradation = (observed["processedInboxCount"] == 1
                         and observed["propertyPointCount"] == 0)
    if needs_degradation:
        validate_rule_degradation_attribution(degradation)
    elif degradation is not None:
        validate_rule_degradation_attribution(degradation)


def validate_rule_replay_metric_evidence(
        root: Path, reference: Any, *, require_success: bool) -> dict[str, Any]:
    """硬判 RW-04 重启后当前 SUT 原始样本，拒绝 Prometheus 缓存与跨进程差值。"""
    value = referenced_json(root, reference, "RW-04 重放指标证据")
    expected_keys = {"stage": "engine", "result": "replayed"}
    identity = value.get("processIdentity")
    sample = value.get("sample")
    raw_samples = sample.get("rawSamples") if isinstance(sample, dict) else None
    if (value.get("schemaVersion") != 1 or value.get("scenario") != "RW-04"
            or value.get("source") != "SUT_ACTUATOR"
            or value.get("endpointPath") != "/actuator/prometheus"
            or value.get("metricName") != "thingslink_rule_execution_seconds_count"
            or value.get("labels") != expected_keys
            or not isinstance(value.get("attempts"), int) or isinstance(value.get("attempts"), bool)
            or value["attempts"] < 1 or value.get("verdict") not in ("PASS", "FAIL", "IN_PROGRESS")
            or not isinstance(identity, dict) or set(identity) != {
                "pid", "created", "commandSha256", "jarSha256"}
            or not isinstance(identity.get("pid"), int) or isinstance(identity.get("pid"), bool)
            or identity["pid"] <= 0 or not isinstance(identity.get("created"), str)
            or not identity["created"]
            or not SHA256_PATTERN.fullmatch(str(identity.get("commandSha256")))
            or not SHA256_PATTERN.fullmatch(str(identity.get("jarSha256")))
            or not isinstance(sample, dict) or set(sample) != {"present", "value", "rawSamples"}
            or not isinstance(sample.get("present"), bool) or not isinstance(raw_samples, list)):
        raise C4bError("RW-04 重放指标证据身份或结构无效")
    parse_utc(value.get("startedAt"), "RW-04 重放指标 startedAt")
    parse_utc(value.get("capturedAt"), "RW-04 重放指标 capturedAt")
    metric_value = sample.get("value")
    if sample["present"]:
        if (len(raw_samples) != 1 or not isinstance(raw_samples[0], str)
                or not raw_samples[0].startswith("thingslink_rule_execution_seconds_count{")
                or 'stage="engine"' not in raw_samples[0]
                or 'result="replayed"' not in raw_samples[0]
                or not isinstance(metric_value, (int, float)) or isinstance(metric_value, bool)
                or not math.isfinite(metric_value) or metric_value < 0):
            raise C4bError("RW-04 重放指标原始样本无效")
        raw_value = re.search(r"\}\s+([^\s]+)(?:\s+\d+)?\Z", raw_samples[0])
        try:
            parsed = float(raw_value.group(1)) if raw_value is not None else math.nan
        except ValueError as exception:
            raise C4bError("RW-04 重放指标原始值无效") from exception
        if not math.isfinite(parsed) or abs(parsed - metric_value) > 1e-9:
            raise C4bError("RW-04 重放指标原始值不闭合")
    elif metric_value is not None or raw_samples:
        raise C4bError("RW-04 重放指标缺席语义无效")
    if require_success and (value.get("verdict") != "PASS"
                            or sample.get("present") is not True or metric_value < 1):
        raise C4bError("RW-04 PASS 缺少当前 SUT replayed 样本")
    return identity


def validate_rule_degradation_attribution(value: Any) -> None:
    """复算三项 quota 状态和 processed 目标 committed，拒绝自报“降级”布尔值。"""
    if (not isinstance(value, dict) or value.get("complete") is not True
            or value.get("probeErrors") != {}):
        raise C4bError("RW 点位降级归因没有完整闭合")
    parse_utc(value.get("capturedAt"), "RW 点位降级 capturedAt")
    quota = value.get("quotaDecisions")
    if not isinstance(quota, list) or len(quota) != 3:
        raise C4bError("RW 点位降级缺少三项额度决策")
    expected_metrics = {"UPLINK_MESSAGE", "UPLINK_BYTES", "TIME_SERIES_POINT"}
    calculated_degraded = False
    seen: set[str] = set()
    for item in quota:
        if not isinstance(item, dict) or item.get("metric") not in expected_metrics:
            raise C4bError("RW 点位降级额度指标无效")
        metric = item["metric"]
        if metric in seen:
            raise C4bError("RW 点位降级额度指标重复")
        seen.add(metric)
        limit = item.get("limit")
        used = item.get("tenantUsed")
        soft = item.get("softLimitBasisPoints")
        degrade = item.get("degradeBasisPoints")
        if ((limit is not None and (not isinstance(limit, int) or isinstance(limit, bool)
                                    or limit < 0))
                or not isinstance(used, int) or isinstance(used, bool) or used < 0
                or not isinstance(soft, int) or isinstance(soft, bool) or not 0 <= soft <= 10000
                or not isinstance(degrade, int) or isinstance(degrade, bool) or degrade < 10000):
            raise C4bError("RW 点位降级额度数值无效")
        if limit is None:
            status = "NORMAL"
        elif limit == 0:
            status = "DEGRADED" if used > 0 else "HARD_LIMIT"
        elif used * 10000 >= limit * degrade:
            status = "DEGRADED"
        elif used >= limit:
            status = "HARD_LIMIT"
        elif used * 10000 >= limit * soft:
            status = "SOFT_LIMIT"
        else:
            status = "NORMAL"
        if item.get("status") != status:
            raise C4bError("RW 点位降级额度状态无法由原始事实复算")
        calculated_degraded = calculated_degraded or status == "DEGRADED"
    if seen != expected_metrics or value.get("historicalStorageDegraded") is not calculated_degraded:
        raise C4bError("RW 历史存储降级汇总与三项额度不一致")
    processed = value.get("processed")
    if (not isinstance(processed, dict)
            or processed.get("topic") != "tc.device.uplink.processed"
            or processed.get("group") != "things-link-ingestion-processed"):
        raise C4bError("RW processed 归因身份无效")
    locations = processed.get("sourceLocations")
    snapshot = processed.get("groupSnapshot")
    partitions = snapshot.get("partitions") if isinstance(snapshot, dict) else None
    if (not isinstance(locations, list) or not locations
            or not isinstance(snapshot, dict) or snapshot.get("state", "").lower() != "stable"
            or snapshot.get("topic") != processed["topic"]
            or snapshot.get("group") != processed["group"]
            or not isinstance(snapshot.get("members"), int) or snapshot["members"] <= 0
            or not isinstance(snapshot.get("totalLag"), int) or snapshot["totalLag"] < 0
            or not isinstance(partitions, list) or len(partitions) != 12
            or any(not isinstance(item, dict)
                   or not isinstance(item.get("partition"), int)
                   or isinstance(item.get("partition"), bool) for item in partitions)
            or sorted(item["partition"] for item in partitions) != list(range(12))
            or any(not isinstance(item.get("memberId"), str) or not item["memberId"]
                   or item["memberId"] == "-" for item in partitions if isinstance(item, dict))):
        raise C4bError("RW processed 目标位置或消费组快照无效")
    offsets = {item.get("partition"): item.get("currentOffset") for item in partitions
               if isinstance(item, dict)}
    committed = all(
        isinstance(item, dict) and isinstance(item.get("partition"), int)
        and isinstance(item.get("offset"), int)
        and isinstance(offsets.get(item["partition"]), int)
        and offsets[item["partition"]] > item["offset"] for item in locations)
    if processed.get("targetOffsetsCommitted") is not committed:
        raise C4bError("RW processed committed 无法由目标位置与组快照复算")


def validate_phase_evidence(root: Path, receipt: dict[str, Any]) -> None:
    """校验 receipt 引用的阶段证据存在且摘要匹配；PASS 必须覆盖全部五阶段。"""
    references = receipt.get("phaseEvidence", {})
    if not isinstance(references, dict):
        raise C4bError("phaseEvidence 必须是对象")
    if receipt.get("verdict") == "PASS" and set(references) != set(PHASES):
        raise C4bError("PASS receipt 必须引用全部五阶段证据")
    for phase, reference in references.items():
        if phase not in PHASES or not isinstance(reference, dict):
            raise C4bError("阶段证据引用非法")
        phase_value = referenced_json(root, reference, "阶段证据")
        if (phase_value.get("scenario") != receipt.get("scenario")
                or phase_value.get("phase") != phase
                or phase_value.get("status") not in ("PASS", "FAIL", "SKIPPED_EMPTY")):
            raise C4bError("receipt 引用了身份或状态无效的阶段证据")
        if (receipt.get("verdict") == "PASS"
                and phase_value.get("status") not in ("PASS", "SKIPPED_EMPTY")):
            raise C4bError("PASS receipt 引用了失败的阶段证据")
    readiness = receipt.get("consumerReadinessEvidence")
    if receipt.get("verdict") == "PASS" and not isinstance(readiness, dict):
        raise C4bError("PASS receipt 缺少 Kafka 消费就绪证据")
    if readiness is not None:
        validate_consumer_evidence(root, readiness, str(receipt.get("scenario")), "fixture")
    settled = receipt.get("consumerSettledEvidence")
    if receipt.get("verdict") == "PASS" and not isinstance(settled, dict):
        raise C4bError("PASS receipt 缺少场景终态消费组证据")
    if settled is not None:
        validate_consumer_evidence(root, settled, str(receipt.get("scenario")), "settled")
    attribution = receipt.get("notificationAttributionEvidence")
    failed_notification = root / "recovery" / "DB-01" / "notification-delivery.json"
    if receipt.get("scenario") == "DB-01" and (
            receipt.get("verdict") == "PASS" or failed_notification.is_file()):
        if not isinstance(attribution, dict):
            raise C4bError("DB-01 通知事实阶段已执行但缺少可归因证据")
        validate_notification_attribution_evidence(
            root, attribution, require_success=receipt.get("verdict") == "PASS")
    elif attribution is not None:
        raise C4bError("非 DB-01 场景不得引用通知链归因证据")
    uplink_attribution = receipt.get("uplinkAttributionEvidence")
    failed_uplink = root / "recovery" / "DB-01" / "inbox-persisted.json"
    if receipt.get("scenario") == "DB-01" and (
            receipt.get("verdict") == "PASS" or failed_uplink.is_file()):
        if not isinstance(uplink_attribution, dict):
            raise C4bError("DB-01 inbox 阶段已执行但缺少 normalized 可归因证据")
        validate_uplink_attribution_evidence(
            root, uplink_attribution, require_success=receipt.get("verdict") == "PASS")
        if isinstance(attribution, dict):
            notification_value = referenced_json(root, attribution, "DB-01 通知链归因证据")
            uplink_value = referenced_json(root, uplink_attribution, "DB-01 normalized 归因证据")
            notification_identity = notification_value.get("metricsBefore", {}).get(
                "processIdentity")
            normalized_identity = uplink_value.get("metricsBefore", {}).get("processIdentity")
            if notification_identity != normalized_identity:
                raise C4bError("DB-01 normalized 与通知指标未绑定同一场景 SUT")
    elif uplink_attribution is not None:
        raise C4bError("非 DB-01 场景不得引用 normalized 归因证据")
    outbox_lane = receipt.get("outboxLaneEvidence")
    failed_outbox_lane = root / "attribution" / "OB-01" / "outbox-lane.json"
    after_probe_executed = isinstance(references.get("afterProbe"), dict)
    if receipt.get("scenario") == "OB-01" and (
            receipt.get("verdict") == "PASS"
            or (after_probe_executed and failed_outbox_lane.is_file())):
        if not isinstance(outbox_lane, dict):
            raise C4bError("OB-01 afterProbe 已执行但缺少精确 lane 证据")
        validate_outbox_lane_evidence(
            root, outbox_lane, require_success=receipt.get("verdict") == "PASS")
    elif outbox_lane is not None:
        raise C4bError("非 OB-01 场景不得引用 Outbox lane 归因证据")
    lease_takeover = receipt.get("leaseTakeoverEvidence")
    failed_lease_takeover = root / "attribution" / "OB-02" / "lease-takeover.json"
    if receipt.get("scenario") == "OB-02" and (
            receipt.get("verdict") == "PASS"
            or (after_probe_executed and failed_lease_takeover.is_file())):
        if not isinstance(lease_takeover, dict):
            raise C4bError("OB-02 afterProbe 已执行但缺少租约接管证据")
        validate_lease_takeover_evidence(
            root, lease_takeover, receipt.get("facts", {}),
            require_success=receipt.get("verdict") == "PASS")
    elif lease_takeover is not None:
        raise C4bError("非 OB-02 场景不得引用租约接管证据")
    terminal_no_reclaim = receipt.get("terminalNoReclaimEvidence")
    failed_terminal = root / "attribution" / "OB-04" / "terminal-no-reclaim.json"
    if receipt.get("scenario") == "OB-04" and (
            receipt.get("verdict") == "PASS"
            or (after_probe_executed and failed_terminal.is_file())):
        if not isinstance(terminal_no_reclaim, dict):
            raise C4bError("OB-04 afterProbe 已执行但缺少终态不重领证据")
        validate_terminal_no_reclaim_evidence(
            root, terminal_no_reclaim, receipt.get("facts", {}),
            require_success=receipt.get("verdict") == "PASS")
    elif terminal_no_reclaim is not None:
        raise C4bError("非 OB-04 场景不得引用终态不重领证据")
    rule_chain = receipt.get("ruleChainEvidence")
    failed_rule_chain = root / "attribution" / str(receipt.get("scenario")) / "rule-chain.json"
    if receipt.get("scenario") in {"RW-01", "RW-02", "RW-03", "RW-04"} and (
            receipt.get("verdict") == "PASS"
            or (after_probe_executed and failed_rule_chain.is_file())):
        if not isinstance(rule_chain, dict):
            raise C4bError("RW afterProbe 已执行但缺少规则恢复链证据")
        validate_rule_chain_evidence(
            root, rule_chain, receipt.get("facts", {}),
            require_success=receipt.get("verdict") == "PASS")
    elif rule_chain is not None:
        raise C4bError("非 RW-01～04 场景不得引用规则恢复链证据")
    replay_metric = receipt.get("facts", {}).get("replayMetricEvidence")
    failed_replay_metric = root / "attribution" / "RW-04" / "rule-replay-metric.json"
    if receipt.get("scenario") == "RW-04" and (
            receipt.get("verdict") == "PASS"
            or (after_probe_executed and failed_replay_metric.is_file())):
        if not isinstance(replay_metric, dict):
            raise C4bError("RW-04 afterProbe 已执行但缺少当前 SUT 重放指标证据")
        validate_rule_replay_metric_evidence(
            root, replay_metric, require_success=receipt.get("verdict") == "PASS")
    elif replay_metric is not None:
        raise C4bError("非 RW-04 场景不得引用重放指标证据")


def capture_environment(root: Path, project: str,
                        run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run,
                        java_command: str = "java") -> dict[str, Any]:
    """采集 Docker/JDK 和隔离资源 ID；敏感环境变量只记录存在性。"""
    if not PROJECT_PATTERN.fullmatch(project):
        raise C4bError("非法 Compose project")

    def output(command: list[str]) -> str:
        completed = run(command, capture_output=True, text=True, check=False)
        if completed.returncode != 0:
            raise C4bError(f"环境采集失败: {command[0]}")
        # java -version 等工具按惯例写 stderr；环境证据必须保留两条流，不能把空 stdout 当成功采集。
        return "\n".join(part.strip() for part in (completed.stdout, completed.stderr)
                          if part and part.strip())

    resources = {}
    for kind in ("container", "volume", "network"):
        resources[kind] = output(["docker", kind, "ls", "-q", "--filter",
                                 f"label=com.docker.compose.project={project}"]).splitlines()
    image_digests = {}
    for container_id in resources["container"]:
        image = output(["docker", "inspect", "--format", "{{.Image}}", container_id])
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image):
            raise C4bError("隔离容器镜像摘要无效")
        image_digests[container_id] = image
    manifest = read_json_object(root / "run-manifest.json", "run-manifest.json")
    environment = {"schemaVersion": 1, "runId": manifest.get("runId"), "project": project,
                   "os": {"name": os.name, "platform": sys.platform,
                          "description": platform.platform()},
                   "dockerClientServer": output(["docker", "version"]),
                   "jdk": output([java_command, "-version"]), "resources": resources,
                   "imageDigests": image_digests,
                   "secretPresence": {name: bool(os.environ.get(name)) for name in
                                      ("POSTGRES_PASSWORD", "REDIS_PASSWORD", "MINIO_ROOT_PASSWORD")},
                   "capturedAt": utc_now()}
    write_json(root / "environment.json", environment)
    return environment


def write_probe_snapshot(root: Path, name: str, facts: dict[str, Any]) -> None:
    """保存只含稳定 ID、计数和摘要的数据库探针快照。"""
    if name not in ("database-before", "database-after"):
        raise C4bError("未知数据库快照名称")
    forbidden = {"payload", "password", "token", "secret"}
    if any(key.lower() in forbidden for key in facts):
        raise C4bError("数据库快照包含禁止字段")
    manifest = read_json_object(root / "run-manifest.json", "run-manifest.json")
    write_json(root / f"{name}.json", {
        "schemaVersion": 1, "runId": manifest.get("runId"),
        "project": manifest.get("project"), "snapshot": name,
        "facts": facts, "capturedAt": utc_now(),
    })


def write_prometheus_alerts(root: Path, firing: dict[str, Any],
                            resolved: dict[str, Any]) -> None:
    """如实保存 DB-01 数据库不可用与恢复信号，不从日志文字推断告警。"""
    manifest = read_json_object(root / "run-manifest.json", "run-manifest.json")
    write_json(root / "prometheus-alerts.json", {
        "schemaVersion": 1, "runId": manifest.get("runId"),
        "project": manifest.get("project"),
        "rule": "ThingsLinkDatabaseUnavailable", "firing": firing,
        "resolved": resolved, "capturedAt": utc_now(),
    })


def query_prometheus(base_url: str, expression: str, timeout: int = 10) -> dict[str, Any]:
    """从真实 Prometheus HTTP API 查询瞬时状态，并保留原始 result。"""
    url = base_url.rstrip("/") + "/api/v1/query?" + urllib.parse.urlencode({"query": expression})
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            value = json.loads(response.read())
    except Exception as exception:
        raise C4bError("Prometheus 查询失败") from exception
    if value.get("status") != "success" or value.get("data", {}).get("resultType") != "vector":
        raise C4bError("Prometheus 未返回成功向量")
    return value


def wait_checkpoint(root: Path, scenario: str, pid: int,
                    timeout: float = 60.0, sleep: Callable[[float], None] = time.sleep) -> dict[str, Any]:
    """等待原子发布的唯一 REACHED；旧式空文件或未完成尾行只在预算内重试。"""
    if scenario not in SCENARIOS:
        raise C4bError("未知场景")
    deadline = time.monotonic() + timeout
    events = root / "control" / scenario / "events.jsonl"
    while time.monotonic() < deadline:
        if events.exists() and not events.is_file():
            raise C4bError("checkpoint REACHED 路径不是普通文件")
        if events.is_file():
            try:
                content = events.read_text(encoding="utf-8")
            except UnicodeDecodeError as exception:
                raise C4bError("checkpoint REACHED 不是有效 UTF-8") from exception
            # F22 writer 以原子 move 发布；这里保留有界兼容，避免旧 writer 刚创建路径时被误判。
            if not content or not content.endswith("\n"):
                sleep(0.05)
                continue
            lines = [line for line in content.splitlines() if line.strip()]
            if len(lines) != 1:
                raise C4bError("checkpoint REACHED 必须且只能出现一次")
            try:
                record = json.loads(lines[0])
            except json.JSONDecodeError as exception:
                raise C4bError("checkpoint REACHED 完整记录不是合法 JSON") from exception
            if not isinstance(record, dict) or record.get("event") != "REACHED":
                raise C4bError("checkpoint REACHED 事件类型无效")
            expected = (scenario, SCENARIOS[scenario], pid)
            actual = (record.get("scenario"), record.get("checkpoint"), record.get("pid"))
            if actual != expected:
                raise C4bError("checkpoint 身份与当前场景或 SUT PID 不一致")
            return record
        sleep(0.05)
    raise C4bError("预算内未取得完整冻结 checkpoint")


def cleanup(root: Path, manifest: dict[str, Any], compose_files: list[Path],
            shared_before: dict[str, Any] | None = None, ports: list[int] | None = None,
            sut_pid: int | None = None,
            run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run, *,
            environment: dict[str, str] | None = None, env_file: Path | None = None,
            profiles: tuple[str, ...] = ("obs", "init")) -> dict[str, Any]:
    """只对 manifest 中精确 project 执行幂等 Compose down，并核对标签残留为零。"""
    project = manifest.get("project", "")
    if not PROJECT_PATTERN.fullmatch(project) or project != project_for(manifest.get("runId", "")):
        raise C4bError("manifest project 不满足精确清理合同")
    command = ["docker", "compose"]
    for item in compose_files:
        command.extend(["-f", str(item.resolve())])
    if env_file:
        command.extend(["--env-file", str(env_file.resolve())])
    for profile in profiles:
        command.extend(["--profile", profile])
    command.extend(["-p", project, "down", "--volumes", "--remove-orphans"])
    down = run(command, env=environment, capture_output=True, text=True, check=False)
    remaining: dict[str, list[str]] = {}
    query_errors: list[str] = []
    for kind in ("container", "volume", "network"):
        listed = run(["docker", kind, "ls", "-q", "--filter",
                      f"label=com.docker.compose.project={project}"],
                     capture_output=True, text=True, check=False)
        if listed.returncode != 0:
            query_errors.append(kind)
        remaining[kind] = [line for line in listed.stdout.splitlines() if line]
    shared_after = snapshot_shared_resources(run) if shared_before is not None else None
    port_status = wait_ports_released(ports or [])
    ports_released = all(port_status.values())
    pid_gone = sut_pid is None or not process_exists(sut_pid)
    shared_unchanged = shared_before is None or shared_before == shared_after
    shared_changes = {}
    if shared_before is not None and shared_after is not None:
        shared_changes = {kind: {
            "removed": sorted(set(shared_before.get(kind, [])) - set(shared_after.get(kind, []))),
            "added": sorted(set(shared_after.get(kind, [])) - set(shared_before.get(kind, []))),
        } for kind in ("container", "volume", "network")
            if set(shared_before.get(kind, [])) != set(shared_after.get(kind, []))}
    receipt = {"project": project, "downCommand": command, "downExitCode": down.returncode,
               "remaining": remaining, "resourceQueryErrors": query_errors,
               "portsReleased": ports_released, "portRelease": port_status,
               "sutPidGone": pid_gone,
               "sharedUnchanged": shared_unchanged, "sharedChanges": shared_changes,
               "verdict": "PASS" if down.returncode == 0
               and not query_errors and not any(remaining.values()) and ports_released
               and pid_gone and shared_unchanged
               else "FAIL", "completedAt": utc_now()}
    write_json(root / "cleanup.json", receipt)
    return receipt


def snapshot_shared_resources(
        run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run) -> dict[str, list[str]]:
    """记录共享资源稳定身份；排除健康日志等自然变化的运行态字段。"""
    result: dict[str, list[str]] = {}
    for kind in ("container", "volume", "network"):
        format_value = ("{{.ID}} {{.Names}}" if kind == "container"
                        else "{{.Name}} {{.Name}}" if kind == "volume"
                        else "{{.ID}} {{.Name}}")
        all_flag = ["-a"] if kind == "container" else []
        listed = run(["docker", kind, "ls", *all_flag, "--format", format_value],
                     capture_output=True, text=True, check=False)
        if listed.returncode != 0:
            raise C4bError(f"共享 {kind} 清单采集失败")
        snapshots: list[str] = []
        for line in listed.stdout.splitlines():
            parts = line.split(maxsplit=1)
            if len(parts) != 2 or not parts[1].startswith(("tc-", "c4a1c-")):
                continue
            inspected = run(["docker", "inspect", parts[0]], capture_output=True,
                            text=True, check=False)
            if inspected.returncode != 0:
                raise C4bError(f"共享 {kind} inspect 失败")
            try:
                raw = json.loads(inspected.stdout)[0]
            except (json.JSONDecodeError, IndexError, TypeError) as exception:
                raise C4bError("共享资源 inspect 不是有效 JSON") from exception
            stable = _stable_resource_projection(kind, raw)
            digest = hashlib.sha256(json.dumps(stable, sort_keys=True,
                                               separators=(",", ":")).encode("utf-8")).hexdigest()
            snapshots.append(f"{parts[1]}:{parts[0]}:{digest}")
        result[kind] = sorted(snapshots)
    return result


def _stable_resource_projection(kind: str, raw: dict[str, Any]) -> dict[str, Any]:
    """只保留资源身份与配置，避免 health/log/计数器漂移造成清理误报。"""
    if kind == "container":
        state = raw.get("State") or {}
        config = raw.get("Config") or {}
        host = raw.get("HostConfig") or {}
        networks = (raw.get("NetworkSettings") or {}).get("Networks") or {}
        # EndpointID、IP/MAC/Gateway 是 Docker Desktop 的运行态投影；EMQX 连接状态变化时
        # 实测会漂移，但不代表容器或网络被重建。网络身份、别名和声明式 IPAM 足以发现
        # 误删/重连，同时 StartedAt 仍能发现同一容器被重启。
        stable_networks = {name: {"NetworkID": value.get("NetworkID"),
                                  "Aliases": sorted(value.get("Aliases") or []),
                                  "IPAMConfig": value.get("IPAMConfig"),
                                  "Links": sorted(value.get("Links") or []),
                                  "DriverOpts": value.get("DriverOpts")}
                           for name, value in networks.items()}
        stable_config = {key: config.get(key) for key in
                         ("Image", "Cmd", "Entrypoint", "Healthcheck", "Labels",
                          "ExposedPorts", "WorkingDir", "User")}
        stable_config["Env"] = sorted(config.get("Env") or [])
        stable_host = {key: host.get(key) for key in
                       ("PortBindings", "RestartPolicy", "NetworkMode", "Resources",
                        "Memory", "NanoCpus", "ReadonlyRootfs")}
        for key in ("Binds", "ExtraHosts", "SecurityOpt", "CapAdd", "CapDrop", "Dns"):
            stable_host[key] = sorted(host.get(key) or [])
        mounts = sorted(raw.get("Mounts") or [],
                        key=lambda item: (item.get("Destination", ""), item.get("Source", "")))
        health = (state.get("Health") or {}).get("Status")
        # 已在资格开始前处于 restarting/unhealthy 的共享容器不具备稳定运行代际；把它的
        # StartedAt 当硬指纹会因 Docker 自身退避重启误报本片触碰。健康运行中的容器仍锁定
        # StartedAt，以发现本片期间发生的真实重启；异常容器的身份与声明式配置仍必须一致。
        stable_started = (state.get("StartedAt")
                          if state.get("Status") == "running" and health in (None, "healthy")
                          else None)
        return {"Id": raw.get("Id"), "Name": raw.get("Name"), "Image": raw.get("Image"),
                "Created": raw.get("Created"), "StartedAt": stable_started,
                "Config": stable_config, "HostConfig": stable_host, "Mounts": mounts,
                "Networks": stable_networks}
    if kind == "volume":
        return {key: raw.get(key) for key in ("Name", "Driver", "Mountpoint", "CreatedAt", "Labels")}
    if kind == "network":
        return {key: raw.get(key) for key in
                ("Id", "Name", "Created", "Driver", "Internal", "Attachable", "Ingress", "IPAM", "Labels")}
    raise C4bError("未知 Docker 资源类型")


def port_is_bindable(port: int) -> bool:
    """检查清理后宿主端口是否已经释放。"""
    with socket.socket() as probe:
        try:
            probe.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False


def wait_ports_released(ports: list[int], timeout: float = 10.0,
                        sleep: Callable[[float], None] = time.sleep) -> dict[str, bool]:
    """等待 Docker Desktop 异步撤销宿主端口转发，并保留逐端口机器事实。"""
    pending = set(ports)
    deadline = time.monotonic() + timeout
    while pending:
        pending = {port for port in pending if not port_is_bindable(port)}
        if not pending or time.monotonic() >= deadline:
            break
        sleep(0.1)
    return {str(port): port not in pending for port in ports}


def process_exists(pid: int) -> bool:
    """只检查精确 PID 是否仍存在，不按名称扫描。"""
    try:
        process_identity(pid)
        return True
    except C4bError:
        return False


def close_scenarios_after_failure(root: Path, exception: BaseException, stage: str) -> None:
    """setup 等场景外异常也必须形成一个首错和其余 NOT_RUN 的完整十场景闭包。"""
    scenarios_root = root / "scenarios"
    scenarios_root.mkdir(parents=True, exist_ok=True)
    first_failure: str | None = None
    for scenario, checkpoint in SCENARIOS.items():
        path = scenarios_root / f"{scenario}.json"
        if path.is_file():
            try:
                existing = read_json_object(path, f"{scenario} receipt")
                if existing.get("verdict") == "FAIL" and first_failure is None:
                    first_failure = scenario
            except C4bError:
                first_failure = first_failure or scenario
            continue
        if first_failure is None:
            first_failure = scenario
            receipt = {"scenario": scenario, "checkpoint": checkpoint, "verdict": "FAIL",
                       "directFailure": type(exception).__name__, "lifecycleStage": stage,
                       "exception": exception_diagnostic(exception), "phaseEvidence": {},
                       "completedAt": utc_now()}
        else:
            receipt = {"scenario": scenario, "checkpoint": checkpoint, "verdict": "NOT_RUN",
                       "reason": f"前序直接失败: {first_failure}/{stage}",
                       "blockedBy": first_failure, "completedAt": utc_now()}
        write_json(path, receipt)


def write_cleanup_failure(root: Path, manifest: dict[str, Any], exception: BaseException) -> dict[str, Any]:
    """cleanup 自身抛错时仍生成可诊断 FAIL receipt，禁止异常覆盖整个证据闭包。"""
    receipt = {"schemaVersion": 1, "project": manifest.get("project"), "verdict": "FAIL",
               "directFailure": type(exception).__name__, "lifecycleStage": "cleanup",
               "exception": exception_diagnostic(exception), "completedAt": utc_now()}
    write_json(root / "cleanup.json", receipt)
    return receipt


def validate_manifest(value: dict[str, Any]) -> None:
    """验证正式运行源码、时间、构件、场景、端口与租约身份。"""
    require_uuid_v7(str(value.get("runId")))
    if value.get("schemaVersion") != 1 or value.get("project") != project_for(value["runId"]):
        raise C4bError("manifest schema 或 project 身份无效")
    source = value.get("sourceIdentity")
    commit = value.get("gitCommit")
    if (not isinstance(source, dict) or not GIT_COMMIT_PATTERN.fullmatch(str(commit))
            or value.get("gitClean") is not True
            or source.get("qualificationCommit") != commit
            or source.get("evidenceArchiveParentCommit") != commit
            or source.get("gitClean") is not True
            or not isinstance(source.get("repository"), str) or not source.get("repository")):
        raise C4bError("manifest Git 双身份或 clean 证明无效")
    parse_utc(source.get("verifiedAt"), "manifest.sourceIdentity.verifiedAt")
    started = parse_utc(value.get("startedAt"), "manifest.startedAt")
    completed = parse_utc(value.get("completedAt"), "manifest.completedAt")
    if completed < started:
        raise C4bError("manifest completedAt 早于 startedAt")
    if value.get("scenarios") != list(SCENARIOS):
        raise C4bError("manifest 场景清单不匹配")
    ports = value.get("ports")
    if (not isinstance(ports, dict) or len(ports) != 11
            or len(set(ports.values())) != 11
            or any(not isinstance(port, int) or isinstance(port, bool)
                   or port < 1024 or port > 65535 for port in ports.values())):
        raise C4bError("manifest 端口集合无效")
    if value.get("leaseSeconds") != {"outbox": 30, "ruleReceipt": 60}:
        raise C4bError("manifest 租约参数漂移")
    artifacts = value.get("artifacts")
    required = {"jar", "runner", "compose", "qualification", "scenarioDriver",
                "plan", "prometheusConfig"}
    if not isinstance(artifacts, dict) or set(artifacts) != required:
        raise C4bError("manifest 构件集合不完整")
    if (not SHA256_PATTERN.fullmatch(str(artifacts.get("jar")))
            or not SHA256_PATTERN.fullmatch(str(artifacts.get("runner")))):
        raise C4bError("manifest JAR/runner 摘要无效")
    compose = artifacts.get("compose")
    if (not isinstance(compose, dict) or len(compose) != 2
            or any(not SHA256_PATTERN.fullmatch(str(item)) for item in compose.values())):
        raise C4bError("manifest Compose 摘要无效")
    for name in required - {"jar", "runner", "compose"}:
        if not SHA256_PATTERN.fullmatch(str(artifacts.get(name))):
            raise C4bError(f"manifest 构件摘要无效: {name}")


def validate_environment(value: dict[str, Any], manifest: dict[str, Any]) -> None:
    """验证 OS、运行时和本轮隔离资源/镜像身份。"""
    if (value.get("schemaVersion") != 1 or value.get("runId") != manifest.get("runId")
            or value.get("project") != manifest.get("project")):
        raise C4bError("environment 运行身份不匹配")
    system = value.get("os")
    if (not isinstance(system, dict)
            or any(not isinstance(system.get(key), str) or not system.get(key)
                   for key in ("name", "platform", "description"))):
        raise C4bError("environment OS 身份不完整")
    if not isinstance(value.get("dockerClientServer"), str) or not value["dockerClientServer"].strip():
        raise C4bError("environment Docker 版本缺失")
    if not isinstance(value.get("jdk"), str) or not value["jdk"].strip():
        raise C4bError("environment JDK 版本缺失")
    resources = value.get("resources")
    if (not isinstance(resources, dict) or set(resources) != {"container", "volume", "network"}
            or any(not isinstance(resources[kind], list) or not resources[kind]
                   or len(resources[kind]) != len(set(resources[kind]))
                   or any(not isinstance(item, str) or not item for item in resources[kind])
                   for kind in resources)):
        raise C4bError("environment 隔离资源身份不完整")
    images = value.get("imageDigests")
    if (not isinstance(images, dict) or set(images) != set(resources["container"])
            or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(item))
                   for item in images.values())):
        raise C4bError("environment 容器镜像摘要不完整")
    parse_utc(value.get("capturedAt"), "environment.capturedAt")


def executed_scenario_prefix(scenario_documents: dict[str, dict[str, Any]]) -> list[str]:
    """返回实际执行的连续前缀；fail-fast 后的 NOT_RUN 不得冒充已执行事实。"""
    executed = [scenario for scenario in SCENARIOS if scenario in scenario_documents
                and scenario_documents[scenario].get("verdict") != "NOT_RUN"]
    expected = list(SCENARIOS)[:len(executed)]
    if executed != expected:
        raise C4bError("场景执行集合不是冻结顺序的连续前缀")
    return executed


def checkpoint_reached_scenario_prefix(
        scenario_documents: dict[str, dict[str, Any]]) -> list[str]:
    """由正式 receipt 的持久阶段边界推导 checkpoint 连续前缀。"""
    executed = executed_scenario_prefix(scenario_documents)
    reached: list[str] = []
    checkpoint_gap = False
    for scenario in executed:
        receipt = scenario_documents[scenario]
        phase_evidence = receipt.get("phaseEvidence", {})
        phase_reached = (receipt.get("verdict") == "PASS"
                         or isinstance(phase_evidence, dict)
                         and bool(POST_CHECKPOINT_PHASES.intersection(phase_evidence)))
        if phase_reached:
            # fail-fast 语义下后续场景不能越过前一已执行场景的 checkpoint 缺口；
            # 出现这种组合说明 receipt 或事件被拼接，必须拒绝而不是重排。
            if checkpoint_gap:
                raise C4bError("checkpoint 阶段集合不是冻结顺序的连续前缀")
            reached.append(scenario)
        else:
            checkpoint_gap = True
    return reached


def successful_phase_scenarios(root: Path, scenario_documents: dict[str, dict[str, Any]],
                               phase: str) -> list[str]:
    """从摘要保护的阶段诊断推导真正成功并应进入数据库快照的场景。"""
    expected: list[str] = []
    for scenario in executed_scenario_prefix(scenario_documents):
        references = scenario_documents[scenario].get("phaseEvidence", {})
        reference = references.get(phase) if isinstance(references, dict) else None
        if reference is None:
            continue
        phase_value = referenced_json(root, reference, f"{scenario}/{phase} 阶段证据")
        if (phase_value.get("scenario") != scenario or phase_value.get("phase") != phase
                or phase_value.get("status") not in ("PASS", "FAIL", "SKIPPED_EMPTY")):
            raise C4bError("快照引用了身份或状态无效的阶段证据")
        # FAIL 只证明失败诊断已经落盘，SKIPPED_EMPTY 也没有稳定事实；
        # 二者都不能被“引用存在”提升为成功数据库快照要求。
        if phase_value["status"] == "PASS":
            expected.append(scenario)
    return expected


def validate_snapshot(value: dict[str, Any], manifest: dict[str, Any], name: str,
                      scenario_documents: dict[str, dict[str, Any]], root: Path) -> None:
    """成功要求十场景闭合；fail-fast 失败只接受已落盘的连续执行前缀。"""
    if (value.get("schemaVersion") != 1 or value.get("runId") != manifest.get("runId")
            or value.get("project") != manifest.get("project") or value.get("snapshot") != name):
        raise C4bError(f"{name} 身份不匹配")
    facts = value.get("facts")
    scenarios = facts.get("scenarios") if isinstance(facts, dict) else None
    phase = "beforeProbe" if name == "database-before" else "afterProbe"
    expected = successful_phase_scenarios(root, scenario_documents, phase)
    if not isinstance(scenarios, dict) or list(scenarios) != expected:
        raise C4bError(f"{name} 未精确覆盖已执行阶段前缀")
    for scenario, scenario_facts in scenarios.items():
        if not isinstance(scenario_facts, dict) or not scenario_facts:
            raise C4bError(f"{name}/{scenario} 缺少稳定事实")
        if (name == "database-after"
                and scenario_documents[scenario].get("verdict") == "PASS"
                and scenario_facts.get("finalAssertionsPassed") is not True):
            raise C4bError(f"{name}/{scenario} 最终事实未通过")
    parse_utc(value.get("capturedAt"), f"{name}.capturedAt")


def validate_alerts(value: dict[str, Any], manifest: dict[str, Any],
                    scenario_documents: dict[str, dict[str, Any]]) -> None:
    """DB-01 必须保存专用规则 firing/resolved 的真实查询时间与原始向量。"""
    if (value.get("schemaVersion") != 1 or value.get("runId") != manifest.get("runId")
            or value.get("project") != manifest.get("project")
            or value.get("rule") != "ThingsLinkDatabaseUnavailable"):
        raise C4bError("Prometheus 告警证据身份或规则名无效")
    firing = value.get("firing")
    resolved = value.get("resolved")
    if (not isinstance(firing, dict) or not isinstance(resolved, dict)
            or not isinstance(firing.get("result"), list)
            or not isinstance(resolved.get("result"), list)):
        raise C4bError("Prometheus firing/resolved 原始向量语义无效")
    firing_at = parse_utc(firing.get("queriedAt"), "alert.firing.queriedAt")
    resolved_at = parse_utc(resolved.get("queriedAt"), "alert.resolved.queriedAt")
    if resolved_at < firing_at:
        raise C4bError("Prometheus resolved 查询早于 firing")
    db_passed = scenario_documents.get("DB-01", {}).get("verdict") == "PASS"
    if db_passed and (not firing["result"] or resolved["result"]):
        raise C4bError("DB-01 PASS 缺少 firing 或恢复后仍处于告警状态")
    if not db_passed and resolved["result"]:
        raise C4bError("DB-01 失败闭包不得把仍 firing 的向量记为 resolved")
    parse_utc(value.get("capturedAt"), "prometheus-alerts.capturedAt")


def validate_cleanup(value: dict[str, Any], manifest: dict[str, Any]) -> None:
    """严格验证精确 project 清理、资源归零、端口、PID 和共享资源保护。"""
    project = manifest.get("project")
    command = value.get("downCommand")
    remaining = value.get("remaining")
    expected_ports = {str(port) for port in manifest.get("ports", {}).values()}
    if (value.get("verdict") != "PASS" or value.get("project") != project
            or not isinstance(command, list) or "-p" not in command
            or command[command.index("-p") + 1] != project
            or command[-3:] != ["down", "--volumes", "--remove-orphans"]
            or value.get("downExitCode") != 0):
        raise C4bError("cleanup 精确命令或裁决无效")
    if (not isinstance(remaining, dict) or set(remaining) != {"container", "volume", "network"}
            or any(remaining.values()) or value.get("resourceQueryErrors") != []):
        raise C4bError("cleanup 隔离资源未归零")
    port_release = value.get("portRelease")
    if (value.get("portsReleased") is not True or not isinstance(port_release, dict)
            or set(port_release) != expected_ports or any(item is not True for item in port_release.values())):
        raise C4bError("cleanup 端口释放证据不完整")
    if (value.get("sutPidGone") is not True or value.get("sharedUnchanged") is not True
            or value.get("sharedChanges") != {}):
        raise C4bError("cleanup SUT 或共享资源证据未闭合")
    parse_utc(value.get("completedAt"), "cleanup.completedAt")


def validate_events(root: Path, manifest: dict[str, Any],
                    scenario_documents: dict[str, dict[str, Any]]) -> None:
    """成功要求完整事件；失败只接受与场景 receipt 一致的连续事件前缀。"""
    try:
        records = [json.loads(line) for line in (root / "events.jsonl").read_text(
            encoding="utf-8").splitlines() if line.strip()]
    except (OSError, json.JSONDecodeError) as exception:
        raise C4bError("events.jsonl 无效") from exception
    if not records or any(not isinstance(item, dict) for item in records):
        raise C4bError("events.jsonl 为空或含非对象")
    for record in records:
        parse_utc(record.get("at"), "events.at")
    prepared = [item for item in records if item.get("event") == "PREPARED"]
    if (len(prepared) != 1 or prepared[0].get("runId") != manifest.get("runId")
            or prepared[0].get("project") != manifest.get("project")):
        raise C4bError("PREPARED 事件身份无效")
    reached = [item for item in records if item.get("event") == "CHECKPOINT_REACHED"]
    expected_reached = [(scenario, SCENARIOS[scenario]) for scenario in
                        checkpoint_reached_scenario_prefix(scenario_documents)]
    actual_reached = [(item.get("scenario"), item.get("checkpoint")) for item in reached]
    if actual_reached != expected_reached or len(actual_reached) != len(set(actual_reached)):
        raise C4bError("checkpoint 事件未精确覆盖已执行场景前缀")
    stopped = [item for item in records if item.get("event") == "DATABASE_STOPPED"
               and item.get("scenario") == "DB-01"]
    recovered = [item for item in records if item.get("event") == "DATABASE_RECOVERED"
                 and item.get("scenario") == "DB-01"]
    if len(stopped) > 1 or len(recovered) > 1 or (recovered and not stopped):
        raise C4bError("DB-01 停止/恢复事件顺序或数量无效")
    if scenario_documents.get("DB-01", {}).get("verdict") == "PASS" and (
            len(stopped) != 1 or len(recovered) != 1):
        raise C4bError("DB-01 PASS 的停止/恢复事件不完整")
    db_receipt = scenario_documents.get("DB-01", {})
    attribution_reference = db_receipt.get("notificationAttributionEvidence")
    if attribution_reference is not None:
        attribution = referenced_json(root, attribution_reference, "DB-01 通知链归因证据")
        sample = attribution.get("metricsBefore")
        identity = sample.get("processIdentity") if isinstance(sample, dict) else None
        matching_starts = [item for item in records if item.get("event") == "SUT_STARTED"
                           and isinstance(identity, dict)
                           and all(item.get(key) == identity.get(key)
                                   for key in ("pid", "created", "commandSha256", "jarSha256"))]
        db_checkpoints = [item for item in reached if item.get("scenario") == "DB-01"]
        if (len(matching_starts) != 1 or len(db_checkpoints) != 1
                or db_checkpoints[0].get("pid") != identity.get("pid")
                or parse_utc(matching_starts[0].get("at"), "DB-01 SUT_STARTED.at")
                > parse_utc(sample.get("capturedAt"), "DB-01 metricsBefore.capturedAt")):
            raise C4bError("DB-01 通知指标未绑定实际场景 SUT 启动与 checkpoint 身份")
    rw04_receipt = scenario_documents.get("RW-04", {})
    replay_reference = rw04_receipt.get("facts", {}).get("replayMetricEvidence")
    if replay_reference is not None:
        replay_identity = validate_rule_replay_metric_evidence(
            root, replay_reference, require_success=rw04_receipt.get("verdict") == "PASS")
        matching_indexes = [index for index, item in enumerate(records)
                            if item.get("event") == "SUT_STARTED"
                            and all(item.get(key) == replay_identity.get(key)
                                    for key in ("pid", "created", "commandSha256", "jarSha256"))]
        checkpoint_indexes = [index for index, item in enumerate(records)
                              if item.get("event") == "CHECKPOINT_REACHED"
                              and item.get("scenario") == "RW-04"]
        if (len(matching_indexes) != 1 or len(checkpoint_indexes) != 1
                or matching_indexes[0] <= checkpoint_indexes[0]
                or replay_identity.get("jarSha256") != manifest.get("artifacts", {}).get("jar")):
            raise C4bError("RW-04 重放指标未绑定 checkpoint 后实际重启 SUT 身份")


def write_checksum_closure(root: Path) -> None:
    """最后生成并立即自校验精确文件集合与摘要，拒绝缺项、多项和路径逃逸。"""
    checksum = root / "sha256sums.txt"
    files = sorted(path for path in root.rglob("*") if path.is_file() and path != checksum)
    checksum.write_text("".join(
        f"{sha256(path)}  {path.relative_to(root).as_posix()}\n" for path in files), encoding="utf-8")
    verify_checksum_closure(root)


def verify_checksum_closure(root: Path) -> None:
    """重新解析 checksum 并验证它与磁盘文件集合完全相等。"""
    checksum = root / "sha256sums.txt"
    try:
        lines = checksum.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise C4bError("sha256sums.txt 缺失") from exception
    entries: dict[str, str] = {}
    for line in lines:
        match = re.fullmatch(r"([0-9a-f]{64})  ([^\\]+)", line)
        if match is None or match.group(2) in entries:
            raise C4bError("sha256sums.txt 格式或路径重复")
        relative = match.group(2)
        candidate = (root / relative).resolve()
        try:
            candidate.relative_to(root.resolve())
        except ValueError as exception:
            raise C4bError("sha256sums.txt 路径逃逸") from exception
        entries[relative] = match.group(1)
    actual = {path.relative_to(root).as_posix(): path for path in root.rglob("*")
              if path.is_file() and path != checksum}
    if set(entries) != set(actual):
        raise C4bError("sha256sums.txt 文件集合不闭合")
    if any(sha256(actual[path]) != digest for path, digest in entries.items()):
        raise C4bError("sha256sums.txt 摘要漂移")


def verdict(root: Path) -> dict[str, Any]:
    """缺任一场景、基础证据、清理或哈希闭包即 FAIL；仅完整闭合返回 VALID_PASS。"""
    missing = [name for name in REQUIRED_EVIDENCE if not (root / name).is_file()]
    validation_errors: list[str] = []
    manifest: dict[str, Any] | None = None

    def validate_document(name: str, validator: Callable[..., None], *arguments: Any) -> None:
        """把文档损坏归入机器裁决，避免校验异常绕过 FAIL 闭包。"""
        try:
            value = read_json_object(root / name, name)
            validator(value, *arguments)
        except Exception as exception:
            validation_errors.append(f"{name}: {type(exception).__name__}: {exception}")

    try:
        manifest = read_json_object(root / "run-manifest.json", "run-manifest.json")
        validate_manifest(manifest)
    except Exception as exception:
        validation_errors.append(
            f"run-manifest.json: {type(exception).__name__}: {exception}")

    scenario_verdicts: dict[str, str] = {}
    scenario_documents: dict[str, dict[str, Any]] = {}
    for scenario in SCENARIOS:
        receipt = root / "scenarios" / f"{scenario}.json"
        if not receipt.is_file():
            scenario_verdicts[scenario] = "MISSING"
            continue
        try:
            value = read_json_object(receipt, f"{scenario} receipt")
            validate_scenario_receipt(value, root)
            scenario_documents[scenario] = value
            scenario_verdicts[scenario] = value.get("verdict", "FAIL")
        except Exception as exception:
            scenario_verdicts[scenario] = "FAIL"
            validation_errors.append(
                f"scenarios/{scenario}.json: {type(exception).__name__}: {exception}")

    if manifest is not None:
        validate_document("environment.json", validate_environment, manifest)
        validate_document("database-before.json", validate_snapshot, manifest,
                          "database-before", scenario_documents, root)
        validate_document("database-after.json", validate_snapshot, manifest,
                          "database-after", scenario_documents, root)
        validate_document("prometheus-alerts.json", validate_alerts, manifest,
                          scenario_documents)
        validate_document("cleanup.json", validate_cleanup, manifest)
        try:
            validate_events(root, manifest, scenario_documents)
        except Exception as exception:
            validation_errors.append(f"events.jsonl: {type(exception).__name__}: {exception}")

    first_direct_failure: dict[str, Any] | None = None
    not_run: list[dict[str, Any]] = []
    for scenario in SCENARIOS:
        value = scenario_documents.get(scenario, {})
        if scenario_verdicts.get(scenario) == "FAIL" and first_direct_failure is None:
            first_direct_failure = {
                "scenario": scenario,
                "lifecycleStage": value.get("lifecycleStage", "scenario-validation"),
                "reason": value.get("directFailure", "场景证据语义校验失败"),
            }
        if scenario_verdicts.get(scenario) == "NOT_RUN":
            not_run.append({"scenario": scenario, "reason": value.get("reason"),
                            "blockedBy": value.get("blockedBy")})
    if first_direct_failure is None and validation_errors:
        first_direct_failure = {"lifecycleStage": "evidence-validation",
                                "reason": validation_errors[0]}

    passed = (not missing and not validation_errors
              and all(value == "PASS" for value in scenario_verdicts.values()))
    result = {"schemaVersion": 1,
              "runId": manifest.get("runId") if manifest else None,
              "project": manifest.get("project") if manifest else None,
              "scenarios": scenario_verdicts, "missing": missing,
              "validationErrors": validation_errors,
              "firstDirectFailure": first_direct_failure,
              "notRun": not_run,
              "verdict": "VALID_PASS" if passed else "FAIL", "completedAt": utc_now()}
    write_json(root / "verdict.json", result)
    # FAIL 现场也必须形成精确文件集合，生成后立即按同一读取合同自校验。
    write_checksum_closure(root)
    return result


def main() -> int:
    """提供独立 verdict 命令；正式编排由 C4b-2 调用同一库完成。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("verdict",))
    parser.add_argument("--evidence-root", type=Path, required=True)
    args = parser.parse_args()
    result = verdict(args.evidence_root.resolve())
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["verdict"] == "VALID_PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
