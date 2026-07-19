"""Playlist membership in SQLite — Bock-native source of truth when storage='sql'."""
import os
import time

SCHEMA_MIGRATION = 'playlists_sql_v1'

_DDL = (
    '''
    CREATE TABLE IF NOT EXISTS playlist_sources (
      id            TEXT PRIMARY KEY,
      name          TEXT NOT NULL,
      source_kind   TEXT NOT NULL,
      storage       TEXT NOT NULL DEFAULT 'm3u',
      m3u_path      TEXT,
      track_count   INTEGER NOT NULL DEFAULT 0,
      xml_synced_at REAL,
      updated_at    REAL NOT NULL
    )
    ''',
    'CREATE INDEX IF NOT EXISTS idx_playlist_sources_kind ON playlist_sources(source_kind)',
    'CREATE INDEX IF NOT EXISTS idx_playlist_sources_storage ON playlist_sources(storage)',
    '''
    CREATE TABLE IF NOT EXISTS playlist_tracks (
      playlist_id     TEXT NOT NULL,
      position        INTEGER NOT NULL,
      path            TEXT NOT NULL,
      added_at        REAL,
      added_by_member TEXT,
      PRIMARY KEY (playlist_id, position),
      FOREIGN KEY (playlist_id) REFERENCES playlist_sources(id) ON DELETE CASCADE
    )
    ''',
    'CREATE INDEX IF NOT EXISTS idx_playlist_tracks_path ON playlist_tracks(path)',
    '''
    CREATE UNIQUE INDEX IF NOT EXISTS idx_playlist_tracks_unique
      ON playlist_tracks(playlist_id, path)
    ''',
    '''
    CREATE TABLE IF NOT EXISTS schema_migrations (
      name        TEXT PRIMARY KEY,
      applied_at  REAL NOT NULL
    )
    ''',
)


def ensure_schema(get_db_rw):
    conn = get_db_rw()
    try:
        for sql in _DDL:
            conn.execute(sql)
        row = conn.execute(
            'SELECT 1 FROM schema_migrations WHERE name=?',
            (SCHEMA_MIGRATION,),
        ).fetchone()
        if not row:
            conn.execute(
                'INSERT INTO schema_migrations(name, applied_at) VALUES (?, ?)',
                (SCHEMA_MIGRATION, time.time()),
            )
        conn.commit()
    finally:
        conn.close()


def is_sql_backed(playlist_id, db_one):
    pid = (playlist_id or '').strip()
    if not pid:
        return False
    row = db_one(
        "SELECT 1 FROM playlist_sources WHERE id=? AND storage='sql'",
        [pid],
    )
    return bool(row)


def track_count(playlist_id, db_one):
    row = db_one(
        'SELECT track_count FROM playlist_sources WHERE id=?',
        [(playlist_id or '').strip()],
    )
    if not row:
        return 0
    return int(row.get('track_count') or 0)


def upsert_source(get_db_rw, *, playlist_id, name, source_kind, storage='sql',
                  m3u_path=None, track_count=0, xml_synced_at=None):
    now = time.time()
    conn = get_db_rw()
    try:
        conn.execute(
            '''
            INSERT INTO playlist_sources(id, name, source_kind, storage, m3u_path,
                                         track_count, xml_synced_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
              name=excluded.name,
              source_kind=excluded.source_kind,
              storage=excluded.storage,
              m3u_path=excluded.m3u_path,
              track_count=excluded.track_count,
              xml_synced_at=excluded.xml_synced_at,
              updated_at=excluded.updated_at
            ''',
            [playlist_id, name, source_kind, storage, m3u_path, track_count,
             xml_synced_at, now],
        )
        conn.commit()
    finally:
        conn.close()


def tracks(playlist_id, db_query, *, offset=0, limit=None, order='asc'):
    paths, _ = tracks_page(
        playlist_id, db_query, sort_by='original', order=order,
        offset=offset, limit=limit,
    )
    return paths


def tracks_page(playlist_id, db_query, db_one=None, *, sort_by='original', order='asc',
                offset=0, limit=None, q=''):
    """Paginated playlist paths — SQL join to songs_cache for sort/search."""
    pid = (playlist_id or '').strip()
    if not pid:
        return [], 0
    sort_by = (sort_by or 'original').strip().lower()
    if sort_by in ('track',):
        sort_by = 'title'
    order = (order or 'asc').strip().lower()
    desc = order == 'desc'
    q = (q or '').strip()
    join = ''
    where = 'pt.playlist_id=?'
    params = [pid]

    if sort_by == 'original':
        order_sql = f'pt.position {"DESC" if desc else "ASC"}'
    else:
        join = 'LEFT JOIN songs_cache s ON s.path = pt.path'
        col_map = {
            'title': 'COALESCE(NULLIF(s.title, ""), pt.path)',
            'artist': 'COALESCE(NULLIF(s.artist, ""), "")',
            'album': 'COALESCE(NULLIF(s.album, ""), "")',
            'path': 'pt.path',
        }
        col = col_map.get(sort_by, 'pt.position')
        if col == 'pt.position':
            order_sql = f'pt.position {"DESC" if desc else "ASC"}'
        else:
            order_sql = f'{col} COLLATE NOCASE {"DESC" if desc else "ASC"}, pt.position ASC'

    if q:
        if not join:
            join = 'LEFT JOIN songs_cache s ON s.path = pt.path'
        qc = q.lower()
        pat = f'%{qc}%'
        where += (
            ' AND (LOWER(COALESCE(s.title,"")) LIKE ?'
            ' OR LOWER(COALESCE(s.artist,"")) LIKE ?'
            ' OR LOWER(COALESCE(s.album,"")) LIKE ?'
            ' OR LOWER(pt.path) LIKE ?)'
        )
        params.extend([pat, pat, pat, pat])

    count_sql = f'SELECT COUNT(*) AS n FROM playlist_tracks pt {join} WHERE {where}'
    if db_one:
        total_row = db_one(count_sql, params) or {}
        total = int(total_row.get('n') or 0)
    else:
        rows = db_query(count_sql, params) or []
        total = int((rows[0] if rows else {}).get('n') or 0)

    sql = f'SELECT pt.path FROM playlist_tracks pt {join} WHERE {where} ORDER BY {order_sql}'
    page_params = list(params)
    if limit is not None:
        sql += ' LIMIT ? OFFSET ?'
        page_params.extend([int(limit), int(offset)])
    elif offset:
        sql += ' LIMIT -1 OFFSET ?'
        page_params.append(int(offset))
    rows = db_query(sql, page_params) or []
    return [r['path'] for r in rows if r.get('path')], total


