"""Listen Agent — natural-language play requests via Claude (with local fallback)."""
import difflib
import json
import os
import re

import bock_mix_muse
import bock_search

_VALID_INTENTS = frozenset({'artist_top', 'album', 'artist', 'song', 'playlist', 'mood'})

_LISTEN_FILLER = {
    'the', 'a', 'an', 'please', 'music', 'some', 'my', 'me', 'to', 'on',
    'play', 'start', 'listen', 'put', 'queue', 'shuffle', 'from', 'by',
}


def status(load_config_fn):
    cfg = bock_mix_muse.llm_config(load_config_fn)
    return {
        'configured': bool(cfg['claudeKey'] or cfg['openaiKey']),
        'supportsClaude': bool(cfg['claudeKey']),
        'supportsOpenAi': bool(cfg['openaiKey']),
        'supportsLocal': True,
        'mode': bock_mix_muse.effective_mode(load_config_fn),
    }


def _parse_llm_json(text):
    m = re.search(r'\{.*\}', text or '', re.DOTALL)
    if not m:
        raise ValueError('llm_invalid_response')
    return json.loads(m.group(0))


def _normalize_intent(raw):
    out = {
        'intent': (raw.get('intent') or 'mood').strip().lower(),
        'artist': (raw.get('artist') or '').strip() or None,
        'album': (raw.get('album') or '').strip() or None,
        'song': (raw.get('song') or '').strip() or None,
        'playlist': (raw.get('playlist') or '').strip() or None,
        'limit': min(max(int(raw.get('limit') or 25), 1), 50),
        'shuffle': bool(raw.get('shuffle', False)),
    }
    if out['intent'] not in _VALID_INTENTS:
        out['intent'] = 'mood'
    if out['intent'] == 'artist_top':
        out['shuffle'] = False
    if out['intent'] == 'album':
        out['shuffle'] = False
    return out


def _clean_listen_prompt(prompt):
    """Strip common voice/command words so 'play Steely Dan' -> 'Steely Dan'."""
    s = (prompt or '').strip().rstrip('.')
    s = re.sub(
        r'^(?:please\s+)?(?:play|start|listen\s+to|put\s+on)\s+(?:the\s+)?',
        '', s, flags=re.I,
    )
    s = re.sub(
        r'^(?:top\s+)?(?:songs|tracks|music)\s+(?:from|by)\s+',
        '', s, flags=re.I,
    )
    s = re.sub(r'^(?:the\s+)?album\s+', '', s, flags=re.I)
    return s.strip()


def _wants_top_songs(prompt):
    lower = (prompt or '').lower()
    return any(w in lower for w in ('top', 'popular', 'best', 'hit', 'greatest', 'favorite', 'favourite'))


def _artist_track_count(db_query, artist):
    rows = db_query(
        'SELECT COUNT(*) AS n FROM songs_cache '
        'WHERE (artist = ? OR album_artist = ?) AND path IS NOT NULL',
        [artist, artist],
    ) or []
    return int(rows[0].get('n') or 0) if rows else 0


def lookup_artist(db_query, query):
    """Best library artist for a spoken name. Exact name wins; then prefer the
    candidate whose name adds the least noise and has the most tracks, so
    'Steely Dan' (196 tracks) beats 'Marian McPartland with guest Steely Dan' (1)."""
    q = _clean_listen_prompt(query)
    if len(q) < 2:
        return None
    ql = q.lower()

    exact = db_query(
        'SELECT artist FROM songs_cache WHERE LOWER(artist) = ? '
        'AND path IS NOT NULL LIMIT 1',
        [ql],
    ) or []
    if exact:
        return exact[0]['artist']
    exact = db_query(
        'SELECT DISTINCT album_artist AS artist FROM songs_cache '
        'WHERE LOWER(album_artist) = ? AND path IS NOT NULL LIMIT 1',
        [ql],
    ) or []
    if exact:
        return exact[0]['artist']

    hits = bock_search.search_artists(db_query, q, limit=8)
    candidates = []
    for row in hits:
        name = (row.get('artist') or '').strip()
        if not name:
            continue
        nl = name.lower()
        if nl == ql:
            tier = 3
        elif nl.startswith(ql) or nl.startswith('the ' + ql):
            tier = 2
        elif ql in nl:
            tier = 1
        else:
            tier = 0
        count = _artist_track_count(db_query, name)
        candidates.append((tier, count, -len(name), name))
    if not candidates:
        return None
    candidates.sort(reverse=True)
    tier, count, _neg_len, name = candidates[0]
    if tier == 0 and count == 0:
        return None
    return name


