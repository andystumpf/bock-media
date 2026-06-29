#!/usr/bin/env bash
# Profile preference CRUD + analytics scope tests (needs 2+ household members).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT/android"

if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "No adb device connected." >&2
  exit 1
fi

SERIAL="${ANDROID_DEVICE:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi

TEST_CLASS="com.bockmedia.console.smoke.BockProfilePrefsTest"

echo "Installing debug APK on $SERIAL …"
./gradlew :app:installDebug -q

adb -s "$SERIAL" shell settings put system screen_off_timeout 600000 || true
adb -s "$SERIAL" shell svc power stayon usb || true

echo "Running $TEST_CLASS …"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentation.targetSerial="$SERIAL" \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS" \
  -Pandroid.testInstrumentationRunnerArguments.smokeTimeoutMs="45000" \
  "$@"

echo "Profile tests finished. Report: android/app/build/reports/androidTests/connected/debug/index.html"
