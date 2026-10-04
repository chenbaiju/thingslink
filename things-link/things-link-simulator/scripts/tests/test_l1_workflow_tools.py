"""G1-C3d 工作流工具的无外部依赖契约测试。"""

from __future__ import annotations

import argparse
import concurrent.futures
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name: str):
    """从非 package 的 scripts 目录加载被测入口。"""
    specification = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(specification)
    assert specification.loader is not None
    # dataclass 在装饰类时会按 __module__ 读取 sys.modules；显式登记也让同一入口的类型解析稳定。
    sys.modules[name] = module
    specification.loader.exec_module(module)
    return module


class L1TopologyContractTests(unittest.TestCase):
    """锁住两核 Runner 候选的单源参数，防止实跑与比较指纹各写一套数字。"""

    def test_six_shard_candidate_uses_derived_host_budgets(self) -> None:
        script = (SCRIPTS / "run-c3d-l1.sh").read_text(encoding="utf-8")
        self.assertIn("L1_DEVICE_COUNT=1000", script)
        self.assertIn("L1_SHARD_SIZE=167", script)
        self.assertIn("L1_SHARD_COUNT=$(((L1_DEVICE_COUNT + L1_SHARD_SIZE - 1) / L1_SHARD_SIZE))", script)
        self.assertIn("L1_SHARD_START_STAGGER_SECONDS=15", script)
        self.assertIn('--shard-start-stagger-seconds "${L1_SHARD_START_STAGGER_SECONDS}"', script)
        self.assertIn("L1_HOST_RSS_BUDGET=$((L1_RSS_BUDGET_PER_SHARD * L1_SHARD_COUNT))", script)
        self.assertIn("L1_HOST_THREAD_BUDGET=$((L1_THREAD_BUDGET_PER_SHARD * L1_SHARD_COUNT))", script)
        self.assertNotIn("--shard-size 125", script)
        self.assertNotIn('"shards": 8', script)
        self.assertIn('--performance-complete-file "${PERFORMANCE_COMPLETE}"', script)
        self.assertIn('--command-drain-complete-file "${COMMAND_DRAIN_COMPLETE}"', script)
        self.assertIn("--performance-completion-grace-seconds 30", script)
        self.assertIn("--command-drain-watchdog-seconds 240", script)
        self.assertNotIn("--steady-complete-file", script)
        self.assertIn('prometheus-correctness-start.txt', script)
        self.assertIn('prometheus-correctness-final.txt', script)
        self.assertIn('/api/v5/rules/tc_durable_uplink/metrics', script)
        self.assertNotIn('/api/v5/actions/http:tc_raw_uplink/metrics', script)
        self.assertNotIn('/api/v5/rules/tc_raw_uplink/metrics', script)
        self.assertIn('THINGS_LINK_INGRESS_HANDOFF_ENABLED=true', script)
        self.assertIn('THINGS_LINK_INGRESS_HANDOFF_PASSWORD="${INGRESS_HANDOFF_PASSWORD}"', script)
        self.assertIn('MINIO_STORAGE_ENDPOINT="http://127.0.0.1:${MINIO_PORT}"', script)
        self.assertIn('THINGS_LINK_STORAGE_INTERNAL_ENDPOINT="${MINIO_STORAGE_ENDPOINT}"', script)
        self.assertIn('THINGS_LINK_STORAGE_EXTERNAL_ENDPOINT="${MINIO_STORAGE_ENDPOINT}"', script)
        self.assertIn('THINGS_LINK_STORAGE_ACCESS_KEY="${MINIO_ROOT_USER}"', script)
        self.assertIn('THINGS_LINK_STORAGE_SECRET_KEY="${MINIO_ROOT_PASSWORD}"', script)
        self.assertIn('THINGS_LINK_OUTBOX_PUBLISHER_FIXED_DELAY_MILLIS=100', script)
        self.assertIn('THINGS_LINK_OUTBOX_PUBLISHER_BATCH_SIZE=4 java -jar', script)
        self.assertIn('thingslink_ingress_handoff_connected', script)
        self.assertIn('<<< "${metrics}"', script)
        self.assertNotIn("actuator/prometheus 2>/dev/null \\\n    | grep -Eq", script)

    def test_control_pool_wait_is_explicit_and_in_candidate_metadata(self) -> None:
        """千台 ACL 突发使用有界借连接窗口；不增加池容量或放松正确性判据。"""
        script = (SCRIPTS / "run-c3d-l1.sh").read_text(encoding="utf-8")
        self.assertIn("L1_CONTROL_CONNECTION_TIMEOUT_MS=2000", script)
        self.assertIn('THINGS_LINK_DATASOURCE_CONTROL_CONNECTION_TIMEOUT_MS="${L1_CONTROL_CONNECTION_TIMEOUT_MS}"', script)
        self.assertIn('"controlConnectionTimeoutMs": int(sys.argv[12])', script)
        self.assertIn('"${L1_CONTROL_CONNECTION_TIMEOUT_MS}" <<', script)
        self.assertNotIn("THINGS_LINK_DATASOURCE_CONTROL_MAXIMUM_POOL_SIZE", script)

    def test_failed_submission_preserves_partial_evidence_without_success_signal(self) -> None:
        """第二条命令被拒绝后保留第一条受理事实，且不能发出性能完成或设备停机许可。"""
        probe = load_script("l1_steady_probe")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            credentials = root / "credentials.json"
            credentials.write_text(json.dumps({"projectId": "project-1", "devices": [
                {"deviceId": f"device-{i}"} for i in range(1000)]}))
            args = SimpleNamespace(output_dir=root, credentials_file=credentials,
                base_url="http://localhost", password_env="L1_TEST_PASSWORD", email="test@example.com",
                run_id="run-1", duration_seconds=600, command_interval_seconds=1,
                performance_complete_file=root / "performance.json",
                command_drain_complete_file=root / "drain.json")
            responses = [{"accessToken": "login"}, {"accessToken": "scoped"},
                         {"id": "command-1", "status": "PENDING"}, RuntimeError("HTTP 429")]
            with mock.patch.object(probe, "parse_args", return_value=args), \
                    mock.patch.dict(probe.os.environ, {"L1_TEST_PASSWORD": "test-only"}), \
                    mock.patch.object(probe, "request", side_effect=responses), \
                    mock.patch.object(probe, "prometheus_snapshot"), \
                    mock.patch.object(probe.threading, "Thread") as thread, \
                    mock.patch.object(probe, "write_lag_tsv"), \
                    mock.patch.object(probe.time, "sleep"):
                thread.return_value.is_alive.return_value = False
                with self.assertRaisesRegex(RuntimeError, "HTTP 429"):
                    probe.main()
            submissions = json.loads((root / "command-submissions.json").read_text())
            self.assertEqual([item["commandId"] for item in submissions], ["command-1"])
            failure = json.loads((root / "command-submission-failure.json").read_text())
            self.assertEqual(failure["submitted"], 1)
            self.assertEqual(failure["expected"], 600)
            self.assertFalse(args.performance_complete_file.exists())
            self.assertFalse(args.command_drain_complete_file.exists())

    def test_runner_supervises_qualification_and_probe_after_ready(self) -> None:
        """资格器 ready 后非零早退必须立刻终止 probe，不能继续制造十余分钟派生命令噪声。"""
        script = (SCRIPTS / "run-c3d-l1.sh").read_text(encoding="utf-8")
        wait_index = script.index('wait -n -p FIRST_EXIT_PID "${QUALIFICATION_PID}" "${PROBE_PID}"')
        fallback_index = script.index('python3 - "${PERFORMANCE_COMPLETE}"')
        self.assertLess(wait_index, fallback_index)
        self.assertIn('if [[ "${FIRST_EXIT_PID}" == "${QUALIFICATION_PID}" ]]', script)
        self.assertIn('[[ "${QUALIFICATION_RC}" -ne 0 ]] && kill -0 "${PROBE_PID}"', script)
        self.assertIn('kill "${PROBE_PID}" 2>/dev/null || true', script)

    def test_qualification_collects_all_shard_failures_with_gap_boundaries(self) -> None:
        """同一采样轮多个分片越线时必须全部归档，并把累计缺口标作 qualification-wide。"""
        qualification = load_script("a4_qualification")
        args = SimpleNamespace(
            sample_gap_limit_ms=2500, cpu_limit=0.7, resource_limit=0.7,
            rss_budget_bytes=536_870_912, thread_budget=1024)

        def shard(name: str, gap: int):
            resources = {
                "sampleCount": 3, "maxSampleGapMillis": gap,
                "maxSampleGapStartedAt": f"2026-08-24T08:43:{name[-1]}0Z",
                "maxSampleGapEndedAt": f"2026-08-24T08:43:{name[-1]}3Z",
                "incompleteSamples": 0, "maxFiveSecondCpuAverage": 0.2,
                "peakHeapUsedBytes": 10, "heapMaxBytes": 100,
                "peakRssBytes": 10, "peakThreadCount": 10,
                "maxFileDescriptors": 100, "peakOpenFileDescriptors": 10,
            }
            return SimpleNamespace(shard_id=name, last_stats={"generatorResources": resources})

        failures = qualification.all_shard_resource_failures(
            args, [shard("shard-000", 2663), shard("shard-002", 2709)])

        self.assertEqual(
            ["shard-000.qualification.sampleGap", "shard-002.qualification.sampleGap"],
            [item["name"] for item in failures])
        self.assertEqual(2663, failures[0]["actual"]["millis"])
        self.assertIsNotNone(failures[0]["actual"]["startedAt"])
        self.assertIsNotNone(failures[0]["actual"]["endedAt"])

    def test_probe_dual_signals_are_atomic_and_cannot_be_confused(self) -> None:
        probe = load_script("l1_steady_probe")
        with tempfile.TemporaryDirectory() as directory:
            performance = Path(directory) / "performance-complete.json"
            drain = Path(directory) / "command-drain-complete.json"
            probe.write_performance_completion(performance, "run-1", 600)
            probe.write_command_drain_completion(drain, "run-1", "COMPLETE", 600, 600, 600, "a" * 64)
            performance_document = json.loads(performance.read_text(encoding="utf-8"))
            drain_document = json.loads(drain.read_text(encoding="utf-8"))
        self.assertEqual("PERFORMANCE_COMPLETE", performance_document["phase"])
        self.assertNotIn("terminal", performance_document)
        self.assertEqual("COMMAND_DRAIN_COMPLETE", drain_document["phase"])
        self.assertEqual(600, drain_document["terminal"])
        self.assertEqual("a" * 64, drain_document["evidenceSha256"])

    def test_command_poll_retries_rate_limit_without_losing_terminal(self) -> None:
        probe = load_script("l1_steady_probe")
        current = [0.0]

        def sleep(seconds: float) -> None:
            current[0] += seconds

        commands = [{"commandId": "command-1", "deviceId": "device-1"}]
        with (mock.patch.object(probe, "request", side_effect=[
                  probe.RateLimited("2"), {"status": "SUCCEEDED"}]) as request,
              mock.patch.object(probe.time, "monotonic", side_effect=lambda: current[0]),
              mock.patch.object(probe.time, "sleep", side_effect=sleep)):
            final = probe.poll_command_terminals(commands, "http://localhost", "project-1", "token", 3)

        self.assertEqual({"command-1": {"status": "SUCCEEDED"}}, final)
        self.assertEqual(2, request.call_count)
        self.assertGreaterEqual(current[0], 2)

    def test_command_get_preserves_retry_after_from_http_429(self) -> None:
        probe = load_script("l1_steady_probe")
        url = "http://localhost/api/v1/commands/one"
        limited = urllib.error.HTTPError(url, 429, "Too Many Requests",
                                         {"Retry-After": "3"}, io.BytesIO(b"{}"))
        with mock.patch.object(probe.OPENER, "open", side_effect=limited):
            with self.assertRaises(probe.RateLimited) as raised:
                probe.request("GET", url, token="test-token")
        limited.close()
        self.assertEqual(3, raised.exception.retry_after_seconds)

    def test_command_summary_is_derived_from_stable_detail(self) -> None:
        probe = load_script("l1_steady_probe")
        timestamp = "2026-08-24T08:00:00+00:00"
        commands = []
        for sequence in range(600):
            command_id = f"00000000-0000-0000-0000-{sequence + 1:012d}"
            commands.append({
                "sequence": sequence,
                "deviceId": f"10000000-0000-0000-0000-{sequence + 1:012d}",
                "commandId": command_id,
                "acceptedStatus": "ACCEPTED",
                "terminal": {"status": "SUCCEEDED", "failureCode": None,
                             "attemptCount": 1, "maxAttempts": 3,
                             "acceptedAt": timestamp, "dispatchedAt": timestamp,
                             "acknowledgedAt": timestamp, "completedAt": timestamp},
                "attempts": [{"attemptNo": 1, "status": "SUCCEEDED", "errorCode": None,
                              "replyMessageId": f"20000000-0000-0000-0000-{sequence + 1:012d}",
                              "createdAt": timestamp, "deadlineAt": timestamp,
                              "publishedAt": timestamp, "acknowledgedAt": timestamp,
                              "completedAt": timestamp,
                              "outbox": {"eventId": f"30000000-0000-0000-0000-{sequence + 1:012d}",
                                         "aggregateType": "DEVICE_COMMAND", "aggregateId": command_id,
                                         "eventType": "DEVICE_COMMAND_DISPATCH", "status": "PUBLISHED",
                                         "attemptCount": 0, "createdAt": timestamp,
                                         "publishedAt": timestamp}}],
            })
        result = probe.summarize_command_evidence(
            {"schemaVersion": 1, "runId": "run-1", "expected": 600, "commands": commands}, [])
        self.assertTrue(result["passed"])
        self.assertEqual({"SUCCEEDED": 600}, result["statusCounts"])
        self.assertEqual(600, result["database"]["replyMessageIds"])

    def test_probe_collects_docker_state_from_one_concurrent_tick(self) -> None:
        """四个 Docker 读取必须同时在途；若退化为串行，屏障会超时并使测试失败。"""
        probe = load_script("l1_steady_probe")
        barrier = threading.Barrier(1 + len(probe.GROUPS))

        def fake_cpu():
            barrier.wait(timeout=2)
            return 12.5, {name: 2.5 for name in probe.CORE_CONTAINERS}

        def fake_lag(group):
            barrier.wait(timeout=2)
            return {name: index for index, name in enumerate(probe.GROUPS)}[group]

        with (mock.patch.object(probe, "docker_cpu_percent", side_effect=fake_cpu),
              mock.patch.object(probe, "group_lag", side_effect=fake_lag),
              concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor):
            total, containers, lags = probe.collect_external_state(executor)

        self.assertEqual(12.5, total)
        self.assertEqual(set(probe.CORE_CONTAINERS), set(containers))
        self.assertEqual({name: index for index, name in enumerate(probe.GROUPS)}, lags)

    def test_probe_atomically_derives_frozen_lag_tsv_from_jsonl(self) -> None:
        probe = load_script("l1_steady_probe")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            samples = root / "steady-samples.jsonl"
            destination = root / "lag.tsv"
            document = {
                "sequence": 7, "epochMillis": 1_234_567,
                "groupLag": {probe.GROUPS[0]: 3, probe.GROUPS[1]: 2, probe.GROUPS[2]: 1},
                "totalLag": 6,
            }
            samples.write_text(json.dumps(document) + "\n", encoding="utf-8")
            probe.write_lag_tsv(samples, destination)
            rows = destination.read_text(encoding="utf-8").splitlines()
            self.assertFalse(destination.with_suffix(".tsv.tmp").exists())
        self.assertEqual(
            "sequence\tepochMillis\t" + "\t".join(probe.GROUPS) + "\ttotalLag", rows[0])
        self.assertEqual("7\t1234567\t3\t2\t1\t6", rows[1])


