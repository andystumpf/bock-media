# Bock Media — agent guidance

Cursor rules also live in `.cursor/rules/` (local, gitignored). This file is the tracked copy.

## Cross-platform parity

When changing home feed, playback, or shared API behavior, update **server + web + Android + iOS** unless scoped to one platform.

| Surface | Primary files |
|---------|---------------|
| Server | `server.py`, `shared/api-contract/api-contract.yaml` |
| Web | `public/js/homeFeed.js`, `public/js/app.js` |
| Android | `android/.../HomeFeedComposer.kt`, `PlayLauncher.kt`, `NowPlayingScreen.kt` |
| iOS | `ios/BockMedia/Domain/HomeFeedComposer.swift`, `Features/Play/`, `Features/NowPlaying/` |

Home feed: mirror `HomeFeedRules`, claim art paths in `registerCard`, pass `genreHint` on genre mixes. Run `HomeFeedComposerGoldenTest`, `HomeFeedComposerGoldenTests`, `tests/ui/test_homeFeed.mjs`.

## Deploy & smoke

- Host: `plex@192.168.1.187`, unit `ourmedia`, data `~/.bockmedia`
- Web + server: `scp server.py …` then `./scripts/deploy_web.sh`
- Bump `?v=` on changed JS/CSS; match entries in `public/sw.js`
- Android debug: `./gradlew assembleDebug` + `adb install -r …`
- Release: `./scripts/deploy_mobile_app.sh` (versions must match in gradle + release notes)
- Verify: `/api/health`, dashboard Service Health, hard refresh web, reopen mobile app

## Suggested Cursor Automations

1. **Parity agent** — on PR touching home feed / play launcher / `server.py` API routes → run golden tests, diff three clients
2. **Deploy smoke** — manual trigger → deploy web+server, curl health, optional adb smoke script
3. **NAS health** — daily → SSH, check `ourmedia`, watchdog age, Plex sync log
