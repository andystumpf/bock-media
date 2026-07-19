"""Mix Muse — LLM (Claude/OpenAI) with local Picard/MusicBrainz fallback."""
import json
import os
import re
from urllib.request import urlopen, Request

import bock_mix_muse_local

_FILLER = {
    'the', 'and', 'for', 'with', 'that', 'like', 'songs', 'song', 'music',
    'playlist', 'mix', 'vibe', 'vibes', 'from', 'sound', 'sounds',
}


def _prompt_words(prompt):
    words = []
    for w in re.split(r'\W+', (prompt or '').lower()):
        if len(w) > 2 and w not in _FILLER:
            words.append(w)
    return words[:16]


def _usable_key(key):
    """Filter template placeholders like SET_LOCALLY / GENERATE_..."""
    key = (key or '').strip()
    if key.startswith(('SET_', 'GENERATE_')):
        return ''
    return key


def llm_config(load_config_fn):
    cfg = load_config_fn() or {}
    claude = cfg.get('claude') or {}
    openai = cfg.get('openai') or {}
    c_key = _usable_key(claude.get('apiKey')) or _usable_key(os.environ.get('ANTHROPIC_API_KEY'))
    o_key = _usable_key(openai.get('apiKey')) or _usable_key(os.environ.get('OPENAI_API_KEY'))
    provider = (cfg.get('mixMuse') or {}).get('provider') or ''
    provider = provider.strip().lower()
    if not provider:
        if c_key:
            provider = 'claude'
        elif o_key:
            provider = 'openai'
    return {
        'provider': provider,
        'claudeKey': c_key,
        'claudeModel': (claude.get('model') or 'claude-sonnet-4-20250514').strip(),
        'openaiKey': o_key,
        'openaiModel': (openai.get('model') or 'gpt-4o-mini').strip(),
        'configured': bool(c_key or o_key),
    }


def effective_mode(load_config_fn):
    forced = ((load_config_fn() or {}).get('mixMuse') or {}).get('provider') or ''
    forced = forced.strip().lower()
    if forced in ('local', 'claude', 'openai'):
        return forced
    c = llm_config(load_config_fn)
    if c['claudeKey']:
        return 'claude'
    if c['openaiKey']:
        return 'openai'
    return 'local'


def status(load_config_fn):
    c = llm_config(load_config_fn)
    mode = effective_mode(load_config_fn)
    return {
        'configured': True,
        'provider': mode,
        'mode': mode,
        'supportsLocal': True,
        'supportsOpenAi': bool(c['openaiKey']),
        'supportsClaude': bool(c['claudeKey']),
    }


def _parse_llm_json(text):
    m = re.search(r'\{.*\}', text or '', re.DOTALL)
    if not m:
        raise ValueError('llm_invalid_response')
    return json.loads(m.group(0))


def _call_claude(api_key, model, user_msg):
    body = json.dumps({
        'model': model,
        'max_tokens': 1024,
        'messages': [{'role': 'user', 'content': user_msg}],
    }).encode('utf-8')
    req = Request(
        'https://api.anthropic.com/v1/messages',
        data=body,
        headers={
            'Content-Type': 'application/json',
            'x-api-key': api_key,
            'anthropic-version': '2023-06-01',
        },
        method='POST',
    )
    with urlopen(req, timeout=90) as resp:
        data = json.loads(resp.read().decode('utf-8', errors='replace'))
    text = ''
    for block in data.get('content') or []:
        if block.get('type') == 'text':
            text += block.get('text') or ''
    return text


def _call_openai(api_key, model, user_msg):
    body = json.dumps({
        'model': model,
        'max_tokens': 1024,
        'messages': [
            {'role': 'system', 'content': 'Reply with JSON only.'},
            {'role': 'user', 'content': user_msg},
        ],
        'response_format': {'type': 'json_object'},
    }).encode('utf-8')
    req = Request(
        'https://api.openai.com/v1/chat/completions',
        data=body,
        headers={
            'Content-Type': 'application/json',
            'Authorization': f'Bearer {api_key}',
        },
        method='POST',
    )
    with urlopen(req, timeout=90) as resp:
        data = json.loads(resp.read().decode('utf-8', errors='replace'))
    return ((data.get('choices') or [{}])[0].get('message') or {}).get('content') or ''


