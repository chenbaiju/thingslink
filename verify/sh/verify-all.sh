#!/usr/bin/env bash
# 按顺序运行，避免共享 Maven target、Docker、端口与浏览器产物相互争用。
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/ci-common.sh"
jobs=(commitlint client-contracts console platform webapp backend console-e2e nightly-l1)

if [[ "${1:-}" == --help || "${1:-}" == --list ]]; then
  echo '用法：bash verify/sh/verify-all.sh [工作流名 ...]'
  echo '默认运行八项代码检查。Console 使用同一平台候选。'
  echo '不操作 Git 提交、钩子或推送；旧 backend-full 仍是单独的 Maven 全量入口。'
  printf '  %s\n' "${jobs[@]}"
  exit 0
fi
if [[ "$#" -gt 0 ]]; then
  selected=()
  for job in "$@"; do
    found=false
    for available in "${jobs[@]}"; do [[ "$job" != "$available" ]] || found=true; done
    $found || { echo "[verify] 未知或不适用于当前仓库的工作流：$job" >&2; exit 2; }
    selected+=("$job")
  done
  jobs=("${selected[@]}")
fi
mkdir -p "$VERIFY_ROOT/logs/ci"
summary_dir="$(mktemp -d "$VERIFY_ROOT/logs/ci/all-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")"
summary="$summary_dir/results.tsv"
printf 'workflow\texit_code\n' > "$summary"
failed=0
for job in "${jobs[@]}"; do
  # 外部 bash 的 errexit 不受调用方 if 影响；单项失败仍检查其他项。
  if bash "$SCRIPT_DIR/verify-$job.sh"; then code=0; else code=$?; fi
  printf '%s\t%s\n' "$job" "$code" >> "$summary"
  [[ "$code" -eq 0 ]] || failed=1
  if [[ "$code" -eq 130 || "$code" -eq 143 ]]; then exit "$code"; fi
done
cat "$summary"
echo "[verify] 汇总：${summary}；退出码：${failed}（0=全部通过，1=存在失败）"
exit "$failed"
