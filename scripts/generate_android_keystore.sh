#!/usr/bin/env bash
# Generate a release signing keystore for the Bock Media Android app.
# Run once, then copy keystore.properties.example → keystore.properties and fill in passwords.
set -euo pipefail
cd "$(dirname "$0")/../android"

KEYSTORE="${1:-release.keystore}"
ALIAS="${2:-bockmedia}"

if [[ -f "$KEYSTORE" ]]; then
  echo "Keystore already exists: $KEYSTORE" >&2
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Bock Media, OU=Personal, O=Bock Media, L=Local, ST=Local, C=US"

echo ""
echo "Created $KEYSTORE (alias: $ALIAS)"
echo "Next:"
echo "  cp keystore.properties.example keystore.properties"
echo "  # edit passwords, then:"
echo "  ./gradlew assembleRelease"
