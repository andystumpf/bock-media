#!/usr/bin/env python3
import sqlite3
import xml.etree.ElementTree as ET
import os
import json
import math
import glob
import html
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
import threading
import uuid
import ipaddress
from logging.handlers import RotatingFileHandler
from urllib.parse import quote, urlparse
from urllib.request import urlopen
from cryptography import x509
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from flask import Flask, jsonify, request, send_from_directory, send_file, Response

HERE = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__, static_folder=os.path.join(HERE, 'public'))

# Service-health bookkeeping (surfaced by /api/health + the dashboard card).
_START_TIME = time.time()
_LAST_ALEXA_HIT = 0.0

# External data locations are machine-specific and live outside this repo, so they
# are configurable via environment variables (the defaults preserve the original
# deployment). Override in the systemd unit / shell to relocate without code changes.
#   OURMEDIA_DB_PATH    – SQLite music index (table songs_cache)
#   OURMEDIA_DATA_DIR   – library data dir (Preferences/WatchFolders/ServerPlaylists XML, ImageCache)
#   OURMEDIA_MUSIC_ROOT – root of the music library that gets streamed
DB_PATH = os.environ.get('OURMEDIA_DB_PATH', '/mnt/bock/Music/music_organizer.db')
DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', '/home/plex/.bockmedia')
HEALTH_STATE_PATH = os.path.join(DATA_DIR, 'health_state.json')

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

_pref_cache: dict = {}
_pref_mtime: float = 0.0

def get_pref(xml_tag, default=''):
    global _pref_cache, _pref_mtime
    path = os.path.join(DATA_DIR, 'Preferences.xml')
    try:
        mtime = os.path.getmtime(path)
        if mtime != _pref_mtime:
            tree = ET.parse(path)
            _pref_cache = {el.tag: (el.text or '') for el in tree.getroot()}
            _pref_mtime = mtime
    except:
        pass
    return _pref_cache.get(xml_tag, default)

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

# Paths Alexa fetches over the Cloudflare tunnel — reachable without admin login.
# Direct port-forward hits (public IP :3001) must NOT treat these as public.
_ALEXA_TUNNEL_PREFIXES = ('/alexa', '/stream/', '/artwork/', '/music', '/oauth/')

def _is_tunnel_request():
    # Cloudflare cloudflared adds Cf-Connecting-Ip (and Cf-Ray) on every tunneled
    # request. Local LAN traffic never has these. We treat presence of either as
    # "came from the public internet via Cloudflare".
    return bool(
        request.headers.get('Cf-Connecting-Ip')
        or request.headers.get('Cf-Ray')
    )

def _client_ip():
    """Best-effort client IP. Trust Cf-Connecting-Ip only on Cloudflare tunnel hits."""
    if _is_tunnel_request():
        cf = (request.headers.get('Cf-Connecting-Ip') or '').strip()
        if cf:
            return cf.split(',')[0].strip()
    return (request.remote_addr or '').strip()

def _is_private_ip(ip):
    if not ip:
        return False
    ip = ip.split('%')[0].strip()
    try:
        addr = ipaddress.ip_address(ip)
        return addr.is_private or addr.is_loopback or addr.is_link_local
    except ValueError:
        return False

def _is_external_request():
    """True for direct internet hits (e.g. router port-forward to :3001), not LAN."""
    return not _is_lan_request()

def _host_ip():
    return (request.host or '').split(':')[0].strip().lower()

def _is_lan_request():
    """True when the request Host is a private/local address (e.g. 192.168.x)."""
    host = _host_ip()
    if host in ('localhost',):
        return True
    return _is_private_ip(host)

def _cfg_flag(section, key, default=False):
    try:
        v = (load_config().get(section) or {}).get(key, default)
        if isinstance(v, bool):
            return v
        if isinstance(v, str):
            return v.strip().lower() in ('true', '1', 'yes', 'on')
        return bool(v)
    except Exception:
        return default

def _web_username():
    return (get_pref('WebUsername', '') or 'morejava').strip() or 'morejava'

def _basic_auth_ok():
    stored = get_pref('WebPassword', '').strip()
    if not stored:
        return False
    auth = request.authorization
    return bool(auth and auth.username == _web_username() and auth.password == stored)

def _mobile_api_bearer_value():
    auth_header = request.headers.get('Authorization', '')
    if auth_header.startswith('Bearer '):
        return auth_header[7:].strip()
    return (request.headers.get('X-BockMedia-Token') or '').strip()

def _mobile_api_token_ok():
    token = _mobile_api_bearer_value()
    if not token:
        return False
    try:
        ma = load_config().get('mobileApi') or {}
        expected = ma.get('token', '').strip()
        if not expected or token != expected:
            return False
        ext = _is_external_request() and not _is_tunnel_request()
        tun = _is_tunnel_request()
        if ext:
            return _cfg_flag('mobileApi', 'allowExternalAccess')
        if tun:
            return _cfg_flag('mobileApi', 'allowTunnelApi')
        return True
    except Exception:
        return False

def _mobile_api_only():
    """True when this request authenticated via Bearer token (not Basic)."""
    return _mobile_api_token_ok() and not _basic_auth_ok()

def _redact_config(cfg):
    redacted = dict(cfg)
    for key in ('alexaRemote', 'mspOauth', 'claude', 'mobileApi'):
        if key in redacted:
            redacted[key] = {'_redacted': True}
    return redacted

def _api_auth_required():
    """Credentials required for direct external hits (port-forward), not on LAN."""
    if _is_tunnel_request() or _is_lan_request():
        return False
    return _cfg_flag('mobileApi', 'allowExternalAccess')

def _auth_required():
    return Response('Authentication required', 401,
                    {'WWW-Authenticate': 'Basic realm="Bock Media"'})

def _forbidden(msg='Forbidden'):
    return Response(msg, 403, {'Content-Type': 'text/plain; charset=utf-8'})

# Alexa request-signature verification per
# https://developer.amazon.com/en-US/docs/alexa/custom-skills/host-a-custom-skill-as-a-web-service.html
EXPECTED_SKILL_APP_ID = 'amzn1.ask.skill.c13622d4-8780-4bea-93a5-0ded84307466'
_ALEXA_CERT_HOST = 's3.amazonaws.com'
_ALEXA_CERT_PATH_PREFIX = '/echo.api/'
_ALEXA_SIG_SAN = 'echo-api.amazon.com'
_ALEXA_TIMESTAMP_WINDOW_SEC = 150
_ALEXA_CERT_CACHE = {}

def _alexa_cert_url_ok(url):
    p = urlparse(url or '')
    return (p.scheme == 'https'
            and p.hostname == _ALEXA_CERT_HOST
            and p.path.startswith(_ALEXA_CERT_PATH_PREFIX)
            and (p.port is None or p.port == 443))

def _alexa_cert_validity_window(cert):
    nbf = getattr(cert, 'not_valid_before_utc', None) or cert.not_valid_before
    naf = getattr(cert, 'not_valid_after_utc', None) or cert.not_valid_after
    return nbf, naf

def _alexa_load_cert(url):
    cached = _ALEXA_CERT_CACHE.get(url)
    now = datetime.datetime.now(datetime.timezone.utc)
    if cached is not None:
        nbf, naf = _alexa_cert_validity_window(cached)
        if nbf.tzinfo is None: nbf = nbf.replace(tzinfo=datetime.timezone.utc)
        if naf.tzinfo is None: naf = naf.replace(tzinfo=datetime.timezone.utc)
        if nbf <= now <= naf:
            return cached
    pem = urlopen(url, timeout=5).read()
    cert = x509.load_pem_x509_certificate(pem, default_backend())
    nbf, naf = _alexa_cert_validity_window(cert)
    if nbf.tzinfo is None: nbf = nbf.replace(tzinfo=datetime.timezone.utc)
    if naf.tzinfo is None: naf = naf.replace(tzinfo=datetime.timezone.utc)
    if not (nbf <= now <= naf):
        raise ValueError('certificate not within validity window')
    san_ext = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    if _ALEXA_SIG_SAN not in san_ext.get_values_for_type(x509.DNSName):
        raise ValueError(f'certificate SAN missing {_ALEXA_SIG_SAN}')
    _ALEXA_CERT_CACHE[url] = cert
    return cert

def _verify_alexa_signature(raw_body, body_json):
    cert_url = request.headers.get('SignatureCertChainUrl', '')
    if not _alexa_cert_url_ok(cert_url):
        return 'invalid SignatureCertChainUrl'
    sig_b64 = request.headers.get('Signature', '')
    if not sig_b64:
        return 'missing Signature header'
    try:
        sig = base64.b64decode(sig_b64)
    except Exception:
        return 'unparsable Signature'
    try:
        cert = _alexa_load_cert(cert_url)
    except Exception as e:
        return f'cert load failed: {e}'
    try:
        cert.public_key().verify(sig, raw_body, rsa_padding.PKCS1v15(), hashes.SHA1())
    except Exception:
        return 'signature does not match request body'
    ts_str = (body_json.get('request') or {}).get('timestamp', '')
    try:
        ts = datetime.datetime.strptime(ts_str.replace('Z', ''), '%Y-%m-%dT%H:%M:%S')
        ts = ts.replace(tzinfo=datetime.timezone.utc)
    except Exception:
        return 'malformed request timestamp'
    if abs((datetime.datetime.now(datetime.timezone.utc) - ts).total_seconds()) > _ALEXA_TIMESTAMP_WINDOW_SEC:
        return 'request timestamp outside acceptance window'
    return None

def _app_download_user():
    try:
        ad = load_config().get('appDownload') or {}
    except Exception:
        ad = {}
    return (ad.get('username') or _web_username() or 'morejava').strip()

def _app_download_password():
    try:
        ad = load_config().get('appDownload') or {}
        pw = (ad.get('password') or '').strip()
        if pw:
            return pw
    except Exception:
        pass
    return get_pref('WebPassword', '').strip()

def _app_download_auth_ok():
    stored = _app_download_password()
    if not stored:
        return False
    auth = request.authorization
    return bool(auth and auth.username == _app_download_user() and auth.password == stored)

