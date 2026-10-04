#!/usr/bin/env python3
"""Concurrent bounded SSH artifact transfer. Credentials belong to SSH, never the plan."""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time

RECEIVER = Path(__file__).with_name("l2_atomic_receive.py")
spec = importlib.util.spec_from_file_location("l2_atomic_receive", RECEIVER)
receiver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receiver)


def validate_task(task):
    required = {"direction", "target", "remoteRoot", "metadata"}
    optional = {"port", "controlPath", "source", "localRoot"}
    if not isinstance(task, dict) or not required <= set(task) or set(task) - required - optional:
        raise ValueError("invalid task fields")
    if task["direction"] not in {"UPLOAD", "DOWNLOAD"}:
        raise ValueError("invalid direction")
    if task["direction"] == "DOWNLOAD" and os.name != "posix":
        raise ValueError("atomic local download store currently requires POSIX")
    if not isinstance(task["target"], str) or not re.fullmatch(
            r"[a-zA-Z0-9_][a-zA-Z0-9_.-]*@[a-zA-Z0-9][a-zA-Z0-9.-]*", task["target"]):
        raise ValueError("explicit user@host required")
    port = task.get("port", 22)
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("invalid SSH port")
    root = task["remoteRoot"]
    if not isinstance(root, str) or not root.startswith("/") or ".." in Path(root).parts:
        raise ValueError("absolute remote root required")
    receiver.validate(task["metadata"])
    for key in ("controlPath", "source", "localRoot"):
        if key in task and (not isinstance(task[key], str) or not Path(task[key]).is_absolute()):
            raise ValueError("absolute local paths required")
    required_path = "source" if task["direction"] == "UPLOAD" else "localRoot"
    if required_path not in task:
        raise ValueError("transfer path missing")
    if task["direction"] == "UPLOAD" and "localRoot" in task \
            or task["direction"] == "DOWNLOAD" and "source" in task:
        raise ValueError("ambiguous transfer paths")


def ssh_argv(task, remaining):
    # Fixed helper bytes are transported as public code; caller data only enters quoted arguments.
    encoded = base64.b64encode(RECEIVER.read_bytes()).decode("ascii")
    program = "import base64;exec(compile(base64.b64decode('" + encoded + "'),'<receiver>','exec'))"
    remote = ["python3", "-c", program, "--action",
              "receive" if task["direction"] == "UPLOAD" else "send",
              "--root", task["remoteRoot"], "--metadata", json.dumps(task["metadata"]),
              "--timeout-seconds", str(max(1, min(3600, math.ceil(remaining))))]
    argv = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=" + str(max(1, min(15, math.ceil(remaining)))),
            "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
            "-p", str(task.get("port", 22))]
    if "controlPath" in task:
        argv += ["-o", "ControlPath=" + task["controlPath"]]
    return argv + [task["target"], shlex.join(remote)]


def transfer(task, deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("batch deadline exhausted")
    argv = ssh_argv(task, remaining)
    if task["direction"] == "UPLOAD":
        with Path(task["source"]).open("rb") as source:
            result = subprocess.run(argv, stdin=source, capture_output=True,
                                    timeout=max(0.001, deadline - time.monotonic()), check=False)
        if result.returncode:
            raise RuntimeError("SSH upload failed or outcome unknown")
        ack = json.loads(result.stdout)
        if ack.get("schemaVersion") != 1 or ack.get("metadata") != task["metadata"] \
                or ack.get("status") not in {"PUBLISHED", "REUSED"}:
            raise ValueError("invalid receiver ACK")
        return ack["status"]
    # A failed/partial remote read stays outside the published local store.
    with tempfile.TemporaryFile() as spool:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=spool, stderr=subprocess.PIPE,
                                timeout=max(0.001, deadline - time.monotonic()), check=False)
        if result.returncode:
            raise RuntimeError("SSH download failed")
        if spool.tell() != task["metadata"]["sizeBytes"]:
            raise ValueError("download size mismatch")
        if time.monotonic() >= deadline:
            raise TimeoutError("download deadline exhausted")
        spool.seek(0)
        return receiver.receive(Path(task["localRoot"]), task["metadata"], spool)["status"]


def run_batch(plan):
    if not isinstance(plan, dict) or set(plan) != {"schemaVersion", "timeoutSeconds", "tasks"} \
            or type(plan["schemaVersion"]) is not int or plan["schemaVersion"] != 1:
        raise ValueError("invalid batch schema")
    timeout = plan["timeoutSeconds"]
    if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 3600:
        raise ValueError("finite 0..3600 second batch deadline required")
    tasks = plan["tasks"]
    if not isinstance(tasks, list) or not 1 <= len(tasks) <= 32:
        raise ValueError("batch requires 1..32 transfers")
    for task in tasks:
        validate_task(task)
    if len({(t["metadata"]["runId"], t["metadata"]["environmentFingerprint"]) for t in tasks}) != 1:
        raise ValueError("one frozen run/environment per batch required")
    deadline = time.monotonic() + timeout
    def execute(task):
        record = {"direction": task["direction"], "metadata": task["metadata"]}
        try:
            publication = transfer(task, deadline)
            if time.monotonic() >= deadline:
                raise TimeoutError("batch deadline exceeded; publication may have completed")
            record.update(result="PASS", publication=publication)
        except Exception as error:
            record.update(result="FAIL_OR_UNKNOWN", errorType=type(error).__name__)
        return record
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(tasks)) as pool:
        records = list(pool.map(execute, tasks))
    return {"schemaVersion": 1, "result": "PASS" if all(r["result"] == "PASS" for r in records)
            else "FAIL_OR_UNKNOWN", "receiverSha256": hashlib.sha256(RECEIVER.read_bytes()).hexdigest(),
            "transfers": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--plan", type=Path, required=True)
    args = parser.parse_args()
    try:
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError("duplicate plan field")
                result[key] = value
            return result
        result = run_batch(json.loads(args.plan.read_text(encoding="utf-8"), object_pairs_hook=pairs))
        print(json.dumps(result, sort_keys=True))
        return 0 if result["result"] == "PASS" else 1
    except (ValueError, OSError):
        print("Invalid transfer plan; no success verdict.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
