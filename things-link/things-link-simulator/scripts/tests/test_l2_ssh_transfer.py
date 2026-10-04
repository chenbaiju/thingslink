"""SSH transport safety, common deadlines and download publication boundaries."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "l2_ssh_transfer.py"
spec = importlib.util.spec_from_file_location("transfer", SCRIPT)
MODULE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MODULE)


class SshTransferTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.data = b"public artifact"
        self.source = self.root / "source"; self.source.write_bytes(self.data)
        self.metadata = {"schemaVersion": 1, "runId": "run-1", "hostId": "host-1",
                         "environmentFingerprint": "a" * 64, "artifactName": "artifact.bin",
                         "sizeBytes": len(self.data), "sha256": hashlib.sha256(self.data).hexdigest()}
        self.task = {"direction": "UPLOAD", "target": "runner@host.example",
                     "remoteRoot": "/private/store", "metadata": self.metadata, "source": str(self.source)}

    def tearDown(self):
        self.tmp.cleanup()

    def plan(self, tasks=None, timeout=5):
        return {"schemaVersion": 1, "timeoutSeconds": timeout, "tasks": tasks or [self.task]}

    def test_concurrent_tasks_share_one_deadline(self):
        barrier = threading.Barrier(4)
        deadlines = []
        def transfer(task, deadline):
            deadlines.append(deadline)
            barrier.wait(timeout=2)
            return "PUBLISHED"
        with patch.object(MODULE, "transfer", side_effect=transfer):
            result = MODULE.run_batch(self.plan([self.task] * 4))
        self.assertEqual("PASS", result["result"])
        self.assertEqual(1, len(set(deadlines)))

    def test_one_failure_does_not_hide_other_results_or_expose_errors(self):
        with patch.object(MODULE, "transfer", side_effect=["PUBLISHED", RuntimeError("PRIVATE_PATH")]):
            result = MODULE.run_batch(self.plan([self.task] * 2))
        self.assertEqual("FAIL_OR_UNKNOWN", result["result"])
        self.assertEqual(["PASS", "FAIL_OR_UNKNOWN"], [r["result"] for r in result["transfers"]])
        self.assertNotIn("PRIVATE_PATH", json.dumps(result))

    def test_late_success_is_unknown(self):
        def late(*_):
            time.sleep(0.03)
            return "PUBLISHED"
        with patch.object(MODULE, "transfer", side_effect=late):
            self.assertEqual("FAIL_OR_UNKNOWN", MODULE.run_batch(self.plan(timeout=0.01))["result"])

    def test_all_tasks_validate_before_network(self):
        for bad in ({**self.task, "target": "-oProxyCommand=bad"},
                    {**self.task, "port": True}, {**self.task, "password": "forbidden"},
                    {**self.task, "metadata": {**self.metadata, "runId": "old-run"}}):
            with patch.object(MODULE, "transfer") as transfer:
                with self.assertRaises(ValueError): MODULE.run_batch(self.plan([self.task, bad]))
                transfer.assert_not_called()

    def test_ssh_strict_mode_and_shell_quoting(self):
        argv = MODULE.ssh_argv({**self.task, "remoteRoot": "/private/a'$(false)"}, 20)
        self.assertIn("StrictHostKeyChecking=yes", argv)
        self.assertIn("BatchMode=yes", argv)
        remote = shlex.split(argv[-1])
        self.assertEqual("/private/a'$(false)", remote[remote.index("--root") + 1])
        self.assertEqual(self.metadata, json.loads(remote[remote.index("--metadata") + 1]))

    def test_upload_exact_ack_required(self):
        for metadata, expected in ((self.metadata, "PUBLISHED"), ({**self.metadata, "runId": "old"}, None)):
            response = SimpleNamespace(returncode=0, stdout=json.dumps({
                "schemaVersion": 1, "status": "PUBLISHED", "metadata": metadata}).encode())
            with patch.object(MODULE.subprocess, "run", return_value=response):
                if expected:
                    self.assertEqual(expected, MODULE.transfer(self.task, time.monotonic() + 10))
                else:
                    with self.assertRaises(ValueError): MODULE.transfer(self.task, time.monotonic() + 10)

    @unittest.skipUnless(os.name == "posix", "Atomic local store uses POSIX filesystem semantics")
    def test_download_checks_integrity_before_publication(self):
        store = self.root / "store"; store.mkdir(mode=0o700)
        task = {k: v for k, v in self.task.items() if k != "source"}
        task.update(direction="DOWNLOAD", localRoot=str(store))
        for data in (b"x" * len(self.data), self.data):
            def run(*_, **kwargs):
                kwargs["stdout"].write(data)
                return SimpleNamespace(returncode=0)
            with patch.object(MODULE.subprocess, "run", side_effect=run):
                if data != self.data:
                    with self.assertRaises(ValueError): MODULE.transfer(task, time.monotonic() + 10)
                    self.assertFalse(list(store.rglob("payload")))
                else:
                    self.assertEqual("PUBLISHED", MODULE.transfer(task, time.monotonic() + 10))
                    self.assertEqual(self.data, next(store.rglob("payload")).read_bytes())

    def test_ssh_timeout_is_not_success(self):
        with patch.object(MODULE.subprocess, "run", side_effect=subprocess.TimeoutExpired("ssh", 1)):
            self.assertEqual("FAIL_OR_UNKNOWN", MODULE.run_batch(self.plan())["result"])


if __name__ == "__main__":
    unittest.main()
