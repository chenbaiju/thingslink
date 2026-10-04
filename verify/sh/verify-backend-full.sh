#!/usr/bin/env bash
# ================================================================
#  ThingsLink 后端全量验证：clean verify
#   - 边看控制台边写日志（tee），不会静默
#   - 日志递增命名 verify-1.log / verify-2.log ...，不覆盖旧文件
#   - 日志目录：<项目根>/logs/backend（logs/ 已在 .gitignore，不进 git）
#   - 与 CI backend.yml 一致：./mvnw -B clean verify（-B 关闭进度条，日志可读）
# 用法：bash verify/sh/verify-backend-full.sh
# ================================================================

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_DIR="$ROOT_DIR/things-link"
LOGDIR="$ROOT_DIR/logs/backend"

mkdir -p "$LOGDIR"

N=1
while [ -f "$LOGDIR/verify-$N.log" ]; do
  N=$((N + 1))
done
LOG="$LOGDIR/verify-$N.log"

echo
echo "[verify] 后端目录 : $BACKEND_DIR"
echo "[verify] 日志文件 : $LOG"
echo "[verify] 开始全量验证（clean verify）..."
echo

bash "$ROOT_DIR/scripts/prepare-test-tls.sh" 2>&1 | tee "$LOG"
PREPARE_EXIT=${PIPESTATUS[0]}
if [ "$PREPARE_EXIT" -ne 0 ]; then
  echo "[verify] Test TLS preparation failed ($PREPARE_EXIT); Maven was not started."
  exit "$PREPARE_EXIT"
fi

cd "$BACKEND_DIR" || { echo "[verify] 无法进入后端目录：$BACKEND_DIR"; exit 1; }

./mvnw -B clean verify 2>&1 | tee -a "$LOG"
EXIT=${PIPESTATUS[0]}

echo
if [ "$EXIT" -eq 0 ]; then
  echo "[verify] BUILD SUCCESS（退出码 0）"
else
  echo "[verify] BUILD FAILED（退出码 ${EXIT}）"
fi
echo "[verify] 完整日志：$LOG"
echo

exit "$EXIT"
