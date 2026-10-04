#!/usr/bin/env bash
# G1-C2d 浏览器 E2E 运行脚本：起依赖栈 → 后端存在性/健康检查 → 严格清空专用 Redis DB → 预置账号 →
# 起后端（专用 Redis DB）→ 起模拟器 → 起 Vite → 跑 Playwright → 收尾。
#
# 用法（仓库根或 console 目录均可）：
#   ./things-link-console/scripts/run-e2e-tests.sh [--with-simulator]
#
# 选项：
#   --with-simulator   旅程 3/6/7 的真实 MQTT 上报与命令需要；不带时排除 @simulator。
#
# 环境变量（可选）：
#   CONTRACT_BASE_URL   后端基地址，默认 http://localhost:8080
#   E2E_BASE_URL        控制台基地址，默认 http://localhost:3006
#   E2E_LOCAL_LOG_DIR   普通本机验证的子进程日志目录；默认 things-link-console/logs
#   E2E_WEBAPP_PORT     匿名分享旅程独立宿主端口，默认3019；不接管已有进程
#   SKIP_BACKEND=1      复用已按本脚本同一环境启动的后端（专用 Redis DB 15）
#   E2E_SPEC            可选单文件（如 journey-7.spec.ts）；用于按验证方针先跑单旅程 L3。
#                       选择 ota-campaign-lifecycle.spec.ts 时额外启用D-154路径①：后端启动前
#                       预置固定(tenant,project,deviceType)夹具、注入受控类型基线与信任锚，
#                       并启动ADR0139受控signer桩；其他旅程路径不变。
#   E2E_MATRIX_SPECS    正式证据工具注入的冒号分隔冻结矩阵；普通调用不得自行设置。
# 正式 G2 资格经 e2e-evidence.py 领取冻结 JAR 和独立证据目录，不能用普通模式代替。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONSOLE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$CONSOLE_DIR/.." && pwd)"
DEPLOY_DIR="$REPO_ROOT/deploy"
BACKEND_DIR="$REPO_ROOT/things-link"

# G2-A4a：任何环境变更之前检查候选，普通调用不得与资格批次并发。
if [[ -n "${E2E_BATCH_DIR:-}" ]]; then
  [[ -n "${E2E_RUN_DIR:-}" && "${SKIP_BACKEND:-0}" != "1" ]] || exit 1
  python3 "${SCRIPT_DIR}/e2e-evidence.py" check --batch "${E2E_BATCH_DIR}" --run-dir "${E2E_RUN_DIR}"
elif [[ -n "${E2E_RUN_DIR:-}" || -e "${CONSOLE_DIR}/.e2e-evidence/runtime.lock" ]]; then
  printf '[e2e] 拒绝缺少候选的证据目录或与正式资格并发运行\n' >&2
  exit 1
fi

WITH_SIMULATOR=false
if [[ "${1:-}" == "--with-simulator" ]]; then WITH_SIMULATOR=true; fi
E2E_SPEC="${E2E_SPEC:-}"
E2E_MATRIX_SPECS="${E2E_MATRIX_SPECS:-}"
if [[ -n "${E2E_MATRIX_SPECS}" && -z "${E2E_BATCH_DIR:-}" ]]; then
  printf '[e2e] 错误：E2E_MATRIX_SPECS 仅允许正式证据工具注入\n' >&2
  exit 1
fi
if [[ -n "${E2E_SPEC}" && -n "${E2E_MATRIX_SPECS}" ]]; then
  printf '[e2e] 错误：单场与组合选择器不能同时设置\n' >&2
  exit 1
