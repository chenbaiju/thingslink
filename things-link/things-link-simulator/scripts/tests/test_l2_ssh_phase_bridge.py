"""Phase transport with real existing coordinator and isolated remote-helper processes."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "l2_ssh_phase_bridge.py"
spec = importlib.util.spec_from_file_location("bridge", SCRIPT)
MODULE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MODULE)


def make_plan():
    hosts = []
    for host in ("host-a", "host-b"):
        hosts.append({"hostId": host, "deviceCount": 5000,
                      "shardIds": [f"{host}-shard-{i:03d}" for i in range(5)],
                      "a4Argv": ["python3", "a4_qualification.py", "--run-id", "run-1",
                                 "--device-count", "5000", "--shard-size", "1000",
                                 "--shard-id-prefix", host, "--output-dir", host],
                      "a4TimeoutSeconds": 20, "reportPath": host + "/qualification-report.json",
                      "manifestRoot": host + "/manifests"})
    return {"schemaVersion": 1, "runId": "run-1", "environmentFingerprint": "a" * 64,
            "targetDeviceCount": 10000, "artifacts": {"simulatorJarSha256": "b" * 64,
            "a4RunnerSha256": "c" * 64}, "hosts": hosts}


@unittest.skipUnless(os.name == "posix", "Remote phase helper requires POSIX")
class PhaseBridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.plan = make_plan()
        self.plan_path = self.root / "plan.json"; self.plan_path.write_text(json.dumps(self.plan))
        self.signals = self.root / "signals"; self.signals.mkdir()
        self.peers = []
        for host in ("host-a", "host-b"):
            remote = self.root / host; remote.mkdir(mode=0o700)
            self.peers.append({"hostId": host, "target": "runner@host.example", "remoteRoot": str(remote)})

    def tearDown(self):
        self.tmp.cleanup()

    def signal(self, host, phase, **extra):
        return {"schemaVersion": 1, "runId": "run-1", "environmentFingerprint": "a" * 64,
                "hostId": host, "phase": phase, **extra}

    def write_remote(self, peer, phase, **extra):
        path = Path(peer["remoteRoot"]) / "coordination" / (peer["hostId"] + "." + phase.lower() + ".json")
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(self.signal(peer["hostId"], phase, **extra)))

    def local_exchange(self, peer, name, deadline, data=None):
        run = subprocess.run([sys.executable, "-c", MODULE.REMOTE, peer["remoteRoot"], name,
                              "read" if data is None else "write"], input=b"" if data is None else data,
                             capture_output=True, timeout=max(0.01, deadline-time.monotonic()))
        if run.returncode == 75 and data is None: return None
        if run.returncode: raise RuntimeError("helper failure")
        return run.stdout

    def test_existing_coordinator_complete_roundtrip_and_concurrent_ready(self):
        for peer in self.peers: self.write_remote(peer, "READY")
        barrier = threading.Barrier(2)
        def exchange(peer, name, deadline, data=None):
            if name.endswith(".ready.json"): barrier.wait(timeout=2)
            result = self.local_exchange(peer, name, deadline, data)
            if data is not None: self.write_remote(peer, "COMPLETE", outcome="PASS", hostEnvelopeSha256="d"*64)
            return result
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            verdict = pool.submit(MODULE.coordinator.coordinate, self.plan_path, self.signals, 3, 3, 0.01)
            result = MODULE.bridge(self.plan_path, self.peers, self.signals, 3, 3, 3, 0.01, exchange)
            self.assertEqual("PASS", verdict.result()["outcome"])
        self.assertEqual("TRANSPORT_COMPLETE", result["result"])
        self.assertFalse(result["environmentQualificationGranted"])
        for peer in self.peers:
            start = json.loads((Path(peer["remoteRoot"])/"coordination/controller.start.json").read_text())
            self.assertEqual(["host-a", "host-b"], start["readyHosts"])

    def test_old_run_wrong_host_wrong_phase_rejected(self):
        for change in ({"schemaVersion": True}, {"runId": "old"}, {"hostId": "other"}, {"phase": "COMPLETE"},
                       {"environmentFingerprint": "b" * 64}):
            def exchange(peer, *_): return json.dumps({**self.signal(peer["hostId"], "READY"), **change}).encode()
            with self.subTest(change=change), self.assertRaises(ValueError):
                MODULE.collect(self.plan, self.peers, self.signals, "READY", 1, 0.01, exchange)
        self.assertEqual([], list(self.signals.iterdir()))

    def test_partial_network_failure_does_not_emit_start(self):
        def exchange(peer, *_):
            if peer["hostId"] == "host-b": raise OSError("disconnected")
            return json.dumps(self.signal(peer["hostId"], "READY")).encode()
        with self.assertRaises(OSError):
            MODULE.bridge(self.plan_path, self.peers, self.signals, 1, 1, 1, 0.01, exchange)
        self.assertFalse((self.signals / "controller.start.json").exists())
        self.assertFalse((self.signals / ".ssh-bridge.lock").exists())

    def test_missing_and_late_peers_are_bounded(self):
        for delay in (0, 0.03):
            def exchange(*_): time.sleep(delay); return None
            with self.assertRaises(TimeoutError):
                MODULE.collect(self.plan, self.peers, self.signals, "READY", 0.02, 0.001, exchange)

    def test_peer_set_and_duplicate_json_rejected(self):
        for peers in (self.peers[:1], [self.peers[0]] * 2):
            with self.assertRaises(ValueError): MODULE.validate_peers(self.plan, peers)
        with self.assertRaises(ValueError): MODULE.parse_signal(b'{"runId":"a","runId":"b"}')

    def test_remote_error_is_preserved_not_success(self):
        def exchange(peer, *_): return json.dumps(self.signal(peer["hostId"], "COMPLETE", outcome="ERROR")).encode()
        with self.assertRaises(RuntimeError):
            MODULE.collect(self.plan, self.peers, self.signals, "COMPLETE", 1, 0.01, exchange)
        self.assertEqual("ERROR", json.loads((self.signals / "host-a.complete.json").read_text())["outcome"])

    def test_remote_start_replay_conflict_and_symlink(self):
        peer = self.peers[0]; deadline = time.monotonic() + 3
        self.local_exchange(peer, "controller.start.json", deadline, b'{"runId":"one"}')
        self.local_exchange(peer, "controller.start.json", deadline, b'{"runId":"one"}')
        with self.assertRaises(RuntimeError):
            self.local_exchange(peer, "controller.start.json", deadline, b'{"runId":"other"}')
        path = Path(peer["remoteRoot"])/"coordination/controller.start.json"
        path.unlink(); path.symlink_to(self.plan_path)
        with self.assertRaises(RuntimeError): self.local_exchange(peer, "controller.start.json", deadline)


if __name__ == "__main__":
    unittest.main()
