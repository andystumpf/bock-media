# ourMedia

A self-hosted local music server and **custom Alexa skill** that lets any Echo
device stream music straight off your NAS — playlists, artists, albums, tracks,
genres, audiobooks — using your own voice commands instead of a paid streaming
service.

It reads a local media indexer's on-disk data (preferences, watch folders,
playlists, image cache, and a `songs_cache` SQLite index) and replaces any paid
cloud bridge with:

- a **Flask backend** (`server.py`) that talks SQLite + XML on disk,
- a **vanilla-JS admin console** for the LAN, and
- a **custom Alexa skill** exposed over a fixed Cloudflare named tunnel.

---

## Features

### Web admin console (LAN-only)

A single-page app served from `/` with a dark sidebar and 10 pages, all backed
by JSON endpoints:

- **Dashboard** — counts of songs, artists, albums, watch folders, playlists.
- **Now Playing** — live track + per-device state pulled from Alexa
  `AudioPlayer` events; artwork resolved from sidecar files, embedded ID3/MP4
  tags, sibling tracks on the same album, or the iTunes Search API.
- **Playlists** — browse `.m3u` playlists referenced from
  `~/.bockmedia/ServerPlaylists.xml`; rename in place.
- **Albums / Artists / Songs** — paginated browsers backed by the
  `songs_cache` SQLite table.
- **Watch Folders** — read the `WatchFolders.xml` configuration.
- **Alexa Devices** — list every Echo that has hit the skill, rename them,
  merge duplicates left over from device-id rotation, and dismiss false-positive
  merge candidates.
- **Analytics** — per-device / per-artist / per-day streaming history pulled
  from `streaming_history.jsonl` with Chart.js visualisations.
- **Settings** — edit a subset of `Preferences.xml` keys (default playlist,
  ffmpeg path, web password, verbose logging, ...) plus `config.json` toggles
  (public URL, launch playlist prompt).

### Alexa custom skill

Endpoint: `https://alexa.morejava.bid/alexa` (fixed Cloudflare named tunnel).
Invocation name: **"bock media"**.

Intents defined in [`skill/interaction_model.json`](skill/interaction_model.json):

| Intent | Example utterance |
| --- | --- |
| `PlayPlaylistIntent` | *"Alexa, ask bock media to start the yacht rock playlist"* |
| `ShufflePlaylistIntent` | *"Alexa, ask bock media to mix the yacht rock playlist"* |
| `PlayArtistIntent` / `ShuffleArtistIntent` | *"Alexa, ask bock media to play Steely Dan"* |
| `PlayAlbumIntent` / `ShuffleAlbumIntent` | *"Alexa, ask bock media to play the album Aja"* |
| `PlayTrackIntent` / `PlayTrackByArtistIntent` | *"Alexa, ask bock media to play Peg by Steely Dan"* |
| `PlayGenreIntent` / `ShuffleGenreIntent` | *"Alexa, ask bock media to play some jazz"* |
| `ReadBookIntent` | *"Alexa, ask bock media to read \<book title\>"* |
| `PlayCurrentIntent` | *"Alexa, ask bock media to play this"* (uses the track selected in the web UI) |
| `WhatsPlayingIntent` | *"Alexa, what's playing?"* |
| `AddToPlaylistIntent` | *"Alexa, ask bock media to add this to my road trip playlist"* |
| `IgnoreSongIntent` | *"Alexa, ask bock media to never play this again"* |
| `ListServersIntent` / `CurrentServerIntent` / `SwitchServersIntent` | *"Alexa, ask bock media what servers do I have"* |
| `ListInvitationsIntent` | *"Alexa, ask bock media do I have any invitations"* |
| `AMAZON.{Pause,Resume,Next,Previous,Stop,Cancel,Help,ShuffleOn,ShuffleOff,LoopOn,LoopOff}` | Standard audio-player controls |

Fuzzy matching falls back through playlist → artist → album → track when a
slot value doesn't match exactly, and `normalize_spoken_value()` strips
invocation bleed-through (`"ask bock media to ..."`) that occasionally shows up
in slot values.

### Audio streaming + artwork

- `/stream/<path>` streams `.mp3 / .m4a / .aac` natively with HTTP Range
  support, and transcodes `.flac / .wma / .wav / .ogg / .aif(f)` on the fly via
  `ffmpeg -f mp3 -`.
