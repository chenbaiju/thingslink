#!/usr/bin/env bash
set -euo pipefail

# 直接本机调用仍拒绝；本地入口必须在专用 Runner 内拥有独立 Docker daemon。
# 本地证据显式标记，不冒充 GitHub 资格，也不混入 GitHub 的性能历史。
if [[ "${GITHUB_ACTIONS:-false}" == true && "${CI:-false}" == true ]]; then
  export L1_EXECUTION_ENVIRONMENT=github-actions
  export L1_RUN_NUMBER="${GITHUB_RUN_ID:?GITHUB_RUN_ID 必填}" L1_RUN_ATTEMPT="${GITHUB_RUN_ATTEMPT:?GITHUB_RUN_ATTEMPT 必填}"
  export L1_REPOSITORY="${GITHUB_REPOSITORY:-}" L1_SOURCE_HEAD="${GITHUB_SHA:-}"
  L1_TEMP_DIR="${RUNNER_TEMP:?RUNNER_TEMP 必填}"
elif [[ "${THINGS_LINK_LOCAL_CI:-}" == nightly-l1 && -f /.thingslink-local-ci \
  && "${DOCKER_HOST:-}" == unix:///var/run/docker.sock ]] \
  && docker info --format '{{json .Labels}}' | python3 -c \
    'import json,sys; sys.exit(0 if "thingslink.local-ci=disposable" in json.load(sys.stdin) else 1)'; then
  export L1_EXECUTION_ENVIRONMENT=local-docker
  export L1_RUN_NUMBER="${LOCAL_CI_RUN_ID:?LOCAL_CI_RUN_ID 必填}" L1_RUN_ATTEMPT=1
  export L1_REPOSITORY=local/working-tree L1_SOURCE_HEAD="${LOCAL_CI_SOURCE_HEAD:?LOCAL_CI_SOURCE_HEAD 必填}"
  L1_TEMP_DIR=/tmp
else
  echo "[c3d-l1] 错误：请在一次性 GitHub Runner 或 verify-nightly-l1.sh 的独立本地 Runner 执行" >&2
  exit 2
fi
[[ "$L1_RUN_NUMBER" =~ ^[0-9]+$ && "$L1_RUN_ATTEMPT" =~ ^[0-9]+$ ]] || exit 2

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ROOT_DIR="$(cd "${BACKEND_DIR}/.." && pwd)"
DEPLOY_DIR="${ROOT_DIR}/deploy"
QUALIFICATION_SCRIPT="${SCRIPT_DIR}/a4_qualification.py"
FIXTURE_SCRIPT="${SCRIPT_DIR}/l0_fixture.py"
QUOTA_SCRIPT="${SCRIPT_DIR}/l1_quota_prepare.py"
PROBE_SCRIPT="${SCRIPT_DIR}/l1_steady_probe.py"
OUTPUT_DIR="${1:-${BACKEND_DIR}/target/c3d-l1}"
SECRET_DIR="${L1_TEMP_DIR}/thingslink-c3d-l1-secrets"
RUN_ID="g1-c3d-l1-${L1_RUN_NUMBER}-${L1_RUN_ATTEMPT}"
EMAIL="nightly-${L1_RUN_NUMBER}@example.com"
PASSWORD="nightly-${L1_RUN_NUMBER}-${L1_RUN_ATTEMPT}-pass"
REDIS_TEST_DB=13
BACKEND_PID=""
QUALIFICATION_PID=""
PROBE_PID=""
EMQX_KEY_OWNED=false
EMQX_TOKEN=""
EMQX_KEY_NAME="nightly-${L1_RUN_NUMBER}-${L1_RUN_ATTEMPT}"
# #3 证据冻结的两核 Runner 候选；夹具、元数据和资格参数必须引用同一组值，避免比较指纹与实跑漂移。
# 第12轮控制池6连接在ACL突发时500ms借连接超时；仅为该候选预留2s等待，容量仍为6。
L1_CONTROL_CONNECTION_TIMEOUT_MS=2000
L1_DEVICE_COUNT=1000
L1_SHARD_SIZE=167
L1_SHARD_COUNT=$(((L1_DEVICE_COUNT + L1_SHARD_SIZE - 1) / L1_SHARD_SIZE))
# #16 的六片同启使两核 Runner 发生器 CPU 五秒值达到 72.95%；15 秒错峰仍把全局爬坡锁在 300 秒内。
L1_SHARD_START_STAGGER_SECONDS=15
L1_RSS_BUDGET_PER_SHARD=536870912
L1_THREAD_BUDGET_PER_SHARD=1024
# 聚合预算必须随实际分片数同比收紧，不能沿用八分片分母把 70% 资格线静默放宽。
L1_HOST_RSS_BUDGET=$((L1_RSS_BUDGET_PER_SHARD * L1_SHARD_COUNT))
L1_HOST_THREAD_BUDGET=$((L1_THREAD_BUDGET_PER_SHARD * L1_SHARD_COUNT))

if [[ -e "${OUTPUT_DIR}" ]]; then
  echo "[c3d-l1] 错误：证据目录必须全新：${OUTPUT_DIR}" >&2
  exit 2
fi
mkdir -p "${OUTPUT_DIR}" "${SECRET_DIR}"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"
chmod 700 "${SECRET_DIR}"
CREDENTIALS_FILE="${SECRET_DIR}/credentials.json"

BACKEND_JAR="$(find "${BACKEND_DIR}/things-link-bootstrap/target" -maxdepth 1 -type f -name 'things-link-bootstrap-*.jar' ! -name '*.original' | head -1)"
SIMULATOR_JAR="$(find "${BACKEND_DIR}/things-link-simulator/target" -maxdepth 1 -type f -name 'things-link-simulator-*-exec.jar' ! -name '*.original' | head -1)"
if [[ ! -f "${BACKEND_JAR}" || ! -f "${SIMULATOR_JAR}" ]]; then
  echo "[c3d-l1] 错误：backend/simulator JAR 不存在，工作流须先完成构建" >&2
  exit 2
fi

(cd "${DEPLOY_DIR}" && make init) >/dev/null
set -a
source "${DEPLOY_DIR}/.env"
set +a
EMQX_API_BASE_URL="http://127.0.0.1:${EMQX_DASHBOARD_PORT}"
# 后端存储配置按失败关闭策略要求完整凭据；L1 直接启动 JAR，不能依赖 Compose 自动传递容器环境。
MINIO_STORAGE_ENDPOINT="http://127.0.0.1:${MINIO_PORT}"

capture_emqx_ingress_metrics() {
  local destination="$1"
  local rule_file
  rule_file="$(mktemp "${OUTPUT_DIR}/emqx-rule.XXXXXX.json")"
  # ADR 0046 已删除 raw HTTP action。这里只归档当前 durable republish 规则的真实聚合计数；
  # 不能用零值兼容旧 schema，否则规则缺失或失败会被伪装成“没有 Broker 错误”。
  curl -fsS --max-time 5 "${EMQX_API_BASE_URL}/api/v5/rules/tc_durable_uplink/metrics" \
    -H "Authorization: Bearer ${EMQX_TOKEN}" > "${rule_file}"
  python3 - "${rule_file}" "${destination}" <<'PY'
import json, sys
from pathlib import Path

rule = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["metrics"]
document = {
    "schemaVersion": 2,
    "ingressMode": "durable-republish",
    "rule": {key: rule[key] for key in ("matched", "actions.total", "actions.success", "actions.failed")},
}
Path(sys.argv[2]).write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
  rm -f -- "${rule_file}"
}

capture_diagnostics() {
  {
    date -u '+timestamp=%Y-%m-%dT%H:%M:%SZ' || true
    df -h || true
    docker system df || true
    (cd "${DEPLOY_DIR}" && docker compose --env-file .env ps -a) || true
    (cd "${DEPLOY_DIR}" && docker compose --env-file .env logs --no-color --tail=500) || true
  } > "${OUTPUT_DIR}/dependency-diagnostics.log" 2>&1
}

delete_emqx_key() {
  if ! ${EMQX_KEY_OWNED}; then return 0; fi
  if [[ -z "${EMQX_TOKEN}" ]]; then return 1; fi
  curl -fsS --max-time 5 -X DELETE "${EMQX_API_BASE_URL}/api/v5/api_key/${EMQX_KEY_NAME}" \
    -H "Authorization: Bearer ${EMQX_TOKEN}" >/dev/null
  EMQX_KEY_OWNED=false
}

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ "${rc}" -ne 0 ]]; then capture_diagnostics; fi
  if [[ -n "${QUALIFICATION_PID}" ]] && kill -0 "${QUALIFICATION_PID}" 2>/dev/null; then
    kill "${QUALIFICATION_PID}" 2>/dev/null || true
    wait "${QUALIFICATION_PID}" 2>/dev/null || true
  fi
  if [[ -n "${PROBE_PID}" ]] && kill -0 "${PROBE_PID}" 2>/dev/null; then
    kill "${PROBE_PID}" 2>/dev/null || true
    wait "${PROBE_PID}" 2>/dev/null || true
  fi
  if [[ -n "${BACKEND_PID}" ]] && kill -0 "${BACKEND_PID}" 2>/dev/null; then
    kill "${BACKEND_PID}" 2>/dev/null || true
    wait "${BACKEND_PID}" 2>/dev/null || true
  fi
  delete_emqx_key || echo "[c3d-l1] 警告：临时 EMQX API Key 未确认删除" >&2
  (cd "${DEPLOY_DIR}" && docker compose --env-file .env down) >/dev/null 2>&1 || true
  exit "${rc}"
}
trap cleanup EXIT INT TERM

