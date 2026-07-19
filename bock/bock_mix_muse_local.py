"""Local Mix Muse — Picard metadata + MusicBrainz tags (no LLM)."""
import random
import re

import bock_acquire
import bock_resonance

_FILLER = {
    'the', 'and', 'for', 'with', 'that', 'like', 'songs', 'song', 'music',
    'playlist', 'mix', 'vibe', 'vibes', 'from', 'sound', 'sounds',
}

_MOOD_GENRE_HINTS = {
    'calm': ('ambient', 'acoustic', 'chill', 'easy listening', 'soft', 'lounge', 'classical', 'new age'),
    'relax': ('ambient', 'acoustic', 'chill', 'easy listening', 'soft', 'lounge', 'classical'),
    'peaceful': ('ambient', 'acoustic', 'classical', 'new age', 'folk'),
    'quiet': ('ambient', 'acoustic', 'classical', 'folk', 'easy listening'),
    'morning': ('acoustic', 'folk', 'indie', 'soft rock', 'jazz', 'singer-songwriter', 'bossa nova'),
    'summer': ('acoustic', 'indie', 'soft rock', 'bossa nova', 'lounge', 'folk'),
    'weekday': ('indie', 'acoustic', 'jazz', 'alternative', 'folk'),
    'weekend': ('rock', 'pop', 'dance', 'electronic', 'funk'),
    'evening': ('jazz', 'blues', 'soul', 'lounge', 'ambient'),
    'night': ('electronic', 'ambient', 'jazz', 'r&b', 'downtempo'),
    'energy': ('rock', 'electronic', 'dance', 'punk', 'hip hop'),
    'happy': ('pop', 'funk', 'disco', 'indie pop'),
    'sad': ('blues', 'singer-songwriter', 'indie', 'alternative'),
    'chill': ('ambient', 'acoustic', 'lounge', 'downtempo', 'easy listening'),
}

_CALM_WORDS = {'calm', 'relax', 'peaceful', 'quiet', 'soft', 'gentle', 'morning', 'mellow', 'chill', 'serene', 'easy'}
_HOLIDAY_TERMS = (
    'christmas', 'xmas', 'noel', 'holiday', 'carol', 'jingle', 'santa', 'rudolph',
    'frosty', 'nutcracker', 'wonderland', 'silent night', 'deck the halls',
    'merry', 'yuletide', 'nativity', 'hark the', 'o holy', 'little drummer',
)


def _prompt_words(prompt):
    words = []
    for w in re.split(r'\W+', (prompt or '').lower()):
        if len(w) > 2 and w not in _FILLER:
            words.append(w)
    return words[:16]


def _track_haystack(row):
    return ' '.join([
        str(row.get('title') or ''),
        str(row.get('artist') or ''),
        str(row.get('album') or ''),
        str(row.get('genre') or ''),
    ]).lower()


def _is_holiday_track(row):
    hay = _track_haystack(row)
    return any(term in hay for term in _HOLIDAY_TERMS)


def _mood_penalty(row, words):
    penalty = 0.0
    word_set = set(words)
    calm_request = bool(_CALM_WORDS & word_set)
    if calm_request and _is_holiday_track(row):
        return 100.0
    if calm_request and 'christmas' not in word_set and 'holiday' not in word_set:
        genre = (row.get('genre') or '').lower()
        for loud in ('metal', 'punk', 'hardcore', 'christmas', 'holiday', 'grunge'):
            if loud in genre:
                penalty += 20.0
    return penalty


def _minimum_score(words):
    if _CALM_WORDS & set(words):
        return 2.0
    return 0.5


def _keyword_score(row, words):
    if not words:
        return 0.0
    hay = ' '.join([
        str(row.get('title') or ''),
        str(row.get('artist') or ''),
        str(row.get('album') or ''),
        str(row.get('genre') or ''),
        str(row.get('year') or ''),
    ]).lower()
    score = sum(1.5 for w in words if w in hay)
    genre = (row.get('genre') or '').lower()
    for w in words:
        for hint in _MOOD_GENRE_HINTS.get(w, ()):
            if hint in genre:
                score += 2.5
    return score


