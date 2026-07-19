#!/usr/bin/env bash
# Export logged-in YouTube cookies from this Mac and upload to the Bock NAS.
# YouTube blocks anonymous/datacenter IPs — music video streaming needs fresh cookies.
#
# Usage:
#   ./scripts/youtube_cookies.sh
#   NAS=user@your-server.local YOUTUBE_COOKIES_BROWSER=chrome ./scripts/youtube_cookies.sh
#
# Schedule (Mac): ./scripts/install_youtube_cookies_automation.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAS="${NAS:-user@your-server.local}"
DATA_REMOTE="${DATA_REMOTE:-~/.bockmedia}"
OUT="${1:-/tmp/youtube-cookies.txt}"
VERIFY_URL="${YOUTUBE_COOKIES_VERIFY_URL:-https://www.youtube.com/watch?v=jNQXAC9IVRw}"
RESTART_SERVICE="${YOUTUBE_COOKIES_RESTART:-1}"

log() { printf '[youtube-cookies] %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

command -v yt-dlp >/dev/null || die "yt-dlp required (brew install yt-dlp)"
command -v scp >/dev/null && command -v ssh >/dev/null || die "scp/ssh required"

REMOTE_DIR="$(ssh "$NAS" "bash -lc 'echo ${DATA_REMOTE}'")"
REMOTE="${REMOTE_DIR}/youtube-cookies.txt"
VERIFY_JSON="${REMOTE_DIR}/youtube-cookies-verify.json"

# Browser/profile fallbacks — Chrome cookie rotation often needs "Profile 1".
BROWSER_SPECS=()
if [[ -n "${YOUTUBE_COOKIES_BROWSER:-}" ]]; then
  BROWSER_SPECS+=("$YOUTUBE_COOKIES_BROWSER")
else
  BROWSER_SPECS+=(
    'chrome:Profile 1'
    'chrome:Default'
    'chrome'
    'chromium:Default'
    'chromium'
    'brave:Default'
    'brave'
    'firefox'
    'safari'
  )
fi

# A usable export must include first-party YouTube login cookies. Profiles that
# are not signed into YouTube export only 3P cookies and yt-dlp gets
# "Sign in to confirm you're not a bot" on every request.
has_login_cookies() {
  awk -F'\t' '$1 ~ /youtube/ && ($6 == "LOGIN_INFO" || $6 == "SAPISID" || $6 == "__Secure-1PSID") {found=1} END {exit !found}' "$1"
}

export_ok=false
last_err=""
for spec in "${BROWSER_SPECS[@]}"; do
  log "Trying cookies-from-browser ${spec}…"
  rm -f "$OUT"
  if yt-dlp --no-update --cookies-from-browser "$spec" --cookies "$OUT" \
      --skip-download "$VERIFY_URL" 2>/tmp/youtube-cookies-export.err; then
    if [[ -s "$OUT" ]] && has_login_cookies "$OUT"; then
      log "Exported from ${spec} (login cookies present)"
      export_ok=true
      break
    elif [[ -s "$OUT" ]]; then
      last_err="${spec} is not logged into YouTube (no LOGIN_INFO/SAPISID cookie)"
      log "  skipped: ${last_err}"
    else
      last_err="empty cookie file from ${spec}"
    fi
  else
    last_err="$(tail -3 /tmp/youtube-cookies-export.err 2>/dev/null | tr '\n' ' ')"
    log "  failed: ${last_err:-export error}"
  fi
done

[[ "$export_ok" == true ]] || die "Could not export logged-in cookies (${last_err:-all browsers failed}). Open youtube.com in Chrome, sign in, then retry."

log "Uploading to ${NAS}:${REMOTE}…"
scp "$OUT" "${NAS}:${REMOTE}"
ssh "$NAS" "mkdir -p $(printf '%q' "$REMOTE_DIR") && chmod 600 $(printf '%q' "$REMOTE")"

if ! ssh "$NAS" "test -x ~/.deno/bin/deno"; then
  log "Installing Deno on server (required for YouTube stream extraction)…"
  ssh "$NAS" "curl -fsSL https://deno.land/install.sh | sh"
fi

log "Verifying cookies on NAS…"
REMOTE_Q="$(printf '%q' "$REMOTE")"
URL_Q="$(printf '%q' "$VERIFY_URL")"
if ! ssh "$NAS" "export PATH=\"\${HOME}/.deno/bin:\$PATH\"; yt-dlp --no-update --js-runtimes deno:\${HOME}/.deno/bin/deno --cookies $REMOTE_Q -g --no-warnings $URL_Q 2>/dev/null | head -1 | grep -q googlevideo"; then
  die "NAS verification failed — re-login to YouTube in your browser, then retry"
fi

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ssh "$NAS" "printf '%s\n' '{\"ok\":true,\"verifiedAt\":\"${TS}\"}' > $(printf '%q' "$VERIFY_JSON")"

if [[ "$RESTART_SERVICE" == "1" ]]; then
  log "Restarting ourmedia…"
  ssh "$NAS" "sudo systemctl restart ourmedia" || log "WARN: could not restart ourmedia (run manually)"
fi

log "Done — music video cookies refreshed and verified."
