"""Resonance — library-native acoustic similarity mixes (no cloud ML)."""
import random
import re


def _genre_tokens(genre):
    if not genre:
        return set()
    return {t for t in re.split(r'[,;/|&]+', str(genre).lower()) if t.strip()}


def _year_int(val):
    try:
        y = int(val)
        return y if 1900 <= y <= 2100 else None
    except (TypeError, ValueError):
        return None


def _duration_sec(row):
    try:
        return float(row.get('duration_seconds') or 0)
    except (TypeError, ValueError):
        return 0.0


def score_similarity(seed, candidate):
    """Higher = more similar vibe. seed/candidate are songs_cache row dicts."""
    if not seed or not candidate:
        return 0.0
    if seed.get('path') == candidate.get('path'):
        return -1.0
    score = 0.0
    s_artist = (seed.get('artist') or '').strip().lower()
    c_artist = (candidate.get('artist') or '').strip().lower()
    if s_artist and c_artist == s_artist:
        score += 2.5
    s_genres = _genre_tokens(seed.get('genre'))
    c_genres = _genre_tokens(candidate.get('genre'))
    if s_genres and c_genres:
        overlap = len(s_genres & c_genres)
        score += min(overlap * 2.0, 6.0)
    s_year = _year_int(seed.get('year'))
    c_year = _year_int(candidate.get('year'))
    if s_year and c_year:
        delta = abs(s_year - c_year)
        if delta <= 3:
            score += 3.0
        elif delta <= 8:
            score += 1.5
        elif delta <= 15:
            score += 0.5
    s_dur = _duration_sec(seed)
    c_dur = _duration_sec(candidate)
    if s_dur > 30 and c_dur > 30:
        ratio = min(s_dur, c_dur) / max(s_dur, c_dur)
        if ratio >= 0.85:
            score += 2.0
        elif ratio >= 0.7:
            score += 1.0
    s_lufs = seed.get('loudness_lufs')
    c_lufs = candidate.get('loudness_lufs')
    if s_lufs is not None and c_lufs is not None:
        try:
            if abs(float(s_lufs) - float(c_lufs)) <= 2.0:
                score += 2.5
            elif abs(float(s_lufs) - float(c_lufs)) <= 4.0:
                score += 1.0
        except (TypeError, ValueError):
            pass
    s_album = (seed.get('album') or '').strip().lower()
    c_album = (candidate.get('album') or '').strip().lower()
    if s_album and c_album == s_album and s_artist == c_artist:
        score += 1.0
    return score


def fetch_seed_row(db_one, db_query, seed_kind, path=None, album=None, artist=None, playlist_paths=None):
    if seed_kind == 'song' and path:
        return db_one(
            'SELECT path, title, artist, album, genre, year, duration_seconds, loudness_lufs '
            'FROM songs_cache WHERE path = ?',
            [path],
        )
    if seed_kind == 'album' and album:
        where = 'LOWER(album) = LOWER(?)'
        params = [album]
        if artist:
            where += ' AND LOWER(artist) = LOWER(?)'
            params.append(artist)
        rows = db_query(
            f'SELECT path, title, artist, album, genre, year, duration_seconds, loudness_lufs '
            f'FROM songs_cache WHERE {where} AND path IS NOT NULL LIMIT 1',
            params,
        ) or []
        return rows[0] if rows else {}
    if seed_kind == 'playlist' and playlist_paths:
        for p in playlist_paths[:40]:
            row = db_one(
                'SELECT path, title, artist, album, genre, year, duration_seconds, loudness_lufs '
                'FROM songs_cache WHERE path = ?',
                [p],
            )
            if row:
                return row
    return {}


def similar_tracks(db_query, seed_row, limit=40, exclude_paths=None, genre_hint=None):
    exclude = set(exclude_paths or [])
    if seed_row.get('path'):
        exclude.add(seed_row['path'])
    genres = _genre_tokens(seed_row.get('genre'))
    artist = (seed_row.get('artist') or '').strip()
    clauses = ['path IS NOT NULL']
    params = []
    if genres:
        g_clause = []
        for g in list(genres)[:4]:
            g_clause.append('LOWER(COALESCE(genre,"")) LIKE ?')
            params.append(f'%{g}%')
        clauses.append(f'({" OR ".join(g_clause)})')
    elif genre_hint:
        clauses.append('LOWER(COALESCE(genre,"")) LIKE ?')
        params.append(f'%{genre_hint.lower()}%')
    elif artist:
        clauses.append('(LOWER(artist) LIKE ? OR LOWER(COALESCE(genre,"")) != "")')
        params.append(f'%{artist.lower()[:20]}%')
    sql = (
        'SELECT path, title, artist, album, genre, year, duration_seconds, loudness_lufs '
        f'FROM songs_cache WHERE {" AND ".join(clauses)} LIMIT ?'
    )
    params.append(min(limit * 8, 800))
    pool = db_query(sql, params) or []
    scored = []
    for row in pool:
        p = row.get('path')
        if not p or p in exclude:
            continue
        s = score_similarity(seed_row, row)
        if s > 0.5:
            scored.append((s + random.random() * 0.35, row))
    scored.sort(key=lambda x: x[0], reverse=True)
    out = []
    seen_artists = set()
    for _, row in scored:
        a = (row.get('artist') or '').lower()
        if a and a in seen_artists and len(out) > limit // 2:
            continue
        if a:
            seen_artists.add(a)
        out.append(row)
        if len(out) >= limit:
            break
    return out


def build_mix(db_query, db_one, seed_kind, path=None, album=None, artist=None,
              playlist_paths=None, limit=30):
    seed = fetch_seed_row(db_one, db_query, seed_kind, path, album, artist, playlist_paths)
    if not seed or not seed.get('path'):
        raise ValueError('seed_not_found')
    if seed_kind == 'playlist' and playlist_paths:
        seeds = []
        for p in playlist_paths[:12]:
            row = db_one(
                'SELECT path, title, artist, album, genre, year, duration_seconds, loudness_lufs '
                'FROM songs_cache WHERE path = ?',
                [p],
            )
            if row:
                seeds.append(row)
        tracks = []
        seen = set()
        for s in seeds or [seed]:
            for row in similar_tracks(db_query, s, limit=max(8, limit // max(len(seeds), 1)), exclude_paths=seen):
                p = row.get('path')
                if p and p not in seen:
                    seen.add(p)
                    tracks.append(row)
        tracks.sort(key=lambda r: score_similarity(seed, r), reverse=True)
        return seed, tracks[:limit]
    tracks = similar_tracks(db_query, seed, limit=limit)
    return seed, tracks


def mix_title(seed_row, seed_kind):
    title = seed_row.get('title') or 'Track'
    artist = seed_row.get('artist') or ''
    album = seed_row.get('album') or ''
    if seed_kind == 'album' and album:
        return f'Resonance · {album}'
    if seed_kind == 'playlist':
        return 'Resonance · Mix'
    if artist:
        return f'Resonance · {title}'
    return f'Resonance · {title}'
