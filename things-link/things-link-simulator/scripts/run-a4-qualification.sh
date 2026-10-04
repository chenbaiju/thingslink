#!/usr/bin/env bash
set -euo pipefail

# 从任意目录运行都定位到仓库内 wrapper；不依赖本机全局 Maven。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# D-163 根因修复：模拟器可执行 JAR 现在带 exec 分类器（主构件保留普通 JAR 供 bootstrap 测试编译）。
JAR="${BACKEND_DIR}/things-link-simulator/target/things-link-simulator-0.0.1-SNAPSHOT-exec.jar"

cd "${BACKEND_DIR}"
./mvnw -pl things-link-simulator -am package -DskipTests

if [[ ! -f "${JAR}" ]]; then
  echo "[a4-0] 错误：构建完成但未找到模拟器 JAR：${JAR}" >&2
  exit 1
fi

exec python3 "${SCRIPT_DIR}/a4_qualification.py" --jar "${JAR}" "$@"