echo "[c3d-l1] 启动干净 deploy 核心栈"
(cd "${DEPLOY_DIR}" && make up) > "${OUTPUT_DIR}/dependencies-start.log" 2>&1

# 本轮 Redis DB 独立且必须从零开始；不修改 DB 0，防止脚本语义被复制到非一次性环境后扩大破坏范围。
redis_result="$(docker exec -e "REDISCLI_AUTH=${REDIS_PASSWORD}" tc-redis redis-cli -n "${REDIS_TEST_DB}" FLUSHDB)"
[[ "${redis_result}" == "OK" ]]
[[ "$(docker exec -e "REDISCLI_AUTH=${REDIS_PASSWORD}" tc-redis redis-cli -n "${REDIS_TEST_DB}" DBSIZE)" == "0" ]]

echo "[c3d-l1] 创建本轮 EMQX 下行 API Key"
EMQX_TOKEN="$(curl -fsS --max-time 5 -X POST "${EMQX_API_BASE_URL}/api/v5/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"${EMQX_DASHBOARD_USER}\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
expiry="$(python3 -c 'import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"))')"
created="$(curl -fsS --max-time 5 -X POST "${EMQX_API_BASE_URL}/api/v5/api_key" \
  -H "Authorization: Bearer ${EMQX_TOKEN}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"${EMQX_KEY_NAME}\",\"enable\":true,\"expired_at\":\"${expiry}\",\"desc\":\"G1-C3d nightly downlink\"}")"
