#!/usr/bin/env python3
import sqlite3
import xml.etree.ElementTree as ET
import os
import json
import math
import glob
import shutil
import base64
import random
import time
import difflib
import re
import subprocess
import logging
import socket
import datetime
from logging.handlers import RotatingFileHandler
from urllib.parse import quote
from flask import Flask, jsonify, request, send_from_directory, send_file, Response

HERE = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__, static_folder=os.path.join(HERE, 'public'))

DB_PATH = '/mnt/bock/Music/music_organizer.db'
MMA_PATH = '/home/plex/.MyMediaForAlexa'

# ── DB helper ────────────────────────────────────────────────────────────────

def get_db():
    conn = sqlite3.connect(f'file:{DB_PATH}?mode=ro', uri=True)
    conn.row_factory = sqlite3.Row
    return conn

def db_query(sql, params=()):
    try:
        conn = get_db()
        cur = conn.execute(sql, params)
        rows = [dict(r) for r in cur.fetchall()]
        conn.close()
        return rows
    except Exception as e:
        print(f'DB error: {e}')
        return []

def db_one(sql, params=()):
    rows = db_query(sql, params)
    return rows[0] if rows else {}

# ── XML helper ───────────────────────────────────────────────────────────────

def xml_text(el, tag, default=''):
    child = el.find(tag)
    return (child.text or default) if child is not None else default

def xml_int(el, tag, default=0):
    try:
        return int(xml_text(el, tag, str(default)))
    except:
        return default

def get_pref(xml_tag, default=''):
    try:
        tree = ET.parse(os.path.join(MMA_PATH, 'Preferences.xml'))
        el = tree.getroot().find(xml_tag)
        return (el.text or default) if el is not None else default
    except:
        return default

# ── Logging ───────────────────────────────────────────────────────────────────

LOG_PATH = os.path.join(HERE, 'server.log')

def apply_logging():
    if get_pref('VerboseLogging', '').lower() == 'true':
        handler = RotatingFileHandler(LOG_PATH, maxBytes=5 * 1024 * 1024, backupCount=3)
        handler.setLevel(logging.INFO)
        handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(message)s'))
        app.logger.addHandler(handler)
        app.logger.setLevel(logging.INFO)
        log = logging.getLogger('werkzeug')
        log.addHandler(handler)

# ── Auth ──────────────────────────────────────────────────────────────────────

# Paths that Alexa fetches directly — must never require browser auth
_NO_AUTH_PREFIXES = ('/alexa', '/stream/', '/artwork/')

@app.before_request
def check_auth():
    if request.path.startswith('/stream/') or request.path.startswith('/artwork/'):
        ua = request.headers.get('User-Agent', '')
        rng = request.headers.get('Range', '')
        print(f"[STREAM] {request.method} {request.path} ua={ua!r} range={rng!r}", flush=True)
    if any(request.path.startswith(p) for p in _NO_AUTH_PREFIXES):
        return None
    if get_pref('RequirePassword', '').lower() != 'true':
        return None
    stored = get_pref('WebPassword', '').strip()
    if not stored:
        return None
    auth = request.authorization
    if not auth or auth.username != 'admin' or auth.password != stored:
        return Response('Authentication required', 401,
                        {'WWW-Authenticate': 'Basic realm="Our Media"'})
    return None

# ── Static files ─────────────────────────────────────────────────────────────

PUBLIC = os.path.join(HERE, 'public')

@app.route('/')
def index():
    return send_from_directory(PUBLIC, 'index.html')

@app.route('/<path:filename>')
def static_files(filename):
    return send_from_directory(PUBLIC, filename)

# ── API: Summary ─────────────────────────────────────────────────────────────

@app.route('/api/summary')
def summary():
    songs = db_one('SELECT COUNT(*) as count FROM songs_cache')
    artists = db_one('SELECT COUNT(DISTINCT artist) as count FROM songs_cache WHERE artist IS NOT NULL AND artist != ""')
    albums = db_one('SELECT COUNT(DISTINCT album) as count FROM songs_cache WHERE album IS NOT NULL AND album != ""')

    watch_folders = 0
    playlists = 0
    try:
        wf = ET.parse(os.path.join(MMA_PATH, 'WatchFolders.xml'))
        watch_folders = len(wf.getroot().findall('WatchFolder'))
    except:
        pass
    try:
        pl = ET.parse(os.path.join(MMA_PATH, 'ServerPlaylists.xml'))
        playlists = len(pl.getroot().findall('Entry'))
    except:
        pass

    return jsonify({
        'songs': songs.get('count', 0),
        'artists': artists.get('count', 0),
        'albums': albums.get('count', 0),
        'playlists': playlists,
        'watchFolders': watch_folders,
    })

# ── API: Watch Folders ───────────────────────────────────────────────────────

@app.route('/api/watchfolders')
def watchfolders():
    try:
        tree = ET.parse(os.path.join(MMA_PATH, 'WatchFolders.xml'))
        folders = []
        for wf in tree.getroot().findall('WatchFolder'):
            folders.append({
                'guid': xml_text(wf, 'Guid'),
                'path': xml_text(wf, 'Path'),
                'label': xml_text(wf, 'Label'),
                'status': xml_text(wf, 'Status'),
                'count': xml_int(wf, 'Count'),
                'identifiedFiles': xml_int(wf, 'IdentifiedFiles'),
                'errors': xml_int(wf, 'Errors'),
                'playlists': xml_int(wf, 'Playlists'),
                'type': xml_text(wf, 'Type'),
            })
        return jsonify(folders)
    except Exception as e:
        return jsonify([])

# ── API: Playlists ───────────────────────────────────────────────────────────

@app.route('/api/playlists')
def playlists():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 50))
    search = request.args.get('search', '').lower()

    try:
        tree = ET.parse(os.path.join(MMA_PATH, 'ServerPlaylists.xml'))
        all_playlists = []
        for entry in tree.getroot().findall('Entry'):
            key = entry.find('Key')
            if key is None:
                continue
            name = xml_text(key, 'Name')
            if search and search not in name.lower():
                continue
            all_playlists.append({
                'id': xml_text(key, 'ID'),
                'name': name,
                'trackCount': xml_int(key, 'TrackCount'),
                'shuffle': xml_text(key, 'Shuffle') == 'true',
                'loop': xml_text(key, 'Loop') == 'true',
                'createDate': xml_text(key, 'CreateDate'),
                'lastUsed': xml_text(key, 'LastUsed'),
                'source': xml_text(key, 'SourceID'),
                'isAudioBook': xml_text(key, 'IsAudioBook') == 'true',
            })

        total = len(all_playlists)
        start = (page - 1) * limit
        items = all_playlists[start:start + limit]
        return jsonify({'items': items, 'total': total})
    except Exception as e:
        print(f'Playlists error: {e}')
        return jsonify({'items': [], 'total': 0})

@app.route('/api/playlists/rename', methods=['POST'])
def rename_playlist():
    """Rename a playlist by its stable ID. Both UI and Alexa fuzzy match
    will then use the new name immediately (no restart needed)."""
    data = request.get_json() or {}
    pid     = (data.get('id') or '').strip()
    new_name = (data.get('name') or '').strip()
    if not pid:
        return jsonify({'error': 'id required'}), 400
    if not new_name:
        return jsonify({'error': 'name required'}), 400
    try:
        path = os.path.join(MMA_PATH, 'ServerPlaylists.xml')
        ET.register_namespace('xsd', 'http://www.w3.org/2001/XMLSchema')
        ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
        tree = ET.parse(path)
        root = tree.getroot()
        target = None
        for entry in root.findall('Entry'):
            key = entry.find('Key')
            if key is None:
                continue
            if (key.findtext('ID') or '') == pid:
                target = key
                break
        if target is None:
            return jsonify({'error': 'unknown playlist id'}), 404
        name_el = target.find('Name')
        if name_el is None:
            name_el = ET.SubElement(target, 'Name')
        old = name_el.text or ''
        name_el.text = new_name
        tree.write(path, xml_declaration=True, encoding='utf-8')
        return jsonify({'ok': True, 'id': pid, 'oldName': old, 'name': new_name})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

# ── API: Artists ─────────────────────────────────────────────────────────────

