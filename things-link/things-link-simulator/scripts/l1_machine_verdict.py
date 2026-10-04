#!/usr/bin/env python3
"""G1-C3d L1 当前正确性、同指纹五次中位数与连续回退机器裁决。"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import re
import statistics
import sys
import traceback
from pathlib import Path
from typing import Any, Callable


UPLINK_HISTOGRAM = "thingslink_ingestion_uplink_end_to_end_seconds_bucket"
COMMAND_HISTOGRAM = "thingslink_command_acceptance_seconds_bucket"
TIMESERIES_WRITES = "thingslink_telemetry_timeseries_write_total"
HTTP_REQUESTS = "http_server_requests_seconds_count"
HIKARI_TIMEOUTS = "hikaricp_connections_timeout_total"
METRIC_KEYS = ("uplinkP99Seconds", "commandAcceptanceP95Seconds", "steadyLagPeak",
               "sutCpuAveragePercent", "timeSeriesWriteFailureRate")
TARGET_PROMETHEUS_METRICS = {
    UPLINK_HISTOGRAM, COMMAND_HISTOGRAM, TIMESERIES_WRITES, HTTP_REQUESTS, HIKARI_TIMEOUTS,
}
SAMPLE_RE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+([^\s]+)(?:\s+\d+)?$")
LABEL_RE = re.compile(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"')


class EvidenceError(RuntimeError):
    """证据缺失、损坏或无法形成机器口径。"""


def parse_args() -> argparse.Namespace:
    """读取本轮证据与只读历史目录。"""
    parser = argparse.ArgumentParser(description="G1-C3d L1 机器裁决")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--history-dir", type=Path, required=True)
    parser.add_argument("--runner-outcome", choices=("success", "failure", "cancelled", "skipped"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    parser.add_argument("--job-summary", type=Path)
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    """读取必需 JSON 对象；空文件/数组均不接受。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"无法读取 {path.name}: {exception}") from exception
    if not isinstance(value, dict):
        raise EvidenceError(f"{path.name} 顶层必须是对象")
    return value


def read_json_array(path: Path) -> list[Any]:
    """读取顶层数组证据；与对象入口分开，避免静默接受错误 schema。"""
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"无法读取 {path.name}: {exception}") from exception
    if not isinstance(value, list):
        raise EvidenceError(f"{path.name} 顶层必须是数组")
    return value


def parse_labels(raw: str | None) -> dict[str, str]:
    """解析 Prometheus 文本标签；当前目标指标不包含嵌套结构。"""
    if not raw:
        return {}
    return {match.group(1): bytes(match.group(2), "utf-8").decode("unicode_escape")
            for match in LABEL_RE.finditer(raw)}


def parse_prometheus(path: Path) -> dict[str, list[tuple[dict[str, str], float]]]:
    """解析数值样本，拒绝 NaN/Inf；HELP/TYPE 注释不参与判定。"""
    metrics: dict[str, list[tuple[dict[str, str], float]]] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise EvidenceError(f"Prometheus 快照缺失: {path.name}") from exception
    for line in lines:
        if not line or line.startswith("#"):
            continue
        match = SAMPLE_RE.match(line)
        if not match:
            continue
        name = match.group(1)
        if name not in TARGET_PROMETHEUS_METRICS:
            continue
        try:
            value = float(match.group(3))
        except ValueError:
            continue
        if not math.isfinite(value):
            raise EvidenceError(f"{path.name} 含非有限指标 {name}")
        metrics.setdefault(name, []).append((parse_labels(match.group(2)), value))
    return metrics


def aggregate(metrics: dict[str, list[tuple[dict[str, str], float]]], name: str,
              predicate: Callable[[dict[str, str]], bool] = lambda _: True) -> float:
    """汇总一个低基数指标的匹配时序。"""
    return sum(value for labels, value in metrics.get(name, []) if predicate(labels))


def counter_delta(start: dict[str, list[tuple[dict[str, str], float]]],
                  end: dict[str, list[tuple[dict[str, str], float]]], name: str,
                  predicate: Callable[[dict[str, str]], bool] = lambda _: True) -> float:
    """计算进程内 counter 增量；负值表示重启或错误拼接快照。"""
    value = aggregate(end, name, predicate) - aggregate(start, name, predicate)
    if value < -1e-9:
        raise EvidenceError(f"{name} 增量为负，疑似进程重启")
    return max(0.0, value)


def nested_counter_delta(start: dict[str, Any], end: dict[str, Any], section: str, key: str) -> float:
    """计算 EMQX 聚合 metrics 的 counter 增量，并拒绝缺字段、非数值与计数倒退。"""
    try:
        before = float(start[section][key])
        after = float(end[section][key])
    except (KeyError, TypeError, ValueError) as exception:
        raise EvidenceError(f"EMQX {section}.{key} 计数不可读") from exception
    if not math.isfinite(before) or not math.isfinite(after) or after < before:
        raise EvidenceError(f"EMQX {section}.{key} 计数非有限或倒退")
    return after - before


