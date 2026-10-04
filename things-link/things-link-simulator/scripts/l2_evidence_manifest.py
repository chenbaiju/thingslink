#!/usr/bin/env python3
"""构建 G1-C3e-0 注册式输入/归档 SHA-256 清单；旧阶段仅保留迁移兼容。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Any


SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
NEW_STAGES = ("C3E0_QUALIFICATION_INPUT", "C3E0_FULL_INPUT", "C3E0_ARCHIVE")
OUTPUT_NAMES = {
    "C3E0_QUALIFICATION_INPUT": "c3e0-qualification-input-sha256.json",
    "C3E0_FULL_INPUT": "c3e0-full-input-sha256.json",
    "C3E0_ARCHIVE": "c3e0-archive-sha256.json",
}
QUALIFICATION_STATIC_FILES = (
    "environment-inventory.input.json", "environment-inventory.json",
    "environment-inventory-report.json", "run-metadata.json", "distributed-plan.json",
    "external-runbook.json", "coordination/controller.start.json",
    "coordination/coordination-result.json", "distributed-qualification.json",
)
FULL_STATIC_FILES = QUALIFICATION_STATIC_FILES + (
    "c3e0-qualification-input-sha256.json",
    "fixture-quota.json", "fixture-assignment-plan.json", "fixture-tenants.json",
    "metrics-source-plan.json", "metrics-summary.json", "unified-metrics.jsonl",
    "tool-error-matrix.json",
)
ARCHIVE_STATIC_FILES = ("machine-report.json", "machine-report.md")

# 旧机器裁决仍硬编码这两个阶段；只为迁移期调用方保留，正式 C3e-0 入口不生成它们。
QUALIFICATION_FILES = (
    "run-metadata.json", "environment-inventory.json", "environment-inventory-report.json",
    "distributed-plan.json", "distributed-qualification.json",
)
FULL_FILES = QUALIFICATION_FILES + (
    "fixture-tenants.json", "metrics-source-plan.json", "metrics-summary.json",
    "unified-metrics.jsonl", "sut-verdict-input.json",
)


def read_object(path: Path, description: str) -> dict[str, Any]:
    """严格读取 JSON 对象；重复键和非对象都不能参与清单推导。"""
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"{description} 含重复键: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=reject_duplicates,
                           parse_constant=lambda token: (_ for _ in ()).throw(
                               ValueError(f"{description} 含非有限数值: {token}")))
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise ValueError(f"{description} 不可读: {path}") from exception
    if not isinstance(value, dict):
        raise ValueError(f"{description} 顶层必须是对象")
    return value


def safe_component(value: object, description: str) -> str:
    """动态身份只能生成单层固定后缀路径，不能把 plan 变成任意文件注册器。"""
    if not isinstance(value, str) or IDENTIFIER_RE.fullmatch(value) is None:
        raise ValueError(f"{description} 必须是稳定身份")
    return value


def safe_relative(value: object, description: str) -> str:
    """只接受规范 POSIX 相对文件路径，并拒绝目录、反斜杠、穿越和绝对路径。"""
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError(f"{description} 必须是 POSIX 相对文件路径")
    path = Path(value)
    if path.is_absolute() or ".." in path.parts or path.as_posix() != value or value.endswith("/"):
        raise ValueError(f"{description} 非法或包含路径穿越")
    return value


def resolve_registered(evidence_dir: Path, name: str) -> Path:
    """解析注册文件时同时封闭符号链接越界。"""
    root = evidence_dir.resolve()
    path = (root / safe_relative(name, "证据文件")).resolve()
    if path == root or root not in path.parents:
        raise ValueError(f"证据文件解析后越出 evidence dir: {name}")
    if not path.is_file():
        raise ValueError(f"注册证据缺失: {name}")
    return path


def qualification_dynamic_files(evidence_dir: Path) -> tuple[str, ...]:
    """按冻结 host 集生成固定 envelope/signal 路径，再核对 envelope 的 A4 文件合同。"""
    plan = read_object(evidence_dir / "distributed-plan.json", "distributed plan")
    hosts = plan.get("hosts")
    if not isinstance(hosts, list) or not hosts:
        raise ValueError("distributed-plan hosts 缺失")
    names: list[str] = []
    seen_hosts: set[str] = set()
    run_id = plan.get("runId")
    fingerprint = plan.get("environmentFingerprint")
    completed_outcomes: dict[str, str] = {}
    for raw_host in hosts:
        if not isinstance(raw_host, dict):
            raise ValueError("distributed-plan host 必须是对象")
        host_id = safe_component(raw_host.get("hostId"), "hostId")
        if host_id in seen_hosts:
            raise ValueError("distributed-plan hostId 重复")
        seen_hosts.add(host_id)
        envelope_name = f"host-envelopes/{host_id}.host-envelope.json"
        names.extend((envelope_name, f"coordination/{host_id}.ready.json",
                      f"coordination/{host_id}.complete.json"))
        envelope = read_object(resolve_registered(evidence_dir, envelope_name), f"host {host_id} envelope")
        if envelope.get("hostId") != host_id:
            raise ValueError(f"host {host_id} envelope 身份漂移")
        ready = read_object(resolve_registered(evidence_dir, f"coordination/{host_id}.ready.json"),
                            f"host {host_id} READY")
        complete = read_object(resolve_registered(evidence_dir, f"coordination/{host_id}.complete.json"),
                               f"host {host_id} COMPLETE")
        common_identity = {"schemaVersion": 1, "runId": run_id,
                           "environmentFingerprint": fingerprint, "hostId": host_id}
        if any(ready.get(key) != value for key, value in common_identity.items()) \
                or ready.get("phase") != "READY":
            raise ValueError(f"host {host_id} READY 身份漂移")
        if (any(complete.get(key) != value for key, value in common_identity.items())
                or complete.get("phase") != "COMPLETE"
                or complete.get("outcome") not in {"PASS", "FAIL"}
                or complete.get("outcome") != envelope.get("result")):
            raise ValueError(f"host {host_id} COMPLETE 身份/结果漂移")
        if complete.get("hostEnvelopeSha256") != sha256_file(
                resolve_registered(evidence_dir, envelope_name)):
            raise ValueError(f"host {host_id} COMPLETE envelope SHA-256 错配")
        completed_outcomes[host_id] = complete["outcome"]
        evidence_files = envelope.get("evidenceFiles")
        shards = envelope.get("shards")
        report = envelope.get("a4Report")
        if not isinstance(evidence_files, dict) or not isinstance(shards, list) \
                or not isinstance(report, dict):
            raise ValueError(f"host {host_id} envelope 缺 A4 原始证据定位合同")
        report_name = safe_relative(evidence_files.get("reportFile"), "reportFile")
        markdown_name = safe_relative(evidence_files.get("reportMarkdownFile"), "reportMarkdownFile")
        expected_report = safe_relative(raw_host.get("reportPath"), "host reportPath")
        expected_markdown = str(Path(expected_report).with_suffix(".md")).replace("\\", "/")
        if report_name != expected_report or markdown_name != expected_markdown:
            raise ValueError(f"host {host_id} report 路径不能由 distributed plan 推导")
        report_path = resolve_registered(evidence_dir, report_name)
        markdown_path = resolve_registered(evidence_dir, markdown_name)
        if sha256_file(report_path) != envelope.get("a4ReportSha256"):
            raise ValueError(f"host {host_id} A4 report SHA-256 错配")
        if sha256_file(markdown_path) != evidence_files.get("reportMarkdownSha256"):
            raise ValueError(f"host {host_id} A4 markdown SHA-256 错配")
        if read_object(report_path, f"host {host_id} A4 report") != report:
            raise ValueError(f"host {host_id} 嵌入 A4 report 与原件不一致")
        planned_shards = raw_host.get("shardIds")
        actual_shards = [item.get("shardId") for item in shards if isinstance(item, dict)]
        report_shards = report.get("shards")
        report_shard_ids = [item.get("shardId") for item in report_shards
                            if isinstance(item, dict)] if isinstance(report_shards, list) else []
        if actual_shards != planned_shards or report_shard_ids != planned_shards:
            raise ValueError(f"host {host_id} shard 路径集合不能由 plan/report 精确推导")
        names.extend((report_name, markdown_name))
        manifest_root = safe_relative(raw_host.get("manifestRoot"), "host manifestRoot").rstrip("/")
        for shard in shards:
            shard_id = safe_component(shard.get("shardId"), "shardId")
            log_name = safe_relative(shard.get("resourceLogFile"), "resourceLogFile")
            expected_log = f"{Path(expected_report).parent.as_posix()}/logs/{shard_id}.log"
            if log_name != expected_log:
                raise ValueError(f"host {host_id}/{shard_id} resource log 路径不可推导")
            if sha256_file(resolve_registered(evidence_dir, log_name)) != shard.get("resourceLogSha256"):
                raise ValueError(f"host {host_id}/{shard_id} resource log SHA-256 错配")
            names.append(log_name)
            manifest_name = shard.get("manifestFile")
            if manifest_name is None:
                if envelope.get("result") == "PASS" or shard.get("manifestLines") != 0:
                    raise ValueError(f"host {host_id}/{shard_id} manifest 缺失不符合资格结果")
                continue
            manifest_name = safe_relative(manifest_name, "manifestFile")
            allowed = {f"{manifest_root}/{plan.get('runId')}/{shard_id}/property_report.log",
                       f"{manifest_root}/{shard_id}/property_report.log"}
            if manifest_name not in allowed:
                raise ValueError(f"host {host_id}/{shard_id} manifest 路径不可推导")
            if sha256_file(resolve_registered(evidence_dir, manifest_name)) != shard.get("manifestSha256"):
                raise ValueError(f"host {host_id}/{shard_id} manifest SHA-256 错配")
            names.append(manifest_name)
    if len(names) != len(set(names)):
        raise ValueError("注册资格证据路径重复")
    start = read_object(resolve_registered(evidence_dir, "coordination/controller.start.json"),
                        "controller START")
    expected_hosts = sorted(seen_hosts)
    if (start.get("schemaVersion") != 1 or start.get("runId") != run_id
            or start.get("environmentFingerprint") != fingerprint or start.get("phase") != "START"
            or start.get("coordinationVersion") != 1 or start.get("readyHosts") != expected_hosts):
        raise ValueError("controller START 身份/READY 集漂移")
    result = read_object(resolve_registered(evidence_dir, "coordination/coordination-result.json"),
                         "coordination result")
    expected_pairs = [[host_id, completed_outcomes[host_id]] for host_id in sorted(completed_outcomes)]
    expected_outcome = "PASS" if all(value == "PASS" for value in completed_outcomes.values()) else "FAIL"
    if (result.get("schemaVersion") != 1 or result.get("runId") != run_id
            or result.get("environmentFingerprint") != fingerprint or result.get("phase") != "COMPLETE"
            or result.get("outcome") != expected_outcome or result.get("hosts") != expected_pairs):
        raise ValueError("coordination result 与不可变 COMPLETE 集不一致")
    return tuple(names)


def metric_source_files(evidence_dir: Path) -> tuple[str, ...]:
    """FULL 只接受固定 metric-sources 目录内由 typed plan 精确列出的单文件名。"""
    plan = read_object(evidence_dir / "metrics-source-plan.json", "metrics source plan")
    sources = plan.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("metrics-source-plan sources 缺失")
    names: list[str] = []
    for source in sources:
        if not isinstance(source, dict):
            raise ValueError("metrics source 必须是对象")
        output = safe_component(source.get("outputFile"), "metric outputFile")
        names.append(f"metric-sources/{output}")
    if len(names) != len(set(names)):
        raise ValueError("metrics source outputFile 重复")
    return tuple(names)


def registered_files(evidence_dir: Path, stage: str) -> tuple[str, ...]:
    """返回新合同的唯一注册集合；ARCHIVE 对输入清单做传递式且即时的哈希复核。"""
    if stage == "C3E0_QUALIFICATION_INPUT":
        names = QUALIFICATION_STATIC_FILES + qualification_dynamic_files(evidence_dir)
    elif stage == "C3E0_FULL_INPUT":
        qualification_names = QUALIFICATION_STATIC_FILES + qualification_dynamic_files(evidence_dir)
        qualification_manifest = read_object(
            evidence_dir / OUTPUT_NAMES["C3E0_QUALIFICATION_INPUT"], "qualification input manifest")
        validate_manifest_document(evidence_dir, qualification_manifest,
                                   "C3E0_QUALIFICATION_INPUT", qualification_names)
        names = FULL_STATIC_FILES + qualification_dynamic_files(evidence_dir) \
            + metric_source_files(evidence_dir)
    elif stage == "C3E0_ARCHIVE":
        full_name = OUTPUT_NAMES["C3E0_FULL_INPUT"]
        qualification_name = OUTPUT_NAMES["C3E0_QUALIFICATION_INPUT"]
        selected_name = full_name if (evidence_dir / full_name).is_file() else qualification_name
        if not (evidence_dir / selected_name).is_file():
            raise ValueError("ARCHIVE 缺对应的 C3e-0 input manifest")
        input_manifest = read_object(evidence_dir / selected_name, "C3e-0 input manifest")
        input_stage = input_manifest.get("stage")
        if input_stage not in NEW_STAGES[:2] or OUTPUT_NAMES[input_stage] != selected_name:
            raise ValueError("ARCHIVE input manifest stage/文件名不一致")
        expected_input = registered_files(evidence_dir, input_stage)
        validate_manifest_document(evidence_dir, input_manifest, input_stage, expected_input)
        names = expected_input + (selected_name,) + ARCHIVE_STATIC_FILES
        if (evidence_dir / "cleanup-receipt.json").is_file():
            names += ("cleanup-receipt.json",)
        if (evidence_dir / "secret-cleanup-receipt.json").is_file():
            receipt = read_object(resolve_registered(evidence_dir, "secret-cleanup-receipt.json"),
                                  "secret cleanup receipt")
            metadata = read_object(evidence_dir / "run-metadata.json", "run metadata")
            if (receipt.get("schemaVersion") != 2 or receipt.get("cleanupKind") != "SECRET"
                    or receipt.get("operation") != "PURGE_SECRETS"
                    or receipt.get("runId") != metadata.get("runId")
                    or receipt.get("environmentFingerprint") != metadata.get("environmentFingerprint")
                    or receipt.get("result") not in {"PASS", "FAIL"}
                    or receipt.get("secretDirectoryPurged") is not (receipt.get("result") == "PASS")
                    or receipt.get("cloudCleanupPerformed") is not False
                    or receipt.get("fixtureCleanupPerformed") is not False):
                raise ValueError("secret cleanup receipt 身份、类别或结果错配")
            names += ("secret-cleanup-receipt.json",)
    else:
        raise ValueError(f"stage 必须为 {'/'.join(NEW_STAGES)}")
    if len(names) != len(set(names)):
        raise ValueError("注册证据集合重复")
    return names


def sha256_file(path: Path) -> str:
    """流式计算大证据文件摘要。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_manifest_document(evidence_dir: Path, manifest: dict[str, Any], stage: str,
                               expected: tuple[str, ...]) -> None:
    """重算已有清单，ARCHIVE 不能只相信输入清单自报的摘要。"""
    metadata = read_object(evidence_dir / "run-metadata.json", "run metadata")
    files = manifest.get("files")
    if (manifest.get("schemaVersion") != 2 or manifest.get("stage") != stage
            or manifest.get("runId") != metadata.get("runId")
            or manifest.get("environmentFingerprint") != metadata.get("environmentFingerprint")
            or not isinstance(files, dict) or set(files) != set(expected)):
        raise ValueError("C3e-0 input manifest 身份或注册文件集合不一致")
    for name in expected:
        if not SHA256_RE.fullmatch(str(files.get(name, ""))) \
                or sha256_file(resolve_registered(evidence_dir, name)) != files[name]:
            raise ValueError(f"C3e-0 input manifest SHA-256 错配: {name}")


