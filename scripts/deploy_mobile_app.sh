#!/usr/bin/env bash
# Build Android APK and deploy to NAS with a version sidecar so /app always matches
# app-release-notes.json. Partial uploads (notes without APK stamp) caused version drift.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$REPO_ROOT/android/app/build.gradle.kts"
NOTES="$REPO_ROOT/app-release-notes.json"
NAS="${NAS:-plex@192.168.1.187}"
DATA_REMOTE="${DATA_REMOTE:-/home/plex/.bockmedia}"
REPO_REMOTE="${REPO_REMOTE:-/home/plex/Documents/github/ourMedia}"

version_from_gradle() {
  grep 'versionName' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/'
}

version_from_notes() {
  python3 - <<PY
import json
with open("$NOTES", encoding="utf-8") as fh:
    data = json.load(fh)
print(data["releases"][0]["version"])
PY
}

GRADLE_VER="$(version_from_gradle)"
NOTES_VER="$(version_from_notes)"

if [[ -z "$GRADLE_VER" || -z "$NOTES_VER" ]]; then
  echo "Could not read version from build.gradle.kts or app-release-notes.json" >&2
  exit 1
fi

if [[ "$GRADLE_VER" != "$NOTES_VER" ]]; then
  echo "Version mismatch: android/app/build.gradle.kts has $GRADLE_VER but app-release-notes.json latest is $NOTES_VER" >&2
  echo "Bump both to the same version before deploying." >&2
  exit 1
fi

echo "Building Android v${GRADLE_VER} (sideload — full-size APK for /app)…"
LOCAL_PROPS="$REPO_ROOT/android/local.properties"
if ! grep -q '^bockmedia.mobileApiToken=' "$LOCAL_PROPS" 2>/dev/null; then
  MOBILE_TOKEN="$(python3 - <<PY
import json
from pathlib import Path
cfg = json.loads(Path("$REPO_ROOT/config.json").read_text(encoding="utf-8"))
print(cfg.get("mobileApi", {}).get("token", "") or "")
PY
)"
  if [[ -n "$MOBILE_TOKEN" && "$MOBILE_TOKEN" != SET_* && "$MOBILE_TOKEN" != GENERATE_* ]]; then
    mkdir -p "$(dirname "$LOCAL_PROPS")"
    printf '\nbockmedia.mobileApiToken=%s\n' "$MOBILE_TOKEN" >> "$LOCAL_PROPS"
    echo "Injected mobile API token from config.json into android/local.properties for sideload build"
  fi
fi
(cd "$REPO_ROOT/android" && ./gradlew assembleSideload)

APK="$REPO_ROOT/android/app/build/outputs/apk/sideload/app-sideload.apk"
if [[ ! -f "$APK" ]]; then
  APK="$REPO_ROOT/android/app/build/outputs/apk/sideload/app-sideload-unsigned.apk"
fi
if [[ ! -f "$APK" ]]; then
  echo "APK not found after assembleSideload" >&2
  exit 1
fi
BUILD_TOOLS="$(ls -d "$HOME/Library/Android/sdk/build-tools/"* 2>/dev/null | sort -V | tail -1)"
if [[ -n "$BUILD_TOOLS" && -x "$BUILD_TOOLS/apksigner" ]]; then
  if ! "$BUILD_TOOLS/apksigner" verify --print-certs "$APK" >/dev/null 2>&1; then
    echo "Refusing to deploy unsigned sideload APK (devices reject INSTALL_PARSE_FAILED_NO_CERTIFICATES)" >&2
    exit 1
  fi
fi

mobile_api_token() {
  python3 - <<PY
import json, re
from pathlib import Path
root = Path("$REPO_ROOT")
props = root / "android/local.properties"
if props.exists():
    m = re.search(r'^bockmedia\\.mobileApiToken=(.+)$', props.read_text(), re.M)
    if m and m.group(1).strip():
        print(m.group(1).strip())
        raise SystemExit(0)
cfg = json.loads((root / "config.json").read_text(encoding="utf-8"))
print(cfg.get("mobileApi", {}).get("token", "") or "")
PY
}

remote_api_base() {
  python3 - <<PY
from pathlib import Path
props = Path("$REPO_ROOT/android/local.properties")
if props.exists():
    for line in props.read_text().splitlines():
        if line.startswith("bockmedia.externalServerUrl="):
            print(line.split("=", 1)[1].strip().rstrip("/"))
            raise SystemExit(0)
print("http://142.56.8.193:3001")
PY
}

upload_apk_remote() {
  local token base
  token="$(mobile_api_token)"
  base="$(remote_api_base)"
  if [[ -z "$token" ]]; then
    echo "No mobile API token for remote upload" >&2
    return 1
  fi
  echo "SSH unavailable — uploading APK via ${base}…"
  curl -fS --max-time 600 \
    -X PUT \
    -H "Authorization: Bearer ${token}" \
    -H "X-App-Version: ${GRADLE_VER}" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@${APK}" \
    "${base}/api/admin/mobile-app/android"
}

echo "Deploying to ${NAS}…"
if scp "$APK" "${NAS}:${DATA_REMOTE}/bockmedia-console.apk" \
  && ssh "$NAS" "printf '%s' '${GRADLE_VER}' > '${DATA_REMOTE}/bockmedia-console.version'" \
  && scp "$NOTES" "${NAS}:${REPO_REMOTE}/app-release-notes.json" \
  && scp "$GRADLE" "${NAS}:${REPO_REMOTE}/android/app/build.gradle.kts" \
  && scp "$REPO_ROOT/server.py" "${NAS}:${REPO_REMOTE}/server.py" \
  && { for mod in "$REPO_ROOT"/bock_*.py; do scp "$mod" "${NAS}:${REPO_REMOTE}/$(basename "$mod")"; done; true; } \
  && "$REPO_ROOT/scripts/deploy_web.sh" --no-restart \
  && ssh "$NAS" "sudo systemctl restart ourmedia"; then
  :
elif upload_apk_remote; then
  echo "Remote APK upload OK (release notes / server.py not synced — run full deploy on LAN when available)"
else
  echo "Deploy failed: NAS SSH unreachable and remote upload unavailable (update server.py on NAS first)." >&2
  exit 1
fi

echo "Deployed v${GRADLE_VER} ($(du -h "$APK" | awk '{print $1}'))"
echo "Verify: http://192.168.1.187:3001/app (download label and release notes should both say ${GRADLE_VER})"

ADB_SERIAL="${ANDROID_DEVICE:-}"
if [[ -z "$ADB_SERIAL" ]]; then
  ADB_SERIAL="$(adb devices 2>/dev/null | awk '/device$/{print $1; exit}')"
fi
if [[ -n "$ADB_SERIAL" ]]; then
  echo "Installing on ${ADB_SERIAL}…"
  adb -s "$ADB_SERIAL" install -r "$APK"
  adb -s "$ADB_SERIAL" shell am start -n com.bockmedia.console/com.bockmedia.console.MainActivity >/dev/null 2>&1 || true
  echo "Phone install OK (${ADB_SERIAL})"
else
  echo "Phone: run from repo root after wireless pairing:"
  echo "  adb connect 192.168.1.66:PORT && adb install -r android/app/build/outputs/apk/sideload/app-sideload.apk"
fi
