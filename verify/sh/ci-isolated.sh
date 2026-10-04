#!/usr/bin/env bash
# 供 E2E/L1 入口 source；宿主仅负责创建和清理本次拥有的 Runner 容器。
verify_isolated() {
  local job="$1" image name pnpm_version
  verify_need python3
  local snapshot_args=("$VERIFY_ROOT" "$VERIFY_RUN_DIR/source.tar" "$VERIFY_RUN_DIR/source.json")
  # 嵌套镜像与构建需额外余量；只创建自己的探测容器/匿名卷。
  verify_docker 26214400
  pnpm_version="$(python3 -c 'import json; print(json.load(open("things-link-client-contracts/package.json"))["packageManager"].split("@")[1])')"
  image="thingslink-local-ci:pnpm-$pnpm_version"
  # 每次检查构建缓存，Dockerfile/runner 变更不能继续使用旧镜像。
  docker build --build-arg "PNPM_VERSION=$pnpm_version" -t "$image" "$VERIFY_ROOT/verify/docker"
  python3 "$VERIFY_ROOT/verify/sh/ci-snapshot.py" "${snapshot_args[@]}"
  local source_sha
  source_sha="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceSha256"])' "$VERIFY_RUN_DIR/source.json")"
  # 仅收集本地历史，绝不把本地结果写回 GitHub 的正式性能基线。
  python3 - "$VERIFY_ROOT/logs/ci" "$VERIFY_RUN_DIR/history.tar" <<'PY'
from pathlib import Path
import io, json, sys, tarfile
root, output = map(Path, sys.argv[1:])
with tarfile.open(output, 'w') as archive:
    records = []
    for index, report in enumerate(sorted(root.glob('nightly-l1-*/evidence/things-link/target/c3d-l1/machine-report.json'))):
        archive.add(report, arcname=f'{index}/machine-report.json')
        records.append({'path': f'{index}/machine-report.json'})
    content = json.dumps({'status': 'PASS', 'scope': 'local-docker', 'reports': records}).encode()
    info = tarfile.TarInfo('history-index.json')
    info.size = len(content)
    archive.addfile(info, io.BytesIO(content))
PY
  name="thingslink-verify-$(basename "$VERIFY_RUN_DIR" | tr '[:upper:]' '[:lower:]')"
  mkdir -p "$VERIFY_RUN_DIR/evidence"
  # 创建成功后才取得清理所有权；命名冲突时不得删除别人的容器。
  VERIFY_CONTAINER_ID="$(docker create --privileged --init --name "$name" \
    --label thingslink.local-verification=true \
    --mount "type=bind,src=$VERIFY_RUN_DIR/source.tar,dst=/source.tar,readonly" \
    --mount "type=bind,src=$VERIFY_RUN_DIR/history.tar,dst=/history.tar,readonly" \
    --mount "type=bind,src=$VERIFY_RUN_DIR/evidence,dst=/evidence" \
    --volume /var/lib/docker \
    --env "LOCAL_CI_RUN_ID=$(date +%s)" --env "LOCAL_CI_SOURCE_SHA256=$source_sha" \
    --env "LOCAL_CI_SOURCE_HEAD=$(git rev-parse HEAD)" "$image" "$job")"
  # EXIT 在函数返回后触发，不能引用已销毁的 local 变量。
  verify_cleanup_runner() {
    local result=$?
    trap - EXIT INT TERM
    if ! docker rm -f -v "$VERIFY_CONTAINER_ID" >/dev/null 2>&1; then
      echo '[local-ci] 本次运行器清理失败，不能记为验证通过。' >&2
      result=1
    fi
    exit "$result"
  }
  trap verify_cleanup_runner EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  set +e
  docker start --attach "$VERIFY_CONTAINER_ID"
  local attach_result=$?
  set -e
  local result
  result="$(docker inspect --format '{{.State.ExitCode}}' "$VERIFY_CONTAINER_ID")"
  if [[ "$result" -eq 0 && "$attach_result" -ne 0 ]]; then result="$attach_result"; fi
  # start --attach 本身并不保证转发容器退出码，必须读取 State.ExitCode。
  return "$result"
}
