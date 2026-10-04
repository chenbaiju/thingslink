#!/usr/bin/env python3
"""校验并规范化 G1-C3e 最终环境清单，生成稳定归档 SHA 与比较指纹。"""

from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
IMAGE_DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
PLACEHOLDER_RE = re.compile(r"^(?:todo|tbd|unknown|n/?a|none|replace[-_ ]?me|change[-_ ]?me|example)$",
                            re.IGNORECASE)
SENSITIVE_KEY_RE = re.compile(
    r"(^|[^a-z])(password|passwd|secret|token|authorization|credential|privatekey|apikey)([^a-z]|$)")
UTC_TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")
HOST_COMMON_FIELDS = (
    "hostId", "cloud", "hardware", "os", "disks", "runtime", "jvm", "network", "ntp",
    "images", "configSha256",
)
REQUIRED_SCRIPT_NAMES = (
    "a4_qualification.py",
    "l2_atomic_receive.py",
    "l2_ssh_transfer.py",
    "l2_ssh_phase_bridge.py",
    "l2_distributed_coordinator.py",
    "l2_evidence_manifest.py",
    "l2_environment_inventory.py",
    "l2_fixture.py",
    "l2_machine_verdict.py",
    "l2_metrics.py",
    "run_c3e0.py",
)


class InventoryError(RuntimeError):
    """表示环境清单不能形成可信且无秘密的机器证据。"""


def parse_args() -> argparse.Namespace:
    """读取清单输入及两个相互独立的机器输出路径。"""
    parser = argparse.ArgumentParser(description="G1-C3e 最终环境清单校验与规范化")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--normalized-output", type=Path, required=True)
    parser.add_argument("--report-output", type=Path, required=True)
    return parser.parse_args()


def read_json(path: Path) -> dict[str, Any]:
    """读取 JSON 对象，拒绝重复键，避免规范化时静默覆盖配置。"""
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise InventoryError(f"JSON 含重复键: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicate_keys,
                           parse_constant=lambda token: (_ for _ in ()).throw(
                               InventoryError(f"JSON 含非有限数值: {token}")))
    except InventoryError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise InventoryError(f"无法读取环境清单: {exception}") from exception
    if not isinstance(value, dict):
        raise InventoryError("环境清单顶层必须是对象")
    return value


def normalize(value: Any) -> Any:
    """裁剪字符串并稳定排列发生器；其他数组保留其具有语义的原始顺序。"""
    if isinstance(value, dict):
        normalized: dict[str, Any] = {}
        for key, item in value.items():
            normalized_key = key.strip()
            if not normalized_key:
                raise InventoryError("JSON 对象键不得为空")
            if normalized_key in normalized:
                raise InventoryError(f"JSON 规范化后含重复键: {normalized_key}")
            normalized[normalized_key] = normalize(item)
        generators = normalized.get("generators")
        if isinstance(generators, list) and all(isinstance(item, dict) for item in generators):
            generators.sort(key=lambda item: str(item.get("hostId", "")))
        return normalized
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if isinstance(value, str):
        return value.strip()
    return value


def canonical_bytes(value: Any) -> bytes:
    """使用唯一 JSON 表示；末尾换行也属于归档 SHA，便于直接核对输出文件。"""
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8")


def require_object(value: Any, path: str, errors: list[str]) -> dict[str, Any]:
    """要求字段为对象并返回可继续校验的值。"""
    if not isinstance(value, dict):
        errors.append(f"{path} 必须是对象")
        return {}
    return value


def require_fields(value: dict[str, Any], fields: tuple[str, ...], path: str,
                   errors: list[str]) -> None:
    """要求对象包含全部字段；空字符串不能冒充已采集事实。"""
    for field in fields:
        if field not in value or value[field] is None or value[field] == "":
            errors.append(f"{path}.{field} 必填")


def require_text(value: Any, path: str, errors: list[str]) -> None:
    """要求不可为空的文本字段。"""
    if not isinstance(value, str) or not value.strip():
        errors.append(f"{path} 必须是非空字符串")


def valid_utc_timestamp(value: Any) -> bool:
    """只接受可真实解析且以 Z 表示的 UTC RFC3339 时间。"""
    if not isinstance(value, str) or UTC_TIMESTAMP_RE.fullmatch(value) is None:
        return False
    try:
        parsed = dt.datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError:
        return False
    return parsed.tzinfo == dt.timezone.utc


