#!/usr/bin/env bash
# Offline Agent contract tests; no model provider or ThingsLink service needed.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../" && pwd)"
python_bin="${AGENT_PYTHON:-$(command -v python3)}"
command -v uv >/dev/null || { echo '需要 uv 0.12.23，参见 Agent 知识库。' >&2; exit 2; }
cd "$root/things-link-agent"
uv sync --locked --group test --python "$python_bin"
uv run --locked --group test pytest tests/agent/ -v
