"""Acquire ideas — MusicBrainz suggestions for artists not in your library."""
import json
import os
import random
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import bock_resonance

MB_BASE = 'https://musicbrainz.org/ws/2'
CACHE_TTL_SEC = 7 * 24 * 3600
_REL_TYPES = {
    'member of band', 'parent', 'collaboration', 'influenced by',
    'supporting musician', 'associated with', 'is person',
}
_mb_fetch = None  # injectable for tests: fn(path) -> dict|None
_last_mb_at = 0.0


def _norm_artist(name):
    if not name:
        return ''
    s = re.sub(r'\s+', ' ', str(name).strip().lower())
    s = re.sub(r'[^\w\s&\-\']', '', s)
    return s


def _strip_the(name):
    return re.sub(r'^the\s+', '', name or '', flags=re.I)


def _artist_key(name):
    """Normalized artist name for equality checks (handles leading \"The\")."""
    return _strip_the(_norm_artist(name))


def owned_artists(db_query):
    rows = db_query(
        'SELECT DISTINCT LOWER(TRIM(artist)) AS a FROM songs_cache '
        'WHERE artist IS NOT NULL AND TRIM(artist) != ""'
    ) or []
    owned = set()
    for row in rows:
        raw = row.get('a') or row.get('artist')
        if not raw:
            continue
        key = _artist_key(raw)
        if key:
            owned.add(key)
    return owned


def is_owned(name, owned):
    n = _artist_key(name)
    return bool(n and n in owned)


def config(load_config_fn):
    cfg = load_config_fn() or {}
    ac = cfg.get('acquire') or {}
    enabled = ac.get('enabled')
    if enabled is None:
        enabled = True
    ua = (ac.get('userAgent') or 'BockMedia/1.0 ( https://github.com/bockmedia )').strip()
    return {'enabled': bool(enabled), 'userAgent': ua}


def status(load_config_fn):
    c = config(load_config_fn)
    return {'enabled': c['enabled'], 'source': 'musicbrainz'}


def _rate_limit():
    global _last_mb_at
    now = time.time()
    wait = 1.05 - (now - _last_mb_at)
    if wait > 0:
        time.sleep(wait)
    _last_mb_at = time.time()


def _mb_get(path, user_agent):
    if _mb_fetch is not None:
        return _mb_fetch(path)
    url = f'{MB_BASE}{path}'
    req = Request(url, headers={'User-Agent': user_agent, 'Accept': 'application/json'})
    _rate_limit()
    try:
        with urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except (HTTPError, URLError, json.JSONDecodeError, TimeoutError, OSError):
        return None


def _cache_path(data_dir):
    return os.path.join(data_dir, 'acquire_cache.json')


def _cache_load(data_dir):
    path = _cache_path(data_dir)
    if not os.path.isfile(path):
        return {}
    try:
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
        return data.get('entries') or {}
    except (OSError, json.JSONDecodeError):
        return {}


def _cache_save(data_dir, entries, atomic_write):
    path = _cache_path(data_dir)
    payload = {'entries': entries}
    if atomic_write:
        atomic_write(path, payload)
    else:
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(payload, f)


def _cache_get(data_dir, key):
    ent = _cache_load(data_dir).get(key)
    if not ent:
        return None
    ts = ent.get('ts')
    if ts is not None:
        try:
            if time.time() - float(ts) > CACHE_TTL_SEC:
                return None
        except (TypeError, ValueError):
            return None
    return ent.get('data')


def _cache_put(data_dir, key, data, atomic_write):
    entries = _cache_load(data_dir)
    entries[key] = {'ts': time.time(), 'data': data}
    if len(entries) > 400:
        oldest = sorted(entries.items(), key=lambda kv: kv[1].get('ts') or 0)[:100]
        for k, _ in oldest:
            entries.pop(k, None)
    _cache_save(data_dir, entries, atomic_write)


