#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/ci-common.sh"

run_webapp() {
  verify_need python3
  verify_java
  verify_node things-link-webapp things-link-client-contracts
  verify_docker
  python3 -m unittest scripts.tests.test_generate_openapi_contracts
  verify_install things-link-client-contracts
  verify_install things-link-webapp
  export PLAYWRIGHT_BROWSERS_PATH="$VERIFY_ROOT/things-link-webapp/.playwright-browsers"
  cd things-link-webapp
  pnpm verify
  if [[ "$(uname -s)" == Linux ]]; then
    pnpm exec playwright install --with-deps chromium firefox webkit
  else
    # macOS 的浏览器与系统依赖按当前系统安装；不执行 Linux 的 apt 步骤。
    pnpm exec playwright install chromium firefox webkit
  fi
  cd "$VERIFY_ROOT"
  ./things-link/mvnw -f things-link/pom.xml -B -ntp -pl things-link-bootstrap -am test \
    -Dtest=AppWebAppSessionJourneyTests,AppWebAppStaticCanvasJourneyTests,AppWebAppStaticRecoveryJourneyTests,AppWebAppDeviceCanvasJourneyTests,AppWebAppMultiDeviceCanvasJourneyTests,AppWebAppCompositeCanvasJourneyTests,AppWebAppInteractiveCanvasJourneyTests,AppWebAppRealtimeJourneyTests,AppWebAppShareJourneyTests,AppWebAppPwaJourneyTests,AppWebAppForegroundAlarmJourneyTests,AppWebAppDeviceControlJourneyTests,ManagedWebAppHostQualificationIntegrationTests \
    -Dwebapp.session.verify=true -Dwebapp.static.verify=true -Dwebapp.recovery.verify=true \
    -Dwebapp.device.verify=true -Dwebapp.multi-device.verify=true -Dwebapp.composite.verify=true \
    -Dwebapp.interactive.verify=true -Dwebapp.realtime.verify=true -Dwebapp.share.verify=true \
    -Dwebapp.pwa.verify=true -Dwebapp.foreground-alarm.verify=true -Dwebapp.device-control.verify=true \
    -Dwebapp.host-qualification.verify=true -Dsurefire.failIfNoSpecifiedTests=false
}
verify_run webapp run_webapp "$@"
