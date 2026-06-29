"""Continue listening / playback resume state."""
import json
import os
import threading
import time

_LOCK = threading.Lock()
MIN_OFFSET_MS = 30_000
MAX_TAIL_MS = 10_000


def load_resume(path):
    with _LOCK:
        if not os.path.isfile(path):
            return {'byMember': {}}
        try:
            with open(path) as f:
                data = json.load(f)
            if not isinstance(data, dict):
                return {'byMember': {}}
            data.setdefault('byMember', {})
            return data
        except Exception:
            return {'byMember': {}}


def save_resume(path, data):
    with _LOCK:
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        tmp = path + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, path)


def _eligible(offset_ms, duration_ms):
    if offset_ms < MIN_OFFSET_MS:
        return False
    if duration_ms and duration_ms - offset_ms < MAX_TAIL_MS:
        return False
    return True


def update_from_playback(path, member_id, body):
    """Persist resume point from client playback report."""
    member_id = (member_id or 'household').strip() or 'household'
    offset_ms = int(body.get('offset_ms') or body.get('offsetMs') or 0)
    duration_ms = int(body.get('duration_ms') or body.get('durationMs') or 0)
    filepath = (body.get('filepath') or body.get('path') or '').strip()
    if not filepath or not _eligible(offset_ms, duration_ms):
        return
    playing = body.get('playing')
    paused = body.get('paused')
    if playing and not paused:
        return
    ctx = {
        'kind': (body.get('contextKind') or body.get('playlistId') and 'playlist' or 'track'),
        'id': (body.get('playlistId') or body.get('playlist_id') or '').strip() or None,
        'name': (body.get('playlist') or body.get('contextName') or '').strip() or None,
    }
    entry = {
        'context': ctx,
        'filepath': filepath,
        'track': (body.get('track') or '').strip(),
        'artist': (body.get('artist') or '').strip(),
        'album': (body.get('album') or '').strip(),
        'trackIndex': int(body.get('trackIndex') or body.get('track_index') or 0),
        'offsetMs': offset_ms,
        'durationMs': duration_ms,
        'updatedAt': time.time(),
    }
    data = load_resume(path)
    data['byMember'][member_id] = entry
    save_resume(path, data)


def get_continue(path, member_id, db_one=None):
    member_id = (member_id or 'household').strip() or 'household'
    data = load_resume(path)
    by = data.get('byMember') or {}
    entry = by.get(member_id) or by.get('household')
    if not entry:
        return {'resume': None, 'recent': []}
    resume = {
        **entry,
        'id': f"{member_id}:{entry.get('filepath', '')}",
        'progress': (entry.get('offsetMs', 0) / entry['durationMs']) if entry.get('durationMs') else 0,
    }
    recent = []
    seen = set()
    for mid, e in sorted(by.items(), key=lambda x: x[1].get('updatedAt', 0), reverse=True):
        key = e.get('context', {}).get('id') or e.get('filepath')
        if key in seen:
            continue
        seen.add(key)
        recent.append({**e, 'memberId': mid, 'id': f"{mid}:{e.get('filepath', '')}"})
        if len(recent) >= 10:
            break
    return {'resume': resume, 'recent': recent}


def dismiss(path, resume_id):
    data = load_resume(path)
    by = data.get('byMember') or {}
    for mid, entry in list(by.items()):
        rid = f"{mid}:{entry.get('filepath', '')}"
        if rid == resume_id:
            by.pop(mid, None)
            break
    data['byMember'] = by
    save_resume(path, data)
    return True
