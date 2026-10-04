#!/usr/bin/env python3
"""G1-C3e-0 跨主机 A4 证据封装、整组聚合与单向有界阶段协调。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any


IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")


class CoordinationError(RuntimeError):
    """跨主机输入或阶段合同不能形成唯一、完整且有界的事实。"""


def read_object(path: Path) -> dict[str, Any]:
    """严格读取 JSON 对象；缺文件、空对象与畸形 JSON 都由上层归为 ERROR。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise CoordinationError(f"无法读取 {path}: {exception}") from exception
    if not isinstance(value, dict) or not value:
        raise CoordinationError(f"{path} 顶层必须是非空对象")
    return value


def sha256_file(path: Path) -> str:
    """流式计算证据摘要，避免大 manifest 被一次读入内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json_atomic(path: Path, value: dict[str, Any], immutable: bool = False) -> None:
    """原子发布阶段事实；不可变信号只允许完全相同的幂等重放。"""
    rendered = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    if immutable and path.exists():
        if path.read_text(encoding="utf-8") != rendered:
            raise CoordinationError(f"不可变阶段信号已存在且内容不同: {path.name}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(rendered, encoding="utf-8")
    os.replace(temporary, path)


def require_identity(value: Any, field: str) -> str:
    """限制文件名身份字符，防止 hostId/shardId 穿越共享信号目录。"""
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise CoordinationError(f"{field} 必须匹配 {IDENTIFIER.pattern}")
    return value


def require_relative_path(value: Any, field: str) -> str:
    """运行路径必须相对 plan 目录且不得穿越。"""
    if not isinstance(value, str) or not value:
        raise CoordinationError(f"{field} 必须是非空相对路径")
    path = Path(value)
    if path.is_absolute() or ".." in path.parts:
        raise CoordinationError(f"{field} 不得是绝对路径或包含 ..")
    return value


def resolve_plan_path(working_dir: Path, value: str, field: str) -> Path:
    """把 plan 相对路径约束在 plan 目录内，连符号链接穿越也拒绝。"""
    root = working_dir.resolve()
    resolved = (root / value).resolve()
    if resolved != root and root not in resolved.parents:
        raise CoordinationError(f"{field} 解析后越出 plan 目录")
    return resolved


def archive_relative_path(evidence_root: Path, path: Path, field: str) -> str:
    """把原始 A4 文件锁在证据根内，并返回跨主机归档统一使用的 POSIX 相对路径。"""
    root = evidence_root.resolve()
    resolved = path.resolve()
    if resolved == root or root not in resolved.parents:
        raise CoordinationError(f"{field} 越出 evidence root")
    return resolved.relative_to(root).as_posix()


def validate_a4_argv(argv: Any, host_id: str) -> list[str]:
    """只允许直接 argv；明显秘密禁止入 plan，credentials-file 后只能跟路径。"""
    if not isinstance(argv, list) or not argv or any(not isinstance(item, str) or not item for item in argv):
        raise CoordinationError(f"host {host_id} a4Argv 必须是非空字符串数组")
    if Path(argv[0]).name.lower() in {"sh", "bash", "cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe"}:
        raise CoordinationError(f"host {host_id} a4Argv 禁止 shell 入口")
    index = 0
    while index < len(argv):
        item = argv[index]
        if item == "--credentials-file":
            if index + 1 >= len(argv):
                raise CoordinationError(f"host {host_id} --credentials-file 缺路径")
            require_relative_path(argv[index + 1], f"host {host_id} credentials-file")
            index += 2
            continue
        if any(word in item.lower() for word in ("authorization", "bearer", "password", "token", "secret")):
            raise CoordinationError(f"host {host_id} a4Argv 疑似包含秘密")
        index += 1
    return argv


def cli_option(argv: list[str], name: str) -> str | None:
    """读取一次性 CLI 选项，重复项拒绝，避免前后值覆盖造成 plan 推导分叉。"""
    values: list[str] = []
    for index, item in enumerate(argv):
        if item == name:
            if index + 1 >= len(argv):
                raise CoordinationError(f"{name} 缺值")
            values.append(argv[index + 1])
        elif item.startswith(name + "="):
            values.append(item.split("=", 1)[1])
    if len(values) > 1:
        raise CoordinationError(f"{name} 重复")
    return values[0] if values else None


def manifest_path(root: Path, run_id: str, shard_id: str) -> Path:
    """接受 A4 原始根目录或已按 host 打包后的扁平根目录。"""
    candidates = (root / run_id / shard_id / "property_report.log",
                  root / shard_id / "property_report.log")
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    raise CoordinationError(f"分片 {shard_id} 缺 property_report.log")


def build_host_envelope(report_path: Path, manifest_root: Path, host_id: str,
                        run_id: str | None = None, environment_fingerprint: str | None = None,
                        plan_path: Path | None = None) -> dict[str, Any]:
    """把单机 A4 报告包装为可跨主机搬运的自包含低敏证据。"""
    host_id = require_identity(host_id, "hostId")
    plan_sha = None
    artifact_hashes = None
    evidence_root = report_path.resolve().parent
    if plan_path is not None:
        plan, hosts = load_plan(plan_path)
        if host_id not in {host["hostId"] for host in hosts}:
            raise CoordinationError(f"host {host_id} 不在 distributed plan")
        run_id, environment_fingerprint = plan["runId"], plan["environmentFingerprint"]
        plan_sha, artifact_hashes = sha256_file(plan_path), plan["artifacts"]
        evidence_root = plan_path.resolve().parent
    run_id = require_identity(run_id, "runId")
    if not SHA256.fullmatch(environment_fingerprint):
        raise CoordinationError("environmentFingerprint 必须是 SHA-256")
    report = read_object(report_path)
    if (report.get("schemaVersion") != 1 or report.get("runId") != run_id
            or report.get("result") not in {"PASS", "FAIL"}):
        raise CoordinationError("A4 报告 schema/runId/result 不受支持")
    if artifact_hashes is not None:
        report_fingerprint = report.get("fingerprint", {})
        if (report_fingerprint.get("jarSha256") != artifact_hashes["simulatorJarSha256"]
                or report_fingerprint.get("runnerSha256") != artifact_hashes["a4RunnerSha256"]):
            raise CoordinationError("A4 report JAR/runner SHA 与 distributed plan 不一致")
    shards = report.get("shards")
    if not isinstance(shards, list) or not shards:
        raise CoordinationError("A4 报告缺少非空 shards")
    seen_shards: set[str] = set()
    all_message_ids: list[str] = []
    shard_evidence: list[dict[str, Any]] = []
    report_markdown_path = report_path.with_suffix(".md")
    if not report_markdown_path.is_file():
        raise CoordinationError("A4 报告缺 qualification-report.md")
    for shard in shards:
        if not isinstance(shard, dict):
            raise CoordinationError("A4 shards 含非对象")
        shard_id = require_identity(shard.get("shardId"), "shardId")
        if shard_id in seen_shards:
            raise CoordinationError(f"host {host_id} shardId 重复: {shard_id}")
        seen_shards.add(shard_id)
        try:
            path = manifest_path(manifest_root, run_id, shard_id)
        except CoordinationError:
            # 资格 FAIL 可能发生在首次 PUBACK 之前；缺 manifest 是失败现场的一部分而非工具损坏。
            if report["result"] == "PASS":
                raise
            path = None
        ids = ([] if path is None else
               [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()])
        if len(ids) != len(set(ids)):
            raise CoordinationError(f"分片 {shard_id} manifest messageId 重复")
        expected = shard.get("propertyManifestLines")
        if report["result"] == "PASS" and (not ids or not isinstance(expected, int) or expected != len(ids)):
            raise CoordinationError(f"分片 {shard_id} PASS manifest 为空或与 A4 报告不一致")
        if report["result"] == "FAIL" and isinstance(expected, int) and expected != len(ids):
            raise CoordinationError(f"分片 {shard_id} FAIL manifest 与已报告行数不一致")
        all_message_ids.extend(ids)
        log_path = report_path.parent / "logs" / f"{shard_id}.log"
        if not log_path.is_file():
            raise CoordinationError(f"分片 {shard_id} 缺原始资源/进程日志")
        shard_evidence.append({
            "shardId": shard_id,
            "globalShardId": f"{host_id}/{shard_id}",
            "deviceCount": shard.get("deviceCount"),
            "manifestLines": len(ids),
            "manifestSha256": sha256_file(path) if path is not None else None,
            "manifestFile": (archive_relative_path(evidence_root, path, "manifestFile")
                             if path is not None else None),
            "resourceLogFile": archive_relative_path(evidence_root, log_path, "resourceLogFile"),
            "resourceLogSha256": sha256_file(log_path),
        })
    if len(all_message_ids) != len(set(all_message_ids)):
        raise CoordinationError(f"host {host_id} 跨分片 messageId 重复")
    return {
        "schemaVersion": 1,
        "runId": run_id,
        "environmentFingerprint": environment_fingerprint,
        "hostId": host_id,
        "result": report["result"],
        "qualifiedShardSize": report.get("configuration", {}).get("shardSize"),
        "deviceCount": report.get("configuration", {}).get("deviceCount"),
        "a4ReportSha256": sha256_file(report_path),
        "evidenceFiles": {
            "reportFile": archive_relative_path(evidence_root, report_path, "reportFile"),
            "reportMarkdownFile": archive_relative_path(
                evidence_root, report_markdown_path, "reportMarkdownFile"),
            "reportMarkdownSha256": sha256_file(report_markdown_path),
        },
        # A4 report 不含凭据；完整保留 checks/per-shard stats，中央不可只信任一个 PASS 字符串。
        "a4Report": report,
        "distributedPlanSha256": plan_sha,
        "artifacts": artifact_hashes,
        "hostGeneratorResources": report.get("hostGeneratorResources"),
        "clockSamples": report.get("clockSamples"),
        "shards": shard_evidence,
        # messageId 不含凭据/载荷，保留明细才能证明跨主机全局唯一而非比较不可靠的局部计数。
        "propertyMessageIds": all_message_ids,
    }


def load_plan(plan_path: Path) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """读取冻结的 host 列表；运行中不得用已到达文件反推应到 host。"""
    plan = read_object(plan_path)
    if plan.get("schemaVersion") != 1:
        raise CoordinationError("跨主机计划 schemaVersion 必须为 1")
    require_identity(plan.get("runId"), "runId")
    if not SHA256.fullmatch(str(plan.get("environmentFingerprint", ""))):
        raise CoordinationError("计划 environmentFingerprint 必须是 SHA-256")
    if plan.get("targetDeviceCount") != 10000:
        raise CoordinationError("C3e 整组计划 targetDeviceCount 必须精确为 10000")
    artifacts = plan.get("artifacts")
    if (not isinstance(artifacts, dict)
            or any(not SHA256.fullmatch(str(artifacts.get(key, "")))
                   for key in ("simulatorJarSha256", "a4RunnerSha256"))):
        raise CoordinationError("计划 artifacts 必须含 simulatorJarSha256/a4RunnerSha256")
    hosts = plan.get("hosts")
    if not isinstance(hosts, list) or not hosts:
        raise CoordinationError("发生器计划至少需要一个 host")
    ids = [require_identity(host.get("hostId") if isinstance(host, dict) else None, "hostId")
           for host in hosts]
    if len(ids) != len(set(ids)):
        raise CoordinationError("跨主机计划 hostId 重复")
    planned_globals: list[str] = []
    for host in hosts:
        shard_ids = host.get("shardIds")
        if not isinstance(shard_ids, list) or not shard_ids:
            raise CoordinationError(f"host {host['hostId']} 必须显式列出非空 shardIds")
        normalized = [require_identity(shard_id, "shardId") for shard_id in shard_ids]
        if len(normalized) != len(set(normalized)):
            raise CoordinationError(f"host {host['hostId']} shardIds 重复")
        validate_a4_argv(host.get("a4Argv"), host["hostId"])
        argv = host["a4Argv"]
        try:
            device_count = int(cli_option(argv, "--device-count") or "")
            shard_size = int(cli_option(argv, "--shard-size") or "")
        except ValueError as exception:
            raise CoordinationError(f"host {host['hostId']} device-count/shard-size 非整数") from exception
        prefix = cli_option(argv, "--shard-id-prefix")
        run_id = cli_option(argv, "--run-id")
        output_dir = cli_option(argv, "--output-dir")
        if (run_id != plan["runId"] or prefix != host["hostId"]
                or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,47}", prefix or "") is None
                or not 1 <= shard_size <= 1000
                or not isinstance(host.get("deviceCount"), int)
                or isinstance(host.get("deviceCount"), bool)
                or host.get("deviceCount") != device_count or device_count <= 0
                or cli_option(argv, "--credentials-file") is not None):
            raise CoordinationError(f"host {host['hostId']} A4 run/device/shard/prefix/匿名资格参数不一致")
        expected_shards = [f"{prefix}-shard-{index:03d}"
                           for index in range((device_count + shard_size - 1) // shard_size)]
        if normalized != expected_shards:
            raise CoordinationError(f"host {host['hostId']} shardIds 不能由 A4 参数精确推导")
        if (output_dir is None
                or host.get("reportPath") != (Path(output_dir) / "qualification-report.json").as_posix()
                or host.get("manifestRoot") != (Path(output_dir) / "manifests").as_posix()):
            raise CoordinationError(f"host {host['hostId']} reportPath/manifestRoot 与 A4 output-dir 不一致")
        timeout = host.get("a4TimeoutSeconds")
        if not isinstance(timeout, int) or isinstance(timeout, bool) or timeout <= 0:
            raise CoordinationError(f"host {host['hostId']} a4TimeoutSeconds 必须为正整数")
        require_relative_path(host.get("reportPath"), f"host {host['hostId']} reportPath")
        require_relative_path(host.get("manifestRoot"), f"host {host['hostId']} manifestRoot")
        planned_globals.extend(f"{host['hostId']}/{shard_id}" for shard_id in normalized)
    if len(planned_globals) < 2 or len(planned_globals) != len(set(planned_globals)):
        raise CoordinationError("整组计划须至少含两个全局唯一并发分片")
    if sum(host["deviceCount"] for host in hosts) != plan["targetDeviceCount"]:
        raise CoordinationError("各 host deviceCount 合计必须精确为 10000")
    return plan, hosts


def aggregate_group(plan_path: Path, envelope_dir: Path) -> dict[str, Any]:
    """按计划精确聚合所有 host；多、少、重复、漂移均拒绝，不以平均值掩盖单机失败。"""
    plan, hosts = load_plan(plan_path)
    run_id = plan["runId"]
    fingerprint = plan["environmentFingerprint"]
    plan_sha = sha256_file(plan_path)
    expected_ids = {host["hostId"] for host in hosts}
    actual_paths = list(envelope_dir.glob("*.host-envelope.json"))
    envelopes = [read_object(path) for path in actual_paths]
    actual_ids = [envelope.get("hostId") for envelope in envelopes]
    if set(actual_ids) != expected_ids or len(actual_ids) != len(expected_ids):
        raise CoordinationError(f"host envelope 集合不匹配 expected={sorted(expected_ids)} actual={sorted(map(str, actual_ids))}")
    all_shards: list[str] = []
    all_messages: list[str] = []
    shard_sizes: list[int] = []
    device_counts: list[int] = []
    host_results: list[dict[str, Any]] = []
    for envelope in envelopes:
        host_id = require_identity(envelope.get("hostId"), "hostId")
        if (envelope.get("schemaVersion") != 1 or envelope.get("runId") != run_id
                or envelope.get("environmentFingerprint") != fingerprint):
            raise CoordinationError(f"host {host_id} schema/runId/environmentFingerprint 漂移")
        if (envelope.get("distributedPlanSha256") != plan_sha
                or envelope.get("artifacts") != plan["artifacts"]):
            raise CoordinationError(f"host {host_id} distributed plan/artifact SHA 漂移")
        if envelope.get("result") not in {"PASS", "FAIL"}:
            raise CoordinationError(f"host {host_id} result 不受支持")
        embedded = envelope.get("a4Report")
        if not isinstance(embedded, dict) or (embedded.get("schemaVersion"), embedded.get("runId"),
                                              embedded.get("result")) != (1, run_id, envelope["result"]):
            raise CoordinationError(f"host {host_id} 嵌入 A4 report 身份不一致")
        embedded_checks = embedded.get("checks")
        if not isinstance(embedded_checks, list) or not embedded_checks \
                or any(not isinstance(check, dict) or not isinstance(check.get("passed"), bool)
                       for check in embedded_checks):
            raise CoordinationError(f"host {host_id} 嵌入 A4 checks 缺失或畸形")
        recomputed_result = "PASS" if all(check["passed"] for check in embedded_checks) else "FAIL"
        embedded_fingerprint = embedded.get("fingerprint", {})
        if (recomputed_result != envelope["result"]
                or embedded_fingerprint.get("jarSha256") != plan["artifacts"]["simulatorJarSha256"]
                or embedded_fingerprint.get("runnerSha256") != plan["artifacts"]["a4RunnerSha256"]):
            raise CoordinationError(f"host {host_id} A4 checks/result/artifact 不自洽")
        shards = envelope.get("shards")
        messages = envelope.get("propertyMessageIds")
        if not isinstance(shards, list) or not shards or not isinstance(messages, list):
            raise CoordinationError(f"host {host_id} 分片或 manifest 明细缺失")
        if envelope["result"] == "PASS" and not messages:
            raise CoordinationError(f"host {host_id} PASS manifest 明细为空")
        global_ids = [shard.get("globalShardId") for shard in shards if isinstance(shard, dict)]
        expected_globals = [f"{host_id}/{shard.get('shardId')}" for shard in shards if isinstance(shard, dict)]
        if global_ids != expected_globals or len(global_ids) != len(shards):
            raise CoordinationError(f"host {host_id} globalShardId 不可重算")
        if len(global_ids) != len(set(global_ids)):
            raise CoordinationError(f"host {host_id} globalShardId 重复")
        planned_host = next(host for host in hosts if host["hostId"] == host_id)
        planned_globals = [f"{host_id}/{shard_id}" for shard_id in planned_host["shardIds"]]
        if sorted(global_ids) != sorted(planned_globals):
            raise CoordinationError(f"host {host_id} 实际 shard 集合与计划不一致")
        shard_size = envelope.get("qualifiedShardSize")
        if not isinstance(shard_size, int) or isinstance(shard_size, bool) or not 1 <= shard_size <= 1000:
            raise CoordinationError(f"host {host_id} qualifiedShardSize 非法")
        shard_sizes.append(shard_size)
        device_count = envelope.get("deviceCount")
        shard_device_counts = [shard.get("deviceCount") for shard in shards]
        if (not isinstance(device_count, int) or isinstance(device_count, bool) or device_count <= 0
                or any(not isinstance(count, int) or isinstance(count, bool) or count <= 0
                       for count in shard_device_counts)
                or sum(shard_device_counts) != device_count):
            raise CoordinationError(f"host {host_id} deviceCount 与分片不一致")
        device_counts.append(device_count)
        all_shards.extend(global_ids)
        all_messages.extend(messages)
        host_results.append({"hostId": host_id, "result": envelope["result"],
                             "shardCount": len(shards), "messageCount": len(messages)})
    if len(all_shards) != len(set(all_shards)):
        raise CoordinationError("globalShardId 重复")
    if len(all_messages) != len(set(all_messages)):
        raise CoordinationError("跨主机 property messageId 重复")
    if len(set(shard_sizes)) != 1:
        raise CoordinationError(f"各 host qualifiedShardSize 不一致: {sorted(set(shard_sizes))}")
    qualified_shard_size = shard_sizes[0]
    target_devices = plan["targetDeviceCount"]
    expected_shards = (target_devices + qualified_shard_size - 1) // qualified_shard_size
    if sum(device_counts) != target_devices or len(all_shards) != expected_shards:
        raise CoordinationError("整组设备数/分片数不满足 ceil(10000 / qualifiedShardSize)")
    result = "PASS" if all(host["result"] == "PASS" for host in host_results) else "FAIL"
    return {"schemaVersion": 1, "runId": run_id, "environmentFingerprint": fingerprint,
            "distributedPlanFile": plan_path.name, "distributedPlanSha256": plan_sha,
            "artifacts": plan["artifacts"],
            "result": result, "hostCount": len(host_results), "shardCount": len(all_shards),
            "propertyMessageCount": len(all_messages),
            # 整组只能采用所有 host 都证明过的最小安全分片值，不能用平均数抬高容量。
            "targetDeviceCount": target_devices, "qualifiedShardSize": qualified_shard_size,
            "hosts": sorted(host_results, key=lambda item: item["hostId"]),
            "globalShardIds": sorted(all_shards)}


def read_phase_signal(path: Path, plan: dict[str, Any], host_id: str, phase: str) -> dict[str, Any]:
    """核验一个不可变阶段信号的完整身份，禁止旧 run 或另一环境误放行。"""
    signal = read_object(path)
    if (signal.get("schemaVersion") != 1 or signal.get("runId") != plan["runId"]
            or signal.get("environmentFingerprint") != plan["environmentFingerprint"]
            or signal.get("hostId") != host_id or signal.get("phase") != phase):
        raise CoordinationError(f"{path.name} 阶段身份不一致")
    return signal


def wait_host_phase(signal_dir: Path, plan: dict[str, Any], hosts: list[dict[str, Any]],
                    phase: str, deadline: float, poll_seconds: float) -> list[dict[str, Any]]:
    """只等待当前阶段一次且受绝对 deadline 约束；不会回退或循环握手。"""
    suffix = phase.lower()
    while True:
        paths = {host["hostId"]: signal_dir / f"{host['hostId']}.{suffix}.json" for host in hosts}
        if phase == "COMPLETE":
            for host_id, path in paths.items():
                if path.is_file():
                    signal = read_phase_signal(path, plan, host_id, phase)
                    if signal.get("outcome") == "ERROR":
                        raise CoordinationError(f"host {host_id} 提前报告 ERROR: {signal.get('error')}")
        if all(path.is_file() for path in paths.values()):
            return [read_phase_signal(path, plan, host_id, phase) for host_id, path in paths.items()]
        if time.monotonic() >= deadline:
            missing = sorted(host_id for host_id, path in paths.items() if not path.is_file())
            raise CoordinationError(f"等待 {phase} 超时，缺 host={missing}")
        time.sleep(min(poll_seconds, max(0.0, deadline - time.monotonic())))


def coordinate(plan_path: Path, signal_dir: Path, ready_timeout: float,
               complete_timeout: float, poll_seconds: float) -> dict[str, Any]:
    """READY -> START -> COMPLETE 单调推进；每个等待窗口独立有界且无反向 ACK。"""
    plan, hosts = load_plan(plan_path)
    ready = wait_host_phase(signal_dir, plan, hosts, "READY",
                            time.monotonic() + ready_timeout, poll_seconds)
    start = {"schemaVersion": 1, "runId": plan["runId"],
             "environmentFingerprint": plan["environmentFingerprint"],
             "phase": "START", "readyHosts": sorted(item["hostId"] for item in ready),
             # START 内容必须由计划和 READY 集合唯一决定，watchdog 重启才可安全幂等恢复。
             "coordinationVersion": 1}
    write_json_atomic(signal_dir / "controller.start.json", start, immutable=True)
    completed = wait_host_phase(signal_dir, plan, hosts, "COMPLETE",
                                time.monotonic() + complete_timeout, poll_seconds)
    if any(item.get("outcome") not in {"PASS", "FAIL"} for item in completed):
        raise CoordinationError("COMPLETE outcome 必须为 PASS/FAIL")
    return {"schemaVersion": 1, "runId": plan["runId"],
            "environmentFingerprint": plan["environmentFingerprint"], "phase": "COMPLETE",
            "outcome": "PASS" if all(item["outcome"] == "PASS" for item in completed) else "FAIL",
            "hosts": sorted({item["hostId"]: item["outcome"] for item in completed}.items())}


def wait_controller_start(signal_dir: Path, plan: dict[str, Any], hosts: list[dict[str, Any]],
                          deadline: float, poll_seconds: float) -> dict[str, Any]:
    """host 只消费一次 START；身份或 READY 集合漂移立即拒绝。"""
    path = signal_dir / "controller.start.json"
    expected_hosts = sorted(host["hostId"] for host in hosts)
    while True:
        if path.is_file():
            start = read_object(path)
            if (start.get("schemaVersion") != 1 or start.get("runId") != plan["runId"]
                    or start.get("environmentFingerprint") != plan["environmentFingerprint"]
                    or start.get("phase") != "START" or start.get("readyHosts") != expected_hosts
                    or start.get("coordinationVersion") != 1):
                raise CoordinationError("controller START 身份/READY 集合不一致")
            return start
        if time.monotonic() >= deadline:
            raise CoordinationError("等待 controller START 超时")
        time.sleep(min(poll_seconds, max(0.0, deadline - time.monotonic())))


def host_worker(plan_path: Path, host_id: str, signal_dir: Path, evidence_dir: Path,
                start_timeout: float, poll_seconds: float) -> tuple[dict[str, Any], int]:
    """READY 后执行一次有界 A4，归档 envelope 并发布唯一 COMPLETE。"""
    host_id = require_identity(host_id, "hostId")
    try:
        plan, hosts = load_plan(plan_path)
    except Exception as exception:  # noqa: BLE001 - 可读身份下的 plan 合同错误也须发布 ERROR
        raw = read_object(plan_path)
        common = {"schemaVersion": 1, "runId": raw.get("runId"),
                  "environmentFingerprint": raw.get("environmentFingerprint"), "hostId": host_id}
        signal = {**common, "phase": "COMPLETE", "outcome": "ERROR",
                  "errorType": type(exception).__name__, "error": str(exception)}
        write_json_atomic(signal_dir / f"{host_id}.complete.json", signal, immutable=True)
        return signal, 2
    host = next((item for item in hosts if item["hostId"] == host_id), None)
    if host is None:
        raise CoordinationError(f"host {host_id} 不在 distributed plan")
    common = {"schemaVersion": 1, "runId": plan["runId"],
              "environmentFingerprint": plan["environmentFingerprint"], "hostId": host_id}
    write_json_atomic(signal_dir / f"{host_id}.ready.json", {**common, "phase": "READY"}, immutable=True)
    try:
        wait_controller_start(signal_dir, plan, hosts, time.monotonic() + start_timeout, poll_seconds)
        working_dir = plan_path.resolve().parent
        report_path = resolve_plan_path(working_dir, host["reportPath"],
                                        f"host {host_id} reportPath")
        manifest_root = resolve_plan_path(working_dir, host["manifestRoot"],
                                          f"host {host_id} manifestRoot")
        # 同 run 的旧报告或 manifest 会让失败/空执行伪装成成功；正式执行必须从干净输出边界开始。
        if report_path.exists() or (manifest_root / plan["runId"]).exists():
            raise CoordinationError("A4 启动前已存在同 run 报告或 manifest")
        try:
            completed = subprocess.run(host["a4Argv"], cwd=working_dir, shell=False, check=False,
                                       timeout=host["a4TimeoutSeconds"])
        except subprocess.TimeoutExpired as exception:
            raise CoordinationError(f"A4 执行超时 {host['a4TimeoutSeconds']}s") from exception
        envelope = build_host_envelope(report_path, manifest_root, host_id,
                                       plan_path=plan_path)
        if ([item.get("shardId") for item in envelope.get("shards", [])] != host["shardIds"]
                or envelope.get("deviceCount") != host["deviceCount"]
                or envelope.get("qualifiedShardSize")
                != int(cli_option(host["a4Argv"], "--shard-size") or 0)):
            raise CoordinationError("A4 envelope shard/device/qualifiedShardSize 与 host plan 不一致")
        expected_exit = 0 if envelope["result"] == "PASS" else 1
        if completed.returncode != expected_exit:
            raise CoordinationError(f"A4 exit={completed.returncode} 与 report {envelope['result']} 不一致")
        envelope_path = evidence_dir / "host-envelopes" / f"{host_id}.host-envelope.json"
        write_json_atomic(envelope_path, envelope, immutable=True)
        outcome = envelope["result"]
        signal = {**common, "phase": "COMPLETE", "outcome": outcome,
                  "hostEnvelopeSha256": sha256_file(envelope_path)}
        write_json_atomic(signal_dir / f"{host_id}.complete.json", signal, immutable=True)
        return signal, 0 if outcome == "PASS" else 1
    except Exception as exception:  # noqa: BLE001 - 先发布 ERROR，controller 才能立即终止
        signal = {**common, "phase": "COMPLETE", "outcome": "ERROR",
                  "errorType": type(exception).__name__, "error": str(exception)}
        write_json_atomic(signal_dir / f"{host_id}.complete.json", signal, immutable=True)
        return signal, 2


def parse_args() -> argparse.Namespace:
    """提供 envelope、aggregate、coordinate、worker 四个独立动作。"""
    parser = argparse.ArgumentParser(description="G1-C3e 跨主机发生器协调")
    sub = parser.add_subparsers(dest="action", required=True)
    envelope = sub.add_parser("envelope")
    envelope.add_argument("--report", type=Path, required=True)
    envelope.add_argument("--manifest-root", type=Path, required=True)
    envelope.add_argument("--host-id", required=True)
    envelope.add_argument("--plan", type=Path, required=True)
    envelope.add_argument("--output", type=Path, required=True)
    aggregate_parser = sub.add_parser("aggregate")
    aggregate_parser.add_argument("--plan", type=Path, required=True)
    aggregate_parser.add_argument("--envelope-dir", type=Path, required=True)
    aggregate_parser.add_argument("--output", type=Path, required=True)
    coordinator = sub.add_parser("coordinate")
    coordinator.add_argument("--plan", type=Path, required=True)
    coordinator.add_argument("--signal-dir", type=Path, required=True)
    coordinator.add_argument("--ready-timeout-seconds", type=float, default=300)
    coordinator.add_argument("--complete-timeout-seconds", type=float, default=1200)
    coordinator.add_argument("--poll-seconds", type=float, default=1)
    coordinator.add_argument("--output", type=Path, required=True)
    worker = sub.add_parser("worker")
    worker.add_argument("--plan", type=Path, required=True)
    worker.add_argument("--host-id", required=True)
    worker.add_argument("--signal-dir", type=Path, required=True)
    worker.add_argument("--evidence-dir", type=Path, required=True)
    worker.add_argument("--start-timeout-seconds", type=float, default=300)
    worker.add_argument("--poll-seconds", type=float, default=1)
    worker.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    """任何合同错误都输出 ERROR 证据并以 2 退出，host FAIL 则以 1 退出。"""
    args = parse_args()
    try:
        if args.action == "envelope":
            result = build_host_envelope(args.report, args.manifest_root, args.host_id,
                                         plan_path=args.plan)
        elif args.action == "aggregate":
            result = aggregate_group(args.plan, args.envelope_dir)
        elif args.action == "coordinate":
            result = coordinate(args.plan, args.signal_dir, args.ready_timeout_seconds,
                                args.complete_timeout_seconds, args.poll_seconds)
        else:
            result, exit_code = host_worker(args.plan, args.host_id, args.signal_dir,
                                            args.evidence_dir, args.start_timeout_seconds,
                                            args.poll_seconds)
            write_json_atomic(args.output, result)
            return exit_code
        write_json_atomic(args.output, result)
        return 0 if result.get("result", result.get("outcome")) == "PASS" else 1
    except Exception as exception:  # noqa: BLE001 - 协调器必须留下可归档 ERROR
        write_json_atomic(args.output, {"schemaVersion": 1, "result": "ERROR",
                                        "errorType": type(exception).__name__, "error": str(exception)})
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
