#!/usr/bin/env bash
# §7 iOS parity QA: deploy, API contract probes, smoke + parity UI tests on a physical device.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$REPO_ROOT/ios"
REPORT="${PARITY_QA_REPORT:-/tmp/bock-ios-parity-qa.txt}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export PATH="$DEVELOPER_DIR/usr/bin:$PATH"

: >"$REPORT"
log() { echo "$*" | tee -a "$REPORT"; }
pass() { log "  PASS  $1"; }
fail() { log "  FAIL  $1"; exit 1; }
skip() { log "  SKIP  $1"; }
manual() { log "  MANUAL  $1"; }

log "=== Bock Media iOS §7 Parity QA ==="
log "$(date -Iseconds)"
log ""

# --- Server API probes (parity endpoints) ---
SERVER_URL="${BOCK_TEST_SERVER_URL:-}"
TOKEN="${BOCK_TEST_API_TOKEN:-}"
if [[ -z "$SERVER_URL" || -z "$TOKEN" ]]; then
  SERVER_URL="$(python3 - <<PY
import re
from pathlib import Path
props = Path("$IOS/Config.xcconfig").read_text()
for key in ("BOCK_EXTERNAL_SERVER_URL", "BOCK_LOCAL_SERVER_URL"):
    m = re.search(rf'^{key} = (.+)$', props, re.M)
    if m:
        val = m.group(1).strip().replace("/\$()/", "//")
        if "YOUR_" not in val:
            print(val)
            break
PY
)"
  TOKEN="$(python3 - <<PY
import re
from pathlib import Path
props = Path("$IOS/Config.xcconfig").read_text()
m = re.search(r'^BOCK_MOBILE_API_TOKEN = (.+)$', props, re.M)
print(m.group(1).strip() if m else "")
PY
)"
fi
if [[ -z "$SERVER_URL" || -z "$TOKEN" ]]; then
  read_cfg() { python3 - <<PY
import json
from pathlib import Path
cfg = json.loads(Path("$REPO_ROOT/config.json").read_text())
print(cfg.get("publicUrl", "").rstrip("/"))
print(cfg.get("mobileApi", {}).get("token", ""))
PY
  }
  mapfile -t _cfg < <(read_cfg)
  SERVER_URL="${SERVER_URL:-${_cfg[0]}}"
  TOKEN="${TOKEN:-${_cfg[1]}}"
fi

log "Server: $SERVER_URL"
log ""

api_probe() {
  local label="$1" path="$2"
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "X-Client-Id: parity-qa" \
    "${SERVER_URL%/}/${path}" 2>/dev/null || echo "000")"
  if [[ "$code" =~ ^2 ]]; then
    pass "API $label ($path → HTTP $code)"
  else
    fail "API $label ($path → HTTP $code)"
  fi
}

if [[ -n "$SERVER_URL" && -n "$TOKEN" ]]; then
  log "-- API parity endpoints --"
  api_probe "library health" "api/library/health"
  api_probe "home feed" "api/home?deferred=1&playlist_limit=5&genre_limit=5"
  api_probe "search pins" "api/search/pins"
  api_probe "followed artists" "api/followed-artists"
  api_probe "follow notifications" "api/notifications/followed?since=30d&limit=5"
  log ""
else
  skip "API probes (missing BOCK_TEST_SERVER_URL or token)"
  log ""
fi

log "-- API contract (repo) --"
if python3 "$REPO_ROOT/scripts/check_api_contract.py" >>"$REPORT" 2>&1; then
  pass "check_api_contract.py"
else
  fail "check_api_contract.py"
fi
log ""

# --- Deploy to device ---
log "-- Deploy app --"
if [[ "${SKIP_DEPLOY:-0}" != "1" ]]; then
  IOS_DEVICE_ID="${IOS_DEVICE_ID:-}" LAUNCH=0 "$REPO_ROOT/scripts/deploy_ios_app.sh" 2>&1 | tee -a "$REPORT"
  pass "deploy_ios_app.sh"
else
  skip "deploy (SKIP_DEPLOY=1)"
fi
log ""

manual "Follow artist → feed row after index (requires library change + wait)"
manual "Section pin persists across relaunch + profile sync"
manual "Music video skip-clear on real track skip (play video track, skip next)"
manual "Lyrics on real track"
manual "Local lock screen controls during local playback"
manual "Full download workflow + cancel notification"
manual "Widget snapshot on home screen"
manual "Cellular URL / Wi‑Fi then cellular (change network manually)"
log ""

log "-- UI tests (unlock iPhone, tap Allow if prompted) --"
if ONLY_TEST="${ONLY_TEST:-BockMediaUITests/BockDeviceSmokeTests,BockMediaUITests/BockParityQATests}" \
  BOCK_TEST_SERVER_URL="$SERVER_URL" BOCK_TEST_API_TOKEN="$TOKEN" \
  "$REPO_ROOT/scripts/run_ios_smoke_tests.sh" 2>&1 | tee -a "$REPORT"; then
  pass "UI tests (smoke + parity)"
else
  log "  FAIL  UI tests — unlock device, enable Developer Mode, retry: IOS_DEVICE_ID=... ./scripts/run_ios_parity_qa.sh SKIP_DEPLOY=1"
fi

log ""
log "=== §7 iOS parity QA complete ==="
log "Report: $REPORT"
