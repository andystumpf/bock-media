# Music video (Now Playing)

Bock Media can show a **music video** behind Now Playing on web, Android, and iOS while audio plays from your library.

## How it works

1. Client asks **`GET /api/music-video?title=…&artist=…`**
2. Server resolves a YouTube video id (cached; optional manual overrides in `config.json` → `musicVideoOverrides`)
3. Client requests **`GET /api/music-video/{id}/play`** for stream metadata
4. Playback uses **`/api/music-video/{id}/proxy`** — server pulls the stream via **yt-dlp** (and Deno when installed) so clients avoid YouTube embed / bot blocks

Audio always comes from your **`/stream/`** library URL; video is visual only.

## Server requirements

- **yt-dlp** on `PATH`
- Optional: **Deno** for more reliable HLS extraction (`scripts/youtube_cookies.sh` can install on Linux hosts)
- Optional: **`youtube-cookies.txt`** in data dir for age-restricted content (never commit this file)

## Clients

| Platform | UI |
|----------|-----|
| Web | Now Playing panel toggles art ↔ video |
| Android | `MusicVideoPanel` + ExoPlayer HLS |
| iOS | Now Playing video layer |

## Demo / screenshots

Use the fictional demo library — video lookup still hits YouTube for real ids; for offline demos, expect thumbnail-only fallback if streaming is unavailable.

## Privacy

Video resolution sends **track title + artist** to YouTube search logic on the server. No audio leaves your LAN except the YouTube fetch initiated by your server.
