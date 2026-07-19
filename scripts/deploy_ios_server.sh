#!/usr/bin/env bash
# Archive, export IPA, and deploy to NAS / remote API for /app + topbar download links.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$REPO_ROOT/ios"
NOTES="$REPO_ROOT/app-release-notes.json"
NAS="${NAS:-user@your-server.local}"
REPO_REMOTE="${REPO_REMOTE:-~/bock-media}"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

version_from_notes() {
  python3 - <<PY
import json
with open("$NOTES", encoding="utf-8") as fh:
    print(json.load(fh)["releases"][0]["version"])
PY
}

VER="$(version_from_notes)"
ARCHIVE="${IOS_DERIVED_DATA:-/tmp/BockMediaDerived}/Build/Intermediates.noindex/ArchiveIntermediates/BockMedia/InstallationBuildProductsLocation/Applications/BockMedia.app"
DERIVED="${IOS_DERIVED_DATA:-/tmp/BockMediaDerived}"
EXPORT_DIR="$DERIVED/ipa-export"
IPA="$EXPORT_DIR/bockmedia-console.ipa"

echo "Building iOS v${VER} for server OTA…"
cd "$IOS"
if [[ -f project.yml ]] && command -v xcodegen >/dev/null; then
  xcodegen generate >/dev/null
fi

xcodebuild \
  -scheme BockMedia \
  -destination 'generic/platform=iOS' \
  -allowProvisioningUpdates \
  -configuration Debug \
  -derivedDataPath "$DERIVED" \
  -archivePath "$DERIVED/BockMedia.xcarchive" \
  archive

mkdir -p "$EXPORT_DIR"
xcodebuild \
  -exportArchive \
  -archivePath "$DERIVED/BockMedia.xcarchive" \
  -exportPath "$EXPORT_DIR" \
  -exportOptionsPlist "$IOS/ExportOptions.plist" \
  -allowProvisioningUpdates

if [[ -f "$EXPORT_DIR/BockMedia.ipa" ]]; then
  mv -f "$EXPORT_DIR/BockMedia.ipa" "$IPA"
fi
if [[ ! -f "$IPA" ]]; then
  echo "IPA export failed — expected $IPA" >&2
  exit 1
fi

echo "Deploying IPA to ${NAS}…"
if scp "$IPA" "${NAS}:.bockmedia/bockmedia-console.ipa" \
  && ssh "$NAS" "printf '%s' '${VER}' > .bockmedia/bockmedia-console-ios.version"; then
  echo "NAS iOS upload OK (v${VER})"
else
  TOKEN="$(python3 - <<PY
import json, re
from pathlib import Path
root = Path("$REPO_ROOT")
for path in (root / "android/local.properties", root / "config.json"):
    if path.name == "local.properties" and path.exists():
        m = re.search(r'^bockmedia\\.mobileApiToken=(.+)$', path.read_text(), re.M)
        if m and m.group(1).strip():
            print(m.group(1).strip())
            break
    elif path.name == "config.json":
        tok = json.loads(path.read_text()).get("mobileApi", {}).get("token", "")
        if tok:
            print(tok)
            break
PY
)"
  BASE="$(python3 - <<PY
import json, re
from pathlib import Path
root = Path("$REPO_ROOT")
props = root / "android/local.properties"
if props.exists():
    for line in props.read_text().splitlines():
        if line.startswith("bockmedia.externalServerUrl="):
            print(line.split("=", 1)[1].strip().rstrip("/"))
            raise SystemExit(0)
print("https://your-tunnel.example.com")
PY
)"
  if [[ -z "$TOKEN" ]]; then
    echo "NAS SSH failed and no mobile API token for remote upload" >&2
    exit 1
  fi
  echo "SSH unavailable — uploading IPA via ${BASE}…"
  curl -fS --max-time 600 \
    -X PUT \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "X-App-Version: ${VER}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@${IPA}" \
    "${BASE}/api/admin/mobile-app/ios"
fi

echo "iOS server deploy OK v${VER} ($(du -h "$IPA" | awk '{print $1}'))"
echo "Verify: ${BASE:-https://your-tunnel.example.com}/api/app/info"
