#!/usr/bin/env bash
# JDK 21 only. Does not run tests or touch deployment TLS material.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA" "$ROOT/things-link/things-link-testing/src/main/java/com/things/link/testing/tls/TestTlsMaterial.java" "$ROOT"
