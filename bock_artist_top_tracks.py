"""Artist popular tracks — Spotify Web API (preferred), Deezer fallback, local plays last."""
import base64
import difflib
import json
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

import bock_artist_art

_DEEZER_SEARCH = 'https://api.deezer.com/search/artist'
_DEEZER_TOP = 'https://api.deezer.com/artist/{artist_id}/top'
_SPOTIFY_TOKEN = 'https://accounts.spotify.com/api/token'
_SPOTIFY_API = 'https://api.spotify.com/v1'
_TIMEOUT = 3
_LIBRARY_ROW_LIMIT = 400

_token_cache = {}  # client_id -> (expires_at, access_token)
_ref_cache = {}  # (source, artist_key, limit) -> (saved_at, refs)
_REF_TTL = 6 * 3600

_LIVE_RE = re.compile(r'\b(live|unplugged|acoustic|demo|remix)\b', re.I)
_SUFFIX_RE = re.compile(
    r'\s*[\(\[][^\)\]]*(?:remaster|live|version|mix|edit|mono|stereo|bonus)[^\)\]]*[\)\]]',
    re.I,
)


def _norm_title(title):
    s = re.sub(r'\s+', ' ', (title or '').strip().lower())
    s = _SUFFIX_RE.sub('', s)
    s = re.sub(r'\s*-\s*(radio edit|single version|album version)$', '', s, flags=re.I)
    return s.strip()


def _artist_key(name):
    return bock_artist_art._artist_key(name)


def _http_json(url, user_agent, headers=None, data=None, method='GET'):
    hdrs = {'User-Agent': user_agent, 'Accept': 'application/json'}
    if headers:
        hdrs.update(headers)
    req = Request(url, headers=hdrs, data=data, method=method)
    with urlopen(req, timeout=_TIMEOUT) as resp:
        return json.loads(resp.read().decode('utf-8', errors='replace'))


def _credential_usable(value):
    v = (value or '').strip()
    if not v:
        return False
    if v.upper().startswith('SET_'):
        return False
    return True


def spotify_config(load_config_fn):
    cfg = load_config_fn() or {}
    sp = cfg.get('spotify') or {}
    cid = (sp.get('clientId') or '').strip()
    secret = (sp.get('clientSecret') or '').strip()
    market = (sp.get('market') or 'US').strip() or 'US'
    enabled = sp.get('enabled', True)
    return {
        'enabled': bool(enabled and _credential_usable(cid) and _credential_usable(secret)),
        'clientId': cid,
        'clientSecret': secret,
        'market': market,
    }