@app.route('/api/artists')
def artists():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 50))
    search = request.args.get('search', '')
    offset = (page - 1) * limit

    where = 'artist IS NOT NULL AND artist != ""'
    params = []
    if search:
        where += ' AND artist LIKE ?'
        params.append(f'%{search}%')

    rows = db_query(
        f'SELECT artist, COUNT(*) as track_count, COUNT(DISTINCT album) as album_count '
        f'FROM songs_cache WHERE {where} GROUP BY artist ORDER BY artist LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    total_row = db_one(
        f'SELECT COUNT(DISTINCT artist) as total FROM songs_cache WHERE {where}',
        params
    )
    return jsonify({'items': rows, 'total': total_row.get('total', 0)})

# ── API: Albums ──────────────────────────────────────────────────────────────

@app.route('/api/albums')
def albums():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 50))
    search = request.args.get('search', '')
    artist = request.args.get('artist', '')
    offset = (page - 1) * limit

    conditions = ['album IS NOT NULL', 'album != ""']
    params = []
    if search:
        conditions.append('(album LIKE ? OR album_artist LIKE ? OR artist LIKE ?)')
        params += [f'%{search}%', f'%{search}%', f'%{search}%']
    if artist:
        conditions.append('(artist = ? OR album_artist = ?)')
        params += [artist, artist]

    where = ' AND '.join(conditions)

    rows = db_query(
        f'SELECT album, COALESCE(NULLIF(album_artist,""), artist) as artist, COUNT(*) as track_count '
        f'FROM songs_cache WHERE {where} GROUP BY album, artist ORDER BY album LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    total_row = db_one(
        f'SELECT COUNT(DISTINCT album) as total FROM songs_cache WHERE {where}',
        params
    )
    return jsonify({'items': rows, 'total': total_row.get('total', 0)})

# ── API: Songs ───────────────────────────────────────────────────────────────

@app.route('/api/songs')
def songs():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 100))
    search = request.args.get('search', '')
    artist = request.args.get('artist', '')
    album = request.args.get('album', '')
    offset = (page - 1) * limit

    conditions = ['1=1']
    params = []
    if search:
        conditions.append('(title LIKE ? OR artist LIKE ? OR album LIKE ?)')
        params += [f'%{search}%', f'%{search}%', f'%{search}%']
    if artist:
        conditions.append('artist = ?')
        params.append(artist)
    if album:
        conditions.append('album = ?')
        params.append(album)

    where = ' AND '.join(conditions)

    rows = db_query(
        f'SELECT id, title, artist, album, genre, year, duration_seconds, bitrate, track_number, path '
        f'FROM songs_cache WHERE {where} '
        f'ORDER BY artist, album, CAST(track_number AS INTEGER), title LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    total_row = db_one(
        f'SELECT COUNT(*) as total FROM songs_cache WHERE {where}',
        params
    )
    return jsonify({'items': rows, 'total': total_row.get('total', 0)})

# ── API: Settings ────────────────────────────────────────────────────────────

# Maps frontend key → XML tag name
SETTINGS_MAP = {
    'label':                'Label',
    'pairedUser':           'PairedUser',
    'defaultPlaylist':      'DefaultPlaylist',
    'defaultPlaylistShuffle': 'DefaultPlaylistShuffle',
    'watchFolderPollHours': 'WatchFolderPollHours',
    'transcodeBitrate':     'TranscodeBitrate',
    'ffmpegLocation':       'FFmpegLocation',
    'flacSupport':          'FlacSupport',
    'replayGain':           'ReplayGain',
    'requirePassword':      'RequirePassword',
    'autoImportPlaylists':  'AutoImportPlaylists',
    'suppressAutoScan':     'SuppressAutoScan',
    'sendAlbumArt':         'SendAlbumArt',
    'sendMetadata':         'SendMetadata',
    'verboseLogging':       'VerboseLogging',
    'scanIgnoreFiles':      'ScanIgnoreFiles',
    'bypassProxy':          'BypassProxy',
    'allowExternalAccess':  'AllowExternalAccess',
    'webPassword':          'WebPassword',
}

@app.route('/api/settings', methods=['GET'])
def settings_get():
    try:
        tree = ET.parse(os.path.join(MMA_PATH, 'Preferences.xml'))
        root = tree.getroot()
        def t(tag): return (root.find(tag).text or '') if root.find(tag) is not None else ''
        return jsonify({k: t(v) for k, v in SETTINGS_MAP.items()})
    except Exception as e:
        return jsonify({})

@app.route('/api/settings', methods=['POST'])
def settings_post():
    data = request.get_json() or {}
    prefs_path = os.path.join(MMA_PATH, 'Preferences.xml')
    try:
        ET.register_namespace('xsd', 'http://www.w3.org/2001/XMLSchema')
        ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
        tree = ET.parse(prefs_path)
        root = tree.getroot()
        changed = []
        for key, value in data.items():
            xml_tag = SETTINGS_MAP.get(key)
            if not xml_tag:
                continue
            el = root.find(xml_tag)
            if el is None:
                el = ET.SubElement(root, xml_tag)
            el.text = str(value)
            changed.append(key)
        if changed:
            tree.write(prefs_path, xml_declaration=True, encoding='unicode')
        return jsonify({'ok': True, 'saved': changed})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/clearcache', methods=['POST'])
def clear_cache():
    cache_dir = os.path.join(MMA_PATH, 'ImageCache')
    try:
        deleted = 0
        for f in glob.glob(os.path.join(cache_dir, '*')):
            if os.path.isfile(f):
                os.remove(f)
                deleted += 1
            elif os.path.isdir(f):
                shutil.rmtree(f)
                deleted += 1
        return jsonify({'ok': True, 'deleted': deleted})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

# ── API: Devices ─────────────────────────────────────────────────────────────

DEVICES_PATH = os.path.join(HERE, 'devices.json')

def _load_devices():
    try:
        with open(DEVICES_PATH) as f:
            data = json.load(f)
        if isinstance(data, dict):
            return data
    except Exception:
        pass
    return {}

def _save_devices(data):
    try:
        with open(DEVICES_PATH, 'w') as f:
            json.dump(data, f, indent=2)
    except Exception as e:
        print(f'devices write error: {e}', flush=True)

def register_device(device_id, default_name=None):
    if not device_id:
        return
    data = _load_devices()
    entry = data.get(device_id)
    now = time.time()
    if not entry:
        data[device_id] = {
            'name': default_name or f'Echo {device_id[-6:]}',
            'firstSeen': now,
            'lastSeen': now,
        }
    else:
        entry['lastSeen'] = now
        if default_name and not entry.get('name'):
            entry['name'] = default_name
    _save_devices(data)

def device_friendly_name(device_id):
    if not device_id:
        return ''
    return (_load_devices().get(device_id) or {}).get('name') or ''

@app.route('/api/devices')
def devices():
    data = _load_devices()
    result = []
    for did, e in data.items():
        result.append({
            'deviceId':  did,
            'name':      e.get('name') or did[-6:],
            'lastSeen':  e.get('lastSeen'),
            'firstSeen': e.get('firstSeen'),
        })
    result.sort(key=lambda x: x.get('lastSeen') or 0, reverse=True)
    return jsonify(result)

@app.route('/api/devices/<device_id>', methods=['POST'])
def rename_device(device_id):
    data = request.get_json()
    new_name = (data or {}).get('name', '').strip()
    if not new_name:
        return jsonify({'error': 'Name required'}), 400
    try:
        store = _load_devices()
        entry = store.get(device_id)
        if not entry:
            return jsonify({'error': 'Unknown device'}), 404
        entry['name'] = new_name
        _save_devices(store)
        return jsonify({'ok': True})
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/api/devices/<device_id>', methods=['DELETE'])
def remove_device(device_id):
    store = _load_devices()
    if device_id in store:
        del store[device_id]
        _save_devices(store)
    return jsonify({'ok': True})

# ── API: Recent play requests ────────────────────────────────────────────────

CRITERIA_TYPES = ['Playlist', 'Song', 'Genre', 'Artist', 'Album']

