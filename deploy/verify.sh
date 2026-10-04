#!/usr/bin/env bash
# S0 依赖栈验收脚本。
#
# 目的：验证每个组件「真的能用」，而不是「容器在跑」。
# 容器 running 但不可用是本地开发最常见的浪费时间来源 ——
# Postgres 端口开了但扩展没装、Redpanda 起来了但 advertised 地址不可达、
# MinIO 活着但桶没建，这些都通过 docker ps 看不出来。
#
# 用法： cd deploy && make verify

# 注意：凡是变量后面紧跟中文字符的地方，一律写成 ${var} 而不是 $var。
# bash 3.2（macOS 自带）在 UTF-8 locale 下会把全角字符的首字节吞进变量名，
# 报出 `ts_ver?: unbound variable` 这种指向不明的错误 —— 而在 LANG=C 下
# 完全正常，因此极易在某些环境「测试通过」却在另一些环境崩溃。
# 花括号显式划定变量边界，消除这一整类问题。

set -uo pipefail
cd "$(dirname "$0")"

# Git Bash 会把容器内的 /opt/... 误改写为 Windows 安装目录，导致 EMQX 自检假失败；
# 关闭 MSYS 参数路径转换对 Linux/macOS 无副作用，并保留传给容器的原始绝对路径。
export MSYS_NO_PATHCONV=1

if [ ! -f .env ]; then
  echo "缺少 deploy/.env，先执行 make init"
  exit 1
fi
set -a; . ./.env; set +a

COMPOSE="docker compose --env-file .env"
PASS=0
FAIL=0

ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$1"; [ $# -gt 1 ] && printf "      %s\n" "$2"; FAIL=$((FAIL+1)); }
head_() { printf "\n\033[1m%s\033[0m\n" "$1"; }

# ---------------------------------------------------------------- PostgreSQL
head_ "PostgreSQL / TimescaleDB"

PSQL="$COMPOSE exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAX"

if out=$($PSQL -c "SELECT 1" 2>&1) && [ "$out" = "1" ]; then
  ok "连接正常"
else
  bad "无法连接" "$out"
fi

ts_ver=$($PSQL -c "SELECT extversion FROM pg_extension WHERE extname='timescaledb'" 2>/dev/null | tr -d '[:space:]')
if [ -n "$ts_ver" ]; then
  ok "TimescaleDB 扩展已装（${ts_ver}）"
else
  bad "TimescaleDB 扩展缺失" "没有它，device_property_point 无法转 hypertable"
fi

