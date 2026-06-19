# iOS ↔ Android Parity Plan

**Goal:** Make the iOS app function exactly like the Android app (`com.bockmedia.console`). This is the authoritative work plan to close every remaining gap.

**Status at time of writing:** The iOS app is a native SwiftUI rewrite that already has ~99 Swift source files and implements roughly Phases 0–5 of `docs/IOS_BUILD_PLAN.md`. The 4-tab shell, data/repository layer, home feed composer, search/library/playlists, Now Playing, offline downloads, local AVPlayer playback, and a WidgetKit extension are all present. **This plan therefore targets the specific behavioral gaps, not a from-scratch build.**

- Android source of truth: `android/app/src/main/kotlin/com/bockmedia/console/` (118 Kotlin files)
- iOS code under test: `ios/BockMedia/` + `ios/BockMediaWidget/`
- Shared API contract: `shared/api-contract/api-contract.yaml`

---

## 0. Implementation Status — DELIVERED

All phases A–F have been implemented. Summary of what shipped and the new files/edits:

| Gap | Status | Key files |
|---|---|---|
| G2 lock-screen for local | ✅ Done | `Media/LocalPlaybackController.swift` — `MPRemoteCommandCenter` (play/pause/toggle/next/previous/seek), `MPNowPlayingInfoCenter` with **artwork** + queue index |
| Local queue auto-advance (bug found) | ✅ Fixed | `LocalPlaybackController` — `AVPlayerItemDidPlayToEndTime` observer now advances/stops the queue (previously a track ended and playback stalled) |
| G1 lock-screen for remote Alexa | ⛔️ Not feasible on iOS (documented) | See **§6 platform constraint**. Remote control surface remains the WidgetKit widget + in-app Now Playing/mini bar (both already present) |
| G6 unified poller | ✅ Done | New `Media/NowPlayingPollService.swift`; `NowPlayingView`/`MiniNowPlayingBar` now share one loop. Cadence **5s playing / 20s idle**, paused when backgrounded (`RootView` scenePhase) |
| G3 background downloads + resume | ✅ Done | New `Offline/OfflineBackgroundSession.swift` (background `URLSession` + persisted task map); `OfflineDownloadManager` rewritten to enqueue + reconcile + `resumeIncomplete()`; `AppDelegate.handleEventsForBackgroundURLSession`; resume on Wi‑Fi return + BG task |
| G4 cancel from notification | ✅ Done | New `Offline/DownloadNotifications.swift`; `AppDelegate` `UNUserNotificationCenterDelegate` handles the Cancel action |
| G5 analytics charts | ✅ Done | `Features/Analytics/AnalyticsView.swift` — Swift `Charts` bar charts for by-date/by-hour/by-day-of-week, matching Android's Vico chart set |
| G7 cellular endpoint behavior | ✅ Done | `Data/Network/ServerEndpointResolver.swift` — `NWPathMonitor`; skips LAN probe on cellular and invalidates cache on network-class change (matches Android) |
| G9 widget artwork caching | ✅ Done | `BockMediaWidget` bundle configures a disk `URLCache` (cross-process cache can't be literally shared; see note) |
| G10 dead code / unused API | ✅ Done | Deleted `Features/Account/PlaceholderAccountViews.swift`; removed unused `playbackStatus()` from the API client |
| G8 ATS | ✅ Decided | Keep `NSAllowsArbitraryLoads` — required so users can point at arbitrary self-hosted LAN/HTTP servers (matches Android's cleartext flexibility) |
| G11 composer parity | 🔬 Test task | Covered by `BockMediaTests/ModelsTests.swift`; a full golden-fixture diff vs Android remains a QA step (no blind edits made) |
| G12 tests / CI | ✅ Done | New `BockMediaTests/OfflineAndNetworkTests.swift`; new `BockMediaUITests/` smoke target; `project.yml` scheme runs both bundles (CI `ios-tests` job already runs `xcodebuild test`) |

**Correction to original plan:** the plan's G5 said "add add-ignored from analytics rows." On inspection, the **Android** analytics screen only supports *removing* ignored tracks ("Allow again") — `CountRow` carries no file path, so add-ignored is only reachable from Now Playing on Android too. iOS now matches Android exactly (charts + "Allow again"); no invented feature.

> **Build note:** verified via `xcodegen generate` (project + scheme regenerate cleanly, all new files wired). A full `xcodebuild test` must be run on a machine with full Xcode (this dev box only has Command Line Tools); CI's `ios-tests` job covers it.

---

## 1. Parity Matrix (current state)

| Capability | Android | iOS | Gap |
|---|---|---|---|
| Setup / auth / endpoint resolver | ✅ | ✅ | TTL + ATS differences |
| 4-tab shell + account menu | ✅ | ✅ | — |
| Home feed (full composer rules) | ✅ | ✅ | Verify rule-for-rule parity |
| Search + browse + genre detail | ✅ | ✅ | — |
| Library hub + drill-down | ✅ | ✅ | — |
| Playlists CRUD / merge / AI / smart | ✅ | ✅ | — |
| Favorites | ✅ | ✅ | — |
| Device picker + remote (Alexa) play | ✅ | ✅ | — |
| Now Playing + mini bar | ✅ | ✅ | Poll cadence + lock screen |
| Alexa auth + monitor | ✅ | ✅ | — |
| Local phone playback | ✅ ExoPlayer | ✅ AVPlayer | `MPRemoteCommandCenter` missing |
| Offline downloads + Downloads UI | ✅ | ✅ (foreground) | Background URLSession + resume |
| Automations CRUD / run | ✅ | ✅ | — |
| Devices admin (rename/merge/groups/identify) | ✅ | ✅ | — |
| Rooms / voice log / routines | ✅ | ✅ | — |
| Settings (watch folders, cache, config, Plex) | ✅ | ✅ | — |
| Analytics + CSV export | ✅ charts (Vico) | ⚠️ text lists | Swift Charts + add-ignored |
| Deep links + quick actions / shortcuts | ✅ | ✅ | — |
| Now Playing widget | ✅ | ✅ | Idle/playing cadence |
| Artwork caching | ✅ Coil | ✅ NSCache/URLCache | Widget shares cache |
| Lock-screen NP for **Alexa remote** | ✅ (notif/widget) | ❌ | Build remote NP surface |
| Download cancel from notification | ✅ | ❌ | Add notification action |
| Tests (unit + UI) | ✅ instrumented + perf | ⚠️ 2 unit files | Expand + XCUITest |
| Per-device accounts (Phase 6) | ⚠️ partial | ❌ | Out of parity scope unless Android ships it |

Legend: ✅ done · ⚠️ partial · ❌ missing

---

## 2. Confirmed Gaps to Close (the actual work)

### G1 — Now Playing lock screen / Control Center for Alexa remote
- **Android:** Now-playing notification + widget reflect the focused Echo and accept transport controls even for remote (Alexa) playback (`media/NowPlayingNotificationManager.kt`, `media/NowPlayingMonitorService.kt`, widget controllers).
- **iOS today:** `MPNowPlayingInfoCenter` is updated **only** for local AVPlayer playback. No `MPRemoteCommandCenter` handlers at all.
- **Required:** Populate `MPNowPlayingInfoCenter` for the focused remote device while NP/mini bar is active, and register `MPRemoteCommandCenter` play/pause/next/previous targets that route to `repository.deviceControl(...)` for remote or `LocalPlaybackController` for local.

### G2 — `MPRemoteCommandCenter` for local playback
- Even local AVPlayer playback has no hardware/lock-screen button handling. Wire play/pause/next/previous/seek to `LocalPlaybackController`.

### G3 — Background downloads + resume
- **Android:** Foreground service + WorkManager periodic sync; downloads survive app backgrounding and resume on network availability (`local/OfflineDownloadForegroundService.kt`, `OfflineSyncWorker.kt`, `OfflineNetworkMonitor.kt`).
- **iOS today:** Uses foreground `URLSession.shared.download`; `BackgroundDownloadScheduler` only re-reads manifests on `BGAppRefreshTask`.
- **Required:** Move downloads to a `URLSessionConfiguration.background` session with a delegate; persist task→track mapping; have the BG task and network monitor resume incomplete downloads.

### G4 — Download cancel notification action
- **Android:** Notification exposes a cancel action during downloads (`local/OfflineDownloadActionReceiver.kt`).
- **iOS:** Add a `UNUserNotificationCenter` notification with a cancel action while a collection downloads.

### G5 — Analytics charts
- **Android:** Vico charts for plays-by-date, top artists/albums/genres/devices (`ui/analytics/AnalyticsScreen.kt`).
- **iOS today:** Text lists only (`Features/Analytics/AnalyticsView.swift`).
- **Required:** Swift Charts visualizations matching Android chart set; add the **add-ignored** action from analytics rows (iOS currently only supports remove-ignored; add is only reachable from Now Playing).

### G6 — Now Playing poll cadence + lifecycle parity
- **Android:** NP polls `nowPlayingDevices()` every 5s; mini bar shares cadence; widget refresh 5s playing / 30s idle.
- **iOS today:** NP 1.5s, mini bar 3–5s, widget 3s playing / 30s idle.
- **Required:** Align cadence to Android (or to the agreed optimized values) and ensure polling pauses when the view is not active. Pick one source-of-truth poller shared by NP + mini bar to avoid duplicate requests.

### G7 — Endpoint resolver TTL parity
- iOS `ServerEndpointResolver` uses 60s in-memory cache; Android uses 60s too — **already aligned**, but verify cellular behavior matches Android (`onCellularNetwork()` invalidation + external re-prime, skip-LAN-probe on cellular).

### G8 — App Transport Security tightening
- iOS currently sets `NSAllowsArbitraryLoads`. Android talks to LAN HTTP + external. **Decision needed** (see §6): keep arbitrary loads for self-hosted flexibility, or scope to specific exception domains. Functionally non-blocking for parity; security hygiene item.

### G9 — Widget artwork cache sharing
- iOS widget uses `AsyncImage` directly instead of the shared `ArtworkImageCache`. Align so the widget benefits from the shared disk cache and auth headers.

### G10 — Dead code / loose ends
- Remove unused `Features/Account/PlaceholderAccountViews.swift`.
- Wire or delete the unused `playbackStatus()` API method on the iOS client (Android uses `playbackStatus` in widget/now-playing controllers).

### G11 — Home feed rule-for-rule verification
- Both have `HomeFeedComposer` (~716 Kotlin / ~778 Swift lines). Likely close but **not guaranteed identical**. Needs a golden-output comparison (see §5 Testing) to confirm sections, ordering, dedup, mood rotation, and daily-mix seeds match.

### G12 — Tests / CI
- **Android:** unit + instrumented + perf budget tests.
- **iOS:** 2 unit test files, no XCUITest, no CI `xcodebuild test`.
- **Required:** port domain tests (composer, search filter, rotation, NP merge, routine phrase), add an XCUITest smoke path, add macOS CI job.

> **Phase 6 (per-device accounts)** is intentionally excluded from "parity" because Android only has it partially. Track it separately; do not block iOS parity on it.

---

## 3. Architecture Alignment Notes

Keep the iOS layering 1:1 with Android so future changes mirror cleanly:

| Concern | Android | iOS target |
|---|---|---|
| App entry | `MainActivity.kt`, `BockMediaApp.kt` | `BockMediaApp.swift`, `RootView.swift`, `AppState.swift` |
| Data repo | `BockMediaRepository.kt` | `BockMediaRepository.swift` |
| API | `BockMediaApi.kt` (Retrofit) | `BockMediaAPIClient.swift` (URLSession) |
| Endpoint resolve | `ServerEndpointResolver.kt` | `ServerEndpointResolver.swift` |
| Auth | `AuthInterceptor.kt` | `AuthHeaders.swift` |
| Prefs | `AppPreferences.kt` (DataStore) | `AppPreferences.swift` (UserDefaults + Keychain) |
| Home logic | `HomeFeed*.kt` | `HomeFeed*.swift` |
| Library logic | `LibraryLoader.kt` | `LibraryLoader.swift` |
| Playback focus | `PlaybackFocus.kt` | `PlaybackFocus.swift` |
| Local playback | ExoPlayer (`LocalPlaybackService.kt`) | AVPlayer (`LocalPlaybackController.swift`) |
| Offline | `local/Offline*.kt` | `Offline/Offline*.swift` |
| Widget | `widget/*` (RemoteViews) | `BockMediaWidget/*` + `Widget/*` (WidgetKit) |

**Platform translation rules of thumb:**
- ExoPlayer ↔ AVPlayer + `AVAudioSession(.playback)`
- WorkManager + foreground service ↔ `BGTaskScheduler` + background `URLSession`
- RemoteViews widget + AppWidgetProvider ↔ WidgetKit `TimelineProvider`
- DataStore ↔ UserDefaults/Keychain
- Coil ↔ `ArtworkImageCache` (NSCache + URLCache)
- Now-playing notification ↔ `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`

---

## 4. Phased Execution Plan

### Phase A — Playback surface parity (highest user-visible impact)
1. **G2:** Add `MPRemoteCommandCenter` handlers routed to `LocalPlaybackController`.
2. **G1:** Drive `MPNowPlayingInfoCenter` + remote command center for the focused **remote** device while NP/mini bar is active; route commands to `repository.deviceControl`.
3. **G6:** Unify NP + mini bar into a single shared poller; align cadence; pause when inactive.
- **Files:** `Media/LocalPlaybackController.swift`, `Features/NowPlaying/NowPlayingView.swift` (+ view model), `UI/Components/MiniNowPlayingBar.swift`, new `Media/RemoteCommandCenterBridge.swift`, `Domain/PlaybackFocus.swift`.
- **Done when:** Lock screen / Control Center shows current track + artwork and transport buttons work for both local and the focused Echo.

### Phase B — Offline hardening
4. **G3:** Convert `OfflineDownloadManager` to a background `URLSession`; persist task↔track map; resume on launch/network change/BG task.
5. **G4:** Add download notification with cancel action.
- **Files:** `Offline/OfflineDownloadManager.swift`, `Offline/BackgroundDownloadScheduler.swift`, `Offline/OfflineDownloadNetwork.swift`, new `Offline/DownloadNotifications.swift`.
- **Done when:** A large download started, then app backgrounded, completes/resumes; cancel works from the notification; matches Android 150-track cap, 6/2 concurrency (Wi-Fi/cellular), 128 kbps cellular transcode.

### Phase C — Analytics parity
6. **G5:** Swift Charts for plays-by-date + top artists/albums/genres/devices; add-ignored from rows; keep CSV export.
- **Files:** `Features/Analytics/AnalyticsView.swift`, new `Features/Analytics/AnalyticsCharts.swift`.
- **Done when:** Visual + interaction parity with `ui/analytics/AnalyticsScreen.kt` (date presets 7/30/all/custom, export, ignored add+remove).

### Phase D — Correctness verification
7. **G11:** Golden-output comparison of `HomeFeedComposer` against Android for identical API fixtures; fix divergences.
8. **G7:** Verify cellular endpoint behavior matches Android.
- **Files:** `Domain/HomeFeedComposer.swift`, `Domain/HomeFeedRules.swift`, `Domain/HomeMoodSections.swift`, `Data/Network/ServerEndpointResolver.swift`.

### Phase E — Cleanup + polish
9. **G9:** Widget uses shared `ArtworkImageCache`.
10. **G10:** Delete `PlaceholderAccountViews.swift`; wire/remove `playbackStatus()`.
11. **G8:** ATS decision + implementation.
- **Files:** `BockMediaWidget/BockMediaWidget.swift`, `Features/Account/PlaceholderAccountViews.swift` (delete), `Data/API/BockMediaAPIClient.swift`, `Info.plist`.

### Phase F — Tests & CI
12. **G12:** Port domain unit tests; add XCUITest smoke path; add macOS CI `xcodebuild test`.
- **Files:** `ios/BockMediaTests/*`, new `ios/BockMediaUITests/*`, `.github/workflows/ci.yml`.

**Suggested sequencing:** A → B → C in parallel tracks if multiple people; D before final QA; E and F continuous.

---

## 5. Per-Feature Acceptance Criteria (parity checklist)

Use this as the QA sign-off. Each item must behave identically to Android.

**Now Playing**
- [ ] Multi-device vertical pager; focused device first (`PlaybackFocus`)
- [ ] Local phone device prepended ahead of remote when active
- [ ] Progress scrubber, volume, shuffle, sleep timer (minutes/songs), favorite, ignore, stop
- [ ] Up-next + stream-history sheets
- [ ] Lock screen / Control Center reflects state and controls work (G1/G2)

**Home**
- [ ] Filter pills: All, Playlists, Mixes, Radio, Discover, Offline/Downloads
- [ ] Shortcut grid, horizontal sections, show-all, long-press actions, pull-to-refresh
- [ ] Identical section set/order/dedup/mood-rotation vs Android (G11)

**Library / Search / Playlists**
- [ ] Filters, list/grid, sort (Recents/Name), search/debounce, drill-downs
- [ ] Playlist CRUD, merge, AI playlist, smart playlist CRUD/refresh
- [ ] Favorites add/remove, add-to-playlist

**Offline**
- [ ] 150-track cap; 6 (Wi-Fi)/2 (cellular) concurrency; 128 kbps cellular transcode
- [ ] Survives backgrounding; resumes on network; cancel from notification (G3/G4)
- [ ] Wi-Fi-only toggle honored

**Devices / Rooms / Automations / Routines / Voice log**
- [ ] Rename, delete, merge, groups, identify, test clip
- [ ] Rooms grouped (Now playing/Paused/Idle), auto-refresh
- [ ] Automations CRUD + run; routine phrase generator

**Settings**
- [ ] Health card, library stats, Wi-Fi-only, watch folders, Plex sync, clear cache, server config editor, Alexa re-login

**Analytics**
- [ ] Charts parity + presets + CSV export + ignored add/remove (G5)

**Platform**
- [ ] Deep links: `bockmedia://` home/search/library/nowplaying/downloads/settings/analytics/control/play
- [ ] Quick actions/shortcuts: Now Playing, Search, Downloads
- [ ] Widget: per-device cards, controls, recent playlists when idle, correct refresh cadence

---

## 6. Decisions (resolved during implementation)

1. **Poll cadence (G6):** Adopted the optimized profile — **5s while playing, 20s idle, paused in background** — via the shared `NowPlayingPollService`. This also fixes the duplicate-poller inefficiency noted in the perf audit.
2. **ATS (G8):** **Keep `NSAllowsArbitraryLoads`.** The app must reach arbitrary user-configured self-hosted servers over HTTP on the LAN; scoping to fixed exception domains would break that and diverge from Android's cleartext config.
3. **Phase 6 accounts:** **Out of scope** for parity until Android finalizes it.
4. **Analytics (G5):** **Full Swift Charts** delivered (bar charts), matching Android's Vico chart set.

### Platform constraint: lock-screen control for *remote* Alexa playback (G1)

On iOS, `MPNowPlayingInfoCenter` / lock-screen "Now Playing" controls are only granted to the app that owns an **active audio session** (i.e. local AVPlayer playback). When a remote Echo is playing, the iPhone produces no audio, so iOS will not surface that session on the lock screen — unlike Android, where a media-style notification can represent any device. This is an OS-level limitation, not a code gap.

**Delivered instead (full parity within iOS capabilities):**
- Local phone playback now has complete lock-screen / Control Center support (transport + scrubbing + artwork).
- Remote-device control parity is provided by the **WidgetKit Now Playing widget** (Lock Screen / Home Screen) and the in-app Now Playing screen + mini bar, all of which control the focused Echo.

---

## 7. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Background `URLSession` + AVPlayer audio session interactions | Test download-while-playing; configure audio session category carefully |
| Remote command center conflicting with local vs remote focus | Single arbiter keyed off `PlaybackFocus.focusedDeviceId`; update commands on focus change |
| Home composer drift is subtle | Golden fixtures from the same API responses on both platforms |
| Widget timeline budget on iOS | Respect WidgetKit refresh budgets; don't poll faster than Android equivalent |
| API contract drift | Add CI check that iOS DTOs cover `shared/api-contract/api-contract.yaml` |

---

## 8. Reference: Android Source Map (for translation)

- Entry: `MainActivity.kt`, `BockMediaApp.kt`
- Data: `data/repository/BockMediaRepository.kt`, `data/api/BockMediaApi.kt`, `data/api/dto/ApiDtos.kt`, `data/network/ServerEndpointResolver.kt`, `data/auth/AuthInterceptor.kt`, `data/local/AppPreferences.kt`
- Domain: `domain/model/HomeFeed*.kt`, `LibraryLoader.kt`, `PlaybackFocus.kt`, `SessionDiskHydrator.kt`, caches/persistence
- Media: `media/LocalPlaybackController.kt`, `LocalPlaybackService.kt`, `NowPlayingNotificationManager.kt`, `NowPlayingMonitorService.kt`
- Offline: `local/OfflineDownloadManager.kt`, `OfflineDownloadStore.kt`, `OfflineDownloadForegroundService.kt`, `OfflineSyncWorker.kt`, `OfflineNetworkMonitor.kt`, `OfflineDownloadActionReceiver.kt`
- Widget: `widget/NowPlayingWidgetProvider.kt`, `WidgetRefreshWorker.kt`, `NowPlayingController.kt`
- UI: `ui/{home,search,library,nowplaying,playlists,automation,settings,downloads,devices,analytics,rooms,routines,recent,favorites,setup}/`, `ui/components/`, `ui/theme/Theme.kt`

---

*This plan is scoped to behavioral parity. Treat screen-level features as complete on iOS; the work is closing G1–G12.*
