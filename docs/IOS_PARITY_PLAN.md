# iOS ↔ Android Parity Plan

**Goal:** The iOS app must match **every** user-facing feature and UI workflow that Android ships. Android (`com.bockmedia.console`) is the **sole source of truth** for behavior, navigation, and capability. iOS may use SwiftUI idioms (navigation, sheets, toolbars) but must not omit, simplify, or substitute a different workflow unless the gap is **platform-limited** and documented in §8.

**Parity policy**

1. **New Android feature → iOS port required** before the feature is considered done (same release train when possible).
2. **No iOS-only product features** unless Android gets the same capability in the same sprint.
3. **Platform limits** (remote Alexa lock-screen, etc.) are mitigations, not excuses to skip adjacent parity (widget, in-app NP, notifications).
4. **Acceptable differences:** system UI chrome, haptics, SwiftUI vs Compose layout — not missing screens, buttons, or API wiring.
5. **Verification:** side-by-side QA (§7) + golden home fixtures + API contract check.

**Status (July 2026 · Android/iOS v2.6.155):** Core shell, home composer, search/library/playlists, Now Playing, offline downloads, local playback, WidgetKit, Family, Listen Agent, lyrics, music video, **artist follow feed**, and **followed-artist notifications** are on both platforms. **iOS parity backlog P1–P8 closed in this pass** (library health, home pins, search discovery, NP video skip-clear, profile banner, device fix wizard, download status snapshot, CI + API contract + 45 unit tests green). **§7 device QA (July 6):** API + contract + deploy to Andrew's iPhone ✅; automated UI parity tests blocked on device (profile picker + UI automation permission) — see §7.1. **Remaining optional rows:** routines menu, Release Radar alignment, Quick Actions.

| Surface | Path | Scale |
|---------|------|-------|
| Android (source of truth) | `android/app/src/main/kotlin/com/bockmedia/console/` | ~90 UI Kotlin files + domain/media/local/widget |
| iOS (under test) | `ios/BockMedia/` + `ios/BockMediaWidget/` | ~100 Swift source files |
| Shared API contract | `shared/api-contract/api-contract.yaml` | |

---

## 1. Android Feature Catalog (July 2026)

Complete inventory of user-facing features on Android **v2.6.155**. When in doubt about iOS behavior, read Android first.

### 1.1 App shell & navigation

**Bottom navigation (5 tabs)**

| Tab | Route | Screen |
|-----|-------|--------|
| Home | `home` | `HomeScreen` |
| Search | `search` | `SearchScreen` |
| Library | `library` | `LibraryScreen` |
| Downloads | `downloads` | `DownloadsScreen` |
| Automations | `automations` | `AutomationScreen` |

**Account menu** (person icon on tab headers) — `AccountMenu.kt`

| Section | Destinations |
|---------|--------------|
| Library | Settings, Downloads, Analytics |
| Alexa & home | Voice log, Rooms, Alexa Devices, Family, Driving Mode |
| App | About |

**Overlays (not routes)**

| Feature | Trigger | Screen / component |
|---------|---------|-------------------|
| Listen Agent | Mic on **Home, Search, Library, Automations** headers | Modal `ListenAgentScreen` |
| Device picker | Any play action | `DevicePickerSheet` |
| Profile picker gate | First launch when household has members | `ProfilePickerGate` |
| Now Playing | Mini bar, widget, deep link, auto after play | Full-screen `NowPlayingScreen` |

**Registered routes** (`BockNavGraph.kt` / `Routes.kt`)

| Route | Screen | Notes |
|-------|--------|-------|
| `home` | Home | Start destination |
| `search` | Search | |
| `library` | Library | |
| `downloads` | Downloads | Also bottom tab |
| `automations` | Automations | |
| `nowplaying` | Now Playing | No top bar |
| `favorites` | Rated Songs | From Library |
| `playlists` | Playlists | From Settings / Library |
| `playlists/detail/{id}` | Immersive playlist detail | Optional home-pin prompt |
| `artists` | Artists | Library drill-down |
| `albums/{artist}` | Artist detail | Immersive; Music \| About tabs |
| `albums/{artist}/discography` | Full discography | Filters |
| `albums` | Albums | |
| `songs/artist/{artist}` | Songs by artist | |
| `songs/album/{album}` | Album detail | Immersive; sticky mini-header |
| `songs` | All songs | |
| `genre/{name}` | Genre detail | From search browse |
| `rooms` | Rooms | Account menu |
| `devices` | Alexa Devices | Account menu |
| `family` | Family | Account menu |
| `driving` | Driving Mode | Account menu |
| `recent` | Voice log | Account menu |
| `analytics` | Analytics | Account menu |
| `settings` | Settings | Account menu |
| `about` | About | Account menu |
| `routines` | Routines | **Route only** — not in Account menu; `#routines` deep link |