- `/artwork/<path>` resolves art in four tiers — sidecar files
  (`cover.jpg`, `folder.png`, ...), embedded ID3/MP4 tags via
  [mutagen](https://mutagen.readthedocs.io/), sibling tracks on the same album,
  then the iTunes Search API — caching results under `artwork_cache/`.

### Security

- The Flask app is exposed publicly only via the Cloudflare tunnel; the
  `check_auth` hook hard-rejects any tunneled request that isn't under
  `/alexa`, `/stream/`, `/artwork/`, `/music`, or `/oauth/`.
- All Alexa requests get full **request-signature verification**: cert URL
  validation, SAN check for `echo-api.amazon.com`, RSA-SHA1 signature
  validation, ±150 s timestamp window, and `applicationId` pinning to the
  skill ID.
- LAN requests can be optionally protected with HTTP Basic auth driven by
  the `WebPassword` preference.

### Music Skill API (experimental)

`/oauth/authorize`, `/oauth/token`, and `/music` implement the OAuth + MSP
directive scaffold required to register ourMedia as an Amazon Music Skill
provider. The directive handler currently returns `INVALID_DIRECTIVE` for
everything — the OAuth dance works end-to-end, but actual Now-Playing /
PlaybackController support is not implemented yet.

---

## Repository layout

```
.
├── server.py                # Flask app — ~3000 LOC, all routes and helpers
├── start.sh                 # Trivial launcher invoked by systemd
├── config.json              # Public URL + MSP OAuth client config
├── ourmedia.service         # systemd unit for the Flask backend
├── ourmedia-stack.target    # Aggregate target pulling in Flask + tunnel
├── public/                  # Static admin console (vanilla JS + Chart.js)
│   ├── index.html
│   ├── css/style.css
│   ├── js/app.js            # ~1500 LOC SPA
│   └── img/                 # default-art.png placeholder + favicon-flat.svg
├── skill/
│   ├── interaction_model.json    # ASK interaction model
│   └── manifest.development.json # ASK skill manifest
├── scripts/
│   ├── playlist_audit.py         # Audit ServerPlaylists.xml for broken paths
│   └── sync_alexa_public_url.py  # Force-resync skill endpoint via SMAPI
├── tests/                   # pytest suite + Playwright UI tests
│   ├── conftest.py          # Isolated tmp-path fixtures + Alexa envelope builder
│   ├── test_api.py
│   ├── test_alexa.py
│   ├── test_helpers.py
│   └── ui/
└── .cursor/rules/           # Project-specific agent rules
```

Runtime state (excluded via `.gitignore`):

- `nowplaying_state.json`, `selected_state.json`, `ignored_tracks.json`
- `queues.json` — per-device playback queues with shuffle/loop state
- `devices.json` — Echo device registry + merge history
- `streaming_history.jsonl` — append-only play log feeding Analytics
- `artwork_cache/` — sidecar/embedded/iTunes art

External data on disk (configurable via env vars — see below):

- `$OURMEDIA_DB_PATH` (default `/mnt/bock/Music/music_organizer.db`) — SQLite `songs_cache`
- `$OURMEDIA_DATA_DIR` (default `~/.bockmedia`) — XML config and image cache
- `$OURMEDIA_MUSIC_ROOT` (default `/mnt/bock/Music`) — music library root

The code hardcodes no machine-specific paths; override the three `OURMEDIA_*`
environment variables (declared in `ourmedia.service`) to relocate.

---

## Requirements

- **Python 3.10+**
- **ffmpeg** on `PATH` (or set `FFmpegLocation` in preferences) for
  non-native formats.
- A **media indexer** populating the `songs_cache` SQLite DB and the XML config
  in `$OURMEDIA_DATA_DIR` (e.g. a local library scanner writing `ServerPlaylists.xml`).
- **Cloudflare account + named tunnel** with a public hostname pointing to
  `http://127.0.0.1:3001`.
- **Amazon Developer account** to host the custom skill in development mode.

### Python packages

```bash
pip install flask cryptography mutagen
```

(`sqlite3`, `xml.etree`, `subprocess`, `urllib`, `logging`, `re` are stdlib.)

---

## Setup

### 1. Run the backend

```bash
./start.sh
# or directly
python3 server.py        # listens on PORT (default 3001)
```

Open `http://<host>:3001/` from your LAN.

### 2. Install as a systemd stack

```bash
sudo cp ourmedia.service /etc/systemd/system/
sudo cp ourmedia-stack.target /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ourmedia-stack.target
sudo systemctl status ourmedia
```

A separate `ourmedia-tunnel-named.service` runs `cloudflared tunnel run
ourmedia` against `~/.cloudflared/config.yml` (tunnel name `ourmedia`,
hostname `alexa.morejava.bid`). It is machine-specific and not tracked here.

### 3. Cloudflare named tunnel

`~/.cloudflared/config.yml`:

```yaml
tunnel: <tunnel-uuid>
credentials-file: /home/<user>/.cloudflared/<tunnel-uuid>.json
ingress:
  - hostname: <your-public-hostname>
    service: http://127.0.0.1:3001
  - service: http_status:404
```

Then run `cloudflared tunnel route dns <tunnel-name> <your-public-hostname>`
to point the DNS at the tunnel.

### 4. Deploy the Alexa skill

Using [`ask-cli`](https://developer.amazon.com/en-US/docs/alexa/smapi/quick-start-alexa-skills-kit-command-line-interface.html)
v2:

```bash
ask smapi set-interaction-model \
  -s <your-skill-id> -g development -l en-US \
  --interaction-model "file:skill/interaction_model.json"

ask smapi set-skill-enablement --skill-id <your-skill-id>
```

Update `EXPECTED_SKILL_APP_ID` in `server.py` and the endpoint URI in
`skill/manifest.development.json` to match your skill and tunnel hostname.
A helper script is provided to keep the endpoint URI in sync:

```bash
python3 scripts/sync_alexa_public_url.py https://<your-public-hostname>
```

The model build is asynchronous — wait for `SUCCEEDED`:

```bash
ask smapi get-skill-status --skill-id <your-skill-id> --resource interactionModel
```

A cron entry keeps developer-mode testing alive (Amazon's enablement lapses on
its own):

```cron
0 */6 * * * ask smapi set-skill-enablement --skill-id <your-skill-id>
```

---

## Configuration

`config.json`:

```json
{
  "publicUrl": "https://<your-public-hostname>",
  "launchPlaylistPrompt": false,
  "mspOauth": {
    "clientId": "...",
    "clientSecret": "...",
    "accessToken": "...",
    "refreshToken": "...",
    "redirectUriPrefixes": ["https://alexa.amazon.com/", "https://layla.amazon.com/", "https://pitangui.amazon.com/"]
  }
}
```

- `launchPlaylistPrompt` — when `true`, `"Alexa, open bock media"` asks for a
  playlist name and stays in the skill session. Useful workaround when
  Spotify keeps stealing one-shot commands; reply with **only the name** or
  `"mix <name>"`.
- When `false`, a `LaunchRequest` immediately plays the playlist named in
  the `DefaultPlaylist` preference.

Other relevant prefs surfaced under **Settings** in the UI:
`DefaultPlaylist`, `FFmpegLocation`, `RequirePassword`, `WebPassword`,
`VerboseLogging`.

---

## Diagnostics

```bash
# Is the stack up?
sudo systemctl is-active ourmedia ourmedia-tunnel-named

# Did Alexa actually reach our server?
grep "POST /alexa\|ALEXA" server.log | tail -20

# End-to-end tunnel latency (must be < 2s — Alexa times out at 8s).
curl -o /dev/null -s -w "total=%{time_total}s\n" \
  https://<your-public-hostname>/alexa \
  -X POST -H "Content-Type: application/json" -d '{}'

# Tail logs live
tail -f server.log
tail -f tunnel.log
```

If the skill suddenly starts routing to Spotify or returning "the requested
skill did not provide a valid response", check in this order:

1. Tunnel down → `systemctl restart ourmedia-tunnel-named`
2. Backend down → `systemctl restart ourmedia`
3. Developer-mode enablement lapsed → re-run `ask smapi set-skill-enablement`
4. Spotify default-music collision — say **mix** or **randomize** instead of
   **shuffle**, or set Amazon Music as the default music service in the Alexa
   app.

More gotchas are documented in
[`.cursor/rules/alexa-skill-troubleshooting.mdc`](.cursor/rules/alexa-skill-troubleshooting.mdc).

---

## Testing

```bash
pip install pytest
pytest tests/
```

`tests/conftest.py` redirects every writable path in `server.py` into a
per-test `tmp_path`, seeds it with snapshots of `$OURMEDIA_DATA_DIR` XML, and
gives each test a Flask test client plus an `alexa_request()` envelope
builder. Real DB-backed fixtures (`sample_artist`, `sample_album`,
`sample_track`, `sample_playlist`) read the live `songs_cache` so the Alexa
intent tests exercise actual fuzzy-matching paths.

UI tests live under `tests/ui/` (Playwright; install separately with
`npm install` in that directory).

---

## License

No license declared. All rights reserved by the author until one is added.
