"""FTS5 search + suggest extensions."""
import sqlite3

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
    try:
        rows = db_query(
            'SELECT s.title, s.artist, s.album, s.path FROM songs_fts f '
            'JOIN songs_cache s ON s.rowid = f.rowid '
            'WHERE songs_fts MATCH ? LIMIT ?',
            [q + '*', limit],
        ) or []
        return rows
    except Exception:
        like = f'%{q.lower()}%'
        return db_query(
            'SELECT title, artist, album, path FROM songs_cache '
            'WHERE (LOWER(title) LIKE ? OR LOWER(artist) LIKE ?) AND path IS NOT NULL LIMIT ?',
            [like, like, limit],
        ) or []


def suggest_payload(db_query, q, playlist_names, device_names, smart_names, limit=5):
    songs = fts_songs(db_query, q, limit)
    ql = q.lower()
    playlists = [p for p in playlist_names if ql in p['name'].lower()][:limit]
    artists = db_query(
        'SELECT artist, MIN(path) as path FROM songs_cache '
        'WHERE LOWER(artist) LIKE ? AND artist != "" GROUP BY artist LIMIT ?',
        [f'%{ql}%', limit],
    ) or []
    albums = db_query(
        'SELECT album, artist, MIN(path) as path FROM songs_cache '
        'WHERE LOWER(album) LIKE ? AND album != "" GROUP BY album, artist LIMIT ?',
        [f'%{ql}%', limit],
    ) or []
    genres = db_query(
        'SELECT genre, MIN(path) as path FROM songs_cache '
        'WHERE LOWER(genre) LIKE ? AND genre != "" GROUP BY genre LIMIT ?',
        [f'%{ql}%', limit],
    ) or []
    rooms = [{'name': n} for n in device_names if ql in n.lower()][:limit]
    smart = [s for s in smart_names if ql in s['name'].lower()][:limit]
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