**Deep linking:** `MainActivity` route extras / hash routes. Widget tap → `#nowplaying`.

---

### 1.2 Bootstrap, auth & profiles

| Workflow | Android behavior | Key files |
|----------|------------------|-----------|
| Cold boot | Disk-hydrate home/library cache; endpoint priming; auto-login if remembered | `SessionDiskHydrator`, `SplashScreen` |
| Setup | Mobile API token **or** username/password; Remember me; LAN/external URL | `SetupScreen` |
| Profile gate | “Who’s listening?” — pick household member | `ProfilePickerGate` |
| Home profile banner | Prompt to select Family profile for ratings/sync | `HomeScreen` |
| Alexa auth monitor | Snackbar when Alexa session needs re-login | `AlexaAuthMonitor` |
| Endpoint failover | LAN probe; cellular uses external URL | `ServerEndpointResolver` |

---

### 1.3 Home

| Capability | Detail |
|------------|--------|
| Feed sections | Jump Back In, Rated Songs, Browse Genres, Top Mixes, Mood, Decade, **New from artists you follow**, Release Radar, Discover Weekly, Explore Themes, Daily Mixes, Recent Playlists, Radio, Discover, More Playlists, Offline downloads |
| Filter pills | All, Recents, Playlists, Mixes, Radio, Discover, Downloads |
| Shortcut row | Up to 6 playlist/mix tiles (All filter only) |
| Card actions | Play, Download, Browse (playlist/artist/album/genre/downloads) |
| Section pins | Pin playlists to home rows; local + server sync | `HomeSectionPinSheet`, `HomeSectionPinsStore` |
| Tile rotation | Daily shuffle of stale tiles | `HomeTileRotation` |
| Listen Agent | Header mic |
| Follow notifications | Local notification when followed artists get new library music | `FollowNotificationSync` |
| Downloads pill | Quick jump to Downloads tab |
| Alexa / profile banners | Remote unavailable; profile not selected |

**Performance:** disk cache hydrate; `HomeLoadCoordinator`; `HomeArtworkResolver.warmAll`; `LocalVisibleDownloadStatuses` (single download snapshot for scroll).

---

### 1.4 Search & discovery

| Capability | Detail |
|------------|--------|
| Unified search | Debounced; songs, albums, artists, playlists, genres, smart playlists |
| Result filters | All / Songs / Albums / Artists / Playlists / Genres |
| In-search rating | Star ratings on song hits |
| Empty-state browse | Picked For You, New Releases, Genres |
| Plexamp browse | Top Artists/Albums/Tracks/Best Of; **Search Pins**; **Sonic Adventure**; Sonic Sage (MixMuse) |
| Search pins editor | Add/remove/reorder pinned shortcuts | `SearchPinsEditorSheet` |
| Recent selections | Persisted history |
| Discovery dialogs | MixMuse, Resonance mix/radio, Acquire ideas |
| Library source filter | All libraries vs watch-folder path |
| Session cache | Restores last query on return |
| Listen Agent | Search bar mic |

---

### 1.5 Library & rated songs

| Capability | Detail |
|------------|--------|
| Filters | All, Playlists, Artists, Albums, Tracks (+ Favorites) |
| View mode | Grid / List (persisted) |
| Sort | Recents, A–Z (persisted) |
| In-library search | Unified search overlay |
| Pagination | Artists, Albums, Tracks |
| Download badges | Per-item offline state |
| **Library health banner** | Missing tags, duplicate artists; **artist merge** action | `LibraryHealthBanner` |
| Artist / album detail | Follow, ratings, discovery, discography, videos row, share |
| Tab warm | Background library load | `TabWarmCoordinator` |
| Listen Agent | Header mic |