# 真正建一次 hypertable。只查扩展存在是不够的 —— 扩展装了但 preload 没配时，
# create_hypertable 会在这里失败，而这个失败在 S3 才暴露的话代价大得多。
smoke=$($PSQL -c "
  CREATE TABLE IF NOT EXISTS _tc_smoke(ts timestamptz NOT NULL, v double precision);
  SELECT create_hypertable('_tc_smoke','ts',if_not_exists=>true);
  INSERT INTO _tc_smoke VALUES (now(), 1.0);
  SELECT count(*) FROM _tc_smoke;
  DROP TABLE _tc_smoke;
" 2>&1)
if echo "$smoke" | grep -q "^1$"; then
  ok "hypertable 创建 / 写入 / 删除 全流程可用"
else
  bad "hypertable 冒烟测试失败" "$(echo "$smoke" | tail -2 | tr '\n' ' ')"
fi

tz=$($PSQL -c "SHOW timezone" 2>/dev/null | tr -d '[:space:]')
[ "$tz" = "UTC" ] && ok "库时区为 UTC" || bad "库时区是 ${tz}，应为 UTC" "架构文档 9 节：统一 UTC 存储"

if $COMPOSE exec -T postgres psql -U "$POSTGRES_USER" -d thingslink_test -tAX -c "SELECT 1" >/dev/null 2>&1; then
  ok "测试库 thingslink_test 存在"
else
  bad "测试库 thingslink_test 缺失"
fi

for ext in pgcrypto pg_trgm; do
  if [ -n "$($PSQL -c "SELECT 1 FROM pg_extension WHERE extname='$ext'" 2>/dev/null | tr -d '[:space:]')" ]; then
    ok "扩展 $ext 已装"
  else
    bad "扩展 $ext 缺失"
  fi
done

# ---------------------------------------------------------------- Redis
head_ "Redis"

R="$COMPOSE exec -T redis redis-cli -a $REDIS_PASSWORD --no-auth-warning"

if [ "$($R ping 2>/dev/null | tr -d '[:space:]')" = "PONG" ]; then
  ok "连接正常（密码校验通过）"
else
  bad "无法连接或密码错误"
fi

$R set _tc_smoke hello EX 10 >/dev/null 2>&1
if [ "$($R get _tc_smoke 2>/dev/null | tr -d '[:space:]')" = "hello" ]; then
  ok "读写正常"
  $R del _tc_smoke >/dev/null 2>&1
else
  bad "读写失败"
fi

pol=$($R config get maxmemory-policy 2>/dev/null | tail -1 | tr -d '[:space:]')
if [ "$pol" = "noeviction" ]; then
  ok "淘汰策略为 noeviction（限流键不会被静默淘汰）"
else
  bad "淘汰策略是 ${pol}，应为 noeviction" "LRU 会淘汰限流令牌桶，导致限流静默失效"
fi

# ---------------------------------------------------------------- Redpanda
head_ "Redpanda（Kafka 协议）"

# 容器内的 rpk 直接读本地配置，不要传 --brokers ——
# rpk v24 已移除该全局 flag，传了会以「unknown flag」失败，
# 看起来却像是「集群不健康」，非常有迷惑性。
RPK="$COMPOSE exec -T redpanda rpk"

if $RPK cluster health 2>/dev/null | grep -q "Healthy:.*true"; then
  ok "集群健康"
else
  bad "集群不健康"
fi

topics=$($RPK topic list 2>/dev/null)
for t in tc.device.uplink.raw tc.device.uplink.normalized tc.device.uplink.processed tc.device.topo tc.device.batch tc.device.topo.reply tc.device.config tc.device.config.reply tc.device.modbus.request tc.device.modbus.response tc.device.downlink tc.device.command.terminal tc.device.realtime tc.domain.event tc.notification tc.rule.automation.property.accepted tc.integration.webhook.source tc.rule.retry.1m tc.rule.retry.5m tc.rule.dlq tc.dlq; do
  if echo "$topics" | grep -q "^$t "; then
    ok "主题 $t 已创建"
  else
    bad "主题 $t 缺失" "重跑 make bootstrap"
  fi
done

# 分区数是决策 #7，事后不可安全调整（架构文档 5.1），必须在 S0 就验证
parts=$(echo "$topics" | awk '$1=="tc.device.uplink.raw"{print $2}')
if [ "$parts" = "12" ]; then
  ok "tc.device.uplink.raw 分区数为 12"
else
  bad "tc.device.uplink.raw 分区数为 ${parts:-?}，应为 12" "扩分区会打断历史顺序，只能停写迁移"
fi

# S8-2C 的 processed 是 normalized 的可靠续接段；两者分区数不同会在规则前后改变 deviceId 映射。
processed_parts=$(echo "$topics" | awk '$1=="tc.device.uplink.processed"{print $2}')
if [ "$processed_parts" = "12" ]; then
  ok "tc.device.uplink.processed 分区数为 12"
else
  bad "tc.device.uplink.processed 分区数为 ${processed_parts:-?}，应为 12" "规则续接必须保持设备内顺序"
fi

# S4-4 的实时增量同样按 deviceId 分区。把部署声明纳入运行态门禁，避免旧环境继续以
# 单分区运行而掩盖吞吐瓶颈；实时事件允许丢失，断线后由 REST 事实补拉，不承担历史顺序。
realtime_parts=$(echo "$topics" | awk '$1=="tc.device.realtime"{print $2}')
if [ "$realtime_parts" = "12" ]; then
  ok "tc.device.realtime 分区数为 12"
else
  bad "tc.device.realtime 分区数为 ${realtime_parts:-?}，应为 12" "按部署声明非破坏性扩至 12 分区"
fi
notification_parts=$(echo "$topics" | awk '$1=="tc.notification"{print $2}')
if [[ "$notification_parts" == "6" ]]; then
  ok "tc.notification 分区数为 6"
else
  bad "tc.notification 分区数为 ${notification_parts:-?}，应为 6" "按部署声明非破坏性扩至 6 分区"
fi

# 生产/消费往返
$RPK topic create _tc_smoke -p 1 -r 1 >/dev/null 2>&1
echo "hello" | $RPK topic produce _tc_smoke >/dev/null 2>&1
if $RPK topic consume _tc_smoke -n 1 -o start 2>/dev/null | grep -q "hello"; then
  ok "生产 / 消费往返正常"
else
  bad "生产消费往返失败"
fi
$RPK topic delete _tc_smoke >/dev/null 2>&1

# 从宿主机视角验证 external listener。容器内能连不代表宿主机上的 Spring Boot 能连 ——
# 这正是双 listener 配错时的典型症状（表现为超时而非拒绝，极易误判成网络问题）。
#
# 两点注意：
#  - 镜像的 ENTRYPOINT 已经是 rpk，参数里不能再写一遍 rpk，否则报 unknown command "rpk"
#  - rpk v24 用 -X brokers=，不是 --brokers
info=$(docker run --rm --network host redpandadata/redpanda:"$REDPANDA_TAG" \
         -X brokers="localhost:$REDPANDA_PORT" cluster info 2>&1)
if echo "$info" | grep -qE "localhost[[:space:]]+$REDPANDA_PORT"; then
  ok "宿主机可连 localhost:${REDPANDA_PORT}，且 broker 回告的地址就是它（external listener 正确）"
elif echo "$info" | grep -q "BROKERS"; then
  # 能连上但 advertised 地址不对 —— 客户端会拿到一个自己解析不了的地址
  bad "broker 回告的地址不是 localhost:$REDPANDA_PORT" "$(echo "$info" | grep -A3 BROKERS | tail -2 | tr '\n' ' ')"
else
  bad "宿主机无法连 localhost:$REDPANDA_PORT" "$(echo "$info" | grep -v '^+' | tail -2 | tr '\n' ' ')"
fi

# ---------------------------------------------------------------- EMQX
head_ "EMQX"

if $COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl status 2>/dev/null | grep -qi "is started"; then
  ok "节点已启动"
else
  bad "节点未就绪"
fi

# ADR 0048 把 durable 候选固定为 6.2.3；只看容器标签会漏掉本机旧容器没有重建的漂移。
broker_info=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl broker 2>&1)
if echo "$broker_info" | grep -q "6.2.3"; then
  ok "EMQX 运行版本为 6.2.3"
