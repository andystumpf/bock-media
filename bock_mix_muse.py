"""Mix Muse — conversational LLM playlist curation (Claude or OpenAI)."""
import json
import os
import re
from urllib.request import urlopen, Request


def llm_config(load_config_fn):
    cfg = load_config_fn() or {}
    claude = cfg.get('claude') or {}
    openai = cfg.get('openai') or {}
    c_key = (claude.get('apiKey') or os.environ.get('ANTHROPIC_API_KEY') or '').strip()
    o_key = (openai.get('apiKey') or os.environ.get('OPENAI_API_KEY') or '').strip()
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


def status(load_config_fn):
    c = llm_config(load_config_fn)
    return {
        'configured': c['configured'],
        'provider': c['provider'] or None,
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
    words = [w for w in re.split(r'\W+', (prompt or '').lower()) if len(w) > 2][:12]
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
    return db_query(sql, params) or []


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
