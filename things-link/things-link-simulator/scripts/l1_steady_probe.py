#!/usr/bin/env python3
"""G1-C3d L1 稳态命令探针、Prometheus 快照与 SUT/lag 采样。"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import http.cookiejar
import json
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
GROUPS = ("things-link-ingestion-raw", "things-link-ingestion-normalized",
          "things-link-ingestion-processed")
CORE_CONTAINERS = ("tc-postgres", "tc-redis", "tc-redpanda", "tc-emqx", "tc-minio")
COMMAND_DRAIN_SECONDS = 180
COMMAND_POLL_INTERVAL_SECONDS = 0.07


class RateLimited(RuntimeError):
    """只让命令查询的 429 进入有界退避；其他 HTTP 失败照常终止。"""

    def __init__(self, retry_after: str | None):
        super().__init__("命令查询触及 REST 速率上限")
        try:
            seconds = int(retry_after) if retry_after is not None else 60
        except ValueError:
            seconds = 60
        self.retry_after_seconds = min(120, max(1, seconds))


def parse_args() -> argparse.Namespace:
    """读取冻结的 600 秒探针参数；密码只允许来自环境变量。"""
    parser = argparse.ArgumentParser(description="G1-C3d L1 稳态探针")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--email", required=True)
    parser.add_argument("--password-env", default="L1_ACCOUNT_PASSWORD")
    parser.add_argument("--credentials-file", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--backend-pid", type=int, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--duration-seconds", type=int, default=600)
    parser.add_argument("--command-interval-seconds", type=float, default=1.0)
    parser.add_argument("--sample-interval-seconds", type=float, default=5.0)
    parser.add_argument("--performance-complete-file", type=Path, required=True,
                        help="固定性能窗口落盘后的单向信号；该信号绝不授权停止设备")
    parser.add_argument("--command-drain-complete-file", type=Path, required=True,
                        help="命令终态与逐命令证据落盘后的设备停止信号")
    args = parser.parse_args()
    if args.duration_seconds != 600 or args.command_interval_seconds != 1.0:
        parser.error("正式 L1 固定 600 秒、1 command/s；变更须先修改设计冻结")
    if args.sample_interval_seconds != 5.0:
        parser.error("正式 L1 固定每 5 秒采样")
    if not args.credentials_file.is_file():
        parser.error("凭据文件不存在")
    if not os.environ.get(args.password_env):
        parser.error(f"环境变量 {args.password_env} 缺失")
    return args


def write_json_atomic(path: Path, document: Any) -> None:
    """以同目录临时文件原子写 JSON；异常时不留下可能被误读的半成品。"""
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def write_performance_completion(path: Path, run_id: str, submitted: int) -> None:
    """发布固定性能窗口终点；phase 防止资格器把它误当作停机许可。"""
    write_json_atomic(path, {
        "schemaVersion": 1,
        "runId": run_id,
        "phase": "PERFORMANCE_COMPLETE",
        "outcome": "COMPLETE",
        "submitted": submitted,
        "completedAtEpochMillis": time.time_ns() // 1_000_000,
    })


def write_command_drain_completion(path: Path, run_id: str, outcome: str,
                                   submitted: int, terminal: int, succeeded: int,
                                   evidence_sha256: str) -> None:
    """在命令明细和聚合均落盘后发布唯一停机信号。"""
    if outcome not in {"COMPLETE", "TIMEOUT"}:
        raise ValueError(f"不支持的命令排空结果: {outcome}")
    write_json_atomic(path, {
        "schemaVersion": 1,
        "runId": run_id,
        "phase": "COMMAND_DRAIN_COMPLETE",
        "outcome": outcome,
        "submitted": submitted,
        "terminal": terminal,
        "succeeded": succeeded,
        "evidenceSha256": evidence_sha256,
        "completedAtEpochMillis": time.time_ns() // 1_000_000,
    })


def request(method: str, url: str, body: Any = None, token: str | None = None,
            headers: dict[str, str] | None = None, expected: tuple[int, ...] = (200,)) -> Any:
    """调用本轮隔离后端；错误正文只保留有限诊断且从不包含请求头。"""
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Content-Type": "application/json"}
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    if headers:
        request_headers.update(headers)
    http_request = urllib.request.Request(url, data=payload, method=method, headers=request_headers)
    try:
        with OPENER.open(http_request, timeout=15) as response:
            if response.status not in expected:
                raise RuntimeError(f"HTTP {response.status} {url}")
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exception:
        if method == "GET" and exception.code == 429:
            raise RateLimited(exception.headers.get("Retry-After")) from exception
        diagnostic = exception.read().decode(errors="replace")[:800]
        raise RuntimeError(f"HTTP {exception.code} {url}: {diagnostic}") from exception


def poll_command_terminals(submitted: list[dict[str, Any]], base: str, project_id: str,
                           token: str, drain_seconds: int = COMMAND_DRAIN_SECONDS) -> dict[str, dict[str, Any]]:
    """按隔离 L1 策略的 1200/min 查询，429 遵循 Retry-After，期限内不丢掉任何命令。"""
    deadline = time.monotonic() + drain_seconds
    next_poll = time.monotonic()
    final: dict[str, dict[str, Any]] = {}
    while time.monotonic() < deadline and len(final) < len(submitted):
        for item in submitted:
            command_id = item["commandId"]
            if command_id in final:
                continue
            while time.monotonic() < deadline:
                delay = min(max(0.0, next_poll - time.monotonic()), max(0.0, deadline - time.monotonic()))
                if delay > 0:
                    time.sleep(delay)
                if time.monotonic() >= deadline:
                    break
                try:
                    response = request(
                        "GET", f"{base}/api/v1/projects/{project_id}/devices/{item['deviceId']}/commands/{command_id}",
                        token=token)
                except RateLimited as exception:
                    next_poll = time.monotonic() + exception.retry_after_seconds
                    continue
                next_poll = time.monotonic() + COMMAND_POLL_INTERVAL_SECONDS
                if response["status"] in {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"}:
                    final[command_id] = response
                break
            if time.monotonic() >= deadline:
                break
    return final


def prometheus_snapshot(base_url: str, destination: Path) -> None:
    """保存完整原始 exposition，直方图机器判定必须使用起止 bucket 增量。"""
    with urllib.request.urlopen(f"{base_url}/actuator/prometheus", timeout=10) as response:
        destination.write_bytes(response.read())


def group_lag(group: str) -> int:
    """读取真实 consumer group TOTAL-LAG；组缺失与零严格区分。"""
    completed = subprocess.run(
        ["docker", "exec", "tc-redpanda", "rpk", "group", "describe", group],
        capture_output=True, text=True, timeout=10, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"consumer group {group} 不可读")
    for line in completed.stdout.splitlines():
        fields = line.split()
        if fields and fields[0] == "TOTAL-LAG" and len(fields) >= 2:
            return int(fields[1])
    raise RuntimeError(f"consumer group {group} 缺少 TOTAL-LAG")


def docker_cpu_percent() -> tuple[float, dict[str, float]]:
    """采集 core 容器 CPU；模拟器 JVM 不在容器集合中，避免污染 SUT 回归指标。"""
    completed = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}",
         *CORE_CONTAINERS], capture_output=True, text=True, timeout=20, check=False)
    if completed.returncode != 0:
        raise RuntimeError("docker stats 失败")
    values: dict[str, float] = {}
    for line in completed.stdout.splitlines():
        name, raw = line.split("\t", 1)
        values[name] = float(raw.rstrip("%"))
    missing = sorted(set(CORE_CONTAINERS) - set(values))
    if missing:
        raise RuntimeError(f"core 容器 CPU 缺失: {missing}")
    return sum(values.values()), values


def process_ticks(pid: int) -> int:
    """读取后端用户态+内核态 CPU tick；进程名可能含空格，不能直接整行 split。"""
    raw = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    fields = raw[raw.rfind(")") + 2:].split()
    return int(fields[11]) + int(fields[12])


def collect_external_state(
        executor: concurrent.futures.Executor) -> tuple[float, dict[str, float], dict[str, int]]:
    """从同一调度点并发读取容器 CPU 与三组 lag，避免 Docker CLI 延迟串行累加。"""
    container_future = executor.submit(docker_cpu_percent)
    lag_futures = {group: executor.submit(group_lag, group) for group in GROUPS}
    container_total, containers = container_future.result()
    lags = {group: future.result() for group, future in lag_futures.items()}
    return container_total, containers, lags


def sampling_loop(args: argparse.Namespace, destination: Path, stop: threading.Event,
                  failures: list[str]) -> None:
    """与 1/s 命令线程并行采样，避免 docker stats 延迟改变命令节拍。"""
    clock_ticks = os.sysconf("SC_CLK_TCK")
    previous_ticks = process_ticks(args.backend_pid)
    previous_time = time.monotonic()
    sequence = 0
    # 四个持久 worker 对应一次 docker stats 和三个独立 group describe；反复建池会把线程创建噪声带入证据。
    with (concurrent.futures.ThreadPoolExecutor(
            max_workers=1 + len(GROUPS), thread_name_prefix="l1-sample") as collector,
          destination.open("w", encoding="utf-8") as output):
        while not stop.is_set():
            started = time.monotonic()
            started_epoch_millis = time.time_ns() // 1_000_000
            try:
                current_ticks = process_ticks(args.backend_pid)
                current_time = time.monotonic()
                backend_cpu = ((current_ticks - previous_ticks) / clock_ticks
                               / max(current_time - previous_time, 0.001) * 100.0)
                container_total, containers, lags = collect_external_state(collector)
                sample = {
                    "sequence": sequence,
                    "epochMillis": started_epoch_millis,
                    "backendCpuPercent": backend_cpu,
                    "containerCpuPercent": containers,
                    "sutCpuPercent": backend_cpu + container_total,
                    "groupLag": lags,
                    "totalLag": sum(lags.values()),
                }
                output.write(json.dumps(sample, separators=(",", ":")) + "\n")
                output.flush()
                previous_ticks, previous_time = current_ticks, current_time
                sequence += 1
            except Exception as exception:  # noqa: BLE001 - 缺样必须留在证据中并由硬门禁拒绝
                failures.append(f"{type(exception).__name__}: {exception}")
            elapsed = time.monotonic() - started
            stop.wait(max(0.0, args.sample_interval_seconds - elapsed))


def write_lag_tsv(samples_path: Path, destination: Path) -> None:
    """从 JSONL 单一事实源原子派生冻结六列 lag 证据，避免实时双写产生半行或分叉。"""
    header = ("sequence", "epochMillis", *GROUPS, "totalLag")
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    try:
        with (samples_path.open("r", encoding="utf-8") as samples,
              temporary.open("w", encoding="utf-8", newline="\n") as output):
            output.write("\t".join(header) + "\n")
            for index, raw in enumerate(samples):
                if not raw.strip():
                    continue
                sample = json.loads(raw)
                lags = sample["groupLag"]
                row = (int(sample["sequence"]), int(sample["epochMillis"]),
                       *(int(lags[group]) for group in GROUPS), int(sample["totalLag"]))
                if row[-1] != sum(row[2:5]):
                    raise RuntimeError(f"稳态样本第 {index + 1} 行 totalLag 不等于三组之和")
                output.write("\t".join(str(value) for value in row) + "\n")
        os.replace(temporary, destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def database_command_evidence(submitted: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """一次快照读取 command/attempt/Outbox，避免依赖日志时序猜测下行关联。"""
    rows = []
    for item in submitted:
        sequence = int(item["sequence"])
        device_id = uuid.UUID(item["deviceId"])
        command_id = uuid.UUID(item["commandId"])
        accepted_status = str(item["acceptedStatus"])
        if accepted_status not in {"ACCEPTED", "DISPATCHED", "ACKNOWLEDGED", "SUCCEEDED"}:
            raise RuntimeError(f"命令 {command_id} 受理状态不受支持: {accepted_status}")
        rows.append(f"({sequence},'{device_id}'::uuid,'{command_id}'::uuid,'{accepted_status}')")
    values = ",".join(rows)
    sql = f"""