def pick_tracks(prompt, candidates, max_tracks, load_config_fn, provider=None):
    cfg = llm_config(load_config_fn)
    use = (provider or cfg['provider'] or '').lower()
    if not candidates:
        raise ValueError('no_library_matches')
    lines = []
    for i, r in enumerate(candidates):
        lines.append(
            f'{i}: {r.get("title") or "?"} | {r.get("artist") or ""} | '
            f'{r.get("album") or ""} | {r.get("genre") or ""} | {r.get("year") or ""}'
        )
    catalog = '\n'.join(lines)
    user_msg = (
        f'Create a music playlist for this request: "{prompt}"\n\n'
        f'Pick up to {max_tracks} tracks ONLY from the numbered list below. '
        'Match the requested mood closely. Exclude holiday/Christmas music unless the request '
        'explicitly asks for it. Avoid loud or aggressive tracks for calm/relaxing requests.\n'
        'Reply with JSON only: {"indices":[0,1,2], "name":"Short Playlist Title"}\n\n'
        f'{catalog}'
    )
    if use == 'openai':
        if not cfg['openaiKey']:
            raise ValueError('openai_api_key_not_configured')
        text = _call_openai(cfg['openaiKey'], cfg['openaiModel'], user_msg)
    else:
        if not cfg['claudeKey']:
            if cfg['openaiKey']:
                text = _call_openai(cfg['openaiKey'], cfg['openaiModel'], user_msg)
            else:
                raise ValueError('llm_api_key_not_configured')
        else:
            text = _call_claude(cfg['claudeKey'], cfg['claudeModel'], user_msg)
    parsed = _parse_llm_json(text)
    indices = parsed.get('indices') or []
    name = (parsed.get('name') or '').strip() or 'Mix Muse Playlist'
    paths = []
    for idx in indices:
        try:
            i = int(idx)
        except (TypeError, ValueError):
            continue
        if 0 <= i < len(candidates) and candidates[i].get('path'):
            p = candidates[i]['path']
            if p not in paths:
                paths.append(p)
        if len(paths) >= max_tracks:
            break
    if not paths:
        raise ValueError('llm_no_tracks_picked')
    return name, paths


def curate_playlist(
    prompt,
    candidates,
    max_tracks,
    load_config_fn,
    provider=None,
    seed_row=None,
    db_query=None,
):
    """LLM when configured; local Picard/MusicBrainz otherwise (or on LLM failure)."""
    forced = (provider or '').strip().lower()
    pool = list(candidates)
    if seed_row and db_query:
        pool = bock_mix_muse_local.enrich_candidate_pool(db_query, pool, seed_row)

    if forced == 'local':
        name, paths = bock_mix_muse_local.pick_tracks_local(
            prompt, pool, max_tracks, seed_row=seed_row, db_query=db_query, load_config_fn=load_config_fn,
        )
        return name, paths, 'local'

    try_llm = forced in ('claude', 'openai') or (not forced and llm_config(load_config_fn)['configured'])
    if try_llm:
        try:
            pick_provider = forced if forced in ('claude', 'openai') else None
            name, paths = pick_tracks(prompt, pool, max_tracks, load_config_fn, provider=pick_provider)
            used = forced if forced in ('claude', 'openai') else effective_mode(load_config_fn)
            return name, paths, used
        except Exception:
            if forced in ('claude', 'openai'):
                raise

    name, paths = bock_mix_muse_local.pick_tracks_local(
        prompt, pool, max_tracks, seed_row=seed_row, db_query=db_query, load_config_fn=load_config_fn,
    )
    return name, paths, 'local'


def _calm_genre_pool(db_query, limit):
    genres = (
        'ambient', 'acoustic', 'jazz', 'easy listening', 'folk', 'classical',
        'lounge', 'soft rock', 'singer-songwriter', 'new age', 'bossa nova',
    )
    clauses, params = [], []
    for g in genres:
        clauses.append('LOWER(COALESCE(genre,"")) LIKE ?')
        params.append(f'%{g}%')
    sql = (
        'SELECT path, title, artist, album, genre, year FROM songs_cache WHERE path IS NOT NULL '
        f'AND ({" OR ".join(clauses)}) LIMIT ?'
    )
    params.append(limit)
    return db_query(sql, params) or []


