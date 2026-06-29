#!/usr/bin/env python3
"""Backfill songs_cache.genre and songs_cache.year from embedded file tags (mutagen).

The music_organizer index populates path/title/artist/album but often leaves genre
and year empty. Smart playlists and genre radio query those columns.

  python3 scripts/backfill_genres.py              # only rows missing genre or year
  python3 scripts/backfill_genres.py --force      # re-read every file
  python3 scripts/backfill_genres.py --dry-run
  python3 scripts/backfill_genres.py --limit 1000
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lib import tag_io  # noqa: E402

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data', 'songs_cache.db'))
SUPPORTED = tag_io.SUPPORTED


def _genre_year_from_file(path):
    """Read (genre, year) from embedded tags via the shared reader. ('', None) on miss."""
    try:
        tags = tag_io.read_tags(path)
    except ImportError:
        print('mutagen required: pip3 install --user mutagen', file=sys.stderr)
        return '', None
    return (tags.get('genre') or ''), tags.get('year')


def _connect_db():
    return tag_io.connect_db(DB_PATH)


def _db_retry(fn, *, retries=12, label='db'):
    return tag_io.db_retry(fn, retries=retries, label=label)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--force', action='store_true', help='Update all rows with readable tags')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--limit', type=int, default=0, help='Max files to process (0 = all)')
    ap.add_argument('--commit-every', type=int, default=2000, help='Commit interval')
    args = ap.parse_args()

    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}', file=sys.stderr)
        return 1

    conn = _connect_db()
    cur = conn.cursor()

    if args.force:
        cur.execute('SELECT path, genre, year FROM songs_cache WHERE path IS NOT NULL AND path != ""')
    else:
        cur.execute(
            'SELECT path, genre, year FROM songs_cache WHERE path IS NOT NULL AND path != "" '
            'AND (genre IS NULL OR TRIM(genre) = "" OR year IS NULL OR year = 0)'
        )
    rows = cur.fetchall()
    if args.limit:
        rows = rows[: args.limit]

    updated = skipped = missing = no_tags = 0
    genre_hits = year_hits = 0

    for i, row in enumerate(rows):
        path = row['path']
        if os.path.splitext(path)[1].lower() not in SUPPORTED:
            skipped += 1
            continue
        if not os.path.isfile(path):
            missing += 1
            continue

        file_genre, file_year = _genre_year_from_file(path)
        if not file_genre and file_year is None:
            no_tags += 1
            continue

        new_genre = file_genre or (row['genre'] or '').strip()
        new_year = file_year if file_year is not None else row['year']

        if not args.force:
            if (row['genre'] or '').strip():
                new_genre = row['genre']
            if row['year']:
                new_year = row['year']

        if not new_genre and not new_year:
            skipped += 1
            continue

        if file_genre:
            genre_hits += 1
        if file_year is not None:
            year_hits += 1

        if not args.dry_run:
            params = (new_genre or None, new_year or None, path)
            _db_retry(
                lambda: conn.execute(
                    'UPDATE songs_cache SET genre = ?, year = ? WHERE path = ?', params
                ),
                label='update',
            )
            if (i + 1) % args.commit_every == 0:
                _db_retry(lambda: conn.commit(), label='commit')
                print(f'  … {i + 1}/{len(rows)} committed', flush=True)

        updated += 1
        if (i + 1) % 5000 == 0:
            print(f'  … {i + 1}/{len(rows)} processed', flush=True)

    if not args.dry_run:
        _db_retry(lambda: conn.commit(), label='final commit')
    conn.close()

    print(
        f'done: updated={updated} genre_from_file={genre_hits} year_from_file={year_hits} '
        f'no_tags={no_tags} skipped={skipped} missing_file={missing} dry_run={args.dry_run}'
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
