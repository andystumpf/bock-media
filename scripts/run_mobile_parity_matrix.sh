#!/usr/bin/env bash
# Compare Android vs iOS UI test results for shared catalog workflow IDs.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPORT_DIR="${REPORT_DIR:-$REPO_ROOT/build/mobile-ui-reports}"
mkdir -p "$REPORT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
REPORT="$REPORT_DIR/parity-$STAMP.json"

echo "Running Android parity smoke…"
TIER=1 PLATFORM=android SKIP_PREFLIGHT="${SKIP_PREFLIGHT:-0}" \
  "$REPO_ROOT/scripts/run_mobile_ui_suite.sh" && ANDROID_OK=1 || ANDROID_OK=0

echo "Running iOS parity smoke…"
TIER=1 PLATFORM=ios SKIP_PREFLIGHT=1 \
  "$REPO_ROOT/scripts/run_mobile_ui_suite.sh" && IOS_OK=1 || IOS_OK=0

python3 - <<PY
import json
from pathlib import Path
report = {
    "workflows": [
        {"id": "shell.bottom_nav", "android": "pass" if ${ANDROID_OK} else "fail", "ios": "pass" if ${IOS_OK} else "fail"},
        {"id": "search.query_unified", "android": "pass" if ${ANDROID_OK} else "fail", "ios": "pass" if ${IOS_OK} else "fail"},
        {"id": "playlist.open_detail", "android": "pass" if ${ANDROID_OK} else "fail", "ios": "pass" if ${IOS_OK} else "fail"},
    ],
    "androidOk": bool(${ANDROID_OK}),
    "iosOk": bool(${IOS_OK}),
}
path = Path("${REPORT}")
path.write_text(json.dumps(report, indent=2) + "\\n")
print(f"Parity report: {path}")
if not report["androidOk"] or not report["iosOk"]:
    raise SystemExit(1)
PY
