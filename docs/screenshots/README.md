# Screenshot gallery

All README captures use **`scripts/seed_demo_data.py`** — real well-known artists
(Fleetwood Mac, Miles Davis, Pearl Jam, etc.) with synthetic household names,
devices, and listening history. No personal library data.

## Regenerate web console shots

```bash
pip install -r requirements.txt
python3 scripts/seed_demo_data.py --config --alexa-remote --write-audio

# Demo server (default port 3033 in capture script)
OURMEDIA_DATA_DIR=demo-data \
OURMEDIA_DB_PATH=demo-data/music_organizer.db \
OURMEDIA_MUSIC_ROOT=/Users/Shared/bock-media/music \
PORT=3033 python3 server.py

# In another terminal (needs Node + Playwright)
node scripts/capture_readme_screenshots.mjs --port 3033
node scripts/capture_automation_screenshots.mjs --port 3033
```

Output: `img/screenshots/*.png` (web), referenced from the root `README.md`.

## Regenerate mobile shots

```bash
# Demo server must already be listening (see above)
./scripts/capture_mobile_readme_screenshots.sh
```

Output:

| Path | Description |
|------|-------------|
| `img/screenshots/ios/01-home.png` … `05-automations.png` | iOS Simulator (launch args — no deep-link dialog) |
| `img/screenshots/android/01-home.png` … `05-automations.png` | Android device/emulator (`adb reverse` for USB phone) |

## Legacy paths under `docs/screenshots/`

Older captures live under `docs/screenshots/web/` and `docs/screenshots/android/`.
The public README uses **`img/screenshots/`** only — regenerate there before publishing.
