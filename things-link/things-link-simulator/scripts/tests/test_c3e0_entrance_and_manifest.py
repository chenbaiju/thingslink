"""C3e-0 注册式清单、外部传输边界与入口顺序回归。"""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]


def load(name: str):
    specification = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(specification)
    assert specification.loader is not None
    specification.loader.exec_module(module)
    return module


COORDINATOR = load("l2_distributed_coordinator")
MANIFEST = load("l2_evidence_manifest")
ENTRANCE = load("run_c3e0")
FINGERPRINT = "a" * 64


class RegisteredManifestTests(unittest.TestCase):
    """清单集合只能由注册表、冻结 host 和 envelope 原始文件合同推导。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.evidence = Path(self.temporary.name)
        self.run_id = "run-1"
        shards = [f"host-a-shard-{index:03d}" for index in range(10)]
        self.plan = {
            "schemaVersion": 1, "runId": self.run_id,
            "environmentFingerprint": FINGERPRINT, "targetDeviceCount": 10000,
            "artifacts": {"simulatorJarSha256": "b" * 64, "a4RunnerSha256": "c" * 64},
            "hosts": [{"hostId": "host-a", "shardIds": shards, "deviceCount": 10000,
                       "a4Argv": [sys.executable, "-c", "raise SystemExit(0)",
                                  "--run-id", self.run_id, "--device-count", "10000",
                                  "--shard-size", "1000", "--shard-id-prefix", "host-a",
                                  "--output-dir", "host-a"],
                       "a4TimeoutSeconds": 10,
                       "reportPath": "host-a/qualification-report.json",
                       "manifestRoot": "host-a/manifests"}],
        }
        self.write("run-metadata.json", {"schemaVersion": 1, "runId": self.run_id,
                                         "environmentFingerprint": FINGERPRINT})
        self.write("distributed-plan.json", self.plan)
        for name in ("environment-inventory.input.json", "environment-inventory.json",
                     "environment-inventory-report.json", "external-runbook.json"):
            self.write(name, {"schemaVersion": 1, "name": name})
        report_shards = []
        for shard_id in shards:
            self.text(f"host-a/logs/{shard_id}.log", f"resource-{shard_id}\n")
            self.text(f"host-a/manifests/{self.run_id}/{shard_id}/property_report.log",
                      f"message-{shard_id}\n")
            report_shards.append({"shardId": shard_id, "deviceCount": 1000,
                                  "propertyManifestLines": 1})
        report = {"schemaVersion": 1, "runId": self.run_id, "result": "PASS",
                  "configuration": {"shardSize": 1000, "deviceCount": 10000},
                  "fingerprint": {"jarSha256": "b" * 64, "runnerSha256": "c" * 64},
                  "checks": [{"name": "all", "passed": True}], "shards": report_shards}
        self.write("host-a/qualification-report.json", report)
        self.text("host-a/qualification-report.md", "# PASS\n")
        envelope = COORDINATOR.build_host_envelope(
            self.evidence / "host-a/qualification-report.json",
            self.evidence / "host-a/manifests", "host-a",
            plan_path=self.evidence / "distributed-plan.json")
        self.write("host-envelopes/host-a.host-envelope.json", envelope)
        common = {"schemaVersion": 1, "runId": self.run_id,
                  "environmentFingerprint": FINGERPRINT, "hostId": "host-a"}
        self.write("coordination/host-a.ready.json", {**common, "phase": "READY"})
        self.write("coordination/host-a.complete.json",
                   {**common, "phase": "COMPLETE", "outcome": "PASS",
                    "hostEnvelopeSha256": COORDINATOR.sha256_file(
                        self.evidence / "host-envelopes/host-a.host-envelope.json")})
        self.write("coordination/controller.start.json", {
            "schemaVersion": 1, "runId": self.run_id, "environmentFingerprint": FINGERPRINT,
            "phase": "START", "readyHosts": ["host-a"], "coordinationVersion": 1})
        self.write("coordination/coordination-result.json", {
            "schemaVersion": 1, "runId": self.run_id, "environmentFingerprint": FINGERPRINT,
            "phase": "COMPLETE", "outcome": "PASS", "hosts": [["host-a", "PASS"]]})
        qualification = COORDINATOR.aggregate_group(
            self.evidence / "distributed-plan.json", self.evidence / "host-envelopes")
        self.write("distributed-qualification.json", qualification)

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, name, value):
        path = self.evidence / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def text(self, name, value):
        path = self.evidence / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")

    def test_qualification_manifest_covers_signals_and_all_raw_a4_files(self):
        output = self.evidence / MANIFEST.OUTPUT_NAMES["C3E0_QUALIFICATION_INPUT"]
        result = MANIFEST.build_registered(self.evidence, "C3E0_QUALIFICATION_INPUT", output)
        files = set(result["files"])
        self.assertIn("coordination/host-a.ready.json", files)
        self.assertIn("host-a/qualification-report.json", files)
        self.assertIn("host-a/logs/host-a-shard-000.log", files)
        self.assertIn(
            "host-a/manifests/run-1/host-a-shard-000/property_report.log", files)
        # 任意 plan 扩展字段不具有注册文件能力。
        self.plan["extraEvidenceFiles"] = ["not-registered.txt"]
        self.write("distributed-plan.json", self.plan)
        rebuilt = MANIFEST.build_registered(self.evidence, "C3E0_QUALIFICATION_INPUT", output)
        self.assertNotIn("not-registered.txt", rebuilt["files"])

    def test_raw_resource_tamper_and_envelope_path_traversal_fail_closed(self):
        log = self.evidence / "host-a/logs/host-a-shard-000.log"
        log.write_text("tampered\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "resource log SHA-256 错配"):
            MANIFEST.registered_files(self.evidence, "C3E0_QUALIFICATION_INPUT")
        envelope_path = self.evidence / "host-envelopes/host-a.host-envelope.json"
        envelope = json.loads(envelope_path.read_text(encoding="utf-8"))
        envelope["shards"][0]["resourceLogFile"] = "../outside.log"
        self.write("host-envelopes/host-a.host-envelope.json", envelope)
        complete_path = self.evidence / "coordination/host-a.complete.json"
        complete = json.loads(complete_path.read_text(encoding="utf-8"))
        complete["hostEnvelopeSha256"] = COORDINATOR.sha256_file(envelope_path)
        self.write("coordination/host-a.complete.json", complete)
        with self.assertRaisesRegex(ValueError, "穿越"):
            MANIFEST.registered_files(self.evidence, "C3E0_QUALIFICATION_INPUT")

    def test_archive_recomputes_input_manifest_before_covering_reports(self):
        input_name = MANIFEST.OUTPUT_NAMES["C3E0_QUALIFICATION_INPUT"]
        MANIFEST.build_registered(self.evidence, "C3E0_QUALIFICATION_INPUT",
                                  self.evidence / input_name)
        self.write("machine-report.json", {"schemaVersion": 1, "validityStatus": "INVALID_GENERATOR"})
        self.text("machine-report.md", "# report\n")
        archive = MANIFEST.build_registered(
            self.evidence, "C3E0_ARCHIVE",
            self.evidence / MANIFEST.OUTPUT_NAMES["C3E0_ARCHIVE"])
        self.assertIn(input_name, archive["files"])
        self.assertIn("machine-report.md", archive["files"])
        self.text("environment-inventory.json", "{}\n")
        with self.assertRaisesRegex(ValueError, "input manifest SHA-256 错配"):
            MANIFEST.build_registered(
                self.evidence, "C3E0_ARCHIVE",
                self.evidence / MANIFEST.OUTPUT_NAMES["C3E0_ARCHIVE"])

    def test_archive_keeps_legacy_and_checks_independent_secret_receipt(self):
        MANIFEST.build_registered(self.evidence, "C3E0_QUALIFICATION_INPUT",
                                  self.evidence / MANIFEST.OUTPUT_NAMES["C3E0_QUALIFICATION_INPUT"])
        self.write("machine-report.json", {"validityStatus": "INVALID_GENERATOR"})
        self.text("machine-report.md", "# Invalid generator\n")
        self.write("cleanup-receipt.json", {"legacy": True})
        receipt = {"schemaVersion": 2, "runId": self.run_id, "environmentFingerprint": FINGERPRINT,
                   "operation": "PURGE_SECRETS", "cleanupKind": "SECRET", "result": "PASS",
                   "secretDirectoryPurged": True, "cloudCleanupPerformed": False,
                   "fixtureCleanupPerformed": False}
        for result in ("PASS", "FAIL"):
            self.write("secret-cleanup-receipt.json",
                       {**receipt, "result": result, "secretDirectoryPurged": result == "PASS"})
            names = MANIFEST.registered_files(self.evidence, "C3E0_ARCHIVE")
            self.assertIn("secret-cleanup-receipt.json", names)
            self.assertIn("cleanup-receipt.json", names)
        for wrong in ({"runId": "other"}, {"environmentFingerprint": "b" * 64},
                      {"cleanupKind": "ENVIRONMENT"}, {"secretDirectoryPurged": False},
                      {"cloudCleanupPerformed": True}, {"schemaVersion": 1}):
            self.write("secret-cleanup-receipt.json", {**receipt, **wrong})
            with self.subTest(wrong=wrong), self.assertRaisesRegex(ValueError, "secret cleanup receipt"):
                MANIFEST.registered_files(self.evidence, "C3E0_ARCHIVE")


class EntranceOrderTests(unittest.TestCase):
    """入口首先拒绝无效环境，并明确停在外部 worker 边界。"""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name).resolve()
        self.evidence = self.base / "evidence"
        self.environment = self.base / "environment.json"
        self.environment.write_text("{}", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def config(self):
        return {"schemaVersion": 1, "runId": "run-1", "evidenceDir": str(self.evidence),
                "environmentInventoryInput": str(self.environment),
                "ownerSecrets": str(self.base / "missing-owners.json"),
                "secretTempRoot": str(self.base / "secrets")}

    def test_invalid_environment_is_first_and_no_remote_or_secret_step_runs(self):
        calls = []
        def reject_environment(argv, **_kwargs):
            calls.append(argv)
            return SimpleNamespace(returncode=1)
        with self.assertRaisesRegex(ENTRANCE.EntranceError, "工具步骤失败"):
            ENTRANCE.prepare(self.config(), reject_environment)
        self.assertEqual("l2_environment_inventory.py", Path(calls[0][1]).name)
        self.assertFalse((self.evidence / "external-runbook.json").exists())
        self.assertFalse((self.base / "secrets").exists())

    def test_prepare_stops_at_explicit_external_worker_boundary(self):
        owner = self.base / "owners.json"; owner.write_text("{}", encoding="utf-8")
        metadata = self.base / "metadata.json"
        metadata.write_text(json.dumps({"schemaVersion": 1, "runId": "run-1",
                                        "scope": "C3E0", "profile": "TOOL_QUALIFICATION",
                                        "environmentFingerprint": FINGERPRINT}), encoding="utf-8")
        plan = self.base / "plan.json"
        shards = [f"host-a-shard-{index:03d}" for index in range(10)]
        plan.write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "targetDeviceCount": 10000,
            "artifacts": {"simulatorJarSha256": "b" * 64, "a4RunnerSha256": "c" * 64},
            "hosts": [{"hostId": "host-a", "shardIds": shards, "deviceCount": 10000,
                       "a4Argv": [sys.executable, "-c", "raise SystemExit(0)",
                                  "--run-id", "run-1", "--device-count", "10000",
                                  "--shard-size", "1000", "--shard-id-prefix", "host-a",
                                  "--output-dir", "host-a"],
                       "a4TimeoutSeconds": 10,
                       "reportPath": "host-a/qualification-report.json",
                       "manifestRoot": "host-a/manifests"}]}), encoding="utf-8")
        config = self.config()
        config.update({"ownerSecrets": str(owner), "runMetadata": str(metadata),
                       "distributedPlan": str(plan)})
        calls = []
        def pass_environment(argv, **_kwargs):
            calls.append(argv)
            normalized = Path(argv[argv.index("--normalized-output") + 1])
            report = Path(argv[argv.index("--report-output") + 1])
            normalized.write_text(json.dumps({
                "artifacts": {"simulatorJarSha256": "b" * 64,
                              "scriptsSha256": {"a4_qualification.py": "c" * 64}},
                "generators": [{"hostId": "host-a"}]}), encoding="utf-8")
            report.write_text(json.dumps({"schemaVersion": 1, "status": "PASS",
                                          "comparisonFingerprint": FINGERPRINT}), encoding="utf-8")
            return SimpleNamespace(returncode=0)
        state = ENTRANCE.prepare(config, pass_environment)
        self.assertEqual("AWAITING_EXTERNAL_COORDINATION", state["phase"])
        self.assertEqual(1, len(calls))
        runbook = json.loads((self.evidence / "external-runbook.json").read_text(encoding="utf-8"))
        self.assertTrue(runbook["transportBoundary"]["noExecutionClaim"])
        self.assertEqual("EXTERNAL_GENERATOR_HOST", runbook["hosts"][0]["executeOn"])

    def test_prepare_rejects_inventory_plan_host_drift_before_runbook(self):
        owner = self.base / "owners.json"; owner.write_text("{}", encoding="utf-8")
        metadata = self.base / "metadata.json"
        metadata.write_text(json.dumps({"schemaVersion": 1, "runId": "run-1",
                                        "scope": "C3E0", "profile": "TOOL_QUALIFICATION",
                                        "environmentFingerprint": FINGERPRINT}), encoding="utf-8")
        plan = self.base / "plan.json"
        plan.write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "environmentFingerprint": FINGERPRINT,
            "targetDeviceCount": 10000,
            "artifacts": {"simulatorJarSha256": "b" * 64, "a4RunnerSha256": "c" * 64},
            "hosts": [{"hostId": "host-a", "shardIds": [f"host-a-shard-{i:03d}" for i in range(10)],
                       "deviceCount": 10000,
                       "a4Argv": [sys.executable, "-c", "raise SystemExit(0)", "--run-id", "run-1",
                                  "--device-count", "10000", "--shard-size", "1000",
                                  "--shard-id-prefix", "host-a", "--output-dir", "host-a"],
                       "a4TimeoutSeconds": 10, "reportPath": "host-a/qualification-report.json",
                       "manifestRoot": "host-a/manifests"}]}), encoding="utf-8")
        config = self.config(); config.update({"ownerSecrets": str(owner), "runMetadata": str(metadata),
                                               "distributedPlan": str(plan)})
        def drifted_environment(argv, **_kwargs):
            Path(argv[argv.index("--normalized-output") + 1]).write_text(json.dumps({
                "artifacts": {"simulatorJarSha256": "b" * 64,
                              "scriptsSha256": {"a4_qualification.py": "c" * 64}},
                "generators": [{"hostId": "host-b"}]}), encoding="utf-8")
            Path(argv[argv.index("--report-output") + 1]).write_text(json.dumps({
                "schemaVersion": 1, "status": "PASS", "comparisonFingerprint": FINGERPRINT}),
                encoding="utf-8")
            return SimpleNamespace(returncode=0)
        with self.assertRaisesRegex(ENTRANCE.EntranceError, "generator hostId 集不一致"):
            ENTRANCE.prepare(config, drifted_environment)
        self.assertFalse((self.evidence / "external-runbook.json").exists())

    def test_finalize_runtime_error_retains_run_secret_directory_and_recovery_state(self):
        self.evidence.mkdir()
        (self.evidence / "c3e0-entrance-state.json").write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "phase": "QUALIFICATION_PASS",
            "environmentFingerprint": FINGERPRINT}), encoding="utf-8")
        config = self.config()
        owner = self.base / "owners.json"; owner.write_text("{}", encoding="utf-8")
        config["ownerSecrets"] = str(owner)
        for field in ("fixtureAssignmentPlan", "metricsSourcePlan"):
            path = self.base / f"{field}.json"; path.write_text("{}", encoding="utf-8")
            config[field] = str(path)
        config["runnerOutcome"] = "success"
        config["confirmIsolatedDisposableEnvironmentOrSnapshot"] = True
        config["fixture"] = {"baseUrl": "http://sut.invalid", "policyId": "policy-id",
                             "postgresUser": "postgres", "postgresDb": "thingslink"}
        marker = self.base / "secrets" / "keep.txt"
        marker.parent.mkdir(); marker.write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(ENTRANCE.EntranceError, "工具步骤失败"):
            ENTRANCE.finalize(config, lambda *_args, **_kwargs: SimpleNamespace(returncode=1))
        self.assertTrue(marker.is_file())
        self.assertTrue((self.base / "secrets/c3e0-run-1").is_dir())
        state = json.loads((self.evidence / "c3e0-entrance-state.json").read_text(encoding="utf-8"))
        self.assertEqual("FIXTURE_PREPARE_IN_PROGRESS", state["phase"])
        self.assertTrue(state["credentialsRetainedOutsideEvidence"])
        self.assertNotIn(str(self.base / "secrets"), json.dumps(state))

    def test_finalize_requires_disposable_environment_or_snapshot_before_fixture_api(self):
        self.evidence.mkdir()
        (self.evidence / "c3e0-entrance-state.json").write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "phase": "QUALIFICATION_PASS",
            "environmentFingerprint": FINGERPRINT}), encoding="utf-8")
        owner = self.base / "owners.json"; owner.write_text("{}", encoding="utf-8")
        config = self.config(); config["ownerSecrets"] = str(owner)
        config["runnerOutcome"] = "success"
        config["fixture"] = {"baseUrl": "http://sut.invalid", "policyId": "policy-id",
                             "postgresUser": "postgres", "postgresDb": "thingslink"}
        for field in ("fixtureAssignmentPlan", "metricsSourcePlan"):
            path = self.base / f"{field}.json"; path.write_text("{}", encoding="utf-8")
            config[field] = str(path)
        calls = []
        with self.assertRaisesRegex(ENTRANCE.EntranceError, "隔离可销毁环境|可恢复快照"):
            ENTRANCE.finalize(config, lambda argv, **_kwargs: calls.append(argv))
        self.assertEqual([], calls)

    def test_purge_secrets_removes_only_exact_run_directory(self):
        self.evidence.mkdir()
        (self.evidence / "c3e0-entrance-state.json").write_text(json.dumps({
            "schemaVersion": 1, "runId": "run-1", "phase": "MACHINE_REPORT_READY",
            "environmentFingerprint": FINGERPRINT,
            "credentialsRetainedOutsideEvidence": True}), encoding="utf-8")
        config = self.config()
        config["confirmProfilesCompleteOrFixtureCleaned"] = True
        run_secret = self.base / "secrets/c3e0-run-1"; run_secret.mkdir(parents=True)
        (run_secret / "credential.json").write_text("secret", encoding="utf-8")
        sibling = self.base / "secrets/keep.txt"; sibling.write_text("keep", encoding="utf-8")
        result = ENTRANCE.purge_secrets(config)
        self.assertFalse(run_secret.exists())
        self.assertTrue(sibling.is_file())
        self.assertFalse(result["credentialsRetainedOutsideEvidence"])
        receipt = json.loads((self.evidence / "secret-cleanup-receipt.json").read_text(encoding="utf-8"))
        self.assertFalse(receipt["cloudCleanupPerformed"])


class SecretCleanupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name).resolve()
        self.evidence = self.base / "evidence"
        self.evidence.mkdir()
        self.root = self.base / "secrets"
        self.target = self.root / "c3e0-run-1"
        self.target.mkdir(parents=True)
        (self.target / "secret").write_text("DO_NOT_ARCHIVE")
        self.state = {"runId": "run-1", "environmentFingerprint": FINGERPRINT,
                      "phase": "MACHINE_REPORT_READY", "credentialsRetainedOutsideEvidence": True}
        self.state_path = self.evidence / "c3e0-entrance-state.json"
        self.state_path.write_text(json.dumps(self.state))
        self.config = {"schemaVersion": 1, "runId": "run-1", "evidenceDir": str(self.evidence),
                       "secretTempRoot": str(self.root), "confirmProfilesCompleteOrFixtureCleaned": True}

    def tearDown(self):
        self.tmp.cleanup()

    def test_success_retry_and_old_other_receipts_untouched(self):
        for name in ("cleanup-receipt.json", "fixture-cleanup-receipt.json", "environment-cleanup-receipt.json"):
            (self.evidence / name).write_text("unchanged")
        for existed in (True, False):
            ENTRANCE.purge_secrets(self.config)
            text = (self.evidence / "secret-cleanup-receipt.json").read_text()
            receipt = json.loads(text)
            self.assertEqual(existed, receipt["directoryExistedBefore"])
            self.assertEqual("PASS", receipt["result"])
            self.assertNotIn("DO_NOT_ARCHIVE", text)
            self.assertNotIn(str(self.root), text)
        for name in ("cleanup-receipt.json", "fixture-cleanup-receipt.json", "environment-cleanup-receipt.json"):
            self.assertEqual("unchanged", (self.evidence / name).read_text())

    def test_root_and_ancestor_symlink_rejected_before_delete(self):
        alias = self.base / "alias"
        try:
            alias.symlink_to(self.root, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"symlink unavailable: {type(error).__name__}")
        for path in (alias, alias / "nested"):
            with self.subTest(path=path), self.assertRaises(ENTRANCE.EntranceError):
                ENTRANCE.purge_secrets({**self.config, "secretTempRoot": str(path)})
            self.assertTrue((self.target / "secret").is_file())

    def test_target_symlink_rejected(self):
        alias = self.root / "c3e0-other"
        try:
            alias.symlink_to(self.target, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"symlink unavailable: {type(error).__name__}")
        self.state_path.write_text(json.dumps({**self.state, "runId": "other"}))
        with self.assertRaises(ENTRANCE.EntranceError):
            ENTRANCE.purge_secrets({**self.config, "runId": "other"})
        self.assertTrue((self.target / "secret").is_file())

    def test_overlap_root_and_traversal_rejected(self):
        for root in (self.base, self.evidence, self.evidence / "nested", Path(self.base.anchor), self.root / ".." / "secrets"):
            with self.subTest(root=root), self.assertRaises(ENTRANCE.EntranceError):
                ENTRANCE.purge_secrets({**self.config, "secretTempRoot": str(root)})
            self.assertTrue(self.target.is_dir())

    def test_identity_and_confirmation_rejected(self):
        for state in ({**self.state, "runId": "other"}, {**self.state, "environmentFingerprint": None},
                      {**self.state, "environmentFingerprint": "0" * 64}):
            self.state_path.write_text(json.dumps(state))
            with self.assertRaises(ENTRANCE.EntranceError): ENTRANCE.purge_secrets(self.config)
            self.assertTrue(self.target.is_dir())
        self.state_path.write_text(json.dumps(self.state))
        with self.assertRaises(ENTRANCE.EntranceError):
            ENTRANCE.purge_secrets({**self.config, "confirmProfilesCompleteOrFixtureCleaned": False})

    def test_delete_failure_records_fail_without_changing_state(self):
        with patch.object(ENTRANCE.shutil, "rmtree", side_effect=PermissionError("DO_NOT_ARCHIVE")):
            with self.assertRaises(ENTRANCE.EntranceError): ENTRANCE.purge_secrets(self.config)
        text = (self.evidence / "secret-cleanup-receipt.json").read_text()
        self.assertEqual("FAIL", json.loads(text)["result"])
        self.assertNotIn("DO_NOT_ARCHIVE", text)
        self.assertEqual(self.state, json.loads(self.state_path.read_text()))
        self.assertFalse((self.evidence / ".secret-cleanup.lock").exists())

    def test_concurrent_lock_refuses_delete(self):
        (self.evidence / ".secret-cleanup.lock").mkdir()
        with self.assertRaises(ENTRANCE.EntranceError): ENTRANCE.purge_secrets(self.config)
        self.assertTrue(self.target.is_dir())

    def test_atomic_writer_does_not_follow_old_temporary_symlink(self):
        victim = self.base / "victim"; victim.write_text("keep")
        output = self.evidence / "output.json"
        try:
            output.with_suffix(".json.tmp").symlink_to(victim)
        except OSError as error:
            self.skipTest(f"symlink unavailable: {type(error).__name__}")
        ENTRANCE.write_atomic(output, {"result": "PASS"})
        self.assertEqual("keep", victim.read_text())
        self.assertEqual("PASS", json.loads(output.read_text())["result"])


if __name__ == "__main__":
    unittest.main()