def _core_album_title(name):
    """Strip release decorations: '[1993] Siamese Dream (2011 Remaster)' -> 'siamese dream'."""
    s = (name or '').lower()
    s = re.sub(r'^\[\d{4}\]\s*', '', s)
    s = re.sub(r'\((?:\d{4}\s*[-–]?\s*)?(?:remaster(?:ed)?|deluxe|expanded|anniversary|bonus|edition|version|mono|stereo|reissue|remix)[^)]*\)', '', s)
    s = re.sub(r'\s*[-–]\s*(?:remaster(?:ed)?|deluxe|expanded).*$', '', s)
    return re.sub(r'\s+', ' ', s).strip()


def lookup_album(db_query, query, artist_hint=None):
    raw = (query or '').strip()
    q = _clean_listen_prompt(raw)
    hint = (artist_hint or '').strip() or None
    m = re.search(r'album\s+(.+?)(?:\s+by\s+(.+))?$', raw, flags=re.I)
    if m:
        q = m.group(1).strip().rstrip('.')
        if not hint and m.group(2):
            hint = m.group(2).strip()
    if len(q) < 2:
        return None
    ql = q.lower()

    rows = bock_search.search_albums(db_query, q, limit=10)
    like_rows = db_query(
        'SELECT album AS name, COALESCE(NULLIF(album_artist, ""), artist) AS artist, '
        'COUNT(*) AS n FROM songs_cache '
        'WHERE LOWER(album) LIKE ? AND path IS NOT NULL '
        'GROUP BY album, COALESCE(NULLIF(album_artist, ""), artist) '
        'ORDER BY n DESC LIMIT 10',
        [f'%{ql}%'],
    ) or []
    seen = set()
    merged = []
    for row in list(rows) + list(like_rows):
        name = row.get('name') or row.get('album')
        if not name:
            continue
        key = (name, row.get('artist') or '')
        if key in seen:
            continue
        seen.add(key)
        merged.append({'name': name, 'artist': row.get('artist')})
    if not merged:
        return None

    hint_canon = None
    if hint:
        hint_canon = lookup_artist(db_query, hint) or hint

    def track_count(row):
        rows = db_query(
            'SELECT COUNT(*) AS n FROM songs_cache WHERE album = ? AND path IS NOT NULL',
            [row['name']],
        ) or []
        return int(rows[0].get('n') or 0) if rows else 0

    def score(row):
        name = row['name']
        nl = name.lower()
        core = _core_album_title(name)
        title = 0
        if nl == ql:
            # Exact plain title: the canonical release, not a deluxe/demos set.
            title = 4
        elif core == ql:
            title = 3
        elif core.startswith(ql) or nl.startswith(ql):
            title = 2
        elif ql in core or ql in nl:
            title = 1
        artist_ok = 0
        if hint_canon:
            artist = row.get('artist') or ''
            if artist and bock_search.field_matches_query(hint_canon, artist):
                artist_ok = 1
        # Within a tier prefer the fuller release (2-track partial rip loses).
        return (artist_ok, title, track_count(row))

    merged.sort(key=score, reverse=True)
    best = merged[0]
    artist_ok, title, _n = score(best)
    if title == 0 and not artist_ok:
        return None
    return best['name'], best.get('artist')


def infer_intent_from_library(prompt, db_query):
    """Prefer concrete library artist/album matches over vague mood playlists."""
    lower = (prompt or '').lower()
    cleaned = _clean_listen_prompt(prompt)

    if re.search(r'\balbum\b', lower):
        hit = lookup_album(db_query, prompt)
        if hit:
            album, artist = hit
            return _normalize_intent({
                'intent': 'album', 'album': album, 'artist': artist, 'shuffle': False,
            })

    artist = lookup_artist(db_query, cleaned)
    if artist:
        intent = 'artist_top' if _wants_top_songs(prompt) else 'artist_top'
        return _normalize_intent({
            'intent': intent, 'artist': artist, 'limit': 25, 'shuffle': False,
        })

    hit = lookup_album(db_query, cleaned)
    if hit:
        album, artist = hit
        return _normalize_intent({
            'intent': 'album', 'album': album, 'artist': artist, 'shuffle': False,
        })

    return None


