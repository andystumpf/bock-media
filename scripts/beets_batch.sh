#!/usr/bin/env bash
# Headless MusicBrainz tagging via beets (no GUI).
# Reuses picard-queue-dirs.tsv; syncs with scripts/after_picard.sh afterward.
#
#   pip3 install --user 'beets>=2.0'
#   python3 scripts/picard_queue.py --fast
#   ./scripts/beets_batch.sh --limit 5
#   ./scripts/beets_batch.sh --sleep 15 --sync-every 50 --resume
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${OURMEDIA_DATA_DIR:-$HOME/.bockmedia}"
BEETSDIR="${BEETSDIR:-$DATA_DIR/beets}"
DIRS_TSV="${DIRS_TSV:-$DATA_DIR/picard-queue-dirs.tsv}"
STATE_FILE="${STATE_FILE:-$DATA_DIR/beets-batch.state}"
LOG_FILE="${LOG_FILE:-$DATA_DIR/beets-batch.log}"

START=0
LIMIT=0
SLEEP_SEC=15
SYNC_EVERY=0
DRY_RUN=0
RESUME=0

usage() {
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
  echo ""
  echo "Options:"
  echo "  --start N         Skip first N folders in TSV"
  echo "  --limit N         Max folders (0 = all)"
  echo "  --sleep SEC       Pause between folders"
  echo "  --sync-every N    Run after_picard.sh every N folders (0 = end only)"
  echo "  --resume          Continue from $STATE_FILE"
  echo "  --dry-run         List folders only"
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start) START="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --sleep) SLEEP_SEC="$2"; shift 2 ;;
    --sync-every) SYNC_EVERY="$2"; shift 2 ;;
    --resume) RESUME=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

log() {
  printf '%s %s\n' "$(date -Iseconds)" "$*" | tee -a "$LOG_FILE"
}

if ! command -v beet >/dev/null 2>&1; then
  if [[ -x "$HOME/.local/bin/beet" ]]; then
    export PATH="$HOME/.local/bin:$PATH"
  else
    echo "beet not found. Install: pip3 install --user 'beets>=2.0'" >&2
    exit 1
  fi
fi

if [[ ! -f "$DIRS_TSV" ]]; then
  echo "Missing $DIRS_TSV — run: python3 scripts/picard_queue.py --fast" >&2
  exit 1
fi

mkdir -p "$BEETSDIR" "$DATA_DIR"
if [[ ! -f "$BEETSDIR/config.yaml" ]]; then
  cp "$REPO/scripts/beets/config.yaml" "$BEETSDIR/config.yaml"
  log "installed default config -> $BEETSDIR/config.yaml"
fi
export BEETSDIR

if [[ "$RESUME" -eq 1 && -f "$STATE_FILE" ]]; then
  START="$(cat "$STATE_FILE")"
  log "resuming at folder index $START"
fi

log "beets_batch start beet=$(beet version 2>/dev/null | head -1 || beet --version)"

processed=0
data_idx=-1
while IFS=$'\t' read -r count dir; do
  [[ "$count" == "tracks" && "$dir" == "directory" ]] && continue
  [[ -z "${dir:-}" ]] && continue
  data_idx=$((data_idx + 1))
  [[ "$data_idx" -lt "$START" ]] && continue
  [[ "$LIMIT" -gt 0 && "$processed" -ge "$LIMIT" ]] && break

  if [[ ! -d "$dir" ]]; then
    log "SKIP missing ($count tracks): $dir"
    echo "$((data_idx + 1))" > "$STATE_FILE"
    processed=$((processed + 1))
    continue
  fi

  log "FOLDER [$((processed + 1))] $count tracks: $dir"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    processed=$((processed + 1))
    echo "$((data_idx + 1))" > "$STATE_FILE"
    continue
  fi

  # -q never prompts; -A skip album art; -I force re-import (incremental skips whole dirs)
  if ! beet import -qA -I "$dir" >> "$LOG_FILE" 2>&1; then
    log "WARN: beet import had errors on: $dir (continuing)"
  fi

  processed=$((processed + 1))
  echo "$((data_idx + 1))" > "$STATE_FILE"

  if [[ "$SYNC_EVERY" -gt 0 && $((processed % SYNC_EVERY)) -eq 0 ]]; then
    log "sync after $processed folders"
    "$REPO/scripts/after_picard.sh" >> "$LOG_FILE" 2>&1 || log "WARN: after_picard.sh failed"
  fi

  [[ "$SLEEP_SEC" -gt 0 ]] && sleep "$SLEEP_SEC"
done < "$DIRS_TSV"

if [[ "$DRY_RUN" -eq 0 && "$processed" -gt 0 ]]; then
  log "final sync"
  "$REPO/scripts/after_picard.sh" >> "$LOG_FILE" 2>&1 || log "WARN: after_picard.sh failed"
fi

log "done folders=$processed"
echo "Log: $LOG_FILE"
