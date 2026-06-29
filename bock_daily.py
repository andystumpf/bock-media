"""Daily auto-generated playlists.

Fresh themed mixes that change every day. Each recipe yields one playlist whose
contents are reselected daily (seeded by the date) so the home tile is stable but
the songs are new and different each day. Playlists are persisted as real
Bock-managed playlists so playback, covers, and detail views all work unchanged.

The module is dependency-injected (db_query / persist / meta callbacks) so it can
be unit-tested with fakes, mirroring bock_discover.py.
"""
import datetime
import json
import os
import random
import threading
import time

_LOCK = threading.Lock()

# Names carry this prefix so the clients' `isDailyMixName` ("daily mix") routing
# surfaces them in the dedicated daily-playlists home row with no client changes.
NAME_PREFIX = 'Daily Mix · '
DEFAULT_TRACK_TARGET = 40
_CANDIDATE_CAP = 4000

# Loose genre buckets for energy-flavoured recipes (no tempo data in the cache).
_UPBEAT_GENRES = (
    'pop', 'dance', 'rock', 'electronic', 'edm', 'house', 'techno', 'disco',
    'funk', 'punk', 'metal', 'hip hop', 'hip-hop', 'rap', 'reggaeton', 'latin',
)
_CHILL_GENRES = (
    'ambient', 'acoustic', 'jazz', 'classical', 'folk', 'soul', 'blues',
    'lo-fi', 'lofi', 'chill', 'singer-songwriter', 'piano', 'bossa',
)

RECIPES = [
    {'key': 'discovery', 'title': 'Daily Discovery', 'subtitle': 'New finds, refreshed daily', 'kind': 'discovery'},
    {'key': 'rotation', 'title': 'Daily Rotation', 'subtitle': 'A fresh shuffle of your library', 'kind': 'rotation'},
    {'key': 'throwback', 'title': 'Daily Throwback', 'subtitle': 'Older gems, dusted off', 'kind': 'throwback'},
    {'key': 'energy', 'title': 'Daily Energy', 'subtitle': 'Upbeat picks for today', 'kind': 'energy'},
    {'key': 'chill', 'title': 'Daily Chill', 'subtitle': 'Laid-back songs for right now', 'kind': 'chill'},
    {'key': 'genre', 'title': 'Daily Genre Mix', 'subtitle': "Today's spotlight genre", 'kind': 'genre'},
]


def today_str(today=None):
    return today or datetime.date.today().isoformat()


def _seeded_rng(date, key, member_id=''):
    mid = (member_id or '').strip() or 'household'
    return random.Random(f'{date}:{mid}:{key}')


def _genre_clause(genres):
    parts = ' OR '.join(['LOWER(COALESCE(genre,"")) LIKE ?'] * len(genres))
    params = [f'%{g}%' for g in genres]
    return f'({parts})', params


def _candidate_rows(db_query, where_sql, params):
    sql = 'SELECT path, year, genre FROM songs_cache WHERE path IS NOT NULL '
    if where_sql:
        sql += f'AND {where_sql} '
    sql += 'ORDER BY path LIMIT ?'
    return db_query(sql, list(params) + [_CANDIDATE_CAP]) or []


def _pick(rows, rng, target, file_exists, prefer_unplayed=None):
    """Seeded-shuffle deterministic candidate rows, then take existing files."""
    rows = list(rows)
    rng.shuffle(rows)
    if prefer_unplayed is not None:
        # Stable partition: unplayed first, then least-played — keeps daily variety
        # while leaning toward songs the listener hasn't worn out.
        rows.sort(key=lambda r: int(prefer_unplayed.get(r.get('path'), 0)))
    out, seen = [], set()
    for r in rows:
        p = r.get('path')
        if not p or p in seen or not file_exists(p):
            continue
        seen.add(p)
        out.append(p)
        if len(out) >= target:
            break
    return out


