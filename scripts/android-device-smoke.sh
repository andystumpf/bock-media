#!/usr/bin/env bash
# DEPRECATED: use instrumented smoke instead:
#   TIER=1 ./scripts/run_mobile_ui_suite.sh
#   ./scripts/run_android_smoke_tests.sh
#
# Legacy uiautomator smoke test for Bock Media Android on a physical device (adb + uiautomator).
# Usage: ANDROID_SERIAL=RFCT30VF9AE ./scripts/android-device-smoke.sh
#
# Manual QA checklist (artist page — run when changing artist flows):
#  1. Open artist from Library → Artists (multi-word name e.g. The Smashing Pumpkins)
#  2. Open artist from Now Playing artist tap
#  3. Follow → Following toggle persists after leaving and returning
#  4. Tap genre chip → genre detail → back → artist page still populated (cache)
#  5. Listen Agent mic → pre-filled "play top songs from …" prompt
#  6. Videos row visible when cache has related videos; tap opens Now Playing video
#  7. Sticky mini-header appears when scrolling past hero
#  8. Highly rated play uses rated subset only (not all top tracks)
#  9. Track/album context menu → download / add to playlist
# 10. Album sort toggle (Newest / Oldest / A–Z) reorders discography

set -eo pipefail

SERIAL="${ANDROID_SERIAL:-}"
PKG="${Bock_PACKAGE:-com.bockmedia.console}"
ACTIVITY="com.bockmedia.console.MainActivity"
UI="/sdcard/bock_ui.xml"
LOCAL_UI="/tmp/bock_ui.xml"
REPORT="/tmp/bock-smoke-report.txt"
PASS=0
FAIL=0
SKIP=0
MANUAL=0

if [[ -z "$SERIAL" ]]; then
  SERIAL=$(adb devices | awk '/device$/{print $1; exit}')
fi
ADB=(adb -s "$SERIAL")

