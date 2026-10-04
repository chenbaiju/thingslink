#!/usr/bin/env bash
# backend.yml 的完整本地入口；保留旧 verify-backend-full.sh 的 Maven/TLS/日志行为。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"

run_backend() {
  verify_java
  verify_need python3
  python3 -m unittest scripts.tests.test_workflow_contracts
  python3 -m unittest scripts.tests.test_ci_resource_monitor
  python3 -m unittest scripts.tests.test_check_flyway_migrations
  python3 -m unittest scripts.tests.test_generate_openapi_contracts
  python3 -m unittest discover -s deploy/scripts -p test_saas_utc_midnight.py -v
  python3 scripts/check-flyway-migrations.py --base-ref "$VERIFY_BASE_REF" --head-ref "$VERIFY_HEAD_REF"
  verify_docker
  verify_minio
  cd things-link
  python3 ../scripts/ci-resource-monitor.py &
  monitor_pid=$!
  trap 'kill "$monitor_pid" 2>/dev/null || true; wait "$monitor_pid" 2>/dev/null || true' EXIT
  bash "$VERIFY_ROOT/verify/sh/verify-backend-full.sh"
}
verify_run backend run_backend "$@"
