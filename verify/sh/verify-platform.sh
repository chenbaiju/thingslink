#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"

run_platform() {
  verify_node things-link-platform
  verify_install things-link-platform
  cd things-link-platform
  pnpm test:production-config
  SITE_URL='' PUBLIC_CONSOLE_URL='' pnpm build
  pnpm verify:deployment
  pnpm verify:accessibility
  pnpm verify:performance
}
verify_run platform run_platform "$@"
