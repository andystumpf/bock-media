#!/usr/bin/env bash
# Run functional smoke tests on a connected Android device (server must be configured).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT/android"

if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "No adb device connected. Plug in phone and enable USB debugging." >&2
  exit 1
fi

SERIAL="${ANDROID_DEVICE:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi

SMOKE_QUERY="${SMOKE_QUERY:-love}"
SMOKE_CLASS="com.bockmedia.console.smoke.BockDeviceSmokeTest"
TIMEOUT_MS="${SMOKE_TIMEOUT_MS:-45000}"

echo "Installing debug APK on $SERIAL …"
./gradlew :app:installDebug -q

echo "Keeping screen on during tests …"
adb -s "$SERIAL" shell settings put system screen_off_timeout 600000 || true
adb -s "$SERIAL" shell svc power stayon usb || true

echo "Running 20 smoke tests ($SMOKE_CLASS) on $SERIAL …"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentation.targetSerial="$SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.class="$SMOKE_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.smokeSearchQuery="$SMOKE_QUERY" \
  -Pandroid.testInstrumentationRunnerArguments.smokeTimeoutMs="$TIMEOUT_MS" \
  "$@"

echo "Smoke tests finished. Report: android/app/build/reports/androidTests/connected/debug/index.html"