def parse_intent_local(prompt):
    """Regex fallback when LLM is unavailable."""
    text = (prompt or '').strip()
    lower = text.lower()
    if not text:
        raise ValueError('prompt_required')

    m = re.search(
        r'(?:play\s+)?(?:the\s+)?(?:album\s+)(.+?)(?:\s+by\s+(.+))?$',
        text, flags=re.I,
    )
    if m:
        album = m.group(1).strip().rstrip('.')
        artist = (m.group(2) or '').strip() or None
        return _normalize_intent({'intent': 'album', 'album': album, 'artist': artist, 'shuffle': False})

    m = re.search(
        r'(?:play\s+)?(?:the\s+)?top\s+(?:songs|tracks)\s+(?:from|by)\s+(.+?)$',
        text, flags=re.I,
    )
    if m:
        return _normalize_intent({
            'intent': 'artist_top',
            'artist': m.group(1).strip().rstrip('.'),
            'limit': 25,
            'shuffle': False,
        })

    m = re.search(r'(?:play\s+)?(?:the\s+)?song\s+(.+?)(?:\s+by\s+(.+))?$', text, flags=re.I)
    if m:
        return _normalize_intent({
            'intent': 'song',
            'song': m.group(1).strip().rstrip('.'),
            'artist': (m.group(2) or '').strip() or None,
        })

    m = re.search(r'(?:play\s+)?(?:the\s+)?playlist\s+(.+?)$', text, flags=re.I)
    if m:
        return _normalize_intent({'intent': 'playlist', 'playlist': m.group(1).strip().rstrip('.')})

    m = re.search(r'(?:play\s+(?:music\s+)?by|shuffle\s+)(.+?)$', text, flags=re.I)
    if m and 'album' not in lower and 'playlist' not in lower and 'song' not in lower:
        return _normalize_intent({
            'intent': 'artist_top',
            'artist': m.group(1).strip().rstrip('.'),
            'limit': 25,
            'shuffle': False,
        })

    return _normalize_intent({'intent': 'mood', 'limit': 25, 'shuffle': True})


def parse_intent(prompt, load_config_fn, db_query=None):
    prompt = (prompt or '').strip()
    if not prompt:
        raise ValueError('prompt_required')
    intent, mode = None, 'local'
    try:
        intent, mode = parse_intent_claude(prompt, load_config_fn)
    except Exception:
        intent = parse_intent_local(prompt)

    if db_query and intent.get('intent') == 'mood':
        inferred = infer_intent_from_library(prompt, db_query)
        if inferred:
            intent = inferred
            if mode == 'local':
                mode = 'library'

    return intent, mode


def parse_intent_claude(prompt, load_config_fn):
    cfg = bock_mix_muse.llm_config(load_config_fn)
    if not cfg['claudeKey'] and not cfg['openaiKey']:
        return parse_intent_local(prompt), 'local'

    user_msg = (
        'You interpret music play requests for a home media library. '
        'Reply with JSON only, no markdown.\n\n'
        'Fields:\n'
        '- intent: one of "artist_top", "album", "artist", "song", "playlist", "mood"\n'
        '- artist: artist name when relevant\n'
        '- album: album title when relevant\n'
        '- song: song title when relevant\n'
        '- playlist: playlist name when relevant\n'
        '- limit: track count for artist_top/mood (default 25, max 50)\n'
        '- shuffle: boolean — false for album and artist_top, true for artist unless user says album order\n\n'
        'Use artist_top when the user asks for popular/top/best songs from an artist. '
        'Use album when they name an album. Use mood ONLY for vague vibe requests '
        '(e.g. "chill jazz") with no specific artist or album.\n\n'
        'Examples:\n'
        '"play top songs from Steely Dan" -> '
        '{"intent":"artist_top","artist":"Steely Dan","limit":25,"shuffle":false}\n'
        '"play Steely Dan" -> '
        '{"intent":"artist_top","artist":"Steely Dan","limit":25,"shuffle":false}\n'
        '"Play the Album Siamese Dream" -> '
        '{"intent":"album","album":"Siamese Dream","artist":"The Smashing Pumpkins","shuffle":false}\n'
        '"play some chill jazz" -> {"intent":"mood","limit":25,"shuffle":true}\n\n'
        f'User request: "{prompt.strip()}"'
    )
    if cfg['claudeKey']:
        text = bock_mix_muse._call_claude(cfg['claudeKey'], cfg['claudeModel'], user_msg)
        mode = 'claude'
    else:
        text = bock_mix_muse._call_openai(cfg['openaiKey'], cfg['openaiModel'], user_msg)
        mode = 'openai'
    return _normalize_intent(_parse_llm_json(text)), mode