class L1FixtureTests(unittest.TestCase):
    """验证 L0 夹具扩展不会丢失 L1 命令与设备标识。"""

    def test_expired_project_access_token_rotates_cookie_and_replays_once(self) -> None:
        fixture = load_script("l0_fixture")
        session = fixture.AuthSession("http://127.0.0.1:8080", "expired-token")
        calls: list[tuple[str, str | None]] = []

        def response(document: dict):
            body = io.BytesIO(json.dumps(document).encode())
            body.status = 200
            return body

        def open_request(request, timeout):
            calls.append((request.full_url, request.get_header("Authorization")))
            if len(calls) == 1:
                raise urllib.error.HTTPError(request.full_url, 401, "Unauthorized", {}, io.BytesIO(b"{}"))
            if len(calls) == 2:
                return response({"accessToken": "rotated-token"})
            return response({"id": "device-1"})

        with mock.patch.object(fixture.OPENER, "open", side_effect=open_request):
            created = fixture.request_json("POST", "http://127.0.0.1:8080/api/v1/devices",
                                           {"deviceKey": "one"}, session)
        self.assertEqual(created, {"id": "device-1"})
        self.assertEqual(calls, [
            ("http://127.0.0.1:8080/api/v1/devices", "Bearer expired-token"),
            ("http://127.0.0.1:8080/api/v1/auth/refresh", None),
            ("http://127.0.0.1:8080/api/v1/devices", "Bearer rotated-token"),
        ])

    def test_prepare_adds_command_and_keeps_device_ids(self) -> None:
        fixture = load_script("l0_fixture")
        calls: list[tuple[str, str, object]] = []

        def fake_request(method, url, body=None, token=None, expected=(200,)):
            calls.append((method, url, body))
            if url.endswith("/auth/login"):
                return {"accessToken": "login-token"}
            if url.endswith("/auth/switch-project"):
                return {"accessToken": "project-token"}
            if url.endswith("/device-types"):
                return {"id": "019c0000-0000-7000-8000-000000000010"}
            if url.endswith("/devices"):
                index = len([call for call in calls if call[1].endswith("/devices")])
                return {"id": f"019c0000-0000-7000-8000-{index:012d}"}
            if url.endswith("/credentials"):
                return {"plainSecret": "one-time-secret"}
            return None

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "credentials.json"
            args = argparse.Namespace(
                base_url="http://127.0.0.1:8080", email="l1@example.com", password="secret",
                project_id="019c0000-0000-7000-8000-000000000001", project_key="l1-project",
                device_count=2, profile_prefix="l1", command_key="nightly_probe", output=output)
            with mock.patch.object(fixture, "request_json", side_effect=fake_request):
                fixture.prepare(args)
            document = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(document["projectId"], args.project_id)
        self.assertEqual([device["deviceKey"] for device in document["devices"]],
                         ["l1_device_0000", "l1_device_0001"])
        self.assertTrue(all(device["deviceId"] for device in document["devices"]))
        command_calls = [call for call in calls if call[1].endswith("/commands")]
        self.assertEqual(len(command_calls), 1)
        self.assertEqual(command_calls[0][2]["commandKey"], "nightly_probe")
        publish_index = next(index for index, call in enumerate(calls) if call[1].endswith("/publish"))
        command_index = calls.index(command_calls[0])
        self.assertLess(command_index, publish_index)