EMQX_KEY_OWNED=true
EMQX_API_KEY="$(printf '%s' "${created}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["api_key"])')"
EMQX_API_SECRET="$(printf '%s' "${created}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["api_secret"])')"
unset created

echo "[c3d-l1] 启动当前提交后端"
INGRESS_HANDOFF_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
# 冻结 L1 的 16.7 条/s 自动化 Outbox 负载超过默认 8 条/s 领取上限；用明确候选吞吐配置，
# 不减少自动化/命令事件，也不放宽命令正确性判据。
SPRING_DATA_REDIS_DATABASE="${REDIS_TEST_DB}" EMQX_API_KEY="${EMQX_API_KEY}" \
EMQX_API_SECRET="${EMQX_API_SECRET}" THINGS_LINK_INGRESS_HANDOFF_ENABLED=true \
THINGS_LINK_INGRESS_HANDOFF_BROKER_URI="tcp://127.0.0.1:${EMQX_MQTT_PORT}" \
THINGS_LINK_INGRESS_HANDOFF_PASSWORD="${INGRESS_HANDOFF_PASSWORD}" \
THINGS_LINK_STORAGE_INTERNAL_ENDPOINT="${MINIO_STORAGE_ENDPOINT}" \
THINGS_LINK_STORAGE_EXTERNAL_ENDPOINT="${MINIO_STORAGE_ENDPOINT}" \
THINGS_LINK_STORAGE_ACCESS_KEY="${MINIO_ROOT_USER}" \
THINGS_LINK_STORAGE_SECRET_KEY="${MINIO_ROOT_PASSWORD}" \
THINGS_LINK_DATASOURCE_CONTROL_CONNECTION_TIMEOUT_MS="${L1_CONTROL_CONNECTION_TIMEOUT_MS}" \
THINGS_LINK_OUTBOX_PUBLISHER_FIXED_DELAY_MILLIS=100 \
THINGS_LINK_OUTBOX_PUBLISHER_BATCH_SIZE=4 java -jar "${BACKEND_JAR}" \
  > "${OUTPUT_DIR}/backend.log" 2>&1 &
