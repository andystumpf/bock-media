#!/usr/bin/env bash
# Generate strong WebPassword + mobileApi.token on the Bock Media server.
# Run ON THE SERVER (or via SSH):
#   bash scripts/rotate_server_credentials.sh
#
# Writes new credentials to $OURMEDIA_DATA_DIR/.last-credential-rotation (mode 600).
# Back up config first; restart ourmedia when done.

set -euo pipefail

DATA_DIR="${OURMEDIA_DATA_DIR:-$HOME/.bockmedia}"
PREFS="$DATA_DIR/Preferences.xml"
CONFIG="$DATA_DIR/config.json"
OUT="$DATA_DIR/.last-credential-rotation"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP="$DATA_DIR/backups/credentials-$STAMP"

log() { echo "[rotate-credentials] $*"; }

if [[ ! -f "$PREFS" || ! -f "$CONFIG" ]]; then
  log "Missing $PREFS or $CONFIG"
  exit 1
fi

mkdir -p "$BACKUP"
cp -a "$PREFS" "$BACKUP/Preferences.xml"
cp -a "$CONFIG" "$BACKUP/config.json"

read -r WEB_PASS MOBILE_TOKEN <<< "$(python3 << 'PY'
import json, secrets, xml.etree.ElementTree as ET
from pathlib import Path

data = Path.home() / ".bockmedia"
if __import__("os").environ.get("OURMEDIA_DATA_DIR"):
    data = Path(__import__("os").environ["OURMEDIA_DATA_DIR"])

prefs = data / "Preferences.xml"
config = data / "config.json"

web_pass = secrets.token_urlsafe(24)  # ~32 chars, URL-safe
mobile_token = secrets.token_urlsafe(36)  # ~48 chars

tree = ET.parse(prefs)
root = tree.getroot()
el = root.find("WebPassword")
if el is None:
    el = ET.SubElement(root, "WebPassword")
el.text = web_pass
req = root.find("RequirePassword")
if req is None:
    req = ET.SubElement(root, "RequirePassword")
req.text = "true"
tree.write(prefs, xml_declaration=True, encoding="unicode")

cfg = json.loads(config.read_text())
ma = cfg.setdefault("mobileApi", {})
ma["token"] = mobile_token
ma.setdefault("allowExternalAccess", True)
ma.setdefault("allowTunnelApi", True)
ma["allowOpenLanApi"] = False
ma["allowOpenLanMedia"] = False
config.write_text(json.dumps(cfg, indent=2) + "\n")

out = data / ".last-credential-rotation"
out.write_text(
    f"# Rotated {__import__('datetime').datetime.utcnow().isoformat()}Z\n"
    f"webUsername={root.find('WebUsername').text if root.find('WebUsername') is not None else 'admin'}\n"
    f"webPassword={web_pass}\n"
    f"mobileApiToken={mobile_token}\n"
)
out.chmod(0o600)

print(web_pass, mobile_token)
PY
)"

if systemctl is-active --quiet ourmedia.service 2>/dev/null; then
  log "Restarting ourmedia.service..."
  sudo systemctl restart ourmedia.service
elif systemctl is-active --quiet ourmedia 2>/dev/null; then
  sudo systemctl restart ourmedia
else
  log "ourmedia service not found — restart the server process manually."
fi

log "Done."
log "Backup: $BACKUP/"
log "New credentials: $OUT (chmod 600 — read once, then delete)"
log "Update: web browser login, Android/iOS app setup, and android/local.properties if you build APKs."
