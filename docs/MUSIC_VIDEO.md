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
- **`youtube-cookies.txt`** in the NAS data dir (`~/.bockmedia/`) — required for reliable streaming; YouTube blocks datacenter IPs without logged-in cookies

## Cookie refresh (stale cookies)

**Symptom:** Now Playing shows *"Could not resolve a playable stream for the video"* — cookies on the NAS are expired or blocked.

**One-time fix** (Mac must be logged into YouTube in Chrome):

```bash
./scripts/youtube_cookies.sh
```

This exports cookies from Chrome, uploads to `user@your-server.local:~/.bockmedia/youtube-cookies.txt`, verifies with yt-dlp on the server, writes `youtube-cookies-verify.json`, and restarts `ourmedia`.

**Daily automation** (recommended):

```bash
./scripts/install_youtube_cookies_automation.sh
```

Installs a launchd agent (`com.bockmedia.youtube-cookies`) that runs **every day at 03:15**. Logs: `~/Library/Logs/bockmedia/youtube-cookies.{out,err}.log`.

Requires **Full Disk Access** for `/bin/bash` (System Settings → Privacy & Security → Full Disk Access) so the job can read Chrome cookies. Re-run the install script after updating `youtube_cookies.sh` in the repo.

Override env vars when running manually:

```bash
NAS=user@your-server.local YOUTUBE_COOKIES_BROWSER=chrome ./scripts/youtube_cookies.sh
```

**Health check:** `GET /api/health` includes a `musicVideo` object:

| Field | Meaning |
|-------|---------|
| `cookiesPresent` | Cookie file exists on server |
| `cookiesAgeHours` | Hours since last upload |
| `verifiedOk` | Last NAS verification passed (`youtube-cookies-verify.json`) |
| `verifiedAt` | ISO timestamp of last successful verify |
| `ytDlpInstalled` / `denoInstalled` | Extraction dependencies |

Never commit `youtube-cookies.txt` or verify JSON — they contain session material.

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