def resolve_seed_artist(db_one, db_query, seed_kind, path=None, album=None, artist=None, playlist_paths=None):
    kind = (seed_kind or 'artist').strip().lower()
    if kind == 'artist' and artist:
        return artist.strip()
    row = bock_resonance.fetch_seed_row(
        db_one, db_query, kind, path=path, album=album, artist=artist, playlist_paths=playlist_paths,
    )
    if row and row.get('artist'):
        return (row.get('artist') or '').strip()
    if artist:
        return artist.strip()
    if kind == 'album' and album:
        rows = db_query(
            'SELECT artist FROM songs_cache WHERE LOWER(album) = LOWER(?) AND artist IS NOT NULL LIMIT 1',
            [album],
        ) or []
        if rows:
            return (rows[0].get('artist') or '').strip()
    return ''


def _search_artist(name, user_agent):
    q = urlencode({'query': f'artist:"{name}"', 'fmt': 'json', 'limit': '5'})
    data = _mb_get(f'/artist?{q}', user_agent)
    if not data:
        return None
    artists = data.get('artists') or []
    if not artists:
        q2 = urlencode({'query': name, 'fmt': 'json', 'limit': '5'})
        data = _mb_get(f'/artist?{q2}', user_agent)
        artists = (data or {}).get('artists') or []
    if not artists:
        return None
    target = _norm_artist(name)
    best = artists[0]
    for a in artists:
        if _norm_artist(a.get('name')) == target:
            best = a
            break
    return {'mbid': best.get('id'), 'name': best.get('name') or name}


def _artist_detail(mbid, user_agent):
    if not mbid:
        return None
    return _mb_get(f'/artist/{mbid}?inc=tags+genres+artist-rels&fmt=json', user_agent)


def _tag_artists(tag, user_agent, limit=20):
    tag_q = str(tag).lower().strip()
    if not tag_q:
        return []
    q = urlencode({'query': f'tag:{tag_q}', 'fmt': 'json', 'limit': str(limit)})
    data = _mb_get(f'/artist?{q}', user_agent)
    return (data or {}).get('artists') or []


def _add_candidate(candidates, name, mbid, reason, tags, score):
    key = _norm_artist(name)
    if not key:
        return
    max_tags = 8
    cur = candidates.get(key)
    if cur:
        cur['score'] += score
        if reason not in cur['reasons']:
            cur['reasons'].append(reason)
        for t in tags or []:
            if len(cur['tags']) >= max_tags:
                break
            if t and t not in cur['tags']:
                cur['tags'].append(t)
    else:
        candidates[key] = {
            'name': name,
            'mbid': mbid,
            'reasons': [reason],
            'tags': list(tags or [])[:max_tags],
            'score': score,
        }


def _collect_from_mb(seed_name, user_agent, owned, limit):
    resolved = _search_artist(seed_name, user_agent)
    if not resolved or not resolved.get('mbid'):
        return [], resolved, f'Could not match "{seed_name}" on MusicBrainz'
    detail = _artist_detail(resolved['mbid'], user_agent)
    if not detail:
        return [], resolved, 'MusicBrainz lookup failed'

    candidates = {}
    seed_norm = _norm_artist(resolved.get('name') or seed_name)
    tag_items = []
    for src in (detail.get('tags') or [], detail.get('genres') or []):
        for t in src:
            name = (t.get('name') or '').strip()
            if name:
                tag_items.append((name, int(t.get('count') or 0)))
    tag_items.sort(key=lambda x: x[1], reverse=True)
    top_tags = [t[0] for t in tag_items[:4] if t[0]]

    for rel in detail.get('relations') or []:
        rtype = (rel.get('type') or '').lower()
        if rtype not in _REL_TYPES:
            continue
        target = rel.get('artist') or {}
        aname = (target.get('name') or '').strip()
        if not aname or _norm_artist(aname) == seed_norm:
            continue
        if is_owned(aname, owned):
            continue
        _add_candidate(
            candidates, aname, target.get('id'), f'Related to {resolved["name"]}',
            top_tags[:3], 4.0,
        )

    for tag in top_tags[:3]:
        for a in _tag_artists(tag, user_agent, limit=18):
            aname = (a.get('name') or '').strip()
            if not aname or _norm_artist(aname) == seed_norm:
                continue
            if is_owned(aname, owned):
                continue
            _add_candidate(
                candidates, aname, a.get('id'), f'Shared tag: {tag}',
                [tag], 2.0 + min(int(a.get('count') or 0), 50) / 25.0,
            )

    ranked = sorted(candidates.values(), key=lambda c: c['score'], reverse=True)
    out = []
    for c in ranked[:limit]:
        mbid = c.get('mbid')
        out.append({
            'name': c['name'],
            'mbid': mbid,
            'reasons': c['reasons'][:3],
            'tags': c['tags'][:6],
            'inLibrary': False,
            'musicbrainzUrl': f'https://musicbrainz.org/artist/{mbid}' if mbid else None,
        })
    note = None
    if not out:
        note = 'No new artists found — your library may already cover this niche'
    return out, resolved, note


