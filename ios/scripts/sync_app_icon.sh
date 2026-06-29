#!/usr/bin/env bash
# Keep iOS home-screen icon in sync with Android / web (public/img/icon-512.png).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/public/img/icon-512.png"
DEST="$ROOT/ios/BockMedia/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png"

if [[ ! -f "$SRC" ]]; then
  echo "Missing source icon: $SRC" >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
sips -z 1024 1024 "$SRC" --out "$DEST" >/dev/null
echo "Updated iOS AppIcon from $SRC"