class L1QuotaPrepareTests(unittest.TestCase):
    """验证配额准备严格绑定唯一 OWNER 范围并拒绝冻结值漂移。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.quota = load_script("l1_quota_prepare")

    def test_writes_verified_quota_evidence(self) -> None:
        """两次 psql 均返回唯一 JSON 时才形成配额证据。"""
        scope = {"projectId": "project-a", "projectKey": "project-key", "tenantId": "tenant-a"}
        evidence = {"projectId": "project-a", "tenantId": "tenant-a", "policyId": "policy-a",
                    "policyCode": "L1_NIGHTLY", "policyVersion": 1,
                    "deviceCountLimit": 2000, "uplinkMessageDailyLimit": 20000,
                    "timeSeriesPointDailyLimit": 200000, "downlinkMessageDailyLimit": 1200,
                    "restApiRatePerMinute": 1200, "restApiReadRatePerMinute": 1200,
                    "assignmentVersion": 3}
        completed = [
            subprocess.CompletedProcess([], 0, json.dumps(scope) + "\n", ""),
            subprocess.CompletedProcess([], 0, json.dumps(evidence) + "\n", ""),
        ]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "quota-policy.json"
            args = argparse.Namespace(postgres_container="tc-postgres", postgres_user="user",
                                      postgres_db="database", email="l1@example.com",
                                      policy_id="policy-a", output=output)
            with mock.patch.object(self.quota.subprocess, "run", side_effect=completed) as runner:
                result = self.quota.prepare(args)
            document = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual(document, evidence)
        self.assertEqual(result["projectKey"], "project-key")
        self.assertEqual(runner.call_count, 2)
        quota_call = runner.call_args_list[1]
        self.assertIn("L1_NIGHTLY", quota_call.kwargs["input"])
        self.assertIn("PLAN_R1_FREE", quota_call.kwargs["input"])
        self.assertIn("SELECT 1 / 0;", quota_call.kwargs["input"])
        self.assertIn("project_id=project-a", quota_call.args[0])
        self.assertIn("tenant_id=tenant-a", quota_call.args[0])

    def test_rejects_quota_evidence_with_free_limit(self) -> None:
        """数据库若仍投影 FREE 的 100 台上限，必须在建机前明确失败。"""
        scope = {"projectId": "project-a", "projectKey": "project-key", "tenantId": "tenant-a"}
        wrong = {"projectId": "project-a", "tenantId": "tenant-a", "policyId": "policy-a",
                 "policyCode": "FREE", "policyVersion": 1,
                 "deviceCountLimit": 100, "assignmentVersion": 1}
        completed = [
            subprocess.CompletedProcess([], 0, json.dumps(scope) + "\n", ""),
            subprocess.CompletedProcess([], 0, json.dumps(wrong) + "\n", ""),
        ]
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(postgres_container="tc-postgres", postgres_user="user",
                                      postgres_db="database", email="l1@example.com",
                                      policy_id="policy-a", output=Path(directory) / "quota-policy.json")
            with mock.patch.object(self.quota.subprocess, "run", side_effect=completed):
                with self.assertRaisesRegex(RuntimeError, "冻结值不一致"):
                    self.quota.prepare(args)
            self.assertFalse(args.output.exists())


if __name__ == "__main__":
    unittest.main()
