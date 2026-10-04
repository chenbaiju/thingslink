#!/usr/bin/env python3
"""G1-C3e 注册式四态裁决；当前只实现 C3e-0 工具资格。"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import sys
import traceback
from pathlib import Path
from typing import Any


class EvidenceError(RuntimeError):
    """证据合同不完整或不自洽，不能归因发生器或 SUT。"""


EVALUATOR_REGISTRY = {
    ("C3E0", "TOOL_QUALIFICATION"): {
        "key": "C3E0_TOOL_QUALIFICATION", "evaluatorImplemented": True,
        "claimBoundary": "只证明工具、环境、发生器、fixture 与 typed 采样合同，不证明 L2-S 容量",
    },
    **{("L2", profile): {
        "key": profile, "evaluatorImplemented": False,
        "claimBoundary": f"L2-{profile} evaluator 尚未实现",
    } for profile in ("S", "H", "M", "R", "B", "E")},
}


def load_sibling(name: str) -> Any:
    """安全加载相邻纯函数模块，使 CLI 与测试不依赖工作目录或 PYTHONPATH。"""
    path = Path(__file__).resolve().with_name(f"{name}.py")
    specification = importlib.util.spec_from_file_location(f"c3e_{name}", path)
    if specification is None or specification.loader is None:
        raise EvidenceError(f"无法加载相邻模块 {name}")
    module = importlib.util.module_from_spec(specification)
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


def read_object(path: Path) -> dict[str, Any]:
    """严格读取 JSON 对象。"""
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        """重复键会让不同解析器看到不同事实，证据入口必须 fail-closed。"""
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise EvidenceError(f"{path.name} 含重复 JSON 键: {key}")
            result[key] = value
        return result

    def reject_constant(value: str) -> None:
        """在解码阶段拒绝 JSON 标准之外的 NaN 与 Infinity。"""
        raise EvidenceError(f"{path.name} 含非有限数值: {value}")

    try:
        value = json.loads(path.read_text(encoding="utf-8"),
                           object_pairs_hook=reject_duplicates,
                           parse_constant=reject_constant)
    except (OSError, json.JSONDecodeError, EvidenceError) as exception:
        raise EvidenceError(f"无法读取 {path.name}: {exception}") from exception
    if not isinstance(value, dict) or not value:
        raise EvidenceError(f"{path.name} 顶层必须是非空对象")
    reject_non_finite(value, path.name)
    return value


def reject_non_finite(value: Any, field: str) -> None:
    """递归拒绝 Python JSON 解码器默认接受的 NaN/Infinity。"""
    if isinstance(value, bool) or value is None or isinstance(value, str):
        return
    if isinstance(value, (int, float)):
        if not math.isfinite(float(value)):
            raise EvidenceError(f"{field} 含非有限数值")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            reject_non_finite(item, f"{field}[{index}]")
    elif isinstance(value, dict):
        for key, item in value.items():
            reject_non_finite(item, f"{field}.{key}")
    else:
        raise EvidenceError(f"{field} 含不支持类型")


def add_check(checks: list[dict[str, Any]], name: str, passed: bool,
              actual: Any, expected: str) -> None:
    """追加可渲染、可测试的稳定检查结构。"""
    checks.append({"name": name, "passed": bool(passed), "actual": actual, "expected": expected})


def common_identity(document: dict[str, Any], name: str, run_id: str,
                    environment_fingerprint: str) -> None:
    """所有后续证据必须属于同一 run 与环境指纹。"""
    if (document.get("schemaVersion") != 1 or document.get("runId") != run_id
            or document.get("environmentFingerprint") != environment_fingerprint):
        raise EvidenceError(f"{name} schema/runId/environmentFingerprint 不一致")


def resolve_registration(metadata: dict[str, Any]) -> dict[str, Any]:
    """解析冻结的 scope/profile；未注册或未实现路径都不能借用工具资格。"""
    scope, profile = metadata.get("scope"), metadata.get("profile")
    if not isinstance(scope, str) or not scope or not isinstance(profile, str) or not profile:
        raise EvidenceError("run-metadata 缺必填 scope/profile")
    registration = EVALUATOR_REGISTRY.get((scope, profile))
    if registration is None:
        raise EvidenceError(f"未注册的 scope/profile: {scope}/{profile}")
    return {"scope": scope, "profile": profile, **registration}


def classify(errors: list[str], generator_result: str | None,
             checks: list[dict[str, Any]]) -> str:
    """纯函数固定四态优先级，供后续真实 L2 evaluator 复用。"""
    if errors:
        return "ERROR"
    if generator_result == "FAIL":
        return "INVALID_GENERATOR"
    if any(not check.get("passed", False) for check in checks):
        return "VALID_FAIL"
    return "VALID_PASS"


def build_error_matrix() -> dict[str, Any]:
    """用固定输入重放四态优先级，不接受外部自报的错误矩阵。"""
    cases = [
        {"name": "evidence-error-precedes-all", "errors": ["synthetic-evidence-error"],
         "generatorResult": "FAIL", "checks": [{"passed": False}], "expected": "ERROR"},
        {"name": "generator-failure-precedes-sut-checks", "errors": [],
         "generatorResult": "FAIL", "checks": [{"passed": False}],
         "expected": "INVALID_GENERATOR"},
        {"name": "valid-evidence-failed-check", "errors": [], "generatorResult": "PASS",
         "checks": [{"passed": False}], "expected": "VALID_FAIL"},
        {"name": "valid-evidence-passed-checks", "errors": [], "generatorResult": "PASS",
         "checks": [{"passed": True}], "expected": "VALID_PASS"},
    ]
    evaluated = []
    for case in cases:
        actual = classify(case["errors"], case["generatorResult"], case["checks"])
        evaluated.append({**case, "actual": actual, "passed": actual == case["expected"]})
    return {"schemaVersion": 1, "evaluatorKey": "C3E0_TOOL_QUALIFICATION",
            "result": "PASS" if all(case["passed"] for case in evaluated) else "FAIL",
            "cases": evaluated}


def canonical_json_bytes(value: dict[str, Any]) -> bytes:
    """为可执行合同生成唯一 JSON 字节，拒绝同对象异编码掩盖替换。"""
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            + "\n").encode("utf-8")


def validate_error_matrix(evidence_dir: Path) -> dict[str, Any]:
    """要求归档矩阵与本版裁决器现场重放的对象和字节都一致。"""
    path = evidence_dir / "tool-error-matrix.json"
    actual = read_object(path)
    expected = build_error_matrix()
    try:
        actual_bytes = path.read_bytes()
    except OSError as exception:
        raise EvidenceError("工具错误矩阵缺失") from exception
    if actual != expected or actual_bytes != canonical_json_bytes(expected):
        raise EvidenceError("tool-error-matrix.json 与裁决器四态纯函数重放结果不一致")
    return {"result": expected["result"], "caseCount": len(expected["cases"])}


def validate_environment(evidence_dir: Path, fingerprint: str) -> dict[str, Any]:
    """从规范化清单重跑唯一纯函数，拒绝自写 PASS report 或非规范化字节。"""
    environment_module = load_sibling("l2_environment_inventory")
    report_path = evidence_dir / "environment-inventory-report.json"
    inventory_path = evidence_dir / "environment-inventory.json"
    try:
        environment = environment_module.read_json(report_path)
        inventory = environment_module.normalize(environment_module.read_json(inventory_path))
        inventory_errors = environment_module.validate_inventory(inventory)
    except Exception as exception:  # noqa: BLE001 - 环境纯函数的任何拒绝都属于证据 ERROR。
        raise EvidenceError(f"最终环境清单无法重算: {exception}") from exception
    if inventory_errors:
        raise EvidenceError(f"最终环境清单校验失败: {'; '.join(inventory_errors)}")
    canonical_inventory = environment_module.canonical_bytes(inventory)
    recomputed_fingerprint = hashlib.sha256(environment_module.canonical_bytes(
        environment_module.fingerprint_document(inventory))).hexdigest()
    expected_environment_report = {
        "schemaVersion": 1, "status": "PASS", "errors": [],
        "inventorySha256": hashlib.sha256(canonical_inventory).hexdigest(),
        "comparisonFingerprint": recomputed_fingerprint,
        "sutHostId": inventory["sut"]["hostId"],
        "generatorHostIds": [item["hostId"] for item in inventory["generators"]],
    }
    if environment != expected_environment_report:
        raise EvidenceError("最终环境报告与规范化清单纯函数重算结果不一致")
    if recomputed_fingerprint != fingerprint:
        raise EvidenceError("最终环境清单重算指纹与 run metadata 不一致")
    try:
        inventory_bytes = inventory_path.read_bytes()
    except OSError as exception:
        raise EvidenceError("规范化 environment-inventory.json 缺失") from exception
    if inventory_bytes != canonical_inventory:
        raise EvidenceError("environment-inventory.json 不是纯函数规范化字节")
    return inventory


def validate_generator_hosts(inventory: dict[str, Any], distributed_plan: dict[str, Any]) -> None:
    """要求资格计划精确使用环境清单中的发生器，禁止借用另一批主机的证据。"""
    inventory_host_ids = [item["hostId"] for item in inventory["generators"]]
    plan_hosts = distributed_plan.get("hosts")
    plan_host_ids = [item.get("hostId") for item in plan_hosts] \
        if isinstance(plan_hosts, list) and all(isinstance(item, dict) for item in plan_hosts) else []
    if (len(plan_host_ids) != len(inventory_host_ids)
            or set(plan_host_ids) != set(inventory_host_ids)):
        raise EvidenceError("environment inventory 与 distributed plan 的 generator hostId 集不一致")


def validate_fixture(fixture: dict[str, Any], qualification: dict[str, Any]) -> dict[str, Any]:
    """逐租户重算 10×1,000 夹具和 L2_CAPACITY v1 配额绑定。"""
    tenants = fixture.get("tenants")
    if not isinstance(tenants, list) or len(tenants) != 10:
        raise EvidenceError("fixture-tenants.json 必须含十个 tenant")
    indexes: list[int] = []
    tenants_ids: list[str] = []
    projects: list[str] = []
    policy_ids: list[str] = []
    device_ids: list[str] = []
    device_keys: list[str] = []
    allowed_shards = set(qualification.get("globalShardIds", []))
    total_devices = 0
    for tenant in tenants:
        if not isinstance(tenant, dict):
            raise EvidenceError("fixture tenants 含非对象")
        indexes.append(tenant.get("tenantIndex"))
        project_id = tenant.get("projectId")
        tenant_id, policy_id = tenant.get("tenantId"), tenant.get("policyId")
        if any(not isinstance(value, str) or not value for value in (tenant_id, project_id, policy_id)):
            raise EvidenceError("fixture tenantId/projectId/policyId 缺失")
        tenants_ids.append(tenant_id); policy_ids.append(policy_id)
        projects.append(project_id)
        if (tenant.get("policyCode") != "L2_CAPACITY" or tenant.get("policyVersion") != 1
                or tenant.get("deviceCountLimit") != 2000 or tenant.get("assignmentVersion") != 2):
            raise EvidenceError(f"tenantIndex={tenant.get('tenantIndex')} 配额绑定漂移")
        if tenant.get("timeSeriesPointDailyLimit") != 10_000_000:
            raise EvidenceError(f"tenantIndex={tenant.get('tenantIndex')} 时序日限额漂移")
        devices = tenant.get("devices")
        if not isinstance(devices, list) or len(devices) != 1000:
            raise EvidenceError(f"tenantIndex={tenant.get('tenantIndex')} 设备数不是 1000")
        for device in devices:
            if not isinstance(device, dict):
                raise EvidenceError("fixture devices 含非对象")
            device_ids.append(device.get("deviceId"))
            device_keys.append(device.get("deviceKey"))
            global_shard = f"{device.get('hostId')}/{device.get('shardId')}"
            if global_shard not in allowed_shards:
                raise EvidenceError(f"fixture 设备指向未资格分片: {global_shard}")
        total_devices += 1000
    if (indexes != list(range(10)) or len(projects) != len(set(projects))
            or len(tenants_ids) != len(set(tenants_ids)) or len(set(policy_ids)) != 1):
        raise EvidenceError("tenantIndex 须为 0..9，tenant/project 唯一且 policyId 十项相同")
    if (len(device_ids) != len(set(device_ids)) or len(device_keys) != len(set(device_keys))
            or any(not isinstance(value, str) or not value for value in device_ids + device_keys)):
        raise EvidenceError("十租户 deviceId/deviceKey 必须全局非空且唯一")
    if (fixture.get("tenantCount"), fixture.get("projectCount"), fixture.get("deviceCount")) != (10, 10, 10000):
        raise EvidenceError("fixture 顶层规模聚合与明细不一致")
    if fixture.get("qualification", {}).get("outcome") != "PASS":
        raise EvidenceError("fixture qualification 未 PASS")
    shard_counts: dict[str, int] = {}
    for tenant in tenants:
        for device in tenant["devices"]:
            key = f"{device['hostId']}/{device['shardId']}"
            shard_counts[key] = shard_counts.get(key, 0) + 1
    if set(shard_counts) != allowed_shards:
        raise EvidenceError("fixture 使用的全局分片集合与资格集合不精确一致")
    qualified_size = qualification["qualifiedShardSize"]
    if any(count > qualified_size for count in shard_counts.values()):
        raise EvidenceError("fixture 单分片设备数超过 qualifiedShardSize")
    return {"tenants": 10, "projects": 10, "devices": total_devices,
            "policy": "L2_CAPACITY", "policyVersion": 1}


def validate_metrics(evidence_dir: Path, summary: dict[str, Any], run_id: str,
                     environment_fingerprint: str, expected_profile: str) -> dict[str, Any]:
    """从固定 typed raw sources 重算工具资格指标，不信任自报 summary。"""
    common_identity(summary, "metrics-summary.json", run_id, environment_fingerprint)
    if (summary.get("result") != "PASS" or summary.get("profile") != expected_profile
            or summary.get("phase") != "TOOL_QUALIFICATION"):
        raise EvidenceError("统一指标摘要未绑定 C3e-0 工具资格 profile/phase")
    plan_name, plan_sha = summary.get("sourcePlanFile"), summary.get("sourcePlanSha256")
    if not isinstance(plan_name, str) or Path(plan_name).name != plan_name:
        raise EvidenceError("指标 source plan 文件名非法")
    plan = read_object(evidence_dir / plan_name)
    common_identity(plan, plan_name, run_id, environment_fingerprint)
    if plan.get("profile") != expected_profile:
        raise EvidenceError("指标 plan profile 与裁决注册项不一致")
    if hashlib.sha256((evidence_dir / plan_name).read_bytes()).hexdigest() != plan_sha:
        raise EvidenceError("指标 source plan SHA-256 错配")
    filename = summary.get("unifiedMetricsFile")
    if not isinstance(filename, str) or Path(filename).name != filename:
        raise EvidenceError("unifiedMetricsFile 必须是证据目录内文件名")
    path = evidence_dir / filename
    metrics_module = load_sibling("l2_metrics")
    try:
        recomputed_bytes, recomputed_summary = metrics_module.recompute(
            evidence_dir / plan_name, evidence_dir / "metric-sources", filename)
    except Exception as exception:  # noqa: BLE001 - 原始源重算失败属于证据 ERROR
        raise EvidenceError(f"原始指标重算失败: {exception}") from exception
    try:
        actual_bytes = path.read_bytes()
    except OSError as exception:
        raise EvidenceError(f"统一指标文件缺失: {filename}") from exception
    if actual_bytes != recomputed_bytes or summary != recomputed_summary:
        raise EvidenceError("统一指标/summary 与原始 source 重算结果不一致")
    return {"profile": summary["profile"], "sources": summary.get("sourceCount"),
            "samplesPerSource": summary.get("sampleCountPerSource"),
            "sha256": hashlib.sha256(actual_bytes).hexdigest()}


def validate_evidence_manifest(evidence_dir: Path, stage: str, run_id: str,
                               environment_fingerprint: str) -> None:
    """通过可替换适配层调用注册清单纯函数，不在 verdict 复制文件集。"""
    manifest_module = load_sibling("l2_evidence_manifest")
    output_name = manifest_module.OUTPUT_NAMES.get(stage)
    if not isinstance(output_name, str):
        raise EvidenceError(f"未注册的证据清单阶段: {stage}")
    try:
        manifest = manifest_module.read_object(evidence_dir / output_name, stage)
        expected = manifest_module.registered_files(evidence_dir, stage)
        manifest_module.validate_manifest_document(evidence_dir, manifest, stage, expected)
    except Exception as exception:  # noqa: BLE001 - 清单注册器的任何拒绝都必须 ERROR。
        raise EvidenceError(f"C3e-0 证据清单校验失败: {exception}") from exception
    if (manifest.get("runId") != run_id
            or manifest.get("environmentFingerprint") != environment_fingerprint):
        raise EvidenceError("C3e-0 证据清单 run/environment 不一致")


def evaluate(evidence_dir: Path, runner_outcome: str) -> dict[str, Any]:
    """按注册项进入 evaluator；当前 VALID_PASS 只能声明 C3e-0 工具资格。"""
    checks: list[dict[str, Any]] = []
    errors: list[str] = []
    run_id = ""
    fingerprint = ""
    qualification: dict[str, Any] = {}
    metadata: dict[str, Any] = {}
    registration: dict[str, Any] = {
        "scope": None, "profile": None, "key": None, "evaluatorImplemented": False,
        "claimBoundary": "scope/profile 尚未通过注册校验",
    }
    try:
        metadata = read_object(evidence_dir / "run-metadata.json")
        if metadata.get("schemaVersion") != 1:
            raise EvidenceError("run-metadata schemaVersion 必须为 1")
        run_id = metadata.get("runId")
        fingerprint = metadata.get("environmentFingerprint")
        if (not isinstance(run_id, str) or not run_id or not isinstance(fingerprint, str)
                or len(fingerprint) != 64
                or any(character not in "0123456789abcdef" for character in fingerprint)):
            raise EvidenceError("run-metadata 缺 runId/environmentFingerprint")
        registration = resolve_registration(metadata)
        if not registration["evaluatorImplemented"]:
            raise EvidenceError(
                f"scope/profile {registration['scope']}/{registration['profile']} evaluator 尚未实现")
        inventory = validate_environment(evidence_dir, fingerprint)
        distributed_plan = read_object(evidence_dir / "distributed-plan.json")
        common_identity(distributed_plan, "distributed-plan.json", run_id, fingerprint)
        plan_sha = hashlib.sha256((evidence_dir / "distributed-plan.json").read_bytes()).hexdigest()
        inventory_artifacts = inventory.get("artifacts", {})
        expected_artifacts = {"simulatorJarSha256": inventory_artifacts.get("simulatorJarSha256"),
                              "a4RunnerSha256": inventory_artifacts.get("scriptsSha256", {}).get("a4_qualification.py")}
        if distributed_plan.get("artifacts") != expected_artifacts:
            raise EvidenceError("distributed plan artifact SHA 与环境清单不一致")
        validate_generator_hosts(inventory, distributed_plan)
        qualification = read_object(evidence_dir / "distributed-qualification.json")
        common_identity(qualification, "distributed-qualification.json", run_id, fingerprint)
        if qualification.get("result") not in {"PASS", "FAIL"}:
            raise EvidenceError("跨主机资格 result 必须为 PASS/FAIL")
        coordinator = load_sibling("l2_distributed_coordinator")
        try:
            recomputed_qualification = coordinator.aggregate_group(
                evidence_dir / "distributed-plan.json", evidence_dir / "host-envelopes")
        except Exception as exception:  # noqa: BLE001 - 原始 host 资格重算失败属于证据 ERROR
            raise EvidenceError(f"跨主机资格重算失败: {exception}") from exception
        if qualification != recomputed_qualification:
            raise EvidenceError("distributed qualification 与 plan/host envelope 重算结果不一致")
        if (qualification.get("distributedPlanSha256") != plan_sha
                or qualification.get("artifacts") != expected_artifacts):
            raise EvidenceError("跨主机资格 plan/artifact SHA 不一致")
        global_shards = qualification.get("globalShardIds")
        qualified_size = qualification.get("qualifiedShardSize")
        if (not isinstance(global_shards, list) or len(global_shards) < 2
                or len(global_shards) != len(set(global_shards))
                or qualification.get("shardCount") != len(global_shards)
                or qualification.get("targetDeviceCount") != 10000
                or not isinstance(qualified_size, int) or not 1 <= qualified_size <= 1000
                or len(global_shards) != (10000 + qualified_size - 1) // qualified_size):
            raise EvidenceError("跨主机资格规模/全局分片集合不满足万级冻结")
        add_check(checks, "distributedGeneratorQualification", qualification["result"] == "PASS",
                  {"result": qualification["result"], "hosts": qualification.get("hostCount"),
                   "shards": qualification.get("shardCount")}, "整组 PASS")
    except EvidenceError as exception:
        errors.append(str(exception))

    # 发生器资格真实 FAIL 时运行必须在 SUT 施压前停止，因此不要求本轮不存在的 fixture/指标证据。
    if not errors and qualification.get("result") == "PASS":
        try:
            fixture = read_object(evidence_dir / "fixture-tenants.json")
            common_identity(fixture, "fixture-tenants.json", run_id, fingerprint)
            fixture_result = validate_fixture(fixture, qualification)
            add_check(checks, "tenTenantFixture", True, fixture_result,
                      "10 tenant/project、各 1000 台、L2_CAPACITY v1/2000")
            metrics = read_object(evidence_dir / "metrics-summary.json")
            metrics_result = validate_metrics(
                evidence_dir, metrics, run_id, fingerprint, registration["key"])
            add_check(checks, "unifiedMetrics", True, metrics_result,
                      "完整固定 typed 源且纯函数重算一致")
            matrix_result = validate_error_matrix(evidence_dir)
            add_check(checks, "toolErrorMatrix", True, matrix_result,
                      "ERROR/INVALID_GENERATOR/VALID_FAIL/VALID_PASS 四态纯函数重放一致")
            if runner_outcome != "success":
                raise EvidenceError(f"C3e-0 工具资格 Runner 未成功: {runner_outcome}")
            add_check(checks, "runnerStepOutcome", True, runner_outcome, "success")
            validate_evidence_manifest(
                evidence_dir, "C3E0_FULL_INPUT", run_id, fingerprint)
        except EvidenceError as exception:
            errors.append(str(exception))

    if not errors and qualification.get("result") == "FAIL":
        try:
            validate_evidence_manifest(
                evidence_dir, "C3E0_QUALIFICATION_INPUT", run_id, fingerprint)
        except EvidenceError as exception:
            errors.append(str(exception))

    validity = classify(errors, qualification.get("result"), checks)
    return {"schemaVersion": 1, "overall": "PASS" if validity == "VALID_PASS" else "FAIL",
            "validityStatus": validity, "runId": run_id or None,
            "environmentFingerprint": fingerprint or None,
            "scope": registration["scope"], "profile": registration["profile"],
            "evaluatorKey": registration["key"],
            "evaluatorImplemented": registration["evaluatorImplemented"],
            "claimBoundary": registration["claimBoundary"],
            "checks": checks, "errors": errors}


def markdown(report: dict[str, Any]) -> str:
    """生成紧凑中文 machine summary。"""
    lines = ["# G1-C3e-0 工具资格机器裁决", "", f"- 总结论：`{report['overall']}`",
             f"- 有效性：`{report['validityStatus']}`",
             f"- Scope/Profile：`{report.get('scope') or 'N/A'}/{report.get('profile') or 'N/A'}`",
             f"- Evaluator：`{report.get('evaluatorKey') or 'N/A'}`"
             f" (implemented={str(bool(report.get('evaluatorImplemented'))).lower()})",
             f"- 声明边界：{report.get('claimBoundary') or 'N/A'}",
             f"- 环境指纹：`{report.get('environmentFingerprint') or 'N/A'}`", "",
             "## Checks", "", "| Check | 结果 | 实际 |", "| --- | --- | --- |"]
    for check in report["checks"]:
        actual = json.dumps(check["actual"], ensure_ascii=False, separators=(",", ":"))
        lines.append(f"| `{check['name']}` | {'PASS' if check['passed'] else 'FAIL'} | `{actual}` |")
    if report["errors"]:
        lines.extend(["", "## 证据错误", ""] + [f"- {item}" for item in report["errors"]])
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    """读取证据目录与 Runner 结果，始终先产出 JSON/Markdown。"""
    parser = argparse.ArgumentParser(description="G1-C3e L2 机器裁决")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--runner-outcome", choices=("success", "failure", "cancelled", "skipped"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    """内部异常同样降为可归档 ERROR，防止报告文件缺失。"""
    args = parse_args()
    try:
        report = evaluate(args.evidence_dir.resolve(), args.runner_outcome)
    except Exception as exception:  # noqa: BLE001 - 未建模异常也必须写 ERROR
        traceback.print_exc()
        report = {"schemaVersion": 1, "overall": "FAIL", "validityStatus": "ERROR",
                  "runId": None, "environmentFingerprint": None,
                  "scope": None, "profile": None, "evaluatorKey": None,
                  "evaluatorImplemented": False,
                  "claimBoundary": "机器裁决内部错误，不能声明 L2 容量",
                  "checks": [],
                  "errors": [f"机器裁决内部错误: {type(exception).__name__}: {exception}"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.markdown_output.write_text(markdown(report), encoding="utf-8")
    return 0 if report["validityStatus"] == "VALID_PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
