#!/usr/bin/env bash
# Sync the full public/ tree to NAS — partial scp of individual files caused broken UI
# (stale shell.css, missing boot.js/webCache.js, files dropped in wrong folders).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAS="${NAS:-plex@192.168.1.187}"
REPO_REMOTE="${REPO_REMOTE:-/home/plex/Documents/github/ourMedia}"
PUBLIC="$REPO_ROOT/public"
REMOTE_PUBLIC="${REPO_REMOTE}/public"

required=(
  "$PUBLIC/index.html"
  "$PUBLIC/css/shell.css"
  "$PUBLIC/css/style.css"
  "$PUBLIC/css/dark-theme.css"
  "$PUBLIC/js/app.js"
  "$PUBLIC/js/boot.js"
  "$PUBLIC/js/webCache.js"
  "$PUBLIC/js/homeFeed.js"
  "$PUBLIC/js/webPlayback.js"
)
for f in "${required[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "Missing required web asset: $f" >&2
    exit 1
  fi
done

echo "Syncing public/ → ${NAS}:${REMOTE_PUBLIC}/"
rsync -avz --delete \
  --exclude '.DS_Store' \
  "$PUBLIC/" "${NAS}:${REMOTE_PUBLIC}/"

# Remove mistaken copies from bad partial deploys (scp target typos)
ssh "$NAS" "rm -f '${REPO_REMOTE}/public/app.js' '${REPO_REMOTE}/public/shell.css' '${REPO_REMOTE}/public/dark-theme.css' \
  '${REMOTE_PUBLIC}/css/app.js' '${REMOTE_PUBLIC}/css/index.html' 2>/dev/null || true"

if [[ "${1:-}" != "--no-restart" ]]; then
  ssh "$NAS" "sudo systemctl restart ourmedia"
fi

echo "Web deploy OK. Verify shell.css size:"
ssh "$NAS" "wc -c '${REMOTE_PUBLIC}/css/shell.css' '${REMOTE_PUBLIC}/js/boot.js' '${REMOTE_PUBLIC}/js/webCache.js'"
