#!/usr/bin/env python3
"""G1-C3a A4-0 多分片负载发生器资格编排、聚合与机器判定。"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import os
import platform
import re
import shlex
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional


@dataclass
class Shard:
    """一个独立模拟器进程及其不含凭据的运行现场。"""

    shard_id: str
    port: int
    devices: list[dict[str, Any]]
    process: subprocess.Popen[bytes]
    log_handle: Any
    start_delay_seconds: float = 0.0
    last_stats: Optional[dict[str, Any]] = None


@dataclass
class HostCpuTracker:
    """按近同时采样的进程 CPU 合计形成宿主机五样本滚动证据。"""

    sample_count: int = 0
    incomplete_samples: int = 0
    max_sample_gap_millis: int = 0
    peak_process_cpu_sum: float = -1.0
    max_five_second_cpu_average: float = -1.0
    recent_samples: list[float] = field(default_factory=list)
    recent_phases: list[str] = field(default_factory=list)
    max_five_second_cpu_phase: str | None = None
    max_five_second_cpu_window: list[float] = field(default_factory=list)
    last_sample_millis: float | None = None

    def observe(self, stats: list[dict[str, Any]], now_millis: float | None = None,
                phase: str = "qualification") -> None:
        """同一编排轮次先合计当前 CPU；任一分片缺值时记录缺样且不拼凑部分宿主样本。"""
        current_millis = now_millis if now_millis is not None else time.monotonic_ns() / 1_000_000.0
        if self.last_sample_millis is not None:
            gap = int(current_millis - self.last_sample_millis)
            self.max_sample_gap_millis = max(self.max_sample_gap_millis, gap)
        self.last_sample_millis = current_millis
        self.sample_count += 1
        loads = [item.get("processCpuLoad", -1.0) for item in stats]
        if any(not isinstance(load, (int, float)) or load < 0.0 for load in loads):
            self.incomplete_samples += 1
            self.recent_samples.clear()
            self.recent_phases.clear()
            return
        total = float(sum(loads))
        self.peak_process_cpu_sum = max(self.peak_process_cpu_sum, total)
        self.recent_samples.append(total)
        self.recent_phases.append(phase)
        if len(self.recent_samples) > 5:
            self.recent_samples.pop(0)
            self.recent_phases.pop(0)
        if len(self.recent_samples) == 5:
            average = sum(self.recent_samples) / len(self.recent_samples)
            if average > self.max_five_second_cpu_average:
                self.max_five_second_cpu_average = average
                distinct_phases = list(dict.fromkeys(self.recent_phases))
                self.max_five_second_cpu_phase = "->".join(distinct_phases)
                self.max_five_second_cpu_window = list(self.recent_samples)

    def report(self) -> dict[str, Any]:
        """返回可归档的低体积宿主 CPU 证据。"""
        return {
            "sampleCount": self.sample_count,
            "maxSampleGapMillis": self.max_sample_gap_millis,
            "incompleteSamples": self.incomplete_samples,
            "peakProcessCpuSum": self.peak_process_cpu_sum,
            "maxFiveSecondCpuAverage": self.max_five_second_cpu_average,
            "maxFiveSecondCpuPhase": self.max_five_second_cpu_phase,
            "maxFiveSecondCpuWindow": self.max_five_second_cpu_window,
        }


class QualificationRejected(RuntimeError):
    """冻结门槛在运行中已确定越界；继续施压没有资格价值，应保存 FAIL 而非工具 ERROR。"""

    def __init__(self, checks: list[dict[str, Any]], phase: str = "connection-ramp"):
        super().__init__("发生器先触发冻结资源门槛")
        self.checks = checks
        self.phase = phase


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="A4-0 负载发生器资格自测")
    parser.add_argument("--jar", type=Path, required=True, help="模拟器可执行 JAR")
    parser.add_argument("--broker-uri", required=True, help="隔离资格 Broker，例如 tcp://127.0.0.1:18883")
    parser.add_argument("--broker-fingerprint", required=True,
                        help="Broker 镜像 digest/版本与关键配置摘要，不允许只写产品名")
    parser.add_argument("--project-key", default="a4_qualification")
    parser.add_argument("--credentials-file", type=Path,
                        help="可选 JSON 凭据文件；不提供时生成仅适用于匿名隔离 Broker 的占位身份")
    parser.add_argument("--device-count", type=int, default=1000)
    parser.add_argument("--shard-size", type=int, default=1000)
    parser.add_argument("--shard-id-prefix",
                        help="跨主机资格时使用稳定 host 前缀；省略则保持 shard-000 旧合同")
    parser.add_argument("--interval-seconds", type=int, default=60)
    parser.add_argument("--properties-per-report", type=int, default=10)
    parser.add_argument("--steady-seconds", type=int, default=300)
    parser.add_argument("--base-port", type=int, default=18090)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--run-id", help="默认按 UTC 时间生成")
    parser.add_argument("--java", default="java")
    parser.add_argument("--xms", default="256m")
    parser.add_argument("--xmx", default="1g")
    parser.add_argument("--rss-budget-bytes", type=int, required=True,
                        help="每分片进程被分配的 RSS 预算；峰值必须保留 30%% 余量")
    parser.add_argument("--thread-budget", type=int, required=True,
                        help="每分片进程被分配的线程预算；峰值必须保留 30%% 余量")
    parser.add_argument("--host-rss-budget-bytes", type=int,
                        help="发生器宿主机分配给全部分片的 RSS 预算；默认按每分片预算之和")
    parser.add_argument("--host-thread-budget", type=int,
                        help="发生器宿主机分配给全部分片的线程预算；默认按每分片预算之和")
    parser.add_argument("--sut-time-command", required=True,
                        help="输出 SUT 当前 epoch milliseconds 的命令；按 argv 执行，不经 shell")
    parser.add_argument("--ramp-deadline-seconds", type=int, default=300)
    parser.add_argument("--shard-start-stagger-seconds", type=float, default=0.0,
                        help="相邻分片连接爬坡的固定错峰秒数；全局仍受 ramp deadline 约束")
    parser.add_argument("--clock-limit-ms", type=float, default=100.0)
    parser.add_argument("--cpu-limit", type=float, default=0.70)
    parser.add_argument("--resource-limit", type=float, default=0.70)
    parser.add_argument("--sample-gap-limit-ms", type=int, default=2500)
    parser.add_argument("--steady-ready-file", type=Path,
                        help="全部分片进入稳态时原子写入的同步文件，供 L1 探针精确对齐测量窗口")
    parser.add_argument("--performance-complete-file", type=Path,
                        help="探针固定性能窗口完成信号；该文件本身绝不授权停止设备")
    parser.add_argument("--command-drain-complete-file", type=Path,
                        help="探针逐命令证据落盘后的停止信号；须与性能信号配对")
    parser.add_argument("--performance-completion-grace-seconds", type=int, default=30,
                        help="最短稳态结束后等待性能信号的硬上限")
    parser.add_argument("--command-drain-watchdog-seconds", type=int, default=240,
                        help="性能信号后等待命令排空信号的失联看门狗；不拥有业务排空判定")
    args = parser.parse_args()
    if not args.jar.is_file():
        parser.error(f"JAR 不存在: {args.jar}")
    if not 1 <= args.device_count:
        parser.error("device-count 必须大于 0")
    if not 1 <= args.shard_size <= 1000:
        parser.error("shard-size 必须在 1..1000")
    if args.shard_id_prefix is not None and re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,47}",
                                                         args.shard_id_prefix) is None:
        parser.error("shard-id-prefix 必须是最多 48 位稳定小写标识")
    if not 1 <= args.properties_per_report <= 100:
        parser.error("properties-per-report 必须在 1..100")
    if args.steady_seconds < 5:
        parser.error("steady-seconds 至少 5 秒，正式 A4-0 使用 300 秒")
    if args.rss_budget_bytes <= 0 or args.thread_budget <= 0:
        parser.error("资源预算必须大于 0")
    if args.shard_start_stagger_seconds < 0:
        parser.error("shard-start-stagger-seconds 不能为负数")
    shard_count = (args.device_count + args.shard_size - 1) // args.shard_size
    if (shard_count - 1) * args.shard_start_stagger_seconds >= args.ramp_deadline_seconds:
        parser.error("全部分片的启动错峰必须小于 ramp deadline")
    if args.host_rss_budget_bytes is not None and args.host_rss_budget_bytes <= 0:
        parser.error("host-rss-budget-bytes 必须大于 0")
    if args.host_thread_budget is not None and args.host_thread_budget <= 0:
        parser.error("host-thread-budget 必须大于 0")
    coordination = (args.steady_ready_file, args.performance_complete_file,
                    args.command_drain_complete_file)
    if any(value is not None for value in coordination) and not all(value is not None for value in coordination):
        parser.error("steady-ready、performance-complete 与 command-drain-complete 必须同时提供")
    if args.performance_completion_grace_seconds <= 0:
        parser.error("performance-completion-grace-seconds 必须大于 0")
    if args.command_drain_watchdog_seconds <= 180:
        parser.error("command-drain-watchdog-seconds 必须大于探针 180 秒业务排空期限")
    return args


def http_json(method: str, url: str, body: Optional[dict[str, Any]] = None,
              timeout: float = 10.0) -> dict[str, Any]:
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method=method,
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        diagnostic = exception.read().decode("utf-8", errors="replace")[:1000]
        raise RuntimeError(f"HTTP {exception.code} {url}: {diagnostic}") from exception


def load_devices(args: argparse.Namespace) -> list[dict[str, Any]]:
    if args.credentials_file is None:
        prefix = getattr(args, "shard_id_prefix", None)
        if prefix is None:
            # 旧单机 CLI 的占位身份保持字节级兼容。
            return [{"deviceKey": f"a4_device_{index:05d}",
                     "accessToken": f"qualification-only-{index:05d}", "gateway": False}
                    for index in range(args.device_count)]
        # 同 run 的多 host 不能复用 MQTT clientId/token，否则资格还未开始就会互踢连接。
        return [{"deviceKey": f"{prefix}_a4_device_{index:05d}",
                 "accessToken": f"qualification-only-{prefix}-{index:05d}", "gateway": False}
                for index in range(args.device_count)]
    document = json.loads(args.credentials_file.read_text(encoding="utf-8"))
    devices = document["devices"] if isinstance(document, dict) else document
    if not isinstance(devices, list) or not devices:
        raise ValueError("凭据文件必须是非空 devices 数组或数组本身")
    if len(devices) < args.device_count:
        raise ValueError(f"凭据只有 {len(devices)} 台，少于 device-count={args.device_count}")
    # 凭据只进入请求内存，不写报告、不打印；输出目录只保存不含 Token 的 stats/manifest。
    return devices[:args.device_count]


def build_shard_id(prefix: str | None, index: int) -> str:
    """生成本机分片身份；prefix 缺省保持旧 CLI，跨主机时由 hostId 保证全局唯一。"""
    return f"shard-{index:03d}" if prefix is None else f"{prefix}-shard-{index:03d}"


def wait_ready(shard: Shard, deadline_seconds: int = 60) -> None:
    deadline = time.monotonic() + deadline_seconds
    while time.monotonic() < deadline:
        if shard.process.poll() is not None:
            raise RuntimeError(f"{shard.shard_id} 启动退出，exit={shard.process.returncode}")
        try:
            http_json("GET", f"http://127.0.0.1:{shard.port}/simulations/stats", timeout=2)
            return
        except (OSError, RuntimeError, json.JSONDecodeError):
            time.sleep(0.2)
    raise TimeoutError(f"{shard.shard_id} 60 秒内未就绪")


def start_shard(shard: Shard, args: argparse.Namespace, run_id: str) -> dict[str, Any]:
    """等待冻结错峰后启动分片；延迟是连接爬坡的一部分，不延长全局 deadline。"""
    if shard.start_delay_seconds > 0:
        time.sleep(shard.start_delay_seconds)
    body = {
        "brokerUri": args.broker_uri,
        "projectKey": args.project_key,
        "devices": shard.devices,
        "intervalSeconds": args.interval_seconds,
        "autoReplyCommands": True,
        "runId": run_id,
        "shardId": shard.shard_id,
        "propertiesPerReport": args.properties_per_report,
    }
    # 连接接口同步完成全量爬坡；超时略高于冻结的 5 分钟窗口，以便失败后仍能读取诊断 stats。
    return http_json("POST", f"http://127.0.0.1:{shard.port}/simulations/start", body,
                     timeout=args.ramp_deadline_seconds + 30)


def read_sut_clock(command: str) -> tuple[float, float]:
    argv = shlex.split(command)
    if not argv:
        raise ValueError("sut-time-command 为空")
    before = time.time_ns() / 1_000_000.0
    result = subprocess.run(argv, check=True, capture_output=True, text=True, timeout=10)
    after = time.time_ns() / 1_000_000.0
    sut_millis = float(result.stdout.strip())
    # 用请求本地起止中点抵消网络/SSH 往返的一阶误差；RTT 也归档，过大时结论仍可审计。
    return abs(sut_millis - (before + after) / 2.0), after - before


def add_check(checks: list[dict[str, Any]], name: str, passed: bool,
              actual: Any, expected: str) -> None:
    checks.append({"name": name, "passed": bool(passed), "actual": actual, "expected": expected})


def ramp_resource_failures(args: argparse.Namespace, shard: Shard,
                           stats: dict[str, Any], phase: str = "qualification") -> list[dict[str, Any]]:
    resources = stats["generatorResources"]
    # `/start` 是同步长请求，编排器可能在处理线程初始化采样器前先读到 stats。
    # 此时 -1 表示“尚无样本”而非资源越线；主循环会在同一有界爬坡期限内等待首样本，不能把哨兵值算成比例。
    if resources["sampleCount"] <= 0:
        return []
    checks: list[dict[str, Any]] = []
    prefix = shard.shard_id
    candidates = [
        ("sampleGap", resources["maxSampleGapMillis"] <= args.sample_gap_limit_ms,
         {"millis": resources["maxSampleGapMillis"],
          "startedAt": resources.get("maxSampleGapStartedAt"),
          "endedAt": resources.get("maxSampleGapEndedAt")},
         f"<={args.sample_gap_limit_ms}ms"),
        ("resourceCompleteness", resources["incompleteSamples"] == 0,
         resources["incompleteSamples"], "0"),
        ("cpu", resources["maxFiveSecondCpuAverage"] < 0
         or resources["maxFiveSecondCpuAverage"] <= args.cpu_limit,
         resources["maxFiveSecondCpuAverage"], f"5秒滚动最大<={args.cpu_limit}"),
        ("heap", resources["peakHeapUsedBytes"] <= resources["heapMaxBytes"] * args.resource_limit,
         resources["peakHeapUsedBytes"] / resources["heapMaxBytes"], f"<={args.resource_limit}"),
        ("rss", resources["peakRssBytes"] >= 0
         and resources["peakRssBytes"] <= args.rss_budget_bytes * args.resource_limit,
         resources["peakRssBytes"] / args.rss_budget_bytes, f"<={args.resource_limit}"),
        ("threads", resources["peakThreadCount"] <= args.thread_budget * args.resource_limit,
         resources["peakThreadCount"] / args.thread_budget, f"<={args.resource_limit}"),
        ("fd", resources["maxFileDescriptors"] > 0
         and resources["peakOpenFileDescriptors"] >= 0
         and resources["peakOpenFileDescriptors"] <= resources["maxFileDescriptors"] * args.resource_limit,
         resources["peakOpenFileDescriptors"] / resources["maxFileDescriptors"], f"<={args.resource_limit}"),
    ]
    for name, passed, actual, expected in candidates:
        if not passed:
            add_check(checks, f"{prefix}.{phase}.{name}", False, actual, expected)
    return checks


def all_shard_resource_failures(args: argparse.Namespace, shards: list[Shard],
                                phase: str = "qualification") -> list[dict[str, Any]]:
    """聚合同一轮全部分片的资格失败；不能因首片越线而丢掉同 tick 的其他现场。"""
    failures: list[dict[str, Any]] = []
    for shard in shards:
        failures.extend(ramp_resource_failures(args, shard, shard.last_stats, phase))
    return failures


def poll_shard_stats(executor: concurrent.futures.ThreadPoolExecutor,
                     shards: list[Shard]) -> list[dict[str, Any]]:
    """并发读取各分片 stats，使宿主 CPU 合计代表同一编排采样点。"""
    stats = list(executor.map(
        lambda shard: http_json("GET", f"http://127.0.0.1:{shard.port}/simulations/stats", timeout=3),
        shards))
    for shard, current in zip(shards, stats):
        shard.last_stats = current
    return stats


def read_coordination_signal(path: Path | None, run_id: str, phase: str) -> dict[str, Any] | None:
    """读取并校验原子同步信号；存在但损坏的文件必须失败，不能退化为继续等待。"""
    if path is None or not path.is_file():
        return None
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError(f"{path.name} 不可读: {exception}") from exception
    if (not isinstance(document, dict) or document.get("schemaVersion") != 1
            or document.get("runId") != run_id or document.get("phase") != phase):
        raise ValueError(f"{path.name} schema/runId/phase 不符合本轮合同")
    return document


def coordination_status(now: float, minimum_deadline: float, performance_deadline: float,
                        drain_deadline: float | None, performance: dict[str, Any] | None,
                        drain: dict[str, Any] | None) -> str:
    """返回 RUN/PERFORMANCE_COMPLETE/COMPLETE/ABORT/TIMEOUT，且信号不能缩短最短稳态。"""
    if now < minimum_deadline:
        return "RUN"
    if performance is None:
        return "TIMEOUT" if now > performance_deadline else "RUN"
    if performance.get("outcome") != "COMPLETE" or performance.get("submitted") != 600:
        return "ABORT"
    if drain is None:
        if drain_deadline is None:
            return "PERFORMANCE_COMPLETE"
        return "TIMEOUT" if now > drain_deadline else "PERFORMANCE_COMPLETE"
    if drain.get("outcome") == "COMPLETE" and (drain.get("submitted"), drain.get("terminal")) == (600, 600):
        digest = drain.get("evidenceSha256")
        succeeded = drain.get("succeeded")
        if (isinstance(digest, str) and len(digest) == 64
                and all(character in "0123456789abcdef" for character in digest)
                and isinstance(succeeded, int) and 0 <= succeeded <= 600):
            return "COMPLETE"
    return "ABORT"


def aggregate(args: argparse.Namespace, run_id: str, shards: list[Shard],
              stopped: list[dict[str, Any]], clock_samples: list[dict[str, float]],
              host_resources: dict[str, Any],
              coordination: dict[str, Any] | None = None) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    all_property_ids: list[str] = []
    shard_reports: list[dict[str, Any]] = []
    if len(shards) != len(stopped):
        raise ValueError("停止统计与分片数量不一致")
    for shard, stats in zip(shards, stopped):
        connection = stats["connection"]
        resources = stats["generatorResources"]
        prefix = shard.shard_id
        add_check(checks, f"{prefix}.identity",
                  stats["runId"] == run_id and stats["shardId"] == shard.shard_id,
                  f"{stats['runId']}/{stats['shardId']}", f"{run_id}/{shard.shard_id}")
        success_rate = connection["succeeded"] / connection["target"] if connection["target"] else 0.0
        add_check(checks, f"{prefix}.initialConnectionRate", success_rate >= 0.999,
                  success_rate, ">=0.999")
        add_check(checks, f"{prefix}.allConnected",
                  connection["succeeded"] == connection["target"]
                  and connection["failed"] == 0 and connection["allConnectedAt"] is not None,
                  connection, "全部目标连接成功且失败=0")
        add_check(checks, f"{prefix}.rampDuration",
                  0 <= connection["rampDurationMillis"] <= args.ramp_deadline_seconds * 1000,
                  connection["rampDurationMillis"], f"<= {args.ramp_deadline_seconds * 1000}ms")
        add_check(checks, f"{prefix}.propertiesPerReport",
                  stats["propertiesPerReport"] == args.properties_per_report,
                  stats["propertiesPerReport"], str(args.properties_per_report))
        add_check(checks, f"{prefix}.failedOperations", stats["failedOperations"] == 0,
                  stats["failedOperations"], "0")
        add_check(checks, f"{prefix}.manifestHealthy", stats["manifestHealthy"],
                  stats.get("manifestFailureReason"), "healthy=true")
        add_check(checks, f"{prefix}.schedulingP99",
                  stats["schedulingDeviationSampleCount"] > 0
                  and stats["schedulingDeviationP99Millis"] <= args.interval_seconds * 1000 * 0.05,
                  {"p99Millis": stats["schedulingDeviationP99Millis"],
                   "samples": stats["schedulingDeviationSampleCount"]},
                  f"样本>0 且 P99<={args.interval_seconds * 50}ms")
        add_check(checks, f"{prefix}.resourceSamples",
                  resources["sampleCount"] >= args.steady_seconds - 2
                  and resources["maxSampleGapMillis"] <= args.sample_gap_limit_ms
                  and resources["incompleteSamples"] == 0,
                  {key: resources[key] for key in ("sampleCount", "maxSampleGapMillis", "incompleteSamples")},
                  f"样本>={args.steady_seconds - 2}, gap<={args.sample_gap_limit_ms}ms, incomplete=0")
        add_check(checks, f"{prefix}.cpu",
                  0 <= resources["maxFiveSecondCpuAverage"] <= args.cpu_limit,
                  resources["maxFiveSecondCpuAverage"], f"5秒滚动最大<={args.cpu_limit}")
        heap_ratio = resources["peakHeapUsedBytes"] / resources["heapMaxBytes"]
        rss_ratio = resources["peakRssBytes"] / args.rss_budget_bytes
        thread_ratio = resources["peakThreadCount"] / args.thread_budget
        fd_ratio = resources["peakOpenFileDescriptors"] / resources["maxFileDescriptors"]
        for name, ratio, denominator in (
                ("heap", heap_ratio, resources["heapMaxBytes"]),
                ("rss", rss_ratio, args.rss_budget_bytes),
                ("threads", thread_ratio, args.thread_budget),
                ("fd", fd_ratio, resources["maxFileDescriptors"])):
            add_check(checks, f"{prefix}.{name}", denominator > 0 and 0 <= ratio <= args.resource_limit,
                      ratio, f"使用率<={args.resource_limit}")

        for counter_name in ("propertyReports", "commandReplies", "configReplies", "batchReports"):
            counter = stats[counter_name]
            add_check(checks, f"{prefix}.{counter_name}.equation",
                      counter["initiated"] == counter["confirmed"] + counter["failed"],
                      counter, "initiated=confirmed+failed")
            add_check(checks, f"{prefix}.{counter_name}.noFailure", counter["failed"] == 0,
                      counter["failed"], "0")

        manifest_file = Path(stats["manifestDir"]) / "property_report.log"
        lines = [line.strip() for line in manifest_file.read_text(encoding="utf-8").splitlines() if line.strip()]
        all_property_ids.extend(lines)
        property_counter = stats["propertyReports"]
        add_check(checks, f"{prefix}.propertyManifestLines",
                  len(lines) == property_counter["confirmedMessages"],
                  len(lines), str(property_counter["confirmedMessages"]))
        add_check(checks, f"{prefix}.propertyManifestUnique",
                  len(set(lines)) == len(lines), len(set(lines)), str(len(lines)))
        shard_reports.append({"shardId": shard.shard_id, "deviceCount": len(shard.devices),
                              "stats": stats, "propertyManifestLines": len(lines),
                              "propertyManifestUnique": len(set(lines))})

    max_clock = max(sample["offsetMillis"] for sample in clock_samples)
    add_check(checks, "sutClockOffset", max_clock <= args.clock_limit_ms,
              max_clock, f"<={args.clock_limit_ms}ms")
    add_check(checks, "crossShardPropertyUniqueness",
              len(set(all_property_ids)) == len(all_property_ids),
              {"lines": len(all_property_ids), "unique": len(set(all_property_ids))}, "lines=unique")
    ramp_started = min(dt.datetime.fromisoformat(
        report["stats"]["connection"]["rampStartedAt"].replace("Z", "+00:00"))
        for report in shard_reports)
    all_connected = max(dt.datetime.fromisoformat(
        report["stats"]["connection"]["allConnectedAt"].replace("Z", "+00:00"))
        for report in shard_reports)
    aggregate_ramp_millis = int((all_connected - ramp_started).total_seconds() * 1000)
    add_check(checks, "aggregateConnectionRampDuration",
              0 <= aggregate_ramp_millis <= args.ramp_deadline_seconds * 1000,
              aggregate_ramp_millis, f"首片开始至末片全连<={args.ramp_deadline_seconds * 1000}ms")
    # 分片进程逐一不过线仍可能合计压满同一台发生器，因此宿主机账必须独立判定。
    host_rss_budget = (args.host_rss_budget_bytes
                       if args.host_rss_budget_bytes is not None
                       else args.rss_budget_bytes * len(shards))
    host_thread_budget = (args.host_thread_budget
                          if args.host_thread_budget is not None
                          else args.thread_budget * len(shards))
    aggregate_peak_rss = sum(report["stats"]["generatorResources"]["peakRssBytes"]
                             for report in shard_reports)
    aggregate_peak_threads = sum(report["stats"]["generatorResources"]["peakThreadCount"]
                                 for report in shard_reports)
    add_check(checks, "hostAggregate.rss", aggregate_peak_rss <= host_rss_budget * args.resource_limit,
              aggregate_peak_rss / host_rss_budget, f"使用率<={args.resource_limit}")
    add_check(checks, "hostAggregate.threads",
              aggregate_peak_threads <= host_thread_budget * args.resource_limit,
              aggregate_peak_threads / host_thread_budget, f"使用率<={args.resource_limit}")
    add_check(checks, "hostAggregate.cpuSamples",
              host_resources["sampleCount"] >= args.steady_seconds - 2
              and host_resources["maxSampleGapMillis"] <= args.sample_gap_limit_ms
              and host_resources["incompleteSamples"] == 0,
              {key: host_resources[key]
               for key in ("sampleCount", "maxSampleGapMillis", "incompleteSamples")},
              f"样本>={args.steady_seconds - 2}, gap<={args.sample_gap_limit_ms}ms, incomplete=0")
    # 各进程不同时刻的峰值不可相加；这里只判同一编排采样点合计后的五样本滚动最大值。
    host_cpu = host_resources["maxFiveSecondCpuAverage"]
    add_check(checks, "hostAggregate.cpu", 0 <= host_cpu <= args.cpu_limit,
              host_cpu, f"同点进程 CPU 合计的 5 样本滚动最大值<={args.cpu_limit}")
    return {
        "schemaVersion": 1,
        "runId": run_id,
        "result": "PASS" if all(item["passed"] for item in checks) else "FAIL",
        "startedAt": min(stats["connection"]["rampStartedAt"] for stats in stopped),
        "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        "configuration": {
            "deviceCount": args.device_count, "shardSize": args.shard_size,
            "shardCount": len(shards), "shardIdPrefix": getattr(args, "shard_id_prefix", None),
            "shardStartStaggerSeconds": args.shard_start_stagger_seconds,
            "intervalSeconds": args.interval_seconds,
            "propertiesPerReport": args.properties_per_report,
            "steadySeconds": args.steady_seconds, "rssBudgetBytesPerShard": args.rss_budget_bytes,
            "threadBudgetPerShard": args.thread_budget,
            "hostRssBudgetBytes": host_rss_budget, "hostThreadBudget": host_thread_budget,
            "xms": args.xms, "xmx": args.xmx,
            "brokerUri": args.broker_uri, "brokerFingerprint": args.broker_fingerprint,
        },
        "fingerprint": {
            "os": platform.platform(), "machine": platform.machine(),
            "logicalCpuCount": os.cpu_count(), "hostMemoryBytes": host_memory_bytes(),
            "python": platform.python_version(), "java": java_version(args.java),
            "jarSha256": sha256_file(args.jar),
            "runnerSha256": sha256_file(Path(__file__).resolve()),
        },
        "clockSamples": clock_samples,
        "coordination": coordination,
        "hostGeneratorResources": host_resources,
        "checks": checks,
        "shards": shard_reports,
        "aggregatePropertyManifest": {"lines": len(all_property_ids), "unique": len(set(all_property_ids))},
    }


def java_version(java: str) -> str:
    result = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=10)
    return (result.stderr or result.stdout).splitlines()[0]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def host_memory_bytes() -> int:
    try:
        return os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE")
    except (ValueError, OSError, AttributeError):
        return -1


def write_report(output_dir: Path, report: dict[str, Any]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "qualification-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    failed = [item for item in report["checks"] if not item["passed"]]
    lines = [
        "# A4-0 负载发生器资格报告", "", f"- runId：`{report['runId']}`",
        f"- 结论：**{report['result']}**", f"- 设备/分片：{report['configuration']['deviceCount']} / "
        f"{report['configuration']['shardCount']}",
        f"- 稳态：{report['configuration']['steadySeconds']} 秒；"
        f"{report['configuration']['propertiesPerReport']} 属性/报文；"
        f"{report['configuration']['intervalSeconds']} 秒/报文", "",
        "## 失败项", "",
    ]
    if failed:
        lines.extend(f"- `{item['name']}`：实际 `{item['actual']}`，要求 `{item['expected']}`" for item in failed)
    else:
        lines.append("- 无。所有冻结门槛均通过。")
    lines.extend(["", "## 证据", "",
                  "完整机器可读判定、硬件指纹、分片统计与时钟样本见 `qualification-report.json`；",
                  "每分片日志位于 `logs/`，PUBACK messageId 原始清单位于 `manifests/<runId>/<shardId>/`。", ""])
    (output_dir / "qualification-report.md").write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    args = parse_args()
    run_id = args.run_id or dt.datetime.now(dt.timezone.utc).strftime("a4-%Y%m%dT%H%M%SZ")
    output_dir = args.output_dir.resolve()
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError(f"输出目录必须不存在或为空，避免旧报告/manifest 混入本轮证据: {output_dir}")
    logs_dir = output_dir / "logs"
    manifest_dir = output_dir / "manifests"
    logs_dir.mkdir(parents=True, exist_ok=True)
    manifest_dir.mkdir(parents=True, exist_ok=True)
    devices = load_devices(args)
    partitions = [devices[index:index + args.shard_size]
                  for index in range(0, len(devices), args.shard_size)]
    shards: list[Shard] = []
    stats_executor: concurrent.futures.ThreadPoolExecutor | None = None
    host_cpu_tracker = HostCpuTracker()

    def cleanup(*_: Any) -> None:
        for shard in shards:
            if shard.process.poll() is None:
                shard.process.terminate()
        for shard in shards:
            if shard.process.poll() is None:
                try:
                    shard.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    shard.process.kill()
                    shard.process.wait(timeout=5)
            if not shard.log_handle.closed:
                shard.log_handle.close()

    def interrupt(signum: int, _frame: Any) -> None:
        cleanup()
        raise KeyboardInterrupt(f"收到信号 {signum}")

    signal.signal(signal.SIGTERM, interrupt)
    signal.signal(signal.SIGINT, interrupt)
    try:
        for index, partition in enumerate(partitions):
            shard_id = build_shard_id(args.shard_id_prefix, index)
            port = args.base_port + index
            log_handle = (logs_dir / f"{shard_id}.log").open("wb")
            command = [args.java, f"-Xms{args.xms}", f"-Xmx{args.xmx}", "-jar", str(args.jar),
                       f"--server.address=127.0.0.1", f"--server.port={port}",
                       f"--simulator.shard.max-devices={args.shard_size}"]
            environment = os.environ.copy()
            environment["SIMULATOR_MANIFEST_DIR"] = str(manifest_dir)
            process = subprocess.Popen(command, stdout=log_handle, stderr=subprocess.STDOUT, env=environment)
            shards.append(Shard(shard_id, port, partition, process, log_handle,
                                index * args.shard_start_stagger_seconds))
        for shard in shards:
            wait_ready(shard)
        stats_executor = concurrent.futures.ThreadPoolExecutor(max_workers=len(shards))

        clock_samples = []
        offset, rtt = read_sut_clock(args.sut_time_command)
        clock_samples.append({"phase": "before", "offsetMillis": offset, "roundTripMillis": rtt})
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=len(shards))
        futures = {executor.submit(start_shard, shard, args, run_id): shard for shard in shards}
        pending = set(futures)
        sampled_shards: set[str] = set()
        ramp_deadline = time.monotonic() + args.ramp_deadline_seconds + 5
        try:
            # `/start` 完成只代表连接爬坡完成；还要等每个分片取得首个资源样本，才允许进入稳态判定。
            while pending or len(sampled_shards) < len(shards):
                done, pending = concurrent.futures.wait(
                    pending, timeout=1.0, return_when=concurrent.futures.FIRST_COMPLETED)
                for future in done:
                    shard = futures[future]
                    shard.last_stats = future.result()
                for shard in shards:
                    if shard.process.poll() is not None:
                        raise RuntimeError(f"{shard.shard_id} 连接爬坡期退出，exit={shard.process.returncode}")
                current_stats = poll_shard_stats(stats_executor, shards)
                host_cpu_tracker.observe(current_stats, phase="connection-ramp")
                for shard in shards:
                    if shard.last_stats["generatorResources"]["sampleCount"] > 0:
                        sampled_shards.add(shard.shard_id)
                ramp_failures = all_shard_resource_failures(args, shards)
                if ramp_failures:
                    cleanup()
                    raise QualificationRejected(ramp_failures)
                if time.monotonic() > ramp_deadline:
                    cleanup()
                    if pending:
                        name = "connectionRampDeadline"
                        actual = "未在期限内完成全部分片连接"
                    else:
                        name = "resourceSamplingStartDeadline"
                        actual = {"missingShards": sorted(
                            shard.shard_id for shard in shards
                            if shard.shard_id not in sampled_shards)}
                    raise QualificationRejected([{
                        "name": name, "passed": False,
                        "actual": actual, "expected": f"<={args.ramp_deadline_seconds}s"}])
        finally:
            executor.shutdown(wait=True, cancel_futures=True)

        minimum_deadline = time.monotonic() + args.steady_seconds
        performance_deadline = minimum_deadline + args.performance_completion_grace_seconds
        performance_file = (args.performance_complete_file.resolve()
                            if args.performance_complete_file is not None else None)
        drain_file = (args.command_drain_complete_file.resolve()
                      if args.command_drain_complete_file is not None else None)
        drain_deadline: float | None = None
        accepted_performance: dict[str, Any] | None = None
        accepted_drain: dict[str, Any] | None = None
        if args.steady_ready_file is not None:
            ready_file = args.steady_ready_file.resolve()
            ready_file.parent.mkdir(parents=True, exist_ok=True)
            temporary = ready_file.with_suffix(ready_file.suffix + ".tmp")
            temporary.write_text(json.dumps({
                "runId": run_id,
                "steadySeconds": args.steady_seconds,
                "startedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                "shardPorts": [shard.port for shard in shards],
            }, ensure_ascii=False) + "\n", encoding="utf-8")
            # os.replace 保证探针不会读到半写 JSON；输出目录全新，所以不存在覆盖历史证据的情况。
            os.replace(temporary, ready_file)
        while True:
            now = time.monotonic()
            if performance_file is None:
                # 独立 A4-0/L0 没有并行命令探针，保持原有最短稳态结束即停语义。
                window_status = "COMPLETE" if now >= minimum_deadline else "RUN"
            else:
                performance = read_coordination_signal(
                    performance_file, run_id, "PERFORMANCE_COMPLETE")
                drain = read_coordination_signal(drain_file, run_id, "COMMAND_DRAIN_COMPLETE")
                if performance is not None and accepted_performance is None:
                    accepted_performance = performance
                    drain_deadline = now + args.command_drain_watchdog_seconds
                if drain is not None:
                    accepted_drain = drain
                window_status = coordination_status(
                    now, minimum_deadline, performance_deadline, drain_deadline,
                    accepted_performance, accepted_drain)
            if window_status == "COMPLETE":
                break
            if window_status == "ABORT":
                # 明确失败信号只授权 fail-closed 清理；不能冒充 600 条已安全排空。
                break
            if window_status == "TIMEOUT":
                raise TimeoutError(
                    "未在冻结期限内收到性能完成或命令排空信号")
            for shard in shards:
                if shard.process.poll() is not None:
                    raise RuntimeError(f"{shard.shard_id} 稳态期退出，exit={shard.process.returncode}")
            current_stats = poll_shard_stats(stats_executor, shards)
            host_cpu_tracker.observe(current_stats, phase="steady-state")
            for shard in shards:
                if not shard.last_stats["running"]:
                    raise RuntimeError(f"{shard.shard_id} 稳态期不再运行")
            steady_failures = all_shard_resource_failures(args, shards)
            if steady_failures:
                cleanup()
                raise QualificationRejected(steady_failures, "steady-state")
            time.sleep(1.0)

        offset, rtt = read_sut_clock(args.sut_time_command)
        clock_samples.append({"phase": "after", "offsetMillis": offset, "roundTripMillis": rtt})
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(shards)) as executor:
            stopped = list(executor.map(
                lambda shard: http_json("POST", f"http://127.0.0.1:{shard.port}/simulations/stop", timeout=30),
                shards))
        report = aggregate(args, run_id, shards, stopped, clock_samples, host_cpu_tracker.report())
        report["coordination"] = {
            "performance": accepted_performance,
            "commandDrain": accepted_drain,
            "stopMode": "NORMAL" if window_status == "COMPLETE" else "FAIL_CLOSED",
        }
        write_report(output_dir, report)
        print(f"[a4-0] {report['result']} report={output_dir / 'qualification-report.json'}")
        return 0 if report["result"] == "PASS" else 1
    except QualificationRejected as exception:
        report = {
            "schemaVersion": 1, "runId": run_id, "result": "FAIL",
            "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
            "configuration": {
                "deviceCount": args.device_count, "shardSize": args.shard_size,
                "shardCount": len(shards),
                "shardIdPrefix": getattr(args, "shard_id_prefix", None),
                "shardStartStaggerSeconds": args.shard_start_stagger_seconds,
                "steadySeconds": args.steady_seconds,
                "intervalSeconds": args.interval_seconds,
                "propertiesPerReport": args.properties_per_report,
                "rssBudgetBytesPerShard": args.rss_budget_bytes,
                "threadBudgetPerShard": args.thread_budget,
                "xms": args.xms, "xmx": args.xmx,
                "brokerUri": args.broker_uri, "brokerFingerprint": args.broker_fingerprint,
            },
            "checks": exception.checks,
            "phase": exception.phase,
            "hostGeneratorResources": host_cpu_tracker.report(),
            "shards": [{"shardId": shard.shard_id, "deviceCount": len(shard.devices),
                        "stats": shard.last_stats} for shard in shards],
            "fingerprint": {
                "os": platform.platform(), "machine": platform.machine(),
                "logicalCpuCount": os.cpu_count(), "hostMemoryBytes": host_memory_bytes(),
                "python": platform.python_version(), "java": java_version(args.java),
                "jarSha256": sha256_file(args.jar),
                "runnerSha256": sha256_file(Path(__file__).resolve()),
            },
        }
        write_report(output_dir, report)
        print(f"[a4-0] FAIL（{exception.phase} 资源门槛） report={output_dir / 'qualification-report.json'}")
        return 1
    except Exception as exception:  # noqa: BLE001 - 顶层必须把运行器失败转为可诊断报告/退出码
        failure = {"schemaVersion": 1, "runId": run_id, "result": "ERROR",
                   "finishedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                   "errorType": type(exception).__name__, "error": str(exception)}
        output_dir.mkdir(parents=True, exist_ok=True)
        (output_dir / "qualification-error.json").write_text(
            json.dumps(failure, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"[a4-0] ERROR {type(exception).__name__}: {exception}", file=sys.stderr)
        return 2
    finally:
        if stats_executor is not None:
            stats_executor.shutdown(wait=True, cancel_futures=True)
        cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