BACKEND_PID=$!
unset INGRESS_HANDOFF_PASSWORD
for _ in $(seq 1 120); do
  if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
    echo "[c3d-l1] 错误：后端启动退出" >&2
    exit 1
  fi
  curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/health > "${OUTPUT_DIR}/backend-health.json"

echo "[c3d-l1] 等待固定 durable ingress owner 完成 CONNACK 与 SUBACK"
INGRESS_READY=false
for _ in $(seq 1 60); do
  metrics="$(curl -fsS --max-time 3 http://127.0.0.1:8080/actuator/prometheus 2>/dev/null || true)"
  # 与 console E2E 使用相同的无管道检查；grep -q 不能在 pipefail 下提前截断大型 Prometheus 响应。
  if grep -Eq '^thingslink_ingress_handoff_connected\{[^}]*\} 1(\.0+)?$' <<< "${metrics}"; then
    INGRESS_READY=true
    break
  fi
  sleep 1
done
if ! ${INGRESS_READY}; then
  echo "[c3d-l1] 错误：durable ingress owner 未在 60 秒内就绪" >&2
  exit 1
fi

echo "[c3d-l1] 建立本轮 OWNER、项目及 1,000 台设备夹具"
"${DEPLOY_DIR}/scripts/add-console-account.sh" --email "${EMAIL}" --password "${PASSWORD}" \
  --display-name 'C3d Nightly' --project-name 'C3d Nightly 项目' > "${SECRET_DIR}/account-prepare.log"
# FREE 的设备、日上行和时序点上限不能承载冻结的 L1。仓库尚无套餐管理 API，因此只在一次性隔离库克隆专用策略；
# 设备和凭据仍走生产 API，既保留配额门禁语义，也不把直接灌业务表伪装成真实控制面流量。
scope_line="$(python3 "${QUOTA_SCRIPT}" --postgres-user "${POSTGRES_USER}" --postgres-db "${POSTGRES_DB}" \
  --email "${EMAIL}" --policy-id '019c0000-0000-7000-8000-000000000001' \
  --output "${OUTPUT_DIR}/quota-policy.json")"
read -r PROJECT_ID PROJECT_KEY extra_field <<< "${scope_line}"
[[ -n "${PROJECT_ID}" && -n "${PROJECT_KEY}" && -z "${extra_field}" ]]
python3 "${FIXTURE_SCRIPT}" prepare --base-url http://127.0.0.1:8080 --email "${EMAIL}" \
  --password "${PASSWORD}" --project-id "${PROJECT_ID}" --project-key "${PROJECT_KEY}" \
  --device-count "${L1_DEVICE_COUNT}" --profile-prefix l1 --command-key nightly_probe --output "${CREDENTIALS_FILE}" \
  > "${OUTPUT_DIR}/fixture-summary.json"

echo "[c3d-l1] 归档 Runner 与镜像元数据（不含环境变量和秘密）"
python3 - "${OUTPUT_DIR}/run-metadata.json" "${BACKEND_JAR}" "${SIMULATOR_JAR}" \
  "${L1_DEVICE_COUNT}" "${L1_SHARD_SIZE}" "${L1_SHARD_COUNT}" \
  "${L1_SHARD_START_STAGGER_SECONDS}" \
  "${L1_RSS_BUDGET_PER_SHARD}" "${L1_THREAD_BUDGET_PER_SHARD}" \
  "${L1_HOST_RSS_BUDGET}" "${L1_HOST_THREAD_BUDGET}" \
  "${L1_CONTROL_CONNECTION_TIMEOUT_MS}" <<'PY'
import hashlib, json, os, platform, subprocess, sys
from pathlib import Path

def run(*argv):
    return subprocess.run(argv, capture_output=True, text=True, check=True, timeout=30).stdout.strip()

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

containers = ["tc-postgres", "tc-redis", "tc-redpanda", "tc-emqx", "tc-minio"]
images = {}
for name in containers:
    images[name] = json.loads(run("docker", "inspect", "--format", "{{json .Image}}", name))
root_size = int(run("df", "--output=size", "-B1", "/").splitlines()[-1])
memory = int(next(line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines()
                  if line.startswith("MemTotal:"))) * 1024