@app.route('/api/recent')
def recent():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 10))
    try:
        tree = ET.parse(os.path.join(MMA_PATH, 'PlaylistHistory.xml'))
        all_entries = list(tree.getroot().findall('Entry'))
        all_entries.reverse()  # most recent first

        result = []
        for entry in all_entries:
            val = entry.find('Value')
            if val is None:
                continue
            d = {c.tag: c.text for c in val}
            heard_type = heard_val = found_type = found_val = None
            for ctype in CRITERIA_TYPES:
                if f'Criteria{ctype}' in d and d[f'Criteria{ctype}']:
                    heard_type = ctype
                    heard_val = d[f'Criteria{ctype}']
                if f'Found{ctype}' in d and d[f'Found{ctype}']:
                    found_type = ctype
                    found_val = d[f'Found{ctype}']
            if not heard_val:
                continue
            track_count = d.get('TrackCount', '')
            result.append({
                'heard': f'{heard_type} = {heard_val}' if heard_type else heard_val,
                'found': f'{found_type} = {found_val} ({track_count})' if found_type else f'({track_count})',
                'success': d.get('Success', '0') == '1',
                'timestamp': d.get('TimeStamp', ''),
            })

        total = len(result)
        start = (page - 1) * limit
        return jsonify({'items': result[start:start + limit], 'total': total})
    except Exception as e:
        print(f'Recent error: {e}')
        return jsonify({'items': [], 'total': 0})

# ── API: Now Playing (recent streaming events from Messages.xml) ─────────────

import re as _re

def _parse_stream_message(title, description):
    """Extract track, artist, device from a streaming message."""
    track = artist = device = filepath = None
    # Title: "Streaming the track <Title> by <Artist> to '<Device>'"
    m = _re.match(r"Streaming the track (.+?) by (.+?) to '(.+)'", title or '')
    if m:
        track, artist, device = m.group(1), m.group(2), m.group(3)
    # Description has the filename
    m2 = _re.search(r'The filename was: (.+)', description or '')
    if m2:
        filepath = m2.group(1).strip()
    return {'track': track, 'artist': artist, 'device': device, 'filepath': filepath}

STREAM_HISTORY_PATH = os.path.join(HERE, 'streaming_history.jsonl')
_STREAM_HISTORY_MAX = 5000

def append_stream_history(entry):
    try:
        with open(STREAM_HISTORY_PATH, 'a') as f:
            f.write(json.dumps(entry) + '\n')
    except Exception as e:
        print(f'history write error: {e}', flush=True)

def _read_stream_history():
    rows = []
    try:
        with open(STREAM_HISTORY_PATH) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except:
                    continue
    except FileNotFoundError:
        pass
    if len(rows) > _STREAM_HISTORY_MAX:
        rows = rows[-_STREAM_HISTORY_MAX:]
        try:
            with open(STREAM_HISTORY_PATH, 'w') as f:
                for r in rows:
                    f.write(json.dumps(r) + '\n')
        except Exception:
            pass
    return rows

@app.route('/api/nowplaying')
def now_playing():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 20))
    try:
        rows = _read_stream_history()
        rows.reverse()
        total = len(rows)
        start = (page - 1) * limit
        return jsonify({'items': rows[start:start + limit], 'total': total})
    except Exception as e:
        print(f'Now playing error: {e}')
        return jsonify({'items': [], 'total': 0})

# ── Config ───────────────────────────────────────────────────────────────────

CONFIG_PATH = os.path.join(HERE, 'config.json')

def load_config():
    try:
        with open(CONFIG_PATH) as f:
            return json.load(f)
    except:
        return {}

def get_public_url():
    return load_config().get('publicUrl', '').rstrip('/')

@app.route('/api/localip')
def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
    except Exception:
        ip = '127.0.0.1'
    return jsonify({'ip': ip})

@app.route('/api/config', methods=['GET', 'POST'])
def config_endpoint():
    if request.method == 'GET':
        return jsonify(load_config())
    data = request.get_json() or {}
    cfg = load_config()
    cfg.update(data)
    with open(CONFIG_PATH, 'w') as f:
        json.dump(cfg, f, indent=2)
    return jsonify({'ok': True})

# ── Audio Streaming ───────────────────────────────────────────────────────────

MUSIC_ROOT = '/mnt/bock/Music'
NATIVE_EXTS   = {'.mp3', '.m4a', '.aac'}
TRANSCODE_EXTS = {'.flac', '.wma', '.wav', '.ogg', '.aif', '.aiff'}
SUPPORTED_EXTS = NATIVE_EXTS | TRANSCODE_EXTS

_ART_BASE_NAMES = ('cover', 'folder', 'album', 'front', 'artwork', 'albumart', 'thumb')
_ART_EXTS = ('.jpg', '.jpeg', '.png', '.webp')

ARTWORK_CACHE = os.path.join(HERE, 'artwork_cache')

def _sidecar_artwork(audio_path):
    """Look for cover.jpg / folder.png / etc. in the track's dir or its parent."""
    for d in (os.path.dirname(audio_path), os.path.dirname(os.path.dirname(audio_path))):
        if not d or not os.path.isdir(d):
            continue
        try:
            entries = os.listdir(d)
        except OSError:
            continue
        for f in entries:
            low = f.lower()
            base, ext = os.path.splitext(low)
            if ext in _ART_EXTS and base in _ART_BASE_NAMES:
                return os.path.join(d, f)
    return None

def _embedded_artwork(audio_path):
    """Extract embedded ID3/MP4 cover art into ARTWORK_CACHE; return file path."""
    import hashlib
    h = hashlib.sha1(audio_path.encode('utf-8')).hexdigest()
    for ext in ('.jpg', '.png'):
        cached = os.path.join(ARTWORK_CACHE, h + ext)
        if os.path.isfile(cached):
            return cached
    try:
        from mutagen import File as MutaFile
        mf = MutaFile(audio_path)
        if mf is None or not getattr(mf, 'tags', None):
            return None
        data, mime = None, 'image/jpeg'
        for k, v in mf.tags.items():
            if k.startswith('APIC'):
                data, mime = v.data, (v.mime or 'image/jpeg')
                break
        if data is None:
            covers = mf.tags.get('covr') if hasattr(mf.tags, 'get') else None
            if covers:
                data = bytes(covers[0])
                fmt = getattr(covers[0], 'imageformat', None)
                mime = 'image/png' if fmt == 14 else 'image/jpeg'
        if not data:
            return None
        ext = '.png' if 'png' in mime.lower() else '.jpg'
        os.makedirs(ARTWORK_CACHE, exist_ok=True)
        out = os.path.join(ARTWORK_CACHE, h + ext)
        with open(out, 'wb') as f:
            f.write(data)
        return out
    except Exception as e:
        print(f'embedded-art error {audio_path}: {e}', flush=True)
        return None

_ALBUM_ART_CACHE = {}  # (album, artist) -> file path
_REMOTE_ART_NEG_CACHE = set()  # negative cache so we don't re-hit iTunes per track
_REMOTE_ART_TIMEOUT = 4  # seconds, total
_ITUNES_SEARCH_URL = 'https://itunes.apple.com/search'

def _remote_album_artwork(audio_path):
    """iTunes Search API fallback — caches under ARTWORK_CACHE."""
    row = db_one('SELECT album, artist FROM songs_cache WHERE path = ?', [audio_path]) or {}
    album  = (row.get('album') or '').strip()
    artist = (row.get('artist') or '').strip()
    if not album:
        return None
    import hashlib
    from urllib.parse import urlencode
    from urllib.request import Request, urlopen
    key = (album.lower(), artist.lower())
    h = hashlib.sha1(f'{artist}|{album}'.encode('utf-8')).hexdigest()
    out = os.path.join(ARTWORK_CACHE, f'remote-{h}.jpg')
    if os.path.isfile(out):
        return out
    if key in _REMOTE_ART_NEG_CACHE:
        return None
    try:
        term = f'{artist} {album}' if artist else album
        url = f'{_ITUNES_SEARCH_URL}?' + urlencode({
            'term': term, 'entity': 'album', 'limit': 1, 'media': 'music'
        })
        req = Request(url, headers={'User-Agent': 'ourMedia/1.0'})
        with urlopen(req, timeout=_REMOTE_ART_TIMEOUT) as r:
            data = json.loads(r.read().decode('utf-8', errors='replace'))
        results = data.get('results') or []
        art_url = (results[0].get('artworkUrl100') if results else None)
        if not art_url:
            _REMOTE_ART_NEG_CACHE.add(key)
            return None
        art_url = re.sub(r'/\d+x\d+(bb)?\.', '/1000x1000bb.', art_url)
        os.makedirs(ARTWORK_CACHE, exist_ok=True)
        with urlopen(Request(art_url, headers={'User-Agent': 'ourMedia/1.0'}),
                     timeout=_REMOTE_ART_TIMEOUT) as r:
            blob = r.read()
        if not blob:
            _REMOTE_ART_NEG_CACHE.add(key)
            return None
        with open(out, 'wb') as f:
            f.write(blob)
        return out
    except Exception as e:
        print(f'remote art lookup failed {key}: {e}', flush=True)
        _REMOTE_ART_NEG_CACHE.add(key)
        return None

