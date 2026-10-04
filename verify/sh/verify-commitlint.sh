#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"

run_commitlint() {
  verify_node tools/commitlint
  verify_install tools/commitlint
  # 平台提交检查独立安装，与可选的 Console 仓库脱钩。
  export NODE_PATH="$VERIFY_ROOT/tools/commitlint/node_modules"
  local cli
  cli="$(node -p "require.resolve('@commitlint/cli/cli.js', {paths: [process.env.NODE_PATH]})")"
  if [[ -n "$VERIFY_BASE_REF" ]]; then
    node "$cli" --config "$VERIFY_ROOT/commitlint.config.cjs" \
      --from "$VERIFY_BASE_REF" --to "$VERIFY_HEAD_REF" --verbose
  else
    node "$cli" --config "$VERIFY_ROOT/commitlint.config.cjs" --last --verbose
  fi
  # 未提交代码没有最终提交消息；可先用候选消息文件预检，默认仍检查实际提交范围。
  if [[ -n "${VERIFY_COMMIT_MESSAGE_FILE:-}" ]]; then
    node scripts/check-commit-message.cjs "$VERIFY_COMMIT_MESSAGE_FILE"
  fi
}
verify_run commitlint run_commitlint "$@"