else
  bad "EMQX 运行版本不是 6.2.3" "$(echo "$broker_info" | head -3 | tr '\n' ' ')"
fi

# 本机 Compose 只允许 Community License 的单节点内部开发用途；该检查有意不宣称生产可用。
license_info=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl license info 2>&1)
if echo "$license_info" | grep -qE '^type[[:space:]]*:[[:space:]]*community$' \
    && echo "$license_info" | grep -qE '^deployment[[:space:]]*:[[:space:]]*Development$' \
    && echo "$license_info" | grep -qE '^expiry[[:space:]]*:[[:space:]]*false$'; then
  ok "Community License 仅按单节点内部开发用途验收"
else
  bad "本机 EMQX License 状态不符合冻结的内部开发用途"
fi

if curl -fsS "http://localhost:$EMQX_DASHBOARD_PORT/status" >/dev/null 2>&1; then
  ok "Dashboard 可访问（http://localhost:${EMQX_DASHBOARD_PORT}）"
else
  bad "Dashboard 不可访问"
fi

# S3 已关闭匿名接入。基础设施自检没有业务设备凭据，因此这里只验证 fail-closed；
# 带真实凭据的认证、ACL 和上行闭环由 S3 验收步骤完成，不能再用匿名 smoke topic 绕过安全基线。
mq=$(docker run --rm --network host eclipse-mosquitto:2 \
  mosquitto_pub -h localhost -p "$EMQX_MQTT_PORT" -t tc/smoke -m hello 2>&1 || true)
if echo "$mq" | grep -qiE "not authorised|not authorized|connection refused"; then
  ok "MQTT 匿名连接已拒绝（端口 ${EMQX_MQTT_PORT}）"
else
  bad "MQTT 匿名连接未按 S3 安全基线拒绝" "$(echo "$mq" | tail -1)"
fi

# 三条规则必须随 base.hocon 重建后自动出现；只在 Dashboard 手工创建会在 make reset 后静默丢失。
rules=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl rules list 2>&1)
for rule in tc_durable_uplink tc_client_connected tc_client_disconnected; do
  if echo "$rules" | grep -q "id=$rule.*enabled=true"; then
    ok "EMQX 规则 $rule 已固化并启用"
  else
    bad "EMQX 规则 $rule 缺失或未启用"
  fi
done

# S3.5-3：规则存在还不够，若 SQL 回退成 property/report 精确匹配，事件等新类型会在 Broker 侧静默消失。
raw_uplink_rule=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl rules show tc_durable_uplink 2>&1)
if echo "$raw_uplink_rule" | grep -Fq 'tc/v1/+/+/up/#'; then
  ok "EMQX 原始上行规则使用 tc/v1/+/+/up/# 单条通配"
else
  bad "EMQX 原始上行规则未使用 up/# 通配" "$(echo "$raw_uplink_rule" | tail -2 | tr '\n' ' ')"
