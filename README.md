<div align="center">

# Bock Media

**Stream your own music library on any Amazon Echo — by voice, with no paid streaming service.**

[![CI](https://github.com/andystumpf/bock-media/actions/workflows/ci.yml/badge.svg)](https://github.com/andystumpf/bock-media/actions/workflows/ci.yml)
&nbsp;·&nbsp; Python 3.10+ &nbsp;·&nbsp; Flask &nbsp;·&nbsp; vanilla-JS admin console &nbsp;·&nbsp; custom Alexa skill &nbsp;·&nbsp; Android / iOS apps

**[Live demo →](https://bock-media.onrender.com)** *(Free tier — may take a minute to wake up)*

</div>

Bock Media is a self-hosted music server plus a **custom Alexa skill** that lets
any Echo play music straight off your own NAS or disk — playlists, artists,
albums, tracks, genres, even audiobooks — using your voice. No Spotify, no
Amazon Music subscription, no cloud middle-man. It indexes a local SQLite music
library, streams audio over a Cloudflare tunnel, and gives you a slick web
console to manage everything. **Native Android and iOS apps** share the same API
for home playback, offline downloads, and Now Playing on your phone.

> **Heads up — this is a personal/hobby project.** It glues together a custom
> Alexa skill, an *unofficial* Alexa control API, and an optional Plex
> integration. It works great for a home setup but is **not** an official Amazon
> product and is not intended for commercial use. See [Caveats](#caveats).

---

## Contents

- [Screenshots](#screenshots)
- [Features](#features)
- [Try it in 2 minutes (demo data)](#try-it-in-2-minutes-demo-data)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Full setup](#full-setup)
- [Configuration reference](#configuration-reference)
- [Voice commands](#voice-commands)
- [Repository layout](#repository-layout)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Caveats](#caveats)
- [License](#license)
- [Support](#support)

---

## Screenshots

> All screenshots below were generated from the bundled **demo dataset**
> (`scripts/seed_demo_data.py`) — real, well-known artists and albums
> (Fleetwood Mac, Miles Davis, The Beach Boys, etc.) so album art resolves via
> the iTunes Search API. Listening history, Alexa devices, and household profiles
> are **synthetic** — no personal data.

### Web console

#### Home, Now Playing & Library

| Home feed | Now Playing | Your Library |
|:---:|:---:|:---:|
| ![Home feed](img/screenshots/dashboard.png) | ![Now Playing](img/screenshots/nowplaying.png) | ![Your Library](img/screenshots/library.png) |

#### Browse & search

| Playlists | Albums | Search |
|:---:|:---:|:---:|
| ![Playlists](img/screenshots/playlists.png) | ![Albums](img/screenshots/albums.png) | ![Search](img/screenshots/search.png) |

#### Artists & songs

| Artists | Songs |
|:---:|:---:|
| ![Artists](img/screenshots/artists.png) | ![Songs](img/screenshots/songs.png) |

#### Analytics, devices & family

| Analytics | Alexa Devices | Family |
|:---:|:---:|:---:|
| ![Analytics](img/screenshots/analytics.png) | ![Alexa Devices](img/screenshots/devices.png) | ![Family](img/screenshots/family.png) |

#### Automation

Schedule a playlist to start on a specific Echo at a set time. Automations use the
same unofficial remote-control path as **Play on device** on the Playlists page
(`start` / `mix` commands via `alexapy`). You need `alexaRemote` in `config.json`
and a one-time login via `scripts/alexa_login.py` (see
[step 6](#6-optional-play-on-device--automation) in Full setup).

| Setup required (no remote creds) | New automation form | Scheduled list |
|:---:|:---:|:---:|
| ![Automation — setup required](img/screenshots/automation-setup.png) | ![New automation form](img/screenshots/automation-new.png) | ![Scheduled automations](img/screenshots/automation-list.png) |

Full Automation page (form + list):

![Automation — overview](img/screenshots/automation.png)

#### Routines

Alexa won't let third-party apps create Routines directly — Bock Media generates
the exact *"ask bock media to …"* phrase to paste into the Alexa app.

| Routines helper |
|:---:|
| ![Routines](img/screenshots/routines.png) |

#### Watch Folders & Settings

| Watch Folders | Settings |
|:---:|:---:|
| ![Watch Folders](img/screenshots/watchfolders.png) | ![Settings](img/screenshots/settings.png) |

### iOS app

Native **SwiftUI** client — same REST API as web and Android. Captured from the
demo server with synthetic household data.

| Home | Search | Library |
|:---:|:---:|:---:|
| ![iOS Home](img/screenshots/ios/01-home.png) | ![iOS Search](img/screenshots/ios/02-search.png) | ![iOS Library](img/screenshots/ios/03-library.png) |

| Now Playing | Automations |
|:---:|:---:|
| ![iOS Now Playing](img/screenshots/ios/04-now-playing.png) | ![iOS Automations](img/screenshots/ios/05-automations.png) |

### Android app

Native **Jetpack Compose** client — feature parity with iOS and the web console.

| Home | Search | Library |
|:---:|:---:|:---:|
| ![Android Home](img/screenshots/android/01-home.png) | ![Android Search](img/screenshots/android/02-search.png) | ![Android Library](img/screenshots/android/03-library.png) |

| Now Playing | Automations |
|:---:|:---:|
| ![Android Now Playing](img/screenshots/android/04-now-playing.png) | ![Android Automations](img/screenshots/android/05-automations.png) |

---

## Features

### 🎙️ Voice control on any Echo
- A **custom Alexa skill** ("bock media") that plays playlists, artists, albums,
  tracks, and genres from your local library.
- Robust **fuzzy matching** — falls back playlist → artist → album → track when a
  spoken name isn't an exact match, and strips invocation bleed-through
  (`"ask bock media to..."`) that sometimes leaks into slot values.
- Full `AudioPlayer` transport: pause, resume, **next/previous**, shuffle, loop.
- **Sleep timer & stop-after-N**: *"…stop after this song"*, *"…stop after 3
  songs"*, *"…set a sleep timer for 20 minutes"*.
- **"Never play again"**: *"…never play this again"* permanently skips a track;
  manage the ignore list from the Analytics page.
- **Add to playlist by voice** with optional two-way write-back to Plex.

### 📊 Web admin console
A single-page app with a dark sidebar — Home, Search, Your Library, Now Playing,
Playlists, Albums, Artists, Songs, Watch Folders, Alexa Devices, Automation,
Routines, Analytics, Settings, Family — all backed by JSON endpoints. Includes:
- A **Service Health** card (backend, tunnel, Alexa session, Plex) on the dashboard.
- **Home feed** — mixes, genre radio, mood rows, jump back in.
- **Your Library** — sort and filter artists, albums, songs, genres, and sources.
- Rich **Analytics** built from an append-only `streaming_history.jsonl`.
- In-place **playlist renaming** that Alexa picks up immediately.

### 🎬 Music videos (Now Playing)
When a track is playing, clients resolve a matching **YouTube music video** and
stream it **through your server** (yt-dlp + optional Deno), so embeds are not
blocked in WebViews. Web, Android, and iOS Now Playing show cover art or video.
See [`docs/MUSIC_VIDEO.md`](docs/MUSIC_VIDEO.md).

### 📱 Native mobile apps (`android/`, `ios/`)
- **Jetpack Compose** (Android) and **SwiftUI** (iOS) clients with the same REST API.
- Local phone playback, **offline downloads**, driving mode, and Now Playing.
- **Who's listening?** profile picker and per-profile downloads/settings.
- Home screen **shortcuts** and **WidgetKit / App Widget** Now Playing widgets.
- Self-hosters can expose a sideload APK/IPA at **`/app`** (see [Mobile apps](#8-android-app-build--setup)).

### 👨‍👩‍👧‍👦 Family & kid-safe rooms
- Household **members** (parent / kid / guest) with optional parent PIN.
- Assign Echo rooms to a person; kid-safe rooms can **queue** tracks until a parent approves.
- Per-profile ratings, playlists, and client preferences sync via the server.

### 🔊 "Play on device" & multi-room
- Push a playlist to a **specific Echo** straight from the web UI (▶ on any
  playlist row), via the unofficial Alexa API (`alexapy`).
- **Schedule** playlists to start on a chosen device at a set time (Automation).
- **Device groups** + **group-aware Now Playing** that collapses a multi-room
  group into a single row.

### 🧭 Resilient device identity
- Echoes auto-register the first time they stream.
- **Serial-indexed auto-aliasing** folds a rotated `deviceId` back onto the same
  physical speaker so history/analytics stay attached.
- **Guided "Fix my devices"** flow plays a short clip on each unnamed Echo so you
  can label rooms (test/identify plays are excluded from analytics).

### 🎵 Audio streaming + artwork
- `/stream/<path>` streams `.mp3/.m4a/.aac` natively with HTTP Range support and
  transcodes `.flac/.wma/.wav/.ogg/.aif(f)` on the fly via `ffmpeg`.
- `/artwork/<path>` resolves art in four tiers — sidecar files → embedded
  ID3/MP4 tags ([mutagen](https://mutagen.readthedocs.io/)) → sibling album
  tracks → iTunes Search API — cached on disk.

### 🔁 Plex playlist sync (optional)
- `scripts/sync_plex_playlists.py` pulls audio playlists directly from a local
  Plex server into Bock Media (near real-time CRUD, incremental).
- Voice "add this to \<playlist\>" can **write back** to the Plex playlist.

### 🔐 Security
- The public tunnel only exposes `/alexa`, `/stream/`, `/artwork/`, `/music`, and
  `/oauth/`; everything else is hard-rejected for tunneled requests.
- Full Alexa **request-signature verification**: cert URL validation, SAN check,
  RSA-SHA1 signature, ±150 s timestamp window, and `applicationId` pinning.
- Optional HTTP Basic auth for the LAN console (`WebPassword` preference).
- **Mobile API Bearer token** gates external and tunneled API access (see
  [Configuration reference](#configuration-reference)).

### 🧪 Quality
- A `pytest` suite (API + Alexa intents + regressions) and a `jsdom` UI test
  suite, wired into **GitHub Actions** CI.
- A **health watchdog** (`scripts/health_check.py` + a systemd timer) that
  restarts the stack if the tunnel or backend goes unhealthy.

---

## Try it in 2 minutes (demo data)

You can explore the entire web console **without any music, Alexa hardware, or
Plex** — just seed the bundled demo dataset and run the server:

```bash
git clone https://github.com/andystumpf/bock-media.git
cd bock-media
pip install -r requirements.txt

# Generate a fictional library + playlists + devices + listening history
# (--write-audio needs ffmpeg; creates playable demo tracks for streaming tests)
python3 scripts/seed_demo_data.py --config --alexa-remote --write-audio

# Run the server against the demo data
OURMEDIA_DATA_DIR=$PWD/demo-data \
OURMEDIA_DB_PATH=$PWD/demo-data/music_organizer.db \
OURMEDIA_MUSIC_ROOT=$PWD/demo-data/music \
PORT=3001 python3 server.py
```

Open <http://localhost:3001/> and click through every page. The screenshots
above are exactly what you'll see. (The demo data lives in `demo-data/` and is
gitignored; delete the folder to remove it.)

Without `--write-audio`, library pages still work from the SQLite index but track
files are not on disk — add the flag when you want to test `/stream/` playback.

### Live demo on Render

**[https://bock-media.onrender.com](https://bock-media.onrender.com)** — a hosted
copy of the demo console. The Free tier sleeps when idle; give it a minute to
wake up on first visit.

The repo includes a [Render Blueprint](https://render.com/docs/blueprint-spec)
(`render.yaml`) that builds the fictional demo library on deploy and serves the
web console. Alexa skill / tunnel features are **not** available on Render — this
is for browsing the UI and API with test data only.

1. Sign in at [render.com](https://render.com) and choose **New → Blueprint**.
2. Connect the public [`andystumpf/bock-media`](https://github.com/andystumpf/bock-media) repo (Render reads `render.yaml`).
3. Apply the blueprint — service name **bock-media-demo**, Free plan is fine.
4. Wait for the build (`seed_demo_library.py` + `pip install`) and open the `.onrender.com` URL.

Render sits behind a CDN that sends Cloudflare-style headers; the app detects `RENDER`
and allows the web console (home installs still block tunneled access to `/` unless
you set `OURMEDIA_ALLOW_PUBLIC_CONSOLE=true`).

Health check: `GET /api/summary`. On first boot, `scripts/render_start.sh` re-seeds
if the DB is missing (ephemeral disk on Free tier is wiped on redeploy; the build
step seeds again each deploy).

---

## Architecture

```
                         Voice                    HTTPS
  "Alexa, ask ───▶  ┌─────────────┐         ┌──────────────────┐
   bock media …"    │  Amazon /   │ ──────▶ │ Cloudflare named │
                    │  Echo       │         │ tunnel (fixed    │
                    └──────┬──────┘         │ public hostname) │
                           │                └────────┬─────────┘
              AudioPlayer  │ stream + metadata       │ /alexa /stream /music
              directives   │                         ▼
                    ┌──────┴─────────────────────────────────────────────┐
                    │  Flask backend  (server.py, :3001)                  │
                    │  • Alexa skill handler + signature verify           │
                    │  • /stream  /artwork  (ffmpeg transcode)            │
                    │  • JSON REST API (web + Android + iOS)                │
                    │  • optional MSP /music + /oauth scaffold              │
                    └───┬──────────────┬──────────────┬────────────────────┘
                        │              │              │
               SQLite   │       XML +  │     unofficial│ Alexa API
            songs_cache │  ServerPlay- │      (alexapy)│  "play on device"
                        │  lists.xml   │              ▼
                    ┌───┴───┐     ┌────┴─────┐   ┌──────────────┐
                    │ music │     │ data dir │   │ your Echoes  │
                    │ files │     │ ~/.bock  │   │ (multi-room) │
                    └───┬───┘     │ media/   │   └──────────────┘
                        │         └──────────┘
               optional │ playlist sync / write-back
                    ┌───┴───┐
                    │ Plex  │
                    └───────┘

        ┌─────────────────────────────────────────────────────────────┐
        │  Mobile clients (Android Jetpack Compose / iOS SwiftUI)     │
        │  LAN or external URL + Bearer token → same /api/* endpoints │
        └─────────────────────────────────────────────────────────────┘
```

Bock Media stores no machine-specific paths in code — three env vars relocate
everything (see [Configuration reference](#configuration-reference)).

---

## Requirements

### Server

- **Python 3.10+** and **ffmpeg** on `PATH` (for non-MP3/AAC formats).
- A **`songs_cache` SQLite index** of your library (table schema below). If you
  already use a local media indexer that writes `ServerPlaylists.xml` /
  `WatchFolders.xml`, point Bock Media at its data dir. Otherwise the demo seed
  script shows the exact schema to populate.
- A **Cloudflare account + named tunnel** with a public hostname → `http://127.0.0.1:3001`.
- An **Amazon Developer account** to host the custom skill (development mode is fine).
- *(Optional)* a local **Plex** server for playlist sync, and Amazon account
  credentials for "Play on device".
- *(Optional)* **yt-dlp** (+ Deno) for Now Playing music videos — see
  [`docs/MUSIC_VIDEO.md`](docs/MUSIC_VIDEO.md).

### Mobile app development

| Platform | Requirements |
| --- | --- |
| **Android** | Android Studio Ladybug+, JDK 17, Android SDK 34+ |
| **iOS** | macOS, **Xcode 15+** (full Xcode), iOS 17+ device or simulator |

### `songs_cache` schema

```sql
CREATE TABLE songs_cache (
  id INTEGER PRIMARY KEY,
  title TEXT, artist TEXT, album_artist TEXT, album TEXT,
  genre TEXT, year INTEGER, duration_seconds INTEGER,
  bitrate INTEGER, track_number INTEGER, path TEXT
);
```

`path` must be a readable absolute path under `$OURMEDIA_MUSIC_ROOT`.

---

## Full setup

### 1. Install

```bash
git clone https://github.com/andystumpf/bock-media.git
cd bock-media
pip install -r requirements.txt
cp config.example.json config.json     # then edit (see Configuration)
```

For production, run behind **gunicorn** (see [systemd](#4-run-as-a-systemd-stack)).

### 2. Point at your library

Set three environment variables (the systemd unit declares them — see step 4):

| Variable | Example | Purpose |
| --- | --- | --- |
| `OURMEDIA_DB_PATH` | `/srv/music/music_organizer.db` | SQLite `songs_cache` index |
| `OURMEDIA_DATA_DIR` | `~/.bockmedia` | XML config (`ServerPlaylists.xml`, `WatchFolders.xml`, `Preferences.xml`) + image cache + `config.json` |
| `OURMEDIA_MUSIC_ROOT` | `/srv/music` | Music library root |

Run it:

```bash
PORT=3001 python3 server.py     # or ./start.sh
```

Open `http://your-server.local:3001/` on your LAN.

Copy `config.example.json` → `$OURMEDIA_DATA_DIR/config.json` and set
`publicUrl` to your tunnel hostname before deploying the Alexa skill.

### 3. Cloudflare named tunnel

Create a named tunnel and a permanent hostname, then `~/.cloudflared/config.yml`:

```yaml
tunnel: <your-tunnel-uuid>
credentials-file: /home/youruser/.cloudflared/<your-tunnel-uuid>.json
ingress:
  - hostname: your-domain.example.com
    service: http://127.0.0.1:3001
  - service: http_status:404
```

```bash
cloudflared tunnel route dns <tunnel-name> your-domain.example.com
```

> A named tunnel gives you a **fixed** public hostname (quick tunnels rotate and
> will break the skill). Latency to the tunnel must stay under ~2 s — Alexa times
> out at 8 s.

### 4. Run as a systemd stack

```bash
cp ourmedia.service.example ourmedia.service     # edit paths/user + OURMEDIA_SKILL_ID
sudo cp ourmedia.service       /etc/systemd/system/
sudo cp ourmedia-stack.target  /etc/systemd/system/
sudo cp ourmedia-health.service ourmedia-health.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ourmedia-stack.target ourmedia-health.timer
```

Create a `ourmedia-tunnel-named.service` that runs `cloudflared tunnel run
<tunnel-name>` and add it to `ourmedia-stack.target`. Example unit:

```ini
[Unit]
Description=Cloudflare named tunnel for Bock Media
After=network-online.target

[Service]
Type=simple
User=youruser
ExecStart=/usr/local/bin/cloudflared tunnel run <tunnel-name>
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

> For router port-forward (public IP:3001 → this host), gunicorn must bind
> `0.0.0.0:3001`. Binding `127.0.0.1` only works with a Cloudflare tunnel, not
> direct port-forward.

### 5. Deploy the Alexa skill

1. Create a custom skill in the [Alexa Developer Console](https://developer.amazon.com/alexa/console/ask),
   enable the **AudioPlayer** interface, and set the endpoint to
   `https://your-domain.example.com/alexa`.
2. Set `OURMEDIA_SKILL_ID` in `ourmedia.service` (and in
   `skill/manifest.development.json`). Without it, automations utterances reach
   Echo but the server rejects the skill callback (`applicationId mismatch`).
3. Upload the interaction model with [`ask-cli`](https://developer.amazon.com/en-US/docs/alexa/smapi/quick-start-alexa-skills-kit-command-line-interface.html):

```bash
ask smapi set-interaction-model \
  -s <your-skill-id> -g development -l en-US \
  --interaction-model "file:skill/interaction_model.json"

ask smapi get-skill-status --skill-id <your-skill-id> --resource interactionModel  # wait for SUCCEEDED
ask smapi set-skill-enablement --skill-id <your-skill-id>
```

Helper to keep the endpoint URI in sync after a hostname change:

```bash
python3 scripts/sync_alexa_public_url.py https://your-domain.example.com
```

Amazon's developer-mode testing lapses on its own; a cron keeps it alive:

```cron
0 */6 * * * ask smapi set-skill-enablement --skill-id <your-skill-id>
```

For **Amazon UK** (`amazon.co.uk`) accounts, see [`docs/AMAZON_UK.md`](docs/AMAZON_UK.md).

### 6. (Optional) "Play on device" + Automation

These use the *unofficial* Alexa API via `alexapy`:

```bash
pip install "alexapy==1.26.9" "aiohttp>=3.10,<3.11"   # the aiohttp pin is required
```

Add `alexaRemote` credentials to `config.json`, then log in once via the proxy
helper (handles modern Amazon login / passkey accounts):

```bash
python3 scripts/alexa_login.py --proxy --host <lan-ip> --port 3005
# open http://<lan-ip>:3005 and sign in
```

The ▶ button on playlist rows and the Automation page appear once this is configured.

Prefer environment variables over on-disk passwords when possible:
`ALEXA_REMOTE_EMAIL`, `ALEXA_REMOTE_PASSWORD`, `ALEXA_REMOTE_OTP_SECRET`,
`ALEXA_REMOTE_URL`.

### 7. (Optional) Plex playlist sync

Point Bock Media at a local Plex server and run the sync (a cron every few
minutes keeps it current):

```bash
python3 scripts/sync_plex_playlists.py          # incremental
python3 scripts/sync_plex_playlists.py --force   # full re-pull
```

Configure Plex connection details from the **Settings** page or `Preferences.xml`.

### 8. Android app build & setup

1. Open `android/` in **Android Studio Ladybug+**.
2. Optional `android/local.properties`:

   ```
   sdk.dir=/path/to/Android/sdk
   bockmedia.localServerUrl=http://your-server.local:3001
   bockmedia.externalServerUrl=http://YOUR_PUBLIC_IP:3001
   bockmedia.mobileApiToken=your-long-random-token
   ```

3. Build debug: `cd android && ./gradlew assembleDebug`
4. Install: `adb install app/build/outputs/apk/debug/app-debug.apk`

**First launch:** enter **local** (LAN) and **external** (public IP or tunnel) URLs.
The app probes local first (~2 s), then falls back to external when away from home.

**Release build:**

```bash
bash scripts/generate_android_keystore.sh
cp android/keystore.properties.example android/keystore.properties
cd android && ./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

See [`android/README.md`](android/README.md) and [`android/QA.md`](android/QA.md)
for screen parity and manual QA.

### 9. iOS app build & setup

**Requirements:** macOS, Xcode 15+, iOS 17+.

Point Xcode at the full app (once):

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

Generate the Xcode project:

```bash
cd ios
cp Config.xcconfig.example Config.xcconfig   # fill in Team ID + optional URLs
xcodegen generate
open BockMedia.xcodeproj
```

1. Select your **Personal Team** in Signing & Capabilities.
2. Choose a simulator or connect an iPhone via USB.
3. **Product → Run** (⌘R).

Optional pre-fill in `ios/Config.xcconfig`:

```
DEVELOPMENT_TEAM = YOUR_TEAM_ID_HERE
BOCK_LOCAL_SERVER_URL = http:/$()/your-server.local:3001
BOCK_EXTERNAL_SERVER_URL = http:/$()/YOUR_PUBLIC_IP:3001
BOCK_MOBILE_API_TOKEN =
BOCK_ADMIN_USER =
BOCK_ADMIN_PASSWORD =
```

`Config.xcconfig` is gitignored — never commit API tokens or admin passwords.

**Personal (free) Apple ID:** re-sign every **7 days** via Xcode, or use
AltStore/SideStore for longer sideloading.

Sync app icons after updating `public/img/icon-512.png`:

```bash
ios/scripts/sync_app_icon.sh
```

See [`ios/README.md`](ios/README.md) and [`ios/QA.md`](ios/QA.md).

### 10. Mobile API token in config

Mobile apps authenticate with a **Bearer token** separate from the web admin
password. Generate a long random string and add it to `config.json`:

```json
"mobileApi": {
  "token": "your-long-random-token-here",
  "allowExternalAccess": true,
  "allowTunnelApi": false,
  "allowOpenLanApi": false,
  "allowOpenLanMedia": false
}
```

| Flag | When to enable |
| --- | --- |
| `allowExternalAccess` | Phone reaches server via public IP / port-forward (`http://YOUR_PUBLIC_IP:3001`) |
| `allowTunnelApi` | Phone uses the Cloudflare tunnel hostname for API calls |
| `allowOpenLanApi` | Allow unauthenticated LAN API reads/writes (not recommended) |
| `allowOpenLanMedia` | Allow unauthenticated `/stream/` on LAN (not recommended) |

Enter the same token in the Android/iOS setup screen along with the admin
password. For **external** access, `allowExternalAccess` must be `true`.

Serve signed builds at **`GET /app`** — configure `appDownload` credentials in
`config.json` and keep [`app-release-notes.json`](app-release-notes.json) updated
when you ship new APK/IPA builds.

**Security checklist:**

- [ ] Set `WebPassword` or disable open LAN API/media
- [ ] Generate a strong `mobileApi.token`
- [ ] Do not expose `:3001` without auth on the public internet
- [ ] Use a tunnel for Alexa; block direct-IP skill access (default)

---

## Configuration reference

Copy `config.example.json` → `$OURMEDIA_DATA_DIR/config.json` and fill in:

| Key | Meaning |
| --- | --- |
| `publicUrl` | Your fixed tunnel hostname, e.g. `https://your-domain.example.com` |
| `launchPlaylistPrompt` | `true` → *"Alexa, open bock media"* asks for a playlist and stays in-session (handy when Spotify steals one-shots). `false` → immediately plays the `DefaultPlaylist` preference. |
| `identifyPlaylist` | Playlist used for the "Fix my devices" identify clips. |
| `timezone` | IANA timezone for automation schedules (e.g. `America/Chicago`) |
| `deviceDiscovery` | Background sweep that binds Alexa skill deviceIds to hardware serials |
| `msp` / `mspOauth` | Optional Music Skill Provider ids + OAuth client (experimental) |
| `alexaRemote` | Optional Amazon creds for "Play on device" / Automation |
| `alexaAplLyrics` | Echo Show synced lyrics via APL — see [`docs/ECHO_SHOW_APL_LYRICS.md`](docs/ECHO_SHOW_APL_LYRICS.md) |
| `mobileApi` | Bearer token + external/tunnel/LAN access flags for mobile apps |
| `appDownload` | Basic auth for `GET /app` (APK/IPA downloads) |
| `appAbout.githubPublic` | Public repo URL shown in About screens |
| `claude` / `openai` / `mixMuse` | Optional LLM keys for Mix Muse conversational playlists |

Library preferences (Default Playlist, ffmpeg path, web password, logging, …)
live in `Preferences.xml` and are editable from the **Settings** page.

### Environment variables

| Variable | Default | Description |
| --- | --- | --- |
| `OURMEDIA_DATA_DIR` | `~/.bockmedia` | XML prefs, playlists, caches, `config.json` |
| `OURMEDIA_DB_PATH` | (your index path) | SQLite `songs_cache` |
| `OURMEDIA_MUSIC_ROOT` | (library root) | Path prefix for media files |
| `OURMEDIA_SKILL_ID` | — | Custom Alexa skill id (required for automations) |
| `OURMEDIA_ALLOW_PUBLIC_CONSOLE` | `false` | Allow web console over tunnel/Render |
| `PORT` | `3001` | HTTP port |

**Secrets never get committed** — `config.json`, `skill/account-linking.json`,
`devices.json`, runtime state, and the generated MSP catalog are all in
`.gitignore`. Templates are provided as `*.example.json`.

---

## Voice commands

Invocation name: **"bock media"**. Collision-safe verbs are **`start`** and
**`mix`** (Amazon's music domain tends to hijack `play`/`shuffle` + a music-like
name).

| Intent | Example utterance |
| --- | --- |
| `PlayPlaylistIntent` | *"Alexa, ask bock media to start the road trip playlist"* |
| `ShufflePlaylistIntent` | *"Alexa, ask bock media to mix the road trip playlist"* |
| `PlayArtistIntent` / `ShuffleArtistIntent` | *"Alexa, ask bock media to play Fleetwood Mac"* |
| `PlayAlbumIntent` / `ShuffleAlbumIntent` | *"Alexa, ask bock media to play the album Rumours"* |
| `PlayTrackIntent` / `PlayTrackByArtistIntent` | *"Alexa, ask bock media to play Dreams by Fleetwood Mac"* |
| `PlayGenreIntent` / `ShuffleGenreIntent` | *"Alexa, ask bock media to play some jazz"* |
| `AddToPlaylistIntent` | *"Alexa, ask bock media to add this to my road trip playlist"* |
| `SleepTimerIntent` / `StopAfterIntent` | *"…set a sleep timer for 20 minutes"* / *"…stop after 3 songs"* |
| `IgnoreSongIntent` | *"Alexa, ask bock media to never play this again"* |
| `WhatsPlayingIntent` / `PlayCurrentIntent` | *"Alexa, what's playing?"* / *"…play this"* |
| `AMAZON.{Pause,Resume,Next,Previous,Stop,Shuffle*,Loop*}` | Standard transport controls |

For true hands-free, no-"ask" playback, use the **Routines builder** page to
generate a phrase and paste it into an Alexa Routine.

---

## Repository layout

```
.
├── server.py                     # Flask app — all routes, Alexa handler, helpers
├── alexa_remote.py               # Unofficial Alexa API wrapper (alexapy)
├── plex_client.py                # Plex API client (playlist write-back, status)
├── start.sh                      # Trivial launcher
├── requirements.txt
├── render.yaml                   # Render Blueprint (hosted demo)
├── app-release-notes.json        # Changelog for GET /app mobile downloads
├── config.example.json           # → copy to config.json (gitignored)
├── ourmedia.service.example      # → systemd unit for the backend
├── ourmedia-stack.target         # Aggregate target (backend + tunnel + health)
├── ourmedia-health.service/.timer# Health watchdog
├── public/                       # Static admin console (vanilla JS + Chart.js)
│   ├── index.html
│   ├── css/  js/app.js  img/
├── skill/
│   ├── interaction_model.json    # ASK interaction model
│   ├── manifest.development.json # custom-skill manifest
│   ├── music-manifest.json       # MSP (music skill) manifest
│   ├── account-linking.example.json
│   └── catalog_playlists.example.json
├── scripts/
│   ├── seed_demo_data.py         # generate the demo library (for screenshots/trying it)
│   ├── seed_demo_library.py      # lighter committed fixture seed (Render deploy)
│   ├── capture_readme_screenshots.mjs      # regenerate img/screenshots/*.png (web)
│   ├── capture_automation_screenshots.mjs  # regenerate Automation README images
│   ├── sync_plex_playlists.py    # Plex → Bock Media playlist sync
│   ├── sync_alexa_public_url.py  # resync skill endpoint via SMAPI
│   ├── alexa_login.py            # one-time alexapy login helper
│   ├── health_check.py           # stack watchdog
│   └── … (catalog build/upload, audits, deploy helpers)
├── tests/                        # pytest (API/Alexa/regressions) + tests/ui (jsdom)
├── android/                      # Jetpack Compose Android app
├── ios/                          # SwiftUI iOS app
├── img/
│   ├── screenshots/              # README screenshots (web)
│   │   ├── ios/                  # iOS app captures (01-home.png …)
│   │   └── android/              # Android app captures (01-home.png …)
│   └── venmo.png
├── fixtures/demo-data/           # committed fictional demo library (Render)
├── docs/
│   ├── SETUP.md                  # condensed self-host notes
│   ├── MUSIC_VIDEO.md            # Now Playing music video pipeline
│   ├── AMAZON_UK.md              # Amazon UK (amazon.co.uk) config
│   ├── screenshots/README.md     # how to regenerate README captures
│   └── dev/                      # internal bug hunts & QA (private repo only)
├── .github/workflows/ci.yml
├── package.json                  # Playwright (README screenshot scripts only)
└── requirements.txt
```

Regenerate web screenshots after UI changes:

```bash
npm install && npx playwright install chromium
node scripts/capture_readme_screenshots.mjs
```

Capture mobile screenshots from a running demo server — see
[`docs/screenshots/README.md`](docs/screenshots/README.md).

---

## Testing

### Server & web UI

```bash
pip install pytest
pytest tests/

# UI tests
cd tests/ui && npm install && npm test
```

`tests/conftest.py` redirects every writable path into a per-test `tmp_path`,
seeds isolated XML/state, and provides a Flask test client plus an
`alexa_request()` envelope builder. GitHub Actions runs both suites on push/PR.

### Android

```bash
cd android && ./gradlew testDebugUnitTest
# Instrumented smoke/journey tests (device or emulator):
cd android && ./gradlew connectedDebugAndroidTest
```

### iOS

```bash
cd ios && xcodegen generate
xcodebuild test -project BockMedia.xcodeproj -scheme BockMedia \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

For features that need real hardware (multi-room, device identity, sleep timer
boundaries), follow [`docs/dev/DEVICE_TEST_PLAN.md`](docs/dev/DEVICE_TEST_PLAN.md).

---

## Troubleshooting

### Quick health checks

```bash
sudo systemctl is-active ourmedia ourmedia-tunnel-named   # stack up?
grep "POST /alexa\|ALEXA" server.log | tail -20            # did Alexa reach us?
curl -o /dev/null -s -w "total=%{time_total}s\n" \
  https://your-domain.example.com/alexa -X POST -H "Content-Type: application/json" -d '{}'
curl -s http://your-server.local:3001/api/summary | python3 -m json.tool
```

### Alexa skill not responding

If the skill suddenly routes to Spotify or returns *"the requested skill did not
provide a valid response"*, check in order:

1. **Tunnel down** → `systemctl restart ourmedia-tunnel-named`
2. **Backend down** → `systemctl restart ourmedia`
3. **Developer-mode enablement lapsed** → re-run `ask smapi set-skill-enablement`
4. **Music-domain collision** → say **mix**/**randomize** instead of
   **shuffle**, or set Amazon Music as the default music service in the Alexa app.
5. **`applicationId mismatch`** → verify `OURMEDIA_SKILL_ID` matches the skill
   in the Alexa Developer Console.

### Mobile app can't connect

| Symptom | Fix |
| --- | --- |
| Works on Wi‑Fi, fails on cellular | Set `mobileApi.allowExternalAccess: true` and use the external URL |
| 401 on every API call | Token in app must match `mobileApi.token` in `config.json` |
| LAN works, tunnel fails | Set `mobileApi.allowTunnelApi: true` if using the tunnel hostname |
| Stream fails on phone | Check `OURMEDIA_MUSIC_ROOT` paths; verify `/stream/` works from curl |

### Play on device / Automation

- Re-run `python3 scripts/alexa_login.py --proxy` when cookies expire.
- Confirm `alexapy` and the pinned `aiohttp` version are installed.
- UK accounts: set `alexaRemote.url` to `amazon.co.uk` — see [`docs/AMAZON_UK.md`](docs/AMAZON_UK.md).

### Music video won't play

Now Playing video needs **yt-dlp** on the server and (usually) fresh YouTube
cookies. See [`docs/MUSIC_VIDEO.md`](docs/MUSIC_VIDEO.md).

The full operations notes are in [`docs/SETUP.md`](docs/SETUP.md).

---

## Caveats

- **Unofficial Alexa API.** "Play on device" and Automation rely on `alexapy`
  (the same library Home Assistant uses). Amazon can change it without warning;
  cookies expire and need re-login.
- **One-shot music routing.** Amazon's music domain often claims
  *"Alexa, play \<name\>"* before a custom skill sees it. Use the "ask bock media
  to start/mix …" phrasing, a Routine, or set Amazon Music as default. This is a
  platform limitation, not a bug.
- **Personal use.** Built for a private home library. It is not certified for
  public Alexa skill distribution and ships no license to redistribute content.
- **Music videos** pull from YouTube via yt-dlp — subject to YouTube's terms and
  occasional bot-detection blocks without logged-in cookies.

---

## License

See [LICENSE](LICENSE). **Bock Media** is a trademark of Andy Stumpf. Personal,
non-commercial use only unless you obtain written permission.

---

## Support

Tips help keep Bock Media maintained. PayPal hosted button and Venmo QR on **Support** /
**Settings** in the web console; Venmo also below:

<p align="center">
  <img src="img/venmo.png" alt="Venmo QR code — scan to tip" width="200">
</p>
