#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x "$HOME/.local/share/oki/jdk-21/bin/java" ]]; then
    export JAVA_HOME="$HOME/.local/share/oki/jdk-21"
  elif [[ -x /tmp/oki-jdk/bin/java ]]; then
    export JAVA_HOME=/tmp/oki-jdk
  fi
fi
if [[ $# -eq 0 ]]; then
  set -- :app:spotlessCheck :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
fi
exec ./gradlew "$@" --console=plain
