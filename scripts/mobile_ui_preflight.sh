#!/usr/bin/env bash
# Abort mobile UI suites when the backend is unhealthy (avoids false app failures).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$REPO_ROOT/shared/fixtures/ui_test_manifest.json"
MAX_SEC="${PREFLIGHT_MAX_SEC:-3}"

read_token() {
  python3 - <<PY
import json, re
from pathlib import Path
root = Path("$REPO_ROOT")
for path in (root / "android/local.properties", root / "ios/Config.xcconfig"):
    if not path.exists():
        continue
    text = path.read_text(encoding="utf-8")
    if path.name == "local.properties":
        m = re.search(r'^bockmedia\\.mobileApiToken=(.+)$', text, re.M)
        if m and m.group(1).strip():
            print(m.group(1).strip()); raise SystemExit(0)
        m = re.search(r'^bockmedia\\.externalServerUrl=(.+)$', text, re.M)
        if m and m.group(1).strip():
            print("URL=" + m.group(1).strip().rstrip("/")); raise SystemExit(0)
    else:
        m = re.search(r'^BOCK_MOBILE_API_TOKEN = (.+)$', text, re.M)
        if m and m.group(1).strip():
            print(m.group(1).strip()); raise SystemExit(0)
        for key in ("BOCK_EXTERNAL_SERVER_URL", "BOCK_LOCAL_SERVER_URL"):
            m = re.search(rf'^{key} = (.+)$', text, re.M)
            if m and m.group(1).strip():
                print("URL=" + m.group(1).strip().rstrip("/").replace("/\$()/", "//"))
                raise SystemExit(0)
raise SystemExit("missing token/url")
PY
}

TOKEN="${BOCK_TEST_API_TOKEN:-}"
BASE="${BOCK_TEST_SERVER_URL:-}"
while IFS= read -r line; do
  if [[ "$line" == URL=* ]]; then
    BASE="${line#URL=}"
  elif [[ -n "$line" && -z "$TOKEN" ]]; then
    TOKEN="$line"
  fi
done < <(read_token || true)

if [[ -z "$BASE" ]]; then
  BASE="${BOCK_TEST_SERVER_URL:-http://your-server.local:3001}"
fi
if [[ -z "$TOKEN" ]]; then
  echo "mobile_ui_preflight: set BOCK_TEST_API_TOKEN or android/local.properties token" >&2
  exit 1
fi

fail() {
  echo "mobile_ui_preflight FAILED: $1" >&2
  echo "Server unhealthy — fix backend before running UI tests (not an app bug)." >&2
  exit 1
}

check_http() {
  local label="$1" url="$2" auth="${3:-0}"
  local code time resp
  if [[ "$auth" == "1" ]]; then
    resp="$(curl -sS -m "$MAX_SEC" -o /dev/null -w '%{http_code} %{time_total}' \
      -H "Authorization: Bearer ${TOKEN}" "$url" || true)"
  else
    resp="$(curl -sS -m "$MAX_SEC" -o /dev/null -w '%{http_code} %{time_total}' "$url" || true)"
  fi
  code="${resp%% *}"
  time="${resp#* }"
  if [[ -z "$code" || "$code" == "$resp" ]]; then
    fail "$label unreachable ($url)"
  fi
  if [[ "$code" != "200" ]]; then
    fail "$label HTTP $code ($url)"
  fi
  python3 - <<PY
import sys
t = float("${time}")
if t > float("${MAX_SEC}"):
    sys.exit(1)
PY
  if [[ $? -ne 0 ]]; then
    fail "$label slow ${time}s (budget ${MAX_SEC}s)"
  fi
  echo "  OK $label (${time}s)"
}

echo "Preflight: $BASE"
check_http health "$BASE/api/health" 0

HEALTH_JSON="$(curl -sS -m "$MAX_SEC" "$BASE/api/health")"
python3 - <<PY
import json, sys
d = json.loads('''$HEALTH_JSON''')
if not d.get('watchdogFresh', True):
    print('watchdogFresh=false', file=sys.stderr)
    sys.exit(1)
PY
if [[ $? -ne 0 ]]; then
  fail "watchdogFresh=false"
fi
echo "  OK watchdog fresh"

check_http home "$BASE/api/home?deferred=1&playlistLimit=10" 1

PLAYLIST_ID="$(python3 - <<PY
import json, urllib.request
from pathlib import Path
manifest = json.loads(Path("$MANIFEST").read_text())
name = manifest["playlist"]["small"]["name"]
req = urllib.request.Request(
    "$BASE/api/playlists?search=" + urllib.parse.quote(name) + "&limit=5",
    headers={"Authorization": "Bearer $TOKEN"},
)
import urllib.parse
with urllib.request.urlopen(req, timeout=$MAX_SEC) as resp:
    data = json.load(resp)
items = data.get("items") or []
if items:
    print(items[0]["id"])
else:
    # fallback: first playlist in catalog
    req2 = urllib.request.Request(
        "$BASE/api/playlists?limit=1",
        headers={"Authorization": "Bearer $TOKEN"},
    )
    with urllib.request.urlopen(req2, timeout=$MAX_SEC) as resp2:
        data2 = json.load(resp2)
    print((data2.get("items") or [{}])[0].get("id", ""))
PY
)"

if [[ -n "$PLAYLIST_ID" ]]; then
  check_http "playlist/$PLAYLIST_ID" "$BASE/api/playlists/${PLAYLIST_ID}?limit=10" 1
else
  echo "  WARN no playlist id for detail preflight"
fi

if [[ "${SKIP_PLEX_LOCK_CHECK:-0}" != "1" ]] && command -v ssh >/dev/null; then
  NAS="${NAS:-user@your-server.local}"
  if ssh -o ConnectTimeout=3 -o BatchMode=yes "$NAS" 'test ! -f ~/.bockmedia/.ServerPlaylists.xml.lock || ! lsof ~/.bockmedia/.ServerPlaylists.xml.lock 2>/dev/null | grep -q python' 2>/dev/null; then
    echo "  OK playlist XML lock free"
  else
    fail "Plex sync may be holding ServerPlaylists.xml lock"
  fi
fi

echo "Preflight passed."
