#!/usr/bin/env python3
"""Audit title/artist/album/album_artist quality in songs_cache vs embedded tags.

Read-only. Mirrors scripts/audit_genres.py: a DB-only coverage pass plus an
optional tag-reading pass (sampled by default) that flags where the indexed
metadata disagrees with the file's own tags — the cases backfill_metadata.py
would fix.

  python3 scripts/audit_metadata.py                 # coverage + 20k-row tag sample
  python3 scripts/audit_metadata.py --all           # read tags for every audio row (slow)
  python3 scripts/audit_metadata.py --sample 50000
  python3 scripts/audit_metadata.py --no-tags       # DB coverage only (fast)
  python3 scripts/audit_metadata.py --json
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lib import tag_io  # noqa: E402

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', '/mnt/bock/Music/music_organizer.db')

# Match any supported audio extension on the lowercased path (handles 4- and
# 5-char extensions correctly, unlike a fixed SUBSTR slice).
AUDIO_EXT = '(' + ' OR '.join(f"LOWER(path) LIKE '%{ext}'" for ext in sorted(tag_io.SUPPORTED)) + ')'

CORE_TEXT = ('title', 'artist', 'album', 'album_artist')


def q(conn, sql, params=()):
    return conn.execute(sql, params).fetchall()


def _norm(s):
    return (tag_io.normalize_text(s) or '').lower()


def main():
    ap = argparse.ArgumentParser(description='Metadata audit for songs_cache')
    ap.add_argument('--top', type=int, default=20, help='How many duplicate groups to list')
    ap.add_argument('--sample', type=int, default=20000,
                    help='Rows to read tags from for mismatch checks (0 with --all = every row)')
    ap.add_argument('--all', action='store_true', help='Read tags for every audio row (slow)')
    ap.add_argument('--no-tags', action='store_true', help='Skip the file-reading pass entirely')
    ap.add_argument('--json', action='store_true', help='Emit JSON instead of text')
    args = ap.parse_args()

    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}', file=sys.stderr)
        return 1

    conn = tag_io.connect_db(DB_PATH, readonly=True)

    total = q(conn, 'SELECT COUNT(*) AS n FROM songs_cache WHERE path IS NOT NULL')[0]['n']
    audio = q(conn, f'SELECT COUNT(*) AS n FROM songs_cache WHERE path IS NOT NULL AND {AUDIO_EXT}')[0]['n']

    coverage = {}
    for col in CORE_TEXT:
        empty = q(conn, f'''
            SELECT COUNT(*) AS n FROM songs_cache
            WHERE path IS NOT NULL AND {AUDIO_EXT}
              AND ({col} IS NULL OR TRIM({col}) = '')
        ''')[0]['n']
        coverage[col] = {
            'empty': empty,
            'empty_pct': round(100.0 * empty / audio, 1) if audio else 0,
        }

    def dupe_groups(col):
        return q(conn, f'''
            SELECT LOWER(v) AS g, COUNT(*) AS variants, SUM(cnt) AS tracks
            FROM (
                SELECT TRIM({col}) AS v, COUNT(*) AS cnt
                FROM songs_cache
                WHERE path IS NOT NULL AND {AUDIO_EXT}
                  AND {col} IS NOT NULL AND TRIM({col}) != ''
                GROUP BY TRIM({col})
            )
            GROUP BY LOWER(v)
            HAVING variants > 1
            ORDER BY tracks DESC
            LIMIT ?
        ''', (args.top,))

    artist_dupes = dupe_groups('artist')
    album_dupes = dupe_groups('album')

    # ── tag-reading pass ─────────────────────────────────────────────────────
    tag_report = None
    if not args.no_tags:
        sql = f'''SELECT path, title, artist, album, album_artist, track_number
                  FROM songs_cache WHERE path IS NOT NULL AND {AUDIO_EXT}'''
        if not args.all and args.sample > 0:
            sql += ' ORDER BY RANDOM() LIMIT ?'
            rows = q(conn, sql, (args.sample,))
        else:
            rows = q(conn, sql)

        scanned = no_tags = missing_file = unsupported = 0
        mismatch = {f: 0 for f in CORE_TEXT}
        folder_album = 0       # db album == parent folder, but tag album differs
        title_noise = 0        # db title has "NN - " prefix, tag title is clean
        comp_missing = 0       # db album_artist empty, file has album_artist
        examples = {'folder_album': [], 'title_noise': [], 'album_mismatch': []}

        for r in rows:
            path = r['path']
            if os.path.splitext(path)[1].lower() not in tag_io.SUPPORTED:
                unsupported += 1
                continue
            if not os.path.isfile(path):
                missing_file += 1
                continue
            tags = tag_io.read_tags(path)
            if not any(tags[f] for f in ('title', 'artist', 'album', 'album_artist')):
                no_tags += 1
                continue
            scanned += 1

            for f in CORE_TEXT:
                tv = tags.get(f)
                if tv and _norm(tv) != _norm(r[f]):
                    mismatch[f] += 1
                    if f == 'album' and len(examples['album_mismatch']) < 8:
                        examples['album_mismatch'].append(
                            {'path': path, 'db': r['album'], 'tag': tv})

            parent = os.path.basename(os.path.dirname(path))
            if (r['album'] and tags.get('album')
                    and _norm(r['album']) == _norm(parent)
                    and _norm(tags['album']) != _norm(parent)):
                folder_album += 1
                if len(examples['folder_album']) < 8:
                    examples['folder_album'].append(
                        {'path': path, 'db_album': r['album'], 'tag_album': tags['album']})

            db_title = r['title'] or ''
            if (tag_io.strip_track_prefix(db_title) != db_title
                    and tags.get('title')
                    and tag_io.strip_track_prefix(tags['title']) == tags['title']):
                title_noise += 1
                if len(examples['title_noise']) < 8:
                    examples['title_noise'].append(
                        {'path': path, 'db_title': r['title'], 'tag_title': tags['title']})

            if (not (r['album_artist'] or '').strip()) and tags.get('album_artist'):
                comp_missing += 1

        tag_report = {
            'rows_considered': len(rows),
            'scanned': scanned,
            'no_tags': no_tags,
            'missing_file': missing_file,
            'unsupported': unsupported,
            'mismatch': mismatch,
            'folder_album_suspect': folder_album,
            'title_track_prefix_noise': title_noise,
            'album_artist_fillable': comp_missing,
            'examples': examples,
        }

    report = {
        'db_path': DB_PATH,
        'total_indexed': total,
        'audio_tracks': audio,
        'coverage': coverage,
        'artist_case_duplicates': [
            {'normalized': r['g'], 'variants': r['variants'], 'tracks': r['tracks']}
            for r in artist_dupes
        ],
        'album_case_duplicates': [
            {'normalized': r['g'], 'variants': r['variants'], 'tracks': r['tracks']}
            for r in album_dupes
        ],
        'tag_scan': tag_report,
    }

    if args.json:
        print(json.dumps(report, indent=2))
        return 0

    print(f'Database: {DB_PATH}\n')
    print('── Coverage (audio files) ──')
    print(f'  Indexed paths:    {total:>9,}')
    print(f'  Audio extensions: {audio:>9,}')
    for col in CORE_TEXT:
        c = coverage[col]
        print(f'  Missing {col:<13} {c["empty"]:>9,}  ({c["empty_pct"]}%)')

    if tag_report:
        t = tag_report
        scope = 'all audio rows' if args.all else f'{t["rows_considered"]:,}-row sample'
        print(f'\n── Tag vs DB ({scope}; {t["scanned"]:,} scanned, '
              f'{t["no_tags"]:,} untagged, {t["missing_file"]:,} missing) ──')
        base = t['scanned'] or 1
        for f in CORE_TEXT:
            m = t['mismatch'][f]
            print(f'  {f:<13} differs in {m:>8,}  ({100.0 * m / base:4.1f}% of scanned)')
        print(f'\n  Folder-name album smell:  {t["folder_album_suspect"]:>8,}  '
              '(db album == folder, tag album differs)')
        print(f'  Title track-no. prefix:   {t["title_track_prefix_noise"]:>8,}  '
              '(db "NN - title", tag clean)')
        print(f'  Album-artist fillable:    {t["album_artist_fillable"]:>8,}  '
              '(db empty, file has album artist)')

        if t['examples']['album_mismatch']:
            print('\n  Sample album mismatches (db -> tag):')
            for e in t['examples']['album_mismatch']:
                print(f'    "{e["db"]}"  ->  "{e["tag"]}"')

    if album_dupes:
        print(f'\n── Album case/spelling duplicates (top {args.top}) ──')
        for r in album_dupes:
            print(f'  {r["tracks"]:>6,} tracks  {r["variants"]} variants  "{r["g"]}"')

    print('\n── Readiness ──')
    if tag_report:
        worst = max(tag_report['mismatch'].values()) if tag_report['scanned'] else 0
        pct = 100.0 * worst / (tag_report['scanned'] or 1)
        if pct > 15:
            print('  ⚠  Significant tag/DB drift — run backfill_metadata.py to realign DB + files.')
        elif pct > 3:
            print('  ○  Some drift — a backfill pass will tidy album/title/artist fields.')
        else:
            print('  ✓  DB metadata closely matches file tags.')
    else:
        print('  (run without --no-tags to compare DB against embedded tags)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
