#!/usr/bin/env python3
"""G1-C4a-1 durable handoff 七场景机器裁决。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any

SCENARIOS = (
    "normal", "application-stop", "kafka-stop", "database-stop",
    "ack-ambiguity", "emqx-restart", "poison",
)
RESULTS = {"accepted", "duplicate", "permanent_reject", "transient_retry", "quarantined"}
DISPATCH_TYPES = {"raw", "command-reply", "poison"}
REQUIRED_ARTIFACTS = {
    "emqxBaseHocon", "dockerCompose", "bootstrapJar", "verdictScript",
    "qualificationScript", "matrixRunner", "cleanupRunner",
}
HEX_64 = re.compile(r"^[0-9a-f]{64}$")
HEX_40 = re.compile(r"^[0-9a-f]{40}$")


class EvidenceError(ValueError):
    """输入缺失、类型漂移或互相矛盾，不能当作 SUT 失败。"""


def strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    """拒绝重复 JSON 键，防止不同解析器对同一证据得出不同值。"""
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise EvidenceError(f"重复 JSON 键: {key}")
        value[key] = item
    return value


def reject_constant(value: str) -> None:
    """NaN/Infinity 不是 JSON 数值，指标出现时必须 fail-closed。"""
    raise EvidenceError(f"非法非有限数: {value}")


def loads_strict(text: str, label: str) -> Any:
    """读取严格 JSON 并把解析错误归一为证据错误。"""
    try:
        return json.loads(text, object_pairs_hook=strict_object, parse_constant=reject_constant)
    except EvidenceError:
        raise
    except Exception as exception:
        raise EvidenceError(f"{label} 不是合法 JSON: {exception}") from exception


def read_object(path: Path, label: str) -> dict[str, Any]:
    """读取必须存在的 JSON 对象。"""
    if not path.is_file():
        raise EvidenceError(f"缺少 {label}: {path.name}")
    value = loads_strict(path.read_text(encoding="utf-8"), label)
    if not isinstance(value, dict):
        raise EvidenceError(f"{label} 必须是对象")
    return value


def exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    """锁定 schema，新增或缺失字段均要求显式升级版本。"""
    if set(value) != expected:
        raise EvidenceError(f"{label} 字段不匹配: expected={sorted(expected)} actual={sorted(value)}")


def finite_number(value: Any, label: str) -> float:
    """布尔值不能借 Python 的 int 子类关系冒充指标。"""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
        raise EvidenceError(f"{label} 必须是有限数")
    return float(value)


def string_list(value: Any, label: str, allow_empty: bool = False) -> list[str]:
    """读取无重复、非空的业务 messageId 集合。"""
    if not isinstance(value, list) or (not allow_empty and not value):
        raise EvidenceError(f"{label} 必须是{'可空' if allow_empty else '非空'}数组")
    if any(not isinstance(item, str) or not item or len(item) > 128 for item in value):
        raise EvidenceError(f"{label} 含非法 messageId")
    if len(set(value)) != len(value):
        raise EvidenceError(f"{label} 含重复 messageId")
    return value


def add_check(checks: list[dict[str, Any]], name: str, passed: bool, actual: Any, expected: Any) -> None:
    """生成稳定低基数检查项。"""
    checks.append({"name": name, "passed": bool(passed), "actual": actual, "expected": expected})


def validate_top(document: dict[str, Any]) -> tuple[str, dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    """校验运行身份、工具资格、配置不变量与源文件指纹。"""
    exact_keys(document, {"schemaVersion", "run", "qualification", "configuration", "metrics",
                          "artifacts", "scenarios"}, "qualification-input")
    if document["schemaVersion"] != 1:
        raise EvidenceError("schemaVersion 必须精确为 1")

    run = document["run"]
    if not isinstance(run, dict):
        raise EvidenceError("run 必须是对象")
    exact_keys(run, {"runId", "gitCommit", "startedAt", "finishedAt"}, "run")
    if not isinstance(run["runId"], str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", run["runId"]):
        raise EvidenceError("runId 非法")
    if not isinstance(run["gitCommit"], str) or not HEX_40.fullmatch(run["gitCommit"]):
        raise EvidenceError("gitCommit 必须是 40 位小写 SHA")
    for field in ("startedAt", "finishedAt"):
        try:
            datetime.fromisoformat(str(run[field]).replace("Z", "+00:00"))
        except ValueError as exception:
            raise EvidenceError(f"{field} 必须是 ISO-8601") from exception
    started = datetime.fromisoformat(str(run["startedAt"]).replace("Z", "+00:00"))
    finished = datetime.fromisoformat(str(run["finishedAt"]).replace("Z", "+00:00"))
    if finished <= started:
        raise EvidenceError("finishedAt 必须晚于 startedAt")

    qualification = document["qualification"]
    if not isinstance(qualification, dict):
        raise EvidenceError("qualification 必须是对象")
    exact_keys(qualification, {"result", "isolatedStack", "evidenceComplete", "clockSkewMs"}, "qualification")
    if qualification["result"] not in {"PASS", "FAIL"}:
        raise EvidenceError("qualification.result 只允许 PASS/FAIL")
    finite_number(qualification["clockSkewMs"], "qualification.clockSkewMs")

    artifacts = document["artifacts"]
    if not isinstance(artifacts, dict) or set(artifacts) != REQUIRED_ARTIFACTS:
        raise EvidenceError("artifacts 必须精确覆盖七个冻结指纹")
    if any(not isinstance(value, str) or not HEX_64.fullmatch(value) for value in artifacts.values()):
        raise EvidenceError("artifact SHA-256 必须是 64 位小写十六进制")

    configuration = document["configuration"]
    metrics = document["metrics"]
    if not isinstance(configuration, dict) or not isinstance(metrics, dict):
        raise EvidenceError("configuration/metrics 必须是对象")
    exact_keys(configuration, {"emqxVersion", "durableSessions", "messageRetentionSeconds",
                               "sessionExpirySeconds", "clientId", "internalTopic",
                               "httpMessageActions", "ingressOwners", "clusterNodes",
                               "legacyBridgesAbsent", "lifecycleActionsConnected", "license"},
               "configuration")
    license_status = configuration["license"]
    if not isinstance(license_status, dict):
        raise EvidenceError("configuration.license 必须是对象")
    exact_keys(license_status, {"type", "deployment", "expired", "purpose", "productionEligible"},
               "configuration.license")
    exact_keys(metrics, {"emqxRuleFailed", "emqxRuleDropped", "durableStoreFailures",
                         "diskFreeBytes", "diskFreePercent"}, "metrics")

    checks: list[dict[str, Any]] = []
    add_check(checks, "isolatedQualification", qualification["isolatedStack"] is True
              and qualification["evidenceComplete"] is True
              and abs(finite_number(qualification["clockSkewMs"], "clockSkewMs")) <= 1000,
              qualification, "isolated=true, evidenceComplete=true, |clockSkewMs|<=1000")
    expected_config = {"emqxVersion": "6.2.3", "durableSessions": True,
                       "messageRetentionSeconds": 86400, "sessionExpirySeconds": 172800,
                       "clientId": "thingslink-uplink-ingress-v1",
                       "internalTopic": "tc/internal/v1/ingress/uplink",
                       "httpMessageActions": 0, "ingressOwners": 1, "clusterNodes": 1,
                       "legacyBridgesAbsent": True, "lifecycleActionsConnected": 2,
                       "license": {"type": "community", "deployment": "Development",
                                   "expired": False, "purpose": "INTERNAL_DEVELOPMENT",
                                   "productionEligible": False}}
    add_check(checks, "frozenConfiguration", configuration == expected_config, configuration, expected_config)
    metric_values = {key: finite_number(metrics[key], f"metrics.{key}") for key in metrics}
    metrics_pass = (metric_values["emqxRuleFailed"] == 0 and metric_values["emqxRuleDropped"] == 0
                    and metric_values["durableStoreFailures"] == 0
                    and metric_values["diskFreeBytes"] >= 5 * 1024 ** 3
                    and metric_values["diskFreePercent"] >= 20)
    add_check(checks, "brokerDurabilityMetrics", metrics_pass, metrics,
              "failed/dropped/storeFailures=0, diskFree>=5GiB and >=20%")
    return qualification["result"], configuration, artifacts, checks


def validate_scenarios(value: Any, checks: list[dict[str, Any]]) -> None:
    """按七场景冻结集合与各自恢复事实执行精确对账。"""
    if not isinstance(value, list) or len(value) != len(SCENARIOS):
        raise EvidenceError("scenarios 必须精确包含七项")
    by_name: dict[str, dict[str, Any]] = {}
    fields = {"name", "expectedMessageIds", "terminalMessageIds", "pubAckMessageIds",
              "unackedDuringFault", "recoveredMessageIds", "duplicatedMessageIds",
              "finalFactCounts", "sessionPresentAfterRecovery", "poison"}
    for scenario in value:
        if not isinstance(scenario, dict):
            raise EvidenceError("scenario 必须是对象")
        exact_keys(scenario, fields, "scenario")
        name = scenario["name"]
        if name not in SCENARIOS or name in by_name:
            raise EvidenceError(f"场景名非法或重复: {name}")
        by_name[name] = scenario
    if set(by_name) != set(SCENARIOS):
        raise EvidenceError("七场景集合不完整")

    for name in SCENARIOS:
        scenario = by_name[name]
        expected = string_list(scenario["expectedMessageIds"], f"{name}.expected")
        terminal = string_list(scenario["terminalMessageIds"], f"{name}.terminal")
        pubacks = string_list(scenario["pubAckMessageIds"], f"{name}.pubacks")
        unacked = string_list(scenario["unackedDuringFault"], f"{name}.unacked", True)
        recovered = string_list(scenario["recoveredMessageIds"], f"{name}.recovered", True)
        duplicated = string_list(scenario["duplicatedMessageIds"], f"{name}.duplicated", True)
        counts = scenario["finalFactCounts"]
        if not isinstance(counts, dict) or set(counts) != set(expected):
            raise EvidenceError(f"{name}.finalFactCounts 必须精确覆盖 expected")
        common = (set(terminal) == set(expected) and set(pubacks) == set(expected)
                  and all(isinstance(counts[item], int) and not isinstance(counts[item], bool)
                          and counts[item] == 1 for item in expected))
        scenario_pass = common
        if name == "normal":
            scenario_pass &= not unacked and not recovered and not duplicated
        elif name in {"application-stop", "kafka-stop", "database-stop"}:
            scenario_pass &= set(unacked) == set(expected) and set(recovered) == set(expected)
        elif name == "ack-ambiguity":
            scenario_pass &= bool(duplicated) and set(duplicated).issubset(expected) \
                and set(duplicated).issubset(recovered)
        elif name == "emqx-restart":
            scenario_pass &= scenario["sessionPresentAfterRecovery"] is True \
                and set(unacked) == set(expected) and set(recovered) == set(expected)
        elif name == "poison":
            poison = scenario["poison"]
            if not isinstance(poison, dict):
                raise EvidenceError("poison 场景必须提供 poison 对象")
            exact_keys(poison, {"payloadSha256", "dlqPersisted", "mqttAckAfterDlq",
                                "subsequentLegalPassed"}, "poison")
            scenario_pass &= (isinstance(poison["payloadSha256"], str)
                              and bool(HEX_64.fullmatch(poison["payloadSha256"]))
                              and poison["dlqPersisted"] is True
                              and poison["mqttAckAfterDlq"] is True
                              and poison["subsequentLegalPassed"] is True)
        if name != "poison" and scenario["poison"] is not None:
            raise EvidenceError(f"{name}.poison 必须为 null")
        if name not in {"emqx-restart"} and scenario["sessionPresentAfterRecovery"] is not None:
            raise EvidenceError(f"{name}.sessionPresentAfterRecovery 必须为 null")
        add_check(checks, f"scenario.{name}", scenario_pass,
                  {"expected": len(expected), "terminal": len(terminal), "recovered": len(recovered),
                   "duplicated": len(duplicated)}, "集合精确对账且最终事实各 1 次")


def validate_jsonl(path: Path, checks: list[dict[str, Any]], scenarios: list[dict[str, Any]]) -> str:
    """验证逐 handoff 证据连续、脱敏且重试内容不漂移，返回文件 SHA-256。"""
    if not path.is_file():
        raise EvidenceError("缺少 handoff-evidence.jsonl")
    raw = path.read_bytes()
    if not raw or not raw.endswith(b"\n"):
        raise EvidenceError("handoff-evidence.jsonl 必须非空并以换行结束")
    lines = raw.decode("utf-8").splitlines()
    attempts: dict[str, list[int]] = defaultdict(list)
    scenario_attempts: dict[tuple[str, str], list[int]] = defaultdict(list)
    identity: dict[str, tuple[str, str | None, str]] = {}
    scenario_rows: dict[str, int] = defaultdict(int)
    scenario_results: dict[str, list[str]] = defaultdict(list)
    fields = {"sequence", "scenario", "handoffId", "businessMessageId", "payloadSha256", "dispatchType",
              "attempt", "result", "wallClock"}
    for index, line in enumerate(lines, 1):
        row = loads_strict(line, f"handoff-evidence line {index}")
        if not isinstance(row, dict):
            raise EvidenceError(f"handoff-evidence line {index} 必须是对象")
        exact_keys(row, fields, f"handoff-evidence line {index}")
        if row["sequence"] != index or row["scenario"] not in SCENARIOS:
            raise EvidenceError(f"handoff-evidence line {index} sequence/场景非法")
        handoff = row["handoffId"]
        business_message_id = row["businessMessageId"]
        digest = row["payloadSha256"]
        dispatch = row["dispatchType"]
        attempt = row["attempt"]
        if (not isinstance(handoff, str) or not handoff or len(handoff) > 128
                or (business_message_id is not None
                    and (not isinstance(business_message_id, str) or not business_message_id
                         or len(business_message_id) > 128))
                or not isinstance(digest, str) or not HEX_64.fullmatch(digest)
                or dispatch not in DISPATCH_TYPES or row["result"] not in RESULTS
                or isinstance(attempt, bool) or not isinstance(attempt, int) or attempt < 1):
            raise EvidenceError(f"handoff-evidence line {index} 字段非法")
        try:
            datetime.fromisoformat(str(row["wallClock"]).replace("Z", "+00:00"))
        except ValueError as exception:
            raise EvidenceError(f"handoff-evidence line {index} wallClock 非法") from exception
        if handoff in identity and identity[handoff] != (digest, business_message_id, dispatch):
            raise EvidenceError(f"同一 handoffId 的 payload/messageId/分派漂移: {handoff}")
        identity[handoff] = (digest, business_message_id, dispatch)
        attempts[handoff].append(attempt)
        scenario_attempts[(row["scenario"], handoff)].append(attempt)
        scenario_rows[row["scenario"]] += 1
        scenario_results[row["scenario"]].append(row["result"])
    for key, values in attempts.items():
        if values != list(range(1, len(values) + 1)):
            raise EvidenceError(f"handoff attempt 不连续: {key}")
    semantic_pass = (
        "accepted" in scenario_results["normal"]
        and "accepted" in scenario_results["application-stop"]
        and all("transient_retry" in scenario_results[name]
                and ("accepted" in scenario_results[name] or "duplicate" in scenario_results[name])
                for name in ("kafka-stop", "database-stop"))
        and any(result in {"accepted", "duplicate"} for result in scenario_results["ack-ambiguity"])
        and any(len(values) >= 2 for key, values in scenario_attempts.items()
                if key[0] == "ack-ambiguity")
        and any(len(values) >= 2 for key, values in scenario_attempts.items()
                if key[0] == "emqx-restart")
        and "quarantined" in scenario_results["poison"]
        and "accepted" in scenario_results["poison"]
    )
    expected_by_scenario = {
        item["name"]: set(string_list(item["expectedMessageIds"], f"{item['name']}.expected"))
        for item in scenarios
    }
    observed_by_scenario: dict[str, set[str]] = defaultdict(set)
    for line in lines:
        row = loads_strict(line, "handoff-evidence message binding")
        if row["businessMessageId"] is not None:
            observed_by_scenario[row["scenario"]].add(row["businessMessageId"])
    expected_global = set().union(*expected_by_scenario.values())
    observed_global = set().union(*observed_by_scenario.values())
    message_binding_pass = expected_global == observed_global and all(
        expected_by_scenario[name].issubset(observed_by_scenario[name])
        for name in SCENARIOS
    )
    add_check(checks, "handoffEvidence", all(scenario_rows[name] > 0 for name in SCENARIOS) and semantic_pass,
              {name: {"rows": scenario_rows[name], "results": scenario_results[name]} for name in SCENARIOS},
              "七场景逐 handoff 行及故障重试/歧义/隔离语义完整")
    add_check(checks, "businessMessageBinding", message_binding_pass,
              {name: sorted(observed_by_scenario[name]) for name in SCENARIOS},
              "每场 expectedMessageIds 均来自生产入口逐 handoff 证据")
    return hashlib.sha256(raw).hexdigest()


def evaluate(evidence_dir: Path) -> dict[str, Any]:
    """生成四态结论；证据错误、资格失败、SUT 失败互不混淆。"""
    checks: list[dict[str, Any]] = []
    errors: list[str] = []
    document: dict[str, Any] = {}
    artifacts: dict[str, Any] = {}
    jsonl_sha: str | None = None
    qualification_result: str | None = None
    try:
        document = read_object(evidence_dir / "qualification-input.json", "qualification-input")
        qualification_result, configuration, artifacts, checks = validate_top(document)
        validate_scenarios(document["scenarios"], checks)
        jsonl_sha = validate_jsonl(evidence_dir / "handoff-evidence.jsonl", checks, document["scenarios"])
    except EvidenceError as exception:
        errors.append(str(exception))

    if errors:
        validity = "ERROR"
    elif qualification_result != "PASS" or any(
            not check["passed"] for check in checks
            if check["name"] in {"isolatedQualification", "frozenConfiguration"}):
        validity = "INVALID_GENERATOR"
    elif any(not check["passed"] for check in checks):
        validity = "VALID_FAIL"
    else:
        validity = "VALID_PASS"
    fingerprint = None
    if not errors:
        material = {"configuration": document["configuration"], "artifacts": artifacts}
        fingerprint = hashlib.sha256(json.dumps(material, ensure_ascii=False, sort_keys=True,
                                                separators=(",", ":")).encode("utf-8")).hexdigest()
    return {"schemaVersion": 1, "overall": "PASS" if validity == "VALID_PASS" else "FAIL",
            "validityStatus": validity, "runId": document.get("run", {}).get("runId"),
            "comparisonFingerprint": fingerprint, "handoffEvidenceSha256": jsonl_sha,
            "checks": checks, "errors": errors}


def write_atomic(path: Path, value: str) -> None:
    """先写同目录临时文件再替换，禁止上传半份报告。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(value, encoding="utf-8", newline="\n")
    temporary.replace(path)


