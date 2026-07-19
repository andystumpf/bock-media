#!/usr/bin/env bash
# Capture iOS Simulator + Android device screenshots for the public README.
# Uses the generated demo-data server (real artists/art, no personal data).
#
# Prerequisites:
#   - demo server already listening (default http://<LAN>:3033)
#   - Xcode + iPhone simulator
#   - adb device or emulator
#
#   ./scripts/capture_mobile_readme_screenshots.sh
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT_IOS="$REPO/img/screenshots/ios"
OUT_ANDROID="$REPO/img/screenshots/android"
LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo 127.0.0.1)"
PORT="${DEMO_PORT:-3033}"
SERVER_URL="http://${LAN_IP}:${PORT}"
TOKEN="${DEMO_MOBILE_TOKEN:-demo}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export PATH="$DEVELOPER_DIR/usr/bin:$PATH"

mkdir -p "$OUT_IOS" "$OUT_ANDROID"

echo "==> Demo server: $SERVER_URL"
curl -sf "$SERVER_URL/api/summary" >/dev/null

# ── Patch WatchFolders display path (no personal home dir in screenshots) ──
WF="$REPO/demo-data/WatchFolders.xml"
if [[ -f "$WF" ]]; then
  python3 - <<'PY'
import pathlib, re
p = pathlib.Path("demo-data/WatchFolders.xml")
text = p.read_text()
text = re.sub(r"<Path>[^<]*</Path>", "<Path>/Users/Shared/bock-media/music</Path>", text)
p.write_text(text)
print("Patched WatchFolders.xml display path")
PY
fi

