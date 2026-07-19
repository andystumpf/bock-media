#!/bin/bash
# Production wrapper for cron — sets paths to match ourmedia.service.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export OURMEDIA_DATA_DIR="${OURMEDIA_DATA_DIR:-${HOME}/.bockmedia}"
export OURMEDIA_MUSIC_ROOT="${OURMEDIA_MUSIC_ROOT:-/mnt/bock/Music}"
export OURMEDIA_DB_PATH="${OURMEDIA_DB_PATH:-/mnt/bock/Music/music_organizer.db}"
export OURMEDIA_PLEX_URL="${OURMEDIA_PLEX_URL:-http://127.0.0.1:32400}"
exec /usr/bin/python3 "$ROOT/scripts/sync_plex_playlists.py" "$@"
