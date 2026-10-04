"""G1-C4b 隔离工具的 fail-closed 合同测试。"""

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).parents[1] / "c4b_matrix_runner.py"
SPEC = importlib.util.spec_from_file_location("c4b_matrix_runner", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)
RUN_ID = "019d2c58-7c6d-7000-8000-000000000001"


def notification_metric_sample(now: str, success: float | None,
                               failure: float | None, pid: int = 101,
                               group: str = "rule-notification",
                               topic: str = "tc.rule.notification") -> dict:
    """构造绑定同一合成 SUT 身份的 actuator 原始样本。"""
    def result(name: str, value: float | None) -> dict:
        if value is None:
            return {"present": False, "value": None, "rawSamples": []}
        line = (f"thingslink_kafka_consumer_result_total{{group=\"{group}\","
                f"topic=\"{topic}\",result=\"{name}\"}} {value}")
        return {"present": True, "value": value, "rawSamples": [line]}

    return {"schemaVersion": 1, "source": "SUT_ACTUATOR",
            "endpointPath": "/actuator/prometheus",
            "processIdentity": {"pid": pid, "created": "process-start",
                                "commandSha256": "c" * 64, "jarSha256": "d" * 64},
            "results": {"success": result("success", success),
                        "failure": result("failure", failure)},
            "capturedAt": now}


