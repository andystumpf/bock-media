"""Unified library search — Plexamp-style grouped metadata + resonance discovery."""
import concurrent.futures as _futures
import difflib
import json
import os
import re
import unicodedata

import bock_search_ext

_FILLER = frozenset(
    'a an the of in on at to for and or from with by feat ft featuring vs'.split()
)

SPARSE_THRESHOLD = 5
FUZZY_RATIO_MIN = 0.72

PINS_PATH = None  # set via configure()


def configure(data_dir):
    global PINS_PATH
    PINS_PATH = os.path.join(data_dir, 'search_pins.json')


def _fold(s):
    """Normalize for matching: lowercase, diacritics, &→and, punctuation→space."""
    s = (s or '').strip().lower().replace('&', ' and ')
    s = unicodedata.normalize('NFKD', s)
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r'[^\w\s]', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()


def _compact(s):
    return re.sub(r'[^a-z0-9]', '', _fold(s))


def _norm(s):
    return _fold(s)


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


def _field_matches_query_strict(q, text):
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


def field_matches_query(q, text):
    """Prefix/acronym match, plus one-step typing tolerance (e.g. rem → reme while typing R.E.M.)."""
    if _field_matches_query_strict(q, text):
        return True
    q = (q or '').strip()
    if len(_compact(q)) < 3:
        return False
    for back in (1, 2):
        if len(q) > back:
            shorter = q[:-back].rstrip()
            if shorter and _field_matches_query_strict(shorter, text):
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
    return best_match_score(query, text)


def score_folded(query, text):
    """Folded prefix / token-overlap tier (diacritics, &/and)."""
    qf, tf = _fold(query), _fold(text)
    if not qf or not tf:
        return 0.0
    qfc, tfc = _compact(query), _compact(text)
    if qfc == tfc:
        return 0.97
    if tfc.startswith(qfc):
        return 0.82 + 0.1 * min(1.0, len(qfc) / max(len(tfc), 1))
    qt, tt = set(_tokens(query)), set(_tokens(text))
    if qt and tt:
        inter = len(qt & tt)
        if inter and inter >= len(qt):
            return 0.72 + 0.08 * min(1.0, inter / max(len(tt), 1))
    for word in _word_tokens(text):
        wc = _compact(word)
        if wc.startswith(qfc):
            return 0.78 + 0.08 * min(1.0, len(qfc) / max(len(wc), 1))
    return 0.0


def score_substring(query, text):
    """Contains match on folded compact text (mid-word allowed for longer queries)."""
    qfc, tfc = _compact(query), _compact(text)
    if len(qfc) < 2 or not tfc:
        return 0.0
    if len(qfc) <= 3:
        for word in _word_tokens(text):
            wc = _compact(word)
            if wc.startswith(qfc):
                return 0.45 + 0.12 * min(1.0, len(qfc) / max(len(wc), 1))
        return 0.0
    if qfc in tfc:
        return 0.45 + 0.12 * min(1.0, len(qfc) / max(len(tfc), 1))
    return 0.0


def score_fuzzy(query, text):
    """Typo-tolerant edit distance on folded strings."""
    qn, tn = _fold(query), _fold(text)
    if len(_compact(query)) < 3 or not tn:
        return 0.0
    ratio = difflib.SequenceMatcher(None, qn, tn).ratio()
    if ratio >= FUZZY_RATIO_MIN:
        return 0.35 + 0.2 * ratio
    qt, tt = list(_tokens(query)), list(_tokens(text))
    if qt and tt:
        best = 0.0
        for qw in qt:
            for tw in tt:
                wr = difflib.SequenceMatcher(None, qw, tw).ratio()
                if wr >= FUZZY_RATIO_MIN:
                    best = max(best, wr)
        if best >= FUZZY_RATIO_MIN:
            return 0.32 + 0.18 * best
    return 0.0


def best_match_score(query, text, allow_substring=False, allow_fuzzy=False):
    """Unified match score: strict > folded > substring > fuzzy."""
    for scorer in (score_prefix, score_folded):
        s = scorer(query, text)
        if s > 0:
            return s
    if allow_substring:
        s = score_substring(query, text)
        if s > 0:
            return s
    if allow_fuzzy:
        s = score_fuzzy(query, text)
        if s > 0:
            return s
    return 0.0


