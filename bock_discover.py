"""Discover Weekly — server-side recommendations cache."""
import json
import os
import random
import threading
import time
from collections import Counter, defaultdict

_LOCK = threading.Lock()


def load_cache(path):
    with _LOCK:
        if not os.path.isfile(path):
            return {'members': {}, 'generatedAt': None}
        try:
            with open(path) as f:
                data = json.load(f)
            if not isinstance(data, dict):
                return {'members': {}, 'generatedAt': None}
            data.setdefault('members', {})
            return data
        except Exception:
            return {'members': {}, 'generatedAt': None}


def save_cache(path, data):
    with _LOCK:
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        tmp = path + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, path)


def _parse_history_line(line):
    try:
        return json.loads(line)
    except Exception:
        return None


def _history_paths(history_path, since_ts=None, member_id=None):
    paths = []
    if not os.path.isfile(history_path):
        return paths
    with open(history_path) as f:
        for line in f:
            ev = _parse_history_line(line)
            if not ev:
                continue
            fp = ev.get('filepath') or ev.get('path')
            if not fp:
                continue
            if member_id and ev.get('memberId') and ev.get('memberId') != member_id:
                continue
            if since_ts:
                try:
                    dt = ev.get('date') or ''
                    if dt and dt < since_ts:
                        continue
                except Exception:
                    pass
            paths.append(fp)
    return paths


def generate_for_member(member_id, db_query, history_path, household_members=None):
    """Build discover sections for one member."""
    now = time.time()
    week_ago = time.strftime('%Y-%m-%d', time.gmtime(now - 7 * 86400))
    month_ago = time.strftime('%Y-%m-%d', time.gmtime(now - 30 * 86400))
    heavy = _history_paths(history_path, since_ts=month_ago, member_id=member_id)
    heavy_counts = Counter(heavy)
    top_paths = [p for p, _ in heavy_counts.most_common(30)]

    artist_rows = []
    if top_paths:
        placeholders = ','.join('?' * min(len(top_paths), 20))
        artist_rows = db_query(
            f'SELECT DISTINCT artist, path, title, album, genre FROM songs_cache '
            f'WHERE path IN ({placeholders}) AND artist IS NOT NULL',
            top_paths[:20],
        ) or []
    top_artists = list({r.get('artist') for r in artist_rows if r.get('artist')})[:5]
    top_genres = list({r.get('genre') for r in artist_rows if r.get('genre')})[:3]

    sections = []
    if top_paths:
        picks = []
        for p in top_paths[:8]:
            row = db_query('SELECT path, title, artist, album FROM songs_cache WHERE path=?', [p])
            if row:
                picks.append(row[0])
        if picks:
            sections.append({
                'id': 'heavy',
                'title': 'On repeat',
                'reason': 'Because you play these often',
                'tracks': picks,
            })

    if top_artists:
        artist = random.choice(top_artists)
        rediscover = db_query(
            'SELECT path, title, artist, album FROM songs_cache '
            'WHERE LOWER(artist) LIKE ? AND path IS NOT NULL ORDER BY RANDOM() LIMIT 6',
            [f'%{artist.lower()}%'],
        ) or []
        if rediscover:
            sections.append({
                'id': 'rediscover',
                'title': 'Rediscover',
                'reason': f'Because you like {artist}',
                'tracks': rediscover,
            })

    genre = top_genres[0] if top_genres else None
    if genre:
        unheard = db_query(
            'SELECT path, title, artist, album FROM songs_cache '
            'WHERE LOWER(COALESCE(genre,"")) LIKE ? AND path IS NOT NULL ORDER BY RANDOM() LIMIT 8',
            [f'%{genre.lower()}%'],
        ) or []
        if unheard:
            sections.append({
                'id': 'unheard',
                'title': 'New to you',
                'reason': f'From {genre}',
                'tracks': unheard,
            })

    if household_members:
        others = [m for m in household_members if m != member_id]
        if others:
            other = random.choice(others)
            blend = _history_paths(history_path, since_ts=month_ago, member_id=other)
            blend_rows = []
            for p in blend[:5]:
                row = db_query('SELECT path, title, artist, album FROM songs_cache WHERE path=?', [p])
                if row:
                    blend_rows.append(row[0])
            if blend_rows:
                sections.append({
                    'id': 'blend',
                    'title': 'From the house',
                    'reason': f'Popular with {other}',
                    'tracks': blend_rows,
                })

    return {
        'memberId': member_id,
        'generatedAt': time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(now)),
        'sections': sections,
    }


def run_weekly_job(cache_path, db_query, history_path, member_ids):
    cache = {'members': {}, 'generatedAt': time.strftime('%Y-%m-%dT%H:%M:%S')}
    mids = member_ids or ['household']
    for mid in mids:
        cache['members'][mid] = generate_for_member(mid, db_query, history_path, mids)
    save_cache(cache_path, cache)
    return cache


def get_discover_weekly(cache_path, member_id):
    member_id = (member_id or 'household').strip() or 'household'
    cache = load_cache(cache_path)
    pack = (cache.get('members') or {}).get(member_id)
    if not pack:
        pack = (cache.get('members') or {}).get('household')
    return {
        'memberId': member_id,
        'generatedAt': cache.get('generatedAt'),
        'sections': (pack or {}).get('sections') or [],
    }
