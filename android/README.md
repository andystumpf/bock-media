# Bock Media — Native Android App (v2.0)

Full **Jetpack Compose** client for the Bock Media Flask API — feature parity with the web console.

## Stack

- Kotlin, Jetpack Compose, Material 3, Navigation Compose
- Retrofit + kotlinx.serialization + OkHttp (Basic auth + optional mobile Bearer token)
- Coil (artwork), WorkManager (widget refresh), Vico (analytics charts planned)

## Build

1. Open `android/` in Android Studio Ladybug+
2. Optional `android/local.properties`:
   ```
   sdk.dir=/path/to/Android/sdk
   bockmedia.serverUrl=http://192.168.1.187:3001
   ```
3. Debug: `./gradlew assembleDebug`
4. Release: see [keystore setup](#release) below

## First launch

Enter your server URL (`http://192.168.x.x:3001` on LAN). Phone must reach the server (same Wi‑Fi or VPN).

**Public URL (`https://alexa.morejava.bid`):** `/api/*` is blocked on the tunnel by default. Options:

1. **VPN (recommended):** Tailscale/WireGuard → use LAN IP in app
2. **Port forward (`http://YOUR_PUBLIC_IP:3001`):** Requires auth — set in `config.json`:
   ```json
   "mobileApi": {
     "token": "your-long-random-token",
     "allowExternalAccess": true
   }
   ```
   Enter admin password + the same token in Setup. External access without token/password returns 401.
3. **Cloudflare tunnel token:** `"allowTunnelApi": true` with the same token (tunnel only)

## Screens (web parity)

| Route | Screen |
|-------|--------|
| Dashboard | Stats, health, favorites, recent |
| Now Playing | Live control, history, sleep timer |
| Rooms | Per-Echo snapshot, quick play |
| Search | Unified library search |
| Playlists | CRUD, merge, smart, AI |
| Artists / Albums / Songs | Browse + play |
| Watch Folders | Read-only |
| Alexa Devices | Rename, merge, groups, identify |
| Automation | Scheduled playback |
| Routines | Phrase generator |
| Analytics | Tops, ignored tracks |
| Settings | Prefs, Alexa browser login |

## Widget & shortcuts

- Home screen **Now Playing** widget (pause/next, 30s refresh)
- Launcher shortcuts → Now Playing, Playlists, Rooms, Search
- Deep links: `bockmedia://nowplaying` (via intent extra `route`)

## Release

```bash
bash scripts/generate_android_keystore.sh
cp android/keystore.properties.example android/keystore.properties
cd android && ./gradlew assembleRelease
```

APK: `app/build/outputs/apk/release/app-release.apk`

## Tests

```bash
cd android && ./gradlew testDebugUnitTest
```

Manual QA: [QA.md](QA.md)

## Project layout

```
app/src/main/kotlin/com/bockmedia/console/
  data/          API, repository, DataStore prefs
  domain/model/  PlayTarget, progress helpers
  ui/            Compose screens + navigation
  widget/        Now Playing home screen widget
```