def row_match_score(query, row, name_key, extra_keys=(), allow_substring=False, allow_fuzzy=False):
    primary = row.get(name_key) or ''
    keys_to_try = (primary,) + tuple(row.get(k) or '' for k in extra_keys)
    return max(
        best_match_score(query, v, allow_substring=allow_substring, allow_fuzzy=allow_fuzzy)
        for v in keys_to_try if v
    ) if keys_to_try else 0.0


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


def _path_clause(prefix, col='path'):
    if not prefix:
        return '', []
    return f' AND {col} LIKE ?', [prefix + '%']


def _display_artist_sql():
    return 'COALESCE(NULLIF(album_artist, ""), artist)'


def _rank_rows(query, rows, name_key, extra_keys=(), limit=None):
    """Rank rows with strict matching; fall back to substring/fuzzy when sparse."""
    scored = []
    for row in rows:
        s = row_match_score(query, row, name_key, extra_keys)
        if s > 0:
            scored.append((s, row))
    if len(scored) < SPARSE_THRESHOLD:
        seen = {id(r) for _, r in scored}
        for row in rows:
            if id(row) in seen:
                continue
            s = row_match_score(query, row, name_key, extra_keys, allow_substring=True)
            if s > 0:
                scored.append((s, row))
                seen.add(id(row))
    if len(scored) < SPARSE_THRESHOLD:
        seen = {id(r) for _, r in scored}
        for row in rows:
            if id(row) in seen:
                continue
            s = row_match_score(query, row, name_key, extra_keys, allow_fuzzy=True)
            if s > 0:
                scored.append((s, row))
                seen.add(id(row))
    scored.sort(key=lambda x: (-x[0], (x[1].get(name_key) or '').lower()))
    out = [r for s, r in scored if s > 0]
    return out[:limit] if limit else out


def _slice_bucket(rows, preview, limit, section, bucket_name):
    if section and section != bucket_name:
        return []
    cap = limit if section == bucket_name else preview
    return rows[:cap]


def library_search_song_match(q, title, album, artist=None, genre=None):
    """True when a track belongs in unified search Songs, not album tracklist noise."""
    if len(_compact(q)) < 2:
        return False
    if artist and field_matches_query(q, artist):
        return True
    if genre and genre_field_matches_query(q, genre):
        return True
    if not field_matches_query(q, title or ''):
        if best_match_score(q, title or '', allow_substring=True, allow_fuzzy=True) <= 0:
            if not (artist and best_match_score(q, artist, allow_substring=True, allow_fuzzy=True) > 0):
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


def _compact_sql(field):
    """Strip punctuation/spaces from a column for compact prefix matching."""
    expr = f'LOWER({field})'
    for ch in (' ', '-', '.', "'", '"', '(', ')', '[', ']', '/'):
        lit = "'" + ch.replace("'", "''") + "'"
        expr = f'REPLACE({expr}, {lit}, {chr(39)}{chr(39)})'
    return expr


def _like_compact_prefix_clause(fields, q):
    """Match compact(query) as prefix of compact(field) — fixes multi-word titles like Learn to Fly."""
    qc = _compact(q)
    if len(qc) < 1:
        return '', []
    clauses, params = [], []
    for field in fields:
        clauses.append(f'{_compact_sql(field)} LIKE ?')
        params.append(f'{qc}%')
    return ' OR '.join(clauses), params


def _sql_substring_patterns(q):
    """SQL LIKE contains patterns on folded compact query."""
    qc = _compact(q)
    if len(qc) < 2:
        return []
    return [f'%{qc}%']


def _like_substring_clause(fields, q):
    """Mid-string compact match — substring fallback tier."""
    patterns = _sql_substring_patterns(q)
    if not patterns:
        return '', []
    clauses, params = [], []
    for field in fields:
        for pat in patterns:
            clauses.append(f'{_compact_sql(field)} LIKE ?')
            params.append(pat)
    return ' OR '.join(clauses), params