**Rated Songs:** star buckets 1–5; play all at each level.

---

### 1.6 Artist & album detail (v2.6.150+)

| Capability | Detail |
|------------|--------|
| Dynamic hero color | From artwork |
| Music \| About tabs | Bio, stats, genres, first added |
| Follow / Following | 3-star artist rating; feeds + notifications |
| Popular tracks | Inline like + star rating |
| Latest in library | Highlight card |
| Discography | Full view with filters |
| Videos row | Artist-scoped music videos only |
| Fans also like | Similar artists grid |
| Appears on | Compilation appearances |
| Listen Agent mic | Opens voice input (suggested prompt, no auto-play) |
| Mix Muse / Resonance / download / share | Detail sheets |

Key files: `ArtistDetailScreen.kt`, `AlbumDetailScreen.kt`.

---

### 1.7 Playlists

| Capability | Detail |
|------------|--------|
| List | Search, pagination, merge mode |
| Smart playlists | Create/edit/delete/refresh |
| AI playlist | `POST /api/playlists/ai` |
| Merge | Multi-select |
| Detail | Filter/sort, paginate, rename, delete, drag reorder |
| Share | Household member sharing |
| Daily playlists | Save to library |
| Discovery | MixMuse, Resonance, Acquire |
| Download / resync | Offline collection |
| **Pin to Home** | Prompt on new AI playlists | `HomeSectionPinSheet` |
| Play | Device picker or Play on this phone |
| Add to room | Queue to Echo |

---

### 1.8 Now Playing & playback

**Play targets:** Playlist, Artist, Album, Song, Radio.

**Remote (Alexa):** device picker, transport, shuffle, **loop**, volume, multi-device pager, sleep timer, stream history, Up Next, add-to-room, room requests.

**Local phone:** Media3 ExoPlayer, crossfade 0–20s, continue-after-queue, foreground notification, offline fallback.

**Panels:** Artwork, **Lyrics**, **Music video** (muted proxy + library audio). Prefetch next track video; **skip-clear** on track change (`activeVideoTrackKey`).

**Mini bar:** all bottom-nav roots.

**Driving Mode:** local playback only.

**Android Auto:** minimal browse stub.

---

### 1.9 Listen Agent

| Step | Behavior |
|------|----------|
| Entry | Mic on Home, Search, Library, Automations; artist page |
| UI | SpeechRecognizer; mic orb; transcript; keyboard fallback |
| API | `GET/POST /api/listen-agent/*` |
| Playback | Local phone; auto-opens Now Playing |

---

### 1.10 Artist follow & notifications (v2.6.155)

| Capability | Detail |
|------------|--------|
| Follow button | Artist detail; toggles 3-star artist rating |
| Home feed | **New from artists you follow** — album cards from `/api/home` → `followedLibraryNew` |
| Notifications API | `GET /api/notifications/followed` — unread since cursor |
| Mobile alert | Local notification on Home load when unread (not background FCM yet) |
| Web | Bell badge + “Artists you follow” section in notifications panel |

Key files: `FollowNotificationSync.kt`, `bock_library_new.py`, `bock_ratings.list_followed_artists`.

---

### 1.11 Offline downloads

Initiate from home long-press, playlist detail, play/download actions. Max 150 tracks; profile-scoped; Wi‑Fi-only; 6/2 concurrency; 128 kbps cellular transcode; foreground service; cancel notification; Downloads tab; home offline section.

---

### 1.12 Automations, rooms, devices, family

**Automations:** scheduled Alexa play; CRUD + run now.

**Rooms:** live list; 8s poll; quick play.

**Alexa Devices:** rename, delete, merge, groups, identify-all, test clip, **fix wizard** (`DeviceFixWizard`).

**Family:** profile switch, members + PIN, room ownership, kid policies, room requests, messages.

**Voice log:** paginated Alexa heard/found/success.

**Routines:** phrase generator — route exists, not in Account menu (both platforms).

---

### 1.13 Analytics

Date presets, summary grid, **device filter**, Swift Charts / Vico charts, CSV export, ignored tracks + “Allow again”. Add-ignore from rows **not wired** on either platform.

---

