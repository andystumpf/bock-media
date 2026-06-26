"""Unified library search — Plexamp-style grouped metadata + resonance discovery."""
import difflib
import json
import os
import re

import bock_search_ext

_FILLER = frozenset(
    'a an the of in on at to for and or from with by feat ft featuring vs'.split()
)

PINS_PATH = None  # set via configure()


def configure(data_dir):
    global PINS_PATH
    PINS_PATH = os.path.join(data_dir, 'search_pins.json')


def _compact(s):
    return re.sub(r'[^a-z0-9]', '', (s or '').lower())


def _norm(s):
    s = (s or '').lower().strip()
    s = re.sub(r'[^\w\s]', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()


def _word_tokens(s):
    return [w for w in re.split(r'[\W_]+', s or '') if w]


def _acronym_compact(text):
    """R.E.M. -> rem; Pink Floyd unchanged for single-word tokens."""
    parts = [p for p in re.split(r'[.\s]+', text or '') if p]
    if len(parts) > 1:
        return ''.join(p[0] for p in parts).lower()
    words = _word_tokens(text)
    if len(words) > 1:
        return ''.join(w[0] for w in words).lower()
    return _compact(text)


def field_matches_query(q, text):
    """True when query prefix-matches text: word-start or acronym (R.E.M. ~ rem), never mid-word."""
    qc = _compact(q)
    if not qc or not text:
        return False
    tc = _compact(text)
    if tc.startswith(qc):
        return True
    ac = _acronym_compact(text)
    if ac.startswith(qc):
        return True
    for word in _word_tokens(text):
        wc = _compact(word)
        if wc.startswith(qc):
            return True
        wac = _acronym_compact(word)
        if wac.startswith(qc):
            return True
    return False


def _sql_prefix_patterns(q):
    """SQL LIKE prefixes — never '%q%' mid-string matches."""
    qc = _compact(q)
    if not qc:
        return []
    patterns = [f'{qc}%']
    if qc.isalpha():
        dotted = '.'.join(qc)
        patterns.append(f'{dotted}%')
        patterns.append(f'{dotted}.%')
        upper = '.'.join(c.upper() for c in qc)
        patterns.append(f'{upper}%')
        patterns.append(f'{upper}.%')
    return list(dict.fromkeys(patterns))


def _sql_field_patterns(q):
    """SQL LIKE patterns: string prefix plus word-start after separators ([year], spaces, etc.)."""
    patterns = list(_sql_prefix_patterns(q))
    qc = _compact(q).lower()
    if qc:
        for sep in (' ', ']', '(', '-', '/', '.'):
            pat = f'%{sep}{qc}%'
            if pat not in patterns:
                patterns.append(pat)
    return patterns


def _tokens(s):
    return [t for t in _norm(s).split() if t and t not in _FILLER]


def score_prefix(query, text):
    """Prefix/acronym relevance in [0, 1]."""
    if not field_matches_query(query, text):
        return 0.0
    qc, tc = _compact(query), _compact(text)
    if qc == tc:
        return 1.0
    ac = _acronym_compact(text)
    if qc == ac:
        return 0.98
    if tc.startswith(qc):
        return 0.90 + 0.08 * min(1.0, len(qc) / max(len(tc), 1))
    if ac.startswith(qc):
        return 0.88 + 0.08 * min(1.0, len(qc) / max(len(ac), 1))
    for word in _word_tokens(text):
        wc = _compact(word)
        if wc.startswith(qc):
            return 0.86 + 0.08 * min(1.0, len(qc) / max(len(wc), 1))
        wac = _acronym_compact(word)
        if wac.startswith(qc):
            return 0.84 + 0.08 * min(1.0, len(qc) / max(len(wac), 1))
    return 0.75


def score_text(query, text):
    """Closeness of query to text in [0, 1] — prefix/acronym aware."""
    base = score_prefix(query, text)
    if base > 0:
        return base
    qn, tn = _norm(query), _norm(text)
    if not qn or not tn:
        return 0.0
    score = difflib.SequenceMatcher(None, qn, tn).ratio()
    qt, tt = set(_tokens(query)), set(_tokens(text))
    if qt and tt:
        inter = len(qt & tt)
        if inter:
            cover_q, cover_n = inter / len(qt), inter / len(tt)
            score = max(score, (cover_q + cover_n) / 2)
    return score if field_matches_query(query, text) else 0.0


def _fts_query(q):
    tokens = re.findall(r'\w+', (q or '').strip())
    if not tokens:
        return None
    return ' '.join(f'{t}*' for t in tokens)


def _source_prefix(source):
    """Watch-folder path prefix filter, or None for all libraries."""
    s = (source or '').strip()
    if not s or s.lower() in ('all', '*'):
        return None
    if not s.endswith('/'):
        s += '/'
    return s


def _path_clause(prefix):
    if not prefix:
        return '', []
    return ' AND path LIKE ?', [prefix + '%']


def _display_artist_sql():
    return 'COALESCE(NULLIF(album_artist, ""), artist)'


def _rank_rows(query, rows, name_key, extra_keys=()):
    scored = []
    for row in rows:
        primary = row.get(name_key) or ''
        keys_to_try = (primary,) + tuple(row.get(k) or '' for k in extra_keys)
        if not any(field_matches_query(query, v) for v in keys_to_try if v):
            continue
        s = score_prefix(query, primary)
        for k in extra_keys:
            s = max(s, score_prefix(query, row.get(k) or ''))
        scored.append((s, row))
    scored.sort(key=lambda x: (-x[0], (x[1].get(name_key) or '').lower()))
    return [r for s, r in scored if s > 0]


def _slice_bucket(rows, preview, limit, section, bucket_name):
    if section and section != bucket_name:
        return []
    cap = limit if section == bucket_name else preview
    return rows[:cap]


def library_search_song_match(q, title, album, artist=None):
    """True when a track belongs in unified search Songs, not album tracklist noise."""
    if len(_compact(q)) < 2:
        return False
    if artist and field_matches_query(q, artist):
        return True
    if not field_matches_query(q, title or ''):
        return False
    al = (album or '').lower().strip()
    for sep in (' - from ', ' – from '):
        if sep in (title or '').lower():
            primary, suffix = (title or '').lower().split(sep, 1)
            if field_matches_query(q, album or '') and not field_matches_query(q, primary):
                if field_matches_query(q, suffix):
                    return False
    tl = (title or '').lower()
    if ' - ' in tl and field_matches_query(q, album or ''):
        primary, suffix = tl.split(' - ', 1)
        if not field_matches_query(q, primary) and field_matches_query(q, suffix):
            return False
    return True


def _like_or_clause(fields, patterns):
    clauses, params = [], []
    for field in fields:
        for pat in patterns:
            clauses.append(f'LOWER({field}) LIKE ?')
            params.append(pat.lower())
    return ' OR '.join(clauses), params


def fts_songs_ranked(db_query, q, limit, prefix=None):
    """FTS5 bm25-ranked songs, with prefix LIKE fallback."""
    fts_q = _fts_query(q)
    extra_sql, extra_params = _path_clause(prefix)
    if fts_q:
        try:
            rows = db_query(
                'SELECT s.title, s.artist, s.album, s.path, s.album_artist, '
                '       bm25(songs_fts) AS fts_rank '
                'FROM songs_fts f '
                'JOIN songs_cache s ON s.rowid = f.rowid '
                f'WHERE songs_fts MATCH ?{extra_sql} '
                'ORDER BY fts_rank LIMIT ?',
                [fts_q, *extra_params, limit * 3],
            ) or []
            if rows:
                return rows
        except Exception:
            pass
    patterns = _sql_prefix_patterns(q)
    if not patterns:
        return []
    clause, params = _like_or_clause(
        ('title', 'artist', 'album_artist', 'album'), patterns,
    )
    return db_query(
        f'SELECT title, artist, album, path, album_artist FROM songs_cache '
        f'WHERE ({clause}) AND path IS NOT NULL{extra_sql} LIMIT ?',
        [*params, *extra_params, limit * 4],
    ) or []


def search_artists(db_query, q, limit, prefix=None):
    patterns = _sql_prefix_patterns(q)
    if not patterns:
        return []
    da = _display_artist_sql()
    extra_sql, extra_params = _path_clause(prefix)
    clause, params = _like_or_clause(('artist', 'album_artist'), patterns)
    rows = db_query(
        f'SELECT {da} AS artist, MIN(path) AS art_path, COUNT(DISTINCT album) AS albums FROM songs_cache '
        f'WHERE ({clause}) AND path IS NOT NULL AND path != ""{extra_sql} '
        f'GROUP BY {da} LIMIT ?',
        [*params, *extra_params, limit * 8],
    ) or []
    rows = [r for r in rows if field_matches_query(q, r.get('artist') or '')]
    ranked = _rank_rows(q, rows, 'artist')[:limit]
    return ranked


def search_albums(db_query, q, limit, prefix=None, albums_played_fn=None):
    patterns = _sql_field_patterns(q)
    if not patterns:
        return []
    da = _display_artist_sql()
    extra_sql, extra_params = _path_clause(prefix)
    album_clause, album_params = _like_or_clause(('album',), patterns)
    artist_clause, artist_params = _like_or_clause(('artist', 'album_artist'), patterns)
    rows = db_query(
        f'SELECT album, {da} AS artist, MIN(path) AS art_path FROM songs_cache '
        f'WHERE (({album_clause}) OR ({artist_clause})) '
        f'AND album IS NOT NULL AND album != "" '
        f'AND path IS NOT NULL{extra_sql} '
        f'GROUP BY album, {da} LIMIT ?',
        [*album_params, *artist_params, *extra_params, limit * 12],
    ) or []
    rows = [
        r for r in rows
        if field_matches_query(q, r.get('album') or '')
        or field_matches_query(q, r.get('artist') or '')
    ]
    ranked = _rank_rows(q, rows, 'album', ('artist',))
    album_rows = [{'album': r['album'], 'artist': r.get('artist'), 'path': r.get('art_path')} for r in ranked]
    played = albums_played_fn(album_rows) if albums_played_fn else {}
    out = []
    for r in album_rows:
        key = (r['album'], r.get('artist') or '')
        out.append({
            'name': r['album'],
            'artist': r.get('artist'),
            'path': r.get('path'),
            'played': played.get(key, False),
        })
    return out[:limit]


def search_genres(db_query, q, limit, prefix=None):
    patterns = _sql_prefix_patterns(q)
    if not patterns:
        return []
    extra_sql, extra_params = _path_clause(prefix)
    clause, params = _like_or_clause(('genre',), patterns)
    rows = db_query(
        'SELECT genre, MIN(path) AS path FROM songs_cache '
        f'WHERE ({clause}) AND genre IS NOT NULL AND genre != ""{extra_sql} '
        'GROUP BY genre LIMIT ?',
        [*params, *extra_params, limit * 4],
    ) or []
    rows = [r for r in rows if field_matches_query(q, r.get('genre') or '')]
    return _rank_rows(q, rows, 'genre')[:limit]


def search_playlists_by_name(query, load_entries_fn, score_fn, limit):
    playlists = []
    for pid, name, src in load_entries_fn():
        s = score_fn(query, name)
        if s >= 0.5 and field_matches_query(query, name):
            playlists.append({'id': pid, 'name': name, 'source': src, '_score': s})
    playlists.sort(key=lambda x: (-x['_score'], (x.get('name') or '').lower()))
    for p in playlists:
        p.pop('_score', None)
    return playlists[:limit]


def search_playlists_by_tracks(query, load_entries_fn, playlist_paths_fn, song_paths, limit):
    """Playlists whose track list overlaps top song hits (content match)."""
    if not song_paths:
        return []
    path_set = set(song_paths[:30])
    found = []
    seen_ids = set()
    for pid, name, src in load_entries_fn():
        if pid in seen_ids:
            continue
        try:
            paths = playlist_paths_fn(pid, src) or []
        except Exception:
            continue
        overlap = sum(1 for p in paths[:800] if p in path_set)
        if overlap > 0:
            found.append({
                'id': pid,
                'name': name,
                'source': src,
                '_score': overlap + score_text(query, name) * 0.5,
            })
            seen_ids.add(pid)
        if len(found) >= limit * 2:
            break
    found.sort(key=lambda x: -x['_score'])
    for p in found:
        p.pop('_score', None)
    return found[:limit]


def merge_playlists(name_hits, content_hits, limit):
    seen = set()
    out = []
    for lst in (name_hits, content_hits):
        for p in lst:
            pid = p.get('id')
            if not pid or pid in seen:
                continue
            seen.add(pid)
            out.append(p)
            if len(out) >= limit:
                return out
    return out


def build_radios(query, artists, albums, songs):
    """Search-driven radio launch points."""
    radios = []
    if artists:
        a = artists[0]
        name = a.get('name') or a.get('artist')
        if name and score_text(query, name) >= 0.55:
            radios.append({
                'kind': 'artist',
                'name': name,
                'displayTitle': f'{name} Radio',
                'path': a.get('path'),
            })
    if albums:
        al = albums[0]
        name = al.get('name') or al.get('album')
        if name and score_text(query, name) >= 0.55:
            radios.append({
                'kind': 'album',
                'name': name,
                'artist': al.get('artist'),
                'displayTitle': f'{name} Radio',
                'path': al.get('path'),
            })
    if songs:
        s = songs[0]
        title = s.get('title') or s.get('name')
        if title and score_text(query, title) >= 0.55:
            radios.append({
                'kind': 'song',
                'name': title,
                'artist': s.get('artist'),
                'displayTitle': f'{title} Radio',
                'path': s.get('path'),
            })
    return radios[:3]


def build_similar(db_one, db_query, resonance_mod, songs, limit=8):
    """Resonance similar tracks for the top song hit."""
    if not songs or not resonance_mod:
        return []
    top = songs[0]
    path = top.get('path')
    if not path:
        return []
    try:
        seed = resonance_mod.fetch_seed_row(db_one, db_query, 'song', path=path)
        if not seed:
            return []
        rows = resonance_mod.similar_tracks(db_query, seed, limit=limit)
        return [
            {'title': r.get('title'), 'artist': r.get('artist'), 'album': r.get('album'), 'path': r.get('path')}
            for r in rows if r.get('path')
        ]
    except Exception:
        return []


def run_search(
    *,
    db_query,
    db_one,
    q,
    limit=30,
    preview=5,
    section=None,
    source=None,
    include_rooms=True,
    include_messages=False,
    include_resonance=True,
    ensure_fts_fn=None,
    load_playlist_entries_fn=None,
    score_playlist_fn=None,
    load_smart_playlists_fn=None,
    albums_played_fn=None,
    playlist_paths_fn=None,
    list_devices_fn=None,
    messages_path=None,
    enrich_paths_fn=None,
    resonance_mod=None,
):
    """Full grouped search payload."""
    q = (q or '').strip()
    preview = min(max(int(preview or 5), 1), 15)
    limit = min(max(int(limit or 30), 1), 100)
    empty = {
        'query': q,
        'playlists': [], 'artists': [], 'albums': [], 'songs': [],
        'genres': [], 'smartPlaylists': [], 'rooms': [], 'messages': [],
        'radios': [], 'similar': [],
        'counts': {},
        'preview': preview,
    }
    if len(q) < 2:
        return empty

    if ensure_fts_fn:
        ensure_fts_fn()
    prefix = _source_prefix(source)

    raw_songs = fts_songs_ranked(db_query, q, limit, prefix=prefix)
    songs_all = [
        r for r in raw_songs
        if library_search_song_match(
            q, r.get('title'), r.get('album'), artist=r.get('artist') or r.get('album_artist'),
        )
    ]
    songs_all = _rank_rows(q, songs_all, 'title', ('artist', 'album'))
    songs_all = songs_all[:limit]
    song_paths = [r.get('path') for r in songs_all if r.get('path')]

    artists_all = search_artists(db_query, q, limit, prefix=prefix)
    albums_all = search_albums(db_query, q, limit, prefix=prefix, albums_played_fn=albums_played_fn)
    genres_all = search_genres(db_query, q, limit, prefix=prefix)

    playlists_all = []
    if load_playlist_entries_fn and score_playlist_fn:
        by_name = search_playlists_by_name(q, load_playlist_entries_fn, score_playlist_fn, limit)
        by_tracks = []
        if playlist_paths_fn and song_paths:
            by_tracks = search_playlists_by_tracks(
                q, load_playlist_entries_fn, playlist_paths_fn, song_paths, limit,
            )
        playlists_all = merge_playlists(by_name, by_tracks, limit)

    smart_all = []
    if load_smart_playlists_fn:
        for sp in load_smart_playlists_fn():
            name = sp.get('name') or ''
            if field_matches_query(q, name) or score_text(q, name) >= 0.6:
                smart_all.append({'id': sp.get('id'), 'name': name})
            if len(smart_all) >= limit:
                break
        smart_all.sort(key=lambda x: -score_text(q, x.get('name') or ''))

    rooms_all = []
    if include_rooms and list_devices_fn:
        try:
            for d in list_devices_fn() or []:
                nm = d.get('name') or ''
                if q.lower() in nm.lower():
                    rooms_all.append({'name': nm, 'serial': d.get('serialNumber')})
                if len(rooms_all) >= limit:
                    break
        except Exception:
            pass

    messages_all = []
    if include_messages and messages_path and os.path.isfile(messages_path):
        try:
            with open(messages_path) as f:
                for line in f:
                    try:
                        m = json.loads(line)
                    except Exception:
                        continue
                    text = (m.get('text') or m.get('body') or '')
                    if q.lower() in text.lower():
                        messages_all.append({'id': m.get('id'), 'text': text[:120]})
                    if len(messages_all) >= limit:
                        break
        except Exception:
            pass

    song_items = [
        {'title': r['title'], 'artist': r.get('artist'), 'album': r.get('album'), 'path': r['path']}
        for r in songs_all
    ]
    artist_items = [
        {'name': r['artist'], 'path': r.get('art_path'), 'albums': r.get('albums') or 0}
        for r in artists_all
    ]
    album_items = albums_all
    genre_items = [{'name': r['genre'], 'path': r.get('path')} for r in genres_all]

    radios = build_radios(q, artist_items, album_items, song_items)
    similar = build_similar(db_one, db_query, resonance_mod if include_resonance else None, song_items)

    counts = {
        'playlists': len(playlists_all),
        'artists': len(artist_items),
        'albums': len(album_items),
        'songs': len(song_items),
        'genres': len(genre_items),
        'smartPlaylists': len(smart_all),
        'rooms': len(rooms_all),
        'similar': len(similar),
        'radios': len(radios),
    }

    return {
        'query': q,
        'playlists': _slice_bucket(playlists_all, preview, limit, section, 'playlists'),
        'artists': _slice_bucket(artist_items, preview, limit, section, 'artists'),
        'albums': _slice_bucket(album_items, preview, limit, section, 'albums'),
        'songs': _slice_bucket(song_items, preview, limit, section, 'songs'),
        'genres': _slice_bucket(genre_items, preview, limit, section, 'genres'),
        'smartPlaylists': _slice_bucket(smart_all, preview, limit, section, 'smartPlaylists'),
        'rooms': _slice_bucket(rooms_all, preview, limit, section, 'rooms'),
        'messages': _slice_bucket(messages_all, preview, limit, section, 'messages'),
        'radios': _slice_bucket(radios, preview, limit, section, 'radios'),
        'similar': _slice_bucket(similar, preview, limit, section, 'similar'),
        'counts': counts,
        'preview': preview,
    }


# ── Search pins (Aural Fixations) ────────────────────────────────────────────

def load_pins():
    if not PINS_PATH or not os.path.isfile(PINS_PATH):
        return []
    try:
        with open(PINS_PATH) as f:
            data = json.load(f)
        pins = data.get('pins') if isinstance(data, dict) else data
        return pins if isinstance(pins, list) else []
    except Exception:
        return []


def save_pins(pins):
    if not PINS_PATH:
        return False
    os.makedirs(os.path.dirname(PINS_PATH) or '.', exist_ok=True)
    with open(PINS_PATH, 'w') as f:
        json.dump({'pins': pins}, f, indent=2)
    return True
