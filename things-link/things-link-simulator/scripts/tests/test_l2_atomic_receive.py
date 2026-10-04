"""Real filesystem/concurrency and bounded CLI failure checks for atomic publication."""
import concurrent.futures
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "l2_atomic_receive.py"
spec = importlib.util.spec_from_file_location("receiver", SCRIPT)
MODULE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MODULE)


@unittest.skipUnless(os.name == "posix", "Receiver filesystem contract targets POSIX/Linux")
class AtomicReceiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.data = b"public artifact" * 1000
        self.metadata = self.meta(self.data)

    def tearDown(self):
        self.tmp.cleanup()

    def meta(self, data):
        return {"schemaVersion": 1, "runId": "test-run", "hostId": "test-host",
                "environmentFingerprint": "a" * 64, "artifactName": "artifact.bin",
                "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    def target(self, metadata=None):
        m = metadata or self.metadata
        return self.root / m["runId"] / m["environmentFingerprint"] / m["hostId"] / m["artifactName"]

    def put(self, data=None, metadata=None):
        return MODULE.receive(self.root, metadata or self.metadata,
                              io.BytesIO(self.data if data is None else data))

    def test_roundtrip_retry_and_empty(self):
        self.assertEqual("PUBLISHED", self.put()["status"])
        self.assertEqual(self.data, (self.target() / "payload").read_bytes())
        self.assertEqual(self.metadata, json.loads((self.target() / "metadata.json").read_text()))
        self.assertEqual("REUSED", self.put()["status"])
        empty = {**self.meta(b""), "artifactName": "empty.bin"}
        self.assertEqual("PUBLISHED", self.put(b"", empty)["status"])

    def test_bad_streams_never_publish_or_leave_staging(self):
        for data in (self.data[:-1], self.data + b"x", b"x" * len(self.data)):
            with self.subTest(size=len(data)), self.assertRaises(MODULE.TransferError): self.put(data)
            self.assertFalse(self.target().exists())
            self.assertFalse(list(self.root.rglob(".incoming-*")))

    def test_conflicting_digest_never_overwrites_original(self):
        self.put()
        with self.assertRaises(MODULE.TransferError): self.put(b"different", self.meta(b"different"))
        self.assertEqual(self.data, (self.target() / "payload").read_bytes())

    def test_old_run_and_fingerprint_have_separate_namespaces(self):
        self.put()
        for change in ({"runId": "old-run"}, {"environmentFingerprint": "b" * 64}):
            metadata = {**self.meta(b"older"), **change}
            self.assertEqual("PUBLISHED", self.put(b"older", metadata)["status"])
        self.assertEqual(self.data, (self.target() / "payload").read_bytes())

    def test_metadata_rejects_traversal_extra_fields_and_bool_size(self):
        for change in ({"runId": "../other"}, {"hostId": "/tmp"}, {"artifactName": "../bad"},
                       {"artifactName": "a/b"}, {"sizeBytes": True}, {"sizeBytes": -1},
                       {"sizeBytes": 33 * 1024 ** 3}, {"sha256": "bad"}, {"extra": "bad"}):
            with self.subTest(change=change), self.assertRaises(MODULE.TransferError):
                self.put(metadata={**self.metadata, **change})
        self.assertEqual([], list(self.root.iterdir()))

    def test_symlink_ancestor_and_final_rejected(self):
        outside = self.root / "outside"; outside.mkdir()
        alias = self.root / "test-run"
        alias.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(MODULE.TransferError): self.put()
        self.assertEqual([], list(outside.iterdir()))
        alias.unlink()
        self.target().parent.mkdir(parents=True)
        self.target().symlink_to(outside, target_is_directory=True)
        with self.assertRaises(MODULE.TransferError): self.put()

    def test_existing_empty_or_tampered_directory_rejected(self):
        self.target().mkdir(parents=True)
        with self.assertRaises(MODULE.TransferError): self.put()
        self.target().rmdir()
        self.put()
        (self.target() / "payload").write_bytes(b"tampered")
        with self.assertRaises(MODULE.TransferError): self.put()

    def test_same_identity_concurrent_publish(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: self.put(), range(8)))
        self.assertEqual(1, sum(item["status"] == "PUBLISHED" for item in results))
        MODULE.verify_existing(self.target(), self.metadata)
        self.assertFalse(list(self.root.rglob(".incoming-*")))

    def test_conflicting_concurrent_publish_has_exactly_one_winner(self):
        def submit(data):
            try:
                self.put(data, self.meta(data)); return True
            except MODULE.TransferError:
                return False
        values = [bytes([i]) * 1024 for i in range(8)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            self.assertEqual(1, sum(pool.map(submit, values)))
        content = (self.target() / "payload").read_bytes()
        MODULE.verify_existing(self.target(), self.meta(content))

    @unittest.skipUnless(sys.platform.startswith("linux"), "CLI deadline uses Linux signals")
    def test_cli_deadline_cleans_unfinished_stream(self):
        command = [sys.executable, str(SCRIPT), "--root", str(self.root), "--metadata",
                   json.dumps(self.metadata), "--timeout-seconds", "1"]
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE)
        try:
            self.assertEqual(1, child.wait(timeout=5))
            stdout, stderr = child.communicate()
            self.assertEqual(b"", stdout)
            self.assertIn(b"no success ACK", stderr)
            self.assertFalse(self.target().exists())
            self.assertFalse(list(self.root.rglob(".incoming-*")))
        finally:
            if child.poll() is None: child.kill()
            child.communicate()


if __name__ == "__main__":
    unittest.main()
