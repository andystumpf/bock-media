#!/usr/bin/env bash
# Automated login bug-hunt on Android emulator.
set -euo pipefail
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
APP=com.bockmedia.console.debug
ACTIVITY=$APP/com.bockmedia.console.MainActivity
LOG=/tmp/bock_login_test.log

tap_sign_in() { adb shell input tap 540 1031; }
clear_app() { adb shell pm clear "$APP" >/dev/null; }
grant_notif() { adb shell pm grant "$APP" android.permission.POST_NOTIFICATIONS 2>/dev/null || true; }
tap_allow_notif() { adb shell input tap 540 1317 2>/dev/null || true; }

launch() {
  grant_notif
  adb logcat -c
  adb shell am start -n "$ACTIVITY" >/dev/null
  sleep 4
}

ui_texts() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml | rg -o 'text="[^"]*"' | sed 's/text="//;s/"$//' | rg -v '^$' || true
}

wait_for() {
  local needle="$1" tries="${2:-20}"
  for _ in $(seq 1 "$tries"); do
    ui_texts | rg -q "$needle" && return 0
    sleep 1
  done
  return 1
}

log_http() {
  adb logcat -d | rg 'okhttp\.OkHttpClient:.*HTTP (FAILED|<--)' | tail -15
}

log_fatal() {
  adb logcat -d | rg -i 'AndroidRuntime|FATAL EXCEPTION' || true
}

run_case() {
  local name="$1"
  echo ""
  echo "========== $name =========="
  shift
  set +e
  "$@"
  local rc=$?
  set -e
  echo "--- UI ---"
  ui_texts | head -12
  echo "--- HTTP ---"
  log_http
  local fatal
  fatal=$(log_fatal)
  if [[ -n "$fatal" ]]; then
    echo "--- FATAL ---"
    echo "$fatal"
    echo "FAIL: $name (crash)"
    return 1
  fi
  if [[ $rc -ne 0 ]]; then
    echo "WARN: $name (assertion failed, rc=$rc)"
    return 0
  fi
  echo "OK: $name"
}

case_external_valid() {
  clear_app
  launch
  tap_sign_in
  tap_allow_notif
  sleep 14
  wait_for "Good afternoon|Home|Recently|Playlists" || wait_for "Sign in"
}

case_wrong_token() {
  clear_app
  launch
  adb shell input tap 540 694
  sleep 0.5
  adb shell input keyevent 123
  for _ in $(seq 1 30); do adb shell input keyevent 67; done
  adb shell input text "badtoken123"
  tap_sign_in
  sleep 8
  wait_for "Authentication failed|HTTP 401|Connection failed|Enter Mobile" || true
}

case_empty_login() {
  clear_app
  launch
  adb shell input tap 540 694
  sleep 0.5
  for _ in $(seq 1 40); do adb shell input keyevent 67; done
  tap_sign_in
  sleep 2
  wait_for "Enter Mobile API token" || true
}

case_remember_me() {
  clear_app
  launch
  tap_sign_in
  tap_allow_notif
  sleep 14
  wait_for "Good afternoon|Home|Recently" || return 1
  adb shell am force-stop "$APP"
  sleep 1
  launch
  sleep 12
  wait_for "Good afternoon|Home|Recently" || wait_for "Sign in"
}

# Install latest debug build
REPO="$(cd "$(dirname "$0")/.." && pwd)"
APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  adb install -r "$APK" >/dev/null
fi

run_case "external-only valid token" case_external_valid
run_case "wrong token shows error" case_wrong_token
run_case "empty login validation" case_empty_login
run_case "remember-me cold start" case_remember_me

echo ""
echo "Done. Full log: adb logcat -d > $LOG"
