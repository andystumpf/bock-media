# Setup guide

Production setup for Bock Media on your own hardware. Uses placeholder hostnames — replace with yours.

## Requirements

- Python 3.10+
- ffmpeg (transcoding non-MP3/AAC formats)
- SQLite **`songs_cache`** index + XML config (from your library scanner or demo seed)
- Optional: Cloudflare tunnel for Alexa
- Optional: Amazon Developer account for the custom skill

## 1. Demo (no real library)

```bash
python3 scripts/seed_demo_library.py
export OURMEDIA_DATA_DIR="$PWD/fixtures/demo-data"
export OURMEDIA_DB_PATH="$PWD/fixtures/demo-data/songs_cache.db"
python3 server.py
```

## 2. Configuration

Copy [`config.example.json`](config.example.json) to `$OURMEDIA_DATA_DIR/config.json`.

| Key | Purpose |
|-----|---------|
| `publicUrl` | HTTPS hostname Alexa reaches (tunnel) |
| `mobileApi.token` | Bearer token for Android/iOS and external API |
| `alexaRemote` | Unofficial “Play on device” (see [`AMAZON_UK.md`](AMAZON_UK.md) for `.co.uk`) |
| `msp` / `mspOauth` | Music Skill Provider (optional) |

**Never commit** real `config.json`, passwords, or cookie files.

## 3. Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `OURMEDIA_DATA_DIR` | `~/.bockmedia` | XML prefs, playlists, caches |
| `OURMEDIA_DB_PATH` | (your index path) | SQLite `songs_cache` |
| `OURMEDIA_MUSIC_ROOT` | (library root) | Path prefix for media files |
| `PORT` | `3001` | HTTP port |

## 4. Alexa skill

1. Create a skill from [`skill/interaction_model.json`](skill/interaction_model.json)
2. Point endpoint to `https://YOUR_HOST/alexa`
3. Enable on your Echo(es) in development mode

## 5. Mobile apps

- Android: `android/README.md` — set server URL in `local.properties` or build config
- iOS: `ios/README.md`
- Serve builds at `/app` with `app-release-notes.json`

## 6. Security checklist

- [ ] Set `WebPassword` or disable open LAN API/media
- [ ] Generate strong `mobileApi.token`
- [ ] Do not expose `:3001` without auth on the public internet
- [ ] Use tunnel for Alexa; block direct-IP skill access (default)

## 7. Music video

See [`MUSIC_VIDEO.md`](MUSIC_VIDEO.md) for yt-dlp / Deno setup.