def _album_fallback_artwork(audio_path):
    """If this track has no art, scan up to 30 other tracks in the same album."""
    row = db_one('SELECT album, artist FROM songs_cache WHERE path = ?', [audio_path]) or {}
    album  = (row.get('album') or '').strip()
    artist = (row.get('artist') or '').strip()
    if not album:
        return None
    key = (album.lower(), artist.lower())
    if key in _ALBUM_ART_CACHE:
        cached = _ALBUM_ART_CACHE[key]
        return cached if cached and os.path.isfile(cached) else None
    others = db_query(
        "SELECT path FROM songs_cache WHERE album = ? AND COALESCE(artist,'') = ? "
        "AND path IS NOT NULL AND path != ? LIMIT 30",
        [album, artist, audio_path]
    )
    for r in others:
        p = r['path']
        if not p or not os.path.isfile(p):
            continue
        art = _sidecar_artwork(p) or _embedded_artwork(p)
        if art:
            _ALBUM_ART_CACHE[key] = art
            return art
    _ALBUM_ART_CACHE[key] = None
    return None

def _default_artwork():
    """Static placeholder served when nothing else is found."""
    p = os.path.join(app.static_folder, 'img', 'default-art.png')
    return p if os.path.isfile(p) else None

def find_artwork(audio_path):
    return (_sidecar_artwork(audio_path)
            or _embedded_artwork(audio_path)
            or _album_fallback_artwork(audio_path)
            or _remote_album_artwork(audio_path)
            or _default_artwork())

@app.route('/stream/<path:filepath>')
def stream_audio(filepath):
    full_path = '/' + filepath
    if not os.path.abspath(full_path).startswith(MUSIC_ROOT):
        return 'Forbidden', 403
    if not os.path.isfile(full_path):
        return 'Not found', 404
    ext = os.path.splitext(full_path)[1].lower()

    if ext in TRANSCODE_EXTS:
        if get_pref('FlacSupport', '').lower() != 'true':
            return 'Transcoding not enabled', 415
        ffmpeg_bin = get_pref('FFmpegLocation', '').strip() or 'ffmpeg'
        bitrate    = get_pref('TranscodeBitrate', '128').strip() or '128'
        def _generate():
            proc = subprocess.Popen(
                [ffmpeg_bin, '-i', full_path, '-ar', '44100', '-ac', '2',
                 '-b:a', f'{bitrate}k', '-f', 'mp3', '-'],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL
            )
            try:
                while True:
                    chunk = proc.stdout.read(16384)
                    if not chunk:
                        break
                    yield chunk
            finally:
                proc.terminate()
        return Response(_generate(), mimetype='audio/mpeg',
                        headers={'Accept-Ranges': 'none'})

    mime = 'audio/mpeg' if ext == '.mp3' else 'audio/mp4' if ext == '.m4a' else 'audio/aac'
    return send_file(full_path, mimetype=mime, conditional=True)

_ART_MIME_BY_EXT = {
    '.jpg':  'image/jpeg', '.jpeg': 'image/jpeg',
    '.png':  'image/png',  '.webp': 'image/webp',
}

@app.route('/artwork/<path:filepath>')
def serve_artwork(filepath):
    full_path = '/' + filepath
    abs_path = os.path.abspath(full_path)
    allowed_roots = (
        MUSIC_ROOT,
        os.path.abspath(ARTWORK_CACHE),
        os.path.abspath(os.path.join(app.static_folder, 'img')),
    )
    if not any(abs_path.startswith(r) for r in allowed_roots):
        return 'Forbidden', 403
    if not os.path.isfile(abs_path):
        return 'Not found', 404
    mime = _ART_MIME_BY_EXT.get(os.path.splitext(abs_path)[1].lower(), 'image/jpeg')
    return send_file(abs_path, mimetype=mime)

def file_to_stream_url(filepath):
    rel = filepath.lstrip('/')
    return f"{get_public_url()}/stream/{quote(rel, safe='/')}"

def can_stream_track(path):
    """True if this track can be served by current settings/runtime."""
    if not path or not os.path.isfile(path):
        return False
    ext = os.path.splitext(path)[1].lower()
    if ext in NATIVE_EXTS:
        return True
    if ext in TRANSCODE_EXTS:
        if get_pref('FlacSupport', '').lower() != 'true':
            return False
        ffmpeg_bin = get_pref('FFmpegLocation', '').strip() or 'ffmpeg'
        # If ffmpeg is unavailable, transcoded tracks will always fail at runtime.
        return shutil.which(ffmpeg_bin) is not None
    return False

def normalize_track_queue(tracks):
    """Return tracks that can actually be streamed right now."""
    return [p for p in tracks if can_stream_track(p)]

# ── Token encode/decode ───────────────────────────────────────────────────────

QUEUES_PATH = os.path.join(HERE, 'queues.json')
_QUEUE_TTL_SECONDS = 24 * 3600

def _load_queues():
    try:
        with open(QUEUES_PATH) as f:
            return json.load(f)
    except:
        return {}

def _save_queues(queues):
    try:
        with open(QUEUES_PATH, 'w') as f:
            json.dump(queues, f)
    except Exception as e:
        print(f'Queue save error: {e}')

def _new_queue_id():
    return base64.urlsafe_b64encode(os.urandom(9)).decode().rstrip('=')

def _store_queue(tracks, shuffle=False, loop=False):
    queues = _load_queues()
    now = time.time()
    queues = {k: v for k, v in queues.items() if now - v.get('ts', 0) < _QUEUE_TTL_SECONDS}
    qid = _new_queue_id()
    queues[qid] = {'tracks': list(tracks), 'shuffle': bool(shuffle),
                   'loop': bool(loop), 'ts': now}
    _save_queues(queues)
    return qid

def encode_token(data):
    qid = data.get('qid')
    idx = int(data.get('idx', 0) or 0)
    if not qid:
        qid = _store_queue(data.get('tracks', []),
                           data.get('shuffle', False),
                           data.get('loop', False))
    return f"{qid}:{idx}"

def decode_token(token):
    try:
        token = token or ''
        if ':' in token:
            qid, idx = token.split(':', 1)
            queues = _load_queues()
            entry = queues.get(qid)
            if not entry:
                return {}
            return {
                'qid': qid,
                'tracks': entry.get('tracks', []),
                'idx': int(idx),
                'shuffle': entry.get('shuffle', False),
                'loop': entry.get('loop', False),
            }
        padding = 4 - len(token) % 4
        return json.loads(base64.urlsafe_b64decode(token + '=' * padding))
    except:
        return {}

# ── M3U Parser ───────────────────────────────────────────────────────────────

def parse_m3u(filepath):
    tracks = []
    base_dir = os.path.dirname(filepath)
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                path = line if os.path.isabs(line) else os.path.normpath(os.path.join(base_dir, line))
                if os.path.isfile(path) and os.path.splitext(path)[1].lower() in SUPPORTED_EXTS:
                    tracks.append(path)
    except Exception as e:
        print(f'M3U parse error {filepath}: {e}')
    return tracks

# ── Fuzzy Search ─────────────────────────────────────────────────────────────

def fuzzy_find_playlist(query):
    try:
        tree = ET.parse(os.path.join(MMA_PATH, 'ServerPlaylists.xml'))
        entries = []
        for e in tree.getroot().findall('Entry'):
            key = e.find('Key')
            if key is not None:
                name = xml_text(key, 'Name')
                source = xml_text(key, 'SourceID')
                if name:
                    entries.append((name, source))
        q = query.lower()
        # Exact → contains → fuzzy
        for name, src in entries:
            if name.lower() == q:
                return name, src
        for name, src in entries:
            if q in name.lower() or name.lower() in q:
                return name, src
        names = [e[0] for e in entries]
        matches = difflib.get_close_matches(query, names, n=1, cutoff=0.4)
        if matches:
            for name, src in entries:
                if name == matches[0]:
                    return name, src
    except Exception as e:
        print(f'Playlist search error: {e}')
    return None, None

