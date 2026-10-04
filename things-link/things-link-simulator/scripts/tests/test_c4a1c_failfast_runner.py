"""C4a-1c 本机运行器的销毁安全合同测试。"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parents[1] / "c4a1c_failfast_runner.ps1"


class C4a1cFailFastRunnerTests(unittest.TestCase):
    """锁定首轮诊断暴露的路径、错误传播与先归档后销毁边界。"""

    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_enables_terminating_errors_and_rejects_relative_evidence_paths(self) -> None:
        """PowerShell 与调用参数必须同时 fail-fast，不能再次带错相对路径继续清理。"""
        self.assertIn("$ErrorActionPreference = 'Stop'", self.source)
        self.assertIn("[System.IO.Path]::IsPathFullyQualified", self.source)
        self.assertIn("if ($LASTEXITCODE -ne 0)", self.source)

    def test_compose_project_and_container_allowlist_are_fixed(self) -> None:
        """运行器只能操作本片 Compose 项目，不得扫描或接管 tc 开发资源。"""
        self.assertIn("$ComposeProject = 'c4a1c'", self.source)
        self.assertIn("[AllowEmptyCollection()][string[]]$Names", self.source)
        expected = {
            "c4a1c-postgres", "c4a1c-redis", "c4a1c-redpanda", "c4a1c-redpanda-init",
            "c4a1c-emqx", "c4a1c-minio", "c4a1c-minio-init",
        }
        declared = set(re.findall(r"'((?:c4a1c)-(?:postgres|redis|redpanda(?:-init)?|emqx|minio(?:-init)?))'",
                                  self.source))
        self.assertEqual(expected, declared)
        self.assertNotIn("tc-postgres", self.source)

    def test_archive_and_hash_checks_precede_compose_down(self) -> None:
        """销毁只能位于 ZIP 非空与 SHA-256 校验之后，异常路径不得放在 finally 中清理。"""
        archive = self.source.index("Compress-Archive")
        non_empty = self.source.index("$archiveInfo.Length -le 0")
        hash_check = self.source.index("$hash -notmatch '^[0-9a-f]{64}$'")
        destroy = self.source.index("@('down', '-v', '--remove-orphans')")
        self.assertLess(archive, non_empty)
        self.assertLess(non_empty, hash_check)
        self.assertLess(hash_check, destroy)
        self.assertNotRegex(self.source, r"(?is)finally\s*\{[^}]*down")

    def test_existing_archive_is_never_overwritten(self) -> None:
        """同名不可变证据存在时必须停止，避免一次重跑覆盖上一轮事实。"""
        self.assertIn("归档文件已存在，拒绝覆盖", self.source)

    def test_destroy_pass_requires_zero_project_resources(self) -> None:
        """Compose 遗留的 init 容器只能按白名单补删，PASS 前必须核验三类资源均为零。"""
        down = self.source.index("@('down', '-v', '--remove-orphans')")
        remove = self.source.index("@('rm', '-f', $container)")
        postcondition = self.source.index("销毁后置条件失败")
        passed = self.source.index("DESTROY PASS")
        self.assertLess(down, remove)
        self.assertLess(remove, postcondition)
        self.assertLess(postcondition, passed)
        self.assertIn("Get-ProjectResources -Kind 'volume'", self.source)
        self.assertIn("Get-ProjectResources -Kind 'network'", self.source)
        self.assertIn("$absoluteArchive.cleanup.json", self.source)
        self.assertIn("cleanupSha256", self.source)

    def test_preflight_rejects_stale_volumes_and_networks(self) -> None:
        """全新栈资格不能只检查容器；遗留卷或网络同样会污染恢复结论。"""
        preflight = self.source[self.source.index("function Invoke-Preflight"):
                                self.source.index("function Resolve-RequiredAbsolutePath")]
        self.assertIn("Get-ProjectResources -Kind 'volume'", preflight)
        self.assertIn("Get-ProjectResources -Kind 'network'", preflight)
        self.assertIn("拒绝复用旧状态", preflight)

    def test_archive_redacts_secret_bearing_text(self) -> None:
        """Compose inspect、fixture 与日志进入 ZIP 前必须统一经过字段级脱敏。"""
        self.assertIn("function Protect-DiagnosticLines", self.source)
        self.assertIn("function Protect-StagedTextFiles", self.source)
        self.assertIn("<redacted>", self.source)

    def test_archive_preserves_empty_text_evidence(self) -> None:
        """未刷新 manifest 可以为空，但不能因此绕过归档与清理后置条件。"""
        self.assertIn("if ($protectedLines.Count -eq 0)", self.source)
        self.assertIn("[System.IO.File]::WriteAllText($_.FullName, '', $utf8NoBom)", self.source)
        self.assertIn("[System.IO.File]::WriteAllLines($_.FullName, [string[]]$protectedLines", self.source)


if __name__ == "__main__":
    unittest.main()
