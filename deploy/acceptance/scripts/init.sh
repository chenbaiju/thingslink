#!/bin/sh
# Run only after all five dependency services are healthy. No shell tracing.
set -eu
if [ "$#" -ne 1 ]; then echo 'Usage: init.sh /absolute/private/.env' >&2; exit 2; fi
asset_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
env_file=$1
compose() { docker compose --env-file "$env_file" -f "$asset_dir/compose.yml" "$@"; }
# Assert isolation before any mutation.
[ "$(docker network inspect tc-acceptance-internal --format '{{.Internal}}')" = true ]
[ "$(docker network inspect tc-acceptance-internal --format '{{.Driver}}')" = bridge ]
compose exec -T postgres sh -ec 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /acceptance-init.sql' >/dev/null
count=0
while read -r topic partitions retention; do
  # Only swallow the precise already-exists error; all other failures stop init.
  if result=$(compose exec -T redpanda rpk topic create "$topic" -p "$partitions" -r 1 -c "retention.ms=$retention" 2>&1 </dev/null); then :
  else
    case "$result" in *TOPIC_ALREADY_EXISTS*|*'already exists'*) :;; *) printf '%s\n' "$result" >&2; exit 1;; esac
  fi
  # Existing partition counts must never be silently changed.
  actual=$(compose exec -T redpanda rpk topic describe "$topic" -p </dev/null | awk '$1 ~ /^[0-9]+$/ {n++} END {print n+0}')
  [ "$actual" = "$partitions" ] || { echo "Partition mismatch: $topic" >&2; exit 1; }
  compose exec -T redpanda rpk topic alter-config "$topic" --set "retention.ms=$retention" >/dev/null </dev/null
  count=$((count + 1))
 done < "$asset_dir/scripts/topics.tsv"
[ "$count" -eq 24 ] || { echo "Expected 24 verified topics, got $count" >&2; exit 1; }
compose --profile init run --rm minio-init
echo 'Database extensions, topic contracts and private buckets initialized.'
