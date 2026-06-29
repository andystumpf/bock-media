#!/usr/bin/env python3
"""Set songs_cache.album to '[YEAR] Album' from embedded tags when the DB value is wrong.

The indexer uses folder names; unknown years become '[1900] …' or 'Unknown Album'.
Picard writes a real release year + album into file tags. after_picard.sh fills the
year column but leaves album unchanged — this script rebuilds album as:

  [{year}] {tag_album}

only when the DB album is missing a real year prefix or is a placeholder.

  python3 scripts/backfill_album_year_prefix.py --dry-run
  python3 scripts/backfill_album_year_prefix.py --limit 5000
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lib import tag_io  # noqa: E402

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data', 'songs_cache.db'))
AUDIO_WHERE = '(' + ' OR '.join(f"LOWER(path) LIKE '%{ext}'" for ext in sorted(tag_io.SUPPORTED)) + ')'

_GOOD_YEAR_PREFIX = re.compile(r'^\[(19[1-9]\d|20\d{2})\] ')
_PLACEHOLDER_PREFIX = re.compile(r'^\[1900\] ')


def needs_rebuild(db_album):
    if not db_album or not str(db_album).strip():
        return True
    s = str(db_album).strip()
    if s.lower() in ('unknown album', 'unknown'):
        return True
    if _PLACEHOLDER_PREFIX.match(s):
        return True
    if not _GOOD_YEAR_PREFIX.match(s):
        return True
    return False


def album_from_tags(tags):
    year = tags.get('year')
    album = tag_io.normalize_text(tags.get('album'))
    if not album or not year or year == 1900:
        return None
    return f'[{year}] {album}'


def main():
    ap = argparse.ArgumentParser(description='Rebuild [YEAR] Album from file tags')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--commit-every', type=int, default=2000)
    args = ap.parse_args()

    conn = tag_io.connect_db(DB_PATH, readonly=False)
    # Only rows that might need rebuilding (~45k), not all 398k audio paths.
    rows = conn.execute(f'''
        SELECT path, album FROM songs_cache
        WHERE path IS NOT NULL AND path != "" AND {AUDIO_WHERE}
          AND (
            album IS NULL OR TRIM(album) = ''
            OR LOWER(TRIM(album)) IN ('unknown album', 'unknown')
            OR album LIKE '[1900]%'
            OR (album NOT LIKE '[19%' AND album NOT LIKE '[20%')
          )
    ''').fetchall()
    print(f'candidates: {len(rows):,} rows (reading tags from disk — slow on NFS)', flush=True)

    updated = skipped = no_tags = missing = unchanged = 0
    for i, row in enumerate(rows):
        if args.limit and updated >= args.limit:
            break
        path = row['path']
        db_album = row['album']
        if (i + 1) % 500 == 0:
            print(f'  … {i + 1:,}/{len(rows):,} scanned, {updated:,} updated', flush=True)
        if not needs_rebuild(db_album):
            unchanged += 1
            continue
        if not os.path.isfile(path):
            missing += 1
            continue
        tags = tag_io.read_tags(path)
        new_album = album_from_tags(tags)
        if not new_album:
            no_tags += 1
            continue
        if tag_io.normalize_text(db_album) == new_album:
            unchanged += 1
            continue
        if not args.dry_run:
            tag_io.db_retry(
                lambda p=path, a=new_album: conn.execute(
                    'UPDATE songs_cache SET album = ? WHERE path = ?', (a, p)),
                label='update',
            )
            if (updated + 1) % args.commit_every == 0:
                tag_io.db_retry(lambda: conn.commit(), label='commit')
        updated += 1
        if updated <= 5:
            print(f'  {db_album!r} -> {new_album!r}')

    if not args.dry_run:
        tag_io.db_retry(lambda: conn.commit(), label='final commit')
    conn.close()
    print(
        f'done: updated={updated} unchanged_ok_prefix={unchanged} '
        f'no_year_or_album_tag={no_tags} missing_file={missing} dry_run={args.dry_run}'
    )
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
