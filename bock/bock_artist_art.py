"""Artist portrait lookup — free sources only (Deezer, iTunes, library fallback)."""
import hashlib
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

_ITUNES_SEARCH_URL = 'https://itunes.apple.com/search'
_DEEZER_SEARCH_URL = 'https://api.deezer.com/search/artist'
_TIMEOUT = 6

_neg_cache = set()  # normalized artist names with no remote portrait


def _norm_artist(name):
    if not name:
        return ''
    s = re.sub(r'\s+', ' ', str(name).strip().lower())
    s = re.sub(r'[^\w\s&\-\']', '', s)
    return s


def _strip_the(name):
    return re.sub(r'^the\s+', '', name or '', flags=re.I)


def _artist_key(name):
    return _strip_the(_norm_artist(name))


def config(load_config_fn):
    cfg = load_config_fn() or {}
    aa = cfg.get('artistArt') or {}
    ac = cfg.get('acquire') or {}
    enabled = aa.get('enabled')
    if enabled is None:
        enabled = True
    ua = (aa.get('userAgent') or ac.get('userAgent')
          or 'BockMedia/1.0 ( https://github.com/bockmedia )').strip()
    return {'enabled': bool(enabled), 'userAgent': ua}


def portrait_cache_file(artwork_cache_dir, artist_name):
    key = _artist_key(artist_name) or _norm_artist(artist_name)
    h = hashlib.sha1(key.encode('utf-8')).hexdigest()
    return os.path.join(artwork_cache_dir, f'artist-portrait-{h}.jpg')


def portrait_rel_path(abs_path, artwork_cache_dir):
    base = os.path.basename(abs_path)
    return f'artwork_cache/{base}'


def _http_json(url, user_agent):
    req = Request(url, headers={'User-Agent': user_agent, 'Accept': 'application/json'})
    with urlopen(req, timeout=_TIMEOUT) as resp:
        return json.loads(resp.read().decode('utf-8', errors='replace'))


def _download_url(url, dest, user_agent):
    os.makedirs(os.path.dirname(dest) or '.', exist_ok=True)
    req = Request(url, headers={'User-Agent': user_agent})
    with urlopen(req, timeout=_TIMEOUT) as resp:
        blob = resp.read()
    if not blob or len(blob) < 512:
        return False
    with open(dest, 'wb') as fh:
        fh.write(blob)
    return os.path.isfile(dest)


def _itunes_portrait_url(name, user_agent):
    try:
        url = f'{_ITUNES_SEARCH_URL}?' + urlencode({
            'term': name, 'entity': 'musicArtist', 'limit': 5, 'media': 'music',
        })
        data = _http_json(url, user_agent)
    except (HTTPError, URLError, json.JSONDecodeError, TimeoutError, OSError):
        return None
    results = data.get('results') or []
    if not results:
        return None
    target = _artist_key(name)
    pick = results[0]
    for item in results:
        if _artist_key(item.get('artistName') or item.get('collectionArtistName')) == target:
            pick = item
            break
    art = (pick.get('artworkUrl100') or pick.get('artworkUrl60') or '').strip()
    if not art:
        return None
    return re.sub(r'/\d+x\d+(bb)?\.', '/1000x1000bb.', art)


def _deezer_portrait_url(name, user_agent):
    try:
        url = f'{_DEEZER_SEARCH_URL}?' + urlencode({'q': name, 'limit': 5})
        data = _http_json(url, user_agent)
    except (HTTPError, URLError, json.JSONDecodeError, TimeoutError, OSError):
        return None
    items = data.get('data') or []
    if not items:
        return None
    target = _artist_key(name)
    pick = items[0]
    for item in items:
        if _artist_key(item.get('name')) == target:
            pick = item
            break
    for key in ('picture_xl', 'picture_big', 'picture_medium', 'picture'):
        url = (pick.get(key) or '').strip()
        if url:
            return url
    return None


def _library_album_art_path(artist_name, db_query):
    if not db_query:
        return None
    rows = db_query(
        'SELECT art_path FROM albums_agg WHERE artist = ? AND art_path IS NOT NULL '
        'AND art_path != "" ORDER BY track_count DESC LIMIT 1',
        [artist_name],
    ) or []
    if rows:
        path = (rows[0].get('art_path') or '').strip()
        return path or None
    rows = db_query(
        'SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL '
        'AND path != "" ORDER BY RANDOM() LIMIT 1',
        [artist_name],
    ) or []
    if rows:
        return (rows[0].get('path') or '').strip() or None
    return None


def resolve_portrait(
    artist_name,
    artwork_cache_dir,
    load_config_fn,
    db_query=None,
    find_artwork_fn=None,
):
    """Return dict with art_path (library-relative), source, cached — or None if disabled/missing."""
    name = (artist_name or '').strip()
    if not name:
        return None
    cfg = config(load_config_fn)
    if not cfg['enabled']:
        return None

    cache_file = portrait_cache_file(artwork_cache_dir, name)
    if os.path.isfile(cache_file):
        return {
            'artist': name,
            'art_path': portrait_rel_path(cache_file, artwork_cache_dir),
            'source': 'cache',
            'cached': True,
        }

    key = _artist_key(name) or _norm_artist(name)
    if key in _neg_cache:
        lib = _library_album_art_path(name, db_query)
        if lib:
            return {'artist': name, 'art_path': lib, 'source': 'library', 'cached': False}
        return None

    ua = cfg['userAgent']
    sources = []
    deezer_url = _deezer_portrait_url(name, ua)
    if deezer_url:
        sources.append(('deezer', deezer_url))
    itunes_url = _itunes_portrait_url(name, ua)
    if itunes_url:
        sources.append(('itunes', itunes_url))

    for source, url in sources:
        try:
            if _download_url(url, cache_file, ua):
                return {
                    'artist': name,
                    'art_path': portrait_rel_path(cache_file, artwork_cache_dir),
                    'source': source,
                    'cached': True,
                }
        except (HTTPError, URLError, TimeoutError, OSError):
            continue

    _neg_cache.add(key)
    lib = _library_album_art_path(name, db_query)
    if lib and find_artwork_fn:
        resolved = find_artwork_fn(lib)
        if resolved and os.path.isfile(resolved):
            try:
                os.makedirs(artwork_cache_dir, exist_ok=True)
                with open(resolved, 'rb') as src, open(cache_file, 'wb') as dst:
                    dst.write(src.read())
                return {
                    'artist': name,
                    'art_path': portrait_rel_path(cache_file, artwork_cache_dir),
                    'source': 'library',
                    'cached': True,
                }
            except OSError:
                pass
        if lib:
            return {'artist': name, 'art_path': lib, 'source': 'library', 'cached': False}
    elif lib:
        return {'artist': name, 'art_path': lib, 'source': 'library', 'cached': False}
    return None


def cached_portrait_rel_path(artist_name, artwork_cache_dir):
    """Disk-only check for list endpoints — no network."""
    cache_file = portrait_cache_file(artwork_cache_dir, artist_name)
    if os.path.isfile(cache_file):
        return portrait_rel_path(cache_file, artwork_cache_dir)
    return None