def _app_apk_path():
    for p in (
        os.path.join(DATA_DIR, 'bockmedia-console.apk'),
        os.path.join(HERE, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
        os.path.join(HERE, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'),
    ):
        if os.path.isfile(p):
            return p
    return None

_APP_DOWNLOAD_PATHS = frozenset({'/app', '/download/bockmedia-console.apk'})

@app.before_request
def check_auth():
    if request.path == '/api/auth/info':
        return None

    if request.path in _APP_DOWNLOAD_PATHS:
        if _app_download_auth_ok():
            return None
        return _auth_required()

    if request.path.startswith('/stream/') or request.path.startswith('/artwork/'):
        ua = request.headers.get('User-Agent', '')
        rng = request.headers.get('Range', '')
        print(f"[STREAM] {request.method} {request.path} ua={ua!r} range={rng!r}", flush=True)

    is_alexa_tunnel_path = any(request.path.startswith(p) for p in _ALEXA_TUNNEL_PREFIXES)
    external = _is_external_request()
    tunnel = _is_tunnel_request()

    # Direct port-forward / public-IP access (:3001) — never expose Alexa paths or
    # anonymous streams. Require admin Basic auth and/or mobileApi Bearer token.
    if external and not tunnel:
        if not _cfg_flag('mobileApi', 'allowExternalAccess'):
            return _forbidden('External access disabled')
        if any(request.path.startswith(p) for p in ('/alexa', '/music', '/oauth/')):
            return _forbidden('Alexa endpoints use the Cloudflare tunnel only')
        if _mobile_api_token_ok() or _basic_auth_ok():
            return None
        return _auth_required()

    if tunnel and not is_alexa_tunnel_path:
        if request.path.startswith('/api/') and _mobile_api_token_ok():
            return None
        return _forbidden()

    if is_alexa_tunnel_path and (tunnel or not external):
        return None

    if request.path.startswith('/api/') and _mobile_api_token_ok():
        return None

    return None  # LAN open; external credentials enforced above

# ── Static files ─────────────────────────────────────────────────────────────

PUBLIC = os.path.join(HERE, 'public')

def _no_cache(resp):
    """Force revalidation so UI changes show on a normal reload (no hard-refresh)."""
    resp.headers['Cache-Control'] = 'no-cache, must-revalidate'
    resp.headers['Pragma'] = 'no-cache'
    resp.headers['Expires'] = '0'
    return resp

@app.route('/')
def index():
    return _no_cache(send_from_directory(PUBLIC, 'index.html'))

@app.route('/<path:filename>')
def static_files(filename):
    resp = send_from_directory(PUBLIC, filename)
    # App shell (html/js/css) must always revalidate; let images/fonts cache.
    if filename.rsplit('.', 1)[-1].lower() in ('html', 'js', 'css'):
        resp = _no_cache(resp)
    return resp

@app.route('/app')
def app_download_page():
    apk = _app_apk_path()
    version = 'unknown'
    if apk:
        try:
            with open(os.path.join(HERE, 'android', 'app', 'build.gradle.kts')) as f:
                for line in f:
                    if 'versionName' in line:
                        version = line.split('"')[1]
                        break
        except Exception:
            pass
    size_mb = round(os.path.getsize(apk) / (1024 * 1024), 1) if apk else None
    return _no_cache(Response(
        _render_app_download_html(version, size_mb, apk is not None),
        mimetype='text/html; charset=utf-8',
    ))

@app.route('/download/bockmedia-console.apk')
def app_download_apk():
    apk = _app_apk_path()
    if not apk:
        return Response('Android APK not built yet — run ./gradlew assembleDebug on the server', 404,
                        {'Content-Type': 'text/plain; charset=utf-8'})
    return send_file(
        apk,
        mimetype='application/vnd.android.package-archive',
        as_attachment=True,
        download_name='bockmedia-console.apk',
        max_age=0,
    )

def _render_app_download_html(version, size_mb, available):
    size_line = f'<p class="meta">Build {html.escape(version)} · {size_mb} MB</p>' if available and size_mb else ''
    btn = (
        '<a class="btn" href="/download/bockmedia-console.apk">Download APK</a>'
        if available else
        '<p class="warn">APK not available on server yet.</p>'
    )
    return f'''<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bock Media — Android app</title>
<style>
  body {{ font-family: system-ui, sans-serif; background: #f4f6f9; color: #333; margin: 0; min-height: 100vh;
    display: flex; align-items: center; justify-content: center; padding: 24px; }}
  .card {{ background: #fff; border-radius: 12px; padding: 32px; max-width: 420px; width: 100%;
    box-shadow: 0 4px 24px rgba(0,0,0,.08); text-align: center; }}
  h1 {{ font-size: 1.5rem; margin: 0 0 8px; color: #30426a; }}
  .meta {{ color: #666; margin: 0 0 20px; font-size: .95rem; }}
  .btn {{ display: inline-block; background: #30426a; color: #fff; padding: 14px 28px;
    border-radius: 8px; font-weight: 600; text-decoration: none; }}
  .btn:hover {{ background: #3d5285; }}
  .steps {{ text-align: left; margin-top: 24px; font-size: .9rem; color: #555; }}
  ol {{ margin: 8px 0 0; padding-left: 20px; }}
</style></head><body>
<div class="card">
  <h1>Bock Media Console</h1>
  <p>Android app for your server</p>
  {size_line}
  {btn}
  <div class="steps">
    <strong>Install</strong>
    <ol>
      <li>Download the APK</li>
      <li>Allow installs from browser if prompted</li>
      <li>Open app → enter external URL + Mobile API token</li>
    </ol>
  </div>
</div></body></html>'''

# ── API: Summary ─────────────────────────────────────────────────────────────

@app.route('/api/summary')
def summary():
    songs = db_one('SELECT COUNT(*) as count FROM songs_cache')
    artists = db_one('SELECT COUNT(DISTINCT artist) as count FROM songs_cache WHERE artist IS NOT NULL AND artist != ""')
    albums = db_one('SELECT COUNT(DISTINCT album) as count FROM songs_cache WHERE album IS NOT NULL AND album != ""')

    watch_folders = 0
    playlists = 0
    try:
        wf = ET.parse(os.path.join(DATA_DIR, 'WatchFolders.xml'))
        watch_folders = len(wf.getroot().findall('WatchFolder'))
    except:
        pass
    try:
        pl = ET.parse(os.path.join(DATA_DIR, 'ServerPlaylists.xml'))
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
        tree = ET.parse(os.path.join(DATA_DIR, 'WatchFolders.xml'))
        folders = []
        for wf in tree.getroot().findall('WatchFolder'):
            path = xml_text(wf, 'Path')
            exists = os.path.isdir(path)

            if exists:
                prefix = path.rstrip('/') + '/'
                row = db_one(
                    "SELECT COUNT(*) AS cnt FROM songs_cache WHERE path LIKE ?",
                    [prefix + '%']
                )
                song_count = row.get('cnt', 0) if row else 0
                try:
                    m3u_count = sum(
                        1 for f in os.listdir(path)
                        if f.lower().endswith('.m3u')
                    )
                except Exception:
                    m3u_count = 0
                identified = song_count
                playlists  = m3u_count
                status = 'Done' if (song_count > 0 or m3u_count > 0) else 'Empty'
            else:
                identified = 0
                playlists  = 0
                status     = 'Missing'

            folders.append({
                'guid':            xml_text(wf, 'Guid'),
                'path':            path,
                'label':           xml_text(wf, 'Label'),
                'status':          status,
                'count':           xml_int(wf, 'Count'),
                'identifiedFiles': identified,
                'errors':          xml_int(wf, 'Errors'),
                'playlists':       playlists,
                'type':            xml_text(wf, 'Type'),
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
    sort_by = (request.args.get('sortBy') or 'name').strip().lower()
    order = (request.args.get('order') or 'asc').strip().lower()
    if sort_by in ('tracks', 'track', 'trackcount', 'count'):
        sort_by = 'trackCount'
    elif sort_by != 'name':
        sort_by = 'name'
    reverse = order == 'desc'

    try:
        tree = ET.parse(os.path.join(DATA_DIR, 'ServerPlaylists.xml'))
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
                'sourceName': xml_text(key, 'SourceName'),
                'isAudioBook': xml_text(key, 'IsAudioBook') == 'true',
            })

        if sort_by == 'trackCount':
            all_playlists.sort(key=lambda x: (x.get('trackCount') or 0, (x.get('name') or '').lower()),
                               reverse=reverse)
        else:
            all_playlists.sort(key=lambda x: (x.get('name') or '').lower(),
                               reverse=reverse)

        total = len(all_playlists)
        start = (page - 1) * limit
        items = all_playlists[start:start + limit]
        return jsonify({'items': items, 'total': total, 'sortBy': sort_by, 'order': 'desc' if reverse else 'asc'})
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
        path = os.path.join(DATA_DIR, 'ServerPlaylists.xml')
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

# ── API: Alexa remote control ("Play on device") ─────────────────────────────
# Uses the unofficial Alexa API (alexapy) to inject a text command on a chosen
# Echo, since Amazon gives skills/MSP no way to initiate playback on a device.

def _alexa_alias():
    return ((load_config().get('msp') or {}).get('alias') or 'bock media').strip()

@app.route('/api/alexa_remote/status')
def alexa_remote_status():
    try:
        import alexa_remote
    except Exception as e:
        return jsonify({'available': False, 'configured': False, 'authenticated': None, 'reason': str(e)})
    configured = alexa_remote.is_configured()
    if request.args.get('probe') in ('1', 'true', 'yes'):
        alexa_remote.invalidate_auth_cache()
    authenticated = alexa_remote.is_authenticated() if configured else None
    login = alexa_remote.proxy_login_state() if configured else {}
    host = login.get('host') or alexa_remote.lan_ip()
    port = login.get('port') or int((alexa_remote.cfg() or {}).get('loginProxyPort') or 3005)
    return jsonify({
        'available': True,
        'configured': configured,
        'authenticated': authenticated,
        'loginCommand': f'python3 scripts/alexa_login.py --proxy --host {host} --port {port}',
        'loginProxyPort': port,
        'loginProxyHost': host,
        'loginUrl': login.get('url') or f'http://{host}:{port}',
        'loginStatus': login.get('status') or 'idle',
        'loginError': login.get('error'),
    })


@app.route('/api/alexa_remote/login', methods=['GET'])
def alexa_remote_login_state():
    try:
        import alexa_remote
    except Exception as e:
        return jsonify({'error': str(e)}), 503
    st = alexa_remote.proxy_login_state()
    st['configured'] = alexa_remote.is_configured()
    st['authenticated'] = alexa_remote.is_authenticated() if st['configured'] else None
    return jsonify(st)


def _is_private_ip(ip):
    """True for RFC1918 / loopback (client on home LAN)."""
    if not ip:
        return False
    if ip in ('127.0.0.1', '::1', 'localhost'):
        return True
    if ip.startswith('fe80:') or ip.startswith('fd') or ip.startswith('fc'):
        return True
    parts = ip.split('.')
    if len(parts) != 4:
        return False
    try:
        a, b = int(parts[0]), int(parts[1])
    except ValueError:
        return False
    if a == 10:
        return True
    if a == 172 and 16 <= b <= 31:
        return True
    if a == 192 and b == 168:
        return True
    return False


def _login_advertise_host(body):
    """Host for the OAuth proxy URL — must match what the user's browser can reach."""
    try:
        import alexa_remote
    except ImportError:
        alexa_remote = None
    client = (request.remote_addr or '').split('%')[0].strip()
    # Phones on home Wi‑Fi cannot load the public IP (router hairpin NAT) — use LAN.
    if alexa_remote and _is_private_ip(client):
        return alexa_remote.lan_ip()
    explicit = (body.get('host') or '').strip()
    if explicit:
        return explicit
    forwarded = (request.headers.get('X-Forwarded-Host') or '').split(',')[0].strip()
    raw = forwarded or request.host or ''
    h = raw.split(':')[0].strip()
    if h and h not in ('127.0.0.1', 'localhost'):
        return h
    return None


@app.route('/api/alexa_remote/login/start', methods=['POST'])
def alexa_remote_login_start():
    body = request.get_json(silent=True) or {}
    try:
        import alexa_remote
        st = alexa_remote.start_proxy_login(
            host=_login_advertise_host(body),
            port=body.get('port'),
        )
        return jsonify({'ok': True, **st})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code.split(' — ')[0] in (
            'not_configured', 'password_required', 'port_busy',
        ) else 500
        return jsonify({'error': code, 'code': code.split(' — ')[0]}), status


@app.route('/api/alexa_remote/login/stop', methods=['POST'])
def alexa_remote_login_stop():
    try:
        import alexa_remote
        st = alexa_remote.stop_proxy_login()
        return jsonify({'ok': True, **st})
    except ImportError:
        return jsonify({'error': 'alexapy not installed'}), 503

def _read_health_state():
    try:
        with open(HEALTH_STATE_PATH) as f:
            return json.load(f)
    except Exception:
        return {}

@app.route('/api/health')
def health():
    """Single-pane service health for the dashboard. Merges the out-of-process
    watchdog snapshot (health_state.json: tunnel/backend reachability, public
    latency, alexapy auth) with in-process facts (uptime, last /alexa hit)."""
    hs = _read_health_state()
    now = time.time()
    state_age = (now - hs.get('ts', 0)) if hs.get('ts') else None
    # The watchdog runs every 60s; treat a >180s-old snapshot as stale/unknown.
    fresh = state_age is not None and state_age < 180
    last_hit = _LAST_ALEXA_HIT or None

    # Skill testing-enablement: the 6-hourly cron can drop a small marker; if
    # absent we report unknown rather than guessing.
    skill_testing = 'unknown'
    try:
        with open(os.path.join(HERE, 'skill_enablement_state.json')) as f:
            skill_testing = bool((json.load(f) or {}).get('enabled'))
    except Exception:
        pass

    plex = {'configured': False, 'reachable': None}
    try:
        import plex_client
        plex = plex_client.status()
    except Exception:
        pass

    return jsonify({
        'uptimeSeconds':   int(now - _START_TIME),
        'lastAlexaHit':    last_hit,
        'lastAlexaHitAgo': int(now - last_hit) if last_hit else None,
        'watchdogFresh':   fresh,
        'watchdogAgeSeconds': int(state_age) if state_age is not None else None,
        'backend':         hs.get('backend') if fresh else None,
        'tunnel':          hs.get('tunnel') if fresh else None,
        'backendHttp':     hs.get('backendHttp') if fresh else None,
        'tunnelReachable': hs.get('tunnelReachable') if fresh else None,
        'publicLatencyMs': hs.get('publicLatencyMs') if fresh else None,
        'publicStatus':    hs.get('publicStatus') if fresh else None,
        'alexaAuth':       hs.get('alexaAuth') if fresh else None,
        'skillTesting':    skill_testing,
        'plexConfigured':  plex.get('configured'),
        'plexReachable':   plex.get('reachable'),
    })

# ── Plex sync status (dashboard panel) ───────────────────────────────────────

PLEX_SYNC_LOG = os.path.join(HERE, 'plex-sync.log')
PLEX_SYNC_STATE = os.path.join(
    os.environ.get('OURMEDIA_MUSIC_ROOT', '/mnt/bock/Music'),
    'exportedPlaylists', 'plex', '.plex_sync_state.json',
)

@app.route('/api/plex_sync/status')
def plex_sync_status():
    state = {}
    state_mtime = None
    if os.path.isfile(PLEX_SYNC_STATE):
        try:
            state_mtime = os.path.getmtime(PLEX_SYNC_STATE)
            with open(PLEX_SYNC_STATE) as f:
                state = json.load(f) or {}
        except Exception:
            pass
    log_lines = []
    log_mtime = None
    if os.path.isfile(PLEX_SYNC_LOG):
        try:
            log_mtime = os.path.getmtime(PLEX_SYNC_LOG)
            with open(PLEX_SYNC_LOG, encoding='utf-8', errors='replace') as f:
                log_lines = f.readlines()[-15:]
        except Exception:
            pass
    return jsonify({
        'playlistCount': len(state) if isinstance(state, dict) else 0,
        'statePath': PLEX_SYNC_STATE,
        'stateUpdatedAt': state_mtime,
        'logPath': PLEX_SYNC_LOG,
        'logUpdatedAt': log_mtime,
        'logTail': [ln.rstrip() for ln in log_lines],
        'cronHint': '*/5 * * * * scripts/sync_plex_playlists.py',
    })

# ── Favorites (starred tracks) ───────────────────────────────────────────────

FAVORITES_PATH = os.path.join(HERE, 'favorites.json')
_FAVORITES_LOCK = threading.Lock()

def _load_favorites():
    with _FAVORITES_LOCK:
        try:
            with open(FAVORITES_PATH) as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:
            return []

def _save_favorites(items):
    with _FAVORITES_LOCK:
        _atomic_json_write(FAVORITES_PATH, items)

@app.route('/api/favorites', methods=['GET'])
def list_favorites():
    return jsonify({'items': _load_favorites()})

@app.route('/api/favorites', methods=['POST'])
def add_favorite():
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    title, artist, album, _ = track_metadata(path)
    item = {
        'path': path,
        'title': body.get('title') or title,
        'artist': body.get('artist') or artist,
        'album': body.get('album') or album,
        'addedAt': time.time(),
    }
    items = _load_favorites()
    if not any(x.get('path') == path for x in items):
        items.insert(0, item)
        _save_favorites(items[:200])
    return jsonify({'ok': True, 'item': item})

@app.route('/api/favorites', methods=['DELETE'])
def remove_favorite():
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    items = [x for x in _load_favorites() if x.get('path') != path]
    _save_favorites(items)
    return jsonify({'ok': True})

@app.route('/api/dashboard/quick')
def dashboard_quick():
    """Recent unique plays + favorites for the dashboard."""
    seen = set()
    recent = []
    for row in reversed(_read_stream_history()):
        if row.get('test'):
            continue
        key = row.get('filepath') or f"{row.get('track')}|{row.get('artist')}"
        if key in seen:
            continue
        seen.add(key)
        recent.append({
            'track': row.get('track'),
            'artist': row.get('artist'),
            'album': row.get('album'),
            'filepath': row.get('filepath'),
            'device': row.get('device'),
            'date': row.get('date') or row.get('timestamp'),
        })
        if len(recent) >= 5:
            break
    return jsonify({'recent': recent, 'favorites': _load_favorites()[:20]})

# ── Library search ─────────────────────────────────────────────────────────────

def _library_search_song_match(q, title, album):
    """True when a track belongs in unified search Songs, not album tracklist noise."""
    ql = (q or '').lower().strip()
    if len(ql) < 2:
        return False
    tl = (title or '').lower().strip()
    al = (album or '').lower().strip()
    if ql not in tl:
        return False
    for sep in (' - from ', ' – from '):
        if sep in tl:
            primary, suffix = tl.split(sep, 1)
            if ql in al and ql not in primary and ql in suffix:
                return False
    if ' - ' in tl and ql in al:
        primary, suffix = tl.split(' - ', 1)
        if ql not in primary and ql in suffix:
            return False
    return True


@app.route('/api/search')
def library_search():
    q = (request.args.get('q') or '').strip()
    limit = min(max(int(request.args.get('limit', 15) or 15), 1), 30)
    if len(q) < 2:
        return jsonify({'query': q, 'playlists': [], 'artists': [], 'albums': [], 'songs': []})
    like = f'%{q.lower()}%'
    raw_songs = db_query(
        'SELECT title, artist, album, path FROM songs_cache '
        'WHERE LOWER(title) LIKE ? '
        'AND path IS NOT NULL LIMIT ?',
        [like, limit * 8],
    ) or []
    songs = [
        r for r in raw_songs
        if _library_search_song_match(q, r.get('title'), r.get('album'))
    ][:limit]
    artists = db_query(
        'SELECT artist, MIN(path) as art_path FROM songs_cache WHERE LOWER(artist) LIKE ? '
        'AND artist IS NOT NULL AND artist != "" AND path IS NOT NULL AND path != "" '
        'GROUP BY artist LIMIT ?',
        [like, limit],
    ) or []
    albums = db_query(
        'SELECT album, artist, MIN(path) as art_path FROM songs_cache WHERE LOWER(album) LIKE ? '
        'AND album IS NOT NULL AND album != "" AND path IS NOT NULL AND path != "" '
        'GROUP BY album, artist LIMIT ?',
        [like, limit],
    ) or []
    playlists = []
    for pid, name, src in _load_playlist_entries():
        if _score_playlist(q, name) >= 0.5:
            playlists.append({'id': pid, 'name': name, 'source': src})
        if len(playlists) >= limit:
            break
    playlists.sort(key=lambda x: _score_playlist(q, x['name']), reverse=True)
    return jsonify({
        'query': q,
        'playlists': playlists[:limit],
        'artists': [{'name': r['artist'], 'path': r.get('art_path')} for r in artists],
        'albums': [{'name': r['album'], 'artist': r.get('artist'), 'path': r.get('art_path')} for r in albums],
        'songs': [{'title': r['title'], 'artist': r['artist'], 'album': r['album'], 'path': r['path']} for r in songs],
    })

@app.route('/api/alexa_remote/devices')
def alexa_remote_devices():
    try:
        import alexa_remote
        return jsonify({'devices': alexa_remote.list_devices()})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated') else 500
        return jsonify({'error': code, 'code': code}), status

def _optimistic_np_skip(device_id, delta):
    """Advance Now Playing metadata immediately after remote skip (before Alexa event)."""
    st = read_np_state_for_device(device_id)
    if not st:
        return
    token = st.get('token') or ''
    if ':' not in token:
        return
    data = decode_token(token)
    tracks = data.get('tracks') or []
    if not tracks:
        return
    try:
        idx = int(data.get('idx', 0))
    except (TypeError, ValueError):
        idx = 0
    if delta > 0:
        new_idx = idx + 1
        if new_idx >= len(tracks):
            if data.get('loop'):
                new_idx = 0
            else:
                return
    else:
        new_idx = max(idx - 1, 0)
    if new_idx == idx:
        return
    path = tracks[new_idx]
    title, artist, album, _ = track_metadata_fast(path)
    new_token = encode_token({**data, 'idx': new_idx})
    src = _np_source_fields(new_token, device_id)
    write_np_state_for_device(device_id, {
        **st,
        'track': title,
        'artist': artist,
        'album': album,
        'filepath': path,
        'token': new_token,
        'playing': True,
        'paused': False,
        'timestamp': time.time(),
        'duration_ms': _duration_ms_for_path(path),
        'offset_ms': 0,
        **src,
    })

@app.route('/api/alexa_remote/control', methods=['POST'])
def alexa_remote_control():
    """Pause/play/skip/shuffle on a specific Echo (unofficial Alexa API)."""
    data = request.get_json() or {}
    device = (data.get('device') or '').strip()
    device_id = (data.get('deviceId') or '').strip()
    serial = (data.get('serial') or '').strip()
    action = (data.get('action') or '').strip().lower()
    allowed = {'pause', 'play', 'stop', 'next', 'previous', 'shuffle_on', 'shuffle_off'}
    target = serial or device
    if not target:
        return jsonify({'error': 'device required'}), 400
    if action not in allowed:
        return jsonify({'error': 'invalid action'}), 400
    try:
        import alexa_remote
        result = alexa_remote.device_control(target, action, _alexa_alias())
        # Keep UI state in sync without waiting for the skill round-trip.
        if device_id:
            st = read_np_state_for_device(device_id) or {}
            if action == 'pause' and st:
                st = {**st, 'playing': False, 'paused': True}
                write_np_state_for_device(device_id, st)
            elif action == 'play' and st.get('token'):
                st = {**st, 'playing': True, 'paused': False}
                write_np_state_for_device(device_id, st)
            elif action == 'stop':
                # Device goes home — drop it from Now Playing entirely.
                write_np_state_for_device(device_id, None)
            elif action == 'next':
                _optimistic_np_skip(device_id, 1)
            elif action == 'previous':
                _optimistic_np_skip(device_id, -1)
        return jsonify({'ok': True, **result})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found', 'invalid_action') else 500
        return jsonify({'error': code, 'code': code}), status

# ── Device Groups (play/schedule on multiple Echoes at once) ──────────────────
# A group is a named set of Alexa devices (by stable serialNumber). Selecting a
# group anywhere a single device is accepted fans the command out to every
# member — each member then streams independently and shows its own Now Playing
# row (the custom skill keys state per deviceId). Group ids are referenced in
# dropdowns / automations as the opaque value "group:<id>".

DEVICE_GROUPS_PATH = os.path.join(HERE, 'device_groups.json')
_DEVICE_GROUPS_LOCK = threading.Lock()
GROUP_PREFIX = 'group:'


def _load_device_groups():
    with _DEVICE_GROUPS_LOCK:
        try:
            with open(DEVICE_GROUPS_PATH) as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:
            return []


def _save_device_groups(items):
    with _DEVICE_GROUPS_LOCK:
        _atomic_json_write(DEVICE_GROUPS_PATH, items, indent=2)


def _find_group(group_id):
    return next((g for g in _load_device_groups() if g.get('id') == group_id), None)


def _normalize_group_members(raw):
    """Accept a list of {serial,name} (or bare serial strings) → de-duped list."""
    members, seen = [], set()
    for m in raw or []:
        if isinstance(m, str):
            serial, name = m.strip(), ''
        elif isinstance(m, dict):
            serial = (m.get('serial') or '').strip()
            name = (m.get('name') or '').strip()
        else:
            continue
        if not serial or serial in seen:
            continue
        seen.add(serial)
        members.append({'serial': serial, 'name': name or serial})
    return members


def _expand_play_targets(device):
    """Resolve a dropdown device value to a list of (serial, name) targets.

    A plain serial/name → single target. A "group:<id>" value → every member.
    """
    device = (device or '').strip()
    if device.startswith(GROUP_PREFIX):
        group = _find_group(device[len(GROUP_PREFIX):])
        if not group:
            raise ValueError('group_not_found')
        members = _normalize_group_members(group.get('members'))
        if not members:
            raise ValueError('group_empty')
        return [(m['serial'], m['name']) for m in members]
    # Single target: the dropdown value is the serial. Resolve its friendly
    # Alexa name so play-intent correlation labels the room correctly (not the
    # raw serial). Falls back to the serial if the lookup is unavailable.
    return [(device, _alexa_name_for_serial(device) or device)]


@app.route('/api/device_groups')
def list_device_groups():
    items = _load_device_groups()
    items.sort(key=lambda g: (g.get('name') or '').lower())
    return jsonify({'items': items})


def _validate_group_body(body, existing=None):
    name = (body.get('name') or '').strip()
    members = _normalize_group_members(body.get('members'))
    if not name:
        return None, ('name required', 400)
    if not members:
        return None, ('at least one device required', 400)
    now = time.time()
    return {
        'id': (existing or {}).get('id') or str(uuid.uuid4()),
        'name': name,
        'members': members,
        'createdAt': (existing or {}).get('createdAt', now),
        'updatedAt': now,
    }, None


@app.route('/api/device_groups', methods=['POST'])
def create_device_group():
    item, err = _validate_group_body(request.get_json() or {})
    if err:
        return jsonify({'error': err[0]}), err[1]
    items = _load_device_groups()
    items.append(item)
    _save_device_groups(items)
    return jsonify(item), 201


@app.route('/api/device_groups/<group_id>', methods=['PUT'])
def update_device_group(group_id):
    items = _load_device_groups()
    idx = next((i for i, g in enumerate(items) if g.get('id') == group_id), None)
    if idx is None:
        return jsonify({'error': 'not found'}), 404
    item, err = _validate_group_body(request.get_json() or {}, existing=items[idx])
    if err:
        return jsonify({'error': err[0]}), err[1]
    items[idx] = item
    _save_device_groups(items)
    return jsonify(item)


@app.route('/api/device_groups/<group_id>', methods=['DELETE'])
def delete_device_group(group_id):
    items = _load_device_groups()
    new_items = [g for g in items if g.get('id') != group_id]
    if len(new_items) == len(items):
        return jsonify({'error': 'not found'}), 404
    _save_device_groups(new_items)
    return jsonify({'ok': True})


@app.route('/api/alexa_remote/volume', methods=['GET', 'POST'])
def alexa_remote_volume():
    """GET current / POST new volume (0-100) on a specific Echo (unofficial API)."""
    if request.method == 'GET':
        target = (request.args.get('serial') or request.args.get('device') or '').strip()
        if not target:
            return jsonify({'error': 'device required'}), 400
        try:
            import alexa_remote
            return jsonify({'ok': True, 'volume': alexa_remote.get_volume(target)})
        except ImportError:
            return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
        except Exception as e:
            code = str(e)
            status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found') else 500
            return jsonify({'error': code, 'code': code}), status

    data = request.get_json() or {}
    serial = (data.get('serial') or '').strip()
    device = (data.get('device') or '').strip()
    target = serial or device
    try:
        volume = int(data.get('volume'))
    except (TypeError, ValueError):
        return jsonify({'error': 'volume must be 0-100'}), 400
    if not target:
        return jsonify({'error': 'device required'}), 400
    if not 0 <= volume <= 100:
        return jsonify({'error': 'volume must be 0-100'}), 400
    try:
        import alexa_remote
        result = alexa_remote.set_volume(target, volume)
        return jsonify({'ok': True, **result})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found') else 500
        return jsonify({'error': code, 'code': code}), status


# Short-lived tokens map UI song plays (known file path) to a unique utterance
# Alexa cannot misroute as a playlist. Populated by /api/alexa_remote/play.
_PLAY_FILE_TOKENS = {}
_PLAY_FILE_TOKEN_LOCK = threading.Lock()
_PLAY_FILE_TOKEN_TTL = 180.0


def _register_play_file_token(path, title, artist=''):
    token = uuid.uuid4().hex[:8]
    now = time.time()
    with _PLAY_FILE_TOKEN_LOCK:
        for k in [k for k, v in _PLAY_FILE_TOKENS.items() if v['exp'] < now]:
            del _PLAY_FILE_TOKENS[k]
        _PLAY_FILE_TOKENS[token] = {
            'path': path,
            'title': title or os.path.basename(path),
            'artist': (artist or '').strip(),
            'exp': now + _PLAY_FILE_TOKEN_TTL,
        }
    return token


def _consume_play_file_token(raw):
    token = re.sub(r'[^a-f0-9]', '', (raw or '').lower())
    if not token:
        return None
    with _PLAY_FILE_TOKEN_LOCK:
        entry = _PLAY_FILE_TOKENS.get(token)
        if not entry or entry['exp'] < time.time():
            return None
        return dict(entry)


def _play_file_token_from_query(query):
    """Parse 'file token <hex>' / 'token <hex>' from a misrouted slot value."""
    q = (query or '').strip().lower()
    m = re.search(r'(?:file\s+)?token\s+([a-f0-9]{6,12})\b', q)
    return _consume_play_file_token(m.group(1)) if m else None


# UI playlist plays use a short token so Alexa hears an exact phrase instead of
# garbling long playlist titles (which causes fuzzy-match failures / timeouts).
_PLAYLIST_TOKENS = {}
_PLAYLIST_TOKEN_LOCK = threading.Lock()
_PLAYLIST_TOKEN_TTL = 600.0
_PLAY_TOKENS_FILE = os.path.join(DATA_DIR, 'play_tokens.json')


def _load_playlist_tokens():
    global _PLAYLIST_TOKENS
    try:
        with open(_PLAY_TOKENS_FILE) as f:
            data = json.load(f) or {}
        now = time.time()
        _PLAYLIST_TOKENS = {
            k: v for k, v in (data.get('playlist') or {}).items()
            if v.get('exp', 0) > now
        }
    except Exception:
        _PLAYLIST_TOKENS = {}


def _save_playlist_tokens():
    try:
        with open(_PLAY_TOKENS_FILE, 'w') as f:
            json.dump({'playlist': _PLAYLIST_TOKENS}, f)
    except Exception as ex:
        print(f'[PLAY TOKEN] save failed: {ex}', flush=True)


_load_playlist_tokens()


def _new_playlist_token_id():
    """Digit-only id — Alexa handles spaced digits better than hex."""
    return str(random.randint(10_000_000, 99_999_999))


def _register_play_playlist_token(playlist_id, name, source, shuffle=False):
    token = _new_playlist_token_id()
    now = time.time()
    with _PLAYLIST_TOKEN_LOCK:
        for k in [k for k, v in _PLAYLIST_TOKENS.items() if v.get('exp', 0) < now]:
            del _PLAYLIST_TOKENS[k]
        _PLAYLIST_TOKENS[token] = {
            'kind': 'playlist',
            'id': str(playlist_id),
            'name': name or '',
            'source': (source or '').strip(),
            'shuffle': bool(shuffle),
            'exp': now + _PLAYLIST_TOKEN_TTL,
        }
        _save_playlist_tokens()
    print(f'[PLAY TOKEN] registered {token} playlist={name!r} shuffle={shuffle}', flush=True)
    return token


def _register_play_album_token(album, artist=None, shuffle=False):
    """Short-lived token so UI album plays avoid fuzzy NLU on album titles."""
    token = _new_playlist_token_id()
    now = time.time()
    with _PLAYLIST_TOKEN_LOCK:
        for k in [k for k, v in _PLAYLIST_TOKENS.items() if v.get('exp', 0) < now]:
            del _PLAYLIST_TOKENS[k]
        _PLAYLIST_TOKENS[token] = {
            'kind': 'album',
            'album': album or '',
            'artist': (artist or '').strip(),
            'shuffle': bool(shuffle),
            'exp': now + _PLAYLIST_TOKEN_TTL,
        }
        _save_playlist_tokens()
    print(f'[PLAY TOKEN] registered {token} album={album!r} artist={artist!r} shuffle={shuffle}', flush=True)
    return token


def _normalize_play_token(raw):
    """Extract digit token from Alexa slot (handles 'token 12345678' or '12 34 56 78')."""
    chunk = (raw or '').strip().lower()
    if 'token' in chunk:
        chunk = chunk.split('token', 1)[1]
    digits = re.sub(r'[^0-9]', '', chunk)
    return digits if len(digits) >= 6 else ''


def _consume_play_playlist_token(raw):
    token = _normalize_play_token(raw)
    if not token:
        return None
    with _PLAYLIST_TOKEN_LOCK:
        _load_playlist_tokens()
        entry = _PLAYLIST_TOKENS.get(token)
        if not entry or entry.get('exp', 0) < time.time():
            return None
        return dict(entry)


def _play_playlist_token_from_query(query):
    return _consume_play_playlist_token(query)


def _start_playlist_token_entry(token_entry, shuffle=None):
    name = token_entry.get('name') or 'playlist'
    pid = token_entry.get('id')
    source = token_entry.get('source')
    do_shuffle = token_entry.get('shuffle', False) if shuffle is None else shuffle
    speech = f"Shuffling {name}." if do_shuffle else f"Playing {name}."
    return start_playing(None, shuffle=do_shuffle, speech=speech,
                        playlist=name, playlist_id=pid, source=source)


def _album_tracks_for_play(album, artist=None, shuffle=False, limit=50):
    order = 'ORDER BY RANDOM()' if shuffle else 'ORDER BY CAST(track_number AS INTEGER), title'
    ext = "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac')"
    base = f"SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL {ext}"
    artist = (artist or '').strip() or None
    if artist:
        rows = db_query(
            f"{base} AND (artist = ? OR album_artist = ?) {order} LIMIT ?",
            [album, artist, artist, limit],
        )
        if rows:
            return [r['path'] for r in rows]
    rows = db_query(f"{base} {order} LIMIT ?", [album, limit])
    return [r['path'] for r in rows]


def _start_album_token_entry(token_entry, shuffle=None):
    album = token_entry.get('album') or 'album'
    artist = (token_entry.get('artist') or '').strip() or None
    do_shuffle = token_entry.get('shuffle', False) if shuffle is None else shuffle
    tracks = _album_tracks_for_play(album, artist=artist, shuffle=do_shuffle)
    if not tracks:
        return alexa_speak(f"I found {album} but no playable files.")
    speech = (f"Shuffling the album {album}." if do_shuffle
              else f"Playing the album {album}.")
    return start_playing(tracks, shuffle=do_shuffle, speech=speech,
                        context=f'Album · {album}')


def _try_ui_token_play(query, shuffle=None):
    """Resolve a UI-registered play token before fuzzy album/artist matching."""
    token_entry = _play_playlist_token_from_query(query)
    if not token_entry:
        return None
    if token_entry.get('kind') == 'album':
        return _start_album_token_entry(token_entry, shuffle=shuffle)
    return _start_playlist_token_entry(token_entry, shuffle=shuffle)


def _play_named_playlist_if_strong_match(query, shuffle=False):
    """Prefer a playlist when NLU misroutes a playlist title into album/artist intents."""
    entry = best_playlist_entry(query)
    if not entry:
        return None
    pid, name, source = entry
    if _score_playlist(query, name) < 0.90:
        return None
    if not source or not os.path.isfile(source):
        return None
    speech = f"Shuffling {name}." if shuffle else f"Playing {name}."
    return start_playing(None, shuffle=shuffle, speech=speech,
                         playlist=name, playlist_id=pid, source=source)


def _build_play_text(kind, name, shuffle, artist=None, path=None, playlist_id=None, playlist_source=None):
    """Build a collision-safe utterance that routes to our custom skill.

    "play"/"shuffle" get grabbed by Amazon's music domain; "start"/"mix" route
    to the custom skill (no account linking, serves the library directly). The
    interaction model declares these verbs for every Play*/Shuffle* intent.
    See alexa-skill-troubleshooting rule.
    """
    alias = _alexa_alias()
    verb = 'mix' if shuffle else 'start'
    if kind == 'artist':
        phrase = f"music by {name}"
    elif kind == 'album':
        token = _register_play_album_token(name, artist=artist, shuffle=shuffle)
        phrase = f"album token {token}"
    elif kind == 'song':
        verb = 'start'
        artist = (artist or '').strip()
        fpath = (path or '').strip()
        if fpath and os.path.isfile(fpath):
            token = _register_play_file_token(fpath, name, artist)
            phrase = f"file token {token}"
        elif artist:
            phrase = f"the song {name} by {artist}"
        else:
            phrase = f"the song {name}"
    else:  # playlist
        pid = (playlist_id or '').strip()
        src = (playlist_source or '').strip()
        if pid:
            if not src:
                _, src = _msp_playlist_by_id(pid)
            token = _register_play_playlist_token(pid, name, src, shuffle=shuffle)
            # Explicit "playlist token" routes to PlayFileTokenIntent and avoids
            # album/artist intents grabbing bare titles like "Mamma Mia".
            phrase = f"playlist token {token}"
        else:
            phrase = f"the {name} playlist"
    return f"ask {alias} to {verb} {phrase}"


# ── Play-intent correlation (rotated deviceId → physical room) ────────────────
# Amazon never gives a custom skill a stable device id — `deviceId` rotates
# (notably after a device sits idle). But when WE start playback from the web UI
# or an automation we command alexapy by the device's STABLE serial + room name.
# So the next unknown/auto-named deviceId that fires PlaybackStarted right after
# is almost certainly the room we just targeted: bind it, and the daily manual
# merge goes away. Single-target plays are unambiguous; group fan-outs are NOT
# (every room plays the same track at once, Amazon gives no room signal), so we
# deliberately suppress correlation during a group window.
_PLAY_INTENTS = []                # [{'name','serial','ts'}]
_PLAY_INTENT_LOCK = threading.Lock()
_PLAY_INTENT_TTL = 12.0           # secs a single-play intent stays correlatable
                                  # (short so back-to-back individual plays each
                                  #  resolve cleanly instead of overlapping)
_PLAY_GROUP_TTL = 60.0            # group-suppression window (longer: covers a
                                  #  full fan-out where slow Echoes start late)
_PLAY_GROUP_UNTIL = 0.0           # suppress correlation during/after a group play
# Recent web/automation play context keyed by Echo serial (playlist name for NP UI).
_DEVICE_PLAY_CONTEXT = {}
_PLAY_CONTEXT_LOCK = threading.Lock()
_PLAY_CONTEXT_TTL = 120.0

_ALEXA_DEV_CACHE = {'ts': 0.0, 'by_serial': {}}
_ALEXA_DEV_CACHE_TTL = 300.0
_ALEXA_DEV_CACHE_LOCK = threading.Lock()

def _alexa_name_for_serial(serial):
    """Best-effort friendly Alexa name for a serial (cached ~5min)."""
    if not serial:
        return ''
    now = time.time()
    with _ALEXA_DEV_CACHE_LOCK:
        stale = now - _ALEXA_DEV_CACHE['ts'] > _ALEXA_DEV_CACHE_TTL
        if stale or not _ALEXA_DEV_CACHE['by_serial']:
            try:
                import alexa_remote
                devs = alexa_remote.list_devices() or []
                _ALEXA_DEV_CACHE['by_serial'] = {
                    d.get('serial'): d.get('name') for d in devs if d.get('serial')}
                _ALEXA_DEV_CACHE['ts'] = now
            except Exception:
                pass
        return _ALEXA_DEV_CACHE['by_serial'].get(serial, '') or ''

# Identify/test sweeps play a real playlist on a device just to hear it; those
# plays must NOT pollute analytics. We mark the serial as "test" for a short
# window, and the PlaybackStarted handler tags the resulting history row.
_TEST_SERIALS = {}                # serial -> expiry epoch
_TEST_SERIAL_TTL = 30.0
_TEST_SERIAL_LOCK = threading.Lock()

def _mark_test_serial(serial):
    if not serial:
        return
    with _TEST_SERIAL_LOCK:
        _TEST_SERIALS[serial] = time.time() + _TEST_SERIAL_TTL

def _is_test_serial(serial):
    if not serial:
        return False
    now = time.time()
    with _TEST_SERIAL_LOCK:
        for s in [k for k, exp in _TEST_SERIALS.items() if exp < now]:
            _TEST_SERIALS.pop(s, None)
        return serial in _TEST_SERIALS

def _identify_playlist_name():
    """Dedicated short clip for identify/test, configurable via config.json
    `identifyPlaylist`; falls back to the first available playlist."""
    name = (load_config().get('identifyPlaylist') or '').strip()
    if name:
        return name
    pls = _load_playlist_entries()
    return pls[0][1] if pls else ''

def _record_play_context(targets, *, playlist=None, playlist_id=None, context=None):
    """Remember what we asked Alexa to play (for Now Playing before queue events)."""
    now = time.time()
    with _PLAY_CONTEXT_LOCK:
        for serial, _name in targets or []:
            if not serial:
                continue
            _DEVICE_PLAY_CONTEXT[serial] = {
                'playlist': playlist,
                'playlistId': playlist_id,
                'context': context,
                'ts': now,
            }


def _play_context_for_device(device_id):
    store = _load_devices()
    ent = store.get(_resolve_device_id(device_id)) or {}
    serial = ent.get('serial')
    if not serial:
        return {}
    now = time.time()
    with _PLAY_CONTEXT_LOCK:
        ctx = _DEVICE_PLAY_CONTEXT.get(serial)
    if not ctx or now - ctx.get('ts', 0) > _PLAY_CONTEXT_TTL:
        return {}
    return ctx


def _np_source_fields(token=None, device_id=None):
    """Display label + ids for the active queue (playlist or artist/album context)."""
    playlist, playlist_id, context = None, None, None
    if token:
        data = decode_token(token) if isinstance(token, str) else (token or {})
        playlist = data.get('playlist')
        playlist_id = data.get('playlist_id')
        context = data.get('context')
    if not playlist and not context and device_id:
        ctx = _play_context_for_device(device_id)
        playlist = ctx.get('playlist')
        playlist_id = ctx.get('playlistId')
        context = ctx.get('context')
    label = playlist or context or ''
    return {
        'playlist': playlist,
        'playlistId': playlist_id,
        'context': context,
        'sourceLabel': label,
    }


def _record_play_intent(targets, *, playlist=None, playlist_id=None, context=None):
    """targets: list of (serial, name). One target → correlatable; many → mark
    an ambiguous group window so we don't mis-attribute rooms."""
    global _PLAY_GROUP_UNTIL
    now = time.time()
    _record_play_context(targets, playlist=playlist, playlist_id=playlist_id, context=context)
    with _PLAY_INTENT_LOCK:
        _PLAY_INTENTS[:] = [i for i in _PLAY_INTENTS if now - i['ts'] < _PLAY_INTENT_TTL]
        if len(targets) == 1 and (targets[0][1] or '').strip():
            _PLAY_INTENTS.append({'name': targets[0][1].strip(),
                                  'serial': targets[0][0], 'ts': now})
        elif len(targets) > 1:
            _PLAY_GROUP_UNTIL = now + _PLAY_GROUP_TTL

def _correlate_play_intent(new_device_id):
    """If exactly one single-device play intent is pending, bind a freshly-seen
    (auto-named) `new_device_id` to that room. Returns True if it bound."""
    if not new_device_id or new_device_id == 'default':
        return False
    now = time.time()
    with _PLAY_INTENT_LOCK:
        if now < _PLAY_GROUP_UNTIL:
            return False
        pending = [i for i in _PLAY_INTENTS if now - i['ts'] < _PLAY_INTENT_TTL]
        if len(pending) != 1:
            return False
        intent = pending[0]
        _PLAY_INTENTS.clear()
    name = intent['name']
    serial = intent.get('serial')
    store = _load_devices()
    ent = store.get(new_device_id) or {}
    if ent.get('aliasOf'):
        return False  # already mapped

    # Serial-first: if this hardware serial is already bound to a primary device
    # (a different deviceId), this is a rotation — fold the new id onto it
    # deterministically, regardless of current name. This is the strongest
    # signal we have and beats name/fingerprint matching.
    serial_primary = _primary_by_serial(serial, store)
    if serial_primary and serial_primary != new_device_id:
        _migrate_state_files(new_device_id, serial_primary)
        _alias_to(new_device_id, serial_primary, store)
        store[new_device_id]['serial'] = serial
        store[serial_primary]['serial'] = serial
        _save_devices(store)
        print(f"[DEVICE CORRELATE] {new_device_id[-12:]} -> serial {serial} == {name!r}", flush=True)
        return True

    cur = (ent.get('name') or '').strip().lower()
    looks_auto = (not cur) or cur == f"echo {new_device_id[-6:]}".lower()
    if not looks_auto:
        # Known, named device — don't relabel, but still record the serial so
        # future rotations can be folded deterministically.
        if serial and ent.get('serial') != serial:
            ent['serial'] = serial
            store[new_device_id] = ent
            _save_devices(store)
        return False
    nl = name.strip().lower()
    target = next((did for did, e in store.items()
                   if not e.get('aliasOf') and did != new_device_id
                   and (e.get('name') or '').strip().lower() == nl), None)
    if target:
        _migrate_state_files(new_device_id, target)
        _alias_to(new_device_id, target, store)
        store[new_device_id]['serial'] = intent['serial']
        if serial:
            store[target]['serial'] = serial  # index serial on the primary
        _save_devices(store)
        print(f"[DEVICE CORRELATE] {new_device_id[-12:]} -> {name!r} (play intent)", flush=True)
    elif name != intent['serial']:
        # Only adopt a real room name — never relabel a device with its serial.
        ent['name'] = name
        ent['serial'] = intent['serial']
        store[new_device_id] = ent
        _save_devices(store)
        print(f"[DEVICE CORRELATE] named {new_device_id[-12:]} = {name!r} (play intent)", flush=True)
    else:
        return False
    return True


@app.route('/api/playlists/play', methods=['POST'])
@app.route('/api/alexa_remote/play', methods=['POST'])
def play_on_device():
    """Start a playlist/artist/album/song on a specific Echo (unofficial Alexa API)."""
    data = request.get_json() or {}
    device = (data.get('device') or '').strip()
    name = (data.get('name') or '').strip()
    pid = (data.get('id') or '').strip()
    kind = (data.get('kind') or 'playlist').strip().lower()
    shuffle = bool(data.get('shuffle'))
    artist = (data.get('artist') or '').strip()
    fpath = (data.get('path') or '').strip()
    if not device:
        return jsonify({'error': 'device required'}), 400
    if not name and pid and kind == 'playlist':
        name, playlist_source = _msp_playlist_by_id(pid)
    else:
        playlist_source = _msp_playlist_by_id(pid)[1] if pid and kind == 'playlist' else None
    if not name:
        return jsonify({'error': 'name or id required'}), 400
    text = _build_play_text(
        kind, name, shuffle, artist=artist, path=fpath,
        playlist_id=pid if kind == 'playlist' else None,
        playlist_source=playlist_source,
    )
    try:
        targets = _expand_play_targets(device)
    except ValueError as e:
        return jsonify({'error': str(e), 'code': str(e)}), 400
    pl_label = name if kind == 'playlist' else None
    ctx_label = None if kind == 'playlist' else (
        f'Artist · {name}' if kind == 'artist' else
        f'Album · {name}' if kind == 'album' else
        (f'Song · {name}' + (f' by {artist}' if artist else '') if kind == 'song' else name)
    )
    _record_play_intent(
        targets,
        playlist=pl_label,
        playlist_id=pid if kind == 'playlist' and pid else None,
        context=ctx_label,
    )
    if kind == 'playlist' and pid:
        warm_src = playlist_source
        if not warm_src:
            _, warm_src = _msp_playlist_by_id(pid)
        if warm_src:
            _playlist_paths_cached(pid, warm_src)
    try:
        import alexa_remote
        results, errors = [], []
        for serial, member_name in targets:
            try:
                results.append(alexa_remote.play_text(serial, text))
            except Exception as e:
                errors.append({'device': member_name, 'error': str(e)})
        if not results:
            code = errors[0]['error'] if errors else 'play_failed'
            status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found') else 500
            return jsonify({'error': code, 'code': code, 'errors': errors}), status
        label = results[0].get('device') if len(results) == 1 else f'{len(results)} devices'
        return jsonify({'ok': True, 'device': label, 'count': len(results), 'errors': errors})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found') else 500
        return jsonify({'error': code, 'code': code}), status


@app.route('/api/playback/status')
def playback_status():
    """Playback reliability snapshot for the dashboard."""
    remote_cfg = False
    remote_auth = None
    device_count = 0
    try:
        import alexa_remote
        remote_cfg = alexa_remote.is_configured()
        remote_auth = alexa_remote.is_authenticated() if remote_cfg else None
        if remote_cfg and remote_auth:
            device_count = len(alexa_remote.list_devices() or [])
    except Exception:
        pass
    skill_testing = None
    try:
        with open(os.path.join(HERE, 'skill_enablement_state.json')) as f:
            skill_testing = bool((json.load(f) or {}).get('enabled'))
    except Exception:
        pass
    return jsonify({
        'alexaRemote': {'configured': remote_cfg, 'authenticated': remote_auth, 'deviceCount': device_count},
        'skillTesting': skill_testing,
        'verbs': {'playlist': 'start', 'shuffle': 'mix'},
        'tips': [
            'Use "start" / "mix" in web Play on device (not play/shuffle) to avoid Spotify hijacking.',
            'Alexa app → Music & Podcasts → set default Music to Amazon Music for better voice routing.',
            'Say "Alexa, open bock media" then only the playlist name when one-shots fail.',
        ],
    })


@app.route('/api/rooms')
def list_rooms():
    """Per-Echo snapshot: now playing, automations, quick play targets."""
    devs = []
    controls = False
    try:
        import alexa_remote
        controls = alexa_remote.is_configured()
        if controls and alexa_remote.is_authenticated():
            devs = alexa_remote.list_devices() or []
    except Exception:
        pass
    store = _load_devices()
    serial_to_did = {}
    for did, ent in store.items():
        s = ent.get('serial')
        if s:
            serial_to_did[s] = _resolve_device_id(did)

    np_payload = _canonicalize_np(_prune_np(_read_all_np() or {'devices': {}}))
    np_by_id = np_payload.get('devices') or {}
    autos = _load_automations()

    rooms = []
    for d in devs:
        serial = d.get('serial')
        name = d.get('name') or serial
        did = serial_to_did.get(serial)
        np_row = None
        if did and did in np_by_id:
            st = np_by_id[did]
            if st.get('playing') or st.get('paused'):
                tok = st.get('token') or ''
                src = _np_source_fields(tok, did)
                np_row = {
                    'track': st.get('track'),
                    'artist': st.get('artist'),
                    'album': st.get('album'),
                    'paused': bool(st.get('paused')) and not st.get('playing'),
                    'sourceLabel': src.get('sourceLabel'),
                    'playlist': src.get('playlist'),
                }
        room_autos = [
            a for a in autos
            if a.get('device') == serial
            or a.get('deviceName') == name
            or (did and a.get('device') == did)
        ]
        rooms.append({
            'serial': serial,
            'name': name,
            'deviceId': did,
            'nowPlaying': np_row,
            'automations': [{'id': a.get('id'), 'name': a.get('name'),
                             'time': a.get('time'), 'playlistName': a.get('playlistName'),
                             'enabled': a.get('enabled')} for a in room_autos],
        })
    # MSP pseudo streams (no real room)
    for did, st in np_by_id.items():
        if not _is_msp_pseudo(did):
            continue
        if not (st.get('playing') or st.get('paused')):
            continue
        tok = st.get('token') or ''
        src = _np_source_fields(tok, did)
        rooms.append({
            'serial': None,
            'name': _device_label(did),
            'deviceId': did,
            'nowPlaying': {
                'track': st.get('track'),
                'artist': st.get('artist'),
                'paused': bool(st.get('paused')) and not st.get('playing'),
                'sourceLabel': src.get('sourceLabel'),
                'playlist': src.get('playlist'),
            },
            'automations': [],
            'pseudo': True,
        })
    return jsonify({'rooms': rooms, 'controlsAvailable': controls})

# ── Device identify (play a short test on each Echo to name/merge them) ───────
# Plays a brief clip on every audio Echo in turn. Each single-device play feeds
# the play-intent correlation, so devices get auto-named as it goes — and you
# hear the test move room-to-room to confirm/merge the rest.
_IDENTIFY = {'running': False, 'total': 0, 'done': 0, 'current': '',
             'named': [], 'errors': []}
_IDENTIFY_LOCK = threading.Lock()
_IDENTIFY_AUDIO_FAMILIES = {'ECHO', 'ROOK', 'KNIGHT', 'WHA'}

def _identify_worker(devices, text, alias, dwell):
    import alexa_remote
    try:
        for d in devices:
            serial, name = d['serial'], d['name']
            with _IDENTIFY_LOCK:
                _IDENTIFY['current'] = name
            _record_play_intent([(serial, name)])  # single → correlatable
            _mark_test_serial(serial)               # keep out of analytics
            try:
                alexa_remote.play_text(serial, text)
            except Exception as e:
                with _IDENTIFY_LOCK:
                    _IDENTIFY['errors'].append(f'{name}: {e}')
                    _IDENTIFY['done'] += 1
                continue
            time.sleep(dwell)                       # let PlaybackStarted correlate
            try:
                alexa_remote.device_control(serial, 'stop', alias)
            except Exception:
                pass
            with _IDENTIFY_LOCK:
                _IDENTIFY['named'].append(name)
                _IDENTIFY['done'] += 1
            time.sleep(1.0)
    finally:
        with _IDENTIFY_LOCK:
            _IDENTIFY['running'] = False
            _IDENTIFY['current'] = ''

@app.route('/api/devices/identify', methods=['POST'])
def identify_devices():
    body = request.get_json(silent=True) or {}
    with _IDENTIFY_LOCK:
        if _IDENTIFY['running']:
            return jsonify({'error': 'already_running', **_IDENTIFY}), 409
    try:
        import alexa_remote
        devs = alexa_remote.list_devices() or []
    except ImportError:
        return jsonify({'error': 'not_installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated') else 500
        return jsonify({'error': code, 'code': code}), status
    # Only real, online audio Echoes (skip Fire TVs, Fitbits, Echo Auto, …).
    targets = [d for d in devs
               if d.get('online') and d.get('family') in _IDENTIFY_AUDIO_FAMILIES]
    if not targets:
        return jsonify({'error': 'no_devices', 'code': 'no_devices'}), 400
    # Test clip = a dedicated short playlist (config identifyPlaylist), stopped
    # after a few seconds. Tagged so these plays stay out of analytics.
    name = (body.get('playlist') or '').strip() or _identify_playlist_name()
    if not name:
        return jsonify({'error': 'no_playlist', 'code': 'no_playlist'}), 400
    text = _build_play_text('playlist', name, False)
    dwell = max(5.0, min(20.0, float(body.get('dwell') or 9.0)))
    with _IDENTIFY_LOCK:
        _IDENTIFY.update({'running': True, 'total': len(targets), 'done': 0,
                          'current': '', 'named': [], 'errors': []})
    threading.Thread(target=_identify_worker,
                     args=(targets, text, _alexa_alias(), dwell),
                     daemon=True).start()
    eta = int(len(targets) * (dwell + 1.5))
    return jsonify({'ok': True, 'total': len(targets), 'playlist': name, 'etaSeconds': eta})

@app.route('/api/devices/identify/status')
def identify_status():
    with _IDENTIFY_LOCK:
        return jsonify(dict(_IDENTIFY))

@app.route('/api/devices/test', methods=['POST'])
def test_device():
    """Play a short clip on ONE speaker (by serial) then stop — for identifying
    and auto-naming a single Echo. Runs in the background; returns immediately."""
    body = request.get_json(silent=True) or {}
    serial = (body.get('serial') or body.get('device') or '').strip()
    if not serial:
        return jsonify({'error': 'serial required'}), 400
    name = (body.get('name') or '').strip() or _alexa_name_for_serial(serial) or serial
    pl = (body.get('playlist') or '').strip() or _identify_playlist_name()
    if not pl:
        return jsonify({'error': 'no_playlist', 'code': 'no_playlist'}), 400
    text = _build_play_text('playlist', pl, False)
    dwell = max(4.0, min(20.0, float(body.get('dwell') or 8.0)))
    alias = _alexa_alias()

    def worker():
        import alexa_remote
        _record_play_intent([(serial, name)])   # single → correlatable
        _mark_test_serial(serial)               # keep out of analytics
        try:
            alexa_remote.play_text(serial, text)
        except Exception as e:
            print(f"[DEVICE TEST] play failed for {name!r}: {e}", flush=True)
            return
        time.sleep(dwell)
        try:
            alexa_remote.device_control(serial, 'stop', alias)
        except Exception:
            pass

    try:
        import alexa_remote  # noqa: F401 — surface not-installed before threading
    except ImportError:
        return jsonify({'error': 'not_installed', 'code': 'not_installed'}), 503
    threading.Thread(target=worker, daemon=True).start()
    return jsonify({'ok': True, 'device': name})

# ── Automations (scheduled playlist playback) ────────────────────────────────

AUTOMATIONS_PATH = os.path.join(DATA_DIR, 'automations.json')
_LEGACY_AUTOMATIONS_PATH = os.path.join(HERE, 'automations.json')
_AUTOMATIONS_LOCK = threading.Lock()


def _ensure_automations_file():
    """Use DATA_DIR so automations survive git clean / repo path changes."""
    if os.path.exists(AUTOMATIONS_PATH):
        return
    os.makedirs(DATA_DIR, exist_ok=True)
    if os.path.exists(_LEGACY_AUTOMATIONS_PATH):
        shutil.copy2(_LEGACY_AUTOMATIONS_PATH, AUTOMATIONS_PATH)
        return
    legacy_mma = os.path.expanduser('~/.MyMediaForAlexa/automations.json')
    if os.path.exists(legacy_mma):
        shutil.copy2(legacy_mma, AUTOMATIONS_PATH)
_TIME_RE = re.compile(r'^([01]\d|2[0-3]):([0-5]\d)$')
_DAY_PRESETS = {
    'daily':    [0, 1, 2, 3, 4, 5, 6],
    'weekdays': [0, 1, 2, 3, 4],
    'weekends': [5, 6],
}


def _load_automations():
    with _AUTOMATIONS_LOCK:
        _ensure_automations_file()
        if not os.path.exists(AUTOMATIONS_PATH):
            return []
        try:
            with open(AUTOMATIONS_PATH) as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:
            return []


def _save_automations(items):
    with _AUTOMATIONS_LOCK:
        tmp = AUTOMATIONS_PATH + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(items, f, indent=2)
        os.replace(tmp, AUTOMATIONS_PATH)


def _normalize_days(raw):
    if isinstance(raw, str):
        preset = raw.strip().lower()
        if preset in _DAY_PRESETS:
            return list(_DAY_PRESETS[preset])
        return []
    days = []
    for d in raw or []:
        try:
            n = int(d)
        except (TypeError, ValueError):
            continue
        if 0 <= n <= 6:
            days.append(n)
    return sorted(set(days))


def _validate_automation_body(body, existing=None):
    name = (body.get('name') or '').strip()
    playlist_name = (body.get('playlistName') or '').strip()
    playlist_id = (body.get('playlistId') or '').strip()
    device = (body.get('device') or '').strip()
    device_name = (body.get('deviceName') or '').strip()
    time_str = (body.get('time') or '').strip()
    days = _normalize_days(body.get('days'))
    shuffle = bool(body.get('shuffle'))
    enabled = bool(body.get('enabled')) if 'enabled' in body else (existing.get('enabled', True) if existing else True)
    volume = (existing or {}).get('volume') if existing else None
    if 'volume' in body:
        raw_vol = body.get('volume')
        if raw_vol is None or raw_vol == '':
            volume = None
        else:
            try:
                volume = int(raw_vol)
            except (TypeError, ValueError):
                return None, ('volume must be 0-100', 400)
            if not 0 <= volume <= 100:
                return None, ('volume must be 0-100', 400)

    if not playlist_name and playlist_id:
        playlist_name, _ = _msp_playlist_by_id(playlist_id)
    if not playlist_name:
        return None, ('playlistName or playlistId required', 400)
    if not device:
        return None, ('device required', 400)
    if not _TIME_RE.match(time_str):
        return None, ('time must be HH:MM (24-hour)', 400)
    if not days:
        return None, ('at least one day required', 400)

    now = time.time()
    item = {
        'id': (existing or {}).get('id') or str(uuid.uuid4()),
        'name': name or f'{playlist_name} on {device_name or device[-6:]}',
        'enabled': enabled,
        'playlistId': playlist_id,
        'playlistName': playlist_name,
        'device': device,
        'deviceName': device_name or device,
        'shuffle': shuffle,
        'time': time_str,
        'days': days,
        'updatedAt': now,
    }
    if volume is not None:
        item['volume'] = volume
    if existing:
        item['createdAt'] = existing.get('createdAt', now)
        item['lastFiredAt'] = existing.get('lastFiredAt')
        item['lastRunAt'] = existing.get('lastRunAt')
        item['lastRunStatus'] = existing.get('lastRunStatus')
    else:
        item['createdAt'] = now
    return item, None


def _fire_automation(auto):
    text = _build_play_text('playlist', auto['playlistName'], auto.get('shuffle'))
    import alexa_remote
    targets = _expand_play_targets(auto['device'])
    _record_play_intent(
        targets,
        playlist=auto.get('playlistName'),
        playlist_id=auto.get('playlistId'),
    )
    vol = auto.get('volume')
    results, errors = [], []
    for serial, member_name in targets:
        if vol is not None:
            try:
                alexa_remote.set_volume(serial, int(vol))
                time.sleep(0.4)
            except Exception as e:
                errors.append(f'{member_name} volume: {e}')
        try:
            results.append(alexa_remote.play_text(serial, text))
        except Exception as e:
            errors.append(f'{member_name}: {e}')
    if not results:
        raise RuntimeError('; '.join(errors) or 'play_failed')
    return {'count': len(results), 'errors': errors}


def _tick_automations():
    now = datetime.datetime.now()
    slot_key = now.strftime('%Y-%m-%d %H:%M')
    current_time = now.strftime('%H:%M')
    current_day = now.weekday()

    items = _load_automations()
    changed = False
    for auto in items:
        if not auto.get('enabled'):
            continue
        if auto.get('time') != current_time:
            continue
        if current_day not in (auto.get('days') or []):
            continue
        if auto.get('lastFiredAt') == slot_key:
            continue
        try:
            _fire_automation(auto)
            auto['lastRunStatus'] = 'ok'
            print(f'AUTOMATION ok: {auto.get("name")} -> {auto.get("deviceName")} at {slot_key}')
        except Exception as e:
            auto['lastRunStatus'] = str(e)
            print(f'AUTOMATION fail: {auto.get("name")}: {e}')
        auto['lastFiredAt'] = slot_key
        auto['lastRunAt'] = time.time()
        changed = True
    if changed:
        _save_automations(items)


def _automation_scheduler_loop():
    while True:
        try:
            _tick_automations()
        except Exception as e:
            print(f'automation scheduler error: {e}')
        time.sleep(30)


_automation_scheduler_started = False

def _start_automation_scheduler():
    global _automation_scheduler_started
    if _automation_scheduler_started:
        return
    _automation_scheduler_started = True
    t = threading.Thread(target=_automation_scheduler_loop, daemon=True, name='automation-scheduler')
    t.start()


@app.route('/api/automations')
def list_automations():
    items = _load_automations()
    items.sort(key=lambda x: (x.get('time') or '', x.get('name') or ''))
    return jsonify({'items': items})


@app.route('/api/automations', methods=['POST'])
def create_automation():
    body = request.get_json() or {}
    item, err = _validate_automation_body(body)
    if err:
        return jsonify({'error': err[0]}), err[1]
    items = _load_automations()
    items.append(item)
    _save_automations(items)
    return jsonify(item), 201


@app.route('/api/automations/<auto_id>', methods=['PUT'])
def update_automation(auto_id):
    body = request.get_json() or {}
    items = _load_automations()
    idx = next((i for i, a in enumerate(items) if a.get('id') == auto_id), None)
    if idx is None:
        return jsonify({'error': 'not found'}), 404
    item, err = _validate_automation_body(body, existing=items[idx])
    if err:
        return jsonify({'error': err[0]}), err[1]
    items[idx] = item
    _save_automations(items)
    return jsonify(item)


@app.route('/api/automations/<auto_id>', methods=['DELETE'])
def delete_automation(auto_id):
    items = _load_automations()
    new_items = [a for a in items if a.get('id') != auto_id]
    if len(new_items) == len(items):
        return jsonify({'error': 'not found'}), 404
    _save_automations(new_items)
    return jsonify({'ok': True})


@app.route('/api/automations/<auto_id>/run', methods=['POST'])
def run_automation_now(auto_id):
    items = _load_automations()
    auto = next((a for a in items if a.get('id') == auto_id), None)
    if not auto:
        return jsonify({'error': 'not found'}), 404
    try:
        result = _fire_automation(auto)
        errs = result.get('errors') or []
        auto['lastRunStatus'] = 'ok (manual)' + (f'; {"; ".join(errs)}' if errs else '')
        auto['lastRunAt'] = time.time()
        _save_automations(items)
        return jsonify({'ok': True, **result})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found') else 500
        return jsonify({'error': code, 'code': code}), status

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
        f'SELECT artist, COUNT(*) as track_count, COUNT(DISTINCT album) as album_count, '
        f'MIN(CASE WHEN path IS NOT NULL AND path != "" THEN path END) as art_path '
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
    sort = (request.args.get('sort') or 'name').strip().lower()
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
    order = 'year DESC, album' if sort == 'year' else 'album'

    rows = db_query(
        f'SELECT album, COALESCE(NULLIF(album_artist,""), artist) as artist, '
        f'COUNT(*) as track_count, '
        f'MAX(CAST(NULLIF(year, "") AS INTEGER)) as year, '
        f'MIN(CASE WHEN path IS NOT NULL AND path != "" THEN path END) as art_path '
        f'FROM songs_cache WHERE {where} '
        f'GROUP BY album, artist ORDER BY {order} LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    total_row = db_one(
        f'SELECT COUNT(DISTINCT album) as total FROM songs_cache WHERE {where}',
        params
    )
    return jsonify({'items': rows, 'total': total_row.get('total', 0)})


@app.route('/api/genres')
def genres_list():
    limit = min(max(int(request.args.get('limit', 20) or 20), 1), 50)
    rows = db_query(
        'SELECT genre, COUNT(*) as track_count FROM songs_cache '
        'WHERE genre IS NOT NULL AND genre != "" '
        'GROUP BY genre ORDER BY track_count DESC LIMIT ?',
        [limit],
    ) or []
    items = []
    for row in rows:
        genre = row.get('genre') or ''
        if not genre:
            continue
        art_row = db_one(
            'SELECT path FROM songs_cache WHERE genre = ? AND path IS NOT NULL AND path != "" LIMIT 1',
            [genre],
        )
        items.append({
            'name': genre,
            'track_count': row.get('track_count') or 0,
            'art_path': art_row.get('path') if art_row else None,
        })
    return jsonify({'items': items, 'total': len(items)})

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
    'webUsername':          'WebUsername',
    'webPassword':          'WebPassword',
    'autoImportPlaylists':  'AutoImportPlaylists',
    'suppressAutoScan':     'SuppressAutoScan',
    'sendAlbumArt':         'SendAlbumArt',
    'sendMetadata':         'SendMetadata',
    'verboseLogging':       'VerboseLogging',
    'scanIgnoreFiles':      'ScanIgnoreFiles',
    'bypassProxy':          'BypassProxy',
    'allowExternalAccess':  'AllowExternalAccess',
}

@app.route('/api/auth/info')
def auth_info():
    """Public login hints (no password)."""
    return jsonify({
        'requirePassword': _api_auth_required(),
        'username': _web_username(),
    })

@app.route('/api/settings', methods=['GET'])
def settings_get():
    try:
        tree = ET.parse(os.path.join(DATA_DIR, 'Preferences.xml'))
        root = tree.getroot()
        def t(tag): return (root.find(tag).text or '') if root.find(tag) is not None else ''
        return jsonify({k: t(v) for k, v in SETTINGS_MAP.items()})
    except Exception as e:
        return jsonify({})

@app.route('/api/settings', methods=['POST'])
def settings_post():
    data = request.get_json() or {}
    prefs_path = os.path.join(DATA_DIR, 'Preferences.xml')
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
    cache_dir = os.path.join(DATA_DIR, 'ImageCache')
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

def _atomic_json_write(path, data, **dump_kwargs):
    """Write JSON atomically: write to a unique .tmp then os.replace.

    The temp name is unique per write (pid + thread + random) so concurrent
    writers — e.g. two members of a device group whose Echoes hit the skill at
    the same instant — never share a temp file and clobber each other's replace.
    """
    tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.{os.urandom(4).hex()}.tmp"
    try:
        with open(tmp, 'w') as f:
            json.dump(data, f, **dump_kwargs)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass

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
        _atomic_json_write(DEVICES_PATH, data, indent=2)
    except Exception as e:
        print(f'devices write error: {e}', flush=True)

def _resolve_device_id(device_id, store=None):
    """Follow aliasOf chain so a merged/rotated id reports as its primary.

    Alexa occasionally rotates `deviceId` (after re-link, endpoint change, or
    skill re-enable). We persist `aliasOf` so the next event from the rotated
    id lands on the original device's analytics, queue, and now-playing state.
    """
    if not device_id:
        return device_id
    if store is None:
        store = _load_devices()
    seen = set()
    cur = device_id
    for _ in range(4):
        entry = store.get(cur) or {}
        nxt = entry.get('aliasOf')
        if not nxt or nxt in seen or nxt == cur:
            return cur
        seen.add(cur)
        cur = nxt
    return cur

def _primary_by_serial(serial, store=None):
    """Return the primary (non-alias) deviceId already bound to this Alexa
    hardware serial, or None.

    Alexa skill/AudioPlayer events never carry a serial — only an opaque,
    occasionally-rotating deviceId. But alexapy-initiated plays (identify, test,
    Play-on-device) DO know the serial, and we persist it on the device entry
    when we correlate. That makes the serial the one stable hardware key we
    have: a rotated deviceId for the same speaker can be folded onto its
    original room deterministically, without guessing by name or fingerprint.
    """
    if not serial:
        return None
    if store is None:
        store = _load_devices()
    for did, e in store.items():
        if e.get('aliasOf'):
            continue
        if e.get('serial') == serial:
            return did
    return None

def _fingerprint_interfaces(supported_interfaces):
    """Stable fingerprint of an Alexa device's supportedInterfaces.

    Alexa skills can't see a hardware/serial id — only an opaque `deviceId`
    that occasionally rotates. The capability set (AudioPlayer / Display /
    APL / VideoApp / …) is a same-physical-device-always-equal signal we
    can use to detect rotations and propose merges.
    """
    if not isinstance(supported_interfaces, dict):
        return ''
    return ','.join(sorted(supported_interfaces.keys()))


def _merge_fingerprints(existing, incoming):
    """Union of capability fingerprints — never lose interfaces we've seen.

    AudioPlayer events report only `{AudioPlayer:{}}` even from Echo Show
    devices, so without unioning we'd degrade a rich fingerprint
    (e.g. 'Alexa.Presentation.APL,AudioPlayer,Display') to bare 'AudioPlayer'
    on the next event, breaking subsequent auto-merge attempts.
    """
    parts = set()
    for s in (existing or '', incoming or ''):
        for p in s.split(','):
            p = p.strip()
            if p:
                parts.add(p)
    return ','.join(sorted(parts))

def _auto_merge_ok_fingerprint(fp):
    """Bare AudioPlayer is shared by nearly every Echo — too weak to auto-merge."""
    return bool(fp) and fp != 'AudioPlayer'

def _auto_merge_target(new_id, fp, store):
    """Return the renamed device this rotated id most likely belongs to, or None.

    Auto-merge requires high confidence:
      * matching fingerprint
      * winner's renamed device went silent within the last 24h (so this looks
        like an actual rotation event, not a stale stranger)
      * any runner-up with the same fingerprint went silent at least 24h
        BEFORE the winner — i.e. clean temporal separation
    Otherwise we defer to manual merge via the candidates UI.
    """
    if not _auto_merge_ok_fingerprint(fp):
        return None
    now = time.time()
    matches = []
    for did, e in store.items():
        if did == new_id or e.get('aliasOf'):
            continue
        if e.get('fingerprint') != fp:
            continue
        n = (e.get('name') or '').strip().lower()
        looks_auto = (not n) or n == f"echo {did[-6:]}".lower()
        if looks_auto:
            continue
        last = e.get('lastSeen') or 0
        gap_h = (now - last) / 3600.0 if last else 999
        matches.append((gap_h, did))
    if not matches:
        return None
    if len(matches) > 1:
        # Multiple renamed devices share this fingerprint (e.g. two Echo Shows
        # both report bare AudioPlayer) — cannot tell which one rotated.
        return None
    matches.sort()
    winner_gap, winner_id = matches[0]
    if winner_gap > 24:
        return None
    return winner_id


def _alias_to(source_id, target_id, store):
    """Mark source as an alias of target in-place (does NOT migrate history).

    Used by register_device when it auto-merges a freshly-rotated deviceId.
    The new id has no history yet, so we only need to plant the alias; future
    events resolve via _resolve_device_id and land on the existing primary.
    """
    src = store.get(source_id) or {}
    tgt = store.get(target_id) or {}
    target_name = tgt.get('name') or target_id[-6:]
    store[source_id] = {
        'aliasOf':   target_id,
        'name':      target_name,
        'firstSeen': src.get('firstSeen'),
        'lastSeen':  src.get('lastSeen'),
        'mergedAt':  time.time(),
        'autoMerged': True,
    }


def register_device(device_id, default_name=None, supported_interfaces=None):
    if not device_id:
        return
    data = _load_devices()
    primary = _resolve_device_id(device_id, data)
    now = time.time()
    incoming_fp = _fingerprint_interfaces(supported_interfaces) if supported_interfaces else ''
    if primary != device_id:
        prim_entry = data.get(primary)
        if prim_entry is not None:
            prim_entry['lastSeen'] = now
            if incoming_fp:
                prim_entry['fingerprint'] = _merge_fingerprints(prim_entry.get('fingerprint',''), incoming_fp)
            _save_devices(data)
        return

    entry = data.get(device_id)
    if not entry:
        data[device_id] = {
            'name': default_name or f'Echo {device_id[-6:]}',
            'firstSeen': now,
            'lastSeen': now,
            'fingerprint': incoming_fp,
            'platform': 'alexa',
        }
    else:
        entry['lastSeen'] = now
        if not entry.get('platform'):
            entry['platform'] = 'alexa'
        if default_name and not entry.get('name'):
            entry['name'] = default_name
        if incoming_fp:
            entry['fingerprint'] = _merge_fingerprints(entry.get('fingerprint',''), incoming_fp)

    cur = data[device_id]
    cur_name = (cur.get('name') or '').strip().lower()
    looks_auto = (not cur_name) or cur_name == f"echo {device_id[-6:]}".lower()

    # Auto-merge on every event while the device is still auto-named so we get
    # a second chance even if the first event had a degraded fingerprint
    # (AudioPlayer events from Echo Shows omit Display from supportedInterfaces).
    if looks_auto and cur.get('fingerprint'):
        tgt = _auto_merge_target(device_id, cur['fingerprint'], data)
        if tgt:
            _alias_to(device_id, tgt, data)
            print(f"[DEVICE AUTO-MERGE] {device_id[-12:]} -> {(data[tgt].get('name') or '')!r}", flush=True)

    _save_devices(data)

def device_friendly_name(device_id):
    if not device_id:
        return ''
    store = _load_devices()
    primary = _resolve_device_id(device_id, store)
    return (store.get(primary) or {}).get('name') or ''

def _device_label(device_id):
    """Human-readable device name for now-playing / history."""
    if not device_id or device_id == 'default':
        return 'default'
    if _is_msp_pseudo(device_id):
        return MSP_DEVICE_NAME
    store = _load_devices()
    entry = store.get(device_id) or {}
    if entry.get('aliasOf'):
        label = device_friendly_name(device_id)
        if label:
            return label
    name = (entry.get('name') or '').strip()
    if name and name.lower() != f"echo {device_id[-6:]}".lower():
        return name
    return f"Echo {device_id[-6:]}"

@app.route('/api/devices')
def devices():
    data = _load_devices()
    result = []
    for did, e in data.items():
        if e.get('aliasOf'):
            continue
        result.append({
            'deviceId':    did,
            'name':        e.get('name') or did[-6:],
            'lastSeen':    e.get('lastSeen'),
            'firstSeen':   e.get('firstSeen'),
            'fingerprint': e.get('fingerprint') or '',
            'platform':    e.get('platform') or ('alexa' if did.startswith('amzn1.') else 'unknown'),
            'connectCount': e.get('connectCount', 0),
            'downloadCount': e.get('downloadCount', 0),
        })
    result.sort(key=lambda x: x.get('lastSeen') or 0, reverse=True)
    return jsonify(result)


@app.route('/api/devices/<device_id>/dismiss_candidate', methods=['POST'])
def dismiss_merge_candidate(device_id):
    """Mark a device as 'not a duplicate' so it stops appearing as a candidate."""
    store = _load_devices()
    e = store.get(device_id)
    if not e:
        return jsonify({'error': 'unknown device'}), 404
    e['notDuplicate'] = True
    _save_devices(store)
    return jsonify({'ok': True})


@app.route('/api/devices/merge_candidates')
def device_merge_candidates():
    """Suggest likely-duplicate pairs (rotated deviceIds for the same Echo).

    Heuristic: for each auto-named device that has a capability fingerprint,
    find the most-recently-active *renamed* device sharing the fingerprint
    that went silent at or before the auto-named one's firstSeen. Returns
    one candidate per auto-named device, ranked by confidence.
    """
    data = _load_devices()
    devices = []
    for did, e in data.items():
        if e.get('aliasOf'):
            continue
        devices.append({
            'id':          did,
            'name':        e.get('name') or '',
            'firstSeen':   e.get('firstSeen') or 0,
            'lastSeen':    e.get('lastSeen')  or 0,
            'fingerprint': e.get('fingerprint') or '',
        })

    # Stream activity per id so we don't propose noise (test/curl entries)
    counts = {}
    if os.path.exists(STREAM_HISTORY_PATH):
        try:
            with open(STREAM_HISTORY_PATH) as f:
                for line in f:
                    try: r = json.loads(line)
                    except: continue
                    did = r.get('deviceId')
                    if did:
                        counts[did] = counts.get(did, 0) + 1
        except Exception:
            pass

    def is_auto(d):
        n = (d['name'] or '').strip().lower()
        return (not n) or n == f"echo {d['id'][-6:]}".lower()

    dismissed = {did for did, e in data.items() if e.get('notDuplicate')}
    auto    = [d for d in devices if is_auto(d) and counts.get(d['id'], 0) >= 1 and d['id'] not in dismissed]
    renamed = [d for d in devices if not is_auto(d)]

    suggestions = []
    for a in auto:
        best = None
        for r in renamed:
            if r['id'] == a['id']:
                continue
            # gap < 0 means renamed went silent BEFORE auto-named first appeared (good)
            gap_h = (a['firstSeen'] - r['lastSeen']) / 3600.0
            if gap_h > 168 or gap_h < -1:  # > 1wk silence or overlap > 1h means likely different devices
                continue
            score = 0
            if a['fingerprint'] and a['fingerprint'] == r['fingerprint']:
                score += 100
            if 0 <= gap_h <= 24:
                score += 30
            elif gap_h <= 72:
                score += 10
            score -= int(abs(gap_h) / 24)  # closer in time wins
            if best is None or score > best['score']:
                best = {'target': r, 'score': score, 'gapHours': round(gap_h, 1)}
        if best and best['score'] > 0:
            suggestions.append({
                'sourceId':         a['id'],
                'sourceName':       a['name'] or a['id'][-6:],
                'sourceStreams':    counts.get(a['id'], 0),
                'sourceFingerprint': a['fingerprint'],
                'targetId':         best['target']['id'],
                'targetName':       best['target']['name'],
                'targetFingerprint': best['target']['fingerprint'],
                'gapHours':         best['gapHours'],
                'score':            best['score'],
                'fingerprintMatch': bool(a['fingerprint']) and a['fingerprint'] == best['target']['fingerprint'],
            })
    suggestions.sort(key=lambda s: s['score'], reverse=True)
    return jsonify({'candidates': suggestions})

@app.route('/api/devices/<device_id>', methods=['POST'])
def rename_device(device_id):
    data = request.get_json()
    new_name = (data or {}).get('name', '').strip()
    if not new_name:
        return jsonify({'error': 'Name required'}), 400
    try:
        store = _load_devices()
        primary = _resolve_device_id(device_id, store)
        entry = store.get(primary)
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
        # Also drop any aliases pointing here (orphaned)
        for did, e in list(store.items()):
            if e.get('aliasOf') == device_id:
                del store[did]
        _save_devices(store)
    return jsonify({'ok': True})

@app.route('/api/devices/<source_id>/merge', methods=['POST'])
def merge_device(source_id):
    """Merge `source_id` into `target` (from JSON body).

    Effects:
      - streaming_history.jsonl rows with deviceId==source are rewritten to
        target's id and name (analytics aggregate cleanly).
      - queues.json and nowplaying_state.json entries for source move to target
        (target wins if both exist).
      - devices.json: source becomes alias `aliasOf=target`. All future Alexa
        events from source automatically resolve to target via _resolve_device_id.
    """
    body = request.get_json(silent=True) or {}
    target_id = (body.get('target') or '').strip()
    if not target_id or target_id == source_id:
        return jsonify({'error': 'target required and must differ from source'}), 400
    store = _load_devices()
    if source_id not in store:
        return jsonify({'error': 'unknown source device'}), 404
    if target_id not in store:
        return jsonify({'error': 'unknown target device'}), 404
    # Reject cycles: target must not already alias to source
    if _resolve_device_id(target_id, store) == source_id:
        return jsonify({'error': 'target already aliased to source'}), 400

    src = store[source_id]
    tgt = store[target_id]
    target_name = tgt.get('name') or target_id[-6:]

    history_rewrites = _rewrite_history_device(source_id, target_id, target_name)
    _migrate_state_files(source_id, target_id)

    # firstSeen/lastSeen take the union
    fs = min(x for x in (src.get('firstSeen'), tgt.get('firstSeen')) if x) if (src.get('firstSeen') or tgt.get('firstSeen')) else None
    ls = max(x for x in (src.get('lastSeen'),  tgt.get('lastSeen'))  if x) if (src.get('lastSeen')  or tgt.get('lastSeen'))  else None
    if fs is not None: tgt['firstSeen'] = fs
    if ls is not None: tgt['lastSeen']  = ls

    store[source_id] = {
        'aliasOf':   target_id,
        'name':      target_name,
        'firstSeen': src.get('firstSeen'),
        'lastSeen':  src.get('lastSeen'),
        'mergedAt':  time.time(),
    }
    # Re-point any aliases that had source as their target to target
    for did, e in store.items():
        if did != source_id and e.get('aliasOf') == source_id:
            e['aliasOf'] = target_id
    _save_devices(store)
    _bust_analytics_cache()
    return jsonify({
        'ok': True,
        'historyRowsRewritten': history_rewrites,
        'targetName': target_name,
    })


def _rewrite_history_device(source_id, target_id, target_name):
    """Rewrite streaming_history.jsonl rows with deviceId==source_id to target."""
    if not os.path.exists(STREAM_HISTORY_PATH):
        return 0
    tmp = STREAM_HISTORY_PATH + '.tmp'
    rewritten = 0
    with open(STREAM_HISTORY_PATH) as fin, open(tmp, 'w') as fout:
        for line in fin:
            line = line.rstrip('\n')
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except Exception:
                fout.write(line + '\n')
                continue
            if row.get('deviceId') == source_id:
                row['deviceId'] = target_id
                row['device']   = target_name
                rewritten += 1
            fout.write(json.dumps(row) + '\n')
    os.replace(tmp, STREAM_HISTORY_PATH)
    return rewritten


def _migrate_state_files(source_id, target_id):
    # queues.json: move source's queue if target has none
    try:
        queues = _load_queues()
        if isinstance(queues, dict) and source_id in queues:
            if target_id not in queues:
                queues[target_id] = queues[source_id]
            queues.pop(source_id, None)
            _save_queues(queues)
    except Exception as e:
        print(f'merge: queues migrate failed: {e}', flush=True)
    # nowplaying_state.json
    try:
        payload = _read_all_np() or {'devices': {}}
        devs = payload.get('devices', {}) or {}
        if source_id in devs:
            if target_id not in devs:
                devs[target_id] = devs[source_id]
            devs.pop(source_id, None)
            payload['devices'] = devs
            _write_all_np(payload)
    except Exception as e:
        print(f'merge: nowplaying migrate failed: {e}', flush=True)

# ── API: Recent play requests ────────────────────────────────────────────────

CRITERIA_TYPES = ['Playlist', 'Song', 'Genre', 'Artist', 'Album']

@app.route('/api/recent')
def recent():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 10))
    try:
        tree = ET.parse(os.path.join(DATA_DIR, 'PlaylistHistory.xml'))
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