def fuzzy_find_artist(query):
    q = query.lower()
    rows = db_query(
        "SELECT DISTINCT artist FROM songs_cache WHERE LOWER(artist) LIKE ? AND artist IS NOT NULL LIMIT 5",
        [f'%{q}%']
    )
    if rows:
        return rows[0]['artist']
    sample = db_query(
        "SELECT DISTINCT artist FROM songs_cache WHERE artist IS NOT NULL AND artist != '' LIMIT 10000"
    )
    names = [r['artist'] for r in sample]
    matches = difflib.get_close_matches(query, names, n=1, cutoff=0.5)
    return matches[0] if matches else None

def fuzzy_find_album(query):
    q = query.lower()
    rows = db_query(
        "SELECT DISTINCT album FROM songs_cache WHERE LOWER(album) LIKE ? AND album IS NOT NULL LIMIT 5",
        [f'%{q}%']
    )
    if rows:
        return rows[0]['album']
    sample = db_query(
        "SELECT DISTINCT album FROM songs_cache WHERE album IS NOT NULL AND album != '' LIMIT 10000"
    )
    names = [r['album'] for r in sample]
    matches = difflib.get_close_matches(query, names, n=1, cutoff=0.5)
    return matches[0] if matches else None

def fuzzy_find_track(title, artist=None):
    """Return list of file paths matching title, optionally narrowed by artist."""
    q = title.lower()
    if artist:
        rows = db_query(
            "SELECT path FROM songs_cache WHERE LOWER(title) LIKE ? AND LOWER(artist) LIKE ? "
            "AND path IS NOT NULL LIMIT 50",
            [f'%{q}%', f'%{artist.lower()}%']
        )
    else:
        rows = db_query(
            "SELECT path FROM songs_cache WHERE LOWER(title) LIKE ? AND path IS NOT NULL LIMIT 50",
            [f'%{q}%']
        )
    if rows:
        return [r['path'] for r in rows]
    sample = db_query(
        "SELECT title, path FROM songs_cache WHERE title IS NOT NULL AND title != '' LIMIT 30000"
    )
    names = [r['title'] for r in sample]
    matches = difflib.get_close_matches(title, names, n=5, cutoff=0.6)
    results = []
    for m in matches:
        for r in sample:
            if r['title'] == m and r.get('path'):
                results.append(r['path'])
                break
    return results

def normalize_spoken_value(value):
    """Strip invocation bleed-through that can appear in slot values."""
    v = (value or '').strip()
    if not v:
        return ''
    prefixes = [
        'alexa ask local media to ',
        'ask local media to ',
        'local media to ',
        'alexa ask local media ',
        'ask local media ',
        'local media ',
    ]
    lowered = v.lower()
    changed = True
    while changed:
        changed = False
        for p in prefixes:
            if lowered.startswith(p):
                v = v[len(p):].strip()
                lowered = v.lower()
                changed = True
    return v

def fuzzy_find_genre(query):
    """Match a genre string against DB genre field. Returns (genre, None) for DB hit
    or (None, playlist_name, source) when a genre playlist matches. Returns None on miss."""
    q = query.lower()
    rows = db_query(
        "SELECT DISTINCT genre FROM songs_cache WHERE LOWER(genre) LIKE ? AND genre IS NOT NULL LIMIT 5",
        [f'%{q}%']
    )
    if rows:
        return rows[0]['genre']
    sample = db_query(
        "SELECT DISTINCT genre FROM songs_cache WHERE genre IS NOT NULL AND genre != '' LIMIT 2000"
    )
    names = [r['genre'] for r in sample]
    matches = difflib.get_close_matches(query, names, n=1, cutoff=0.5)
    return matches[0] if matches else None

def general_search_tracks(query, limit=300):
    """Try playlist → artist → album → track and return (tracks, description)."""
    name, source = fuzzy_find_playlist(query)
    if name and source and os.path.isfile(source):
        tracks = parse_m3u(source)
        if tracks:
            return tracks, f"Playing playlist {name}.", False
    artist = fuzzy_find_artist(query)
    if artist:
        rows = db_query(
            "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
            "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT ?",
            [artist, limit]
        )
        tracks = [r['path'] for r in rows]
        if tracks:
            return tracks, f"Playing music by {artist}.", True
    album = fuzzy_find_album(query)
    if album:
        rows = db_query(
            "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
            "AND SUBSTR(path,-4) = '.mp3' ORDER BY CAST(track_number AS INTEGER), title LIMIT 50",
            [album]
        )
        tracks = [r['path'] for r in rows]
        if tracks:
            return tracks, f"Playing the album {album}.", False
    tracks = fuzzy_find_track(query)
    if tracks:
        return tracks, f"Playing {query}.", False
    return [], None, False

# ── Now Playing State ─────────────────────────────────────────────────────────

NP_STATE_PATH = os.path.join(HERE, 'nowplaying_state.json')
_NP_DEVICE_TTL_SECONDS = 6 * 3600

# Per-request device id (set by the Alexa handler).
from flask import g

def _current_device_id():
    return getattr(g, 'device_id', '') or 'default'

def _read_all_np():
    try:
        with open(NP_STATE_PATH) as f:
            data = json.load(f)
    except:
        return {}
    if isinstance(data, dict) and 'devices' in data:
        return data
    return {}

def _write_all_np(payload):
    try:
        with open(NP_STATE_PATH, 'w') as f:
            json.dump(payload, f)
    except Exception as e:
        print(f'NP write error: {e}', flush=True)

def _prune_np(payload):
    now = time.time()
    devices = payload.get('devices', {})
    keep = {}
    for did, st in devices.items():
        ts = st.get('timestamp', 0) or 0
        if not st.get('playing') and now - ts > _NP_DEVICE_TTL_SECONDS:
            continue
        keep[did] = st
    payload['devices'] = keep
    return payload

def write_np_state(data):
    did = _current_device_id()
    if did == 'default':
        return
    payload = _read_all_np() or {'devices': {}}
    devices = payload.setdefault('devices', {})
    if not data:
        devices.pop(did, None)
    else:
        devices[did] = data
    _prune_np(payload)
    _write_all_np(payload)

def read_np_state():
    payload = _read_all_np()
    devices = payload.get('devices', {}) if payload else {}
    return devices.get(_current_device_id())

def remove_np_state():
    payload = _read_all_np() or {'devices': {}}
    payload.get('devices', {}).pop(_current_device_id(), None)
    _write_all_np(payload)

@app.route('/api/currenttrack')
def current_track():
    g.device_id = request.args.get('deviceId') or 'default'
    return jsonify(read_np_state() or {})

@app.route('/api/nowplaying_devices')
def nowplaying_devices():
    payload = _prune_np(_read_all_np() or {'devices': {}})
    _write_all_np(payload)
    devices = payload.get('devices', {})
    known = set(_load_devices().keys())
    items = []
    for did, st in devices.items():
        if not st.get('playing'):
            continue
        if did == 'default' or did not in known:
            continue
        items.append({
            'deviceId':   did,
            'deviceName': device_friendly_name(did) or did[-6:],
            'track':      st.get('track'),
            'artist':     st.get('artist'),
            'album':      st.get('album'),
            'filepath':   st.get('filepath'),
            'timestamp':  st.get('timestamp'),
        })
    items.sort(key=lambda x: x.get('timestamp') or 0, reverse=True)
    return jsonify({'items': items})

# ── Selected State (for "play this" / "play what's showing") ─────────────────

SELECTED_PATH = os.path.join(HERE, 'selected_state.json')

def read_selected():
    try:
        with open(SELECTED_PATH) as f:
            return json.load(f)
    except:
        return None

def write_selected(data):
    with open(SELECTED_PATH, 'w') as f:
        json.dump(data, f)

@app.route('/api/selected', methods=['GET', 'POST'])
def selected_endpoint():
    if request.method == 'GET':
        return jsonify(read_selected() or {})
    write_selected(request.get_json() or {})
    return jsonify({'ok': True})

# ── Ignore List ───────────────────────────────────────────────────────────────

IGNORE_PATH = os.path.join(HERE, 'ignored_tracks.json')

def get_ignored():
    try:
        with open(IGNORE_PATH) as f:
            return json.load(f)
    except:
        return []

def add_ignored(path):
    ignored = get_ignored()
    if path not in ignored:
        ignored.append(path)
        with open(IGNORE_PATH, 'w') as f:
            json.dump(ignored, f)

# ── Alexa Response Helpers ────────────────────────────────────────────────────

def alexa_speak(text, end_session=True):
    return jsonify({
        'version': '1.0',
        'response': {
            'outputSpeech': {'type': 'PlainText', 'text': text},
            'shouldEndSession': end_session
        }
    })

