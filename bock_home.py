"""Unified /api/home payload builder — aggregates home refresh data in one call."""
import time
from collections import Counter

import bock_home_defaults


_HOME_CACHE: dict = {}
_HOME_CACHE_TTL = 45.0


def _cache_key(member: str, history_mtime: float, deferred: bool, defaults_version: int, *,
               household_mtime: float = 0.0, with_ratings: bool = False) -> str:
    return (f'{member or ""}|{history_mtime:.3f}|{household_mtime:.3f}|{"d" if deferred else "f"}|'
            f'hd{defaults_version}|{"r" if with_ratings else ""}')


def bust_home_cache():
    _HOME_CACHE.clear()


def listening_summary(rows, db_query, *, limit=12):
    """Lightweight top artists/genres for home compose — avoids a separate /api/analytics call."""
    artist_ctr = Counter()
    paths = []
    for row in rows or []:
        if row.get('test'):
            continue
        artist = (row.get('artist') or '').strip()
        if artist and artist.lower() != 'unknown':
            artist_ctr[artist] += 1
        fp = row.get('filepath') or row.get('path')
        if fp:
            paths.append(fp)
    genre_ctr = Counter()
    unique_paths = list(dict.fromkeys(paths))
    for i in range(0, len(unique_paths), 900):
        chunk = unique_paths[i:i + 900]
        if not chunk:
            continue
        ph = ','.join(['?'] * len(chunk))
        for dbr in db_query(f'SELECT path, genre FROM songs_cache WHERE path IN ({ph})', chunk) or []:
            g = (dbr.get('genre') or '').strip()
            if g:
                genre_ctr[g] += 1
    return {
        'topArtists': [{'name': k, 'count': v} for k, v in artist_ctr.most_common(limit)],
        'topGenres': [{'name': k, 'count': v} for k, v in genre_ctr.most_common(limit)],
    }


def build_home_payload(
    *,
    member: str,
    history_mtime: float,
    deferred: bool,
    read_stream_history,
    filter_history_rows,
    load_favorites,
    load_smart_playlists,
    load_playlist_summaries,
    load_recently_created_playlists=None,
    load_genres,
    library_new,
    discover_weekly,
    continue_listening,
    followed_library_new=None,
    analytics_payload=None,
    ratings_items=None,
    listening_summary_payload=None,
    db_query=None,
    playlist_limit: int = 500,
    genre_limit: int = 40,
    history_limit: int = 150,
    home_defaults=None,
    filter_history_for_member=None,
    household_mtime: float = 0.0,
):
    """Return dict matching client HomeFeedLoader inputs."""
    defaults = home_defaults or {}
    policy = defaults.get('policy') or bock_home_defaults.DEFAULT_POLICY
    playlist_scope = (policy.get('playlistsScope') or 'household').strip().lower()
    playlist_member = '' if playlist_scope == 'household' else member
    pl_limit = max(int(playlist_limit or 500), int(policy.get('playlistLimit') or 500))
    g_limit = max(int(genre_limit or 40), int(policy.get('genreLimit') or 40))
    pl_limit = min(pl_limit, 2000)
    g_limit = min(g_limit, 200)
    defaults_version = int(defaults.get('version') or 0)

    cache_key = _cache_key(member, history_mtime, deferred, defaults_version,
                           household_mtime=household_mtime,
                           with_ratings=ratings_items is not None)
    hit = _HOME_CACHE.get(cache_key)
    if hit and time.monotonic() < hit[0]:
        return hit[1]

    rows = [r for r in read_stream_history() if not r.get('test')]
    if member and filter_history_for_member:
        rows = filter_history_for_member(rows, member)
    rows.reverse()
    history_items = rows[:history_limit]

    summary_payload = listening_summary_payload
    if summary_payload is None and db_query is not None:
        summary_payload = listening_summary(rows, db_query)

    seen = set()
    recent = []
    for row in rows:
        key = row.get('filepath') or f"{row.get('track')}|{row.get('artist')}"
        if key in seen:
            continue
        seen.add(key)
        item = {
            'track': row.get('track'),
            'artist': row.get('artist'),
            'album': row.get('album'),
            'filepath': row.get('filepath'),
            'device': row.get('device'),
            'date': row.get('date') or row.get('timestamp'),
        }
        if row.get('playlist'):
            item['playlist'] = row.get('playlist')
        recent.append(item)
        if len(recent) >= 5:
            break

    payload = {
        'history': {'items': history_items, 'total': len(rows)},
        'dashboard': {'recent': recent, 'favorites': load_favorites()[:20]},
        'playlists': load_playlist_summaries(member=playlist_member, limit=pl_limit),
        'recentlyCreatedPlaylists': (
            load_recently_created_playlists(member=playlist_member, limit=10)
            if load_recently_created_playlists else {'items': [], 'total': 0}
        ),
        'smartPlaylists': {'items': load_smart_playlists()},
        'genres': load_genres(limit=g_limit),
        'libraryNew': library_new(),
        'followedLibraryNew': (followed_library_new() if followed_library_new else None),
        'discoverWeekly': discover_weekly(member=member),
        'continue': continue_listening(member=member),
        'homeDefaults': bock_home_defaults.pins_for_clients(defaults),
    }
    if not deferred and analytics_payload is not None:
        payload['analytics'] = analytics_payload
    if ratings_items is not None:
        payload['ratings'] = {'items': ratings_items}
    if listening_summary_payload is not None:
        payload['listeningSummary'] = listening_summary_payload
    elif summary_payload is not None:
        payload['listeningSummary'] = summary_payload

    _HOME_CACHE[cache_key] = (time.monotonic() + _HOME_CACHE_TTL, payload)
    if len(_HOME_CACHE) > 32:
        for k in list(_HOME_CACHE.keys())[:8]:
            _HOME_CACHE.pop(k, None)
    return payload