# ── iOS Simulator screenshots ──────────────────────────────────────────────
capture_ios() {
  local SIM_NAME="${IOS_SIMULATOR:-iPhone 17 Pro}"
  local UDID
  UDID="$(xcrun simctl list devices available | awk -F '[()]' -v n="$SIM_NAME" '
    $0 ~ n && $0 !~ /unavailable/ { print $2; exit }
  ')"
  if [[ -z "$UDID" ]]; then
    echo "No simulator matching '$SIM_NAME'" >&2
    return 1
  fi
  echo "==> Booting iOS simulator $SIM_NAME ($UDID)"
  xcrun simctl boot "$UDID" 2>/dev/null || true
  xcrun simctl bootstatus "$UDID" -b

  local CFG="$REPO/ios/Config.xcconfig"
  local CFG_BAK="$REPO/ios/Config.xcconfig.readme-bak"
  cp "$CFG" "$CFG_BAK"
  # Preserve DEVELOPMENT_TEAM; override server + token for demo captures only.
  DEMO_LAN_IP="$LAN_IP" DEMO_PORT="$PORT" DEMO_TOKEN="$TOKEN" DEMO_CFG="$CFG" python3 - <<'PY'
import os
from pathlib import Path
cfg = Path(os.environ["DEMO_CFG"])
lan = os.environ["DEMO_LAN_IP"]
port = os.environ["DEMO_PORT"]
token = os.environ["DEMO_TOKEN"]
team = "YOUR_TEAM_ID_HERE"
for line in cfg.read_text().splitlines():
    if line.startswith("DEVELOPMENT_TEAM"):
        team = line.split("=", 1)[1].strip()
        break
# xcconfig treats // as a comment — use http:/$()/host form.
cfg.write_text(
    f"DEVELOPMENT_TEAM = {team}\n"
    f"BOCK_LOCAL_SERVER_URL = http:/$()/{lan}:{port}\n"
    f"BOCK_EXTERNAL_SERVER_URL = http:/$()/{lan}:{port}\n"
    f"BOCK_MOBILE_API_TOKEN = {token}\n"
    "BOCK_ADMIN_USER =\n"
    "BOCK_ADMIN_PASSWORD =\n"
)
print("Wrote demo Config.xcconfig")
PY

  cleanup_ios() {
    if [[ -f "$CFG_BAK" ]]; then mv "$CFG_BAK" "$CFG"; echo "Restored ios/Config.xcconfig"; fi
  }
  trap cleanup_ios RETURN

  cd "$REPO/ios"
  if [[ -f project.yml ]] && command -v xcodegen >/dev/null; then
    xcodegen generate >/dev/null
  fi

  printf '{"serverURL":"%s","apiToken":"%s"}\n' "$SERVER_URL" "$TOKEN" > /tmp/bock-smoke-env.json

  echo "==> Building iOS app for simulator"
  xcodebuild build \
    -scheme BockMedia \
    -destination "platform=iOS Simulator,id=$UDID" \
    -derivedDataPath /tmp/bock-ios-readme-dd \
    2>&1 | tee /tmp/bock-ios-readme-xcodebuild.log | tail -40

  local APP_PATH
  APP_PATH="$(find /tmp/bock-ios-readme-dd/Build/Products -name 'BockMedia.app' -type d | head -1)"
  if [[ -z "$APP_PATH" ]]; then
    echo "BockMedia.app not found after build" >&2
    cleanup_ios
    return 1
  fi
  local APP_ID="com.bockmedia.console"
  echo "Installing $APP_PATH"
  xcrun simctl uninstall "$UDID" "$APP_ID" 2>/dev/null || true
  xcrun simctl install "$UDID" "$APP_PATH"

  # Prefer launch args over bockmedia:// openurl — iOS shows an "Open in BockMedia?"
  # confirmation sheet for openurl that contaminates every screenshot.
  # Also reboot once before the first launch if IOS_REBOOT=1 (clears stuck system alerts).
  if [[ "${IOS_REBOOT:-0}" == "1" ]]; then
    echo "==> Rebooting simulator to clear stuck system alerts"
    xcrun simctl shutdown "$UDID" 2>/dev/null || true
    xcrun simctl boot "$UDID"
    xcrun simctl bootstatus "$UDID" -b
  fi

  launch_ios() {
    xcrun simctl terminate "$UDID" "$APP_ID" 2>/dev/null || true
    sleep 1
    xcrun simctl launch "$UDID" "$APP_ID" "$@"
  }

  snap_ios() {
    local name="$1"
    sleep 2
    xcrun simctl io "$UDID" screenshot "$OUT_IOS/${name}.png"
    echo "  ios/${name}.png"
  }

  launch_ios -UITesting -UITestTab home
  sleep 12
  snap_ios "01-home"

  launch_ios -UITesting -UITestSearchQuery fleetwood
  sleep 12
  snap_ios "02-search"

  launch_ios -UITesting -UITestTab library
  sleep 10
  snap_ios "03-library"

  launch_ios -UITesting -NowPlayingPreview
  sleep 12
  snap_ios "04-now-playing"

  launch_ios -UITesting -UITestTab automations
  sleep 10
  snap_ios "05-automations"

  cleanup_ios
  trap - RETURN
  echo "iOS screenshots → $OUT_IOS"
}