def _spotlight_genre(db_query, rng):
    rows = db_query(
        'SELECT genre, COUNT(*) c FROM songs_cache '
        'WHERE genre IS NOT NULL AND genre != "" GROUP BY LOWER(genre) '
        'HAVING c >= 12 ORDER BY genre',
        [],
    ) or []
    genres = [r.get('genre') for r in rows if r.get('genre')]
    return rng.choice(genres) if genres else None


def _paths_for_recipe(recipe, *, db_query, play_counts, date, target, file_exists, member_id=''):
    kind = recipe.get('kind')
    rng = _seeded_rng(date, recipe['key'], member_id)
    subtitle = recipe.get('subtitle')

    if kind == 'throwback':
        cutoff = datetime.date.today().year - 15
        rows = _candidate_rows(
            db_query,
            'CAST(NULLIF(year,"") AS INTEGER) > 0 AND CAST(NULLIF(year,"") AS INTEGER) <= ?',
            [cutoff],
        )
        return _pick(rows, rng, target, file_exists), subtitle

    if kind == 'energy':
        clause, params = _genre_clause(_UPBEAT_GENRES)
        rows = _candidate_rows(db_query, clause, params)
        return _pick(rows, rng, target, file_exists), subtitle

    if kind == 'chill':
        clause, params = _genre_clause(_CHILL_GENRES)
        rows = _candidate_rows(db_query, clause, params)
        return _pick(rows, rng, target, file_exists), subtitle

    if kind == 'genre':
        genre = _spotlight_genre(db_query, rng)
        if genre:
            rows = _candidate_rows(db_query, 'LOWER(COALESCE(genre,"")) LIKE ?', [f'%{genre.lower()}%'])
            return _pick(rows, rng, target, file_exists), f'Spotlight: {genre}'
        rows = _candidate_rows(db_query, '', [])
        return _pick(rows, rng, target, file_exists), subtitle

    if kind == 'discovery':
        rows = _candidate_rows(db_query, '', [])
        return _pick(rows, rng, target, file_exists, prefer_unplayed=play_counts), subtitle

    # rotation / default
    rows = _candidate_rows(db_query, '', [])
    return _pick(rows, rng, target, file_exists), subtitle


# ── State (which playlist id backs each recipe today, per household member) ───

def _normalize_root(raw):
    if not isinstance(raw, dict):
        return {'members': {}}
    if 'members' in raw:
        raw.setdefault('members', {})
        return raw
    if raw.get('date') or raw.get('recipes'):
        return {
            'members': {
                'household': {
                    'date': raw.get('date'),
                    'generatedAt': raw.get('generatedAt'),
                    'recipes': raw.get('recipes') or {},
                },
            },
        }
    return {'members': {}}


def load_root(path):
    with _LOCK:
        if not os.path.isfile(path):
            return {'members': {}}
        try:
            with open(path) as f:
                data = json.load(f)
            return _normalize_root(data)
        except Exception:
            return {'members': {}}


def save_root(path, root):
    with _LOCK:
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        tmp = path + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(_normalize_root(root), f, indent=2)
        os.replace(tmp, path)


def load_state(path, member_id=None):
    """Return one member's daily state, or the full root when member_id is None."""
    root = load_root(path)
    if member_id is None:
        return root
    mid = (member_id or 'household').strip() or 'household'
    state = (root.get('members') or {}).get(mid)
    if not isinstance(state, dict):
        return {'date': None, 'recipes': {}, 'generatedAt': None}
    state.setdefault('recipes', {})
    return state


def save_state(path, data):
    """Legacy helper — persists household-only state."""
    root = load_root(path)
    root.setdefault('members', {})['household'] = data
    save_root(path, root)


def is_stale(state, today=None):
    return (state or {}).get('date') != today_str(today)