def _fuzzy_artist_inline(db_query, query):
    q = (query or '').strip()
    if not q:
        return None
    ql = q.lower()
    rows = db_query(
        'SELECT DISTINCT artist FROM songs_cache WHERE LOWER(artist) LIKE ? '
        'AND artist IS NOT NULL LIMIT 5',
        [f'%{ql}%'],
    )
    if rows:
        return rows[0]['artist']
    sample = db_query(
        'SELECT DISTINCT artist FROM songs_cache WHERE artist IS NOT NULL AND artist != "" LIMIT 10000',
    ) or []
    names = [r['artist'] for r in sample if r.get('artist')]
    matches = difflib.get_close_matches(q, names, n=1, cutoff=0.5)
    return matches[0] if matches else None


def _fuzzy_album_inline(db_query, query):
    q = (query or '').strip()
    if not q:
        return None
    ql = q.lower()
    rows = db_query(
        "SELECT DISTINCT album FROM songs_cache WHERE album IS NOT NULL AND album != '' LIMIT 10000",
    ) or []
    names = [r['album'] for r in rows if r.get('album')]
    for name in names:
        if name.lower() == ql:
            return name
    contains = [n for n in names if ql in n.lower()]
    if contains:
        contains.sort(key=lambda n: (len(n), n.lower()))
        return contains[0]
    matches = difflib.get_close_matches(q, names, n=1, cutoff=0.5)
    return matches[0] if matches else None


def _album_tracks_inline(db_query, album, artist=None, shuffle=False, limit=50):
    order = 'ORDER BY RANDOM()' if shuffle else 'ORDER BY CAST(track_number AS INTEGER), title'
    ext = "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac', '.flac', '.ogg')"
    base = f'SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL {ext}'
    artist = (artist or '').strip() or None
    if artist:
        rows = db_query(
            f'{base} AND (artist = ? OR album_artist = ?) {order} LIMIT ?',
            [album, artist, artist, limit],
        )
        if rows:
            return [r['path'] for r in rows]
    rows = db_query(f'{base} {order} LIMIT ?', [album, limit])
    return [r['path'] for r in rows]


_BEST_OF_RE = re.compile(
    r'best.?of|greatest|hits|anthology|collection|essential|gold|definitive|ultimate',
    re.I,
)
_LIVE_TITLE_RE = re.compile(r'\blive\b|\(live|- live|\[live', re.I)


def _norm_title(title):
    s = (title or '').lower()
    s = re.sub(r'\s*[-–(\[].*$', '', s)  # strip " - Live…", "(Remaster)" suffixes
    s = re.sub(r'[^\w\s]', '', s)
    return re.sub(r'\s+', ' ', s).strip()


def _rank_top_titles_llm(artist, titles, limit, load_config_fn):
    """Ask Claude/OpenAI which of the artist's library titles are most popular."""
    cfg = bock_mix_muse.llm_config(load_config_fn)
    if not cfg['claudeKey'] and not cfg['openaiKey']:
        return None
    numbered = '\n'.join(f'{i}: {t}' for i, t in enumerate(titles))
    user_msg = (
        f'From this list of songs by {artist} in a personal music library, pick the '
        f'{limit} most popular / well-known ones, ordered from most to least popular. '
        'Reply with JSON only: {"indices":[3,17,0]}\n\n'
        f'{numbered}'
    )
    try:
        if cfg['claudeKey']:
            text = bock_mix_muse._call_claude(cfg['claudeKey'], cfg['claudeModel'], user_msg)
        else:
            text = bock_mix_muse._call_openai(cfg['openaiKey'], cfg['openaiModel'], user_msg)
        indices = _parse_llm_json(text).get('indices') or []
        out = []
        for i in indices:
            try:
                i = int(i)
            except (TypeError, ValueError):
                continue
            if 0 <= i < len(titles) and i not in out:
                out.append(i)
        return out or None
    except Exception:
        return None


