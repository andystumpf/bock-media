# Bock Media Android — Manual QA Checklist (v2.0 native)

Run against a live server on LAN (or VPN). Mark each item pass/fail.

## Connectivity
- [ ] Setup: valid LAN URL connects; health loads
- [ ] Setup: invalid URL shows error
- [ ] Setup: public URL without mobile token shows 403 message
- [ ] Optional: mobile token + `allowTunnelApi` in config.json works on tunnel URL
- [ ] Admin password (RequirePassword) prompts work when configured

## Dashboard
- [ ] Summary counts load
- [ ] Favorites play via device picker
- [ ] Health card refreshes; Alexa login link opens Settings
- [ ] Plex sync card shows status

## Now Playing
- [ ] Live devices show track, art, progress
- [ ] Pause / next / sleep / favorite / never-again (when alexa session valid)
- [ ] Streaming history paginates

## Rooms
- [ ] Room list refreshes; quick play starts playlist on Echo

## Search / Library
- [ ] Search returns playlists, artists, albums, songs
- [ ] Artists → Albums → Songs drill-down
- [ ] Play on device from each entity type

## Playlists
- [ ] List, search, pagination
- [ ] Create, merge, smart playlist, AI preview/create
- [ ] Detail: filter, sort, remove track, delete playlist

## Devices & Automation
- [ ] Rename / delete device; merge candidates
- [ ] Device groups CRUD; identify-all; test clip
- [ ] Automation create, run now, edit, delete

## Settings
- [ ] Save default playlist / public URL
- [ ] Browser login via Custom Tab restores alexa remote
- [ ] Clear artwork cache

## Analytics & Routines
- [ ] Analytics tops and date filter
- [ ] Ignored tracks allow-again
- [ ] Routine phrase copy

## Widget & Shortcuts
- [ ] Widget shows track; pause/next
- [ ] Shortcuts open Now Playing, Playlists, Rooms, Search

## Build
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] `./gradlew assembleRelease` produces signed APK