def _spotify_access_token(cfg):
    cid = cfg['clientId']
    now = time.time()
    cached = _token_cache.get(cid)
    if cached and cached[0] > now + 30:
        return cached[1]
    auth = base64.b64encode(f"{cid}:{cfg['clientSecret']}".encode()).decode()
    body = urlencode({'grant_type': 'client_credentials'}).encode()
    data = _http_json(
        _SPOTIFY_TOKEN,
        'BockMedia/1.0',
        headers={
            'Authorization': f'Basic {auth}',
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        data=body,
        method='POST',
    )
    token = (data.get('access_token') or '').strip()
    if not token:
        return None
    expires = now + int(data.get('expires_in') or 3600)
    _token_cache[cid] = (expires, token)
    return token


def _pick_artist_item(items, artist_name):
    if not items:
        return None
    target = _artist_key(artist_name)
    for item in items:
        if _artist_key(item.get('name')) == target:
            return item
    return items[0]


def _fetch_spotify_refs(artist_name, limit, load_config_fn):
    cfg = spotify_config(load_config_fn)
    if not cfg['enabled']:
        return None
    token = _spotify_access_token(cfg)
    if not token:
        return None
    headers = {'Authorization': f'Bearer {token}'}
    try:
        search_url = (
            f'{_SPOTIFY_API}/search?{urlencode({"q": artist_name, "type": "artist", "limit": 5})}'
        )
        search = _http_json(search_url, 'BockMedia/1.0', headers=headers)
        items = ((search.get('artists') or {}).get('items') or [])
        pick = _pick_artist_item(items, artist_name)
        if not pick or not pick.get('id'):
            return None
        top_url = (
            f'{_SPOTIFY_API}/artists/{quote(str(pick["id"]))}/top-tracks'
            f'?{urlencode({"market": cfg["market"]})}'
        )
        top = _http_json(top_url, 'BockMedia/1.0', headers=headers)
        refs = []
        for track in (top.get('tracks') or [])[:limit]:
            title = (track.get('name') or '').strip()
            if not title:
                continue
            album = ((track.get('album') or {}).get('name') or '').strip() or None
            refs.append({'title': title, 'album': album})
        return refs or None
    except (HTTPError, URLError, json.JSONDecodeError, TimeoutError, OSError, KeyError, TypeError):
        return None


def _fetch_deezer_refs(artist_name, limit, load_config_fn):
    aa = bock_artist_art.config(load_config_fn)
    ua = aa['userAgent']
    try:
        search_url = f'{_DEEZER_SEARCH}?{urlencode({"q": artist_name, "limit": 5})}'
        search = _http_json(search_url, ua)
        items = search.get('data') or []
        pick = _pick_artist_item(items, artist_name)
        if not pick or not pick.get('id'):
            return None
        top_url = _DEEZER_TOP.format(artist_id=pick['id'])
        top = _http_json(top_url, ua)
        refs = []
        for track in (top.get('data') or [])[:limit]:
            title = (track.get('title') or '').strip()
            if not title:
                continue
            album = ((track.get('album') or {}).get('title') or '').strip() or None
            refs.append({'title': title, 'album': album})
        return refs or None
    except (HTTPError, URLError, json.JSONDecodeError, TimeoutError, OSError, KeyError, TypeError):
        return None


def _cache_get(source, artist_name, limit):
    key = (source, _artist_key(artist_name) or artist_name.lower(), limit)
    entry = _ref_cache.get(key)
    if not entry:
        return None
    saved_at, refs = entry
    if time.time() - saved_at > _REF_TTL:
        _ref_cache.pop(key, None)
        return None
    return refs


def _cache_put(source, artist_name, limit, refs):
    key = (source, _artist_key(artist_name) or artist_name.lower(), limit)
    _ref_cache[key] = (time.time(), refs)


def fetch_external_top_refs(artist_name, limit, load_config_fn):
    """Return (refs, source) where source is spotify, deezer, or None."""
    limit = min(max(int(limit or 10), 1), 50)
    cached = _cache_get('spotify', artist_name, limit)
    if cached:
        return cached, 'spotify'
    refs = _fetch_spotify_refs(artist_name, limit, load_config_fn)
    if refs:
        _cache_put('spotify', artist_name, limit, refs)
        return refs, 'spotify'
    cached = _cache_get('deezer', artist_name, limit)
    if cached:
        return cached, 'deezer'
    refs = _fetch_deezer_refs(artist_name, limit, load_config_fn)
    if refs:
        _cache_put('deezer', artist_name, limit, refs)
        return refs, 'deezer'
    return None, None


def _library_rows_for_artist(artist, db_query, limit=_LIBRARY_ROW_LIMIT):
    limit = min(max(int(limit or _LIBRARY_ROW_LIMIT), 1), _LIBRARY_ROW_LIMIT)
    return db_query(
        'SELECT id, title, artist, album, genre, year, duration_seconds, '
        'track_number, path FROM songs_cache '
        'WHERE path IS NOT NULL AND path != "" AND '
        '(artist = ? OR album_artist = ? OR LOWER(TRIM(artist)) = LOWER(?)) '
        'ORDER BY title COLLATE NOCASE ASC LIMIT ?',
        [artist, artist, artist, limit],
    ) or []


def _score_match(ref_title, ref_album, row):
    title = _norm_title(row.get('title'))
    target = _norm_title(ref_title)
    if not title or not target:
        return 0.0
    if title == target:
        score = 1.0
    else:
        score = difflib.SequenceMatcher(None, target, title).ratio()
        if score < 0.78:
            return 0.0
    row_title = row.get('title') or ''
    if _LIVE_RE.search(row_title) and not _LIVE_RE.search(ref_title or ''):
        score -= 0.25
    if ref_album and row.get('album'):
        a = _norm_title(ref_album)
        b = _norm_title(row['album'])
        if a == b or a in b or b in a:
            score += 0.12
    return score


def match_refs_to_library(artist, refs, db_query, enrich_fn, member=''):
    rows = _library_rows_for_artist(artist, db_query)
    if not rows or not refs:
        return []
    by_path = {r.get('path'): r for r in rows if r.get('path')}
    used = set()
    ordered_paths = []
    for ref in refs:
        best_path = None
        best_score = 0.0
        for row in rows:
            path = row.get('path')
            if not path or path in used:
                continue
            score = _score_match(ref.get('title'), ref.get('album'), row)
            if score > best_score:
                best_score = score
                best_path = path
        if best_path and best_score >= 0.78:
            used.add(best_path)
            ordered_paths.append(best_path)
    if not ordered_paths:
        return []
    enriched = {r.get('path'): r for r in enrich_fn([by_path[p] for p in ordered_paths]) if r.get('path')}
    out = []
    for path in ordered_paths:
        item = dict(enriched.get(path) or by_path.get(path) or {})
        if not item.get('path'):
            continue
        item['playCount'] = 0
        out.append(item)
    return out


def local_top_by_plays(artist, db_query, enrich_fn, member='', limit=10):
    limit = min(max(int(limit or 10), 1), 50)
    rows = db_query(
        'SELECT id, title, artist, album, genre, year, duration_seconds, '
        'track_number, path FROM songs_cache '
        'WHERE path IS NOT NULL AND path != "" AND artist = ? '
        'ORDER BY title COLLATE NOCASE ASC LIMIT ?',
        [artist, min(limit * 8, _LIBRARY_ROW_LIMIT)],
    ) or []
    items = enrich_fn(rows)
    items.sort(key=lambda x: (-int(x.get('playCount') or 0), (x.get('title') or '').lower()))
    seen = set()
    out = []
    for item in items:
        path = item.get('path')
        if not path or path in seen:
            continue
        seen.add(path)
        out.append(item)
        if len(out) >= limit:
            break
    return out


def resolve_artist_top_tracks(artist, db_query, enrich_fn, member='', limit=10, load_config_fn=None):
    """Popular tracks for artist page — Spotify/Deezer chart order mapped to library files."""
    limit = min(max(int(limit or 10), 1), 50)
    load_config_fn = load_config_fn or (lambda: {})
    refs, source = fetch_external_top_refs(artist, max(limit, 10), load_config_fn)
    if refs:
        matched = match_refs_to_library(artist, refs, db_query, enrich_fn, member)
        if matched:
            return matched[:limit], source or 'external'
    return local_top_by_plays(artist, db_query, enrich_fn, member, limit), 'local'
