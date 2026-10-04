#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"

run_client_contracts() {
  verify_java
  verify_node things-link-client-contracts
  verify_install things-link-client-contracts
  cd things-link-client-contracts
  pnpm verify
  pnpm pack --dry-run
  cd "$VERIFY_ROOT/things-link"
  ./mvnw -B -pl things-link-dashboard -am clean test
}
verify_run client-contracts run_client_contracts "$@"
