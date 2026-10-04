#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"
source "$(dirname "${BASH_SOURCE[0]}")/ci-isolated.sh"
run_nightly_l1() { verify_isolated nightly-l1; }
verify_run nightly-l1 run_nightly_l1 "$@"
