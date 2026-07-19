# Contributing

Thanks for your interest in Bock Media. This repo is the **public, sanitized** copy of the
project — safe to fork and self-host. Pull requests are welcome.

## Try it locally

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# Demo library (real artists, synthetic household — no personal data)
python3 scripts/seed_demo_data.py --config --alexa-remote --write-audio

OURMEDIA_DATA_DIR=demo-data \
OURMEDIA_DB_PATH=demo-data/music_organizer.db \
OURMEDIA_MUSIC_ROOT=/Users/Shared/bock-media/music \
PORT=3033 \
BOCK_MOBILE_API_TOKEN=demo \
python3 server.py
```

Open http://127.0.0.1:3033 — mobile API token is `demo`.

## Run tests

```bash
pytest tests/

# Web UI (jsdom)
cd tests/ui && npm install && npm test
```

## Mobile apps

See the root [`README.md`](README.md) **Full setup** section for Android and iOS build
steps. Point the app at your demo server URL and matching `mobileApi.token`.

## Regenerating screenshots

If you change UI that appears in the README, regenerate captures from demo data — see
[`docs/screenshots/README.md`](docs/screenshots/README.md).

## Pull requests

1. Keep changes focused — one feature or fix per PR when possible.
2. Run `pytest tests/` before opening.
3. Do not commit secrets (`config.json`, tokens, private hostnames).
4. For large architectural changes, open an issue first to discuss scope.

This public repo is a mirror; some changes may be merged here directly or coordinated
with the maintainer for the private development checkout.
