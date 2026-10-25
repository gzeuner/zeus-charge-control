#!/usr/bin/env bash
set -euo pipefail

# Preserve operator-provided settings; secrets belong in the environment or config/.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$SCRIPT_DIR/lib/zeus-charge-control.jar" ]]; then
  APP_HOME="$SCRIPT_DIR"
  APP_JAR="$APP_HOME/lib/zeus-charge-control.jar"
  if [[ ! -f "$APP_HOME/config/application.properties" ]]; then
    printf '%s\n' 'Configuration missing. Copy config/application.properties.example to config/application.properties and review its settings.' >&2
    exit 2
  fi
else
  APP_HOME="$(cd -- "$SCRIPT_DIR/.." && pwd)"
  shopt -s nullglob
  jars=("$APP_HOME"/target/zeus-charge-control-*.jar)
  if [[ ${#jars[@]} -ne 1 ]]; then
    printf '%s\n' 'Expected one application JAR in target. Run mvn clean verify first.' >&2
    exit 2
  fi
  APP_JAR="${jars[0]}"
fi

JAVA_BIN=java
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi
cd -- "$APP_HOME"
exec "$JAVA_BIN" -jar "$APP_JAR" '--spring.config.additional-location=optional:file:./config/' "$@"