def build_registered(evidence_dir: Path, stage: str, output: Path) -> dict[str, Any]:
    """按注册表构建新清单；任一缺件或路径漂移时不发布部分结果。"""
    evidence_dir = evidence_dir.resolve()
    expected_output = (evidence_dir / OUTPUT_NAMES.get(stage, "INVALID")).resolve()
    if output.resolve() != expected_output:
        raise ValueError(f"output 必须精确为 evidence-dir/{OUTPUT_NAMES.get(stage, '对应注册文件')}")
    names = registered_files(evidence_dir, stage)
    metadata = read_object(evidence_dir / "run-metadata.json", "run metadata")
    run_id = metadata.get("runId")
    fingerprint = metadata.get("environmentFingerprint")
    if not isinstance(run_id, str) or not run_id or not SHA256_RE.fullmatch(str(fingerprint or "")):
        raise ValueError("run metadata 缺稳定 runId/environmentFingerprint")
    result = {"schemaVersion": 2, "stage": stage, "runId": run_id,
              "environmentFingerprint": fingerprint,
              "files": {name: sha256_file(resolve_registered(evidence_dir, name)) for name in names}}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")) + "\n", encoding="utf-8")
    os.replace(temporary, output)
    return result


def safe_name(value: object, description: str) -> str:
    """旧 manifest 动态字段只能是单文件名。"""
    if not isinstance(value, str) or not value or Path(value).name != value:
        raise ValueError(f"{description} 必须是单个文件名")
    return value