fi

# S7-5：规则文件能解析不等于告警能触发。promtool 矩阵为每条告警注入对应人工样本，
# 覆盖延迟 histogram、失败率、连接突降/风暴、Hikari、配额与缓存异常，避免新增规则却遗漏触发证据。
prometheus_dir=$(cd "$(dirname "$0")/prometheus" && pwd)
if docker run --rm --entrypoint /bin/promtool -v "$prometheus_dir:/etc/prometheus:ro" \
     "prom/prometheus:${PROMETHEUS_TAG}" \
     test rules /etc/prometheus/tests/data-plane-alerts.test.yml >/dev/null 2>&1; then
  ok "Prometheus 数据面告警规则可解析，且完整告警矩阵均可被人工样本触发"
else
  bad "Prometheus 数据面告警规则或触发验收失败" \
      "运行 promtool test rules /etc/prometheus/tests/data-plane-alerts.test.yml 查看详情"
fi

# S4-3：认证、鉴权和生命周期 connector 三处配置必须都带共享密钥（架构文档 8.3）。
#
# 为什么值得单独一项：漏掉任意一个，那个端点就退回无鉴权入口，而**症状是零**——
# 回调照常工作，功能测试全绿，只有被伪造时才会知道。这里数的是「带 token 的回调数」，
# 不是「配置里有没有出现过 token」，后者写一处也能过。
#
# authn / authz 各一个；connected/disconnected 两个 action 共用一个 connector，header 只在 connector 保存一次。
authn_token=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl conf show authentication 2>&1 \
  | grep -c "X-Broker-Callback-Token" || true)
authz_token=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl conf show authorization 2>&1 \
  | grep -c "X-Broker-Callback-Token" || true)
conn_token=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl conf show connectors 2>&1 \
  | grep -c "X-Broker-Callback-Token" || true)
callback_token_total=$((authn_token + authz_token + conn_token))
if [ "$callback_token_total" -eq 3 ]; then
  ok "认证、鉴权和生命周期 connector 均携带 X-Broker-Callback-Token"
else
  bad "携带回调密钥的配置数为 ${callback_token_total}，应为 3" \
      "authn=${authn_token} authz=${authz_token} connectors=${conn_token}"
fi

# EMQX 6 已删除 Bridges V1。ctl 对 key_not_found 仍可能返回 0，所以按结构化诊断文本判断缺失。
legacy_bridges=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl conf show bridges 2>&1)
if echo "$legacy_bridges" | grep -q "key_not_found"; then
  ok "legacy bridges 配置根不存在"
else
  bad "检测到 legacy bridges 配置根，EMQX 6 可能忽略生命周期回调"
fi

actions=$($COMPOSE exec -T emqx /opt/emqx/bin/emqx ctl conf show actions 2>&1)
for action in tc_client_connected tc_client_disconnected; do
  if echo "$actions" | grep -q "$action"; then
    ok "EMQX HTTP action $action 已固化"
  else
    bad "EMQX HTTP action $action 缺失"
  fi
done

# ---------------------------------------------------------------- MinIO
head_ "MinIO"

if curl -fsS "http://localhost:$MINIO_PORT/minio/health/live" >/dev/null 2>&1; then
  ok "服务存活（S3 API 端口 ${MINIO_PORT}）"
else
  bad "服务不可访问"
fi

# 用 minio 容器自带的 mc，不另起 minio/mc 容器 ——
# minio/mc 镜像的 ENTRYPOINT 就是 mc，`docker run minio/mc sh -c ...` 会被解析成
# `mc sh -c ...` 而失败，表现为「桶缺失」，但桶其实好好地在那儿。
$COMPOSE exec -T minio mc alias set tc http://localhost:9000 \
  "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1
buckets=$($COMPOSE exec -T minio mc ls tc 2>/dev/null)
for b in firmware device-image export backup; do
  if echo "$buckets" | grep -q "$b"; then
    ok "桶 $b 已创建"
  else
    bad "桶 $b 缺失" "重跑 make bootstrap"
  fi
done

# ---------------------------------------------------------------- 汇总
head_ "结果"
printf "  通过 %d 项" "$PASS"
[ "$FAIL" -gt 0 ] && printf "，\033[31m失败 %d 项\033[0m" "$FAIL"
printf "\n\n"

if [ "$FAIL" -eq 0 ]; then
  printf "\033[32m依赖栈与 MQTT 接入安全基线验收通过。\033[0m\n\n"
  exit 0
else
  printf "\033[31m有 %d 项未通过，先修复再继续。\033[0m\n\n" "$FAIL"
  exit 1
fi
