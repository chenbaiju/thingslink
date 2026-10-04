#!/usr/bin/env bash
set -euo pipefail

# G1-C3b 的 L0 正确性入口。它只使用共享 PostgreSQL/EMQX 事实库，Kafka 与 Redis
# 使用本轮独立实例/DB，防止开发后端抢占 consumer group 或继承限流计数造成假绿。
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ROOT_DIR="$(cd "${BACKEND_DIR}/.." && pwd)"
DEPLOY_DIR="${ROOT_DIR}/deploy"
BACKEND_JAR="${BACKEND_DIR}/things-link-bootstrap/target/things-link-bootstrap-0.0.1-SNAPSHOT.jar"
# D-163 根因修复：可执行 JAR 带 exec 分类器（主构件为普通 JAR，供 bootstrap 测试编译）。
SIMULATOR_JAR="${BACKEND_DIR}/things-link-simulator/target/things-link-simulator-0.0.1-SNAPSHOT-exec.jar"
FIXTURE_SCRIPT="${SCRIPT_DIR}/l0_fixture.py"
QUALIFICATION_SCRIPT="${SCRIPT_DIR}/a4_qualification.py"

usage() {
  echo "用法: $0 --credentials-file <json> --output-dir <全新目录> [--run-id <id>]" >&2
}

CREDENTIALS_FILE=""
OUTPUT_DIR=""
RUN_ID="g1-c3b-l0-$(date -u +%Y%m%dT%H%M%SZ)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --credentials-file)
      CREDENTIALS_FILE="$2"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --run-id)
      RUN_ID="$2"
      shift 2
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

if [[ -z "${CREDENTIALS_FILE}" || -z "${OUTPUT_DIR}" ]]; then
  usage
  exit 2
fi
CREDENTIALS_FILE="$(cd "$(dirname "${CREDENTIALS_FILE}")" && pwd)/$(basename "${CREDENTIALS_FILE}")"
if [[ ! -f "${CREDENTIALS_FILE}" || ! -f "${BACKEND_JAR}" || ! -f "${SIMULATOR_JAR}" ]]; then
  echo "[c3b-l0] 错误：凭据或构建产物不存在；先构建 backend/simulator JAR" >&2
  exit 1
fi
PROJECT_KEY="$(python3 - "${CREDENTIALS_FILE}" <<'PY'
import json
import sys
from pathlib import Path

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
project_key = document.get("projectKey") if isinstance(document, dict) else None
if not isinstance(project_key, str) or not project_key.strip():
    raise SystemExit("凭据文件缺少非空 projectKey；禁止使用资格脚本默认占位项目")
print(project_key)
PY
)"
if [[ -e "${OUTPUT_DIR}" ]]; then
  echo "[c3b-l0] 错误：输出目录必须全新，禁止覆盖既有证据：${OUTPUT_DIR}" >&2
  exit 1
fi
mkdir -p "${OUTPUT_DIR}"
OUTPUT_DIR="$(cd "${OUTPUT_DIR}" && pwd)"

# 固定端口使 connector 与证据可审计；占用时直接失败，不误用其他后端或 broker。
BACKEND_PORT=18080
BROKER_PORT=29092
SIMULATOR_PORT=18190
REDIS_TEST_DB=14
BROKER_CONTAINER="tc-c3b-redpanda-$$"
BACKEND_PID=""
MONITOR_PID=""
EMQX_TOKEN=""
ORIGINAL_CONNECTOR=""
ORIGINAL_AUTHENTICATION=""
ORIGINAL_AUTHORIZATION=""

set -a
source "${DEPLOY_DIR}/.env"
set +a

port_is_free() {
  local port="$1"
  ! (echo > "/dev/tcp/127.0.0.1/${port}") >/dev/null 2>&1
}

for port in "${BACKEND_PORT}" "${BROKER_PORT}" "${SIMULATOR_PORT}"; do
  if ! port_is_free "${port}"; then
    echo "[c3b-l0] 错误：端口 ${port} 已被占用，拒绝误测旧进程" >&2
    exit 1
  fi
done

