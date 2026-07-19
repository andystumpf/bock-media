"""Library additions — tracks/albums recently added to the local index."""
import datetime


def parse_since_days(since, default=7):
    since = (since or f'{default}d').strip().lower()
    days = default
    if since.endswith('d') and since[:-1].isdigit():
        days = int(since[:-1])
    return max(days, 1)


def cutoff_date(days):
    return (datetime.datetime.now() - datetime.timedelta(days=days)).strftime('%Y-%m-%d')


def _artist_filter_clause(artists):
    """Return (sql_fragment, params) for case-insensitive artist IN filter."""
    lowers = sorted({(a or '').strip().lower() for a in (artists or []) if (a or '').strip()})
    if not lowers:
        return 'AND 0', []
    placeholders = ','.join('?' * len(lowers))
    return f'AND LOWER(artist) IN ({placeholders})', lowers


def library_new_payload(db_query, *, since='7d', limit=50, artists=None, after=None):
    """Return {since, tracks, albums, playlists} for library additions."""
    days = parse_since_days(since)
    limit = min(max(int(limit or 50), 1), 200)
    cutoff = cutoff_date(days)
    artist_sql, artist_params = _artist_filter_clause(artists)
    after_sql = ''
    after_params = []
    if after:
        after_sql = 'AND first_seen_at > ?'
        after_params = [after]

    tracks = db_query(
        'SELECT title, artist, album, path, first_seen_at FROM songs_cache '
        f'WHERE first_seen_at >= ? AND path IS NOT NULL {artist_sql} {after_sql} '
        'ORDER BY first_seen_at DESC LIMIT ?',
        [cutoff, *artist_params, *after_params, limit],
    ) or []
    albums = db_query(
        'SELECT album, artist, MIN(path) as path, MIN(first_seen_at) as first_seen_at '
        f'FROM songs_cache WHERE first_seen_at >= ? AND album != "" {artist_sql} {after_sql} '
        'GROUP BY album, artist ORDER BY first_seen_at DESC LIMIT ?',
        [cutoff, *artist_params, *after_params, limit],
    ) or []
    return {
        'since': since if isinstance(since, str) else f'{days}d',
        'tracks': tracks,
        'albums': albums,
        'playlists': [],
    }
