#!/usr/bin/env bash
# Physical device smoke tests for offline downloads (Plexamp-style).
# Usage: ./scripts/offline_device_test.sh [device_serial]
set -euo pipefail

SERIAL="${1:-}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

PKG="com.bockmedia.console.debug"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Checking device"
"${ADB[@]}" get-state

echo "==> Installing debug APK"
./gradlew assembleDebug -q
"${ADB[@]}" install -r "$APK"

echo "==> Launch app"
"${ADB[@]}" shell am start -n "$PKG/com.bockmedia.console.MainActivity"

echo "==> Verify offline storage path exists after manual download"
"${ADB[@]}" shell run-as "$PKG" ls files/offline/collections 2>/dev/null || echo "(no collections yet — download from Home long-press)"

echo "==> Dump logcat for offline download tags (30s window — trigger download on device now)"
"${ADB[@]}" logcat -c
sleep 2
echo "    Long-press a Home tile → Download, or open a playlist → download icon"
sleep 28
"${ADB[@]}" logcat -d | grep -iE "offline|download|LocalPlayback|ExoPlayer" | tail -40 || true

echo "==> Check WorkManager offline sync job registered"
"${ADB[@]}" shell dumpsys jobscheduler | grep -iE "bockmedia_offline_sync|OfflineSync" || echo "(sync worker runs on network connect)"

echo "==> Done. Manual checks:"
echo "  1. Home → long-press mix/playlist → Download for offline"
echo "  2. Playlist detail → download icon shows progress bar"
echo "  3. Settings → Downloads → art, progress, play on phone"
echo "  4. Toggle airplane mode off → failed downloads retry / playlists resync"
