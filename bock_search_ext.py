"""FTS5 search + suggest extensions."""
import sqlite3

import bock_search

_FTS_READY = False


def ensure_fts(get_db_rw, db_query):
    global _FTS_READY
    if _FTS_READY:
        return
    rows = db_query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='songs_fts'"
    ) or []
    try:
        conn = get_db_rw()
        try:
            if not rows:
                conn.execute(
                    'CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts USING fts5('
                    'title, artist, album, path, content=songs_cache, content_rowid=rowid)'
                )
                conn.execute(
                    'INSERT INTO songs_fts(rowid, title, artist, album, path) '
                    'SELECT rowid, title, artist, album, path FROM songs_cache'
                )
            conn.commit()
            _FTS_READY = True
        finally:
            conn.close()
    except Exception:
        pass


def rebuild_fts(get_db):
    conn = get_db()
    try:
        conn.execute('DELETE FROM songs_fts')
        conn.execute(
            'INSERT INTO songs_fts(rowid, title, artist, album, path) '
            'SELECT rowid, title, artist, album, path FROM songs_cache'
        )
        conn.commit()
    finally:
        conn.close()


def fts_songs(db_query, q, limit=5):
    if len(q) < 1:
        return []
    fts_q = bock_search._fts_query(q)
    try:
        if fts_q:
            rows = db_query(
                'SELECT s.title, s.artist, s.album, s.path FROM songs_fts f '
                'JOIN songs_cache s ON s.rowid = f.rowid '
                'WHERE songs_fts MATCH ? LIMIT ?',
                [fts_q, limit * 3],
            ) or []
            if rows:
                return [
                    r for r in rows
                    if bock_search.library_search_song_match(
                        q, r.get('title'), r.get('album'), artist=r.get('artist'),
                    )
                ][:limit]
    except Exception:
        pass
    patterns = bock_search._sql_prefix_patterns(q)
    if not patterns:
        return []
    clause, params = bock_search._like_or_clause(('title', 'artist'), patterns)
    rows = db_query(
        f'SELECT title, artist, album, path FROM songs_cache '
        f'WHERE ({clause}) AND path IS NOT NULL LIMIT ?',
        [*params, limit * 4],
    ) or []
    return [
        r for r in rows
        if bock_search.library_search_song_match(
            q, r.get('title'), r.get('album'), artist=r.get('artist'),
        )
    ][:limit]


def suggest_payload(db_query, q, playlist_names, device_names, smart_names, limit=5):
    songs = fts_songs(db_query, q, limit)
    playlists = [
        p for p in playlist_names
        if bock_search.field_matches_query(q, p.get('name') or '')
    ][:limit]
    patterns = bock_search._sql_prefix_patterns(q)
    artists, albums, genres = [], [], []
    if patterns:
        clause, params = bock_search._like_or_clause(('artist',), patterns)
        artists = [
            r for r in db_query(
                'SELECT artist, MIN(path) as path FROM songs_cache '
                f'WHERE ({clause}) AND artist != "" GROUP BY artist LIMIT ?',
                [*params, limit * 4],
            ) or []
            if bock_search.field_matches_query(q, r.get('artist') or '')
        ][:limit]
        clause, params = bock_search._like_or_clause(('album',), patterns)
        albums = [
            r for r in db_query(
                'SELECT album, artist, MIN(path) as path FROM songs_cache '
                f'WHERE ({clause}) AND album != "" GROUP BY album, artist LIMIT ?',
                [*params, limit * 4],
            ) or []
            if bock_search.field_matches_query(q, r.get('album') or '')
        ][:limit]
        clause, params = bock_search._like_or_clause(('genre',), patterns)
        genres = [
            r for r in db_query(
                'SELECT genre, MIN(path) as path FROM songs_cache '
                f'WHERE ({clause}) AND genre != "" GROUP BY genre LIMIT ?',
                [*params, limit * 4],
            ) or []
            if bock_search.field_matches_query(q, r.get('genre') or '')
        ][:limit]
    rooms = [
        {'name': n} for n in device_names
        if bock_search.field_matches_query(q, n)
    ][:limit]
    smart = [
        s for s in smart_names
        if bock_search.field_matches_query(q, s.get('name') or '')
    ][:limit]
    return {
        'query': q,
        'songs': songs,
        'playlists': playlists,
        'artists': [{'name': r['artist'], 'path': r.get('path')} for r in artists],
        'albums': [{'name': r['album'], 'artist': r.get('artist'), 'path': r.get('path')} for r in albums],
        'genres': [{'name': r['genre'], 'path': r.get('path')} for r in genres],
        'rooms': rooms,
        'smartPlaylists': smart,
    }
