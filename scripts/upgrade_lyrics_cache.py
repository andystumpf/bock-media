#!/usr/bin/env python3
"""Upgrade plain-only lyrics cache entries to karaoke lines (estimated timing).

Run after server.py learns _lyrics_finalize — no network required.

  python3 scripts/upgrade_lyrics_cache.py
  python3 scripts/upgrade_lyrics_cache.py --dry-run
"""
import argparse
import glob
import json
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

import server


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    cache_dir = server.LYRICS_CACHE_DIR
    if not os.path.isdir(cache_dir):
        print(f'No cache dir: {cache_dir}')
        return 0

    db_path = server.DB_PATH
    conn = sqlite3.connect(db_path) if os.path.isfile(db_path) else None
    upgraded = skipped = 0

    for fp in glob.glob(os.path.join(cache_dir, '*.json')):
        try:
            with open(fp, encoding='utf-8') as fh:
                data = json.load(fh)
        except Exception:
            continue
        if not isinstance(data, dict) or data.get('lines'):
            skipped += 1
            continue
        path = data.get('path') or ''
        dur = None
        if conn and path:
            row = conn.execute(
                'SELECT duration_seconds FROM songs_cache WHERE path = ?',
                [path],
            ).fetchone()
            if row and row[0]:
                try:
                    dur = int(float(row[0]))
                except (TypeError, ValueError):
                    pass
        if not dur and path:
            ms = server._duration_ms_for_path(path)
            if ms > 0:
                dur = max(ms // 1000, 1)
        out = server._lyrics_finalize(data, dur)
        if not out.get('lines'):
            skipped += 1
            continue
        if args.dry_run:
            print(f'would upgrade: {path or fp}')
        else:
            server._lyrics_cache_write(path or fp, out)
        upgraded += 1

    if conn:
        conn.close()
    print(f'Done. upgraded={upgraded} skipped={skipped} dir={cache_dir}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
