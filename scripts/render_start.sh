#!/usr/bin/env bash
# Render.com web service entrypoint — demo dataset, no real Alexa tunnel required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export OURMEDIA_DATA_DIR="${OURMEDIA_DATA_DIR:-$ROOT/demo-data}"
export OURMEDIA_DB_PATH="${OURMEDIA_DB_PATH:-$OURMEDIA_DATA_DIR/music_organizer.db}"
export OURMEDIA_MUSIC_ROOT="${OURMEDIA_MUSIC_ROOT:-$OURMEDIA_DATA_DIR/music}"

if [[ ! -f "$OURMEDIA_DB_PATH" ]]; then
  echo "Seeding demo data (first boot)…"
  python scripts/seed_demo_data.py --base "$OURMEDIA_DATA_DIR" --state-dir "$ROOT" --config --alexa-remote
fi

if [[ -n "${RENDER_EXTERNAL_URL:-}" ]] && [[ -f "$ROOT/config.json" ]]; then
  export ROOT
  python - <<'PY'
import json, os
path = os.path.join(os.environ["ROOT"], "config.json")
with open(path) as f:
    cfg = json.load(f)
cfg["publicUrl"] = os.environ["RENDER_EXTERNAL_URL"].rstrip("/")
with open(path, "w") as f:
    json.dump(cfg, f, indent=2)
print("Set config publicUrl from RENDER_EXTERNAL_URL")
PY
fi

exec python server.py
