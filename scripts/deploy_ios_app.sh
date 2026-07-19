#!/usr/bin/env bash
# Build and install Bock Media on a connected iOS device.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$REPO_ROOT/ios"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export PATH="$DEVELOPER_DIR/usr/bin:$PATH"

if ! command -v xcodebuild >/dev/null; then
  echo "Xcode required (xcodebuild not found). Set DEVELOPER_DIR to Xcode.app/Contents/Developer" >&2
  exit 1
fi

DEVICE_ID="${IOS_DEVICE_ID:-}"
if [[ -z "$DEVICE_ID" ]]; then
  DEVICE_ID="$(
    cd "$IOS"
    xcodebuild -showdestinations -scheme BockMedia 2>/dev/null \
      | grep -m1 'platform:iOS, arch:arm64, id:000' \
      | sed -n 's/.*id:\([^,]*\).*/\1/p'
  )"
fi
if [[ -z "$DEVICE_ID" ]]; then
  echo "No physical iOS device connected. Set IOS_DEVICE_ID." >&2
  exit 1
fi

DERIVED="${IOS_DERIVED_DATA:-/tmp/BockMediaDerived}"
APP="$DERIVED/Build/Products/Debug-iphoneos/BockMedia.app"

echo "Building Bock Media for device $DEVICE_ID …"
cd "$IOS"
if [[ -f project.yml ]] && command -v xcodegen >/dev/null; then
  xcodegen generate >/dev/null
fi

xcodebuild \
  -scheme BockMedia \
  -destination "platform=iOS,id=${DEVICE_ID}" \
  -allowProvisioningUpdates \
  -configuration Debug \
  -derivedDataPath "$DERIVED" \
  build

echo "Installing …"
xcrun devicectl device install app --device "$DEVICE_ID" "$APP"

if [[ "${LAUNCH:-1}" != "0" ]]; then
  echo "Launching com.bockmedia.console …"
  xcrun devicectl device process launch --device "$DEVICE_ID" com.bockmedia.console
fi

echo "iOS install OK (device ${DEVICE_ID})"