def durable_republish_deltas(start: dict[str, Any], end: dict[str, Any]) -> dict[str, float | str]:
    """校验当前 Broker 接入 schema 并计算 durable republish 规则增量。"""
    for label, document in (("start", start), ("final", end)):
        if document.get("schemaVersion") != 2 or document.get("ingressMode") != "durable-republish":
            raise EvidenceError(f"EMQX {label} 快照不是 durable-republish schema v2")
    return {
        "ingressMode": "durable-republish",
        "matched": nested_counter_delta(start, end, "rule", "matched"),
        "actionsTotal": nested_counter_delta(start, end, "rule", "actions.total"),
        "actionsSuccess": nested_counter_delta(start, end, "rule", "actions.success"),
        "actionsFailed": nested_counter_delta(start, end, "rule", "actions.failed"),
    }


def histogram_quantile(start: dict[str, list[tuple[dict[str, str], float]]],
                       end: dict[str, list[tuple[dict[str, str], float]]],
                       name: str, quantile: float) -> tuple[float, float, dict[str, float | bool]]:
    """计算 bucket 增量分位数，并显式返回有限量程是否覆盖目标排位。"""
    buckets: dict[float, float] = {}
    for labels, value in end.get(name, []):
        upper_raw = labels.get("le")
        if upper_raw is None:
            continue
        upper = math.inf if upper_raw == "+Inf" else float(upper_raw)
        buckets[upper] = buckets.get(upper, 0.0) + value
    for labels, value in start.get(name, []):
        upper_raw = labels.get("le")
        if upper_raw is None:
            continue
        upper = math.inf if upper_raw == "+Inf" else float(upper_raw)
        buckets[upper] = buckets.get(upper, 0.0) - value
    if math.inf not in buckets or buckets[math.inf] <= 0:
        raise EvidenceError(f"{name} +Inf bucket 无稳态样本")
    if any(value < -1e-9 for value in buckets.values()):
        raise EvidenceError(f"{name} bucket 增量为负")
    finite = sorted((upper, max(0.0, count)) for upper, count in buckets.items() if math.isfinite(upper))
    if not finite:
        raise EvidenceError(f"{name} 缺少有限 bucket")
    total = buckets[math.inf]
    previous = 0.0
    for upper, count in finite:
        if count + 1e-9 < previous:
            raise EvidenceError(f"{name} bucket 在 le={upper} 处非单调")
        previous = count
    if total + 1e-9 < finite[-1][1]:
        raise EvidenceError(f"{name} +Inf bucket 小于最大有限 bucket")
    rank = quantile * total
    range_diagnostics: dict[str, float | bool] = {
        "quantile": quantile,
        "total": total,
        "rank": rank,
        "maxFiniteUpper": finite[-1][0],
        "maxFiniteCount": finite[-1][1],
        "overflowCount": max(0.0, total - finite[-1][1]),
        "overflowRate": max(0.0, total - finite[-1][1]) / total,
        # 等于排位时目标分位仍落在最大有限桶内；只有严格小于才算饱和。
        "saturated": finite[-1][1] + 1e-9 < rank,
    }
    previous_upper = 0.0
    previous_count = 0.0
    for upper, count in finite:
        if count >= rank:
            bucket_count = count - previous_count
            if bucket_count <= 0:
                return upper, total, range_diagnostics
            value = previous_upper + (upper - previous_upper) * (rank - previous_count) / bucket_count
            return value, total, range_diagnostics
        previous_upper, previous_count = upper, count
    # 保留最大有限上界只为失败报告可读；saturated check 会阻止截顶值进入性能历史。
    return finite[-1][0], total, range_diagnostics


def add_check(checks: list[dict[str, Any]], name: str, passed: bool,
              actual: Any, expected: str, category: str = "correctness") -> None:
    """追加稳定结构的机器 check。"""
    checks.append({"name": name, "category": category, "passed": bool(passed),
                   "actual": actual, "expected": expected})


def parse_evidence_time(value: Any, field: str, required: bool) -> None:
    """校验 PostgreSQL JSON 时间；允许状态机尚未经过的生命周期时间为 null。"""
    if value is None and not required:
        return
    if not isinstance(value, str):
        raise EvidenceError(f"逐命令证据 {field} 必须是 RFC3339 字符串")
    try:
        dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise EvidenceError(f"逐命令证据 {field} 时间不可解析") from exception