connector_payload() {
  python3 -c '
import json, sys
d = json.load(sys.stdin)
allowed = ("connect_timeout", "description", "enable", "enable_pipelining", "headers",
           "pool_size", "pool_type", "resource_opts", "ssl", "url")
print(json.dumps({key: d[key] for key in allowed if key in d}, separators=(",", ":")))
'
}

restore_connector() {
  if [[ -z "${EMQX_TOKEN}" || -z "${ORIGINAL_CONNECTOR}" ]]; then
    return 0
  fi
  local payload
  payload="$(printf '%s' "${ORIGINAL_CONNECTOR}" | connector_payload)"
  curl -fsS --max-time 10 -X PUT \
    "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/connectors/http:tc_raw_uplink" \
    -H "Authorization: Bearer ${EMQX_TOKEN}" -H 'Content-Type: application/json' \
    -d "${payload}" >/dev/null
}

restore_http_security_resource() {
  local endpoint="$1"
  local original="$2"
  local allowed="$3"
  if [[ -z "${EMQX_TOKEN}" || -z "${original}" ]]; then
    return 0
  fi
  local payload
  payload="$(printf '%s' "${original}" | python3 -c \
    'import json,sys; allowed=sys.argv[1].split(","); d=json.load(sys.stdin); print(json.dumps({k:d[k] for k in allowed if k in d},separators=(",",":")))' \
    "${allowed}")"
  curl -fsS --max-time 10 -X PUT \
    "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/${endpoint}" \
    -H "Authorization: Bearer ${EMQX_TOKEN}" -H 'Content-Type: application/json' \
    -d "${payload}" >/dev/null
}

cleanup() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ -n "${MONITOR_PID}" ]] && kill -0 "${MONITOR_PID}" 2>/dev/null; then
    kill "${MONITOR_PID}" 2>/dev/null || true
    wait "${MONITOR_PID}" 2>/dev/null || true
  fi
  if [[ -n "${BACKEND_PID}" ]] && kill -0 "${BACKEND_PID}" 2>/dev/null; then
    kill "${BACKEND_PID}" 2>/dev/null || true
    wait "${BACKEND_PID}" 2>/dev/null || true
  fi
  restore_connector || echo "[c3b-l0] 警告：EMQX connector 自动恢复失败，须人工核对" >&2
  restore_http_security_resource "authentication/password_based:http" "${ORIGINAL_AUTHENTICATION}" \
    "backend,body,connect_timeout,enable,enable_pipelining,headers,mechanism,method,pool_size,request_timeout,ssl,url" \
    || echo "[c3b-l0] 警告：EMQX authentication 自动恢复失败，须人工核对" >&2
  restore_http_security_resource "authorization/sources/http" "${ORIGINAL_AUTHORIZATION}" \
    "body,connect_timeout,enable,enable_pipelining,headers,method,pool_size,request_timeout,ssl,type,url" \
    || echo "[c3b-l0] 警告：EMQX authorization 自动恢复失败，须人工核对" >&2
  docker exec tc-redis sh -c \
    "REDISCLI_AUTH='${REDIS_PASSWORD}' redis-cli -n ${REDIS_TEST_DB} FLUSHDB" >/dev/null 2>&1 || true
  docker stop "${BROKER_CONTAINER}" >/dev/null 2>&1 || true
  exit "${rc}"
}
trap cleanup EXIT INT TERM

echo "[c3b-l0] 启动本轮独立 Redpanda"
docker run -d --rm --name "${BROKER_CONTAINER}" \
  -p "127.0.0.1:${BROKER_PORT}:${BROKER_PORT}" \
  "redpandadata/redpanda:${REDPANDA_TAG}" \
  redpanda start --overprovisioned --smp 1 --memory 1G --reserve-memory 0M --node-id 0 --check=false \
  --kafka-addr "0.0.0.0:${BROKER_PORT}" --advertise-kafka-addr "localhost:${BROKER_PORT}" >/dev/null
for _ in $(seq 1 60); do
  if docker exec "${BROKER_CONTAINER}" rpk cluster health 2>/dev/null | grep -q 'Healthy:.*true'; then
    break
  fi
  sleep 1
