#!/usr/bin/env python3
"""G1-C3e-0 固定 profile、固定 source、固定字段的统一指标采集合同。"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import math
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Callable


INTERVAL_MILLIS = 5_000
MAX_TICK_LATENESS_MILLIS = 500
PROFILE_CONTRACTS = {
    "C3E0_TOOL_QUALIFICATION": {
        "phase": "TOOL_QUALIFICATION", "durationMillis": 60_000,
        "evaluatorImplemented": True,
    },
    "S": {"phase": "PERFORMANCE", "durationMillis": 1_800_000, "evaluatorImplemented": False},
    "H": {"phase": "PERFORMANCE", "durationMillis": 1_800_000, "evaluatorImplemented": False},
    "M": {"phase": "PERFORMANCE", "durationMillis": 1_800_000, "evaluatorImplemented": False},
    "B": {"phase": "PERFORMANCE", "durationMillis": 1_800_000, "evaluatorImplemented": False},
    "E": {"phase": "PERFORMANCE", "durationMillis": 1_800_000, "evaluatorImplemented": False},
    "R": {"phase": "SCENARIO", "durationMillis": 3_900_000, "evaluatorImplemented": False},
}
GROUPS = ("things-link-ingestion-raw", "things-link-ingestion-normalized",
          "things-link-ingestion-processed")
CONTAINERS = ("backend", "postgresql-timescale", "redpanda", "redis", "emqx", "minio")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")


class MetricsError(RuntimeError):
    """指标计划、身份、字段、采样窗口或计数器不满足冻结合同。"""


def finite_number(value: Any, field: str, *, integer: bool = False, minimum: float = 0) -> None:
    """要求非 bool 的有限非负数；累计计数器可进一步要求整数。"""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        raise MetricsError(f"{field} 必须是有限数值")
    if integer and not isinstance(value, int):
        raise MetricsError(f"{field} 必须是整数")
    if value < minimum:
        raise MetricsError(f"{field} 必须 >= {minimum}")


def exact_keys(value: Any, keys: set[str], field: str) -> dict[str, Any]:
    """固定字段集合；未知字段与缺失字段同样拒绝，避免任意 JSON 假绿。"""
    if not isinstance(value, dict) or set(value) != keys:
        actual = set(value) if isinstance(value, dict) else set()
        raise MetricsError(f"{field} 字段集合不一致 missing={sorted(keys - actual)} extra={sorted(actual - keys)}")
    return value


def validate_timer(value: Any, field: str) -> None:
    """校验 Prometheus 累计 Timer 原始桶，保留后续有限桶分位重算所需事实。"""
    timer = exact_keys(value, {"buckets", "count", "sum"}, field)
    finite_number(timer["count"], f"{field}.count", integer=True)
    finite_number(timer["sum"], f"{field}.sum")
    buckets = timer["buckets"]
    if not isinstance(buckets, list) or len(buckets) < 2:
        raise MetricsError(f"{field}.buckets 至少包含一个有限桶和 +Inf")
    previous_upper = -math.inf
    previous_count = -1
    for index, bucket in enumerate(buckets):
        item = exact_keys(bucket, {"le", "count"}, f"{field}.buckets[{index}]")
        upper = item["le"]
        if upper == "+Inf":
            numeric_upper = math.inf
        else:
            finite_number(upper, f"{field}.buckets[{index}].le")
            numeric_upper = float(upper)
        finite_number(item["count"], f"{field}.buckets[{index}].count", integer=True)
        if numeric_upper <= previous_upper or item["count"] < previous_count:
            raise MetricsError(f"{field}.buckets 上界或累计计数非单调")
        previous_upper, previous_count = numeric_upper, item["count"]
    if buckets[-1]["le"] != "+Inf" or buckets[-1]["count"] != timer["count"]:
        raise MetricsError(f"{field} 最后桶必须是 +Inf 且 count 与总数一致")


def validate_host_metrics(value: Any, field: str, *, generator: bool) -> None:
    """校验宿主资源；发生器额外冻结调度器、线程与 FD 字段。"""
    common = {"cpuPercent", "memoryUsedBytes", "memoryTotalBytes", "swapUsedBytes",
              "rootDiskFreeBytes", "rootDiskTotalBytes", "dataDiskFreeBytes", "dataDiskTotalBytes",
              "dataDiskReadOnly", "oomKillCount", "networkRxBytes", "networkTxBytes"}
    generator_fields = {"threadCount", "fdCount", "tickLatenessMillis",
                        "scheduledOperations", "submittedOperations", "pubackCount"}
    metrics = exact_keys(value, common | (generator_fields if generator else set()), field)
    for key in common - {"dataDiskReadOnly"}:
        finite_number(metrics[key], f"{field}.{key}", integer=key != "cpuPercent")
    for key in ("memoryTotalBytes", "rootDiskTotalBytes", "dataDiskTotalBytes"):
        if metrics[key] <= 0:
            raise MetricsError(f"{field}.{key} 必须大于零")
    if not isinstance(metrics["dataDiskReadOnly"], bool):
        raise MetricsError(f"{field}.dataDiskReadOnly 必须是 bool")
    if metrics["memoryUsedBytes"] > metrics["memoryTotalBytes"]:
        raise MetricsError(f"{field}.memoryUsedBytes 不得超过 memoryTotalBytes")
    for prefix in ("rootDisk", "dataDisk"):
        if metrics[f"{prefix}FreeBytes"] > metrics[f"{prefix}TotalBytes"]:
            raise MetricsError(f"{field}.{prefix}FreeBytes 不得超过总容量")
    if generator:
        for key in generator_fields:
            finite_number(metrics[key], f"{field}.{key}", integer=True)
        if not metrics["pubackCount"] <= metrics["submittedOperations"] <= metrics["scheduledOperations"]:
            raise MetricsError(f"{field} 必须满足 pubackCount <= submittedOperations <= scheduledOperations")


def validate_container_runtime(value: Any, field: str) -> None:
    """要求六个运行单元全部出现，Timescale 归入 PostgreSQL 容器。"""
    root = exact_keys(value, {"containers"}, field)
    containers = exact_keys(root["containers"], set(CONTAINERS), f"{field}.containers")
    keys = {"cpuPercent", "rssBytes", "memoryLimitBytes", "restartCount", "running", "oomKilled",
            "networkRxBytes", "networkTxBytes", "blockReadBytes", "blockWriteBytes"}
    for name, raw in containers.items():
        item = exact_keys(raw, keys, f"{field}.containers.{name}")
        for key in keys - {"running", "oomKilled"}:
            finite_number(item[key], f"{field}.containers.{name}.{key}", integer=key != "cpuPercent")
        if not isinstance(item["running"], bool) or not isinstance(item["oomKilled"], bool):
            raise MetricsError(f"{field}.containers.{name} running/oomKilled 必须是 bool")
        if item["memoryLimitBytes"] <= 0 or item["rssBytes"] > item["memoryLimitBytes"]:
            raise MetricsError(f"{field}.containers.{name} RSS/内存上限不自洽")


def validate_backend(value: Any, field: str) -> None:
    """固定共同业务 Timer、连接池、失败计数和后台队列字段。"""
    keys = {"uplinkLatencySeconds", "commandAcceptanceSeconds", "hikariAcquireSeconds", "hikari",
            "http5xxTotal", "timeSeriesSuccessTotal", "timeSeriesFailureTotal", "dlqTotal",
            "outboxPending", "workerFailuresTotal", "schedulerFailuresTotal"}
    metrics = exact_keys(value, keys, field)
    validate_timer(metrics["uplinkLatencySeconds"], f"{field}.uplinkLatencySeconds")
    validate_timer(metrics["commandAcceptanceSeconds"], f"{field}.commandAcceptanceSeconds")
    acquires = exact_keys(metrics["hikariAcquireSeconds"], {"CONTROL", "DATA"},
                          f"{field}.hikariAcquireSeconds")
    pools = exact_keys(metrics["hikari"], {"CONTROL", "DATA"}, f"{field}.hikari")
    for pool in ("CONTROL", "DATA"):
        validate_timer(acquires[pool], f"{field}.hikariAcquireSeconds.{pool}")
        state = exact_keys(pools[pool], {"active", "idle", "pending", "max", "timeoutTotal"},
                           f"{field}.hikari.{pool}")
        for key, raw in state.items():
            finite_number(raw, f"{field}.hikari.{pool}.{key}", integer=True)
        if state["max"] <= 0:
            raise MetricsError(f"{field}.hikari.{pool}.max 必须大于零")
        if state["active"] + state["idle"] > state["max"]:
            raise MetricsError(f"{field}.hikari.{pool} active+idle 不得超过 max")
    for key in keys - {"uplinkLatencySeconds", "commandAcceptanceSeconds", "hikariAcquireSeconds", "hikari"}:
        finite_number(metrics[key], f"{field}.{key}", integer=True)


def validate_source_metrics(source_type: str, value: Any, field: str) -> None:
    """按 source type 调度唯一 schema，不提供任意扩展口。"""
    if source_type == "sut-host":
        validate_host_metrics(value, field, generator=False)
    elif source_type == "generator-host":
        validate_host_metrics(value, field, generator=True)
    elif source_type == "container-runtime":
        validate_container_runtime(value, field)
    elif source_type == "backend":
        validate_backend(value, field)
    elif source_type == "postgresql-timescale":
        metrics = exact_keys(value, {"connectionsUsed", "connectionsMax", "pgStatStatementsAvailable",
                                     "pgStatStatementsResetEpochMillis"}, field)
        finite_number(metrics["connectionsUsed"], f"{field}.connectionsUsed", integer=True)
        finite_number(metrics["connectionsMax"], f"{field}.connectionsMax", integer=True, minimum=1)
        finite_number(metrics["pgStatStatementsResetEpochMillis"],
                      f"{field}.pgStatStatementsResetEpochMillis", integer=True)
        if not isinstance(metrics["pgStatStatementsAvailable"], bool):
            raise MetricsError(f"{field}.pgStatStatementsAvailable 必须是 bool")
        if metrics["connectionsUsed"] > metrics["connectionsMax"]:
            raise MetricsError(f"{field}.connectionsUsed 不得超过 connectionsMax")
    elif source_type == "redpanda":
        metrics = exact_keys(value, {"groupLag", "healthy"}, field)
        lags = exact_keys(metrics["groupLag"], set(GROUPS), f"{field}.groupLag")
        for group, lag in lags.items():
            finite_number(lag, f"{field}.groupLag.{group}", integer=True)
        if not isinstance(metrics["healthy"], bool):
            raise MetricsError(f"{field}.healthy 必须是 bool")
    elif source_type == "redis":
        metrics = exact_keys(value, {"evictedKeys", "healthy"}, field)
        finite_number(metrics["evictedKeys"], f"{field}.evictedKeys", integer=True)
        if not isinstance(metrics["healthy"], bool):
            raise MetricsError(f"{field}.healthy 必须是 bool")
    elif source_type == "emqx":
        metrics = exact_keys(value, {"connectedClients", "actionFailedTotal", "actionDroppedTotal",
                                     "ruleActionsFailedTotal", "healthy"}, field)
        for key in set(metrics) - {"healthy"}:
            finite_number(metrics[key], f"{field}.{key}", integer=True)
        if not isinstance(metrics["healthy"], bool):
            raise MetricsError(f"{field}.healthy 必须是 bool")
    elif source_type == "minio":
        metrics = exact_keys(value, {"healthy"}, field)
        if not isinstance(metrics["healthy"], bool):
            raise MetricsError(f"{field}.healthy 必须是 bool")
    else:
        raise MetricsError(f"未知 sourceType: {source_type}")


def strict_json_loads(raw: str, description: str) -> Any:
    """统一拒绝重复键与 Python JSON 默认接受的非有限常量。"""
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise MetricsError(f"{description} 含重复键: {key}")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
                      parse_constant=lambda token: (_ for _ in ()).throw(
                          MetricsError(f"{description} 含非有限数值: {token}")))


def read_object(path: Path) -> dict[str, Any]:
    """读取 JSON 对象并拒绝 NaN/Infinity 与重复键。"""
    try:
        value = strict_json_loads(path.read_text(encoding="utf-8"), path.name)
    except MetricsError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise MetricsError(f"无法读取 {path}: {exception}") from exception
    if not isinstance(value, dict) or not value:
        raise MetricsError(f"{path.name} 顶层必须是非空对象")
    return value


def sha256_file(path: Path) -> str:
    """计算证据 SHA-256。"""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_sibling(name: str) -> Any:
    """加载环境清单纯函数，保证 source 身份来自已验证 inventory。"""
    path = Path(__file__).resolve().with_name(f"{name}.py")
    specification = importlib.util.spec_from_file_location(f"metrics_{name}", path)
    if specification is None or specification.loader is None:
        raise MetricsError(f"无法加载 {name}")
    module = importlib.util.module_from_spec(specification)
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


def plan_relative(plan_path: Path, value: Any, field: str) -> Path:
    """身份文件必须与 plan 同目录且只用单文件名，避免替换为目录外输入。"""
    if not isinstance(value, str) or Path(value).name != value:
        raise MetricsError(f"{field} 必须是单个文件名")
    return plan_path.parent / value


def derive_expected_sources(inventory: dict[str, Any], distributed: dict[str, Any]) -> list[dict[str, str]]:
    """从环境与分布式计划派生唯一 source 集，不接受 plan 自报角色或组件。"""
    sut_id = inventory.get("sut", {}).get("hostId")
    generator_ids = [item.get("hostId") for item in inventory.get("generators", [])
                     if isinstance(item, dict)]
    plan_ids = [item.get("hostId") for item in distributed.get("hosts", [])
                if isinstance(item, dict)]
    if not isinstance(sut_id, str) or not IDENTIFIER.fullmatch(sut_id) \
            or not generator_ids or any(not isinstance(item, str) or not IDENTIFIER.fullmatch(item)
                                         for item in generator_ids):
        raise MetricsError("环境清单 hostId 非法")
    if len(plan_ids) != len(generator_ids) or set(plan_ids) != set(generator_ids):
        raise MetricsError("distributed plan 与 inventory 的 generator host 集不一致")
    fixed = [
        {"sourceId": "sut-host", "sourceType": "sut-host", "hostId": sut_id,
         "role": "sut", "component": "host"},
        {"sourceId": "sut-container-runtime", "sourceType": "container-runtime", "hostId": sut_id,
         "role": "sut", "component": "container-runtime"},
    ]
    for source_type in ("backend", "postgresql-timescale", "redpanda", "redis", "emqx", "minio"):
        fixed.append({"sourceId": f"sut-{source_type}", "sourceType": source_type, "hostId": sut_id,
                      "role": "middleware" if source_type != "backend" else "sut",
                      "component": source_type})
    for host_id in sorted(generator_ids):
        fixed.append({"sourceId": f"generator-{host_id}", "sourceType": "generator-host",
                      "hostId": host_id, "role": "generator", "component": "host"})
    return fixed


def validate_plan(path: Path, *, require_implemented: bool = False) -> dict[str, Any]:
    """重算环境指纹、source 集和固定 profile 窗口；plan 只能提供采集 argv/输出名。"""
    plan = read_object(path)
    profile = plan.get("profile")
    contract = PROFILE_CONTRACTS.get(profile)
    if contract is None:
        raise MetricsError("profile 不在冻结集合")
    if require_implemented and not contract["evaluatorImplemented"]:
        raise MetricsError(f"profile {profile} evaluator 尚未实现")
    required = {"schemaVersion", "runId", "environmentFingerprint", "profile", "phase",
                "intervalMillis", "durationMillis", "exactSampleCount", "maxTickLatenessMillis",
                "evaluatorImplemented", "inventoryFile", "inventorySha256",
                "distributedPlanFile", "distributedPlanSha256", "sources"}
    exact_keys(plan, required, "指标计划")
    run_id, fingerprint = plan["runId"], plan["environmentFingerprint"]
    if plan["schemaVersion"] != 1 or not isinstance(run_id, str) or not IDENTIFIER.fullmatch(run_id) \
            or not isinstance(fingerprint, str) or not SHA256.fullmatch(fingerprint):
        raise MetricsError("指标计划 schema/runId/environmentFingerprint 非法")
    # 首个样本固定在 t=0，末个样本固定在 t=duration；因此 30min/5s 是 361 行而不是 360 行。
    expected_samples = contract["durationMillis"] // INTERVAL_MILLIS + 1
    expected_contract = (contract["phase"], INTERVAL_MILLIS, contract["durationMillis"],
                         expected_samples, MAX_TICK_LATENESS_MILLIS, contract["evaluatorImplemented"])
    actual_contract = (plan["phase"], plan["intervalMillis"], plan["durationMillis"],
                       plan["exactSampleCount"], plan["maxTickLatenessMillis"],
                       plan["evaluatorImplemented"])
    if actual_contract != expected_contract:
        raise MetricsError("profile 的 phase/5s tick/精确窗口/evaluator 标记漂移")
    inventory_path = plan_relative(path, plan["inventoryFile"], "inventoryFile")
    distributed_path = plan_relative(path, plan["distributedPlanFile"], "distributedPlanFile")
    try:
        hashes_match = (sha256_file(inventory_path) == plan["inventorySha256"]
                        and sha256_file(distributed_path) == plan["distributedPlanSha256"])
    except OSError as exception:
        raise MetricsError("inventory/distributed plan 文件缺失") from exception
    if not hashes_match:
        raise MetricsError("inventory/distributed plan SHA-256 错配")
    environment_module = load_sibling("l2_environment_inventory")
    inventory = environment_module.normalize(environment_module.read_json(inventory_path))
    errors = environment_module.validate_inventory(inventory)
    if errors or inventory_path.read_bytes() != environment_module.canonical_bytes(inventory):
        raise MetricsError("environment inventory 未通过规范化校验")
    recomputed_fingerprint = hashlib.sha256(environment_module.canonical_bytes(
        environment_module.fingerprint_document(inventory))).hexdigest()
    coordinator_module = load_sibling("l2_distributed_coordinator")
    try:
        distributed, _ = coordinator_module.load_plan(distributed_path)
    except Exception as exception:  # noqa: BLE001 - coordinator 的严格拒绝统一映射为指标合同失败。
        raise MetricsError(f"distributed plan 未通过协调器校验: {exception}") from exception
    if distributed.get("runId") != run_id or distributed.get("environmentFingerprint") != fingerprint \
            or recomputed_fingerprint != fingerprint:
        raise MetricsError("inventory/distributed/run identity 不一致")
    expected_artifacts = {
        "simulatorJarSha256": inventory["artifacts"]["simulatorJarSha256"],
        "a4RunnerSha256": inventory["artifacts"]["scriptsSha256"]["a4_qualification.py"],
    }
    if distributed.get("artifacts") != expected_artifacts:
        raise MetricsError("distributed plan artifact SHA 与 inventory 不一致")
    expected = derive_expected_sources(inventory, distributed)
    sources = plan["sources"]
    if not isinstance(sources, list) or len(sources) != len(expected):
        raise MetricsError("指标 source 数量与最终拓扑不一致")
    expected_by_id = {item["sourceId"]: item for item in expected}
    seen_outputs: set[str] = set()
    for source in sources:
        keys = {"sourceId", "sourceType", "hostId", "role", "component",
                "argv", "timeoutMillis", "outputFile"}
        exact_keys(source, keys, "指标 source")
        identity = expected_by_id.get(source["sourceId"])
        if identity is None or any(source.get(key) != value for key, value in identity.items()):
            raise MetricsError(f"source {source.get('sourceId')} 身份未由最终拓扑派生")
        argv = source["argv"]
        if not isinstance(argv, list) or not argv or any(not isinstance(item, str) or not item for item in argv):
            raise MetricsError(f"source {source['sourceId']} argv 非法")
        if Path(argv[0]).name.lower() in {"sh", "bash", "cmd", "cmd.exe", "powershell", "pwsh"}:
            raise MetricsError(f"source {source['sourceId']} 禁止 shell 入口")
        lowered = " ".join(argv).lower()
        if any(word in lowered for word in ("authorization", "bearer", "password", "token", "secret")):
            raise MetricsError(f"source {source['sourceId']} argv 疑似包含秘密")
        timeout = source["timeoutMillis"]
        if not isinstance(timeout, int) or isinstance(timeout, bool) or not 1 <= timeout < INTERVAL_MILLIS:
            raise MetricsError(f"source {source['sourceId']} timeoutMillis 必须位于 1..4999")
        output = source["outputFile"]
        if not isinstance(output, str) or Path(output).name != output or not output.endswith(".jsonl") \
                or output in seen_outputs:
            raise MetricsError("source outputFile 非法或重复")
        seen_outputs.add(output)
    if set(expected_by_id) != {source["sourceId"] for source in sources}:
        raise MetricsError("source 集缺失或重复")
    return plan


def run_source(source: dict[str, Any]) -> dict[str, Any]:
    """以 shell=False 执行有界采集，并立即应用 source type 固定 schema。"""
    try:
        completed = subprocess.run(source["argv"], capture_output=True, text=True, shell=False,
                                   timeout=source["timeoutMillis"] / 1000.0, check=False)
    except subprocess.TimeoutExpired as exception:
        raise MetricsError(f"source {source['sourceId']} 采集超时") from exception
    if completed.returncode != 0:
        raise MetricsError(f"source {source['sourceId']} 退出码 {completed.returncode}")
    try:
        metrics = strict_json_loads(completed.stdout, f"source {source['sourceId']}")
    except MetricsError:
        raise
    except json.JSONDecodeError as exception:
        raise MetricsError(f"source {source['sourceId']} stdout 不是 JSON") from exception
    validate_source_metrics(source["sourceType"], metrics, f"source.{source['sourceId']}.metrics")
    return metrics


def sample(plan_path: Path, output_dir: Path, *, monotonic: Callable[[], float] = time.monotonic,
           epoch_millis: Callable[[], int] = lambda: time.time_ns() // 1_000_000,
           sleeper: Callable[[float], None] = time.sleep) -> list[Path]:
    """固定 tick 并发采样；注入时钟只为单测解耦 30/65 分钟真实等待。"""
    plan = validate_plan(plan_path, require_implemented=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    paths = {source["sourceId"]: output_dir / source["outputFile"] for source in plan["sources"]}
    if any(path.exists() for path in paths.values()):
        raise MetricsError("指标输出已存在，拒绝覆盖旧证据")
    handles = {key: path.open("w", encoding="utf-8", newline="\n") for key, path in paths.items()}
    started = monotonic()
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(plan["sources"])) as executor:
            for sequence in range(plan["exactSampleCount"]):
                target = started + sequence * INTERVAL_MILLIS / 1000.0
                now = monotonic()
                if now > target + MAX_TICK_LATENESS_MILLIS / 1000.0:
                    raise MetricsError(f"采样漏 tick sequence={sequence}，拒绝追赶补发")
                if target > now:
                    sleeper(target - now)
                observed = monotonic()
                scheduled_millis = round(target * 1000)
                observed_millis = round(observed * 1000)
                epoch = epoch_millis()
                futures = {source["sourceId"]: executor.submit(run_source, source)
                           for source in plan["sources"]}
                for source in plan["sources"]:
                    row = {"schemaVersion": 1, "runId": plan["runId"],
                           "environmentFingerprint": plan["environmentFingerprint"],
                           "profile": plan["profile"], "phase": plan["phase"],
                           "source": {key: source[key] for key in
                                      ("sourceId", "sourceType", "hostId", "role", "component")},
                           "sequence": sequence, "scheduledMonotonicMillis": scheduled_millis,
                           "observedMonotonicMillis": observed_millis, "epochMillis": epoch,
                           "metrics": futures[source["sourceId"]].result()}
                    handles[source["sourceId"]].write(json.dumps(
                        row, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
                    handles[source["sourceId"]].flush()
    finally:
        for handle in handles.values():
            handle.close()
    return list(paths.values())


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    """严格读取 JSONL 行。"""
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exception:
        raise MetricsError(f"无法读取指标源 {path}") from exception
    rows = []
    for line_no, raw in enumerate(lines, 1):
        if not raw:
            raise MetricsError(f"{path.name}:{line_no} 不允许空行")
        try:
            row = strict_json_loads(raw, f"{path.name}:{line_no}")
        except MetricsError:
            raise
        except json.JSONDecodeError as exception:
            raise MetricsError(f"{path.name}:{line_no} JSON 畸形") from exception
        if not isinstance(row, dict):
            raise MetricsError(f"{path.name}:{line_no} 必须是对象")
        rows.append(row)
    if not rows:
        raise MetricsError(f"指标源 {path.name} 为空")
    return rows


def counter_paths(source_type: str, metrics: dict[str, Any]) -> dict[str, int | float]:
    """提取必须跨样本单调的累计计数器，gauge 不参与回退判定。"""
    counters: dict[str, int | float] = {}
    if source_type in {"sut-host", "generator-host"}:
        for key in ("oomKillCount", "networkRxBytes", "networkTxBytes"):
            counters[key] = metrics[key]
        if source_type == "generator-host":
            for key in ("scheduledOperations", "submittedOperations", "pubackCount"):
                counters[key] = metrics[key]
    elif source_type == "container-runtime":
        for name, item in metrics["containers"].items():
            for key in ("restartCount", "networkRxBytes", "networkTxBytes", "blockReadBytes", "blockWriteBytes"):
                counters[f"containers.{name}.{key}"] = item[key]
    elif source_type == "backend":
        for timer_name in ("uplinkLatencySeconds", "commandAcceptanceSeconds"):
            counters[f"{timer_name}.count"] = metrics[timer_name]["count"]
            counters[f"{timer_name}.sum"] = metrics[timer_name]["sum"]
            for index, bucket in enumerate(metrics[timer_name]["buckets"]):
                counters[f"{timer_name}.buckets.{index}"] = bucket["count"]
        for pool in ("CONTROL", "DATA"):
            timer = metrics["hikariAcquireSeconds"][pool]
            counters[f"hikariAcquireSeconds.{pool}.count"] = timer["count"]
            counters[f"hikariAcquireSeconds.{pool}.sum"] = timer["sum"]
            for index, bucket in enumerate(timer["buckets"]):
                counters[f"hikariAcquireSeconds.{pool}.buckets.{index}"] = bucket["count"]
            counters[f"hikari.{pool}.timeoutTotal"] = metrics["hikari"][pool]["timeoutTotal"]
        for key in ("http5xxTotal", "timeSeriesSuccessTotal", "timeSeriesFailureTotal",
                    "dlqTotal", "workerFailuresTotal", "schedulerFailuresTotal"):
            counters[key] = metrics[key]
    elif source_type == "redis":
        counters["evictedKeys"] = metrics["evictedKeys"]
    elif source_type == "emqx":
        for key in ("actionFailedTotal", "actionDroppedTotal", "ruleActionsFailedTotal"):
            counters[key] = metrics[key]
    return counters


def timer_boundaries(source_type: str, metrics: dict[str, Any]) -> dict[str, tuple[Any, ...]]:
    """冻结同一运行内 Timer 桶边界；只按数组下标比较计数会掩盖边界漂移。"""
    if source_type != "backend":
        return {}
    result = {
        name: tuple(bucket["le"] for bucket in metrics[name]["buckets"])
        for name in ("uplinkLatencySeconds", "commandAcceptanceSeconds")
    }
    for pool in ("CONTROL", "DATA"):
        result[f"hikariAcquireSeconds.{pool}"] = tuple(
            bucket["le"] for bucket in metrics["hikariAcquireSeconds"][pool]["buckets"])
    return result


def normalize_source(path: Path, plan: dict[str, Any], expected: dict[str, Any]) \
        -> tuple[list[dict[str, Any]], dict[str, Any]]:
    """验证单源精确行数、身份、5s tick、固定字段和累计计数器。"""
    rows = read_jsonl(path)
    if len(rows) != plan["exactSampleCount"]:
        raise MetricsError(f"{path.name} 行数必须精确为 {plan['exactSampleCount']}")
    previous_counters: dict[str, int | float] | None = None
    expected_timer_boundaries: dict[str, tuple[Any, ...]] | None = None
    first_scheduled: int | None = None
    for sequence, row in enumerate(rows):
        required = {"schemaVersion", "runId", "environmentFingerprint", "profile", "phase", "source",
                    "sequence", "scheduledMonotonicMillis", "observedMonotonicMillis", "epochMillis", "metrics"}
        exact_keys(row, required, f"{path.name}:{sequence + 1}")
        expected_identity = {key: expected[key] for key in
                             ("sourceId", "sourceType", "hostId", "role", "component")}
        if (row["schemaVersion"] != 1 or row["runId"] != plan["runId"]
                or row["environmentFingerprint"] != plan["environmentFingerprint"]
                or row["profile"] != plan["profile"] or row["phase"] != plan["phase"]
                or row["source"] != expected_identity or row["sequence"] != sequence):
            raise MetricsError(f"{path.name} identity/phase/sequence 漂移")
        for key in ("scheduledMonotonicMillis", "observedMonotonicMillis", "epochMillis"):
            finite_number(row[key], f"{path.name}:{sequence + 1}.{key}", integer=True)
        if first_scheduled is None:
            first_scheduled = row["scheduledMonotonicMillis"]
        if row["scheduledMonotonicMillis"] != first_scheduled + sequence * INTERVAL_MILLIS:
            raise MetricsError(f"{path.name} scheduled tick 不是精确 5s 序列")
        lateness = row["observedMonotonicMillis"] - row["scheduledMonotonicMillis"]
        if not 0 <= lateness <= MAX_TICK_LATENESS_MILLIS:
            raise MetricsError(f"{path.name} tick lateness 越过 500ms")
        validate_source_metrics(expected["sourceType"], row["metrics"],
                                f"{path.name}:{sequence + 1}.metrics")
        boundaries = timer_boundaries(expected["sourceType"], row["metrics"])
        if expected_timer_boundaries is None:
            expected_timer_boundaries = boundaries
        elif boundaries != expected_timer_boundaries:
            raise MetricsError(f"{path.name} Timer 桶边界漂移")
        current = counter_paths(expected["sourceType"], row["metrics"])
        if previous_counters is not None:
            regressed = [key for key, value in current.items() if value < previous_counters.get(key, value)]
            if regressed:
                raise MetricsError(f"{path.name} 累计计数器回退: {sorted(regressed)}")
        previous_counters = current
    return rows, {"sourceId": expected["sourceId"], "sourceType": expected["sourceType"],
                  "hostId": expected["hostId"], "role": expected["role"],
                  "component": expected["component"], "sourceFile": f"metric-sources/{path.name}",
                  "sampleCount": len(rows), "durationMillis": plan["durationMillis"],
                  "sourceSha256": sha256_file(path)}


def recompute(plan_path: Path, source_dir: Path,
              unified_filename: str) -> tuple[bytes, dict[str, Any]]:
    """从固定原始源重算统一字节，并校验所有 source 的 tick/epoch 完全对齐。"""
    plan = validate_plan(plan_path, require_implemented=True)
    if Path(unified_filename).name != unified_filename:
        raise MetricsError("统一指标文件名必须是单个文件名")
    expected_paths = {source_dir / source["outputFile"]: source for source in plan["sources"]}
    actual_paths = set(source_dir.glob("*.jsonl"))
    if actual_paths != set(expected_paths):
        raise MetricsError("指标源集合不匹配")
    rows_by_source: list[list[dict[str, Any]]] = []
    summaries = []
    for path, expected in expected_paths.items():
        rows, summary = normalize_source(path, plan, expected)
        rows_by_source.append(rows)
        summaries.append(summary)
    for sequence in range(plan["exactSampleCount"]):
        tick = {(rows[sequence]["scheduledMonotonicMillis"],
                 rows[sequence]["observedMonotonicMillis"], rows[sequence]["epochMillis"])
                for rows in rows_by_source}
        if len(tick) != 1:
            raise MetricsError(f"跨源 sequence={sequence} tick/epoch 漂移")
    all_rows = [row for rows in rows_by_source for row in rows]
    ordered = sorted(all_rows, key=lambda row: (row["sequence"], row["source"]["sourceId"]))
    rendered = "".join(json.dumps(row, ensure_ascii=False, sort_keys=True,
                                  separators=(",", ":")) + "\n" for row in ordered).encode("utf-8")
    summary = {"schemaVersion": 1, "runId": plan["runId"],
               "environmentFingerprint": plan["environmentFingerprint"],
               "profile": plan["profile"], "phase": plan["phase"], "result": "PASS",
               "sourcePlanFile": plan_path.name, "sourcePlanSha256": sha256_file(plan_path),
               "sourceCount": len(summaries), "sampleCountPerSource": plan["exactSampleCount"],
               "intervalMillis": INTERVAL_MILLIS, "durationMillis": plan["durationMillis"],
               "sources": sorted(summaries, key=lambda item: item["sourceId"]),
               "unifiedMetricsFile": unified_filename,
               "unifiedMetricsSha256": hashlib.sha256(rendered).hexdigest()}
    return rendered, summary


def collect(plan_path: Path, source_dir: Path, output: Path, summary_path: Path) -> dict[str, Any]:
    """按纯函数重算并原子发布统一证据。"""
    rendered, summary = recompute(plan_path, source_dir, output.name)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_bytes(rendered)
    os.replace(temporary, output)
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return summary


def parse_args() -> argparse.Namespace:
    """区分真实 60 秒工具资格采样与纯函数归一化。"""
    parser = argparse.ArgumentParser(description="G1-C3e 固定合同统一指标")
    sub = parser.add_subparsers(dest="action", required=True)
    sampler = sub.add_parser("sample")
    sampler.add_argument("--plan", type=Path, required=True)
    sampler.add_argument("--output-dir", type=Path, required=True)
    collector = sub.add_parser("collect")
    collector.add_argument("--plan", type=Path, required=True)
    collector.add_argument("--source-dir", type=Path, required=True)
    collector.add_argument("--output", type=Path, required=True)
    collector.add_argument("--summary-output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.action == "sample":
        sample(args.plan, args.output_dir)
    else:
        collect(args.plan, args.source_dir, args.output, args.summary_output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
