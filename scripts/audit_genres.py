#!/usr/bin/env python3
"""Report genre coverage and distribution in songs_cache (music_organizer.db).

Use before standing up genre radio / smart playlists to see NULL rates,
fragmentation, and candidate station buckets.

  python3 scripts/audit_genres.py
  python3 scripts/audit_genres.py --top 80
  python3 scripts/audit_genres.py --json
"""
import argparse
import json
import os
import sqlite3
import sys

DB_PATH = os.environ.get('OURMEDIA_DB_PATH', '/mnt/bock/Music/music_organizer.db')

AUDIO_EXT = "LOWER(SUBSTR(path, -4)) IN ('.mp3', '.m4a', '.aac', '.flac', '.ogg', '.wav', '.wma')"


def q(conn, sql, params=()):
    return conn.execute(sql, params).fetchall()


def main():
    ap = argparse.ArgumentParser(description='Genre audit for songs_cache')
    ap.add_argument('--top', type=int, default=50, help='How many genres to list (default 50)')
    ap.add_argument('--json', action='store_true', help='Emit JSON instead of text report')
    args = ap.parse_args()

    if not os.path.isfile(DB_PATH):
        print(f'DB not found: {DB_PATH}', file=sys.stderr)
        return 1

    conn = sqlite3.connect(f'file:{DB_PATH}?mode=ro', uri=True)
    conn.row_factory = sqlite3.Row

    total = q(conn, f'SELECT COUNT(*) AS n FROM songs_cache WHERE path IS NOT NULL')[0]['n']
    audio = q(conn, f'SELECT COUNT(*) AS n FROM songs_cache WHERE path IS NOT NULL AND {AUDIO_EXT}')[0]['n']

    def genre_stats(where_extra=''):
        w = f'path IS NOT NULL AND {AUDIO_EXT}' + (f' AND {where_extra}' if where_extra else '')
        row = q(conn, f'''
            SELECT
                COUNT(*) AS n,
                SUM(CASE WHEN genre IS NULL OR TRIM(genre) = '' THEN 1 ELSE 0 END) AS empty_genre,
                SUM(CASE WHEN year IS NULL OR year = 0 THEN 1 ELSE 0 END) AS empty_year,
                COUNT(DISTINCT CASE WHEN genre IS NOT NULL AND TRIM(genre) != '' THEN genre END) AS distinct_genres
            FROM songs_cache WHERE {w}
        ''')[0]
        return dict(row)

    all_s = genre_stats()
    playable = genre_stats('genre IS NOT NULL AND TRIM(genre) != ""')

    top_genres = q(conn, f'''
        SELECT TRIM(genre) AS genre, COUNT(*) AS n
        FROM songs_cache
        WHERE path IS NOT NULL AND {AUDIO_EXT}
          AND genre IS NOT NULL AND TRIM(genre) != ''
        GROUP BY LOWER(TRIM(genre))
        ORDER BY n DESC
        LIMIT ?
    ''', (args.top,))

    # Loose buckets (LIKE) — mirrors smart-playlist / station rules
    buckets = [
        ('Rock', '%rock%'),
        ('Pop', '%pop%'),
        ('Jazz', '%jazz%'),
        ('Classical', '%classic%'),
        ('Country', '%country%'),
        ('Blues', '%blues%'),
        ('Soul/R&B', '%soul%'),
        ('Hip-Hop', '%hip%'),
        ('Electronic', '%electronic%'),
        ('Folk', '%folk%'),
        ('Metal', '%metal%'),
        ('Punk', '%punk%'),
        ('Reggae', '%reggae%'),
        ('Gospel', '%gospel%'),
        ('Soundtrack', '%soundtrack%'),
        ('Alternative', '%alternative%'),
        ('Disco', '%disco%'),
        ('Funk', '%funk%'),
    ]
    bucket_rows = []
    for label, pat in buckets:
        n = q(conn, f'''
            SELECT COUNT(*) AS n FROM songs_cache
            WHERE path IS NOT NULL AND {AUDIO_EXT}
              AND LOWER(COALESCE(genre,'')) LIKE ?
        ''', (pat,))[0]['n']
        bucket_rows.append({'label': label, 'pattern': pat, 'tracks': n})

    # Case duplicates: same genre, different casing
    case_dupes = q(conn, f'''
        SELECT LOWER(TRIM(genre)) AS g, COUNT(DISTINCT TRIM(genre)) AS variants, SUM(cnt) AS tracks
        FROM (
            SELECT TRIM(genre) AS genre, COUNT(*) AS cnt
            FROM songs_cache
            WHERE path IS NOT NULL AND {AUDIO_EXT}
              AND genre IS NOT NULL AND TRIM(genre) != ''
            GROUP BY TRIM(genre)
        )
        GROUP BY LOWER(TRIM(genre))
        HAVING variants > 1
        ORDER BY tracks DESC
        LIMIT 15
    ''')

    report = {
        'db_path': DB_PATH,
        'total_indexed': total,
        'audio_tracks': audio,
        'genre_empty': all_s['empty_genre'],
        'genre_empty_pct': round(100.0 * all_s['empty_genre'] / audio, 1) if audio else 0,
        'year_empty': all_s['empty_year'],
        'year_empty_pct': round(100.0 * all_s['empty_year'] / audio, 1) if audio else 0,
        'distinct_genres': all_s['distinct_genres'],
        'tracks_with_genre': playable['n'],
        'top_genres': [{'genre': r['genre'], 'tracks': r['n']} for r in top_genres],
        'station_buckets': bucket_rows,
        'case_duplicates': [
            {'normalized': r['g'], 'variants': r['variants'], 'tracks': r['tracks']}
            for r in case_dupes
        ],
    }

    if args.json:
        print(json.dumps(report, indent=2))
        return 0

    print(f'Database: {DB_PATH}\n')
    print('── Coverage (audio files) ──')
    print(f'  Indexed paths:     {total:>8,}')
    print(f'  Audio extensions:  {audio:>8,}')
    print(f'  Missing genre:     {all_s["empty_genre"]:>8,}  ({report["genre_empty_pct"]}%)')
    print(f'  Missing year:      {all_s["empty_year"]:>8,}  ({report["year_empty_pct"]}%)')
    print(f'  Distinct genres:   {all_s["distinct_genres"]:>8,}')
    print(f'  Playable w/ genre: {playable["n"]:>8,}')

    print(f'\n── Top {args.top} genres ──')
    for r in top_genres:
        pct = 100.0 * r['n'] / audio if audio else 0
        print(f'  {r["n"]:>6,}  ({pct:4.1f}%)  {r["genre"]}')

    print('\n── Station bucket preview (LIKE rules) ──')
    for b in sorted(bucket_rows, key=lambda x: -x['tracks']):
        if b['tracks']:
            print(f'  {b["tracks"]:>6,}  {b["label"]:<14}  genre LIKE {b["pattern"]}')

    if case_dupes:
        print('\n── Case/spelling duplicates (same genre, multiple tags) ──')
        for r in case_dupes:
            print(f'  {r["tracks"]:>6,} tracks  {r["variants"]} variants  "{r["g"]}"')

    print('\n── Readiness ──')
    if report['genre_empty_pct'] > 30:
        print('  ⚠  >30% missing genre — smart stations will skip many tracks; consider tagging pass.')
    elif report['genre_empty_pct'] > 10:
        print('  ○  Some missing genres — stations work; optional cleanup improves coverage.')
    else:
        print('  ✓  Genre coverage looks good for smart-playlist stations.')

    if all_s['distinct_genres'] > 200:
        print('  ○  High genre fragmentation — use LIKE/multi-tag station rules, not exact voice match.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
