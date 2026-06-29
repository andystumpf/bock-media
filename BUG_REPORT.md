# Bock Media — Open Bug / Defect Report

> **2026-06-11 update:** a follow-up hunt + fix pass resolved nearly all items below (plus 26 new findings). See `BUG_FIXES_2026-06-11.md` for per-bug status and fixes.

**Date:** 2026-06-10
**Scope:** Full QA sweep of iOS, Android, server backend, and server web UI, performed *after* the prior 60-bug fix pass. All items below were verified against current code.

| Platform | Bugs | P0 | P1 | P2 | P3 |
|---|---|---|---|---|---|
| iOS | 0 (N/A) | — | — | — | — |
| Android | 20 | 2 | 3 | 11 | 4 |
| Server backend | 20 | 0 | 0 | 18 | 2 |
| Web UI | 20 | 2 | 5 | 12 | 1 |
| **Total** | **60** | **4** | **8** | **41** | **7** |

---

## iOS

**N/A — no iOS app exists in this repository** (0 Swift / `.xcodeproj` / `.xcworkspace` files). If an iOS client is planned, track it as a feature, not a defect.

---

## Android (`android/app/src/main/kotlin/com/bockmedia/console/`)

### P0 — blockers

- [ ] **AND-01** · `ui/playlists/PlaylistsScreens.kt:116` — **Compile error (regression from last fix pass).** `AiPlaylistDialog` dismiss handler calls `load()` but the loader is now `suspend fun load(expectedGen: Int)`; no zero-arg overload exists. Project will not build.
- [ ] **AND-02** · `media/NowPlayingNotificationManager.kt:173` — **ANR risk.** Notification `sync()` runs on the main looper and `loadArtwork()` uses `runBlocking { … HTTP … }` there. The widget artwork path was fixed but the notification path still blocks the main thread.

### P1 — major

- [ ] **AND-03** · `MainActivity.kt:56-59` — After `testConnection()` fails, startup falls through to `hasServer = app.hasServerUrl()` instead of `false`, so the app enters the main UI with a dead server and every screen errors.
- [ ] **AND-04** · `ui/playlists/PlaylistsScreens.kt:222-224` — Playlist delete handler unconditionally calls `onBack()` with no `runCatching` / `ok` check; failed deletes (403/404/network) look like success.
- [ ] **AND-05** · `data/api/dto/ApiDtos.kt:108-125` — Server now returns `shuffle: bool` per item from `/api/nowplaying_devices` (`server.py:5897`), but `NowPlayingDeviceItem` has no `shuffle` field; shuffle button state never seeds from the server and is wrong after refresh.

### P2 — minor

