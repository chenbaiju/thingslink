#!/usr/bin/env python3
"""Bridge immutable phase files over SSH; the existing coordinator retains verdict authority."""
import argparse
import concurrent.futures
import importlib.util
import json
import math
from pathlib import Path
import shlex
import subprocess
import sys
import time


def sibling(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


coordinator = sibling("l2_distributed_coordinator")
transport = sibling("l2_ssh_transfer")

# Only fixed single-level phase filenames are accepted; no shell evaluates these paths.
REMOTE = r'''
import os,sys,tempfile
from pathlib import Path
root,name,action=sys.argv[1:]
root=Path(root)
if not root.is_absolute() or '..' in root.parts: sys.exit(2)
if any(p.is_symlink() for p in (root,*root.parents)): sys.exit(2)
if not root.is_dir() or root.stat().st_mode & 0o077 or root.stat().st_uid != os.geteuid(): sys.exit(2)
if '/' in name or '\\' in name or not name.endswith('.json'): sys.exit(2)
directory=root/'coordination'
if directory.is_symlink(): sys.exit(2)
path=directory/name
if path.is_symlink(): sys.exit(2)
if action=='read':
    if not path.exists(): sys.exit(75)
    if not path.is_file() or path.stat().st_size>65536: sys.exit(2)
    with path.open('rb') as source: data=source.read(65537)
    if len(data)>65536: sys.exit(2)
    sys.stdout.buffer.write(data)
elif action=='write':
    data=sys.stdin.buffer.read(65537)
    if not data or len(data)>65536: sys.exit(2)
    directory.mkdir(mode=0o700,exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=directory,delete=False) as target:
        temporary=Path(target.name)
        target.write(data);target.flush();os.fsync(target.fileno())
    try:
        try: os.link(temporary,path)
        except FileExistsError:
            if path.is_symlink() or not path.is_file() or path.stat().st_size!=len(data) or path.read_bytes()!=data: sys.exit(2)
        for folder in (directory,root):
            fd=os.open(folder,os.O_RDONLY)
            try: os.fsync(fd)
            finally: os.close(fd)
    finally: temporary.unlink(missing_ok=True)
else: sys.exit(2)
'''


def parse_signal(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate signal field")
            result[key] = value
        return result
    result = json.loads(data, object_pairs_hook=pairs)
    if not isinstance(result, dict):
        raise ValueError("phase signal must be object")
    return result


def exchange(peer, name, deadline, data=None):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("phase deadline exhausted")
    # Reuse only the fixed SSH safety options; replace the artifact command with the phase helper.
    dummy = {**peer, "direction": "UPLOAD", "metadata": {}}
    argv = transport.ssh_argv(dummy, remaining)[:-1]
    argv.append(shlex.join(["python3", "-c", REMOTE, peer["remoteRoot"], name,
                           "read" if data is None else "write"]))
    result = subprocess.run(argv, input=b"" if data is None else data, capture_output=True,
                            timeout=max(0.001, min(5, deadline - time.monotonic())), check=False)
    if result.returncode == 75 and data is None:
        return None
    if result.returncode:
        raise RuntimeError("SSH phase transfer failed")
    if time.monotonic() >= deadline:
        raise TimeoutError("late phase response")
    return result.stdout


def validate_peers(plan, peers):
    if not isinstance(peers, list) or not 1 <= len(peers) <= 32:
        raise ValueError("1..32 explicit peers required")
    ids = []
    for peer in peers:
        if not isinstance(peer, dict) or not {"hostId", "target", "remoteRoot"} <= set(peer) \
                or set(peer) - {"hostId", "target", "remoteRoot", "port", "controlPath"}:
            raise ValueError("invalid peer schema")
        ids.append(peer["hostId"])
        task = {k: v for k, v in peer.items() if k != "hostId"}
        task.update(direction="UPLOAD", source="/unused", metadata={
            "schemaVersion": 1, "runId": plan["runId"], "hostId": peer["hostId"],
            "environmentFingerprint": plan["environmentFingerprint"], "artifactName": "phase.json",
            "sizeBytes": 0, "sha256": "0" * 64})
        transport.validate_task(task)
    if len(ids) != len(set(ids)) or set(ids) != {h["hostId"] for h in plan["hosts"]}:
        raise ValueError("peer identities differ from frozen plan")


def collect(plan, peers, signal_dir, phase, timeout, poll, exchange_fn=exchange):
    deadline = time.monotonic() + timeout
    pending = {p["hostId"]: p for p in peers}
    received = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(peers)) as pool:
        while pending:
            if time.monotonic() >= deadline:
                raise TimeoutError("missing phase peers")
            ids = list(pending)
            calls = [pool.submit(exchange_fn, pending[host], f"{host}.{phase.lower()}.json", deadline)
                     for host in ids]
            for host, call in zip(ids, calls):
                data = call.result()
                if time.monotonic() >= deadline:
                    raise TimeoutError("late phase response")
                if data is None:
                    continue
                signal = parse_signal(data)
                if type(signal.get("schemaVersion")) is not int:
                    raise ValueError("invalid phase schemaVersion")
                expected = {"schemaVersion": 1, "runId": plan["runId"],
                            "environmentFingerprint": plan["environmentFingerprint"],
                            "hostId": host, "phase": phase}
                if any(signal.get(key) != value for key, value in expected.items()):
                    raise ValueError("remote phase identity mismatch")
                if phase == "COMPLETE" and signal.get("outcome") not in {"PASS", "FAIL", "ERROR"}:
                    raise ValueError("invalid COMPLETE outcome")
                coordinator.write_json_atomic(signal_dir / f"{host}.{phase.lower()}.json",
                                               signal, immutable=True)
                if phase == "COMPLETE" and signal["outcome"] == "ERROR":
                    raise RuntimeError("remote worker reported ERROR")
                received[host] = signal
                del pending[host]
            if pending:
                time.sleep(min(poll, max(0, deadline - time.monotonic())))
    return received


def bridge(plan_path, peers, signal_dir, ready_timeout, start_timeout, complete_timeout, poll=0.2,
           exchange_fn=exchange):
    plan, hosts = coordinator.load_plan(plan_path)
    validate_peers(plan, peers)
    for value in (ready_timeout, start_timeout, complete_timeout, poll):
        if type(value) not in (int, float) or not math.isfinite(value) or not 0 < value <= 3600:
            raise ValueError("finite positive phase budgets required")
    signal_dir = Path(signal_dir)
    signal_dir.mkdir(parents=True, exist_ok=True)
    lock = signal_dir / ".ssh-bridge.lock"
    lock.mkdir()
    try:
        collect(plan, peers, signal_dir, "READY", ready_timeout, poll, exchange_fn)
        deadline = time.monotonic() + start_timeout
        start = coordinator.wait_controller_start(signal_dir, plan, hosts, deadline, poll)
        data = (json.dumps(start, sort_keys=True, separators=(",", ":")) + "\n").encode()
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(peers)) as pool:
            calls = [pool.submit(exchange_fn, peer, "controller.start.json", deadline, data) for peer in peers]
            for call in calls:
                call.result()
        completed = collect(plan, peers, signal_dir, "COMPLETE", complete_timeout, poll, exchange_fn)
        return {"schemaVersion": 1, "runId": plan["runId"],
                "environmentFingerprint": plan["environmentFingerprint"], "result": "TRANSPORT_COMPLETE",
                "hostOutcomes": {host: signal["outcome"] for host, signal in completed.items()},
                "environmentQualificationGranted": False}
    finally:
        lock.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--peers", type=Path, required=True)
    parser.add_argument("--signal-dir", type=Path, required=True)
    parser.add_argument("--ready-timeout", type=float, default=300)
    parser.add_argument("--start-timeout", type=float, default=300)
    parser.add_argument("--complete-timeout", type=float, default=1200)
    args = parser.parse_args()
    try:
        result = bridge(args.plan, json.loads(args.peers.read_text()), args.signal_dir,
                        args.ready_timeout, args.start_timeout, args.complete_timeout)
        print(json.dumps(result, sort_keys=True))
        return 0
    except Exception as error:
        print(json.dumps({"result": "ERROR", "errorType": type(error).__name__,
                          "environmentQualificationGranted": False}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
