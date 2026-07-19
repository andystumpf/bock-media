#!/usr/bin/env bash
# Install a macOS launchd job to refresh YouTube cookies daily (03:15).
# Requires: Mac logged into YouTube in Chrome, SSH key to NAS, yt-dlp locally.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_SRC="$REPO_ROOT/scripts/youtube_cookies.sh"
TEMPLATE="$REPO_ROOT/scripts/com.bockmedia.youtube-cookies.plist"
SUPPORT_DIR="$HOME/Library/Application Support/BockMedia"
SCRIPT="$SUPPORT_DIR/youtube-cookies.sh"
AGENT_DIR="$HOME/Library/LaunchAgents"
AGENT_PLIST="$AGENT_DIR/com.bockmedia.youtube-cookies.plist"
LOG_DIR="$HOME/Library/Logs/bockmedia"

chmod +x "$SCRIPT_SRC"
mkdir -p "$LOG_DIR" "$AGENT_DIR" "$SUPPORT_DIR"
cp "$SCRIPT_SRC" "$SCRIPT"
chmod +x "$SCRIPT"

sed \
  -e "s|__SCRIPT_PATH__|$SCRIPT|g" \
  -e "s|__LOG_DIR__|$LOG_DIR|g" \
  "$TEMPLATE" > "$AGENT_PLIST"

launchctl bootout "gui/$(id -u)/com.bockmedia.youtube-cookies" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$AGENT_PLIST"
launchctl enable "gui/$(id -u)/com.bockmedia.youtube-cookies"

echo "Installed: $AGENT_PLIST"
echo "Script:    $SCRIPT"
echo "Schedule:  Daily at 03:15 (edit plist to change)"
echo "Logs:      $LOG_DIR/youtube-cookies.{out,err}.log"
echo "Run now:   $SCRIPT"
echo "Re-install after script updates: $0"
echo "Uninstall: launchctl bootout gui/$(id -u)/com.bockmedia.youtube-cookies && rm $AGENT_PLIST"
echo ""
echo "Note: grant Full Disk Access to /bin/bash (or Terminal) so launchd can read Chrome cookies."
