# iOS QA checklist

Mirror of `android/QA.md` — mark items as iOS implements each phase.

## Phase 0 — Foundation

- [x] Setup connects on LAN (`192.168.1.187:3001`)
- [x] Setup works on external URL with token + basic auth
- [x] Four bottom tabs render with dark theme
- [x] Green accent `#1DB954` on buttons and tab tint

## Phase 1 — Browse + remote play

- [x] Home loads sections (jump back in, favorites, playlists)
- [x] Home pull-to-refresh
- [x] Search returns playlists / artists / songs
- [x] Tap search result → device picker
- [x] Play playlist on Kitchen Echo (or other device)
- [x] Last-used speaker pre-selected in picker

## Phase 2 — Now Playing

- [x] Full Now Playing screen
- [x] Mini bar above tabs
- [x] Pause / skip / volume
- [x] Alexa re-login via ASWebAuthenticationSession (Settings → Re-login)

## Phase 3 — Offline

- [x] Download playlist on Wi‑Fi
- [x] Airplane mode playback

## Phase 4 — Admin

- [x] Settings, Analytics, Devices, Rooms, Routines
- [x] Automations list / run / delete / create / edit
- [x] Smart playlists (Manage playlists)
- [x] Analytics date presets + CSV export
- [x] Ignored tracks list (remove)
- [x] Deep links (`bockmedia://nowplaying`, etc.)
- [x] Genre detail from Search browse
- [x] Add to playlist from Search songs
- [x] Settings: watch folders, clear server cache
- [x] Home: Daily Mix, Radio, Discover, offline sections
- [x] Devices: rename, merge duplicates, speaker groups, identify, test clip
- [x] Playlists: create, merge, AI, filter/sort, remove track, delete playlist
- [x] Now Playing: sleep timer, up next, stream history, favorite/ignore
- [x] Settings: Plex sync status, server config editor
- [x] Pinned speakers in device picker

## Phase 5 — Widget & background

- [x] Home screen Now Playing widget (medium/large)
- [x] Widget tap opens app (`bockmedia://nowplaying`)
- [x] Widget play/pause/next (`bockmedia://control`)
- [x] Home screen quick actions
- [x] BGAppRefreshTask registered for offline refresh

## Cross-device Now Playing

- [x] iOS sees Alexa + this iPhone (not other mobile clients)
- [x] Web dashboard `#nowplaying` shows all devices with platform badges
