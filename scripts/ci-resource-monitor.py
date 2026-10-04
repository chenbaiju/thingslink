#!/usr/bin/env python3
"""为 backend CI 保留关闭前的资源证据；不改变验证结果或自动执行恢复。"""

import datetime
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import threading

# proc/cgroup伪文件与命令日志均有上限；不读取环境、命令行或容器凭据。
FILE_LIMIT = 64 * 1024
OUTPUT_LIMIT = 64 * 1024
UINT64_MAX = (1 << 64) - 1


def read_bounded(path, limit=FILE_LIMIT):
    """只接受完整有界UTF-8文件；超限不把截断内容当作完整计数证据。"""
    try:
        with Path(path).open("rb") as source:
            raw = source.read(limit + 1)
        if len(raw) > limit:
            return None
        return raw.decode("utf-8", errors="strict")
    except (OSError, UnicodeError, ValueError):
        return None


def counter(text, name=None):
    """严格取无符号整数；缺失、重复、非法与溢出均不可解释为零。"""
    if text is None:
        return None
    if name is None:
        values = [text.strip()]
    else:
        rows = [line.split() for line in text.splitlines()]
        matches = [row for row in rows if row and row[0] == name]
        if len(matches) != 1 or len(matches[0]) != 2:
            return None
        values = [matches[0][1]]
    value = values[0]
    if not re.fullmatch(r"[0-9]{1,20}", value):
        return None
    number = int(value)
    return number if number <= UINT64_MAX else None


class CounterEvidence:
    """启动后首次可读计数作为基线；重置与不可读期间不制造零增量。"""

    def __init__(self):
        self.baselines = {}

    def describe(self, label, value):
        if value is None:
            return label + " unavailable"
        baseline = self.baselines.setdefault(label, value)
        if value < baseline:
            self.baselines[label] = value
            return f"{label}={value} baseline={value} delta=unavailable(counter-decreased)"
        return f"{label}={value} baseline={baseline} delta={value - baseline}"


def absolute_path(value, escaped=False):
    """mountinfo只解码内核四种八进制转义；拒绝相对路径及任何父目录跳转。"""
    if escaped:
        escapes = {"040": " ", "011": "\t", "012": "\n", "134": "\\"}
        # 先检查原串，避免把合法解码后的反斜杠再次当作转义处理。
        if re.search(r"\\(?!040|011|012|134)", value):
            return None
        value = re.sub(r"\\(040|011|012|134)", lambda match: escapes[match[1]], value)
    if not value.startswith("/") or value.startswith("//") or "\x00" in value:
        return None
    path = PurePosixPath(value)
    if ".." in path.parts or str(path) != value:
        return None
    return path


def memory_cgroups(membership, mounts):
    """把当前进程层级路径映射到实际挂载根；不盲读宿主/sys/fs/cgroup根。"""
    if membership is None or mounts is None:
        return []
    groups = []
    for line in membership.splitlines():
        parts = line.split(":", 2)
        if len(parts) != 3 or not parts[0].isdigit():
            continue
        path = absolute_path(parts[2])
        if path is None:
            continue
        if parts[0] == "0" and not parts[1]:
            groups.append(("v2", path))
        elif "memory" in parts[1].split(","):
            groups.append(("v1", path))
    candidates = []
    for line in mounts.splitlines():
        fields = line.split()
        try:
            separator = fields.index("-")
        except ValueError:
            continue
        if separator < 6 or len(fields) <= separator + 3:
            continue
        kind = fields[separator + 1]
        if kind not in ("cgroup", "cgroup2"):
            continue
        version = "v2" if kind == "cgroup2" else "v1"
        if version == "v1" and "memory" not in fields[separator + 3].split(","):
            continue
        root = absolute_path(fields[3], escaped=True)
        mount = absolute_path(fields[4], escaped=True)
        if root is None or mount is None:
            continue
        for group_version, group in groups:
            if group_version != version:
                continue
            try:
                relative = group.relative_to(root)
            except ValueError:
                continue
            candidate = (version, str(mount / relative))
            if candidate not in candidates:
                candidates.append(candidate)
    # 混合层级/绑定挂载最多保留8处，避免异常挂载信息撑大单次日志。
    return candidates[:8]


