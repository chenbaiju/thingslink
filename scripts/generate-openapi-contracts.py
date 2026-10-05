#!/usr/bin/env python3
"""以唯一写租约生成并发布 OpenAPI 及其当前消费端类型。"""

from __future__ import annotations

import argparse
import contextlib
import dataclasses
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Callable, Iterator, Mapping, Sequence
from pathlib import Path


CONSOLE_GENERATION_ALIAS = "python3 ../scripts/generate-openapi-contracts.py"
# 显式消费端闭集：缺失任何已登记工程必须失败，不能通过目录探测静默漏生成。
CONSUMER_DIRECTORIES = ("things-link-console", "things-link-webapp")


class GenerationError(RuntimeError):
    """表示生成候选不完整、已经漂移或无法安全发布。"""


class LeaseConflict(GenerationError):
    """表示另一进程正在持有 OpenAPI 唯一写租约。"""


@dataclasses.dataclass(frozen=True)
class FileState:
    """目标文件在生成开始时的存在性与内容摘要。"""

    exists: bool
    sha256: str | None


@dataclasses.dataclass(frozen=True)
class CandidateIdentity:
    """绑定本次生成输入与待替换目标的本地候选身份。"""

    head: str
    input_sha256: str
    target_states: tuple[FileState, ...]


@dataclasses.dataclass(frozen=True)
class GenerationResult:
    """成功发布后的候选身份与全部生成物摘要。"""

    candidate: CandidateIdentity
    openapi_sha256: str
    console_schema_sha256: str
    webapp_schema_sha256: str


CommandRunner = Callable[[Sequence[str], Path, Mapping[str, str]], None]
ReplaceFile = Callable[[str | bytes | os.PathLike[str] | os.PathLike[bytes],
                        str | bytes | os.PathLike[str] | os.PathLike[bytes]], None]


def run_checked(command: Sequence[str], cwd: Path, environment: Mapping[str, str]) -> None:
    """运行一个生成步骤，失败时保留原退出码与命令身份。"""

    try:
        completed = subprocess.run(command, cwd=cwd, env=dict(environment), check=False)
    except OSError as exception:
        raise GenerationError(f"无法启动生成步骤：{Path(command[0]).name}（{exception}）") from exception
    if completed.returncode != 0:
        raise GenerationError(
            f"生成步骤失败（exit={completed.returncode}）：{Path(command[0]).name}"
        )


def sha256_file(path: Path) -> str:
    """流式计算文件摘要，避免把大型契约一次读入内存。"""

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_state(path: Path) -> FileState:
    """读取目标文件状态；目录或其他特殊节点一律拒绝。"""

    if path.is_symlink():
        raise GenerationError(f"生成目标不能是符号链接：{path}")
    if not path.exists():
        return FileState(False, None)
    if not path.is_file():
        raise GenerationError(f"生成目标不是普通文件：{path}")
    return FileState(True, sha256_file(path))


def validate_repository_target(repository: Path, target: Path) -> None:
    """拒绝仓外目标及仓库根以下任何符号链接路径组件。"""

    repository = repository.resolve(strict=True)
    absolute_target = Path(os.path.abspath(target))
    try:
        relative = absolute_target.relative_to(repository)
    except ValueError as exception:
        raise GenerationError(f"生成目标位于仓库外：{target}") from exception
    cursor = repository
    for part in relative.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise GenerationError(f"生成目标路径包含符号链接：{cursor}")


def git_output(repository: Path, *arguments: str) -> bytes:
    """读取 Git 结果，并把不可达仓库或引用转成明确失败。"""

    completed = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise GenerationError(f"Git 候选身份读取失败：{detail or arguments[0]}")
    return completed.stdout


def current_head(repository: Path) -> str:
    """返回生成候选所在的精确 HEAD。"""

    return git_output(repository, "rev-parse", "--verify", "HEAD").decode().strip()


