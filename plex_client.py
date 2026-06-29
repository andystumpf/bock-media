"""Minimal Plex client for two-way playlist sync (read token, resolve a track
by file path, add it to a Plex playlist).

Used by server.py's AddToPlaylistIntent to write voice "add this to <playlist>"
back to Plex (not just the local .m3u). All network calls are best-effort and
swallow errors, returning falsy so callers can fall back to the .m3u append.

Proven by scripts/plex_twoway_spike.py against the live server.
"""
import os
import re
import time
from urllib.parse import quote
from urllib.request import urlopen, Request
import xml.etree.ElementTree as ET

PLEX_URL = os.environ.get('OURMEDIA_PLEX_URL', 'http://localhost:32400').rstrip('/')
PREFS = os.environ.get(
    'OURMEDIA_PLEX_PREFS',
    '/var/lib/plexmediaserver/Library/Application Support/Plex Media Server/Preferences.xml')
MUSIC_SECTION = os.environ.get('OURMEDIA_PLEX_MUSIC_SECTION', '12')

# Playlist .m3u files written by sync_plex_playlists.py are "<name>.<ratingKey>.m3u".
_PLEX_M3U_RE = re.compile(r'\.(\d+)\.m3u$')

_MID_CACHE = {'id': None, 'ts': 0.0}
_MID_TTL = 3600.0


def token():
    t = os.environ.get('OURMEDIA_PLEX_TOKEN')
    if t:
        return t
    try:
        with open(PREFS, encoding='utf-8', errors='replace') as f:
            m = re.search(r'PlexOnlineToken="([^"]+)"', f.read())
        return m.group(1) if m else None
    except Exception:
        return None


def is_configured():
    return bool(token())


def _url(path, tok):
    return f'{PLEX_URL}{path}{"&" if "?" in path else "?"}X-Plex-Token={quote(tok)}'


def _get(path, tok, timeout=20):
    with urlopen(_url(path, tok), timeout=timeout) as r:
        return ET.fromstring(r.read())


def _put(path, tok, timeout=20):
    req = Request(_url(path, tok), method='PUT')
    with urlopen(req, timeout=timeout) as r:
        r.read()


def machine_id(tok=None, timeout=20):
    now = time.time()
    if _MID_CACHE['id'] and (now - _MID_CACHE['ts']) < _MID_TTL:
        return _MID_CACHE['id']
    tok = tok or token()
    if not tok:
        return None
    try:
        mid = _get('/', tok, timeout=timeout).get('machineIdentifier')
    except Exception:
        return None
    if mid:
        _MID_CACHE['id'] = mid
        _MID_CACHE['ts'] = now
    return mid


def status(timeout=2):
    """Health summary: configured + reachable + machine id (best-effort)."""
    tok = token()
    if not tok:
        return {'configured': False, 'reachable': False, 'machineId': None}
    mid = machine_id(tok, timeout=timeout)
    return {'configured': True, 'reachable': bool(mid), 'machineId': mid}


def playlist_ratingkey_from_source(source_path):
    """Extract the Plex playlist ratingKey from a synced .m3u path, or None for
    a non-Plex (My Media) playlist."""
    if not source_path:
        return None
    m = _PLEX_M3U_RE.search(source_path)
    return m.group(1) if m else None


def track_ratingkey_for_path(path, tok=None):
    """Resolve a track file path to its Plex ratingKey, or None."""
    tok = tok or token()
    if not tok or not path:
        return None
    base = os.path.splitext(os.path.basename(path))[0]
    try:
        root = _get(f'/library/sections/{MUSIC_SECTION}/search?type=10&query={quote(base)}', tok)
    except Exception:
        return None
    for tr in root.findall('.//Track'):
        for part in tr.findall('.//Part'):
            if part.get('file') == path:
                return tr.get('ratingKey')
    return None


def add_track_to_playlist(playlist_rating_key, track_path):
    """Add a track (by file path) to a Plex playlist (by ratingKey).
    Returns True on success, False on any failure (caller falls back to .m3u)."""
    tok = token()
    if not tok or not playlist_rating_key:
        return False
    mid = machine_id(tok)
    track_rk = track_ratingkey_for_path(track_path, tok)
    if not mid or not track_rk:
        return False
    uri = f'server://{mid}/com.plexapp.plugins.library/library/metadata/{track_rk}'
    try:
        _put(f'/playlists/{playlist_rating_key}/items?uri={quote(uri, safe="")}', tok)
        return True
    except Exception:
        return False
