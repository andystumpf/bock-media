# Screenshot gallery

All README captures use **`scripts/seed_demo_data.py`** — real well-known artists
(Fleetwood Mac, Miles Davis, Pearl Jam, etc.) with synthetic household names,
devices, and listening history. No personal library data.

## Regeneration checklist (before publishing)

1. **Seed demo data**
   ```bash
   python3 scripts/seed_demo_data.py --config --alexa-remote --write-audio
   ```

2. **Start demo server** (port 3033)
   ```bash
   OURMEDIA_DATA_DIR=demo-data \
   OURMEDIA_DB_PATH=demo-data/music_organizer.db \
   OURMEDIA_MUSIC_ROOT=/Users/Shared/bock-media/music \
   PORT=3033 \
   BOCK_MOBILE_API_TOKEN=demo \
   python3 server.py
   ```

3. **Web console** (Node + Playwright)
   ```bash
   npm install && npx playwright install chromium
   node scripts/capture_readme_screenshots.mjs --port 3033
   node scripts/capture_automation_screenshots.mjs --port 3033
   ```

4. **Mobile apps** (Xcode simulator + adb device/emulator)
   ```bash
   ./scripts/capture_mobile_readme_screenshots.sh
   ```

5. **Sensitive-string scan** (text files + spot-check PNGs)
   ```bash
   rg -l '192\.168\.1\.187|admin|ourMedia' img/ docs/ README.md || echo "OK"
   ```

6. **Publish** — see [`docs/dev/PUBLISHING.md`](../dev/PUBLISHING.md) (private repo only).

## Output paths

| Path | Description |
|------|-------------|
| `img/screenshots/*.png` | Web console (linked from root `README.md`) |
| `img/screenshots/ios/01-home.png` … `05-automations.png` | iOS Simulator |
| `img/screenshots/android/01-home.png` … `05-automations.png` | Android device/emulator |

Legacy dev captures under `docs/screenshots/` were removed — use **`img/screenshots/`** only.
