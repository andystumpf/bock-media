"""Loudness normalization — scan library, apply on /stream."""
import json
import os
import re
import shutil
import subprocess
import threading
import time


def nice_prefix(level=19, io_idle=True):
    """Wrap ffmpeg in nice/ionice so it can't starve the web server or playback.

    Audio transcode/analysis is pure CPU (no GPU path exists for loudnorm or MP3),
    so the only lever is scheduling priority. Batch analysis uses the lowest priority
    (level 19 + idle IO); live stream transcodes use a gentler nice so audio still
    keeps up without dominating the cores.
    """
    prefix = []
    if shutil.which('nice'):
        prefix += ['nice', '-n', str(level)]
    if io_idle and shutil.which('ionice'):
        prefix += ['ionice', '-c', '3']
    return prefix

_ANALYZE_LOCK = threading.Lock()
_ANALYZE_STATE = {
    'running': False,
    'processed': 0,
    'total': 0,
    'lastError': None,
    'startedAt': None,
}

LOUDNESS_MODES = ('off', 'track', 'album', 'loudnorm')
TARGET_LUFS = -14.0


def ensure_songs_cache_columns(get_db, db_query):
    """Add loudness + first_seen columns if missing."""
    cols = {r.get('name') for r in (db_query('PRAGMA table_info(songs_cache)') or [])}
    adds = [
        ('replaygain_track_db', 'REAL'),
        ('replaygain_album_db', 'REAL'),
        ('loudness_lufs', 'REAL'),
        ('loudness_analyzed_at', 'TEXT'),
        ('first_seen_at', 'TEXT'),
    ]
    conn = get_db()
    try:
        for name, typ in adds:
            if name not in cols:
                conn.execute(f'ALTER TABLE songs_cache ADD COLUMN {name} {typ}')
        conn.commit()
    finally:
        conn.close()


def normalize_mode_from_pref(replay_gain_pref):
    raw = (replay_gain_pref or 'off').strip().lower()
    if raw in ('true', '1', 'yes', 'on'):
        return 'track'
    if raw in LOUDNESS_MODES:
        return raw
    return 'off'


def analyze_status():
    with _ANALYZE_LOCK:
        return dict(_ANALYZE_STATE)


def cancel_analyze_job():
    """Signal the running analyze worker to stop after its current file."""
    with _ANALYZE_LOCK:
        if _ANALYZE_STATE.get('running'):
            _ANALYZE_STATE['running'] = False
            return True
    return False


def _parse_loudnorm_json(stderr_text):
    m = re.search(r'\{[\s\S]*"input_i"[\s\S]*\}', stderr_text)
    if not m:
        return None
    try:
        data = json.loads(m.group(0))
        input_i = data.get('input_i')
        if input_i is None:
            return None
        return float(input_i)
    except (json.JSONDecodeError, TypeError, ValueError):
        return None


def analyze_file(full_path, ffmpeg_bin='ffmpeg'):
    """Return (loudness_lufs, replaygain_track_db) or None."""
    if not os.path.isfile(full_path):
        return None
    try:
        proc = subprocess.run(
            nice_prefix(level=19, io_idle=True)
            + [ffmpeg_bin, '-hide_banner', '-nostats', '-threads', '1', '-i', full_path,
               '-af', 'loudnorm=print_format=json', '-f', 'null', '-'],
            capture_output=True,
            text=True,
            timeout=120,
        )
        lufs = _parse_loudnorm_json(proc.stderr or '')
        if lufs is None:
            return None
        gain_db = TARGET_LUFS - lufs
        return lufs, round(gain_db, 2)
    except Exception:
        return None


