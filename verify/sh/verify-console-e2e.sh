#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"
source "$(dirname "${BASH_SOURCE[0]}")/ci-isolated.sh"
run_console_e2e() { verify_isolated console-e2e; }
verify_run console-e2e run_console_e2e "$@"
