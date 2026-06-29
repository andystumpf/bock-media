#!/usr/bin/env bash
# Upload sideload APK to the server over HTTPS (no SSH). Requires server.py with
# PUT /api/admin/mobile-app/android (deploy server once via LAN, then use this off-LAN).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$REPO_ROOT/android/app/build.gradle.kts"
APK="${1:-$REPO_ROOT/android/app/build/outputs/apk/sideload/app-sideload.apk}"

version_from_gradle() {
  grep 'versionName' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/'
}

VER="$(version_from_gradle)"
TOKEN="$(python3 - <<PY
import json, re
from pathlib import Path
for path in (Path("$REPO_ROOT/android/local.properties"), Path("$REPO_ROOT/config.json")):
    if path.name == "local.properties":
        m = re.search(r'^bockmedia\\.mobileApiToken=(.+)$', path.read_text(), re.M)
        if m and m.group(1).strip(): print(m.group(1).strip()); break
    else:
        tok = json.loads(path.read_text()).get("mobileApi", {}).get("token", "")
        if tok: print(tok); break
PY
)"

BASE="${REMOTE_API_BASE:-$(python3 - <<PY
import json
from pathlib import Path
cfg = json.loads(Path("$REPO_ROOT/config.json").read_text())
ext = Path("$REPO_ROOT/android/local.properties").read_text() if Path("$REPO_ROOT/android/local.properties").exists() else ""
for line in ext.splitlines():
    if line.startswith("bockmedia.externalServerUrl="):
        print(line.split("=", 1)[1].strip().rstrip("/"))
        break
else:
    print("http://127.0.0.1:3001")
PY
)}"

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  exit 1
fi
if [[ -z "$TOKEN" ]]; then
  echo "No mobile API token in android/local.properties or config.json" >&2
  exit 1
fi

echo "Uploading v${VER} to ${BASE}…"
curl -fS --max-time 600 \
  -X PUT \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "X-App-Version: ${VER}" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@${APK}" \
  "${BASE}/api/admin/mobile-app/android"

echo ""
echo "Verify: curl -H 'Authorization: Bearer …' ${BASE}/api/app/info | python3 -m json.tool | head -20"
