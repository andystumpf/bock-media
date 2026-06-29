# Demo data (fictional)

Run the server against this fixture:

```bash
python3 scripts/seed_demo_library.py   # refresh
export OURMEDIA_DATA_DIR="$PWD/fixtures/demo-data"
export OURMEDIA_DB_PATH="$PWD/fixtures/demo-data/songs_cache.db"
python3 server.py
```

Open http://127.0.0.1:3001/ — all artist/album names are synthetic.
