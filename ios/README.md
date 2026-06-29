# Bock Media — iOS

Native SwiftUI client for the Bock Media server. Mirrors the Android app (`android/`) against the same `/api/*` REST API.

## Requirements

- **macOS** with **Xcode 15+** (full Xcode, not Command Line Tools only)
- iOS 17+ device or simulator
- Bock Media server on LAN (`http://127.0.0.1:3001`) or your external URL

## Point Xcode at the full app (once)

If `xcodebuild` says Command Line Tools only:

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

## Generate Xcode project

```bash
cd ios
cp Config.xcconfig.example Config.xcconfig   # first time only; fill in Team ID + credentials
xcodegen generate
open BockMedia.xcodeproj
```

`Config.xcconfig` is gitignored — never commit API tokens or admin passwords.

## Build & run

1. Select your **Personal Team** in Signing & Capabilities.
2. Choose a simulator or connect an iPhone via USB.
3. **Product → Run** (⌘R).

### Server defaults

Optional LAN/external URLs can be pre-filled in `ios/Config.xcconfig` (from `Config.xcconfig.example`). Mobile API token and admin credentials are **not** hardcoded — set them in `Config.xcconfig` or enter them on the setup screen.

## App Transport Security

HTTP to the home server is allowed via `NSAllowsLocalNetworking` in `project.yml` / `Info.plist`.

## Icons

**Home screen app icon** matches Android — sourced from `public/img/icon-512.png` (Bock Media bottle-cap logo). Re-sync after updating that file:

```bash
ios/scripts/sync_app_icon.sh
```

**In-app UI icons** use the same **Material Icons (baseline/filled)** as Android `Icons.Default.*`, bundled in `BockMedia/Resources/Assets.xcassets` and referenced via `BockIcon` / `BockIcons`. Re-fetch after adding icons:

```bash
ios/scripts/fetch_material_icons.sh
```

## URL scheme

`bockmedia://` deep links: `home`, `search`, `library`, `nowplaying`, `downloads`, `settings`, `analytics`, `control?deviceId=&action=`.

## Current status

| Done | Feature |
|------|---------|
| ✅ | Setup / auth / endpoint resolver |
| ✅ | 4-tab shell (Home, Search, Library, Automations) |
| ✅ | Home feed (dashboard + playlists) |
| ✅ | Search + remote play via device picker |
| ✅ | Theme parity (`#1DB954` green) |
| ✅ | `clientId` in Keychain (Phase 6 stub) |
| ✅ | Now Playing screen (poll, scrubber, transport, volume) |
| ✅ | Mini now playing bar above tab bar |
| ✅ | Alexa auth monitor + Settings health / re-login |
| ✅ | Play on this iPhone (AVPlayer + LAN stream) |
| ✅ | Offline downloads (manifest, Wi‑Fi only, Downloads screen) |
| ✅ | Library drill-down (Favorites, Playlists, Artists, Albums, Songs) |
| ✅ | Account menu: Downloads, Analytics, Routines, Voice log, Rooms, Devices |
| ✅ | Home / library download buttons |
| ✅ | Automations create/edit sheet (device groups, day presets) |
| ✅ | Smart playlists (Manage playlists → create/edit/refresh) |
| ✅ | Analytics date presets, CSV export, ignored tracks list |
| ✅ | Deep links (`bockmedia://…`) |
| ✅ | WidgetKit Now Playing widget (App Group snapshot) |
| ✅ | Genre detail, browse genres, add-to-playlist sheet |
| ✅ | Full HomeFeedRules (Daily Mix, Radio, Discover, offline home row) |
| ✅ | Settings: watch folders, clear server cache, Plex sync, server config |
| ✅ | Devices admin (rename, merge, groups, identify, test clip) |
| ✅ | Playlist admin (create, merge, AI, filter/sort/remove/delete) |
| ✅ | Now Playing: sleep timer, up next, history, favorite/ignore |
| ✅ | Pinned speakers in device picker |
| ✅ | Widget play/pause/next via `bockmedia://control` links |
| ✅ | Home screen quick actions (Now Playing, Search, Downloads) |
| ✅ | BGAppRefreshTask stub for offline refresh |
| ✅ | Cross-device Now Playing (mobile hides other phones; web shows all) |

**Phases 0–5 (v1) are complete.** Phase 6 (per-device accounts) is future work.

See [docs/IOS_BUILD_PLAN.md](../docs/IOS_BUILD_PLAN.md) for the full roadmap.

## Tests

```bash
cd ios && xcodegen generate
xcodebuild test -project BockMedia.xcodeproj -scheme BockMedia \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest'
```

## Personal deployment (free Apple ID)

- Install via Xcode with a Personal Team.
- Re-sign every **7 days** (or use AltStore/SideStore).
- Widget extension (future) must use the same team.

## QA

Device checklist: [QA.md](QA.md) (parity with `android/QA.md`).
