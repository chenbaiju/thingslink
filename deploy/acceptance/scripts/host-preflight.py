#!/usr/bin/env python3
"""Emit a non-secret Linux host snapshot; collection success is not G3 qualification."""
import datetime
import json
import platform
import subprocess
import sys


def main():
    if platform.system() != "Linux":
        print("Run this read-only collector on the Linux candidate host.", file=sys.stderr)
        return 2
    commands = {
        "os": ["cat", "/etc/os-release"],
        "kernel": ["uname", "-r"],
        "cpu": ["lscpu", "--json"],
        "memory": ["free", "-b"],
        "disk": ["df", "-B1", "/"],
        "routes": ["ip", "-4", "route"],
        "listeners": ["ss", "-lntu"],
        "ntp": ["chronyc", "tracking"],
        "time": ["timedatectl", "show", "-p", "NTPSynchronized"],
        "firewall": ["ufw", "status"],
        "docker": ["docker", "version", "--format", "{{.Client.Version}} {{.Server.Version}}"],
        "compose": ["docker", "compose", "version"],
        "java": ["java", "-version"],
        "python": ["python3", "--version"],
        "containers": ["docker", "ps", "-a", "--format", "{{.Names}} {{.Status}}"],
        "images": ["docker", "image", "ls", "--digests", "--format",
                   "{{.Repository}}:{{.Tag}} {{.Digest}}"],
        "hostKey": ["ssh-keygen", "-lf", "/etc/ssh/ssh_host_ed25519_key.pub"],
    }
    observations = {}
    for name, command in commands.items():
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=20)
            observations[name] = {"command": command, "exitCode": result.returncode,
                                  "stdout": result.stdout, "stderr": result.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            observations[name] = {"command": command, "exitCode": None,
                                  "collectionError": type(error).__name__}
    snapshot = {
        "schemaVersion": 1,
        "evidenceKind": "HOST_PREPARATION_SNAPSHOT_NOT_ENVIRONMENT_QUALIFICATION",
        "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "architecture": platform.machine(),
        "observations": observations,
    }
    print(json.dumps(snapshot, ensure_ascii=False, indent=2))
    return 0 if all(item["exitCode"] == 0 for item in observations.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
