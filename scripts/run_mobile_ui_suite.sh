#!/usr/bin/env bash
# Run tiered mobile UI tests (Android and/or iOS) with server preflight.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TIER="${TIER:-1}"
PLATFORM="${PLATFORM:-android}"
AREA="${AREA:-}"
SKIP_PREFLIGHT="${SKIP_PREFLIGHT:-0}"
REPORT_DIR="${REPORT_DIR:-$REPO_ROOT/build/mobile-ui-reports}"

usage() {
  cat <<EOF
Usage: TIER=1|2|3|4|5 PLATFORM=android|ios|both ./scripts/run_mobile_ui_suite.sh

  TIER=1     Smoke (BockDeviceSmokeTest / BockDeviceSmokeTests)
  TIER=2     Navigation audit
  TIER=3     Feature journeys (AREA=Home|Search|Playlist)
  TIER=4     Profile + resilience
  TIER=5     Parity matrix (both platforms)

Env: SKIP_PREFLIGHT=1, SMOKE_TIMEOUT_MS, ANDROID_DEVICE, IOS_DEVICE_ID
EOF
}

android_class_for_tier() {
  case "$1" in
    1) echo "com.bockmedia.console.smoke.BockDeviceSmokeTest" ;;
    2) echo "com.bockmedia.console.smoke.BockNavigationAuditTest" ;;
    3)
      case "${AREA:-all}" in
        Home) echo "com.bockmedia.console.journeys.HomeJourneyTest" ;;
        Search) echo "com.bockmedia.console.journeys.SearchJourneyTest" ;;
        Playlist) echo "com.bockmedia.console.journeys.PlaylistJourneyTest" ;;
        Playback) echo "com.bockmedia.console.journeys.PlaybackJourneyTest" ;;
        Downloads) echo "com.bockmedia.console.journeys.DownloadsJourneyTest" ;;
        Settings) echo "com.bockmedia.console.journeys.SettingsJourneyTest" ;;
        Family) echo "com.bockmedia.console.journeys.FamilyJourneyTest" ;;
        *) echo "com.bockmedia.console.journeys.HomeJourneyTest,com.bockmedia.console.journeys.SearchJourneyTest,com.bockmedia.console.journeys.PlaylistJourneyTest,com.bockmedia.console.journeys.PlaybackJourneyTest,com.bockmedia.console.journeys.DownloadsJourneyTest,com.bockmedia.console.journeys.SettingsJourneyTest,com.bockmedia.console.journeys.FamilyJourneyTest" ;;
      esac
      ;;
    4) echo "com.bockmedia.console.smoke.BockProfilePrefsTest,com.bockmedia.console.journeys.ResilienceJourneyTest,com.bockmedia.console.smoke.VersionConsistencyTest" ;;
    5) echo "com.bockmedia.console.smoke.BockParityQATest" ;;
    *) echo "Unknown tier $1" >&2; return 1 ;;
  esac
}

ios_only_for_tier() {
  case "$1" in
    1) echo "BockMediaUITests/BockDeviceSmokeTests" ;;
    2) echo "BockMediaUITests/BockNavigationAuditTests" ;;
    3)
      case "${AREA:-all}" in
        Home) echo "BockMediaUITests/HomeJourneyTests" ;;
        Search) echo "BockMediaUITests/SearchJourneyTests" ;;
        Playlist) echo "BockMediaUITests/PlaylistJourneyTests" ;;
        Playback) echo "BockMediaUITests/PlaybackJourneyTests" ;;
        Downloads) echo "BockMediaUITests/DownloadsJourneyTests" ;;
        Settings) echo "BockMediaUITests/SettingsJourneyTests" ;;
        Family) echo "BockMediaUITests/FamilyJourneyTests" ;;
        *) echo "BockMediaUITests/HomeJourneyTests,BockMediaUITests/SearchJourneyTests,BockMediaUITests/PlaylistJourneyTests,BockMediaUITests/PlaybackJourneyTests,BockMediaUITests/DownloadsJourneyTests,BockMediaUITests/SettingsJourneyTests,BockMediaUITests/FamilyJourneyTests" ;;
      esac
      ;;
    4) echo "BockMediaUITests/BockProfilePrefsTests,BockMediaUITests/ResilienceJourneyTests" ;;
    5) echo "BockMediaUITests/BockParityQATests" ;;
    *) echo "Unknown tier $1" >&2; return 1 ;;
  esac
}

run_android() {
  local tier="$1"
  local classes
  classes="$(android_class_for_tier "$tier")"
  IFS=',' read -ra parts <<< "$classes"
  for class in "${parts[@]}"; do
    echo "Android: $class"
    SMOKE_CLASS="$class" "$REPO_ROOT/scripts/run_android_smoke_tests.sh"
  done
}

run_ios() {
  local tier="$1"
  local only
  only="$(ios_only_for_tier "$tier")"
  IFS=',' read -ra parts <<< "$only"
  for class in "${parts[@]}"; do
    echo "iOS: $class"
    ONLY_TEST="$class" "$REPO_ROOT/scripts/run_ios_smoke_tests.sh"
  done
}

mkdir -p "$REPORT_DIR"
START_MS="$(python3 -c 'import time; print(int(time.time()*1000))')"

if [[ "$SKIP_PREFLIGHT" != "1" && "$TIER" != "5" ]]; then
  "$REPO_ROOT/scripts/mobile_ui_preflight.sh"
fi

if [[ "$TIER" == "5" && "$PLATFORM" == "both" ]]; then
  "$REPO_ROOT/scripts/run_mobile_parity_matrix.sh"
  exit 0
fi

case "$PLATFORM" in
  android) run_android "$TIER" ;;
  ios) run_ios "$TIER" ;;
  both)
    run_android "$TIER"
    run_ios "$TIER"
    ;;
  *) usage; exit 1 ;;
esac

END_MS="$(python3 -c 'import time; print(int(time.time()*1000))')"
python3 - <<PY
import json, os
from pathlib import Path
report = {
    "tier": os.environ.get("TIER"),
    "platform": os.environ.get("PLATFORM"),
    "area": os.environ.get("AREA") or None,
    "durationMs": int("${END_MS}") - int("${START_MS}"),
    "status": "ok",
}
path = Path("${REPORT_DIR}") / f"run-{report['platform']}-t{report['tier']}.json"
path.write_text(json.dumps(report, indent=2) + "\\n")
print(f"Report: {path}")
PY

echo "Mobile UI suite tier $TIER ($PLATFORM) finished."

if [[ "$PLATFORM" == "android" || "$PLATFORM" == "both" ]]; then
  ANDROID_DEVICE="${ANDROID_DEVICE:-}" "$REPO_ROOT/scripts/mobile_ui_collect_crashes.sh" || exit 1
fi
