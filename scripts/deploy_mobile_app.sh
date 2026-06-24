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
(cd "$REPO_ROOT/android" && ./gradlew assembleSideload)

APK="$REPO_ROOT/android/app/build/outputs/apk/sideload/app-sideload-unsigned.apk"
if [[ ! -f "$APK" ]]; then
  APK="$REPO_ROOT/android/app/build/outputs/apk/sideload/app-sideload.apk"
fi
if [[ ! -f "$APK" ]]; then
  echo "APK not found after assembleSideload" >&2
  exit 1
fi

echo "Deploying to ${NAS}…"
scp "$APK" "${NAS}:${DATA_REMOTE}/bockmedia-console.apk"
ssh "$NAS" "printf '%s' '${GRADLE_VER}' > '${DATA_REMOTE}/bockmedia-console.version'"
scp "$NOTES" "${NAS}:${REPO_REMOTE}/app-release-notes.json"
scp "$GRADLE" "${NAS}:${REPO_REMOTE}/android/app/build.gradle.kts"
scp "$REPO_ROOT/server.py" "${NAS}:${REPO_REMOTE}/server.py"
"$REPO_ROOT/scripts/deploy_web.sh" --no-restart

ssh "$NAS" "sudo systemctl restart ourmedia"

echo "Deployed v${GRADLE_VER} ($(du -h "$APK" | awk '{print $1}'))"
echo "Verify: http://192.168.1.187:3001/app (download label and release notes should both say ${GRADLE_VER})"
