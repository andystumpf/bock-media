#!/usr/bin/env python3
"""Analyze library loudness and store ReplayGain-style offsets in songs_cache."""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)

import bock_loudness  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description='Analyze loudness for songs_cache')
    parser.add_argument('--db', default=os.environ.get('OURMEDIA_DB_PATH', '/mnt/bock/Music/music_organizer.db'))
    parser.add_argument('--music-root', default=os.environ.get('OURMEDIA_MUSIC_ROOT', '/mnt/bock/Music'))
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--limit', type=int, default=0)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    args = parser.parse_args()

    import sqlite3

    def get_db():
        c = sqlite3.connect(args.db)
        c.row_factory = sqlite3.Row
        return c

    def db_query(sql, params=()):
        conn = get_db()
        try:
            cur = conn.execute(sql, params)
            return [dict(r) for r in cur.fetchall()]
        finally:
            conn.close()

    def db_one(sql, params=()):
        rows = db_query(sql, params)
        return rows[0] if rows else {}

    bock_loudness.ensure_songs_cache_columns(get_db, db_query)
    where = 'path IS NOT NULL AND path != ""'
    if not args.force:
        where += ' AND loudness_analyzed_at IS NULL'
    rows = db_query(f'SELECT path FROM songs_cache WHERE {where}')
    if args.limit:
        rows = rows[: args.limit]
    total = len(rows)
    print(f'Analyzing {total} tracks…')
    conn = get_db()
    now = __import__('time').strftime('%Y-%m-%dT%H:%M:%S')
    done = 0
    for row in rows:
        rel = row['path']
        full = rel if os.path.isabs(rel) else os.path.join(args.music_root, rel.lstrip('/'))
        result = bock_loudness.analyze_file(full, args.ffmpeg)
        if result:
            lufs, gain = result
            conn.execute(
                'UPDATE songs_cache SET loudness_lufs=?, replaygain_track_db=?, '
                'replaygain_album_db=?, loudness_analyzed_at=? WHERE path=?',
                (lufs, gain, gain, now, rel),
            )
        done += 1
        if done % 50 == 0:
            conn.commit()
            print(f'  {done}/{total}')
    conn.commit()
    conn.close()
    print(f'Done: {done}/{total}')


if __name__ == '__main__':
    main()