def relevant_input_paths(repository: Path) -> tuple[Path, ...]:
    """列出影响后端契约或当前 TypeScript 消费端生成的受控输入。

    使用 Git 的已跟踪与未跟踪未忽略集合，既覆盖正常切片中的未提交源码，
    又不会把 Maven target、node_modules 等运行产物误判为候选漂移。
    """

    output = git_output(
        repository,
        "ls-files",
        "--cached",
        "--others",
        "--exclude-standard",
        "-z",
        "--",
        "things-link",
        "things-link-console/package.json",
        "things-link-console/pnpm-lock.yaml",
        "things-link-webapp/package.json",
        "things-link-webapp/pnpm-lock.yaml",
        "scripts/generate-openapi-contracts.py",
    )
    relative_paths = {entry for entry in output.split(b"\0") if entry}
    # 实际运行的生成器包也是候选输入；只摘要该包，避免扫描整个node_modules。
    for consumer in CONSUMER_DIRECTORIES:
        # 即使消费端索引状态改变，也须冻结清单和锁文件的实际内容。
        for filename in ("package.json", "pnpm-lock.yaml"):
            relative_paths.add(os.fsencode(f"{consumer}/{filename}"))
        generator_package = repository / consumer / "node_modules/openapi-typescript"
        if generator_package.is_dir():
            for runtime_path in generator_package.rglob("*"):
                if runtime_path.is_file() or runtime_path.is_symlink():
                    relative_paths.add(os.fsencode(runtime_path.relative_to(repository).as_posix()))
        else:
            relative_paths.add(os.fsencode(f"{consumer}/node_modules/openapi-typescript/package.json"))
    relative_paths = sorted(relative_paths)
    return tuple(repository / os.fsdecode(entry) for entry in relative_paths)


def relevant_input_digest(repository: Path) -> str:
    """计算路径、节点类型与内容共同参与的工作树摘要。"""

    digest = hashlib.sha256()
    for path in relevant_input_paths(repository):
        relative = path.relative_to(repository).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        if path.is_symlink():
            payload = os.readlink(path).encode("utf-8")
            digest.update(b"L")
        elif path.is_file():
            payload = path.read_bytes()
            digest.update(b"F")
        elif not path.exists():
            # 已跟踪但在工作树删除的文件也必须进入候选身份。
            payload = b""
            digest.update(b"D")
        else:
            raise GenerationError(f"相关输入不是普通文件或符号链接：{path}")
        mode = path.lstat().st_mode & 0o7777 if path.exists() else 0
        digest.update(mode.to_bytes(4, "big"))
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def candidate_identity(repository: Path, targets: Sequence[Path]) -> CandidateIdentity:
    """在租约内采集生成开始时的完整候选身份。"""

    for target in targets:
        validate_repository_target(repository, target)
    return CandidateIdentity(
        head=current_head(repository),
        input_sha256=relevant_input_digest(repository),
        target_states=tuple(file_state(target) for target in targets),
    )


def git_lock_path(repository: Path) -> Path:
    """把锁放在当前工作树的 Git 元数据目录，避免进入提交候选。"""

    raw = git_output(repository, "rev-parse", "--git-path", "thingslink-openapi.lock")
    resolved = Path(raw.decode().strip())
    return resolved if resolved.is_absolute() else repository / resolved


def _try_lock(handle: object) -> None:
    """用当前平台的 OS 文件锁执行一次非阻塞独占领取。"""

    if os.name == "nt":
        import msvcrt

        file_handle = handle
        file_handle.seek(0, os.SEEK_END)
        if file_handle.tell() == 0:
            file_handle.write(b"\0")
            file_handle.flush()
        file_handle.seek(0)
        msvcrt.locking(file_handle.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)


def _unlock(handle: object) -> None:
    """释放当前平台的 OS 文件锁；进程异常退出时内核也会自动释放。"""

    if os.name == "nt":
        import msvcrt

        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    else:
        import fcntl

        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


@contextlib.contextmanager
def exclusive_generation_lease(repository: Path, token: str) -> Iterator[Path]:
    """领取不等待的跨平台 OS 锁，并写入仅用于冲突诊断的持有者信息。"""

    lock_path = git_lock_path(repository)
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as handle:
        try:
            _try_lock(handle)
        except OSError as exc:
            handle.seek(0)
            try:
                holder = handle.read().decode("utf-8", errors="replace").strip("\0\r\n ")
            except OSError:
                # Windows prevents reading a byte range held by another process.
                # Diagnostic metadata is optional; the lease conflict still fails closed.
                holder = "身份无法读取（锁正被持有）"
            raise LeaseConflict(
                "OpenAPI 生成租约正被另一进程持有；"
                f"当前持有者：{holder or '身份尚未写入'}"
            ) from exc

        try:
            metadata = json.dumps(
                {"pid": os.getpid(), "token": token[:12]},
                ensure_ascii=False,
                sort_keys=True,
            ).encode("utf-8")
            handle.seek(0)
            handle.truncate()
            handle.write(metadata)
            handle.flush()
            os.fsync(handle.fileno())
            yield lock_path
        finally:
            _unlock(handle)


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    """构造JSON对象并拒绝重复键，避免last-wins掩盖候选差异。"""

    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise GenerationError(f"JSON对象含重复键：{key}")
        result[key] = value
    return result