# ── Android device / emulator screenshots ──────────────────────────────────
capture_android() {
  if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
    echo "No adb device — starting emulator Medium_Phone_API_36.1"
    local AVD="${AVD_NAME:-Medium_Phone_API_36.1}"
    local EMU="$HOME/Library/Android/sdk/emulator/emulator"
    "$EMU" -avd "$AVD" -no-audio -gpu swiftshader_indirect >/tmp/bock-emu.log 2>&1 &
    adb wait-for-device
    until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 2; done
  fi

  local LP="$REPO/android/local.properties"
  local LP_BAK="$REPO/android/local.properties.readme-bak"
  cp "$LP" "$LP_BAK"

  # Physical phones often cannot reach the Mac LAN IP (AP/client isolation).
  # Use adb reverse so the device talks to the demo server via 127.0.0.1.
  local ANDROID_HOST="$LAN_IP"
  if adb reverse tcp:"$PORT" tcp:"$PORT" >/dev/null 2>&1; then
    if adb shell "curl -sf -m 3 http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1 \
      || adb shell "wget -q -O - http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then
      ANDROID_HOST="127.0.0.1"
      echo "==> Using adb reverse → http://127.0.0.1:${PORT}"
    fi
  fi

  DEMO_ANDROID_HOST="$ANDROID_HOST" DEMO_PORT="$PORT" DEMO_TOKEN="$TOKEN" DEMO_LP="$LP" python3 - <<'PY'
import os
from pathlib import Path
lp = Path(os.environ["DEMO_LP"])
host = os.environ["DEMO_ANDROID_HOST"]
port = os.environ["DEMO_PORT"]
token = os.environ["DEMO_TOKEN"]
sdk = ""
for line in lp.read_text().splitlines():
    if line.startswith("sdk.dir="):
        sdk = line
        break
lp.write_text(
    f"{sdk}\n"
    f"bockmedia.localServerUrl=http://{host}:{port}\n"
    f"bockmedia.externalServerUrl=http://{host}:{port}\n"
    f"bockmedia.mobileApiToken={token}\n"
    "bockmedia.adminUser=\n"
    "bockmedia.adminPassword=\n"
)
print(f"Wrote demo android/local.properties → http://{host}:{port}")
PY

  cleanup_android() {
    if [[ -f "$LP_BAK" ]]; then mv "$LP_BAK" "$LP"; echo "Restored android/local.properties"; fi
  }
  trap cleanup_android RETURN

  echo "==> Building Android debug APK with demo server URLs"
  (cd "$REPO/android" && ./gradlew assembleDebug -q)

  local APK="$REPO/android/app/build/outputs/apk/debug/app-debug.apk"
  local APP_ID="com.bockmedia.console"
  adb install -r "$APK"
  APP_ID="$(adb shell pm list packages | grep -i 'bockmedia.console' | head -1 | cut -d: -f2 | tr -d '\r')"
  APP_ID="${APP_ID:-com.bockmedia.console}"
  echo "Android app id: $APP_ID"

  # Never use BACK — it can leave the app into Phone Recents (PII risk).
  adb shell pm clear "$APP_ID" >/dev/null 2>&1 || true
  adb shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  adb shell am start -n "$APP_ID/com.bockmedia.console.MainActivity" --ez UITesting true
  sleep 14

  # Dismiss "Who's listening?" by tapping Continue unattributed (deep link alone is not enough).
  adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1 || true
  adb pull /sdcard/uidump.xml /tmp/bock-android-uidump.xml >/dev/null 2>&1 || true
  python3 - <<'PY'
import re
from pathlib import Path
p = Path("/tmp/bock-android-uidump.xml")
if not p.exists():
    raise SystemExit(0)
t = p.read_text(errors="ignore")
m = re.search(r'text="Continue unattributed"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    Path("/tmp/bock-android-tap.txt").write_text(f"{(x1+x2)//2} {(y1+y2)//2}")
PY
  if [[ -f /tmp/bock-android-tap.txt ]]; then
    read -r CX CY < /tmp/bock-android-tap.txt
    adb shell input tap "$CX" "$CY"
    sleep 8
  fi

  snap_android() {
    local name="$1"
    sleep 2
    adb exec-out screencap -p > "$OUT_ANDROID/${name}.png"
    if [[ "$(wc -c < "$OUT_ANDROID/${name}.png")" -gt 40000 ]]; then
      echo "  android/${name}.png"
    else
      echo "  WARN: android/${name}.png looks empty/bad" >&2
    fi
  }

  # Pass UITesting on every VIEW so onNewIntent keeps uitest hooks enabled.
  adb_nav() {
    adb shell am start -a android.intent.action.VIEW -d "$1" "$APP_ID" --ez UITesting true
  }

  adb_nav "bockmedia://uitest/tab?route=home"
  sleep 6
  snap_android "01-home"

  adb_nav "bockmedia://uitest/search?q=fleetwood"
  sleep 8
  snap_android "02-search"

  adb_nav "bockmedia://uitest/tab?route=library"
  sleep 6
  snap_android "03-library"

  adb_nav "bockmedia://uitest/now-playing-preview"
  sleep 5
  snap_android "04-now-playing"

  adb_nav "bockmedia://uitest/tab?route=automations"
  sleep 5
  snap_android "05-automations"

  adb shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  cleanup_android
  trap - RETURN
  echo "Android screenshots → $OUT_ANDROID"
}

capture_ios
capture_android

echo "Done."