### 1.14 Settings & About

Library stats, crossfade, continue-after-queue, Wi‑Fi downloads, watch folders, server config, health, Alexa login, About/version.

---

### 1.15 Widget

Now Playing widget: all devices, per-device transport, refresh worker, tap → `#nowplaying`. Remote Echo → low-priority NP notification when widget installed.

---

### 1.16 API surface (Android `BockMediaApi.kt`)

iOS `BockMediaAPIClient.swift` must cover the same endpoints.

| Domain | Endpoints |
|--------|-----------|
| Core / home | `/api/home` (incl. `followedLibraryNew`), `/api/summary`, `/api/health`, `/api/dashboard/quick`, `/api/config`, `/api/settings` |
| Follow / new music | `/api/library/new?followed=1`, `/api/followed-artists`, `/api/notifications/followed` |
| Playback | `/api/nowplaying*`, `/api/playlists/play`, `/api/playback/handoff` |
| Library | `/api/playlists*`, `/api/artists/{name}`, `/api/albums`, `/api/songs`, `/api/genres`, `/api/library/health`, `/api/library/artists/merge`, `/api/music-video/related` |
| Search & discovery | `/api/search*`, `/api/search/pins`, `/api/continue`, `/api/discover-weekly`, `/api/listen-agent/*`, `/api/mix-muse/*`, `/api/resonance/*`, `/api/acquire/*` |
| Lyrics & video | `/api/lyrics`, `/api/music-video*` |
| Alexa remote | `/api/alexa_remote/*` |
| Household | `/api/household`, `/api/clients/prefs`, `/api/messages`, `/api/analytics/household` |
| Ratings | `/api/ratings`, `/api/favorites`, `/api/ignored` |
| Analytics | `/api/analytics`, `/api/analytics/export`, `/api/recent` |

---

## 2. Full Parity Matrix (July 2026)

Legend: ✅ parity · ⚠️ partial · ❌ missing · ⛔ platform-limited · 🔧 infra

