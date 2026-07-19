#!/usr/bin/env bash
# Export the current branch to the public bock-media repo with sanitization.
#
# Usage:
#   ./scripts/publish_public_repo.sh            # dry-run (default)
#   ./scripts/publish_public_repo.sh --push     # sanitize, verify, push public/main
#
# Requires: git, python3, network for --push
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PUBLIC_REMOTE="${PUBLIC_REMOTE:-public}"
PUBLIC_BRANCH="${PUBLIC_BRANCH:-main}"
EXPORT_DIR="${EXPORT_DIR:-$REPO_ROOT/.publish-export}"
DRY_RUN=1

if [[ "${1:-}" == "--push" ]]; then
  DRY_RUN=0
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--push]" >&2
  exit 2
fi

should_skip() {
  local rel="$1"
  case "$rel" in
    .git/*|.git) return 0 ;;
    .publish-export/*|.publish-export) return 0 ;;
    .cursor/*|.cursor) return 0 ;;
    tmp/*|tmp) return 0 ;;
    tmp-device-screenshot.png|backfill-metadata.log|cloudflared.deb) return 0 ;;
    img/screenshots/android-home.png) return 0 ;;
    *.readme-bak|*.bak-prod) return 0 ;;
    build/mobile-ui-reports/*|build/mobile-ui-reports) return 0 ;;
    .github/workflows/notify-dashboard.yml) return 0 ;;
    docs/BUG_HUNT_BACKLOG.md|docs/BUG_HUNT_PROMPT.md) return 0 ;;
    docs/complete-bug-search-today.md) return 0 ;;
    docs/MOBILE_UI_TESTING.md) return 0 ;;
    docs/HOME_STARTUP_REGRESSION_TEST_PLAN.md) return 0 ;;
    docs/PHASE6_ACCOUNTS.md) return 0 ;;
    docs/IOS_PARITY_PLAN.md) return 0 ;;
    docs/dev/*|docs/dev) return 0 ;;
  esac
  return 1
}

echo "==> Exporting tracked + untracked (non-ignored) files to ${EXPORT_DIR}"
rm -rf "$EXPORT_DIR"
mkdir -p "$EXPORT_DIR"

copy_path() {
  local rel="$1"
  should_skip "$rel" && return 0
  [[ -e "$REPO_ROOT/$rel" ]] || return 0
  local dest="$EXPORT_DIR/$rel"
  mkdir -p "$(dirname "$dest")"
  cp -a "$REPO_ROOT/$rel" "$dest"
}

while IFS= read -r -d '' rel; do
  [[ -n "$rel" ]] || continue
  copy_path "$rel"
done < <(git -C "$REPO_ROOT" ls-files -z)

while IFS= read -r -d '' rel; do
  [[ -n "$rel" ]] || continue
  copy_path "$rel"
done < <(git -C "$REPO_ROOT" ls-files -o --exclude-standard -z)

echo "==> Sanitizing export"
python3 "$REPO_ROOT/scripts/sanitize_for_public.py" "$EXPORT_DIR"

echo "==> Running pytest on export (sanity check)"
# Prefer a python that has pytest (Xcode's python3 often does not).
PYTEST_PY="${OURMEDIA_PYTHON:-}"
if [[ -z "$PYTEST_PY" ]]; then
  for cand in \
    /Library/Frameworks/Python.framework/Versions/3.13/bin/python3 \
    /Library/Frameworks/Python.framework/Versions/3.12/bin/python3 \
    /opt/homebrew/bin/python3 \
    "$(command -v python3)"
  do
    [[ -x "$cand" ]] || continue
    if "$cand" -c "import pytest" 2>/dev/null; then
      PYTEST_PY="$cand"
      break
    fi
  done
fi
if [[ -z "${PYTEST_PY:-}" ]]; then
  echo "No python with pytest found; set OURMEDIA_PYTHON=/path/to/python3" >&2
  exit 1
fi
echo "    using $PYTEST_PY"
(
  cd "$EXPORT_DIR"
  # Don't inherit a local demo-data override from the developer shell —
  # public export tests must use fixtures/demo-data.
  env -u OURMEDIA_DATA_DIR -u OURMEDIA_DB_PATH -u OURMEDIA_MUSIC_ROOT \
    "$PYTEST_PY" -m pytest tests/ -q --tb=no \
    --ignore=tests/test_bock_uitest.py
)

echo "==> Re-sanitizing after pytest (tests write runtime fixture paths)"
python3 "$REPO_ROOT/scripts/sanitize_for_public.py" "$EXPORT_DIR"
find "$EXPORT_DIR" -type d -name '__pycache__' -prune -exec rm -rf {} + 2>/dev/null || true
rm -f "$EXPORT_DIR/queues.json" "$EXPORT_DIR/nowplaying_state.json" \
  "$EXPORT_DIR/streaming_history.jsonl" "$EXPORT_DIR/server.log"

echo "==> Verifying no tracked secrets in export"
SCAN_FILES=()
while IFS= read -r -d '' f; do
  case "$f" in
    */scripts/sanitize_for_public.py|*/scripts/publish_public_repo.sh) continue ;;
  esac
  case "${f##*.}" in
    png|jpg|jpeg|gif|webp|ico|db|deb|mp3|m4a|aac|flac|wav|ogg|zip|jar|apk|keystore|p12|woff|woff2|ttf|eot|pdf|bin|pyc|pyo) continue ;;
  esac
  case "$f" in
    */__pycache__/*) continue ;;
  esac
  SCAN_FILES+=("$f")
done < <(find "$EXPORT_DIR" -type f -print0)

if ((${#SCAN_FILES[@]})) && rg -n --hidden -S \
  -e '192\.168\.1\.187' \
  -e 'andymac' \
  -e '/Users/andymac' \
  -e 'github\.com/andystumpf/ourMedia' \
  -e 'plex@192\.168\.1\.187' \
  -e 'ghp_[A-Za-z0-9]{20,}' \
  -e 'BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY' \
  "${SCAN_FILES[@]}"; then
  echo "rg found unresolved sensitive content (see above)" >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo ""
  echo "Dry run complete. Export is at: $EXPORT_DIR"
  echo "Review, then publish with: $0 --push"
  exit 0
fi

echo "==> Pushing to ${PUBLIC_REMOTE}/${PUBLIC_BRANCH}"
WORKTREE="$(mktemp -d)"
trap 'rm -rf "$WORKTREE"' EXIT

git clone --branch "$PUBLIC_BRANCH" --single-branch \
  "$(git -C "$REPO_ROOT" remote get-url "$PUBLIC_REMOTE")" "$WORKTREE"

rsync -a --delete \
  --exclude '.git/' \
  "$EXPORT_DIR/" "$WORKTREE/"

cd "$WORKTREE"
git add -A
if git diff --cached --quiet; then
  echo "No changes to publish."
  exit 0
fi

git commit -m "$(cat <<EOF
Sync from private repo (main).

Automated public export with home-lab defaults removed and internal-only
docs/workflows excluded.
EOF
)"
git push origin "HEAD:${PUBLIC_BRANCH}"

echo "Published to $(git -C "$REPO_ROOT" remote get-url "$PUBLIC_REMOTE") (${PUBLIC_BRANCH})"
