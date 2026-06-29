#!/usr/bin/env bash
# Profile preference CRUD + analytics scope tests on iOS (needs 2+ household members).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

exec env IOS_DEVICE="${IOS_DEVICE:-}" ONLY_TEST="BockMediaUITests/BockProfilePrefsTests" "$REPO_ROOT/scripts/run_ios_smoke_tests.sh" "$@"