class C4bMatrixRunnerTests(unittest.TestCase):
    @staticmethod
    def _write_complete_evidence(root: Path) -> None:
        """构造语义完整但不代表正式运行的合成证据，仅验证裁决器正向边界。"""
        now = "2026-08-28T00:00:00Z"
        project = MODULE.project_for(RUN_ID)
        digest = "a" * 64
        commit = "b" * 40
        ports = {f"PORT_{index}": 11000 + index for index in range(11)}
        manifest = {
            "schemaVersion": 1, "runId": RUN_ID, "project": project,
            "gitCommit": commit, "gitClean": True, "startedAt": now, "completedAt": now,
            "sourceIdentity": {"qualificationCommit": commit,
                               "evidenceArchiveParentCommit": commit, "gitClean": True,
                               "repository": "D:/ThingsLink", "verifiedAt": now},
            "scenarios": list(MODULE.SCENARIOS), "ports": ports,
            "leaseSeconds": {"outbox": 30, "ruleReceipt": 60},
            "artifacts": {"jar": digest, "runner": digest,
                          "compose": {"base.yml": digest, "overlay.yml": digest},
                          "qualification": digest, "scenarioDriver": digest,
                          "plan": digest, "prometheusConfig": digest},
        }
        MODULE.write_json(root / "run-manifest.json", manifest)
        containers = ["container-1"]
        MODULE.write_json(root / "environment.json", {
            "schemaVersion": 1, "runId": RUN_ID, "project": project,
            "os": {"name": "nt", "platform": "win32", "description": "test"},
            "dockerClientServer": "Docker test", "jdk": "OpenJDK 21",
            "resources": {"container": containers, "volume": ["volume-1"],
                          "network": ["network-1"]},
            "imageDigests": {"container-1": "sha256:" + digest}, "capturedAt": now,
        })
        facts = {scenario: {"baseline": 1} for scenario in MODULE.SCENARIOS}
        MODULE.write_json(root / "database-before.json", {
            "schemaVersion": 1, "runId": RUN_ID, "project": project,
            "snapshot": "database-before", "facts": {"scenarios": facts}, "capturedAt": now})
        after = {scenario: {"finalAssertionsPassed": True} for scenario in MODULE.SCENARIOS}
        MODULE.write_json(root / "database-after.json", {
            "schemaVersion": 1, "runId": RUN_ID, "project": project,
            "snapshot": "database-after", "facts": {"scenarios": after}, "capturedAt": now})
        MODULE.write_json(root / "prometheus-alerts.json", {
            "schemaVersion": 1, "runId": RUN_ID, "project": project,
            "rule": "ThingsLinkDatabaseUnavailable",
            "firing": {"queriedAt": now, "result": [{"metric": {"alertstate": "firing"}}]},
            "resolved": {"queriedAt": now, "result": []}, "capturedAt": now})
        MODULE.write_json(root / "cleanup.json", {
            "project": project,
            "downCommand": ["docker", "compose", "-p", project,
                            "down", "--volumes", "--remove-orphans"],
            "downExitCode": 0, "remaining": {"container": [], "volume": [], "network": []},
            "resourceQueryErrors": [], "portsReleased": True,
            "portRelease": {str(port): True for port in ports.values()},
            "sutPidGone": True, "sharedUnchanged": True, "sharedChanges": {},
            "verdict": "PASS", "completedAt": now})
        events = [{"event": "PREPARED", "runId": RUN_ID, "project": project, "at": now},
                  {"event": "SUT_STARTED", "pid": 101, "created": "process-start",
                   "commandSha256": "c" * 64, "jarSha256": "d" * 64, "at": now}]
        events.extend({"event": "CHECKPOINT_REACHED", "scenario": scenario,
                       "checkpoint": checkpoint, "pid": 101, "at": now}
                      for scenario, checkpoint in MODULE.SCENARIOS.items())
        events.extend(({"event": "DATABASE_STOPPED", "scenario": "DB-01", "at": now},
                       {"event": "DATABASE_RECOVERED", "scenario": "DB-01", "at": now}))
        events.append({"event": "SUT_STARTED", "pid": 104, "created": "rw04-restart",
                       "commandSha256": "e" * 64, "jarSha256": "a" * 64, "at": now})
        (root / "events.jsonl").write_text(
            "".join(json.dumps(item) + "\n" for item in events), encoding="utf-8")

        for scenario in MODULE.SCENARIOS:
            phase_references = {}
            for phase in MODULE.PHASES:
                diagnostic = root / "diagnostics" / scenario / f"{phase}.json"
                MODULE.write_json(diagnostic, {"scenario": scenario, "phase": phase,
                                               "status": "PASS"})
                phase_references[phase] = MODULE.evidence_reference(root, diagnostic)
            readiness_references = {}
            for purpose in ("fixture", "settled"):
                groups = {}
                for group, (topic, partitions) in MODULE.CONSUMER_REQUIREMENTS.items():
                    assignments = [
                        {"partition": partition,
                         "memberId": f"member-{partition // 3}" if partitions == 12 else "member-1"}
                        for partition in range(partitions)
                    ]
                    groups[group] = {"returnCode": 0, "parsed": {
                        "topic": topic, "state": "Stable", "assignor": "range", "totalLag": 0,
                        "assignedPartitions": list(range(partitions)),
                        "expectedPartitions": list(range(partitions)),
                        "memberIds": sorted({item["memberId"] for item in assignments}),
                        "partitionAssignments": assignments, "ready": True}}
                path = root / "readiness" / f"{scenario}-{purpose}.json"
                MODULE.write_json(path, {"schemaVersion": 1, "scenario": scenario,
                                         "purpose": purpose, "verdict": "PASS", "groups": groups,
                                         "attempts": 1, "durationMillis": 1, "completedAt": now})
                readiness_references[purpose] = MODULE.evidence_reference(root, path)
            saturation_path = root / "qualification" / "RW-05-saturation.json"
            saturation_selections = [
                {"partition": partition, "memberId": f"member-{index}"}
                for index, partition in enumerate((0, 3, 6))
            ]
            MODULE.write_json(saturation_path, {
                "schemaVersion": 1, "scenario": "RW-05",
                "topic": "tc.device.uplink.normalized", "requiredDistinctMembers": 3,
                "distinctMemberCount": 3, "selections": saturation_selections,
                "readinessEvidence": readiness_references["fixture"], "capturedAt": now})
            saturation_reference = MODULE.evidence_reference(root, saturation_path)
            receipt_facts = {"checkpointReachedCount": 1, "exactPidKilled": True,
                             "databaseStopped": True, "finalAssertionsPassed": True,
                             "databaseVolumeBefore": "pg-volume",
                             "databaseVolumeAfter": "pg-volume",
                             "sameDatabaseVolume": True, "databaseUnavailableObserved": True,
                             "databaseRecovered": True, "databaseUnavailableAlertFiring": True,
                             "databaseUnavailableAlertResolved": True,
                             "targetKafkaRecords": 1, "leaseTakeoverSeconds": 60,
                             "leaseTokenChanged": True, "leaseClaimCount": 2,
                             "businessFactCount": 1,
                             "targetAndFollowerPublished": True,
                             "laneOrderPreserved": True, "noDanglingLease": True,
                             "targetStatusBefore": "PUBLISHED",
                             "targetKafkaRecordsBefore": 1,
                             "targetLeaseClaimCount": 1,
                             "targetPublishedAtUnchanged": True,
                             "targetReclaimed": False, "scriptSuccessCount": 1,
                             "processedInboxCount": 1, "propertyPointCount": 1,
                             "notificationOutboxCount": 1, "sideEffectCount": 1,
                             "replayed": True, "continuationAddedAfterRestart": 0,
                             "nextAttemptTerminalCount": 1, "oldAttemptSideEffectCount": 0,
                             "saturationPartitions": [0, 3, 6],
                             "saturationDistinctMemberCount": 3,
                             "saturationQualificationEvidence": saturation_reference}
            if scenario in ("OB-01", "OB-02", "OB-03", "OB-04"):
                receipt_facts["targetKafkaRecords"] = 2
            if scenario == "DB-01":
                recovery_references = {}
                recovery_observed = {
                    "outbox-terminal": 2, "notification-delivery": 1,
                    "inbox-persisted": 1,
                    "outbox-integrity": {
                        "targetAndFollowerPublished": True, "laneOrderPreserved": True,
                        "noDanglingLease": True, "businessFactCount": 1,
                        "targetKafkaRecords": 1, "finalAssertionsPassed": True,
                        "targetState": {"id": "target-1", "status": "PUBLISHED", "attemptCount": 1,
                                        "createdAt": "2026-08-29T00:00:00Z",
                                        "publishedAt": "2026-08-29T00:01:00Z", "leasePresent": False},
                        "followerState": {"id": "follower-1", "status": "PUBLISHED", "attemptCount": 0,
                                          "createdAt": "2026-08-29T00:00:01Z",
                                          "publishedAt": "2026-08-29T00:01:01Z", "leasePresent": False}},
                    "alert-resolved": {"queriedAt": now, "result": []},
                    "database-volume": {"before": "pg-volume", "after": "pg-volume",
                                        "same": True, "nonEmpty": True},
                }
                for step, (budget_group, budget_seconds) in \
                        MODULE.DATABASE_RECOVERY_EVIDENCE.items():
                    path = root / "recovery" / scenario / f"{step}.json"
                    MODULE.write_json(path, {
                        "schemaVersion": 1, "scenario": scenario, "step": step,
                        "budgetGroup": budget_group, "groupBudgetSeconds": budget_seconds,
                        "remainingBudgetMillisAtStart": budget_seconds * 1000,
                        "startedAt": now, "completedAt": now, "durationMillis": 0,
                        "attempts": 1, "expected": True,
                        "lastObserved": recovery_observed[step],
                        "verdict": "PASS"})
                    recovery_references[step] = MODULE.evidence_reference(root, path)
                receipt_facts["recoveryEvidence"] = recovery_references
                attribution = root / "attribution" / scenario / "notification-chain.json"
                target_id = "target-1"
                follower_id = "follower-1"
                outbox_identity = root / "identity" / scenario / "notification-outbox.json"
                MODULE.write_json(outbox_identity, {
                    "schemaVersion": 1, "scenario": scenario,
                    "topic": "tc.rule.notification",
                    "identities": {
                        label: {"outboxId": outbox_id, "deliveryEventId": target_id,
                                "partitionKeySha256": hashlib.sha256(
                                    target_id.encode()).hexdigest(),
                                "payloadEventIdSha256": hashlib.sha256(
                                    target_id.encode()).hexdigest(),
                                "identityMatch": True}
                        for label, outbox_id in (("target", target_id),
                                                ("follower", follower_id))},
                    "capturedAt": now})
                MODULE.write_json(attribution, {
                    "schemaVersion": 3, "scenario": scenario,
                    "topic": "tc.rule.notification",
                    "group": "things-link-rule-notification", "targetId": target_id,
                    "followerId": follower_id,
                    "outboxIdentityEvidence": MODULE.evidence_reference(root, outbox_identity),
                    "sourceIdentity": {"eventId": target_id, "records": [{
                            "partition": 0, "offset": offset,
                            "keySha256": hashlib.sha256(target_id.encode()).hexdigest(),
                            "payloadEventIdSha256": hashlib.sha256(
                                target_id.encode()).hexdigest(),
                            "keyMatchesPayloadEventId": True,
                        } for offset in (0, 1)]},
                    "groupSnapshot": {
                        "group": "things-link-rule-notification", "topic": "tc.rule.notification",
                        "state": "Stable", "members": 1, "totalLag": 0,
                        "partitions": [{"partition": partition, "logStartOffset": 0,
                                        "logEndOffset": 2 if partition == 0 else 0,
                                        "currentOffset": 2 if partition == 0 else 0,
                                        "lag": 0, "memberId": "member-1"}
                                       for partition in range(6)]},
                    "metricsBefore": notification_metric_sample(now, None, None),
                    "metricsAfter": notification_metric_sample(now, 1, None),
                    "metricDelta": {"success": 1, "failure": 0},
                    "deliveryFactCount": 1,
                    "dlq": {topic: {"beforeTotal": 0, "afterTotal": 0,
                                     "totalDelta": 0, "targetRecords": []}
                            for topic in ("tc.dlq", "tc.rule.dlq")},
                    "targetOffsetsCommitted": True, "offsetOutcome": "DURABLE_FACT",
                    "probeErrors": {}, "complete": True, "capturedAt": now})
                uplink_attribution = root / "attribution" / scenario / "normalized-chain.json"
                MODULE.write_json(uplink_attribution, {
                    "schemaVersion": 2, "scenario": scenario,
                    "topic": "tc.device.uplink.normalized",
                    "group": "things-link-ingestion-normalized",
                    "targetMessageId": "message-1",
                    "sourceLocations": [{"partition": 3, "offset": 7}],
                    "groupSnapshot": {
                        "group": "things-link-ingestion-normalized",
                        "topic": "tc.device.uplink.normalized", "state": "Stable",
                        "members": 4, "totalLag": 0,
                        "partitions": [{"partition": partition, "logStartOffset": 0,
                                        "logEndOffset": 8 if partition == 3 else 0,
                                        "currentOffset": 8 if partition == 3 else 0,
                                        "lag": 0, "memberId": "member-1"}
                                       for partition in range(12)]},
                    "metricsBefore": notification_metric_sample(
                        now, None, None, group="ingestion-normalized",
                        topic="tc.device.uplink.normalized"),
                    "metricsAfter": notification_metric_sample(
                        now, 1, 1, group="ingestion-normalized",
                        topic="tc.device.uplink.normalized"),
                    "metricDelta": {"success": 1, "failure": 1},
                    "inboxFactCount": 1,
                    "dlq": {"topic": "tc.dlq", "beforeTotal": 0, "afterTotal": 0,
                             "totalDelta": 0, "targetRecords": []},
                    "targetOffsetsCommitted": True, "offsetOutcome": "DURABLE_FACT",
                    "probeErrors": {}, "complete": True, "capturedAt": now})
            if scenario == "OB-01":
                target_id = "target-ob-01"
                follower_id = "follower-ob-01"
                lane_path = root / "attribution" / scenario / "outbox-lane.json"
                token_digest = hashlib.sha256(b"lease-token").hexdigest()
                MODULE.write_json(lane_path, {
                    "schemaVersion": 1, "scenario": scenario,
                    "targetId": target_id, "followerId": follower_id,
                    "targetState": {"id": target_id, "status": "PUBLISHED",
                                    "attemptCount": 0,
                                    "createdAt": "2026-08-29T00:00:00Z",
                                    "availableAt": "2026-08-29T00:00:00Z",
                                    "publishedAt": "2026-08-29T00:01:00Z",
                                    "leasedUntil": None, "leasePresent": False},
                    "followerState": {"id": follower_id, "status": "PUBLISHED",
                                      "attemptCount": 0,
                                      "createdAt": "2026-08-29T00:00:01Z",
                                      "availableAt": "2026-08-29T00:00:01Z",
                                      "publishedAt": "2026-08-29T00:01:01Z",
                                      "leasedUntil": None, "leasePresent": False},
                    "laneOrderPreserved": True,
                    "transitions": [
                        {"sequence": 1, "eventId": target_id,
                         "oldLeaseTokenSha256": None,
                         "newLeaseTokenSha256": token_digest,
                         "oldStatus": "PENDING", "newStatus": "PENDING",
                         "availableAt": "2026-08-29T00:00:00Z",
                         "leasedUntil": "2026-08-29T00:00:30Z",
                         "publishedAt": None, "observedAt": "2026-08-29T00:00:00Z"},
                        {"sequence": 2, "eventId": target_id,
                         "oldLeaseTokenSha256": token_digest,
                         "newLeaseTokenSha256": None,
                         "oldStatus": "PENDING", "newStatus": "PUBLISHED",
                         "availableAt": "2026-08-29T00:00:00Z",
                         "leasedUntil": None,
                         "publishedAt": "2026-08-29T00:01:00Z",
                         "observedAt": "2026-08-29T00:01:00Z"},
                        {"sequence": 3, "eventId": follower_id,
                         "oldLeaseTokenSha256": None,
                         "newLeaseTokenSha256": token_digest,
                         "oldStatus": "PENDING", "newStatus": "PENDING",
                         "availableAt": "2026-08-29T00:00:01Z",
                         "leasedUntil": "2026-08-29T00:01:30Z",
                         "publishedAt": None, "observedAt": "2026-08-29T00:01:00Z"},
                        {"sequence": 4, "eventId": follower_id,
                         "oldLeaseTokenSha256": token_digest,
                         "newLeaseTokenSha256": None,
                         "oldStatus": "PENDING", "newStatus": "PUBLISHED",
                         "availableAt": "2026-08-29T00:00:01Z",
                         "leasedUntil": None,
                         "publishedAt": "2026-08-29T00:01:01Z",
                         "observedAt": "2026-08-29T00:01:01Z"}],
                    "capturedAt": now})
            if scenario == "OB-02":
                target_id = "target-ob-02"
                old_digest = hashlib.sha256(b"lease-old").hexdigest()
                new_digest = hashlib.sha256(b"lease-new").hexdigest()
                lease_path = root / "attribution" / scenario / "lease-takeover.json"
                MODULE.write_json(lease_path, {
                    "schemaVersion": 1, "scenario": scenario, "targetId": target_id,
                    "leaseSeconds": 30, "leaseUntilEpoch": 1787875230,
                    "beforeLeaseTokenSha256": old_digest,
                    "claims": [
                        {"leaseTokenSha256": old_digest,
                         "observedAt": "2026-08-28T00:00:00Z"},
                        {"leaseTokenSha256": new_digest,
                         "observedAt": "2026-08-28T00:00:30Z"}],
                    "targetState": {"id": target_id, "status": "PUBLISHED",
                                    "leasePresent": False},
                    "takeoverSeconds": 30, "tokenChanged": True,
                    "capturedAt": "2026-08-28T00:00:31Z"})
                receipt_facts["leaseTakeoverSeconds"] = 30
            if scenario == "OB-04":
                target_id = "target-ob-04"
                claim_digest = hashlib.sha256(b"lease-only").hexdigest()
                terminal_path = root / "attribution" / scenario / "terminal-no-reclaim.json"
                MODULE.write_json(terminal_path, {
                    "schemaVersion": 1, "scenario": scenario, "targetId": target_id,
                    "before": {"status": "PUBLISHED",
                               "publishedAt": "2026-08-28T00:00:01Z",
                               "kafkaRecords": 1},
                    "claims": [{"leaseTokenSha256": claim_digest,
                                "observedAt": "2026-08-28T00:00:00Z"}],
                    "targetState": {"id": target_id, "status": "PUBLISHED",
                                    "attemptCount": 0,
                                    "publishedAt": "2026-08-28T00:00:01Z",
                                    "leasePresent": False},
                    "publishedAtUnchanged": True, "targetReclaimed": False,
                    "capturedAt": "2026-08-28T00:00:02Z"})
            if scenario in ("RW-01", "RW-02", "RW-03", "RW-04"):
                rule_chain_path = root / "attribution" / scenario / "rule-chain.json"
                MODULE.write_json(rule_chain_path, {
                    "schemaVersion": 2, "scenario": scenario,
                    "messageId": "message-1", "projectId": "project-1",
                    "deviceId": "device-1", "ruleId": "rule-1",
                    "ruleVersionId": "version-1", "sourceSha256": "e" * 64,
                    "startedAt": now, "completedAt": now, "attempts": 2,
                    "budget": {"innerRecoverySeconds": 180,
                               "outerAfterProbeSeconds": 300,
                               "attributionReserveSeconds": 120},
                    "lastObserved": {
                        "receiptCount": 1, "receiptStatus": "COMPLETED",
                        "scriptSuccessCount": 1, "processedInboxCount": 1,
                        "propertyPointCount": 1, "notificationOutboxCount": 1,
                        "sideEffectCount": 1, "qualifiedActionVersionCount": 1,
                        "shadowTemperaturePresentCount": 1, "messageLogCount": 1,
                        "capturedAt": now},
                    "degradationAttribution": None, "verdict": "PASS"})
            if scenario == "RW-04":
                replay_path = root / "attribution" / scenario / "rule-replay-metric.json"
                raw = ('thingslink_rule_execution_seconds_count{stage="engine",'
                       'result="replayed"} 1.0')
                MODULE.write_json(replay_path, {
                    "schemaVersion": 1, "scenario": scenario, "source": "SUT_ACTUATOR",
                    "endpointPath": "/actuator/prometheus",
                    "processIdentity": {"pid": 104, "created": "rw04-restart",
                                        "commandSha256": "e" * 64, "jarSha256": "a" * 64},
                    "metricName": "thingslink_rule_execution_seconds_count",
                    "labels": {"stage": "engine", "result": "replayed"},
                    "sample": {"present": True, "value": 1.0, "rawSamples": [raw]},
                    "capturedAt": now, "attempts": 2, "startedAt": now,
                    "verdict": "PASS"})
                receipt_facts["replayMetricEvidence"] = MODULE.evidence_reference(
                    root, replay_path)
            receipt = {
                "scenario": scenario, "checkpoint": MODULE.SCENARIOS[scenario], "verdict": "PASS",
                "facts": receipt_facts, "phaseEvidence": phase_references,
                "consumerReadinessEvidence": readiness_references["fixture"],
                "consumerSettledEvidence": readiness_references["settled"], "completedAt": now}
            if scenario == "DB-01":
                receipt["notificationAttributionEvidence"] = MODULE.evidence_reference(
                    root, attribution)
                receipt["uplinkAttributionEvidence"] = MODULE.evidence_reference(
                    root, uplink_attribution)
            if scenario == "OB-01":
                receipt["outboxLaneEvidence"] = MODULE.evidence_reference(root, lane_path)
            if scenario == "OB-02":
                receipt["leaseTakeoverEvidence"] = MODULE.evidence_reference(root, lease_path)
            if scenario == "OB-04":
                receipt["terminalNoReclaimEvidence"] = MODULE.evidence_reference(
                    root, terminal_path)
            if scenario in ("RW-01", "RW-02", "RW-03", "RW-04"):
                receipt["ruleChainEvidence"] = MODULE.evidence_reference(root, rule_chain_path)
            MODULE.write_json(root / "scenarios" / f"{scenario}.json", receipt)

    def test_process_identity_uses_ps_on_macos(self) -> None:
        """macOS 没有 /proc，仍须取得稳定创建时间与完整命令摘要。"""
        completed = [
            subprocess.CompletedProcess([], 0, "Wed Aug 27 05:00:00 2026\n", ""),
            subprocess.CompletedProcess([], 0, "java -jar app.jar --server.port=18080\n", ""),
        ]
        with patch.object(MODULE.sys, "platform", "darwin"), \
                patch.object(MODULE.os, "name", "posix"), \
                patch.object(MODULE.subprocess, "run", side_effect=completed) as run:
            identity = MODULE.process_identity(1234)

        self.assertEqual(1234, identity["pid"])
        self.assertEqual("Wed Aug 27 05:00:00 2026", identity["created"])
        self.assertEqual(hashlib.sha256(
            b"java -jar app.jar --server.port=18080").hexdigest(), identity["commandSha256"])
        self.assertEqual(["ps", "-p", "1234", "-o", "lstart="], run.call_args_list[0].args[0])

    """覆盖身份、checkpoint、清理和证据闭包的关键拒绝路径。"""

    def test_rejects_non_v7_run_id(self):
        with self.assertRaises(MODULE.C4bError):
            MODULE.project_for("00000000-0000-4000-8000-000000000000")

    def test_wait_checkpoint_requires_exact_single_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "control" / "OB-03"
            control.mkdir(parents=True)
            (control / "events.jsonl").write_text(json.dumps({
                "event": "REACHED", "scenario": "OB-03",
                "checkpoint": "OUTBOX_AFTER_KAFKA_ACK", "pid": 41,
            }) + "\n", encoding="utf-8")
            self.assertEqual(41, MODULE.wait_checkpoint(root, "OB-03", 41, timeout=0.1)["pid"])
            with self.assertRaises(MODULE.C4bError):
                MODULE.wait_checkpoint(root, "OB-03", 42, timeout=0.1)

    def test_wait_checkpoint_retries_empty_and_partial_publication(self):
        """旧 writer 先暴露空文件或未完成尾行时必须等待，不能重演 attempt 17。"""
        for initial in ("", '{"event":"REACHED"'):
            with self.subTest(initial=initial), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                control = root / "control" / "OB-01"
                control.mkdir(parents=True)
                events = control / "events.jsonl"
                events.write_text(initial, encoding="utf-8")
                calls = 0

                def complete_publication(_seconds: float) -> None:
                    nonlocal calls
                    calls += 1
                    events.write_text(json.dumps({
                        "event": "REACHED", "scenario": "OB-01",
                        "checkpoint": "OUTBOX_BEFORE_CLAIM", "pid": 73,
                    }) + "\n", encoding="utf-8")

                reached = MODULE.wait_checkpoint(
                    root, "OB-01", 73, timeout=0.1, sleep=complete_publication)
                self.assertEqual(73, reached["pid"])
                self.assertEqual(1, calls)

    def test_wait_checkpoint_rejects_complete_corruption_and_duplicates(self):
        """完整损坏或重复记录不是瞬态发布，必须立即 fail-closed。"""
        invalid_values = (
            "not-json\n",
            json.dumps({"event": "OTHER", "scenario": "OB-01",
                        "checkpoint": "OUTBOX_BEFORE_CLAIM", "pid": 73}) + "\n",
            "\n".join((json.dumps({"event": "REACHED", "scenario": "OB-01",
                                     "checkpoint": "OUTBOX_BEFORE_CLAIM", "pid": 73}),
                         json.dumps({"event": "REACHED", "scenario": "OB-01",
                                     "checkpoint": "OUTBOX_BEFORE_CLAIM", "pid": 73}), "")),
        )
        for value in invalid_values:
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                control = root / "control" / "OB-01"
                control.mkdir(parents=True)
                (control / "events.jsonl").write_text(value, encoding="utf-8")
                with self.assertRaises(MODULE.C4bError):
                    MODULE.wait_checkpoint(root, "OB-01", 73, timeout=0.1,
                                           sleep=lambda _seconds: None)

    def test_wait_checkpoint_times_out_on_never_completed_tail(self):
        """长期空文件或残缺尾行不能被宽松解释为 REACHED。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            control = root / "control" / "OB-01"
            control.mkdir(parents=True)
            (control / "events.jsonl").write_text("", encoding="utf-8")
            with self.assertRaisesRegex(MODULE.C4bError, "预算内未取得完整"):
                MODULE.wait_checkpoint(root, "OB-01", 73, timeout=0.001,
                                       sleep=lambda _seconds: None)

    def test_cleanup_targets_only_exact_manifest_project_and_is_repeatable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            compose = root / "compose.yml"
            compose.write_text("services: {}\n", encoding="utf-8")
            calls = []

            def fake_run(command, **_kwargs):
                calls.append(command)
                return subprocess.CompletedProcess(command, 0, "", "")

            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            first = MODULE.cleanup(root, manifest, [compose], run=fake_run)
            second = MODULE.cleanup(root, manifest, [compose], run=fake_run)
            self.assertEqual("PASS", first["verdict"])
            self.assertEqual("PASS", second["verdict"])
            self.assertNotIn("prune", " ".join(part for call in calls for part in call))
            self.assertTrue(all("tc-" not in part for call in calls for part in call))
            down = next(call for call in calls if "down" in call)
            self.assertIn("obs", down)
            self.assertIn("init", down)

    def test_cleanup_waits_for_docker_port_forward_release(self):
        """Compose 资源归零后端口转发可短暂存活，必须有限等待而非立即误判。"""
        states = iter([False, False, True])
        with patch.object(MODULE, "port_is_bindable", side_effect=lambda _port: next(states)):
            result = MODULE.wait_ports_released([39093], timeout=1, sleep=lambda _value: None)
        self.assertEqual({"39093": True}, result)

    def test_verdict_fails_closed_then_accepts_complete_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self.assertEqual("FAIL", MODULE.verdict(root)["verdict"])
            self.assertTrue((root / "sha256sums.txt").is_file())
            self._write_complete_evidence(root)
            self.assertEqual("VALID_PASS", MODULE.verdict(root)["verdict"])
            self.assertTrue((root / "sha256sums.txt").is_file())

    def test_thin_pass_documents_cannot_reach_valid_pass(self):
        """仅写 verdict=PASS 的文件不得再冒充完整身份与语义证据。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            for name in MODULE.REQUIRED_EVIDENCE:
                (root / name).write_text(json.dumps({"verdict": "PASS"}) + "\n", encoding="utf-8")
            result = MODULE.verdict(root)
            self.assertEqual("FAIL", result["verdict"])
            self.assertTrue(result["validationErrors"])

    def test_manifest_requires_clean_identity_and_completed_at(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            manifest = json.loads((root / "run-manifest.json").read_text(encoding="utf-8"))
            manifest.pop("completedAt")
            with self.assertRaises((MODULE.C4bError, TypeError)):
                MODULE.validate_manifest(manifest)
            manifest["completedAt"] = "2026-08-28T00:00:00Z"
            manifest["gitClean"] = False
            with self.assertRaises(MODULE.C4bError):
                MODULE.validate_manifest(manifest)

    def test_settled_readiness_rejects_wrong_purpose_and_nonzero_lag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            path = root / "readiness" / "DB-01-settled.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["purpose"] = "fixture"
            MODULE.write_json(path, value)
            reference = MODULE.evidence_reference(root, path)
            with self.assertRaises(MODULE.C4bError):
                MODULE.validate_consumer_evidence(root, reference, "DB-01", "settled")
            value["purpose"] = "settled"
            group = next(iter(value["groups"].values()))
            group["parsed"]["totalLag"] = 1
            MODULE.write_json(path, value)
            reference = MODULE.evidence_reference(root, path)
            with self.assertRaises(MODULE.C4bError):
                MODULE.validate_consumer_evidence(root, reference, "DB-01", "settled")

    def test_readiness_requires_rule_notification_group(self):
        """DB-01 不能只等遥测两组；缺规则通知组时资格屏障必须拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            path = root / "readiness" / "DB-01-fixture.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["groups"].pop("things-link-rule-notification")
            MODULE.write_json(path, value)
            with self.assertRaisesRegex(MODULE.C4bError, "冻结生产组"):
                MODULE.validate_consumer_evidence(
                    root, MODULE.evidence_reference(root, path), "DB-01", "fixture")

    def test_notification_attribution_rejects_derived_outcome_drift(self):
        """目标 offset、持久事实与 DLQ 推导结果不一致时不得由自报 outcome 放行。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(
                encoding="utf-8"))
            reference = receipt["notificationAttributionEvidence"]
            path = root / reference["path"]
            value = json.loads(path.read_text(encoding="utf-8"))
            value["offsetOutcome"] = "COMMITTED_WITHOUT_DURABLE_FACT"
            MODULE.write_json(path, value)
            with self.assertRaisesRegex(MODULE.C4bError, "结果推导不闭合"):
                MODULE.validate_notification_attribution_evidence(
                    root, MODULE.evidence_reference(root, path), require_success=True)

    def test_notification_metrics_reject_cross_process_or_non_monotonic_samples(self):
        """进程替换、已注册序列消失与 Counter 下降均不得形成 DB-01 PASS。"""
        def cross_process(value):
            value["metricsAfter"]["processIdentity"]["pid"] = 102

        def disappeared(value):
            value["metricsBefore"] = notification_metric_sample(
                "2026-08-28T00:00:00Z", 1, None)
            value["metricsAfter"] = notification_metric_sample(
                "2026-08-28T00:00:01Z", None, None)
            value["metricDelta"] = {"success": 0, "failure": 0}

        def decreased(value):
            value["metricsBefore"] = notification_metric_sample(
                "2026-08-28T00:00:00Z", 2, None)
            value["metricsAfter"] = notification_metric_sample(
                "2026-08-28T00:00:01Z", 1, None)
            value["metricDelta"] = {"success": 0, "failure": 0}

        def raw_value_drift(value):
            value["metricsAfter"]["results"]["success"]["rawSamples"][0] = (
                value["metricsAfter"]["results"]["success"]["rawSamples"][0]
                .rsplit(" ", 1)[0] + " 9")

        for mutate in (cross_process, disappeared, decreased, raw_value_drift):
            with self.subTest(mutation=mutate.__name__), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(
                    encoding="utf-8"))
                path = root / receipt["notificationAttributionEvidence"]["path"]
                value = json.loads(path.read_text(encoding="utf-8"))
                mutate(value)
                MODULE.write_json(path, value)
                with self.assertRaises(MODULE.C4bError):
                    MODULE.validate_notification_attribution_evidence(
                        root, MODULE.evidence_reference(root, path), require_success=True)

    def test_notification_metrics_reject_checkpoint_process_identity_drift(self):
        """样本自报同一 PID 仍不足够，最终事件必须证明该 PID 命中 DB-01 checkpoint。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            events = [json.loads(line) for line in (root / "events.jsonl").read_text(
                encoding="utf-8").splitlines()]
            next(item for item in events if item.get("event") == "CHECKPOINT_REACHED"
                 and item.get("scenario") == "DB-01")["pid"] = 102
            (root / "events.jsonl").write_text(
                "".join(json.dumps(item) + "\n" for item in events), encoding="utf-8")

            result = MODULE.verdict(root)

            self.assertEqual("FAIL", result["verdict"])
            self.assertTrue(any("实际场景 SUT" in item for item in result["validationErrors"]))

    def test_normalized_metrics_reject_cross_process_and_counter_decrease(self):
        """normalized 当前 SUT 样本不得跨进程，已注册 Counter 也不得下降。"""
        def cross_process(value):
            value["metricsAfter"]["processIdentity"]["pid"] = 202

        def decrease(value):
            value["metricsBefore"] = notification_metric_sample(
                "2026-08-28T00:00:00Z", 2, None, group="ingestion-normalized",
                topic="tc.device.uplink.normalized")
            value["metricsAfter"] = notification_metric_sample(
                "2026-08-28T00:00:01Z", 1, None, group="ingestion-normalized",
                topic="tc.device.uplink.normalized")
            value["metricDelta"] = {"success": 0, "failure": 0}

        for mutate in (cross_process, decrease):
            with self.subTest(mutation=mutate.__name__), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(
                    encoding="utf-8"))
                path = root / receipt["uplinkAttributionEvidence"]["path"]
                value = json.loads(path.read_text(encoding="utf-8"))
                mutate(value)
                MODULE.write_json(path, value)
                with self.assertRaises(MODULE.C4bError):
                    MODULE.validate_uplink_attribution_evidence(
                        root, MODULE.evidence_reference(root, path), require_success=True)

    def test_rw04_replay_metric_rejects_absence_raw_drift_and_wrong_jar(self):
        """摘要布尔值不能掩盖样本缺席、原始值漂移或重启进程 JAR 身份错误。"""
        def absent(value):
            value["sample"] = {"present": False, "value": None, "rawSamples": []}

        def raw_drift(value):
            value["sample"]["rawSamples"][0] = (
                value["sample"]["rawSamples"][0].rsplit(" ", 1)[0] + " 9.0")

        def wrong_jar(value):
            value["processIdentity"]["jarSha256"] = "f" * 64

        for mutate in (absent, raw_drift, wrong_jar):
            with self.subTest(mutation=mutate.__name__), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt_path = root / "scenarios" / "RW-04.json"
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                reference = receipt["facts"]["replayMetricEvidence"]
                evidence_path = root / reference["path"]
                value = json.loads(evidence_path.read_text(encoding="utf-8"))
                mutate(value)
                MODULE.write_json(evidence_path, value)
                receipt["facts"]["replayMetricEvidence"] = MODULE.evidence_reference(
                    root, evidence_path)
                MODULE.write_json(receipt_path, receipt)

                result = MODULE.verdict(root)

                self.assertEqual("FAIL", result["verdict"])

    def test_outbox_lane_evidence_rejects_transition_order_drift(self):
        """摘要正确也不能把 follower 先领取的转换序列冒充 OB-01 PASS。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            receipt_path = root / "scenarios" / "OB-01.json"
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            lane_path = root / receipt["outboxLaneEvidence"]["path"]
            lane = json.loads(lane_path.read_text(encoding="utf-8"))
            lane["transitions"][1], lane["transitions"][2] = (
                lane["transitions"][2], lane["transitions"][1])
            lane["transitions"][1]["sequence"] = 2
            lane["transitions"][2]["sequence"] = 3
            MODULE.write_json(lane_path, lane)
            receipt["outboxLaneEvidence"] = MODULE.evidence_reference(root, lane_path)
            MODULE.write_json(receipt_path, receipt)

            result = MODULE.verdict(root)

            self.assertEqual("FAIL", result["verdict"])
            self.assertTrue(any("领取/发布转换顺序" in item
                                for item in result["validationErrors"]))

    def test_ob02_lease_evidence_rejects_early_or_self_reported_takeover(self):
        """OB-02 必须由摘要闭合证据证明原租约到期，不能只改 facts 布尔值。"""
        def early(lease, _receipt):
            lease["claims"][1]["observedAt"] = "2026-08-28T00:00:29Z"
            lease["takeoverSeconds"] = 29

        def same_token(lease, _receipt):
            lease["claims"][1]["leaseTokenSha256"] = lease["beforeLeaseTokenSha256"]

        def self_report_drift(_lease, receipt):
            receipt["facts"]["leaseTakeoverSeconds"] = 31

        for mutate in (early, same_token, self_report_drift):
            with self.subTest(mutation=mutate.__name__), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt_path = root / "scenarios" / "OB-02.json"
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                lease_path = root / receipt["leaseTakeoverEvidence"]["path"]
                lease = json.loads(lease_path.read_text(encoding="utf-8"))
                mutate(lease, receipt)
                MODULE.write_json(lease_path, lease)
                receipt["leaseTakeoverEvidence"] = MODULE.evidence_reference(root, lease_path)
                MODULE.write_json(receipt_path, receipt)

                result = MODULE.verdict(root)

                self.assertEqual("FAIL", result["verdict"])
                self.assertTrue(any("租约到期" in item
                                    for item in result["validationErrors"]))

    def test_ob04_terminal_evidence_rejects_second_claim_or_published_at_rewrite(self):
        """OB-04 不重领必须来自 target 自身证据，不能由 lane 总记录或布尔值代替。"""
        def second_claim(value, _receipt):
            value["claims"].append({
                "leaseTokenSha256": hashlib.sha256(b"lease-second").hexdigest(),
                "observedAt": "2026-08-28T00:00:02Z"})

        def rewritten(value, _receipt):
            value["targetState"]["publishedAt"] = "2026-08-28T00:00:02Z"

        def self_report_drift(_value, receipt):
            receipt["facts"]["targetLeaseClaimCount"] = 2

        for mutate in (second_claim, rewritten, self_report_drift):
            with self.subTest(mutation=mutate.__name__), \
                    tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt_path = root / "scenarios" / "OB-04.json"
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                evidence_path = root / receipt["terminalNoReclaimEvidence"]["path"]
                value = json.loads(evidence_path.read_text(encoding="utf-8"))
                mutate(value, receipt)
                MODULE.write_json(evidence_path, value)
                receipt["terminalNoReclaimEvidence"] = MODULE.evidence_reference(
                    root, evidence_path)
                MODULE.write_json(receipt_path, receipt)

                result = MODULE.verdict(root)

                self.assertEqual("FAIL", result["verdict"])
                self.assertTrue(any("OB-04" in item for item in result["validationErrors"]))

    def test_notification_attribution_rejects_key_identity_drift(self):
        """摘要、自报等值标记、同 lane 双记录或前置引用漂移均不得进入正式 PASS。"""
        mutations = (
            lambda value: value["sourceIdentity"]["records"][0].update(
                {"keySha256": "0" * 64}),
            lambda value: value["sourceIdentity"]["records"][0].update(
                {"keyMatchesPayloadEventId": False}),
            lambda value: value["sourceIdentity"]["records"].pop(),
            lambda value: value.update({"outboxIdentityEvidence": {
                "path": "identity/DB-01/notification-outbox.json", "sha256": "0" * 64}}),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(
                    encoding="utf-8"))
                path = root / receipt["notificationAttributionEvidence"]["path"]
                value = json.loads(path.read_text(encoding="utf-8"))
                mutate(value)
                MODULE.write_json(path, value)
                with self.assertRaises(MODULE.C4bError):
                    MODULE.validate_notification_attribution_evidence(
                        root, MODULE.evidence_reference(root, path), require_success=True)

    def test_uplink_attribution_rejects_offset_inbox_and_dlq_drift(self):
        """目标位置、committed、inbox 与 DLQ 任一自报漂移都不得形成 DB-01 PASS。"""
        mutations = (
            lambda value: value.update({"targetOffsetsCommitted": False}),
            lambda value: value.update({"inboxFactCount": 0}),
            lambda value: value["sourceLocations"][0].update({"offset": 9}),
            lambda value: value["dlq"]["targetRecords"].append(
                {"partition": 0, "offset": 1}),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_complete_evidence(root)
                receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(
                    encoding="utf-8"))
                path = root / receipt["uplinkAttributionEvidence"]["path"]
                value = json.loads(path.read_text(encoding="utf-8"))
                mutate(value)
                MODULE.write_json(path, value)
                with self.assertRaises(MODULE.C4bError):
                    MODULE.validate_uplink_attribution_evidence(
                        root, MODULE.evidence_reference(root, path), require_success=True)

    def test_fail_fast_prefix_does_not_require_not_run_scenario_evidence(self):
        """首场景直接失败后，空阶段快照和无 checkpoint 事件是合法失败闭包。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            for receipt in (root / "scenarios").glob("*.json"):
                receipt.unlink()
            MODULE.close_scenarios_after_failure(root, RuntimeError("setup failed"), "setup")
            # setup 失败发生在通知阶段之前，不继承正向 helper 的阶段标记文件。
            for step in ("notification-delivery", "inbox-persisted"):
                (root / "recovery" / "DB-01" / f"{step}.json").unlink()
            manifest = json.loads((root / "run-manifest.json").read_text(encoding="utf-8"))
            for name in ("database-before", "database-after"):
                MODULE.write_json(root / f"{name}.json", {
                    "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                    "snapshot": name, "facts": {"scenarios": {}},
                    "capturedAt": "2026-08-28T00:00:00Z"})
            MODULE.write_json(root / "prometheus-alerts.json", {
                "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                "rule": "ThingsLinkDatabaseUnavailable",
                "firing": {"queriedAt": "2026-08-28T00:00:00Z", "result": []},
                "resolved": {"queriedAt": "2026-08-28T00:00:00Z", "result": []},
                "capturedAt": "2026-08-28T00:00:00Z"})
            (root / "events.jsonl").write_text(json.dumps({
                "event": "PREPARED", "runId": RUN_ID, "project": manifest["project"],
                "at": "2026-08-28T00:00:00Z"}) + "\n", encoding="utf-8")
            result = MODULE.verdict(root)
            self.assertEqual("FAIL", result["verdict"])
            self.assertEqual("DB-01", result["firstDirectFailure"]["scenario"])
            self.assertEqual([], result["validationErrors"])

    def test_failed_before_probe_diagnostic_does_not_require_success_snapshot(self):
        """失败阶段引用仍可审计，但不能让空成功快照产生与首因无关的前缀错误。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diagnostic = root / "diagnostics" / "DB-01" / "beforeProbe.json"
            MODULE.write_json(diagnostic, {
                "scenario": "DB-01", "phase": "beforeProbe", "status": "FAIL"})
            receipts = {"DB-01": {"scenario": "DB-01", "verdict": "FAIL",
                                    "phaseEvidence": {"beforeProbe":
                                        MODULE.evidence_reference(root, diagnostic)}}}
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            snapshot = {
                "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                "snapshot": "database-before", "facts": {"scenarios": {}},
                "capturedAt": "2026-08-28T00:00:00Z"}

            MODULE.validate_snapshot(
                snapshot, manifest, "database-before", receipts, root)

    def test_successful_before_probe_requires_exact_snapshot_prefix(self):
        """阶段诊断为 PASS 时仍必须要求对应稳定事实，不能借 F13 放宽成功闭包。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diagnostic = root / "diagnostics" / "DB-01" / "beforeProbe.json"
            MODULE.write_json(diagnostic, {
                "scenario": "DB-01", "phase": "beforeProbe", "status": "PASS"})
            receipts = {"DB-01": {"scenario": "DB-01", "verdict": "FAIL",
                                    "phaseEvidence": {"beforeProbe":
                                        MODULE.evidence_reference(root, diagnostic)}}}
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            snapshot = {
                "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                "snapshot": "database-before", "facts": {"scenarios": {}},
                "capturedAt": "2026-08-28T00:00:00Z"}

            with self.assertRaisesRegex(MODULE.C4bError, "精确覆盖"):
                MODULE.validate_snapshot(
                    snapshot, manifest, "database-before", receipts, root)

    def test_failed_after_probe_rejects_partial_success_snapshot(self):
        """失败诊断只能进入阶段/告警证据，database-after 出现部分 firing 仍必须 fail-closed。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diagnostic = root / "diagnostics" / "DB-01" / "afterProbe.json"
            MODULE.write_json(diagnostic, {
                "scenario": "DB-01", "phase": "afterProbe", "status": "FAIL"})
            receipts = {"DB-01": {"scenario": "DB-01", "verdict": "FAIL",
                                    "phaseEvidence": {"afterProbe":
                                        MODULE.evidence_reference(root, diagnostic)}}}
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            snapshot = {
                "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                "snapshot": "database-after", "facts": {"scenarios": {"DB-01": {
                    "alertFiring": {"queriedAt": "2026-08-29T00:00:00Z", "result": [{}]}}}},
                "capturedAt": "2026-08-29T00:00:00Z"}

            with self.assertRaisesRegex(MODULE.C4bError, "精确覆盖"):
                MODULE.validate_snapshot(
                    snapshot, manifest, "database-after", receipts, root)

    def test_snapshot_rejects_forged_failed_phase_reference(self):
        """即使 FAIL 不要求成功快照，伪造的阶段摘要也必须在快照推导时 fail-closed。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diagnostic = root / "diagnostics" / "DB-01" / "beforeProbe.json"
            MODULE.write_json(diagnostic, {
                "scenario": "DB-01", "phase": "beforeProbe", "status": "FAIL"})
            reference = MODULE.evidence_reference(root, diagnostic)
            reference["sha256"] = "0" * 64
            receipts = {"DB-01": {"scenario": "DB-01", "verdict": "FAIL",
                                    "phaseEvidence": {"beforeProbe": reference}}}
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            snapshot = {
                "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                "snapshot": "database-before", "facts": {"scenarios": {}},
                "capturedAt": "2026-08-28T00:00:00Z"}

            with self.assertRaisesRegex(MODULE.C4bError, "摘要漂移"):
                MODULE.validate_snapshot(
                    snapshot, manifest, "database-before", receipts, root)

    def test_failed_after_checkpoint_accepts_exact_event_prefix(self):
        """复现 attempt 9：afterProbe 失败仍须接受已经落盘的 DB-01 checkpoint。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            receipt_path = root / "scenarios" / "DB-01.json"
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            receipt.update({"verdict": "FAIL", "directFailure": "C4bError"})
            MODULE.write_json(receipt_path, receipt)
            for scenario in list(MODULE.SCENARIOS)[1:]:
                MODULE.write_json(root / "scenarios" / f"{scenario}.json", {
                    "scenario": scenario, "checkpoint": MODULE.SCENARIOS[scenario],
                    "verdict": "NOT_RUN", "reason": "前序场景直接失败",
                    "blockedBy": "DB-01", "completedAt": "2026-08-28T00:00:00Z"})
            manifest = json.loads((root / "run-manifest.json").read_text(encoding="utf-8"))
            for name in ("database-before", "database-after"):
                MODULE.write_json(root / f"{name}.json", {
                    "schemaVersion": 1, "runId": RUN_ID, "project": manifest["project"],
                    "snapshot": name,
                    "facts": {"scenarios": {"DB-01": {"baseline": 1}}},
                    "capturedAt": "2026-08-28T00:00:00Z"})
            events = [
                {"event": "PREPARED", "runId": RUN_ID, "project": manifest["project"],
                 "at": "2026-08-28T00:00:00Z"},
                {"event": "SUT_STARTED", "pid": 101, "created": "process-start",
                 "commandSha256": "c" * 64, "jarSha256": "d" * 64,
                 "at": "2026-08-28T00:00:00Z"},
                {"event": "CHECKPOINT_REACHED", "scenario": "DB-01",
                 "checkpoint": MODULE.SCENARIOS["DB-01"], "pid": 101,
                 "at": "2026-08-28T00:00:00Z"},
                {"event": "DATABASE_STOPPED", "scenario": "DB-01",
                 "at": "2026-08-28T00:00:00Z"},
                {"event": "DATABASE_RECOVERED", "scenario": "DB-01",
                 "at": "2026-08-28T00:00:00Z"},
            ]
            (root / "events.jsonl").write_text(
                "".join(json.dumps(item) + "\n" for item in events), encoding="utf-8")

            result = MODULE.verdict(root)

            self.assertEqual("FAIL", result["verdict"])
            self.assertEqual("DB-01", result["firstDirectFailure"]["scenario"])
            self.assertEqual([], result["validationErrors"])

    def test_failed_after_checkpoint_rejects_missing_event(self):
        """阶段证据已经越过 checkpoint 时，缺少对应事件必须 fail-closed。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            now = "2026-08-28T00:00:00Z"
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            (root / "events.jsonl").write_text(json.dumps({
                "event": "PREPARED", "runId": RUN_ID, "project": manifest["project"],
                "at": now}) + "\n", encoding="utf-8")
            receipts = {"DB-01": {"scenario": "DB-01", "verdict": "FAIL",
                                    "phaseEvidence": {"beforeProbe": {"path": "x"}}}}

            with self.assertRaisesRegex(MODULE.C4bError, "checkpoint 事件"):
                MODULE.validate_events(root, manifest, receipts)

    def test_pass_rejects_missing_checkpoint_event(self):
        """PASS receipt 不能借完整事实绕过独立 checkpoint 事件。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            now = "2026-08-28T00:00:00Z"
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            (root / "events.jsonl").write_text(json.dumps({
                "event": "PREPARED", "runId": RUN_ID, "project": manifest["project"],
                "at": now}) + "\n", encoding="utf-8")
            receipts = {"DB-01": {"scenario": "DB-01", "verdict": "PASS",
                                    "phaseEvidence": {}}}

            with self.assertRaisesRegex(MODULE.C4bError, "checkpoint 事件"):
                MODULE.validate_events(root, manifest, receipts)

    def test_pre_checkpoint_failure_rejects_forged_or_not_run_event(self):
        """fixture 失败和 NOT_RUN 场景均不能通过伪造 checkpoint 事件扩展前缀。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            now = "2026-08-28T00:00:00Z"
            manifest = {"runId": RUN_ID, "project": MODULE.project_for(RUN_ID)}
            receipts = {
                "DB-01": {"scenario": "DB-01", "verdict": "FAIL",
                          "phaseEvidence": {"fixture": {"path": "x"}}},
                "OB-01": {"scenario": "OB-01", "verdict": "NOT_RUN"},
            }
            for scenario in ("DB-01", "OB-01"):
                events = [
                    {"event": "PREPARED", "runId": RUN_ID, "project": manifest["project"],
                     "at": now},
                    {"event": "CHECKPOINT_REACHED", "scenario": scenario,
                     "checkpoint": MODULE.SCENARIOS[scenario], "at": now},
                ]
                (root / "events.jsonl").write_text(
                    "".join(json.dumps(item) + "\n" for item in events), encoding="utf-8")
                with self.subTest(scenario=scenario), \
                        self.assertRaisesRegex(MODULE.C4bError, "checkpoint 事件"):
                    MODULE.validate_events(root, manifest, receipts)

    def test_failed_phase_evidence_rejects_identity_drift(self):
        """失败 receipt 的阶段引用将参与 checkpoint 推导，故同样必须验证身份。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            diagnostic = root / "diagnostics" / "DB-01" / "beforeProbe.json"
            MODULE.write_json(diagnostic, {
                "scenario": "OB-01", "phase": "beforeProbe", "status": "FAIL"})
            receipt = {"scenario": "DB-01", "verdict": "FAIL", "phaseEvidence": {
                "beforeProbe": MODULE.evidence_reference(root, diagnostic)}}

            with self.assertRaisesRegex(MODULE.C4bError, "身份或状态无效"):
                MODULE.validate_phase_evidence(root, receipt)

    def test_failed_notification_attribution_is_revalidated_by_verdict(self):
        """失败 receipt 的归因引用在归档复验时也必须拒绝摘要漂移。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            receipt_path = root / "scenarios" / "DB-01.json"
            receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
            receipt["verdict"] = "FAIL"
            receipt["directFailure"] = "DriverError"
            MODULE.write_json(receipt_path, receipt)
            attribution = root / receipt["notificationAttributionEvidence"]["path"]
            value = json.loads(attribution.read_text(encoding="utf-8"))
            value["offsetOutcome"] = "UNCOMMITTED"
            MODULE.write_json(attribution, value)
            result = MODULE.verdict(root)
            self.assertEqual("FAIL", result["verdict"])
            self.assertTrue(any("摘要漂移" in item for item in result["validationErrors"]))

    def test_cleanup_exception_receipt_cannot_pass_semantic_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"project": MODULE.project_for(RUN_ID),
                        "ports": {"POSTGRES": 15432}}
            receipt = MODULE.write_cleanup_failure(root, manifest, RuntimeError("down failed"))
            self.assertEqual("FAIL", receipt["verdict"])
            self.assertEqual("cleanup", receipt["lifecycleStage"])
            with self.assertRaises(MODULE.C4bError):
                MODULE.validate_cleanup(receipt, manifest)

    def test_checksum_closure_rejects_post_verdict_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "evidence.json").write_text("{}\n", encoding="utf-8")
            MODULE.write_checksum_closure(root)
            (root / "evidence.json").write_text('{"changed":true}\n', encoding="utf-8")
            with self.assertRaises(MODULE.C4bError):
                MODULE.verify_checksum_closure(root)

    def test_setup_failure_closes_first_failure_and_not_run_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            MODULE.close_scenarios_after_failure(root, RuntimeError("setup failed"), "setup")
            first = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            later = json.loads((root / "scenarios" / "OB-01.json").read_text(encoding="utf-8"))
            self.assertEqual(("FAIL", "setup"), (first["verdict"], first["lifecycleStage"]))
            self.assertEqual(("NOT_RUN", "DB-01"), (later["verdict"], later["blockedBy"]))

    def test_git_source_identity_rejects_mismatched_or_dirty_head(self):
        expected = "a" * 40
        anchor = Path("D:/ThingsLink/tool.py")

        def mismatch(command, **_kwargs):
            outputs = {"--show-toplevel": "D:/ThingsLink\n", "HEAD": "b" * 40 + "\n"}
            return subprocess.CompletedProcess(command, 0, outputs[command[-1]], "")

        with self.assertRaises(MODULE.C4bError):
            MODULE.git_source_identity(expected, anchor, mismatch)

        calls = iter(["D:/ThingsLink\n", expected + "\n", " M tracked.py\n"])
        with self.assertRaises(MODULE.C4bError):
            MODULE.git_source_identity(expected, anchor, lambda command, **_kwargs:
                subprocess.CompletedProcess(command, 0, next(calls), ""))

    def test_lease_takeover_has_frozen_upper_bound(self):
        for scenario, seconds in (("OB-02", 61), ("RW-02", 91)):
            facts = {"checkpointReachedCount": 1, "exactPidKilled": True,
                     "finalAssertionsPassed": True, "leaseTakeoverSeconds": seconds,
                     "leaseTokenChanged": True, "leaseClaimCount": 2,
                     "targetKafkaRecords": 2, "businessFactCount": 1,
                     "targetAndFollowerPublished": True, "laneOrderPreserved": True,
                     "noDanglingLease": True,
                     "scriptSuccessCount": 1}
            with self.subTest(scenario=scenario), self.assertRaises(MODULE.C4bError):
                MODULE.validate_scenario_receipt({"scenario": scenario,
                    "checkpoint": MODULE.SCENARIOS[scenario], "verdict": "PASS", "facts": facts})

    def test_controller_kill_rejects_process_or_jar_fingerprint_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "sut.jar"
            jar.write_bytes(b"jar")
            controller = MODULE.SutController(jar, root)
            controller.process = Mock(pid=123)
            controller.process.poll.return_value = None
            controller.fingerprint = {"pid": 123, "created": "one", "commandSha256": "a",
                                      "jarSha256": hashlib.sha256(b"jar").hexdigest()}
            with patch.object(MODULE, "process_identity", return_value={
                    "pid": 123, "created": "two", "commandSha256": "a"}):
                with self.assertRaises(MODULE.C4bError):
                    controller.kill()
            controller.fingerprint["created"] = "two"
            jar.write_bytes(b"changed")
            with patch.object(MODULE, "process_identity", return_value={
                    "pid": 123, "created": "two", "commandSha256": "a"}):
                with self.assertRaises(MODULE.C4bError):
                    controller.kill()

    def test_matrix_stops_after_first_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            called = []

            def execute(scenario, checkpoint):
                called.append(scenario)
                return {"scenario": scenario, "checkpoint": checkpoint, "verdict": "FAIL",
                        "facts": {}}

            results = MODULE.run_matrix(root, execute)
            self.assertEqual(["DB-01"], called)
            self.assertEqual("NOT_RUN", results["OB-01"])

    def test_matrix_failure_keeps_exception_chain_and_context_references(self):
        """直接失败 receipt 必须可诊断，且保留执行器已经落盘的引用。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()

            def execute(_scenario, _checkpoint):
                try:
                    raise ValueError("root cause")
                except ValueError as exception:
                    raise MODULE.C4bError("fixture failed") from exception

            MODULE.run_matrix(root, execute,
                              lambda _scenario, _exception: {"phaseEvidence": {}})
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text())
            self.assertEqual("C4bError", receipt["directFailure"])
            self.assertEqual(["C4bError", "ValueError"],
                             [item["type"] for item in receipt["exception"]["chain"]])
            self.assertIn("fixture failed", receipt["exception"]["stackTrace"]["text"])

    def test_pass_receipt_cannot_ignore_unexpired_lease(self):
        receipt = {"scenario": "OB-02", "checkpoint": MODULE.SCENARIOS["OB-02"],
                   "verdict": "PASS", "facts": {"checkpointReachedCount": 1,
                   "exactPidKilled": True, "finalAssertionsPassed": True,
                   "leaseTakeoverSeconds": 29.9, "leaseTokenChanged": True,
                   "leaseClaimCount": 2, "targetKafkaRecords": 2,
                   "businessFactCount": 1, "targetAndFollowerPublished": True,
                   "laneOrderPreserved": True, "noDanglingLease": True}}
        with self.assertRaises(MODULE.C4bError):
            MODULE.validate_scenario_receipt(receipt)

    def test_database_receipt_requires_database_injection_not_pid_kill(self):
        receipt = {"scenario": "DB-01", "checkpoint": MODULE.SCENARIOS["DB-01"],
                   "verdict": "PASS", "facts": {"checkpointReachedCount": 1,
                   "databaseStopped": True, "finalAssertionsPassed": True,
                   "databaseVolumeBefore": "pg-volume", "databaseVolumeAfter": "pg-volume",
                   "sameDatabaseVolume": True, "databaseUnavailableObserved": True,
                   "databaseRecovered": True, "databaseUnavailableAlertFiring": True,
                   "databaseUnavailableAlertResolved": True,
                   "targetKafkaRecords": 1, "businessFactCount": 1,
                   "targetAndFollowerPublished": True,
                   "laneOrderPreserved": True, "noDanglingLease": True}}
        MODULE.validate_scenario_receipt(receipt)
        receipt["facts"].pop("databaseStopped")
        receipt["facts"]["exactPidKilled"] = True
        with self.assertRaises(MODULE.C4bError):
            MODULE.validate_scenario_receipt(receipt)

    def test_rw05_receipt_rejects_saturation_member_collapse_after_rehash(self):
        """即使重算资格摘要，三个选择退化到两个成员也不得形成 PASS。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "RW-05.json").read_text(
                encoding="utf-8"))
            reference = receipt["facts"]["saturationQualificationEvidence"]
            path = root / reference["path"]
            qualification = json.loads(path.read_text(encoding="utf-8"))
            qualification["selections"][2]["memberId"] = qualification["selections"][0]["memberId"]
            qualification["distinctMemberCount"] = 2
            MODULE.write_json(path, qualification)
            receipt["facts"]["saturationQualificationEvidence"] = MODULE.evidence_reference(root, path)
            with self.assertRaisesRegex(MODULE.C4bError, "资格结构无效"):
                MODULE.validate_scenario_receipt(receipt, root)

    def test_database_recovery_evidence_rejects_missing_and_tampered_segments(self):
        """DB-01 的六段证据集合与摘要都必须闭合，不能只相信 facts 自报。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            receipt["facts"]["recoveryEvidence"].pop("inbox-persisted")
            with self.assertRaisesRegex(MODULE.C4bError, "六段恢复证据"):
                MODULE.validate_scenario_receipt(receipt, root)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            reference = receipt["facts"]["recoveryEvidence"]["alert-resolved"]
            path = root / reference["path"]
            value = json.loads(path.read_text(encoding="utf-8"))
            value["verdict"] = "FAIL"
            MODULE.write_json(path, value)
            with self.assertRaisesRegex(MODULE.C4bError, "摘要漂移"):
                MODULE.validate_scenario_receipt(receipt, root)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            reference = receipt["facts"]["recoveryEvidence"]["outbox-terminal"]
            path = root / reference["path"]
            value = json.loads(path.read_text(encoding="utf-8"))
            value["groupBudgetSeconds"] = 181
            MODULE.write_json(path, value)
            receipt["facts"]["recoveryEvidence"]["outbox-terminal"] = \
                MODULE.evidence_reference(root, path)
            with self.assertRaisesRegex(MODULE.C4bError, "预算"):
                MODULE.validate_scenario_receipt(receipt, root)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            reference = receipt["facts"]["recoveryEvidence"]["inbox-persisted"]
            path = root / reference["path"]
            value = json.loads(path.read_text(encoding="utf-8"))
            value["lastObserved"] = 0
            MODULE.write_json(path, value)
            receipt["facts"]["recoveryEvidence"]["inbox-persisted"] = \
                MODULE.evidence_reference(root, path)
            with self.assertRaisesRegex(MODULE.C4bError, "观测语义"):
                MODULE.validate_scenario_receipt(receipt, root)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            reference = receipt["facts"]["recoveryEvidence"]["outbox-integrity"]
            path = root / reference["path"]
            value = json.loads(path.read_text(encoding="utf-8"))
            value["lastObserved"].pop("targetState")
            MODULE.write_json(path, value)
            receipt["facts"]["recoveryEvidence"]["outbox-integrity"] = \
                MODULE.evidence_reference(root, path)
            with self.assertRaisesRegex(MODULE.C4bError, "观测语义"):
                MODULE.validate_scenario_receipt(receipt, root)

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scenarios").mkdir()
            self._write_complete_evidence(root)
            receipt = json.loads((root / "scenarios" / "DB-01.json").read_text(encoding="utf-8"))
            reference = receipt["facts"]["recoveryEvidence"]["outbox-integrity"]
            path = root / reference["path"]
            value = json.loads(path.read_text(encoding="utf-8"))
            value["lastObserved"]["targetState"]["publishedAt"] = "2026-08-29T00:02:00Z"
            MODULE.write_json(path, value)
            receipt["facts"]["recoveryEvidence"]["outbox-integrity"] = \
                MODULE.evidence_reference(root, path)
            with self.assertRaisesRegex(MODULE.C4bError, "观测语义"):
                MODULE.validate_scenario_receipt(receipt, root)

    def test_capture_environment_keeps_jdk_stderr(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            MODULE.write_json(root / "run-manifest.json", {
                "runId": RUN_ID, "project": MODULE.project_for(RUN_ID)})

            def fake_run(command, **_kwargs):
                if command[:2] == ["java", "-version"]:
                    return subprocess.CompletedProcess(command, 0, "", "openjdk 21\n")
                if command[1:3] == ["container", "ls"]:
                    return subprocess.CompletedProcess(command, 0, "container-1\n", "")
                if command[1:3] in (["volume", "ls"], ["network", "ls"]):
                    return subprocess.CompletedProcess(command, 0, "resource-1\n", "")
                if command[1:3] == ["inspect", "--format"]:
                    return subprocess.CompletedProcess(command, 0, "sha256:" + "a" * 64 + "\n", "")
                return subprocess.CompletedProcess(command, 0, "docker\n", "")

            value = MODULE.capture_environment(root, MODULE.project_for(RUN_ID), fake_run)
            self.assertEqual("openjdk 21", value["jdk"])

    def test_shared_snapshot_ignores_health_log_but_detects_start_identity(self):
        state = {"started": "2026-08-27T00:00:00Z", "health": "one",
                 "endpoint": "endpoint-one", "network": "network-one"}

        def fake_run(command, **_kwargs):
            if command[1:3] == ["container", "ls"]:
                return subprocess.CompletedProcess(command, 0, "abc tc-postgres\n", "")
            if command[1] in ("volume", "network"):
                return subprocess.CompletedProcess(command, 0, "", "")
            body = [{"Id": "abc", "Name": "/tc-postgres", "Image": "sha256:image",
                     "Created": "fixed", "State": {"Status": "running",
                     "StartedAt": state["started"],
                     "Health": {"Status": "healthy", "Log": [state["health"]]}}, "Config": {},
                     "HostConfig": {}, "Mounts": [], "NetworkSettings": {"Networks": {
                         "tc-default": {"NetworkID": state["network"], "Aliases": ["postgres"],
                                        "IPAMConfig": None, "Links": None, "DriverOpts": None,
                                        "EndpointID": state["endpoint"], "IPAddress": "172.1.0.2"}}}}]
            return subprocess.CompletedProcess(command, 0, json.dumps(body), "")

        before = MODULE.snapshot_shared_resources(fake_run)
        state["health"] = "two"
        self.assertEqual(before, MODULE.snapshot_shared_resources(fake_run))
        state["endpoint"] = "endpoint-two"
        self.assertEqual(before, MODULE.snapshot_shared_resources(fake_run))
        state["network"] = "network-two"
        self.assertNotEqual(before, MODULE.snapshot_shared_resources(fake_run))
        state["network"] = "network-one"
        state["started"] = "2026-08-27T01:00:00Z"
        self.assertNotEqual(before, MODULE.snapshot_shared_resources(fake_run))

    def test_shared_snapshot_ignores_preexisting_restart_loop_timestamp(self):
        """资格前已异常的共享容器仍锁身份/配置，但 Docker 自身退避不是本片漂移。"""
        base = {"Id": "container-id", "Name": "/tc-emqx", "Image": "image-id",
                "Created": "fixed", "Config": {"Image": "emqx:6"}, "HostConfig": {},
                "Mounts": [], "NetworkSettings": {"Networks": {}}}
        first = {**base, "State": {"Status": "restarting", "StartedAt": "first",
                                    "Health": {"Status": "unhealthy"}}}
        second = {**base, "State": {"Status": "restarting", "StartedAt": "second",
                                     "Health": {"Status": "unhealthy"}}}
        self.assertEqual(MODULE._stable_resource_projection("container", first),
                         MODULE._stable_resource_projection("container", second))

    def test_probe_snapshot_rejects_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(MODULE.C4bError):
                MODULE.write_probe_snapshot(Path(directory), "database-before", {"token": "raw"})

    def test_rule_degradation_attribution_recomputes_quota_and_processed_commit(self):
        """裁决器必须由原始额度和 offset 复算，拒绝信任驱动自报布尔值。"""
        partitions = [{"partition": index, "currentOffset": 8 if index == 3 else 0,
                       "logStartOffset": 0, "logEndOffset": 8 if index == 3 else 0,
                       "lag": 0, "memberId": "member-1"} for index in range(12)]
        value = {
            "quotaDecisions": [
                {"metric": metric, "limit": 1_000_000, "tenantUsed": 1,
                 "softLimitBasisPoints": 8000, "degradeBasisPoints": 12000,
                 "status": "NORMAL"}
                for metric in ("UPLINK_MESSAGE", "UPLINK_BYTES", "TIME_SERIES_POINT")],
            "historicalStorageDegraded": False,
            "processed": {
                "topic": "tc.device.uplink.processed",
                "group": "things-link-ingestion-processed",
                "sourceLocations": [{"partition": 3, "offset": 7}],
                "groupSnapshot": {"topic": "tc.device.uplink.processed",
                                  "group": "things-link-ingestion-processed",
                                  "state": "Stable", "members": 1, "totalLag": 0,
                                  "partitions": partitions},
                "targetOffsetsCommitted": True,
            },
            "probeErrors": {}, "complete": True,
            "capturedAt": "2026-08-29T00:00:00Z",
        }
        MODULE.validate_rule_degradation_attribution(value)
        value["processed"]["targetOffsetsCommitted"] = False
        with self.assertRaisesRegex(MODULE.C4bError, "committed"):
            MODULE.validate_rule_degradation_attribution(value)

    def test_compose_controller_only_uses_exact_project_and_database_service(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = [root / "base.yml", root / "overlay.yml"]
            for item in files:
                item.write_text("services: {}\n", encoding="utf-8")
            calls = []

            def fake_run(command, **_kwargs):
                calls.append(command)
                return subprocess.CompletedProcess(command, 0, "", "")

            project = MODULE.project_for(RUN_ID)
            controller = MODULE.ComposeController(project, files, {}, fake_run)
            controller.stop_database()
            controller.start_database()
            self.assertTrue(all(command[command.index("-p") + 1] == project for command in calls))
            self.assertTrue(all("postgres" in command for command in calls))
            self.assertTrue(all("down" not in command and "volume" not in command for command in calls))


if __name__ == "__main__":
    unittest.main()