def total_duration_db(playlist_id, db_query):
    """Sum cached duration_seconds for playlist tracks — no per-file probe."""
    pid = (playlist_id or '').strip()
    if not pid:
        return 0
    rows = db_query(
        'SELECT COALESCE(SUM(COALESCE(s.duration_seconds, 0)), 0) AS total '
        'FROM playlist_tracks pt '
        'LEFT JOIN songs_cache s ON s.path = pt.path '
        'WHERE pt.playlist_id=?',
        [pid],
    ) or []
    if not rows:
        return 0
    return int(rows[0].get('total') or 0)


def replace_tracks(get_db_rw, playlist_id, paths, *, added_by_member=None):
    pid = (playlist_id or '').strip()
    norm = []
    seen = set()
    for p in paths or []:
        if not p:
            continue
        np = os.path.normpath(p)
        if np in seen:
            continue
        seen.add(np)
        norm.append(p)
    now = time.time()
    conn = get_db_rw()
    try:
        conn.execute('DELETE FROM playlist_tracks WHERE playlist_id=?', [pid])
        for i, path in enumerate(norm):
            conn.execute(
                'INSERT INTO playlist_tracks(playlist_id, position, path, added_at, added_by_member) '
                'VALUES (?, ?, ?, ?, ?)',
                [pid, i, path, now, added_by_member],
            )
        conn.execute(
            'UPDATE playlist_sources SET track_count=?, updated_at=? WHERE id=?',
            [len(norm), now, pid],
        )
        conn.commit()
    finally:
        conn.close()
    return len(norm)


def append_track(get_db_rw, db_one, playlist_id, path, *, added_by_member=None):
    pid = (playlist_id or '').strip()
    if not pid or not path:
        return False
    np = os.path.normpath(path)
    existing = db_one(
        'SELECT 1 FROM playlist_tracks WHERE playlist_id=? AND path=?',
        [pid, path],
    )
    if existing:
        return False
    count = track_count(pid, db_one)
    now = time.time()
    conn = get_db_rw()
    try:
        conn.execute(
            'INSERT INTO playlist_tracks(playlist_id, position, path, added_at, added_by_member) '
            'VALUES (?, ?, ?, ?, ?)',
            [pid, count, path, now, added_by_member],
        )
        conn.execute(
            'UPDATE playlist_sources SET track_count=?, updated_at=? WHERE id=?',
            [count + 1, now, pid],
        )
        conn.commit()
    finally:
        conn.close()
    return True


def remove_track(get_db_rw, db_query, db_one, playlist_id, path):
    pid = (playlist_id or '').strip()
    if not pid or not path:
        return False
    rows = db_query(
        'SELECT position FROM playlist_tracks WHERE playlist_id=? AND path=?',
        [pid, path],
    )
    if not rows:
        return False
    pos = int(rows[0]['position'])
    conn = get_db_rw()
    try:
        conn.execute(
            'DELETE FROM playlist_tracks WHERE playlist_id=? AND path=?',
            [pid, path],
        )
        conn.execute(
            'UPDATE playlist_tracks SET position=position-1 '
            'WHERE playlist_id=? AND position>?',
            [pid, pos],
        )
        new_count = max(0, track_count(pid, db_one) - 1)
        conn.execute(
            'UPDATE playlist_sources SET track_count=?, updated_at=? WHERE id=?',
            [new_count, time.time(), pid],
        )
        conn.commit()
    finally:
        conn.close()
    return True


def move_track(get_db_rw, db_query, db_one, playlist_id, path, new_index):
    pid = (playlist_id or '').strip()
    count = track_count(pid, db_one)
    if count <= 1:
        return False
    new_index = max(0, min(int(new_index), count - 1))
    paths = tracks(pid, db_query)
    try:
        old_index = paths.index(path)
    except ValueError:
        return False
    if old_index == new_index:
        return True
    paths.pop(old_index)
    paths.insert(new_index, path)
    replace_tracks(get_db_rw, pid, paths)
    return True


def import_from_m3u(get_db_rw, playlist_id, name, m3u_path, paths, *, source_kind='bockmedia'):
    upsert_source(
        get_db_rw,
        playlist_id=playlist_id,
        name=name,
        source_kind=source_kind,
        storage='sql',
        m3u_path=m3u_path,
        track_count=len(paths),
        xml_synced_at=time.time(),
    )
    return replace_tracks(get_db_rw, playlist_id, paths)


def export_to_m3u(m3u_path, paths, write_m3u_fn):
    """Write paths to m3u via caller-supplied write_m3u_fn(path, paths)."""
    write_m3u_fn(m3u_path, paths)