| # | Capability | Android | iOS | iOS action |
|---|------------|---------|-----|------------|
| | **Shell & navigation** | | | |
| 1 | 5-tab shell + account menu | ✅ | ✅ | — |
| 2 | All registered routes | ✅ | ✅ | — |
| 3 | Routines in account menu | ❌ | ❌ | Add to both menus (optional polish) |
| 4 | Deep links + widget → NP | ✅ | ✅ | — |
| | **Bootstrap** | | | |
| 5 | Setup / auth / endpoint resolver | ✅ | ✅ | — |
| 6 | Profile picker gate | ✅ | ✅ | — |
| 7 | Home “select profile” banner | ✅ | ✅ | — |
| | **Home** | | | |
| 8 | Home feed composer (all sections) | ✅ | ✅ | — |
| 9 | New from artists you follow | ✅ | ✅ | — |
| 10 | Follow-release notifications | ✅ | ✅ | — |
| 11 | Filter pills + shortcuts | ✅ | ✅ | — |
| 12 | Section pins **display** (server) | ✅ | ✅ | — |
| 13 | Section pins **editor UI** | ✅ | ✅ | — |
| 14 | Section pins **local store** + merge | ✅ | ✅ | — |
| 15 | AI playlist pin-to-home prompt | ✅ | ✅ | — |
| 16 | Home scroll under downloads | ✅ | ✅ | — |
| 17 | Listen Agent mic (Home/Search/Library/Auto) | ✅ | ✅ | iOS uses nav toolbar; verify UX parity |
| | **Search** | | | |
| 18 | Unified search + chips | ✅ | ✅ | — |
| 19 | Plexamp Top * rankings | ✅ | ✅ | — |
| 20 | Search pins display | ✅ | ✅ | — |
| 21 | Search pins **editor** | ✅ | ✅ | — |
| 22 | Sonic Adventure screen | ✅ | ✅ | — |
| 23 | Sonic Sage → MixMuse (not Best Of) | ✅ | ✅ | — |
| 24 | Release Radar browse | ⚠️ card→Genre | ✅ dedicated | Align behavior (either port `ReleaseRadarView` to Android or accept) |
| | **Library** | | | |
| 25 | Library hub (filters, grid/list, sort) | ✅ | ✅ | — |
| 26 | Library health banner + artist merge | ✅ | ✅ | — |
| 27 | Rated songs star buckets | ✅ | ✅ | — |
| | **Artist / album** | | | |
| 28 | Artist detail (tabs, follow, videos, etc.) | ✅ | ✅ | — |
| 29 | Album detail (sticky header, disc play) | ✅ | ✅ | Verify side-by-side |
| 30 | Listen Agent from artist (voice, no auto-play) | ✅ | ✅ | — |
| | **Playlists** | | | |
| 31 | CRUD / merge / smart / AI / share / reorder | ✅ | ✅ | — |
| 32 | Pin to home from playlist detail | ✅ | ✅ | — |
| | **Now Playing** | | | |
| 33 | Multi-device pager + transport + loop + sleep | ✅ | ✅ | — |
| 34 | Lyrics panel | ✅ | ✅ | — |
| 35 | Music video toggle + proxy | ✅ | ✅ | — |
| 36 | Music video **skip-clear** | ✅ | ✅ | — |
| 37 | Music video when cookies stale | ✅ tries proxy | ✅ tries proxy | — |
| 38 | Music video prefetch | ✅ | ✅ | — |
| 39 | Remote Alexa lock-screen NP | ✅ notif/widget | ⛔ | Widget + in-app NP (§8) |
| 40 | Playback handoff UI | ❌ | ❌ | Future — both platforms |
| 41 | Block/ignore from NP | ❌ | ❌ | Future — both platforms |
| | **Listen Agent** | | | |
| 42 | Voice + text + local play | ✅ | ✅ | — |
| | **Offline** | | | |
| 43 | Downloads full workflow | ✅ | ✅ | — |
| | **Automations / Family / Rooms / Devices** | | | |
| 44 | Automations CRUD | ✅ | ✅ | — |
| 45 | Family / room requests / messages | ✅ | ✅ | — |
| 46 | Devices admin | ✅ | ✅ | — |
| 47 | Device **fix wizard** | ✅ | ✅ | — |
| 48 | Rooms / voice log / driving | ✅ | ✅ | — |
| | **Analytics / Settings** | | | |
| 49 | Analytics + device filter + CSV | ✅ | ✅ | — |
| 50 | Settings / About / Alexa login | ✅ | ✅ | — |
| | **Widget / platform** | | | |
| 51 | Now Playing widget | ✅ | ✅ | — |
| 52 | iOS Quick Actions | ❌ | ✅ | Port to Android or document as iOS-only |
| 53 | Android Auto / CarPlay | ⚠️ stub | ⚠️ stub | Out of scope (§10) |
| | **Quality** | | | |
| 54 | Golden home composer tests | ✅ | ✅ | — |
| 55 | CI green (iOS simulator job) | ✅ | ✅ | — |
| 56 | API contract validation | ✅ | ✅ | — |

**Summary:** ~56 ✅ · ~2 ⚠️ (Release Radar browse, CarPlay stub) · **0 ❌ on iOS product gaps** · 2 ⛔/shared-missing · 0 🔧

---

## 3. iOS Backlog — Close Every Gap

Work until **every ❌ and ⚠️ row in §2 is ✅** (except ⛔ and §10 out-of-scope).

| ID | Priority | Gap | Android reference | iOS target |
|----|----------|-----|-------------------|------------|
| **P1** | High | Library health banner + artist merge | ✅ Done — `LibraryHealthBanner.swift`, `libraryHealth()` / `mergeArtists()` |
| **P2** | High | Home section pins (UI + local store + AI prompt) | ✅ Done — `HomeSectionPinSheet`, `HomeSectionPinsStore`, `suggestHomePin` |
| **P3** | High | Search discovery parity | ✅ Done — `SearchPinsEditorSheet`, `SearchSonicAdventureView`, Sonic Sage → MixMuse |
| **P4** | High | Music video skip UX | ✅ Done — `activeVideoTrackKey`, removed cookie-stale display gate |
| **P5** | Medium | Home profile-selection banner | ✅ Done — `HomeView` unattributed banner |
| **P6** | Medium | Device fix wizard | ✅ Done — `DeviceFixWizard.swift` |
| **P7** | Medium | Home scroll under active downloads | ✅ Done — `VisibleDownloadStatusesProvider` |
| **P8** | Medium | CI + contract + QA | ✅ Done — `check_api_contract.py`, CI `api-contract` job, 45 iOS unit tests green; §7 device QA optional |