def _song_field_match_clause(q, fields=('title', 'artist', 'album_artist', 'album', 'genre'), include_substring=False):
    """SQL OR clause: spaced LIKE prefixes plus compact-prefix (multi-word safe)."""
    patterns = _sql_prefix_patterns(q)
    parts, params = [], []
    compact_clause, compact_params = _like_compact_prefix_clause(fields, q)
    if compact_clause:
        parts.append(f'({compact_clause})')
        params.extend(compact_params)
    if patterns:
        like_clause, like_params = _like_or_clause(fields, patterns)
        parts.append(f'({like_clause})')
        params.extend(like_params)
    if include_substring:
        sub_clause, sub_params = _like_substring_clause(fields, q)
        if sub_clause:
            parts.append(f'({sub_clause})')
            params.extend(sub_params)
    if not parts:
        return '', []
    return ' OR '.join(parts), params


def filter_rows_for_query(query, rows, name_key, extra_keys=(), limit=None):
    """Post-filter + rank rows using the unified matcher ladder."""
    return _rank_rows(query, rows, name_key, extra_keys, limit=limit)


def fts_songs_ranked(db_query, q, limit, prefix=None):
    """FTS5 bm25-ranked songs, with prefix LIKE fallback and substring tier."""
    fts_q = _fts_query(q)
    extra_sql, extra_params = _path_clause(prefix)
    rows = []
    if fts_q:
        # Qualify path against songs_cache — songs_fts has its own path column.
        fts_extra_sql, _ = _path_clause(prefix, col='s.path')
        try:
            rows = db_query(
                'SELECT s.title, s.artist, s.album, s.genre, s.path, s.album_artist, '
                '       bm25(songs_fts) AS fts_rank '
                'FROM songs_fts f '
                'JOIN songs_cache s ON s.rowid = f.rowid '
                f'WHERE songs_fts MATCH ?{fts_extra_sql} '
                'ORDER BY fts_rank LIMIT ?',
                [fts_q, *extra_params, limit * 3],
            ) or []
        except Exception:
            rows = []
    if not rows:
        clause, params = _song_field_match_clause(q)
        if clause:
            rows = db_query(
                f'SELECT title, artist, album, genre, path, album_artist FROM songs_cache '
                f'WHERE ({clause}) AND path IS NOT NULL{extra_sql} LIMIT ?',
                [*params, *extra_params, limit * 4],
            ) or []
    rank_keys = ('artist', 'album', 'album_artist', 'genre')
    ranked = _rank_rows(q, rows, 'title', rank_keys, limit=limit)
    if len(ranked) >= SPARSE_THRESHOLD:
        return ranked
    sub_clause, sub_params = _song_field_match_clause(q, include_substring=True)
    if sub_clause:
        extra = db_query(
            f'SELECT title, artist, album, genre, path, album_artist FROM songs_cache '
            f'WHERE ({sub_clause}) AND path IS NOT NULL{extra_sql} LIMIT ?',
            [*sub_params, *extra_params, limit * 6],
        ) or []
        seen = {r.get('path') for r in ranked}
        for r in extra:
            if r.get('path') not in seen:
                rows.append(r)
                seen.add(r.get('path'))
    return _rank_rows(q, rows, 'title', rank_keys, limit=limit)


def _has_albums_agg(db_query):
    """True when the pre-aggregated albums table exists (one row per album)."""
    try:
        rows = db_query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='albums_agg'"
        ) or []
        return bool(rows)
    except Exception:
        return False


def _fetch_artist_rows(db_query, q, limit, prefix=None, include_substring=False):
    patterns = _sql_field_patterns(q)
    sub_patterns = _sql_substring_patterns(q) if include_substring else []
    if not patterns and not sub_patterns:
        return []
    da = _display_artist_sql()
    extra_sql, extra_params = _path_clause(prefix)
    all_patterns = list(dict.fromkeys(patterns + sub_patterns))
    if not prefix and _has_albums_agg(db_query):
        agg_clause, agg_params = _like_or_clause(('artist',), all_patterns)
        return db_query(
            f'SELECT artist AS artist, MIN(art_path) AS art_path, '
            f'COUNT(DISTINCT album) AS albums FROM albums_agg '
            f'WHERE ({agg_clause}) AND artist IS NOT NULL AND artist != "" '
            f'GROUP BY artist LIMIT ?',
            [*agg_params, limit * 8],
        ) or []
    clause, params = _like_or_clause(('artist', 'album_artist'), all_patterns)
    return db_query(
        f'SELECT {da} AS artist, MIN(path) AS art_path, COUNT(DISTINCT album) AS albums FROM songs_cache '
        f'WHERE ({clause}) AND path IS NOT NULL AND path != ""{extra_sql} '
        f'GROUP BY {da} LIMIT ?',
        [*params, *extra_params, limit * 8],
    ) or []


