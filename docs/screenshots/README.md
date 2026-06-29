# Screenshot gallery

All captures use **`fixtures/demo-data/`** — fictional artists, playlists, and room names.

Regenerate after UI changes:

```bash
python3 scripts/seed_demo_library.py
OURMEDIA_DATA_DIR=fixtures/demo-data OURMEDIA_DB_PATH=fixtures/demo-data/songs_cache.db python3 server.py
# Web: open http://127.0.0.1:3001 — capture each view
# Android: adb exec-out screencap -p > docs/screenshots/android/NN-name.png
# iOS: Simulator → File → Save Screen
```

| File | Description |
|------|-------------|
| `web/01-home.png` | Dashboard / home feed |
| `web/02-now-playing.png` | Now Playing (+ music video when available) |
| `web/03-family.png` | Family / profiles |
| `web/04-app-download.png` | `/app` download page |
| `android/01-home.png` | Android home tab |
| `ios/01-now-playing.png` | iOS Now Playing |
