"""FTS5 search + suggest extensions."""
import sqlite3
import time

import bock_search

_FTS_TABLE_READY = False  # table exists + initial backfill done
_FTS_LAST_SYNC = 0.0      # monotonic ts of last incremental sync
_FTS_RESYNC_INTERVAL = 60.0  # re-check for new rows at most this often (live server)
_FTS_LAST_ERROR = None    # last maintenance failure, surfaced via /api/health
_FTS_CREATE_SQL = (
    'CREATE VIRTUAL TABLE IF NOT EXISTS songs_fts USING fts5('
    'title, artist, album, genre, path, content=songs_cache, content_rowid=rowid, '
    'tokenize="unicode61 remove_diacritics 2")'
)
_FTS_INSERT_COLS = '(rowid, title, artist, album, genre, path)'
_FTS_SELECT_COLS = 'rowid, title, artist, album, genre, path'


def _fts_needs_rebuild(db_query):
    rows = db_query(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name='songs_fts'"
    ) or []
    if not rows:
        return False
    row = rows[0]
    sql = ((row.get('sql') if isinstance(row, dict) else row) or '').lower()
    return 'remove_diacritics' not in sql or 'genre' not in sql


def _fts_row_count(db_one):
    if not db_one:
        return 0
    row = db_one('SELECT COUNT(*) AS n FROM songs_fts')
    if row is None:
        return 0
    return int(row.get('n', row) if isinstance(row, dict) else row)


def _cache_row_count(db_one):
    if not db_one:
        return 0
    row = db_one(
        'SELECT COUNT(*) AS n FROM songs_cache '
        'WHERE path IS NOT NULL AND path != ""'
    )
    if row is None:
        return 0
    return int(row.get('n', row) if isinstance(row, dict) else row)


def _swap_rebuild(conn):
    """Build songs_fts_new fully, then swap it in — searches keep hitting the
    old table until the replacement is ready (no DROP gap under live traffic)."""
    conn.execute('DROP TABLE IF EXISTS songs_fts_new')
    conn.execute(_FTS_CREATE_SQL.replace('songs_fts', 'songs_fts_new', 1))
    conn.execute(
        f'INSERT INTO songs_fts_new{_FTS_INSERT_COLS} '
        f'SELECT {_FTS_SELECT_COLS} FROM songs_cache'
    )
    if conn.in_transaction:
        conn.commit()
    conn.execute('BEGIN IMMEDIATE')
    try:
        conn.execute('DROP TABLE IF EXISTS songs_fts')
        conn.execute('ALTER TABLE songs_fts_new RENAME TO songs_fts')
        conn.commit()
    except Exception:
        conn.rollback()
        raise


def fts_status():
    """Module state for /api/health — is the index ready, did maintenance fail."""
    return {'ftsReady': _FTS_TABLE_READY, 'ftsLastError': _FTS_LAST_ERROR}


def needs_full_build(db_query):
    """True when ensure_fts would run a full (slow) index build — missing table
    or outdated schema. Lets startup defer that work to a background thread."""
    rows = db_query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='songs_fts'"
    ) or []
    return not rows or _fts_needs_rebuild(db_query)