def _artist_top_paths(db_query, enrich_song_rows_fn, artist, limit, load_config_fn=None):
    """Most-popular tracks for an artist.

    Popularity signals, best first: member play counts, LLM ranking of titles,
    then a local heuristic — songs repeated across albums (compilations pick
    singles) and appearances on best-of albums. One path per song title.
    """
    rows = db_query(
        'SELECT id, title, artist, album, genre, year, duration_seconds, '
        'track_number, path FROM songs_cache '
        'WHERE path IS NOT NULL AND path != "" AND '
        '(artist = ? OR album_artist = ?) LIMIT 800',
        [artist, artist],
    ) or []
    items = enrich_song_rows_fn(rows)
    if not items:
        return []

    groups = {}
    for item in items:
        key = _norm_title(item.get('title'))
        if not key:
            continue
        groups.setdefault(key, []).append(item)

    def pick_version(versions):
        # Prefer studio versions with plays, then non-live, shortest album name.
        def vkey(v):
            title = v.get('title') or ''
            album = v.get('album') or ''
            return (
                -int(v.get('playCount') or 0),
                1 if _LIVE_TITLE_RE.search(title) else 0,
                1 if _BEST_OF_RE.search(album) else 0,
                len(album),
            )
        return sorted(versions, key=vkey)[0]

    scored = []
    for key, versions in groups.items():
        plays = sum(int(v.get('playCount') or 0) for v in versions)
        albums = {(v.get('album') or '').strip() for v in versions if v.get('album')}
        on_best_of = any(_BEST_OF_RE.search(a) for a in albums)
        all_live = all(_LIVE_TITLE_RE.search(v.get('title') or '') for v in versions)
        heuristic = len(albums) + (2 if on_best_of else 0) - (3 if all_live else 0)
        scored.append((key, plays, heuristic, pick_version(versions)))

    have_play_data = any(plays > 0 for _k, plays, _h, _v in scored)

    if not have_play_data and load_config_fn is not None and len(scored) > limit:
        titles = [v.get('title') or k for k, _p, _h, v in scored]
        ranked = _rank_top_titles_llm(artist, titles, limit, load_config_fn)
        if ranked:
            picked = []
            for i in ranked:
                path = scored[i][3].get('path')
                if path and path not in picked:
                    picked.append(path)
                if len(picked) >= limit:
                    break
            if picked:
                return picked

    scored.sort(key=lambda x: (-x[1], -x[2], x[0]))
    paths = []
    for _key, _plays, _heuristic, version in scored[:limit]:
        p = version.get('path')
        if p and p not in paths:
            paths.append(p)
    return paths


