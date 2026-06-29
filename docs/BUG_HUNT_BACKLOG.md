# Bock Media — Bug Hunt Backlog

Living backlog from the June 2026 comprehensive bug hunt and full remaining-work plan.  
**Baseline:** v2.6.48 · see [`BUG_FIXES_2026-06-11.md`](../BUG_FIXES_2026-06-11.md) for earlier hunt IDs.

Status: **Done** · **Open** · **Won't fix**

---

## Phase 0 — Docs

| Item | Status | Notes |
|------|--------|-------|
| `docs/BUG_HUNT_BACKLOG.md` | Done | This file |
| `docs/BUG_HUNT_PROMPT.md` | Done | Audit prompt for future hunts |

---

## Phase 1 — Echo playback engine (P0)

| ID | Item | Status | Files |
|----|------|--------|-------|
| 1.1 | Per-member `continueAfterQueue` on Echo (member prefs override household XML) | Done | `server.py` `_continue_after_queue_mode`, `_device_id_for_queue_context`, `tests/test_continue_after_queue.py` |
| 1.2 | `AMAZON.ShuffleOnIntent` lazy queue `shuffle_seed` (no re-randomize on decode) | Done | `server.py` ShuffleOnIntent, `_update_queue_flags`, `tests/test_shuffle_lazy.py` |
| 1.3 | Alexa Up Next queue jump (`seek_queue_index`) | Done | `server.py` `alexa_remote_control`, Android `UpNextSheet`, iOS `NowPlayingSheets`, web queue panel |
| 1.4 | `alexa_remote` thread-pool executor + login proxy default `127.0.0.1` | Done | `alexa_remote.py`, `server.py` |

### Already done (pre-plan / v2.6.45–47)

| Item | Status | Files |
|------|--------|-------|
| Skip wrap honors `stopAt` / `loop` | Done | `server.py` `_np_skip_next` |
| LAN GET auth when `WebPassword` set | Done | `server.py` `_api_read_auth_ok` |
| NP API exposes `shuffle` | Done | `server.py` `nowplaying_devices` |
| MSP continue at queue end | Done | `server.py` `GetNextItem` |
| Web/iOS continue-after-queue | Done | `webPlayback.js`, iOS local controller |

---

## Phase 2 — Cross-platform parity (P1)

| Feature | Web | Android | iOS | Status |
|---------|-----|---------|-----|--------|
| Loop/repeat (Echo) | Has | Added | Added | Done |
| Mini-bar shuffle | Has | Added | Added | Done |
| Up Next seek (Alexa) | Wired | Wired | Wired | Done |
| Double local shuffle | N/A | Fixed | OK | Done |
| Shared NP poller | N/A | `NowPlayingPollService` | Has | Done |
| Driving + Echo controls | N/A | Poll NP for focused device | Poll NP | Done |

---

## Phase 3 — Web shell reliability (P1)

| Item | Status | Files |
|------|--------|-------|
| Dual-mode Now Playing (skip Alexa rebuild when `WebPlayback.active`) | Done | `public/js/app.js` |
| NP poll in-flight guard | Done | `public/js/app.js` |
| Room queue reorder fresh GET | Done | `public/js/app.js` |
| Artwork signing placeholder | Done | `public/js/app.js` `artworkUrl` |
| `actionBtn` data-* delegation | Done | `public/js/app.js` |
| Service worker register-or-delete | Done | `public/js/boot.js` |

---

## Phase 4 — Handoff (P2)

| Item | Status | Files |
|------|--------|-------|
| Handoff API | Done (pre-existing) | `bock_handoff.py`, `bock_routes.py` |
| Web NP handoff UI | Done | `public/js/app.js` |
| Android handoff | Done | `NowPlayingScreen.kt`, `BockMediaRepository.kt` |
| iOS handoff | Done | `NowPlayingView.swift` |

---

## Phase 5 — Security (P1)

| ID | Item | Status |
|----|------|--------|
| C-01 | LAN GET default-deny without `allowOpenLanApi` | Done |
| H-B05 | OAuth gate (`/oauth/*`) admin auth | Done |
| C-03 | CF header trust regression test | Done |
| H-A07 | Login proxy bind `127.0.0.1` default | Done |
| Secrets | Android BuildConfig empty defaults | Done |
| Timing | `hmac.compare_digest` mobile/MSP tokens | Done |

---

## Phase 6 — Test infrastructure

| Item | Status | Files |
|------|--------|-------|
| Minimal `songs_cache` seed when fixture missing | Done | `tests/conftest.py` |
| `test_continue_after_queue.py` | Done | |
| `test_shuffle_lazy.py` | Done | |
| `test_msp.py` (scaffolding) | Done | |

---

## Phase 7 — Mobile polish (P2/P3)

| ID | Item | Status | Notes |
|----|------|--------|-------|
| AND-12 | POST_NOTIFICATIONS gate | Done | Fixed in prior hunt |
| AND-14 | AI playlist error UI | Done | Fixed in prior hunt |
| C-13 | Volume slider refresh on poll | Done | Android NP poll service |
| C-14 | Volume debounce on dispose | Done | `DisposableEffect` |
| D-20 | `portReady` on iOS DTO | Done | `ApiDtos.swift` |
| D-15 | Widget rapid-tap queue | Done | `MainTabView.swift` |
| M-F* | Analytics chart lifecycle | Done | `AnalyticsScreen.kt` |

---

## Won't fix (unless requested)

| Item | Reason |
|------|--------|
| MSP re-enable in production | RUNBOOK: account-linking collision |
| Full open-LAN removal when passwordless home LAN | Intentional home mode via `allowOpenLanApi` |
| CarPlay | Not in repo |
| Local phone repeat mode | Not in product |

---

## Milestones

1. **M1 Echo correct** — Phase 1.1 + 1.2 + tests ✓  
2. **M2 Parity** — Phase 2 ✓  
3. **M3 Web solid** — Phase 3 ✓  
4. **M4 Secure LAN** — Phase 5 ✓  
5. **M5 Handoff** — Phase 4 ✓  
6. **M6 Polish** — Phase 7 ✓  