def alexa_play(stream_url, token, offset_ms=0, previous_token=None,
               play_behavior='REPLACE_ALL', speech=None,
               title=None, subtitle=None, artwork_url=None):
    audio_item = {
        'stream': {
            'url': stream_url,
            'token': token,
            'offsetInMilliseconds': offset_ms,
        }
    }
    if previous_token:
        audio_item['stream']['expectedPreviousToken'] = previous_token

    # Metadata for Echo Show / Spot display
    send_meta = get_pref('SendMetadata', 'true').lower() != 'false'
    send_art  = get_pref('SendAlbumArt', 'true').lower() != 'false'
    if send_meta and (title or subtitle or artwork_url):
        meta = {}
        if title:
            meta['title'] = title
        if subtitle:
            meta['subtitle'] = subtitle
        if send_art and artwork_url:
            sources = [
                {'url': artwork_url, 'size': 'SMALL',  'widthPixels': 480,  'heightPixels': 480},
                {'url': artwork_url, 'size': 'MEDIUM', 'widthPixels': 720,  'heightPixels': 720},
                {'url': artwork_url, 'size': 'LARGE',  'widthPixels': 1200, 'heightPixels': 1200},
            ]
            meta['art']             = {'sources': sources}
            meta['backgroundImage'] = {'sources': sources}
        if meta:
            audio_item['metadata'] = meta

    resp = {
        'version': '1.0',
        'response': {
            'directives': [{'type': 'AudioPlayer.Play',
                            'playBehavior': play_behavior,
                            'audioItem': audio_item}],
            'shouldEndSession': True
        }
    }
    if speech:
        resp['response']['outputSpeech'] = {'type': 'PlainText', 'text': speech}
    return jsonify(resp)

def alexa_stop():
    return jsonify({'version': '1.0', 'response': {
        'directives': [{'type': 'AudioPlayer.Stop'}],
        'shouldEndSession': True
    }})

def alexa_empty():
    return jsonify({'version': '1.0', 'response': {}})

# ── Play from track list ──────────────────────────────────────────────────────

def track_metadata(path):
    """Return (title, artist, album, artwork_url) for a file path."""
    row = db_one('SELECT title, artist, album FROM songs_cache WHERE path = ?', [path])
    fname = os.path.splitext(os.path.basename(path))[0]
    title   = row.get('title', fname) or fname
    artist  = row.get('artist') or None
    album   = row.get('album') or None
    art_path = find_artwork(path)
    artwork_url = (
        f"{get_public_url()}/artwork/{quote(art_path.lstrip('/'), safe='/')}"
        if art_path else None
    )
    return title, artist, album, artwork_url

def start_playing(tracks, shuffle=False, speech=None, loop=False):
    queue = normalize_track_queue(tracks)
    if not queue:
        return alexa_speak("Sorry, I couldn't find any tracks to play.")
    if shuffle:
        random.shuffle(queue)
    first = queue[0]
    token_data = {'tracks': queue[:300], 'idx': 0, 'shuffle': shuffle, 'loop': loop}
    token = encode_token(token_data)
    title, artist, album, artwork_url = track_metadata(first)
    # playing=False until Alexa confirms PlaybackStarted
    write_np_state({'track': None, 'artist': artist, 'album': album,
                    'filepath': first, 'token': token,
                    'playing': False, 'timestamp': time.time()})
    return alexa_play(file_to_stream_url(first), token, speech=speech,
                      title=title, subtitle=artist, artwork_url=artwork_url)

# ── Alexa Skill Endpoint ──────────────────────────────────────────────────────

