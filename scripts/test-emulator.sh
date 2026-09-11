#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
oki_serial="${1:-emulator-5554}"
if [[ "$oki_serial" != emulator-* ]]; then
  echo 'Use a dedicated emulator: these tests change OKI permissions and test data.' >&2
  exit 1
fi
oki_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
./scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
"$oki_sdk/platform-tools/adb" -s "$oki_serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$oki_sdk/platform-tools/adb" -s "$oki_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$oki_sdk/platform-tools/adb" -s "$oki_serial" shell appops set com.oki SCHEDULE_EXACT_ALARM deny
"$oki_sdk/platform-tools/adb" -s "$oki_serial" shell pm revoke com.oki android.permission.POST_NOTIFICATIONS
"$oki_sdk/platform-tools/adb" -s "$oki_serial" shell settings put secure show_ime_with_hard_keyboard 1
mkdir -p app/build/reports/instrumentation
oki_report=app/build/reports/instrumentation/results.txt
"$oki_sdk/platform-tools/adb" -s "$oki_serial" shell am instrument -w com.oki.test/androidx.test.runner.AndroidJUnitRunner | tee "$oki_report"
rg -q '^OK \([0-9]+ tests\)' "$oki_report"