# ── API: Client analytics (Android / iOS) ────────────────────────────────────

@app.route('/api/clients/report', methods=['POST'])
def client_report():
    """Mobile clients report connect, play, and download events."""
    body = request.get_json(silent=True) or {}
    client_id = (body.get('clientId') or '').strip()
    event = (body.get('event') or '').strip().lower()
    platform = (body.get('platform') or '').strip().lower()
    device_name = (body.get('deviceName') or '').strip()

    if not client_id:
        return jsonify({'error': 'clientId required'}), 400
    if event not in ('connect', 'play', 'download', 'playback'):
        return jsonify({'error': 'event must be connect, play, download, or playback'}), 400

    did = register_client_device(client_id, platform=platform, device_name=device_name)
    if not did:
        return jsonify({'error': 'invalid clientId'}), 400

    now_iso = datetime.datetime.now().isoformat(timespec='seconds')
    label = _device_label(did)

    if event == 'connect':
        _record_client_connect(did)
        return jsonify({'ok': True, 'deviceId': did})

    if event == 'play':
        track = (body.get('track') or '').strip()
        artist = (body.get('artist') or '').strip() or None
        album = (body.get('album') or '').strip() or None
        filepath = (body.get('filepath') or body.get('path') or '').strip()
        if not track and not filepath:
            return jsonify({'error': 'track or filepath required for play'}), 400
        append_stream_history({
            'track': track or os.path.splitext(os.path.basename(filepath))[0],
            'artist': artist,
            'album': album,
            'filepath': filepath,
            'device': label,
            'deviceId': did,
            'platform': platform or None,
            'date': now_iso,
        })
        _write_client_np_state(did, {
            'track': track,
            'artist': artist,
            'album': album,
            'filepath': filepath,
            'playing': True,
            'paused': False,
            'offset_ms': 0,
            'duration_ms': int(body.get('duration_ms') or body.get('durationMs') or 0),
        }, platform=platform)
        return jsonify({'ok': True, 'deviceId': did})

    if event == 'playback':
        _write_client_np_state(did, body, platform=platform)
        return jsonify({'ok': True, 'deviceId': did})

    # download
    title = (body.get('collectionTitle') or body.get('title') or '').strip()
    kind = (body.get('collectionKind') or body.get('kind') or '').strip()
    track_count = int(body.get('trackCount') or body.get('tracks') or 0)
    append_download_history({
        'deviceId': did,
        'device': label,
        'platform': platform or None,
        'collectionTitle': title,
        'collectionKind': kind,
        'trackCount': track_count,
        'date': now_iso,
    })
    store = _load_devices()
    entry = store.get(did)
    if entry is not None:
        entry['downloadCount'] = int(entry.get('downloadCount') or 0) + 1
        entry['lastSeen'] = time.time()
        _save_devices(store)
    return jsonify({'ok': True, 'deviceId': did})


