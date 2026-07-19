#!/usr/bin/env bash
# Fail if the app crashed during a mobile UI suite (adb logcat scan).
set -euo pipefail

PKG="${BOCK_PACKAGE:-com.bockmedia.console}"
SERIAL="${ANDROID_DEVICE:-}"
if [[ -z "$SERIAL" ]]; then
  SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  echo "mobile_ui_collect_crashes: no adb device (skip)" >&2
  exit 0
fi

ADB=(adb -s "$SERIAL")
CRASH_LOG="${REPORT_DIR:-/tmp}/mobile-ui-crash-${SERIAL}.log"
mkdir -p "$(dirname "$CRASH_LOG")"

"${ADB[@]}" logcat -d -b crash 2>/dev/null > "$CRASH_LOG" || true
if grep -q "$PKG" "$CRASH_LOG" 2>/dev/null; then
  echo "mobile_ui_collect_crashes FAILED: crash buffer mentions $PKG" >&2
  grep "$PKG" "$CRASH_LOG" | tail -20 >&2
  exit 1
fi

RECENT="$("${ADB[@]}" logcat -d -t 300 2>/dev/null || true)"
if echo "$RECENT" | grep -q "FATAL EXCEPTION.*$PKG"; then
  echo "mobile_ui_collect_crashes FAILED: FATAL EXCEPTION for $PKG" >&2
  echo "$RECENT" | grep -A3 "FATAL EXCEPTION.*$PKG" | tail -20 >&2
  exit 1
fi

echo "mobile_ui_collect_crashes: no crashes for $PKG"