log() { echo "$*" | tee -a "$REPORT"; }
pass() { PASS=$((PASS + 1)); log "  PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); log "  FAIL  $1"; }
skip() { SKIP=$((SKIP + 1)); log "  SKIP  $1"; }
manual() { MANUAL=$((MANUAL + 1)); log "  MANUAL  $1"; }

alive() {
  "${ADB[@]}" shell pidof "$PKG" >/dev/null 2>&1
}

clear_logcat() { "${ADB[@]}" logcat -c 2>/dev/null || true; }

recent_crash() {
  "${ADB[@]}" logcat -d -t 80 2>/dev/null | grep -q "FATAL EXCEPTION.*$PKG"
}

launch() {
  "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1 || true
  sleep 1
  clear_logcat
  "${ADB[@]}" shell am start -n "$PKG/$ACTIVITY" >/dev/null
}

wait_ui() { sleep "$1"; }

dump_ui() {
  "${ADB[@]}" shell uiautomator dump "$UI" >/dev/null 2>&1
  "${ADB[@]}" pull "$UI" "$LOCAL_UI" >/dev/null 2>&1
  [[ -s "$LOCAL_UI" ]]
}

ui_has() {
  local needle="$1"
  dump_ui && grep -q "$needle" "$LOCAL_UI"
}

tap_text() {
  local target="$1"
  dump_ui || return 1
  python3 - "$LOCAL_UI" "$target" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, target = sys.argv[1], sys.argv[2]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    texts = [node.get("text") or "", node.get("content-desc") or ""]
    if any(target.lower() in t.lower() for t in texts if t):
        b = node.get("bounds")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

do_tap_text() {
  local target="$1"
  local coords
  coords=$(tap_text "$target") || return 1
  read -r x y <<< "$coords"
  "${ADB[@]}" shell input tap "$x" "$y"
}

swipe() {
  "${ADB[@]}" shell input swipe "$1" "$2" "$3" "$4" 400
}

open_drawer() {
  do_tap_text "Menu" || swipe 20 600 500 600
  wait_ui 1
}

first_playlist_tap() {
  dump_ui || return 1
  python3 - "$LOCAL_UI" <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
rows = []
skip = {"Playlists", "Search", "Rooms", "Now Playing", "Menu", "Navigation bar"}
for node in root.iter("node"):
    t = (node.get("text") or "").strip()
    b = node.get("bounds")
    if t and b and t not in skip and not t.startswith("Fix"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if m and int(m.group(2)) > 250:
            rows.append((int(m.group(2)), t, b))
rows.sort()
if rows:
    y, t, b = rows[0]
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2, t)
PY
}

rapid_tabs() {
  local i
  for i in 1 2 3 4 5; do
    do_tap_text "Rooms" || true
    do_tap_text "Search" || true
    do_tap_text "Playlists" || true
    do_tap_text "Now Playing" || true
  done
}

pull_refresh() {
  local out w h cx y1 y2
  out=$("${ADB[@]}" shell wm size 2>/dev/null | tr -d '\r')
  w=$(echo "$out" | grep -oE '[0-9]+' | head -1)
  h=$(echo "$out" | grep -oE '[0-9]+' | tail -1)
  [[ -z "$w" || -z "$h" ]] && w=1080 && h=2400
  cx=$((w / 2))
  y1=$((h / 3))
  y2=$((h / 2))
  swipe "$cx" "$y1" "$cx" "$y2"
}

assert_no_crash() {
  local label="$1"
  wait_ui 2
  if ! alive; then
    fail "$label — app process died"
    return 1
  fi
  if recent_crash; then
    fail "$label — FATAL in logcat"
    return 1
  fi
  pass "$label — no crash"
  return 0
}

run_test() {
  local id="$1" name="$2"
  shift 2
  log ""
  log "[$id] $name"
  "$@"
}

: > "$REPORT"
log "Bock Media device smoke test"
log "Device: $SERIAL  Package: $PKG"
log "Started: $(date)"

# --- 1 Startup ---
run_test "1.1" "Cold launch shows main UI" launch && wait_ui 5 && assert_no_crash "Cold launch"

run_test "1.2" "Bottom nav visible" \
  ui_has "Now Playing" && pass "Now Playing tab visible" || fail "Now Playing tab missing"

ui_has "Rooms" && pass "Rooms tab visible" || fail "Rooms tab missing"
ui_has "Search" && pass "Search tab visible" || fail "Search tab missing"
ui_has "Playlists" && pass "Playlists tab visible" || fail "Playlists tab missing"

# --- 2 Bottom navigation ---
for tab in "Rooms" "Search" "Playlists" "Now Playing"; do
  run_test "2.x" "Navigate to $tab" \
    do_tap_text "$tab" && wait_ui 2 && assert_no_crash "Tab $tab"
done

run_test "2.4" "Drawer opens (Dashboard reachable)" \
  open_drawer && wait_ui 1 && ui_has "Dashboard" && pass "Drawer shows Dashboard" || fail "Drawer/Dashboard not found"

do_tap_text "Dashboard" 2>/dev/null && wait_ui 3
assert_no_crash "Dashboard screen"
ui_has "Navigation bar" && skip "Bottom nav on drawer route — check manually" || pass "Dashboard (no bottom nav label in dump)"

do_tap_text "Now Playing" 2>/dev/null || open_drawer && do_tap_text "Now Playing" 2>/dev/null || true
wait_ui 2

# --- 3 Mini bar (conditional) ---
log ""
log "[3.x] Mini now-playing bar"
if ui_has "Now playing"; then
  pass "Mini/full NP content visible"
else
  manual "No active playback — mini bar test needs music playing"
fi

# --- 3b Artist page ---
log ""
log "[3b] Artist page"
do_tap_text "Library" && wait_ui 2
if do_tap_text "Artists" 2>/dev/null; then
  wait_ui 3
  if dump_ui && coords=$(python3 - <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse("/tmp/bock_ui.xml").getroot()
for node in root.iter("node"):
    t = (node.get("text") or "") + (node.get("content-desc") or "")
    if node.get("clickable") == "true" and t.strip() and "Search" not in t and "Artists" not in t:
        b = node.get("bounds")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print((x1+x2)//2, (y1+y2)//2, t.strip()[:40])
            break
PY
); then
    read -r ax ay aname <<< "$coords"
    if [[ -n "$ax" ]]; then
      "${ADB[@]}" shell input tap "$ax" "$ay"
      wait_ui 4
      assert_no_crash "Artist detail open"
      ui_has "Albums" && pass "Artist page Albums section" || manual "Albums section not visible"
      if ui_has "Follow"; then
        pass "Follow button visible"
        do_tap_text "Follow" && wait_ui 2
        if ui_has "Following"; then
          pass "Follow toggled to Following"
          do_tap_text "Following" && wait_ui 2
          ui_has "Follow" && pass "Following toggled back to Follow" || manual "Follow toggle back not visible"
        else
          manual "Following label not shown after Follow tap"
        fi
      elif ui_has "Following"; then
        pass "Following button visible (already followed)"
      else
        manual "Follow/Following not visible"
      fi
      if dump_ui && genre_coords=$(python3 - <<'PY'
import re, sys, xml.etree.ElementTree as ET
root = ET.parse("/tmp/bock_ui.xml").getroot()
for node in root.iter("node"):
    cls = node.get("class") or ""
    t = (node.get("text") or "") + (node.get("content-desc") or "")
    if node.get("clickable") == "true" and t.strip() and "Genres" in t:
        continue
    if node.get("clickable") == "true" and t.strip() and len(t.strip()) < 24:
        skip = {"Follow", "Following", "Shuffle", "Play", "Albums", "Popular", "Back", "Library", "Search", "Now Playing", "Rooms", "Menu"}
        if t.strip() in skip or t.strip().startswith("See "):
            continue
        if "Demo" in t or "Rock" in t or "Pop" in t or "Alternative" in t:
            b = node.get("bounds")
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
            if m:
                x1,y1,x2,y2 = map(int, m.groups())
                print((x1+x2)//2, (y1+y2)//2, t.strip()[:30])
                break
PY
); then
        read -r gx gy gname <<< "$genre_coords"
        if [[ -n "$gx" ]]; then
          "${ADB[@]}" shell input tap "$gx" "$gy"
          wait_ui 3
          assert_no_crash "Genre detail from artist chip"
          ui_has "$gname" && pass "Genre screen opened ($gname)" || manual "Genre screen title not visible"
          do_tap_text "Back" 2>/dev/null || "${ADB[@]}" shell input keyevent 4
          wait_ui 2
          ui_has "Albums" && pass "Artist page still populated after back (cache)" || manual "Artist page blank after back"
        fi
      else
        manual "No genre chip found to tap"
      fi
      do_tap_text "Back" 2>/dev/null || "${ADB[@]}" shell input keyevent 4
      wait_ui 2
    else
      skip "No artist row to tap"
    fi
  else
    skip "Artists list empty"
  fi
else
  skip "Artists tab not found"
fi

# --- 4 Now Playing ---
run_test "4.x" "Now Playing screen loads" \
  do_tap_text "Now Playing" && wait_ui 3 && assert_no_crash "Now Playing screen"

run_test "4.6" "Pull-to-refresh on Now Playing" \
  pull_refresh && wait_ui 3 && assert_no_crash "NP pull refresh"

# --- 5 Pull refresh other tabs ---
for tab in "Rooms" "Search"; do
  run_test "5.x" "Pull refresh on $tab" \
    do_tap_text "$tab" && wait_ui 2 && pull_refresh && wait_ui 3 && assert_no_crash "Pull refresh $tab"
done

run_test "5.x" "Analytics pull refresh" \
  open_drawer && do_tap_text "Analytics" && wait_ui 4 && pull_refresh && wait_ui 3 && assert_no_crash "Analytics refresh"

run_test "5.x" "Devices pull refresh" \
  open_drawer && do_tap_text "Alexa Devices" && wait_ui 4 && pull_refresh && wait_ui 3 && assert_no_crash "Devices refresh"

# --- 6 Dark theme ---
log ""
log "[6.x] Dark mode toggle (system)"
"${ADB[@]}" shell cmd uimode night yes >/dev/null 2>&1 && wait_ui 2 && assert_no_crash "Dark mode ON"
"${ADB[@]}" shell cmd uimode night no >/dev/null 2>&1 && wait_ui 2 && assert_no_crash "Dark mode OFF"

# --- 7 Nested navigation ---
log ""
log "[7.x] Playlist detail + back"
do_tap_text "Playlists" && wait_ui 3
if coords=$(first_playlist_tap 2>/dev/null); then
  read -r px py pname <<< "$coords"
  if [[ -n "$px" && -n "$py" ]]; then
    log "  Tapping playlist: $pname"
    "${ADB[@]}" shell input tap "$px" "$py"
    wait_ui 3
    assert_no_crash "Playlist detail open"
    ui_has "Playlist" && pass "Playlist detail title visible" || fail "Playlist detail title missing"
    do_tap_text "Back" 2>/dev/null || "${ADB[@]}" shell input keyevent 4
    wait_ui 2
    assert_no_crash "Back from playlist"
  else
    skip "No playlists in list to open"
  fi
else
  skip "Could not find playlist row in UI dump"
fi

# --- 8 Devices wizard button ---
log ""
log "[10.1] Devices — Fix my devices entry"
open_drawer && do_tap_text "Alexa Devices" && wait_ui 4
if ui_has "Fix my devices"; then pass "Fix my devices button visible"; else manual "Fix my devices not shown (no online Echoes?)"; fi

# --- 12 Stability ---
run_test "12.x" "Rapid tab switching" rapid_tabs
wait_ui 2 && assert_no_crash "Rapid tab switching"

run_test "12.x" "Background / foreground" \
  "${ADB[@]}" shell input keyevent 3 && wait_ui 2 && "${ADB[@]}" shell am start -n "$PKG/$ACTIVITY" >/dev/null && wait_ui 3 && assert_no_crash "Resume from background"

log ""
log "========================================"
log "SUMMARY: PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP  MANUAL=$MANUAL"
log "Report: $REPORT"
log "Finished: $(date)"

if [[ "$FAIL" -gt 0 ]]; then exit 1; fi
exit 0