# ── API: Analytics ───────────────────────────────────────────────────────────

def _row_dt(row):
    """Best-effort timestamp parse from a stream-history row.

    Older rows used `timestamp`, newer ones use `date`. Both are ISO8601;
    we accept either so historical analytics keep working.
    """
    raw = (row.get('date') or row.get('timestamp') or '').replace('Z', '')
    if not raw:
        return None
    try:
        return datetime.datetime.fromisoformat(raw)
    except Exception:
        return None


@app.route('/api/analytics/export')
def analytics_export():
    """CSV download of streaming history for the optional date range."""
    import csv
    from io import StringIO
    from_str = request.args.get('from', '').strip()
    to_str   = request.args.get('to', '').strip()
    rows = _read_stream_history()
    from_dt = to_dt = None
    try:
        if from_str:
            from_dt = datetime.datetime.fromisoformat(from_str)
        if to_str:
            to_dt = datetime.datetime.fromisoformat(to_str).replace(hour=23, minute=59, second=59)
    except Exception:
        pass
    rows = _filter_history_rows(rows, from_dt, to_dt)
    rows = [r for r in rows
            if r.get('deviceId', '') not in ('DEVICE_ALPHA', 'DEVICE_BETA')
            and not r.get('test')]
    buf = StringIO()
    w = csv.writer(buf)
    w.writerow(['date', 'track', 'artist', 'album', 'device', 'platform', 'filepath'])
    for r in rows:
        w.writerow([
            r.get('date') or r.get('timestamp') or '',
            r.get('track') or '',
            r.get('artist') or '',
            r.get('album') or '',
            r.get('device') or '',
            r.get('platform') or '',
            r.get('filepath') or '',
        ])
    return Response(
        buf.getvalue(),
        mimetype='text/csv',
        headers={'Content-Disposition': 'attachment; filename=bock_media_streams.csv'},
    )


@app.route('/api/analytics')
def analytics():
    from collections import Counter, defaultdict
    from_str = request.args.get('from', '').strip()
    to_str   = request.args.get('to',   '').strip()
    cache_key = (from_str, to_str)
    cached = _analytics_cache.get(cache_key)
    if cached and (time.time() - cached['ts']) < _ANALYTICS_TTL:
        return jsonify(cached['data'])

    rows = _read_stream_history()

    from_dt = to_dt = None
    try:
        if from_str:
            from_dt = datetime.datetime.fromisoformat(from_str)
        if to_str:
            to_dt   = datetime.datetime.fromisoformat(to_str).replace(hour=23, minute=59, second=59)
    except Exception:
        pass

    rows = _filter_history_rows(rows, from_dt, to_dt)

    # Strip test/placeholder rows that were never real device plays
    rows = [r for r in rows
            if r.get('deviceId', '') not in ('DEVICE_ALPHA', 'DEVICE_BETA')
            and not r.get('test')]

    total = len(rows)
    download_rows = _filter_history_rows(_read_download_history(), from_dt, to_dt)
    EMPTY = {
        'totalPlays': 0, 'uniqueTracks': 0, 'uniqueArtists': 0, 'uniqueAlbums': 0,
        'topTracks': [], 'topArtists': [], 'topAlbums': [], 'topDevices': [],
        'topGenres': [], 'topDecades': [],
        'activity': {'day': [], 'week': [], 'month': [], 'year': []},
        'hourOfDay': [{'hour': h, 'count': 0} for h in range(24)],
        'dayOfWeek': [{'day': d, 'count': 0} for d in ['Mon','Tue','Wed','Thu','Fri','Sat','Sun']],
        'heatmap': [[0]*7 for _ in range(24)],
        'playsPerDay': {},
        'listeningStreak': {'current': 0, 'longest': 0},
        'currentStreak': 0, 'longestStreak': 0,
        'catalogCoverage': {'heard': 0, 'total': 0, 'pct': 0},
        'repeatRate':      {'repeated': 0, 'total': 0, 'pct': 0},
        'mostActiveDay':   None,
        'entity_activity': {},
        'deviceBreakdown': [],
        'dateRange': {'from': from_str, 'to': to_str},
    }
    device_store = _load_devices()
    device_breakdown = _build_device_breakdown(rows, download_rows, device_store)
    if total == 0 and not download_rows and not any(d.get('connects') for d in device_breakdown):
        EMPTY['deviceBreakdown'] = device_breakdown
        _analytics_cache[cache_key] = {'data': EMPTY, 'ts': time.time()}
        return jsonify(EMPTY)

    # Bulk-enrich genre/year from songs_cache (one query per 900-path chunk)
    paths = list({r['filepath'] for r in rows if r.get('filepath')})
    genre_map, year_map = {}, {}
    for i in range(0, len(paths), 900):
        chunk = paths[i:i + 900]
        ph = ','.join(['?'] * len(chunk))
        try:
            for dbr in db_query(f'SELECT path, genre, year FROM songs_cache WHERE path IN ({ph})', chunk):
                p = dbr.get('path')
                if p:
                    genre_map[p] = (dbr.get('genre') or '').strip()
                    year_map[p]  = dbr.get('year') or ''
        except Exception:
            pass

    artist_ctr  = Counter()
    album_ctr   = Counter()
    track_ctr   = Counter()
    genre_ctr   = Counter()
    decade_ctr  = Counter()
    device_ctr  = Counter()
    hour_ctr    = Counter()
    dow_ctr     = Counter()
    day_ctr     = Counter()
    week_ctr    = Counter()
    month_ctr   = Counter()
    year_ctr    = Counter()
    heatmap     = [[0] * 7 for _ in range(24)]

    artist_day_ctr = defaultdict(Counter)
    album_day_ctr  = defaultdict(Counter)
    track_day_ctr  = defaultdict(Counter)
    device_day_ctr = defaultdict(Counter)

    seen_tracks  = set()
    repeat_count = 0
    all_dates    = set()

    # Bucket devices by stable deviceId (alias-aware) so renames/merges fold
    # historical rows automatically — the label is always the *current* name.
    device_store = _load_devices()
    def _device_label(row):
        did = row.get('deviceId') or ''
        if did:
            primary = _resolve_device_id(did, device_store)
            entry = device_store.get(primary)
            if entry:
                return entry.get('name') or row.get('device') or primary[-6:]
        return row.get('device') or 'Unknown'

    for r in rows:
        artist   = (r.get('artist') or '').strip() or 'Unknown'
        album    = (r.get('album')  or '').strip() or 'Unknown'
        track    = (r.get('track')  or '').strip() or 'Unknown'
        filepath = r.get('filepath', '')
        device   = _device_label(r)

        artist_ctr[artist] += 1
        album_ctr[(album, artist)] += 1
        track_ctr[(track, artist)] += 1
        device_ctr[device] += 1

        g = genre_map.get(filepath, '')
        if g:
            genre_ctr[g] += 1

        yr_raw = year_map.get(filepath, '')
        if yr_raw:
            try:
                y = int(str(yr_raw)[:4])
                if 1900 <= y <= 2100:
                    decade_ctr[f'{(y // 10) * 10}s'] += 1
            except (ValueError, TypeError):
                pass

        dt = _row_dt(r)
        if dt:
            h, dow    = dt.hour, dt.weekday()
            day_key   = dt.strftime('%Y-%m-%d')
            week_key  = dt.strftime('%G-W%V')
            month_key = dt.strftime('%Y-%m')
            yr_key    = str(dt.year)

            hour_ctr[h]          += 1
            dow_ctr[dow]         += 1
            day_ctr[day_key]     += 1
            week_ctr[week_key]   += 1
            month_ctr[month_key] += 1
            year_ctr[yr_key]     += 1
            heatmap[h][dow]      += 1
            all_dates.add(day_key)

            artist_day_ctr[artist][day_key]    += 1
            album_day_ctr[(album, artist)][day_key] += 1
            track_day_ctr[(track, artist)][day_key] += 1
            device_day_ctr[device][day_key]    += 1

        tk = (track.lower(), artist.lower())
        if tk in seen_tracks:
            repeat_count += 1
        else:
            seen_tracks.add(tk)

    today = datetime.date.today()

    # Series: when a date filter is active use that range; otherwise use the
    # legacy rolling windows (last 30d / 26w / 24mo / all years).
    def _date_range(start, end):
        out, d = [], start
        while d <= end:
            out.append(d)
            d += datetime.timedelta(days=1)
        return out

    if from_dt or to_dt:
        rstart = from_dt.date() if from_dt else (today - datetime.timedelta(days=29))
        rend   = to_dt.date()   if to_dt   else today
        day_series = [
            {'label': d.strftime('%Y-%m-%d'), 'count': day_ctr.get(d.strftime('%Y-%m-%d'), 0)}
            for d in _date_range(rstart, rend)
        ]
    else:
        day_series = []
        for i in range(29, -1, -1):
            d = today - datetime.timedelta(days=i)
            k = d.strftime('%Y-%m-%d')
            day_series.append({'label': k, 'count': day_ctr.get(k, 0)})

    week_series = []
    for i in range(25, -1, -1):
        d = today - datetime.timedelta(weeks=i)
        k = d.strftime('%G-W%V')
        week_series.append({'label': k, 'count': week_ctr.get(k, 0)})

    month_series = []
    for i in range(23, -1, -1):
        m = today.month - i
        y = today.year
        while m <= 0:
            m += 12
            y -= 1
        k = f'{y}-{m:02d}'
        month_series.append({'label': k, 'count': month_ctr.get(k, 0)})

    year_series = [{'label': k, 'count': v} for k, v in sorted(year_ctr.items())]

    dow_names   = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    hour_of_day = [{'hour': h, 'count': hour_ctr.get(h, 0)} for h in range(24)]
    day_of_week = [{'day': dow_names[d], 'count': dow_ctr.get(d, 0)} for d in range(7)]

    top_artists = [{'name': k, 'count': v}                    for k, v in artist_ctr.most_common(10)]
    top_albums  = [{'name': k[0], 'artist': k[1], 'count': v} for k, v in album_ctr.most_common(10)]
    top_tracks  = [{'name': k[0], 'artist': k[1], 'count': v} for k, v in track_ctr.most_common(10)]
    top_genres  = [{'name': k, 'count': v}                    for k, v in genre_ctr.most_common(10)]
    top_decades = [{'decade': k, 'count': v}                  for k, v in sorted(decade_ctr.items())]
    top_devices = [{'name': k, 'count': v}                    for k, v in device_ctr.most_common(10)]

    # Listening streaks — day-based (more meaningful than hour-based)
    sorted_dates   = sorted(all_dates)
    longest_streak = current_streak = 0
    if sorted_dates:
        run = 1
        for i in range(1, len(sorted_dates)):
            d1 = datetime.date.fromisoformat(sorted_dates[i - 1])
            d2 = datetime.date.fromisoformat(sorted_dates[i])
            if (d2 - d1).days == 1:
                run += 1
            else:
                longest_streak = max(longest_streak, run)
                run = 1
        longest_streak = max(longest_streak, run)
        check = today
        while check.strftime('%Y-%m-%d') in all_dates:
            current_streak += 1
            check -= datetime.timedelta(days=1)
        if current_streak == 0:
            check = today - datetime.timedelta(days=1)
            while check.strftime('%Y-%m-%d') in all_dates:
                current_streak += 1
                check -= datetime.timedelta(days=1)

    try:
        total_in_db = (db_one('SELECT COUNT(*) as c FROM songs_cache WHERE path IS NOT NULL AND path != ""') or {}).get('c', 0)
    except Exception:
        total_in_db = 0
    heard_unique = len(seen_tracks)

    most_active = None
    if day_ctr:
        best = max(day_ctr.items(), key=lambda x: x[1])
        most_active = {'date': best[0], 'count': best[1]}

    def entity_series(day_ctr_map, top_keys, key_to_label=None):
        out = {}
        for k in top_keys:
            label = key_to_label(k) if key_to_label else k
            out[label] = dict(day_ctr_map.get(k, {}))
        return out

    top5_artists = [k for k, _ in artist_ctr.most_common(5)]
    top5_albums  = [k for k, _ in album_ctr.most_common(5)]
    top5_tracks  = [k for k, _ in track_ctr.most_common(5)]
    top5_devices = [k for k, _ in device_ctr.most_common(5)]

    result = {
        'totalPlays':      total,
        'uniqueTracks':    len(track_ctr),
        'uniqueArtists':   len(artist_ctr),
        'uniqueAlbums':    len(album_ctr),
        'activity':        {'day': day_series, 'week': week_series, 'month': month_series, 'year': year_series},
        'hourOfDay':       hour_of_day,
        'dayOfWeek':       day_of_week,
        'heatmap':         heatmap,
        'topArtists':      top_artists,
        'topAlbums':       top_albums,
        'topTracks':       top_tracks,
        'topGenres':       top_genres,
        'topDecades':      top_decades,
        'topDevices':      top_devices,
        'playsPerDay':     dict(day_ctr),
        'listeningStreak': {'current': current_streak, 'longest': longest_streak},
        'currentStreak':   current_streak,
        'longestStreak':   longest_streak,
        'catalogCoverage': {
            'heard': heard_unique, 'total': total_in_db,
            'pct': round(heard_unique / total_in_db * 100, 2) if total_in_db else 0,
        },
        'repeatRate': {
            'repeated': repeat_count, 'total': total,
            'pct': round(repeat_count / total * 100, 1) if total else 0,
        },
        'mostActiveDay': most_active,
        'entity_activity': {
            'artists': entity_series(artist_day_ctr, top5_artists),
            'albums':  entity_series(album_day_ctr,  top5_albums,  lambda k: k[0]),
            'tracks':  entity_series(track_day_ctr,  top5_tracks,  lambda k: k[0]),
            'devices': entity_series(device_day_ctr, top5_devices),
        },
        'deviceBreakdown': _build_device_breakdown(rows, download_rows, device_store),
        'dateRange': {'from': from_str, 'to': to_str},
    }
    _analytics_cache[cache_key] = {'data': result, 'ts': time.time()}
    return jsonify(result)

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
DOWNLOAD_HISTORY_PATH = os.path.join(HERE, 'download_history.jsonl')
_DOWNLOAD_HISTORY_MAX = 5000
_CLIENT_CONNECT_DEBOUNCE_SEC = 3600

_analytics_cache: dict = {}
_ANALYTICS_TTL = 60  # seconds

def _bust_analytics_cache():
    _analytics_cache.clear()

def _client_device_id(client_id):
    cid = (client_id or '').strip().lower()
    if not cid:
        return None
    return f'client-{cid}'

def _is_client_device(device_id):
    return bool(device_id) and str(device_id).startswith('client-')

_CLIENT_NP_HEARTBEAT_STALE_SECONDS = 45

def _write_client_np_state(device_id, body, platform=None):
    """Live now-playing row for a mobile client (web dashboard + household view)."""
    if body.get('stopped'):
        write_np_state_for_device(device_id, None)
        return
    track = (body.get('track') or '').strip()
    artist = (body.get('artist') or '').strip() or None
    album = (body.get('album') or '').strip() or None
    filepath = (body.get('filepath') or body.get('path') or '').strip() or None
    if not track and not filepath:
        write_np_state_for_device(device_id, None)
        return
    playing = body.get('playing')
    if playing is None:
        playing = not bool(body.get('paused'))
    paused = bool(body.get('paused')) and not bool(playing)
    write_np_state_for_device(device_id, {
        'track': track or (os.path.splitext(os.path.basename(filepath))[0] if filepath else ''),
        'artist': artist,
        'album': album,
        'filepath': filepath,
        'playing': bool(playing),
        'paused': paused,
        'offset_ms': int(body.get('offset_ms') or body.get('offsetMs') or 0),
        'duration_ms': int(body.get('duration_ms') or body.get('durationMs') or 0),
        'timestamp': time.time(),
        'platform': platform,
    })

def _expire_stale_client_playback(payload):
    """Drop mobile client rows when the app stops heartbeating."""
    now = time.time()
    devices = payload.get('devices', {}) or {}
    changed = False
    for did in list(devices.keys()):
        if not _is_client_device(did):
            continue
        st = devices.get(did) or {}
        ts = st.get('timestamp') or 0
        if not st.get('playing') and not st.get('paused'):
            devices.pop(did, None)
            changed = True
            continue
        if ts and now - ts > _CLIENT_NP_HEARTBEAT_STALE_SECONDS:
            devices.pop(did, None)
            changed = True
    return changed

def register_client_device(client_id, platform=None, device_name=None):
    """Register or refresh a mobile client install in devices.json."""
    did = _client_device_id(client_id)
    if not did:
        return None
    data = _load_devices()
    now = time.time()
    plat = (platform or '').strip().lower() or 'unknown'
    entry = data.get(did)
    if not entry:
        label = (device_name or '').strip() or f'{plat.title()} {client_id[:8]}'
        data[did] = {
            'name': label,
            'firstSeen': now,
            'lastSeen': now,
            'platform': plat,
            'connectCount': 0,
            'downloadCount': 0,
        }
    else:
        entry['lastSeen'] = now
        if plat and plat != 'unknown':
            entry['platform'] = plat
        if device_name:
            cur = (entry.get('name') or '').strip()
            auto = not cur or cur.lower() == f'{plat} {client_id[:8]}'.lower()
            if auto or cur.startswith('Android ') or cur.startswith('Ios '):
                entry['name'] = device_name
    _save_devices(data)
    return did

def _record_client_connect(device_id):
    """Count a client session connect, debounced to once per hour."""
    data = _load_devices()
    entry = data.get(device_id)
    if not entry:
        return
    now = time.time()
    last = entry.get('lastConnectAt') or 0
    if now - last >= _CLIENT_CONNECT_DEBOUNCE_SEC:
        entry['connectCount'] = int(entry.get('connectCount') or 0) + 1
        entry['lastConnectAt'] = now
    entry['lastSeen'] = now
    _save_devices(data)

def append_download_history(entry):
    try:
        with open(DOWNLOAD_HISTORY_PATH, 'a') as f:
            f.write(json.dumps(entry) + '\n')
        _bust_analytics_cache()
    except Exception as e:
        print(f'download history write error: {e}', flush=True)

def _read_download_history():
    rows = []
    try:
        with open(DOWNLOAD_HISTORY_PATH) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except Exception:
                    continue
    except FileNotFoundError:
        pass
    if len(rows) > _DOWNLOAD_HISTORY_MAX:
        rows = rows[-_DOWNLOAD_HISTORY_MAX:]
        try:
            with open(DOWNLOAD_HISTORY_PATH, 'w') as f:
                for r in rows:
                    f.write(json.dumps(r) + '\n')
        except Exception:
            pass
    return rows

def _filter_history_rows(rows, from_dt, to_dt):
    if not from_dt and not to_dt:
        return rows
    filtered = []
    for r in rows:
        ts = _row_dt(r)
        if not ts:
            continue
        if from_dt and ts < from_dt:
            continue
        if to_dt and ts > to_dt:
            continue
        filtered.append(r)
    return filtered

def _build_device_breakdown(play_rows, download_rows, device_store):
    """Per-device connects, plays, and downloads for the analytics dashboard."""
    stats = {}

    def _ensure(did):
        primary = _resolve_device_id(did, device_store) if did else ''
        if not primary:
            return None
        if primary not in stats:
            entry = device_store.get(primary) or {}
            if entry.get('aliasOf'):
                return None
            stats[primary] = {
                'deviceId': primary,
                'name': entry.get('name') or _device_label(primary),
                'platform': entry.get('platform') or ('alexa' if primary.startswith('amzn1.') else 'unknown'),
                'plays': 0,
                'downloads': 0,
                'connects': int(entry.get('connectCount') or 0),
                'lastSeen': entry.get('lastSeen'),
                'firstSeen': entry.get('firstSeen'),
            }
        return primary

    for r in play_rows:
        did = r.get('deviceId') or ''
        primary = _ensure(did)
        if primary:
            stats[primary]['plays'] += 1

    for r in download_rows:
        did = r.get('deviceId') or ''
        primary = _ensure(did)
        if primary:
            stats[primary]['downloads'] += 1

    for did, entry in device_store.items():
        if entry.get('aliasOf'):
            continue
        _ensure(did)

    breakdown = list(stats.values())
    breakdown.sort(key=lambda x: (x['plays'] + x['downloads'], x.get('lastSeen') or 0), reverse=True)
    return breakdown

def append_stream_history(entry):
    try:
        with open(STREAM_HISTORY_PATH, 'a') as f:
            f.write(json.dumps(entry) + '\n')
        _bust_analytics_cache()
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
        rows = [r for r in _read_stream_history() if not r.get('test')]
        rows.reverse()
        total = len(rows)
        start = (page - 1) * limit
        return jsonify({'items': rows[start:start + limit], 'total': total})
    except Exception as e:
        print(f'Now playing error: {e}')
        return jsonify({'items': [], 'total': 0})

# ── Config ───────────────────────────────────────────────────────────────────

CONFIG_PATH = os.path.join(DATA_DIR, 'config.json')

_config_cache: dict = {}
_config_mtime: float = 0.0

def load_config():
    global _config_cache, _config_mtime
    try:
        mtime = os.path.getmtime(CONFIG_PATH)
        if mtime != _config_mtime:
            with open(CONFIG_PATH) as f:
                _config_cache = json.load(f)
            _config_mtime = mtime
    except:
        pass
    return _config_cache


