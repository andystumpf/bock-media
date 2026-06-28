#!/usr/bin/env bash
# Export logged-in YouTube cookies for Bock music-video streaming on the server.
# YouTube blocks anonymous access from the NAS IP; yt-dlp needs these cookies.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAS="${NAS:-plex@192.168.1.187}"
DATA_REMOTE="${DATA_REMOTE:-/home/plex/.bockmedia}"
BROWSER="${YOUTUBE_COOKIES_BROWSER:-chrome}"
OUT="${1:-/tmp/youtube-cookies.txt}"
REMOTE="${DATA_REMOTE}/youtube-cookies.txt"

if ! command -v yt-dlp >/dev/null; then
  echo "yt-dlp required (brew install yt-dlp)" >&2
  exit 1
fi

echo "Exporting YouTube cookies from ${BROWSER}…"
yt-dlp --no-update --cookies-from-browser "$BROWSER" --cookies "$OUT" \
  --skip-download "https://www.youtube.com/watch?v=jNQXAC9IVRw"

echo "Uploading to ${NAS}:${REMOTE}…"
scp "$OUT" "${NAS}:${REMOTE}"
ssh "$NAS" "chmod 600 '${REMOTE}'"

# Deno is required on the server for YouTube stream extraction (yt-dlp EJS).
if ! ssh "$NAS" "test -x ~/.deno/bin/deno"; then
  echo "Installing Deno on server (required for music-video streams)…"
  ssh "$NAS" "curl -fsSL https://deno.land/install.sh | sh"
fi

echo "Done. Restart ourmedia if videos still fail: ssh ${NAS} sudo systemctl restart ourmedia"