def require_number(value: Any, path: str, errors: list[str], *, minimum: float = 0,
                   maximum: float | None = None) -> None:
    """要求有限数值并应用冻结下限或资格上限。"""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        errors.append(f"{path} 必须是有限数值")
    elif value < minimum or (maximum is not None and value > maximum):
        suffix = f" 且 <= {maximum}" if maximum is not None else ""
        errors.append(f"{path} 必须 >= {minimum}{suffix}")


def validate_digest_map(value: Any, path: str, errors: list[str], *, image: bool = False) -> None:
    """要求镜像或配置映射非空且只保存不可逆 SHA-256。"""
    mapping = require_object(value, path, errors)
    if not mapping:
        errors.append(f"{path} 至少包含一项")
    pattern = IMAGE_DIGEST_RE if image else SHA256_RE
    for name, digest in mapping.items():
        require_text(name, f"{path} 的键", errors)
        if not isinstance(digest, str) or pattern.fullmatch(digest) is None:
            expected = "sha256:<64位小写十六进制>" if image else "64位小写十六进制"
            errors.append(f"{path}.{name} 必须是{expected}")


def validate_disk(value: Any, path: str, errors: list[str]) -> None:
    """校验根盘/数据盘的容量与承诺性能，零值也必须显式给出。"""
    disk = require_object(value, path, errors)
    require_fields(disk, ("type", "capacityBytes", "baselineIops",
                          "baselineThroughputBytesPerSecond"), path, errors)
    require_text(disk.get("type"), f"{path}.type", errors)
    require_number(disk.get("capacityBytes"), f"{path}.capacityBytes", errors, minimum=1)
    require_number(disk.get("baselineIops"), f"{path}.baselineIops", errors)
    require_number(disk.get("baselineThroughputBytesPerSecond"),
                   f"{path}.baselineThroughputBytesPerSecond", errors)


def validate_host(host: Any, path: str, role: str, errors: list[str]) -> None:
    """校验 SUT 与发生器共同的固定云 VM、运行时、时钟和脱敏证据。"""
    item = require_object(host, path, errors)
    require_fields(item, HOST_COMMON_FIELDS, path, errors)
    require_text(item.get("hostId"), f"{path}.hostId", errors)

    cloud = require_object(item.get("cloud"), f"{path}.cloud", errors)
    require_fields(cloud, ("provider", "region", "availabilityZone", "instanceType"),
                   f"{path}.cloud", errors)
    for field in ("provider", "region", "availabilityZone", "instanceType"):
        require_text(cloud.get(field), f"{path}.cloud.{field}", errors)

    hardware = require_object(item.get("hardware"), f"{path}.hardware", errors)
    require_fields(hardware, ("cpuModel", "vcpus", "memoryBytes"), f"{path}.hardware", errors)
    require_text(hardware.get("cpuModel"), f"{path}.hardware.cpuModel", errors)
    require_number(hardware.get("vcpus"), f"{path}.hardware.vcpus", errors, minimum=1)
    require_number(hardware.get("memoryBytes"), f"{path}.hardware.memoryBytes", errors, minimum=1)
    if role == "sut" and (hardware.get("vcpus") != 8
                          or hardware.get("memoryBytes") != 16 * 1024 * 1024 * 1024):
        errors.append(f"{path}.hardware 必须精确匹配冻结的 8 vCPU/16 GiB SUT")

    os_value = require_object(item.get("os"), f"{path}.os", errors)
    require_fields(os_value, ("name", "version", "imageId", "kernel", "architecture"),
                   f"{path}.os", errors)
    for field in ("name", "version", "imageId", "kernel", "architecture"):
        require_text(os_value.get(field), f"{path}.os.{field}", errors)
    if os_value.get("name") != "Ubuntu" or os_value.get("version") != "24.04":
        errors.append(f"{path}.os 必须是冻结的 Ubuntu 24.04")
    if os_value.get("architecture") != "x86_64":
        errors.append(f"{path}.os.architecture 必须为 x86_64")

    disks = require_object(item.get("disks"), f"{path}.disks", errors)
    require_fields(disks, ("root", "data"), f"{path}.disks", errors)
    validate_disk(disks.get("root"), f"{path}.disks.root", errors)
    validate_disk(disks.get("data"), f"{path}.disks.data", errors)

    runtime = require_object(item.get("runtime"), f"{path}.runtime", errors)
    require_fields(runtime, ("dockerClient", "dockerServer", "jdk", "python"),
                   f"{path}.runtime", errors)
    for field in ("dockerClient", "dockerServer", "jdk", "python"):
        require_text(runtime.get(field), f"{path}.runtime.{field}", errors)

    jvm = require_object(item.get("jvm"), f"{path}.jvm", errors)
    require_fields(jvm, ("heapInitialBytes", "heapMaxBytes"), f"{path}.jvm", errors)
    require_number(jvm.get("heapInitialBytes"), f"{path}.jvm.heapInitialBytes", errors, minimum=1)
    require_number(jvm.get("heapMaxBytes"), f"{path}.jvm.heapMaxBytes", errors, minimum=1)
    if (isinstance(jvm.get("heapInitialBytes"), (int, float))
            and isinstance(jvm.get("heapMaxBytes"), (int, float))
            and jvm["heapInitialBytes"] > jvm["heapMaxBytes"]):
        errors.append(f"{path}.jvm.heapInitialBytes 不得大于 heapMaxBytes")

    network = require_object(item.get("network"), f"{path}.network", errors)
    require_fields(network, ("bandwidthMbps", "privateRttPeer", "privateRttMillis"),
                   f"{path}.network", errors)
    require_number(network.get("bandwidthMbps"), f"{path}.network.bandwidthMbps", errors, minimum=1)
    require_text(network.get("privateRttPeer"), f"{path}.network.privateRttPeer", errors)
    require_number(network.get("privateRttMillis"), f"{path}.network.privateRttMillis", errors)

    ntp = require_object(item.get("ntp"), f"{path}.ntp", errors)
    require_fields(ntp, ("service", "synchronized", "offsetMillis", "checkedAt"), f"{path}.ntp", errors)
    require_text(ntp.get("service"), f"{path}.ntp.service", errors)
    if ntp.get("synchronized") is not True:
        errors.append(f"{path}.ntp.synchronized 必须为 true")
    require_number(ntp.get("offsetMillis"), f"{path}.ntp.offsetMillis", errors,
                   minimum=-100, maximum=100)
    if not valid_utc_timestamp(ntp.get("checkedAt")):
        errors.append(f"{path}.ntp.checkedAt 必须是 UTC RFC3339 时间")

    validate_digest_map(item.get("images"), f"{path}.images", errors, image=True)
    if role == "sut" and isinstance(item.get("images"), dict):
        for image_name in ("timescaledb", "redis", "redpanda", "emqx", "minio"):
            if image_name not in item["images"]:
                errors.append(f"{path}.images.{image_name} 必填")
    validate_digest_map(item.get("configSha256"), f"{path}.configSha256", errors)

    if role == "sut":
        validate_sut(item, path, errors)


