"""资源证据区分缺失与零；路径解析不越界，诊断不得泄露环境或命令stderr。"""

import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "ci_resource_monitor", Path(__file__).resolve().parents[1] / "ci-resource-monitor.py")
MONITOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITOR)


class ResourceMonitorTests(unittest.TestCase):
    def test_missing_tools_and_timeout_are_diagnostic_only(self):
        with patch.object(MONITOR, "read_bounded", return_value=None), \
                patch.object(MONITOR.subprocess, "run", side_effect=[
                    FileNotFoundError(), subprocess.TimeoutExpired("ps", 5),
                    subprocess.CompletedProcess([], 1, "", "sensitive-error")]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            MONITOR.snapshot()
        text = output.getvalue()
        self.assertIn("host.oom_kill unavailable", text)
        self.assertIn("process.memory-cgroup unavailable", text)
        self.assertIn("ps unavailable-or-timeout", text)
        self.assertIn("docker exit=1 no-output", text)
        self.assertNotIn("sensitive-error", text)
        self.assertNotIn("host.oom_kill=0", text)

    def test_process_output_is_bounded_and_excludes_arguments(self):
        with patch.object(MONITOR, "read_bounded", return_value=None), \
                patch.object(MONITOR.subprocess, "run", return_value=
                             subprocess.CompletedProcess([], 0, "row\n" * 100, "")) as run, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            MONITOR.snapshot()
        self.assertIn("pid,ppid,rss,nlwp,comm", run.call_args_list[1].args[0])
        process_output = output.getvalue().split("ps exit=0\n")[1].split("docker exit=0")[0]
        self.assertEqual(process_output.count("row"), 16)
        self.assertEqual(output.getvalue().split("docker exit=0\n")[1].count("row"), 64)
        self.assertTrue(all(call.kwargs["timeout"] == 5 for call in run.call_args_list))
        self.assertNotIn("inspect", str(run.call_args_list))

    def test_blank_docker_lines_do_not_flood_snapshot(self):
        with patch.object(MONITOR, "read_bounded", return_value=None), \
                patch.object(MONITOR.subprocess, "run", return_value=
                             subprocess.CompletedProcess([], 0, " \n" * 500, "private")), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            MONITOR.snapshot()
        self.assertIn("docker exit=0 no-output", output.getvalue())
        self.assertNotIn("\n \n", output.getvalue())
        self.assertNotIn("private", output.getvalue())

    def test_reads_are_complete_and_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "proc-fixture"
            path.write_bytes(b"a" * 16)
            self.assertEqual(MONITOR.read_bounded(path, 16), "a" * 16)
            self.assertIsNone(MONITOR.read_bounded(path, 15))
            path.write_bytes(b"\xff")
            self.assertIsNone(MONITOR.read_bounded(path))
            self.assertIsNone(MONITOR.read_bounded(Path(directory) / "missing"))

    def test_vmstat_only_exports_oom_kill_with_persistent_baseline_and_delta(self):
        evidence = MONITOR.CounterEvidence()
        inputs = {"/proc/vmstat": "pgfault 999\noom_kill 7\nother_private 888\n"}
        with patch.object(MONITOR, "read_bounded", side_effect=lambda path: inputs.get(str(path))):
            first = "\n".join(MONITOR.oom_evidence(evidence))
            inputs["/proc/vmstat"] = "oom_kill 9\n"
            second = "\n".join(MONITOR.oom_evidence(evidence))
            inputs["/proc/vmstat"] = None
            missing = "\n".join(MONITOR.oom_evidence(evidence))
            inputs["/proc/vmstat"] = "oom_kill 10\n"
            resumed = "\n".join(MONITOR.oom_evidence(evidence))
        self.assertIn("host.oom_kill=7 baseline=7 delta=0", first)
        self.assertIn("host.oom_kill=9 baseline=7 delta=2", second)
        self.assertIn("host.oom_kill unavailable", missing)
        self.assertIn("host.oom_kill=10 baseline=7 delta=3", resumed)
        self.assertNotIn("pgfault", first)
        self.assertNotIn("other_private", first)

    def test_counter_missing_duplicate_malformed_and_overflow_never_become_zero(self):
        self.assertEqual(MONITOR.counter("oom_kill 0\n", "oom_kill"), 0)
        self.assertEqual(MONITOR.counter("18446744073709551615"), (1 << 64) - 1)
        for text in (None, "", "oom_kill", "oom_kill -1", "oom_kill 1.0",
                     "oom_kill 1 trailing", "oom_kill 1\noom_kill 2", "oom_kill 18446744073709551616"):
            with self.subTest(text=text):
                self.assertIsNone(MONITOR.counter(text, "oom_kill"))

    def test_counter_decrease_is_explicit_reset_not_negative_or_zero_delta(self):
        evidence = MONITOR.CounterEvidence()
        evidence.describe("host.oom_kill", 9)
        self.assertIn("delta=unavailable(counter-decreased)", evidence.describe("host.oom_kill", 1))
        self.assertIn("baseline=1 delta=1", evidence.describe("host.oom_kill", 2))

    def test_v2_uses_current_process_nested_path_and_mount_root(self):
        membership = "0::/runner.slice/job.scope\n"
        mounts = "29 23 0:26 /runner.slice /sys/fs/cgroup rw,nosuid - cgroup2 cgroup rw\n"
        self.assertEqual(MONITOR.memory_cgroups(membership, mounts),
                         [("v2", "/sys/fs/cgroup/job.scope")])
        self.assertEqual(MONITOR.memory_cgroups("0::/\n",
                         "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n"),
                         [("v2", "/sys/fs/cgroup")])

    def test_v1_resolves_only_memory_controller_not_cpu_mount(self):
        membership = "2:cpu,cpuacct:/job\n7:memory:/job\n"
        mounts = ("29 23 0:26 / /sys/fs/cgroup/cpu rw - cgroup cgroup rw,cpu,cpuacct\n"
                  "30 23 0:27 / /sys/fs/cgroup/memory rw - cgroup cgroup rw,memory\n")
        self.assertEqual(MONITOR.memory_cgroups(membership, mounts),
                         [("v1", "/sys/fs/cgroup/memory/job")])

    def test_mountinfo_octal_paths_are_decoded_once_without_path_traversal(self):
        mounts = r"29 23 0:26 /runner\040root /sys/cgroup\040memory rw - cgroup2 cgroup rw" + "\n"
        self.assertEqual(MONITOR.memory_cgroups("0::/runner root/job\n", mounts),
                         [("v2", "/sys/cgroup memory/job")])
        self.assertEqual(str(MONITOR.absolute_path(r"/root\134040", escaped=True)), r"/root\040")
        for path in ("relative", "//a", "/a/../outside", "/a/./b", "/a//b", "/a/\x00b"):
            self.assertIsNone(MONITOR.absolute_path(path))
        for mount_root in (r"/a/../outside", r"/a/\056\056/outside", r"/a\999"):
            mounts = f"29 23 0:26 / {mount_root} rw - cgroup2 cgroup rw\n"
            self.assertEqual(MONITOR.memory_cgroups("0::/job\n", mounts), [])

    def test_missing_unmapped_or_prefix_collision_does_not_guess_root(self):
        mounts = "29 23 0:26 /runner /sys/fs/cgroup rw - cgroup2 cgroup rw\n"
        for membership in (None, "broken", "0::/runner-other/job", "0::/../../job", "0::/"):
            self.assertEqual(MONITOR.memory_cgroups(membership, mounts), [])
        self.assertEqual(MONITOR.memory_cgroups("0::/runner/job", None), [])

    def test_cgroup_evidence_reads_exact_resolved_files_missing_local_remains_unavailable(self):
        inputs = {
            "/proc/vmstat": "oom_kill 2\n",
            "/proc/self/cgroup": "0::/job\n",
            "/proc/self/mountinfo": "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
            "/sys/fs/cgroup/job/memory.events": "low 0\nhigh 1\nmax 8\noom 3\noom_kill 2\n",
        }
        with patch.object(MONITOR, "read_bounded", side_effect=lambda path: inputs.get(str(path))) as read:
            output = "\n".join(MONITOR.oom_evidence(MONITOR.CounterEvidence()))
        self.assertIn("path='/sys/fs/cgroup/job'", output)
        self.assertIn("memory.events.oom_kill=2", output)
        self.assertIn("memory.events.local.oom_kill unavailable", output)
        self.assertIn("memory.events.oom_group_kill unavailable", output)
        self.assertNotIn("/sys/fs/cgroup/memory.events", [str(call.args[0]) for call in read.call_args_list])

    def test_v1_limit_failures_do_not_manufacture_oom_kill(self):
        inputs = {
            "/proc/self/cgroup": "2:memory:/job\n",
            "/proc/self/mountinfo": "29 23 0:26 / /cgroup/memory rw - cgroup cgroup rw,memory\n",
            "/cgroup/memory/job/memory.failcnt": "42\n",
            "/cgroup/memory/job/memory.oom_control": "oom_kill_disable 0\nunder_oom 0\n",
        }
        with patch.object(MONITOR, "read_bounded", side_effect=lambda path: inputs.get(str(path))):
            output = "\n".join(MONITOR.oom_evidence(MONITOR.CounterEvidence()))
        self.assertIn("memory.failcnt=42", output)
        self.assertIn("memory.oom_control.under_oom=0", output)
        self.assertIn("memory.oom_control.oom_kill unavailable", output)
        self.assertNotIn("oom_kill=42", output)


if __name__ == "__main__":
    unittest.main()