def markdown(report: dict[str, Any]) -> str:
    """生成与 JSON 同源的人工摘要。"""
    lines = ["# G1-C4a-1 durable handoff 机器裁决", "",
             f"- 总结论：`{report['overall']}`", f"- 有效性：`{report['validityStatus']}`",
             f"- 比较指纹：`{report['comparisonFingerprint'] or 'N/A'}`", "", "## Checks", "",
             "| Check | 结果 | 实际 |", "| --- | --- | --- |"]
    for check in report["checks"]:
        actual = json.dumps(check["actual"], ensure_ascii=False, separators=(",", ":"))
        lines.append(f"| `{check['name']}` | {'PASS' if check['passed'] else 'FAIL'} | `{actual}` |")
    if report["errors"]:
        lines.extend(["", "## 证据错误", ""] + [f"- {item}" for item in report["errors"]])
    return "\n".join(lines) + "\n"


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    args = parser.parse_args()
    report = evaluate(args.evidence_dir.resolve())
    write_atomic(args.output, json.dumps(report, ensure_ascii=False, sort_keys=True,
                                         separators=(",", ":")) + "\n")
    write_atomic(args.markdown_output, markdown(report))
    print(f"[c4a-verdict] {report['overall']} validity={report['validityStatus']}")
    return 0 if report["validityStatus"] == "VALID_PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
