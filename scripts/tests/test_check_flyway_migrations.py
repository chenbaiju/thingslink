"""Flyway迁移Git增量守卫的黑盒测试。"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "check-flyway-migrations.py"


class FlywayMigrationGuardTests(unittest.TestCase):
    """使用临时Git仓库验证静态与增量失败边界。"""

    def setUp(self) -> None:
        """创建具备固定作者身份的独立Git仓库。"""

        self.temporary_directory = tempfile.TemporaryDirectory()
        self.repo = Path(self.temporary_directory.name)
        self.git("init", "--quiet")
        self.git("config", "user.name", "Migration Guard Tests")
        self.git("config", "user.email", "migration-guard@example.invalid")

    def tearDown(self) -> None:
        """清理临时仓库。"""

        self.temporary_directory.cleanup()

    def git(self, *arguments: str) -> str:
        """执行Git命令并返回标准输出。"""

        return subprocess.run(
            ["git", *arguments],
            cwd=self.repo,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ).stdout.strip()

    def migration_path(self, domain: str, filename: str) -> Path:
        """返回指定模块的生产迁移路径。"""

        return (
            self.repo
            / "things-link"
            / f"things-link-{domain}"
            / "src/main/resources/db/migration"
            / domain
            / filename
        )

    def write(self, path: Path, content: str = "SELECT 1;\n") -> None:
        """写入测试文件及其父目录。"""

        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8", newline="")

    def commit_all(self, message: str) -> str:
        """提交当前测试树并返回提交ID。"""

        self.git("add", "-A")
        self.git("commit", "--quiet", "-m", message)
        return self.git("rev-parse", "HEAD")

    def create_baseline(self, with_sidecar: bool = False) -> tuple[str, Path]:
        """创建包含一个合法迁移的基线提交。"""

        migration = self.migration_path("support", "V20260906_0100__baseline.sql")
        self.write(migration, "CREATE TABLE baseline(id bigint);\n")
        if with_sidecar:
            self.write(Path(f"{migration}.conf"), "executeInTransaction=false\n")
        return self.commit_all("baseline"), migration

    def guard(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        """从临时仓库运行被测守卫。"""

        return subprocess.run(
            [sys.executable, str(SCRIPT), *arguments],
            cwd=self.repo,
            check=False,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def test_accepts_legal_newer_migration(self) -> None:
        """新增版本越过基线最大值时通过增量审计。"""

        base, _ = self.create_baseline()
        self.write(self.migration_path("dashboard", "V20260906_0110__dashboard_schema.sql"))
        self.commit_all("add dashboard migration")

        result = self.guard("--base-ref", base, "--head-ref", "HEAD")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Git增量审计通过", result.stdout)

    def test_rejects_new_migration_not_above_base_maximum(self) -> None:
        """新增迁移即使当前树唯一，也不能回填到基线最大版本之前。"""

        base, _ = self.create_baseline()
        self.write(self.migration_path("dashboard", "V20260905_9990__backfilled.sql"))
        self.commit_all("add low migration")

        result = self.guard("--base-ref", base)

        self.assertEqual(1, result.returncode)
        self.assertIn("不大于基线最大版本 20260906_0100", result.stderr)

    def test_rejects_historical_modify_delete_and_rename(self) -> None:
        """基线已有SQL或sidecar不能被改、删或重命名。"""

        base, migration = self.create_baseline(with_sidecar=True)
        scenarios = {
            "modify": "M",
            "delete": "D",
            "rename": "R",
        }
        for operation, expected_status in scenarios.items():
            with self.subTest(operation=operation):
                clone_directory = Path(tempfile.mkdtemp())
                self.addCleanup(shutil.rmtree, clone_directory, True)
                subprocess.run(
                    ["git", "clone", "--quiet", str(self.repo), str(clone_directory)],
                    check=True,
                )
                subprocess.run(
                    ["git", "config", "user.name", "Migration Guard Tests"],
                    cwd=clone_directory,
                    check=True,
                )
                subprocess.run(
                    ["git", "config", "user.email", "migration-guard@example.invalid"],
                    cwd=clone_directory,
                    check=True,
                )
                relative_migration = migration.relative_to(self.repo)
                cloned_migration = clone_directory / relative_migration
                if operation == "modify":
                    cloned_migration.write_text("SELECT 2;\n", encoding="utf-8", newline="")
                elif operation == "delete":
                    Path(f"{cloned_migration}.conf").unlink()
                elif operation == "rename":
                    cloned_migration.rename(
                        cloned_migration.with_name("V20260906_0110__renamed.sql")
                    )
                subprocess.run(["git", "add", "-A"], cwd=clone_directory, check=True)
                subprocess.run(
                    ["git", "commit", "--quiet", "-m", operation],
                    cwd=clone_directory,
                    check=True,
                )
                result = subprocess.run(
                    [sys.executable, str(SCRIPT), "--base-ref", base],
                    cwd=clone_directory,
                    check=False,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                self.assertEqual(1, result.returncode, result.stderr)
                self.assertIn(f"不可{expected_status}", result.stderr)

    def test_accepts_copy_to_legal_newer_version(self) -> None:
        """Git识别为copy时保留历史源，并把高版本目的文件作为新增迁移。"""

        base, migration = self.create_baseline()
        shutil.copyfile(migration, migration.with_name("V20260906_0110__copied.sql"))
        self.commit_all("copy to newer version")
        copy_status = self.git("diff", "--name-status", "--find-copies-harder", base, "HEAD")
        self.assertTrue(copy_status.startswith("C"), copy_status)

        result = self.guard("--base-ref", base)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Git增量审计通过", result.stdout)

    def test_accepts_new_sql_and_sidecar_in_same_change(self) -> None:
        """同一增量内新SQL与同名sidecar成对加入时允许。"""

        base, _ = self.create_baseline()
        migration = self.migration_path("dashboard", "V20260906_0110__dashboard_schema.sql")
        self.write(migration)
        self.write(Path(f"{migration}.conf"), "executeInTransaction=false\n")
        self.commit_all("add migration with sidecar")

        result = self.guard("--base-ref", base)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Git增量审计通过", result.stdout)

    def test_rejects_sidecar_added_to_baseline_sql(self) -> None:
        """为最新base或共同祖先中的SQL新增sidecar会改变历史执行配置，必须拒绝。"""

        base, migration = self.create_baseline()
        self.write(Path(f"{migration}.conf"), "executeInTransaction=false\n")
        self.commit_all("add historical sidecar")

        result = self.guard("--base-ref", base)

        self.assertEqual(1, result.returncode)
        self.assertIn("既有生产迁移SQL不可新增sidecar", result.stderr)

    def test_rejects_sidecar_copy_to_baseline_sql(self) -> None:
        """Git识别为copy的sidecar目的地同样受历史SQL保护。"""

        source = self.migration_path("support", "V20260906_0100__source.sql")
        destination = self.migration_path("dashboard", "V20260906_0110__destination.sql")
        self.write(source)
        self.write(destination)
        source_sidecar = Path(f"{source}.conf")
        self.write(source_sidecar, "executeInTransaction=false\n")
        base = self.commit_all("baseline with source sidecar")
        destination_sidecar = Path(f"{destination}.conf")
        shutil.copyfile(source_sidecar, destination_sidecar)
        self.commit_all("copy sidecar to historical migration")
        copy_status = self.git("diff", "--name-status", "--find-copies-harder", base, "HEAD")
        self.assertTrue(copy_status.startswith("C"), copy_status)

        result = self.guard("--base-ref", base)

        self.assertEqual(1, result.returncode)
        self.assertIn("既有生产迁移SQL不可新增sidecar", result.stderr)

    def test_rejects_copy_to_version_below_base_maximum(self) -> None:
        """copy目的文件与普通新增文件使用同一基线最大版本下界。"""

        base, migration = self.create_baseline()
        shutil.copyfile(migration, migration.with_name("V20260905_9990__copied.sql"))
        self.commit_all("copy to lower version")
        copy_status = self.git("diff", "--name-status", "--find-copies-harder", base, "HEAD")
        self.assertTrue(copy_status.startswith("C"), copy_status)

        result = self.guard("--base-ref", base)

        self.assertEqual(1, result.returncode)
        self.assertIn("不大于基线最大版本 20260906_0100", result.stderr)

    def test_diverged_branch_without_migration_passes_against_advanced_base(self) -> None:
        """目标分支前进后，功能分支只审共同祖先以后由自身引入的变化。"""

        common, _ = self.create_baseline()
        self.git("checkout", "--quiet", "-b", "feature")
        self.write(self.repo / "README.md", "feature only\n")
        head = self.commit_all("feature without migration")
        self.git("checkout", "--quiet", "-b", "target", common)
        self.write(self.migration_path("dashboard", "V20260906_0200__target_advanced.sql"))
        latest_base = self.commit_all("target advances migration")
        self.git("checkout", "--quiet", "feature")

        result = self.guard("--base-ref", latest_base, "--head-ref", head)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"分支变化={common[:12]}..{head[:12]}", result.stdout)
        self.assertIn(f"新增版本下界=最新base {latest_base[:12]}", result.stdout)

    def test_merge_checkout_skips_worktree_incremental_against_explicit_feature_head(self) -> None:
        """PR合并检出HEAD不同于源分支SHA时，不把目标分支迁移算成功能分支新增。"""

        common, _ = self.create_baseline()
        self.git("checkout", "--quiet", "-b", "feature")
        self.write(self.repo / "README.md", "feature only\n")
        feature_head = self.commit_all("feature without migration")
        self.git("checkout", "--quiet", "-b", "target", common)
        self.write(self.migration_path("dashboard", "V20260906_0200__target_advanced.sql"))
        latest_base = self.commit_all("target advances migration")

        result = self.guard("--base-ref", latest_base, "--head-ref", feature_head)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("跳过工作树增量审计", result.stdout)
        self.assertIn("仍已执行工作树静态清单检查", result.stdout)
        self.assertIn(f"分支变化={common[:12]}..{feature_head[:12]}", result.stdout)

    def test_diverged_branch_new_version_uses_latest_base_maximum(self) -> None:
        """分支迁移虽高于共同祖先，仍必须高于已前进目标分支的最新最大值。"""

        common, _ = self.create_baseline()
        self.git("checkout", "--quiet", "-b", "feature")
        self.write(self.migration_path("ota", "V20260906_0110__feature_ota.sql"))
        head = self.commit_all("feature migration")
        self.git("checkout", "--quiet", "-b", "target", common)
        self.write(self.migration_path("dashboard", "V20260906_0200__target_dashboard.sql"))
        latest_base = self.commit_all("target migration")
        self.git("checkout", "--quiet", "feature")

        result = self.guard("--base-ref", latest_base, "--head-ref", head)

        self.assertEqual(1, result.returncode)
        self.assertIn("不大于基线最大版本 20260906_0200", result.stderr)

    def test_rejects_untracked_low_version_against_explicit_base(self) -> None:
        """本地提交前以HEAD为base时，未跟踪低版本也必须进入增量下界审计。"""

        base, _ = self.create_baseline()
        self.write(self.migration_path("dashboard", "V20260905_9990__untracked.sql"))

        result = self.guard("--base-ref", base)

        self.assertEqual(1, result.returncode)
        self.assertIn("不大于基线最大版本 20260906_0100", result.stderr)

    def test_unreachable_explicit_ref_returns_configuration_error(self) -> None:
        """显式base或head不可达时不能静默降级为静态检查。"""

        self.create_baseline()

        missing_base = self.guard("--base-ref", "refs/heads/missing")
        missing_head = self.guard("--base-ref", "HEAD", "--head-ref", "refs/heads/missing")

        self.assertEqual(2, missing_base.returncode)
        self.assertIn("--base-ref不可达", missing_base.stderr)
        self.assertEqual(2, missing_head.returncode)
        self.assertIn("--head-ref不可达", missing_head.stderr)

    def test_without_base_runs_static_checks_and_disclaims_incremental_audit(self) -> None:
        """未提供基线时明确声明只完成当前树静态检查。"""

        self.create_baseline()

        result = self.guard()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("未执行Git增量不可变审计", result.stdout)

    def test_static_check_rejects_invalid_names_and_dates(self) -> None:
        """静态扫描拒绝非法日期、非十进槽和非规范文件名。"""

        self.write(self.migration_path("support", "V20260906_0100__baseline.sql"))
        self.write(self.migration_path("alarm", "V20260906_0000__zero_slot.sql"))
        self.write(self.migration_path("dashboard", "V20260230_0020__bad_date.sql"))
        self.write(self.migration_path("ota", "V20260906_0015__bad_slot.sql"))
        self.write(self.migration_path("project", "V20260906.0030__bad_separator.sql"))
        self.write(self.migration_path("device", "V20260906_0040__Bad_name.sql"))
        self.commit_all("invalid names")

        result = self.guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("迁移日期无效", result.stderr)
        self.assertGreaterEqual(result.stderr.count("迁移排序槽必须位于0100..9990且按10递增"), 2)
        self.assertGreaterEqual(
            result.stderr.count("不符合 VyyyyMMdd_NNNN__lower_snake.sql"), 2
        )

    def test_static_check_rejects_global_duplicate_and_orphan_sidecar(self) -> None:
        """不同未来模块仍共享版本空间，且sidecar必须有同名SQL。"""

        self.write(self.migration_path("dashboard", "V20260906_0100__dashboard.sql"))
        self.write(self.migration_path("ota", "V20260906_0100__ota.sql"))
        orphan = self.migration_path("support", "V20260906_0110__orphan.sql.conf")
        self.write(orphan, "executeInTransaction=false\n")
        self.commit_all("duplicate and orphan")

        result = self.guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("Flyway版本重复 20260906_0100", result.stderr)
        self.assertIn("Flyway sidecar缺少同名SQL", result.stderr)

    def test_integration_domain_is_registered(self) -> None:
        """新集成域仍受全局版本与模块目录约束，不能被误判为未登记。"""
        self.write(self.migration_path("integration", "V20260920_0360__api_key_facts.sql"))
        self.commit_all("integration domain")
        result = self.guard()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_issuer_domain_is_registered_and_audited(self) -> None:
        """发行方专属迁移已列入Bootstrap，仍须接受全局增量审计。"""
        base, _ = self.create_baseline()
        self.write(self.migration_path("issuer", "V20260928_0180__review_policy.sql"))
        self.commit_all("issuer domain")

        result = self.guard("--base-ref", base, "--head-ref", "HEAD")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Git增量审计通过", result.stdout)

    def test_issuer_legacy_slots_require_exact_published_bytes(self) -> None:
        """仅两份已发布发行迁移原文可沿用旧排序槽，篡改即拒绝。"""
        base, _ = self.create_baseline()
        filenames = (
            "V20260928_0101__self_hosted_enrollment_queue_index.sql",
            "V20260928_0102__self_hosted_review_attestation.sql",
        )
        source_directory = (
            SCRIPT.parents[1]
            / "things-link/things-link-issuer/src/main/resources/db/migration/issuer"
        )
        for filename in filenames:
            self.write(
                self.migration_path("issuer", filename),
                (source_directory / filename).read_text(encoding="utf-8"),
            )
        self.commit_all("published issuer migrations")

        accepted = self.guard("--base-ref", base, "--head-ref", "HEAD")
        self.assertEqual(0, accepted.returncode, accepted.stderr)

        legacy_path = self.migration_path("issuer", filenames[0])
        legacy_path.write_text(
            legacy_path.read_text(encoding="utf-8") + "-- changed\n", encoding="utf-8", newline=""
        )
        rejected = self.guard("--base-ref", base, "--head-ref", "HEAD")
        self.assertEqual(1, rejected.returncode)
        self.assertIn("迁移排序槽必须位于0100..9990且按10递增", rejected.stderr)

    def test_static_check_rejects_wrong_domain_directory(self) -> None:
        """迁移候选即使放错模块或目录，也必须被捕获而非静默忽略。"""

        self.write(self.migration_path("support", "V20260906_0100__baseline.sql"))
        wrong_directory = (
            self.repo
            / "things-link/things-link-dashboard/src/main/resources/db/migration/ota"
            / "V20260906_0110__wrong_owner.sql"
        )
        nested_directory = (
            self.repo
            / "things-link/things-link-ota/src/main/resources/db/migration/ota/nested"
            / "V20260906_0120__nested.sql"
        )
        unknown_domain = (
            self.repo
            / "things-link/things-link-billing/src/main/resources/db/migration/billing"
            / "V20260906_0130__unknown_domain.sql"
        )
        self.write(wrong_directory)
        self.write(nested_directory)
        self.write(unknown_domain)
        self.commit_all("wrong migration paths")

        result = self.guard()

        self.assertEqual(1, result.returncode)
        self.assertIn("生产迁移模块与domain目录不一致", result.stderr)
        self.assertIn("生产迁移路径不符合", result.stderr)
        self.assertIn("生产迁移域未登记在Bootstrap迁移location中", result.stderr)


if __name__ == "__main__":
    unittest.main()
