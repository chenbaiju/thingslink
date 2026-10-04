#!/usr/bin/env python3
"""校验生产 Flyway 迁移的静态命名和相对 Git 基线的不可变性。"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Callable, Iterable, Sequence


MIGRATION_CANDIDATE_DIRECTORY = re.compile(
    r"^things-link/things-link-[^/]+/src/main/resources/db/migration(?:/.*)?$"
)
MIGRATION_DIRECTORY = re.compile(
    r"^things-link/things-link-(?P<module>[a-z][a-z0-9-]*)/"
    r"src/main/resources/db/migration/(?P<domain>[a-z][a-z0-9-]*)/(?P<filename>[^/]+)$"
)
MIGRATION_FILENAME = re.compile(
    r"^V(?P<date>\d{8})_(?P<slot>\d{4})__"
    r"(?P<description>[a-z][a-z0-9]*(?:_[a-z0-9]+)*)\.sql$"
)
ALLOWED_MIGRATION_DOMAINS = frozenset(
    {
        "support",
        "project",
        "issuer",
        "device",
        "telemetry",
        "alarm",
        "task",
        "rule",
        "iam",
        "enduser",
        "export",
        "dashboard",
        "ota",
        "integration",
    }
)
# 这两份已提交并在持久库执行的迁移不能改名或改字节；仅原路径与原文豁免旧排序槽。
LEGACY_MIGRATION_SHA256 = {
    "things-link/things-link-issuer/src/main/resources/db/migration/issuer/"
    "V20260928_0101__self_hosted_enrollment_queue_index.sql":
        "53ea668dc89eb9655000c0bc61bd0b1aa6d33b28d380e38482cd89a65cc32ff8",
    "things-link/things-link-issuer/src/main/resources/db/migration/issuer/"
    "V20260928_0102__self_hosted_review_attestation.sql":
        "e5c8a14b5b0aab6e38e31be95bbbc08a8578ce5e1be71afeb5a7262dbc1dfce6",
}
MigrationContentReader = Callable[[str], bytes]


@dataclass(frozen=True, order=True)
class MigrationVersion:
    """日期与当日十进排序槽组成的可比较版本。"""

    date: str
    slot: int

    def display(self) -> str:
        """返回与迁移文件一致的版本文本。"""

        return f"{self.date}_{self.slot:04d}"


@dataclass(frozen=True)
class MigrationEntry:
    """已通过文件名解析的生产迁移。"""

    path: str
    version: MigrationVersion


def is_migration_candidate_path(path: str) -> bool:
    """捕获生产迁移根下的全部SQL与sidecar候选，避免错误目录被漏扫。"""

    normalized = path.replace("\\", "/")
    return bool(MIGRATION_CANDIDATE_DIRECTORY.fullmatch(normalized)) and (
        normalized.endswith(".sql") or normalized.endswith(".sql.conf")
    )


def validate_migration_location(path: str) -> str | None:
    """校验模块、迁移域目录与Bootstrap迁移location完全一致。"""

    normalized = path.replace("\\", "/")
    match = MIGRATION_DIRECTORY.fullmatch(normalized)
    if not match:
        return f"生产迁移路径不符合things-link-<domain>/.../migration/<domain>/文件：{path}"
    module = match.group("module")
    domain = match.group("domain")
    if domain not in ALLOWED_MIGRATION_DOMAINS:
        return f"生产迁移域未登记在Bootstrap迁移location中：{path}"
    if module != domain:
        return f"生产迁移模块与domain目录不一致：{path}"
    return None


def parse_migration(
    path: str, read_content: MigrationContentReader | None = None
) -> tuple[MigrationEntry | None, str | None]:
    """解析一个 SQL 迁移文件，并返回稳定的格式错误。"""

    filename = Path(path).name
    match = MIGRATION_FILENAME.fullmatch(filename)
    if not match:
        return None, (
            f"迁移文件名不符合 VyyyyMMdd_NNNN__lower_snake.sql：{path}"
        )

    date_text = match.group("date")
    try:
        parsed_date = datetime.strptime(date_text, "%Y%m%d")
    except ValueError:
        return None, f"迁移日期无效：{path}"
    if parsed_date.strftime("%Y%m%d") != date_text:
        return None, f"迁移日期无效：{path}"

    slot = int(match.group("slot"))
    if slot < 100 or slot > 9990 or slot % 10 != 0:
        expected_digest = LEGACY_MIGRATION_SHA256.get(path)
        if expected_digest and read_content:
            actual_digest = hashlib.sha256(read_content(path)).hexdigest()
            if actual_digest == expected_digest:
                return MigrationEntry(path, MigrationVersion(date_text, slot)), None
        return None, f"迁移排序槽必须位于0100..9990且按10递增：{path}"

    return MigrationEntry(path, MigrationVersion(date_text, slot)), None


def validate_inventory(
    paths: Iterable[str],
    label: str,
    read_content: MigrationContentReader | None = None,
) -> tuple[list[MigrationEntry], list[str]]:
    """校验一棵树中的格式、全局版本唯一性和 sidecar 配对。"""

    normalized_paths = sorted({path.replace("\\", "/") for path in paths})
    candidates = [path for path in normalized_paths if is_migration_candidate_path(path)]
    valid_candidates: list[str] = []
    entries: list[MigrationEntry] = []
    errors: list[str] = []

    for path in candidates:
        location_error = validate_migration_location(path)
        if location_error:
            errors.append(f"{label}：{location_error}")
        else:
            valid_candidates.append(path)

    sql_paths = [path for path in valid_candidates if path.endswith(".sql")]
    sidecars = [path for path in valid_candidates if path.endswith(".sql.conf")]

    for path in sql_paths:
        entry, error = parse_migration(path, read_content)
        if error:
            errors.append(f"{label}：{error}")
        elif entry:
            entries.append(entry)

    paths_by_version: dict[MigrationVersion, list[str]] = {}
    for entry in entries:
        paths_by_version.setdefault(entry.version, []).append(entry.path)
    for version, duplicate_paths in sorted(paths_by_version.items()):
        if len(duplicate_paths) > 1:
            errors.append(
                f"{label}：Flyway版本重复 {version.display()}："
                + "、".join(sorted(duplicate_paths))
            )

    sql_path_set = set(sql_paths)
    for sidecar in sidecars:
        sql_path = sidecar.removesuffix(".conf")
        if sql_path not in sql_path_set:
            errors.append(f"{label}：Flyway sidecar缺少同名SQL：{sidecar}")

    if not sql_paths:
        errors.append(f"{label}：未发现任何生产Flyway SQL迁移，拒绝空扫描假绿")

    return entries, errors


def run_git(repo_root: Path, arguments: Sequence[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    """在仓库根目录执行 Git 并捕获稳定文本输出。"""

    return subprocess.run(
        ["git", *arguments],
        cwd=repo_root,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def git_blob_content(repo_root: Path, commit: str, path: str) -> bytes:
    """按指定Git树读取原始迁移字节，不借用可能不同步的工作树。"""

    return subprocess.run(
        ["git", "show", f"{commit}:{path}"],
        cwd=repo_root,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    ).stdout


def resolve_commit(repo_root: Path, ref: str) -> str | None:
    """将引用解析为提交；不可达时返回空。"""

    result = run_git(repo_root, ["rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"], False)
    return result.stdout.strip() if result.returncode == 0 else None


def git_tree_paths(repo_root: Path, commit: str) -> list[str]:
    """读取指定提交的全部文件路径。"""

    result = run_git(repo_root, ["ls-tree", "-r", "--name-only", commit])
    return [line for line in result.stdout.splitlines() if line]


def resolve_merge_base(repo_root: Path, base: str, head: str) -> str | None:
    """求目标分支与功能分支的共同祖先；历史不相交时返回空。"""

    result = run_git(repo_root, ["merge-base", base, head], False)
    return result.stdout.strip() if result.returncode == 0 else None


def workspace_paths(repo_root: Path) -> list[str]:
    """读取工作树内全部生产迁移候选路径，包括未跟踪文件。"""

    backend = repo_root / "things-link"
    if not backend.is_dir():
        return []
    paths: list[str] = []
    for path in backend.glob("things-link-*/src/main/resources/db/migration/**/*"):
        if path.is_file() and (path.name.endswith(".sql") or path.name.endswith(".sql.conf")):
            paths.append(path.relative_to(repo_root).as_posix())
    return paths


def added_migration_errors(
    path: str,
    base_max: MigrationVersion,
    protected_sql_paths: frozenset[str],
    read_content: MigrationContentReader,
) -> list[str]:
    """按最新目标分支最大版本校验一个新增迁移候选。"""

    if validate_migration_location(path):
        return []
    if path.endswith(".sql.conf"):
        sql_path = path.removesuffix(".conf")
        if sql_path in protected_sql_paths:
            return [f"既有生产迁移SQL不可新增sidecar：{path}"]
        return []
    if not path.endswith(".sql"):
        return []
    entry, error = parse_migration(path, read_content)
    if error:
        return [error]
    if entry and entry.version <= base_max:
        return [
            f"新增迁移版本 {entry.version.display()} 不大于基线最大版本 "
            f"{base_max.display()}：{path}"
        ]
    return []


def changed_migration_errors(
    repo_root: Path,
    comparison_base: str,
    base_max: MigrationVersion,
    protected_sql_paths: frozenset[str],
    comparison_head: str | None = None,
) -> list[str]:
    """拒绝历史迁移变更，并要求新增SQL越过最新base的全局最大版本。"""

    diff_range = [comparison_base]
    if comparison_head:
        diff_range.append(comparison_head)
    result = run_git(
        repo_root,
        [
            "diff",
            "--name-status",
            "--find-renames=50%",
            "--find-copies=50%",
            "--find-copies-harder",
            *diff_range,
            "--",
        ],
    )
    errors: list[str] = []
    read_content = (
        (lambda path: git_blob_content(repo_root, comparison_head, path))
        if comparison_head else (lambda path: (repo_root / path).read_bytes())
    )
    for line in result.stdout.splitlines():
        if not line:
            continue
        fields = line.split("\t")
        status = fields[0]
        code = status[0]
        paths = fields[1:]
        migration_paths = [path for path in paths if is_migration_candidate_path(path)]
        if not migration_paths:
            continue

        if code in {"M", "D", "R"}:
            errors.append(
                f"既有生产迁移不可{code}：" + " -> ".join(migration_paths)
            )
            continue

        if code in {"A", "C"}:
            path = paths[-1]
            if not is_migration_candidate_path(path):
                continue
            errors.extend(
                added_migration_errors(path, base_max, protected_sql_paths, read_content)
            )
    return errors


def workspace_addition_errors(
    workspace: Iterable[str],
    merge_base_paths: Iterable[str],
    base_max: MigrationVersion,
    protected_sql_paths: frozenset[str],
    read_content: MigrationContentReader,
) -> list[str]:
    """校验共同祖先后出现在当前工作树的提交内及未跟踪新增SQL。"""

    ancestor_paths = {path.replace("\\", "/") for path in merge_base_paths}
    errors: list[str] = []
    for path in sorted({item.replace("\\", "/") for item in workspace} - ancestor_paths):
        if is_migration_candidate_path(path):
            errors.extend(
                added_migration_errors(path, base_max, protected_sql_paths, read_content)
            )
    return errors


def find_repo_root() -> Path:
    """定位当前Git仓库根目录。"""

    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        raise RuntimeError("当前目录不在Git仓库中")
    return Path(result.stdout.strip()).resolve()


def build_parser() -> argparse.ArgumentParser:
    """创建命令行参数解析器。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", help="增量不可变审计的基线Git引用")
    parser.add_argument("--head-ref", default="HEAD", help="增量不可变审计的目标Git引用")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """执行静态守卫，并在提供可达基线时执行Git增量审计。"""

    args = build_parser().parse_args(argv)
    try:
        repo_root = find_repo_root()
        current_workspace_paths = workspace_paths(repo_root)
        workspace_reader = lambda path: (repo_root / path).read_bytes()
        _, errors = validate_inventory(
            current_workspace_paths, "当前工作树", workspace_reader
        )

        if not args.base_ref:
            if errors:
                for error in errors:
                    print(f"ERROR: {error}", file=sys.stderr)
                return 1
            print("当前工作树Flyway静态检查通过；未提供--base-ref，未执行Git增量不可变审计。")
            return 0

        base = resolve_commit(repo_root, args.base_ref)
        if not base:
            print(f"ERROR: --base-ref不可达：{args.base_ref}", file=sys.stderr)
            return 2
        head = resolve_commit(repo_root, args.head_ref)
        if not head:
            print(f"ERROR: --head-ref不可达：{args.head_ref}", file=sys.stderr)
            return 2
        merge_base = resolve_merge_base(repo_root, base, head)
        if not merge_base:
            print("ERROR: base-ref与head-ref没有可达共同祖先，无法执行增量审计", file=sys.stderr)
            return 2

        base_entries, base_errors = validate_inventory(
            git_tree_paths(repo_root, base), "Git基线",
            lambda path: git_blob_content(repo_root, base, path),
        )
        _, head_errors = validate_inventory(
            git_tree_paths(repo_root, head), "Git目标",
            lambda path: git_blob_content(repo_root, head, path),
        )
        errors.extend(base_errors)
        errors.extend(head_errors)
        worktree_incremental_note = "工作树增量审计未执行"
        if base_entries:
            base_max = max(entry.version for entry in base_entries)
            merge_base_paths = git_tree_paths(repo_root, merge_base)
            protected_sql_paths = frozenset(
                path
                for path in {*git_tree_paths(repo_root, base), *merge_base_paths}
                if is_migration_candidate_path(path) and path.endswith(".sql")
            )
            errors.extend(
                changed_migration_errors(
                    repo_root, merge_base, base_max, protected_sql_paths, head
                )
            )
            checkout_head = resolve_commit(repo_root, "HEAD")
            if checkout_head == head:
                errors.extend(
                    changed_migration_errors(
                        repo_root, merge_base, base_max, protected_sql_paths
                    )
                )
                errors.extend(
                    workspace_addition_errors(
                        current_workspace_paths,
                        merge_base_paths,
                        base_max,
                        protected_sql_paths,
                        workspace_reader,
                    )
                )
                worktree_incremental_note = "工作树增量审计已执行（checkout HEAD与显式head一致）"
            else:
                checkout_display = checkout_head[:12] if checkout_head else "不可达"
                worktree_incremental_note = (
                    "跳过工作树增量审计："
                    f"checkout HEAD={checkout_display} 与显式head={head[:12]}不同；"
                    "仍已执行工作树静态清单检查"
                )

        if errors:
            print(worktree_incremental_note)
            for error in dict.fromkeys(errors):
                print(f"ERROR: {error}", file=sys.stderr)
            return 1
        print(
            "Flyway静态检查与Git增量审计通过："
            f"分支变化={merge_base[:12]}..{head[:12]}，"
            f"新增版本下界=最新base {base[:12]} 的 {base_max.display()}；"
            f"{worktree_incremental_note}"
        )
        return 0
    except (OSError, subprocess.CalledProcessError, RuntimeError) as error:
        print(f"ERROR: Flyway迁移守卫执行失败：{error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