def oom_evidence(evidence):
    """全局kill计数证明主机事件，cgroup计数缩小范围；二者都不能单独指认某个PID。"""
    lines = [evidence.describe("host.oom_kill", counter(read_bounded("/proc/vmstat"), "oom_kill"))]
    groups = memory_cgroups(read_bounded("/proc/self/cgroup"), read_bounded("/proc/self/mountinfo"))
    if not groups:
        lines.append("process.memory-cgroup unavailable")
    for version, directory in groups:
        # repr转义挂载路径中的换行，不让内核合法路径改变日志结构。
        lines.append(f"process.memory-cgroup {version} path={directory!r}")
        if version == "v2":
            for filename in ("memory.events", "memory.events.local"):
                text = read_bounded(str(PurePosixPath(directory) / filename))
                for field in ("oom", "oom_kill", "oom_group_kill"):
                    label = f"cgroup.{directory!r}.{filename}.{field}"
                    lines.append(evidence.describe(label, counter(text, field)))
        else:
            filename = str(PurePosixPath(directory) / "memory.failcnt")
            # failcnt仅是内存限制命中次数，不等于OOM kill次数。
            lines.append(evidence.describe(f"cgroup.{directory!r}.memory.failcnt", counter(read_bounded(filename))))
            text = read_bounded(str(PurePosixPath(directory) / "memory.oom_control"))
            for field in ("under_oom", "oom_kill"):
                value = counter(text, field)
                label = f"cgroup.{directory!r}.memory.oom_control.{field}"
                # under_oom是瞬时状态，不计算累计增量。
                lines.append((label + " unavailable") if value is None else
                             (f"{label}={value}" if field == "under_oom" else evidence.describe(label, value)))
    return lines


def snapshot(evidence=None):
    """外部命令最多五秒；进程仅输出名称，不输出stderr或凭据。"""
    if evidence is None:
        evidence = CounterEvidence()
    lines = ["[ci-resource] " + datetime.datetime.now(datetime.timezone.utc).isoformat()]
    for path in ("/proc/meminfo", "/proc/pressure/memory", "/proc/pressure/cpu"):
        text = read_bounded(path)
        lines.append(path + ("\n" + text.rstrip() if text is not None else " unavailable"))
    lines.extend(oom_evidence(evidence))
    commands = (
        ["df", "-h", "."],
        ["ps", "-eo", "pid,ppid,rss,nlwp,comm", "--sort=-rss"],
        ["docker", "stats", "--no-stream", "--format",
         "{{.ID}} memory={{.MemUsage}} cpu={{.CPUPerc}} pids={{.PIDs}}"],
    )
    for command in commands:
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=5, check=False)
            # 不枚举每个已退出容器；只保留有输出的资源行，命令整体退出码仍可诊断工具错误。
            output = [line[:1024] for line in result.stdout[:OUTPUT_LIMIT].splitlines() if line.strip()]
            maximum = 16 if command[0] == "ps" else 64
            truncated = len(output) > maximum or len(result.stdout) > OUTPUT_LIMIT
            output = output[:maximum]
            lines.append(command[0] + " exit=" + str(result.returncode) +
                         ("\n" + "\n".join(output) if output else " no-output") +
                         ("\n[output bounded]" if truncated else ""))
        except (OSError, subprocess.TimeoutExpired):
            lines.append(command[0] + " unavailable-or-timeout")
    print("\n".join(lines), flush=True)


def main():
    """每30秒采样并复用同一基线，步骤退出时响应SIGTERM，不遗留监控循环。"""
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    evidence = CounterEvidence()
    while not stopped.is_set():
        snapshot(evidence)
        stopped.wait(30)


if __name__ == "__main__":
    main()