def expected_files(evidence_dir: Path, stage: str) -> tuple[str, ...]:
    """迁移兼容：保留旧机器裁决依赖的 QUALIFICATION/FULL 集合。"""
    if stage not in {"QUALIFICATION", "FULL"}:
        raise ValueError("旧 stage 必须为 QUALIFICATION/FULL")
    distributed = read_object(evidence_dir / "distributed-plan.json", "distributed plan")
    hosts = distributed.get("hosts")
    if not isinstance(hosts, list) or not hosts:
        raise ValueError("distributed-plan hosts 缺失")
    host_files = tuple(f"host-envelopes/{safe_name(host.get('hostId'), 'hostId')}.host-envelope.json"
                       for host in hosts if isinstance(host, dict))
    if len(host_files) != len(hosts) or len(host_files) != len(set(host_files)):
        raise ValueError("distributed-plan hostId 重复或非法")
    if stage == "QUALIFICATION":
        return QUALIFICATION_FILES + host_files
    metrics = read_object(evidence_dir / "metrics-source-plan.json", "metrics source plan")
    sources = metrics.get("sources")
    if not isinstance(sources, list) or not sources:
        raise ValueError("metrics-source-plan sources 缺失")
    source_files = tuple(f"metric-sources/{safe_name(source.get('outputFile'), 'outputFile')}"
                         for source in sources if isinstance(source, dict))
    if len(source_files) != len(sources) or len(source_files) != len(set(source_files)):
        raise ValueError("metrics source outputFile 重复或非法")
    return FULL_FILES + host_files + source_files


def build(evidence_dir: Path, stage: str, output: Path) -> dict[str, Any]:
    """迁移兼容：旧 verdict 测试仍可生成 evidence-sha256.json。"""
    if stage in NEW_STAGES:
        return build_registered(evidence_dir, stage, output)
    if output.resolve() != (evidence_dir / "evidence-sha256.json").resolve():
        raise ValueError("旧 output 必须精确为 evidence-dir/evidence-sha256.json")
    names = expected_files(evidence_dir, stage)
    metadata = read_object(evidence_dir / "run-metadata.json", "run metadata")
    result = {"schemaVersion": 1, "stage": stage, "runId": metadata["runId"],
              "environmentFingerprint": metadata["environmentFingerprint"],
              "files": {name: sha256_file(evidence_dir / name) for name in names}}
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")) + "\n", encoding="utf-8")
    os.replace(temporary, output)
    return result


def main() -> int:
    """CLI 新旧阶段共存；新入口只使用 C3E0_* 注册阶段。"""
    parser = argparse.ArgumentParser(description="G1-C3e 注册式证据 SHA-256 清单")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--stage", choices=("QUALIFICATION", "FULL", *NEW_STAGES), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.evidence_dir, args.stage, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
