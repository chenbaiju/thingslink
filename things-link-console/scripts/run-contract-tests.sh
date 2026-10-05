#!/usr/bin/env bash
# G1-C2c 契约测试运行脚本：起依赖栈 → 后端存在性/健康检查 → 严格清空专用 Redis DB → 预置账号 →
# 默认模式构建并启动后端 → 跑 openapi-fetch 行为契约 → 收尾。
#
# 用法（仓库根或 console 目录均可）：
#   ./things-link-console/scripts/run-contract-tests.sh
#
# 环境变量（可选）：
#   CONTRACT_BASE_URL   后端基地址，默认 http://localhost:8080
#   SKIP_BACKEND=1      复用已按本脚本同一环境启动的后端（专用 Redis DB 15，见下）
#
# 限流隔离：契约测试后端固定使用专用 Redis DB 15（**不允许外部覆盖，防止误清共享 dev 的 DB 0**），
# 每次运行（无论默认启动还是 SKIP_BACKEND=1 复用）都先认证并 FLUSHDB DB 15、再严格复核 DBSIZE 为 0，
# 保证注册 IP 限流计数每轮从 0 开始、严格序列 [204,204,204,429] 可重复。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONSOLE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$CONSOLE_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
BACKEND_DIR="$REPO_ROOT/things-link"

BASE_URL="${CONTRACT_BASE_URL:-http://localhost:8080}"
# 固定专用测试库；不支持外部覆盖（覆盖即可能误清共享 dev 的 DB 0）
REDIS_TEST_DB=15
PASSWORD="contract-pass-123"
TS="$(date +%s)"
OWNER_EMAIL="owner-${TS}@example.com"
MEMBER_EMAIL="member-${TS}@example.com"
BACKEND_PID=""
CONTRACT_LOG_TIMESTAMP="$(python3 -c 'import time; print(time.time_ns())')"
mkdir -p "${CONSOLE_DIR}/logs"
BACKEND_LOG="${CONSOLE_DIR}/logs/target-contract-backend-${CONTRACT_LOG_TIMESTAMP}.log"

log() { printf '\033[36m[contract]\033[0m %s\n' "$*"; }

# EXIT trap 必须保存并恢复原退出码，否则失败会被覆盖成 0（假绿）。
cleanup() {
  local rc=$?
  if [[ -n "${BACKEND_PID}" ]] && kill -0 "${BACKEND_PID}" 2>/dev/null; then
    kill "${BACKEND_PID}" 2>/dev/null || true
  fi
  exit "${rc}"
}
trap cleanup EXIT