done
docker exec "${BROKER_CONTAINER}" rpk cluster health | tee "${OUTPUT_DIR}/redpanda-health.txt"

while read -r topic partitions; do
  docker exec "${BROKER_CONTAINER}" rpk topic create "${topic}" -p "${partitions}" -r 1 >/dev/null
done <<'TOPICS'
tc.device.uplink.raw 12
tc.device.uplink.normalized 12
tc.device.uplink.processed 12
tc.device.topo 12
tc.device.batch 12
tc.device.topo.reply 12
tc.device.config 12
tc.device.config.reply 12
tc.device.modbus.request 12
tc.device.modbus.response 12
tc.device.downlink 12
tc.device.command.terminal 12
tc.device.realtime 12
tc.device.lifecycle 6
tc.domain.event 6
tc.notification 6
tc.retry 6
tc.rule.retry.1m 6
tc.rule.retry.5m 6
tc.rule.notification 6
tc.rule.dlq 3
tc.dlq 3
TOPICS
docker exec "${BROKER_CONTAINER}" rpk topic list > "${OUTPUT_DIR}/topics.txt"

# DB 14 是此入口的固定测试 DB；严格检查 FLUSHDB 与 DBSIZE，绝不接受 DB 0 覆盖参数。
FLUSH_RESULT="$(docker exec -e "REDISCLI_AUTH=${REDIS_PASSWORD}" tc-redis \
  redis-cli -n "${REDIS_TEST_DB}" FLUSHDB)"
[[ "${FLUSH_RESULT}" == "OK" ]]
DB_SIZE="$(docker exec -e "REDISCLI_AUTH=${REDIS_PASSWORD}" tc-redis \
  redis-cli -n "${REDIS_TEST_DB}" DBSIZE)"
[[ "${DB_SIZE}" == "0" ]]

echo "[c3b-l0] 启动本轮后端"
SERVER_PORT="${BACKEND_PORT}" KAFKA_BOOTSTRAP_SERVERS="localhost:${BROKER_PORT}" \
SPRING_DATA_REDIS_DATABASE="${REDIS_TEST_DB}" java -jar "${BACKEND_JAR}" \
  > "${OUTPUT_DIR}/backend.log" 2>&1 &
BACKEND_PID=$!
for _ in $(seq 1 90); do
  if ! kill -0 "${BACKEND_PID}" 2>/dev/null; then
    tail -100 "${OUTPUT_DIR}/backend.log" >&2
    exit 1
  fi
  if curl -fsS --max-time 3 "http://127.0.0.1:${BACKEND_PORT}/actuator/health" \
    | grep -q '"status":"UP"'; then
    break
  fi
  sleep 1
done
curl -fsS --max-time 3 "http://127.0.0.1:${BACKEND_PORT}/actuator/health" \
  | tee "${OUTPUT_DIR}/backend-health.json"

# EMQX 5.8 的规则实际引用 http action/connector；修改 legacy webhook bridge 不会改变活动资源。
# authn/authz 也必须指向本轮后端，否则一轮测试会混用共享 8080 与本轮 18080 两套进程/Redis 状态。
EMQX_TOKEN="$(curl -fsS --max-time 5 -X POST \
  "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"${EMQX_DASHBOARD_USER}\",\"password\":\"${EMQX_DASHBOARD_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
ORIGINAL_AUTHENTICATION="$(curl -fsS --max-time 5 \
  "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/authentication/password_based:http" \
  -H "Authorization: Bearer ${EMQX_TOKEN}")"
ORIGINAL_AUTHORIZATION="$(curl -fsS --max-time 5 \
  "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/authorization/sources/http" \
  -H "Authorization: Bearer ${EMQX_TOKEN}")"
ORIGINAL_CONNECTOR="$(curl -fsS --max-time 5 \
  "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/connectors/http:tc_raw_uplink" \
  -H "Authorization: Bearer ${EMQX_TOKEN}")"
