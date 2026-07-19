"""albums_agg / genres_agg rebuild — shared by server.py and maintenance scripts.

Builds the new tables first, then swaps them in inside one transaction so live
readers never observe a dropped/missing table (the old DROP-then-CREATE pattern
briefly 404'd /api/albums during rebuilds).
"""

_ALBUMS_AGG_SELECT = '''
    SELECT album,
           COALESCE(NULLIF(album_artist, ''), artist) AS artist,
           COUNT(*) AS track_count,
           MAX(CAST(NULLIF(year, '') AS INTEGER)) AS year,
           MIN(CASE WHEN path IS NOT NULL AND path != '' THEN path END) AS art_path,
           MIN(first_seen_at) AS first_seen_at
    FROM songs_cache
    WHERE album IS NOT NULL AND album != ''
    GROUP BY album, COALESCE(NULLIF(album_artist, ''), artist)
'''

_GENRES_AGG_SELECT = '''
    SELECT genre,
           MIN(CASE WHEN path IS NOT NULL AND path != '' THEN path END) AS path,
           COUNT(*) AS track_count
    FROM songs_cache
    WHERE genre IS NOT NULL AND genre != ''
    GROUP BY genre
'''


def rebuild(conn):
    """Rebuild both aggregate tables from songs_cache on an open RW connection."""
    # Heavy scans happen on side tables outside the swap transaction.
    conn.execute('DROP TABLE IF EXISTS albums_agg_new')
    conn.execute('DROP TABLE IF EXISTS genres_agg_new')
    conn.execute(f'CREATE TABLE albums_agg_new AS {_ALBUMS_AGG_SELECT}')
    conn.execute(f'CREATE TABLE genres_agg_new AS {_GENRES_AGG_SELECT}')
    if conn.in_transaction:
        conn.commit()
    conn.execute('BEGIN IMMEDIATE')
    try:
        conn.execute('DROP TABLE IF EXISTS albums_agg')
        conn.execute('ALTER TABLE albums_agg_new RENAME TO albums_agg')
        conn.execute('DROP TABLE IF EXISTS genres_agg')
        conn.execute('ALTER TABLE genres_agg_new RENAME TO genres_agg')
        conn.execute('CREATE INDEX idx_albums_agg_album ON albums_agg(album COLLATE NOCASE)')
        conn.execute('CREATE INDEX idx_albums_agg_artist ON albums_agg(artist COLLATE NOCASE)')
        conn.execute('CREATE INDEX idx_genres_agg_genre ON genres_agg(genre COLLATE NOCASE)')
        conn.commit()
    except Exception:
        conn.rollback()
        raise