# 严格清空专用 Redis DB：FLUSHDB 必须退出码 0 且输出严格为 OK；DBSIZE 必须退出码 0 且输出严格为 0。
# 用 `if out="$(...)"; then ... else rc=$?; ... fi` 捕获失败：set -e 下裸命令替换失败会直接退出，rc=$? 不可达。
flush_test_redis_db() {
  local password out rc
  password="$(grep -E '^REDIS_PASSWORD=' "${DEPLOY_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
  password="${password:-thingslink}"
  log "清空专用 Redis DB ${REDIS_TEST_DB}（只清测试库，不碰 dev 的 DB 0）"
  # 用 -e REDISCLI_AUTH 而不是 -a：-a 会在输出里混入密码命令行警告，破坏「输出严格为 OK」的判定
  local redis_cli=(docker exec -e "REDISCLI_AUTH=${password}" tc-redis redis-cli -n "${REDIS_TEST_DB}")

  if out="$("${redis_cli[@]}" FLUSHDB 2>&1)"; then
    if [[ "${out}" != "OK" ]]; then
      log "错误：FLUSHDB 输出异常（应为 OK，实际 '${out}'）"
      exit 1
    fi
  else
    rc=$?
    log "错误：FLUSHDB 失败（退出码 ${rc}，输出 '${out}'）"
    exit 1
  fi

  if out="$("${redis_cli[@]}" DBSIZE 2>&1)"; then
    if [[ "${out}" != "0" ]]; then
      log "错误：专用 Redis DB ${REDIS_TEST_DB} 未清空（DBSIZE 输出 '${out}'）"
      exit 1
    fi
  else
    rc=$?
    log "错误：DBSIZE 失败（退出码 ${rc}，输出 '${out}'）"
    exit 1
  fi
}

# 1. 依赖栈（PostgreSQL/TimescaleDB、Redis、Redpanda、EMQX、MinIO）
log "启动依赖栈（幂等）"
(cd "${DEPLOY_DIR}" && make up >/dev/null 2>&1)

# 2. 后端存在性/健康检查（在预置账号之前）：默认模式拒绝已存在的旧后端；SKIP 模式要求后端健康可达。
if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  if curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "错误：${BASE_URL} 已有后端在运行；若要用它请加 SKIP_BACKEND=1（它必须用专用 Redis DB ${REDIS_TEST_DB} 启动）"
    exit 1
  fi
  log "确认 ${BASE_URL} 无旧后端"
else
  if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "错误：SKIP_BACKEND=1 但 ${BASE_URL} 后端不可达"
    exit 1
  fi
  log "复用已有后端 ${BASE_URL}"
fi

# 3. 公共路径：严格清空并复核专用 Redis DB——默认启动与 SKIP_BACKEND=1 都必须执行，
#    保证限流计数每轮从 0 开始（否则复用模式第二次运行会得到 [429,429,429,429]）。
flush_test_redis_db

# 4. 预置两个已验证账号（OWNER 与成员，各自独立租户/项目）。
# 与 E2E 同一口径：夹具显式走 --legacy-runtime-baseline 以保留宽松额度（契约套件会连打多个端点）；
# 运维补号默认路径的商用供给由 deploy/sql/provision-console-tenant.sql 与真库用例覆盖（D-174）。
log "预置 OWNER 账号 ${OWNER_EMAIL}"
"${DEPLOY_DIR}/scripts/add-console-account.sh" \
  --email "${OWNER_EMAIL}" --password "${PASSWORD}" --display-name '契约OWNER' --project-name '契约项目' \
  --legacy-runtime-baseline >/dev/null
log "预置成员账号 ${MEMBER_EMAIL}"
"${DEPLOY_DIR}/scripts/add-console-account.sh" \
  --email "${MEMBER_EMAIL}" --password "${PASSWORD}" --display-name '契约成员' --project-name '成员项目' \
  --legacy-runtime-baseline >/dev/null

# 5. 默认模式：构建并启动后端（专用 Redis DB），等待健康检查。
if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  log "构建后端胖 jar（package 在 reactor 内完成，无需 install 到 ~/.m2）"
  (cd "${BACKEND_DIR}" && ./mvnw -q -pl things-link-bootstrap -am package -DskipTests)
  JAR="$(ls "${BACKEND_DIR}"/things-link-bootstrap/target/things-link-bootstrap-*.jar | head -1)"
  log "启动后端 ${JAR}（SPRING_DATA_REDIS_DATABASE=${REDIS_TEST_DB}）"
  SPRING_DATA_REDIS_DATABASE="${REDIS_TEST_DB}" java -jar "${JAR}" >"${BACKEND_LOG}" 2>&1 &
  BACKEND_PID=$!

  # 后端进程必须存活；若启动即退出（端口冲突/配置错），立即失败而不是误连旧服务。
  sleep 1
  if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
    log "错误：后端启动即退出，日志见 ${BACKEND_LOG}"
    exit 1
  fi

  for _ in $(seq 1 60); do
    if curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then break; fi
    sleep 1
  done
  if ! curl -sf "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "后端启动失败，日志见 ${BACKEND_LOG}"
    exit 1
  fi
fi

# 6. 跑契约测试（openapi-fetch 派生客户端 → 真实后端）
log "运行契约测试（BASE_URL=${BASE_URL}）"
(
  cd "${CONSOLE_DIR}"
  CONTRACT_BASE_URL="${BASE_URL}" \
  CONTRACT_OWNER_EMAIL="${OWNER_EMAIL}" \
  CONTRACT_OWNER_PASSWORD="${PASSWORD}" \
  CONTRACT_MEMBER_EMAIL="${MEMBER_EMAIL}" \
  CONTRACT_MEMBER_PASSWORD="${PASSWORD}" \
    pnpm test:contract
)

log "契约测试完成"