def search_artists(db_query, q, limit, prefix=None):
    rows = _fetch_artist_rows(db_query, q, limit, prefix=prefix)
    ranked = _rank_rows(q, rows, 'artist', limit=limit)
    if len(ranked) >= SPARSE_THRESHOLD:
        return ranked
    extra = _fetch_artist_rows(db_query, q, limit, prefix=prefix, include_substring=True)
    seen = {r.get('artist') for r in ranked}
    for r in extra:
        if r.get('artist') not in seen:
            rows.append(r)
            seen.add(r.get('artist'))
    return _rank_rows(q, rows, 'artist', limit=limit)


def search_albums(db_query, q, limit, prefix=None, albums_played_fn=None):
    patterns = _sql_field_patterns(q)
    sub_patterns = _sql_substring_patterns(q)
    all_patterns = list(dict.fromkeys(patterns + sub_patterns))
    if not all_patterns:
        return []
    da = _display_artist_sql()
    extra_sql, extra_params = _path_clause(prefix)
    album_clause, album_params = _like_or_clause(('album',), all_patterns)
    if not prefix and _has_albums_agg(db_query):
        agg_artist_clause, agg_artist_params = _like_or_clause(('artist',), all_patterns)
        rows = db_query(
            f'SELECT album, artist AS artist, MIN(art_path) AS art_path FROM albums_agg '
            f'WHERE (({album_clause}) OR ({agg_artist_clause})) '
            f'AND album IS NOT NULL AND album != "" '
            f'GROUP BY album, artist LIMIT ?',
            [*album_params, *agg_artist_params, limit * 12],
        ) or []
    else:
        artist_clause, artist_params = _like_or_clause(('artist', 'album_artist'), all_patterns)
        rows = db_query(
            f'SELECT album, {da} AS artist, MIN(path) AS art_path FROM songs_cache '
            f'WHERE (({album_clause}) OR ({artist_clause})) '
            f'AND album IS NOT NULL AND album != "" '
            f'AND path IS NOT NULL{extra_sql} '
            f'GROUP BY album, {da} LIMIT ?',
            [*album_params, *artist_params, *extra_params, limit * 12],
        ) or []
    ranked = _rank_rows(q, rows, 'album', ('artist',), limit=limit)
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


def _has_genres_agg(db_query):
    try:
        rows = db_query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='genres_agg'"
        ) or []
        return bool(rows)
    except Exception:
        return False


# Folded-compact query → equivalent stored-genre spellings (language translations),
# so "french" finds tags stored as "Français"/"Chanson française" and vice versa.
GENRE_ALIASES = {
    'french': ('francais', 'francaise'),
    'francais': ('french',),
    'francaise': ('french',),
    'german': ('deutsch',),
    'deutsch': ('german',),
    'spanish': ('espanol',),
    'espanol': ('spanish',),
    'italian': ('italiano',),
    'italiano': ('italian',),
    'portuguese': ('portugues',),
    'portugues': ('portuguese',),
}


def genre_query_variants(q):
    """Query plus known translation aliases, folded for diacritic-free matching."""
    variants = [q]
    variants.extend(GENRE_ALIASES.get(_compact(q), ()))
    return variants


def genre_field_matches_query(q, genre):
    """Genre match with translation aliases; _compact folds diacritics both sides."""
    return any(
        best_match_score(v, genre, allow_substring=True) > 0
        for v in genre_query_variants(q)
    )


def _rank_genre_variants(variants, rows, limit):
    """Rank per variant, direct query matches first, dedup by genre."""
    out, seen = [], set()
    for v in variants:
        for r in _rank_rows(v, rows, 'genre', limit=limit):
            g = r.get('genre')
            if g in seen:
                continue
            seen.add(g)
            out.append(r)
            if len(out) >= limit:
                return out
    return out


