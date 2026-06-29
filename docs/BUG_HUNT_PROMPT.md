# Bock Media — Comprehensive Bug Hunt Prompt

Use this prompt for periodic full-stack audits (server, web, Android, iOS, Alexa skill, MSP scaffolding).

---

## Mission

Find **real bugs** and **parity gaps** across every playback surface: Echo skill, browser WebPlayback, Android ExoPlayer, iOS AVPlayer, alexa_remote, room queues, shuffle/skip/continue-after-queue, and security on LAN/tunnel.

Deliver a structured report with severity, reproduction steps, file references, and **Done / Open / Won't fix** triage.

---

## Scope layers

### 1. Server (`server.py`, `bock_*.py`, `alexa_remote.py`)

- Every `/api/*` route: auth (LAN GET default-deny, write auth, media sig)
- Alexa `/alexa` intents: skip/back at queue end, shuffle seed on lazy queues, continue-after-queue per member
- MSP `/music` directives (if enabled in test env)
- Queue persistence: `_store_queue`, `_resolve_queue_tracks`, token encode/decode
- Now Playing: per-device state, `memberId`, upcoming, sleep timers
- OAuth `/oauth/*` gated behind admin auth
- Token compare: constant-time for mobile + MSP bearer

### 2. Web (`public/js/app.js`, `webPlayback.js`, `boot.js`, `sw.js`)

- Dual-mode NP: WebPlayback vs Alexa poll — no clobbering
- Poll efficiency: in-flight guard, timer cleanup on `navigate()`
- Queue panel: Up Next tap on Alexa → `seek_queue_index`
- Artwork: signed URLs when `mediaAuthRequired`
- `actionBtn`: no inline `onclick` XSS/quoting bugs
- Service worker: registered with version sync or removed
- Handoff: browser ↔ Echo from Now Playing

### 3. Android (`android/app/src/main/kotlin/com/bockmedia/console/`)

- Parity with web/iOS: loop, shuffle (NP + mini-bar), Up Next seek
- `NowPlayingPollService`: single poller, 5s playing / 20s idle
- `LocalPlaybackQueueResolver`: no double-shuffle with ExoPlayer
- Driving mode: Echo NP when not local-only
- Handoff UI
- BuildConfig: no baked secrets

### 4. iOS (`ios/BockMedia/`)

- Same parity checklist as Android
- `NowPlayingPollService` subscribers
- Handoff UI
- DTO completeness (`portReady`, `loop`, `shuffle`)

### 5. Cross-cutting

- [`shared/api-contract/api-contract.yaml`](../shared/api-contract/api-contract.yaml) matches server + clients
- [`.cursor/rules/bock-media-parity.mdc`](../.cursor/rules/bock-media-parity.mdc) — any playback UX change touches all platforms
- pytest: zero skips for playback-critical tests in CI (`fixtures/demo-data/songs_cache.db`)

---

## Severity rubric

| Level | Definition |
|-------|------------|
| P0 | Data loss, security hole, silent wrong playback, crash/ANR |
| P1 | Broken feature, major parity gap, flaky UX |
| P2 | Polish, performance, accessibility |
| P3 | Nice-to-have |

---

## Required deliverables

1. **Findings table** — ID, platform, severity, summary, file:line, status  
2. **Regression tests** for each P0/P1 fix  
3. Update [`docs/BUG_HUNT_BACKLOG.md`](BUG_HUNT_BACKLOG.md)  
4. Bump `versionName` + [`app-release-notes.json`](../app-release-notes.json) when shipping mobile/server behavior changes  
5. Run `./scripts/deploy_mobile_app.sh` after Android/server/web playback changes  

---

## Commands

```bash
pytest -q
cd android && ./gradlew :app:compileDebugKotlin
node --check public/js/app.js public/js/boot.js public/js/webPlayback.js
```

---

## Out of scope (document as won't-fix unless user overrides)

- MSP production enablement
- CarPlay
- Local phone repeat/loop mode
- Removing passwordless LAN when user opts in via config
