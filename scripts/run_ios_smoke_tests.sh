#!/usr/bin/env bash
# Run functional smoke tests on a connected iOS device (server must be configured).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$REPO_ROOT/ios"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

if ! command -v xcodebuild >/dev/null; then
  echo "Xcode required (xcodebuild not found). Set DEVELOPER_DIR to Xcode.app/Contents/Developer" >&2
  exit 1
fi

DEVICE="${IOS_DEVICE:-}"
DEVICE_ID="${IOS_DEVICE_ID:-}"
if [[ -z "$DEVICE_ID" && -z "$DEVICE" ]]; then
  DEVICE_ID="$(
    cd "$IOS"
    xcodebuild -showdestinations -scheme BockMedia 2>/dev/null \
      | grep -m1 'platform:iOS, arch:arm64, id:000' \
      | sed -n 's/.*id:\([^,]*\).*/\1/p'
  )"
fi
if [[ -z "$DEVICE_ID" && -z "$DEVICE" ]]; then
  echo "No physical iOS device connected. Set IOS_DEVICE_ID or IOS_DEVICE." >&2
  exit 1
fi

if [[ -n "$DEVICE_ID" ]]; then
  DESTINATION="platform=iOS,id=${DEVICE_ID}"
  DEVICE_LABEL="$DEVICE_ID"
else
  DESTINATION="platform=iOS,name=${DEVICE}"
  DEVICE_LABEL="$DEVICE"
fi

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
        print(m.group(1).strip())
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

TIMEOUT_MS="${SMOKE_TIMEOUT_MS:-45000}"
SMOKE_QUERY="${SMOKE_QUERY:-love}"
ONLY_TEST="${ONLY_TEST:-BockMediaUITests/BockDeviceSmokeTests}"

echo "Running iOS UI tests ($ONLY_TEST) on \"$DEVICE_LABEL\" …"
cd "$IOS"

if [[ -f project.yml ]] && command -v xcodegen >/dev/null; then
  xcodegen generate >/dev/null
fi

xcodebuild test \
  -scheme BockMedia \
  -destination "$DESTINATION" \
  -allowProvisioningUpdates \
  -skip-testing:BockMediaTests \
  -only-testing:"$ONLY_TEST" \
  BOCK_TEST_SERVER_URL="$SERVER_URL" \
  BOCK_TEST_API_TOKEN="$TOKEN" \
  SMOKE_TIMEOUT_MS="$TIMEOUT_MS" \
  SMOKE_SEARCH_QUERY="$SMOKE_QUERY" \
  SMOKE_SHORT_SEARCH_QUERY="${SMOKE_SHORT_SEARCH_QUERY:-ab}" \
  "$@"

echo "iOS smoke tests finished."