def reconcile_command_evidence(evidence: dict[str, Any], submissions: list[Any],
                               summary: dict[str, Any]) -> dict[str, Any]:
    """逐条核对提交、command、attempt、Outbox 与聚合；结构漂移直接视为证据 ERROR。"""
    if evidence.get("schemaVersion") != 1 or summary.get("schemaVersion") != 2:
        raise EvidenceError("逐命令证据或命令聚合 schemaVersion 不受支持")
    if evidence.get("runId") != summary.get("runId") or evidence.get("expected") != 600:
        raise EvidenceError("逐命令证据 runId/expected 与命令聚合不一致")
    commands = evidence.get("commands")
    if not isinstance(commands, list) or len(commands) != 600 or len(submissions) != 600:
        raise EvidenceError("逐命令证据与提交记录必须各含 600 条")
    submitted_by_sequence: dict[int, dict[str, Any]] = {}
    for raw in submissions:
        if not isinstance(raw, dict):
            raise EvidenceError("command-submissions.json 含非对象记录")
        sequence = int(raw.get("sequence"))
        if sequence in submitted_by_sequence:
            raise EvidenceError(f"命令提交 sequence 重复: {sequence}")
        submitted_by_sequence[sequence] = raw
    if sorted(submitted_by_sequence) != list(range(600)):
        raise EvidenceError("命令提交 sequence 必须连续为 0..599")

    command_ids: set[str] = set()
    outbox_event_ids: set[str] = set()
    reply_ids: list[str] = []
    status_counts: dict[str, int] = {}
    attempts_total = 0
    succeeded_attempts = 0
    for sequence, command in enumerate(commands):
        if not isinstance(command, dict) or command.get("sequence") != sequence:
            raise EvidenceError(f"逐命令证据 sequence 在 {sequence} 处断裂")
        submission = submitted_by_sequence[sequence]
        command_id = command.get("commandId")
        if (command_id != submission.get("commandId")
                or command.get("deviceId") != submission.get("deviceId")
                or command.get("acceptedStatus") != submission.get("acceptedStatus")):
            raise EvidenceError(f"逐命令证据在 sequence={sequence} 与提交记录不一致")
        if not isinstance(command_id, str) or command_id in command_ids:
            raise EvidenceError(f"逐命令 commandId 缺失或重复: {command_id}")
        command_ids.add(command_id)
        terminal = command.get("terminal")
        attempts = command.get("attempts")
        if not isinstance(terminal, dict) or not isinstance(attempts, list):
            raise EvidenceError(f"命令 {command_id} 缺 terminal/attempts")
        status = terminal.get("status")
        if status not in {"ACCEPTED", "DISPATCHED", "ACKNOWLEDGED", "SUCCEEDED", "FAILED", "TIMED_OUT"}:
            raise EvidenceError(f"命令 {command_id} 状态不受支持: {status}")
        status_counts[status] = status_counts.get(status, 0) + 1
        attempt_count = terminal.get("attemptCount")
        if (not isinstance(attempt_count, int) or not 1 <= attempt_count <= 3
                or attempt_count != len(attempts)
                or terminal.get("maxAttempts") != 3):
            raise EvidenceError(f"命令 {command_id} attemptCount/maxAttempts 与明细不一致")
        parse_evidence_time(terminal.get("acceptedAt"), "terminal.acceptedAt", True)
        parse_evidence_time(terminal.get("dispatchedAt"), "terminal.dispatchedAt", False)
        parse_evidence_time(terminal.get("acknowledgedAt"), "terminal.acknowledgedAt", False)
        parse_evidence_time(terminal.get("completedAt"), "terminal.completedAt",
                            status in {"SUCCEEDED", "FAILED", "TIMED_OUT"})
        for attempt_no, attempt in enumerate(attempts, start=1):
            if not isinstance(attempt, dict) or attempt.get("attemptNo") != attempt_no:
                raise EvidenceError(f"命令 {command_id} attemptNo 必须从 1 连续递增")
            outbox = attempt.get("outbox")
            if not isinstance(outbox, dict):
                raise EvidenceError(f"命令 {command_id} attempt {attempt_no} 缺 Outbox")
            event_id = outbox.get("eventId")
            if (outbox.get("aggregateType") != "DEVICE_COMMAND"
                    or outbox.get("aggregateId") != command_id
                    or outbox.get("eventType") != "DEVICE_COMMAND_DISPATCH"
                    or not isinstance(event_id, str) or event_id in outbox_event_ids):
                raise EvidenceError(f"命令 {command_id} attempt {attempt_no} Outbox 关联错误")
            outbox_event_ids.add(event_id)
            if attempt.get("status") not in {
                    "PENDING", "PUBLISHED", "ACKNOWLEDGED", "SUCCEEDED", "FAILED", "TIMED_OUT"}:
                raise EvidenceError(f"命令 {command_id} attempt {attempt_no} 状态非法")
            parse_evidence_time(attempt.get("createdAt"), "attempt.createdAt", True)
            parse_evidence_time(attempt.get("deadlineAt"), "attempt.deadlineAt", True)
            parse_evidence_time(attempt.get("publishedAt"), "attempt.publishedAt", False)
            parse_evidence_time(attempt.get("acknowledgedAt"), "attempt.acknowledgedAt", False)
            parse_evidence_time(attempt.get("completedAt"), "attempt.completedAt",
                                attempt.get("status") in {"SUCCEEDED", "FAILED", "TIMED_OUT"})
            parse_evidence_time(outbox.get("createdAt"), "outbox.createdAt", True)
            parse_evidence_time(outbox.get("publishedAt"), "outbox.publishedAt",
                                outbox.get("status") == "PUBLISHED")
            if outbox.get("status") not in {"PENDING", "PUBLISHED"}:
                raise EvidenceError(f"命令 {command_id} attempt {attempt_no} Outbox 状态非法")
            reply_id = attempt.get("replyMessageId")
            if reply_id is not None:
                if not isinstance(reply_id, str):
                    raise EvidenceError("replyMessageId 必须是字符串或 null")
                reply_ids.append(reply_id)
            attempts_total += 1
            succeeded_attempts += attempt.get("status") == "SUCCEEDED"
        if status == "SUCCEEDED":
            successful_attempts = [attempt for attempt in attempts if attempt.get("status") == "SUCCEEDED"]
            successful = successful_attempts[0] if len(successful_attempts) == 1 else {}
            if (terminal.get("failureCode") is not None or len(successful_attempts) != 1
                    or successful is not attempts[-1] or successful.get("errorCode") is not None
                    or not isinstance(successful.get("replyMessageId"), str)
                    or successful.get("outbox", {}).get("status") != "PUBLISHED"):
                raise EvidenceError(f"成功命令 {command_id} 与最终成功 attempt 不一致")

    if len(reply_ids) != len(set(reply_ids)):
        raise EvidenceError("逐命令证据 replyMessageId 不唯一")
    recomputed_database = {
        "commands": 600,
        "attempts": attempts_total,
        "succeededAttempts": succeeded_attempts,
        "replyMessageIds": len(reply_ids),
        "uniqueReplyMessageIds": len(set(reply_ids)),
    }
    expected_summary = {
        "submitted": 600,
        "uniqueCommandIds": 600,
        "terminal": sum(status_counts.get(status, 0)
                        for status in ("SUCCEEDED", "FAILED", "TIMED_OUT")),
        "statusCounts": status_counts,
        "database": recomputed_database,
    }
    if any(summary.get(key) != value for key, value in expected_summary.items()):
        raise EvidenceError("command-results.json 与逐命令证据重算聚合不一致")
    expected_passed = (status_counts == {"SUCCEEDED": 600}
                       and recomputed_database == {
                           "commands": 600, "attempts": 600, "succeededAttempts": 600,
                           "replyMessageIds": 600, "uniqueReplyMessageIds": 600}
                       and summary.get("sampleFailures") == [])
    if summary.get("passed") is not expected_passed:
        raise EvidenceError("command-results.json passed 与逐命令事实不一致")
    return {"passed": True, "commands": 600, "attempts": attempts_total,
            "singleAttemptSuccess": expected_passed,
            "replyMessageIds": len(reply_ids), "statusCounts": status_counts}


