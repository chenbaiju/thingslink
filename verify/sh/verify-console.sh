#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"

run_console() {
  verify_need python3
  verify_node things-link-console things-link-client-contracts
  python3 -m unittest scripts.tests.test_generate_openapi_contracts
  verify_install things-link-client-contracts
  verify_install things-link-console
  cd things-link-console
  # build 先生成自动导入声明，再执行类型、单测和静态检查；顺序与 CI 一致。
  pnpm build
  pnpm test
  pnpm api:check
  pnpm lint
  pnpm lint:stylelint:check
}
verify_run console run_console "$@"