def search_genres(db_query, q, limit, prefix=None):
    """Genre bucket — alias + diacritic-folded ranking.

    The distinct-genre list is tiny (genres_agg has one row per genre), so
    fetch it whole and rank in Python where _fold() handles diacritics
    (français ≈ francais) that SQL LOWER/LIKE cannot. A LIKE prefilter would
    silently drop accented spellings, so none is used; the songs_cache GROUP
    BY only runs while genres_agg is still being built.
    """
    if not _compact(q):
        return []
    variants = genre_query_variants(q)
    if not prefix and _has_genres_agg(db_query):
        rows = db_query(
            'SELECT genre, track_count, MIN(path) AS path FROM genres_agg '
            'WHERE genre IS NOT NULL AND genre != "" '
            'GROUP BY genre LIMIT ?',
            [4000],
        ) or []
    else:
        extra_sql, extra_params = _path_clause(prefix)
        rows = db_query(
            'SELECT genre, COUNT(*) AS track_count, MIN(path) AS path FROM songs_cache '
            f'WHERE genre IS NOT NULL AND genre != ""{extra_sql} '
            'GROUP BY genre LIMIT ?',
            [*extra_params, 4000],
        ) or []
    return _rank_genre_variants(variants, rows, limit)


def search_playlists_by_name(query, load_entries_fn, score_fn, limit):
    playlists = []
    for pid, name, src in load_entries_fn():
        s = best_match_score(query, name, allow_substring=True, allow_fuzzy=True)
        if s < 0.35:
            continue
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
    scanned = 0
    max_scan = max(limit * 8, 80)
    for pid, name, src in load_entries_fn():
        scanned += 1
        if pid in seen_ids:
            continue
        if scanned > max_scan and len(found) >= limit:
            break
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
    fast=False,
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
        if fast and load_playlist_entries_fn is not None:
            import bock_search_ext
            pl_names = [{'id': pid, 'name': name} for pid, name, _ in load_playlist_entries_fn()]
            smart = [
                {'id': s.get('id'), 'name': s.get('name')}
                for s in (load_smart_playlists_fn() or [])
            ]
            payload = bock_search_ext.suggest_payload(db_query, q, pl_names, [], smart)
            payload['radios'] = []
            payload['similar'] = []
            payload['messages'] = []
            payload['preview'] = preview
            payload['counts'] = {
                'playlists': len(payload.get('playlists') or []),
                'artists': len(payload.get('artists') or []),
                'albums': len(payload.get('albums') or []),
                'songs': len(payload.get('songs') or []),
                'genres': len(payload.get('genres') or []),
                'smartPlaylists': len(payload.get('smartPlaylists') or []),
                'rooms': len(payload.get('rooms') or []),
                'similar': 0,
                'radios': 0,
            }
            return payload
        return empty

    prefix = _source_prefix(source)

    # The four SQL buckets are independent — run them concurrently (each
    # db_query opens its own connection, and SQLite releases the GIL).
    with _futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix='search-bucket') as pool:
        f_songs = pool.submit(fts_songs_ranked, db_query, q, limit, prefix)
        f_artists = pool.submit(search_artists, db_query, q, limit, prefix)
        f_albums = pool.submit(
            search_albums, db_query, q, limit, prefix, albums_played_fn,
        )
        f_genres = pool.submit(search_genres, db_query, q, limit, prefix)
        raw_songs = f_songs.result()
        artists_all = f_artists.result()
        albums_all = f_albums.result()
        genres_all = f_genres.result()

    songs_all = [
        r for r in raw_songs
        if library_search_song_match(
            q, r.get('title'), r.get('album'), artist=r.get('artist') or r.get('album_artist'),
            genre=r.get('genre'),
        )
    ]
    songs_all = _rank_rows(q, songs_all, 'title', ('artist', 'album', 'genre'))
    songs_all = songs_all[:limit]
    song_paths = [r.get('path') for r in songs_all if r.get('path')]

    playlists_all = []
    if load_playlist_entries_fn and score_playlist_fn:
        by_name = search_playlists_by_name(q, load_playlist_entries_fn, score_playlist_fn, limit)
        by_tracks = []
        # Content overlap scans playlist track lists — never on the fast path.
        need_content = not fast and len(by_name) < limit
        if need_content and playlist_paths_fn and song_paths:
            by_tracks = search_playlists_by_tracks(
                q, load_playlist_entries_fn, playlist_paths_fn, song_paths, limit,
            )
        playlists_all = merge_playlists(by_name, by_tracks, limit)

    smart_all = []
    if load_smart_playlists_fn:
        for sp in load_smart_playlists_fn():
            name = sp.get('name') or ''
            if field_matches_query(q, name) or (not fast and score_text(q, name) >= 0.6):
                smart_all.append({'id': sp.get('id'), 'name': name})
            if len(smart_all) >= limit:
                break
        smart_all.sort(key=lambda x: -score_text(q, x.get('name') or ''))

    rooms_all = []
    if include_rooms and list_devices_fn and not fast:
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
        {
            'title': r['title'],
            'artist': r.get('artist'),
            'album': r.get('album'),
            'genre': r.get('genre'),
            'path': r['path'],
        }
        for r in songs_all
    ]
    artist_items = [
        {'name': r['artist'], 'path': r.get('art_path'), 'albums': r.get('albums') or 0}
        for r in artists_all
    ]
    album_items = albums_all
    genre_items = [{'name': r['genre'], 'path': r.get('path')} for r in genres_all]

    radios = build_radios(q, artist_items, album_items, song_items)
    similar = []
    if include_resonance and song_items and (not fast or section == 'similar'):
        similar = build_similar(db_one, db_query, resonance_mod, song_items)

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