CREATE TEMP TABLE l1_commands(
  sequence integer PRIMARY KEY,
  device_id uuid NOT NULL,
  command_id uuid UNIQUE NOT NULL,
  accepted_status text NOT NULL
);
INSERT INTO l1_commands(sequence, device_id, command_id, accepted_status) VALUES {values};
SELECT coalesce(json_agg(command_document ORDER BY sequence), '[]'::json)::text
FROM (
  SELECT l.sequence,
         json_build_object(
           'sequence', l.sequence,
           'deviceId', l.device_id,
           'commandId', l.command_id,
           'acceptedStatus', l.accepted_status,
           'terminal', json_build_object(
             'status', c.status,
             'failureCode', c.failure_code,
             'attemptCount', c.attempt_count,
             'maxAttempts', c.max_attempts,
             'acceptedAt', c.accepted_at,
             'dispatchedAt', c.dispatched_at,
             'acknowledgedAt', c.acknowledged_at,
             'completedAt', c.completed_at
           ),
           'attempts', (
             SELECT coalesce(json_agg(json_build_object(
               'attemptNo', a.attempt_no,
               'status', a.status,
               'errorCode', a.error_code,
               'replyMessageId', a.reply_message_id,
               'createdAt', a.created_at,
               'deadlineAt', a.deadline_at,
               'publishedAt', a.published_at,
               'acknowledgedAt', a.acknowledged_at,
               'completedAt', a.completed_at,
               'outbox', json_build_object(
                 'eventId', o.id,
                 'aggregateType', o.aggregate_type,
                 'aggregateId', o.aggregate_id,
                 'eventType', o.event_type,
                 'status', o.status,
                 'attemptCount', o.attempt_count,
                 'createdAt', o.created_at,
                 'publishedAt', o.published_at
               )
             ) ORDER BY a.attempt_no), '[]'::json)
             FROM ts_device_command_attempt a
             JOIN sys_outbox_event o ON o.id = a.outbox_event_id
             WHERE a.project_id = c.project_id AND a.command_id = c.id
           )
         ) AS command_document
  FROM l1_commands l
  JOIN ts_device_command c ON c.id = l.command_id AND c.target_device_id = l.device_id
) evidence;
"""
    completed = subprocess.run(
        ["docker", "exec", "-i", "tc-postgres", "psql", "-U", "thingslink", "-d", "thingslink",
         "--no-psqlrc", "-t", "-A", "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True, timeout=30, check=False)
    if completed.returncode != 0:
        raise RuntimeError("逐命令数据库证据查询失败: " + completed.stderr[-800:])
    lines = [line for line in completed.stdout.splitlines() if line.startswith("[")]
    if len(lines) != 1:
        raise RuntimeError("逐命令数据库证据未返回唯一 JSON 数组")
    return json.loads(lines[0])


def build_command_evidence(run_id: str, submitted: list[dict[str, Any]],
                           final: dict[str, dict[str, Any]],
                           commands: list[dict[str, Any]]) -> dict[str, Any]:
    """校验 API 终态与数据库快照一致，再形成稳定排序且不含载荷的明细。"""
    expected_by_id = {item["commandId"]: item for item in submitted}
    if len(expected_by_id) != len(submitted) or len(commands) != len(submitted):
        raise RuntimeError("逐命令证据数量或提交 commandId 唯一性不成立")
    ordered = sorted(commands, key=lambda item: int(item["sequence"]))
    for sequence, item in enumerate(ordered):
        if int(item["sequence"]) != sequence:
            raise RuntimeError(f"逐命令证据 sequence 在 {sequence} 处不连续")
        command_id = item.get("commandId")
        submission = expected_by_id.get(command_id)
        if (submission is None or submission["deviceId"] != item.get("deviceId")
                or submission["acceptedStatus"] != item.get("acceptedStatus")):
            raise RuntimeError(f"逐命令证据与提交记录不一致: {command_id}")
        api_terminal = final.get(command_id)
        terminal = item.get("terminal", {})
        if api_terminal is not None and (api_terminal.get("status") != terminal.get("status")
                                         or api_terminal.get("failureCode") != terminal.get("failureCode")):
            raise RuntimeError(f"命令 API 与数据库终态不一致: {command_id}")
    return {"schemaVersion": 1, "runId": run_id, "expected": 600, "commands": ordered}


def summarize_command_evidence(evidence: dict[str, Any], sample_failures: list[str]) -> dict[str, Any]:
    """从逐命令单一事实源派生聚合，避免明细与 summary 使用两次查询。"""
    commands = evidence["commands"]
    status_counts: dict[str, int] = {}
    attempts = [attempt for command in commands for attempt in command.get("attempts", [])]
    reply_ids = [attempt["replyMessageId"] for attempt in attempts if attempt.get("replyMessageId")]
    for command in commands:
        status = command["terminal"]["status"]
        status_counts[status] = status_counts.get(status, 0) + 1
    database = {
        "commands": len(commands),
        "attempts": len(attempts),
        "succeededAttempts": sum(attempt.get("status") == "SUCCEEDED" for attempt in attempts),
        "replyMessageIds": len(reply_ids),
        "uniqueReplyMessageIds": len(set(reply_ids)),
    }
    result = {
        "schemaVersion": 2,
        "runId": evidence["runId"],
        "expected": evidence["expected"],
        "submitted": len(commands),
        "uniqueCommandIds": len({command["commandId"] for command in commands}),
        "terminal": sum(command["terminal"]["status"]
                        in {"SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED"} for command in commands),
        "statusCounts": status_counts,
        "sampleFailures": sample_failures,
        "database": database,
    }
    result["passed"] = (
        result["submitted"] == 600
        and result["uniqueCommandIds"] == 600
        and result["terminal"] == 600
        and status_counts == {"SUCCEEDED": 600}
        and not sample_failures
        and database == {"commands": 600, "attempts": 600, "succeededAttempts": 600,
                         "replyMessageIds": 600, "uniqueReplyMessageIds": 600}
    )
    return result


def main() -> int:
    """在精确稳态窗口内发 600 条命令，并归档可由下一切片判定的原始证据。"""
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    credentials = json.loads(args.credentials_file.read_text(encoding="utf-8"))
    devices = credentials.get("devices", [])
    if len(devices) != 1000 or any(not device.get("deviceId") for device in devices):
        raise RuntimeError("L1 凭据必须包含恰好 1,000 台带 deviceId 的设备")
    base = args.base_url.rstrip("/")
    password = os.environ[args.password_env]
    login = request("POST", f"{base}/api/v1/auth/login", {"email": args.email, "password": password})
    switched = request("POST", f"{base}/api/v1/auth/switch-project",
                       {"projectId": credentials["projectId"]}, login["accessToken"])
    token = switched["accessToken"]

    prometheus_snapshot(base, output_dir / "prometheus-start.txt")
    sample_failures: list[str] = []
    stop_sampling = threading.Event()
    sampler = threading.Thread(target=sampling_loop,
                               args=(args, output_dir / "steady-samples.jsonl",
                                     stop_sampling, sample_failures), daemon=True)
    sampler.start()
    submitted: list[dict[str, Any]] = []
    started = time.monotonic()
    try:
        for index in range(args.duration_seconds):
            target = started + index * args.command_interval_seconds
            delay = target - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            device = devices[index % len(devices)]
            idempotency_key = f"{args.run_id}-command-{index:04d}"
            response = request(
                "POST", f"{base}/api/v1/projects/{credentials['projectId']}/devices/{device['deviceId']}/commands",
                {"commandKey": "nightly_probe", "input": {"sequence": index}}, token,
                {"Idempotency-Key": idempotency_key}, (202,))
            submitted.append({"sequence": index, "deviceId": device["deviceId"],
                              "commandId": response["id"], "acceptedStatus": response["status"]})
    except Exception as failure:
        write_json_atomic(output_dir / "command-submission-failure.json", {
            "runId": args.run_id, "expected": args.duration_seconds, "submitted": len(submitted),
            "errorType": type(failure).__name__, "message": str(failure)[:800],
        })
        raise
    finally:
        # 429/连接异常也保留已受理命令；不能让异常跳过证据落盘或伪造600条完成信号。
        write_json_atomic(output_dir / "command-submissions.json", submitted)
        # 终点快照必须先于命令终态轮询，保证直方图窗口与 600 秒稳态一致。
        prometheus_snapshot(base, output_dir / "prometheus-end.txt")
        stop_sampling.set()
        sampler.join(timeout=30)
        if sampler.is_alive():
            raise RuntimeError("稳态采样线程在 30 秒内未停止，拒绝发布不完整 lag.tsv")
        write_lag_tsv(output_dir / "steady-samples.jsonl", output_dir / "lag.tsv")

    # 提交明细已在 finally 落盘；只有完整窗口才能发布性能信号。
    write_performance_completion(args.performance_complete_file, args.run_id, len(submitted))

    final = poll_command_terminals(submitted, base, credentials["projectId"], token)

    database_commands = database_command_evidence(submitted)
    evidence = build_command_evidence(args.run_id, submitted, final, database_commands)
    evidence_path = output_dir / "command-evidence.json"
    write_json_atomic(evidence_path, evidence)
    result = summarize_command_evidence(evidence, sample_failures)
    write_json_atomic(output_dir / "command-results.json", result)
    evidence_sha256 = hashlib.sha256(evidence_path.read_bytes()).hexdigest()
    drain_outcome = "COMPLETE" if result["terminal"] == 600 else "TIMEOUT"
    write_command_drain_completion(
        args.command_drain_complete_file, args.run_id, drain_outcome,
        result["submitted"], result["terminal"], result["statusCounts"].get("SUCCEEDED", 0),
        evidence_sha256)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
