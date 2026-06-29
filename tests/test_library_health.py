"""Tests for bock_library_health."""
import os
import sqlite3

import bock_library_health


def _make_db(tmp_path):
    path = str(tmp_path / 'test.db')
    conn = sqlite3.connect(path)
    conn.execute('''
        CREATE TABLE songs_cache (
            path TEXT, title TEXT, artist TEXT, album TEXT,
            album_artist TEXT, genre TEXT
        )
    ''')
    conn.executemany(
        'INSERT INTO songs_cache VALUES (?, ?, ?, ?, ?, ?)',
        [
            ('/music/a/s1.mp3', 'S1', 'Beatles', 'Abbey', 'Beatles', 'Rock'),
            ('/music/a/s2.mp3', 'S2', 'beatles', 'Help', 'Beatles', ''),
            ('/music/b/s3.mp3', 'S3', 'Artist', 'Album', '', 'Jazz'),
        ],
    )
    conn.commit()
    conn.close()
    return path


def test_metadata_summary(tmp_path):
    db_path = _make_db(tmp_path)

    def db_query(sql, params=()):
        conn = sqlite3.connect(f'file:{db_path}?mode=ro', uri=True)
        conn.row_factory = sqlite3.Row
        rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
        conn.close()
        return rows

    summary = bock_library_health.metadata_summary(db_query)
    assert summary['totalTracks'] == 3
    assert summary['missingGenre'] == 1
    assert summary['missingAlbumArtist'] == 1


def test_top_untagged_dirs(tmp_path):
    db_path = _make_db(tmp_path)
    dirs = bock_library_health.top_untagged_dirs(db_path, limit=3)
    assert len(dirs) >= 1
    assert dirs[0]['trackCount'] >= 1
    assert os.path.basename(dirs[0]['path']) in ('a', 'b')


def test_duplicate_artist_groups(tmp_path):
    db_path = _make_db(tmp_path)

    def db_query(sql, params=()):
        conn = sqlite3.connect(f'file:{db_path}?mode=ro', uri=True)
        conn.row_factory = sqlite3.Row
        rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
        conn.close()
        return rows

    groups = bock_library_health.duplicate_artist_groups(db_query)
    assert any('beatles' in g['canonical'].lower() or 'Beatles' in g['variants'] for g in groups)


def test_merge_artists(tmp_path):
    db_path = _make_db(tmp_path)

    def db_execute(sql, params=()):
        conn = sqlite3.connect(db_path)
        cur = conn.execute(sql, params)
        conn.commit()
        n = cur.rowcount
        conn.close()
        return n

    out = bock_library_health.merge_artists(db_execute, ['beatles'], 'Beatles')
    assert out['rowsUpdated'] >= 1

    conn = sqlite3.connect(db_path)
    artists = {r[0] for r in conn.execute('SELECT DISTINCT artist FROM songs_cache')}
    conn.close()
    assert artists == {'Beatles', 'Artist'}


def test_album_star_averages(tmp_path):
    import bock_ratings

    db_path = _make_db(tmp_path)
    ratings_path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(ratings_path, 'song', '/music/a/s1.mp3', 5, None, member_id='p-a')
    bock_ratings.set_rating(ratings_path, 'song', '/music/a/s2.mp3', 3, None, member_id='p-a')

    def db_query(sql, params=()):
        conn = sqlite3.connect(f'file:{db_path}?mode=ro', uri=True)
        conn.row_factory = sqlite3.Row
        rows = [dict(r) for r in conn.execute(sql, params).fetchall()]
        conn.close()
        return rows

    stats = bock_library_health.album_star_averages(ratings_path, 'p-a', db_query)
    assert stats[('Abbey', 'Beatles')]['avgStars'] == 5.0
    assert stats[('Help', 'beatles')]['avgStars'] == 3.0