def regenerate(
    *,
    state_path,
    db_query,
    persist_playlist,
    new_id,
    set_meta,
    play_counts,
    today=None,
    target=DEFAULT_TRACK_TARGET,
    recipes=None,
    file_exists=os.path.isfile,
    member_id='household',
    force=False,
):
    """Rebuild every daily playlist for `today` for one household member."""
    recipes = recipes or RECIPES
    date = today_str(today)
    mid = (member_id or 'household').strip() or 'household'
    root = load_root(state_path)
    state = load_state(state_path, mid)
    if not force and not is_stale(state, today):
        return state
    prior = state.get('recipes') or {}
    new_recipes = {}

    for recipe in recipes:
        key = recipe['key']
        paths, subtitle = _paths_for_recipe(
            recipe, db_query=db_query, play_counts=play_counts or {},
            date=date, target=target, file_exists=file_exists, member_id=mid,
        )
        if not paths:
            if key in prior:
                new_recipes[key] = prior[key]
            continue
        name = NAME_PREFIX + recipe['title']
        pid = (prior.get(key) or {}).get('playlistId') or new_id()
        created = key not in prior or not (prior.get(key) or {}).get('playlistId')
        result = persist_playlist(pid, name, paths, create=created)
        if not result:
            result = persist_playlist(pid, name, paths, create=True)
        set_meta(pid, {
            'daily': True,
            'dailyRecipe': key,
            'dailyDate': date,
            'dailySubtitle': subtitle,
            'dailyMemberId': mid,
        })
        track_count = result.get('trackCount', len(paths)) if isinstance(result, dict) else len(paths)
        new_recipes[key] = {
            'playlistId': pid,
            'title': recipe['title'],
            'subtitle': subtitle,
            'trackCount': track_count,
        }

    state = {
        'date': date,
        'generatedAt': time.strftime('%Y-%m-%dT%H:%M:%S'),
        'recipes': new_recipes,
    }
    root.setdefault('members', {})[mid] = state
    save_root(state_path, root)
    return state


def regenerate_all(
    *,
    state_path,
    member_ids,
    db_query,
    persist_playlist,
    new_id,
    set_meta,
    play_counts_by_member,
    today=None,
    target=DEFAULT_TRACK_TARGET,
    recipes=None,
    file_exists=os.path.isfile,
    force=False,
):
    """Regenerate daily playlists for every listed member id."""
    mids = [m.strip() for m in (member_ids or []) if m and str(m).strip()] or ['household']
    last = None
    for mid in mids:
        counts = (play_counts_by_member or {}).get(mid) or {}
        last = regenerate(
            state_path=state_path,
            db_query=db_query,
            persist_playlist=persist_playlist,
            new_id=new_id,
            set_meta=set_meta,
            play_counts=counts,
            today=today,
            target=target,
            recipes=recipes,
            file_exists=file_exists,
            member_id=mid,
            force=force,
        )
    return last


def list_daily(state_path, member_id='household', today=None):
    """Return today's daily playlists as lightweight cards (id, title, subtitle)."""
    state = load_state(state_path, (member_id or 'household').strip() or 'household')
    items = []
    for recipe in RECIPES:
        entry = (state.get('recipes') or {}).get(recipe['key'])
        if not entry or not entry.get('playlistId'):
            continue
        items.append({
            'playlistId': entry['playlistId'],
            'recipe': recipe['key'],
            'title': entry.get('title') or recipe['title'],
            'subtitle': entry.get('subtitle') or recipe.get('subtitle'),
            'trackCount': entry.get('trackCount') or 0,
        })
    return {
        'date': state.get('date'),
        'generatedAt': state.get('generatedAt'),
        'stale': is_stale(state, today),
        'memberId': (member_id or 'household').strip() or 'household',
        'items': items,
    }


def detach_saved(state_path, playlist_id, member_id=None):
    """Drop a playlist from a member's daily set (or search all members)."""
    root = load_root(state_path)
    members = root.get('members') or {}
    scan = [member_id] if member_id else list(members.keys())
    hit = None
    for mid in scan:
        state = members.get(mid) or {}
        recipes = state.get('recipes') or {}
        for key, entry in list(recipes.items()):
            if (entry or {}).get('playlistId') == playlist_id:
                hit = key
                recipes.pop(key, None)
                state['recipes'] = recipes
                members[mid] = state
                break
        if hit:
            break
    if hit:
        root['members'] = members
        save_root(state_path, root)
    return hit
