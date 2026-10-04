"""G1-C3d L1 机器判定的确定性单元测试。"""

from __future__ import annotations

import importlib.util
import hashlib
import json
import tempfile
import unittest
import urllib.request
import zipfile
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name: str):
    """加载 scripts 下的独立 Python 入口。"""
    specification = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(specification)
    assert specification.loader is not None
    specification.loader.exec_module(module)
    return module


def write_json(path: Path, value) -> None:
    """为测试夹具写 JSON。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value), encoding="utf-8")


def metadata(backend_sha: str = "backend-a") -> dict:
    """返回完整但最小的比较元数据。"""
    return {
        "repository": "owner/repo", "commit": "abc", "runId": 10, "runAttempt": 1,
        "runnerImageOs": "ubuntu24", "runnerImageVersion": "20260820.1",
        "os": "Linux-test", "machine": "x86_64", "logicalCpuCount": 4,
        "hostMemoryBytes": 16_000_000_000, "rootDiskBytes": 80_000_000_000,
        "python": "3.12", "java": "21", "dockerClient": "28", "dockerServer": "28",
        "images": {"tc-postgres": "sha256:p", "tc-emqx": "sha256:e"},
        "backendJarSha256": backend_sha, "simulatorJarSha256": "simulator-a",
        "load": {"devices": 1000, "steadySeconds": 600, "quotaPolicy": "L1_NIGHTLY"},
    }


def prometheus(end: bool) -> str:
    """构造 1,000 条上行、600 条命令、10,000 个成功点的起止快照。"""
    factor = 1 if end else 0
    return "\n".join([
        f'thingslink_ingestion_uplink_end_to_end_seconds_bucket{{le="0.1"}} {900 * factor}',
        f'thingslink_ingestion_uplink_end_to_end_seconds_bucket{{le="1.0"}} {1000 * factor}',
        f'thingslink_ingestion_uplink_end_to_end_seconds_bucket{{le="300.0"}} {1000 * factor}',
        f'thingslink_ingestion_uplink_end_to_end_seconds_bucket{{le="+Inf"}} {1000 * factor}',
        f'thingslink_command_acceptance_seconds_bucket{{le="0.1"}} {500 * factor}',
        f'thingslink_command_acceptance_seconds_bucket{{le="1.0"}} {600 * factor}',
        f'thingslink_command_acceptance_seconds_bucket{{le="+Inf"}} {600 * factor}',
        f'thingslink_telemetry_timeseries_write_total{{result="success"}} {10000 * factor}',
        'thingslink_telemetry_timeseries_write_total{result="failure"} 0',
        'http_server_requests_seconds_count{status="500"} 0',
        'hikaricp_connections_timeout_total{pool="thingslink-control"} 0',
        'hikaricp_connections_timeout_total{pool="thingslink-data"} 0',
    ]) + "\n"


def emqx_metrics(total: int = 0, failed: int = 0) -> dict:
    """构造固定 EMQX 5.8 durable republish 规则聚合计数。"""
    return {
        "schemaVersion": 2,
        "ingressMode": "durable-republish",
        "rule": {"matched": total, "actions.total": total,
                 "actions.success": total - failed, "actions.failed": failed},
    }


def write_command_evidence(evidence: Path, failed: int = 0) -> None:
    """生成逐命令、聚合和双信号；默认 600 条单 attempt 成功。"""
    timestamp = "2026-08-24T08:00:00+00:00"
    submissions = []
    commands = []
    for sequence in range(600):
        command_id = f"00000000-0000-0000-0000-{sequence + 1:012d}"
        device_id = f"10000000-0000-0000-0000-{sequence + 1:012d}"
        success = sequence < 600 - failed
        attempts = 1 if success else 3
        submissions.append({"sequence": sequence, "deviceId": device_id,
                            "commandId": command_id, "acceptedStatus": "ACCEPTED"})
        command_attempts = []
        for attempt_no in range(1, attempts + 1):
            reply_id = (f"20000000-0000-0000-{sequence + 1:04d}-{attempt_no:012d}"
                        if success else None)
            command_attempts.append({
                "attemptNo": attempt_no, "status": "SUCCEEDED" if success else "TIMED_OUT",
                "errorCode": None if success else "RESPONSE_TIMEOUT", "replyMessageId": reply_id,
                "createdAt": timestamp, "deadlineAt": timestamp,
                "publishedAt": timestamp, "acknowledgedAt": timestamp if success else None,
                "completedAt": timestamp,
                "outbox": {"eventId": f"30000000-{sequence + 1:04d}-0000-{attempt_no:04d}-000000000001",
                           "aggregateType": "DEVICE_COMMAND", "aggregateId": command_id,
                           "eventType": "DEVICE_COMMAND_DISPATCH", "status": "PUBLISHED",
                           "attemptCount": 0, "createdAt": timestamp, "publishedAt": timestamp},
            })
        commands.append({
            "sequence": sequence, "deviceId": device_id, "commandId": command_id,
            "acceptedStatus": "ACCEPTED",
            "terminal": {"status": "SUCCEEDED" if success else "TIMED_OUT",
                         "failureCode": None if success else "RESPONSE_TIMEOUT",
                         "attemptCount": attempts, "maxAttempts": 3,
                         "acceptedAt": timestamp, "dispatchedAt": timestamp,
                         "acknowledgedAt": timestamp if success else None, "completedAt": timestamp},
            "attempts": command_attempts,
        })
    document = {"schemaVersion": 1, "runId": "run-1", "expected": 600, "commands": commands}
    write_json(evidence / "command-evidence.json", document)
    write_json(evidence / "command-submissions.json", submissions)
    succeeded = 600 - failed
    attempts = succeeded + failed * 3
    summary = {
        "schemaVersion": 2, "runId": "run-1", "expected": 600,
        "submitted": 600, "uniqueCommandIds": 600, "terminal": 600,
        "statusCounts": ({"SUCCEEDED": 600} if failed == 0
                         else {"SUCCEEDED": succeeded, "TIMED_OUT": failed}),
        "sampleFailures": [],
        "database": {"commands": 600, "attempts": attempts,
                     "succeededAttempts": succeeded, "replyMessageIds": succeeded,
                     "uniqueReplyMessageIds": succeeded},
        "passed": failed == 0,
    }
    write_json(evidence / "command-results.json", summary)
    digest = hashlib.sha256((evidence / "command-evidence.json").read_bytes()).hexdigest()
    write_json(evidence / "performance-complete.json",
               {"schemaVersion": 1, "runId": "run-1", "phase": "PERFORMANCE_COMPLETE",
                "outcome": "COMPLETE", "submitted": 600})
    write_json(evidence / "command-drain-complete.json",
               {"schemaVersion": 1, "runId": "run-1", "phase": "COMMAND_DRAIN_COMPLETE",
                "outcome": "COMPLETE", "submitted": 600, "terminal": 600,
                "succeeded": succeeded, "evidenceSha256": digest})


def valid_evidence(root: Path) -> tuple[Path, Path]:
    """写入一轮满足全部 correctness 的最小证据。"""
    evidence = root / "evidence"
    history = root / "history"
    write_json(evidence / "quota-policy.json",
               {"projectId": "project", "tenantId": "tenant", "policyId": "policy",
                "policyCode": "L1_NIGHTLY", "policyVersion": 1,
                "deviceCountLimit": 2000, "uplinkMessageDailyLimit": 20000,
                "timeSeriesPointDailyLimit": 200000, "downlinkMessageDailyLimit": 1200,
                "restApiRatePerMinute": 1200, "restApiReadRatePerMinute": 1200,
                "assignmentVersion": 3})
    write_json(evidence / "qualification" / "qualification-report.json",
               {"result": "PASS", "fingerprint": {"runnerSha256": "a4", "jarSha256": "sim"}})
    write_json(evidence / "run-metadata.json", metadata())
    write_command_evidence(evidence)
    write_json(evidence / "fact-reconciliation.json",
               {"passed": True, "manifest": 1000, "inbox": 1000, "points": 10000})
    (evidence / "prometheus-start.txt").write_text(prometheus(False), encoding="utf-8")
    (evidence / "prometheus-end.txt").write_text(prometheus(True), encoding="utf-8")
    (evidence / "prometheus-correctness-start.txt").write_text(prometheus(False), encoding="utf-8")
    (evidence / "prometheus-correctness-final.txt").write_text(prometheus(True), encoding="utf-8")
    write_json(evidence / "emqx-ingress-start.json", emqx_metrics())
    write_json(evidence / "emqx-ingress-final.json", emqx_metrics(total=1600))
    groups = ("things-link-ingestion-raw", "things-link-ingestion-normalized",
              "things-link-ingestion-processed")
    samples = [{"sequence": index, "epochMillis": 1_000_000 + index * 5000,
                "groupLag": {groups[0]: index % 3, groups[1]: 0, groups[2]: 0},
                "totalLag": index % 3, "sutCpuPercent": 25.0} for index in range(119)]
    (evidence / "steady-samples.jsonl").write_text(
        "\n".join(json.dumps(sample) for sample in samples) + "\n", encoding="utf-8")
    lag_header = ("sequence", "epochMillis", *groups, "totalLag")
    lag_rows = [lag_header] + [
        (sample["sequence"], sample["epochMillis"],
         *(sample["groupLag"][group] for group in groups), sample["totalLag"])
        for sample in samples
    ]
    (evidence / "lag.tsv").write_text(
        "\n".join("\t".join(str(value) for value in row) for row in lag_rows) + "\n",
        encoding="utf-8")
    (evidence / "dlq.txt").write_text("tc.dlq=0\ntc.rule.dlq=0\n", encoding="utf-8")
    (evidence / "final-groups.txt").write_text(
        "GROUP a\nTOTAL-LAG 0\nGROUP b\n  TOTAL-LAG   0\nGROUP c\nTOTAL-LAG 0\n", encoding="utf-8")
    write_json(history / "history-index.json", {"status": "PASS", "reports": []})
    return evidence, history


class MachineVerdictTests(unittest.TestCase):
    """覆盖 warmup、连续退化与指纹边界。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.verdict = load_script("l1_machine_verdict")

    def test_valid_first_run_is_warmup_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            report = self.verdict.evaluate(evidence, history, "success")
        self.assertEqual(report["overall"], "PASS")
        self.assertEqual(report["validityStatus"], "VALID_PASS")
        self.assertEqual(report["performanceStatus"], "WARMUP")
        self.assertEqual(report["metrics"]["timeSeriesWriteFailureRate"], 0)
        self.assertTrue(next(check for check in report["checks"]
                             if check["name"] == "quotaPolicyBinding")["passed"])

    def test_complete_two_command_timeout_is_valid_fail_with_direct_detail(self) -> None:
        """证据完整的真实终态失败属于 VALID_FAIL，不能降成缺证 ERROR。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            write_command_evidence(evidence, failed=2)
            report = self.verdict.evaluate(evidence, history, "failure")
        self.assertEqual("VALID_FAIL", report["validityStatus"])
        checks = {check["name"]: check for check in report["checks"]}
        self.assertFalse(checks["commandClosure"]["passed"])
        self.assertTrue(checks["commandEvidenceReconciliation"]["passed"])
        self.assertEqual({"SUCCEEDED": 598, "TIMED_OUT": 2},
                         checks["commandClosure"]["actual"]["statusCounts"])

    def test_bounded_drain_with_nonterminal_command_is_valid_fail(self) -> None:
        """180 秒排空期满后的非终态是完整业务失败证据，不是 JSON 结构损坏。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            detail = json.loads((evidence / "command-evidence.json").read_text(encoding="utf-8"))
            last = detail["commands"][-1]
            last["terminal"]["status"] = "ACCEPTED"
            last["terminal"]["completedAt"] = None
            last["attempts"][0]["status"] = "PUBLISHED"
            last["attempts"][0]["completedAt"] = None
            last["attempts"][0]["replyMessageId"] = None
            write_json(evidence / "command-evidence.json", detail)
            summary = json.loads((evidence / "command-results.json").read_text(encoding="utf-8"))
            summary.update({"terminal": 599, "statusCounts": {"SUCCEEDED": 599, "ACCEPTED": 1},
                            "passed": False})
            summary["database"].update({"succeededAttempts": 599, "replyMessageIds": 599,
                                        "uniqueReplyMessageIds": 599})
            write_json(evidence / "command-results.json", summary)
            digest = hashlib.sha256((evidence / "command-evidence.json").read_bytes()).hexdigest()
            drain = json.loads((evidence / "command-drain-complete.json").read_text(encoding="utf-8"))
            drain.update({"outcome": "TIMEOUT", "terminal": 599,
                          "succeeded": 599, "evidenceSha256": digest})
            write_json(evidence / "command-drain-complete.json", drain)
            report = self.verdict.evaluate(evidence, history, "failure")
        self.assertEqual("VALID_FAIL", report["validityStatus"])
        checks = {check["name"]: check for check in report["checks"]}
        self.assertTrue(checks["commandEvidenceReconciliation"]["passed"])
        self.assertEqual("TIMEOUT", checks["commandDrainCoordination"]["actual"]["drainOutcome"])

    def test_retry_then_success_is_valid_fail_not_evidence_error(self) -> None:
        """完整合法的第二次 attempt 成功违反本轮单 attempt 门禁，但不是证据损坏。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            detail = json.loads((evidence / "command-evidence.json").read_text(encoding="utf-8"))
            command = detail["commands"][-1]
            first = command["attempts"][0]
            reply_id = first["replyMessageId"]
            first.update({"status": "FAILED", "errorCode": "DISPATCH_FAILED",
                          "replyMessageId": None})
            second = json.loads(json.dumps(first))
            second.update({"attemptNo": 2, "status": "SUCCEEDED", "errorCode": None,
                           "replyMessageId": reply_id})
            second["outbox"]["eventId"] = "39999999-9999-9999-9999-999999999999"
            command["attempts"].append(second)
            command["terminal"]["attemptCount"] = 2
            write_json(evidence / "command-evidence.json", detail)
            summary = json.loads((evidence / "command-results.json").read_text(encoding="utf-8"))
            summary["database"]["attempts"] = 601
            summary["passed"] = False
            write_json(evidence / "command-results.json", summary)
            digest = hashlib.sha256((evidence / "command-evidence.json").read_bytes()).hexdigest()
            drain = json.loads((evidence / "command-drain-complete.json").read_text(encoding="utf-8"))
            drain["evidenceSha256"] = digest
            write_json(evidence / "command-drain-complete.json", drain)
            report = self.verdict.evaluate(evidence, history, "failure")
        self.assertEqual("VALID_FAIL", report["validityStatus"])
        checks = {check["name"]: check for check in report["checks"]}
        self.assertTrue(checks["commandEvidenceReconciliation"]["passed"])
        self.assertFalse(checks["commandEvidenceReconciliation"]["actual"]["singleAttemptSuccess"])

    def test_impossible_drain_outcome_count_pair_is_evidence_error(self) -> None:
        """TIMEOUT+600 终态不可能由探针生成，必须作为信号合同漂移拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            drain = json.loads((evidence / "command-drain-complete.json").read_text(encoding="utf-8"))
            drain["outcome"] = "TIMEOUT"
            write_json(evidence / "command-drain-complete.json", drain)
            report = self.verdict.evaluate(evidence, history, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertTrue(any("双信号" in error for error in report["errors"]))

    def test_command_detail_summary_drift_is_evidence_error(self) -> None:
        """summary 不能覆盖逐命令事实；任一聚合漂移必须 ERROR fail-closed。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            summary = json.loads((evidence / "command-results.json").read_text(encoding="utf-8"))
            summary["database"]["attempts"] = 599
            write_json(evidence / "command-results.json", summary)
            report = self.verdict.evaluate(evidence, history, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertTrue(any("重算聚合不一致" in error for error in report["errors"]))

    def test_command_outbox_mismatch_is_evidence_error(self) -> None:
        """Outbox 必须由 event_id 直接关联 command，错配不得被聚合绿灯掩盖。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            detail = json.loads((evidence / "command-evidence.json").read_text(encoding="utf-8"))
            detail["commands"][0]["attempts"][0]["outbox"]["aggregateId"] = "wrong"
            write_json(evidence / "command-evidence.json", detail)
            report = self.verdict.evaluate(evidence, history, "success")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertTrue(any("Outbox 关联错误" in error for error in report["errors"]))

    def test_post_steady_http_hikari_and_emqx_failures_reject_correctness(self) -> None:
        """性能终点后的故障必须由 correctness-final 捕获，不能重现 #5 的假零值。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            final = prometheus(True).replace(
                'http_server_requests_seconds_count{status="500"} 0',
                'http_server_requests_seconds_count{status="500"} 7').replace(
                'hikaricp_connections_timeout_total{pool="thingslink-control"} 0',
                'hikaricp_connections_timeout_total{pool="thingslink-control"} 13')
            (evidence / "prometheus-correctness-final.txt").write_text(final, encoding="utf-8")
            write_json(evidence / "emqx-ingress-final.json", emqx_metrics(total=1600, failed=7))
            report = self.verdict.evaluate(evidence, history, "failure")

        self.assertEqual("VALID_FAIL", report["validityStatus"])
        checks = {check["name"]: check for check in report["checks"]}
        self.assertFalse(checks["http5xx"]["passed"])
        self.assertFalse(checks["hikariConnectionTimeouts"]["passed"])
        self.assertFalse(checks["emqxDurableIngressFailures"]["passed"])

    def test_uplink_p99_overflow_is_valid_fail_and_not_performance_input(self) -> None:
        """最大有限桶少于 P99 排位时不能再把 300 秒截顶值当成可比较性能。"""
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            saturated = prometheus(True).replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="1.0"} 1000',
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="1.0"} 980').replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="300.0"} 1000',
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="300.0"} 989')
            (evidence / "prometheus-end.txt").write_text(saturated, encoding="utf-8")
            report = self.verdict.evaluate(evidence, history, "success")

        self.assertEqual("VALID_FAIL", report["validityStatus"])
        self.assertEqual("NOT_EVALUATED", report["performanceStatus"])
        check = next(item for item in report["checks"]
                     if item["name"] == "uplinkP99HistogramRange")
        self.assertFalse(check["passed"])
        self.assertTrue(check["actual"]["saturated"])
        self.assertEqual(11.0, check["actual"]["overflowCount"])

    def test_uplink_p99_rank_equal_to_max_finite_count_is_in_range(self) -> None:
        """冻结规则是严格小于才溢出；累计值恰等于 P99 排位必须判为有效。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            start_path = root / "start.txt"
            end_path = root / "end.txt"
            start_path.write_text(prometheus(False), encoding="utf-8")
            boundary = prometheus(True).replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="1.0"} 1000',
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="1.0"} 980').replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="300.0"} 1000',
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="300.0"} 990')
            end_path.write_text(boundary, encoding="utf-8")
            _, _, diagnostics = self.verdict.histogram_quantile(
                self.verdict.parse_prometheus(start_path), self.verdict.parse_prometheus(end_path),
                self.verdict.UPLINK_HISTOGRAM, 0.99)
        self.assertFalse(diagnostics["saturated"])

    def test_missing_frozen_300_second_bucket_is_valid_fail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            start = prometheus(False).replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="300.0"} 0\n', "")
            end = prometheus(True).replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="300.0"} 1000\n', "")
            (evidence / "prometheus-start.txt").write_text(start, encoding="utf-8")
            (evidence / "prometheus-end.txt").write_text(end, encoding="utf-8")
            report = self.verdict.evaluate(evidence, history, "success")
        check = next(item for item in report["checks"]
                     if item["name"] == "uplinkP99HistogramRange")
        self.assertEqual("VALID_FAIL", report["validityStatus"])
        self.assertFalse(check["passed"])
        self.assertEqual(1.0, check["actual"]["maxFiniteUpper"])

    def test_non_monotonic_histogram_is_evidence_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            end = prometheus(True).replace(
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="1.0"} 1000',
                'thingslink_ingestion_uplink_end_to_end_seconds_bucket{le="1.0"} 800')
            (evidence / "prometheus-end.txt").write_text(end, encoding="utf-8")
            report = self.verdict.evaluate(evidence, history, "failure")
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertTrue(any("bucket" in error and "非单调" in error for error in report["errors"]))

    def test_lag_tsv_mismatch_rejects_correctness(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            evidence, history = valid_evidence(Path(directory))
            rows = (evidence / "lag.tsv").read_text(encoding="utf-8").splitlines()
            fields = rows[1].split("\t")
            fields[-1] = "99"
            rows[1] = "\t".join(fields)
            (evidence / "lag.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
            report = self.verdict.evaluate(evidence, history, "success")
        check = next(item for item in report["checks"]
                     if item["name"] == "lagEvidenceReconciliation")
        self.assertEqual("VALID_FAIL", report["validityStatus"])
        self.assertFalse(check["passed"])
        self.assertEqual(0, check["actual"]["firstMismatchSequence"])

    def test_control_pool_wait_changes_comparison_fingerprint(self) -> None:
        """借连接预算变化不能混入旧候选性能历史。"""
        qualification = {"fingerprint": {"runnerSha256": "a4", "jarSha256": "sim"}}
        old = metadata()
        old["load"]["controlConnectionTimeoutMs"] = 500
        candidate = metadata()
        candidate["load"]["controlConnectionTimeoutMs"] = 2000
        first, _ = self.verdict.fingerprint(old, qualification, SCRIPTS)
        second, _ = self.verdict.fingerprint(candidate, qualification, SCRIPTS)
        self.assertNotEqual(first, second)

    def test_backend_sha_is_archived_but_does_not_split_comparison_fingerprint(self) -> None:
        qualification = {"fingerprint": {"runnerSha256": "a4", "jarSha256": "sim"}}
        first, document = self.verdict.fingerprint(metadata("backend-a"), qualification, SCRIPTS)
        second, _ = self.verdict.fingerprint(metadata("backend-b"), qualification, SCRIPTS)
        self.assertEqual(first, second)
        self.assertNotIn("backendJarSha256", document)
        self.assertIn("l0_fixture.py", document["scripts"])
        self.assertIn("l1_quota_prepare.py", document["scripts"])

    def test_local_runner_cannot_share_github_performance_fingerprint(self) -> None:
        qualification = {"fingerprint": {"runnerSha256": "a4", "jarSha256": "sim"}}
        original = metadata()
        original_sha, _ = self.verdict.fingerprint(original, qualification, SCRIPTS)
        original["executionEnvironment"] = "github-actions"
        github_sha, _ = self.verdict.fingerprint(original, qualification, SCRIPTS)
        original["executionEnvironment"] = "local-docker"
        local_sha, document = self.verdict.fingerprint(original, qualification, SCRIPTS)
        self.assertEqual(original_sha, github_sha)
        self.assertNotEqual(github_sha, local_sha)
        self.assertEqual("local-docker", document["executionEnvironment"])

    def test_unrelated_nan_does_not_invalidate_target_metrics(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prometheus.txt"
            path.write_text(prometheus(True) + "unrelated_optional_gauge NaN\n", encoding="utf-8")
            parsed = self.verdict.parse_prometheus(path)
        self.assertIn(self.verdict.UPLINK_HISTOGRAM, parsed)
        self.assertNotIn("unrelated_optional_gauge", parsed)

    def test_one_degradation_warns_and_same_metric_twice_fails(self) -> None:
        baseline_metrics = {key: 1.0 for key in self.verdict.METRIC_KEYS}
        current = dict(baseline_metrics)
        current["uplinkP99Seconds"] = 1.21
        history = [{"comparisonFingerprint": "same", "validityStatus": "VALID_PASS",
                    "github": {"runId": 5 - index}, "metrics": baseline_metrics,
                    "performance": {"degradedMetrics": []}} for index in range(5)]
        warning = self.verdict.performance_verdict(current, history, "same")
        self.assertEqual(warning["status"], "PERFORMANCE_WARNING")
        history[0]["performance"]["degradedMetrics"] = ["uplinkP99Seconds"]
        regression = self.verdict.performance_verdict(current, history, "same")
        self.assertEqual(regression["status"], "PERFORMANCE_REGRESSION")
        self.assertEqual(regression["consecutiveDegradedMetrics"], ["uplinkP99Seconds"])

    def test_generator_rejection_is_not_sut_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence = root / "evidence"
            history = root / "history"
            write_json(evidence / "quota-policy.json",
                       {"projectId": "project", "tenantId": "tenant", "policyId": "policy",
                        "policyCode": "L1_NIGHTLY", "policyVersion": 1,
                        "deviceCountLimit": 2000, "uplinkMessageDailyLimit": 20000,
                        "timeSeriesPointDailyLimit": 200000, "downlinkMessageDailyLimit": 1200,
                        "restApiRatePerMinute": 1200, "restApiReadRatePerMinute": 1200,
                        "assignmentVersion": 3})
            write_json(evidence / "qualification" / "qualification-report.json",
                       {"result": "FAIL", "fingerprint": {"runnerSha256": "a4"}})
            write_json(evidence / "run-metadata.json", metadata())
            write_json(history / "history-index.json", {"status": "PASS", "reports": []})
            report = self.verdict.evaluate(evidence, history, "failure")
        self.assertEqual(report["validityStatus"], "INVALID_GENERATOR")
        self.assertEqual(report["overall"], "FAIL")

    def test_invalid_history_with_null_run_id_does_not_crash_current_verdict(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            evidence, history = valid_evidence(root)
            report_path = history / "old" / "machine-report.json"
            write_json(report_path, {"schemaVersion": 1, "validityStatus": "ERROR",
                                     "github": {"runId": None, "runAttempt": None}})
            write_json(history / "history-index.json", {
                "status": "PASS",
                "reports": [{"artifactId": 1, "path": "old/machine-report.json"}],
            })
            report = self.verdict.evaluate(evidence, history, "success")
        self.assertEqual("VALID_PASS", report["validityStatus"])
        self.assertEqual("WARMUP", report["performanceStatus"])

    def test_unexpected_internal_error_still_renders_compact_failure_report(self) -> None:
        report = self.verdict.internal_error_report(TypeError("broken history"))
        rendered = self.verdict.markdown(report)
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertIn("TypeError: broken history", rendered)


class HistoryArchiveTests(unittest.TestCase):
    """验证历史 ZIP 只提取唯一紧凑报告。"""

    def test_extracts_one_machine_report(self) -> None:
        history = load_script("l1_history")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "report.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("machine-report.json", '{"overall":"PASS"}')
            destination = root / "out" / "machine-report.json"
            history.extract_report(archive, destination)
            self.assertEqual(json.loads(destination.read_text()), {"overall": "PASS"})

    def test_redirect_strips_authorization_only_when_origin_changes(self) -> None:
        history = load_script("l1_history")
        handler = history.StripCrossOriginAuthorization()
        source = urllib.request.Request(
            "https://api.github.com/repos/owner/repo/actions/artifacts/1/zip",
            headers={"Authorization": "Bearer secret"})
        cross_origin = handler.redirect_request(
            source, None, 302, "Found", {}, "https://results.blob.core.windows.net/report.zip?sig=x")
        same_origin = handler.redirect_request(
            source, None, 302, "Found", {}, "https://api.github.com/redirected")
        self.assertIsNone(cross_origin.get_header("Authorization"))
        self.assertEqual("Bearer secret", same_origin.get_header("Authorization"))


if __name__ == "__main__":
    unittest.main()