def prompt_for_seed(seed_row, user_prompt=None):
    """Build a conversational prompt from a library seed track/album."""
    title = seed_row.get('title') or 'this track'
    artist = seed_row.get('artist') or 'unknown artist'
    album = seed_row.get('album') or ''
    genre = seed_row.get('genre') or ''
    year = seed_row.get('year') or ''
    if user_prompt:
        return user_prompt.strip()
    album_bit = f' from the album "{album}"' if album else ''
    year_bit = f' ({year})' if year else ''
    genre_bit = f' — {genre} vibes' if genre else ''
    return (
        f'Songs that sound like "{title}" by {artist}{album_bit}{year_bit}{genre_bit}. '
        'Match mood, texture, and era — not just the same artist.'
    )


def candidates_for_prompt(db_query, prompt, limit=400):
    words = _prompt_words(prompt)
    calm_words = {'calm', 'relax', 'peaceful', 'quiet', 'soft', 'gentle', 'morning', 'mellow', 'chill', 'serene'}
    wants_calm = bool(calm_words & set(words))

    if wants_calm:
        rows = _calm_genre_pool(db_query, limit)
        if rows:
            return rows

    if not words:
        return db_query(
            'SELECT path, title, artist, album, genre, year FROM songs_cache '
            'WHERE path IS NOT NULL AND title IS NOT NULL ORDER BY RANDOM() LIMIT ?',
            [limit],
        ) or []
    clauses, params = [], []
    for w in words:
        like = f'%{w}%'
        clauses.append(
            '(LOWER(title) LIKE ? OR LOWER(artist) LIKE ? OR LOWER(album) LIKE ? OR LOWER(genre) LIKE ?)'
        )
        params.extend([like, like, like, like])
    sql = (
        'SELECT path, title, artist, album, genre, year FROM songs_cache WHERE path IS NOT NULL '
        f'AND ({" OR ".join(clauses)}) LIMIT ?'
    )
    params.append(limit)
    rows = db_query(sql, params) or []
    if len(rows) < min(80, limit // 2):
        extra = _calm_genre_pool(db_query, limit) if wants_calm else db_query(
            'SELECT path, title, artist, album, genre, year FROM songs_cache '
            'WHERE path IS NOT NULL AND title IS NOT NULL ORDER BY RANDOM() LIMIT ?',
            [limit],
        ) or []
        seen = {r.get('path') for r in rows if r.get('path')}
        for row in extra:
            p = row.get('path')
            if p and p not in seen:
                rows.append(row)
                seen.add(p)
            if len(rows) >= limit:
                break
    return rows


def candidates_for_seed(db_query, seed_kind, path=None, album=None, artist=None, playlist_paths=None, limit=400):
    rows = []
    if seed_kind == 'song' and path:
        rows = db_query(
            'SELECT path, title, artist, album, genre, year FROM songs_cache WHERE path = ?',
            [path],
        ) or []
    elif seed_kind == 'album' and album:
        rows = db_query(
            'SELECT path, title, artist, album, genre, year FROM songs_cache '
            'WHERE LOWER(album) = LOWER(?) AND path IS NOT NULL LIMIT 20',
            [album],
        ) or []
    elif seed_kind == 'playlist' and playlist_paths:
        sample = playlist_paths[:30]
        if sample:
            ph = ','.join('?' * len(sample))
            rows = db_query(
                f'SELECT path, title, artist, album, genre, year FROM songs_cache WHERE path IN ({ph})',
                sample,
            ) or []
    if not rows:
        raise ValueError('seed_not_found')
    prompt = prompt_for_seed(rows[0])
    pool = candidates_for_prompt(db_query, prompt, limit=limit)
    seed_paths = {r.get('path') for r in rows if r.get('path')}
    for r in rows:
        if r.get('path') and r not in pool:
            pool.insert(0, r)
    return pool, prompt, rows[0]