def validate_sut(sut: dict[str, Any], path: str, errors: list[str]) -> None:
    """校验 SUT 独有的连接池与全部同机中间件固定参数。"""
    require_fields(sut, ("hikari", "postgresql", "timescale", "redpanda", "redis", "emqx", "minio"),
                   path, errors)
    hikari = require_object(sut.get("hikari"), f"{path}.hikari", errors)
    require_fields(hikari, ("control", "data"), f"{path}.hikari", errors)
    for pool_name in ("control", "data"):
        pool = require_object(hikari.get(pool_name), f"{path}.hikari.{pool_name}", errors)
        require_fields(pool, ("maximumPoolSize", "connectionTimeoutMillis"),
                       f"{path}.hikari.{pool_name}", errors)
        require_number(pool.get("maximumPoolSize"), f"{path}.hikari.{pool_name}.maximumPoolSize",
                       errors, minimum=1)
        require_number(pool.get("connectionTimeoutMillis"),
                       f"{path}.hikari.{pool_name}.connectionTimeoutMillis", errors, minimum=1)

    for name in ("postgresql", "timescale"):
        service = require_object(sut.get(name), f"{path}.{name}", errors)
        require_fields(service, ("version", "parameters"), f"{path}.{name}", errors)
        require_text(service.get("version"), f"{path}.{name}.version", errors)
        parameters = require_object(service.get("parameters"), f"{path}.{name}.parameters", errors)
        if not parameters:
            errors.append(f"{path}.{name}.parameters 至少包含一项")

    redpanda = require_object(sut.get("redpanda"), f"{path}.redpanda", errors)
    require_fields(redpanda, ("version", "smp", "memoryBytes"), f"{path}.redpanda", errors)
    require_text(redpanda.get("version"), f"{path}.redpanda.version", errors)
    require_number(redpanda.get("smp"), f"{path}.redpanda.smp", errors, minimum=1)
    require_number(redpanda.get("memoryBytes"), f"{path}.redpanda.memoryBytes", errors, minimum=1)

    for name in ("redis", "emqx", "minio"):
        service = require_object(sut.get(name), f"{path}.{name}", errors)
        require_fields(service, ("version",), f"{path}.{name}", errors)
        require_text(service.get("version"), f"{path}.{name}.version", errors)


