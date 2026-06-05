#!/usr/bin/env bash
# Batch-tag folders from picard-queue-dirs.tsv using Picard 2.9+ CLI.
# Ubuntu apt ships 2.7 (no CLUSTER/SAVE_MATCHED) — install Flatpak Picard first.
#
#   sudo snap install picard && sudo snap connect picard:removable-media
#   # or: flatpak install flathub org.musicbrainz.Picard
#   python3 scripts/picard_queue.py --fast
#   ./scripts/picard_batch.sh --limit 5          # pilot
#   ./scripts/picard_batch.sh                      # full queue (long run)
#   ./scripts/picard_batch.sh --resume             # continue after interrupt
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${OURMEDIA_DATA_DIR:-$HOME/.bockmedia}"
DIRS_TSV="${DIRS_TSV:-$DATA_DIR/picard-queue-dirs.tsv}"
COMMANDS="${COMMANDS:-$REPO/scripts/picard/commands-batch.txt}"
STATE_FILE="${STATE_FILE:-$DATA_DIR/picard-batch.state}"
LOG_FILE="${LOG_FILE:-$DATA_DIR/picard-batch.log}"

START=0
LIMIT=0
SLEEP_SEC=15
SYNC_EVERY=0
DRY_RUN=0
RESUME=0

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  echo ""
  echo "Options:"
  echo "  --start N         Skip first N data rows in the TSV (after header)"
  echo "  --limit N         Process at most N folders (0 = all)"
  echo "  --sleep SEC       Pause between folders (MusicBrainz rate limits)"
  echo "  --sync-every N    Run scripts/after_picard.sh every N folders (0 = end only)"
  echo "  --resume          Start at saved line in $STATE_FILE"
  echo "  --dry-run         Print folders only"
  echo "  --dirs-tsv PATH   Override queue TSV"
  echo "  --commands PATH   Override Picard command file"
  echo "  PICARD_BIN=...    Picard executable (auto-detects Flatpak)"
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
    --dirs-tsv) DIRS_TSV="$2"; shift 2 ;;
    --commands) COMMANDS="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

log() {
  printf '%s %s\n' "$(date -Iseconds)" "$*" | tee -a "$LOG_FILE"
}

picard_version_ge_29() {
  local ver="$1"
  local major minor
  major="${ver%%.*}"
  ver="${ver#*.}"
  minor="${ver%%.*}"
  [[ "$major" -gt 2 ]] || { [[ "$major" -eq 2 ]] && [[ "$minor" -ge 9 ]]; }
}

picard_candidates() {
  [[ -n "${PICARD_BIN:-}" ]] && echo "$PICARD_BIN"
  [[ -x /snap/bin/picard ]] && echo /snap/bin/picard
  if command -v flatpak >/dev/null 2>&1 && flatpak info org.musicbrainz.Picard &>/dev/null; then
    echo "flatpak run --command=picard org.musicbrainz.Picard"
  fi
  command -v picard >/dev/null 2>&1 && echo picard
}

find_picard() {
  local cand ver
  while IFS= read -r cand; do
    [[ -z "$cand" ]] && continue
    # shellcheck disable=SC2206
    local arr=($cand)
    ver="$(picard_version_string "${arr[@]}")"
    if [[ -n "$ver" ]] && picard_version_ge_29 "$ver"; then
      echo "$cand"
      return 0
    fi
  done < <(picard_candidates)
  return 1
}

picard_version_string() {
  local bin=("$@")
  "${bin[@]}" --version 2>/dev/null | awk '{print $NF}'
}

run_picard_folder() {
  local dir="$1"
  # shellcheck disable=SC2206
  local bin=($PICARD_WRAPPER)
  "${bin[@]}" -e "LOAD $dir" -e "FROM_FILE $COMMANDS"
}

if [[ ! -f "$DIRS_TSV" ]]; then
  echo "Missing $DIRS_TSV — run: python3 scripts/picard_queue.py --fast" >&2
  exit 1
fi
if [[ ! -f "$COMMANDS" ]]; then
  echo "Missing command file: $COMMANDS" >&2
  exit 1
fi

if ! PICARD_WRAPPER="$(find_picard)"; then
  log "ERROR: Picard 2.9+ not found. Install one of:"
  log "  sudo snap install picard && sudo snap connect picard:removable-media"
  log "  sudo add-apt-repository -y ppa:musicbrainz-developers/stable && sudo apt install picard"
  log "  flatpak install flathub org.musicbrainz.Picard"
  exit 1
fi

# shellcheck disable=SC2206
PICARD_ARR=($PICARD_WRAPPER)
VER="$(picard_version_string "${PICARD_ARR[@]}")"

if [[ "$RESUME" -eq 1 && -f "$STATE_FILE" ]]; then
  START="$(cat "$STATE_FILE")"
  log "Resuming at TSV data row $START"
fi

mkdir -p "$DATA_DIR"
log "picard_batch start ver=$VER start=$START limit=$LIMIT sleep=$SLEEP_SEC"

processed=0
data_idx=0
while IFS=$'\t' read -r count dir; do
  if [[ "$count" == "tracks" && "$dir" == "directory" ]]; then
    continue
  fi
  [[ -z "${dir:-}" ]] && continue

  if [[ "$data_idx" -lt "$START" ]]; then
    data_idx=$((data_idx + 1))
    continue
  fi

  if [[ "$LIMIT" -gt 0 && "$processed" -ge "$LIMIT" ]]; then
    break
  fi

  if [[ ! -d "$dir" ]]; then
    log "SKIP missing dir ($count tracks): $dir"
    data_idx=$((data_idx + 1))
    echo "$data_idx" > "$STATE_FILE"
    continue
  fi

  log "FOLDER [$((processed + 1))] $count tracks: $dir"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    processed=$((processed + 1))
    data_idx=$((data_idx + 1))
    echo "$data_idx" > "$STATE_FILE"
    continue
  fi

  if ! run_picard_folder "$dir"; then
    log "ERROR: Picard failed on: $dir (state saved; re-run with --resume)"
    echo "$data_idx" > "$STATE_FILE"
    exit 1
  fi

  processed=$((processed + 1))
  data_idx=$((data_idx + 1))
  echo "$data_idx" > "$STATE_FILE"

  if [[ "$SYNC_EVERY" -gt 0 && $((processed % SYNC_EVERY)) -eq 0 ]]; then
    log "sync after $processed folders"
    "$REPO/scripts/after_picard.sh" >> "$LOG_FILE" 2>&1 || log "WARN: after_picard.sh failed"
  fi

  if [[ "$SLEEP_SEC" -gt 0 ]]; then
    sleep "$SLEEP_SEC"
  fi
done < "$DIRS_TSV"

if [[ "$DRY_RUN" -eq 0 && "$processed" -gt 0 ]]; then
  log "final sync"
  "$REPO/scripts/after_picard.sh" >> "$LOG_FILE" 2>&1 || log "WARN: after_picard.sh failed"
fi

log "done folders=$processed"
echo "Log: $LOG_FILE"