_PIN_KINDS = frozenset({'genre', 'playlist', 'artist', 'album', 'radio', 'mix'})


def clean_pins(pins):
    """Normalize pin payloads from API / client prefs."""
    if not isinstance(pins, list):
        return []
    cleaned = []
    for p in pins[:24]:
        if not isinstance(p, dict):
            continue
        kind = (p.get('kind') or '').strip().lower()
        if kind not in _PIN_KINDS:
            continue
        entry = {'kind': kind, 'title': (p.get('title') or p.get('name') or '').strip()}
        if p.get('id'):
            entry['id'] = str(p['id'])
        if p.get('name'):
            entry['name'] = str(p['name'])
        if p.get('artist'):
            entry['artist'] = str(p['artist'])
        if p.get('path'):
            entry['path'] = str(p['path'])
        if entry.get('title') or entry.get('name'):
            cleaned.append(entry)
    return cleaned


def load_pins():
    if not PINS_PATH or not os.path.isfile(PINS_PATH):
        return []
    try:
        with open(PINS_PATH) as f:
            data = json.load(f)
        pins = data.get('pins') if isinstance(data, dict) else data
        return clean_pins(pins if isinstance(pins, list) else [])
    except Exception:
        return []


def save_pins(pins):
    if not PINS_PATH:
        return False
    cleaned = clean_pins(pins)
    os.makedirs(os.path.dirname(PINS_PATH) or '.', exist_ok=True)
    with open(PINS_PATH, 'w') as f:
        json.dump({'pins': cleaned}, f, indent=2)
    return True


def load_pins_for_member(prefs_path, member_id, atomic_write=None):
    """Per-household-profile pins in client_prefs; migrate legacy global file once."""
    mid = (member_id or '').strip()
    if not mid:
        return load_pins()
    import bock_client_prefs
    row = bock_client_prefs.get_prefs(prefs_path, member_id=mid)
    stored = (row.get('merged') or {}).get('searchPins')
    if isinstance(stored, list) and stored:
        return clean_pins(stored)
    legacy = load_pins()
    if legacy:
        bock_client_prefs.put_prefs(
            prefs_path,
            member_id=mid,
            member_prefs={'searchPins': legacy},
            atomic_write=atomic_write,
        )
        return legacy
    return []


def save_pins_for_member(prefs_path, member_id, pins, atomic_write=None):
    mid = (member_id or '').strip()
    cleaned = clean_pins(pins)
    if not mid:
        save_pins(cleaned)
        return cleaned
    import bock_client_prefs
    bock_client_prefs.put_prefs(
        prefs_path,
        member_id=mid,
        member_prefs={'searchPins': cleaned},
        atomic_write=atomic_write,
    )
    return cleaned
