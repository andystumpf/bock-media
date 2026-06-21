"""Echo Show lyrics via Alexa Presentation Language (APL).

Gated by config alexaAplLyrics.enabled (default off). See docs/ECHO_SHOW_APL_LYRICS.md.
"""
from __future__ import annotations

import hashlib
import os

APL_INTERFACE = 'Alexa.Presentation.APL'
PROGRESS_REPORT_MS = 1000
MAX_APL_LINES = 80


def apl_lyrics_enabled() -> bool:
    env = os.environ.get('OURMEDIA_APL_LYRICS', '').strip().lower()
    if env in ('1', 'true', 'yes', 'on'):
        return True
    if env in ('0', 'false', 'no', 'off'):
        return False
    try:
        from server import load_config
        cfg = load_config().get('alexaAplLyrics') or {}
        return bool(cfg.get('enabled'))
    except Exception:
        return False


def device_supports_apl(supported_interfaces, device_id=None) -> bool:
    if isinstance(supported_interfaces, dict) and APL_INTERFACE in supported_interfaces:
        return True
    if device_id:
        try:
            from server import _load_devices
            fp = (_load_devices().get(device_id) or {}).get('fingerprint') or ''
            if APL_INTERFACE in fp:
                return True
        except Exception:
            pass
    return False


def should_use_apl(supported_interfaces, device_id=None) -> bool:
    return apl_lyrics_enabled() and device_supports_apl(supported_interfaces, device_id)


def doc_token(device_id: str, path: str) -> str:
    key = f'{device_id or "default"}:{path or ""}'
    return 'lyrics-' + hashlib.sha256(key.encode('utf-8')).hexdigest()[:20]


def active_lyric_index(lines, position_ms: int) -> int:
    if not lines:
        return -1
    lo, hi, ans = 0, len(lines) - 1, -1
    while lo <= hi:
        mid = (lo + hi) // 2
        if lines[mid].get('timeMs', 0) <= position_ms:
            ans = mid
            lo = mid + 1
        else:
            hi = mid - 1
    return ans


def lyrics_for_path(path: str):
    from server import _lyrics_payload, _lyrics_track_meta
    title, artist, album, dur = _lyrics_track_meta(path)
    payload = _lyrics_payload(path, dur, title, artist, album)
    lines = (payload or {}).get('lines') or []
    return payload or {}, lines, title, artist, album, dur


def build_lyrics_apl_document(title, subtitle, lines, active_index: int):
    capped = lines[:MAX_APL_LINES]
    line_items = []
    for i, line in enumerate(capped):
        active = i == active_index
        line_items.append({
            'type': 'Text',
            'text': (line.get('text') if isinstance(line, dict) else str(line)) or '',
            'fontSize': '40dp' if active else '30dp',
            'fontWeight': 'bold' if active else 'normal',
            'color': '#FFFFFF' if active else '#77FFFFFF',
            'textAlign': 'center',
            'paddingBottom': '12dp',
        })
    if not line_items:
        line_items.append({
            'type': 'Text',
            'text': 'Lyrics unavailable',
            'fontSize': '28dp',
            'color': '#88FFFFFF',
            'textAlign': 'center',
        })
    return {
        'type': 'APL',
        'version': '2023.3',
        'theme': 'dark',
        'background': 'transparent',
        'items': [{
            'type': 'Container',
            'width': '100vw',
            'height': '100vh',
            'paddingTop': '24dp',
            'paddingLeft': '24dp',
            'paddingRight': '24dp',
            'items': [
                {
                    'type': 'Text',
                    'text': title or 'Now playing',
                    'fontSize': '28dp',
                    'fontWeight': 'bold',
                    'color': '#FFFFFF',
                    'textAlign': 'center',
                    'maxLines': 2,
                },
                {
                    'type': 'Text',
                    'text': subtitle or '',
                    'fontSize': '20dp',
                    'color': '#AAAAAA',
                    'textAlign': 'center',
                    'paddingBottom': '16dp',
                    'maxLines': 2,
                },
                {
                    'type': 'ScrollView',
                    'width': '100vw',
                    'height': '72vh',
                    'item': {
                        'type': 'Container',
                        'width': '100vw',
                        'alignItems': 'center',
                        'items': line_items,
                    },
                },
            ],
        }],
    }


def render_lyrics_directive(device_id, path, title, subtitle, lines, active_index: int):
    return {
        'type': 'Alexa.Presentation.APL.RenderDocument',
        'token': doc_token(device_id, path),
        'document': build_lyrics_apl_document(title, subtitle, lines, active_index),
    }


def play_apl_directives(path, offset_ms, title, subtitle, supported_interfaces, device_id):
    """Directives to prepend to AudioPlayer.Play when Echo Show lyrics are enabled."""
    if not should_use_apl(supported_interfaces, device_id) or not path:
        return [], False
    _payload, lines, t, artist, album, _dur = lyrics_for_path(path)
    if not lines:
        return [], False
    sub = subtitle or (f'{artist} · {album}' if artist and album else (artist or album or ''))
    active = active_lyric_index(lines, max(offset_ms, 0))
    return [render_lyrics_directive(device_id, path, title or t, sub, lines, active)], True


def playback_started_directives(path, title, artist, album, supported_interfaces, device_id, offset_ms=0):
    if not should_use_apl(supported_interfaces, device_id) or not path:
        return []
    _payload, lines, t, ar, al, _dur = lyrics_for_path(path)
    if not lines:
        return []
    subtitle = f'{ar} · {al}' if ar and al else (ar or al or '')
    active = active_lyric_index(lines, max(offset_ms, 0))
    return [render_lyrics_directive(device_id, path, title or t, subtitle, lines, active)]


def progress_report_response(req, supported_interfaces, device_id):
    """Build Alexa response for AudioPlayer.PlaybackProgressReport."""
    from flask import jsonify
    if not should_use_apl(supported_interfaces, device_id):
        return jsonify({'version': '1.0', 'response': {}})
    token = req.get('token') or ''
    offset_ms = int(req.get('offsetInMilliseconds') or 0)
    try:
        from server import decode_token
        data = decode_token(token)
        tracks = data.get('tracks') or []
        idx = int(data.get('idx') or 0)
        if not (0 <= idx < len(tracks)):
            return jsonify({'version': '1.0', 'response': {}})
        path = tracks[idx]
    except Exception:
        return jsonify({'version': '1.0', 'response': {}})
    _payload, lines, title, artist, album, _dur = lyrics_for_path(path)
    if not lines:
        return jsonify({'version': '1.0', 'response': {}})
    subtitle = f'{artist} · {album}' if artist and album else (artist or album or '')
    active = active_lyric_index(lines, offset_ms)
    directive = render_lyrics_directive(device_id, path, title, subtitle, lines, active)
    return jsonify({
        'version': '1.0',
        'response': {
            'directives': [directive],
        },
    })


def clear_apl_directive(device_id, path):
    """Minimal blank document when playback ends (optional)."""
    return {
        'type': 'Alexa.Presentation.APL.RenderDocument',
        'token': doc_token(device_id, path or 'idle'),
        'document': {
            'type': 'APL',
            'version': '2023.3',
            'theme': 'dark',
            'items': [{
                'type': 'Text',
                'text': '',
                'fontSize': '1dp',
            }],
        },
    }