- [ ] **AND-06** · `ui/analytics/AnalyticsScreen.kt:55-56` + `ApiDtos.kt:392-397` — Server sends `dayOfWeek: [{day:"Mon", count:N}]`; `CountRow` has no `day` property → "By day of week" labels render as "—".
- [ ] **AND-07** · `ui/playlists/PlaylistsScreens.kt:191` — Playlist detail loads only page 1 / limit 100 and ignores `total`/`page`; playlists with >100 tracks are silently truncated.
- [ ] **AND-08** · `ui/library/LibraryScreens.kt:117-127` — Artists/Albums/Songs search has no generation guard; slow earlier responses can overwrite newer query results (Playlists has the `loadGen` pattern; Library doesn't).
- [ ] **AND-09** · `widget/NowPlayingWidgetProvider.kt:12` — `onUpdate()` renders from cached `NowPlayingSessionStore.snapshot` and never calls `NowPlayingController.fetchAndStore()`; system-driven widget updates show stale/empty state.
- [ ] **AND-10** · `ui/automation/AutomationScreen.kt:163-188` — Day chips allow an empty selection; server rejects with `'at least one day required'` (`server.py:2037`) and the user sees a raw server error instead of inline validation.
- [ ] **AND-11** · `ui/components/Common.kt:301-304` — `DevicePickerSheet` falls back to `d.name` when `d.serial` is null and sends it as `device`; server play/control endpoints expect a serial, so the command silently fails or mistargets.
- [ ] **AND-12** · `media/NowPlayingNotificationManager.kt:57-58` — No `areNotificationsEnabled()` check before posting; on Android 13+ with denied `POST_NOTIFICATIONS` the foreground notification silently never appears.
- [ ] **AND-13** · `data/repository/BockMediaRepository.kt:174-186` — `createSmartPlaylist()` omits `"refresh": true`; server only materializes tracks when `refresh` is set (`server.py:5165`), so new smart playlists show 0 tracks until manually refreshed.
- [ ] **AND-14** · `ui/playlists/PlaylistsScreens.kt:324-333` — AI playlist Preview/Create coroutines have no `runCatching`/snackbar; failures (bad prompt, missing Claude key, network) are swallowed with zero feedback.
- [ ] **AND-15** · `data/network/ServerEndpointResolver.kt:48` + `MainActivity.kt:59` — `resolve()` throws `IllegalStateException` when both URL probes fail, but `MainActivity` still sets `hasServer=true` when URLs exist; first API call hard-fails.
- [ ] **AND-16** · `ui/playlists/PlaylistsScreens.kt:244` — Remove-track handler calls `load(loadGen)` without bumping `loadGen` while the filter effect increments it per keystroke; out-of-order loads can repopulate the list with stale tracks.

### P3 — polish

- [ ] **AND-17** · `data/api/dto/ApiDtos.kt:377-388` — Server returns `topDecades` and `activity`/`playsPerDay` (`server.py:3473-3483`) but the DTO expects `decades` / `byDate`; those analytics sections always deserialize empty.
- [ ] **AND-18** · `ui/setup/SetupScreen.kt:131-134` — Footer copy says the password is "only sent to the external URL"; stale after `AuthInterceptor` was changed to also send credentials on LAN.
- [ ] **AND-19** · `ui/components/Common.kt:103` — `SearchField` leading icon has `contentDescription = null`; TalkBack announces an unlabeled control on every search screen.
- [ ] **AND-20** · `ui/playlists/PlaylistsScreens.kt:161,219,224,246`, `ui/library/LibraryScreens.kt:49,79` — Multiple icon-only buttons (back, delete, remove, chevron) use `contentDescription = null`; unlabeled for screen readers.

---

## Server Backend (`server.py`, `alexa_remote.py`, `scripts/sync_plex_playlists.py`)

### P2 — automations & scheduling

- [ ] **SRV-01** · `server.py:2121-2142` — `lastFiredAt` is set only on *successful* fire; a failed automation retries every 30 s for the rest of the minute (duplicate Alexa commands on flaky failures). Needs a "attempted this slot" marker separate from success.
- [ ] **SRV-02** · `server.py:2228-2232` — `run_automation_now()` never sets `lastFiredAt`; manual "Run now" + a same-minute schedule double-plays the automation.
- [ ] **SRV-20** · `server.py:2125-2136` — Scheduler snapshots the automation under lock, then fires outside it without re-checking `enabled`; an automation disabled in the gap still fires once.

### P2 — Alexa skill / playback logic

- [ ] **SRV-03** · `server.py:7076-7078` — `ShuffleArtistIntent` resolves a UI playlist token via `_start_playlist_token_entry(token_entry)` without `shuffle=True`; plays in order (ShuffleAlbumIntent does pass it).
- [ ] **SRV-04** · `server.py:6101-6104` — `_np_skip_next()` always wraps `(idx + 1) % len(tracks)` and ignores `loop`; "next" at the end of a non-looping queue restarts track 1 instead of stopping.
- [ ] **SRV-13** · `server.py:7060-7063, 7091-7094` — Play/Shuffle-artist SQL hard-filters `('.mp3','.m4a','.aac')`, bypassing `TRANSCODE_EXTS`; FLAC/WMA/OGG-heavy artists report "no playable files" even with transcoding enabled.
- [ ] **SRV-14** · `server.py:1330-1336, 6991-6993` — `_consume_play_playlist_token()` burns the token *before* `start_playing()` succeeds; any downstream failure makes a retry say "expired" though nothing ever played.
- [ ] **SRV-18** · `server.py:6892-6909` — `AudioPlayer.PlaybackFailed` auto-advances without checking `stopAt` / `stopAfterIdx`; sleep-timer/stop-after-N limits are ignored after a stream failure.
- [ ] **SRV-19** *(P3)* · `server.py:1203-1220` — `_PLAY_FILE_TOKENS` is in-process only (playlist tokens are persisted); song-on-device tokens vanish on service restart within their 180 s TTL.

### P2 — Now Playing state races

- [ ] **SRV-05** · `server.py:5852-5855` — `nowplaying_devices()` does read → mutate (`_expire_stale_playing`) → write without `_NP_LOCK`, racing concurrent `write_np_state()` from Alexa/MSP events; device rows intermittently drop/revert.
- [ ] **SRV-06** · `server.py:6887-6925` — AudioPlayer event handlers do `read_np_state()` → mutate → `write_np_state()` outside `_NP_LOCK`; concurrent devices clobber each other's snapshots (stale "playing", wrong pause/offset).
- [ ] **SRV-16** · `server.py:3017-3025` — Device-merge NP migration also runs `_read_all_np()`/`_write_all_np()` without `_NP_LOCK`; merging while music plays can lose or duplicate NP rows.

### P2 — Playlist XML / Plex sync integrity

- [ ] **SRV-07** · `server.py:6449-6453` — `_msp_playlist_by_id()` parses `ServerPlaylists.xml` without `playlist_xml_lock()`; mid-sync reads can return `CONTENT_NOT_FOUND` or the wrong playlist to Alexa.
- [ ] **SRV-08** · `server.py:462-463` — `summary()` parses `ServerPlaylists.xml` unlocked; same torn-read window during the 5-min cron sync.
- [ ] **SRV-09** · `scripts/sync_plex_playlists.py:306` — Sync writes XML via non-atomic in-place `tree.write()`; any unlocked reader can parse torn XML mid-write. Should write temp + rename (`_atomic_xml_write` pattern).
- [ ] **SRV-10** · `server.py:4477-4480` — `_save_playlists_tree()` holds the flock but still uses non-atomic `tree.write()`; readers that don't take the lock still see partial bytes.
- [ ] **SRV-11** · `scripts/sync_plex_playlists.py:302-306` — `save_state()` runs *before* `tree.write()`; a crash between them leaves the sync cache ahead of the actual XML/m3u files (playlist changes silently never re-sync).
- [ ] **SRV-12** · `scripts/sync_plex_playlists.py:229-233` — Playlists dropping below `--min-tracks` are skipped without deleting the previous `.m3u`; orphan files accumulate in `exportedPlaylists/plex/`.

### P2 — devices & security

- [ ] **SRV-15** · `server.py:3007-3013` — `_migrate_state_files()` checks `source_id in queues`, but `queues.json` keys are queue IDs, not device IDs; queue state is never migrated on device merge.
- [ ] **SRV-17** · `server.py:5908-5917` — `/api/artwork_url?path=…` never validates `path` with `_path_under_root()`; can mint signed tunnel URLs for artwork resolved from paths outside the music library.

---

## Web UI (`public/js/app.js`, `public/index.html`, `public/css/style.css`)

### P0 — blockers (regressions from last fix pass)

- [ ] **WEB-01** · `app.js:1907-1909` (via `actionBtn` at `:284`) — Playlist-detail **Play / Remove buttons are broken**: `pathArg = JSON.stringify(t.path)` embeds double quotes inside `actionBtn`'s double-quoted `onclick="…"` attribute, truncating the handler. Affects every track row.
- [ ] **WEB-02** · `app.js:2821` — Devices "Play a short test clip" broken the same way: `JSON.stringify(s.name)` injects `"` into the double-quoted `onclick` attribute; the handler never fires for any speaker. *(Fix `actionBtn` to escape, or pass data via `data-*` attributes + delegation.)*

### P1 — major

- [ ] **WEB-03** · `app.js:3246-3248` — `confirmMergeDevice` sets `window._devices = refreshed` (raw `{devices:[…]}` JSON) instead of `apiDevicesList()`; Devices page crashes (`.map is not a function`) after a merge.
- [ ] **WEB-04** · `app.js:3100-3106` — `acceptMergeCandidate` has the same shape mismatch (`window._devices = devices || []`).
- [ ] **WEB-05** · `app.js:3145-3149` — `pollIdentify` same again; after "Identify all" finishes, the list can crash or fail to re-render.
- [ ] **WEB-06** · `app.js:2326-2332, 2037-2040` — `invalidateAlexaRemoteStatus()` clears `_alexaRemote` but never `window._alexaDevices`; after Alexa re-login, device pickers omit newly discovered Echoes until full page reload.
- [ ] **WEB-07** · `app.js:4138-4140` + `server.py:1434-1445` — Search → Genre → ▶ sends `kind:'genre'`, which `_build_play_text()` doesn't handle; it falls through to the playlist branch and tells Alexa to "start the Rock playlist". Needs a genre branch server-side (or hide the button).

### P2 — minor

- [ ] **WEB-08** · `app.js:3934-3943` — `_loadAnalytics()` never calls `isApiError()`; API failures render as "No device activity yet".
- [ ] **WEB-09** · `app.js:2721-2740` — Devices route has no `routeAlive(gen)` guard after its awaits; fast navigation can paint Devices over the new page.
- [ ] **WEB-10** · `app.js:3632-3807` — Settings route mutates `#main-content` and starts Alexa login polling without capturing/checking `_routeGen`; same stale-paint class of bug.
- [ ] **WEB-11** · `app.js:149-156, 4799-4803` — Global Escape handler removes *any* `.modal-overlay` including the login modal, and `ensureAuth()` resolves once the overlay is gone — Escape bypasses the login gate into a broken 401 loop.
- [ ] **WEB-12** · `app.js:1887-1889, 1929` — `renderPlaylistDetailBody()` clamps `_plDetailPage` to `totalPages` but doesn't re-fetch; deleting tracks past a page boundary shows an empty table.
- [ ] **WEB-13** · `app.js:1729-1731` — Merge modal resolves names only from the current page (`window._playlists`); selections from other pages display raw UUIDs.
- [ ] **WEB-14** · `app.js:453-484` — `updateRoutineOutput()` writes `#rt-output.innerHTML` after await with no route check; can inject into a stale element after leaving Routines.
- [ ] **WEB-15** · `app.js:2163-2173` — `loadPlaylists()` never checks `isApiError(listData)`; API failure renders "No playlists found".
- [ ] **WEB-16** · `app.js:2677-2717` — Watch Folders route has no `routeAlive` guard; stale paint after navigating away mid-load.
- [ ] **WEB-17** · `app.js:1137-1167, 4779-4786` — `_npVolumeTimers` debounce timers are never cleared on route change; a volume drag can POST seconds after leaving Now Playing.
- [ ] **WEB-18** · `app.js:410-414` — Routines route never checks `isApiError`; playlist API failure shows "No playlists found yet".
- [ ] **WEB-19** · `app.js:1617-1618` — Playlist detail route reads `params.slice(7)` without `decodeURIComponent()`, while list links use `encodeURIComponent(p.id)`; %-escaped IDs 404 on deep link.

### P3 — polish

- [ ] **WEB-20** · `app.js:284, 1893` — Smart-playlist "Open" link uses `escHtml()` inside a JS string literal in `onclick` (should be `escJsStr`/`encodeURIComponent`); breaks/injects if a linked playlist ID ever contains `'` `"` `<`.

---

## Recommended fix order

1. **P0 first:** AND-01 (compile error), WEB-01/WEB-02 (broken buttons — fix `actionBtn` quoting once, both resolve), AND-02 (notification ANR).
2. **Cross-cutting root causes** (one fix clears several bugs):
   - `actionBtn` attribute escaping → WEB-01, WEB-02, WEB-20
   - `apiDevicesList()` normalization at every `window._devices` assignment → WEB-03/04/05
   - `_NP_LOCK` around all read-mutate-write NP paths → SRV-05/06/16
   - Atomic XML write (`temp + rename`) + `playlist_xml_lock()` on all readers → SRV-07/08/09/10
   - `routeAlive(gen)` guards on Devices/Settings/WatchFolders/Routines → WEB-09/10/14/16
   - Library `loadGen` pattern → AND-08/16
3. Then P1s (Android startup flow AND-03/15, device merge crashes, genre play), then remaining P2/P3.
