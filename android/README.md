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
   bockmedia.localServerUrl=http://127.0.0.1:3001
   bockmedia.externalServerUrl=http://10.0.2.2:3001
   bockmedia.mobileApiToken=your-token
   ```
3. Debug: `./gradlew assembleDebug`
4. Release: see [keystore setup](#release) below

## First launch

Enter **local** (LAN) and **external** (public IP) URLs. The app probes local first (~2s), then uses external when away from home.

**External URL** (`http://YOUR_PUBLIC_IP:3001`) requires `config.json`:

```json
"mobileApi": {
  "token": "your-long-random-token",
  "allowExternalAccess": true
}
```

Enter admin password + the same token in Setup.

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
