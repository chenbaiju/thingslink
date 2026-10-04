"""G1-C4a-1 durable handoff 机器裁决合同测试。"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "c4a_handoff_verdict.py"
SPEC = importlib.util.spec_from_file_location("c4a_handoff_verdict", SCRIPT)
VERDICT = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(VERDICT)


class C4aHandoffVerdictTests(unittest.TestCase):
    """锁住四态分类、七场景集合及严格证据解析。"""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.evidence = Path(self.temporary.name)
        self.document = self.valid_document()
        self.rows = self.valid_rows()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def scenario(name: str) -> dict:
        """生成一项集合精确对账的场景事实。"""
        message_id = f"message-{name}"
        fault = name in {"application-stop", "kafka-stop", "database-stop", "emqx-restart"}
        duplicated = [message_id] if name == "ack-ambiguity" else []
        replayed = [message_id] if fault or name == "ack-ambiguity" else []
        return {"name": name, "expectedMessageIds": [message_id],
                "terminalMessageIds": [message_id], "pubAckMessageIds": [message_id],
                "unackedDuringFault": [message_id] if fault else [],
                "recoveredMessageIds": replayed, "duplicatedMessageIds": duplicated,
                "finalFactCounts": {message_id: 1},
                "sessionPresentAfterRecovery": True if name == "emqx-restart" else None,
                "poison": ({"payloadSha256": "f" * 64, "dlqPersisted": True,
                            "mqttAckAfterDlq": True, "subsequentLegalPassed": True}
                           if name == "poison" else None)}

    def valid_document(self) -> dict:
        """生成合法资格输入。"""
        return {"schemaVersion": 1,
                "run": {"runId": "c4a-local-1", "gitCommit": "a" * 40,
                        "startedAt": "2026-08-24T20:00:00+08:00",
                        "finishedAt": "2026-08-24T20:10:00+08:00"},
                "qualification": {"result": "PASS", "isolatedStack": True,
                                  "evidenceComplete": True, "clockSkewMs": 12},
                "configuration": {"emqxVersion": "6.2.3", "durableSessions": True,
                                  "messageRetentionSeconds": 86400,
                                  "sessionExpirySeconds": 172800,
                                  "clientId": "thingslink-uplink-ingress-v1",
                                  "internalTopic": "tc/internal/v1/ingress/uplink",
                                  "httpMessageActions": 0, "ingressOwners": 1,
                                  "clusterNodes": 1, "legacyBridgesAbsent": True,
                                  "lifecycleActionsConnected": 2,
                                  "license": {"type": "community", "deployment": "Development",
                                              "expired": False, "purpose": "INTERNAL_DEVELOPMENT",
                                              "productionEligible": False}},
                "metrics": {"emqxRuleFailed": 0, "emqxRuleDropped": 0,
                            "durableStoreFailures": 0, "diskFreeBytes": 6 * 1024 ** 3,
                            "diskFreePercent": 30},
                "artifacts": {name: "b" * 64 for name in VERDICT.REQUIRED_ARTIFACTS},
                "scenarios": [self.scenario(name) for name in VERDICT.SCENARIOS]}

    @staticmethod
    def valid_rows() -> list[dict]:
        """生成包含重试、确认歧义、Broker 恢复与 poison 后续消息的逐交接行。"""
        specifications = [
            ("normal", "n", ["accepted"], "raw"),
            ("application-stop", "a", ["accepted"], "raw"),
            ("kafka-stop", "k", ["transient_retry", "accepted"], "raw"),
            ("database-stop", "d", ["transient_retry", "accepted"], "command-reply"),
            ("ack-ambiguity", "x", ["accepted", "accepted"], "raw"),
            ("emqx-restart", "e", ["transient_retry", "accepted"], "raw"),
            ("poison", "p", ["quarantined"], "poison"),
            ("poison", "p-legal", ["accepted"], "raw"),
        ]
        rows: list[dict] = []
        for scenario, handoff, results, dispatch in specifications:
            for attempt, result in enumerate(results, 1):
                rows.append({"sequence": len(rows) + 1, "scenario": scenario,
                             "handoffId": handoff,
                             "businessMessageId": f"message-{scenario}" if handoff != "p" else None,
                             "payloadSha256": "c" * 64,
                             "dispatchType": dispatch, "attempt": attempt, "result": result,
                             "wallClock": "2026-08-24T20:00:00+08:00"})
        return rows

    def write(self, raw_input: str | None = None) -> None:
        """写入原子裁决所需两项输入。"""
        (self.evidence / "qualification-input.json").write_text(
            raw_input if raw_input is not None else json.dumps(self.document), encoding="utf-8")
        (self.evidence / "handoff-evidence.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in self.rows), encoding="utf-8")

    def test_valid_pass_requires_all_seven_scenarios(self) -> None:
        """完整、合格且正确的证据才产生 VALID_PASS。"""
        self.write()
        report = VERDICT.evaluate(self.evidence)
        self.assertEqual("VALID_PASS", report["validityStatus"])
        self.assertTrue(report["comparisonFingerprint"])
        self.assertTrue(report["handoffEvidenceSha256"])

    def test_generator_failure_is_not_sut_failure(self) -> None:
        """工具资格失败归 INVALID_GENERATOR。"""
        self.document["qualification"]["result"] = "FAIL"
        self.write()
        self.assertEqual("INVALID_GENERATOR", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_environment_drift_is_invalid_generator(self) -> None:
        """未隔离或冻结配置漂移不能算作 SUT 正确性失败。"""
        self.document["configuration"]["httpMessageActions"] = 1
        self.write()
        self.assertEqual("INVALID_GENERATOR", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_commercial_claim_with_community_license_is_invalid_generator(self) -> None:
        """本机 Community License 证据不得冒充生产或商业 SaaS 资格。"""
        self.document["configuration"]["license"]["purpose"] = "COMMERCIAL_SAAS"
        self.document["configuration"]["license"]["productionEligible"] = True
        self.write()
        self.assertEqual("INVALID_GENERATOR", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_real_reconciliation_failure_is_valid_fail(self) -> None:
        """合格运行的终态集合缺失归 VALID_FAIL。"""
        self.document["scenarios"][0]["terminalMessageIds"] = ["another-message"]
        self.write()
        self.assertEqual("VALID_FAIL", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_missing_scenario_is_error(self) -> None:
        """场景缺失是证据不完整，不得伪装成被测系统失败。"""
        self.document["scenarios"].pop()
        self.write()
        self.assertEqual("ERROR", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_duplicate_json_key_is_error(self) -> None:
        """重复键必须在解释业务值前 fail-closed。"""
        raw = json.dumps(self.document).replace('{"schemaVersion": 1,',
                                                '{"schemaVersion": 1, "schemaVersion": 1,', 1)
        self.write(raw)
        report = VERDICT.evaluate(self.evidence)
        self.assertEqual("ERROR", report["validityStatus"])
        self.assertIn("重复 JSON 键", report["errors"][0])

    def test_non_contiguous_attempt_is_error(self) -> None:
        """跳号 attempt 不能用于证明重放次数。"""
        self.rows[3]["attempt"] = 3
        self.write()
        self.assertEqual("ERROR", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_cross_scenario_replay_cannot_reset_handoff_attempt(self) -> None:
        """场景标签变化不能让同一 durable handoff 从 attempt 1 重新计数。"""
        replay = dict(next(row for row in self.rows if row["scenario"] == "ack-ambiguity"))
        replay.update({"sequence": len(self.rows) + 1, "scenario": "emqx-restart", "attempt": 1})
        self.rows.append(replay)
        self.write()
        self.assertEqual("ERROR", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_cross_scenario_replay_keeps_global_handoff_attempt(self) -> None:
        """已对账 handoff 可在后续故障窗口重放，但 attempt 必须延续且业务 ID 已全局登记。"""
        source = [row for row in self.rows if row["scenario"] == "ack-ambiguity"][-1]
        replay = dict(source)
        replay.update({"sequence": len(self.rows) + 1, "scenario": "emqx-restart", "attempt": 3})
        self.rows.append(replay)
        self.write()
        self.assertEqual("VALID_PASS", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_ack_ambiguity_requires_second_delivery_attempt(self) -> None:
        """receipt 自报重复不够；同一 handoff 必须有连续的第二次生产分派。"""
        removed = False
        remaining = []
        for row in self.rows:
            if row["scenario"] == "ack-ambiguity" and row["attempt"] == 2 and not removed:
                removed = True
                continue
            remaining.append(row)
        self.rows = remaining
        for sequence, row in enumerate(self.rows, 1):
            row["sequence"] = sequence
        self.write()
        self.assertEqual("VALID_FAIL", VERDICT.evaluate(self.evidence)["validityStatus"])

    def test_ack_ambiguity_allows_raw_replay_to_be_accepted(self) -> None:
        """raw ingress 以 Kafka ACK 分类 accepted，最终 inbox 唯一性才吸收业务重复。"""
        self.write()
        self.assertEqual("VALID_PASS", VERDICT.evaluate(self.evidence)["validityStatus"])


if __name__ == "__main__":
    unittest.main()