def cfg_bool(key, default=False):
    """Read boolean from config.json (handles true/false and legacy strings)."""
    v = load_config().get(key, default)
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        return v.strip().lower() in ('true', '1', 'yes', 'on')
    return default


_DEFAULT_ALEXA_PUBLIC_URL = 'https://alexa.morejava.bid'

def get_public_url():
    raw = (load_config().get('publicUrl') or '').strip().rstrip('/')
    if not raw:
        return _DEFAULT_ALEXA_PUBLIC_URL
    if raw.startswith('https://') or raw.startswith('http://'):
        return raw
    # Bare hostname (no scheme) — assume HTTPS tunnel.
    if not re.match(r'^\d+\.\d+\.\d+\.\d+(:\d+)?$', raw):
        return f'https://{raw}'
    # Bare IP/port-forward is not a valid Alexa stream base (Echo needs HTTPS tunnel).
    print(f'[WARN] publicUrl {raw!r} ignored for Alexa streams; using {_DEFAULT_ALEXA_PUBLIC_URL}', flush=True)
    return _DEFAULT_ALEXA_PUBLIC_URL

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
    if request.method == 'POST':
        if _mobile_api_only():
            return jsonify({'error': 'forbidden'}), 403
        data = request.get_json() or {}
        cfg = load_config()
        if isinstance(data.get('mobileApi'), dict):
            cfg.setdefault('mobileApi', {}).update(data.pop('mobileApi'))
        url = data.get('publicUrl')
        if url is not None:
            url = (url or '').strip().rstrip('/')
            if url and not url.startswith('https://'):
                return jsonify({'error': 'publicUrl must start with https:// (use your Cloudflare tunnel hostname, not a bare IP)'}), 400
            if url and re.match(r'^https://\d+\.\d+\.\d+\.\d+', url):
                return jsonify({'error': 'publicUrl must be a tunnel hostname (e.g. https://alexa.morejava.bid), not a raw IP'}), 400
        cfg.update(data)
        with open(CONFIG_PATH, 'w') as f:
            json.dump(cfg, f, indent=2)
        return jsonify({'ok': True})
    cfg = load_config()
    if _mobile_api_only():
        return jsonify(_redact_config(cfg))
    return jsonify(cfg)

# ── Audio Streaming ───────────────────────────────────────────────────────────

MUSIC_ROOT = os.environ.get('OURMEDIA_MUSIC_ROOT', '/mnt/bock/Music')
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

# ── Artwork serving: resolution memo + thumbnail cache (perf #1/#5) ───────────
_ART_RESOLVE_CACHE = {}          # track path -> resolved art file (or None)
_ART_RESOLVE_LOCK = threading.Lock()
_ART_MAX_AGE = 60 * 60 * 24 * 30  # 30 days — artwork for a given path is immutable
_THUMB_CACHE = os.path.join(ARTWORK_CACHE, 'thumbs')

def _resolve_art_cached(audio_path):
    """Memoize find_artwork() so repeat tile requests skip the sidecar/ID3/iTunes chain."""
    try:
        cached = _ART_RESOLVE_CACHE.get(audio_path)
    except TypeError:
        cached = None
    if cached is not None:
        if os.path.isfile(cached):
            return cached
        with _ART_RESOLVE_LOCK:
            _ART_RESOLVE_CACHE.pop(audio_path, None)
    resolved = find_artwork(audio_path)
    if resolved and os.path.isfile(resolved):
        with _ART_RESOLVE_LOCK:
            _ART_RESOLVE_CACHE[audio_path] = resolved
        return resolved
    return None

def _thumbnail_for(src_path, size):
    """Return a cached downscaled JPEG (longest edge == size) for src_path."""
    import hashlib
    try:
        st = os.stat(src_path)
    except OSError:
        return None
    key = hashlib.sha1(f'{src_path}:{int(st.st_mtime)}:{int(st.st_size)}:{size}'
                       .encode('utf-8')).hexdigest()
    out = os.path.join(_THUMB_CACHE, f'{key}.jpg')
    if os.path.isfile(out) and os.path.getsize(out) > 0:
        return out
    try:
        from PIL import Image
        os.makedirs(_THUMB_CACHE, exist_ok=True)
        with Image.open(src_path) as im:
            if im.mode not in ('RGB', 'L'):
                im = im.convert('RGB')
            im.thumbnail((size, size), Image.LANCZOS)
            tmp = out + '.tmp'
            im.save(tmp, 'JPEG', quality=82, optimize=True)
        os.replace(tmp, out)
        return out
    except Exception as e:
        print(f'thumb error {src_path} @{size}: {e}', flush=True)
        return None

# ── nginx sendfile offload (X-Accel-Redirect) ────────────────────────────────
# When fronted by the nginx site (which injects X-Sendfile-Proxy: nginx and maps
# the internal locations below), hand the heavy byte-pushing to nginx's sendfile/
# HTTP2 instead of copying the file through Python. Falls back to send_file when
# the header is absent (direct gunicorn / rollback), so this is inert off-proxy.
_XACCEL_ROOTS = None

def _xaccel_roots():
    global _XACCEL_ROOTS
    if _XACCEL_ROOTS is None:
        _XACCEL_ROOTS = (
            (os.path.abspath(MUSIC_ROOT).rstrip('/') + '/', '/internal-media/'),
            (os.path.abspath(ARTWORK_CACHE).rstrip('/') + '/', '/internal-art/'),
        )
    return _XACCEL_ROOTS

def _xaccel_send(abs_path, mime, max_age=None):
    """Return an X-Accel-Redirect response if behind nginx, else None (caller sends)."""
    if request.headers.get('X-Sendfile-Proxy') != 'nginx':
        return None
    ap = os.path.abspath(abs_path)
    internal = None
    for root, loc in _xaccel_roots():
        if ap.startswith(root):
            internal = loc + quote(ap[len(root):], safe='/')
            break
    if not internal:
        return None
    resp = Response(b'', mimetype=mime)
    resp.headers['X-Accel-Redirect'] = internal
    if max_age is not None:
        resp.headers['Cache-Control'] = f'public, max-age={max_age}'
    return resp

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
    accel = _xaccel_send(full_path, mime)
    if accel is not None:
        return accel
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
    # Mobile app / UI pass the track path — resolve to sidecar/embedded/cache art.
    if os.path.splitext(abs_path)[1].lower() not in _ART_MIME_BY_EXT:
        resolved = _resolve_art_cached(abs_path)
        if resolved and os.path.isfile(resolved):
            abs_path = os.path.abspath(resolved)
            if not any(abs_path.startswith(r) for r in allowed_roots):
                return 'Forbidden', 403
    if not os.path.isfile(abs_path):
        return 'Not found', 404
    # Optional downscaled thumbnail for grid/list tiles: /artwork/...?size=384
    size_arg = request.args.get('size')
    if size_arg:
        try:
            size_px = max(48, min(int(size_arg), 1024))
        except (TypeError, ValueError):
            size_px = None
        if size_px:
            thumb = _thumbnail_for(abs_path, size_px)
            if thumb:
                abs_path = thumb
    mime = _ART_MIME_BY_EXT.get(os.path.splitext(abs_path)[1].lower(), 'image/jpeg')
    # Artwork for a path is immutable → let the client cache hard and revalidate
    # cheaply (304) so repeat tile views cost zero bytes (perf #1).
    accel = _xaccel_send(abs_path, mime, max_age=_ART_MAX_AGE)
    if accel is not None:
        return accel
    return send_file(abs_path, mimetype=mime, conditional=True, max_age=_ART_MAX_AGE)

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

_FFMPEG_AVAILABLE = None

def _ffmpeg_available():
    global _FFMPEG_AVAILABLE
    if _FFMPEG_AVAILABLE is None:
        ffmpeg_bin = get_pref('FFmpegLocation', '').strip() or 'ffmpeg'
        _FFMPEG_AVAILABLE = shutil.which(ffmpeg_bin) is not None
    return _FFMPEG_AVAILABLE

def normalize_track_queue_fast(tracks):
    """Extension-only filter for Alexa hot path — avoids stat() on every path."""
    flac_ok = get_pref('FlacSupport', '').lower() == 'true'
    ffmpeg_ok = _ffmpeg_available() if flac_ok else False
    out = []
    for p in tracks:
        ext = os.path.splitext(p)[1].lower()
        if ext in NATIVE_EXTS:
            out.append(p)
        elif ext in TRANSCODE_EXTS and flac_ok and ffmpeg_ok:
            out.append(p)
    return out

# ── Token encode/decode ───────────────────────────────────────────────────────

QUEUES_PATH = os.path.join(HERE, 'queues.json')
_QUEUE_TTL_SECONDS = 24 * 3600
# Serialize read-modify-write of queues.json so concurrent group-fanout plays
# don't lose each other's queue entries (last-write-wins on the whole dict).
_QUEUES_LOCK = threading.RLock()

def _load_queues():
    try:
        with open(QUEUES_PATH) as f:
            return json.load(f)
    except:
        return {}

def _save_queues(queues):
    try:
        _atomic_json_write(QUEUES_PATH, queues)
    except Exception as e:
        print(f'Queue save error: {e}')

def _new_queue_id():
    return base64.urlsafe_b64encode(os.urandom(9)).decode().rstrip('=')

def _store_queue(tracks, shuffle=False, loop=False, playlist=None, playlist_id=None, context=None):
    with _QUEUES_LOCK:
        queues = _load_queues()
        now = time.time()
        queues = {k: v for k, v in queues.items() if now - v.get('ts', 0) < _QUEUE_TTL_SECONDS}
        qid = _new_queue_id()
        entry = {'tracks': list(tracks), 'shuffle': bool(shuffle),
                 'loop': bool(loop), 'ts': now}
        if playlist:
            entry['playlist'] = playlist
        if playlist_id:
            entry['playlist_id'] = playlist_id
        if context:
            entry['context'] = context
        queues[qid] = entry
        _save_queues(queues)
        return qid

def _store_queue_lazy(playlist_id, source, shuffle=False, shuffle_seed=None, loop=False,
                      playlist=None, context=None):
    """Store a playlist reference instead of copying thousands of track paths."""
    with _QUEUES_LOCK:
        queues = _load_queues()
        now = time.time()
        queues = {k: v for k, v in queues.items() if now - v.get('ts', 0) < _QUEUE_TTL_SECONDS}
        qid = _new_queue_id()
        entry = {
            'lazy': True,
            'playlist_id': playlist_id,
            'source': source,
            'shuffle': bool(shuffle),
            'loop': bool(loop),
            'ts': now,
        }
        if shuffle_seed is not None:
            entry['shuffle_seed'] = shuffle_seed
        if playlist:
            entry['playlist'] = playlist
        if context:
            entry['context'] = context
        queues[qid] = entry
        _save_queues(queues)
        return qid

_QUEUE_TRACK_LIMIT = 300

def _resolve_queue_tracks(entry):
    """Materialize track list for a queue entry (lazy playlist refs or inline tracks)."""
    if not entry.get('lazy'):
        return entry.get('tracks') or []
    pid = entry.get('playlist_id')
    source = entry.get('source')
    if not pid or not source:
        return []
    paths = _playlist_paths_cached(pid, source)
    queue = _filter_ignored_queue(normalize_track_queue_fast(paths))
    if entry.get('shuffle') and queue:
        seed = entry.get('shuffle_seed')
        if seed is not None:
            rng = random.Random(seed)
            rng.shuffle(queue)
        else:
            random.shuffle(queue)
    return queue[:_QUEUE_TRACK_LIMIT]

def _update_queue_flags(qid, **kwargs):
    """Update loop/shuffle/tracks on an existing queue entry."""
    if not qid:
        return
    with _QUEUES_LOCK:
        queues = _load_queues()
        if qid not in queues:
            return
        for k, v in kwargs.items():
            queues[qid][k] = v
        _save_queues(queues)

def _set_queue_stop(qid, minutes=None, songs=None, current_idx=0):
    """Arm (or clear) a sleep timer / stop-after-N on a queue.

    Both are enforced at the next track boundary (PlaybackNearlyFinished) so the
    current song always finishes cleanly — Alexa gives us no mid-track cutoff.
      * minutes -> stopAt epoch (time-based sleep timer)
      * songs   -> stopAfterIdx = current_idx + songs - 1 (the last idx allowed
                   to play; songs counts the currently-playing track, so
                   songs=1 means "stop after this song").
    Pass minutes=0/songs=0 (or None for both with clear semantics) to cancel.
    """
    if not qid:
        return None
    with _QUEUES_LOCK:
        queues = _load_queues()
        entry = queues.get(qid)
        if not entry:
            return None
        if minutes:
            entry['stopAt'] = time.time() + float(minutes) * 60.0
            entry.pop('stopAfterIdx', None)
        elif songs:
            entry['stopAfterIdx'] = int(current_idx) + int(songs) - 1
            entry.pop('stopAt', None)
        else:
            entry.pop('stopAt', None)
            entry.pop('stopAfterIdx', None)
        entry['ts'] = time.time()
        _save_queues(queues)
        return entry

def _touch_queue(qid):
    """Refresh a queue's TTL so a long-running / looping stream is never pruned
    out from under us — otherwise we lose the track mapping needed to show it in
    Now Playing once a newer play triggers the prune in _store_queue()."""
    if not qid:
        return
    with _QUEUES_LOCK:
        queues = _load_queues()
        entry = queues.get(qid)
        if not entry:
            return
        entry['ts'] = time.time()
        _save_queues(queues)

def encode_token(data):
    qid = data.get('qid')
    idx = int(data.get('idx', 0) or 0)
    if not qid:
        qid = _store_queue(
            data.get('tracks', []),
            data.get('shuffle', False),
            data.get('loop', False),
            playlist=data.get('playlist'),
            playlist_id=data.get('playlist_id'),
            context=data.get('context'),
        )
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
            tracks = _resolve_queue_tracks(entry) if entry.get('lazy') else entry.get('tracks', [])
            return {
                'qid': qid,
                'tracks': tracks,
                'idx': int(idx),
                'shuffle': entry.get('shuffle', False),
                'loop': entry.get('loop', False),
                'playlist': entry.get('playlist'),
                'playlist_id': entry.get('playlist_id'),
                'context': entry.get('context'),
                'stopAt': entry.get('stopAt'),
                'stopAfterIdx': entry.get('stopAfterIdx'),
                'lazy': entry.get('lazy', False),
                'source': entry.get('source'),
            }
        padding = 4 - len(token) % 4
        return json.loads(base64.urlsafe_b64decode(token + '=' * padding))
    except:
        return {}


def _upcoming_tracks_for_token(token, limit=5):
    """Next tracks in the active queue for Now Playing UI."""
    data = decode_token(token)
    tracks = data.get('tracks') or []
    if not tracks:
        return []
    try:
        idx = int(data.get('idx', 0))
    except (TypeError, ValueError):
        idx = 0
    out = []
    for path in tracks[idx + 1:idx + 1 + limit]:
        row = db_one('SELECT title, artist FROM songs_cache WHERE path = ?', [path]) or {}
        fname = os.path.splitext(os.path.basename(path))[0]
        out.append({
            'title': row.get('title') or fname,
            'artist': row.get('artist'),
            'path': path,
        })
    return out

# ── M3U Parser ───────────────────────────────────────────────────────────────

_M3U_PARSE_CACHE = {}
_M3U_CACHE_MAX = 96

def parse_m3u(filepath, verify_exists=True):
    try:
        mtime = os.path.getmtime(filepath)
    except OSError:
        return []
    cache_key = (filepath, mtime, verify_exists)
    cached = _M3U_PARSE_CACHE.get(cache_key)
    if cached is not None:
        return list(cached)
    tracks = []
    base_dir = os.path.dirname(filepath)
    try:
        with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                path = line if os.path.isabs(line) else os.path.normpath(os.path.join(base_dir, line))
                if os.path.splitext(path)[1].lower() not in SUPPORTED_EXTS:
                    continue
                if verify_exists and not os.path.isfile(path):
                    continue
                tracks.append(path)
    except Exception as e:
        print(f'M3U parse error {filepath}: {e}')
    if len(_M3U_PARSE_CACHE) >= _M3U_CACHE_MAX:
        _M3U_PARSE_CACHE.clear()
    _M3U_PARSE_CACHE[cache_key] = tracks
    return tracks

# ── Playlist library (create / merge / sort / AI) ────────────────────────────

PLAYLISTS_XML = os.path.join(DATA_DIR, 'ServerPlaylists.xml')
BOCK_PLAYLIST_DIR = os.path.join(
    os.environ.get('OURMEDIA_MUSIC_ROOT', '/mnt/bock/Music'),
    'exportedPlaylists', 'bockmedia',
)
BOCK_SOURCE_NAME = 'bockmedia'
_PLAYLIST_XML_LOCK = threading.Lock()
# Enriched track lists for large playlists (keyed by playlist id + m3u mtime).
_PLAYLIST_TRACKS_CACHE = {}
_XSI = 'http://www.w3.org/2001/XMLSchema-instance'
_XSD = 'http://www.w3.org/2001/XMLSchema'


def _backup_playlists_xml():
    if not os.path.isfile(PLAYLISTS_XML):
        return None
    bak = f'{PLAYLISTS_XML}.{datetime.datetime.now():%Y%m%d-%H%M%S}.bak'
    shutil.copy2(PLAYLISTS_XML, bak)
    return bak


def _load_playlists_tree():
    ET.register_namespace('xsd', _XSD)
    ET.register_namespace('xsi', _XSI)
    return ET.parse(PLAYLISTS_XML)


def _save_playlists_tree(tree):
    with _PLAYLIST_XML_LOCK:
        _backup_playlists_xml()
        tree.write(PLAYLISTS_XML, xml_declaration=True, encoding='utf-8')


def _find_playlist_key(root, pid):
    for entry in root.findall('Entry'):
        key = entry.find('Key')
        if key is not None and (key.findtext('ID') or '') == str(pid):
            return key, entry
    return None, None


def _playlist_meta_from_key(key):
    source = xml_text(key, 'SourceID')
    return {
        'id': xml_text(key, 'ID'),
        'name': xml_text(key, 'Name'),
        'trackCount': xml_int(key, 'TrackCount'),
        'shuffle': xml_text(key, 'Shuffle') == 'true',
        'loop': xml_text(key, 'Loop') == 'true',
        'createDate': xml_text(key, 'CreateDate'),
        'lastUsed': xml_text(key, 'LastUsed'),
        'source': source,
        'sourceName': xml_text(key, 'SourceName'),
        'isAudioBook': xml_text(key, 'IsAudioBook') == 'true',
        'editable': bool(source and os.path.isfile(source)),
    }


def _safe_playlist_filename(name):
    s = re.sub(r'[^\w\-. ]', '_', (name or '').strip()) or 'playlist'
    return s[:120]


def _write_m3u_file(path, track_paths):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('#EXTM3U\n')
        for p in track_paths:
            if p and os.path.isfile(p):
                f.write(p + '\n')


def _enrich_track_paths(paths):
    """Attach title/artist/album from songs_cache (batched IN queries, not one per row)."""
    if not paths:
        return []
    by_path = {}
    chunk_size = 400
    for i in range(0, len(paths), chunk_size):
        chunk = [p for p in paths[i:i + chunk_size] if p]
        if not chunk:
            continue
        ph = ','.join('?' * len(chunk))
        rows = db_query(
            f'SELECT path, title, artist, album, duration_seconds FROM songs_cache WHERE path IN ({ph})',
            chunk,
        ) or []
        for row in rows:
            by_path[row['path']] = row
    out = []
    for path in paths:
        if not path:
            continue
        row = by_path.get(path) or {}
        fname = os.path.splitext(os.path.basename(path))[0]
        out.append({
            'path': path,
            'title': row.get('title') or fname,
            'artist': row.get('artist') or '',
            'album': row.get('album') or '',
            'duration_seconds': row.get('duration_seconds') or 0,
        })
    return out


def _m3u_path_for_bock(pid, name):
    short = re.sub(r'[^a-zA-Z0-9]', '', pid)[:8] or 'pl'
    return os.path.join(BOCK_PLAYLIST_DIR, f'{_safe_playlist_filename(name)}.{short}.m3u')


def _append_bock_entry(root, pid, name, m3u_path, track_count):
    now = datetime.datetime.now().astimezone().isoformat()
    xml = (
        f'<Entry xmlns:xsi="{_XSI}">\n'
        f'    <Key xsi:type="Playlist">\n'
        f'      <ID>{pid}</ID>\n'
        f'      <MediaClientID>{uuid.uuid4()}</MediaClientID>\n'
        f'      <Name>{name}</Name>\n'
        f'      <Shuffle>false</Shuffle>\n'
        f'      <Loop>false</Loop>\n'
        f'      <Temporary>false</Temporary>\n'
        f'      <CreateDate>{now}</CreateDate>\n'
        f'      <Type>File</Type>\n'
        f'      <IsAudioBook>false</IsAudioBook>\n'
        f'      <TrackCount>{track_count}</TrackCount>\n'
        f'      <LastUsed>{now}</LastUsed>\n'
        f'      <DeviceID />\n'
        f'      <SearchHash />\n'
        f'      <SourceID>{m3u_path}</SourceID>\n'
        f'      <SourceName>{BOCK_SOURCE_NAME}</SourceName>\n'
        f'    </Key>\n'
        f'    <Value xsi:type="ArrayOfGuid" />\n'
        f'  </Entry>'
    )
    from xml.sax.saxutils import escape
    xml = xml.replace(f'<Name>{name}</Name>', f'<Name>{escape(name)}</Name>')
    xml = xml.replace(f'<SourceID>{m3u_path}</SourceID>', f'<SourceID>{escape(m3u_path)}</SourceID>')
    root.append(ET.fromstring(xml))


def _update_playlist_key(key, name, m3u_path, track_count):
    now = datetime.datetime.now().astimezone().isoformat()
    for tag, val in (
        ('Name', name),
        ('SourceID', m3u_path),
        ('TrackCount', str(track_count)),
        ('LastUsed', now),
    ):
        el = key.find(tag)
        if el is None:
            el = ET.SubElement(key, tag)
        el.text = val


def _persist_playlist(pid, name, track_paths, *, create=False):
    """Write .m3u + ServerPlaylists.xml entry for a Bock-managed playlist."""
    paths = [p for p in track_paths if p and os.path.isfile(p)]
    m3u_path = _m3u_path_for_bock(pid, name)
    _write_m3u_file(m3u_path, paths)
    tree = _load_playlists_tree()
    root = tree.getroot()
    key, entry = _find_playlist_key(root, pid)
    if key is None:
        if not create:
            return None
        _append_bock_entry(root, pid, name, m3u_path, len(paths))
    else:
        _update_playlist_key(key, name, m3u_path, len(paths))
    _save_playlists_tree(tree)
    return {'id': pid, 'name': name, 'source': m3u_path, 'trackCount': len(paths)}


def _sort_track_dicts(tracks, field, order):
    reverse = (order or 'asc').lower() == 'desc'
    key_map = {
        'title': lambda t: (t.get('title') or '').lower(),
        'artist': lambda t: (t.get('artist') or '').lower(),
        'album': lambda t: (t.get('album') or '').lower(),
        'path': lambda t: (t.get('path') or '').lower(),
    }
    key_fn = key_map.get((field or 'title').lower(), key_map['title'])
    return sorted(tracks, key=key_fn, reverse=reverse)


def _claude_config():
    cfg = load_config().get('claude') or {}
    return (cfg.get('apiKey') or os.environ.get('ANTHROPIC_API_KEY') or '').strip(), (
        cfg.get('model') or 'claude-sonnet-4-20250514'
    ).strip()


def _library_candidates_for_prompt(prompt, limit=250):
    """Narrow the catalog sent to Claude using simple keyword search."""
    words = [w for w in re.split(r'\W+', (prompt or '').lower()) if len(w) > 2][:8]
    if not words:
        rows = db_query(
            'SELECT path, title, artist, album, genre FROM songs_cache '
            'WHERE path IS NOT NULL AND title IS NOT NULL ORDER BY RANDOM() LIMIT ?',
            [limit],
        ) or []
        return rows
    clauses, params = [], []
    for w in words:
        like = f'%{w}%'
        clauses.append(
            '(LOWER(title) LIKE ? OR LOWER(artist) LIKE ? OR LOWER(album) LIKE ? OR LOWER(genre) LIKE ?)'
        )
        params.extend([like, like, like, like])
    sql = (
        'SELECT path, title, artist, album, genre FROM songs_cache WHERE path IS NOT NULL '
        f'AND ({" OR ".join(clauses)}) LIMIT ?'
    )
    params.append(limit)
    return db_query(sql, params) or []


def _call_claude_pick_tracks(prompt, candidates, max_tracks):
    api_key, model = _claude_config()
    if not api_key:
        raise ValueError('claude_api_key_not_configured')
    if not candidates:
        raise ValueError('no_library_matches')
    lines = []
    for i, r in enumerate(candidates):
        lines.append(
            f'{i}: {r.get("title") or "?"} | {r.get("artist") or ""} | {r.get("album") or ""} | {r.get("genre") or ""}'
        )
    catalog = '\n'.join(lines)
    user_msg = (
        f'Create a music playlist for this request: "{prompt}"\n\n'
        f'Pick up to {max_tracks} tracks ONLY from the numbered list below. '
        'Reply with JSON only: {"indices":[0,1,2], "name":"Short Playlist Title"}\n\n'
        f'{catalog}'
    )
    body = json.dumps({
        'model': model,
        'max_tokens': 1024,
        'messages': [{'role': 'user', 'content': user_msg}],
    }).encode('utf-8')
    req = __import__('urllib.request', fromlist=['Request']).Request(
        'https://api.anthropic.com/v1/messages',
        data=body,
        headers={
            'Content-Type': 'application/json',
            'x-api-key': api_key,
            'anthropic-version': '2023-06-01',
        },
        method='POST',
    )
    with urlopen(req, timeout=90) as resp:
        data = json.loads(resp.read().decode('utf-8', errors='replace'))
    text = ''
    for block in data.get('content') or []:
        if block.get('type') == 'text':
            text += block.get('text') or ''
    m = re.search(r'\{.*\}', text, re.DOTALL)
    if not m:
        raise ValueError('claude_invalid_response')
    parsed = json.loads(m.group(0))
    indices = parsed.get('indices') or []
    name = (parsed.get('name') or '').strip() or 'AI Playlist'
    paths = []
    for idx in indices:
        try:
            i = int(idx)
        except (TypeError, ValueError):
            continue
        if 0 <= i < len(candidates) and candidates[i].get('path'):
            p = candidates[i]['path']
            if p not in paths:
                paths.append(p)
        if len(paths) >= max_tracks:
            break
    return name, paths


def _playlist_paths_cached(playlist_id, source):
    """Parse .m3u once per file mtime."""
    try:
        mtime = os.path.getmtime(source) if source and os.path.isfile(source) else 0
    except OSError:
        mtime = 0
    ent = _PLAYLIST_TRACKS_CACHE.get(playlist_id)
    if ent and ent.get('mtime') == mtime and ent.get('paths') is not None:
        return ent['paths']
    paths = _tracks_from_source(source) if source else []
    prev = _PLAYLIST_TRACKS_CACHE.get(playlist_id) or {}
    _PLAYLIST_TRACKS_CACHE[playlist_id] = {
        'mtime': mtime, 'paths': paths, 'tracks': prev.get('tracks'),
    }
    return paths


def _playlist_all_tracks_enriched(playlist_id, source):
    """Full metadata for every path; cached after first build."""
    try:
        mtime = os.path.getmtime(source) if source and os.path.isfile(source) else 0
    except OSError:
        mtime = 0
    ent = _PLAYLIST_TRACKS_CACHE.get(playlist_id)
    if ent and ent.get('mtime') == mtime and ent.get('tracks') is not None:
        return ent['tracks']
    paths = _playlist_paths_cached(playlist_id, source)
    tracks = _enrich_track_paths(paths)
    _PLAYLIST_TRACKS_CACHE[playlist_id] = {'mtime': mtime, 'paths': paths, 'tracks': tracks}
    return tracks


def _sort_paths_by_field(paths, field, order):
    reverse = order == 'desc'
    if field == 'title':
        return sorted(paths, key=lambda p: os.path.basename(p).lower(), reverse=reverse)
    tracks = _sort_track_dicts(
        [{'path': p, 'title': os.path.splitext(os.path.basename(p))[0], 'artist': '', 'album': ''} for p in paths],
        field, order,
    )
    return [t['path'] for t in tracks]


def _m3u_first_paths(source, limit=12):
    """First N media paths from a playlist file — avoids parsing/sorting huge lists."""
    if not source or not os.path.isfile(source):
        return []
    out = []
    try:
        with open(source, encoding='utf-8', errors='replace') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                out.append(line)
                if len(out) >= limit:
                    break
    except OSError:
        pass
    return out


def _playlist_cover_path_from_tree(root, playlist_id):
    """Resolve first track path for playlist cover art (shared by single + batch endpoints)."""
    key, _entry = _find_playlist_key(root, playlist_id)
    if key is None:
        return None
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    paths = _m3u_first_paths(source, limit=12)
    if not paths:
        return None
    rows = db_query(
        f'SELECT path FROM songs_cache WHERE path IN ({",".join("?" * len(paths))}) '
        f'AND path IS NOT NULL AND path != "" LIMIT 1',
        paths,
    ) or []
    return (rows[0].get('path') if rows else None) or paths[0]


@app.route('/api/playlists/<playlist_id>/cover')
def playlist_cover(playlist_id):
    """Fast cover art path for a playlist — reads only the first few .m3u entries."""
    root = _load_playlists_tree().getroot()
    art_path = _playlist_cover_path_from_tree(root, playlist_id)
    if art_path is None and _find_playlist_key(root, playlist_id)[0] is None:
        return jsonify({'error': 'not_found'}), 404
    return jsonify({'playlistId': playlist_id, 'path': art_path})


@app.route('/api/playlists/covers', methods=['POST'])
def playlist_covers_batch():
    """Batch playlist cover paths for home grids — one playlist tree load for many ids."""
    body = request.get_json(silent=True) or {}
    raw_ids = body.get('ids') or []
    if not isinstance(raw_ids, list):
        return jsonify({'error': 'ids must be an array'}), 400
    ids = []
    seen = set()
    for raw in raw_ids:
        pid = str(raw or '').strip()
        if not pid or pid in seen:
            continue
        seen.add(pid)
        ids.append(pid)
        if len(ids) >= 200:
            break
    root = _load_playlists_tree().getroot()
    covers = {}
    for pid in ids:
        path = _playlist_cover_path_from_tree(root, pid)
        if path:
            covers[pid] = path
    return jsonify({'covers': covers})


