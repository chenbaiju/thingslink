#!/usr/bin/env python3
"""G1-C3e-0 显式分阶段入口：本地校验、外部协调边界、工具裁决与归档。"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable


SCRIPT_DIR = Path(__file__).resolve().parent
STAGES = ("PREPARE", "COORDINATE", "FINALIZE", "ARCHIVE", "PURGE_SECRETS")
RUN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")


class EntranceError(RuntimeError):
    """入口配置、顺序或外部证据不足，不能继续 C3e-0。"""


def read_object(path: Path, description: str) -> dict[str, Any]:
    """读取 JSON 对象并拒绝重复键。"""
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EntranceError(f"{description} 含重复键: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates)
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise EntranceError(f"{description} 不可读: {path}") from exception
    if not isinstance(value, dict):
        raise EntranceError(f"{description} 顶层必须是对象")
    return value


def write_atomic(path: Path, value: dict[str, Any]) -> None:
    """原子写公开编排事实；不把秘密值写入状态或 runbook。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=f".{path.name}.", delete=False) as target:
        temporary = Path(target.name)
        try:
            target.write(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")) + "\n")
            target.flush()
            os.fsync(target.fileno())
        except BaseException:
            temporary.unlink(missing_ok=True)
            raise
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def load_sibling(name: str) -> Any:
    """加载同目录纯函数，避免入口通过 shell 拼接参数。"""
    specification = importlib.util.spec_from_file_location(name, SCRIPT_DIR / f"{name}.py")
    if specification is None or specification.loader is None:
        raise EntranceError(f"入口工具缺失: {name}.py")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def required_path(config: dict[str, Any], name: str, description: str) -> Path:
    """配置路径必须显式存在；不从当前目录猜测实际云输入或秘密。"""
    value = config.get(name)
    if not isinstance(value, str) or not value.strip():
        raise EntranceError(f"{description} 必填")
    return Path(value).resolve()


def run_checked(argv: list[str], runner: Callable[..., subprocess.CompletedProcess] = subprocess.run) -> None:
    """无 shell 执行一个工具步骤，并保留其原始 stdout/stderr 给调用方。"""
    completed = runner(argv, shell=False, check=False)
    if completed.returncode != 0:
        raise EntranceError(f"工具步骤失败 exit={completed.returncode}: {Path(argv[1]).name}")


def run_reporter(argv: list[str], runner: Callable[..., subprocess.CompletedProcess]) -> int:
    """机器裁决的 1 表示有效失败态而非工具未产出；仅 0/1 可进入归档。"""
    completed = runner(argv, shell=False, check=False)
    if completed.returncode not in {0, 1}:
        raise EntranceError(f"机器裁决工具错误 exit={completed.returncode}")
    return completed.returncode


def copy_exact(source: Path, destination: Path, description: str) -> None:
    """复制已冻结输入；同路径只允许复用，目标存在且不同则拒绝覆盖。"""
    if not source.is_file():
        raise EntranceError(f"{description} 缺失: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    if source == destination.resolve():
        return
    if destination.exists():
        if destination.read_bytes() != source.read_bytes():
            raise EntranceError(f"拒绝覆盖不同的已归档 {description}")
        return
    shutil.copyfile(source, destination)


def validate_config(config: dict[str, Any]) -> tuple[str, Path]:
    """只验证入口身份；环境内容必须始终由第一个工具步骤判定。"""
    run_id = config.get("runId")
    evidence_value = config.get("evidenceDir")
    if config.get("schemaVersion") != 1 or not isinstance(run_id, str) \
            or RUN_ID_RE.fullmatch(run_id) is None:
        raise EntranceError("配置 schemaVersion=1 且 runId 必须是稳定小写身份")
    if not isinstance(evidence_value, str) or not evidence_value.strip():
        raise EntranceError("evidenceDir 必填")
    return run_id, Path(evidence_value).resolve()


def validate_environment_first(config: dict[str, Any], evidence_dir: Path,
                               runner: Callable[..., subprocess.CompletedProcess]) -> dict[str, Any]:
    """第一项只校验真实环境；占位/缺字段时不得先接触数据库、远端或秘密。"""
    source = required_path(config, "environmentInventoryInput", "实际 environment inventory")
    if not source.is_file():
        raise EntranceError(f"实际 environment inventory 缺失: {source}")
    evidence_dir.mkdir(parents=True, exist_ok=True)
    archived_source = evidence_dir / "environment-inventory.input.json"
    copy_exact(source, archived_source, "环境原件")
    normalized = evidence_dir / "environment-inventory.json"
    report = evidence_dir / "environment-inventory-report.json"
    run_checked([sys.executable, str(SCRIPT_DIR / "l2_environment_inventory.py"),
                 "--input", str(archived_source), "--normalized-output", str(normalized),
                 "--report-output", str(report)], runner)
    result = read_object(report, "环境校验报告")
    if result.get("status") != "PASS":
        raise EntranceError("最终环境清单未通过；禁止进入远端资格或 fixture")
    return result


def prepare(config: dict[str, Any], runner: Callable[..., subprocess.CompletedProcess] = subprocess.run
            ) -> dict[str, Any]:
    """锁定环境/计划并产出远端 runbook；不执行 SSH、上传或云资源操作。"""
    run_id, evidence_dir = validate_config(config)
    environment = validate_environment_first(config, evidence_dir, runner)
    # 环境通过后才检查秘密存在性，确保缺真实云值永远是第一个 fail-closed 门槛。
    owner_secrets = required_path(config, "ownerSecrets", "十租户 OWNER 秘密清单")
    if not owner_secrets.is_file():
        raise EntranceError("十租户 OWNER 秘密清单缺失")
    secret_root = required_path(config, "secretTempRoot", "秘密临时根目录")
    if secret_root == evidence_dir or evidence_dir in secret_root.parents or secret_root in evidence_dir.parents:
        raise EntranceError("秘密临时根目录必须与公开 evidence dir 完全隔离")
    metadata_source = required_path(config, "runMetadata", "run metadata")
    plan_source = required_path(config, "distributedPlan", "distributed plan")
    copy_exact(metadata_source, evidence_dir / "run-metadata.json", "run metadata")
    copy_exact(plan_source, evidence_dir / "distributed-plan.json", "distributed plan")
    metadata = read_object(evidence_dir / "run-metadata.json", "run metadata")
    plan = read_object(evidence_dir / "distributed-plan.json", "distributed plan")
    fingerprint = environment.get("comparisonFingerprint")
    if (metadata.get("runId") != run_id or metadata.get("environmentFingerprint") != fingerprint
            or plan.get("runId") != run_id or plan.get("environmentFingerprint") != fingerprint):
        raise EntranceError("run metadata/distributed plan 与环境指纹不一致")
    if (metadata.get("scope"), metadata.get("profile")) != ("C3E0", "TOOL_QUALIFICATION"):
        raise EntranceError("C3e-0 run metadata 必须冻结 scope=C3E0/profile=TOOL_QUALIFICATION")
    coordinator = load_sibling("l2_distributed_coordinator")
    _, hosts = coordinator.load_plan(evidence_dir / "distributed-plan.json")
    inventory = read_object(evidence_dir / "environment-inventory.json", "normalized inventory")
    generators = inventory.get("generators")
    inventory_artifacts = inventory.get("artifacts")
    if not isinstance(generators, list) or not all(isinstance(item, dict) for item in generators):
        raise EntranceError("normalized inventory generators 缺失")
    inventory_host_ids = [item.get("hostId") for item in generators]
    plan_host_ids = [host["hostId"] for host in hosts]
    if (len(inventory_host_ids) != len(set(inventory_host_ids))
            or set(inventory_host_ids) != set(plan_host_ids)):
        raise EntranceError("environment inventory 与 distributed plan 的 generator hostId 集不一致")
    if (not isinstance(inventory_artifacts, dict)
            or plan.get("artifacts") != {
                "simulatorJarSha256": inventory_artifacts.get("simulatorJarSha256"),
                "a4RunnerSha256": inventory_artifacts.get("scriptsSha256", {}).get(
                    "a4_qualification.py") if isinstance(inventory_artifacts.get("scriptsSha256"), dict)
                else None}):
        raise EntranceError("distributed plan artifacts 与规范化环境清单不一致")
    runbook_hosts = []
    for host in hosts:
        host_id = host["hostId"]
        runbook_hosts.append({
            "hostId": host_id,
            "executeOn": "EXTERNAL_GENERATOR_HOST",
            "workerArgv": ["python3", "l2_distributed_coordinator.py", "worker",
                           "--plan", "distributed-plan.json", "--host-id", host_id,
                           "--signal-dir", "coordination", "--evidence-dir", ".",
                           "--output", f"coordination/{host_id}.complete.json"],
            "mustTransferBack": [f"coordination/{host_id}.ready.json",
                                 f"coordination/{host_id}.complete.json",
                                 f"host-envelopes/{host_id}.host-envelope.json",
                                 host["reportPath"], str(Path(host["reportPath"]).with_suffix(".md"))
                                 .replace("\\", "/"), host["manifestRoot"] + "/**",
                                 str(Path(host["reportPath"]).parent / "logs").replace("\\", "/") + "/**"],
        })
    runbook = {
        "schemaVersion": 1, "runId": run_id, "environmentFingerprint": fingerprint,
        "status": "AWAITING_EXTERNAL_COORDINATION",
        "transportBoundary": {
            "mode": "EXTERNAL_REQUIRED",
            "statement": "本入口不实现 SSH/上传/云资源创建；运行方须在冻结主机执行 worker，并实时传递不可变阶段文件。",
            "controllerPublishes": ["coordination/controller.start.json"],
            "controllerConsumes": ["coordination/<hostId>.ready.json",
                                   "coordination/<hostId>.complete.json"],
            "noExecutionClaim": True,
        },
        "hosts": runbook_hosts,
    }
    write_atomic(evidence_dir / "external-runbook.json", runbook)
    state = {"schemaVersion": 1, "runId": run_id, "phase": "AWAITING_EXTERNAL_COORDINATION",
             "environmentFingerprint": fingerprint}
    write_atomic(evidence_dir / "c3e0-entrance-state.json", state)
    return state


def coordinate(config: dict[str, Any], runner: Callable[..., subprocess.CompletedProcess] = subprocess.run
               ) -> dict[str, Any]:
    """消费外部实时传输的 READY/COMPLETE；只在完整结束后聚合原始 A4。"""
    run_id, evidence_dir = validate_config(config)
    state = read_object(evidence_dir / "c3e0-entrance-state.json", "entrance state")
    if state.get("phase") != "AWAITING_EXTERNAL_COORDINATION" or state.get("runId") != run_id:
        raise EntranceError("COORDINATE 必须紧随 PREPARE")
    coordinator = load_sibling("l2_distributed_coordinator")
    settings = config.get("coordination")
    if not isinstance(settings, dict):
        raise EntranceError("coordination 设置必填")
    try:
        timing = [float(settings.get(name)) for name in
                  ("readyTimeoutSeconds", "completeTimeoutSeconds", "pollSeconds")]
    except (TypeError, ValueError) as exception:
        raise EntranceError("coordination timeout/poll 必须是有限正数") from exception
    if any(not math.isfinite(value) or value <= 0 for value in timing):
        raise EntranceError("coordination timeout/poll 必须是有限正数")
    result = coordinator.coordinate(
        evidence_dir / "distributed-plan.json", evidence_dir / "coordination", *timing)
    write_atomic(evidence_dir / "coordination" / "coordination-result.json", result)
    qualification = coordinator.aggregate_group(evidence_dir / "distributed-plan.json",
                                                evidence_dir / "host-envelopes")
    write_atomic(evidence_dir / "distributed-qualification.json", qualification)
    manifest = load_sibling("l2_evidence_manifest")
    manifest.build_registered(evidence_dir, "C3E0_QUALIFICATION_INPUT",
                              evidence_dir / manifest.OUTPUT_NAMES["C3E0_QUALIFICATION_INPUT"])
    if qualification.get("result") == "PASS":
        phase = "QUALIFICATION_PASS"
    else:
        run_reporter([sys.executable, str(SCRIPT_DIR / "l2_machine_verdict.py"),
                      "--evidence-dir", str(evidence_dir), "--runner-outcome", "failure",
                      "--output", str(evidence_dir / "machine-report.json"), "--markdown-output",
                      str(evidence_dir / "machine-report.md")], runner)
        phase = "MACHINE_REPORT_READY"
    next_state = {"schemaVersion": 1, "runId": run_id, "phase": phase,
                  "environmentFingerprint": state["environmentFingerprint"]}
    write_atomic(evidence_dir / "c3e0-entrance-state.json", next_state)
    return next_state


def finalize(config: dict[str, Any], runner: Callable[..., subprocess.CompletedProcess] = subprocess.run
             ) -> dict[str, Any]:
    """资格 PASS 后依序执行 fixture、typed metrics、机器裁决；凭据保留给后续 profile。"""
    run_id, evidence_dir = validate_config(config)
    state = read_object(evidence_dir / "c3e0-entrance-state.json", "entrance state")
    if state.get("phase") != "QUALIFICATION_PASS" or state.get("runId") != run_id:
        raise EntranceError("FINALIZE 只允许在整组 qualification PASS 后执行")
    fixture = config.get("fixture")
    if not isinstance(fixture, dict):
        raise EntranceError("fixture 连接参数必填")
    for key in ("baseUrl", "policyId", "postgresUser", "postgresDb"):
        if not isinstance(fixture.get(key), str) or not fixture[key].strip():
            raise EntranceError(f"fixture.{key} 必须是实际非空值")
    if config.get("runnerOutcome") not in {"success", "failure", "cancelled", "skipped"}:
        raise EntranceError("runnerOutcome 必须显式属于 success/failure/cancelled/skipped")
    if config.get("confirmIsolatedDisposableEnvironmentOrSnapshot") is not True:
        raise EntranceError("fixture prepare 非事务；必须确认隔离可销毁环境或已有可恢复快照")
    inputs = (("fixtureAssignmentPlan", "fixture-assignment-plan.json"),
              ("metricsSourcePlan", "metrics-source-plan.json"))
    for config_name, archive_name in inputs:
        copy_exact(required_path(config, config_name, config_name), evidence_dir / archive_name, config_name)
    owner_secrets = required_path(config, "ownerSecrets", "OWNER secrets")
    secret_root = required_path(config, "secretTempRoot", "秘密临时根目录")
    if secret_root == evidence_dir or evidence_dir in secret_root.parents or secret_root in evidence_dir.parents:
        raise EntranceError("秘密临时根目录必须与公开 evidence dir 完全隔离")
    secret_dir = secret_root / f"c3e0-{run_id}"
    if secret_dir.exists():
        raise EntranceError("本轮秘密临时目录已存在，拒绝混用旧凭据")
    secret_dir.mkdir(parents=True, mode=0o700)
    in_progress = {"schemaVersion": 1, "runId": run_id, "phase": "FIXTURE_PREPARE_IN_PROGRESS",
                   "environmentFingerprint": state["environmentFingerprint"],
                   "credentialsRetainedOutsideEvidence": True,
                   "requiresDisposableEnvironmentOrSnapshot": True}
    write_atomic(evidence_dir / "c3e0-entrance-state.json", in_progress)
    common_db = ["--postgres-container", str(fixture.get("postgresContainer", "tc-postgres")),
                 "--postgres-user", str(fixture.get("postgresUser", "")),
                 "--postgres-db", str(fixture.get("postgresDb", ""))]
    run_checked([sys.executable, str(SCRIPT_DIR / "l2_fixture.py"), "quota",
                 "--owner-secrets", str(owner_secrets), "--policy-id", str(fixture.get("policyId", "")),
                 *common_db, "--output", str(evidence_dir / "fixture-quota.json")], runner)
    # prepare 通过多次真实 API 创建设备，可能部分成功且不是跨 API/数据库事务；入口绝不声称失败可自动回滚。
    run_checked([sys.executable, str(SCRIPT_DIR / "l2_fixture.py"), "prepare",
                 "--base-url", str(fixture.get("baseUrl", "")),
                 "--owner-secrets", str(owner_secrets),
                 "--quota-evidence", str(evidence_dir / "fixture-quota.json"),
                 "--assignment-plan", str(evidence_dir / "fixture-assignment-plan.json"),
                 "--secret-output-dir", str(secret_dir / "fixture"),
                 "--output", str(evidence_dir / "fixture-tenants.json"), "--run-id", run_id,
                 "--environment-fingerprint", state["environmentFingerprint"]], runner)
    metrics_script = SCRIPT_DIR / "l2_metrics.py"
    run_checked([sys.executable, str(metrics_script), "sample", "--plan",
                 str(evidence_dir / "metrics-source-plan.json"), "--output-dir",
                 str(evidence_dir / "metric-sources")], runner)
    run_checked([sys.executable, str(metrics_script), "collect", "--plan",
                 str(evidence_dir / "metrics-source-plan.json"), "--source-dir",
                 str(evidence_dir / "metric-sources"), "--output",
                 str(evidence_dir / "unified-metrics.jsonl"), "--summary-output",
                 str(evidence_dir / "metrics-summary.json")], runner)
    verdict_module = load_sibling("l2_machine_verdict")
    write_atomic(evidence_dir / "tool-error-matrix.json", verdict_module.build_error_matrix())
    manifest = load_sibling("l2_evidence_manifest")
    manifest.build_registered(evidence_dir, "C3E0_FULL_INPUT",
                              evidence_dir / manifest.OUTPUT_NAMES["C3E0_FULL_INPUT"])
    run_reporter([sys.executable, str(SCRIPT_DIR / "l2_machine_verdict.py"),
                  "--evidence-dir", str(evidence_dir), "--runner-outcome",
                  str(config.get("runnerOutcome", "failure")), "--output",
                  str(evidence_dir / "machine-report.json"), "--markdown-output",
                  str(evidence_dir / "machine-report.md")], runner)
    next_state = {"schemaVersion": 1, "runId": run_id, "phase": "MACHINE_REPORT_READY",
                  "environmentFingerprint": state["environmentFingerprint"],
                  "credentialsRetainedOutsideEvidence": True,
                  "requiresDisposableEnvironmentOrSnapshot": True}
    write_atomic(evidence_dir / "c3e0-entrance-state.json", next_state)
    return next_state


def purge_secrets(config: dict[str, Any]) -> dict[str, Any]:
    """仅按 runId 删除入口私有根下的精确子目录；不调用 fixture cleanup 或任何云操作。"""
    run_id, evidence_dir = validate_config(config)
    state = read_object(evidence_dir / "c3e0-entrance-state.json", "entrance state")
    if state.get("runId") != run_id:
        raise EntranceError("PURGE_SECRETS runId 与入口状态不一致")
    fingerprint = state.get("environmentFingerprint")
    if not isinstance(fingerprint, str) or re.fullmatch(r"[0-9a-f]{64}", fingerprint) is None \
            or fingerprint == "0" * 64:
        raise EntranceError("PURGE_SECRETS environmentFingerprint 缺失或非法")
    raw_root = config.get("secretTempRoot")
    if not isinstance(raw_root, str) or not raw_root.strip():
        raise EntranceError("秘密临时根目录必填")
    raw_path = Path(raw_root).absolute()
    if ".." in raw_path.parts or any(part.is_symlink() for part in (raw_path, *raw_path.parents)):
        raise EntranceError("秘密临时根目录及祖先不得含符号链接或路径穿越")
    secret_root = raw_path.resolve()
    if config.get("confirmProfilesCompleteOrFixtureCleaned") is not True:
        raise EntranceError("PURGE_SECRETS 必须确认 profile 已完成或 fixture 已清理")
    if secret_root == Path(secret_root.anchor) or secret_root.is_symlink():
        raise EntranceError("秘密临时根目录不得是文件系统根或符号链接")
    if secret_root == evidence_dir or evidence_dir in secret_root.parents or secret_root in evidence_dir.parents:
        raise EntranceError("秘密临时根目录必须与公开 evidence dir 完全隔离")
    secret_dir = secret_root / f"c3e0-{run_id}"
    if secret_dir.is_symlink():
        raise EntranceError("拒绝删除符号链接秘密目录")
    lock = evidence_dir / ".secret-cleanup.lock"
    try:
        lock.mkdir()
    except FileExistsError as exception:
        raise EntranceError("秘密清理锁已存在，拒绝并发或未经检查的崩溃重试") from exception
    try:
        receipt = {"schemaVersion": 2, "runId": run_id, "environmentFingerprint": fingerprint,
                   "operation": "PURGE_SECRETS", "cleanupKind": "SECRET",
                   "scope": "RUN_PRIVATE_DIRECTORY",
                   "scopeSha256": hashlib.sha256(str(secret_dir).encode()).hexdigest(),
                   "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "directoryExistedBefore": secret_dir.exists(),
                   "cloudCleanupPerformed": False, "fixtureCleanupPerformed": False}
        try:
            if secret_dir.exists():
                shutil.rmtree(secret_dir)
            if secret_dir.exists():
                raise OSError("secret directory remains")
        except OSError as exception:
            receipt.update(result="FAIL", secretDirectoryPurged=False,
                           errorType=type(exception).__name__,
                           completedAt=dt.datetime.now(dt.timezone.utc).isoformat())
            write_atomic(evidence_dir / "secret-cleanup-receipt.json", receipt)
            raise EntranceError("秘密清理失败，原状态保留；检查独立失败回执") from exception
        receipt.update(result="PASS", secretDirectoryPurged=True,
                       completedAt=dt.datetime.now(dt.timezone.utc).isoformat())
        write_atomic(evidence_dir / "secret-cleanup-receipt.json", receipt)
        next_state = {**state, "credentialsRetainedOutsideEvidence": False}
        write_atomic(evidence_dir / "c3e0-entrance-state.json", next_state)
        return next_state
    finally:
        lock.rmdir()


def archive(config: dict[str, Any]) -> dict[str, Any]:
    """机器报告存在后构建传递式归档清单；可选 cleanup receipt 只归档、不触发清理。"""
    run_id, evidence_dir = validate_config(config)
    state = read_object(evidence_dir / "c3e0-entrance-state.json", "entrance state")
    if state.get("phase") != "MACHINE_REPORT_READY":
        raise EntranceError("ARCHIVE 前必须已有资格失败或机器报告")
    manifest = load_sibling("l2_evidence_manifest")
    manifest.build_registered(evidence_dir, "C3E0_ARCHIVE",
                              evidence_dir / manifest.OUTPUT_NAMES["C3E0_ARCHIVE"])
    next_state = {**state, "phase": "ARCHIVED"}
    write_atomic(evidence_dir / "c3e0-entrance-state.json", next_state)
    return next_state


def parse_args() -> argparse.Namespace:
    """动作显式分段，外部 worker 不会被一个本地命令假装执行。"""
    parser = argparse.ArgumentParser(description="G1-C3e-0 分阶段入口")
    parser.add_argument("--action", choices=STAGES, required=True)
    parser.add_argument("--config", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    """错误只公开类型与说明；配置模板中的秘密路径内容绝不回显。"""
    args = parse_args()
    try:
        config = read_object(args.config.resolve(), "C3e-0 entrance config")
        action = {"PREPARE": prepare, "COORDINATE": coordinate,
                  "FINALIZE": finalize, "ARCHIVE": archive,
                  "PURGE_SECRETS": purge_secrets}[args.action]
        state = action(config)
        print(f"[c3e0] {state['phase']}")
        return 0
    except Exception as exception:  # noqa: BLE001 - 未建模错误同样禁止越过阶段门禁。
        print(f"[c3e0] ERROR {type(exception).__name__}: {exception}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