def resolve_intent(
    intent,
    prompt,
    db_query,
    load_config_fn,
    enrich_paths_fn,
    enrich_song_rows_fn,
    fuzzy_artist=None,
    fuzzy_album=None,
    album_tracks_fn=None,
    best_playlist_fn=None,
    parse_m3u_fn=None,
    fuzzy_track_fn=None,
):
    """Resolve parsed intent to track paths, display name, and shuffle flag."""

    def find_artist(query):
        # Library-aware lookup first (prefers exact names and high track counts);
        # the generic fuzzy helpers return the first LIKE hit, which picks
        # collaboration credits like 'X with guest Steely Dan' over 'Steely Dan'.
        hit = lookup_artist(db_query, query)
        if hit:
            return hit
        if fuzzy_artist:
            return fuzzy_artist(query)
        return _fuzzy_artist_inline(db_query, query)

    def find_album(query, artist_hint=None):
        hit = lookup_album(db_query, query, artist_hint=artist_hint)
        if hit:
            return hit
        name = fuzzy_album(query) if fuzzy_album else _fuzzy_album_inline(db_query, query)
        return (name, None) if name else None

    album_tracks = album_tracks_fn or (
        lambda album, artist=None, shuffle=False, limit=50:
        _album_tracks_inline(db_query, album, artist=artist, shuffle=shuffle, limit=limit)
    )

    kind = intent['intent']
    if kind == 'artist_top':
        query = intent.get('artist') or _clean_listen_prompt(prompt)
        artist = find_artist(query)
        if not artist:
            raise ValueError('artist_not_found')
        limit = intent.get('limit') or 25
        paths = _artist_top_paths(
            db_query, enrich_song_rows_fn, artist, limit, load_config_fn=load_config_fn,
        )
        if not paths:
            raise ValueError('no_tracks_found')
        return f'Top songs · {artist}', paths, False, kind

    if kind == 'album':
        query = intent.get('album') or _clean_listen_prompt(prompt)
        hit = find_album(query, artist_hint=intent.get('artist'))
        if not hit:
            raise ValueError('album_not_found')
        album, album_artist = hit
        paths = album_tracks(album, artist=album_artist, shuffle=False)
        if not paths:
            raise ValueError('no_tracks_found')
        label = f'{album} · {album_artist}' if album_artist else album
        return label, paths, False, kind

    if kind == 'artist':
        query = intent.get('artist') or _clean_listen_prompt(prompt)
        artist = find_artist(query)
        if not artist:
            raise ValueError('artist_not_found')
        limit = intent.get('limit') or 25
        paths = _artist_top_paths(
            db_query, enrich_song_rows_fn, artist, limit, load_config_fn=load_config_fn,
        )
        if not paths:
            raise ValueError('no_tracks_found')
        shuffle = intent.get('shuffle', True)
        return f'Artist · {artist}', paths, shuffle, kind

    if kind == 'song':
        title = intent.get('song') or prompt
        artist = intent.get('artist')
        if fuzzy_track_fn:
            paths = fuzzy_track_fn(title, artist)
        else:
            ql = title.lower()
            if artist:
                rows = db_query(
                    'SELECT path FROM songs_cache WHERE LOWER(title) LIKE ? '
                    'AND LOWER(artist) LIKE ? AND path IS NOT NULL LIMIT 10',
                    [f'%{ql}%', f'%{artist.lower()}%'],
                )
            else:
                rows = db_query(
                    'SELECT path FROM songs_cache WHERE LOWER(title) LIKE ? '
                    'AND path IS NOT NULL LIMIT 10',
                    [f'%{ql}%'],
                )
            paths = [r['path'] for r in rows or [] if r.get('path')]
        if not paths:
            raise ValueError('song_not_found')
        label = title if not artist else f'{title} · {artist}'
        return label, paths[:1], False, kind

    if kind == 'playlist':
        query = intent.get('playlist') or prompt
        if not best_playlist_fn or not parse_m3u_fn:
            raise ValueError('playlist_not_supported')
        entry = best_playlist_fn(query)
        if not entry or not entry[2] or not os.path.isfile(entry[2]):
            raise ValueError('playlist_not_found')
        paths = parse_m3u_fn(entry[2])
        if not paths:
            raise ValueError('no_tracks_found')
        return entry[1], paths, intent.get('shuffle', False), kind

    # mood / vibe — only when no library artist/album matches
    inferred = infer_intent_from_library(prompt, db_query)
    if inferred and inferred.get('intent') != 'mood':
        return resolve_intent(
            inferred, prompt, db_query, load_config_fn, enrich_paths_fn, enrich_song_rows_fn,
            fuzzy_artist=fuzzy_artist, fuzzy_album=fuzzy_album, album_tracks_fn=album_tracks_fn,
            best_playlist_fn=best_playlist_fn, parse_m3u_fn=parse_m3u_fn, fuzzy_track_fn=fuzzy_track_fn,
        )

    limit = intent.get('limit') or 25
    candidates = bock_mix_muse.candidates_for_prompt(db_query, prompt)
    ai_name, paths, mode = bock_mix_muse.curate_playlist(
        prompt, candidates, limit, load_config_fn, db_query=db_query,
    )
    if not paths:
        raise ValueError('no_tracks_found')
    return ai_name, paths, intent.get('shuffle', True), f'mood-{mode}'


def play_from_prompt(
    prompt,
    db_query,
    load_config_fn,
    enrich_paths_fn,
    enrich_song_rows_fn,
    **helpers,
):
    intent, mode = parse_intent(prompt, load_config_fn, db_query=db_query)
    name, paths, shuffle, resolved_kind = resolve_intent(
        intent,
        prompt,
        db_query,
        load_config_fn,
        enrich_paths_fn,
        enrich_song_rows_fn,
        **helpers,
    )
    tracks = enrich_paths_fn(paths)
    if isinstance(resolved_kind, str) and resolved_kind.startswith('mood-'):
        source_mode = resolved_kind.split('-', 1)[1]
    else:
        source_mode = mode
    return {
        'name': name,
        'tracks': tracks,
        'trackCount': len(tracks),
        'shuffle': shuffle,
        'intent': intent['intent'],
        'artist': intent.get('artist'),
        'album': intent.get('album'),
        'mode': source_mode,
        'source': f'listen-agent-{source_mode}',
        'message': f'Playing {name}',
    }