@app.route('/api/playlists/<playlist_id>')
def playlist_detail(playlist_id):
    page = max(1, int(request.args.get('page', 1)))
    limit = min(max(int(request.args.get('limit', 100)), 1), 500)
    sort_by = (request.args.get('sortBy') or 'title').strip().lower()
    order = (request.args.get('order') or 'asc').strip().lower()
    if sort_by in ('track',):
        sort_by = 'title'
    if sort_by not in ('title', 'artist', 'album', 'path'):
        sort_by = 'title'
    if order not in ('asc', 'desc'):
        order = 'asc'

    key, _entry = _find_playlist_key(_load_playlists_tree().getroot(), playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    paths = _playlist_paths_cached(playlist_id, source)

    if sort_by == 'title':
        ordered_paths = _sort_paths_by_field(paths, 'title', order)
    else:
        ordered_paths = [t['path'] for t in _sort_track_dicts(
            _playlist_all_tracks_enriched(playlist_id, source), sort_by, order)]

    q = (request.args.get('q') or '').strip().lower()
    if q:
        if sort_by == 'title':
            ordered_paths = [
                p for p in ordered_paths
                if q in os.path.basename(p).lower()
                or q in os.path.splitext(os.path.basename(p))[0].lower()
            ]
        else:
            enriched = _playlist_all_tracks_enriched(playlist_id, source)
            ordered_paths = [
                t['path'] for t in enriched
                if q in (t.get('title') or '').lower()
                or q in (t.get('artist') or '').lower()
                or q in (t.get('album') or '').lower()
            ]
    total = len(ordered_paths)
    start = (page - 1) * limit
    page_paths = ordered_paths[start:start + limit]
    tracks = _enrich_track_paths(page_paths)

    return jsonify({
        **meta,
        'tracks': tracks,
        'total': total,
        'page': page,
        'limit': limit,
        'sortBy': sort_by,
        'order': order,
        'q': q or None,
    })


def _tracks_from_source(source):
    # Plex-exported .m3u paths are trusted; skip per-line stat() on network mounts.
    return parse_m3u(source, verify_exists=False) if source and os.path.isfile(source) else []


def _tracks_for_playlist(playlist_id, source):
    """Load playlist tracks using the enriched cache when an id is known."""
    if playlist_id and source:
        return _playlist_paths_cached(playlist_id, source)
    return _tracks_from_source(source)


@app.route('/api/playlists', methods=['POST'])
def create_playlist():
    body = request.get_json(silent=True) or {}
    name = (body.get('name') or '').strip()
    if not name:
        return jsonify({'error': 'name required'}), 400
    tracks = body.get('tracks') or []
    if not isinstance(tracks, list):
        return jsonify({'error': 'tracks must be a list'}), 400
    pid = str(uuid.uuid4())
    result = _persist_playlist(pid, name, tracks, create=True)
    if not result:
        return jsonify({'error': 'create_failed'}), 500
    return jsonify(result), 201


@app.route('/api/playlists/merge', methods=['POST'])
def merge_playlists():
    body = request.get_json(silent=True) or {}
    source_ids = body.get('sourceIds') or body.get('ids') or []
    if not isinstance(source_ids, list) or len(source_ids) < 2:
        return jsonify({'error': 'sourceIds must list at least 2 playlist ids'}), 400
    target_id = (body.get('targetId') or '').strip()
    new_name = (body.get('name') or '').strip()
    tree = _load_playlists_tree()
    root = tree.getroot()
    merged_paths, names = [], []
    for sid in source_ids:
        key, _ = _find_playlist_key(root, sid)
        if key is None:
            continue
        meta = _playlist_meta_from_key(key)
        names.append(meta.get('name') or sid)
        for p in _tracks_from_source(meta.get('source')):
            if p not in merged_paths:
                merged_paths.append(p)
    if not merged_paths:
        return jsonify({'error': 'no_tracks'}), 400
    if target_id:
        key, _ = _find_playlist_key(root, target_id)
        if key is None:
            return jsonify({'error': 'target_not_found'}), 404
        existing = _tracks_from_source(xml_text(key, 'SourceID'))
        for p in merged_paths:
            if p not in existing:
                existing.append(p)
        name = new_name or xml_text(key, 'Name')
        result = _persist_playlist(target_id, name, existing, create=False)
    else:
        name = new_name or f"{' + '.join(names[:2])}" + (' …' if len(names) > 2 else '')
        pid = str(uuid.uuid4())
        result = _persist_playlist(pid, name, merged_paths, create=True)
    return jsonify(result)


@app.route('/api/playlists/<playlist_id>', methods=['PUT'])
def update_playlist(playlist_id):
    body = request.get_json(silent=True) or {}
    tree = _load_playlists_tree()
    key, _ = _find_playlist_key(tree.getroot(), playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404
    meta = _playlist_meta_from_key(key)
    name = (body.get('name') or meta.get('name') or '').strip()
    if body.get('tracks') is not None:
        tracks = body.get('tracks') or []
        if not isinstance(tracks, list):
            return jsonify({'error': 'tracks must be a list'}), 400
        track_paths = tracks
    else:
        track_paths = _tracks_from_source(meta.get('source'))
    if meta.get('sourceName') != BOCK_SOURCE_NAME:
        # Allow reorder/sort on Plex playlists by rewriting local .m3u only.
        source = meta.get('source') or ''
        if not source:
            return jsonify({'error': 'not_editable'}), 403
        _write_m3u_file(source, track_paths)
        _update_playlist_key(key, name, source, len([p for p in track_paths if os.path.isfile(p)]))
        _save_playlists_tree(tree)
        _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
        return jsonify({'id': playlist_id, 'name': name, 'trackCount': len(track_paths)})
    result = _persist_playlist(playlist_id, name, track_paths, create=False)
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    return jsonify(result)


@app.route('/api/playlists/<playlist_id>/sort', methods=['POST'])
def sort_playlist(playlist_id):
    body = request.get_json(silent=True) or {}
    field = (body.get('by') or body.get('field') or 'title').strip().lower()
    order = (body.get('order') or 'asc').strip().lower()
    if order not in ('asc', 'desc'):
        return jsonify({'error': 'order must be asc or desc'}), 400
    tree = _load_playlists_tree()
    key, _ = _find_playlist_key(tree.getroot(), playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404
    meta = _playlist_meta_from_key(key)
    paths = _tracks_from_source(meta.get('source'))
    tracks = _sort_track_dicts(_enrich_track_paths(paths), field, order)
    sorted_paths = [t['path'] for t in tracks]
    source = meta.get('source') or ''
    if meta.get('sourceName') == BOCK_SOURCE_NAME:
        result = _persist_playlist(playlist_id, meta.get('name'), sorted_paths, create=False)
    else:
        _write_m3u_file(source, sorted_paths)
        _update_playlist_key(key, meta.get('name'), source, len(sorted_paths))
        _save_playlists_tree(tree)
        result = {'id': playlist_id, 'name': meta.get('name'), 'trackCount': len(sorted_paths)}
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    return jsonify({**result, 'sortedBy': field, 'order': order, 'ok': True})


@app.route('/api/playlists/<playlist_id>/tracks/remove', methods=['POST'])
def remove_playlist_track(playlist_id):
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    tree = _load_playlists_tree()
    key, _ = _find_playlist_key(tree.getroot(), playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    paths = [p for p in _playlist_paths_cached(playlist_id, source) if p != path]
    if meta.get('sourceName') == BOCK_SOURCE_NAME:
        _persist_playlist(playlist_id, meta.get('name'), paths, create=False)
    elif source:
        _write_m3u_file(source, paths)
        _update_playlist_key(key, meta.get('name'), source, len(paths))
        _save_playlists_tree(tree)
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    return jsonify({'ok': True, 'trackCount': len(paths)})


# ── Smart playlists (rule-based, auto-refresh) ───────────────────────────────

SMART_PLAYLISTS_PATH = os.path.join(HERE, 'smart_playlists.json')
_SMART_LOCK = threading.Lock()


def _load_smart_playlists():
    with _SMART_LOCK:
        if not os.path.isfile(SMART_PLAYLISTS_PATH):
            return []
        try:
            with open(SMART_PLAYLISTS_PATH) as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:
            return []


def _save_smart_playlists(items):
    with _SMART_LOCK:
        tmp = SMART_PLAYLISTS_PATH + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(items, f, indent=2)
        os.replace(tmp, SMART_PLAYLISTS_PATH)


def _paths_for_smart_rules(rules):
    """Build a track list from declarative rules against songs_cache."""
    rules = rules if isinstance(rules, list) else []
    clauses, params = ['path IS NOT NULL'], []
    order = 'RANDOM()'
    limit = 50
    for rule in rules:
        if not isinstance(rule, dict):
            continue
        rtype = (rule.get('type') or '').strip().lower()
        val = rule.get('value')
        if rtype == 'genre' and val:
            clauses.append('LOWER(COALESCE(genre,"")) LIKE ?')
            params.append(f'%{str(val).lower()}%')
        elif rtype == 'artist' and val:
            clauses.append('LOWER(COALESCE(artist,"")) LIKE ?')
            params.append(f'%{str(val).lower()}%')
        elif rtype == 'year_min' and val is not None:
            clauses.append('CAST(year AS INTEGER) >= ?')
            params.append(int(val))
        elif rtype == 'year_max' and val is not None:
            clauses.append('CAST(year AS INTEGER) <= ?')
            params.append(int(val))
        elif rtype == 'limit' and val:
            limit = min(max(int(val), 1), 500)
        elif rtype == 'order' and str(val).lower() == 'title':
            order = 'title COLLATE NOCASE'
    sql = (
        'SELECT path FROM songs_cache WHERE ' + ' AND '.join(clauses) +
        f' ORDER BY {order} LIMIT ?'
    )
    params.append(limit)
    rows = db_query(sql, params) or []
    seen, paths = set(), []
    for r in rows:
        p = r.get('path')
        if p and os.path.isfile(p) and p not in seen:
            seen.add(p)
            paths.append(p)
    return paths


def _refresh_smart_playlist(item):
    paths = _paths_for_smart_rules(item.get('rules'))
    pid = item.get('linkedPlaylistId')
    name = (item.get('name') or 'Smart playlist').strip()
    if pid:
        result = _persist_playlist(pid, name, paths, create=False)
    else:
        pid = str(uuid.uuid4())
        result = _persist_playlist(pid, name, paths, create=True)
        item['linkedPlaylistId'] = pid
    item['trackCount'] = len(paths)
    item['lastRefresh'] = datetime.datetime.now().isoformat(timespec='seconds')
    _PLAYLIST_TRACKS_CACHE.pop(pid, None)
    return item, result


@app.route('/api/smart_playlists')
def list_smart_playlists():
    return jsonify({'items': _load_smart_playlists()})


@app.route('/api/smart_playlists', methods=['POST'])
def create_smart_playlist():
    body = request.get_json() or {}
    name = (body.get('name') or '').strip()
    if not name:
        return jsonify({'error': 'name required'}), 400
    rules = body.get('rules') or []
    if not isinstance(rules, list) or not rules:
        return jsonify({'error': 'rules required'}), 400
    item = {
        'id': str(uuid.uuid4()),
        'name': name,
        'rules': rules,
        'enabled': bool(body.get('enabled', True)),
        'linkedPlaylistId': None,
        'trackCount': 0,
        'createdAt': time.time(),
    }
    if body.get('refresh'):
        item, _ = _refresh_smart_playlist(item)
    items = _load_smart_playlists()
    items.append(item)
    _save_smart_playlists(items)
    return jsonify(item), 201


@app.route('/api/smart_playlists/<smart_id>', methods=['PUT'])
def update_smart_playlist(smart_id):
    body = request.get_json() or {}
    items = _load_smart_playlists()
    for i, item in enumerate(items):
        if item.get('id') != smart_id:
            continue
        if body.get('name'):
            item['name'] = body['name'].strip()
        if 'rules' in body:
            item['rules'] = body['rules']
        if 'enabled' in body:
            item['enabled'] = bool(body['enabled'])
        if body.get('refresh'):
            item, _ = _refresh_smart_playlist(item)
        items[i] = item
        _save_smart_playlists(items)
        return jsonify(item)
    return jsonify({'error': 'not_found'}), 404


@app.route('/api/smart_playlists/<smart_id>', methods=['DELETE'])
def delete_smart_playlist(smart_id):
    items = [x for x in _load_smart_playlists() if x.get('id') != smart_id]
    _save_smart_playlists(items)
    return jsonify({'ok': True})


@app.route('/api/smart_playlists/<smart_id>/refresh', methods=['POST'])
def refresh_smart_playlist(smart_id):
    items = _load_smart_playlists()
    for i, item in enumerate(items):
        if item.get('id') != smart_id:
            continue
        item, result = _refresh_smart_playlist(item)
        items[i] = item
        _save_smart_playlists(items)
        return jsonify({**item, 'playlist': result})
    return jsonify({'error': 'not_found'}), 404


@app.route('/api/playlists/ai', methods=['POST'])
def ai_playlist():
    body = request.get_json(silent=True) or {}
    prompt = (body.get('prompt') or '').strip()
    if not prompt:
        return jsonify({'error': 'prompt required'}), 400
    max_tracks = min(max(int(body.get('maxTracks') or 25), 1), 80)
    save = bool(body.get('save'))
    try:
        candidates = _library_candidates_for_prompt(prompt, limit=400)
        ai_name, paths = _call_claude_pick_tracks(prompt, candidates, max_tracks)
    except ValueError as e:
        code = str(e)
        status = 503 if code == 'claude_api_key_not_configured' else 400
        return jsonify({'error': code}), status
    except Exception as e:
        print(f'AI playlist error: {e}', flush=True)
        return jsonify({'error': 'claude_request_failed', 'detail': str(e)}), 502
    name = (body.get('name') or '').strip() or ai_name
    tracks = _enrich_track_paths(paths)
    out = {'name': name, 'tracks': tracks, 'trackCount': len(tracks)}
    if save:
        pid = str(uuid.uuid4())
        saved = _persist_playlist(pid, name, paths, create=True)
        out.update(saved or {})
    return jsonify(out)


@app.route('/api/playlists/<playlist_id>', methods=['DELETE'])
def delete_playlist(playlist_id):
    tree = _load_playlists_tree()
    root = tree.getroot()
    key, entry = _find_playlist_key(root, playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404
    if xml_text(key, 'SourceName') != BOCK_SOURCE_NAME:
        return jsonify({'error': 'only_bockmedia_playlists_deletable'}), 403
    m3u = xml_text(key, 'SourceID')
    root.remove(entry)
    _save_playlists_tree(tree)
    if m3u and os.path.isfile(m3u):
        try:
            os.remove(m3u)
        except OSError:
            pass
    return jsonify({'ok': True})

# ── Fuzzy Search ─────────────────────────────────────────────────────────────

# Words that carry no discriminating signal in a spoken playlist request, so we
# ignore them when scoring (e.g. "the french bistro playlist" -> "french bistro").
_PL_FILLER = {'the', 'a', 'an', 'playlist', 'playlists', 'mix', 'station',
              'music', 'please', 'to', 'my', 'list'}

def _norm_pl(s):
    """Lowercase, expand &, drop punctuation, collapse whitespace."""
    s = (s or '').lower().replace('&', ' and ')
    s = re.sub(r"[^\w\s]", ' ', s)
    return re.sub(r'\s+', ' ', s).strip()

def _pl_tokens(s):
    return [t for t in _norm_pl(s).split() if t not in _PL_FILLER]

def _score_playlist(query, name):
    """Closeness of a spoken `query` to a playlist `name` in [0, 1]."""
    qn, nn = _norm_pl(query), _norm_pl(name)
    if not qn or not nn:
        return 0.0
    if qn == nn:
        return 1.0
    score = difflib.SequenceMatcher(None, qn, nn).ratio()
    qt, nt = set(_pl_tokens(query)), set(_pl_tokens(name))
    if qt and nt:
        inter = len(qt & nt)
        if inter:
            cover_q, cover_n = inter / len(qt), inter / len(nt)
            score = max(score, (cover_q + cover_n) / 2)
            if qt <= nt:  # every spoken word appears in the name
                score = max(score, 0.90 + 0.10 * cover_n)
    if nn.startswith(qn) or qn.startswith(nn):
        score = max(score, 0.90)
    elif qn in nn or nn in qn:
        score = max(score, 0.85)
    return score

def _load_playlist_entries():
    """[(id, name, source), …] from ServerPlaylists.xml."""
    entries = []
    try:
        tree = ET.parse(os.path.join(DATA_DIR, 'ServerPlaylists.xml'))
        for e in tree.getroot().findall('Entry'):
            key = e.find('Key')
            if key is None:
                continue
            name = xml_text(key, 'Name')
            if name:
                entries.append((key.findtext('ID') or '', name, xml_text(key, 'SourceID')))
    except Exception as e:
        print(f'Playlist load error: {e}')
    return entries

def best_playlist_entry(query, cutoff=0.5):
    """Best (id, name, source) match for a spoken query, or None below cutoff."""
    if not query:
        return None
    best, best_score = None, 0.0
    for pid, name, src in _load_playlist_entries():
        s = _score_playlist(query, name)
        if s > best_score:
            best, best_score = (pid, name, src), s
            if s >= 1.0:
                break
    return best if best and best_score >= cutoff else None

def fuzzy_find_playlist(query):
    entry = best_playlist_entry(query)
    return (entry[1], entry[2]) if entry else (None, None)

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
    q = (query or '').strip()
    if not q:
        return None
    ql = q.lower()
    rows = db_query(
        "SELECT DISTINCT album FROM songs_cache WHERE album IS NOT NULL AND album != '' LIMIT 10000"
    )
    names = [r['album'] for r in rows if r.get('album')]
    if not names:
        return None
    for name in names:
        if name.lower() == ql:
            return name
    contains = [n for n in names if ql in n.lower()]
    if contains:
        contains.sort(key=lambda n: (len(n), n.lower()))
        return contains[0]
    matches = difflib.get_close_matches(q, names, n=1, cutoff=0.5)
    return matches[0] if matches else None

def _resolve_song_tracks(title, artist=None):
    """Best-effort title/artist -> file paths (handles NLU-mangled titles)."""
    title = (title or '').strip()
    artist = (artist or '').strip() or None
    if not title:
        return []
    tracks = fuzzy_find_track(title, artist)
    if tracks:
        return tracks
    if artist:
        tracks = fuzzy_find_track(title)
        if tracks:
            return tracks
    words = _norm_pl(title).split()
    for n in range(min(len(words), 8), 0, -1):
        chunk = ' '.join(words[:n])
        tracks = fuzzy_find_track(chunk, artist) or fuzzy_find_track(chunk)
        if tracks:
            return tracks
    return []


def _try_play_misrouted_song(query):
    """Recover when NLU sends a song utterance to PlayPlaylistIntent."""
    if not query:
        return None
    token_entry = _play_file_token_from_query(query)
    if token_entry and os.path.isfile(token_entry['path']):
        label = token_entry['title']
        if token_entry.get('artist'):
            label = f"{label} by {token_entry['artist']}"
        return [token_entry['path']], label
    t = re.sub(r'^(?:the\s+)?(?:song|track)\s+', '', query.strip(), flags=re.I).strip()
    if not t:
        return None
    lower = t.lower()
    if ' by ' in lower:
        idx = lower.rfind(' by ')
        title, artist = t[:idx].strip(), t[idx + 4:].strip()
        tracks = _resolve_song_tracks(title, artist or None)
        label = f"{title} by {artist}" if artist else title
    else:
        tracks = _resolve_song_tracks(t)
        label = t
    if not tracks:
        return None
    return tracks, label


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
        'alexa ask bock media to ',
        'ask bock media to ',
        'bock media to ',
        'alexa ask bock media ',
        'ask bock media ',
        'bock media ',
        'alexa ask our media to ',
        'ask our media to ',
        'our media to ',
        'alexa ask our media ',
        'ask our media ',
        'our media ',
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
    """Try playlist → artist → album → track. Returns (tracks, speech, shuffle, meta)."""
    entry = best_playlist_entry(query)
    if entry and entry[2] and os.path.isfile(entry[2]):
        tracks = parse_m3u(entry[2])
        if tracks:
            return tracks, f"Playing playlist {entry[1]}.", False, {
                'playlist': entry[1], 'playlist_id': entry[0],
            }
    artist = fuzzy_find_artist(query)
    if artist:
        rows = db_query(
            "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
            "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY RANDOM() LIMIT ?",
            [artist, limit]
        )
        tracks = [r['path'] for r in rows]
        if tracks:
            return tracks, f"Playing music by {artist}.", True, {'context': f'Artist · {artist}'}
    album = fuzzy_find_album(query)
    if album:
        rows = db_query(
            "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
            "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY CAST(track_number AS INTEGER), title LIMIT 50",
            [album]
        )
        tracks = [r['path'] for r in rows]
        if tracks:
            return tracks, f"Playing the album {album}.", False, {'context': f'Album · {album}'}
    tracks = fuzzy_find_track(query)
    if tracks:
        return tracks, f"Playing {query}.", False, {'context': f'Song · {query}'}
    return [], None, False, {}

# ── Now Playing State ─────────────────────────────────────────────────────────

NP_STATE_PATH = os.path.join(HERE, 'nowplaying_state.json')
# Serialize the read-modify-write of nowplaying_state.json. Without this, two
# group members' PlaybackStarted events race: both read the same snapshot, each
# adds only its own device, and the last write drops the other device's row.
_NP_LOCK = threading.RLock()
_NP_DEVICE_TTL_SECONDS = 6 * 3600

# MSP (music-skill) playback exposes no device id, so all of it is attributed to
# this single pseudo-device for the Now Playing UI.
MSP_DEVICE_ID = 'msp-bock-media'
MSP_DEVICE_NAME = 'Bock Media (Alexa)'

# Amazon never tells a music provider which Echo is rendering — every MSP
# directive AND playback event arrives with no deviceId. We deliberately do NOT
# guess a specific Echo (a previous "adopt the most-recently-active speaker"
# heuristic confidently mislabeled cross-device routine playback, e.g. showing
# "Kitchen Show" for audio sent to "Office Show"). Instead each MSP stream is
# keyed by its QUEUE id under a pseudo-device that reads "Bock Media (Alexa)",
# so two simultaneous Bock Media streams (e.g. Kitchen + Office) show as two
# separate Now Playing rows instead of overwriting each other. Pseudo ids are
# NOT persisted in the device registry.
def _msp_pick_device_id(real_device_id, queue_id):
    """Use a real deviceId only if Amazon ever supplies one (it doesn't for MSP
    today); otherwise a per-queue pseudo id so concurrent streams stay distinct."""
    if real_device_id and real_device_id != MSP_DEVICE_ID:
        return real_device_id
    return f'{MSP_DEVICE_ID}:{queue_id}' if queue_id else MSP_DEVICE_ID

def _is_msp_pseudo(device_id):
    return bool(device_id) and (device_id == MSP_DEVICE_ID
                                or device_id.startswith(MSP_DEVICE_ID + ':'))

# Per-request device id (set by the Alexa handler).
from flask import g

def _current_device_id():
    return getattr(g, 'device_id', '') or 'default'

def _np_device_id():
    """Key now-playing by the merged primary device id (not a rotated alias)."""
    did = _current_device_id()
    if did and did != 'default':
        return did
    raw = getattr(g, 'raw_device_id', '') or ''
    if raw and raw != 'default':
        return _resolve_device_id(raw)
    return 'default'

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
        _atomic_json_write(NP_STATE_PATH, payload)
    except Exception as e:
        print(f'NP write error: {e}', flush=True)

def _clear_nowplaying_on_boot():
    """
    Clear active now-playing flags when the service process starts.
    After a reboot, Alexa devices do not replay PlaybackStopped events, so
    stale `playing: true` entries can stick around indefinitely.
    """
    payload = _read_all_np()
    devices = payload.get('devices', {}) if payload else {}
    if not devices:
        return
    changed = False
    for st in devices.values():
        if st.get('playing'):
            st['playing'] = False
            changed = True
    if changed:
        _write_all_np(payload)

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

def _canonicalize_np(payload):
    """Collapse manually-merged alias ids onto their primary for now-playing state.

    Auto-merged ids are kept separate — those merges are often wrong when
    several Echo Shows share a bare AudioPlayer fingerprint.
    """
    devices = payload.get('devices', {}) if payload else {}
    store = _load_devices()
    out = {}
    for did, st in devices.items():
        entry = store.get(did) or {}
        if entry.get('autoMerged'):
            out[did] = st
            continue
        primary = _resolve_device_id(did, store) if did and did != 'default' else did
        if not primary or primary == 'default':
            continue
        prev = out.get(primary)
        if not prev:
            out[primary] = st
            continue
        prev_ts = prev.get('timestamp', 0) or 0
        cur_ts = st.get('timestamp', 0) or 0
        # Keep the newest state for this physical device.
        if cur_ts >= prev_ts:
            out[primary] = st
    payload['devices'] = out
    return payload

def write_np_state(data):
    did = _np_device_id()
    if did == 'default':
        return
    with _NP_LOCK:
        payload = _canonicalize_np(_read_all_np() or {'devices': {}})
        devices = payload.setdefault('devices', {})
        if not data:
            devices.pop(did, None)
        else:
            devices[did] = data
        _canonicalize_np(_prune_np(payload))
        _write_all_np(payload)

def read_np_state():
    payload = _canonicalize_np(_read_all_np())
    devices = payload.get('devices', {}) if payload else {}
    return devices.get(_np_device_id())

def read_np_state_for_device(device_id):
    """Read now-playing for an explicit skill device id (primary/alias resolved)."""
    if not device_id or device_id == 'default':
        return None
    payload = _canonicalize_np(_read_all_np())
    devices = payload.get('devices', {}) if payload else {}
    did = _resolve_device_id(device_id)
    return devices.get(did)

def write_np_state_for_device(device_id, data):
    """Write now-playing for an explicit skill device id."""
    if not device_id or device_id == 'default':
        return
    did = _resolve_device_id(device_id)
    with _NP_LOCK:
        payload = _canonicalize_np(_read_all_np() or {'devices': {}})
        devices = payload.setdefault('devices', {})
        if not data:
            devices.pop(did, None)
        else:
            devices[did] = data
        _write_all_np(_canonicalize_np(_prune_np(payload)))

def remove_np_state():
    payload = _canonicalize_np(_read_all_np() or {'devices': {}})
    payload.get('devices', {}).pop(_np_device_id(), None)
    _write_all_np(payload)

_clear_nowplaying_on_boot()

@app.route('/api/currenttrack')
def current_track():
    raw = request.args.get('deviceId') or 'default'
    g.raw_device_id = raw
    g.device_id = _resolve_device_id(raw) if raw != 'default' else 'default'
    return jsonify(read_np_state() or {})

# Stop trusting `playing: true` when:
#   • we know the track's duration AND elapsed time exceeds duration + grace,
#     OR
#   • we don't know the duration AND no event has been seen for fallback secs.
# Echo devices fail to send PlaybackStopped/Finished after power loss, network
# drops, or skill rotation; without this guard the UI sticks on the last track.
_NP_DURATION_GRACE_SECONDS = 60        # tolerate ~1min of drift after track end
_NP_FALLBACK_STALE_SECONDS = 10 * 60   # used when duration is unknown
_NP_PAUSED_STALE_SECONDS = 30 * 60     # drop a paused row that's never resumed

def _expire_stale_playing(payload):
    now = time.time()
    devices = payload.get('devices', {}) or {}
    changed = False
    for st in devices.values():
        # A device left paused and never resumed shouldn't linger in the UI.
        if not st.get('playing') and st.get('paused'):
            ts = st.get('timestamp') or 0
            if ts and now - ts > _NP_PAUSED_STALE_SECONDS:
                st['paused'] = False
                changed = True
            continue
        if not st.get('playing'):
            continue
        ts = st.get('timestamp') or 0
        if not ts:
            continue
        elapsed = now - ts
        duration_ms = st.get('duration_ms') or 0
        offset_ms = st.get('offset_ms') or 0
        if duration_ms:
            remaining = (duration_ms - offset_ms) / 1000.0
            if elapsed > max(remaining, 0) + _NP_DURATION_GRACE_SECONDS:
                st['playing'] = False
                changed = True
        elif elapsed > _NP_FALLBACK_STALE_SECONDS:
            st['playing'] = False
            changed = True
    return changed

def _sleep_info_for_token(token):
    """Describe an armed sleep timer / stop-after-N for a now-playing token,
    or None. Used to badge the row in the web Now Playing UI."""
    if not token or ':' not in token:
        return None
    data = decode_token(token)
    stop_at = data.get('stopAt')
    stop_after_idx = data.get('stopAfterIdx')
    if stop_at:
        rem = max(0, int((float(stop_at) - time.time()) / 60.0 + 0.5))
        return {'type': 'time', 'remainingMin': rem}
    if stop_after_idx is not None:
        rem = max(0, int(stop_after_idx) - int(data.get('idx', 0)) + 1)
        return {'type': 'songs', 'remaining': rem}
    return None

@app.route('/api/nowplaying/sleep', methods=['POST'])
def nowplaying_sleep():
    """Arm/cancel a sleep timer or stop-after-N on a device's current queue.
    Body: {deviceId, minutes?, songs?}. Omit both (or 0) to cancel."""
    body = request.get_json(silent=True) or {}
    device_id = (body.get('deviceId') or '').strip()
    if not device_id:
        return jsonify({'error': 'deviceId required'}), 400
    st = read_np_state_for_device(device_id)
    token = (st or {}).get('token', '')
    if not token or ':' not in token:
        return jsonify({'error': 'nothing_playing', 'code': 'nothing_playing'}), 409
    qid, _, idx = token.partition(':')
    try:
        cur_idx = int(idx)
    except ValueError:
        cur_idx = 0
    minutes = body.get('minutes')
    songs = body.get('songs')
    entry = _set_queue_stop(qid, minutes=minutes, songs=songs, current_idx=cur_idx)
    if entry is None:
        return jsonify({'error': 'nothing_playing', 'code': 'nothing_playing'}), 409
    return jsonify({'ok': True, 'sleep': _sleep_info_for_token(token)})

@app.route('/api/nowplaying_devices')
def nowplaying_devices():
    payload = _canonicalize_np(_prune_np(_read_all_np() or {'devices': {}}))
    _expire_stale_playing(payload)
    if _expire_stale_client_playback(payload):
        pass
    _write_all_np(payload)
    devices = payload.get('devices', {})
    known = set(_load_devices().keys())
    device_store = _load_devices()
    # Mobile apps pass viewerClientId to hide other phones/tablets; web omits it.
    viewer_client_id = (request.args.get('viewerClientId') or '').strip()
    mobile_view = bool(viewer_client_id)
    items = []
    for did, st in devices.items():
        if not st.get('playing') and not st.get('paused'):
            continue
        if mobile_view and _is_client_device(did):
            continue
        if did == 'default' or (did not in known and not _is_msp_pseudo(did)):
            continue
        duration_ms = st.get('duration_ms') or 0
        if not duration_ms and st.get('filepath'):
            duration_ms = _duration_ms_for_path(st.get('filepath'))
        token = st.get('token') or ''
        src = _np_source_fields(token, did)
        if not src.get('sourceLabel'):
            src = {
                'playlist': st.get('playlist'),
                'playlistId': st.get('playlistId'),
                'context': st.get('context'),
                'sourceLabel': st.get('sourceLabel') or st.get('playlist') or st.get('context') or '',
            }
        entry = device_store.get(did) or {}
        platform = st.get('platform') or entry.get('platform')
        items.append({
            'deviceId':   did,
            'deviceName': _device_label(did) or did[-6:],
            'track':      st.get('track'),
            'artist':     st.get('artist'),
            'album':      st.get('album'),
            'filepath':   st.get('filepath'),
            'timestamp':  st.get('timestamp'),
            'duration_ms': duration_ms,
            'offset_ms':   st.get('offset_ms') or 0,
            'paused':     bool(st.get('paused')) and not st.get('playing'),
            'sleep':      _sleep_info_for_token(token),
            'upcoming':   _upcoming_tracks_for_token(token, limit=5),
            'playlist': src.get('playlist'),
            'playlistId': src.get('playlistId'),
            'context': src.get('context'),
            'sourceLabel': src.get('sourceLabel'),
            'platform': platform,
        })
    items.sort(key=lambda x: (x.get('paused'), -(x.get('timestamp') or 0)))
    controls = False
    try:
        import alexa_remote
        controls = alexa_remote.is_configured()
    except Exception:
        pass
    return jsonify({'items': items, 'controlsAvailable': controls})

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

def _save_ignored(ignored):
    _atomic_json_write(IGNORE_PATH, ignored)

def add_ignored(path):
    ignored = get_ignored()
    if path and path not in ignored:
        ignored.append(path)
        _save_ignored(ignored)

def remove_ignored(path):
    ignored = get_ignored()
    if path in ignored:
        ignored.remove(path)
        _save_ignored(ignored)
        return True
    return False

def ignored_with_metadata():
    """Ignored paths annotated with title/artist/album for the Analytics UI."""
    out = []
    for p in get_ignored():
        title, artist, album, _ = track_metadata(p)
        out.append({'path': p, 'title': title, 'artist': artist, 'album': album})
    return out

@app.route('/api/ignored', methods=['GET', 'POST', 'DELETE'])
def ignored_endpoint():
    if request.method == 'GET':
        return jsonify({'items': ignored_with_metadata()})
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    if request.method == 'POST':
        add_ignored(path)
        return jsonify({'ok': True})
    removed = remove_ignored(path)
    return jsonify({'ok': removed})

# ── Alexa Response Helpers ────────────────────────────────────────────────────

def alexa_speak(text, end_session=True, reprompt=None):
    resp = {
        'outputSpeech': {'type': 'PlainText', 'text': text},
        'shouldEndSession': end_session,
    }
    if reprompt and not end_session:
        resp['reprompt'] = {'outputSpeech': {'type': 'PlainText', 'text': reprompt}}
    return jsonify({'version': '1.0', 'response': resp})

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

def _np_play_path(path, token, *, offset_ms=0, previous_token=None,
                  play_behavior='REPLACE_ALL', speech=None, fast_metadata=False):
    """AudioPlayer.Play with title/artist/artwork for Echo Show / Spot display."""
    if fast_metadata:
        title, artist, album, artwork_url = track_metadata_fast(path)
    else:
        title, artist, album, artwork_url = track_metadata(path)
    if artist and album:
        subtitle = f'{artist} · {album}'
    else:
        subtitle = artist or album
    return alexa_play(file_to_stream_url(path), token,
                      offset_ms=offset_ms,
                      previous_token=previous_token,
                      play_behavior=play_behavior,
                      speech=speech,
                      title=title, subtitle=subtitle, artwork_url=artwork_url)

def _np_skip_next(playback_controller=False):
    """Advance to the next track in the active device queue."""
    state = read_np_state() or {}
    token = state.get('token', '')
    if not token:
        return alexa_empty() if playback_controller else alexa_speak("Nothing is playing.")
    data = decode_token(token)
    tracks = data.get('tracks', [])
    idx = data.get('idx', 0)
    if not tracks:
        return alexa_empty() if playback_controller else alexa_speak("There are no more tracks.")
    next_idx = (idx + 1) % len(tracks)
    next_path = tracks[next_idx]
    next_token = encode_token({**data, 'idx': next_idx})
    return _np_play_path(next_path, next_token)

def _np_skip_previous(playback_controller=False):
    """Go back to the previous track in the active device queue."""
    state = read_np_state() or {}
    token = state.get('token', '')
    if not token:
        return alexa_empty() if playback_controller else alexa_speak("Nothing is playing.")
    data = decode_token(token)
    tracks = data.get('tracks', [])
    if not tracks:
        return alexa_empty() if playback_controller else alexa_speak("Nothing to go back to.")
    idx = data.get('idx', 0)
    prev_idx = max(idx - 1, 0)
    prev_path = tracks[prev_idx]
    prev_token = encode_token({**data, 'idx': prev_idx})
    return _np_play_path(prev_path, prev_token)

def _np_arm_sleep(minutes=None, songs=None):
    """Arm a sleep timer / stop-after-N on the active device's current queue.
    Returns a spoken confirmation. minutes/songs falsy clears any timer."""
    state = read_np_state() or {}
    token = state.get('token', '')
    if not token or ':' not in token:
        return alexa_speak("Nothing is playing right now.")
    qid, _, idx = token.partition(':')
    try:
        cur_idx = int(idx)
    except ValueError:
        cur_idx = 0
    entry = _set_queue_stop(qid, minutes=minutes, songs=songs, current_idx=cur_idx)
    if entry is None:
        return alexa_speak("Nothing is playing right now.")
    if minutes:
        return alexa_speak(f"Okay, I'll stop playing in {int(minutes)} minutes.")
    if songs:
        n = int(songs)
        return alexa_speak(f"Okay, I'll stop after {n} more {'song' if n == 1 else 'songs'}.")
    return alexa_speak("Sleep timer cancelled.")

def _np_accumulate_offset(state):
    """Fold elapsed time since last timestamp into offset_ms (pause/stop)."""
    if state.get('playing') and state.get('timestamp'):
        elapsed_ms = int(max(0, time.time() - float(state['timestamp'])) * 1000)
        state['offset_ms'] = (state.get('offset_ms') or 0) + elapsed_ms
        state['timestamp'] = time.time()
    return state

def _np_pause_playback(playback_controller=False):
    state = read_np_state() or {}
    state = _np_accumulate_offset(state)
    state['playing'] = False
    state['paused'] = True
    write_np_state(state)
    return alexa_stop()

def _np_resume_playback(playback_controller=False):
    state = read_np_state() or {}
    token = state.get('token')
    path = state.get('filepath')
    if not token or not path or not os.path.isfile(path):
        return alexa_empty() if playback_controller else alexa_speak("Nothing to resume.")
    state['playing'] = True
    state['paused'] = False
    state['timestamp'] = time.time()
    write_np_state(state)
    title, artist, album, artwork_url = track_metadata(path)
    title = state.get('track') or title
    artist = state.get('artist') or artist
    album = state.get('album') or album
    if artist and album:
        subtitle = f'{artist} · {album}'
    else:
        subtitle = artist or album
    speech = None
    if not playback_controller:
        speech = f"Playing {title}"
        if artist:
            speech += f" by {artist}"
        speech += "."
    return alexa_play(file_to_stream_url(path), token,
                      offset_ms=state.get('offset_ms', 0),
                      speech=speech,
                      title=title, subtitle=subtitle, artwork_url=artwork_url)

def alexa_can_fulfill(slots=None, can_fulfill='MAYBE'):
    """Respond to Alexa's preflight capability check with a valid schema."""
    slot_map = {}
    for slot_name in (slots or {}).keys():
        slot_map[slot_name] = {
            'canUnderstand': can_fulfill,
            'canFulfill': can_fulfill,
        }
    return jsonify({
        'version': '1.0',
        'response': {
            'canFulfillIntent': {
                'canFulfill': can_fulfill,
                'slots': slot_map,
            }
        }
    })

# ── Play from track list ──────────────────────────────────────────────────────

_DURATION_MS_CACHE = {}  # path -> (mtime, duration_ms)

def _duration_ms_for_path(path):
    """Track length in ms: songs_cache first, else mutagen on the file."""
    if not path:
        return 0
    try:
        mtime = os.path.getmtime(path)
    except OSError:
        return 0
    cached = _DURATION_MS_CACHE.get(path)
    if cached and cached[0] == mtime:
        return cached[1]
    row = db_one('SELECT duration_seconds FROM songs_cache WHERE path = ?', [path]) or {}
    duration_s = row.get('duration_seconds') or 0
    if not duration_s and os.path.isfile(path):
        try:
            from mutagen import File as MutaFile
            mf = MutaFile(path)
            if mf and mf.info and mf.info.length:
                duration_s = float(mf.info.length)
        except Exception:
            duration_s = 0
    duration_ms = int(duration_s * 1000) if duration_s else 0
    _DURATION_MS_CACHE[path] = (mtime, duration_ms)
    return duration_ms

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

def track_metadata_fast(path):
    """DB-only metadata for Alexa responses — no artwork lookup (iTunes can exceed 8s)."""
    row = db_one('SELECT title, artist, album FROM songs_cache WHERE path = ?', [path]) or {}
    fname = os.path.splitext(os.path.basename(path))[0]
    title  = row.get('title', fname) or fname
    artist = row.get('artist') or None
    album  = row.get('album') or None
    return title, artist, album, None

def _filter_ignored_queue(queue):
    """Drop "never play again" tracks at queue-build time so they never start —
    but if the WHOLE queue is ignored (e.g. a one-song ignored selection), keep
    it rather than failing silently."""
    ignored = set(get_ignored())
    if not ignored:
        return queue
    filtered = [t for t in queue if t not in ignored]
    return filtered if filtered else queue

def start_playing(tracks, shuffle=False, speech=None, loop=False,
                  playlist=None, playlist_id=None, context=None, source=None):
    t0 = time.time()
    shuffle_seed = None
    if playlist_id and not source:
        _, source = _msp_playlist_by_id(playlist_id)
    use_lazy = bool(playlist_id and source)
    if use_lazy:
        paths = _playlist_paths_cached(playlist_id, source)
        queue = _filter_ignored_queue(normalize_track_queue_fast(paths))
        if shuffle and queue:
            shuffle_seed = random.randint(0, 2**31 - 1)
            rng = random.Random(shuffle_seed)
            rng.shuffle(queue)
        elif shuffle:
            random.shuffle(queue)
        queue = queue[:_QUEUE_TRACK_LIMIT]
        if not queue:
            return alexa_speak("Sorry, I couldn't find any tracks to play.")
        first = queue[0]
        qid = _store_queue_lazy(
            playlist_id, source, shuffle=shuffle, shuffle_seed=shuffle_seed,
            loop=loop, playlist=playlist, context=context,
        )
        token = f"{qid}:0"
    else:
        queue = _filter_ignored_queue(normalize_track_queue(tracks or []))
        if not queue:
            return alexa_speak("Sorry, I couldn't find any tracks to play.")
        if shuffle:
            random.shuffle(queue)
        first = queue[0]
        token_data = {
            'tracks': queue[:_QUEUE_TRACK_LIMIT], 'idx': 0, 'shuffle': shuffle, 'loop': loop,
            'playlist': playlist, 'playlist_id': playlist_id, 'context': context,
        }
        token = encode_token(token_data)
    title, artist, album, _ = track_metadata_fast(first)
    src = _np_source_fields(token)
    write_np_state({'track': None, 'artist': artist, 'album': album,
                    'filepath': first, 'token': token,
                    'playing': False, 'timestamp': time.time(),
                    **src})
    elapsed = time.time() - t0
    print(f'[ALEXA TIMING] start_playing lazy={use_lazy} tracks={len(queue)} '
          f'playlist={playlist!r} elapsed={elapsed:.2f}s', flush=True)
    return _np_play_path(first, token, speech=speech, fast_metadata=True)

# ── Music Skill (MSP) — scaffolding ───────────────────────────────────────────
# Account linking + directive handler stubs for migrating to Alexa's Music Skill API.
# Custom skill at /alexa stays live as fallback during migration.

import secrets as _secrets
from urllib.parse import urlencode as _urlencode

_MSP_AUTH_CODES = {}  # short-lived auth codes: code -> {client_id, redirect_uri, created}

def _msp_cfg():
    try:
        with open(os.path.join(HERE, 'config.json')) as f:
            return (json.load(f) or {}).get('mspOauth') or {}
    except Exception:
        return {}

@app.route('/oauth/authorize', methods=['GET', 'POST'])
def oauth_authorize():
    cfg = _msp_cfg()
    src = request.form if request.method == 'POST' else request.args
    client_id    = src.get('client_id', '')
    redirect_uri = src.get('redirect_uri', '')
    state        = src.get('state', '')
    response_type= src.get('response_type', 'code')

    if response_type != 'code':
        return Response(f'unsupported response_type: {response_type}', 400)
    if client_id != cfg.get('clientId'):
        return Response('invalid client_id', 400)
    allowed = cfg.get('redirectUriPrefixes', [])
    if not any(redirect_uri.startswith(p) for p in allowed):
        return Response(f'redirect_uri not on allowlist: {redirect_uri}', 400)

    if request.method == 'GET':
        # Minimal one-click approval page (single-user setup)
        html = (
            '<!doctype html><html><body style="font-family:sans-serif;max-width:480px;margin:60px auto">'
            '<h2>Link Bock Media to Alexa</h2>'
            '<p>Authorize Alexa to access your local music library?</p>'
            '<form method="POST" action="/oauth/authorize">'
            f'<input type="hidden" name="client_id" value="{client_id}">'
            f'<input type="hidden" name="redirect_uri" value="{redirect_uri}">'
            f'<input type="hidden" name="state" value="{state}">'
            f'<input type="hidden" name="response_type" value="{response_type}">'
            '<button type="submit" style="padding:12px 24px;font-size:16px">Authorize</button>'
            '</form></body></html>'
        )
        return Response(html, mimetype='text/html')

    # POST → mint a one-time code and redirect
    code = _secrets.token_urlsafe(32)
    _MSP_AUTH_CODES[code] = {
        'client_id': client_id,
        'redirect_uri': redirect_uri,
        'created': time.time(),
    }
    sep = '&' if '?' in redirect_uri else '?'
    location = redirect_uri + sep + _urlencode({'code': code, 'state': state})
    print(f"[MSP OAUTH] authorize granted code={code[:8]}... redirect_uri={redirect_uri}", flush=True)
    return Response('', 302, {'Location': location})

@app.route('/oauth/token', methods=['POST'])
def oauth_token():
    cfg = _msp_cfg()
    grant = request.form.get('grant_type', '')

    # Client auth: either Basic header or form-encoded
    auth = request.authorization
    client_id     = (auth.username if auth else '') or request.form.get('client_id', '')
    client_secret = (auth.password if auth else '') or request.form.get('client_secret', '')
    if client_id != cfg.get('clientId') or client_secret != cfg.get('clientSecret'):
        print(f"[MSP OAUTH] token rejected: bad client creds", flush=True)
        return jsonify({'error': 'invalid_client'}), 401

    if grant == 'authorization_code':
        code = request.form.get('code', '')
        info = _MSP_AUTH_CODES.pop(code, None)
        if info is None:
            return jsonify({'error': 'invalid_grant'}), 400
        if time.time() - info['created'] > 600:
            return jsonify({'error': 'invalid_grant', 'error_description': 'expired'}), 400
    elif grant == 'refresh_token':
        if request.form.get('refresh_token', '') != cfg.get('refreshToken'):
            return jsonify({'error': 'invalid_grant'}), 400
    else:
        return jsonify({'error': 'unsupported_grant_type'}), 400

    print(f"[MSP OAUTH] issued token (grant={grant})", flush=True)
    return jsonify({
        'access_token': cfg.get('accessToken'),
        'refresh_token': cfg.get('refreshToken'),
        'token_type': 'bearer',
        'expires_in': 3600 * 24 * 365,
    })

# ── MSP directive helpers (Music Skill API) ──────────────────────────────────
_MSP_STREAM_VALID_UNTIL = '2099-01-01T00:00:00Z'  # our stream URLs never expire

def _msp_event(namespace, name, header, payload):
    return jsonify({
        'header': {
            'namespace': namespace,
            'name': name,
            'messageId': _secrets.token_hex(12),
            'payloadVersion': header.get('payloadVersion', '1.0'),
        },
        'payload': payload,
    })

def _msp_error(header, etype, msg):
    return _msp_event('Alexa.Media', 'ErrorResponse', header, {'type': etype, 'message': msg})

def _msp_ok(header):
    return _msp_event('Alexa', 'Response', header, {})

def _msp_art_sources(url):
    if not url:
        return None
    sizes = [('X_SMALL', 48), ('SMALL', 60), ('MEDIUM', 110), ('LARGE', 256), ('X_LARGE', 600)]
    return {'sources': [
        {'url': url, 'size': s, 'widthPixels': px, 'heightPixels': px} for s, px in sizes
    ]}

def _msp_name(text):
    text = text or ''
    return {'speech': {'type': 'PLAIN_TEXT', 'text': text.lower()}, 'display': text}

def _msp_playlist_by_id(pid):
    """Return (name, source_path) for a playlist by its stable ID, or (None, None)."""
    if not pid:
        return None, None
    try:
        tree = ET.parse(os.path.join(DATA_DIR, 'ServerPlaylists.xml'))
        for e in tree.getroot().findall('Entry'):
            key = e.find('Key')
            if key is not None and (key.findtext('ID') or '') == str(pid):
                return xml_text(key, 'Name'), xml_text(key, 'SourceID')
    except Exception as ex:
        print(f'[MSP] playlist_by_id error: {ex}', flush=True)
    return None, None

def _msp_item_ref(ref):
    """Extract (item_id, queue_id, content_id) from a *ItemReference object,
    tolerating both the wrapped ({value:{…}}) and flat shapes."""
    if not ref:
        return None, None, None
    val = ref.get('value') if isinstance(ref.get('value'), dict) else ref
    content_id = val.get('contentId') or (val.get('content') or {}).get('id')
    return val.get('id'), val.get('queueId'), content_id

def _msp_parse_idx(item_id):
    try:
        return int(str(item_id).split('#', 1)[1])
    except Exception:
        return None

def _msp_build_item(queue_id, idx, tracks, content_id):
    """Build an Alexa.Audio.PlayQueue Item for tracks[idx]."""
    path = tracks[idx]
    title, artist, album, art = track_metadata(path)
    item = {
        'id': f'{queue_id}#{idx}',
        'queueId': queue_id,
        'contentId': content_id,
        'playbackInfo': {'type': 'DEFAULT'},
        'metadata': {'type': 'TRACK', 'name': _msp_name(title)},
        'controls': [
            {'type': 'COMMAND', 'name': 'NEXT', 'enabled': idx < len(tracks) - 1},
            {'type': 'COMMAND', 'name': 'PREVIOUS', 'enabled': idx > 0},
        ],
        'rules': {'feedbackEnabled': False},
        'stream': {
            'id': f'{queue_id}#{idx}',
            'uri': file_to_stream_url(path),
            'offsetInMilliseconds': 0,
            'validUntil': _MSP_STREAM_VALID_UNTIL,
        },
    }
    if artist:
        item['metadata']['authors'] = [{'name': _msp_name(artist)}]
    if album:
        item['metadata']['album'] = {'name': _msp_name(album)}
    art_obj = _msp_art_sources(art)
    if art_obj:
        item['metadata']['art'] = art_obj
    return item

def _msp_handle(namespace, name, payload, header):
    # ── Search: resolve a spoken query (already entity-resolved by Alexa) ──
    if namespace == 'Alexa.Media.Search' and name == 'GetPlayableContent':
        attrs = (payload.get('selectionCriteria') or {}).get('attributes') or []
        playlist_id = next((a.get('entityId') for a in attrs
                            if a.get('type') == 'PLAYLIST' and a.get('entityId')), None)
        pname = None
        if playlist_id:
            pname, _ = _msp_playlist_by_id(playlist_id)
        # Fallback: Alexa couldn't entity-resolve the catalog id (stale catalog,
        # SLU lag) — fuzzy-match the spoken text against our live playlist names.
        if not pname:
            spoken = next((a.get('value') for a in attrs
                           if a.get('type') in ('PLAYLIST', 'MEDIA_SEARCH_KEY', 'NAME')
                           and a.get('value')), None) \
                     or (payload.get('selectionCriteria') or {}).get('searchText')
            entry = best_playlist_entry(spoken) if spoken else None
            if entry:
                playlist_id, pname = entry[0], entry[1]
                print(f"[MSP] fuzzy playlist match {spoken!r} -> {pname!r}", flush=True)
        if not pname or not playlist_id:
            return _msp_error(header, 'CONTENT_NOT_FOUND', 'No matching playlist')
        return _msp_event('Alexa.Media.Search', 'GetPlayableContent.Response', header, {
            'content': {'id': playlist_id,
                        'metadata': {'type': 'PLAYLIST', 'name': _msp_name(pname)}},
        })

    # ── Playback: build a queue from the resolved contentId (= playlist id) ──
    if namespace == 'Alexa.Media.Playback' and name == 'Initiate':
        content_id = payload.get('contentId')
        modes = payload.get('playbackModes') or {}
        shuffle, loop = bool(modes.get('shuffle')), bool(modes.get('loop'))
        pname, source = _msp_playlist_by_id(content_id)
        tracks = parse_m3u(source) if source and os.path.isfile(source) else []
        tracks = normalize_track_queue(tracks)
        if not tracks:
            return _msp_error(header, 'CONTENT_NOT_FOUND', 'Playlist has no playable tracks')
        if shuffle:
            random.shuffle(tracks)
        qid = _store_queue(tracks, shuffle, loop, playlist=pname, playlist_id=content_id)
        return _msp_event('Alexa.Media.Playback', 'Initiate.Response', header, {
            'playbackMethod': {
                'type': 'ALEXA_AUDIO_PLAYER_QUEUE',
                'id': qid,
                'controls': [
                    {'type': 'TOGGLE', 'name': 'SHUFFLE', 'enabled': True, 'selected': shuffle},
                    {'type': 'TOGGLE', 'name': 'LOOP', 'enabled': True, 'selected': loop},
                ],
                'rules': {'feedback': {'type': 'PREFERENCE', 'enabled': False}},
                'firstItem': _msp_build_item(qid, 0, tracks, content_id),
            },
        })

    # ── Queue navigation ──
    if namespace == 'Alexa.Audio.PlayQueue' and name in ('GetNextItem', 'GetPreviousItem'):
        item_id, queue_id, content_id = _msp_item_ref(payload.get('currentItemReference'))
        q = _load_queues().get(queue_id)
        if not q:
            return _msp_error(header, 'ITEM_NOT_FOUND', 'Queue not found')
        _touch_queue(queue_id)
        tracks, loop = q.get('tracks', []), q.get('loop', False)
        idx = _msp_parse_idx(item_id)
        if idx is None:
            return _msp_error(header, 'ITEM_NOT_FOUND', 'Bad item reference')
        nxt = idx + 1 if name == 'GetNextItem' else idx - 1
        if nxt >= len(tracks):
            nxt = 0 if (loop and tracks) else None
        elif nxt < 0:
            nxt = len(tracks) - 1 if (loop and tracks) else None
        if nxt is None:
            return _msp_event('Alexa.Audio.PlayQueue', f'{name}.Response', header,
                              {'isQueueFinished': True, 'item': None})
        return _msp_event('Alexa.Audio.PlayQueue', f'{name}.Response', header,
                          {'isQueueFinished': False,
                           'item': _msp_build_item(queue_id, nxt, tracks, content_id)})

    # ── Refresh an expired stream URI ──
    if namespace == 'Alexa.Media.PlayQueue' and name == 'GetItem':
        item_id, queue_id, content_id = _msp_item_ref(payload.get('targetItemReference'))
        q = _load_queues().get(queue_id)
        idx = _msp_parse_idx(item_id)
        if not q or idx is None or idx >= len(q.get('tracks', [])):
            return _msp_error(header, 'ITEM_NOT_FOUND', 'Item not found')
        return _msp_event('Alexa.Audio.PlayQueue', 'GetItem.Response', header,
                          {'item': _msp_build_item(queue_id, idx, q['tracks'], content_id)})

    # ── Mode toggles ──
    if namespace == 'Alexa.Media.PlayQueue' and name in ('SetShuffle', 'SetLoop'):
        _, queue_id, _ = _msp_item_ref(payload.get('currentItemReference'))
        enable = bool(payload.get('enable'))
        if queue_id:
            if name == 'SetShuffle':
                q = _load_queues().get(queue_id)
                if q and enable:
                    tracks = list(q.get('tracks', []))
                    random.shuffle(tracks)
                    _update_queue_flags(queue_id, tracks=tracks, shuffle=True)
                else:
                    _update_queue_flags(queue_id, shuffle=enable)
            else:
                _update_queue_flags(queue_id, loop=enable)
        return _msp_ok(header)

    return _msp_error(header, 'INTERNAL_ERROR', f'Unsupported directive {namespace}.{name}')

def _msp_handle_event(req, ctx):
    """Handle music-skill playback lifecycle events
    (AlexaAudioPlayQueueEvent.ItemPlaybackStarted/Stopped/Finished/Failed),
    keeping the web UI's Now Playing in sync. These arrive shaped like a custom
    skill event (top-level request/context), not as a directive."""
    etype = req.get('type', '')
    item = (req.get('body') or {}).get('item') or {}
    queue_id = item.get('queueId') or ''
    item_id = item.get('id') or ''

    # Music-skill (MSP) requests carry NO device id anywhere — neither directives
    # (requestContext has only user/location) nor playback events. Amazon does not
    # tell a music provider which Echo is rendering. So we attribute all MSP
    # playback to one stable pseudo-device that the web UI can display.
    device = ((ctx.get('System') or {}).get('device') or {})
    raw_device_id = _msp_pick_device_id(device.get('deviceId'), queue_id)
    # Only persist REAL Echo ids; per-queue pseudo ids are ephemeral and must
    # not pile up in the device registry / /devices page.
    if not _is_msp_pseudo(raw_device_id):
        register_device(raw_device_id,
                        supported_interfaces=device.get('supportedInterfaces') or {})
    g.raw_device_id = raw_device_id
    g.device_id = _resolve_device_id(raw_device_id)

    print(f"[MSP EVENT] {etype} device={raw_device_id[-12:] if raw_device_id!='default' else 'default'} "
          f"queue={queue_id} item={item_id}", flush=True)

    # Keep the queue alive for the duration of playback.
    _touch_queue(queue_id)

    if etype == 'AlexaAudioPlayQueueEvent.ItemPlaybackStarted':
        q = _load_queues().get(queue_id) or {}
        tracks = q.get('tracks', [])
        idx = _msp_parse_idx(item_id)
        if idx is not None and 0 <= idx < len(tracks):
            path = tracks[idx]
            row = db_one('SELECT title, artist, album FROM songs_cache WHERE path = ?', [path]) or {}
            fname = os.path.splitext(os.path.basename(path))[0]
            track_title = row.get('title', fname) or fname
            artist = row.get('artist')
            album = row.get('album')
            tok = f'{queue_id}:{idx}'
            src = _np_source_fields(tok, _np_device_id())
            write_np_state({
                'track':       track_title,
                'artist':      artist,
                'album':       album,
                'filepath':    path,
                'token':       tok,
                'playing':     True,
                'paused':      False,
                'timestamp':   time.time(),
                'duration_ms': _duration_ms_for_path(path),
                'offset_ms':   (req.get('body') or {}).get('offsetInMilliseconds') or 0,
                **src,
            })
            append_stream_history({
                'track':    track_title,
                'artist':   artist,
                'album':    album,
                'filepath': path,
                'device':   _device_label(_np_device_id()),
                'deviceId': _np_device_id(),
                'playlist': src.get('playlist'),
                'sourceLabel': src.get('sourceLabel'),
                'date':     datetime.datetime.now().isoformat(timespec='seconds'),
            })
        return ('', 200)

    if etype in ('AlexaAudioPlayQueueEvent.ItemPlaybackStopped',
                 'AlexaAudioPlayQueueEvent.ItemPlaybackFinished',
                 'AlexaAudioPlayQueueEvent.ItemPlaybackFailed'):
        state = read_np_state() or {}
        if state:
            state['playing'] = False
            write_np_state(state)
        return ('', 200)

    return ('', 200)

@app.route('/music', methods=['POST'])
def music_skill():
    cfg = _msp_cfg()
    body = request.get_json(force=True) or {}

    # Two payload shapes hit this endpoint:
    #  • Directives   → {"directive"?: {header, payload}}  (or top-level header/payload)
    #  • Skill events → {"request": {type, body}, "context": {System:{user, device}}}
    is_event = isinstance(body.get('request'), dict) and 'header' not in body
    if is_event:
        ctx = body.get('context') or {}
        token = ((ctx.get('System') or {}).get('user') or {}).get('accessToken', '') or ''
    else:
        directive = body.get('directive') if isinstance(body.get('directive'), dict) else body
        header = directive.get('header', {}) or {}
        payload = directive.get('payload', {}) or {}
        # The linked-account bearer arrives either as an Authorization header or, when
        # Alexa calls our HTTPS endpoint directly, in requestContext.user.accessToken.
        auth_header = request.headers.get('Authorization', '')
        token = auth_header[7:].strip() if auth_header.startswith('Bearer ') else ''
        if not token:
            token = ((payload.get('requestContext') or {}).get('user') or {}).get('accessToken', '') or ''

    if token != cfg.get('accessToken'):
        kind = body.get('request', {}).get('type', '') if is_event else \
               f"{(body.get('directive') or body).get('header',{}).get('namespace','')}." \
               f"{(body.get('directive') or body).get('header',{}).get('name','')}"
        print(f"[MSP REJECT] bearer mismatch kind={kind!r} tok_len={len(token)} "
              f"body={json.dumps(body)[:300]}", flush=True)
        return Response('Forbidden', 403)

    if is_event:
        try:
            return _msp_handle_event(body.get('request') or {}, body.get('context') or {})
        except Exception as ex:
            import traceback
            print(f"[MSP EVENT ERROR] {ex}\n{traceback.format_exc()}", flush=True)
            return ('', 200)

    namespace = header.get('namespace', '')
    name = header.get('name', '')

    print(f"[MSP] {namespace}.{name}", flush=True)
    if namespace == 'Alexa.Media.Search':
        attrs = (payload.get('selectionCriteria') or {}).get('attributes')
        print(f"[MSP RESOLVED] attributes={json.dumps(attrs)}", flush=True)
    print(f"[MSP DEBUG] payload={json.dumps(payload)[:2000]}", flush=True)

    try:
        return _msp_handle(namespace, name, payload, header)
    except Exception as ex:
        import traceback
        print(f"[MSP ERROR] {namespace}.{name}: {ex}\n{traceback.format_exc()}", flush=True)
        return _msp_error(header, 'INTERNAL_ERROR', str(ex))

# ── Alexa Skill Endpoint ──────────────────────────────────────────────────────

@app.route('/alexa', methods=['POST'])
def alexa_skill():
    global _LAST_ALEXA_HIT
    _LAST_ALEXA_HIT = time.time()
    raw_body = request.get_data(cache=True)
    body = request.get_json(force=True) or {}

    sess_app = ((body.get('session') or {}).get('application') or {}).get('applicationId', '') or ''
    ctx_app  = (((body.get('context') or {}).get('System') or {}).get('application') or {}).get('applicationId', '') or ''
    presented_app_id = sess_app or ctx_app
    if presented_app_id and presented_app_id != EXPECTED_SKILL_APP_ID:
        print(f"[ALEXA REJECT] applicationId mismatch: {presented_app_id!r}", flush=True)
        return Response('Forbidden: applicationId mismatch', 403,
                        {'Content-Type': 'text/plain; charset=utf-8'})

    if _is_tunnel_request():
        sig_err = _verify_alexa_signature(raw_body, body)
        if sig_err:
            cf_ip = request.headers.get('Cf-Connecting-Ip', '')
            cf_ray = request.headers.get('Cf-Ray', '')
            ua = request.headers.get('User-Agent', '')
            cert_url = request.headers.get('SignatureCertChainUrl', '')
            sig_present = bool(request.headers.get('Signature', ''))
            print(f"[ALEXA REJECT] {sig_err} cf_ip={cf_ip} cf_ray={cf_ray} ua={ua!r} cert_url={cert_url!r} sig_present={sig_present}", flush=True)
            return Response(f'Forbidden: {sig_err}', 403,
                            {'Content-Type': 'text/plain; charset=utf-8'})

    req  = body.get('request', {})
    rtype = req.get('type', '')
    ctx_device = ((body.get('context', {}) or {}).get('System', {}) or {}).get('device', {}) or {}
    raw_device_id = ctx_device.get('deviceId') or 'default'
    supported_ifaces = ctx_device.get('supportedInterfaces') or {}
    if raw_device_id and raw_device_id != 'default':
        register_device(raw_device_id, supported_interfaces=supported_ifaces)
    g.raw_device_id = raw_device_id if raw_device_id != 'default' else 'default'
    # Resolve through alias chain so merged/rotated ids land on their primary
    g.device_id = _resolve_device_id(raw_device_id) if raw_device_id != 'default' else 'default'
    intent_name = (req.get('intent', {}) or {}).get('name', '') if rtype == 'IntentRequest' else ''
    error_summary = ''
    if rtype == 'AudioPlayer.PlaybackFailed':
        err = req.get('error', {}) or {}
        error_summary = f" error_type={err.get('type','')} error_message={err.get('message','')}"
    raw_suffix = raw_device_id[-12:] if raw_device_id and raw_device_id != 'default' else 'default'
    resolved_suffix = g.device_id[-12:] if g.device_id != 'default' else 'default'
    dev_note = f" raw={raw_suffix}" if raw_suffix != resolved_suffix else ''
    print(f"[ALEXA] type={rtype} intent={intent_name} device={resolved_suffix}{dev_note}{error_summary}", flush=True)

    # ── AudioPlayer events ─────────────────────────────────────────────────

    if rtype == 'AudioPlayer.PlaybackStarted':
        # Bind a rotated/auto-named deviceId back to the room we just commanded
        # by serial, so this stream lands on the right device (not a new dupe).
        if g.raw_device_id != 'default' and _correlate_play_intent(g.raw_device_id):
            g.device_id = _resolve_device_id(g.raw_device_id)
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
            src = _np_source_fields(token, _np_device_id())
            write_np_state({
                'track':       track_title,
                'artist':      artist,
                'album':       album,
                'filepath':    path,
                'token':       token,
                'playing':     True,
                'paused':      False,
                'timestamp':   time.time(),
                'duration_ms': _duration_ms_for_path(path),
                **src,
            })
            device_label = _device_label(_np_device_id())
            entry = {
                'track':    track_title,
                'artist':   artist,
                'album':    album,
                'filepath': path,
                'device':   device_label,
                'deviceId': _np_device_id(),
                'playlist': src.get('playlist'),
                'sourceLabel': src.get('sourceLabel'),
                'date':     datetime.datetime.now().isoformat(timespec='seconds'),
            }
            # Identify/test sweeps shouldn't pollute analytics.
            dev_serial = (_load_devices().get(_np_device_id()) or {}).get('serial')
            if _is_test_serial(dev_serial):
                entry['test'] = True
            append_stream_history(entry)
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackNearlyFinished':
        token = req.get('token', '')
        data  = decode_token(token)
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        next_idx = idx + 1
        # Sleep timer / stop-after-N: enforced at the track boundary so the
        # current song finishes, then we simply stop enqueuing.
        stop_at = data.get('stopAt')
        stop_after_idx = data.get('stopAfterIdx')
        if stop_at and time.time() >= float(stop_at):
            return alexa_empty()
        if stop_after_idx is not None and next_idx > int(stop_after_idx):
            return alexa_empty()
        if next_idx >= len(tracks):
            if data.get('loop'):
                next_idx = 0
            else:
                return alexa_empty()
        if next_idx < len(tracks):
            next_path  = tracks[next_idx]
            next_token = encode_token({**data, 'idx': next_idx})
            return _np_play_path(next_path, next_token,
                                 previous_token=token, play_behavior='ENQUEUE')
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
            return _np_play_path(next_path, next_token, play_behavior='REPLACE_ALL')
        state = read_np_state() or {}
        state['playing'] = False
        write_np_state(state)
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackStopped':
        # PlaybackStopped is ambiguous — it fires on pause, on stop, and when a
        # new track replaces the current one. It must NOT decide `paused`; only
        # the explicit pause handlers (PauseIntent / PauseCommandIssued) set that.
        # Don't synthesize a row for a device we aren't already tracking, or a
        # stop/replace would leave a trackless stuck row.
        state = read_np_state()
        if state and state.get('token'):
            state['playing'] = False
            state['offset_ms'] = req.get('offsetInMilliseconds', 0)
            write_np_state(state)
        return alexa_empty()

    if rtype == 'PlaybackController.NextCommandIssued':
        return _np_skip_next(playback_controller=True)
    if rtype == 'PlaybackController.PreviousCommandIssued':
        return _np_skip_previous(playback_controller=True)
    if rtype == 'PlaybackController.PauseCommandIssued':
        return _np_pause_playback(playback_controller=True)
    if rtype == 'PlaybackController.PlayCommandIssued':
        return _np_resume_playback(playback_controller=True)

    if rtype == 'ExceptionEncountered':
        return alexa_empty()

    if rtype == 'CanFulfillIntentRequest':
        intent_slots = (req.get('intent', {}) or {}).get('slots', {}) or {}
        return alexa_can_fulfill(intent_slots, can_fulfill='MAYBE')

    # ── Launch ─────────────────────────────────────────────────────────────

    if rtype == 'LaunchRequest':
        # Two-step flow: avoids Alexa routing one-shot "mix/shuffle … yacht rock" to Spotify.
        # Enable in Settings → Public URL card or set launchPlaylistPrompt in config.json.
        if cfg_bool('launchPlaylistPrompt', False):
            return alexa_speak(
                "Bock Media is listening. Say a playlist name from your library, "
                "or say mix, then the name. For example: yacht rock. "
                "Do not include Spotify — this plays only your server.",
                end_session=False,
                reprompt="Say a playlist name, or mix, then the name.",
            )
        default_pl = get_pref('DefaultPlaylist', '').strip()
        if default_pl:
            entry = best_playlist_entry(default_pl)
            if entry:
                name, source, pid = entry[1], entry[2], entry[0]
            else:
                name, source, pid = None, None, None
            if name and source and os.path.isfile(source):
                tracks = _tracks_for_playlist(pid, source)
                if tracks:
                    do_shuffle = get_pref('DefaultPlaylistShuffle', '').lower() == 'true'
                    return start_playing(tracks, shuffle=do_shuffle,
                                        speech=f"Playing {name}.",
                                        playlist=name, playlist_id=pid)
        return alexa_speak(
            "Welcome to Bock Media. Say play followed by a playlist name, "
            "play music by an artist, or play an album name.",
            end_session=False
        )

    # ── Intents ─────────────────────────────────────────────────────────────

    if rtype == 'IntentRequest':
        intent = req.get('intent', {})
        iname  = intent.get('name', '')
        slots  = intent.get('slots', {})
        def sv(name): return normalize_spoken_value((slots.get(name, {}).get('value') or '').strip())
        print(f'[ALEXA DEBUG] intent={iname} slots={json.dumps({k: slots[k].get("value") for k in slots})}', flush=True)

        # ── Play playlist ──────────────────────────────────────────────────
        if iname == 'PlayPlaylistIntent':
            query = sv('PlaylistName')
            if not query:
                return alexa_speak("Which playlist would you like to play?", end_session=False)
            token_entry = _play_playlist_token_from_query(query)
            if token_entry:
                return _start_playlist_token_entry(token_entry)
            token_entry = _play_file_token_from_query(query)
            if token_entry and os.path.isfile(token_entry['path']):
                label = token_entry['title']
                if token_entry.get('artist'):
                    label = f"{label} by {token_entry['artist']}"
                return start_playing([token_entry['path']], speech=f"Playing {label}.",
                                    context=f'Song · {label}')
            if re.search(r'\bby\b', query, re.I) or re.match(r'^(?:the\s+)?(?:song|track)\s+', query, re.I):
                recovered = _try_play_misrouted_song(query)
                if recovered:
                    tracks, label = recovered
                    return start_playing(tracks, speech=f"Playing {label}.", context=f'Song · {label}')
            entry = best_playlist_entry(query)
            name = entry[1] if entry else None
            source = entry[2] if entry else None
            pid = entry[0] if entry else None
            print(f'[ALEXA DEBUG] PlayPlaylistIntent query={repr(query)} -> name={repr(name)} source={repr(source)}', flush=True)
            if not name:
                if 'token' in query.lower():
                    return alexa_speak("Sorry, that play request expired. Please try again from the app.")
                recovered = _try_play_misrouted_song(query)
                if recovered:
                    tracks, label = recovered
                    return start_playing(tracks, speech=f"Playing {label}.", context=f'Song · {label}')
                return alexa_speak(f"Sorry, I couldn't find a playlist called {query}.")
            return start_playing(None, speech=f"Playing {name}.",
                                playlist=name, playlist_id=pid, source=source)

        # ── Shuffle playlist ───────────────────────────────────────────────
        elif iname == 'ShufflePlaylistIntent':
            query = sv('PlaylistName')
            if not query:
                return alexa_speak("Which playlist would you like to shuffle?", end_session=False)
            token_entry = _play_playlist_token_from_query(query)
            if token_entry:
                return _start_playlist_token_entry(token_entry, shuffle=True)
            entry = best_playlist_entry(query)
            name = entry[1] if entry else None
            source = entry[2] if entry else None
            pid = entry[0] if entry else None
            print(f'[ALEXA DEBUG] ShufflePlaylistIntent query={repr(query)} -> name={repr(name)} source={repr(source)}', flush=True)
            if not name:
                return alexa_speak(f"Sorry, I couldn't find a playlist called {query}.")
            return start_playing(None, shuffle=True, speech=f"Shuffling {name}.",
                                playlist=name, playlist_id=pid, source=source)

        # ── Play artist ────────────────────────────────────────────────────
        elif iname == 'PlayArtistIntent':
            query = sv('ArtistName')
            if not query:
                return alexa_speak("Which artist would you like to play?", end_session=False)
            token_resp = _try_ui_token_play(query)
            if token_resp:
                return token_resp
            if 'playlist' in query.lower():
                pl_q = re.sub(r'\s+playlist$', '', query, flags=re.IGNORECASE).strip()
                pl_q = re.sub(r'^(?:play\s+)?(?:my\s+)?(?:the\s+)?playlist\s+', '', pl_q, flags=re.IGNORECASE).strip() or query
                entry = best_playlist_entry(pl_q)
                if entry and entry[2] and os.path.isfile(entry[2]):
                    tracks = parse_m3u(entry[2])
                    if tracks:
                        return start_playing(tracks, speech=f"Playing {entry[1]}.",
                                            playlist=entry[1], playlist_id=entry[0])
            artist = fuzzy_find_artist(query)
            if not artist:
                return alexa_speak(f"Sorry, I couldn't find any music by {query}.")
            rows = db_query(
                "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
                "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY RANDOM() LIMIT 300",
                [artist]
            )
            tracks = [r['path'] for r in rows]
            if not tracks:
                return alexa_speak(f"I found {artist} but no playable files.")
            return start_playing(tracks, shuffle=True, speech=f"Playing music by {artist}.",
                                context=f'Artist · {artist}')

        # ── Shuffle artist ─────────────────────────────────────────────────
        elif iname == 'ShuffleArtistIntent':
            query = sv('ArtistName')
            if not query:
                return alexa_speak("Which artist would you like to shuffle?", end_session=False)
            token_entry = _play_playlist_token_from_query(query)
            if token_entry:
                return _start_playlist_token_entry(token_entry)
            if 'playlist' in query.lower():
                pl_q = re.sub(r'\s+playlist$', '', query, flags=re.IGNORECASE).strip()
                pl_q = re.sub(r'^(?:shuffle\s+)?(?:play\s+)?(?:my\s+)?(?:the\s+)?playlist\s+', '', pl_q, flags=re.IGNORECASE).strip() or query
                entry = best_playlist_entry(pl_q)
                if entry and entry[2] and os.path.isfile(entry[2]):
                    tracks = parse_m3u(entry[2])
                    if tracks:
                        return start_playing(tracks, shuffle=True, speech=f"Shuffling {entry[1]}.",
                                            playlist=entry[1], playlist_id=entry[0])
            artist = fuzzy_find_artist(query)
            if not artist:
                return alexa_speak(f"Sorry, I couldn't find any music by {query}.")
            rows = db_query(
                "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
                "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY RANDOM() LIMIT 300",
                [artist]
            )
            tracks = [r['path'] for r in rows]
            if not tracks:
                return alexa_speak(f"I found {artist} but no playable files.")
            return start_playing(tracks, shuffle=True, speech=f"Shuffling music by {artist}.",
                                context=f'Artist · {artist}')

        # ── Play album ─────────────────────────────────────────────────────
        elif iname == 'PlayAlbumIntent':
            query = sv('AlbumName')
            if not query:
                return alexa_speak("Which album would you like to play?", end_session=False)
            token_resp = _try_ui_token_play(query)
            if token_resp:
                return token_resp
            if 'playlist' in query.lower():
                pl_q = re.sub(r'\s+playlist$', '', query, flags=re.IGNORECASE).strip()
                pl_q = re.sub(
                    r'^(?:play\s+)?(?:my\s+)?(?:the\s+)?playlist\s+',
                    '', pl_q, flags=re.IGNORECASE,
                ).strip() or query
                entry = best_playlist_entry(pl_q)
                if entry and entry[2] and os.path.isfile(entry[2]):
                    tracks = parse_m3u(entry[2])
                    if tracks:
                        return start_playing(tracks, speech=f"Playing {entry[1]}.",
                                            playlist=entry[1], playlist_id=entry[0])
            album = fuzzy_find_album(query)
            if album:
                tracks = _album_tracks_for_play(album, shuffle=False)
                if tracks:
                    return start_playing(tracks, speech=f"Playing the album {album}.",
                                        context=f'Album · {album}')
            playlist_resp = _play_named_playlist_if_strong_match(query, shuffle=False)
            if playlist_resp:
                return playlist_resp
            if not album:
                return alexa_speak(f"Sorry, I couldn't find the album {query}.")
            return alexa_speak(f"I found {album} but no playable files.")

        # ── Shuffle album ──────────────────────────────────────────────────
        elif iname == 'ShuffleAlbumIntent':
            query = sv('AlbumName')
            if not query:
                return alexa_speak("Which album would you like to shuffle?", end_session=False)
            token_resp = _try_ui_token_play(query, shuffle=True)
            if token_resp:
                return token_resp
            album = fuzzy_find_album(query)
            if album:
                tracks = _album_tracks_for_play(album, shuffle=True)
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Shuffling the album {album}.",
                                        context=f'Album · {album}')
            playlist_resp = _play_named_playlist_if_strong_match(query, shuffle=True)
            if playlist_resp:
                return playlist_resp
            if not album:
                return alexa_speak(f"Sorry, I couldn't find the album {query}.")
            return alexa_speak(f"I found {album} but no playable files.")

        # ── Play file by UI token (exact path, no fuzzy match) ─────────────
        elif iname == 'PlayFileTokenIntent':
            token_raw = sv('FileToken')
            entry = _consume_play_file_token(token_raw)
            if not entry:
                pl_entry = _consume_play_playlist_token(token_raw)
                if pl_entry:
                    if pl_entry.get('kind') == 'album':
                        return _start_album_token_entry(pl_entry)
                    return _start_playlist_token_entry(pl_entry)
            if not entry or not os.path.isfile(entry['path']):
                return alexa_speak("Sorry, I couldn't find that track.")
            label = entry['title']
            if entry.get('artist'):
                label = f"{label} by {entry['artist']}"
            return start_playing([entry['path']], speech=f"Playing {label}.",
                                context=f'Song · {label}')

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
                entry = best_playlist_entry(pl_q)
                if entry and entry[2] and os.path.isfile(entry[2]):
                    tracks = parse_m3u(entry[2])
                    if tracks:
                        return start_playing(tracks, speech=f"Playing {entry[1]}.",
                                            playlist=entry[1], playlist_id=entry[0])

            tracks = fuzzy_find_track(query)
            if not tracks:
                return alexa_speak(f"Sorry, I couldn't find a track called {query}.")
            return start_playing(tracks, speech=f"Playing {query}.", context=f'Song · {query}')

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
            return start_playing(tracks, speech=f"Playing {label}.", context=f'Song · {label}')

        # ── Play genre ─────────────────────────────────────────────────────
        elif iname == 'PlayGenreIntent':
            query = sv('Genre')
            if not query:
                return alexa_speak("Which genre would you like to play?", end_session=False)
            genre = fuzzy_find_genre(query)
            if genre:
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE genre = ? AND path IS NOT NULL "
                    "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY RANDOM() LIMIT 300",
                    [genre]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Playing {genre} music.",
                                        context=f'Genre · {genre}')
            entry = best_playlist_entry(query)
            if entry and entry[2] and os.path.isfile(entry[2]):
                tracks = parse_m3u(entry[2])
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Playing {entry[1]}.",
                                        playlist=entry[1], playlist_id=entry[0])
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
                    "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY RANDOM() LIMIT 300",
                    [genre]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Shuffling {genre} music.",
                                        context=f'Genre · {genre}')
            entry = best_playlist_entry(query)
            if entry and entry[2] and os.path.isfile(entry[2]):
                tracks = parse_m3u(entry[2])
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Shuffling {entry[1]}.",
                                        playlist=entry[1], playlist_id=entry[0])
            return alexa_speak(f"Sorry, I couldn't find music in the genre {query}.")

        # ── General "play X" — tries playlist → artist → album → track ────
        elif iname == 'PlayGeneralIntent':
            query = sv('Query')
            if not query:
                return alexa_speak("What would you like to play?", end_session=False)
            tracks, speech, do_shuffle, meta = general_search_tracks(query)
            if not tracks:
                return alexa_speak(f"Sorry, I couldn't find anything matching {query}.")
            return start_playing(tracks, shuffle=do_shuffle, speech=speech, **meta)

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
                        "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY CAST(track_number AS INTEGER), title LIMIT 100",
                        [album]
                    )
                    tracks = [r['path'] for r in rows]
                    if tracks:
                        return start_playing(tracks, speech=f"Reading {album}.",
                                            context=f'Audiobook · {album}')
                return alexa_speak(f"Sorry, I couldn't find an audio book called {query}.")
            tracks = parse_m3u(source)
            if not tracks:
                return alexa_speak(f"The book {name} has no playable tracks.")
            entry = best_playlist_entry(query)
            pid = entry[0] if entry else None
            return start_playing(tracks, speech=f"Reading {name}.",
                                playlist=name, playlist_id=pid, context=f'Audiobook · {name}')

        # ── Play current selection (set via web UI) ────────────────────────
        elif iname == 'PlayCurrentIntent':
            sel = read_selected()
            if not sel:
                return alexa_speak(
                    "Nothing is currently selected in the console. "
                    "Open the Bock Media web app and browse to a song, album, or artist first."
                )
            sel_type = sel.get('type', '')
            query    = sel.get('name', '')
            if sel_type == 'track':
                path = sel.get('path', '')
                if path and os.path.isfile(path):
                    return start_playing([path], speech=f"Playing {query}.", context=f'Song · {query}')
            elif sel_type == 'album':
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE album = ? AND path IS NOT NULL "
                    "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY CAST(track_number AS INTEGER), title LIMIT 50",
                    [query]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, speech=f"Playing the album {query}.",
                                        context=f'Album · {query}')
            elif sel_type == 'artist':
                rows = db_query(
                    "SELECT path FROM songs_cache WHERE artist = ? AND path IS NOT NULL "
                    "AND LOWER(SUBSTR(path,-4)) IN ('.mp3', '.m4a', '.aac') ORDER BY RANDOM() LIMIT 300",
                    [query]
                )
                tracks = [r['path'] for r in rows]
                if tracks:
                    return start_playing(tracks, shuffle=True, speech=f"Playing music by {query}.",
                                        context=f'Artist · {query}')
            elif sel_type == 'playlist':
                source = sel.get('source', '')
                if source and os.path.isfile(source):
                    tracks = parse_m3u(source)
                    if tracks:
                        return start_playing(tracks, speech=f"Playing {query}.",
                                            playlist=query, playlist_id=sel.get('id'))
            return alexa_speak(f"I couldn't play what's showing. Try selecting something in the console.")

        # ── What's playing ─────────────────────────────────────────────────
        elif iname == 'WhatsPlayingIntent':
            state = read_np_state()
            if not state or not state.get('track'):
                return alexa_speak("Nothing is currently playing.")
            track  = state.get('track', 'Unknown')
            artist = state.get('artist', '')
            album  = state.get('album', '')
            src = state.get('sourceLabel') or state.get('playlist') or state.get('context')
            msg = f"You're listening to {track}"
            if artist:
                msg += f" by {artist}"
            if album:
                msg += f" from the album {album}"
            if src:
                msg += f" from {src}"
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
            track_name = state.get('track', 'the current track')
            # Two-way sync: if this is a Plex-sourced playlist, write the add
            # back to Plex (authoritative). Always also append to the local .m3u
            # for instant reflection in the UI; the next Plex sync run dedupes
            # by rebuilding the .m3u from Plex.
            plex_ok = False
            try:
                import plex_client
                pl_rk = plex_client.playlist_ratingkey_from_source(source)
                if pl_rk:
                    plex_ok = plex_client.add_track_to_playlist(pl_rk, current_path)
            except Exception as e:
                print(f'AddToPlaylist Plex write-back error: {e}', flush=True)
            try:
                with open(source, 'a') as f:
                    f.write(f'\n{current_path}')
            except Exception as e:
                print(f'AddToPlaylist error: {e}', flush=True)
                if not plex_ok:
                    return alexa_speak(f"Sorry, I couldn't add to {name}.")
            return alexa_speak(f"Added {track_name} to {name}.")

        # ── Skip / Back (one-shot transport, collision-safe) ────────────────
        elif iname == 'SkipIntent':
            return _np_skip_next()

        elif iname == 'BackIntent':
            return _np_skip_previous()

        # ── Sleep timer / stop after N songs ────────────────────────────────
        elif iname == 'SleepTimerIntent':
            raw = (slots.get('minutes', {}).get('value') or '').strip()
            try:
                minutes = int(raw)
            except ValueError:
                minutes = 0
            if minutes <= 0:
                return _np_arm_sleep(minutes=None)  # cancel
            return _np_arm_sleep(minutes=minutes)

        elif iname == 'StopAfterIntent':
            raw = (slots.get('count', {}).get('value') or '').strip()
            try:
                count = int(raw)
            except ValueError:
                count = 1
            return _np_arm_sleep(songs=max(1, count))

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
                return _np_play_path(next_path, next_token, speech="OK, skipping that song.")
            return alexa_speak("Song ignored. There are no more tracks.")

        # ── Server management ──────────────────────────────────────────────
        elif iname == 'ListServersIntent':
            return alexa_speak("You have one server: Bock Media.", end_session=False)

        elif iname == 'SwitchServersIntent':
            return alexa_speak(
                "You only have one server configured: Bock Media. "
                "Add additional servers in the Bock Media console to switch between them.",
                end_session=False
            )

        elif iname == 'CurrentServerIntent':
            return alexa_speak("Your current server is Bock Media.", end_session=False)

        elif iname == 'ListInvitationsIntent':
            return alexa_speak(
                "Family share invitations are managed in the Bock Media console under Settings.",
                end_session=False
            )

        # ── Stop / Cancel ──────────────────────────────────────────────────
        elif iname in ('AMAZON.StopIntent', 'AMAZON.CancelIntent'):
            # Stop = device goes home. Clear the row so it doesn't linger in
            # Now Playing (pause keeps the row; stop removes it).
            remove_np_state()
            return alexa_stop()

        # ── Pause ──────────────────────────────────────────────────────────
        elif iname == 'AMAZON.PauseIntent':
            return _np_pause_playback()

        # ── Resume ─────────────────────────────────────────────────────────
        elif iname == 'AMAZON.ResumeIntent':
            return _np_resume_playback()

        # ── Next ───────────────────────────────────────────────────────────
        elif iname == 'AMAZON.NextIntent':
            return _np_skip_next()

        # ── Previous ───────────────────────────────────────────────────────
        elif iname == 'AMAZON.PreviousIntent':
            return _np_skip_previous()

        # ── Loop ───────────────────────────────────────────────────────────
        elif iname == 'AMAZON.LoopOnIntent':
            state = read_np_state() or {}
            if state.get('token'):
                data = decode_token(state['token'])
                _update_queue_flags(data.get('qid'), loop=True)
            return alexa_speak("Loop mode on.")

        elif iname == 'AMAZON.LoopOffIntent':
            state = read_np_state() or {}
            if state.get('token'):
                data = decode_token(state['token'])
                _update_queue_flags(data.get('qid'), loop=False)
            return alexa_speak("Loop mode off.")

        # ── Shuffle (built-in Alexa intents as aliases) ────────────────────
        elif iname == 'AMAZON.ShuffleOnIntent':
            state = read_np_state() or {}
            token = state.get('token', '')
            if token:
                data = decode_token(token)
                # Reshuffle remaining tracks from current position and persist
                idx = data.get('idx', 0)
                remaining = data.get('tracks', [])[idx:]
                random.shuffle(remaining)
                new_tracks = data.get('tracks', [])[:idx] + remaining
                _update_queue_flags(data.get('qid'), shuffle=True, tracks=new_tracks)
                return alexa_speak("Shuffle on.")
            return alexa_speak("Nothing is playing to shuffle.")

        elif iname == 'AMAZON.ShuffleOffIntent':
            state = read_np_state() or {}
            if state.get('token'):
                data = decode_token(state['token'])
                _update_queue_flags(data.get('qid'), shuffle=False)
            return alexa_speak("Shuffle off.")

        # ── Help ───────────────────────────────────────────────────────────
        elif iname == 'AMAZON.HelpIntent':
            return alexa_speak(
                "You can say: play my Yacht Rock playlist, play the album Rumours, "
                "play music by Dave Matthews, play the song Hotel California, "
                "play jazz music, or mix the album Kind of Blue. "
                "Tip: say mix instead of shuffle to avoid Spotify or Amazon Music "
                "intercepting your request. Or open Bock Media first, then say the playlist name. "
                "You can also say: what's playing, next, previous, pause, resume, or stop.",
                end_session=False
            )

        return alexa_speak("I didn't understand that. Try saying play followed by a playlist, artist, or album name.")

    return alexa_speak(
        "Sorry, I couldn't process that Alexa request. Please try again.",
        end_session=True
    )

# ── Run ──────────────────────────────────────────────────────────────────────

_start_automation_scheduler()

if __name__ == '__main__':
    apply_logging()
    port = int(os.environ.get('PORT', 3001))
    print(f'Bock Media running at http://localhost:{port}')
    app.run(host='0.0.0.0', port=port, debug=False)
