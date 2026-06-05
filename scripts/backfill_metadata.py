#!/usr/bin/env python3
"""Realign songs_cache title/artist/album/album_artist with embedded file tags.

The external music_organizer index derives some columns from folder names — most
notably it stores album as "[YEAR] Album Folder" and never fills album_artist.
This reads each file's own tags (the original release metadata) via mutagen and
makes the embedded tag authoritative: the DB row is updated AND, by default, the
tag values are normalized back into the file so DB and files stay in sync.

Tags win. A field is only changed when the file has a non-empty tag value that
differs from the DB; an empty tag never blanks an existing DB value.

`artist` is fill-only by default: the indexed artist is already clean and fully
populated, whereas raw artist tags are frequently multi-value ("A/B", "X feat.
Y") and splitting them is unsafe (e.g. "AC/DC"). Pass --overwrite-artist to make
tags authoritative for artist too.

  python3 scripts/backfill_metadata.py                 # read every audio file, realign on diff
  python3 scripts/backfill_metadata.py --only-missing   # fast: only rows with an empty target field
  python3 scripts/backfill_metadata.py --dry-run        # report, write nothing
  python3 scripts/backfill_metadata.py --limit 5000     # cap rows (pilot)
  python3 scripts/backfill_metadata.py --fields album,album_artist
  python3 scripts/backfill_metadata.py --overwrite-artist
  python3 scripts/backfill_metadata.py --no-write-files # update DB only, leave files untouched
  python3 scripts/backfill_metadata.py --strip-remaster # also drop trailing "(Remastered)" on title/album

CAUTION: writing files modifies tags in place. Back up the library before the
first full run. mutagen overwrites tags; it does not re-encode audio.
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lib import tag_io  # noqa: E402

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', '/mnt/bock/Music/music_organizer.db')

# Field name == songs_cache column name for everything we manage.
DEFAULT_FIELDS = ['title', 'artist', 'album', 'album_artist', 'track_number', 'disc_number']
TEXT_FIELDS = {'title', 'artist', 'album', 'album_artist'}
INT_FIELDS = {'track_number', 'disc_number'}

_REMASTER_RE = re.compile(r'\s*[\(\[][^\)\]]*remaster[^\)\]]*[\)\]]\s*$', re.I)


def _norm_cmp(s):
    return (tag_io.normalize_text(s) or '').lower()


def _clean_tag_value(field, value, *, strip_remaster):
    """Apply field-specific normalization to a tag value before compare/write."""
    if field in INT_FIELDS:
        return value if isinstance(value, int) and value > 0 else None
    v = tag_io.normalize_text(value)
    if not v:
        return None
    if field == 'title':
        v = tag_io.strip_track_prefix(v)
    if strip_remaster and field in ('title', 'album'):
        v = tag_io.normalize_text(_REMASTER_RE.sub('', v)) or v
    return v


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--force', action='store_true',
                    help='Re-write file tags even when DB already matches (forces normalization into files)')
    ap.add_argument('--only-missing', action='store_true',
                    help='Only process rows where a target field is empty (fast path)')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--limit', type=int, default=0, help='Max files to process (0 = all)')
    ap.add_argument('--commit-every', type=int, default=2000, help='DB commit interval')
    ap.add_argument('--fields', default=','.join(DEFAULT_FIELDS),
                    help=f'Comma list of fields to manage (default: {",".join(DEFAULT_FIELDS)})')
    ap.add_argument('--no-write-files', action='store_true',
                    help='Update songs_cache only; do not modify file tags')
    ap.add_argument('--overwrite-artist', action='store_true',
                    help='Let tags overwrite a non-empty artist (default: artist is fill-only)')
    ap.add_argument('--strip-remaster', action='store_true',
                    help='Drop trailing "(Remastered...)" parentheticals on title/album')
    args = ap.parse_args()

    fields = [f.strip() for f in args.fields.split(',') if f.strip()]
    bad = [f for f in fields if f not in DEFAULT_FIELDS]
    if bad:
        print(f'Unknown field(s): {", ".join(bad)}; valid: {", ".join(DEFAULT_FIELDS)}', file=sys.stderr)
        return 2
    if not fields:
        print('No fields selected', file=sys.stderr)
        return 2

    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}', file=sys.stderr)
        return 1

    write_files = not args.no_write_files
    # Fields that only fill an empty DB value rather than overwriting it.
    fill_only = set() if args.overwrite_artist else {'artist'}
    conn = tag_io.connect_db(DB_PATH)
    cur = conn.cursor()

    select_cols = 'path, ' + ', '.join(fields)
    where = 'path IS NOT NULL AND path != ""'
    if args.only_missing:
        empties = ' OR '.join(
            (f'{f} IS NULL OR TRIM({f}) = ""' if f in TEXT_FIELDS else f'{f} IS NULL OR {f} = 0')
            for f in fields
        )
        where += f' AND ({empties})'
    cur.execute(f'SELECT {select_cols} FROM songs_cache WHERE {where}')
    rows = cur.fetchall()
    if args.limit:
        rows = rows[: args.limit]

    total = len(rows)
    print(f'Scanning {total:,} rows for fields: {", ".join(fields)} '
          f'(write_files={write_files}, dry_run={args.dry_run})', flush=True)

    updated_rows = no_tags = missing_file = unsupported = unchanged = 0
    files_written = file_write_failed = 0
    field_changes = {f: 0 for f in fields}

    for i, row in enumerate(rows):
        path = row['path']
        if os.path.splitext(path)[1].lower() not in tag_io.SUPPORTED:
            unsupported += 1
            continue
        if not os.path.isfile(path):
            missing_file += 1
            continue

        tags = tag_io.read_tags(path)
        if not any(tags.get(f) for f in ('title', 'artist', 'album', 'album_artist')):
            no_tags += 1
            continue

        db_updates = {}        # column -> new value (where DB differs)
        write_payload = {}     # field -> value to push into the file
        for f in fields:
            tagval = _clean_tag_value(f, tags.get(f), strip_remaster=args.strip_remaster)
            if not tagval:
                continue
            db_empty = (row[f] is None) or (f in TEXT_FIELDS and not str(row[f]).strip()) or (f in INT_FIELDS and not row[f])
            if f in fill_only and not db_empty:
                continue  # keep the cleaner indexed value
            write_payload[f] = tagval
            if f in INT_FIELDS:
                differs = row[f] != tagval
            else:
                differs = _norm_cmp(row[f]) != _norm_cmp(tagval)
            if differs:
                db_updates[f] = tagval
                field_changes[f] += 1

        if not db_updates and not (args.force and write_files):
            unchanged += 1
            continue

        if db_updates and not args.dry_run:
            set_clause = ', '.join(f'{c} = ?' for c in db_updates)
            params = list(db_updates.values()) + [path]
            tag_io.db_retry(
                lambda: conn.execute(f'UPDATE songs_cache SET {set_clause} WHERE path = ?', params),
                label='update',
            )

        if db_updates:
            updated_rows += 1

        if write_files:
            # In --force we re-push everything; otherwise just the changed fields.
            payload = write_payload if args.force else {f: write_payload[f] for f in db_updates}
            if payload:
                try:
                    if tag_io.write_tags(path, payload, dry_run=args.dry_run):
                        files_written += 1
                except Exception:
                    file_write_failed += 1

        if not args.dry_run and (i + 1) % args.commit_every == 0:
            tag_io.db_retry(lambda: conn.commit(), label='commit')
            print(f'  … {i + 1}/{total} committed', flush=True)
        elif (i + 1) % 5000 == 0:
            print(f'  … {i + 1}/{total} processed', flush=True)

    if not args.dry_run:
        tag_io.db_retry(lambda: conn.commit(), label='final commit')
    conn.close()

    changes_str = ' '.join(f'{f}={field_changes[f]}' for f in fields)
    print(
        f'done: db_rows_updated={updated_rows} files_written={files_written} '
        f'file_write_failed={file_write_failed} unchanged={unchanged} '
        f'no_tags={no_tags} unsupported={unsupported} missing_file={missing_file} '
        f'dry_run={args.dry_run}'
    )
    print(f'field changes: {changes_str}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
