"""Library hygiene — metadata coverage, Picard work queues, artist merge."""
import os
import sqlite3

AUDIO_WHERE = (
    "(LOWER(path) LIKE '%.mp3' OR LOWER(path) LIKE '%.flac' OR LOWER(path) LIKE '%.m4a' "
    "OR LOWER(path) LIKE '%.aac' OR LOWER(path) LIKE '%.ogg' OR LOWER(path) LIKE '%.opus' "
    "OR LOWER(path) LIKE '%.wma' OR LOWER(path) LIKE '%.wav' OR LOWER(path) LIKE '%.aiff' "
    "OR LOWER(path) LIKE '%.aif')"
)

_UNTAGGED_MISS = (
    '((genre IS NULL OR TRIM(genre) = "") OR '
    '(album_artist IS NULL OR TRIM(album_artist) = ""))'
)


def metadata_summary(db_query):
    """Fast DB-only coverage counts for audio rows in songs_cache."""
    audio = db_query(f'''
        SELECT COUNT(*) AS n FROM songs_cache
        WHERE path IS NOT NULL AND path != "" AND {AUDIO_WHERE}
    ''')
    total = int((audio[0] if audio else {}).get('n') or 0)
    if total <= 0:
        return {'totalTracks': 0, 'missingGenre': 0, 'missingAlbumArtist': 0, 'needsAttention': 0}

    def empty_count(col):
        rows = db_query(f'''
            SELECT COUNT(*) AS n FROM songs_cache
            WHERE path IS NOT NULL AND path != "" AND {AUDIO_WHERE}
              AND ({col} IS NULL OR TRIM({col}) = "")
        ''')
        return int((rows[0] if rows else {}).get('n') or 0)

    missing_genre = empty_count('genre')
    missing_aa = empty_count('album_artist')
    needs = db_query(f'''
        SELECT COUNT(*) AS n FROM songs_cache
        WHERE path IS NOT NULL AND path != "" AND {AUDIO_WHERE} AND {_UNTAGGED_MISS}
    ''')
    needs_n = int((needs[0] if needs else {}).get('n') or 0)
    return {
        'totalTracks': total,
        'missingGenre': missing_genre,
        'missingAlbumArtist': missing_aa,
        'needsAttention': needs_n,
    }


def top_untagged_dirs(db_path, limit=5):
    """Parent folders ranked by tracks missing genre or album artist."""
    if not os.path.isfile(db_path):
        return []
    limit = max(1, min(int(limit or 5), 20))
    conn = sqlite3.connect(f'file:{db_path}?mode=ro', uri=True)
    conn.row_factory = sqlite3.Row
    rows = conn.execute(f'''
        SELECT path FROM songs_cache
        WHERE path IS NOT NULL AND path != "" AND {AUDIO_WHERE} AND {_UNTAGGED_MISS}
    ''').fetchall()
    conn.close()
    counts = {}
    for row in rows:
        path = row['path']
        if not path:
            continue
        parent = os.path.dirname(path)
        if parent:
            counts[parent] = counts.get(parent, 0) + 1
    ranked = sorted(counts.items(), key=lambda x: (-x[1], x[0]))
    return [{'path': p, 'trackCount': c} for p, c in ranked[:limit]]


def duplicate_artist_groups(db_query, limit=10):
    """Case-insensitive artist names that appear with multiple spellings."""
    rows = db_query('''
        SELECT LOWER(TRIM(artist)) AS key, TRIM(artist) AS name, COUNT(*) AS tracks
        FROM songs_cache
        WHERE artist IS NOT NULL AND TRIM(artist) != ""
        GROUP BY TRIM(artist)
    ''') or []
    buckets = {}
    for row in rows:
        key = (row.get('key') or '').strip()
        name = (row.get('name') or '').strip()
        if not key or not name:
            continue
        buckets.setdefault(key, []).append({
            'name': name,
            'tracks': int(row.get('tracks') or 0),
        })
    out = []
    for variants in buckets.values():
        if len(variants) < 2:
            continue
        variants.sort(key=lambda v: (-v['tracks'], v['name'].lower()))
        out.append({
            'canonical': variants[0]['name'],
            'variants': [v['name'] for v in variants],
            'totalTracks': sum(v['tracks'] for v in variants),
        })
    out.sort(key=lambda g: (-g['totalTracks'], g['canonical'].lower()))
    return out[: max(1, min(int(limit or 10), 50))]


def library_health(db_path, db_query, attention_limit=5, duplicate_limit=10):
    summary = metadata_summary(db_query)
    return {
        'summary': summary,
        'attentionFolders': top_untagged_dirs(db_path, limit=attention_limit),
        'duplicateArtists': duplicate_artist_groups(db_query, limit=duplicate_limit),
    }


def merge_artists(db_execute, from_names, to_name):
    """Rename artist (and matching album_artist) in songs_cache — DB only."""
    to_name = (to_name or '').strip()
    if not to_name:
        raise ValueError('to required')
    cleaned = []
    for raw in from_names or []:
        name = (raw or '').strip()
        if name and name not in cleaned and name != to_name:
            cleaned.append(name)
    if not cleaned:
        raise ValueError('from required')
    updated = 0
    for name in cleaned:
        updated += db_execute(
            'UPDATE songs_cache SET artist = ? WHERE artist = ?',
            [to_name, name],
        )
        updated += db_execute(
            'UPDATE songs_cache SET album_artist = ? WHERE album_artist = ?',
            [to_name, name],
        )
    return {'ok': True, 'to': to_name, 'from': cleaned, 'rowsUpdated': updated}


def album_star_averages(ratings_path, member_id, db_query, batch_size=400):
    """Map (album, artist) -> {avgStars, ratedCount} from per-profile song ratings."""
    import bock_ratings

    ratings = [
        r for r in bock_ratings.list_ratings(ratings_path, member_id)
        if (r.get('kind') or '') == 'song' and int(r.get('stars') or 0) > 0
    ]
    if not ratings:
        return {}
    path_stars = {r['id']: int(r['stars']) for r in ratings if r.get('id')}
    paths = list(path_stars.keys())
    buckets = {}
    for i in range(0, len(paths), batch_size):
        chunk = paths[i:i + batch_size]
        ph = ','.join('?' * len(chunk))
        rows = db_query(
            f'SELECT path, album, artist FROM songs_cache WHERE path IN ({ph})',
            chunk,
        ) or []
        for row in rows:
            path = row.get('path')
            stars = path_stars.get(path)
            if stars is None:
                continue
            album = (row.get('album') or '').strip()
            artist = (row.get('artist') or '').strip()
            if not album:
                continue
            key = (album, artist)
            acc = buckets.setdefault(key, {'sum': 0, 'count': 0})
            acc['sum'] += stars
            acc['count'] += 1
    return {
        key: {
            'avgStars': round(acc['sum'] / acc['count'], 1),
            'ratedCount': acc['count'],
        }
        for key, acc in buckets.items()
        if acc['count'] > 0
    }