scripts = Path(__file__).resolve() if "__file__" in globals() else None
metadata = {
    "schemaVersion": 1,
    "repository": os.environ.get("L1_REPOSITORY"),
    "commit": os.environ.get("L1_SOURCE_HEAD"),
    "runId": int(os.environ["L1_RUN_NUMBER"]),
    "runAttempt": int(os.environ["L1_RUN_ATTEMPT"]),
    "executionEnvironment": os.environ["L1_EXECUTION_ENVIRONMENT"],
    "localSourceSha256": os.environ.get("LOCAL_CI_SOURCE_SHA256"),
    "runnerImageOs": os.environ.get("ImageOS", "local-ubuntu24"),
    "runnerImageVersion": os.environ.get("ImageVersion", "local-ci-v1"),
    "os": platform.platform(), "machine": platform.machine(),
    "logicalCpuCount": os.cpu_count(), "hostMemoryBytes": memory,
    "rootDiskBytes": root_size, "python": platform.python_version(),
    "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0],
    "dockerClient": run("docker", "version", "--format", "{{.Client.Version}}"),
    "dockerServer": run("docker", "version", "--format", "{{.Server.Version}}"),
    "images": images,
    "backendJarSha256": sha(sys.argv[2]), "simulatorJarSha256": sha(sys.argv[3]),
    "load": {"devices": int(sys.argv[4]), "propertiesPerReport": 10, "intervalSeconds": 60,
             "steadySeconds": 600, "commandRatePerSecond": 1, "commands": 600,
             "shardSize": int(sys.argv[5]), "shards": int(sys.argv[6]),
             "shardStartStaggerSeconds": int(sys.argv[7]),
             "xms": "64m", "xmx": "256m",
             "rssBudgetBytesPerShard": int(sys.argv[8]),
             "threadBudgetPerShard": int(sys.argv[9]),
             "hostRssBudgetBytes": int(sys.argv[10]),
             "hostThreadBudget": int(sys.argv[11]),
             "quotaPolicy": "L1_NIGHTLY", "deviceCountLimit": 2000,
             "controlConnectionTimeoutMs": int(sys.argv[12])}
}
Path(sys.argv[1]).write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
PY

BROKER_IMAGE="$(docker inspect --format '{{.Image}}' tc-emqx)"
BROKER_CONFIG_SHA="$(sha256sum "${DEPLOY_DIR}/emqx/base.hocon" | awk '{print $1}')"
STEADY_READY="${OUTPUT_DIR}/steady-ready.json"
PERFORMANCE_COMPLETE="${OUTPUT_DIR}/performance-complete.json"
COMMAND_DRAIN_COMPLETE="${OUTPUT_DIR}/command-drain-complete.json"
# correctness 覆盖连接爬坡、稳态和停止握手；性能五项仍由探针自己的稳态起止快照计算。
curl -fsS --max-time 10 http://127.0.0.1:8080/actuator/prometheus \
  > "${OUTPUT_DIR}/prometheus-correctness-start.txt"
capture_emqx_ingress_metrics "${OUTPUT_DIR}/emqx-ingress-start.json"
echo "[c3d-l1] 启动 1,000×10×1/min、600 秒资格负载"
set +e
python3 "${QUALIFICATION_SCRIPT}" --jar "${SIMULATOR_JAR}" \
  --broker-uri "tcp://127.0.0.1:${EMQX_MQTT_PORT}" \
  --broker-fingerprint "emqx-image=${BROKER_IMAGE};base-hocon-sha256=${BROKER_CONFIG_SHA}" \
  --project-key "${PROJECT_KEY}" --credentials-file "${CREDENTIALS_FILE}" \
  --device-count "${L1_DEVICE_COUNT}" --shard-size "${L1_SHARD_SIZE}" \
  --shard-start-stagger-seconds "${L1_SHARD_START_STAGGER_SECONDS}" \
  --interval-seconds 60 --properties-per-report 10 \
  --steady-seconds 600 --base-port 18190 --output-dir "${OUTPUT_DIR}/qualification" --run-id "${RUN_ID}" \
  --xms 64m --xmx 256m --rss-budget-bytes "${L1_RSS_BUDGET_PER_SHARD}" \
  --thread-budget "${L1_THREAD_BUDGET_PER_SHARD}" \
  --host-rss-budget-bytes "${L1_HOST_RSS_BUDGET}" \
  --host-thread-budget "${L1_HOST_THREAD_BUDGET}" \
  --sut-time-command "date +%s%3N" --steady-ready-file "${STEADY_READY}" \
  --performance-complete-file "${PERFORMANCE_COMPLETE}" \
  --command-drain-complete-file "${COMMAND_DRAIN_COMPLETE}" \
  --performance-completion-grace-seconds 30 --command-drain-watchdog-seconds 240 \
  > "${OUTPUT_DIR}/qualification.log" 2>&1 &