def suggest_for_seed(
    db_query, db_one, load_config_fn, data_dir, atomic_write,
    seed_kind='artist', path=None, album=None, artist=None, playlist_paths=None,
    limit=20,
):
    c = config(load_config_fn)
    if not c['enabled']:
        return {'error': 'acquire_disabled', 'suggestions': []}
    seed_artist = resolve_seed_artist(
        db_one, db_query, seed_kind, path=path, album=album, artist=artist, playlist_paths=playlist_paths,
    )
    if not seed_artist:
        return {'error': 'seed_not_found', 'suggestions': []}

    cache_key = f'seed:{_norm_artist(seed_artist)}:{limit}'
    cached = _cache_get(data_dir, cache_key)
    if cached:
        return cached

    owned = owned_artists(db_query)
    suggestions, resolved, note = _collect_from_mb(seed_artist, c['userAgent'], owned, limit)
    result = {
        'source': 'musicbrainz',
        'seed': {
            'kind': seed_kind,
            'artist': seed_artist,
            'resolvedName': (resolved or {}).get('name'),
            'mbid': (resolved or {}).get('mbid'),
        },
        'suggestions': suggestions,
        'note': note,
    }
    if suggestions:
        _cache_put(data_dir, cache_key, result, atomic_write)
    return result


def explore_library(
    db_query, db_one, load_config_fn, data_dir, atomic_write, limit=24, seed_count=4,
):
    c = config(load_config_fn)
    if not c['enabled']:
        return {'error': 'acquire_disabled', 'suggestions': []}

    cache_key = f'explore:{limit}'
    cached = _cache_get(data_dir, cache_key)
    if cached:
        return cached

    rows = db_query(
        'SELECT artist, COUNT(*) AS c FROM songs_cache '
        'WHERE artist IS NOT NULL AND TRIM(artist) != "" '
        'GROUP BY LOWER(TRIM(artist)) ORDER BY c DESC LIMIT ?',
        [max(seed_count * 8, 24)],
    ) or []
    if not rows:
        return {'error': 'library_empty', 'suggestions': []}

    pool = [r['artist'] for r in rows if r.get('artist')]
    random.shuffle(pool)
    seeds = pool[:seed_count]
    owned = owned_artists(db_query)
    merged = {}
    per_seed = max(8, limit // max(len(seeds), 1))

    for seed in seeds:
        chunk, _, _ = _collect_from_mb(seed, c['userAgent'], owned, per_seed)
        for item in chunk:
            key = _norm_artist(item.get('name'))
            if key and key not in merged:
                merged[key] = item
            if len(merged) >= limit:
                break
        if len(merged) >= limit:
            break

    suggestions = list(merged.values())[:limit]
    result = {
        'source': 'musicbrainz',
        'seed': {'kind': 'library', 'artists': seeds},
        'suggestions': suggestions,
        'note': 'Based on artists in your library' if suggestions else 'Could not find new artists to suggest',
    }
    if suggestions:
        _cache_put(data_dir, cache_key, result, atomic_write)
    return result