def ensure_fts(get_db_rw, db_query, db_one=None):
    """Create the FTS table if needed, then keep it in sync incrementally.

    Runs at server startup and from the background maintenance thread (never
    on the request path). Re-checks at most every _FTS_RESYNC_INTERVAL seconds
    so newly-synced songs get indexed without a restart; the steady-state cost
    is a cheap COUNT comparison, never a full rebuild.
    """
    global _FTS_TABLE_READY, _FTS_LAST_SYNC, _FTS_LAST_ERROR
    now = time.monotonic()
    if _FTS_TABLE_READY and (now - _FTS_LAST_SYNC) < _FTS_RESYNC_INTERVAL:
        return
    try:
        rows = db_query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='songs_fts'"
        ) or []
        conn = get_db_rw()
        try:
            if not rows:
                conn.execute(_FTS_CREATE_SQL)
                conn.execute(
                    f'INSERT INTO songs_fts{_FTS_INSERT_COLS} '
                    f'SELECT {_FTS_SELECT_COLS} FROM songs_cache'
                )
                conn.commit()
            elif _fts_needs_rebuild(db_query):
                # Schema changed (e.g. genre column added) — swap, don't drop.
                _swap_rebuild(conn)
            else:
                cache_n = _cache_row_count(db_one)
                fts_n = _fts_row_count(db_one)
                if cache_n and fts_n < cache_n:
                    # Incremental: index only rows missing from FTS (library grew).
                    conn.execute(
                        f'INSERT INTO songs_fts{_FTS_INSERT_COLS} '
                        'SELECT s.rowid, s.title, s.artist, s.album, s.genre, s.path '
                        'FROM songs_cache s '
                        'LEFT JOIN songs_fts f ON f.rowid = s.rowid '
                        'WHERE f.rowid IS NULL'
                    )
                    conn.commit()
                elif cache_n and fts_n > cache_n:
                    # Rows removed — rebuild via swap so live queries never
                    # observe a half-empty index.
                    _swap_rebuild(conn)
            _FTS_TABLE_READY = True
            _FTS_LAST_SYNC = now
            _FTS_LAST_ERROR = None
        finally:
            conn.close()
    except Exception as e:
        _FTS_LAST_ERROR = f'{type(e).__name__}: {e}'
        print(f'[fts] maintenance failed: {_FTS_LAST_ERROR}', flush=True)


def rebuild_fts(get_db):
    conn = get_db()
    try:
        conn.execute('DELETE FROM songs_fts')
        conn.execute(
            f'INSERT INTO songs_fts{_FTS_INSERT_COLS} '
            f'SELECT {_FTS_SELECT_COLS} FROM songs_cache'
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
                'SELECT s.title, s.artist, s.album, s.genre, s.path FROM songs_fts f '
                'JOIN songs_cache s ON s.rowid = f.rowid '
                'WHERE songs_fts MATCH ? LIMIT ?',
                [fts_q, limit * 3],
            ) or []
            if rows:
                return [
                    r for r in rows
                    if bock_search.library_search_song_match(
                        q, r.get('title'), r.get('album'), artist=r.get('artist'),
                        genre=r.get('genre'),
                    )
                ][:limit]
    except Exception:
        pass
    clause, params = bock_search._song_field_match_clause(q, ('title', 'artist'))
    if not clause:
        return []
    rows = db_query(
        f'SELECT title, artist, album, genre, path FROM songs_cache '
        f'WHERE ({clause}) AND path IS NOT NULL LIMIT ?',
        [*params, limit * 4],
    ) or []
    return [
        r for r in rows
        if bock_search.library_search_song_match(
            q, r.get('title'), r.get('album'), artist=r.get('artist'),
            genre=r.get('genre'),
        )
    ][:limit]


def suggest_payload(db_query, q, playlist_names, device_names, smart_names, limit=5):
    """Typeahead payload — reuse run_search fast paths (albums_agg + FTS), not songs_cache GROUP BY."""
    songs_raw = bock_search.fts_songs_ranked(db_query, q, limit)
    songs = [
        r for r in songs_raw
        if bock_search.library_search_song_match(
            q, r.get('title'), r.get('album'), artist=r.get('artist'),
            genre=r.get('genre'),
        )
    ][:limit]
    playlists = [
        p for p in playlist_names
        if bock_search.best_match_score(q, p.get('name') or '', allow_substring=True, allow_fuzzy=True) >= 0.35
    ][:limit]
    artist_rows = bock_search.search_artists(db_query, q, limit)
    album_rows = bock_search.search_albums(db_query, q, limit)
    genre_rows = bock_search.search_genres(db_query, q, limit)
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
        'artists': [
            {'name': r.get('artist') or '', 'path': r.get('art_path')}
            for r in artist_rows
        ],
        'albums': [
            {
                'name': r.get('name') or r.get('album') or '',
                'artist': r.get('artist'),
                'path': r.get('path'),
            }
            for r in album_rows
        ],
        'genres': [
            {'name': r.get('genre') or '', 'path': r.get('path')}
            for r in genre_rows
        ],
        'rooms': rooms,
        'smartPlaylists': smart,
    }