QUALIFICATION_PID=$!
set -e

for _ in $(seq 1 360); do
  [[ -f "${STEADY_READY}" ]] && break
  if ! kill -0 "${QUALIFICATION_PID}" 2>/dev/null; then
    wait "${QUALIFICATION_PID}" || true
    QUALIFICATION_PID=""
    echo "[c3d-l1] 错误：发生器未进入稳态，资格证据已保留" >&2
    exit 1
  fi
  sleep 1
done
[[ -f "${STEADY_READY}" ]] || { echo "[c3d-l1] 错误：360 秒内未收到稳态信号" >&2; exit 1; }

echo "[c3d-l1] 稳态窗口内执行 600 条真实 ACK 命令与 5 秒采样"
set +e
L1_ACCOUNT_PASSWORD="${PASSWORD}" timeout --signal=TERM --kill-after=15s 870s \
  python3 "${PROBE_SCRIPT}" --email "${EMAIL}" \
  --credentials-file "${CREDENTIALS_FILE}" --output-dir "${OUTPUT_DIR}" \
  --backend-pid "${BACKEND_PID}" --run-id "${RUN_ID}" \
  --performance-complete-file "${PERFORMANCE_COMPLETE}" \
  --command-drain-complete-file "${COMMAND_DRAIN_COMPLETE}" \
  > "${OUTPUT_DIR}/steady-probe.log" 2>&1 &
PROBE_PID=$!

# ready 只代表连接爬坡完成；资格器仍可能在稳态期触发资源门槛。并发监督两个进程，禁止资格失败后
# 探针继续提交必然超时的命令。资格器先以 0 退出只可能发生在它已消费 drain 信号之后，不应反杀探针收尾。
FIRST_EXIT_PID=""
wait -n -p FIRST_EXIT_PID "${QUALIFICATION_PID}" "${PROBE_PID}"
FIRST_EXIT_RC=$?
if [[ "${FIRST_EXIT_PID}" == "${QUALIFICATION_PID}" ]]; then
  QUALIFICATION_RC=${FIRST_EXIT_RC}
  QUALIFICATION_PID=""
  if [[ "${QUALIFICATION_RC}" -ne 0 ]] && kill -0 "${PROBE_PID}" 2>/dev/null; then
    echo "[c3d-l1] 发生器资格提前失败，终止命令探针并保留资格证据" >&2
    kill "${PROBE_PID}" 2>/dev/null || true
  fi
  wait "${PROBE_PID}"
  PROBE_RC=$?
  PROBE_PID=""
else
  PROBE_RC=${FIRST_EXIT_RC}
  PROBE_PID=""
