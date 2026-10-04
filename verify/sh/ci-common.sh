#!/usr/bin/env bash
# 仅供 verify 入口 source；不安装 Git hook、不提交、不推送。
VERIFY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

verify_need() {
  command -v "$1" >/dev/null 2>&1 || { echo "[verify] 缺少命令：$1" >&2; return 2; }
}

verify_java() {
  verify_need java
  local version
  version="$(java -version 2>&1)"
  [[ "$version" == *'version "21.'* ]] || { echo '[verify] 请使用与 CI 一致的 JDK 21。' >&2; return 2; }
  printf '%s\n' "$version"
}

verify_node() {
  verify_need node
  verify_need pnpm
  [[ "$(node -p 'process.versions.node.split(".")[0]')" == 22 ]] || {
    echo '[verify] 请将 Node.js 22 放到 PATH 首位；不能用其他主版本代替 CI 验证。' >&2; return 2;
  }
  local package expected
  for package in "$@"; do
    expected="$(node -p "require(process.argv[1]).packageManager.split('@')[1]" "$VERIFY_ROOT/$package/package.json")"
    [[ "$(cd "$VERIFY_ROOT/$package" && pnpm --version)" == "$expected" ]] || {
      echo "[verify] $package 要求 pnpm ${expected}，请先切换版本。" >&2; return 2;
    }
  done
  node --version
  pnpm --version
}

verify_install() {
  (cd "$VERIFY_ROOT/$1" && pnpm install --frozen-lockfile)
}

verify_docker() {
  verify_need docker
  docker info >/dev/null
  # 检查 Docker 实际数据分区；宿主 macOS 的剩余容量不能代替虚拟磁盘配额。
  local minimum_kib="${1:-15728640}" available_kib
  available_kib="$(docker run --rm --entrypoint df redis:7.4-alpine -Pk /data | awk 'NR==2 {print $4}')"
  if [[ ! "$available_kib" =~ ^[0-9]+$ || "$available_kib" -lt "$minimum_kib" ]]; then
    echo "[verify] Docker 数据分区至少需 $((minimum_kib / 1048576)) GiB 可用空间；当前 ${available_kib:-未知} KiB。" >&2
    return 2
  fi
}

verify_minio() {
  verify_need docker
  docker info >/dev/null
  local server client
  server=ghcr.io/teableio/minio@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e
  client=ghcr.io/teableio/minio-mc@sha256:aead63c77f9db9107f1696fb08ecb0faeda23729cde94b0f663edf4fe09728e3
  docker pull "$server"
  docker tag "$server" minio/minio:RELEASE.2025-04-22T22-12-26Z
  docker pull "$client"
  docker tag "$client" minio/mc:RELEASE.2025-04-16T18-13-26Z
}

# 与 CI 的 push.before 比较。默认采用本地已知的上游，不自动 fetch/修改 Git。
verify_refs() {
  verify_need git
  VERIFY_HEAD_REF="${VERIFY_HEAD_REF:-HEAD}"
  git cat-file -e "$VERIFY_HEAD_REF^{commit}"
  # 验证的是当前文件树；不允许元数据声称正在验证另一个提交。
  [[ "$(git rev-parse "$VERIFY_HEAD_REF^{commit}")" == "$(git rev-parse HEAD)" ]] || {
    echo '[verify] VERIFY_HEAD_REF 必须对应当前检出的 HEAD。' >&2; return 2;
  }
  if [[ "${VERIFY_BASE_REF+x}" != x ]]; then
    VERIFY_BASE_REF="$(git rev-parse --verify '@{upstream}' 2>/dev/null || true)"
  fi
  [[ "$VERIFY_BASE_REF" != 0000000000000000000000000000000000000000 ]] || VERIFY_BASE_REF=''
  if [[ -n "$VERIFY_BASE_REF" ]]; then
    git cat-file -e "$VERIFY_BASE_REF^{commit}"
    echo "[verify] 增量基线：${VERIFY_BASE_REF}；目标：${VERIFY_HEAD_REF}（含工作区改动）"
  else
    echo '[verify] 无增量基线：Flyway 仅静态检查，commitlint 仅最新提交；首次推送可使用此模式。'
  fi
}

# 必须在独立子 shell 中执行函数，不能放进 if/function || 的条件上下文；否则 Bash 会禁用 errexit。
verify_run() {
  local name="$1" function="$2"
  shift 2
  if [[ "${1:-}" == --help && "$#" -eq 1 ]]; then
    echo "用法：bash verify/sh/verify-$name.sh"
    echo '手动执行对应 CI；VERIFY_BASE_REF 可指定上次推送的提交，日志写入 logs/ci。'
    return 0
  fi
  [[ "$#" -eq 0 ]] || { echo '[verify] 不支持此参数；使用 --help。' >&2; return 2; }
  local directory log result tee_result
  mkdir -p "$VERIFY_ROOT/logs/ci"
  directory="$(mktemp -d "$VERIFY_ROOT/logs/ci/$name-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
  log="$directory/verify.log"
  echo "[verify] $name 日志：$log"
  set +e
  (
    set -euo pipefail
    cd "$VERIFY_ROOT"
    verify_refs
    export VERIFY_BASE_REF VERIFY_HEAD_REF
    # 保留 CI 的测试/浏览器运行策略，但不伪造 GITHUB_ACTIONS 或 GitHub 运行身份。
    export CI=true
    echo "[verify] 平台：$(uname -s)/$(uname -m)；开始：$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    git status --short --untracked-files=normal
    export VERIFY_RUN_DIR="$directory"
    "$function"
  ) 2>&1 | tee "$log"
  local codes=("${PIPESTATUS[@]}")
  set -e
  result="${codes[0]}"
  tee_result="${codes[1]}"
  if [[ "$tee_result" -ne 0 && "$result" -eq 0 ]]; then result="$tee_result"; fi
  printf '%s\n' "$result" > "$directory/exit-code"
  echo "[verify] $name exit=${result}；日志：$log"
  return "$result"
}
