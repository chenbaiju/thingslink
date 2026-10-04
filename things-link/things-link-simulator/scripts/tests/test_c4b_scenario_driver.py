"""G1-C4b 真实场景驱动的安全边界与冻结命令测试。"""

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import MagicMock, patch


SCRIPT = Path(__file__).parents[1] / "c4b_scenario_driver.py"
SPEC = importlib.util.spec_from_file_location("c4b_scenario_driver", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class C4bScenarioDriverTests(unittest.TestCase):
    """验证真实驱动拒绝越界项目且只生成生产接受的消息身份。"""

    def test_uuid_generator_produces_version_seven_rfc_variant(self):
        generated = uuid.UUID(MODULE.uuid7())
        self.assertEqual(7, generated.version)
        self.assertEqual(uuid.RFC_4122, generated.variant)

    def test_driver_rejects_non_isolated_compose_project(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(MODULE.DriverError):
                MODULE.ScenarioDriver(Path(directory), "OB-01", "tc")

    def test_rw01_waits_for_all_asynchronous_rule_facts_and_persists_attribution(self):
        """回执先完成时必须继续等待点位和通知链，不能把瞬态 0/0 判成生产失败。"""
        state = {name: MODULE.uuid7() for name in (
            "messageId", "projectId", "deviceId", "ruleId", "versionId")}
        state["sourceSha256"] = "a" * 64
        early = {
            "receiptCount": 1, "receiptStatus": "COMPLETED", "scriptSuccessCount": 1,
            "processedInboxCount": 1, "propertyPointCount": 0,
            "notificationOutboxCount": 1, "sideEffectCount": 0,
            "qualifiedActionVersionCount": 1, "shadowTemperaturePresentCount": 1,
            "messageLogCount": 1, "capturedAt": "2026-08-29T00:00:00Z",
        }
        settled = {**early, "propertyPointCount": 1, "sideEffectCount": 1,
                   "capturedAt": "2026-08-29T00:00:01Z"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "RW-01", "c4b-019d2c587c6d")
            with patch.object(driver, "_rule_chain_snapshot", side_effect=[early, settled]), \
                    patch.object(driver, "_rule_degradation_attribution",
                                 return_value={"complete": True}), \
                    patch.object(MODULE.time, "sleep"):
                facts = driver.rule_after(state)
            evidence = json.loads((root / "attribution" / "RW-01" / "rule-chain.json")
                                  .read_text(encoding="utf-8"))
        self.assertTrue(facts["finalAssertionsPassed"])
        self.assertEqual(1, facts["propertyPointCount"])
        self.assertEqual(1, facts["sideEffectCount"])
        self.assertEqual(2, evidence["attempts"])
        self.assertEqual("PASS", evidence["verdict"])
        self.assertEqual(settled, evidence["lastObserved"])
        self.assertEqual({"innerRecoverySeconds": 180, "outerAfterProbeSeconds": 300,
                          "attributionReserveSeconds": 120}, evidence["budget"])

    def test_rule_chain_snapshot_uses_all_stable_identities(self):
        """归因查询必须同时绑定消息、规则版本、项目、设备和属性，禁止读取项目总数。"""
        state = {name: MODULE.uuid7() for name in (
            "messageId", "projectId", "deviceId", "ruleId", "versionId")}
        captured: list[str] = []

        def fake_execute(_command, **kwargs):
            captured.append(kwargs["input_text"])
            return "1|COMPLETED|1|1|1|1|1|1|1|1"

        driver = MODULE.ScenarioDriver(
            Path("/tmp/c4b-f23-test"), "RW-01", "c4b-019d2c587c6d", fake_execute)
        snapshot = driver._rule_chain_snapshot(state)
        self.assertTrue(driver._rule_chain_complete(snapshot))
        self.assertEqual(1, len(captured))
        for name in ("messageId", "projectId", "deviceId", "ruleId", "versionId"):
            self.assertIn(state[name], captured[0])
        self.assertIn("FROM ts_property_point_internal", captured[0])
        self.assertNotIn("FROM ts_property_point WHERE", captured[0])
        self.assertIn("property_key='temperature'", captured[0])
        self.assertIn("notification-action", captured[0])
        self.assertIn("dev_shadow", captured[0])
        self.assertIn("ts_device_message_log", captured[0])

    def test_rule_chain_rejects_missing_or_duplicate_authoritative_points(self):
        """内部表的错误身份零行或重复行都不能冒充唯一时序点。"""
        complete = {
            "receiptCount": 1, "receiptStatus": "COMPLETED", "scriptSuccessCount": 1,
            "processedInboxCount": 1, "propertyPointCount": 1,
            "notificationOutboxCount": 1, "sideEffectCount": 1,
            "qualifiedActionVersionCount": 1,
        }
        self.assertTrue(MODULE.ScenarioDriver._rule_chain_complete(complete))
        for point_count in (0, 2):
            with self.subTest(point_count=point_count):
                self.assertFalse(MODULE.ScenarioDriver._rule_chain_complete(
                    {**complete, "propertyPointCount": point_count}))

    def test_rule_degradation_attribution_closes_quota_and_processed_offset(self):
        """点位为零时必须保留三项 quota 原始值和目标 processed committed 推导。"""
        state = {name: MODULE.uuid7() for name in (
            "messageId", "tenantId", "projectId", "deviceId", "ruleId", "versionId")}
        quota_rows = (
            "TIME_SERIES_POINT|1000000|0|8000|12000\n"
            "UPLINK_BYTES|1073741824|32|8000|12000\n"
            "UPLINK_MESSAGE|1000000|1|8000|12000")
        driver = MODULE.ScenarioDriver(
            Path("/tmp/c4b-f24-test"), "RW-01", "c4b-019d2c587c6d")
        group = {"group": MODULE.PROCESSED_GROUP, "topic": MODULE.PROCESSED_TOPIC,
                 "state": "Stable", "members": 1, "totalLag": 0,
                 "partitions": [{"partition": index, "currentOffset": 8 if index == 3 else 0,
                                 "logStartOffset": 0, "logEndOffset": 8 if index == 3 else 0,
                                 "lag": 0, "memberId": "member-1"}
                                for index in range(12)]}
        with patch.object(driver, "sql", return_value=quota_rows), \
                patch.object(driver, "kafka_record_locations",
                             return_value=[{"partition": 3, "offset": 7}]), \
                patch.object(driver, "kafka_group_snapshot", return_value=group):
            evidence = driver._rule_degradation_attribution(state)
        self.assertTrue(evidence["complete"])
        self.assertFalse(evidence["historicalStorageDegraded"])
        self.assertTrue(evidence["processed"]["targetOffsetsCommitted"])
        self.assertEqual({"NORMAL"}, {item["status"] for item in evidence["quotaDecisions"]})

    def test_notification_metrics_bind_raw_samples_to_current_sut_identity(self):
        """当前进程的原始 actuator 样本必须保留已注册与懒注册缺席的区别。"""
        identity = {"pid": 101, "created": "process-start",
                    "commandSha256": "a" * 64, "jarSha256": "b" * 64}
        exposition = ("thingslink_kafka_consumer_result_total{group=\"rule-notification\","
                      "topic=\"tc.rule.notification\",result=\"success\"} 2.0\n")
        response = MagicMock()
        response.__enter__.return_value.read.return_value = exposition.encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sut-process.json").write_text(json.dumps(identity), encoding="utf-8")
            driver = MODULE.ScenarioDriver(
                root, "DB-01", "c4b-019d2c587c6d",
                sut_metrics_url="http://127.0.0.1:47012/actuator/prometheus")
            with patch.object(MODULE.urllib.request, "urlopen", return_value=response):
                sample = driver.notification_metrics()
        self.assertEqual(identity, sample["processIdentity"])
        self.assertTrue(sample["results"]["success"]["present"])
        self.assertEqual(2.0, sample["results"]["success"]["value"])
        self.assertEqual({"present": False, "value": None, "rawSamples": []},
                         sample["results"]["failure"])

    def test_notification_metrics_reject_process_change_during_request(self):
        """metrics 请求跨越 SUT 替换时必须失败，不能把两个进程的 Counter 拼成差值。"""
        first = {"pid": 101, "created": "first", "commandSha256": "a" * 64,
                 "jarSha256": "b" * 64}
        second = {**first, "pid": 102, "created": "second"}
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b""
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(
                Path(directory), "DB-01", "c4b-019d2c587c6d",
                sut_metrics_url="http://127.0.0.1:47012/actuator/prometheus")
            with patch.object(driver, "sut_process_identity", side_effect=[first, second]), \
                    patch.object(MODULE.urllib.request, "urlopen", return_value=response), \
                    self.assertRaisesRegex(MODULE.DriverError, "进程身份发生变化"):
                driver.notification_metrics()

    def test_notification_metrics_reject_duplicate_current_process_series(self):
        """同一结果出现多条序列时不得求和掩盖标签漂移。"""
        line = ("thingslink_kafka_consumer_result_total{group=\"rule-notification\","
                "topic=\"tc.rule.notification\",result=\"success\"} 1.0")
        with self.assertRaisesRegex(MODULE.DriverError, "重复通知指标序列"):
            MODULE.ScenarioDriver.parse_notification_metric(line + "\n" + line, "success")

    def test_notification_metrics_reject_non_loopback_endpoint(self):
        """资格计划不得借指标采样访问本轮 SUT 以外的地址。"""
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(
                Path(directory), "DB-01", "c4b-019d2c587c6d",
                sut_metrics_url="http://example.com/actuator/prometheus")
            with self.assertRaisesRegex(MODULE.DriverError, "本轮本机 actuator URL"):
                driver.notification_metrics()

    def test_normalized_metrics_reads_current_sut_identity_and_exact_labels(self):
        """normalized 指标必须直读当前 SUT，并只接受稳定 group/topic 标签。"""
        identity = {"pid": 201, "created": "db01-process",
                    "commandSha256": "a" * 64, "jarSha256": "b" * 64}
        exposition = ('thingslink_kafka_consumer_result_total{group="ingestion-normalized",'
                      'topic="tc.device.uplink.normalized",result="failure"} 3.0\n')
        response = MagicMock()
        response.__enter__.return_value.read.return_value = exposition.encode("utf-8")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sut-process.json").write_text(json.dumps(identity), encoding="utf-8")
            driver = MODULE.ScenarioDriver(
                root, "DB-01", "c4b-019d2c587c6d",
                sut_metrics_url="http://127.0.0.1:47012/actuator/prometheus")
            with patch.object(MODULE.urllib.request, "urlopen", return_value=response):
                sample = driver.normalized_metrics()
        self.assertEqual(identity, sample["processIdentity"])
        self.assertFalse(sample["results"]["success"]["present"])
        self.assertEqual(3.0, sample["results"]["failure"]["value"])

    def test_rw04_waits_for_current_restarted_sut_replay_sample(self):
        """业务事实先闭合时仍须等待当前重启进程的 replayed 原始样本。"""
        state = {name: MODULE.uuid7() for name in (
            "messageId", "projectId", "deviceId", "ruleId", "versionId")}
        state.update({"sourceSha256": "a" * 64, "continuationBefore": 1})
        complete = {
            "receiptCount": 1, "receiptStatus": "COMPLETED", "scriptSuccessCount": 1,
            "processedInboxCount": 1, "propertyPointCount": 1,
            "notificationOutboxCount": 1, "sideEffectCount": 1,
            "qualifiedActionVersionCount": 1, "shadowTemperaturePresentCount": 1,
            "messageLogCount": 1, "capturedAt": "2026-08-30T00:00:00Z",
        }
        identity = {"pid": 104, "created": "rw04-restart",
                    "commandSha256": "b" * 64, "jarSha256": "c" * 64}
        absent = {"schemaVersion": 1, "source": "SUT_ACTUATOR",
                  "endpointPath": "/actuator/prometheus", "processIdentity": identity,
                  "metricName": MODULE.RULE_REPLAY_METRIC_NAME,
                  "labels": {"stage": "engine", "result": "replayed"},
                  "sample": {"present": False, "value": None, "rawSamples": []},
                  "capturedAt": "2026-08-30T00:00:00Z"}
        present = {**absent,
                   "sample": {"present": True, "value": 1.0,
                              "rawSamples": [
                                  'thingslink_rule_execution_seconds_count{stage="engine",'
                                  'result="replayed"} 1.0']},
                   "capturedAt": "2026-08-30T00:00:01Z"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "RW-04", "c4b-019d2c587c6d")
            with patch.object(driver, "_rule_chain_snapshot",
                              side_effect=[complete, complete]), \
                    patch.object(driver, "rule_replay_metric",
                                 side_effect=[absent, present]), \
                    patch.object(driver, "kafka_count", return_value=1), \
                    patch.object(MODULE.time, "sleep"):
                facts = driver.rule_after(state)
            evidence = json.loads((root / "attribution" / "RW-04" /
                                   "rule-replay-metric.json").read_text(encoding="utf-8"))
        self.assertTrue(facts["replayed"])
        self.assertEqual(0, facts["continuationAddedAfterRestart"])
        self.assertEqual(2, evidence["attempts"])
        self.assertEqual("PASS", evidence["verdict"])
        self.assertEqual(identity, evidence["processIdentity"])

    def test_rule_replay_metric_rejects_duplicate_or_invalid_series(self):
        """当前进程的 replayed 序列必须唯一且为非负有限值。"""
        line = ('thingslink_rule_execution_seconds_count{result="replayed",'
                'stage="engine"} 1.0')
        with self.assertRaisesRegex(MODULE.DriverError, "重复 RW-04"):
            MODULE.ScenarioDriver.parse_rule_replay_metric(line + "\n" + line)
        with self.assertRaisesRegex(MODULE.DriverError, "非负有限数"):
            MODULE.ScenarioDriver.parse_rule_replay_metric(line.rsplit(" ", 1)[0] + " -1")

    def test_unknown_phase_fails_closed_before_external_command(self):
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "OB-01", "c4b-019d2c587c6d")
            with self.assertRaises(MODULE.DriverError):
                driver.dispatch("guess")

    def test_outbox_workload_uses_owner_psql_without_shell(self):
        calls = []

        def fake_execute(command, **kwargs):
            calls.append((command, kwargs))
            return ""

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "OB-03", "c4b-019d2c587c6d",
                                           fake_execute)
            state = {name: MODULE.uuid7() for name in (
                "targetId", "followerId", "tenantId", "projectId", "ruleId", "versionId",
                "messageId", "deviceId")}
            driver.insert_outbox_pair(state)
        command, arguments = calls[0]
        self.assertEqual(["docker", "exec", "-i", "c4b-019d2c587c6d-postgres"], command[:4])
        self.assertNotIn("shell", arguments)
        self.assertIn("ON_ERROR_STOP=1", command)
        sql = arguments["input_text"]
        self.assertIn("event_type,destination_topic,partition_key", sql)
        self.assertEqual(2, sql.count("'tc.rule.notification'"))
        self.assertEqual(2, sql.count(f"'tc.rule.notification','{state['targetId']}'"))
        self.assertEqual(0, sql.count(f"'tc.rule.notification','{state['followerId']}'"))
        self.assertNotIn(f"'tc.rule.notification','{state['deviceId']}'", sql)

    def test_nested_command_failure_keeps_safe_bounded_diagnostics(self):
        """psql 直接错误必须可见，但 DETAIL 载荷、凭据、stdin 和超长输出不得外泄。"""
        completed = subprocess.CompletedProcess(
            ["docker", "exec"], 3, "x" * 6000,
            "ERROR: null value in column destination_topic\n"
            "DETAIL: failing row contains payload password=top-secret\n"
            "authorization=Bearer-secret")
        with patch.object(MODULE.subprocess, "run", return_value=completed):
            with self.assertRaises(MODULE.DriverError) as raised:
                MODULE.run(["docker", "exec", "container"],
                           input_text="INSERT payload password=stdin-secret")

        message = str(raised.exception)
        self.assertIn("rc=3 executable=docker exec", message)
        self.assertIn("ERROR: null value in column destination_topic", message)
        self.assertIn("[已省略可能包含业务载荷的数据库详情]", message)
        self.assertIn("[UTF-8 字节限长]", message)
        self.assertNotIn("failing row", message)
        self.assertNotIn("top-secret", message)
        self.assertNotIn("stdin-secret", message)
        self.assertNotIn("Bearer-secret", message)

    def test_kafka_count_skips_empty_partitions_and_reads_frozen_nonempty_ranges(self):
        """空分区不调用 consume，混合分区只读取 describe 冻结的非空范围。"""
        calls = []
        described = json.dumps([{
            "summary": {"name": "tc.rule.notification", "error": ""},
            "partitions": [
                {"partition": 0, "log_start_offset": 0, "high_watermark": 0},
                {"partition": 1, "log_start_offset": 5, "high_watermark": 7},
                {"partition": 2, "log_start_offset": 9, "high_watermark": 9},
            ],
        }])

        def fake_execute(command, **_kwargs):
            calls.append(command)
            if "describe" in command:
                return described
            return "other\tpayload\ntarget-id\tpayload"

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            self.assertEqual(1, driver.kafka_count("tc.rule.notification", "target-id"))

        self.assertEqual(2, len(calls))
        self.assertIn("describe", calls[0])
        self.assertEqual(["-X", "brokers=localhost:9092"], calls[0][-2:])
        self.assertEqual("1", calls[1][calls[1].index("-p") + 1])
        self.assertEqual("5:7", calls[1][calls[1].index("-o") + 1])
        self.assertEqual(["-X", "brokers=localhost:9092"], calls[1][-2:])

    def test_kafka_count_returns_zero_when_every_partition_is_empty(self):
        """全空 Topic 的零来自已解析水位，不能通过忽略 consume 错误伪造。"""
        calls = []
        described = json.dumps([{
            "summary": {"name": "tc.rule.notification", "error": ""},
            "partitions": [
                {"partition": 0, "log_start_offset": 0, "high_watermark": 0},
                {"partition": 1, "log_start_offset": 4, "high_watermark": 4},
            ],
        }])

        def fake_execute(command, **_kwargs):
            calls.append(command)
            return described

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            self.assertEqual(0, driver.kafka_count("tc.rule.notification", "target-id"))
        self.assertEqual(1, len(calls))

    def test_kafka_record_locations_persists_only_partition_and_offset(self):
        """定位过程可读取 key/value 匹配，但机器证据只能返回非敏感位置。"""
        described = json.dumps([{
            "summary": {"name": "tc.rule.notification", "error": ""},
            "partitions": [{"partition": 0, "log_start_offset": 3, "high_watermark": 5}],
        }])

        def fake_execute(command, **_kwargs):
            if "describe" in command:
                return described
            return "0\t3\tother\tpayload\n0\t4\ttarget-id\tsecret-payload"

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            value = driver.kafka_record_locations("tc.rule.notification", "target-id")
        self.assertEqual([{"partition": 0, "offset": 4}], value)
        self.assertNotIn("secret-payload", json.dumps(value))

    def test_notification_identity_proves_same_lane_records_without_payload(self):
        """目标和后继都须证明 key=payload eventId=投递 ID，证据不保存 payload。"""
        target_id = MODULE.uuid7()
        described = json.dumps([{
            "summary": {"name": MODULE.NOTIFICATION_TOPIC, "error": ""},
            "partitions": [{"partition": 0, "log_start_offset": 0, "high_watermark": 2}],
        }])
        payloads = [json.dumps({"eventId": target_id, "body": "secret-target"}),
                    json.dumps({"eventId": target_id, "body": "secret-follower"})]

        def fake_execute(command, **_kwargs):
            if "describe" in command:
                return described
            return (f"0\t0\t{target_id}\t{payloads[0]}\n"
                    f"0\t1\t{target_id}\t{payloads[1]}")

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            value = driver.kafka_notification_identity(target_id)
        self.assertEqual(target_id, value["eventId"])
        self.assertEqual(2, len(value["records"]))
        self.assertTrue(value["records"][0]["keyMatchesPayloadEventId"])
        serialized = json.dumps(value)
        self.assertNotIn("secret-target", serialized)
        self.assertNotIn("secret-follower", serialized)

    def test_notification_identity_rejects_payload_match_with_wrong_key(self):
        """复现 attempt 10：payload ID 命中但 key=deviceId 时必须 fail-closed。"""
        target_id = MODULE.uuid7()
        device_id = MODULE.uuid7()
        described = json.dumps([{
            "summary": {"name": MODULE.NOTIFICATION_TOPIC, "error": ""},
            "partitions": [{"partition": 3, "log_start_offset": 0, "high_watermark": 2}],
        }])

        def fake_execute(command, **_kwargs):
            if "describe" in command:
                return described
            return (f"3\t0\t{device_id}\t{json.dumps({'eventId': target_id})}\n"
                    f"3\t1\t{device_id}\t{json.dumps({'eventId': target_id})}")

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            with self.assertRaisesRegex(MODULE.DriverError, "身份不一致"):
                driver.kafka_notification_identity(target_id)

    def test_outbox_identity_qualification_closes_both_rows_before_fault(self):
        """停库前必须从 Outbox 权威行证明两条 key/payload 身份，且只落摘要。"""
        target_id = MODULE.uuid7()
        follower_id = MODULE.uuid7()
        state = {"targetId": target_id, "followerId": follower_id}
        rows = f"{target_id}|{target_id}|{target_id}\n{follower_id}|{target_id}|{target_id}"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            with patch.object(driver, "sql", return_value=rows):
                reference = driver.persist_notification_outbox_identity(state)
            value = json.loads((root / reference["path"]).read_text(encoding="utf-8"))
        self.assertEqual({"target", "follower"}, set(value["identities"]))
        self.assertTrue(value["identities"]["target"]["identityMatch"])
        self.assertEqual(
            {"outboxId", "deliveryEventId", "partitionKeySha256",
             "payloadEventIdSha256", "identityMatch"},
            set(value["identities"]["target"]))

    def test_outbox_identity_query_casts_authoritative_text_payload(self):
        """权威列是 text，资格 SQL 必须显式转 jsonb，不能再次使用错误的裸运算符。"""
        target_id = MODULE.uuid7()
        follower_id = MODULE.uuid7()
        statements = []

        def fake_sql(statement, _timeout=30):
            statements.append(statement)
            return f"{target_id}|{target_id}|{target_id}\n{follower_id}|{target_id}|{target_id}"

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d")
            with patch.object(driver, "sql", side_effect=fake_sql):
                driver.persist_notification_outbox_identity(
                    {"targetId": target_id, "followerId": follower_id})

        self.assertEqual(1, len(statements))
        self.assertIn("(payload::jsonb)->>'eventId'", statements[0])
        self.assertNotIn("coalesce(payload->>", statements[0])

    def test_outbox_migration_freezes_text_payload_with_json_object_check(self):
        """探针类型假设必须由权威迁移约束，而不是由测试 fixture 自行臆测。"""
        migration = (Path(__file__).parents[3] / "things-link-support" / "src" / "main" /
                     "resources" / "db" / "migration" / "support" /
                     "V20260808_1200__transactional_outbox.sql").read_text(encoding="utf-8")
        self.assertIn("payload         text          NOT NULL", migration)
        self.assertIn("jsonb_typeof(payload::jsonb) = 'object'", migration)

    def test_outbox_identity_rejects_invalid_text_json_without_writing_evidence(self):
        """真实列若违反 JSON 可转换前提，数据库类型错误必须透传且不得留下伪身份文件。"""
        target_id = MODULE.uuid7()
        follower_id = MODULE.uuid7()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            with patch.object(driver, "sql", side_effect=MODULE.DriverError(
                    "invalid input syntax for type json")), \
                    self.assertRaisesRegex(MODULE.DriverError, "invalid input syntax"):
                driver.persist_notification_outbox_identity(
                    {"targetId": target_id, "followerId": follower_id})
            self.assertFalse((root / "identity" / "DB-01" /
                              "notification-outbox.json").exists())

    def test_outbox_identity_qualification_rejects_attempt_ten_key(self):
        """Outbox 中 key=deviceId 时必须在故障动作前拒绝，不能再消耗正式停库窗口。"""
        target_id = MODULE.uuid7()
        follower_id = MODULE.uuid7()
        device_id = MODULE.uuid7()
        rows = f"{target_id}|{device_id}|{target_id}\n{follower_id}|{device_id}|{target_id}"
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d")
            with patch.object(driver, "sql", return_value=rows), \
                    self.assertRaisesRegex(MODULE.DriverError, "身份不一致"):
                driver.persist_notification_outbox_identity(
                    {"targetId": target_id, "followerId": follower_id})

    def test_before_probe_persists_identity_before_database_fault(self):
        """DB-01 beforeProbe 必须实际接入权威行资格，而不是留下未调用的辅助函数。"""
        target_id = MODULE.uuid7()
        follower_id = MODULE.uuid7()
        state = {"targetId": target_id, "followerId": follower_id}
        identity_rows = (
            f"{target_id}|{target_id}|{target_id}\n"
            f"{follower_id}|{target_id}|{target_id}")

        def fake_sql(statement, _timeout=30):
            if "status||" in statement:
                return "CLAIMED|" + MODULE.uuid7() + "|2000000000"
            if "(payload::jsonb)->>'eventId'" in statement:
                return identity_rows
            raise AssertionError(statement)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            driver.write_state(state)
            with patch.object(driver, "sql", side_effect=fake_sql), \
                    patch.object(driver, "database_volume_id", return_value="pg-volume"), \
                    patch.object(driver, "notification_metrics",
                                 return_value={"success": 0, "failure": 0}), \
                    patch.object(driver, "normalized_metrics",
                                 return_value={"success": 0, "failure": 0}), \
                    patch.object(driver, "kafka_total_records", return_value=0), \
                    patch.object(driver, "kafka_count", return_value=0):
                facts = driver.before_probe()
            reference = facts["notificationOutboxIdentityEvidence"]
            persisted_state = driver.read_state()
            evidence_exists = (root / reference["path"]).is_file()
        self.assertEqual(reference, persisted_state["notificationOutboxIdentityEvidence"])
        self.assertTrue(evidence_exists)

    def test_ob04_before_probe_freezes_published_state_before_kill(self):
        """OB-04 checkpoint 后必须先保存 target 已发布事实和时点，再允许编排器强杀。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "OB-04", "c4b-019d2c587c6d")
            driver.write_state({"targetId": "target", "followerId": "follower"})

            def fake_sql(statement, _timeout=30):
                if "status||" in statement:
                    return "PUBLISHED||"
                if "published_at AT TIME ZONE" in statement:
                    return "2026-08-28T00:00:01.000000Z"
                raise AssertionError(statement)

            with patch.object(driver, "sql", side_effect=fake_sql), \
                    patch.object(driver, "kafka_count", return_value=1):
                facts = driver.before_probe()

            state = driver.read_state()
            self.assertEqual(("PUBLISHED", 1),
                             (facts["targetStatusBefore"], facts["targetKafkaRecordsBefore"]))
            self.assertEqual("2026-08-28T00:00:01.000000Z",
                             state["targetPublishedAtBefore"])

    def test_notification_group_snapshot_requires_stable_six_partitions(self):
        """规则通知组必须形成真实 Stable 六分区 committed offset 快照。"""
        rows = "\n".join(
            f"tc.rule.notification {partition} 1 0 1 0 member-1 client host"
            for partition in range(6))
        output = ("STATE Stable\nMEMBERS 1\nTOTAL-LAG 0\n"
                  "TOPIC PARTITION CURRENT-OFFSET LOG-START-OFFSET LOG-END-OFFSET LAG "
                  "MEMBER-ID CLIENT-ID HOST\n" + rows)
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           lambda _command, **_kwargs: output)
            snapshot = driver.notification_group_snapshot()
            self.assertEqual(list(range(6)), [item["partition"]
                                              for item in snapshot["partitions"]])
            driver.execute = lambda _command, **_kwargs: output.rsplit("\n", 1)[0]
            with self.assertRaisesRegex(MODULE.DriverError, "六分区"):
                driver.notification_group_snapshot()

    def test_normalized_group_snapshot_requires_stable_twelve_partitions(self):
        """normalized 归因必须读取真实 Stable 十二分区 offset，少一分区即失败。"""
        rows = "\n".join(
            f"tc.device.uplink.normalized {partition} 1 0 1 0 member-1 client host"
            for partition in range(12))
        output = ("STATE Stable\nMEMBERS 4\nTOTAL-LAG 0\n"
                  "TOPIC PARTITION CURRENT-OFFSET LOG-START-OFFSET LOG-END-OFFSET LAG "
                  "MEMBER-ID CLIENT-ID HOST\n" + rows)
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           lambda _command, **_kwargs: output)
            snapshot = driver.normalized_group_snapshot()
            self.assertEqual(list(range(12)), [item["partition"]
                                               for item in snapshot["partitions"]])
            driver.execute = lambda _command, **_kwargs: output.rsplit("\n", 1)[0]
            with self.assertRaisesRegex(MODULE.DriverError, "全分区"):
                driver.normalized_group_snapshot()

    def test_rw05_workload_selects_partitions_from_three_distinct_readiness_members(self):
        """RW-05 必须按真实 assignment 跨成员投递，不能再把 0/1/2 串行交给同一实例。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readiness = root / "readiness" / "RW-05-fixture.json"
            readiness.parent.mkdir(parents=True)
            assignments = [
                {"partition": partition, "memberId": f"member-{partition // 3}"}
                for partition in range(12)
            ]
            readiness.write_text(json.dumps({
                "schemaVersion": 1, "scenario": "RW-05", "purpose": "fixture",
                "verdict": "PASS", "groups": {MODULE.NORMALIZED_GROUP: {"parsed": {
                    "topic": MODULE.NORMALIZED_TOPIC, "assignor": "range", "ready": True,
                    "partitionAssignments": assignments,
                }}},
            }), encoding="utf-8")
            driver = MODULE.ScenarioDriver(root, "RW-05", "c4b-019d2c587c6d")
            driver.write_state({
                "messageId": "message-0",
                "deviceIds": ["device-0", "device-1", "device-2"],
            })
            published = []

            def publish(message_id, temperature, device_id=None, partition=None):
                published.append((message_id, temperature, device_id, partition))

            with patch.object(driver, "publish_uplink", side_effect=publish):
                driver.workload_before()

            state = driver.read_state()
            self.assertEqual([0, 3, 6], [item[3] for item in published])
            self.assertEqual(3, state["rw05SaturationQualification"]["distinctMemberCount"])
            evidence = state["rw05SaturationQualification"]["evidence"]
            self.assertTrue((root / evidence["path"]).is_file())

    def test_rw05_workload_rejects_assignment_without_three_distinct_members(self):
        """分区虽完整但成员不足时必须在发布任何工作负载前封闭失败。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            readiness = root / "readiness" / "RW-05-fixture.json"
            readiness.parent.mkdir(parents=True)
            readiness.write_text(json.dumps({
                "schemaVersion": 1, "scenario": "RW-05", "purpose": "fixture",
                "verdict": "PASS", "groups": {MODULE.NORMALIZED_GROUP: {"parsed": {
                    "topic": MODULE.NORMALIZED_TOPIC, "assignor": "range", "ready": True,
                    "partitionAssignments": [
                        {"partition": partition, "memberId": "member-one"}
                        for partition in range(12)
                    ],
                }}},
            }), encoding="utf-8")
            driver = MODULE.ScenarioDriver(root, "RW-05", "c4b-019d2c587c6d")
            driver.write_state({
                "messageId": "message-0",
                "deviceIds": ["device-0", "device-1", "device-2"],
            })
            with patch.object(driver, "publish_uplink") as publish, \
                    self.assertRaisesRegex(MODULE.DriverError, "不足三个独立消费成员"):
                driver.workload_before()
            publish.assert_not_called()

    def test_partition_ranges_reject_identity_error_and_watermark_drift(self):
        """Topic 不匹配、broker 错误、重复分区和倒置水位均须 fail-closed。"""
        invalid_values = [
            "not-json",
            json.dumps([]),
            json.dumps([{"summary": {"name": "other", "error": ""}, "partitions": [{}]}]),
            json.dumps([{"summary": {"name": "topic", "error": "UNKNOWN_TOPIC"},
                         "partitions": [{"partition": 0, "log_start_offset": 0,
                                         "high_watermark": 0}]}]),
            json.dumps([{"summary": {"name": "topic", "error": ""}, "partitions": [
                {"partition": 0, "log_start_offset": 2, "high_watermark": 1}]}]),
            json.dumps([{"summary": {"name": "topic", "error": ""}, "partitions": [
                {"partition": 0, "log_start_offset": 0, "high_watermark": 0},
                {"partition": 0, "log_start_offset": 0, "high_watermark": 0}]}]),
        ]
        for value in invalid_values:
            with self.subTest(value=value), self.assertRaises(MODULE.DriverError):
                MODULE.ScenarioDriver.parse_partition_ranges(value, "topic")

    def test_kafka_count_rejects_incomplete_partition_read(self):
        """describe 声明两条但 consume 只返回一条时不能产生部分计数。"""
        described = json.dumps([{
            "summary": {"name": "topic", "error": ""},
            "partitions": [{"partition": 0, "log_start_offset": 3, "high_watermark": 5}],
        }])

        def fake_execute(command, **_kwargs):
            return described if "describe" in command else "target-id\tpayload"

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            with self.assertRaises(MODULE.DriverError):
                driver.kafka_count("topic", "target-id")

    def test_kafka_count_propagates_broker_command_failure(self):
        """Topic 不存在或 broker 命令失败不能被空分区分支吞成零。"""
        def fake_execute(_command, **_kwargs):
            raise MODULE.DriverError("UNKNOWN_TOPIC_OR_PARTITION")

        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           fake_execute)
            with self.assertRaisesRegex(MODULE.DriverError, "UNKNOWN_TOPIC_OR_PARTITION"):
                driver.kafka_count("missing-topic", "target-id")

    def test_database_volume_requires_exact_authoritative_named_mount(self):
        """卷身份必须来自 Compose 实际数据目录，空值、多匹配与 bind mount 均拒绝。"""
        valid = json.dumps([{"Type": "volume", "Name": "c4b-pg-data",
                             "Destination": MODULE.POSTGRES_DATA_DESTINATION}])
        with tempfile.TemporaryDirectory() as directory:
            driver = MODULE.ScenarioDriver(Path(directory), "DB-01", "c4b-019d2c587c6d",
                                           lambda _command, **_kwargs: valid)
            self.assertEqual("c4b-pg-data", driver.database_volume_id())

        invalid = [
            "not-json",
            json.dumps([]),
            json.dumps([{"Type": "volume", "Name": "wrong",
                         "Destination": "/var/lib/postgresql/data"}]),
            json.dumps([{"Type": "bind", "Name": "",
                         "Destination": MODULE.POSTGRES_DATA_DESTINATION}]),
            json.dumps([{"Type": "volume", "Name": "one",
                         "Destination": MODULE.POSTGRES_DATA_DESTINATION},
                        {"Type": "volume", "Name": "two",
                         "Destination": MODULE.POSTGRES_DATA_DESTINATION}]),
        ]
        for mounts in invalid:
            with self.subTest(mounts=mounts), tempfile.TemporaryDirectory() as directory:
                driver = MODULE.ScenarioDriver(
                    Path(directory), "DB-01", "c4b-019d2c587c6d",
                    lambda _command, value=mounts, **_kwargs: value)
                with self.assertRaises(MODULE.DriverError):
                    driver.database_volume_id()

    def test_recovery_step_failure_persists_first_predicate_and_last_observation(self):
        """共享预算耗尽前至少探测一次，失败文件必须指出具体 step 与最后观测。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            with self.assertRaisesRegex(MODULE.DriverError, "outbox-terminal"):
                driver._wait_recovery_step(
                    "outbox-terminal", "business-recovery", 180,
                    MODULE.time.monotonic(), 2, lambda: 1)
            evidence = json.loads((root / "recovery" / "DB-01" /
                                   "outbox-terminal.json").read_text(encoding="utf-8"))
            self.assertEqual(("FAIL", 1, 1),
                             (evidence["verdict"], evidence["attempts"],
                              evidence["lastObserved"]))
            self.assertEqual(0, evidence["remainingBudgetMillisAtStart"])
            self.assertEqual("DriverError", evidence["exceptionChain"][0]["type"])

    def test_recovery_step_rejects_value_arriving_after_shared_deadline(self):
        """期限后才读到目标值仍是 FAIL，不能用阻塞命令绕过 180 秒恢复上限。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            clock = iter([10.0, 11.1, 11.1, 11.1])
            with patch.object(MODULE.time, "monotonic", side_effect=lambda: next(clock)):
                with self.assertRaisesRegex(MODULE.DriverError, "outbox-terminal"):
                    driver._wait_recovery_step(
                        "outbox-terminal", "business-recovery", 180, 11.0, 2, lambda: 2)
            evidence = json.loads((root / "recovery" / "DB-01" /
                                   "outbox-terminal.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", evidence["verdict"])
            self.assertEqual(2, evidence["lastObserved"])

    def test_database_after_probe_closes_all_six_recovery_segments(self):
        """完整 DB-01 正向路径必须返回六个摘要引用并保留前后卷名。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            state = {"targetId": "target", "followerId": "follower",
                     "messageId": "message", "databaseVolumeBefore": "pg-volume",
                     "alertFiring": {"result": [{"metric": {"alertstate": "firing"}}]}}

            def fake_sql(statement, _timeout=30):
                if "status='PUBLISHED'" in statement:
                    return "2"
                if "rule_notification_delivery" in statement:
                    return "1"
                if "sys_inbox_message" in statement:
                    return "1"
                if "json_build_object" in statement and "target.published_at" in statement:
                    return json.dumps({
                        "target": {"id": "target", "status": "PUBLISHED", "attemptCount": 1,
                                   "createdAt": "2026-08-29T00:00:00Z",
                                   "publishedAt": "2026-08-29T00:01:00Z", "leasePresent": False},
                        "follower": {"id": "follower", "status": "PUBLISHED", "attemptCount": 0,
                                     "createdAt": "2026-08-29T00:00:01Z",
                                     "publishedAt": "2026-08-29T00:01:01Z", "leasePresent": False},
                        "laneOrderPreserved": True,
                    })
                if "published_at <=" in statement:
                    return "t"
                raise AssertionError(statement)

            with patch.object(driver, "sql", side_effect=fake_sql), \
                    patch.object(driver, "kafka_count", return_value=1), \
                    patch.object(driver, "database_volume_id", return_value="pg-volume"), \
                    patch.object(driver, "persist_notification_attribution"), \
                    patch.object(driver, "persist_normalized_attribution"), \
                    patch.object(driver, "_alert_probe_resolved",
                                 return_value={"queriedAt": "2026-08-28T00:00:00Z", "result": []}):
                facts = driver.outbox_after(state)

            self.assertEqual(set(MODULE.DATABASE_RECOVERY_STEPS),
                             set(facts["recoveryEvidence"]))
            self.assertEqual(("pg-volume", "pg-volume", True),
                             (facts["databaseVolumeBefore"], facts["databaseVolumeAfter"],
                              facts["sameDatabaseVolume"]))
            for reference in facts["recoveryEvidence"].values():
                self.assertTrue((root / reference["path"]).is_file())

    def test_ob01_lane_attribution_hashes_tokens_and_keeps_exact_transitions(self):
        """OB-01 只落租约摘要，但必须保留两行的领取与发布序列。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "OB-01", "c4b-019d2c587c6d")
            state = {"targetId": "target", "followerId": "follower"}
            integrity = {
                "targetState": {"id": "target", "status": "PUBLISHED"},
                "followerState": {"id": "follower", "status": "PUBLISHED"},
                "laneOrderPreserved": True,
            }
            rows = "\n".join((
                "1|target||lease-a|PENDING|PENDING|2026-08-29T00:00:00Z|"
                "2026-08-29T00:00:30Z||2026-08-29T00:00:00Z",
                "2|target|lease-a||PENDING|PUBLISHED|2026-08-29T00:00:00Z||"
                "2026-08-29T00:00:01Z|2026-08-29T00:00:01Z",
                "3|follower||lease-b|PENDING|PENDING|2026-08-29T00:00:01Z|"
                "2026-08-29T00:00:31Z||2026-08-29T00:00:01Z",
                "4|follower|lease-b||PENDING|PUBLISHED|2026-08-29T00:00:01Z||"
                "2026-08-29T00:00:02Z|2026-08-29T00:00:02Z",
            ))
            with patch.object(driver, "sql", return_value=rows):
                reference = driver.persist_outbox_lane_attribution(state, integrity)

            value = json.loads((root / reference["path"]).read_text(encoding="utf-8"))
            self.assertEqual([1, 2, 3, 4],
                             [item["sequence"] for item in value["transitions"]])
            self.assertNotIn("lease-a", json.dumps(value))
            self.assertEqual(hashlib.sha256(b"lease-a").hexdigest(),
                             value["transitions"][0]["newLeaseTokenSha256"])

    def test_ob02_lease_takeover_attribution_hashes_tokens_and_closes_timing(self):
        """OB-02 只落原/新 token 摘要，并从数据库观测时间推导接管时长。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "OB-02", "c4b-019d2c587c6d")
            old_digest = hashlib.sha256(b"lease-old").hexdigest()
            state = {"targetId": "target", "leaseDigestBefore": old_digest,
                     "leaseUntilEpoch": 1787875230}
            integrity = {"targetState": {"id": "target", "status": "PUBLISHED",
                                           "leasePresent": False}}
            rows = "\n".join((
                "lease-old|2026-08-28T00:00:00.000000Z",
                "lease-new|2026-08-28T00:00:30.000000Z",
            ))
            with patch.object(driver, "sql", return_value=rows):
                facts, reference = driver.persist_lease_takeover_attribution(state, integrity)

            value = json.loads((root / reference["path"]).read_text(encoding="utf-8"))
            self.assertEqual({"leaseClaimCount": 2, "leaseTakeoverSeconds": 30,
                              "leaseTokenChanged": True}, facts)
            self.assertNotIn("lease-old", json.dumps(value))
            self.assertNotIn("lease-new", json.dumps(value))
            self.assertEqual(old_digest, value["claims"][0]["leaseTokenSha256"])
            self.assertEqual(hashlib.sha256(b"lease-new").hexdigest(),
                             value["claims"][1]["leaseTokenSha256"])

    def test_ob04_terminal_attribution_proves_one_claim_without_lane_record_inference(self):
        """OB-04 以 target 唯一领取和发布时点不变证明未重领，lane 总数不参与推导。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "OB-04", "c4b-019d2c587c6d")
            state = {"targetId": "target", "targetStatusBefore": "PUBLISHED",
                     "targetKafkaRecordsBefore": 1,
                     "targetPublishedAtBefore": "2026-08-28T00:00:01Z"}
            integrity = {"targetKafkaRecords": 2,
                         "targetState": {"id": "target", "status": "PUBLISHED",
                                         "attemptCount": 0,
                                         "publishedAt": "2026-08-28T00:00:01Z",
                                         "leasePresent": False}}
            with patch.object(driver, "sql", return_value=(
                    "lease-only|2026-08-28T00:00:00.000000Z")):
                facts, reference = driver.persist_terminal_no_reclaim_attribution(
                    state, integrity)

            value = json.loads((root / reference["path"]).read_text(encoding="utf-8"))
            self.assertEqual({"targetLeaseClaimCount": 1,
                              "targetPublishedAtUnchanged": True,
                              "targetReclaimed": False}, facts)
            self.assertEqual(2, integrity["targetKafkaRecords"])
            self.assertNotIn("lease-only", json.dumps(value))
            self.assertEqual(hashlib.sha256(b"lease-only").hexdigest(),
                             value["claims"][0]["leaseTokenSha256"])

    def test_remaining_command_timeout_shrinks_to_group_deadline(self):
        """完整性 SQL/Kafka 子命令不能继续使用固定 30 秒越过业务恢复绝对期限。"""
        with patch.object(MODULE.time, "monotonic", return_value=10.0):
            self.assertEqual(5.0, MODULE.ScenarioDriver._remaining_command_timeout(15.0))
            self.assertEqual(30, MODULE.ScenarioDriver._remaining_command_timeout(50.0))
            with self.assertRaisesRegex(MODULE.DriverError, "预算已耗尽"):
                MODULE.ScenarioDriver._remaining_command_timeout(10.0)

    def test_recovery_identity_step_rejects_volume_drift_after_persisting_evidence(self):
        """恢复后换卷即使数据库健康也必须落 FAIL，而不能只返回同卷布尔值。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            driver = MODULE.ScenarioDriver(root, "DB-01", "c4b-019d2c587c6d")
            with self.assertRaisesRegex(MODULE.DriverError, "database-volume"):
                driver._assert_recovery_step(
                    "database-volume", "identity", 0, MODULE.time.monotonic(),
                    {"same": True, "nonEmpty": True},
                    {"before": "volume-a", "after": "volume-b",
                     "same": False, "nonEmpty": True}, False)
            evidence = json.loads((root / "recovery" / "DB-01" /
                                   "database-volume.json").read_text(encoding="utf-8"))
            self.assertEqual("FAIL", evidence["verdict"])
            self.assertEqual("volume-b", evidence["lastObserved"]["after"])


if __name__ == "__main__":
    unittest.main()
