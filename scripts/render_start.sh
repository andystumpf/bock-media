#!/usr/bin/env bash
# Render.com web service entrypoint — demo dataset, no real Alexa tunnel required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export OURMEDIA_DATA_DIR="${OURMEDIA_DATA_DIR:-$ROOT/fixtures/demo-data}"
export OURMEDIA_DB_PATH="${OURMEDIA_DB_PATH:-$OURMEDIA_DATA_DIR/songs_cache.db}"
export OURMEDIA_MUSIC_ROOT="${OURMEDIA_MUSIC_ROOT:-$OURMEDIA_DATA_DIR/music}"
export OURMEDIA_ALLOW_PUBLIC_CONSOLE="${OURMEDIA_ALLOW_PUBLIC_CONSOLE:-true}"

if [[ ! -f "$OURMEDIA_DB_PATH" ]]; then
  echo "Seeding demo data (first boot)…"
  python3 scripts/seed_demo_library.py
fi

cfg_path="$OURMEDIA_DATA_DIR/config.json"
if [[ -n "${RENDER_EXTERNAL_URL:-}" ]] && [[ -f "$cfg_path" ]]; then
  export cfg_path
  python3 - <<'PY'
import json, os
path = os.environ["cfg_path"]
with open(path) as f:
    cfg = json.load(f)
cfg["publicUrl"] = os.environ["RENDER_EXTERNAL_URL"].rstrip("/")
with open(path, "w") as f:
    json.dump(cfg, f, indent=2)
print("Set config publicUrl from RENDER_EXTERNAL_URL")
PY
fi

exec python3 server.py
