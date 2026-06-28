# MISSION: Extreme Comprehensive Bug Hunt — Bock Media (ourMedia)

You are a senior QA architect + playback-systems engineer. Perform an **exhaustive, adversarial bug hunt** across the entire Bock Media stack. Assume nothing from prior audits is still fixed — **re-verify every claim** against current code.

**Repo:** ourMedia (Flask backend, web SPA, Android Kotlin, iOS Swift, Alexa custom skill + MSP)
**Production:** gunicorn `-w 1`, `OURMEDIA_DATA_DIR`, Cloudflare tunnel `alexa.morejava.bid`
**Invocation:** "Alexa, ask bock media to …"

## NON-NEGOTIABLE RULES

1. **Read code, don't speculate.** Every finding must cite `file:line` and include a repro or test that proves it.
2. **Run tests:** `pytest tests/ -v` — report pass/fail count and map failures to root causes.
3. **Cross-platform parity:** Any control that exists on one client but not another is a finding (unless documented).
4. **Two playback paths everywhere:** (a) local phone (`local-phone` / WebPlayback), (b) remote Echo (Alexa skill + unofficial remote).
5. **Two queue meanings:** Alexa internal queue (token-encoded in `queues.json`) vs room request queue (`/api/rooms/{id}/queue`).
6. **"Extended play" = Continue After Queue** (`continueAfterQueue` / `continue_after_queue`) — NOT a separate API. Hunt all end-of-queue behaviors.
7. **Skip on Echo is voice-round-trip:** REST `next`/`previous` → alexa_remote speaks → `/alexa` SkipIntent → `_np_skip_next()`. Never assume optimistic skip is safe (double-skip history).
8. **Output severity:** Critical / High / Medium / Low / Polish. Include ID (e.g. SRV-XX, AND-XX, IOS-XX, WEB-XX, ALEXA-XX).
9. **Do not fix code.** Findings + recommended fixes only.

---

## ARCHITECTURE YOU MUST UNDERSTAND FIRST

**Key files to read first:**
- `server.py` — Alexa handlers, NP state, queues, skip/shuffle, continue-after-queue
- `alexa_remote.py` — unofficial Echo control
- `bock_routes.py` — handoff, continue, client prefs, library extensions
- `skill/interaction_model.json` — voice intents
- `public/js/app.js` + `public/js/webPlayback.js` — web UI
- Android: `NowPlayingScreen.kt`, `LocalPlaybackController.kt`, `BockMediaRepository.kt`
- iOS: `NowPlayingView.swift`, `LocalPlaybackController.swift`, `NowPlayingPollService.swift`
- Prior audits: `docs/COMPREHENSIVE_BUG_HUNT.md`, `BUG_REPORT.md`, `tests/test_regressions.py`

---

## PHASE 1 — PARALLEL DEEP DIVES (run as 4 sub-investigations)

### Layer A: Backend API + Alexa Playback Engine

Catalog every route in `server.py` and `bock_routes.py`. For each mutating endpoint, check: auth (LAN open API?), input validation, idempotency, error codes, race conditions.

**Playback-critical endpoints:** `/api/playlists/play`, `/api/alexa_remote/play`, `/api/alexa_remote/control`, `/api/nowplaying_devices`, `/api/nowplaying/sleep`, settings/client prefs, `/api/clients/report`, `/api/playback/handoff`, room queue routes, `/stream/`, `/artwork/`.

**Alexa skill (`POST /alexa`):** all intents, AudioPlayer events, extended play, skip/shuffle/loop, ENQUEUE semantics, stale tokens, queue cap 300, stop-after-N, sleep timer, IgnoreSongIntent, APL lyrics.

**MSP (`POST /music`):** GetPlayableContent, Initiate, GetNextItem, GetPreviousItem, SetShuffle, SetLoop, room request splice.

**State file concurrency:** `queues.json`, `nowplaying_state.json`, `play_tokens.json`.

### Layer B: Web UI + UX Workflows

Files: `public/index.html`, `public/js/app.js`, `public/js/webPlayback.js`, `public/js/clientPrefsSync.js`, `public/sw.js`

Dual-mode player bar (WebPlayback vs Alexa). Workflows: play chooser, search, playlist detail, `#nowplaying`, `#family`, `#analytics`, settings, automations, service worker version drift.

### Layer C: Android App

NowPlayingScreen, MiniNowPlayingBar, DrivingModeScreen, PlayTargetLauncher, LocalPlaybackService, SettingsScreen, widget/notification. DTO mismatches. Re-verify BUG_REPORT.md AND-xx items.

### Layer D: iOS App

NowPlayingView/ViewModel, MiniNowPlayingBar, DrivingModeView, LocalPlaybackController, PlayLauncher, SettingsView. Parity gaps (shuffle, Up Next jump on Alexa).

---

## PHASE 2 — CROSS-CUTTING PLAYBACK MATRIX

Build Skip / Shuffle / Queue / Extended Play matrix across Echo voice, Echo remote, lock-screen, Android local, iOS local, Web browser, MSP.

---

## PHASE 3 — ENDPOINT INVENTORY CHECKLIST

Verify every route for auth, validation, happy/error path, concurrent access. Flag untested routes as M-Txx.

---

## PHASE 4 — SECURITY + OPS

Re-verify: open LAN API, unsigned streams, forged Cloudflare headers, health watchdog path split, Android secrets, OAuth on LAN.

---

## PHASE 5 — TEST GAPS

Run `pytest tests/ -v`. List top 20 missing tests.

---

## OUTPUT FORMAT

Produce markdown report with Executive Summary, Methodology, Cross-Platform Playback Matrix, Findings by Severity, Platform Parity Gaps, Endpoint Coverage Gaps, Re-verified Prior Findings, Recommended Test Additions, Appendix.

Be exhaustive. Aim for **80+ findings** if warranted. Prefer actionable, reproducible bugs over style nits.