for specification in \
  "authentication/password_based:http|${ORIGINAL_AUTHENTICATION}|backend,body,connect_timeout,enable,enable_pipelining,headers,mechanism,method,pool_size,request_timeout,ssl,url|http://host.docker.internal:${BACKEND_PORT}/api/v1/emqx/auth" \
  "authorization/sources/http|${ORIGINAL_AUTHORIZATION}|body,connect_timeout,enable,enable_pipelining,headers,method,pool_size,request_timeout,ssl,type,url|http://host.docker.internal:${BACKEND_PORT}/api/v1/emqx/acl"; do
  IFS='|' read -r endpoint original allowed target_url <<< "${specification}"
  payload="$(printf '%s' "${original}" | python3 -c \
    'import json,sys; allowed=sys.argv[1].split(","); d=json.load(sys.stdin); out={k:d[k] for k in allowed if k in d}; out["url"]=sys.argv[2]; print(json.dumps(out,separators=(",",":")))' \
    "${allowed}" "${target_url}")"
  curl -fsS --max-time 10 -X PUT \
    "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/${endpoint}" \
    -H "Authorization: Bearer ${EMQX_TOKEN}" -H 'Content-Type: application/json' \
    -d "${payload}" >/dev/null
  actual_url="$(curl -fsS --max-time 5 \
    "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/${endpoint}" \
    -H "Authorization: Bearer ${EMQX_TOKEN}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')"
  [[ "${actual_url}" == "${target_url}" ]]
done
UPDATED_CONNECTOR="$(printf '%s' "${ORIGINAL_CONNECTOR}" | python3 -c '
import json, sys
d = json.load(sys.stdin)
allowed = ("connect_timeout", "description", "enable", "enable_pipelining", "headers",
           "pool_size", "pool_type", "resource_opts", "ssl", "url")
out = {key: d[key] for key in allowed if key in d}
out["url"] = "http://host.docker.internal:18080/api/v1/emqx/events/message-published"
print(json.dumps(out, separators=(",", ":")))
')"
curl -fsS --max-time 10 -X PUT \
  "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/connectors/http:tc_raw_uplink" \
  -H "Authorization: Bearer ${EMQX_TOKEN}" -H 'Content-Type: application/json' \
  -d "${UPDATED_CONNECTOR}" >/dev/null
ACTIVE_URL="$(curl -fsS --max-time 5 \
  "http://127.0.0.1:${EMQX_DASHBOARD_PORT}/api/v5/connectors/http:tc_raw_uplink" \
  -H "Authorization: Bearer ${EMQX_TOKEN}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')"
[[ "${ACTIVE_URL}" == "http://host.docker.internal:${BACKEND_PORT}/api/v1/emqx/events/message-published" ]]
printf '%s\n' "${ACTIVE_URL}" > "${OUTPUT_DIR}/emqx-connector-url.txt"
printf '%s\n' \
  "http://host.docker.internal:${BACKEND_PORT}/api/v1/emqx/auth" \
  "http://host.docker.internal:${BACKEND_PORT}/api/v1/emqx/acl" \
  > "${OUTPUT_DIR}/emqx-security-urls.txt"

group_lag() {
  local group="$1"
  local description
  if ! description="$(docker exec "${BROKER_CONTAINER}" rpk group describe "${group}" 2>/dev/null)"; then
    return 1
  fi
  printf '%s\n' "${description}" | awk '$1 == "TOTAL-LAG" { print $2 }'
}

# MISSING 与零严格区分：组不存在表示消费链未建立，不能以 lag=0 假绿。
(
  while true; do
    now="$(date -u +%FT%TZ)"
    for group in things-link-ingestion-raw things-link-ingestion-normalized things-link-ingestion-processed; do
      if lag="$(group_lag "${group}")" && [[ "${lag}" =~ ^[0-9]+$ ]]; then
        printf '%s\t%s\t%s\n' "${now}" "${group}" "${lag}"
      else
        printf '%s\t%s\tMISSING\n' "${now}" "${group}"
      fi
    done
    sleep 5
  done
) > "${OUTPUT_DIR}/lag.tsv" &
MONITOR_PID=$!

