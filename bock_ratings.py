"""User star ratings (1–5) for songs, albums, and playlists — per household member."""
import json
import os
import threading
import time

_RATINGS_LOCK = threading.Lock()
_LEGACY_MEMBER = ''


def ratings_path(here):
    return os.path.join(here, 'ratings.json')


def _normalize_doc(raw):
    if not isinstance(raw, dict):
        return {'members': {}}
    if isinstance(raw.get('members'), dict):
        return {'members': raw['members']}
    items = raw.get('items')
    if isinstance(items, dict) and items:
        return {'members': {_LEGACY_MEMBER: {'items': items}}}
    return {'members': {}}


def _load_doc(path):
    with _RATINGS_LOCK:
        try:
            with open(path, encoding='utf-8') as fh:
                return _normalize_doc(json.load(fh))
        except OSError:
            return {'members': {}}
        except json.JSONDecodeError:
            return {'members': {}}


def _member_bucket(doc, member_id):
    members = doc.setdefault('members', {})
    mid = (member_id or _LEGACY_MEMBER).strip()
    row = members.get(mid)
    if not isinstance(row, dict):
        row = {'items': {}}
        members[mid] = row
    if not isinstance(row.get('items'), dict):
        row['items'] = {}
    return row


def _member_items(doc, member_id):
    return _member_bucket(doc, member_id)['items']


def _save_doc(path, doc, atomic_write):
    payload = {'members': doc.get('members') or {}}
    with _RATINGS_LOCK:
        if atomic_write:
            atomic_write(path, payload)
        else:
            os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
            with open(path, 'w', encoding='utf-8') as fh:
                json.dump(payload, fh, indent=2)


def migrate_legacy_to_member(path, member_id, atomic_write=None):
    """Move household-wide legacy ratings to a member once (first profile use)."""
    mid = (member_id or '').strip()
    if not mid:
        return False
    doc = _load_doc(path)
    legacy = _member_items(doc, _LEGACY_MEMBER)
    if not legacy:
        return False
    if _member_items(doc, mid):
        return False
    _member_bucket(doc, mid)['items'] = dict(legacy)
    doc['members'].pop(_LEGACY_MEMBER, None)
    _save_doc(path, doc, atomic_write)
    return True


def rating_key(kind, item_id):
    kind = (kind or '').strip().lower()
    item_id = (item_id or '').strip()
    if not kind or not item_id:
        return None
    return f'{kind}:{item_id}'


def list_ratings(path, member_id=''):
    items = _member_items(_load_doc(path), member_id)
    out = []
    for key, row in items.items():
        if not isinstance(row, dict):
            continue
        stars = int(row.get('stars') or 0)
        if stars < 1:
            continue
        out.append({
            'kind': row.get('kind') or key.split(':', 1)[0],
            'id': row.get('id') or (key.split(':', 1)[1] if ':' in key else key),
            'stars': stars,
            'title': row.get('title'),
            'artist': row.get('artist'),
            'album': row.get('album'),
            'updatedAt': row.get('updatedAt'),
        })
    out.sort(key=lambda r: float(r.get('updatedAt') or 0), reverse=True)
    return out


def rated_playlist_id(stars):
    stars = int(stars or 0)
    if stars < 1 or stars > 5:
        raise ValueError('stars must be 1–5')
    return f'rated-stars-{stars}'


def parse_rated_playlist_id(playlist_id):
    pid = (playlist_id or '').strip()
    if not pid.startswith('rated-stars-'):
        return None
    try:
        stars = int(pid.rsplit('-', 1)[-1])
    except ValueError:
        return None
    return stars if 1 <= stars <= 5 else None


def songs_at_stars(path, stars, member_id='', kind='song'):
    stars = int(stars or 0)
    return [
        r for r in list_ratings(path, member_id)
        if (r.get('kind') or kind) == kind and int(r.get('stars') or 0) == stars
    ]


def rated_playlist_name(stars):
    n = int(stars)
    label = 'star' if n == 1 else 'stars'
    return f'{n} {label}'


def rated_playlist_detail(path, stars, page=1, limit=100, sort_by='title', order='asc', q='',
                          member_id=''):
    songs = songs_at_stars(path, stars, member_id=member_id)
    pid = rated_playlist_id(stars)
    name = rated_playlist_name(stars)
    if sort_by == 'updated':
        songs.sort(
            key=lambda r: float(r.get('updatedAt') or 0),
            reverse=(order == 'desc'),
        )
    else:
        songs.sort(
            key=lambda r: (r.get('title') or r.get('id') or '').lower(),
            reverse=(order == 'desc'),
        )
    if q:
        ql = q.lower()
        songs = [
            r for r in songs
            if ql in (r.get('title') or '').lower()
            or ql in (r.get('artist') or '').lower()
            or ql in (r.get('album') or '').lower()
            or ql in (r.get('id') or '').lower()
        ]
    total = len(songs)
    start = max(0, (max(1, int(page)) - 1) * max(1, int(limit)))
    page_rows = songs[start:start + min(max(1, int(limit)), 500)]
    tracks = []
    for row in page_rows:
        path_val = row.get('id') or ''
        tracks.append({
            'path': path_val,
            'title': row.get('title'),
            'artist': row.get('artist'),
            'album': row.get('album'),
            'stars': row.get('stars'),
        })
    return {
        'id': pid,
        'name': name,
        'source': None,
        'tracks': tracks,
        'total': total,
        'page': max(1, int(page)),
        'limit': min(max(1, int(limit)), 500),
        'sortBy': sort_by,
        'order': order,
        'q': q or None,
        'rated': True,
        'stars': int(stars),
    }


def get_rating(path, kind, item_id, member_id=''):
    key = rating_key(kind, item_id)
    if not key:
        return 0
    row = _member_items(_load_doc(path), member_id).get(key) or {}
    return max(0, min(5, int(row.get('stars') or 0)))


def set_rating(path, kind, item_id, stars, atomic_write, title=None, artist=None, album=None,
               member_id=''):
    kind = (kind or '').strip().lower()
    item_id = (item_id or '').strip()
    if kind not in ('song', 'album', 'playlist'):
        raise ValueError('kind must be song, album, or playlist')
    if not item_id:
        raise ValueError('id required')
    stars = int(stars or 0)
    if stars < 0 or stars > 5:
        raise ValueError('stars must be 0–5')
    key = rating_key(kind, item_id)
    doc = _load_doc(path)
    items = _member_items(doc, member_id)
    if stars == 0:
        items.pop(key, None)
    else:
        items[key] = {
            'kind': kind,
            'id': item_id,
            'stars': stars,
            'title': (title or '').strip() or None,
            'artist': (artist or '').strip() or None,
            'album': (album or '').strip() or None,
            'updatedAt': time.time(),
        }
    _save_doc(path, doc, atomic_write)
    return items.get(key)