def _mb_boosts(seed_artist, db_query, load_config_fn):
    """MusicBrainz tags + in-library related artists for seed scoring."""
    ac = bock_acquire.config(load_config_fn)
    if not ac['enabled'] or not seed_artist:
        return set(), set()
    resolved = bock_acquire._search_artist(seed_artist, ac['userAgent'])
    if not resolved or not resolved.get('mbid'):
        return set(), set()
    detail = bock_acquire._artist_detail(resolved['mbid'], ac['userAgent'])
    if not detail:
        return set(), set()

    tags = set()
    for src in (detail.get('tags') or [], detail.get('genres') or []):
        for t in src[:8]:
            name = (t.get('name') or '').strip().lower()
            if name:
                tags.add(name)

    related_keys = set()
    owned = bock_acquire.owned_artists(db_query)
    seed_norm = bock_acquire._norm_artist(resolved.get('name') or seed_artist)
    for rel in detail.get('relations') or []:
        rtype = (rel.get('type') or '').lower()
        if rtype not in bock_acquire._REL_TYPES:
            continue
        target = rel.get('artist') or {}
        aname = (target.get('name') or '').strip()
        key = bock_acquire._artist_key(aname)
        if key and key != seed_norm and key in owned:
            related_keys.add(key)
    return tags, related_keys


def enrich_candidate_pool(db_query, pool, seed_row, limit=400):
    if not seed_row or not seed_row.get('path'):
        return pool
    similar = bock_resonance.similar_tracks(db_query, seed_row, limit=min(limit // 2, 120))
    seen = {r.get('path') for r in pool if r.get('path')}
    out = list(pool)
    for row in similar:
        p = row.get('path')
        if p and p not in seen:
            out.append(row)
            seen.add(p)
    return out


def _playlist_name(prompt, seed_row):
    p = (prompt or '').strip()
    if p:
        short = p[:48] + ('…' if len(p) > 48 else '')
        return f'Mix Muse · {short}'
    if seed_row:
        title = seed_row.get('title') or 'Similar'
        return f'Mix Muse · {title}'
    return 'Mix Muse Playlist'


def pick_tracks_local(
    prompt,
    candidates,
    max_tracks,
    seed_row=None,
    db_query=None,
    load_config_fn=None,
):
    if not candidates:
        raise ValueError('no_library_matches')

    words = _prompt_words(prompt)
    mb_tags, mb_artists = set(), set()
    if seed_row and db_query and load_config_fn:
        seed_artist = (seed_row.get('artist') or '').strip()
        mb_tags, mb_artists = _mb_boosts(seed_artist, db_query, load_config_fn)

    scored = []
    for row in candidates:
        p = row.get('path')
        if not p:
            continue
        score = _keyword_score(row, words)
        if seed_row:
            sim = bock_resonance.score_similarity(seed_row, row)
            if sim > 0:
                score += sim * 2.5
        genre = (row.get('genre') or '').lower()
        for tag in mb_tags:
            if tag in genre:
                score += 2.5
        artist_key = bock_acquire._artist_key(row.get('artist'))
        if artist_key in mb_artists:
            score += 4.0
        penalty = _mood_penalty(row, words)
        base = score - penalty
        score = base + random.random() * 0.25
        if base >= _minimum_score(words):
            scored.append((score, row))

    if not scored and (_CALM_WORDS & set(words)):
        for row in candidates:
            p = row.get('path')
            if not p or _is_holiday_track(row):
                continue
            genre = (row.get('genre') or '').lower()
            if any(h in genre for w in words for h in _MOOD_GENRE_HINTS.get(w, ())):
                scored.append((random.random() + 2.0, row))

    if not scored:
        scored = [
            (random.random(), r) for r in candidates
            if r.get('path') and not (_CALM_WORDS & set(words) and _is_holiday_track(r))
        ]

    scored.sort(key=lambda x: x[0], reverse=True)
    paths = []
    seen_artists = set()
    for _, row in scored:
        p = row.get('path')
        if not p or p in paths:
            continue
        artist = (row.get('artist') or '').lower()
        if artist and artist in seen_artists and len(paths) > max(4, max_tracks // 2):
            continue
        if artist:
            seen_artists.add(artist)
        paths.append(p)
        if len(paths) >= max_tracks:
            break

    if not paths:
        raise ValueError('local_no_tracks_picked')
    return _playlist_name(prompt, seed_row), paths