fi
# 选择器只允许 testDir 内相对文件名；拒绝上跳与绝对路径，避免测试入口越出 e2e 目录。
if [[ -n "${E2E_SPEC}" ]] \
  && { [[ ! "${E2E_SPEC}" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] \
    || [[ "${E2E_SPEC}" == *".."* ]] || [[ "${E2E_SPEC}" == /* ]]; }; then
  printf '[e2e] 错误：E2E_SPEC 只能是 e2e 目录内的相对测试文件：%s\n' "${E2E_SPEC}" >&2
  exit 1
fi
IFS=':' read -r -a E2E_MATRIX_FILES <<< "${E2E_MATRIX_SPECS}"
if [[ -n "${E2E_MATRIX_SPECS}" ]]; then
  [[ "${#E2E_MATRIX_FILES[@]}" -eq 6 ]] || exit 1
  for matrix_spec in "${E2E_MATRIX_FILES[@]}"; do
    if [[ ! "${matrix_spec}" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] \
      || [[ "${matrix_spec}" == *".."* ]] || [[ "${matrix_spec}" == /* ]]; then
      printf '[e2e] 错误：组合选择器包含非法文件：%s\n' "${matrix_spec}" >&2
      exit 1
    fi
  done
fi

# S13-4d-2b-2b-13 / D-154路径①：受控类型基线是后端**启动时**快照的精确三元组索引，
# 而浏览器的生命周期旅程必须在启动前就把它注入。因此仅当显式选择该单文件旅程时，
# 脚本预置固定的 (tenant, project, deviceType) 夹具、注入对应的基线/信任锚配置，
# 并启动一个实现ADR0139的受控signer桩；其他所有旅程的路径完全不变。
OTA_LIFECYCLE=false
if [[ "${E2E_SPEC}" == "ota-campaign-lifecycle.spec.ts" ]]; then
  OTA_LIFECYCLE=true
  if [[ "${SKIP_BACKEND:-0}" == "1" ]]; then
    printf '[e2e] 错误：生命周期旅程必须由本脚本注入启动配置，不支持 SKIP_BACKEND=1\n' >&2
    exit 1
  fi
  if ! command -v node >/dev/null 2>&1; then
    printf '[e2e] 错误：生命周期旅程需要 node 运行受控signer桩\n' >&2
    exit 1
  fi
fi
CONTROLLED_SMTP=false
unset E2E_SMTP_PRIVATE_IDENTITY E2E_INVITATION_RECIPIENT
if [[ "${E2E_SPEC}" == "alarm-smtp-journey.spec.ts" || "${E2E_SPEC}" == "rule-smtp-journey.spec.ts" || "${E2E_SPEC}" == "property-automation-smtp.spec.ts" || "${E2E_SPEC}" == "time-once-smtp.spec.ts" || "${E2E_SPEC}" == "time-cron-smtp.spec.ts" || "${E2E_SPEC}" == "invitation-registration-smtp.spec.ts" ]]; then
  CONTROLLED_SMTP=true
  if [[ "${SKIP_BACKEND:-0}" == "1" || "${WITH_SIMULATOR}" != "true" ]]; then
    printf '[e2e] 受控SMTP旅程需要本脚本启动后端及 --with-simulator\n' >&2
    exit 1
  fi
fi
OTA_FIXTURE_DIR="$CONSOLE_DIR/e2e/fixtures"

BASE_URL="${CONTRACT_BASE_URL:-http://localhost:8080}"
E2E_URL="${E2E_BASE_URL:-http://localhost:3006}"
BASE_PORT="$(printf '%s' "${BASE_URL}" | sed -nE 's#^[a-z]+://[^:/]+:([0-9]+)(/.*)?$#\1#p')"
if [[ -z "${BASE_PORT}" ]]; then
  printf '[e2e] 错误：CONTRACT_BASE_URL 缺少显式端口（应为 http://host:端口 形式）：%s\n' \
    "${BASE_URL}" >&2
  exit 1
fi
REDIS_TEST_DB=15
PASSWORD="contract-pass-123"
TS="${E2E_FIXTURE_ID:-$(date +%s)}"
[[ "${TS}" =~ ^[A-Za-z0-9-]+$ ]] || exit 1
OWNER_EMAIL="e2e-owner-${TS}@example.com"
MEMBER_EMAIL="e2e-member-${TS}@example.com"
# S14-6d：套餐面板旅程专用的**默认供给**账号（带 FREE 订阅与 PLAN_R1_FREE 绑定）。
PLAN_EMAIL="e2e-plan-${TS}@example.com"
PLAN_PROJECT="套餐面板项目"
SHC_OPERATOR_EMAIL="e2e-shc-operator-${TS}@example.com"
SHC_REVIEWER_ONE_EMAIL="e2e-shc-reviewer-one-${TS}@example.com"
SHC_REVIEWER_TWO_EMAIL="e2e-shc-reviewer-two-${TS}@example.com"
BACKEND_PID=""
SIMULATOR_PID=""
VITE_PID=""
WEBAPP_PID=""
SIGNER_PID=""
SMTP_PID=""
SMTP_PORT=""
TEST_TLS_DIRECTORY=""
smtp_env_args=()
smtp_java_args=()
# Identity messages are retained only for this exact private Dind journey.
unset E2E_SMTP_PRIVATE_IDENTITY E2E_INVITATION_RECIPIENT
if [[ "${E2E_SPEC}" == "invitation-registration-smtp.spec.ts" ]]; then
  [[ -f /.thingslink-local-ci ]] || { printf '%s\n' "邀请注册邮件夹具只允许隔离 Dind 选场" >&2; exit 1; }
  export E2E_SMTP_PRIVATE_IDENTITY=1
  E2E_INVITATION_RECIPIENT="$(python3 -c 'import uuid; print("invited-"+uuid.uuid4().hex+"@example.test")')"
  export E2E_INVITATION_RECIPIENT E2E_SPEC
fi
# End private identity selection.
# Populated association fixture: fresh stack only; default runs retain no authority.
unset E2E_ASSOCIATION_FIXTURE
if [[ "${E2E_SPEC}" == "device-associations-populated.spec.ts" ]]; then
  [[ -f /.thingslink-local-ci && "${SKIP_BACKEND:-0}" != "1" && "${WITH_SIMULATOR}" == "true" ]] || {
    printf '[e2e] 非空关联夹具只允许完整隔离 Dind 选场\n' >&2; exit 1;
  }
  smtp_env_args+=("THINGS_LINK_AUTOMATION_PROPERTY_ENABLED=true")
fi
# End populated association selection.
OTA_SIGNING_TOKEN=""
OTA_SIGNING_ENDPOINT=""
OTA_SIGNING_PORT=""
OTA_SIGNER_PORT_FILE=""
OTA_LIFECYCLE_FIXTURE_PROVISIONED=false
WEBAPP_PORT="${E2E_WEBAPP_PORT:-3019}"
DEFAULT_E2E_LOG_DIR="${E2E_LOCAL_LOG_DIR:-${CONSOLE_DIR}/logs}"
mkdir -p "${DEFAULT_E2E_LOG_DIR}"
BACKEND_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-backend.log"
SIMULATOR_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-simulator.log"
SIGNER_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-signer.log"
VITE_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-vite.log"
WEBAPP_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-webapp.log"
DEPENDENCIES_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-dependencies.log"
STACK_LOG="${DEFAULT_E2E_LOG_DIR}/target-e2e-stack.log"
if [[ -n "${E2E_RUN_DIR:-}" ]]; then
  BACKEND_LOG="${E2E_RUN_DIR}/backend.log"
  SIMULATOR_LOG="${E2E_RUN_DIR}/simulator.log"
  SIGNER_LOG="${E2E_RUN_DIR}/signer.log"
  VITE_LOG="${E2E_RUN_DIR}/vite.log"
  WEBAPP_LOG="${E2E_RUN_DIR}/webapp.log"
  DEPENDENCIES_LOG="${E2E_RUN_DIR}/dependencies.log"
  STACK_LOG="${E2E_RUN_DIR}/stack.log"
fi
PROBE_TOPIC=""
REDIS_TOUCHED=false
NORMAL_LEASE=false

# EMQX 下行发布测试凭据（每轮创建、退出时删除；秘密只留在进程变量，不进日志/仓库/VITE_）。
# Dashboard 凭据从 deploy/.env 读取，早于任何可能提前退出的步骤 source，cleanup 也要用。
if [[ -f "${DEPLOY_DIR}/.env" ]]; then
  source <(grep -E '^EMQX_DASHBOARD_USER=|^EMQX_DASHBOARD_PASSWORD=|^EMQX_DASHBOARD_PORT=|^EMQX_MQTT_PORT=' "${DEPLOY_DIR}/.env")
fi
EMQX_DASHBOARD_USER="${EMQX_DASHBOARD_USER:-admin}"
EMQX_DASHBOARD_PASSWORD="${EMQX_DASHBOARD_PASSWORD:-thingslink123}"
EMQX_DASHBOARD_PORT="${EMQX_DASHBOARD_PORT:-18083}"
EMQX_MQTT_PORT="${EMQX_MQTT_PORT:-1883}"
EMQX_API_BASE_URL="http://localhost:${EMQX_DASHBOARD_PORT}"
EMQX_KEY_NAME="e2e-downlink-${TS}"
EMQX_API_KEY="${EMQX_API_KEY:-}"
EMQX_API_SECRET="${EMQX_API_SECRET:-}"
EMQX_KEY_OWNED=false
EMQX_AUTH_CALLBACK_REPOINTED=false
EMQX_AUTHZ_CALLBACK_REPOINTED=false
EMQX_LIFECYCLE_CALLBACK_REPOINTED=false

# E2E 启动的胖 JAR 不能从 Docker Compose 自动继承 MinIO 凭据。只读取三个固定键的原始值，
# 不 source 整份 .env，避免密码中的 shell 字符被展开；缺文件时使用与 .env.example 相同的本机基线。
MINIO_ROOT_USER="$(grep -m1 '^MINIO_ROOT_USER=' "${DEPLOY_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
MINIO_ROOT_PASSWORD="$(grep -m1 '^MINIO_ROOT_PASSWORD=' "${DEPLOY_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
MINIO_PORT="$(grep -m1 '^MINIO_PORT=' "${DEPLOY_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
MINIO_ROOT_USER="${MINIO_ROOT_USER:-thingslink}"
MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-thingslink123}"
MINIO_PORT="${MINIO_PORT:-9000}"
if [[ ! "${MINIO_PORT}" =~ ^[0-9]+$ ]] \
  || (( 10#${MINIO_PORT} < 1 || 10#${MINIO_PORT} > 65535 )); then
  printf '[e2e] 错误：deploy/.env 的 MINIO_PORT 不是有效端口\n' >&2
  exit 1
fi
MINIO_STORAGE_ENDPOINT="http://127.0.0.1:${MINIO_PORT}"
# ADR0119/D-153：平台刻意不创建也不借用对象存储桶。E2E 的固件桶必须由部署侧
# （deploy/docker-compose.yml 的 minio-init）准备；这里显式指定，绝不静默回退。
OTA_STORAGE_BUCKET="${THINGS_LINK_OTA_STORAGE_BUCKET:-firmware}"

log() { printf '\033[36m[e2e]\033[0m %s\n' "$*"; }

new_uuid_v7() {
  python3 - <<'PY'
import secrets
import time
import uuid

value = ((time.time_ns() // 1_000_000) & ((1 << 48) - 1)) << 80
value |= 0x7 << 76 | secrets.randbits(12) << 64 | 0x2 << 62 | secrets.randbits(62)
print(uuid.UUID(int=value))
PY
}

# 只在包含自部署浏览器用例的隔离栈中授予真实平台权限；不修改生产授权逻辑。
provision_shc_browser_fixture() {
  for account in operator reviewer-one reviewer-two; do
    local email display
    case "${account}" in
      operator) email="${SHC_OPERATOR_EMAIL}"; display='E2E-SHC-Operator' ;;
      reviewer-one) email="${SHC_REVIEWER_ONE_EMAIL}"; display='E2E-SHC-Reviewer-One' ;;
      reviewer-two) email="${SHC_REVIEWER_TWO_EMAIL}"; display='E2E-SHC-Reviewer-Two' ;;
    esac
    "${DEPLOY_DIR}/scripts/add-console-account.sh" --email "${email}" --password "${PASSWORD}" \
      --display-name "${display}" --project-name '自部署浏览器验收项目' >/dev/null
  done
  ota_pg_credentials
  local commercial_op reviewer_one_op reviewer_two_op
  commercial_op="$(new_uuid_v7)"
  reviewer_one_op="$(new_uuid_v7)"
  reviewer_two_op="$(new_uuid_v7)"
  # 隔离库的超级用户管理连接满足函数的管理员角色检查；函数仍按 session_user 审计。
  docker exec -i tc-postgres psql -U "${PGUSER}" -d "${PGDB}" --no-psqlrc -q \
    -v ON_ERROR_STOP=1 \
    -v operator_email="${SHC_OPERATOR_EMAIL}" \
    -v reviewer_one_email="${SHC_REVIEWER_ONE_EMAIL}" \
    -v reviewer_two_email="${SHC_REVIEWER_TWO_EMAIL}" \
    -v commercial_op="${commercial_op}" -v reviewer_one_op="${reviewer_one_op}" \
    -v reviewer_two_op="${reviewer_two_op}" >/dev/null <<'SQL'
BEGIN;
SELECT result_version FROM commercial_operator_manage(
  :'commercial_op'::uuid, (SELECT id FROM sys_account WHERE email = :'operator_email'), true,
  '隔离浏览器验收运营账号');
SELECT result_version FROM shc_reviewer_manage(
  :'reviewer_one_op'::uuid, (SELECT id FROM sys_account WHERE email = :'reviewer_one_email'), true,
  '隔离浏览器验收审核账号');
SELECT result_version FROM shc_reviewer_manage(
  :'reviewer_two_op'::uuid, (SELECT id FROM sys_account WHERE email = :'reviewer_two_email'), true,
  '隔离浏览器验收审核账号');
COMMIT;
SQL
}

# 生命周期旅程的固定三元组夹具（S13-4d-2b-2b-13）。夹具SQL只直写测试身份骨架，
# 不改变生产语义；后端仍按启动配置的精确三元组索引基线。
ota_pg_credentials() {
  if [[ -f "${DEPLOY_DIR}/.env" ]]; then
    source <(grep -E '^POSTGRES_USER=|^POSTGRES_DB=' "${DEPLOY_DIR}/.env")
  fi
  PGUSER="${POSTGRES_USER:-thingslink}"
  PGDB="${POSTGRES_DB:-thingslink}"
}

ota_schema_present() {
  local present
  ota_pg_credentials
  present="$(docker exec tc-postgres psql -U "${PGUSER}" -d "${PGDB}" --no-psqlrc -t -A \
    -c "SELECT to_regclass('public.sys_tenant') IS NOT NULL OR to_regclass('public.dev_type') IS NOT NULL;" \
    2>/dev/null | tr -d '[:space:]')"
  [[ "${present}" == "t" ]]
}

provision_ota_lifecycle_fixture() {
  ota_pg_credentials
  log "预置生命周期固定夹具（TEST-ONLY SQL，固定三元组 $OTA_FIXTURE_DIR/ota-lifecycle-fixture.json）"
  docker exec -i tc-postgres psql -U "${PGUSER}" -d "${PGDB}" --no-psqlrc \
    -v ON_ERROR_STOP=1 -v password="${PASSWORD}" \
    <"${OTA_FIXTURE_DIR}/ota-lifecycle-fixture.sql" >/dev/null
  OTA_LIFECYCLE_FIXTURE_PROVISIONED=true
}

# 受控signer桩：ADR0139单POST合同，只对平台给定的signingInput字节做Ed25519签名。
start_ota_signer_stub() {
  OTA_SIGNER_PORT_FILE="$(mktemp -t tc-ota-signer-port)"
  OTA_SIGNING_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  node "${SCRIPT_DIR}/ota-signer-stub.mjs" --port 0 --token "${OTA_SIGNING_TOKEN}" \
    --keys "${OTA_FIXTURE_DIR}/ota-lifecycle-test-keys.json" --port-file "${OTA_SIGNER_PORT_FILE}" \
    >"${SIGNER_LOG}" 2>&1 &
  SIGNER_PID=$!
  for _ in $(seq 1 50); do
    [[ -s "${OTA_SIGNER_PORT_FILE}" ]] && break
    kill -0 "${SIGNER_PID}" 2>/dev/null || break
    sleep 0.2
  done
  OTA_SIGNING_PORT="$(tr -d '[:space:]' <"${OTA_SIGNER_PORT_FILE}" 2>/dev/null || true)"
  if [[ ! "${OTA_SIGNING_PORT}" =~ ^[0-9]+$ ]] || ! kill -0 "${SIGNER_PID}" 2>/dev/null; then
    log "错误：受控signer桩未就绪，日志见 ${SIGNER_LOG}"
    exit 1
  fi
  OTA_SIGNING_ENDPOINT="http://127.0.0.1:${OTA_SIGNING_PORT}/sign"
  log "受控signer桩已监听 127.0.0.1:${OTA_SIGNING_PORT}（秘密不入日志）"
}

# deploy/Makefile 的 up 目标只封装 Compose 与三类幂等初始化。Windows Git Bash 通常没有 make，
# 此时执行同一组命令；不安装宿主工具，也不降低任何依赖健康或初始化门禁。
start_dependency_stack() {
  if command -v make >/dev/null 2>&1; then
    (cd "${DEPLOY_DIR}" && make up)
    return
  fi

  log "未检测到 make，使用等价 Docker Compose 初始化路径"
  (
    cd "${DEPLOY_DIR}"
    if [[ ! -f .env ]]; then cp .env.example .env; fi
    local compose=(docker compose --env-file .env)
    local pg_user pg_db sql_file
    "${compose[@]}" up -d --wait postgres redis redpanda emqx minio
    pg_user="$(grep '^POSTGRES_USER=' .env | cut -d= -f2-)"
    pg_db="$(grep '^POSTGRES_DB=' .env | cut -d= -f2-)"
    [[ -n "${pg_user}" && -n "${pg_db}" ]]
    for sql_file in ./postgres/init/*.sql; do
      "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 \
        -U "${pg_user}" -d "${pg_db}" <"${sql_file}" >/dev/null
    done
    "${compose[@]}" run --rm redpanda-init
    "${compose[@]}" run --rm minio-init
  )
}

# 存储资格不只看凭据：桶必须存在，且版本化必须为 ENABLED，否则
# PUT /uploads/{id}/content 会在旅程中变成 503/70007。复用与 minio-init 相同的 minio/mc
# 容器回读确认，缺任一项立即失败并指明桶名，不静默继续到旅程。
verify_ota_storage_bucket() {
  local versioning
  if ! versioning="$(cd "${DEPLOY_DIR}" && docker compose --env-file .env run --rm --no-deps \
    -e "MINIO_ROOT_USER=${MINIO_ROOT_USER}" -e "MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}" \
    --entrypoint sh minio-init -c "
      mc alias set tc http://minio:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null 2>&1 || exit 2
      mc ls tc/${OTA_STORAGE_BUCKET} >/dev/null 2>&1 || exit 3
      mc version info tc/${OTA_STORAGE_BUCKET} 2>/dev/null
    " 2>/dev/null)"; then
    log "错误：固件桶 ${OTA_STORAGE_BUCKET} 未就绪（不存在或不可读）；存储资格要求桶存在，请先执行 make up / minio-init"
    exit 1
  fi
  if [[ "${versioning}" != *"versioning is enabled"* ]]; then
    log "错误：固件桶 ${OTA_STORAGE_BUCKET} 版本化不是 ENABLED（mc version info 实测：${versioning:-无输出}）"
    exit 1
  fi
  log "固件桶 ${OTA_STORAGE_BUCKET} 存在且版本化 ENABLED"
}

# 健康端点只证明 Web 进程可用；ADR 0046 还要求固定 ingress owner 已完成 CONNACK+SUBACK。
# 页面等待不能替代该前置资格，否则服务身份漏配会晚到旅程 3 才表现为上报状态 `{}`。
wait_for_durable_ingress_ready() {
  local metrics
  for _ in $(seq 1 60); do
    metrics="$(curl -fsS --max-time 3 "${BASE_URL}/actuator/prometheus" 2>/dev/null || true)"
    # pipefail 下 grep -q 命中后会提前关闭管道，大型 Prometheus 响应会让上游 printf 因 SIGPIPE 返回非零，
    # 从而把已就绪误判为未就绪；here-string 让 grep 完整拥有输入，不再依赖管道退出时序。
    if grep -Eq '^thingslink_ingress_handoff_connected\{[^}]*\} 1(\.0+)?$' <<< "${metrics}"; then
      log "Broker durable ingress owner 已完成连接与订阅"
      return 0
    fi
    sleep 1
  done
  log "错误：Broker durable ingress owner 未在 60 秒内就绪"
  return 1
}

# 把 timeout 放在 Redpanda 容器内执行，兼容没有 GNU timeout 的 macOS 宿主机；任何 rpk 网络调用
# 都必须有硬上限，否则 GitHub 只能在 40 分钟 job timeout 时粗暴取消，EXIT 诊断也来不及完成。
redpanda_rpk() {
  local max_seconds="$1"
  shift
  (cd "${DEPLOY_DIR}" && docker compose --env-file .env exec -T redpanda \
    timeout "${max_seconds}" rpk "$@")
}

# Runner 失败必须留下依赖事实，不能只上传后端看到的二手异常。这里只采集状态、磁盘、
# Redpanda 集群/目标主题和 broker 日志，不输出 compose config 或环境变量，避免把秘密带进产物。
capture_dependency_diagnostics() {
  {
    printf '%s\n' '=== timestamp ==='
    date -u '+%Y-%m-%dT%H:%M:%SZ' || true
    printf '%s\n' '=== filesystem ==='
    df -h || true
    printf '%s\n' '=== docker disk ==='
    docker system df || true
    printf '%s\n' '=== compose ps ==='
    (cd "${DEPLOY_DIR}" && docker compose --env-file .env ps -a) || true
    printf '%s\n' '=== redpanda container state ==='
    docker inspect --format '{{json .State}}' tc-redpanda || true
    printf '%s\n' '=== redpanda cluster health ==='
    redpanda_rpk 5 cluster health || true
    printf '%s\n' '=== redpanda disk guard ==='
    redpanda_rpk 5 cluster config get storage_min_free_bytes || true
    MSYS_NO_PATHCONV=1 docker exec tc-redpanda timeout 5 df -PB1 /var/lib/redpanda/data || true
    printf '%s\n' '=== tc.device.uplink.raw partitions ==='
    redpanda_rpk 5 topic describe tc.device.uplink.raw -p || true
    printf '%s\n' '=== redpanda logs (tail 500) ==='
    (cd "${DEPLOY_DIR}" && docker compose --env-file .env logs --no-color --tail=500 redpanda) || true
  } >"${DEPENDENCIES_LOG}" 2>&1
}

# 隔离端口运行时，EMQX 容器不能继续把认证/授权回调发给默认 8080。更新前只接受仓库基线的
# 8080 配置，避免覆盖开发者自定义回调；cleanup 无论在哪一步失败都把已改动的部分恢复并回读确认。
emqx_dashboard_token() {
  curl -fsS --max-time 3 -X POST "${EMQX_API_BASE_URL}/api/v5/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${EMQX_DASHBOARD_USER}\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" \
    2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null
}

emqx_callbacks_match_port() {
  # Q4 与 candidate 前资格共用完整 callback + rule→action→connector 合同；URL 单点不能证明送达。
  python3 "${DEPLOY_DIR}/scripts/emqx-environment-qualification.py" callback-route \
    --callback-port "$1" --api-base "${EMQX_API_BASE_URL}"
}

emqx_callback_payload() {
  local kind="$1" port="$2"
  python3 -c '
import json, sys
kind, port = sys.argv[1:]
callback = "dev-only-broker-callback-secret-do-not-use-in-production"
payload = {
    "method": "post", "url": f"http://host.docker.internal:{port}/api/v1/emqx/{kind}",
    "headers": {"content-type": "application/json", "x-broker-callback-token": callback},
    "connect_timeout": "2s", "request_timeout": "3s", "pool_size": 16, "enable": True,
}
if kind == "auth":
    payload.update({"mechanism": "password_based", "backend": "http",
                    "body": {"username": "${username}", "password": "${password}", "clientid": "${clientid}"}})
else:
    payload.update({"type": "http",
                    "body": {"username": "${username}", "clientid": "${clientid}",
                             "topic": "${topic}", "access": "${action}",
                             **{key: "${client_attrs." + key + "}" for key in (
                                 "tc_auth_tenant_id", "tc_auth_project_id", "tc_auth_device_id",
                                 "tc_auth_credential_version", "tc_auth_config_version",
                                 "tc_auth_connection_id")}}})
print(json.dumps(payload, separators=(",", ":")))
' "${kind}" "${port}"
}

emqx_lifecycle_payload() {
  local port="$1"
  python3 -c '
import json, sys
payload = {
    "url": f"http://host.docker.internal:{sys.argv[1]}",
    "headers": {"content-type": "application/json",
                "X-Broker-Callback-Token": "dev-only-broker-callback-secret-do-not-use-in-production"},
    "connect_timeout": "2s", "pool_size": 16, "enable_pipelining": 1, "enable": True,
}
print(json.dumps(payload, separators=(",", ":")))
' "${port}"
}

emqx_set_callback_port() {
  local port="$1" token auth_payload authz_payload lifecycle_payload
  token="$(emqx_dashboard_token)" || return 1
  [[ -n "${token}" ]] || return 1
  auth_payload="$(emqx_callback_payload auth "${port}")" || return 1
  authz_payload="$(emqx_callback_payload acl "${port}")" || return 1
  lifecycle_payload="$(emqx_lifecycle_payload "${port}")" || return 1
  curl -fsS --max-time 3 -o /dev/null -X PUT \
    "${EMQX_API_BASE_URL}/api/v5/authentication/password_based%3Ahttp" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' -d "${auth_payload}" || return 1
  EMQX_AUTH_CALLBACK_REPOINTED=true
  curl -fsS --max-time 3 -o /dev/null -X PUT \
    "${EMQX_API_BASE_URL}/api/v5/authorization/sources/http" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' -d "${authz_payload}" || return 1
  EMQX_AUTHZ_CALLBACK_REPOINTED=true
  curl -fsS --max-time 3 -o /dev/null -X PUT \
    "${EMQX_API_BASE_URL}/api/v5/connectors/http:tc_device_lifecycle" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' -d "${lifecycle_payload}" || return 1
  EMQX_LIFECYCLE_CALLBACK_REPOINTED=true
  emqx_callbacks_match_port "${port}"
}

# Only called after TLS restore succeeds. Keep the sole receipt if archival fails.
archive_restored_test_tls_directory() {
  local directory="$1" destination="$2"
  if [[ -f "${directory}/smtp-receipts.json" ]]; then
    cp "${directory}/smtp-receipts.json" "${destination}/controlled-smtp-receipts.json" || return 1
  fi
  rm -rf -- "${directory}"
}

cleanup() {
  local rc=$?
  # 单项失败不能跳过其他清理和回执；保留最初 rc。
  set +e
  local cleanup_failed=0
  local label port pid token delete_status remaining
  export G2_CLEANUP_PROCESSES=PASS G2_CLEANUP_PORTS=PASS G2_CLEANUP_CALLBACKS=PASS
  export G2_CLEANUP_APIKEY=PASS G2_CLEANUP_PROBETOPIC=PASS G2_CLEANUP_REDIS=PASS
  export G2_STARTED_BACKEND=false G2_STARTED_SIMULATOR=false G2_STARTED_VITE=false
  [[ -n "${BACKEND_PID}" ]] && export G2_STARTED_BACKEND=true
  [[ -n "${SIMULATOR_PID}" ]] && export G2_STARTED_SIMULATOR=true
  [[ -n "${VITE_PID}" ]] && export G2_STARTED_VITE=true
  export G2_FIXTURE_NAMESPACE="${TS}"
  if [[ "${rc}" -ne 0 ]]; then
    capture_dependency_diagnostics
  fi
  # 1. 先停止使用临时凭据的进程，再删除 Key，避免 teardown 与在途下行请求竞争。
  for pid in "${WEBAPP_PID}" "${VITE_PID}" "${SIMULATOR_PID}" "${SIGNER_PID}" "${BACKEND_PID}" "${SMTP_PID}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  # 2. 等进程回收；超时才强制终止，并用 wait 回收子进程，不能只发信号就宣称 cleanup 完成。
  for pid in "${WEBAPP_PID}" "${VITE_PID}" "${SIMULATOR_PID}" "${SIGNER_PID}" "${BACKEND_PID}" "${SMTP_PID}"; do
    if [[ -n "${pid}" ]]; then
      for _ in $(seq 1 50); do kill -0 "${pid}" 2>/dev/null || break; sleep 0.2; done
      if kill -0 "${pid}" 2>/dev/null; then
        kill -9 "${pid}" 2>/dev/null || true
      fi
      wait "${pid}" 2>/dev/null || true
      if kill -0 "${pid}" 2>/dev/null; then
        G2_CLEANUP_PROCESSES=FAIL
        cleanup_failed=1
      fi
    fi
  done
  # 进程 PID 消失仍不足以证明没有遗留子进程；只复核本脚本实际启动过的三个监听端口。
  for label in webapp vite simulator signer backend smtp; do
    case "${label}" in
      webapp) pid="${WEBAPP_PID}"; port="${WEBAPP_PORT}" ;;
      vite) pid="${VITE_PID}"; port="${E2E_PORT:-}" ;;
      simulator) pid="${SIMULATOR_PID}"; port="8090" ;;
      signer) pid="${SIGNER_PID}"; port="${OTA_SIGNING_PORT:-}" ;;
      backend) pid="${BACKEND_PID}"; port="${BASE_PORT:-}" ;;
      smtp) pid="${SMTP_PID}"; port="${SMTP_PORT:-}" ;;
    esac
    if [[ -n "${pid}" && -n "${port}" ]] && (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
      exec 3>&- 3<&-
      log "错误：cleanup 后 ${label} 端口 ${port} 仍被占用"
      cleanup_failed=1
      G2_CLEANUP_PORTS=FAIL
    fi
  done
  if [[ -n "${OTA_SIGNER_PORT_FILE}" ]]; then rm -f "${OTA_SIGNER_PORT_FILE}" || cleanup_failed=1; fi
  # TLS快照包含当时回调；先恢复TLS并回读，再由原入口恢复回调。
  if [[ -n "${TEST_TLS_DIRECTORY}" ]]; then
    if EMQX_DASHBOARD_USER="${EMQX_DASHBOARD_USER}" EMQX_DASHBOARD_PASSWORD="${EMQX_DASHBOARD_PASSWORD}" \
      python3 "${SCRIPT_DIR}/emqx-test-tls.py" restore --directory "${TEST_TLS_DIRECTORY}"; then
      if ! archive_restored_test_tls_directory "${TEST_TLS_DIRECTORY}" "${DEFAULT_E2E_LOG_DIR}"; then
        log "错误：测试TLS材料清理或收件回执归档失败，保留尚未删除的私有材料"
        cleanup_failed=1
      fi
    else
      log "错误：测试TLS恢复未确认，保留私有恢复状态"
      cleanup_failed=1
    fi
  fi
  # 3. 隔离端口的认证/授权回调属于共享 EMQX 的临时状态，必须先恢复默认端口并回读确认。
  if ${EMQX_AUTH_CALLBACK_REPOINTED} || ${EMQX_AUTHZ_CALLBACK_REPOINTED} \
    || ${EMQX_LIFECYCLE_CALLBACK_REPOINTED}; then
    if emqx_set_callback_port 8080 && emqx_callbacks_match_port 8080; then
      EMQX_AUTH_CALLBACK_REPOINTED=false
      EMQX_AUTHZ_CALLBACK_REPOINTED=false
      EMQX_LIFECYCLE_CALLBACK_REPOINTED=false
      log "EMQX 认证/授权/连接事件回调已恢复至 8080"
    else
      log "错误：cleanup 未能确认 EMQX 认证/授权/连接事件回调恢复至 8080"
      cleanup_failed=1
      G2_CLEANUP_CALLBACKS=FAIL
    fi
  fi
  # 4. 只删除本脚本创建的 Key；SKIP_BACKEND 使用的外部凭据不归本脚本所有，绝不能误删。
  if ${EMQX_KEY_OWNED}; then
    token="$(curl -s --max-time 3 -X POST "${EMQX_API_BASE_URL}/api/v5/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"${EMQX_DASHBOARD_USER}\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
    if [[ -z "${token}" ]]; then
      log "错误：cleanup 无法登录 EMQX Dashboard，临时 API Key ${EMQX_KEY_NAME} 未确认删除"
      cleanup_failed=1
      G2_CLEANUP_APIKEY=FAIL
    else
      delete_status="$(curl -s --max-time 3 -o /dev/null -w '%{http_code}' -X DELETE \
        "${EMQX_API_BASE_URL}/api/v5/api_key/${EMQX_KEY_NAME}" -H "Authorization: Bearer ${token}" || true)"
      remaining="$(curl -fsS --max-time 3 "${EMQX_API_BASE_URL}/api/v5/api_key" \
        -H "Authorization: Bearer ${token}" | python3 -c \
        'import json,sys; d=json.load(sys.stdin); rows=d if isinstance(d,list) else d["data"]; assert isinstance(rows,list); print(sum(x.get("name")==sys.argv[1] for x in rows))' \
        "${EMQX_KEY_NAME}" 2>/dev/null)"
      if [[ ( "${delete_status}" != "204" && "${delete_status}" != "404" ) || "${remaining}" != "0" ]]; then
        log "错误：cleanup 删除临时 EMQX API Key 失败（HTTP ${delete_status:-000}）"
        cleanup_failed=1
        G2_CLEANUP_APIKEY=FAIL
      fi
    fi
  fi
  if [[ -n "${PROBE_TOPIC}" ]] && ! delete_probe_topic; then
    G2_CLEANUP_PROBETOPIC=FAIL
    cleanup_failed=1
  fi
  if ${REDIS_TOUCHED} && ! (flush_test_redis_db); then
    G2_CLEANUP_REDIS=FAIL
    cleanup_failed=1
  fi
  if [[ -n "${E2E_RUN_DIR:-}" ]]; then
    if ! python3 "${SCRIPT_DIR}/e2e-evidence.py" cleanup --batch "${E2E_BATCH_DIR}" \
      --run-dir "${E2E_RUN_DIR}" --exit-code "${rc}"; then cleanup_failed=1; fi
  fi
  if ${NORMAL_LEASE} && [[ "$(cat "${CONSOLE_DIR}/.e2e-evidence/runtime.lock")" == "normal:$$" ]]; then
    rm -- "${CONSOLE_DIR}/.e2e-evidence/runtime.lock" || cleanup_failed=1
  fi
  if [[ "${rc}" -eq 0 && "${cleanup_failed}" -ne 0 ]]; then rc=1; fi
  exit "${rc}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# 普通入口也原子领取同一个资源锁，防止它先启动后与正式资格争用 DB 15。
if [[ -z "${E2E_BATCH_DIR:-}" ]]; then
  mkdir -p "${CONSOLE_DIR}/.e2e-evidence"
  if (set -o noclobber; printf 'normal:%s\n' "$$" >"${CONSOLE_DIR}/.e2e-evidence/runtime.lock") 2>/dev/null; then
    NORMAL_LEASE=true
  else
    log "错误：另一 E2E/资格进程已持有运行锁"
    exit 1
  fi
else
  # 外层有界终止使用这个 MSYS PID；不可把 Windows PID 当作 Bash PID 杀到其他进程。
  (set -o noclobber; printf '%s\n' "$$" >"${E2E_RUN_DIR}/runner.pid")
fi

# 1. 端口占用预检（在 make up / 清 Redis / 后端 / 预置账号**之前**）：
#    若 E2E 端口已被占用（例如残留的 `pnpm dev`），readiness curl 会命中旧 Vite、
#    Playwright 会打到旧应用。用 bash 内建 /dev/tcp 探测，不依赖 nc/lsof。
# 严格解析端口：E2E_BASE_URL 必须显式带端口（如 http://host:3006），
# 缺失时 sed 会把整个 URL 留在 E2E_PORT 里导致误判，这里直接拒绝而非回退默认。
E2E_PORT="$(printf '%s' "${E2E_URL}" | sed -nE 's#^[a-z]+://[^:/]+:([0-9]+)(/.*)?$#\1#p')"
if [[ -z "${E2E_PORT}" ]]; then
  log "错误：E2E_BASE_URL 缺少显式端口（应为 http://host:端口 形式）：${E2E_URL}"
  exit 1
fi
if (exec 3<>"/dev/tcp/127.0.0.1/${E2E_PORT}") 2>/dev/null; then
  exec 3>&- 3<&-
  log "错误：${E2E_URL}（端口 ${E2E_PORT}）已被占用——可能是旧的 pnpm dev / 残留 Vite；请先停掉再重跑"
  exit 1
fi

# 在改变共享回调前拒绝其他将启动的端口被占用。
if [[ "${SKIP_BACKEND:-0}" != "1" ]] \
  && (exec 3<>"/dev/tcp/127.0.0.1/${BASE_PORT}") 2>/dev/null; then
  log "错误：后端端口 ${BASE_PORT} 已占用，拒绝进入资格"
  exit 1
fi
if ${WITH_SIMULATOR} && (exec 3<>"/dev/tcp/127.0.0.1/8090") 2>/dev/null; then
  log "错误：模拟器端口 8090 已占用，拒绝进入资格"
  exit 1
fi

# 2. 依赖栈
log "启动依赖栈（幂等）"
if start_dependency_stack >"${STACK_LOG}" 2>&1; then
  :
else
  rc=$?
  log "错误：依赖栈启动或初始化失败（退出码 ${rc}），日志见 ${STACK_LOG}"
  exit "${rc}"
fi
# 基础设施就绪后、后端启动前确认固件桶前置条件（ADR0119/D-153）。
verify_ota_storage_bucket

# S13-4d-2b-2b-13 / D-154路径①：生命周期旅程预置固定三元组夹具（后端启动前，若建表已完成），
# 注入与已提交根签名信任包一致的基线/信任锚配置，并启动ADR0139受控signer桩。
ota_env_args=()
if ${OTA_LIFECYCLE}; then
  if ota_schema_present; then
    provision_ota_lifecycle_fixture
  else
    log "业务表尚未由Flyway建立；固定夹具改在后端首次启动建表后立即写入，受控基线快照仍按启动前注入的固定三元组"
  fi
  start_ota_signer_stub
  ota_env_args+=(
    THINGS_LINK_OTA_TYPE_BASELINES_JSON="$(cat "${OTA_FIXTURE_DIR}/ota-type-baseline.json")"
    THINGS_LINK_OTA_TRUST_ANCHORS_JSON="$(cat "${OTA_FIXTURE_DIR}/ota-trust-anchors.json")"
    THINGS_LINK_OTA_SIGNING_ENDPOINT="${OTA_SIGNING_ENDPOINT}"
    THINGS_LINK_OTA_SIGNING_TOKEN="${OTA_SIGNING_TOKEN}"
    THINGS_LINK_OTA_SIGNING_ALLOW_INSECURE_LOOPBACK=true
    THINGS_LINK_OTA_CAMPAIGN_RUNTIME_ENABLED=false
  )
fi

# make up 只证明 compose healthcheck 与初始化命令返回成功；主题创建后的 leader 选举和真实
# 生产/消费仍需独立门禁。临时主题不承载业务载荷，避免把探针消息送进 raw consumer 制造假错误。
delete_probe_topic() {
  local listing
  redpanda_rpk 10 topic delete "${PROBE_TOPIC}" >/dev/null 2>&1 || return 1
  listing="$(redpanda_rpk 10 topic list)" || return 1
  if awk -v topic="${PROBE_TOPIC}" '$1 == topic { found=1 } END { exit !found }' <<< "${listing}"; then
    return 1
  fi
  PROBE_TOPIC=""
}

verify_redpanda_ready() {
  local health partitions partition_count leader_count min_free available required probe_topic probe_value consumed rc
  log "验证 Redpanda 集群、目标主题 leader 与生产/消费往返"
  health=""
  partitions=""
  # make up 已完成 compose healthcheck，10 轮只为吸收主题刚创建后的短暂 leader 选举；
  # 每个查询最多 5 秒，最坏也会在约两分钟内失败，而不是拖到 CI 的 40 分钟总上限。
  for _ in $(seq 1 10); do
    health=""
    partitions=""
    if health="$(redpanda_rpk 5 cluster health 2>&1)" \
      && partitions="$(redpanda_rpk 5 topic describe tc.device.uplink.raw -p 2>&1)"; then
      :
    fi
    partition_count="$(printf '%s\n' "${partitions}" | awk 'NR > 1 && $1 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"
    leader_count="$(printf '%s\n' "${partitions}" | awk 'NR > 1 && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { count++ } END { print count + 0 }')"
    if printf '%s\n' "${health}" | grep -q 'Healthy:.*true' \
      && [[ "${partition_count}" == "12" && "${leader_count}" == "12" ]]; then
      break
    fi
    sleep 1
  done
  if ! printf '%s\n' "${health}" | grep -q 'Healthy:.*true' \
    || [[ "${partition_count:-0}" != "12" || "${leader_count:-0}" != "12" ]]; then
    log "错误：Redpanda 或 tc.device.uplink.raw 未就绪（partitions=${partition_count:-0}, leaders=${leader_count:-0}）"
    return 1
  fi
  log "Redpanda 集群健康，tc.device.uplink.raw 已有 12/12 leader"

  # Redpanda 默认在剩余空间低于 storage_min_free_bytes 时保持进程 healthy 但拒绝所有生产请求；
  # GitHub Runner 预装工具曾让根盘只余 3.8GiB，表现为业务 BrokerNotAvailable。这里读取数据卷所在
  # 文件系统的真实可用字节，并在 broker 阈值外再留 2GiB 给随后的 Maven 产物和测试日志。
  min_free="$(redpanda_rpk 5 cluster config get storage_min_free_bytes 2>/dev/null || true)"
  available="$(MSYS_NO_PATHCONV=1 docker exec tc-redpanda timeout 5 df -PB1 /var/lib/redpanda/data 2>/dev/null \
    | awk 'NR == 2 { print $4 }' || true)"
  if [[ ! "${min_free}" =~ ^[0-9]+$ || ! "${available}" =~ ^[0-9]+$ ]]; then
    log "错误：无法读取 Redpanda 磁盘保护阈值或数据卷可用空间"
    return 1
  fi
  required=$((min_free + 2147483648))
  if [[ "${available}" -lt "${required}" ]]; then
    log "错误：Redpanda 数据卷空间不足（available=${available}, broker_min=${min_free}, required_with_headroom=${required} bytes）"
    return 1
  fi
  log "Redpanda 磁盘余量通过（available=${available}, required=${required} bytes）"

  probe_topic="_tc_e2e_probe_${TS}"
  PROBE_TOPIC="${probe_topic}"
  probe_value="probe-${TS}"
  log "创建 Redpanda 临时探针主题 ${probe_topic}"
  if ! redpanda_rpk 15 topic create "${probe_topic}" -p 1 -r 1 >/dev/null; then
    log "错误：Redpanda 临时探针主题创建失败"
    return 1
  fi
  log "向 Redpanda 临时探针主题生产一条消息"
  if printf '%s\n' "${probe_value}" | redpanda_rpk 15 topic produce "${probe_topic}" >/dev/null; then
    :
  else
    rc=$?
    delete_probe_topic || true
    log "错误：Redpanda 临时探针生产失败（退出码 ${rc}）"
    return 1
  fi
  log "从 Redpanda 临时探针主题消费并核对消息"
  consumed="$(redpanda_rpk 15 topic consume "${probe_topic}" -n 1 -o start --format '%v' 2>/dev/null || true)"
  delete_probe_topic || { log "错误：临时探针主题未确认清理"; return 1; }
  if [[ "${consumed}" != "${probe_value}" ]]; then
    log "错误：Redpanda 临时探针消费结果不符"
    return 1
  fi
  log "Redpanda 就绪：raw 主题 12/12 leader，临时主题生产/消费往返通过"
}
verify_redpanda_ready

# bind-mounted base.hocon 内容变化不会让已存在的 EMQX 容器自动重启。ADR 0046 已删除 raw/command-reply
# 两个消息 Webhook；E2E 必须核验唯一 durable republish 及旧入口均不存在，不能等待一个源码已删除的 URL。
emqx_durable_ingress_contract() {
  local token rule legacy_raw_status legacy_command_status
  token="$(curl -fsS --max-time 3 -X POST "${EMQX_API_BASE_URL}/api/v5/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${EMQX_DASHBOARD_USER}\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" \
    2>/dev/null | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)" || return 1
  [[ -n "${token}" ]] || return 1
  rule="$(curl -fsS --max-time 3 "${EMQX_API_BASE_URL}/api/v5/rules/tc_durable_uplink" \
    -H "Authorization: Bearer ${token}" 2>/dev/null)" || return 1
  legacy_raw_status="$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' \
    "${EMQX_API_BASE_URL}/api/v5/rules/tc_raw_uplink" -H "Authorization: Bearer ${token}" 2>/dev/null)" || return 1
  legacy_command_status="$(curl -sS --max-time 3 -o /dev/null -w '%{http_code}' \
    "${EMQX_API_BASE_URL}/api/v5/bridges/webhook:tc_command_reply" \
    -H "Authorization: Bearer ${token}" 2>/dev/null)" || return 1
  printf '%s' "${rule}" | python3 "${SCRIPT_DIR}/verify-emqx-durable-ingress.py" \
    --legacy-raw-status "${legacy_raw_status}" --legacy-command-status "${legacy_command_status}" \
    >/dev/null 2>&1 || return 1
  python3 "${DEPLOY_DIR}/scripts/emqx-environment-qualification.py" mqtt-isolation >/dev/null 2>&1
}
if ! emqx_durable_ingress_contract; then
  log "EMQX durable republish 运行配置陈旧，重启 EMQX 以重载 base.hocon"
  (cd "${DEPLOY_DIR}" && docker compose --env-file .env restart emqx >/dev/null)
  DURABLE_INGRESS_READY=false
  for _ in $(seq 1 30); do
    if emqx_durable_ingress_contract; then
      DURABLE_INGRESS_READY=true
      break
    fi
    sleep 1
  done
  if ! ${DURABLE_INGRESS_READY}; then
    log "错误：EMQX 未加载唯一 durable republish 或仍保留旧消息入口"
    exit 1
  fi
fi

# 默认 8080 已被开发者的 IDEA 后端占用时，测试后端使用显式备用端口。EMQX 回调必须与候选后端
# 保持同一端口；只从仓库默认 8080 状态切换，任何未知现状都 fail-closed，避免覆盖本机配置。
if [[ "${BASE_PORT}" != "8080" ]]; then
  if ! emqx_callbacks_match_port 8080; then
    log "错误：EMQX 认证/授权/连接事件回调不是仓库默认 8080，拒绝覆盖未知本机配置"
    exit 1
  fi
  log "将 EMQX 认证/授权/连接事件回调临时切换至隔离后端端口 ${BASE_PORT}"
  if ! emqx_set_callback_port "${BASE_PORT}"; then
    log "错误：EMQX 认证/授权/连接事件回调未能完整切换至端口 ${BASE_PORT}"
    exit 1
  fi
fi

# EMQX 下行发布测试凭据：登录 Dashboard 创建每轮专用 API Key，秘密只留在进程变量。
#     命令下行经 EMQX /api/v5/publish（Basic api_key:api_secret）派发，缺凭据会 fail-closed
#     为 DISPATCH_CREDENTIALS_MISSING；cleanup 会删除，避免本地共享栈无限遗留。
emqx_create_api_key() {
  local token created
  for _ in $(seq 1 30); do
    token="$(curl -s --max-time 3 -X POST "${EMQX_API_BASE_URL}/api/v5/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"${EMQX_DASHBOARD_USER}\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null || true)"
    [[ -n "${token}" ]] && break
    sleep 1
  done
  [[ -n "${token}" ]] || { log "错误：EMQX Dashboard 登录失败（${EMQX_API_BASE_URL}）"; exit 1; }
  # 请求结果不明时也清理本轮唯一名称，不等响应返回才登记所有权。
  EMQX_KEY_OWNED=true
  if ! created="$(curl -fsS --max-time 3 -X POST "${EMQX_API_BASE_URL}/api/v5/api_key" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    -d "{\"name\":\"${EMQX_KEY_NAME}\",\"enable\":true,\"expired_at\":\"${EMQX_KEY_EXPIRY}\",\"desc\":\"e2e downlink publish\"}")"; then
    log "错误：EMQX API Key 创建请求失败（响应正文不输出，避免泄露 api_secret）"
    exit 1
  fi
  EMQX_KEY_OWNED=true
  EMQX_API_KEY="$(printf '%s' "${created}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("api_key",""))' 2>/dev/null || true)"
  EMQX_API_SECRET="$(printf '%s' "${created}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("api_secret",""))' 2>/dev/null || true)"
  [[ -n "${EMQX_API_KEY}" && -n "${EMQX_API_SECRET}" ]] || {
    log "错误：EMQX API Key 响应缺少必要字段（响应正文不输出，避免泄露 api_secret）"; exit 1;
  }
  log "EMQX 下行测试 API Key 已创建（${EMQX_KEY_NAME}，秘密不入日志）"
}
EMQX_KEY_EXPIRY="$(python3 -c 'import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"

# 3. 后端存在性/健康检查（在预置账号之前）。curl 一律加 --max-time，避免对半死连接无限挂起。
if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  if curl -sf --max-time 3 "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "错误：${BASE_URL} 已有后端在运行；若要用它请加 SKIP_BACKEND=1（它必须用专用 Redis DB ${REDIS_TEST_DB} 启动）"
    exit 1
  fi
else
  if ! curl -sf --max-time 3 "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "错误：SKIP_BACKEND=1 但 ${BASE_URL} 后端不可达"
    exit 1
  fi
fi

# 3b. 默认模式创建并注入本轮专用 Key；复用后端时脚本无法改变已启动进程的环境，
#     因此模拟器旅程必须由调用方显式提供与该后端启动时相同的凭据，不能生成一个后端永远看不到的新 Key。
if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  emqx_create_api_key
elif ${WITH_SIMULATOR} && [[ -z "${EMQX_API_KEY}" || -z "${EMQX_API_SECRET}" ]]; then
  log "错误：SKIP_BACKEND=1 + --with-simulator 需要显式提供该后端已使用的 EMQX_API_KEY/EMQX_API_SECRET"
  exit 1
fi

# S12-4：真实Vite使用共享合同包的构建产物；失败应在触及专用Redis之前显露。
(cd "${CONSOLE_DIR}" && pnpm contracts:build)

# 3. 严格清空专用 Redis DB（限流计数每轮从 0 开始）
flush_test_redis_db() {
  local password out rc
  password="$(grep -E '^REDIS_PASSWORD=' "${DEPLOY_DIR}/.env" 2>/dev/null | cut -d= -f2- || true)"
  password="${password:-thingslink}"
  log "清空专用 Redis DB ${REDIS_TEST_DB}（只清测试库，不碰 dev 的 DB 0）"
  local redis_cli=(docker exec -e "REDISCLI_AUTH=${password}" tc-redis redis-cli -n "${REDIS_TEST_DB}")
  if out="$("${redis_cli[@]}" FLUSHDB 2>&1)"; then
    [[ "${out}" == "OK" ]] || { log "错误：FLUSHDB 输出异常（应为 OK，实际 '${out}'）"; exit 1; }
  else
    rc=$?; log "错误：FLUSHDB 失败（退出码 ${rc}，输出 '${out}'）"; exit 1
  fi
  if out="$("${redis_cli[@]}" DBSIZE 2>&1)"; then
    [[ "${out}" == "0" ]] || { log "错误：专用 Redis DB ${REDIS_TEST_DB} 未清空（DBSIZE 输出 '${out}'）"; exit 1; }
  else
    rc=$?; log "错误：DBSIZE 失败（退出码 ${rc}，输出 '${out}'）"; exit 1
  fi
}
REDIS_TOUCHED=true
flush_test_redis_db

# S12-4c发布旅程必须消费真实受管宿主；旧旅程不额外构建，不能以测试Host替身放行。
DASHBOARD_HOST_REGISTRY="${THINGS_LINK_DASHBOARD_HOST_REGISTRY_DIRECTORY:-}"
NEEDS_DASHBOARD_HOST="$(python3 - "${E2E_SPEC}" "${E2E_MATRIX_SPECS}" <<'PY_HOST'
import re, sys
selectors = sys.argv[2].split(':') if sys.argv[2] else [sys.argv[1]]
print('true' if any(not item or any(re.search(item, name) for name in ('e2e/application-publication.spec.ts', 'e2e/application-editor.spec.ts', 'e2e/dashboard-publication.spec.ts', 'e2e/dashboard-share.spec.ts', 'e2e/dashboard-grants.spec.ts')) for item in selectors) else 'false')
PY_HOST
)"
if [[ "${NEEDS_DASHBOARD_HOST}" == "true" && "${SKIP_BACKEND:-0}" != "1" ]]; then
  log "构建并登记发布旅程专用真实WebApp宿主（不覆盖既有登记、不部署）"
  (cd "${CONSOLE_DIR}/../things-link-webapp" && pnpm build)
  DASHBOARD_HOST_REGISTRY="$(python3 -c 'import pathlib,tempfile; print(pathlib.Path(tempfile.mkdtemp(prefix="console-publication-host-")).resolve())')"
  (cd "${CONSOLE_DIR}/../things-link-webapp" && python3 scripts/host-candidate.py register \
    --registry-directory "${DASHBOARD_HOST_REGISTRY}")
  log "测试宿主登记留存供复核：${DASHBOARD_HOST_REGISTRY}"
fi

# 分享旅程只启用本轮隔离匿名宿主；原有旅程不改变分享开关。
NEEDS_SHARE_HOST="$(python3 - "${E2E_SPEC}" "${E2E_MATRIX_SPECS}" <<'PY_SHARE'
import re, sys
selectors = sys.argv[2].split(':') if sys.argv[2] else [sys.argv[1]]
print('true' if any(not item or re.search(item, 'e2e/dashboard-share.spec.ts') for item in selectors) else 'false')
PY_SHARE
)"
SHARE_ENABLED="${THINGS_LINK_DASHBOARD_SHARE_ENABLED:-false}"
SHARE_ORIGIN="${THINGS_LINK_DASHBOARD_SHARE_HOST_ORIGIN:-}"
SHARE_LOOPBACK="${THINGS_LINK_DASHBOARD_SHARE_ALLOW_LOOPBACK_HTTP:-false}"
# 所有自有后端都必须取得非空日志目录；空LOG_PATH会把滚动文件解析到根目录。
E2E_BACKEND_LOG_PATH="${LOG_PATH:-}"
if [[ "${SKIP_BACKEND:-0}" != "1" && -z "${E2E_BACKEND_LOG_PATH}" ]]; then
  E2E_BACKEND_LOG_PATH="$(python3 -c 'import pathlib,tempfile; print(pathlib.Path(tempfile.mkdtemp(prefix="console-backend-log-")).resolve())')"
  log "测试后端日志保留：${E2E_BACKEND_LOG_PATH}（不构成生产持久化资格）"
fi
if [[ "${NEEDS_SHARE_HOST}" == "true" ]]; then
  [[ "${WEBAPP_PORT}" =~ ^[0-9]+$ ]] && (( WEBAPP_PORT > 1024 && WEBAPP_PORT < 65536 )) || {
    log "错误：E2E_WEBAPP_PORT必须为1025..65535"; exit 1;
  }
  if (exec 3<>"/dev/tcp/127.0.0.1/${WEBAPP_PORT}") 2>/dev/null; then
    log "错误：匿名宿主测试端口已占用，不接管已有进程"; exit 1
  fi
  SHARE_ORIGIN="http://localhost:${WEBAPP_PORT}"
  if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
    SHARE_ENABLED=true
    SHARE_LOOPBACK=true
  fi
fi

# 受控SMTP独立选场：临时可信TLS、真实邮件接收，不关闭信任检查或改变生产重试间隔。
if ${CONTROLLED_SMTP}; then
  TEST_TLS_DIRECTORY="${CONSOLE_DIR}/.e2e-evidence/tls-${TS}-$$"
  [[ ! -e "${TEST_TLS_DIRECTORY}" ]] || { log "错误：测试TLS目录已存在"; TEST_TLS_DIRECTORY=""; exit 1; }
  EMQX_DASHBOARD_USER="${EMQX_DASHBOARD_USER}" EMQX_DASHBOARD_PASSWORD="${EMQX_DASHBOARD_PASSWORD}" \
    python3 "${SCRIPT_DIR}/emqx-test-tls.py" install --directory "${TEST_TLS_DIRECTORY}" \
      --dashboard-port "${EMQX_DASHBOARD_PORT}" --mqtt-port "${EMQX_MQTT_TLS_PORT:-8883}"
  keytool -importcert -noprompt -alias controlled-test-ca -storetype PKCS12 \
    -keystore "${TEST_TLS_DIRECTORY}/trust.p12" -storepass changeit \
    -file "${TEST_TLS_DIRECTORY}/ca.pem" >/dev/null 2>&1
  node "${SCRIPT_DIR}/controlled-smtp-fixture.mjs" "${TEST_TLS_DIRECTORY}" \
    >"${DEFAULT_E2E_LOG_DIR}/controlled-smtp.log" 2>&1 &
  SMTP_PID=$!
  for _ in $(seq 1 50); do
    [[ -f "${TEST_TLS_DIRECTORY}/smtp-config.json" ]] && break
    kill -0 "${SMTP_PID}" 2>/dev/null || { log "错误：受控SMTP启动失败"; exit 1; }
    sleep 0.1
  done
  [[ -f "${TEST_TLS_DIRECTORY}/smtp-config.json" ]] || { log "错误：受控SMTP未就绪"; exit 1; }
  SMTP_PORT="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["port"])' "${TEST_TLS_DIRECTORY}/smtp-config.json")"
  smtp_env_args=("MAIL_SMTP_HOST=127.0.0.1" "MAIL_SMTP_PORT=${SMTP_PORT}"
    "MAIL_SMTP_USERNAME=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["username"])' "${TEST_TLS_DIRECTORY}/smtp-config.json")"
    "MAIL_SMTP_PASSWORD=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["password"])' "${TEST_TLS_DIRECTORY}/smtp-config.json")")
  if [[ "${E2E_SPEC}" == "invitation-registration-smtp.spec.ts" ]]; then
    smtp_env_args+=("THINGS_LINK_CONSOLE_BASE_URL=${E2E_URL}")
  fi
  smtp_java_args=("-Djavax.net.ssl.trustStore=${TEST_TLS_DIRECTORY}/trust.p12"
    "-Djavax.net.ssl.trustStorePassword=changeit"
    "-Dspring.mail.properties.mail.smtp.ssl.checkserveridentity=true"
    "-Dlogging.level.com.things.link.support.notification.mail.SmtpMailSender=WARN"
    "-Dlogging.level.com.things.link.support.notification.mail.MailConfiguration=WARN")
  export E2E_CONTROLLED_SMTP=1 E2E_TEST_TLS_DIRECTORY="${TEST_TLS_DIRECTORY}"
  if [[ "${E2E_SPEC}" == "property-automation-smtp.spec.ts" ]]; then
    [[ -f /.thingslink-local-ci ]] || { log "属性自动化运营夹具只允许隔离 Dind 选场"; exit 1; }
    smtp_env_args+=("THINGS_LINK_AUTOMATION_PROPERTY_ENABLED=true")
  elif [[ "${E2E_SPEC}" == "time-once-smtp.spec.ts" || "${E2E_SPEC}" == "time-cron-smtp.spec.ts" ]]; then
    [[ -f /.thingslink-local-ci ]] || { log "时间自动化运营夹具只允许隔离 Dind 选场"; exit 1; }
    smtp_env_args+=("THINGS_LINK_AUTOMATION_TIME_ENABLED=true")
  fi
fi

# 4. 后端（默认模式构建启动）—— **必须在预置账号之前**：业务表由 Flyway 在后端首启时创建，
#    干净检出下 `make up` 只建扩展不建表，若先写 sys_account 会因表不存在而失败。
if [[ "${SKIP_BACKEND:-0}" != "1" ]]; then
  if [[ -n "${E2E_BATCH_DIR:-}" ]]; then
    JAR="${E2E_BACKEND_JAR}"
  else
    log "构建后端胖 jar（普通运行，不构成正式逐场资格）"
    # D-163 已按根因修复：模拟器的可执行 JAR 改带 exec 分类器，主构件保留普通 JAR，
    # 因此 bootstrap 的测试编译在这个 `package` 里能正常解析 `com.things.link.simulator.*`，
    # 不再需要 `-Dmaven.test.skip=true` 这一变通；这里只用 `-DskipTests` 跳过**执行**测试。
    (cd "${BACKEND_DIR}" && ./mvnw -q -pl things-link-bootstrap -am package -DskipTests)
    JAR="$(ls "${BACKEND_DIR}"/things-link-bootstrap/target/things-link-bootstrap-*.jar | head -1)"
  fi
  INGRESS_HANDOFF_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  log "启动后端 ${JAR}（专用 Redis DB，含下行凭据与 durable ingress 服务身份）"
  env "${ota_env_args[@]+"${ota_env_args[@]}"}" "${smtp_env_args[@]+"${smtp_env_args[@]}"}" \
  SPRING_DATA_REDIS_DATABASE="${REDIS_TEST_DB}" \
  SERVER_PORT="${BASE_PORT}" \
  EMQX_API_KEY="${EMQX_API_KEY}" \
  EMQX_API_SECRET="${EMQX_API_SECRET}" \
  THINGS_LINK_STORAGE_INTERNAL_ENDPOINT="${MINIO_STORAGE_ENDPOINT}" \
  THINGS_LINK_STORAGE_EXTERNAL_ENDPOINT="${MINIO_STORAGE_ENDPOINT}" \
  THINGS_LINK_STORAGE_ACCESS_KEY="${MINIO_ROOT_USER}" \
  THINGS_LINK_STORAGE_SECRET_KEY="${MINIO_ROOT_PASSWORD}" \
  THINGS_LINK_OTA_STORAGE_BUCKET="${OTA_STORAGE_BUCKET}" \
  THINGS_LINK_DASHBOARD_HOST_REGISTRY_DIRECTORY="${DASHBOARD_HOST_REGISTRY}" \
  THINGS_LINK_DASHBOARD_CONSOLE_ALLOWED_ORIGINS="${E2E_URL}" \
  THINGS_LINK_DASHBOARD_SHARE_ENABLED="${SHARE_ENABLED}" \
  THINGS_LINK_DASHBOARD_SHARE_HOST_ORIGIN="${SHARE_ORIGIN}" \
  THINGS_LINK_DASHBOARD_SHARE_ALLOW_LOOPBACK_HTTP="${SHARE_LOOPBACK}" \
  LOG_PATH="${E2E_BACKEND_LOG_PATH}" \
  THINGS_LINK_INGRESS_HANDOFF_ENABLED=true \
  THINGS_LINK_INGRESS_HANDOFF_BROKER_URI="tcp://127.0.0.1:${EMQX_MQTT_PORT}" \
  THINGS_LINK_INGRESS_HANDOFF_PASSWORD="${INGRESS_HANDOFF_PASSWORD}" \
    java "${smtp_java_args[@]+"${smtp_java_args[@]}"}" -jar "${JAR}" >"${BACKEND_LOG}" 2>&1 &
  BACKEND_PID=$!
  unset INGRESS_HANDOFF_PASSWORD
  sleep 1
  if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
    log "错误：后端启动即退出，日志见 ${BACKEND_LOG}"; exit 1
  fi
  for _ in $(seq 1 60); do curl -sf --max-time 3 "${BASE_URL}/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
  if ! curl -sf --max-time 3 "${BASE_URL}/actuator/health" >/dev/null 2>&1; then
    log "后端启动失败，日志见 ${BACKEND_LOG}"; exit 1
  fi
fi

# 干净库路径：业务表由本次启动的Flyway建立后立即写入固定夹具；启动前已写入时不重复。
if ${OTA_LIFECYCLE} && ! ${OTA_LIFECYCLE_FIXTURE_PROVISIONED}; then
  provision_ota_lifecycle_fixture
fi

if ${WITH_SIMULATOR}; then
  wait_for_durable_ingress_ready
fi

# 5. 预置两个已验证账号（OWNER 与成员）—— 必须在后端启动、Flyway 建表之后。
# ADR0159：legacy 夹具缺冻结项目投影，新增项目会 503/50047。E2E OWNER/MEMBER
# 使用测试专用模拟供给的 PROFESSIONAL 冻结模板；其项目/设备/REST 上限覆盖这轮串行旅程，
# 但仍由真实计量守卫检查，不能把模拟订单当作真实商用收款或放行。
log "预置 OWNER 账号 ${OWNER_EMAIL}"
"${DEPLOY_DIR}/scripts/add-console-account.sh" --email "${OWNER_EMAIL}" --password "${PASSWORD}" \
  --display-name 'E2E-Owner' --project-name 'E2E项目' --plan-code PROFESSIONAL >/dev/null
log "预置成员账号 ${MEMBER_EMAIL}"
"${DEPLOY_DIR}/scripts/add-console-account.sh" --email "${MEMBER_EMAIL}" --password "${PASSWORD}" \
  --display-name 'E2E-Member' --project-name '成员项目' --plan-code PROFESSIONAL >/dev/null
# 套餐面板旅程需要**真实供给**的租户（有 FREE 订阅才能读到套餐摘要），因此这个账号不传夹具开关：
# 它走默认路径（provision-console-tenant.sql），用来在真实浏览器里验证 S14 的套餐与有效额度展示面。
log "预置套餐面板账号 ${PLAN_EMAIL}（默认供给：写 FREE 订阅与策略绑定）"
"${DEPLOY_DIR}/scripts/add-console-account.sh" --email "${PLAN_EMAIL}" --password "${PASSWORD}" \
  --display-name 'E2E-Plan' --project-name "${PLAN_PROJECT}" >/dev/null
if [[ -z "${E2E_SPEC}" && -z "${E2E_MATRIX_SPECS}" \
  || "${E2E_SPEC}" == self-hosted-enrollment.spec.ts \
  || "${E2E_SPEC}" == self-hosted-review.spec.ts \
  || ":${E2E_MATRIX_SPECS}:" == *:self-hosted-enrollment.spec.ts:* \
  || ":${E2E_MATRIX_SPECS}:" == *:self-hosted-review.spec.ts:* ]]; then
  log "预置自部署浏览器验收的独立运营与审核账号"
  provision_shc_browser_fixture
fi

# 5b. 取 OWNER 项目的真实 project_key（旅程 3 的模拟器按 {projectKey}/{deviceKey} 认证，
#     控制台界面不展示项目 key，从种子库按「OWNER 账号的项目」精确定位，避免命中历史轮次同名项目）
if [[ -f "${DEPLOY_DIR}/.env" ]]; then
  source <(grep -E '^POSTGRES_USER=|^POSTGRES_DB=' "${DEPLOY_DIR}/.env")
fi
PGUSER="${POSTGRES_USER:-thingslink}"
PGDB="${POSTGRES_DB:-thingslink}"
E2E_PROJECT_KEY="$(docker exec tc-postgres psql -U "$PGUSER" -d "$PGDB" --no-psqlrc -t -A \
  -c "SELECT p.project_key FROM sys_project p JOIN sys_project_member pm ON pm.project_id = p.id JOIN sys_account a ON a.id = pm.account_id WHERE a.email = '${OWNER_EMAIL}' AND pm.role = 'OWNER' LIMIT 1;")"
if [[ -z "$E2E_PROJECT_KEY" ]]; then
  log "错误：查不到 OWNER ${OWNER_EMAIL} 的项目 project_key"; exit 1
fi
log "OWNER 项目 project_key=${E2E_PROJECT_KEY}"

# 6. 模拟器（旅程 3 需要）
if $WITH_SIMULATOR; then
  if [[ -n "${E2E_BATCH_DIR:-}" ]]; then
    SIM_JAR="${E2E_SIMULATOR_JAR}"
  else
    log "构建模拟器胖 jar（普通运行）"
    (cd "${BACKEND_DIR}" && ./mvnw -q -pl things-link-simulator -am package -DskipTests)
    SIM_JAR="$(ls "${BACKEND_DIR}"/things-link-simulator/target/things-link-simulator-*.jar | head -1)"
  fi
  log "启动模拟器 ${SIM_JAR}（8090）"
  java -jar "${SIM_JAR}" >"${SIMULATOR_LOG}" 2>&1 &
  SIMULATOR_PID=$!
  sleep 1
  if ! kill -0 "${SIMULATOR_PID}" 2>/dev/null; then
    log "错误：模拟器启动即退出，日志见 ${SIMULATOR_LOG}"; exit 1
  fi
  for _ in $(seq 1 30); do curl -sf --max-time 3 http://localhost:8090/simulations/stats >/dev/null 2>&1 && break; sleep 1; done
  if ! curl -sf --max-time 3 http://localhost:8090/simulations/stats >/dev/null 2>&1; then
    log "模拟器启动失败，日志见 ${SIMULATOR_LOG}"; exit 1
  fi
fi

# 7. Vite 开发服务器（控制台，代理 /api → 后端）。--strictPort 让端口被占用时立即失败退出，
#    而不是自动递增到别的端口后让脚本继续轮询原端口、Playwright 打到错误的地址上。
log "启动 Vite（${E2E_URL}）"
# 用 exec 直接替换 subshell 为 vite 本体，使 VITE_PID 就是 Vite 进程；
# 否则 VITE_PID 指向包装 shell，cleanup 杀掉后 pnpm→vite 子进程仍会监听端口。
(cd "${CONSOLE_DIR}" && VITE_API_PROXY_URL="${BASE_URL}" \
  PLAYWRIGHT_BROWSERS_PATH="$CONSOLE_DIR/.playwright-browsers" \
  exec node_modules/.bin/vite --strictPort --port "${E2E_PORT}" >"${VITE_LOG}" 2>&1) &
VITE_PID=$!
# pnpm exec 是包装进程：端口冲突时它可能先存活一小段再退出。等 2s 让它稳定，而不是 1s 后误判存活。
sleep 2
if ! kill -0 "${VITE_PID}" 2>/dev/null; then
  log "错误：Vite 启动即退出（端口 ${E2E_PORT} 被占用？），日志见 ${VITE_LOG}"; exit 1
fi
# readiness：启动前已拒绝占用端口，故此处命中的必然是我们刚起的 Vite，而不是残留旧服务。
for _ in $(seq 1 30); do curl -sf --max-time 3 "${E2E_URL}" >/dev/null 2>&1 && break; sleep 1; done
if ! curl -sf --max-time 3 "${E2E_URL}" >/dev/null 2>&1; then
  log "Vite 启动失败，日志见 ${VITE_LOG}"; exit 1
fi
# readiness 通过后再次确认启动进程仍存活，捕获包装进程延迟退出（避免 Playwright 打到已消失的服务）。
if ! kill -0 "${VITE_PID}" 2>/dev/null; then
  log "错误：Vite 在就绪后退出，日志见 ${VITE_LOG}"; exit 1
fi

if [[ "${NEEDS_SHARE_HOST}" == "true" ]]; then
  # exec使cleanup持有实际Vite PID；端口只允许本轮创建的宿主。
  (cd "${CONSOLE_DIR}/../things-link-webapp" && VITE_DEV_API_TARGET="${BASE_URL}" \
    exec node_modules/.bin/vite --host localhost --strictPort --port "${WEBAPP_PORT}" \
    >"${WEBAPP_LOG}" 2>&1) &
  WEBAPP_PID=$!
  for _ in $(seq 1 30); do
    kill -0 "${WEBAPP_PID}" 2>/dev/null || { log "错误：匿名宿主启动退出"; exit 1; }
    curl -sf --max-time 3 "${SHARE_ORIGIN}/app/" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -sf --max-time 3 "${SHARE_ORIGIN}/app/" >/dev/null 2>&1 || {
    log "错误：匿名宿主未就绪"; exit 1;
  }
fi

# 8. 复验 C2c 契约测试（同一本地共享真实栈、同一批已验证账号；G1 门禁的隔离环境 9/9 由干净 CI 另行取证）
log "复验 C2c 契约测试（本地共享真实栈）"
(
  cd "${CONSOLE_DIR}"
  contract_args=()
  if [[ -n "${E2E_RUN_DIR:-}" ]]; then
    contract_args+=(--reporter=default --reporter=json --outputFile="${E2E_RUN_DIR}/contract.json")
  fi
  CONTRACT_BASE_URL="${BASE_URL}" \
  CONTRACT_OWNER_EMAIL="${OWNER_EMAIL}" \
  CONTRACT_OWNER_PASSWORD="${PASSWORD}" \
  CONTRACT_MEMBER_EMAIL="${MEMBER_EMAIL}" \
  CONTRACT_MEMBER_PASSWORD="${PASSWORD}" \
    pnpm test:contract ${contract_args[@]+"${contract_args[@]}"}
)

# C2c deliberately exhausts the real per-IP registration budget (3 requests / 5 minutes).
# Only the private invitation journey registers another account. Let the unchanged Redis
# window expire naturally; never clear its counters, spoof the client IP or weaken its limit.
if [[ "${E2E_SPEC}" == "invitation-registration-smtp.spec.ts" ]]; then
  [[ "${CONTROLLED_SMTP:-false}" == "true" && "${E2E_CONTROLLED_SMTP:-}" == "1" && "${E2E_SMTP_PRIVATE_IDENTITY:-}" == "1" ]] || {
    log "邀请注册等待只能在真实私有 SMTP 精确选场中执行"; exit 1;
  }
  for ((registration_wait_part=1; registration_wait_part<=10; registration_wait_part++)); do
    log "等待生产注册限流窗口自然到期（${registration_wait_part}/10，每段30秒）"
    sleep 30
  done
  sleep 1
fi

# 9. 跑 Playwright（浏览器 E2E）
log "运行 Playwright E2E（WITH_SIMULATOR=${WITH_SIMULATOR}, E2E_SPEC=${E2E_SPEC:-全部}, MATRIX=${E2E_MATRIX_SPECS:-无}）"
(
  cd "${CONSOLE_DIR}"
  export PLAYWRIGHT_BROWSERS_PATH="$CONSOLE_DIR/.playwright-browsers"
  export E2E_BASE_URL="${E2E_URL}"
  export E2E_OWNER_EMAIL="${OWNER_EMAIL}"
  export E2E_OWNER_PASSWORD="${PASSWORD}"
  export E2E_MEMBER_EMAIL="${MEMBER_EMAIL}"
  export E2E_MEMBER_PASSWORD="${PASSWORD}"
  export E2E_PLAN_EMAIL="${PLAN_EMAIL}"
  export E2E_PLAN_PASSWORD="${PASSWORD}"
  export E2E_PLAN_PROJECT="${PLAN_PROJECT}"
  export E2E_SHC_OPERATOR_EMAIL="${SHC_OPERATOR_EMAIL}"
  export E2E_SHC_REVIEWER_ONE_EMAIL="${SHC_REVIEWER_ONE_EMAIL}"
  export E2E_SHC_REVIEWER_TWO_EMAIL="${SHC_REVIEWER_TWO_EMAIL}"
  export E2E_SHC_NO_PERMISSION_EMAIL="${MEMBER_EMAIL}"
  export E2E_SHC_PASSWORD="${PASSWORD}"
  export E2E_PROJECT_KEY="${E2E_PROJECT_KEY}"
  export EMQX_MQTT_PORT="${EMQX_MQTT_PORT}" # 真实遥测旅程使用本轮栈端口，不猜1883。
  export E2E_PG_USER="${PGUSER}" E2E_PG_DB="${PGDB}" # 只读夹具绑定版本，不能写业务事实。
  if [[ "${E2E_SPEC}" == "property-automation-smtp.spec.ts" ]]; then
    export E2E_AUTOMATION_FIXTURE=property-smtp E2E_AUTOMATION_ADMIN_USER="${PGUSER}" E2E_AUTOMATION_ADMIN_DB="${PGDB}"
  elif [[ "${E2E_SPEC}" == "time-once-smtp.spec.ts" || "${E2E_SPEC}" == "time-cron-smtp.spec.ts" ]]; then
    export E2E_AUTOMATION_FIXTURE=time-smtp E2E_AUTOMATION_ADMIN_USER="${PGUSER}" E2E_AUTOMATION_ADMIN_DB="${PGDB}"
  else
    unset E2E_AUTOMATION_FIXTURE E2E_AUTOMATION_ADMIN_USER E2E_AUTOMATION_ADMIN_DB
  fi
  if [[ "${E2E_SPEC}" == "device-associations-populated.spec.ts" ]]; then
    export E2E_ASSOCIATION_FIXTURE=1 E2E_AUTOMATION_ADMIN_USER="${PGUSER}" E2E_AUTOMATION_ADMIN_DB="${PGDB}"
  else
    unset E2E_ASSOCIATION_FIXTURE
  fi
  # 生命周期旅程把固定三元组夹具与固定OWNER账号暴露给spec（不写入任何秘密到日志）。
  export E2E_OTA_LIFECYCLE="${OTA_LIFECYCLE}"
  export E2E_OTA_FIXTURE_DIR="${OTA_FIXTURE_DIR}"
  export E2E_OTA_OWNER_EMAIL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["ownerEmail"])' "${OTA_FIXTURE_DIR}/ota-lifecycle-fixture.json")"
  export E2E_OTA_OWNER_PASSWORD="${PASSWORD}"
  playwright_args=()
  if [[ -n "${E2E_SPEC}" ]]; then playwright_args+=("${E2E_SPEC}"); fi
  if [[ -n "${E2E_MATRIX_SPECS}" ]]; then playwright_args+=("${E2E_MATRIX_FILES[@]}"); fi
  # Q7/D-103：Bash 3.2 的 nounset 把空数组视作未设置；保护展开保留零参数与每个元素的原始边界。
  if $WITH_SIMULATOR; then
    pnpm exec playwright test ${playwright_args[@]+"${playwright_args[@]}"}
  else
    pnpm exec playwright test ${playwright_args[@]+"${playwright_args[@]}"} --grep-invert @simulator
  fi
)

log "E2E 完成"