def run_analyze_job(get_db, db_query, db_one, music_root, ffmpeg_bin='ffmpeg', force=False, limit=None, paths=None):
    """Background: scan songs_cache and store loudness.

    When *paths* is set, only those library paths are analyzed (still respects
    *force* / loudness_analyzed_at unless force=True).
    """
    global _ANALYZE_STATE
    with _ANALYZE_LOCK:
        if _ANALYZE_STATE.get('running'):
            return False
        _ANALYZE_STATE = {
            'running': True,
            'processed': 0,
            'total': 0,
            'lastError': None,
            'startedAt': time.time(),
        }

    def _worker():
        global _ANALYZE_STATE
        try:
            if paths:
                path_list = [p for p in paths if p]
                rows = []
                chunk_size = 400
                extra = '' if force else ' AND loudness_analyzed_at IS NULL'
                for i in range(0, len(path_list), chunk_size):
                    chunk = path_list[i:i + chunk_size]
                    ph = ','.join('?' * len(chunk))
                    rows.extend(
                        db_query(
                            f'SELECT path FROM songs_cache WHERE path IN ({ph})'
                            f' AND path IS NOT NULL AND path != ""{extra}',
                            chunk,
                        ) or [],
                    )
                # Preserve play-order-ish uniqueness while dropping dupes.
                seen = set()
                deduped = []
                for row in rows:
                    p = row.get('path')
                    if p and p not in seen:
                        seen.add(p)
                        deduped.append(row)
                rows = deduped
            else:
                where = 'path IS NOT NULL AND path != ""'
                if not force:
                    where += ' AND loudness_analyzed_at IS NULL'
                rows = db_query(f'SELECT path FROM songs_cache WHERE {where}') or []
            if limit:
                rows = rows[: int(limit)]
            with _ANALYZE_LOCK:
                _ANALYZE_STATE['total'] = len(rows)
            now = time.strftime('%Y-%m-%dT%H:%M:%S')
            conn = get_db()
            # Commit in small batches so progress is durable/resumable and we never
            # hold a write transaction open for hours (which would lock the DB for the
            # rest of the app). On a huge library this job runs for a long time.
            commit_every = 25
            pending = 0
            try:
                for row in rows:
                    if not _ANALYZE_STATE.get('running'):
                        break  # cancelled
                    rel = row.get('path') or ''
                    full = rel if os.path.isabs(rel) else os.path.join(music_root, rel.lstrip('/'))
                    if not full.startswith('/'):
                        full = '/' + full.lstrip('/')
                    result = analyze_file(full, ffmpeg_bin)
                    if result:
                        lufs, gain = result
                        conn.execute(
                            'UPDATE songs_cache SET loudness_lufs=?, replaygain_track_db=?, '
                            'replaygain_album_db=?, loudness_analyzed_at=? WHERE path=?',
                            (lufs, gain, gain, now, rel),
                        )
                        pending += 1
                        if pending >= commit_every:
                            conn.commit()
                            pending = 0
                    with _ANALYZE_LOCK:
                        _ANALYZE_STATE['processed'] += 1
                conn.commit()
            finally:
                try:
                    conn.commit()
                except Exception:
                    pass
                conn.close()
        except Exception as e:
            with _ANALYZE_LOCK:
                _ANALYZE_STATE['lastError'] = str(e)
        finally:
            with _ANALYZE_LOCK:
                _ANALYZE_STATE['running'] = False

    threading.Thread(target=_worker, daemon=True).start()
    return True


def gain_db_for_path(db_one, path, mode):
    if not path or mode not in ('track', 'album', 'loudnorm'):
        return None
    row = db_one(
        'SELECT replaygain_track_db, replaygain_album_db, loudness_lufs FROM songs_cache WHERE path = ?',
        [path],
    ) or {}
    if mode == 'track' and row.get('replaygain_track_db') is not None:
        return float(row['replaygain_track_db'])
    if mode == 'album' and row.get('replaygain_album_db') is not None:
        return float(row['replaygain_album_db'])
    if mode == 'loudnorm':
        if row.get('replaygain_track_db') is not None:
            return float(row['replaygain_track_db'])
        if row.get('loudness_lufs') is not None:
            return TARGET_LUFS - float(row['loudness_lufs'])
    return None


def ffmpeg_af_filter(mode, gain_db, use_loudnorm_fallback=False):
    """Audio filter string for ffmpeg -af."""
    if gain_db is not None:
        return f'volume={gain_db:.2f}dB'
    if use_loudnorm_fallback or mode == 'loudnorm':
        return 'loudnorm=I=-14:TP=-1.5:LRA=11'
    return None


def audio_meta_for_path(db_one, path, stream_base_url, mode):
    """Public metadata for clients."""
    row = db_one(
        'SELECT replaygain_track_db, replaygain_album_db, loudness_lufs, loudness_analyzed_at '
        'FROM songs_cache WHERE path = ?',
        [path],
    ) or {}
    rel = (path or '').lstrip('/')
    url = f'{stream_base_url}/stream/{rel}' if rel else None
    norm_url = f'{url}?normalize=1' if url and mode != 'off' else url
    return {
        'path': path,
        'replaygainTrackDb': row.get('replaygain_track_db'),
        'replaygainAlbumDb': row.get('replaygain_album_db'),
        'loudnessLufs': row.get('loudness_lufs'),
        'analyzedAt': row.get('loudness_analyzed_at'),
        'streamUrl': url,
        'streamUrlWithNorm': norm_url,
        'normalizeMode': mode,
    }