fi
# 探针异常退出时补齐两个明确失败信号，只触发资格器清理，绝不伪造 submitted/terminal=600。
python3 - "${PERFORMANCE_COMPLETE}" "${COMMAND_DRAIN_COMPLETE}" "${RUN_ID}" "${PROBE_RC}" <<'PY'
import json, os, sys, time
from pathlib import Path
performance, drain = Path(sys.argv[1]), Path(sys.argv[2])
run_id, exit_code = sys.argv[3], int(sys.argv[4])
def write_if_missing(path, phase):
    if path.exists():
        return
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps({"schemaVersion": 1, "runId": run_id, "phase": phase,
                                     "outcome": "ERROR", "probeExitCode": exit_code,
                                     "completedAtEpochMillis": time.time_ns() // 1_000_000}) + "\n")
    os.replace(temporary, path)
write_if_missing(performance, "PERFORMANCE_COMPLETE")
write_if_missing(drain, "COMMAND_DRAIN_COMPLETE")
PY
if [[ -n "${QUALIFICATION_PID}" ]]; then
  wait "${QUALIFICATION_PID}"
  QUALIFICATION_RC=$?
  QUALIFICATION_PID=""
fi
set -e
if [[ "${QUALIFICATION_RC}" -ne 0 ]]; then
  echo "[c3d-l1] 错误：发生器资格=${QUALIFICATION_RC}，本轮不再形成 SUT 结论" >&2
  exit 1
fi
# 完成信号之后资格器才停止设备；在 wait 返回后取终点，覆盖 #5 中晚于性能终点一秒的上报洪峰。
curl -fsS --max-time 10 http://127.0.0.1:8080/actuator/prometheus \
  > "${OUTPUT_DIR}/prometheus-correctness-final.txt"
capture_emqx_ingress_metrics "${OUTPUT_DIR}/emqx-ingress-final.json"
RUN_FAILURE=0
if [[ "${PROBE_RC}" -ne 0 ]]; then
  echo "[c3d-l1] 错误：稳态探针=${PROBE_RC}；继续采集其余 correctness 证据" >&2
  RUN_FAILURE=1
fi

find "${OUTPUT_DIR}/qualification/manifests/${RUN_ID}" -type f -name property_report.log -print0 \
  | sort -z | xargs -0 --no-run-if-empty awk 'NF' > "${OUTPUT_DIR}/property-report-manifest.log"

group_lag() {
  docker exec tc-redpanda rpk group describe "$1" 2>/dev/null | awk '$1 == "TOTAL-LAG" {print $2}'
}
echo "[c3d-l1] 等待三个上行 group 在 10 分钟内回到基线 0"
for _ in $(seq 1 120); do
  all_zero=1
  for group in things-link-ingestion-raw things-link-ingestion-normalized things-link-ingestion-processed; do
    lag="$(group_lag "${group}" || true)"
    [[ "${lag}" == "0" ]] || all_zero=0
  done
  [[ "${all_zero}" == "1" ]] && break
  sleep 5
done
: > "${OUTPUT_DIR}/final-groups.txt"
for group in things-link-ingestion-raw things-link-ingestion-normalized things-link-ingestion-processed; do
  if ! docker exec tc-redpanda rpk group describe "${group}" >> "${OUTPUT_DIR}/final-groups.txt"; then
    echo "[c3d-l1] 错误：无法读取最终 group ${group}" >&2
    RUN_FAILURE=1
    continue
  fi
  if [[ "$(group_lag "${group}" || true)" != "0" ]]; then
    echo "[c3d-l1] 错误：最终 group ${group} 未回到 0" >&2
    RUN_FAILURE=1
  fi
done

# PUBACK 清单在 lag 清零后再与数据库对账；提前查询会把尚在合法消费中的消息误判为事实丢失。
if ! python3 "${FIXTURE_SCRIPT}" verify --manifest "${OUTPUT_DIR}/property-report-manifest.log" \
  > "${OUTPUT_DIR}/fact-reconciliation.json"; then
  echo "[c3d-l1] 错误：属性事实对账失败；继续采集 DLQ/进程证据" >&2
  RUN_FAILURE=1
fi

: > "${OUTPUT_DIR}/dlq.txt"
for topic in tc.dlq tc.rule.dlq; do
  if ! high_watermark="$(docker exec tc-redpanda rpk topic describe "${topic}" -p \
    | awk 'NR > 1 {sum += $6} END {print sum + 0}')"; then
    echo "[c3d-l1] 错误：无法读取 ${topic} 高水位" >&2
    RUN_FAILURE=1
    continue
  fi
  printf '%s=%s\n' "${topic}" "${high_watermark}" >> "${OUTPUT_DIR}/dlq.txt"
  if [[ "${high_watermark}" != "0" ]]; then RUN_FAILURE=1; fi
done

if ! kill -0 "${BACKEND_PID}"; then RUN_FAILURE=1; fi
for container in tc-postgres tc-redis tc-redpanda tc-emqx tc-minio; do
  if [[ "$(docker inspect --format '{{.RestartCount}}' "${container}" 2>/dev/null || echo MISSING)" != "0" ]]; then
    RUN_FAILURE=1
  fi
done
if [[ "${RUN_FAILURE}" == "0" ]]; then
  echo "[c3d-l1] PASS：发生器资格、命令闭环、事实对账、lag 回收、DLQ 与进程存活全部通过"
else
  echo "[c3d-l1] FAIL：发生器资格有效，但至少一项 SUT correctness 未通过" >&2
fi
exit "${RUN_FAILURE}"
