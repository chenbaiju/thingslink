#!/usr/bin/env python3
"""Linux bounded atomic receiver for public artifacts; never an environment verdict."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sys
import tempfile


class TransferError(ValueError):
    """The incoming artifact cannot be published under its declared identity."""


def validate(metadata):
    fields = {"schemaVersion", "runId", "environmentFingerprint", "hostId", "artifactName",
              "sizeBytes", "sha256"}
    if not isinstance(metadata, dict) or set(metadata) != fields or type(metadata["schemaVersion"]) is not int \
            or metadata["schemaVersion"] != 1:
        raise TransferError("invalid metadata schema")
    for key in ("runId", "hostId"):
        if not isinstance(metadata[key], str) or not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", metadata[key]):
            raise TransferError("invalid identity")
    name = metadata["artifactName"]
    if not isinstance(name, str) or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}", name):
        raise TransferError("invalid artifact name")
    for key in ("sha256", "environmentFingerprint"):
        if not isinstance(metadata[key], str) or not re.fullmatch(r"[0-9a-f]{64}", metadata[key]):
            raise TransferError("invalid SHA256")
    if type(metadata["sizeBytes"]) is not int or not 0 <= metadata["sizeBytes"] <= 32 * 1024 ** 3:
        raise TransferError("invalid size")


def reject_links(path):
    if not path.is_absolute() or ".." in path.parts or any(p.is_symlink() for p in (path, *path.parents)):
        raise TransferError("absolute non-symlink path required")


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def canonical(metadata):
    return (json.dumps(metadata, sort_keys=True, separators=(",", ":")) + "\n").encode()


def verify_existing(path, metadata):
    reject_links(path)
    for child in (path / "metadata.json", path / "payload"):
        reject_links(child)
        if not child.is_file():
            raise TransferError("existing artifact is incomplete")
    if (path / "metadata.json").read_bytes() != canonical(metadata):
        raise TransferError("refusing conflicting artifact")
    digest = hashlib.sha256()
    size = 0
    with (path / "payload").open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
            size += len(block)
    if size != metadata["sizeBytes"] or digest.hexdigest() != metadata["sha256"]:
        raise TransferError("existing artifact integrity failure")


def receive(root, metadata, source):
    validate(metadata)
    root = Path(root)
    reject_links(root)
    if not root.is_dir() or root.stat().st_mode & 0o077 or root.stat().st_uid != os.geteuid():
        raise TransferError("private pre-created transfer root required")
    parent = root / metadata["runId"] / metadata["environmentFingerprint"] / metadata["hostId"]
    reject_links(parent)
    current = root
    for component in (metadata["runId"], metadata["environmentFingerprint"], metadata["hostId"]):
        child = current / component
        child.mkdir(exist_ok=True, mode=0o700)
        sync_directory(current)
        current = child
    final = parent / metadata["artifactName"]
    reject_links(final)
    staging = Path(tempfile.mkdtemp(prefix=".incoming-", dir=parent))
    try:
        digest = hashlib.sha256()
        remaining = metadata["sizeBytes"]
        with (staging / "payload").open("xb") as target:
            while remaining:
                block = source.read(min(remaining, 1024 * 1024))
                if not block:
                    raise TransferError("truncated stream")
                target.write(block)
                digest.update(block)
                remaining -= len(block)
            if source.read(1):
                raise TransferError("excess bytes")
            if digest.hexdigest() != metadata["sha256"]:
                raise TransferError("digest mismatch")
            target.flush()
            os.fsync(target.fileno())
        with (staging / "metadata.json").open("xb") as target:
            target.write(canonical(metadata))
            target.flush()
            os.fsync(target.fileno())
        sync_directory(staging)
        try:
            if final.exists():
                verify_existing(final, metadata)
                sync_directory(parent)
                return {"schemaVersion": 1, "status": "REUSED", "metadata": metadata}
            # A published directory is nonempty: POSIX rename cannot replace it.
            os.rename(staging, final)
            status = "PUBLISHED"
        except OSError:
            if not final.exists():
                raise
            verify_existing(final, metadata)
            status = "REUSED"
        sync_directory(parent)
        return {"schemaVersion": 1, "status": status, "metadata": metadata}
    finally:
        if staging.exists():
            shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--action", choices=("receive", "send"), default="receive")
    parser.add_argument("--metadata", required=True, help="Public JSON identity, never credentials")
    parser.add_argument("--timeout-seconds", required=True, type=int)
    args = parser.parse_args()
    if not sys.platform.startswith("linux") or not 1 <= args.timeout_seconds <= 3600:
        parser.error("Linux and a 1..3600 second deadline are required")
    def timeout(_signal, _frame):
        raise TransferError("receive deadline exceeded")
    signal.signal(signal.SIGALRM, timeout)
    signal.alarm(args.timeout_seconds)
    try:
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise TransferError("duplicate metadata field")
                result[key] = value
            return result
        metadata = json.loads(args.metadata, object_pairs_hook=pairs)
        if args.action == "send":
            validate(metadata)
            root = Path(args.root)
            reject_links(root)
            path = root / metadata["runId"] / metadata["environmentFingerprint"] \
                / metadata["hostId"] / metadata["artifactName"]
            verify_existing(path, metadata)
            with (path / "payload").open("rb") as source:
                shutil.copyfileobj(source, sys.stdout.buffer, 1024 * 1024)
            sys.stdout.buffer.flush()
        else:
            result = receive(Path(args.root), metadata, sys.stdin.buffer)
            print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, OSError):
        print("Atomic receive failed; no success ACK. Inspect or retry the same identity.", file=sys.stderr)
        return 1
    finally:
        signal.alarm(0)


if __name__ == "__main__":
    sys.exit(main())
