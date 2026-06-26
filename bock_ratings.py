"""User star ratings (1–5) for songs, albums, and playlists."""
import json
import os
import threading
import time

_RATINGS_LOCK = threading.Lock()


def ratings_path(here):
    return os.path.join(here, 'ratings.json')


def _load(path):
    with _RATINGS_LOCK:
        try:
            with open(path, encoding='utf-8') as fh:
                data = json.load(fh)
            items = data.get('items') if isinstance(data, dict) else None
            return items if isinstance(items, dict) else {}
        except OSError:
            return {}
        except json.JSONDecodeError:
            return {}


def _save(path, items, atomic_write):
    payload = {'items': items}
    with _RATINGS_LOCK:
        if atomic_write:
            atomic_write(path, payload)
        else:
            os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
            with open(path, 'w', encoding='utf-8') as fh:
                json.dump(payload, fh, indent=2)


def rating_key(kind, item_id):
    kind = (kind or '').strip().lower()
    item_id = (item_id or '').strip()
    if not kind or not item_id:
        return None
    return f'{kind}:{item_id}'


def list_ratings(path):
    items = _load(path)
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


def get_rating(path, kind, item_id):
    key = rating_key(kind, item_id)
    if not key:
        return 0
    row = _load(path).get(key) or {}
    return max(0, min(5, int(row.get('stars') or 0)))


def set_rating(path, kind, item_id, stars, atomic_write, title=None, artist=None, album=None):
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
    items = _load(path)
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
    _save(path, items, atomic_write)
    return items.get(key)
