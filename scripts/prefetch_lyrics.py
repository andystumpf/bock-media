#!/usr/bin/env python3
"""Prefetch synced lyrics for the library into DATA_DIR/lyrics_cache.

Uses LRCLIB (and sidecar/embedded when present). Run once to warm the cache so
every client gets the same high-quality synced lyrics where available.

  python3 scripts/prefetch_lyrics.py              # missing cache only
  python3 scripts/prefetch_lyrics.py --force        # re-fetch all
  python3 scripts/prefetch_lyrics.py --dry-run
  python3 scripts/prefetch_lyrics.py --limit 100    # smoke test
"""
import argparse
import os
import sqlite3
import sys
import time

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

import server


def main():
    ap = argparse.ArgumentParser(description='Prefetch lyrics into lyrics_cache')
    ap.add_argument('--force', action='store_true', help='Ignore existing cache entries')
    ap.add_argument('--upgrade-plain', action='store_true',
                    help='Re-fetch tracks that only have plain (non-karaoke) cache entries')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--limit', type=int, default=0, help='Max tracks (0 = all)')
    ap.add_argument('--sleep', type=float, default=0.45, help='Seconds between LRCLIB calls')
    args = ap.parse_args()

    db_path = server.DB_PATH
    if not os.path.isfile(db_path):
        print(f'DB not found: {db_path}', file=sys.stderr)
        return 1

    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(
        'SELECT path, title, artist, album, duration_seconds FROM songs_cache '
        'WHERE path IS NOT NULL AND path != "" ORDER BY artist, album, title'
    ).fetchall()
    conn.close()

    synced = plain = missing = skipped = 0
    for i, row in enumerate(rows):
        if args.limit and i >= args.limit:
            break
        path = row['path']
        if not args.force:
            cached = server._lyrics_cache_read(path)
            if cached and cached.get('lines') and not cached.get('estimated'):
                skipped += 1
                continue
            if cached and cached.get('lines') and cached.get('estimated') and not args.upgrade_plain:
                skipped += 1
                continue
        if args.dry_run:
            print(f'would fetch: {row["artist"]} — {row["title"]}')
            continue
        payload, _ = server.prefetch_lyrics_for_path(
            path,
            duration_sec=row['duration_seconds'],
            title=row['title'],
            artist=row['artist'],
            album=row['album'],
            force=args.force,
        )
        if payload.get('synced') and payload.get('lines') and not payload.get('estimated'):
            synced += 1
        elif payload.get('lines'):
            plain += 1  # estimated karaoke
        elif (payload.get('plain') or '').strip():
            plain += 1
        else:
            missing += 1
        if (i + 1) % 25 == 0:
            print(f'… {i + 1} processed (synced={synced} plain={plain} missing={missing} skipped={skipped})', flush=True)
        time.sleep(args.sleep)

    print(
        f'Done. synced={synced} plain={plain} missing={missing} skipped={skipped} '
        f'cache_dir={server.LYRICS_CACHE_DIR}'
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
