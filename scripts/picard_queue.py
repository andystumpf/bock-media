#!/usr/bin/env python3
"""Build a Picard work queue from songs_cache rows that lack embedded tags.

Picard tags files on disk; ourMedia backfills read those tags into songs_cache
afterward (see scripts/after_picard.sh). This script does NOT call Picard.

  python3 scripts/picard_queue.py
  python3 scripts/picard_queue.py --mode genre
  python3 scripts/picard_queue.py --mode album_artist
  python3 scripts/picard_queue.py --limit-dirs 50

Writes (under OURMEDIA_DATA_DIR or repo):
  picard-queue-paths.txt   — one audio path per line
  picard-queue-dirs.tsv    — parent dirs ranked by untagged track count
"""
import argparse
import os
import sqlite3
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(HERE, 'scripts'))
from lib import tag_io  # noqa: E402

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data', 'songs_cache.db'))
OUT_DIR = os.environ.get('OURMEDIA_DATA_DIR', os.path.expanduser('~/.bockmedia'))

AUDIO_WHERE = '(' + ' OR '.join(f"LOWER(path) LIKE '%{ext}'" for ext in sorted(tag_io.SUPPORTED)) + ')'


def main():
    ap = argparse.ArgumentParser(description='Export untagged tracks for Picard')
    ap.add_argument('--mode', choices=('any', 'genre', 'album_artist', 'both'),
                    default='any',
                    help='any=missing genre OR album_artist; both=require both missing')
    ap.add_argument('--limit-dirs', type=int, default=0,
                    help='Max directories to list in dirs file (0=all)')
    ap.add_argument('--out-dir', default=OUT_DIR, help='Output directory')
    ap.add_argument('--fast', action='store_true',
                    help='Skip per-file existence checks (much faster on NFS)')
    args = ap.parse_args()

    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}', file=sys.stderr)
        return 1

    if args.mode == 'genre':
        miss = '(genre IS NULL OR TRIM(genre) = "")'
    elif args.mode == 'album_artist':
        miss = '(album_artist IS NULL OR TRIM(album_artist) = "")'
    elif args.mode == 'both':
        miss = ('(genre IS NULL OR TRIM(genre) = "") AND '
                '(album_artist IS NULL OR TRIM(album_artist) = "")')
    else:
        miss = ('((genre IS NULL OR TRIM(genre) = "") OR '
                '(album_artist IS NULL OR TRIM(album_artist) = ""))')

    conn = sqlite3.connect(f'file:{DB_PATH}?mode=ro', uri=True)
    rows = conn.execute(f'''
        SELECT path FROM songs_cache
        WHERE path IS NOT NULL AND path != "" AND {AUDIO_WHERE} AND {miss}
    ''').fetchall()
    conn.close()

    paths = []
    dir_counts = {}
    missing_file = 0
    for (path,) in rows:
        if not args.fast and not os.path.isfile(path):
            missing_file += 1
            continue
        paths.append(path)
        d = os.path.dirname(path)
        dir_counts[d] = dir_counts.get(d, 0) + 1

    os.makedirs(args.out_dir, exist_ok=True)
    paths_file = os.path.join(args.out_dir, 'picard-queue-paths.txt')
    dirs_file = os.path.join(args.out_dir, 'picard-queue-dirs.tsv')

    with open(paths_file, 'w', encoding='utf-8') as f:
        for p in paths:
            f.write(p + '\n')

    ranked = sorted(dir_counts.items(), key=lambda x: -x[1])
    if args.limit_dirs:
        ranked = ranked[: args.limit_dirs]

    with open(dirs_file, 'w', encoding='utf-8') as f:
        f.write('tracks\tdirectory\n')
        for d, n in ranked:
            f.write(f'{n}\t{d}\n')

    print(f'mode={args.mode}')
    print(f'queue paths: {len(paths):,} -> {paths_file}')
    print(f'queue dirs:  {len(dir_counts):,} unique ({len(ranked):,} listed) -> {dirs_file}')
    print(f'missing_file (in DB only): {missing_file:,}')
    if ranked:
        print(f'largest batch: {ranked[0][1]:,} tracks in {ranked[0][0]}')
    print('\nNext: ./scripts/beets_batch.sh --limit 5     # headless (no GUI)')
    print('      ./scripts/picard_batch.sh --limit 5      # Picard CLI (needs display)')
    print('      docs/PICARD.md')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
