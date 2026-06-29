#!/usr/bin/env bash
# Pull Picard-written tags from audio files into songs_cache (DB only).
# Does not change album/title/artist in the DB — keeps [YEAR] Album Name from indexer.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> backfill genres + year (from file tags)"
python3 scripts/backfill_genres.py

echo "==> backfill album_artist, track/disc, year (missing only, DB only)"
python3 scripts/backfill_metadata.py \
  --fields album_artist,track_number,disc_number,year \
  --only-missing \
  --no-write-files

echo "==> rebuild [YEAR] Album from Picard tags (Unknown Album / [1900] / no prefix only)"
python3 scripts/backfill_album_year_prefix.py

echo "==> audit"
python3 scripts/audit_metadata.py --no-tags

echo "Done. Restart not required — server reads songs_cache on each request."