def reconcile_command_coordination(evidence_dir: Path, summary: dict[str, Any]) -> dict[str, Any]:
    """验证双信号身份、计数与证据摘要，禁止性能信号再次充当停机许可。"""
    performance = read_json(evidence_dir / "performance-complete.json")
    drain = read_json(evidence_dir / "command-drain-complete.json")
    evidence_path = evidence_dir / "command-evidence.json"
    evidence_sha256 = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
    expected_common = (performance.get("schemaVersion") == 1
                       and drain.get("schemaVersion") == 1
                       and performance.get("runId") == summary.get("runId")
                       and drain.get("runId") == summary.get("runId"))
    outcome_pair_valid = ((drain.get("outcome") == "COMPLETE" and summary.get("terminal") == 600)
                          or (drain.get("outcome") == "TIMEOUT"
                              and isinstance(summary.get("terminal"), int)
                              and summary.get("terminal") < 600))
    valid = (expected_common
             and performance.get("phase") == "PERFORMANCE_COMPLETE"
             and performance.get("outcome") == "COMPLETE"
             and performance.get("submitted") == 600
             and drain.get("phase") == "COMMAND_DRAIN_COMPLETE"
             and outcome_pair_valid
             and drain.get("submitted") == summary.get("submitted")
             and drain.get("terminal") == summary.get("terminal")
             and drain.get("succeeded") == summary.get("statusCounts", {}).get("SUCCEEDED", 0)
             and drain.get("evidenceSha256") == evidence_sha256)
    if not valid:
        raise EvidenceError("performance/command-drain 双信号与逐命令证据不一致")
    return {"passed": True, "performanceOutcome": performance["outcome"],
            "drainOutcome": drain["outcome"], "terminal": drain["terminal"]}


def load_samples(path: Path) -> list[dict[str, Any]]:
    """读取 JSONL 稳态样本并验证时间单调。"""
    try:
        samples = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    except (OSError, json.JSONDecodeError) as exception:
        raise EvidenceError(f"稳态样本不可读: {exception}") from exception
    if not samples:
        raise EvidenceError("稳态样本为空")
    epochs = [int(sample["epochMillis"]) for sample in samples]
    if any(right <= left for left, right in zip(epochs, epochs[1:])):
        raise EvidenceError("稳态样本时间不单调")
    return samples


def reconcile_lag_tsv(path: Path, samples: list[dict[str, Any]]) -> dict[str, Any]:
    """逐行核对冻结六列 TSV 与 JSONL 单一事实源，拒绝缺行、乱序和字段漂移。"""
    groups = ("things-link-ingestion-raw", "things-link-ingestion-normalized",
              "things-link-ingestion-processed")
    expected_header = ("sequence", "epochMillis", *groups, "totalLag")
    try:
        rows = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise EvidenceError(f"lag.tsv 不可读: {exception}") from exception
    if not rows or tuple(rows[0].split("\t")) != expected_header:
        raise EvidenceError("lag.tsv 表头不符合冻结六列合同")
    if len(rows) - 1 != len(samples):
        return {"passed": False, "jsonlRows": len(samples), "tsvRows": len(rows) - 1,
                "firstMismatchSequence": None}
    for index, (raw, sample) in enumerate(zip(rows[1:], samples)):
        fields = raw.split("\t")
        try:
            actual = tuple(int(value) for value in fields)
            lags = sample["groupLag"]
            expected = (int(sample["sequence"]), int(sample["epochMillis"]),
                        *(int(lags[group]) for group in groups), int(sample["totalLag"]))
        except (KeyError, TypeError, ValueError) as exception:
            raise EvidenceError(f"lag 证据第 {index + 1} 行不可解析") from exception
        if expected[-1] != sum(expected[2:5]) or len(fields) != len(expected) or actual != expected:
            return {"passed": False, "jsonlRows": len(samples), "tsvRows": len(rows) - 1,
                    "firstMismatchSequence": expected[0]}
    return {"passed": True, "jsonlRows": len(samples), "tsvRows": len(rows) - 1,
            "firstMismatchSequence": None}