echo "[c3b-l0] 执行 100×10×1/min，稳态 5 分钟"
python3 "${QUALIFICATION_SCRIPT}" \
  --jar "${SIMULATOR_JAR}" --broker-uri "tcp://127.0.0.1:${EMQX_MQTT_PORT}" \
  --broker-fingerprint "emqx/${EMQX_TAG} deploy-base-hocon qos1 auth-http shared-local-stack" \
  --project-key "${PROJECT_KEY}" --credentials-file "${CREDENTIALS_FILE}" \
  --device-count 100 --shard-size 100 \
  --interval-seconds 60 --properties-per-report 10 --steady-seconds 300 \
  --base-port "${SIMULATOR_PORT}" --output-dir "${OUTPUT_DIR}/qualification" --run-id "${RUN_ID}" \
  --rss-budget-bytes 1073741824 --thread-budget 1000 \
  --host-rss-budget-bytes 1073741824 --host-thread-budget 1000 \
  --sut-time-command "docker exec tc-postgres date +%s%3N" \
  | tee "${OUTPUT_DIR}/qualification.log"
kill "${MONITOR_PID}" 2>/dev/null || true
wait "${MONITOR_PID}" 2>/dev/null || true
MONITOR_PID=""

# 最长两分钟等待峰值积压完全回收；三个组都必须真实存在。
for _ in $(seq 1 60); do
  all_zero=1
  for group in things-link-ingestion-raw things-link-ingestion-normalized things-link-ingestion-processed; do
    if ! lag="$(group_lag "${group}")" || [[ "${lag}" != "0" ]]; then
      all_zero=0
      break
    fi
  done
  [[ "${all_zero}" == "1" ]] && break
  sleep 2
done
: > "${OUTPUT_DIR}/final-groups.txt"
for group in things-link-ingestion-raw things-link-ingestion-normalized things-link-ingestion-processed; do
  description="$(docker exec "${BROKER_CONTAINER}" rpk group describe "${group}")"
  printf '%s\n' "${description}" >> "${OUTPUT_DIR}/final-groups.txt"
  lag="$(printf '%s\n' "${description}" | awk '$1 == "TOTAL-LAG" { print $2 }')"
  [[ "${lag}" == "0" ]]
done

DLQ_CLEAN=1
for topic in tc.dlq tc.rule.dlq; do
  high_watermark="$(docker exec "${BROKER_CONTAINER}" rpk topic describe "${topic}" -p \
    | awk 'NR > 1 { sum += $6 } END { print sum + 0 }')"
  printf '%s.high_watermark=%s\n' "${topic}" "${high_watermark}" | tee -a "${OUTPUT_DIR}/dlq.txt"
  if [[ "${high_watermark}" != "0" ]]; then
    DLQ_CLEAN=0
    docker exec "${BROKER_CONTAINER}" rpk topic consume "${topic}" -n 1 -o start --format json \
      > "${OUTPUT_DIR}/${topic}.sample.json" || true
  fi
done
if [[ "${DLQ_CLEAN}" != "1" ]]; then
  echo "[c3b-l0] 错误：DLQ 非空，样本已归档" >&2
  exit 1
fi

python3 "${FIXTURE_SCRIPT}" verify \
  --manifest "${OUTPUT_DIR}/qualification/manifests/${RUN_ID}/shard-000/property_report.log" \
  | tee "${OUTPUT_DIR}/fact-reconciliation.json"

curl -fsS --max-time 5 "http://127.0.0.1:${BACKEND_PORT}/actuator/prometheus" \
  > "${OUTPUT_DIR}/prometheus.txt"
HTTP_5XX="$(python3 - "${OUTPUT_DIR}/prometheus.txt" <<'PY'
import re
import sys
from pathlib import Path

total = 0.0
for line in Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    if line.startswith("http_server_requests_seconds_count{") and re.search(r'status="5\d\d"', line):
        total += float(line.rsplit(" ", 1)[1])
print(int(total))
PY
)"
printf 'backend_http_5xx=%s\n' "${HTTP_5XX}" | tee "${OUTPUT_DIR}/http-errors.txt"
[[ "${HTTP_5XX}" == "0" ]]

echo "[c3b-l0] PASS：资格、事实对账、lag 回收、DLQ 与 HTTP 5xx 全部通过"