**Suggested order:** Product gaps P1–P8 are closed. Optional: routines menu, Release Radar browse alignment, Quick Actions port, §7 side-by-side QA.

---

## 4. Android-Only Components → iOS Port Map

Every file below **must** have an iOS equivalent before parity is declared done.

| Android component | Purpose | iOS port target |
|-------------------|---------|-----------------|
| `LibraryHealthBanner.kt` | Tag health + duplicate artist merge | `LibraryView.swift` + new `LibraryHealthBanner.swift` |
| `HomeSectionPinSheet.kt` | Pin playlist to home section | `HomeSectionPinSheet.swift` + hook in `HomeView` / playlist detail |
| `HomeSectionPinsStore.kt` | Local pins + ClientPrefs sync | `HomeSectionPinsStore.swift` (mirror Android keys) |
| `SearchPinsEditorSheet.kt` | Edit search pins | `SearchPinsEditorSheet.swift` |
| `SearchSonicAdventureScreen.kt` | Curated discovery tour | `SearchSonicAdventureView.swift` |
| `DeviceFixWizard.kt` | Alexa device troubleshooting | `DeviceFixWizard.swift` |
| `OfflineDownloadUi.kt` `LocalVisibleDownloadStatuses` | Scroll perf during downloads | Shared download-status provider on iOS |
| `NowPlayingScreen.kt` video skip-clear | Immediate video clear on skip | `NowPlayingView.swift` |

---

## 5. iOS-Only Items (Resolve or Port)

| iOS-only | Decision |
|----------|----------|
| `ReleaseRadarView` (dedicated screen) | **Port to Android** or change Android Release Radar card to match — pick one behavior in §7 QA |
| `ManagePlaylistsView` (Library entry) | Android uses Settings → Playlists; verify equivalent path exists |
| Home greeting / first name | Optional polish; not blocking if Android omits |
| Quick Actions (3D Touch / icon menu) | **Port to Android** app shortcuts or drop from parity requirement |
| `CarPlayCoordinator` stub | Matches Android Auto stub — §10 |

---

## 6. Architecture Alignment

| Concern | Android | iOS |
|---------|---------|-----|
| Navigation | `BockNavGraph.kt`, `Routes.kt` | `MainTabView.swift`, `SearchRoute`, `LibraryRoute` |
| Repository | `BockMediaRepository.kt` | `BockMediaRepository.swift` |
| Home logic | `HomeFeed*.kt` | `HomeFeed*.swift` |
| Artist follow | `ArtistDetailScreen.kt`, `FollowNotification*.kt` | `GenreDetailView.swift`, `FollowNotificationSync.swift` |
| Listen Agent | `ui/listen/*` | `DiscoveryActions.swift` |
| Music video | `MusicVideoPanel.kt` | `MusicVideoPanel.swift` |
| Offline | `local/Offline*.kt` | `Offline/*.swift` |
| Widget | `widget/*` | `BockMediaWidget/*` |

**Platform translation:** ExoPlayer ↔ AVPlayer · WorkManager ↔ BGTask/URLSession · RemoteViews ↔ WidgetKit · Coil ↔ `ArtworkImageCache`.

---

## 7. Acceptance Criteria & QA Checklist

Sign-off when **§2 matrix is all ✅** (except ⛔/§10). Run on Android + iPhone, same server/account, Wi‑Fi then cellular.

### Setup & profiles
- [ ] Setup, cellular URL, profile gate, Alexa re-login
- [ ] Home profile banner when unattributed

### Home
- [ ] All filter pills and sections incl. **New from artists you follow**
- [ ] Follow artist → new album appears in feed after library index
- [ ] Follow notification (mobile local / web bell)
- [ ] Section pin: pin playlist, persists across relaunch + profile sync
- [ ] Listen Agent mic on Home, Search, Library, Automations
- [ ] Smooth scroll during active downloads

### Search & Library
- [ ] Search pins editor; Sonic Adventure; Sonic Sage opens MixMuse
- [ ] Library health banner; merge duplicate artists
- [ ] Artist page: Follow, videos row, Listen Agent voice

