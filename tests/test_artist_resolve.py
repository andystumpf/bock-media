"""Artist name resolution for /api/artists/{name}."""
import sqlite3

import pytest

from bock_routes import resolve_library_artist_name


def _memory_db(rows):
    conn = sqlite3.connect(':memory:')
    conn.row_factory = sqlite3.Row
    conn.execute(
        'CREATE TABLE songs_cache (artist TEXT, album TEXT, path TEXT, title TEXT)'
    )
    for row in rows:
        conn.execute(
            'INSERT INTO songs_cache (artist, album, path, title) VALUES (?, ?, ?, ?)',
            (row['artist'], row.get('album', ''), row.get('path', '/x.mp3'), row.get('title', 't')),
        )
    conn.commit()

    def db_query(sql, params=()):
        cur = conn.execute(sql, params)
        return [dict(r) for r in cur.fetchall()]

    def db_one(sql, params=()):
        cur = conn.execute(sql, params)
        row = cur.fetchone()
        return dict(row) if row else None

    return db_query, db_one


class TestResolveLibraryArtistName:
    def test_exact_match(self):
        db_query, db_one = _memory_db([
            {'artist': 'The Smashing Pumpkins', 'album': 'Gish'},
            {'artist': 'The Smashing Pumpkins', 'album': 'Siamese Dream'},
        ])
        assert resolve_library_artist_name(db_query, db_one, 'The Smashing Pumpkins') == 'The Smashing Pumpkins'

    def test_without_the_resolves_to_canonical(self):
        db_query, db_one = _memory_db([
            {'artist': 'The Smashing Pumpkins', 'album': 'Gish'},
            {'artist': 'The Smashing Pumpkins', 'album': 'Siamese Dream'},
            {'artist': 'The Smashing Pumpkins', 'album': 'Mellon Collie'},
        ])
        assert resolve_library_artist_name(db_query, db_one, 'Smashing Pumpkins') == 'The Smashing Pumpkins'

    def test_with_the_resolves_to_short_form(self):
        db_query, db_one = _memory_db([
            {'artist': 'Smashing Pumpkins', 'album': 'Gish'},
            {'artist': 'Smashing Pumpkins', 'album': 'Siamese Dream'},
        ])
        assert resolve_library_artist_name(db_query, db_one, 'The Smashing Pumpkins') == 'Smashing Pumpkins'

    def test_plus_signs_become_spaces(self):
        db_query, db_one = _memory_db([
            {'artist': 'The Smashing Pumpkins', 'album': 'Gish'},
        ])
        assert resolve_library_artist_name(db_query, db_one, 'The+Smashing+Pumpkins') == 'The Smashing Pumpkins'

    def test_prefers_highest_track_count_on_key_collision(self):
        db_query, db_one = _memory_db([
            {'artist': 'Smashing Pumpkins', 'album': 'Gish'},
            {'artist': 'The Smashing Pumpkins', 'album': 'A'},
            {'artist': 'The Smashing Pumpkins', 'album': 'B'},
            {'artist': 'The Smashing Pumpkins', 'album': 'C'},
        ])
        assert resolve_library_artist_name(db_query, db_one, 'Smashing Pumpkins') == 'The Smashing Pumpkins'
