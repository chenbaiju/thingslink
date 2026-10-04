#!/usr/bin/env bash
set -euo pipefail
job="${1:?缺少任务名称}"
export CI=true
[[ "$job" == console-e2e || "$job" == nightly-l1 || "$job" == smoke ]] || exit 2
[[ ! -e /var/run/docker.sock ]] || { echo '[local-ci] 拒绝已有 Docker socket。' >&2; exit 2; }
export DOCKER_HOST=unix:///var/run/docker.sock
mkdir -p /work /evidence
# daemon/socket/容器/匿名数据卷均在这个一次性容器内；cleanup 不会触及宿主开发栈。
dockerd --host "$DOCKER_HOST" --label thingslink.local-ci=disposable > /evidence/docker-daemon.log 2>&1 &
daemon_pid=$!
cleanup() {
  local result=$?
  trap - EXIT INT TERM
  for directory in things-link/target/c3d-l1 things-link-console/logs \
    things-link-console/test-results things-link-console/playwright-report; do
    if [[ -d "/work/$directory" ]]; then
      mkdir -p "/evidence/$(dirname "$directory")"
      cp -a "/work/$directory" "/evidence/$directory" || result=1
    fi
  done
  kill "$daemon_pid" 2>/dev/null || true
  wait "$daemon_pid" 2>/dev/null || true
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ready=false
for ((i=0; i<60; i++)); do
  if docker info >/dev/null 2>&1; then ready=true; break; fi
  kill -0 "$daemon_pid" 2>/dev/null || break
  sleep 1
done
$ready || { echo '[local-ci] 独立 Docker daemon 未就绪，查看 docker-daemon.log。' >&2; exit 2; }
touch /.thingslink-local-ci
if [[ "$job" == smoke ]]; then
  java -version
  node --version
  pnpm --version
  docker compose version
  docker info --format 'Docker={{.ServerVersion}} Labels={{json .Labels}} Containers={{.Containers}}'
  exit 0
fi
tar -xf /source.tar -C /work
cd /work
source verify/sh/ci-common.sh
verify_minio
if [[ "$job" == console-e2e ]]; then
  verify_java
  verify_node things-link-console things-link-client-contracts things-link-webapp
  verify_install things-link-client-contracts
  verify_install things-link-console
  verify_install things-link-webapp
  export PLAYWRIGHT_BROWSERS_PATH=/work/things-link-console/.playwright-browsers
  export THINGS_LINK_DASHBOARD_CONSOLE_ALLOWED_ORIGINS=http://localhost:3006
  # 不继承本机的跳过/选场变量，始终执行 CI 的完整旅程与模拟器。
  unset SKIP_BACKEND E2E_SPEC E2E_MATRIX_SPECS E2E_BATCH_DIR E2E_RUN_DIR
  cd things-link-console
  pnpm exec playwright install --with-deps chromium
  bash scripts/run-e2e-tests.sh --with-simulator
else
  python3 -m py_compile \
    things-link/things-link-simulator/scripts/a4_qualification.py \
    things-link/things-link-simulator/scripts/l1_steady_probe.py \
    things-link/things-link-simulator/scripts/l1_machine_verdict.py
  python3 -m unittest things-link/things-link-simulator/scripts/test_a4_qualification.py
  python3 -m unittest discover -s things-link/things-link-simulator/scripts/tests -p 'test_*.py'
  bash -n things-link/things-link-simulator/scripts/run-c3d-l1.sh
  (cd things-link && ./mvnw -q -pl things-link-bootstrap,things-link-simulator -am package -DskipTests)
  export THINGS_LINK_LOCAL_CI=nightly-l1
  export LOCAL_CI_RUN_ID="${LOCAL_CI_RUN_ID:?}" LOCAL_CI_SOURCE_SHA256="${LOCAL_CI_SOURCE_SHA256:?}"
  set +e
  bash things-link/things-link-simulator/scripts/run-c3d-l1.sh
  run_exit=$?
  set -e
  outcome=success
  [[ "$run_exit" -eq 0 ]] || outcome=failure
  # 本地报告与 GitHub 报告分开：比较同一环境指纹的本地历史，不混合两种性能基线。
  mkdir -p things-link/target/c3d-l1-history
  if [[ -f /history.tar ]]; then tar -xf /history.tar -C things-link/target/c3d-l1-history; fi
  python3 things-link/things-link-simulator/scripts/l1_machine_verdict.py \
    --evidence-dir things-link/target/c3d-l1 \
    --history-dir things-link/target/c3d-l1-history --runner-outcome "$outcome" \
    --output things-link/target/c3d-l1/machine-report.json \
    --markdown-output things-link/target/c3d-l1/machine-report.md
  [[ "$run_exit" -eq 0 ]] || exit "$run_exit"
fi