def script_hashes(script_dir: Path) -> dict[str, str]:
    """脚本变化必须改变比较指纹，防止不同统计算法直接拼接历史。"""
    names = ("run-c3d-l1.sh", "a4_qualification.py", "l0_fixture.py",
             "l1_quota_prepare.py", "l1_steady_probe.py", "l1_history.py",
             "l1_machine_verdict.py")
    result = {}
    for name in names:
        path = script_dir / name
        if not path.is_file():
            raise EvidenceError(f"比较指纹脚本缺失: {name}")
        result[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def fingerprint(metadata: dict[str, Any], qualification: dict[str, Any], script_dir: Path) -> tuple[str, dict[str, Any]]:
    """只纳入明确冻结的环境/实现字段，run ID/commit 不应让每轮天然换指纹。"""
    required = ("runnerImageOs", "runnerImageVersion", "os", "machine", "logicalCpuCount",
                "hostMemoryBytes", "rootDiskBytes", "python", "java", "dockerClient", "dockerServer",
                "images", "backendJarSha256", "simulatorJarSha256", "load")
    missing = [key for key in required if metadata.get(key) in (None, "", {})]
    if missing:
        raise EvidenceError(f"run-metadata 缺少指纹字段: {missing}")
    # backend JAR SHA 必须归档但不能进入比较指纹：L1 正是要在同一测量环境下比较不同 SUT 版本；
    # 若把 SUT 自身哈希纳入指纹，每次代码变化都会重置五次 warmup，历史回归将永远失效。
    comparison_keys = tuple(key for key in required if key != "backendJarSha256")
    document = {key: metadata[key] for key in comparison_keys}
    # GitHub 已发布指纹保持原格式；本地 Runner 必须具有独立的比较命名空间。
    if metadata.get("executionEnvironment") == "local-docker":
        document["executionEnvironment"] = "local-docker"
    document["qualificationFingerprint"] = qualification.get("fingerprint")
    if not document["qualificationFingerprint"]:
        raise EvidenceError("资格报告缺少 fingerprint")
    document["scripts"] = script_hashes(script_dir)
    canonical = json.dumps(document, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(canonical).hexdigest(), document


def load_history(history_dir: Path) -> list[dict[str, Any]]:
    """索引必须明确 PASS；API 失败不能降级成首轮 warmup。"""
    index = read_json(history_dir / "history-index.json")
    if index.get("status") != "PASS":
        raise EvidenceError(f"历史读取失败: {index.get('error', 'unknown')}")
    reports = []
    for record in index.get("reports", []):
        report_path = history_dir / record["path"]
        report = read_json(report_path)
        if report.get("schemaVersion") != 1 or not isinstance(report.get("github"), dict):
            raise EvidenceError(f"历史报告结构不受支持: {report_path}")
        if report.get("validityStatus") == "VALID_PASS":
            if not report.get("comparisonFingerprint") or not isinstance(report.get("metrics"), dict):
                raise EvidenceError(f"VALID_PASS 历史报告缺少指纹/指标: {report_path}")
            if history_run_number(report, "runId") <= 0 or history_run_number(report, "runAttempt") <= 0:
                raise EvidenceError(f"VALID_PASS 历史报告缺少运行标识: {report_path}")
            missing_metrics = [key for key in METRIC_KEYS if key not in report["metrics"]]
            if missing_metrics:
                raise EvidenceError(f"VALID_PASS 历史报告缺少指标 {missing_metrics}: {report_path}")
        report["_historyArtifactId"] = record.get("artifactId")
        reports.append(report)
    # ERROR/INVALID_GENERATOR 报告可能在 run-metadata 生成前失败，github 字段因此合法地为 null。
    # 它们不进入性能基线，但仍须可归档；不能让排序异常阻断当前轮机器报告。
    reports.sort(key=lambda item: (history_run_number(item, "runId"),
                                   history_run_number(item, "runAttempt")), reverse=True)
    return reports


def history_run_number(report: dict[str, Any], field: str) -> int:
    """把历史运行标识规范化为非负整数；无效运行的 null/异常值排序到历史尾部。"""
    value = report.get("github", {}).get(field)
    if isinstance(value, bool):
        return 0
    if isinstance(value, int):
        return max(0, value)
    if isinstance(value, str) and value.isdecimal():
        return int(value)
    return 0


def relative_degraded(current: float, baseline: float) -> tuple[bool, float | None]:
    """严格大于 20% 才退化；零基线使用冻结的显式分支。"""
    if baseline == 0:
        return current > 0, None if current > 0 else 0.0
    change = (current - baseline) / baseline
    return change > 0.20, change


def performance_verdict(metrics: dict[str, float], history: list[dict[str, Any]],
                        comparison_fingerprint: str) -> dict[str, Any]:
    """最近五次有效成功取中位数，并按同一指标的相邻两次退化判回退。"""
    comparable = [report for report in history
                  if report.get("comparisonFingerprint") == comparison_fingerprint
                  and report.get("validityStatus") == "VALID_PASS"]
    if len(comparable) < 5:
        return {"status": "WARMUP", "comparableSuccessCount": len(comparable),
                "baselineRunIds": [], "baselines": {}, "comparisons": {}, "degradedMetrics": []}
    baseline_reports = comparable[:5]
    baselines = {key: statistics.median(float(report["metrics"][key]) for report in baseline_reports)
                 for key in METRIC_KEYS}
    comparisons = {}
    degraded = []
    for key in METRIC_KEYS:
        is_degraded, change = relative_degraded(float(metrics[key]), baselines[key])
        comparisons[key] = {"current": metrics[key], "baseline": baselines[key],
                            "relativeChange": change, "degraded": is_degraded}
        if is_degraded:
            degraded.append(key)
    previous_degraded = set(comparable[0].get("performance", {}).get("degradedMetrics", []))
    consecutive = sorted(set(degraded) & previous_degraded)
    if consecutive:
        status = "PERFORMANCE_REGRESSION"
    elif degraded:
        status = "PERFORMANCE_WARNING"
    else:
        status = "OK"
    return {"status": status, "comparableSuccessCount": len(comparable),
            "baselineRunIds": [report.get("github", {}).get("runId") for report in baseline_reports],
            "baselines": baselines, "comparisons": comparisons,
            "degradedMetrics": degraded, "consecutiveDegradedMetrics": consecutive,
            "previousComparableRunId": comparable[0].get("github", {}).get("runId")}


def evaluate(evidence_dir: Path, history_dir: Path, runner_outcome: str) -> dict[str, Any]:
    """形成完整裁决；尽量保留已读事实，但任何缺证都 fail-closed 为 ERROR。"""
    checks: list[dict[str, Any]] = []
    errors: list[str] = []
    qualification: dict[str, Any] = {}
    metadata: dict[str, Any] = {}
    metrics: dict[str, float] = {}
    comparison_fingerprint: str | None = None
    fingerprint_document: dict[str, Any] | None = None
    performance: dict[str, Any] = {"status": "NOT_EVALUATED"}
    history: list[dict[str, Any]] = []

    try:
        quota = read_json(evidence_dir / "quota-policy.json")
        quota_valid = (all(isinstance(quota.get(key), str) and quota[key]
                           for key in ("projectId", "tenantId", "policyId"))
                       and quota.get("policyCode") == "L1_NIGHTLY"
                       and quota.get("policyVersion") == 1
                       and quota.get("deviceCountLimit") == 2000
                       and quota.get("uplinkMessageDailyLimit") == 20000
                       and quota.get("timeSeriesPointDailyLimit") == 200000
                       and quota.get("downlinkMessageDailyLimit") == 1200
                       and quota.get("restApiRatePerMinute") == 1200
                       and quota.get("restApiReadRatePerMinute") == 1200
                       and quota.get("assignmentVersion") == 3)
        add_check(checks, "quotaPolicyBinding", quota_valid, quota,
                  "唯一 project/tenant，L1_NIGHTLY v1，设备 2000、上行 20000、时序点 200000、下行 1200、REST 读 1200/min，assignmentVersion=3")
    except EvidenceError as exception:
        errors.append(str(exception))
    try:
        qualification = read_json(evidence_dir / "qualification" / "qualification-report.json")
        add_check(checks, "generatorQualification", qualification.get("result") == "PASS",
                  qualification.get("result"), "PASS", "validity")
    except EvidenceError as exception:
        errors.append(str(exception))
    try:
        metadata = read_json(evidence_dir / "run-metadata.json")
    except EvidenceError as exception:
        errors.append(str(exception))

    if qualification.get("result") == "PASS":
        try:
            command = read_json(evidence_dir / "command-results.json")
            command_evidence = read_json(evidence_dir / "command-evidence.json")
            command_submissions = read_json_array(evidence_dir / "command-submissions.json")
            command_reconciliation = reconcile_command_evidence(
                command_evidence, command_submissions, command)
            command_coordination = reconcile_command_coordination(evidence_dir, command)
            reconciliation = read_json(evidence_dir / "fact-reconciliation.json")
            start = parse_prometheus(evidence_dir / "prometheus-start.txt")
            end = parse_prometheus(evidence_dir / "prometheus-end.txt")
            correctness_start = parse_prometheus(evidence_dir / "prometheus-correctness-start.txt")
            correctness_final = parse_prometheus(evidence_dir / "prometheus-correctness-final.txt")
            emqx_start = read_json(evidence_dir / "emqx-ingress-start.json")
            emqx_final = read_json(evidence_dir / "emqx-ingress-final.json")
            samples = load_samples(evidence_dir / "steady-samples.jsonl")
            uplink_p99, uplink_count, uplink_range = histogram_quantile(
                start, end, UPLINK_HISTOGRAM, 0.99)
            command_p95, command_count, command_range = histogram_quantile(
                start, end, COMMAND_HISTOGRAM, 0.95)
            lag_reconciliation = reconcile_lag_tsv(evidence_dir / "lag.tsv", samples)
            write_success = counter_delta(start, end, TIMESERIES_WRITES,
                                          lambda labels: labels.get("result") == "success")
            write_failure = counter_delta(start, end, TIMESERIES_WRITES,
                                          lambda labels: labels.get("result") == "failure")
            write_total = write_success + write_failure
            if write_total <= 0:
                raise EvidenceError("时序写入指标无稳态样本")
            http_5xx = counter_delta(correctness_start, correctness_final, HTTP_REQUESTS,
                                     lambda labels: re.fullmatch(r"5\d\d", labels.get("status", "")) is not None)
            hikari_timeouts = {
                pool: counter_delta(correctness_start, correctness_final, HIKARI_TIMEOUTS,
                                    lambda labels, expected=pool: labels.get("pool") == expected)
                for pool in ("thingslink-control", "thingslink-data")
            }
            emqx_durable = durable_republish_deltas(emqx_start, emqx_final)
            epochs = [int(sample["epochMillis"]) for sample in samples]
            gaps = [right - left for left, right in zip(epochs, epochs[1:])]
            max_gap = max(gaps, default=0)
            span = epochs[-1] - epochs[0]
            metrics = {
                "uplinkP99Seconds": uplink_p99,
                "commandAcceptanceP95Seconds": command_p95,
                "steadyLagPeak": float(max(int(sample["totalLag"]) for sample in samples)),
                "sutCpuAveragePercent": statistics.fmean(float(sample["sutCpuPercent"]) for sample in samples),
                "timeSeriesWriteFailureRate": write_failure / write_total,
            }
            add_check(checks, "commandClosure", command.get("passed") is True,
                      {"statusCounts": command.get("statusCounts"),
                       "database": command.get("database"),
                       "sampleFailures": command.get("sampleFailures")},
                      "600 SUCCEEDED 且 600 个单次成功 attempt/reply/Outbox")
            add_check(checks, "commandEvidenceReconciliation", True,
                      command_reconciliation, "600 条提交/终态/attempt/Outbox/聚合逐项一致")
            add_check(checks, "commandDrainCoordination", True,
                      command_coordination, "双信号与逐命令证据 SHA/计数一致")
            add_check(checks, "propertyFactReconciliation", reconciliation.get("passed") is True,
                      reconciliation, "manifest=inbox 且 points=manifest×10")
            # qualification manifest 覆盖连接爬坡+稳态，而 Prometheus 起止快照只覆盖稳态；二者不能直接比总数。
            # 快照边界还可能切过一个在途事务（点成功 counter 在提交前、端到端 timer 在提交后），故只要求两者有样本。
            add_check(checks, "uplinkHistogramSamples", uplink_count > 0, uplink_count, ">0")
            add_check(checks, "uplinkP99HistogramRange",
                      uplink_range["maxFiniteUpper"] == 300.0
                      and uplink_range["saturated"] is False,
                      uplink_range, "最大有限桶=300s 且 P99 排位未落入 +Inf")
            add_check(checks, "commandP95HistogramRange", command_range["saturated"] is False,
                      command_range, "P95 排位未落入 +Inf")
            add_check(checks, "commandHistogramCount", command_count == 600.0, command_count, "600")
            add_check(checks, "timeSeriesSuccessSamples", write_success > 0, write_success, ">0")
            add_check(checks, "timeSeriesWriteFailure", write_failure == 0, write_failure, "0")
            add_check(checks, "http5xx", http_5xx == 0, http_5xx, "0")
            add_check(checks, "hikariConnectionTimeouts",
                      all(value == 0 for value in hikari_timeouts.values()), hikari_timeouts,
                      "thingslink-control=0 且 thingslink-data=0")
            add_check(checks, "emqxDurableIngressFailures",
                      emqx_durable["matched"] > 0
                      and emqx_durable["actionsTotal"] == emqx_durable["matched"]
                      and emqx_durable["actionsSuccess"] == emqx_durable["actionsTotal"]
                      and emqx_durable["actionsFailed"] == 0,
                      emqx_durable,
                      "matched>0、actions.total=matched、actions.success=total、actions.failed=0")
            add_check(checks, "steadySampleCount", len(samples) >= 119, len(samples), ">=119")
            add_check(checks, "steadySampleGap", max_gap <= 7500, max_gap, "<=7500ms")
            add_check(checks, "steadySampleSpan", span >= 590000, span, ">=590000ms")
            add_check(checks, "lagEvidenceReconciliation", lag_reconciliation["passed"],
                      lag_reconciliation, "lag.tsv 与 steady-samples.jsonl 固定六列逐行一致")

            dlq = {}
            for line in (evidence_dir / "dlq.txt").read_text(encoding="utf-8").splitlines():
                key, value = line.split("=", 1)
                dlq[key] = int(value)
            add_check(checks, "dlq", dlq == {"tc.dlq": 0, "tc.rule.dlq": 0}, dlq,
                      "tc.dlq=0, tc.rule.dlq=0")
            final_group_text = (evidence_dir / "final-groups.txt").read_text(encoding="utf-8")
            final_lags = [int(match.group(1)) for match in re.finditer(r"^\s*TOTAL-LAG\s+(\d+)\s*$",
                                                                       final_group_text, re.MULTILINE)]
            add_check(checks, "finalGroupLag", len(final_lags) == 3 and final_lags == [0, 0, 0],
                      final_lags, "三个 group 均为 0")
        except (EvidenceError, OSError, ValueError, KeyError, TypeError) as exception:
            errors.append(f"当前运行证据解析失败: {exception}")

    add_check(checks, "runnerStepOutcome", runner_outcome == "success", runner_outcome, "success")
    if qualification.get("result") == "PASS" and metadata:
        try:
            comparison_fingerprint, fingerprint_document = fingerprint(
                metadata, qualification, Path(__file__).resolve().parent)
        except EvidenceError as exception:
            errors.append(str(exception))
    try:
        history = load_history(history_dir)
    except EvidenceError as exception:
        errors.append(str(exception))

    if errors:
        validity = "ERROR"
    elif qualification.get("result") != "PASS":
        validity = "INVALID_GENERATOR"
    elif not all(check["passed"] for check in checks):
        validity = "VALID_FAIL"
    else:
        validity = "VALID_PASS"
        performance = performance_verdict(metrics, history, comparison_fingerprint or "")

    overall = "PASS"
    if validity != "VALID_PASS" or performance.get("status") == "PERFORMANCE_REGRESSION":
        overall = "FAIL"
    elif performance.get("status") == "PERFORMANCE_WARNING":
        overall = "WARNING"
    github = {"repository": metadata.get("repository"), "commit": metadata.get("commit"),
              "runId": metadata.get("runId"), "runAttempt": metadata.get("runAttempt")}
    if metadata.get("executionEnvironment") == "local-docker":
        github["executionEnvironment"] = "local-docker"
        github["sourceSha256"] = metadata.get("localSourceSha256")
    return {"schemaVersion": 1, "overall": overall, "validityStatus": validity,
            "performanceStatus": performance.get("status"), "github": github,
            "comparisonFingerprint": comparison_fingerprint,
            "fingerprint": fingerprint_document, "metrics": metrics, "checks": checks,
            "errors": errors, "performance": performance}


def markdown(report: dict[str, Any]) -> str:
    """生成适合 artifact 与 GitHub job summary 的紧凑中文报告。"""
    lines = ["# G1-C3d L1 机器裁决", "",
             f"- 总结论：`{report['overall']}`",
             f"- 有效性/正确性：`{report['validityStatus']}`",
             f"- 性能历史：`{report['performanceStatus']}`",
             f"- 比较指纹：`{report.get('comparisonFingerprint') or 'N/A'}`", ""]
    if report["metrics"]:
        lines.extend(["## 五项指标", "",
                      "| 指标 | 当前值 |", "| --- | ---: |"])
        lines.extend(f"| `{key}` | {report['metrics'][key]:.9g} |" for key in METRIC_KEYS)
        lines.append("")
    lines.extend(["## Checks", "", "| Check | 结果 | 实际 |", "| --- | --- | --- |"])
    for check in report["checks"]:
        actual = json.dumps(check["actual"], ensure_ascii=False, separators=(",", ":"))
        lines.append(f"| `{check['name']}` | {'PASS' if check['passed'] else 'FAIL'} | `{actual}` |")
    if report["errors"]:
        lines.extend(["", "## 证据错误", ""])
        lines.extend(f"- {error}" for error in report["errors"])
    degraded = report.get("performance", {}).get("degradedMetrics", [])
    if degraded:
        lines.extend(["", "## 性能退化", "", f"- 本次超过 20%：{', '.join(degraded)}"])
        consecutive = report["performance"].get("consecutiveDegradedMetrics", [])
        if consecutive:
            lines.append(f"- 连续两次超过 20%：{', '.join(consecutive)}")
    return "\n".join(lines) + "\n"


def internal_error_report(exception: Exception) -> dict[str, Any]:
    """把未建模的裁决器异常降为可归档 ERROR；仍返回失败退出码且在日志保留 traceback。"""
    return {
        "schemaVersion": 1,
        "overall": "FAIL",
        "validityStatus": "ERROR",
        "performanceStatus": "NOT_EVALUATED",
        "github": {},
        "comparisonFingerprint": None,
        "fingerprint": None,
        "metrics": {},
        "checks": [],
        "errors": [f"机器裁决内部错误: {type(exception).__name__}: {exception}"],
        "performance": {"status": "NOT_EVALUATED"},
    }


def main() -> int:
    """无论 PASS/FAIL 都先写完整报告，再用退出码驱动 Actions。"""
    args = parse_args()
    try:
        report = evaluate(args.evidence_dir.resolve(), args.history_dir.resolve(), args.runner_outcome)
    except Exception as exception:  # noqa: BLE001 - 未建模异常也必须先形成紧凑失败证据
        traceback.print_exc()
        report = internal_error_report(exception)
    rendered = markdown(report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.markdown_output.write_text(rendered, encoding="utf-8")
    if args.job_summary:
        with args.job_summary.open("a", encoding="utf-8") as summary:
            summary.write(rendered)
    print(f"[c3d-verdict] {report['overall']} validity={report['validityStatus']} "
          f"performance={report['performanceStatus']}")
    return 0 if report["overall"] in {"PASS", "WARNING"} else 1


if __name__ == "__main__":
    raise SystemExit(main())
