#!/usr/bin/env python3
"""Backfill songs_cache.duration_seconds from audio file metadata (mutagen).

The external music_organizer index often leaves duration_seconds empty; ourMedia
uses this column for Now Playing totals, stale-row expiry, and the Songs table.

  python3 scripts/backfill_duration.py           # only rows missing duration
  python3 scripts/backfill_duration.py --force   # re-read every file
  python3 scripts/backfill_duration.py --dry-run
"""
import argparse
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data', 'songs_cache.db'))
SUPPORTED = {'.mp3', '.m4a', '.flac', '.ogg', '.opus', '.wav', '.aac', '.wma', '.aiff', '.alac'}


def duration_from_file(path):
    try:
        from mutagen import File as MutaFile
        mf = MutaFile(path)
        if mf and mf.info and mf.info.length:
            return int(round(float(mf.info.length)))
    except Exception:
        pass
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--force', action='store_true', help='Update all rows with a readable file')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--limit', type=int, default=0, help='Max files to process (0 = all)')
    args = ap.parse_args()

    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}', file=sys.stderr)
        return 1

    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()
    if args.force:
        cur.execute('SELECT path FROM songs_cache WHERE path IS NOT NULL AND path != ""')
    else:
        cur.execute(
            'SELECT path FROM songs_cache WHERE path IS NOT NULL AND path != "" '
            'AND (duration_seconds IS NULL OR duration_seconds = 0)'
        )
    paths = [r['path'] for r in cur.fetchall()]
    if args.limit:
        paths = paths[: args.limit]

    updated = skipped = missing = 0
    for i, path in enumerate(paths):
        if os.path.splitext(path)[1].lower() not in SUPPORTED:
            skipped += 1
            continue
        if not os.path.isfile(path):
            missing += 1
            continue
        dur = duration_from_file(path)
        if not dur:
            skipped += 1
            continue
        if not args.dry_run:
            conn.execute(
                'UPDATE songs_cache SET duration_seconds = ? WHERE path = ?',
                (dur, path),
            )
        updated += 1
        if (i + 1) % 500 == 0:
            print(f'  … {i + 1}/{len(paths)}', flush=True)

    if not args.dry_run:
        conn.commit()
    conn.close()
    print(f'done: updated={updated} skipped={skipped} missing_file={missing} dry_run={args.dry_run}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