### Playlists & NP
- [ ] AI playlist → pin-to-home prompt
- [ ] Music video: skip clears old video immediately
- [ ] Lyrics; local lock screen (iOS local only)

### Offline / Family / Devices
- [ ] Full download workflow; device fix wizard

### Side-by-side script (10 steps)

1. Setup + profile; Wi‑Fi and cellular URL.
2. Home: same sections; follow an artist; verify feed row after index update.
3. Search: pins editor, Sonic Adventure, MixMuse from Sonic Sage.
4. Listen Agent on **each** mic entry; artist page mic speaks (no auto-play).
5. NP: video skip-clear; lyrics.
6. Library health + merge.
7. Pin playlist to home section.
8. Download + background + cancel notification.
9. Device fix wizard.
10. Analytics CSV + widget.

**Pass:** no user-visible behavioral difference (platform chrome OK).

### 7.1 Device QA run log (2026-07-06 · Andrew's iPhone · v2.6.79)

| Check | Result | Notes |
|-------|--------|-------|
| API `library/health`, `home`, `search/pins`, `followed-artists`, `notifications/followed` | ✅ | All HTTP 200 vs `https://alexa.morejava.bid` |
| `scripts/check_api_contract.py` | ✅ | |
| Debug build install to device | ✅ | `./scripts/deploy_ios_app.sh` |
| Automated smoke + parity UI tests | ⛔ | Blocked: profile gate + *Not authorized for UI testing* — enable automation on device, pick profile, retry `./scripts/run_ios_parity_qa.sh SKIP_DEPLOY=1` |
| Manual §7 side-by-side (10 steps) | 🔧 | Tap-through on device after UI auth fixed |

---

## 8. Platform Constraints (Not iOS Backlog)

| Constraint | Mitigation on iOS |
|------------|-------------------|
| Remote Alexa lock-screen (`MPNowPlayingInfoCenter` requires local audio) | WidgetKit widget + in-app NP + mini bar |
| ATS / self-hosted LAN HTTP | Keep `NSAllowsArbitraryLoads` |
| Background push for follow alerts (no FCM/APNs yet) | Local notification on Home open; web bell badge |
| Handoff / block-from-NP | Not shipped on Android either — do not iOS-only |

---

## 9. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Home composer drift | Golden fixtures both platforms |
| Music video server load | gunicorn pool; client prefetch; timeouts |
| YouTube cookie expiry | `youtube_cookies.sh`; surface stale in health |
| API contract drift | CI DTO validation (P8) |
| Parity doc stale | Update §1–§3 on every Android feature PR |

---

## 10. Out of Scope (Both Platforms)

| Item | Notes |
|------|-------|
| Phase 6 per-device accounts | `docs/PHASE6_ACCOUNTS.md` |
| Full CarPlay / Android Auto browse | Stubs only |
| Background FCM/APNs push for follow alerts | Future; local/in-app notifications shipped |
| Playback handoff UI | API exists |
| Block/ignore from Now Playing | Analytics “Allow again” only |
| Web-only features | Not mobile parity |

---

## 11. Android Source Map

| Area | Path |
|------|------|
| Navigation | `ui/navigation/BockNavGraph.kt`, `Routes.kt` |
| Home | `ui/home/HomeScreen.kt`, `domain/model/HomeFeed*.kt`, `FollowNotification*.kt` |
| Search | `ui/search/*` (incl. `SearchSonicAdventureScreen`, `SearchPinsEditorSheet`) |
| Library | `ui/library/*`, `LibraryHealthBanner.kt`, `ArtistDetailScreen.kt` |
| Listen Agent | `ui/listen/ListenAgentScreen.kt` |
| Now Playing | `ui/nowplaying/NowPlayingScreen.kt`, `MusicVideoPanel.kt` |
| Devices | `ui/devices/DeviceFixWizard.kt` |
| Server (follow + video) | `bock_library_new.py`, `bock_ratings.py`, `bock_listen_agent.py` |

---

*Android **v2.6.155** is the reference. Update this doc when adding Android features or closing iOS gaps in §3. Parity is not complete until §2 has no ❌ or ⚠️ for iOS.*