@app.route('/alexa', methods=['POST'])
def alexa_skill():
    body = request.get_json(force=True) or {}
    req  = body.get('request', {})
    rtype = req.get('type', '')
    ctx_device = ((body.get('context', {}) or {}).get('System', {}) or {}).get('device', {}) or {}
    g.device_id = ctx_device.get('deviceId') or 'default'
    if g.device_id and g.device_id != 'default':
        register_device(g.device_id)
    intent_name = (req.get('intent', {}) or {}).get('name', '') if rtype == 'IntentRequest' else ''
    error_summary = ''
    if rtype == 'AudioPlayer.PlaybackFailed':
        err = req.get('error', {}) or {}
        error_summary = f" error_type={err.get('type','')} error_message={err.get('message','')}"
    print(f"[ALEXA] type={rtype} intent={intent_name} device={g.device_id[-12:]}{error_summary}", flush=True)

    # ── AudioPlayer events ─────────────────────────────────────────────────

    if rtype == 'AudioPlayer.PlaybackStarted':
        token = req.get('token', '')
        data  = decode_token(token)
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        if 0 <= idx < len(tracks):
            path = tracks[idx]
            fname = os.path.splitext(os.path.basename(path))[0]
            row = db_one('SELECT title, artist, album FROM songs_cache WHERE path = ?', [path]) or {}
            track_title = row.get('title', fname) or fname
            artist = row.get('artist')
            album = row.get('album')
            write_np_state({
                'track':     track_title,
                'artist':    artist,
                'album':     album,
                'filepath':  path,
                'token':     token,
                'playing':   True,
                'timestamp': time.time(),
            })
            device_label = device_friendly_name(g.device_id) or (g.device_id[-12:] if g.device_id else 'default')
            append_stream_history({
                'track':    track_title,
                'artist':   artist,
                'album':    album,
                'filepath': path,
                'device':   device_label,
                'deviceId': g.device_id,
                'date':     datetime.datetime.now().isoformat(timespec='seconds'),
            })
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackNearlyFinished':
        token = req.get('token', '')
        data  = decode_token(token)
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        next_idx = idx + 1
        if next_idx >= len(tracks):
            if data.get('loop'):
                next_idx = 0
            else:
                return alexa_empty()
        if next_idx < len(tracks):
            next_path  = tracks[next_idx]
            next_token = encode_token({**data, 'idx': next_idx})
            nt, na, _, nart = track_metadata(next_path)
            return alexa_play(file_to_stream_url(next_path), next_token,
                              previous_token=token, play_behavior='ENQUEUE',
                              title=nt, subtitle=na, artwork_url=nart)
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackFinished':
        state = read_np_state() or {}
        state['playing'] = False
        write_np_state(state)
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackFailed':
        token = req.get('token', '')
        data  = decode_token(token)
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        next_idx = idx + 1
        if next_idx >= len(tracks):
            if data.get('loop'):
                next_idx = 0
            else:
                state = read_np_state() or {}
                state['playing'] = False
                write_np_state(state)
                return alexa_empty()
        if next_idx < len(tracks):
            next_path = tracks[next_idx]
            next_token = encode_token({**data, 'idx': next_idx})
            nt, na, _, nart = track_metadata(next_path)
            # Replace failed stream with next track in queue.
            return alexa_play(file_to_stream_url(next_path), next_token,
                              play_behavior='REPLACE_ALL',
                              title=nt, subtitle=na, artwork_url=nart)
        state = read_np_state() or {}
        state['playing'] = False
        write_np_state(state)
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackStopped':
        state = read_np_state() or {}
        state['playing'] = False
        state['offset_ms'] = req.get('offsetInMilliseconds', 0)
        write_np_state(state)
        return alexa_empty()

    if rtype in ('ExceptionEncountered', 'PlaybackController.NextCommandIssued',
                 'PlaybackController.PreviousCommandIssued'):
        return alexa_empty()

    # ── Launch ─────────────────────────────────────────────────────────────

    if rtype == 'LaunchRequest':
        default_pl = get_pref('DefaultPlaylist', '').strip()
        if default_pl:
            name, source = fuzzy_find_playlist(default_pl)
            if name and source and os.path.isfile(source):
                tracks = parse_m3u(source)
                if tracks:
                    do_shuffle = get_pref('DefaultPlaylistShuffle', '').lower() == 'true'
                    return start_playing(tracks, shuffle=do_shuffle,
                                        speech=f"Playing {name}.")
        return alexa_speak(
            "Welcome to Our Media. Say play followed by a playlist name, "
            "play music by an artist, or play an album name.",
            end_session=False
        )

    # ── Intents ─────────────────────────────────────────────────────────────

    if rtype == 'IntentRequest':
        intent = req.get('intent', {})
        iname  = intent.get('name', '')
        slots  = intent.get('slots', {})
        def sv(name): return normalize_spoken_value((slots.get(name, {}).get('value') or '').strip())

        # ── Play playlist ──────────────────────────────────────────────────
        if iname == 'PlayPlaylistIntent':
            query = sv('PlaylistName')
            if not query:
                return alexa_speak("Which playlist would you like to play?", end_session=False)
            name, source = fuzzy_find_playlist(query)
            if not name:
                return alexa_speak(f"Sorry, I couldn't find a playlist called {query}.")
            tracks = parse_m3u(source) if source and os.path.isfile(source) else []
            if not tracks:
                return alexa_speak(f"The playlist {name} has no playable tracks.")
            return start_playing(tracks, speech=f"Playing {name}.")

        # ── Shuffle playlist ───────────────────────────────────────────────
        elif iname == 'ShufflePlaylistIntent':
            query = sv('PlaylistName')
            if not query:
                return alexa_speak("Which playlist would you like to shuffle?", end_session=False)
            name, source = fuzzy_find_playlist(query)
            if not name:
                return alexa_speak(f"Sorry, I couldn't find a playlist called {query}.")
            tracks = parse_m3u(source) if source and os.path.isfile(source) else []
            if not tracks:
                return alexa_speak(f"The playlist {name} has no playable tracks.")
            return start_playing(tracks, shuffle=True, speech=f"Shuffling {name}.")

        # ── Play artist ────────────────────────────────────────────────────
        elif iname == 'PlayArtistIntent':
            query = sv('ArtistName')
            if not query:
                return alexa_speak("Which artist would you like to play?", end_session=False)
            artist = fuzzy_find_artist(query)
            if not artist:
                return alexa_speak(f"Sorry, I couldn't find any music by {query}.")
            rows = db_query(
                "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
                "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT 300",
                [artist]
            )
            tracks = [r['path'] for r in rows]
            if not tracks:
                return alexa_speak(f"I found {artist} but no playable files.")
            return start_playing(tracks, shuffle=True, speech=f"Playing music by {artist}.")

        # ── Shuffle artist ─────────────────────────────────────────────────
        elif iname == 'ShuffleArtistIntent':
            query = sv('ArtistName')
            if not query:
                return alexa_speak("Which artist would you like to shuffle?", end_session=False)
            artist = fuzzy_find_artist(query)
            if not artist:
                return alexa_speak(f"Sorry, I couldn't find any music by {query}.")
            rows = db_query(
                "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
                "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT 300",
                [artist]
            )
            tracks = [r['path'] for r in rows]
            if not tracks:
                return alexa_speak(f"I found {artist} but no playable files.")
            return start_playing(tracks, shuffle=True, speech=f"Shuffling music by {artist}.")

        # ── Play album ─────────────────────────────────────────────────────
        elif iname == 'PlayAlbumIntent':
            query = sv('AlbumName')
            if not query:
                return alexa_speak("Which album would you like to play?", end_session=False)
            album = fuzzy_find_album(query)
            if not album:
                return alexa_speak(f"Sorry, I couldn't find the album {query}.")
            rows = db_query(
                "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
                "AND SUBSTR(path,-4) = '.mp3' ORDER BY CAST(track_number AS INTEGER), title LIMIT 50",
                [album]
            )
            tracks = [r['path'] for r in rows]
            if not tracks:
                return alexa_speak(f"I found {album} but no playable files.")
            return start_playing(tracks, speech=f"Playing the album {album}.")

        # ── Shuffle album ──────────────────────────────────────────────────
        elif iname == 'ShuffleAlbumIntent':
            query = sv('AlbumName')
            if not query:
                return alexa_speak("Which album would you like to shuffle?", end_session=False)
            album = fuzzy_find_album(query)
            if not album:
                return alexa_speak(f"Sorry, I couldn't find the album {query}.")
            rows = db_query(
                "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
                "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT 50",
                [album]
            )
            tracks = [r['path'] for r in rows]
            if not tracks:
                return alexa_speak(f"I found {album} but no playable files.")
            return start_playing(tracks, shuffle=True, speech=f"Shuffling the album {album}.")

        # ── Play specific track ────────────────────────────────────────────
        elif iname == 'PlayTrackIntent':
            query = sv('TrackName')
            if not query:
                return alexa_speak("Which track would you like to play?", end_session=False)

            # Recovery path: some devices/tests can misroute "play ... playlist"
            # utterances into PlayTrackIntent with invocation text in TrackName.
            q = query.strip()
            q_lower = q.lower()
            if 'playlist' in q_lower:
                pl_q = re.sub(r'^(?:play\s+)?(?:my\s+)?(?:the\s+)?playlist\s+', '', q, flags=re.IGNORECASE).strip()
                pl_q = re.sub(r'\s+playlist$', '', pl_q, flags=re.IGNORECASE).strip()
                if not pl_q:
                    pl_q = q
                name, source = fuzzy_find_playlist(pl_q)
                if name and source and os.path.isfile(source):
                    tracks = parse_m3u(source)
                    if tracks:
                        return start_playing(tracks, speech=f"Playing {name}.")

            tracks = fuzzy_find_track(query)
            if not tracks:
                return alexa_speak(f"Sorry, I couldn't find a track called {query}.")
            return start_playing(tracks, speech=f"Playing {query}.")

        # ── Play track by artist ───────────────────────────────────────────
        elif iname == 'PlayTrackByArtistIntent':
            track_q  = sv('TrackName')
            artist_q = sv('ArtistName')
            if not track_q:
                return alexa_speak("Which song would you like to play?", end_session=False)
            tracks = fuzzy_find_track(track_q, artist_q or None)
            if not tracks:
                msg = f"{track_q} by {artist_q}" if artist_q else track_q
                return alexa_speak(f"Sorry, I couldn't find {msg}.")
            label = f"{track_q} by {artist_q}" if artist_q else track_q
            return start_playing(tracks, speech=f"Playing {label}.")

        # ── Play genre ─────────────────────────────────────────────────────
        elif iname == 'PlayGenreIntent':
            query = sv('Genre')
            if not query:
                return alexa_speak("Which genre would you like to play?", end_session=False)
            genre = fuzzy_find_genre(query)
            if genre:
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE genre = ? AND path IS NOT NULL "
                    "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT 300",
                    [genre]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Playing {genre} music.")
            # Fall back to playlist name matching (e.g. "Jazz Classics", "Soft Rock")
            name, source = fuzzy_find_playlist(query)
            if name and source and os.path.isfile(source):
                tracks = parse_m3u(source)
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Playing {name}.")
            return alexa_speak(f"Sorry, I couldn't find music in the genre {query}.")

        # ── Shuffle genre ──────────────────────────────────────────────────
        elif iname == 'ShuffleGenreIntent':
            query = sv('Genre')
            if not query:
                return alexa_speak("Which genre would you like to shuffle?", end_session=False)
            genre = fuzzy_find_genre(query)
            if genre:
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE genre = ? AND path IS NOT NULL "
                    "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT 300",
                    [genre]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Shuffling {genre} music.")
            name, source = fuzzy_find_playlist(query)
            if name and source and os.path.isfile(source):
                tracks = parse_m3u(source)
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Shuffling {name}.")
            return alexa_speak(f"Sorry, I couldn't find music in the genre {query}.")

        # ── General "play X" — tries playlist → artist → album → track ────
        elif iname == 'PlayGeneralIntent':
            query = sv('Query')
            if not query:
                return alexa_speak("What would you like to play?", end_session=False)
            tracks, speech, do_shuffle = general_search_tracks(query)
            if not tracks:
                return alexa_speak(f"Sorry, I couldn't find anything matching {query}.")
            return start_playing(tracks, shuffle=do_shuffle, speech=speech)

        # ── Read audio book ────────────────────────────────────────────────
        elif iname == 'ReadBookIntent':
            query = sv('BookName')
            if not query:
                return alexa_speak("Which audio book would you like to hear?", end_session=False)
            name, source = fuzzy_find_playlist(query)
            if not name or not source or not os.path.isfile(source):
                # Fall back to searching by album name
                album = fuzzy_find_album(query)
                if album:
                    rows = db_query(
                        "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
                        "AND SUBSTR(path,-4) = '.mp3' ORDER BY CAST(track_number AS INTEGER), title LIMIT 100",
                        [album]
                    )
                    tracks = [r['path'] for r in rows]
                    if tracks:
                        return start_playing(tracks, speech=f"Reading {album}.")
                return alexa_speak(f"Sorry, I couldn't find an audio book called {query}.")
            tracks = parse_m3u(source)
            if not tracks:
                return alexa_speak(f"The book {name} has no playable tracks.")
            return start_playing(tracks, speech=f"Reading {name}.")

        # ── Play current selection (set via web UI) ────────────────────────
        elif iname == 'PlayCurrentIntent':
            sel = read_selected()
            if not sel:
                return alexa_speak(
                    "Nothing is currently selected in the console. "
                    "Open the ourMedia web app and browse to a song, album, or artist first."
                )
            sel_type = sel.get('type', '')
            query    = sel.get('name', '')
            if sel_type == 'track':
                path = sel.get('path', '')
                if path and os.path.isfile(path):
                    return start_playing([path], speech=f"Playing {query}.")
            elif sel_type == 'album':
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
                    "AND SUBSTR(path,-4) = '.mp3' ORDER BY CAST(track_number AS INTEGER), title LIMIT 50",
                    [query]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, speech=f"Playing the album {query}.")
            elif sel_type == 'artist':
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
                    "AND SUBSTR(path,-4) = '.mp3' ORDER BY RANDOM() LIMIT 300",
                    [query]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Playing music by {query}.")
            elif sel_type == 'playlist':
                source = sel.get('source', '')
                if source and os.path.isfile(source):
                    tracks = parse_m3u(source)
                    if tracks:
                        return start_playing(tracks, speech=f"Playing {query}.")
            return alexa_speak(f"I couldn't play what's showing. Try selecting something in the console.")

        # ── What's playing ─────────────────────────────────────────────────
        elif iname == 'WhatsPlayingIntent':
            state = read_np_state()
            if not state or not state.get('track'):
                return alexa_speak("Nothing is currently playing.")
            track  = state.get('track', 'Unknown')
            artist = state.get('artist', '')
            album  = state.get('album', '')
            msg = f"You're listening to {track}"
            if artist:
                msg += f" by {artist}"
            if album:
                msg += f" from the album {album}"
            return alexa_speak(msg + ".", end_session=False)

        # ── Add current track to playlist ──────────────────────────────────
        elif iname == 'AddToPlaylistIntent':
            playlist_q = sv('PlaylistName')
            state = read_np_state() or {}
            current_path = state.get('filepath')
            if not current_path or not os.path.isfile(current_path):
                return alexa_speak("Nothing is currently playing to add.")
            if not playlist_q:
                return alexa_speak("Which playlist would you like to add this to?", end_session=False)
            name, source = fuzzy_find_playlist(playlist_q)
            if not name or not source:
                return alexa_speak(f"I couldn't find a playlist called {playlist_q}.")
            try:
                with open(source, 'a') as f:
                    f.write(f'\n{current_path}')
                track_name = state.get('track', 'the current track')
                return alexa_speak(f"Added {track_name} to {name}.")
            except Exception as e:
                print(f'AddToPlaylist error: {e}')
                return alexa_speak(f"Sorry, I couldn't add to {name}.")

        # ── Ignore/skip current song ───────────────────────────────────────
        elif iname == 'IgnoreSongIntent':
            state = read_np_state() or {}
            current_path = state.get('filepath')
            token = state.get('token', '')
            data  = decode_token(token)
            tracks = data.get('tracks', [])
            idx    = data.get('idx', 0)
            if current_path:
                add_ignored(current_path)
            next_idx = idx + 1
            if next_idx < len(tracks):
                next_path  = tracks[next_idx]
                next_token = encode_token({**data, 'idx': next_idx})
                return alexa_play(file_to_stream_url(next_path), next_token,
                                  speech="OK, skipping that song.")
            return alexa_speak("Song ignored. There are no more tracks.")

        # ── Server management ──────────────────────────────────────────────
        elif iname == 'ListServersIntent':
            return alexa_speak("You have one server: ourMedia.", end_session=False)

        elif iname == 'SwitchServersIntent':
            return alexa_speak(
                "You only have one server configured: ourMedia. "
                "Add additional servers in the ourMedia console to switch between them.",
                end_session=False
            )

        elif iname == 'CurrentServerIntent':
            return alexa_speak("Your current server is ourMedia.", end_session=False)

        elif iname == 'ListInvitationsIntent':
            return alexa_speak(
                "Family share invitations are managed in the ourMedia console under Settings.",
                end_session=False
            )

        # ── Stop / Cancel ──────────────────────────────────────────────────
        elif iname in ('AMAZON.StopIntent', 'AMAZON.CancelIntent'):
            state = read_np_state() or {}
            state['playing'] = False
            write_np_state(state)
            return alexa_stop()

        # ── Pause ──────────────────────────────────────────────────────────
        elif iname == 'AMAZON.PauseIntent':
            state = read_np_state() or {}
            state['playing'] = False
            state['paused'] = True
            write_np_state(state)
            return alexa_stop()

        # ── Resume ─────────────────────────────────────────────────────────
        elif iname == 'AMAZON.ResumeIntent':
            state = read_np_state() or {}
            token = state.get('token')
            path  = state.get('filepath')
            if token and path and os.path.isfile(path):
                return alexa_play(file_to_stream_url(path), token,
                                  offset_ms=state.get('offset_ms', 0))
            return alexa_speak("Nothing to resume.")

        # ── Next ───────────────────────────────────────────────────────────
        elif iname == 'AMAZON.NextIntent':
            state = read_np_state() or {}
            token = state.get('token', '')
            data  = decode_token(token)
            tracks = data.get('tracks', [])
            idx    = data.get('idx', 0)
            next_idx = (idx + 1) % len(tracks) if tracks else 0
            if next_idx < len(tracks):
                next_path  = tracks[next_idx]
                next_token = encode_token({**data, 'idx': next_idx})
                return alexa_play(file_to_stream_url(next_path), next_token)
            return alexa_speak("There are no more tracks.")

        # ── Previous ───────────────────────────────────────────────────────
        elif iname == 'AMAZON.PreviousIntent':
            state = read_np_state() or {}
            token = state.get('token', '')
            data  = decode_token(token)
            tracks = data.get('tracks', [])
            idx    = data.get('idx', 0)
            prev_idx = max(idx - 1, 0)
            if tracks:
                prev_path  = tracks[prev_idx]
                prev_token = encode_token({**data, 'idx': prev_idx})
                return alexa_play(file_to_stream_url(prev_path), prev_token)
            return alexa_speak("Nothing to go back to.")

        # ── Loop ───────────────────────────────────────────────────────────
        elif iname == 'AMAZON.LoopOnIntent':
            state = read_np_state() or {}
            if state.get('token'):
                data = decode_token(state['token'])
                data['loop'] = True
                state['token'] = encode_token(data)
                write_np_state(state)
            return alexa_speak("Loop mode on.")

        elif iname == 'AMAZON.LoopOffIntent':
            state = read_np_state() or {}
            if state.get('token'):
                data = decode_token(state['token'])
                data['loop'] = False
                state['token'] = encode_token(data)
                write_np_state(state)
            return alexa_speak("Loop mode off.")

        # ── Shuffle (built-in Alexa intents as aliases) ────────────────────
        elif iname == 'AMAZON.ShuffleOnIntent':
            state = read_np_state() or {}
            token = state.get('token', '')
            if token:
                data = decode_token(token)
                data['shuffle'] = True
                # Reshuffle remaining tracks from current position
                idx = data.get('idx', 0)
                remaining = data['tracks'][idx:]
                random.shuffle(remaining)
                data['tracks'] = data['tracks'][:idx] + remaining
                state['token'] = encode_token(data)
                write_np_state(state)
                return alexa_speak("Shuffle on.")
            return alexa_speak("Nothing is playing to shuffle.")

        elif iname == 'AMAZON.ShuffleOffIntent':
            return alexa_speak("Shuffle off. Tracks will continue in their original order.")

        # ── Help ───────────────────────────────────────────────────────────
        elif iname == 'AMAZON.HelpIntent':
            return alexa_speak(
                "You can say: play my Yacht Rock playlist, play the album Rumours, "
                "play music by Dave Matthews, play the song Hotel California, "
                "play jazz music, shuffle the album Kind of Blue, "
                "what's playing, next, previous, pause, resume, or stop.",
                end_session=False
            )

        return alexa_speak("I didn't understand that. Try saying play followed by a playlist, artist, or album name.")

    return alexa_empty()

# ── Run ──────────────────────────────────────────────────────────────────────

if __name__ == '__main__':
    apply_logging()
    port = int(os.environ.get('PORT', 3001))
    print(f'ourMedia running at http://localhost:{port}')
    app.run(host='0.0.0.0', port=port, debug=False)
