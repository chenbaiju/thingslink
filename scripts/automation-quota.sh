#!/usr/bin/env bash
# ADR0153：使用已构建后端JAR的独立入口，不启动后端或自动迁移。
set -euo pipefail
: "${TC_BACKEND_JAR:?请设置已构建后端JAR的绝对路径}"
if [[ "$TC_BACKEND_JAR" != /* || ! -f "$TC_BACKEND_JAR" ]]; then
  echo '后端JAR必须是存在的绝对路径' >&2
  exit 2
fi
exec java -Dloader.main=com.things.link.project.infrastructure.operations.AutomationQuotaCli \
  -cp "$TC_BACKEND_JAR" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
