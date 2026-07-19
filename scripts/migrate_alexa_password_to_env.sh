#!/usr/bin/env bash
# Move alexaRemote.password from config.json to systemd env (ALEXA_REMOTE_PASSWORD).
# Run on the NAS host as the user that owns ~/.bockmedia.
set -euo pipefail

DATA="${DATA_DIR:-$HOME/.bockmedia}"
CFG="${DATA}/config.json"
UNIT="${OURMEDIA_UNIT:-ourmedia}"
DROPIN_DIR="/etc/systemd/system/${UNIT}.service.d"
DROPIN="${DROPIN_DIR}/alexa-remote.conf"

if [[ ! -f "$CFG" ]]; then
  echo "config not found: $CFG" >&2
  exit 1
fi

PASS="$(python3 - <<PY
import json
from pathlib import Path
cfg = json.loads(Path("$CFG").read_text(encoding="utf-8"))
print((cfg.get("alexaRemote") or {}).get("password") or "")
PY
)"

ALEXA_REMOTE_PASSWORD="${ALEXA_REMOTE_PASSWORD:-$PASS}"

if [[ -z "$ALEXA_REMOTE_PASSWORD" ]]; then
  echo "No alexaRemote.password in config — nothing to migrate"
  exit 0
fi

sudo mkdir -p "$DROPIN_DIR"
sudo tee "$DROPIN" >/dev/null <<EOF
[Service]
Environment=ALEXA_REMOTE_PASSWORD=${ALEXA_REMOTE_PASSWORD}
EOF

python3 - <<PY
import json
from pathlib import Path
p = Path("$CFG")
cfg = json.loads(p.read_text(encoding="utf-8"))
ar = cfg.setdefault("alexaRemote", {})
ar.pop("password", None)
p.write_text(json.dumps(cfg, indent=2) + "\n", encoding="utf-8")
print("Removed password from config.json")
PY

sudo systemctl daemon-reload
sudo systemctl restart "$UNIT"
echo "Done — alexaRemote.password moved to ${DROPIN}"