def scan_for_secrets(value: Any, path: str, errors: list[str]) -> None:
    """拒绝敏感键和明显的认证材料；只报告路径，不回显可能的秘密值。"""
    if isinstance(value, dict):
        for key, item in value.items():
            compact = re.sub(r"[^a-z]", "", key.lower())
            if SENSITIVE_KEY_RE.search(key.lower()) or any(term in compact for term in {
                    "password", "passwd", "secret", "token", "authorization", "credentials",
                    "credential", "privatekey", "apikey"}):
                errors.append(f"{path}.{key} 是禁止归档的敏感字段")
            scan_for_secrets(item, f"{path}.{key}", errors)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            scan_for_secrets(item, f"{path}[{index}]", errors)
    elif isinstance(value, str):
        lowered = value.lower()
        if ("-----begin private key-----" in lowered or lowered.startswith("bearer ")
                or re.search(r"^[a-z][a-z0-9+.-]*://[^/@\s]+:[^/@\s]+@", value, re.IGNORECASE)
                or re.search(r"\bAKIA[0-9A-Z]{16}\b", value)
                or re.search(r"\bgh[pousr]_[A-Za-z0-9_]{20,}\b", value)
                or re.fullmatch(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", value)):
            errors.append(f"{path} 含禁止归档的认证材料")


def scan_for_placeholders(value: Any, path: str, errors: list[str]) -> None:
    """拒绝模板占位符和全零摘要，确保示例文件永远不能冒充已采集清单。"""
    if isinstance(value, dict):
        for key, item in value.items():
            scan_for_placeholders(item, f"{path}.{key}", errors)
    elif isinstance(value, list):
        for index, item in enumerate(value):
            scan_for_placeholders(item, f"{path}[{index}]", errors)
    elif isinstance(value, str):
        if not value.strip():
            errors.append(f"{path} 不得为空字符串")
        elif PLACEHOLDER_RE.fullmatch(value.strip()) or re.fullmatch(r"(?:sha256:)?0{40,64}", value):
            errors.append(f"{path} 仍是占位值")
    elif isinstance(value, float) and not math.isfinite(value):
        errors.append(f"{path} 不得为 NaN/Inf")
    elif value is None:
        errors.append(f"{path} 不得为 null")


def validate_inventory(value: dict[str, Any]) -> list[str]:
    """应用冻结 schema、同区拓扑、多发生器和资格前时钟硬门禁。"""
    errors: list[str] = []
    scan_for_secrets(value, "$", errors)
    scan_for_placeholders(value, "$", errors)
    require_fields(value, ("schemaVersion", "capturedAt", "repository", "artifacts", "sut", "generators"),
                   "$", errors)
    if type(value.get("schemaVersion")) is not int or value.get("schemaVersion") != 1:
        errors.append("$.schemaVersion 必须为 1")
    if not valid_utc_timestamp(value.get("capturedAt")):
        errors.append("$.capturedAt 必须是 UTC RFC3339 时间")

    repository = require_object(value.get("repository"), "$.repository", errors)
    require_fields(repository, ("commit",), "$.repository", errors)
    if not isinstance(repository.get("commit"), str) or COMMIT_RE.fullmatch(repository["commit"]) is None:
        errors.append("$.repository.commit 必须是 40 位小写 Git SHA")

    artifacts = require_object(value.get("artifacts"), "$.artifacts", errors)
    require_fields(artifacts, ("backendJarSha256", "simulatorJarSha256", "scriptsSha256"),
                   "$.artifacts", errors)
    for name in ("backendJarSha256", "simulatorJarSha256"):
        if not isinstance(artifacts.get(name), str) or SHA256_RE.fullmatch(artifacts[name]) is None:
            errors.append(f"$.artifacts.{name} 必须是 64 位小写十六进制")
    validate_digest_map(artifacts.get("scriptsSha256"), "$.artifacts.scriptsSha256", errors)
    scripts = artifacts.get("scriptsSha256")
    if isinstance(scripts, dict):
        for name in REQUIRED_SCRIPT_NAMES:
            if name not in scripts:
                errors.append(f"$.artifacts.scriptsSha256.{name} 必填")

    validate_host(value.get("sut"), "$.sut", "sut", errors)
    generators = value.get("generators")
    if not isinstance(generators, list):
        errors.append("$.generators 必须是数组")
        generators = []
    if len(generators) < 1:
        errors.append("$.generators 至少包含一台发生器主机")
    for index, generator in enumerate(generators):
        validate_host(generator, f"$.generators[{index}]", "generator", errors)

    hosts = [value.get("sut"), *generators]
    host_ids = [host.get("hostId") for host in hosts if isinstance(host, dict)
                and isinstance(host.get("hostId"), str) and host.get("hostId")]
    if len(host_ids) != len(set(host_ids)):
        errors.append("所有 sut/generator hostId 必须全局唯一")
    sut = value.get("sut") if isinstance(value.get("sut"), dict) else {}
    sut_cloud = sut.get("cloud") if isinstance(sut.get("cloud"), dict) else {}
    sut_id = sut.get("hostId")
    sut_network = sut.get("network") if isinstance(sut.get("network"), dict) else {}
    generator_ids = {item.get("hostId") for item in generators if isinstance(item, dict)}
    if sut_network.get("privateRttPeer") not in generator_ids:
        errors.append("$.sut.network.privateRttPeer 必须指向一台发生器 hostId")
    for index, generator in enumerate(generators):
        if not isinstance(generator, dict):
            continue
        cloud = generator.get("cloud") if isinstance(generator.get("cloud"), dict) else {}
        for field in ("provider", "region", "availabilityZone"):
            if cloud.get(field) != sut_cloud.get(field):
                errors.append(f"$.generators[{index}].cloud.{field} 必须与 SUT 一致")
        network = generator.get("network") if isinstance(generator.get("network"), dict) else {}
        if sut_id and network.get("privateRttPeer") != sut_id:
            errors.append(f"$.generators[{index}].network.privateRttPeer 必须指向 SUT hostId")
    return errors


def fingerprint_document(value: dict[str, Any]) -> dict[str, Any]:
    """排除纯观测时间和瞬时 NTP 偏差，保留会改变容量结论的全部配置。"""
    document = copy.deepcopy(value)
    document.pop("capturedAt", None)
    hosts = [document.get("sut"), *document.get("generators", [])]
    for host in hosts:
        if not isinstance(host, dict) or not isinstance(host.get("ntp"), dict):
            continue
        host["ntp"].pop("checkedAt", None)
        host["ntp"].pop("offsetMillis", None)
    return document


def atomic_write(path: Path, data: bytes) -> None:
    """原子替换证据文件，避免进程中断留下看似完整的半文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(path)


def error_report(errors: list[str]) -> dict[str, Any]:
    """形成不含输入值的 fail-closed 报告。"""
    return {"schemaVersion": 1, "status": "ERROR", "errors": errors,
            "inventorySha256": None, "comparisonFingerprint": None}


def main() -> int:
    """校验成功才发布规范化清单；错误时清除可能误用的旧 PASS 输出。"""
    args = parse_args()
    paths = {args.input.resolve(), args.normalized_output.resolve(), args.report_output.resolve()}
    if len(paths) != 3:
        print("[c3e-inventory] ERROR 输入与两个输出路径必须互不相同", file=sys.stderr)
        return 1
    args.normalized_output.unlink(missing_ok=True)
    args.report_output.unlink(missing_ok=True)
    try:
        inventory = normalize(read_json(args.input.resolve()))
        errors = validate_inventory(inventory)
        if errors:
            raise InventoryError("; ".join(errors))
        normalized = canonical_bytes(inventory)
        report = {
            "schemaVersion": 1,
            "status": "PASS",
            "errors": [],
            "inventorySha256": hashlib.sha256(normalized).hexdigest(),
            "comparisonFingerprint": hashlib.sha256(
                canonical_bytes(fingerprint_document(inventory))).hexdigest(),
            "sutHostId": inventory["sut"]["hostId"],
            "generatorHostIds": [item["hostId"] for item in inventory["generators"]],
        }
        atomic_write(args.normalized_output, normalized)
        atomic_write(args.report_output, canonical_bytes(report))
        print(f"[c3e-inventory] PASS fingerprint={report['comparisonFingerprint']}")
        return 0
    except InventoryError as exception:
        errors = str(exception).split("; ")
        atomic_write(args.report_output, canonical_bytes(error_report(errors)))
        print(f"[c3e-inventory] ERROR checks={len(errors)}", file=sys.stderr)
        return 1
    except Exception as exception:  # noqa: BLE001 - 工具异常也必须生成 ERROR，而不是复用旧 PASS。
        atomic_write(args.report_output, canonical_bytes(error_report(
            [f"工具异常: {type(exception).__name__}"])))
        print(f"[c3e-inventory] ERROR {type(exception).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