def load_json_strict(path: Path) -> object:
    """按UTF-8读取JSON，并保留数值、布尔等原始类型边界。"""

    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=_reject_duplicate_json_keys,
    )


def json_values_identical(left: object, right: object) -> bool:
    """比较JSON树并要求节点类型相同，避免Python把true、1和1.0视为相等。"""

    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        if left.keys() != right.keys():
            return False
        return all(json_values_identical(left[key], right[key]) for key in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(
            json_values_identical(left_value, right_value)
            for left_value, right_value in zip(left, right, strict=True)
        )
    return left == right


def validate_staged_openapi(path: Path) -> None:
    """拒绝错误响应或残缺 JSON 冒充 OpenAPI 生成物。"""

    try:
        document = load_json_strict(path)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GenerationError(f"暂存 OpenAPI 不是有效 UTF-8 JSON：{exc}") from exc
    if not isinstance(document, dict):
        raise GenerationError("暂存 OpenAPI 顶层必须是对象")
    version = document.get("openapi")
    if not isinstance(version, str) or not version.startswith("3.1"):
        raise GenerationError("暂存文件不是 OpenAPI 3.1 契约")
    if not isinstance(document.get("paths"), dict) or not isinstance(document.get("components"), dict):
        raise GenerationError("暂存 OpenAPI 缺少 paths 或 components 对象")


def preserve_semantically_equal_openapi(staged: Path, baseline: Path) -> bool:
    """语义未变时复用已提交字节，避免JSON对象成员顺序制造无意义生成diff。"""

    if not baseline.is_file():
        return False
    try:
        staged_document = load_json_strict(staged)
        baseline_document = load_json_strict(baseline)
    except (OSError, UnicodeError, json.JSONDecodeError, GenerationError):
        return False
    if not json_values_identical(staged_document, baseline_document):
        return False
    shutil.copyfile(baseline, staged)
    return True


def validate_staged_schema(path: Path) -> None:
    """确认消费端产物具有生成器标识与 paths 主类型。"""

    try:
        schema = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise GenerationError(f"暂存消费端类型不可读：{exc}") from exc
    if "This file was auto-generated by openapi-typescript" not in schema:
        raise GenerationError("暂存消费端类型缺少 openapi-typescript 生成标识")
    if "export interface paths" not in schema:
        raise GenerationError("暂存消费端类型缺少 paths 接口")


def validate_consumer_registration(console: Path) -> str:
    """静态确认消费端委托根入口，并返回锁文件登记的生成器版本。"""

    package_path = console / "package.json"
    try:
        package = json.loads(package_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GenerationError(f"已注册消费端清单不可读：{exc}") from exc
    scripts = package.get("scripts")
    # 兼容目标既有的 Windows/Unix Python 入口，只接受固定启动器和根生成器。
    portable_alias = (
        isinstance(scripts, dict)
        and scripts.get("api:generate") == "pnpm run python ../scripts/generate-openapi-contracts.py"
        and scripts.get("python") == 'node -e "const { spawnSync } = require(\'node:child_process\'); const r = spawnSync(process.env.PYTHON || (process.platform === \'win32\' ? \'python\' : \'python3\'), process.argv.slice(1), { stdio: \'inherit\', env: { ...process.env, PYTHONUTF8: \'1\' } }); if (r.error) console.error(r.error.message); process.exit(r.status ?? 1)" --'
    )
    if not isinstance(scripts, dict) or (
        scripts.get("api:generate") != CONSOLE_GENERATION_ALIAS and not portable_alias
    ):
        raise GenerationError(
            "消费端 api:generate必须委托OpenAPI根入口，禁止恢复独立文件写入"
        )

    lock_path = console / "pnpm-lock.yaml"
    try:
        lock_text = lock_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise GenerationError(f"消费端生成器锁文件不可读：{exc}") from exc
    locked_match = re.search(
        r"(?m)^      openapi-typescript:\n"
        r"        specifier: [^\n]+\n"
        r"        version: ([0-9]+\.[0-9]+\.[0-9]+)(?:\(|$)",
        lock_text,
    )
    if locked_match is None:
        raise GenerationError("pnpm锁文件没有登记消费端 OpenAPI生成器版本")
    return locked_match.group(1)


def validate_installed_generator(console: Path, locked_version: str) -> None:
    """实际生成前确认本地运行时与冻结锁版本完全一致。"""

    installed_path = console / "node_modules/openapi-typescript/package.json"
    try:
        installed = json.loads(installed_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GenerationError(f"消费端生成器安装事实不可读：{exc}") from exc
    if installed.get("version") != locked_version:
        raise GenerationError(
            "已安装openapi-typescript与pnpm锁文件不一致；请在对应消费端执行"
            "pnpm install --frozen-lockfile"
        )


def ensure_candidate_unchanged(
    repository: Path,
    targets: Sequence[Path],
    expected: CandidateIdentity,
) -> None:
    """发布前确认生产输入与两个目标都没有被并发改写。"""

    for target in targets:
        validate_repository_target(repository, target)
    if current_head(repository) != expected.head:
        raise GenerationError("生成期间 HEAD 已变化，拒绝发布陈旧 OpenAPI 候选")
    if relevant_input_digest(repository) != expected.input_sha256:
        raise GenerationError("生成期间相关工作树输入已变化，拒绝发布陈旧 OpenAPI 候选")
    actual_targets = tuple(file_state(target) for target in targets)
    if actual_targets != expected.target_states:
        raise GenerationError("生成期间 OpenAPI 目标文件已变化，CAS 冲突，拒绝覆盖")


def _sync_file(path: Path) -> None:
    """在 rename 前刷新暂存内容，缩小掉电后出现空文件的窗口。"""

    with path.open("r+b") as handle:
        os.fsync(handle.fileno())


def publish_with_rollback(
    repository: Path,
    staged: Sequence[Path],
    targets: Sequence[Path],
    expected_states: Sequence[FileState],
    temporary_directory: Path,
    post_publish_check: Callable[[], None],
    replace_file: ReplaceFile = os.replace,
) -> None:
    """锁内逐文件原子替换；任一步失败便恢复已经替换的目标。"""

    if not (len(staged) == len(targets) == len(expected_states)):
        raise GenerationError("发布清单长度不一致")

    backups: list[Path | None] = []
    for index, (target, state) in enumerate(zip(targets, expected_states, strict=True)):
        validate_repository_target(repository, target)
        if state.exists:
            backup = temporary_directory / f"backup-{index}"
            shutil.copy2(target, backup)
            backups.append(backup)
        else:
            backups.append(None)

    published: list[int] = []
    try:
        for index, (source, target, expected_state) in enumerate(
            zip(staged, targets, expected_states, strict=True)
        ):
            validate_repository_target(repository, target)
            if file_state(target) != expected_state:
                raise GenerationError(f"发布前目标 CAS 冲突：{target}")
            target.parent.mkdir(parents=True, exist_ok=True)
            _sync_file(source)
            replace_file(source, target)
            published.append(index)
        post_publish_check()
    except BaseException as original:
        rollback_errors: list[str] = []
        for index in reversed(published):
            target = targets[index]
            backup = backups[index]
            try:
                validate_repository_target(repository, target)
                if backup is None:
                    target.unlink(missing_ok=True)
                else:
                    replace_file(backup, target)
            except BaseException as rollback_error:
                rollback_errors.append(f"{target}: {rollback_error}")
        if rollback_errors:
            raise GenerationError(
                f"发布失败且回滚不完整：{original}；{'；'.join(rollback_errors)}"
            ) from original
        if isinstance(original, GenerationError):
            raise
        if isinstance(original, Exception):
            raise GenerationError(f"生成物发布失败：{original}") from original
        raise


def generate_contracts(
    repository: Path,
    *,
    command_runner: CommandRunner = run_checked,
    replace_file: ReplaceFile = os.replace,
) -> GenerationResult:
    """生成、验证并在候选未漂移时一次发布 OpenAPI、Console 与 WebApp 类型。"""

    repository = repository.resolve()
    backend = repository / "things-link"
    consumers = tuple(repository / name for name in CONSUMER_DIRECTORIES)
    openapi_target = repository / "docs/openapi.json"
    targets = (openapi_target, *(consumer / "src/types/api/schema.d.ts" for consumer in consumers))

    wrapper = backend / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    if not wrapper.is_file():
        raise GenerationError(f"Maven Wrapper 不存在：{wrapper}")
    for consumer in consumers:
        if not (consumer / "node_modules/openapi-typescript/bin/cli.js").is_file():
            raise GenerationError(f"{consumer.name}生成器不存在；先在该工程执行pnpm install --frozen-lockfile")

    token = secrets.token_urlsafe(32)
    with exclusive_generation_lease(repository, token):
        identity = candidate_identity(repository, targets)
        # 校验位于租约和候选冻结之后；package在任一侧变化都由本检查或最终摘要拒绝。
        for consumer in consumers:
            locked_generator_version = validate_consumer_registration(consumer)
            validate_installed_generator(consumer, locked_generator_version)
        with tempfile.TemporaryDirectory(prefix=".openapi-generation-", dir=repository) as temporary:
            temporary_directory = Path(temporary)
            staged_openapi = temporary_directory / "openapi.json"
            staged_schemas = tuple(temporary_directory / f"{consumer.name}-schema.d.ts" for consumer in consumers)
            environment = dict(os.environ)
            environment["THINGS_LINK_OPENAPI_WRITE_LEASE_TOKEN"] = token

            backend_command = [
                str(wrapper),
                "-B",
                "-pl",
                "things-link-bootstrap",
                "-am",
                "clean",
                "test",
                "-Dtest=OpenApiSpecTests",
                "-DfailIfNoTests=false",
                "-Dsurefire.failIfNoSpecifiedTests=false",
                "-Dopenapi.write=true",
                f"-Dopenapi.output={staged_openapi}",
                f"-Dopenapi.write.lease-token={token}",
            ]
            command_runner(backend_command, backend, environment)
            validate_staged_openapi(staged_openapi)
            # Spring装配顺序不属于HTTP契约；语义相等时保留基线字节，生成类型也随之保持稳定。
            preserve_semantically_equal_openapi(staged_openapi, openapi_target)

            for consumer, staged_schema in zip(consumers, staged_schemas, strict=True):
                consumer_command = ["node", str(consumer / "node_modules/openapi-typescript/bin/cli.js"),
                                    str(staged_openapi), "-o", str(staged_schema)]
                command_runner(consumer_command, consumer, environment)
                validate_staged_schema(staged_schema)

            ensure_candidate_unchanged(repository, targets, identity)
            staged_outputs = (staged_openapi, *staged_schemas)
            staged_hashes = tuple(sha256_file(staged) for staged in staged_outputs)

            def post_publish_check() -> None:
                if current_head(repository) != identity.head:
                    raise GenerationError("发布期间 HEAD 已变化，已回滚生成物")
                if relevant_input_digest(repository) != identity.input_sha256:
                    raise GenerationError("发布期间相关工作树输入已变化，已回滚生成物")
                actual_hashes = tuple(sha256_file(target) for target in targets)
                if actual_hashes != staged_hashes:
                    raise GenerationError("发布后生成物摘要不一致，已回滚")

            publish_with_rollback(
                repository,
                staged_outputs,
                targets,
                identity.target_states,
                temporary_directory,
                post_publish_check,
                replace_file,
            )

        return GenerationResult(identity, *staged_hashes)


def parse_arguments(arguments: Sequence[str]) -> argparse.Namespace:
    """解析唯一入口参数；仓库根仅为低层测试和非默认检出路径保留。"""

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help=argparse.SUPPRESS,
    )
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    """命令行入口。"""

    options = parse_arguments(sys.argv[1:] if arguments is None else arguments)
    try:
        result = generate_contracts(options.repo_root)
    except (GenerationError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(
        "OpenAPI 生成物已按唯一租约发布："
        f"HEAD={result.candidate.head} "
        f"input={result.candidate.input_sha256} "
        f"openapi={result.openapi_sha256} "
        f"console={result.console_schema_sha256} "
        f"webapp={result.webapp_schema_sha256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
