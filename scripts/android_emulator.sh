#!/usr/bin/env bash
# Start the Bock Media debug AVD and optionally build/install the app.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

AVD_NAME="${AVD_NAME:-bockmedia_debug}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="${APP_ID:-com.bockmedia.console.debug}"

cmd="${1:-help}"

run_emulator() {
  if adb devices 2>/dev/null | rg -q '^emulator-'; then
    echo "Emulator already running:"
    adb devices
    return 0
  fi
  echo "Starting $AVD_NAME (KVM required — user must be in group kvm)..."
  # Headless: fine over SSH. Drop -no-window if you have a local display.
  # macOS has no `sg`; Linux uses sg kvm when available.
  if command -v sg >/dev/null 2>&1; then
    sg kvm -c "emulator -avd \"$AVD_NAME\" -no-window -no-audio -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save" &
  else
    emulator -avd "$AVD_NAME" -no-window -no-audio -gpu swiftshader_indirect -no-snapshot-load -no-snapshot-save &
  fi
  echo "Waiting for device..."
  adb wait-for-device
  until adb shell getprop sys.boot_completed 2>/dev/null | rg -q '^1$'; do
    sleep 2
  done
  echo "Emulator ready."
  adb devices
}

install_app() {
  if [[ ! -f "$APK" ]]; then
    echo "Building debug APK..."
    (cd "$REPO/android" && ./gradlew assembleDebug)
  fi
  adb install -r "$APK"
  adb shell am start -n "$APP_ID/com.bockmedia.console.MainActivity"
}

logcat_app() {
  adb logcat -c
  adb logcat | rg -i 'bockmedia|AndroidRuntime|FATAL|ourMedia'
}

case "$cmd" in
  start)   run_emulator ;;
  install) install_app ;;
  logs)    logcat_app ;;
  signin)  adb shell input tap 540 1031; adb shell pm grant "${APP_ID:-com.bockmedia.console.debug}" android.permission.POST_NOTIFICATIONS 2>/dev/null || true ;;
  all)     run_emulator; install_app ;;
  *)
    cat <<EOF
Usage: $0 {start|install|logs|all}

  start   — boot AVD $AVD_NAME (headless)
  install — build (if needed) + install + launch Bock Media
  logs    — tail logcat filtered for crashes
  all     — start + install

Env: ANDROID_HOME (default $ANDROID_HOME), AVD_NAME (default $AVD_NAME)

Emulator reaches the host server at 10.0.2.2:<port> (maps to this machine's localhost).
For LAN IP http://127.0.0.1:3001 use that directly if the emulator network can reach it.
EOF
    ;;
esac
