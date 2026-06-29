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
import hashlib
import hmac
import fcntl
import contextlib
import alexa_apl
import ipaddress
from logging.handlers import RotatingFileHandler
from urllib.parse import quote, urlparse, urlencode, parse_qsl
from urllib.request import urlopen
from cryptography import x509
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding as rsa_padding
from flask import Flask, jsonify, request, send_from_directory, send_file, Response, stream_with_context

import bock_loudness
import bock_continue
import bock_folders
import bock_artist_art
import bock_client_prefs
import bock_library_health
import bock_ratings
import bock_member_backup
from bock_routes import register as register_bock_routes

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
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__)))
_DEMO_MUSIC_ROOT = os.path.join(REPO_ROOT, 'fixtures', 'demo-data', 'music')
DB_PATH = os.environ.get('OURMEDIA_DB_PATH', os.path.join(REPO_ROOT, 'fixtures', 'demo-data', 'songs_cache.db'))
DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', os.path.join(REPO_ROOT, 'fixtures', 'demo-data'))

_MEMBER_DATA_BASENAMES = frozenset({
    'ratings.json', 'client_prefs.json', 'household.json', 'favorites.json',
})


def _migrate_ratings_to_data_dir():
    """Keep ratings with other household state under DATA_DIR (not the git repo)."""
    dest = bock_ratings.ratings_path(DATA_DIR)
    legacy = bock_ratings.ratings_path(HERE)
    try:
        os.makedirs(DATA_DIR, exist_ok=True)
        if os.path.isfile(legacy) and not os.path.isfile(dest):
            shutil.copy2(legacy, dest)
            print(f'[RATINGS] migrated {legacy} -> {dest}', flush=True)
        if os.path.isfile(dest):
            return dest
    except OSError as ex:
        print(f'[RATINGS] using legacy path ({ex})', flush=True)
    return legacy if os.path.isfile(legacy) else dest


RATINGS_PATH = _migrate_ratings_to_data_dir()
FAVORITES_PATH = os.path.join(DATA_DIR, 'favorites.json')
try:
    os.makedirs(DATA_DIR, exist_ok=True)
    _legacy_favorites = os.path.join(HERE, 'favorites.json')
    if os.path.isfile(_legacy_favorites) and not os.path.isfile(FAVORITES_PATH):
        shutil.copy2(_legacy_favorites, FAVORITES_PATH)
except OSError:
    FAVORITES_PATH = os.path.join(HERE, 'favorites.json')

import bock_search as _bock_search_mod
_bock_search_mod.configure(DATA_DIR)
HEALTH_STATE_PATH = os.path.join(DATA_DIR, 'health_state.json')
PLAYLIST_FOLDERS_PATH = os.path.join(DATA_DIR, 'playlist_folders.json')
PLAYBACK_RESUME_PATH = os.path.join(DATA_DIR, 'playback_resume.json')
RECOMMENDATIONS_CACHE_PATH = os.path.join(DATA_DIR, 'recommendations_cache.json')
PLAY_COUNTS_PATH = os.path.join(DATA_DIR, 'play_counts.json')

# ── DB helper ────────────────────────────────────────────────────────────────

def get_db():
    conn = sqlite3.connect(f'file:{DB_PATH}?mode=ro', uri=True)
    conn.row_factory = sqlite3.Row
    return conn

def get_db_rw():
    conn = sqlite3.connect(DB_PATH)
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


def db_execute(sql, params=()):
    try:
        conn = get_db_rw()
        cur = conn.execute(sql, params)
        conn.commit()
        n = cur.rowcount
        conn.close()
        return n if n >= 0 else 0
    except Exception as e:
        print(f'DB execute error: {e}')
        return 0

# ── Albums aggregate (fast /api/albums on large libraries) ───────────────────

_ALBUMS_AGG_LOCK = threading.Lock()
_ALBUMS_AGG_BUILDING = False


def _albums_agg_exists():
    row = db_one("SELECT name FROM sqlite_master WHERE type='table' AND name='albums_agg'")
    return bool(row.get('name'))


def refresh_albums_agg():
    """Rebuild albums_agg from songs_cache. One row per (album, artist)."""
    global _ALBUMS_AGG_BUILDING
    with _ALBUMS_AGG_LOCK:
        if _ALBUMS_AGG_BUILDING:
            return False
        _ALBUMS_AGG_BUILDING = True
    try:
        print('[albums_agg] rebuilding…', flush=True)
        conn = get_db_rw()
        conn.execute('DROP TABLE IF EXISTS albums_agg')
        conn.execute('''
            CREATE TABLE albums_agg AS
            SELECT album,
                   COALESCE(NULLIF(album_artist, ''), artist) AS artist,
                   COUNT(*) AS track_count,
                   MAX(CAST(NULLIF(year, '') AS INTEGER)) AS year,
                   MIN(CASE WHEN path IS NOT NULL AND path != '' THEN path END) AS art_path,
                   MIN(first_seen_at) AS first_seen_at
            FROM songs_cache
            WHERE album IS NOT NULL AND album != ''
            GROUP BY album, COALESCE(NULLIF(album_artist, ''), artist)
        ''')
        conn.execute('CREATE INDEX idx_albums_agg_album ON albums_agg(album COLLATE NOCASE)')
        conn.execute('CREATE INDEX idx_albums_agg_artist ON albums_agg(artist COLLATE NOCASE)')
        conn.commit()
        conn.close()
        print('[albums_agg] rebuild complete', flush=True)
        return True
    except Exception as e:
        print(f'[albums_agg] rebuild failed: {e}', flush=True)
        return False
    finally:
        _ALBUMS_AGG_BUILDING = False


def ensure_albums_agg_async():
    if _albums_agg_exists() or _ALBUMS_AGG_BUILDING:
        return
    threading.Thread(target=refresh_albums_agg, daemon=True, name='albums-agg').start()


def _albums_agg_startup():
    if not _albums_agg_exists():
        refresh_albums_agg()


threading.Thread(target=_albums_agg_startup, daemon=True, name='albums-agg-startup').start()

_PLAYED_PATHS_CACHE = {'history_mtime': 0.0, 'counts_mtime': 0.0, 'paths': set()}


def _played_paths_set():
    """Paths that have ever been played (play_counts + stream history)."""
    global _PLAYED_PATHS_CACHE
    try:
        hist_mtime = os.path.getmtime(STREAM_HISTORY_PATH) if os.path.isfile(STREAM_HISTORY_PATH) else 0.0
    except OSError:
        hist_mtime = 0.0
    try:
        counts_mtime = os.path.getmtime(PLAY_COUNTS_PATH) if os.path.isfile(PLAY_COUNTS_PATH) else 0.0
    except OSError:
        counts_mtime = 0.0
    if (hist_mtime == _PLAYED_PATHS_CACHE['history_mtime']
            and counts_mtime == _PLAYED_PATHS_CACHE['counts_mtime']):
        return _PLAYED_PATHS_CACHE['paths']
    import bock_play_counts
    paths = set((bock_play_counts.load_counts(PLAY_COUNTS_PATH).get('paths') or {}).keys())
    for row in _read_stream_history():
        fp = row.get('filepath') or row.get('path')
        if fp:
            paths.add(fp)
    _PLAYED_PATHS_CACHE = {'history_mtime': hist_mtime, 'counts_mtime': counts_mtime, 'paths': paths}
    return paths


def _albums_played_flags(items):
    """Map (album, artist) -> True if any track from that album has been played."""
    if not items:
        return {}
    played_paths = _played_paths_set()
    if not played_paths:
        return {(it['album'], it.get('artist') or ''): False for it in items}
    conds = []
    params = []
    for it in items:
        conds.append('(album = ? AND COALESCE(NULLIF(album_artist, ""), artist) = ?)')
        params.extend([it['album'], it.get('artist') or ''])
    rows = db_query(
        f'SELECT album, COALESCE(NULLIF(album_artist, ""), artist) AS artist, path '
        f'FROM songs_cache WHERE {" OR ".join(conds)}',
        params,
    )
    played_albums = set()
    for row in rows:
        if row.get('path') in played_paths:
            played_albums.add((row['album'], row.get('artist') or ''))
    return {
        (it['album'], it.get('artist') or ''): (it['album'], it.get('artist') or '') in played_albums
        for it in items
    }

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

def _is_loopback_remote():
    ra = (request.remote_addr or '').strip().split('%')[0]
    try:
        return ipaddress.ip_address(ra).is_loopback
    except ValueError:
        return False

def _is_tunnel_request():
    # Trust Cf-* only when cloudflared connects locally (loopback). Port-forward
    # clients can forge Cf-Connecting-Ip to bypass external auth (C-03).
    if not (request.headers.get('Cf-Connecting-Ip') or request.headers.get('Cf-Ray')):
        return False
    return _is_loopback_remote()

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
    return (get_pref('WebUsername', '') or 'admin').strip() or 'admin'

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
    for key in ('alexaRemote', 'mspOauth', 'claude', 'openai', 'mobileApi'):
        if key in redacted:
            redacted[key] = {'_redacted': True}
    return redacted

def _allow_open_lan_api():
    return _cfg_flag('mobileApi', 'allowOpenLanApi')

def _allow_open_lan_media():
    return _cfg_flag('mobileApi', 'allowOpenLanMedia')

def _credentials_configured():
    if get_pref('WebPassword', '').strip():
        return True
    try:
        ma = load_config().get('mobileApi') or {}
        return bool((ma.get('token') or '').strip())
    except Exception:
        return False

def _media_signing_secret():
    try:
        ma = load_config().get('mobileApi') or {}
        token = (ma.get('token') or '').strip()
        if token:
            return token.encode('utf-8')
    except Exception:
        pass
    pw = get_pref('WebPassword', '').strip()
    return pw.encode('utf-8') if pw else None

_MEDIA_SIG_TTL_SEC = 86400

def _media_sig_canonical(path, expires, query_pairs):
    parts = [path, str(expires)]
    for k, v in sorted(query_pairs, key=lambda x: x[0]):
        if k in ('sig', 'expires'):
            continue
        parts.append(f'{k}={v}')
    return '\n'.join(parts).encode('utf-8')

def _append_media_sig(path, query=None):
    """Append HMAC query params to a /stream/ or /artwork/ path."""
    pairs = []
    if isinstance(query, dict):
        pairs = [(k, str(v)) for k, v in query.items()]
    elif query:
        pairs = [(k, v) for k, v in parse_qsl(query, keep_blank_values=True)]
    secret = _media_signing_secret()
    if not secret:
        return f'{path}?{urlencode(pairs)}' if pairs else path
    expires = str(int(time.time()) + _MEDIA_SIG_TTL_SEC)
    sig = hmac.new(
        secret,
        _media_sig_canonical(path, expires, pairs),
        hashlib.sha256,
    ).hexdigest()
    pairs.extend([('expires', expires), ('sig', sig)])
    return f'{path}?{urlencode(pairs)}'

def _verify_media_signature():
    secret = _media_signing_secret()
    if not secret:
        return False
    sig = (request.args.get('sig') or '').strip()
    expires = (request.args.get('expires') or '').strip()
    if not sig or not expires.isdigit():
        return False
    if int(expires) < int(time.time()):
        return False
    pairs = [(k, v) for k, v in request.args.items() if k not in ('sig', 'expires')]
    expected = hmac.new(
        secret,
        _media_sig_canonical(request.path, expires, pairs),
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(sig, expected)

def _media_access_ok():
    if _allow_open_lan_media():
        return True
    if _basic_auth_ok() or _mobile_api_token_ok():
        return True
    return _verify_media_signature()

def _api_write_auth_ok():
    if _allow_open_lan_api():
        return True
    return _basic_auth_ok() or _mobile_api_token_ok()

def _api_read_auth_ok():
    if _allow_open_lan_api():
        return True
    return _basic_auth_ok() or _mobile_api_token_ok()

_API_LAN_GET_PUBLIC = frozenset({
    '/api/health',
    '/api/auth/info',
})

def _api_auth_required():
    """True when the web UI should prompt for console login."""
    if get_pref('WebPassword', '').strip():
        return True
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
EXPECTED_SKILL_APP_ID = os.environ.get(
    'OURMEDIA_SKILL_ID', 'amzn1.ask.skill.YOUR_CUSTOM_SKILL_ID')
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
        raw = (ts_str or '').strip().replace('Z', '+00:00')
        if '.' in raw and '+' in raw:
            ts = datetime.datetime.fromisoformat(raw)
        else:
            ts = datetime.datetime.strptime(raw.split('+')[0], '%Y-%m-%dT%H:%M:%S')
            ts = ts.replace(tzinfo=datetime.timezone.utc)
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=datetime.timezone.utc)
    except Exception:
        return 'malformed request timestamp'
    skew = abs((datetime.datetime.now(datetime.timezone.utc) - ts).total_seconds())
    if skew > _ALEXA_TIMESTAMP_WINDOW_SEC:
        return f'request timestamp outside acceptance window (skew={skew:.0f}s ts={ts_str!r})'
    return None

def _app_download_user():
    try:
        ad = load_config().get('appDownload') or {}
    except Exception:
        ad = {}
    return (ad.get('username') or _web_username() or 'admin').strip()

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

def _app_ipa_path():
    for p in (
        os.path.join(DATA_DIR, 'bockmedia-console.ipa'),
        os.path.join(HERE, 'ios', 'build', 'export', 'BockMedia.ipa'),
        os.path.join(HERE, 'ios', 'build', 'export', 'bockmedia-console.ipa'),
    ):
        if os.path.isfile(p):
            return p
    return None

_APP_IOS_BUNDLE_ID = 'com.bockmedia.console'
_APP_DL_TOKEN_TTL_SEC = 3600
_app_dl_tokens = {}

def _issue_app_download_token():
    tok = uuid.uuid4().hex
    _app_dl_tokens[tok] = time.time() + _APP_DL_TOKEN_TTL_SEC
    stale = [k for k, exp in _app_dl_tokens.items() if exp <= time.time()]
    for k in stale:
        _app_dl_tokens.pop(k, None)
    return tok

def _app_download_token_ok():
    tok = (request.args.get('t') or '').strip()
    if not tok:
        return False
    exp = _app_dl_tokens.get(tok)
    if exp and time.time() < exp:
        return True
    _app_dl_tokens.pop(tok, None)
    return False

def _app_download_auth_ok_or_token():
    return _app_download_auth_ok() or _app_download_token_ok()

def _android_app_deployed_version():
    """Version stamp written next to the APK in DATA_DIR on mobile deploy."""
    path = os.path.join(DATA_DIR, 'bockmedia-console.version')
    try:
        if os.path.isfile(path):
            with open(path, encoding='utf-8') as fh:
                v = (fh.read() or '').strip()
            if v:
                return v
    except Exception as e:
        print(f'app android version sidecar read {path}: {e}', flush=True)
    return None


def _android_app_version():
    deployed = _android_app_deployed_version()
    if deployed:
        return deployed
    try:
        with open(os.path.join(HERE, 'android', 'app', 'build.gradle.kts')) as f:
            for line in f:
                if 'versionName' in line:
                    return line.split('"')[1]
    except Exception:
        pass
    return 'unknown'


def _warn_app_version_mismatch(releases, android_version):
    """Log when the APK stamp and release notes disagree (common partial-deploy mistake)."""
    if not releases or not android_version or android_version == 'unknown':
        return
    notes_ver = (releases[0].get('version') or '').strip()
    if notes_ver and notes_ver != android_version:
        print(
            f'APP VERSION MISMATCH: android build={android_version!r} '
            f'release notes latest={notes_ver!r} — run scripts/deploy_mobile_app.sh',
            flush=True,
        )

def _ios_app_version():
    try:
        with open(os.path.join(HERE, 'ios', 'project.yml')) as f:
            for line in f:
                if 'MARKETING_VERSION' in line:
                    return line.split('"')[1]
    except Exception:
        pass
    return 'unknown'


def _load_app_release_notes():
    """Release notes for GET /app — edit app-release-notes.json on each mobile deploy."""
    path = os.path.join(HERE, 'app-release-notes.json')
    try:
        with open(path, encoding='utf-8') as fh:
            data = json.load(fh)
        releases = data.get('releases') if isinstance(data, dict) else None
        if not isinstance(releases, list):
            return []
        out = []
        for rel in releases:
            if not isinstance(rel, dict):
                continue
            version = (rel.get('version') or '').strip()
            if not version:
                continue
            items = []
            for item in rel.get('items') or []:
                if not isinstance(item, dict):
                    continue
                text = (item.get('text') or '').strip()
                if not text:
                    continue
                kind = (item.get('kind') or 'improve').strip().lower()
                items.append({'kind': kind, 'text': text})
            out.append({
                'version': version,
                'date': (rel.get('date') or '').strip(),
                'items': items,
            })
        return out
    except Exception as e:
        print(f'app release notes read {path}: {e}', flush=True)
        return []


def _release_note_kind_label(kind):
    return {
        'feat': 'New',
        'fix': 'Fixed',
        'improve': 'Improved',
        'chore': 'Update',
    }.get(kind, 'Update')


def _render_app_release_notes_html(releases):
    if not releases:
        return ''
    blocks = []
    for i, rel in enumerate(releases):
        version = html.escape(rel['version'])
        date = html.escape(rel['date']) if rel.get('date') else ''
        heading = f'Version {version}' + (f' · {date}' if date else '')
        cls = 'release release-latest' if i == 0 else 'release release-older'
        items_html = ''
        for item in rel.get('items') or []:
            label = html.escape(_release_note_kind_label(item.get('kind')))
            text = html.escape(item['text'])
            items_html += f'<li><span class="tag tag-{html.escape(item.get("kind") or "improve")}">{label}</span> {text}</li>'
        if not items_html:
            continue
        blocks.append(
            f'<div class="{cls}"><h3>{heading}</h3><ul>{items_html}</ul></div>'
        )
    if not blocks:
        return ''
    return (
        '<section class="release-notes">'
        '<h2>What\u2019s new</h2>'
        + ''.join(blocks)
        + '</section>'
    )


def _render_app_highlights_html(releases):
    """Short summary from the latest entry in app-release-notes.json."""
    latest = releases[0] if releases else None
    items = (latest or {}).get('items') or []
    if not items:
        return ''
    version = html.escape((latest.get('version') or '').strip())
    date = html.escape((latest.get('date') or '').strip())
    heading = 'Highlights in this release'
    if version:
        heading += f' (v{version}' + (f' · {date}' if date else '') + ')'
    lis = ''.join(
        f'<li>{html.escape(item["text"])}</li>'
        for item in items[:6]
        if (item.get('text') or '').strip()
    )
    if not lis:
        return ''
    return (
        f'<section class="highlights"><h2>{heading}</h2>'
        f'<ul>{lis}</ul>'
        f'<p class="hint">Full history below. Mix Muse needs API keys in server config; Resonance works out of the box.</p>'
        f'</section>'
    )


def _app_download_payload(*, issue_token=False):
    apk = _app_apk_path()
    ipa = _app_ipa_path()
    dl_token = _issue_app_download_token() if issue_token else None
    releases = _load_app_release_notes()
    android_version = _android_app_version()
    _warn_app_version_mismatch(releases, android_version)
    android = {
        'version': android_version,
        'sizeMb': round(os.path.getsize(apk) / (1024 * 1024), 1) if apk else None,
        'available': apk is not None,
    }
    ios = {
        'version': _ios_app_version(),
        'sizeMb': round(os.path.getsize(ipa) / (1024 * 1024), 1) if ipa else None,
        'available': ipa is not None,
        'otaAvailable': bool(ipa and get_public_url().startswith('https://')),
    }
    if issue_token and dl_token:
        android['downloadHref'] = f'/download/bockmedia-console.apk?t={dl_token}'
        ios['downloadHref'] = f'/download/bockmedia-console.ipa?t={dl_token}'
        ios['manifestHref'] = f'/download/bockmedia-console-ios.plist?t={dl_token}'
        if ios['otaAvailable']:
            manifest_url = _app_download_abs_url('/download/bockmedia-console-ios.plist', dl_token)
            ios['otaHref'] = f'itms-services://?action=download-manifest&url={quote(manifest_url, safe="")}'
    return {
        'android': android,
        'ios': ios,
        'releases': _load_app_release_notes(),
        'appPageHref': '/app',
    }

def _app_download_abs_url(path, token=None):
    base = get_public_url().rstrip('/')
    q = f'?t={quote(token)}' if token else ''
    return f'{base}{path}{q}'

_APP_DOWNLOAD_PATHS = frozenset({
    '/app',
    '/download/bockmedia-console.apk',
    '/download/bockmedia-console.ipa',
    '/download/bockmedia-console-ios.plist',
})

@app.before_request
def check_auth():
    if request.path == '/api/auth/info':
        return None

    if request.path in _APP_DOWNLOAD_PATHS:
        if request.path == '/app':
            if _app_download_auth_ok():
                return None
        elif _app_download_auth_ok_or_token():
            return None
        return _auth_required()

    is_media = request.path.startswith('/stream/') or request.path.startswith('/artwork/')
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
        if is_media and not _media_access_ok():
            if _credentials_configured():
                return _auth_required()
            return _forbidden('Media access requires authentication')
        if _mobile_api_token_ok() or _basic_auth_ok():
            return None
        return _auth_required()

    if tunnel and not is_alexa_tunnel_path:
        if request.path.startswith('/api/') and _mobile_api_token_ok():
            return None
        return _forbidden()

    # Alexa/audio fetches via cloudflared (loopback + Cf-* headers).
    if tunnel and is_alexa_tunnel_path:
        return None

    # LAN stream/artwork — require credentials, HMAC sig, or opt-in open media (C-02).
    if is_media and _is_lan_request() and not tunnel:
        if not _media_access_ok():
            if _credentials_configured() or _media_signing_secret():
                return _auth_required()
            return _forbidden(
                'Media access disabled on LAN — set WebPassword, mobileApi.token, '
                'or mobileApi.allowOpenLanMedia'
            )

    # Mutating API on LAN — require credentials or opt-in open API (C-01).
    if (request.path.startswith('/api/')
            and request.method in ('POST', 'PUT', 'PATCH', 'DELETE')
            and _is_lan_request()
            and not tunnel):
        if not _api_write_auth_ok():
            if _credentials_configured():
                return _auth_required()
            return _forbidden(
                'API writes require authentication — set credentials or mobileApi.allowOpenLanApi'
            )

    # Read-only API on LAN — require auth when credentials are configured (C-01 partial).
    if (request.path.startswith('/api/')
            and request.method == 'GET'
            and request.path not in _API_LAN_GET_PUBLIC
            and _is_lan_request()
            and not tunnel
            and _credentials_configured()):
        if not _api_read_auth_ok():
            return _auth_required()

    if request.path.startswith('/api/') and _mobile_api_token_ok():
        return None

    return None

# ── Static files ─────────────────────────────────────────────────────────────

PUBLIC = os.path.join(HERE, 'public')

_WEB_REQUIRED = (
    'index.html',
    'css/shell.css',
    'css/style.css',
    'css/dark-theme.css',
    'js/app.js',
    'js/boot.js',
    'js/webCache.js',
    'js/homeFeed.js',
    'js/clientPrefsSync.js',
)


def _verify_web_assets():
    """Log missing/stale web shell assets (partial deploys break the sidebar and routes)."""
    missing = [rel for rel in _WEB_REQUIRED if not os.path.isfile(os.path.join(PUBLIC, rel))]
    if missing:
        print(
            f'WEB UI INCOMPLETE — missing under public/: {", ".join(missing)} '
            f'(run scripts/deploy_web.sh)',
            flush=True,
        )
        return
    try:
        shell_bytes = os.path.getsize(os.path.join(PUBLIC, 'css', 'shell.css'))
    except OSError:
        shell_bytes = 0
    if shell_bytes and shell_bytes < 30000:
        print(
            f'WEB UI WARNING — css/shell.css is only {shell_bytes} bytes; '
            f'expected ~40k (stale partial deploy?)',
            flush=True,
        )


_verify_web_assets()

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
    payload = _app_download_payload(issue_token=True)
    android = {
        'version': payload['android']['version'],
        'size_mb': payload['android']['sizeMb'],
        'available': payload['android']['available'],
        'download_href': payload['android'].get('downloadHref', '/download/bockmedia-console.apk'),
    }
    ios = {
        'version': payload['ios']['version'],
        'size_mb': payload['ios']['sizeMb'],
        'available': payload['ios']['available'],
        'download_href': payload['ios'].get('downloadHref', '/download/bockmedia-console.ipa'),
        'manifest_href': payload['ios'].get('manifestHref', '/download/bockmedia-console-ios.plist'),
        'ota_href': payload['ios'].get('otaHref'),
    }
    return _no_cache(Response(
        _render_app_download_html(android, ios, payload['releases']),
        mimetype='text/html; charset=utf-8',
    ))


@app.route('/api/app/info')
def app_info():
    """Mobile app builds + release notes for the web console download page."""
    return jsonify(_app_download_payload(issue_token=True))


def _atomic_binary_write(path, data):
    """Write binary atomically (APK/IPA uploads)."""
    tmp = path + '.tmp'
    with open(tmp, 'wb') as fh:
        fh.write(data)
    os.replace(tmp, path)


@app.route('/api/admin/mobile-app/android', methods=['PUT'])
def admin_upload_android_apk():
    """Upload sideload APK + version sidecar (mobile API token; works off-LAN)."""
    if not _mobile_api_token_ok():
        return _forbidden()
    version = (request.headers.get('X-App-Version') or request.args.get('version') or '').strip()
    if not version:
        return jsonify({'error': 'version required (X-App-Version header or ?version=)'}), 400
    data = request.get_data()
    if len(data) < 100_000:
        return jsonify({'error': 'APK body too small'}), 400
    apk_path = os.path.join(DATA_DIR, 'bockmedia-console.apk')
    ver_path = os.path.join(DATA_DIR, 'bockmedia-console.version')
    try:
        _atomic_binary_write(apk_path, data)
        with open(ver_path, 'w', encoding='utf-8') as fh:
            fh.write(version)
    except Exception as e:
        return jsonify({'error': str(e)}), 500
    print(f'mobile-app upload: android v{version} ({len(data)} bytes) -> {apk_path}', flush=True)
    return jsonify({'ok': True, 'version': version, 'sizeMb': round(len(data) / (1024 * 1024), 1)})


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

@app.route('/download/bockmedia-console.ipa')
def app_download_ipa():
    ipa = _app_ipa_path()
    if not ipa:
        return Response(
            'iOS IPA not on server yet — archive in Xcode and copy to '
            f'{os.path.join(DATA_DIR, "bockmedia-console.ipa")}',
            404,
            {'Content-Type': 'text/plain; charset=utf-8'},
        )
    return send_file(
        ipa,
        mimetype='application/octet-stream',
        as_attachment=True,
        download_name='bockmedia-console.ipa',
        max_age=0,
    )

@app.route('/download/bockmedia-console-ios.plist')
def app_download_ios_manifest():
    ipa = _app_ipa_path()
    if not ipa:
        return Response('iOS IPA not available', 404, {'Content-Type': 'text/plain; charset=utf-8'})
    token = (request.args.get('t') or '').strip()
    ipa_url = _app_download_abs_url('/download/bockmedia-console.ipa', token or None)
    version = _ios_app_version()
    plist = f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>items</key>
  <array>
    <dict>
      <key>assets</key>
      <array>
        <dict>
          <key>kind</key>
          <string>software-package</string>
          <key>url</key>
          <string>{html.escape(ipa_url)}</string>
        </dict>
      </array>
      <key>metadata</key>
      <dict>
        <key>bundle-identifier</key>
        <string>{_APP_IOS_BUNDLE_ID}</string>
        <key>bundle-version</key>
        <string>{html.escape(version)}</string>
        <key>kind</key>
        <string>software</string>
        <key>title</key>
        <string>Bock Media</string>
      </dict>
    </dict>
  </array>
</dict>
</plist>'''
    return Response(plist, mimetype='application/xml; charset=utf-8')

def _render_app_download_html(android, ios, releases=None):
    def platform_section(title, subtitle, version, size_mb, available, primary_btn, secondary_btn, steps_html):
        meta = (
            f'<p class="meta">Build {html.escape(version)} · {size_mb} MB · full install</p>'
            if available and size_mb else ''
        )
        if available:
            actions = primary_btn
            if secondary_btn:
                actions += secondary_btn
        else:
            actions = f'<p class="warn">{html.escape(subtitle)} not available on server yet.</p>'
        return f'''
  <section class="platform">
    <h2>{html.escape(title)}</h2>
    {meta}
    <div class="actions">{actions}</div>
    <div class="steps">
      <strong>Install</strong>
      <ol>{steps_html}</ol>
    </div>
  </section>'''

    apk_btn = (
        f'<a class="btn" href="{html.escape(android["download_href"])}">Download APK</a>'
        if android['available'] else ''
    )
    android_steps = '''
      <li>Download the APK</li>
      <li>Allow installs from browser if prompted</li>
      <li>Open app → enter external URL + Mobile API token</li>'''

    ios_primary = ''
    ios_secondary = ''
    if ios['available']:
        if ios.get('ota_href'):
            ios_primary = (
                f'<a class="btn btn-ios" href="{html.escape(ios["ota_href"])}">Install on iPhone</a>'
            )
        ios_secondary = (
            f'<a class="btn btn-secondary" href="{html.escape(ios["download_href"])}">Download IPA</a>'
        )
    ios_steps = '''
      <li>Open this page in <strong>Safari</strong> on your iPhone</li>
      <li>Tap <strong>Install on iPhone</strong> (or download the IPA for Xcode / AltStore)</li>
      <li>Trust the developer in Settings → General → VPN &amp; Device Management if prompted</li>
      <li>Open app → enter external URL + Mobile API token</li>'''

    android_section = platform_section(
        'Android', 'APK', android['version'], android['size_mb'], android['available'],
        apk_btn, '', android_steps,
    )
    ios_section = platform_section(
        'iPhone', 'IPA', ios['version'], ios['size_mb'], ios['available'],
        ios_primary, ios_secondary, ios_steps,
    )
    releases = releases if releases is not None else _load_app_release_notes()
    highlights = _render_app_highlights_html(releases)
    release_notes = _render_app_release_notes_html(releases)

    return f'''<!DOCTYPE html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bock Media — Mobile apps</title>
<style>
  body {{ font-family: system-ui, sans-serif; background: #f4f6f9; color: #333; margin: 0; min-height: 100vh;
    display: flex; align-items: center; justify-content: center; padding: 24px; }}
  .card {{ background: #fff; border-radius: 12px; padding: 32px; max-width: 520px; width: 100%;
    box-shadow: 0 4px 24px rgba(0,0,0,.08); text-align: center; }}
  h1 {{ font-size: 1.5rem; margin: 0 0 8px; color: #30426a; }}
  .lead {{ color: #666; margin: 0 0 24px; font-size: .95rem; }}
  .highlights {{ text-align: left; margin: 20px 0; padding: 16px; background: #f8fafc; border-radius: 8px; font-size: .88rem;
    border-top: 1px solid #e8ecf1; padding-top: 20px; }}
  .highlights h2 {{ font-size: .95rem; margin: 0 0 10px; color: #30426a; text-align: center; }}
  .highlights ul {{ margin: 0; padding-left: 18px; color: #444; line-height: 1.5; }}
  .highlights li {{ margin-bottom: 6px; }}
  .highlights .hint {{ margin: 12px 0 0; font-size: .8rem; color: #666; }}
  .highlights code {{ font-size: .78rem; background: #eef2f7; padding: 1px 4px; border-radius: 3px; }}
  .platform {{ text-align: center; padding-top: 20px; margin-top: 20px; border-top: 1px solid #e8ecf1; }}
  .platform:first-of-type {{ border-top: none; margin-top: 0; padding-top: 0; }}
  h2 {{ font-size: 1.15rem; margin: 0 0 8px; color: #30426a; }}
  .meta {{ color: #666; margin: 0 0 16px; font-size: .9rem; }}
  .actions {{ display: flex; flex-direction: column; gap: 10px; align-items: center; }}
  .btn {{ display: inline-block; background: #30426a; color: #fff; padding: 14px 28px;
    border-radius: 8px; font-weight: 600; text-decoration: none; min-width: 200px; }}
  .btn:hover {{ background: #3d5285; }}
  .btn-ios {{ background: #1DB954; }}
  .btn-ios:hover {{ background: #1ed760; }}
  .btn-secondary {{ background: #fff; color: #30426a; border: 2px solid #30426a; }}
  .btn-secondary:hover {{ background: #f0f3f8; }}
  .warn {{ color: #b45309; margin: 0; font-size: .9rem; }}
  .steps {{ text-align: left; margin-top: 16px; font-size: .85rem; color: #555; }}
  ol {{ margin: 8px 0 0; padding-left: 20px; }}
  .release-notes {{ text-align: left; margin-top: 24px; padding-top: 20px; border-top: 1px solid #e8ecf1; }}
  .release-notes > h2 {{ text-align: center; margin-bottom: 16px; }}
  .release {{ margin-bottom: 16px; }}
  .release-latest h3 {{ font-size: 1rem; color: #30426a; margin: 0 0 8px; }}
  .release-older h3 {{ font-size: .9rem; color: #666; margin: 0 0 6px; font-weight: 600; }}
  .release ul {{ margin: 0; padding: 0; list-style: none; }}
  .release li {{ font-size: .85rem; color: #444; margin-bottom: 8px; line-height: 1.45; }}
  .tag {{ display: inline-block; font-size: .7rem; font-weight: 700; text-transform: uppercase;
    letter-spacing: .03em; padding: 2px 6px; border-radius: 4px; margin-right: 6px; vertical-align: baseline; }}
  .tag-feat {{ background: #e8f5e9; color: #2e7d32; }}
  .tag-fix {{ background: #fff3e0; color: #e65100; }}
  .tag-improve {{ background: #e3f2fd; color: #1565c0; }}
  .tag-chore {{ background: #f3e5f5; color: #6a1b9a; }}
</style></head><body>
<div class="card">
  <h1>Bock Media Console</h1>
  <p class="lead">Mobile apps for your home music server — play, download, and discover your library anywhere.</p>
  {android_section}
  {ios_section}
  {highlights}
  {release_notes}
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
        tree = _load_playlists_tree()
        playlists = len(tree.getroot().findall('Entry'))
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

    member_filter = (request.args.get('member') or '').strip()
    if not member_filter and (request.args.get('clientId') or '').strip():
        member_filter = member_for_client(request.args.get('clientId').strip()) or ''
    folder_filter = (request.args.get('folder') or '').strip()
    pl_folders = bock_folders.load_folders(PLAYLIST_FOLDERS_PATH)
    assignments = pl_folders.get('assignments') or {}
    pl_meta = _load_playlist_meta()
    household = _load_household() if member_filter else None
    try:
        tree = _load_playlists_tree()
        all_playlists = []
        for entry in tree.getroot().findall('Entry'):
            key = entry.find('Key')
            if key is None:
                continue
            name = xml_text(key, 'Name')
            if search and search not in name.lower():
                continue
            pid = xml_text(key, 'ID')
            if folder_filter == 'root':
                if assignments.get(pid):
                    continue
            elif folder_filter and assignments.get(pid) != folder_filter:
                continue
            meta_entry = pl_meta.get(pid)
            if member_filter and not _playlist_visible_to(meta_entry, member_filter):
                continue
            all_playlists.append(bock_folders.enrich_playlist_item({
                'id': pid,
                'name': name,
                'trackCount': xml_int(key, 'TrackCount'),
                'shuffle': xml_text(key, 'Shuffle') == 'true',
                'loop': xml_text(key, 'Loop') == 'true',
                'createDate': xml_text(key, 'CreateDate'),
                'lastUsed': xml_text(key, 'LastUsed'),
                'source': xml_text(key, 'SourceID'),
                'sourceName': xml_text(key, 'SourceName'),
                'isAudioBook': xml_text(key, 'IsAudioBook') == 'true',
                **_public_playlist_meta(meta_entry, household),
            }, assignments))

        if sort_by == 'trackCount':
            all_playlists.sort(key=lambda x: (x.get('trackCount') or 0, (x.get('name') or '').lower()),
                               reverse=reverse)
        else:
            all_playlists.sort(key=lambda x: (x.get('name') or '').lower(),
                               reverse=reverse)

        total = len(all_playlists)
        start = (page - 1) * limit
        items = all_playlists[start:start + limit]
        # Inline cover art for the returned page (cached by mtime) so tiles render from
        # the list itself — no separate /covers round-trip. Bound disk reads per request;
        # a background thread warms the rest so repeat loads are fully covered and fast.
        _start_playlist_cover_warm()
        inline_budget = _PLAYLIST_COVER_INLINE_BUDGET
        for it in items:
            cached = _playlist_cover_fast(it.get('id'), it.get('source'), compute=False)
            if cached is None and inline_budget > 0:
                cached = _playlist_cover_fast(it.get('id'), it.get('source'), compute=True)
                inline_budget -= 1
            it['artPath'] = cached
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
        tree = _load_playlists_tree()
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
        _save_playlists_tree(tree)
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
        'credentialsConfigured': _credentials_configured(),
        'allowOpenLanApi': _allow_open_lan_api(),
        'allowOpenLanMedia': _allow_open_lan_media(),
    })

# ── Plex sync status (dashboard panel) ───────────────────────────────────────

PLEX_SYNC_LOG = os.path.join(HERE, 'plex-sync.log')
PLEX_SYNC_STATE = os.path.join(
    os.environ.get('OURMEDIA_MUSIC_ROOT', _DEMO_MUSIC_ROOT),
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

_FAVORITES_LOCK = threading.Lock()

def _rated_songs_as_favorites(limit=200, member_id=''):
    """Song ratings (1–5 stars) in legacy favorites list shape."""
    out = []
    for row in bock_ratings.list_ratings(RATINGS_PATH, member_id):
        if row.get('kind') != 'song':
            continue
        out.append({
            'path': row.get('id'),
            'title': row.get('title'),
            'artist': row.get('artist'),
            'album': row.get('album'),
        })
        if len(out) >= limit:
            break
    return out

def _load_favorites(member_id=''):
    rated = _rated_songs_as_favorites(member_id=member_id)
    if rated:
        return rated
    with _FAVORITES_LOCK:
        try:
            with open(FAVORITES_PATH) as f:
                data = json.load(f)
            legacy = data if isinstance(data, list) else []
        except Exception:
            legacy = []
    return legacy

def _save_favorites(items):
    with _FAVORITES_LOCK:
        _atomic_json_write(FAVORITES_PATH, items)

@app.route('/api/favorites', methods=['GET'])
def list_favorites():
    member_id = _ratings_member_from_request()
    return jsonify({'items': _load_favorites(member_id)})

@app.route('/api/favorites', methods=['POST'])
def add_favorite():
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    title, artist, album, _ = track_metadata(path)
    member_id = _ratings_member_from_request()
    try:
        row = bock_ratings.set_rating(
            RATINGS_PATH, 'song', path, 5, _atomic_json_write,
            title=body.get('title') or title,
            artist=body.get('artist') or artist,
            album=body.get('album') or album,
            member_id=member_id,
        )
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    item = {
        'path': path,
        'title': (row or {}).get('title') or title,
        'artist': (row or {}).get('artist') or artist,
        'album': (row or {}).get('album') or album,
    }
    return jsonify({'ok': True, 'item': item})

@app.route('/api/favorites', methods=['DELETE'])
def remove_favorite():
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    member_id = _ratings_member_from_request()
    try:
        bock_ratings.set_rating(RATINGS_PATH, 'song', path, 0, _atomic_json_write,
                                 member_id=member_id)
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    return jsonify({'ok': True})

@app.route('/api/ratings')
def ratings_list():
    member_id = _ratings_member_from_request()
    return jsonify({
        'items': bock_ratings.list_ratings(RATINGS_PATH, member_id),
        'memberId': member_id or None,
    })

@app.route('/api/ratings/lookup')
def ratings_lookup():
    kind = (request.args.get('kind') or '').strip().lower()
    item_id = (request.args.get('id') or '').strip()
    if not kind or not item_id:
        return jsonify({'error': 'kind and id required'}), 400
    member_id = _ratings_member_from_request()
    return jsonify({
        'kind': kind,
        'id': item_id,
        'stars': bock_ratings.get_rating(RATINGS_PATH, kind, item_id, member_id),
        'memberId': member_id or None,
    })

@app.route('/api/ratings', methods=['PUT'])
def ratings_set():
    body = request.get_json(silent=True) or {}
    kind = (body.get('kind') or '').strip().lower()
    item_id = (body.get('id') or '').strip()
    try:
        stars = int(body.get('stars', 0))
    except (TypeError, ValueError):
        return jsonify({'error': 'stars must be 0–5'}), 400
    member_id = _ratings_member_from_request()
    try:
        row = bock_ratings.set_rating(
            RATINGS_PATH, kind, item_id, stars, _atomic_json_write,
            title=body.get('title'), artist=body.get('artist'), album=body.get('album'),
            member_id=member_id,
        )
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    if stars == 0:
        return jsonify({'ok': True, 'stars': 0})
    return jsonify({'ok': True, 'item': row, 'stars': stars})

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
    import bock_search
    return bock_search.library_search_song_match(q, title, album)


@app.route('/api/search')
def library_search():
    import bock_search
    import bock_search_ext
    import bock_resonance

    def _list_devices():
        import alexa_remote
        return alexa_remote.list_devices()

    q = (request.args.get('q') or '').strip()
    limit = min(max(int(request.args.get('limit', 30) or 30), 1), 100)
    preview = min(max(int(request.args.get('preview', 5) or 5), 1), 15)
    section = (request.args.get('section') or '').strip() or None
    source = (request.args.get('source') or '').strip() or None

    payload = bock_search.run_search(
        db_query=db_query,
        db_one=db_one,
        q=q,
        limit=limit,
        preview=preview,
        section=section,
        source=source,
        include_rooms=request.args.get('includeRooms', '1') != '0',
        include_messages=request.args.get('includeMessages') == '1',
        include_resonance=request.args.get('includeResonance', '1') != '0',
        ensure_fts_fn=lambda: bock_search_ext.ensure_fts(get_db_rw, db_query),
        load_playlist_entries_fn=_load_playlist_entries,
        score_playlist_fn=_score_playlist,
        load_smart_playlists_fn=_load_smart_playlists,
        albums_played_fn=_albums_played_flags,
        playlist_paths_fn=_playlist_paths_cached,
        list_devices_fn=_list_devices,
        messages_path=MESSAGES_PATH,
        resonance_mod=bock_resonance,
    )
    return jsonify(payload)

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
    data = decode_token(token) or {}
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
            # Do not call _optimistic_np_skip for next/previous: remote control speaks
            # "ask <skill> to skip/go back", which triggers SkipIntent/BackIntent and
            # _np_skip_next/_np_skip_previous — advancing idx here skips twice (UI shows
            # track+1, device plays track+2).
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
            status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found', 'speaker_group_not_supported') else 500
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
    # Kid-safe: clamp to the room's volume cap so "Alexa, louder" can't exceed it.
    volume, capped = _clamp_volume_for(target, volume)
    try:
        import alexa_remote
        result = alexa_remote.set_volume(target, volume)
        return jsonify({'ok': True, 'capped': capped, **result})
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


def _resolve_rated_playlist(playlist_id, member_id=''):
    """Virtual rated-stars-N playlists have no .m3u — return (name, track paths)."""
    stars = bock_ratings.parse_rated_playlist_id(playlist_id)
    if stars is None:
        return None
    paths = [
        s['id'] for s in bock_ratings.songs_at_stars(RATINGS_PATH, stars, member_id=member_id or '')
        if s.get('id')
    ]
    return bock_ratings.rated_playlist_name(stars), paths


def _register_play_playlist_token(playlist_id, name, source, shuffle=False, tracks=None):
    src = (source or '').strip()
    if not src and playlist_id:
        _, looked = _msp_playlist_by_id(playlist_id)
        src = (looked or '').strip()
    token = _new_playlist_token_id()
    now = time.time()
    with _PLAYLIST_TOKEN_LOCK:
        for k in [k for k, v in _PLAYLIST_TOKENS.items() if v.get('exp', 0) < now]:
            del _PLAYLIST_TOKENS[k]
        entry = {
            'kind': 'playlist',
            'id': str(playlist_id),
            'name': name or '',
            'source': src,
            'shuffle': bool(shuffle),
            'exp': now + _PLAYLIST_TOKEN_TTL,
        }
        if tracks is not None:
            entry['tracks'] = _filter_ignored_queue(
                normalize_track_queue_fast(tracks),
            )[:_QUEUE_TRACK_LIMIT]
        _PLAYLIST_TOKENS[token] = entry
        _save_playlist_tokens()
    n = len(entry.get('tracks') or [])
    print(f'[PLAY TOKEN] registered {token} playlist={name!r} shuffle={shuffle} tracks={n}', flush=True)
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
    inline = token_entry.get('tracks')
    if pid:
        rated = _resolve_rated_playlist(pid)
        if rated:
            rname, _ = rated
            if rname:
                name = rname
        else:
            looked_name, looked_src = _msp_playlist_by_id(pid)
            if looked_name:
                name = looked_name
            if not source and looked_src:
                source = looked_src
    do_shuffle = token_entry.get('shuffle', False) if shuffle is None else shuffle
    if inline:
        queue = _filter_ignored_queue(normalize_track_queue_fast(inline))
        if not queue:
            return alexa_speak("Sorry, I couldn't find any tracks to play.")
        return start_playing(queue, shuffle=do_shuffle, speech=None,
                            playlist=name, playlist_id=pid)
    # App-initiated token plays: skip TTS — outputSpeech + AudioPlayer on Echo
    # Show often drops lifecycle events (no NearlyFinished → no auto-advance).
    return start_playing(None, shuffle=do_shuffle, speech=None,
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
            rated = _resolve_rated_playlist(pid, _ratings_member_from_request())
            if rated:
                rname, paths = rated
                if rname:
                    name = rname
                token = _register_play_playlist_token(pid, name, '', shuffle=shuffle, tracks=paths)
                phrase = f"file token {token}"
            else:
                resolved_name, src_lookup = _msp_playlist_by_id(pid)
                if not src:
                    src = src_lookup
                if resolved_name:
                    name = resolved_name
                token = _register_play_playlist_token(pid, name, src, shuffle=shuffle)
                phrase = f"file token {token}"
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
        data = (decode_token(token) if isinstance(token, str) else (token or {})) or {}
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


def _play_intents_pending(now=None):
    """True while a recent single-target play may still be correlating to a new deviceId."""
    now = now or time.time()
    with _PLAY_INTENT_LOCK:
        if now < _PLAY_GROUP_UNTIL:
            return True
        return any(now - i['ts'] < _PLAY_INTENT_TTL for i in _PLAY_INTENTS)


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
    """Bind a freshly-seen (auto-named) `new_device_id` to a pending play intent.

    Intents are consumed FIFO so back-to-back plays on different rooms each get
    their own primary deviceId instead of folding onto whoever was active last.
    Returns True if it bound."""
    if not new_device_id or new_device_id == 'default':
        return False
    now = time.time()
    with _PLAY_INTENT_LOCK:
        if now < _PLAY_GROUP_UNTIL:
            return False
        pending = sorted(
            [i for i in _PLAY_INTENTS if now - i['ts'] < _PLAY_INTENT_TTL],
            key=lambda i: i['ts'],
        )
        if not pending:
            return False
        intent = pending[0]
        _PLAY_INTENTS.remove(intent)
    name = intent['name']
    serial = intent.get('serial')
    store = _load_devices()
    ent = store.get(new_device_id) or {}
    if ent.get('aliasOf'):
        # A wrong fingerprint auto-merge can land here before we correlate — peel
        # it so this PlaybackStarted can bind to the room we actually commanded.
        if not ent.get('autoMerged'):
            return False
        prim_name = ((store.get(ent['aliasOf']) or {}).get('name') or '').strip().lower()
        if prim_name == name.strip().lower():
            return False
        store.pop(new_device_id, None)
        ent = {}

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


def _consume_play_intent_for_queue():
    """Pop the oldest pending play intent to label an MSP queue's room.

    MSP playback events carry no deviceId, so we bind the queue to whichever
    room the UI/voice play just targeted (FIFO, same order as correlation).
    """
    now = time.time()
    with _PLAY_INTENT_LOCK:
        if now < _PLAY_GROUP_UNTIL:
            return None
        pending = sorted(
            [i for i in _PLAY_INTENTS if now - i['ts'] < _PLAY_INTENT_TTL],
            key=lambda i: i['ts'],
        )
        if not pending:
            return None
        intent = pending[0]
        _PLAY_INTENTS.remove(intent)
        return dict(intent)


def _attach_queue_play_target(qid):
    """Persist target room on a queue so MSP Now Playing can show Office Show, etc."""
    intent = _consume_play_intent_for_queue()
    if not intent or not qid:
        return
    with _QUEUES_LOCK:
        queues = _load_queues()
        entry = queues.get(qid)
        if not entry:
            return
        if intent.get('serial'):
            entry['target_serial'] = intent['serial']
        if intent.get('name'):
            entry['target_name'] = intent['name']
        _save_queues(queues)


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
    playlist_source = None
    if kind == 'playlist' and pid:
        member_id = _ratings_member_from_request()
        rated = _resolve_rated_playlist(pid, member_id)
        if rated:
            resolved_name, paths = rated
            if resolved_name:
                name = resolved_name
            if not paths:
                return jsonify({'error': 'empty_playlist', 'code': 'empty_playlist'}), 400
        else:
            resolved_name, playlist_source = _msp_playlist_by_id(pid)
            if resolved_name:
                name = resolved_name
            elif not name:
                name = resolved_name or ''
    elif not name:
        return jsonify({'error': 'name or id required'}), 400
    if not name:
        return jsonify({'error': 'name or id required'}), 400
    text = _build_play_text(
        kind, name, shuffle, artist=artist, path=fpath,
        playlist_id=pid if kind == 'playlist' else None,
        playlist_source=playlist_source,
    )
    print(f'[PLAY DEVICE] targets={device!r} kind={kind} name={name!r} text={text!r}', flush=True)
    try:
        targets = _expand_play_targets(device)
    except ValueError as e:
        return jsonify({'error': str(e), 'code': str(e)}), 400
    # Kid-safe enforcement: block content not permitted in a safe room.
    blocked = []
    for serial, member_name in targets:
        ok, reason = _policy_check_play(_policy_for(serial), kind=kind,
                                        playlist_id=(pid if kind == 'playlist' else None),
                                        path=fpath)
        if not ok:
            blocked.append({'device': member_name, 'reason': reason})
    if blocked:
        return jsonify({'error': 'kid_safe_blocked', 'code': 'kid_safe_blocked',
                        'blocked': blocked}), 403
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
            status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found', 'speaker_group_not_supported') else 500
            return jsonify({'error': code, 'code': code, 'errors': errors}), status
        label = results[0].get('device') if len(results) == 1 else f'{len(results)} devices'
        return jsonify({'ok': True, 'device': label, 'count': len(results), 'errors': errors})
    except ImportError:
        return jsonify({'error': 'alexapy not installed', 'code': 'not_installed'}), 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated', 'device_not_found', 'speaker_group_not_supported') else 500
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

# ── Silent device discovery (bind rotated skill deviceIds without audible play) ─
# Alexa skill events only carry an opaque deviceId that Amazon occasionally rotates.
# Automations and Play-on-device use the stable hardware serial via alexapy. A
# scheduled silent ping (volume 0 + ~250ms silent track + immediate stop) triggers
# PlaybackStarted so play-intent correlation can fold the new id onto the room.
_DISCOVER = {'running': False, 'total': 0, 'done': 0, 'current': '',
             'skipped': [], 'pinged': [], 'errors': [], 'lastRun': 0}
_DISCOVER_LOCK = threading.Lock()
_DISCOVER_AUDIO_FAMILIES = {'ECHO', 'ROOK', 'KNIGHT'}
_DISCOVERY_STATE_PATH = os.path.join(DATA_DIR, 'device_discovery.json')
_DISCOVERY_STATE_LOCK = threading.Lock()
_device_discovery_scheduler_started = False


def _device_discovery_cfg():
    raw = load_config().get('deviceDiscovery') or {}
    return {
        'enabled': bool(raw.get('enabled', True)),
        'intervalHours': max(1.0, float(raw.get('intervalHours') or 6)),
        'staleDays': max(0.0, float(raw.get('staleDays') or 7)),
        'dwellSeconds': max(3.0, min(10.0, float(raw.get('dwellSeconds') or 5))),
        'onlyStale': raw.get('onlyStale', True),
    }


def _load_discovery_state():
    with _DISCOVERY_STATE_LOCK:
        try:
            with open(_DISCOVERY_STATE_PATH) as f:
                return json.load(f) or {}
        except Exception:
            return {}


def _save_discovery_state(state):
    with _DISCOVERY_STATE_LOCK:
        os.makedirs(DATA_DIR, exist_ok=True)
        _atomic_json_write(_DISCOVERY_STATE_PATH, state)


def _mark_discovery_serial(serial):
    if not serial:
        return
    state = _load_discovery_state()
    serials = state.setdefault('serials', {})
    serials[serial] = time.time()
    _save_discovery_state(state)


def _ensure_silent_correlation_path():
    """Return a streamable silent mp3 under MUSIC_ROOT (copied from assets/)."""
    dest_dir = os.path.join(MUSIC_ROOT, '.bock')
    dest = os.path.join(dest_dir, 'silent-correlation.mp3')
    if os.path.isfile(dest):
        return dest
    os.makedirs(dest_dir, exist_ok=True)
    bundled = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           'assets', 'silent-correlation.mp3')
    if os.path.isfile(bundled):
        shutil.copy2(bundled, dest)
        return dest
    raise RuntimeError('silent_correlation_track_missing')


def _build_silent_correlation_text():
    path = _ensure_silent_correlation_path()
    token = _register_play_file_token(path, 'Silent ping', artist='')
    alias = _alexa_alias()
    return f"ask {alias} to start file token {token}"


def _device_needs_discovery(serial, store, stale_days, only_stale):
    if not serial:
        return False
    if not only_stale:
        return True
    if not _primary_by_serial(serial, store):
        return True
    if stale_days <= 0:
        return True
    last = (_load_discovery_state().get('serials') or {}).get(serial) or 0
    return (time.time() - last) >= stale_days * 86400


def _silent_ping_device(serial, name, alias, dwell):
    """Mute, play a silent file token, stop, restore volume — for correlation."""
    import alexa_remote
    text = _build_silent_correlation_text()
    prev_vol = None
    try:
        prev_vol = alexa_remote.get_volume(serial)
    except Exception:
        pass
    try:
        alexa_remote.set_volume(serial, 0)
    except Exception as e:
        print(f'[DEVICE DISCOVER] mute failed for {name!r}: {e}', flush=True)
    _record_play_intent([(serial, name)])
    _mark_test_serial(serial)
    try:
        alexa_remote.play_text(serial, text)
    except Exception:
        if prev_vol is not None:
            try:
                alexa_remote.set_volume(serial, prev_vol)
            except Exception:
                pass
        raise
    time.sleep(dwell)
    try:
        alexa_remote.device_control(serial, 'stop', alias)
    except Exception:
        pass
    if prev_vol is not None:
        try:
            alexa_remote.set_volume(serial, prev_vol)
        except Exception as e:
            print(f'[DEVICE DISCOVER] unmute failed for {name!r}: {e}', flush=True)
    _mark_discovery_serial(serial)


def _discover_worker(devices, alias, dwell):
    import alexa_remote  # noqa: F401
    try:
        for d in devices:
            serial, name = d['serial'], d['name']
            with _DISCOVER_LOCK:
                _DISCOVER['current'] = name
            try:
                _silent_ping_device(serial, name, alias, dwell)
            except Exception as e:
                with _DISCOVER_LOCK:
                    _DISCOVER['errors'].append(f'{name}: {e}')
                    _DISCOVER['done'] += 1
                continue
            with _DISCOVER_LOCK:
                _DISCOVER['pinged'].append(name)
                _DISCOVER['done'] += 1
            time.sleep(1.0)
    finally:
        state = _load_discovery_state()
        state['lastRun'] = time.time()
        _save_discovery_state(state)
        with _DISCOVER_LOCK:
            _DISCOVER['running'] = False
            _DISCOVER['current'] = ''
            _DISCOVER['lastRun'] = state['lastRun']


def _start_device_discovery(*, force=False, stale_days=None, dwell=None, only_stale=None):
    """Return (payload_dict, http_status) for API handlers and scheduler."""
    cfg = _device_discovery_cfg()
    if not cfg['enabled'] and not force:
        return {'error': 'disabled', 'code': 'disabled'}, 400
    with _IDENTIFY_LOCK:
        if _IDENTIFY['running']:
            return {'error': 'identify_running', 'code': 'identify_running'}, 409
    with _DISCOVER_LOCK:
        if _DISCOVER['running']:
            return {'error': 'already_running', 'code': 'already_running', **_DISCOVER}, 409
    try:
        import alexa_remote
        devs = alexa_remote.list_devices() or []
    except ImportError:
        return {'error': 'not_installed', 'code': 'not_installed'}, 503
    except Exception as e:
        code = str(e)
        status = 400 if code in ('not_configured', 'not_authenticated') else 500
        return {'error': code, 'code': code}, status
    stale = cfg['staleDays'] if stale_days is None else max(0.0, float(stale_days))
    only = cfg['onlyStale'] if only_stale is None else bool(only_stale)
    dwell_sec = cfg['dwellSeconds'] if dwell is None else max(3.0, min(10.0, float(dwell)))
    store = _load_devices()
    online = [d for d in devs
              if d.get('online') and d.get('family') in _DISCOVER_AUDIO_FAMILIES]
    targets, skipped = [], []
    for d in online:
        serial = d.get('serial')
        if _device_needs_discovery(serial, store, stale, only):
            targets.append(d)
        else:
            skipped.append(d.get('name') or serial or '?')
    if not targets:
        return {'ok': True, 'total': 0, 'skipped': skipped, 'message': 'nothing_to_do'}, 200
    alias = _alexa_alias()
    with _DISCOVER_LOCK:
        _DISCOVER.update({'running': True, 'total': len(targets), 'done': 0,
                          'current': '', 'skipped': skipped, 'pinged': [], 'errors': []})
    threading.Thread(target=_discover_worker,
                     args=(targets, alias, dwell_sec),
                     daemon=True, name='device-discover').start()
    eta = int(len(targets) * (dwell_sec + 1.5))
    return {'ok': True, 'total': len(targets), 'skipped': skipped,
            'dwellSeconds': dwell_sec, 'etaSeconds': eta}, 202


def _tick_device_discovery(force=False):
    cfg = _device_discovery_cfg()
    if not cfg['enabled'] and not force:
        return
    if not force:
        state = _load_discovery_state()
        if time.time() - state.get('lastRun', 0) < cfg['intervalHours'] * 3600:
            return
    payload, status = _start_device_discovery(force=force)
    if status == 202:
        print(f"[DEVICE DISCOVER] started sweep: {payload.get('total')} device(s)", flush=True)
    elif status == 200 and payload.get('total') == 0:
        pass  # nothing due
    elif status >= 400 and payload.get('error') not in ('already_running', 'identify_running'):
        print(f"[DEVICE DISCOVER] skip: {payload.get('error')}", flush=True)


def _device_discovery_scheduler_loop():
    while True:
        try:
            _tick_device_discovery()
        except Exception as e:
            print(f'[DEVICE DISCOVER] scheduler error: {e}', flush=True)
        time.sleep(300)


def _start_device_discovery_scheduler():
    global _device_discovery_scheduler_started
    if _device_discovery_scheduler_started:
        return
    _device_discovery_scheduler_started = True
    t = threading.Thread(target=_device_discovery_scheduler_loop,
                         daemon=True, name='device-discovery-scheduler')
    t.start()


@app.route('/api/devices/discover', methods=['POST'])
def discover_devices():
    body = request.get_json(silent=True) or {}
    force = bool(body.get('force'))
    stale_days = body.get('staleDays')
    dwell = body.get('dwell') or body.get('dwellSeconds')
    only_stale = body.get('onlyStale')
    if only_stale is None and 'onlyStale' not in body and force:
        only_stale = False
    payload, status = _start_device_discovery(
        force=force or bool(body.get('all')),
        stale_days=stale_days,
        dwell=dwell,
        only_stale=only_stale,
    )
    return jsonify(payload), status


@app.route('/api/devices/discover/status')
def discover_status():
    with _DISCOVER_LOCK:
        out = dict(_DISCOVER)
    out['state'] = _load_discovery_state()
    out['config'] = _device_discovery_cfg()
    return jsonify(out)

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
    elif playlist_id:
        resolved, _ = _msp_playlist_by_id(playlist_id)
        if resolved:
            playlist_name = resolved
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
    pid = (auto.get('playlistId') or '').strip()
    pl_name = auto.get('playlistName') or ''
    shuffle = bool(auto.get('shuffle'))
    src = None
    if pid:
        resolved_name, src = _msp_playlist_by_id(pid)
        if resolved_name:
            pl_name = resolved_name
        if src:
            _playlist_paths_cached(pid, src)
    text = _build_play_text(
        'playlist', pl_name, shuffle,
        playlist_id=pid or None,
        playlist_source=src,
    )
    print(f'[AUTOMATION] {auto.get("name")!r} device={auto.get("deviceName")!r} playlist={pl_name!r} text={text!r}', flush=True)
    import alexa_remote
    targets = _expand_play_targets(auto['device'])
    _record_play_intent(
        targets,
        playlist=pl_name,
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
    sort = (request.args.get('sort') or 'name').strip().lower()
    order = (request.args.get('order') or 'asc').strip().lower()
    desc = order == 'desc'
    offset = (page - 1) * limit

    where = 'artist IS NOT NULL AND artist != ""'
    params = []
    if search:
        where += ' AND artist LIKE ?'
        params.append(f'%{search}%')

    if sort == 'tracks':
        order_sql = f'track_count {"DESC" if desc else "ASC"}, artist COLLATE NOCASE ASC'
    else:
        order_sql = f'artist COLLATE NOCASE {"DESC" if desc else "ASC"}'

    rows = db_query(
        f'SELECT artist, COUNT(*) as track_count, COUNT(DISTINCT album) as album_count, '
        f'MIN(CASE WHEN path IS NOT NULL AND path != "" THEN path END) as art_path '
        f'FROM songs_cache WHERE {where} GROUP BY artist ORDER BY {order_sql} LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    total_row = db_one(
        f'SELECT COUNT(DISTINCT artist) as total FROM songs_cache WHERE {where}',
        params
    )
    for row in rows:
        name = (row.get('artist') or '').strip()
        cached = bock_artist_art.cached_portrait_rel_path(name, ARTWORK_CACHE)
        if cached:
            row['art_path'] = cached
    return jsonify({'items': rows, 'total': total_row.get('total', 0)})


@app.route('/api/artist-portrait')
def artist_portrait():
    """Fetch/cache an artist portrait (Deezer → iTunes → library; all free)."""
    artist = (request.args.get('artist') or '').strip()
    if not artist:
        return jsonify({'error': 'artist required'}), 400
    result = bock_artist_art.resolve_portrait(
        artist,
        ARTWORK_CACHE,
        load_config,
        db_query=db_query,
        find_artwork_fn=find_artwork,
    )
    if not result:
        return jsonify({'artist': artist, 'art_path': None}), 404
    return jsonify(result)

# ── API: Albums ──────────────────────────────────────────────────────────────

@app.route('/api/albums')
def albums():
    if not _albums_agg_exists():
        ensure_albums_agg_async()
        return jsonify({'items': [], 'total': 0, 'status': 'building'}), 503

    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 50))
    search = request.args.get('search', '')
    artist = request.args.get('artist', '')
    sort = (request.args.get('sort') or 'name').strip().lower()
    order = (request.args.get('order') or 'asc').strip().lower()
    unplayed_only = request.args.get('unplayed') == '1'
    desc = order == 'desc'
    offset = (page - 1) * limit

    conditions = ['1=1']
    params = []
    if search:
        conditions.append('(album LIKE ? OR artist LIKE ?)')
        params += [f'%{search}%', f'%{search}%']
    if artist:
        conditions.append('artist = ?')
        params.append(artist)

    where = ' AND '.join(conditions)
    has_first_seen = bool(db_one(
        "SELECT 1 FROM pragma_table_info('albums_agg') WHERE name='first_seen_at'"
    ))
    if sort == 'year':
        order_sql = f'year {"DESC" if desc else "ASC"}, album COLLATE NOCASE ASC'
    elif sort == 'tracks':
        order_sql = f'track_count {"DESC" if desc else "ASC"}, album COLLATE NOCASE ASC'
    elif sort == 'added' and has_first_seen:
        order_sql = f'first_seen_at {"DESC" if desc else "ASC"}, album COLLATE NOCASE ASC'
    else:
        order_sql = f'album COLLATE NOCASE {"DESC" if desc else "ASC"}'

    select_cols = 'album, artist, track_count, year, art_path'
    if has_first_seen:
        select_cols += ', first_seen_at'

    rows = db_query(
        f'SELECT {select_cols} FROM albums_agg '
        f'WHERE {where} ORDER BY {order_sql} LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    played = _albums_played_flags(rows)
    if unplayed_only:
        rows = [
            r for r in rows
            if not played.get((r.get('album'), r.get('artist') or ''), False)
        ]
    member_id = _ratings_member_from_request()
    star_map = bock_library_health.album_star_averages(RATINGS_PATH, member_id, db_query)
    for row in rows:
        key = (row['album'], row.get('artist') or '')
        row['played'] = played.get(key, False)
        stats = star_map.get(key)
        if stats:
            row['avg_stars'] = stats['avgStars']
            row['rated_count'] = stats['ratedCount']
        else:
            row['avg_stars'] = None
            row['rated_count'] = 0
    total_row = db_one(f'SELECT COUNT(*) as total FROM albums_agg WHERE {where}', params)
    return jsonify({'items': rows, 'total': total_row.get('total', 0)})


@app.route('/api/genres')
def genres_list():
    limit = min(max(int(request.args.get('limit', 20) or 20), 1), 500)
    sort = (request.args.get('sort') or 'tracks').strip().lower()
    order = (request.args.get('order') or 'desc').strip().lower()
    search = (request.args.get('search') or '').strip()
    desc = order == 'desc'
    params = []
    where = 'genre IS NOT NULL AND genre != ""'
    if search:
        where += ' AND genre LIKE ?'
        params.append(f'%{search}%')
    if sort == 'name':
        order_sql = f'genre COLLATE NOCASE {"DESC" if desc else "ASC"}'
    else:
        order_sql = f'track_count {"DESC" if desc else "ASC"}, genre COLLATE NOCASE ASC'
    rows = db_query(
        f'SELECT genre, COUNT(*) as track_count FROM songs_cache '
        f'WHERE {where} '
        f'GROUP BY genre ORDER BY {order_sql} LIMIT ?',
        params + [limit],
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


@app.route('/api/library/health')
def library_health():
    """Metadata coverage, Picard attention folders, duplicate artist spellings."""
    limit = min(max(int(request.args.get('attentionLimit', 5) or 5), 1), 20)
    dup_limit = min(max(int(request.args.get('duplicateLimit', 10) or 10), 1), 50)
    return jsonify(bock_library_health.library_health(
        DB_PATH, db_query, attention_limit=limit, duplicate_limit=dup_limit,
    ))


@app.route('/api/library/artists/merge', methods=['POST'])
def library_merge_artists():
    body = request.get_json(silent=True) or {}
    to_name = (body.get('to') or '').strip()
    from_raw = body.get('from') or body.get('fromNames') or []
    if isinstance(from_raw, str):
        from_raw = [from_raw]
    if not isinstance(from_raw, list):
        return jsonify({'error': 'from must be an array'}), 400
    try:
        out = bock_library_health.merge_artists(db_execute, from_raw, to_name)
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    if out.get('rowsUpdated', 0) > 0:
        threading.Thread(target=refresh_albums_agg, daemon=True, name='albums-agg-merge').start()
    return jsonify(out)

# ── API: Songs ───────────────────────────────────────────────────────────────

@app.route('/api/track_meta')
def track_meta():
    """Lightweight per-file metadata (incl. release year) by path.

    Used by the mobile app to show the year for locally-played tracks, where the
    now-playing item is synthesized on-device and has no year.
    """
    path = (request.args.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    row = db_one(
        'SELECT title, artist, album, genre, year FROM songs_cache WHERE path = ?',
        [path],
    ) or {}
    return jsonify({
        'path':   path,
        'title':  row.get('title'),
        'artist': row.get('artist'),
        'album':  row.get('album'),
        'genre':  row.get('genre'),
        'year':   _year_for_path(path),
    })


# ── Lyrics (sidecar .lrc → disk cache → LRCLIB → embedded plain) ──────────────

_LRC_TIMESTAMP = re.compile(r'\[(\d{1,2}):(\d{2}(?:\.\d{1,3})?)\]')
_LRC_META = re.compile(r'^\[[a-z]+:', re.I)
_LRC_TITLE_NOISE = re.compile(
    r'[\(\[]?\s*(?:remaster(?:ed)?(?:\s*\d{4})?|live(?:\s+at|\s+from|\s+version)?|'
    r'radio\s+edit|single\s+version|album\s+version|extended|mono|stereo|'
    r'\d+\s*-?\s*bit|digital\s+master)[\)\]]?',
    re.I,
)
_LYRICS_CACHE: dict = {}
_LYRICS_TTL = 3600
LYRICS_CACHE_DIR = os.path.join(DATA_DIR, 'lyrics_cache')


def _looks_like_lrc(text):
    return bool(text and _LRC_TIMESTAMP.search(text))


def _parse_lrc(text):
    """Parse LRC into [{timeMs, text}, ...] plus a plain-text fallback."""
    lines = []
    plain = []
    for raw in (text or '').splitlines():
        s = raw.strip()
        if not s or _LRC_META.match(s):
            continue
        matches = list(_LRC_TIMESTAMP.finditer(s))
        if matches:
            lyric = _LRC_TIMESTAMP.sub('', s).strip()
            if not lyric:
                continue
            for m in matches:
                mins = int(m.group(1))
                secs = float(m.group(2))
                lines.append({'timeMs': int((mins * 60 + secs) * 1000), 'text': lyric})
            plain.append(lyric)
        elif not s.startswith('['):
            plain.append(s)
    lines.sort(key=lambda x: x['timeMs'])
    return lines, '\n'.join(plain).strip()


def _sidecar_lyrics(audio_path):
    base, _ = os.path.splitext(audio_path)
    for ext in ('.lrc', '.LRC', '.txt', '.TXT'):
        p = base + ext
        if os.path.isfile(p):
            try:
                with open(p, encoding='utf-8', errors='replace') as fh:
                    return fh.read()
            except Exception:
                pass
    return None


def _embedded_lyrics(audio_path):
    try:
        from mutagen import File as MutaFile
        f = MutaFile(audio_path)
        if f is None or not getattr(f, 'tags', None):
            return None
        tags = f.tags
        for key in ('lyrics', 'LYRICS', 'unsynced lyrics', 'UNSYNCEDLYRICS', '©lyr'):
            if key in tags:
                val = tags[key]
                text = val[0] if isinstance(val, (list, tuple)) else str(val)
                if str(text).strip():
                    return str(text)
        if hasattr(tags, 'getall'):
            for frame in tags.getall('USLT') or []:
                text = getattr(frame, 'text', '') or ''
                if str(text).strip():
                    return str(text)
    except Exception as e:
        print(f'embedded lyrics error {audio_path}: {e}', flush=True)
    return None


def _lrclib_http_json(url, timeout=18, retries=3):
    from urllib.error import HTTPError, URLError
    from urllib.request import Request, urlopen
    last_err = None
    for attempt in range(retries):
        try:
            req = Request(url, headers={'User-Agent': 'BockMedia/1.0 (https://github.com/ourMedia)'})
            with urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode('utf-8', errors='replace'))
        except HTTPError as e:
            if e.code in (404, 400):
                return None
            last_err = e
            print(f'lrclib http {e.code} {url}: {e}', flush=True)
        except (URLError, TimeoutError, OSError) as e:
            last_err = e
            if attempt + 1 < retries:
                time.sleep(0.6 * (attempt + 1))
                continue
            print(f'lrclib error {url}: {e}', flush=True)
        except Exception as e:
            last_err = e
            print(f'lrclib error {url}: {e}', flush=True)
            break
    if last_err:
        pass
    return None


def _normalize_lrclib_query(title, artist):
    title = re.sub(r'\s+', ' ', (title or '').strip())
    artist = re.sub(r'\s+', ' ', (artist or '').strip())
    if artist:
        artist = re.split(r'\s+(?:feat\.?|ft\.?|featuring|with)\s+', artist, maxsplit=1, flags=re.I)[0]
        artist = artist.split(',')[0].strip()
    title = _LRC_TITLE_NOISE.sub('', title).strip(' -')
    return title, artist


def _lrclib_has_synced(item):
    text = (item.get('syncedLyrics') or '').strip()
    return bool(text and _looks_like_lrc(text))


def _lrclib_duration_delta(item, duration_sec):
    try:
        return abs(float(item.get('duration') or 0) - float(duration_sec))
    except (TypeError, ValueError):
        return 9999.0


def _lrclib_pick_best(results, duration_sec=None):
    if not results:
        return None
    items = [r for r in results if isinstance(r, dict)]
    if not items:
        return None
    synced = [r for r in items if _lrclib_has_synced(r)]
    pool = synced or items
    if duration_sec and duration_sec > 0:
        best = min(pool, key=lambda r: _lrclib_duration_delta(r, duration_sec))
        if synced or _lrclib_duration_delta(best, duration_sec) <= 12:
            return best
    return pool[0]


def _fetch_lrclib(title, artist, album, duration_sec):
    from urllib.parse import urlencode
    title = (title or '').strip()
    artist = (artist or '').strip()
    album = (album or '').strip()
    if not title:
        return None
    norm_title, norm_artist = _normalize_lrclib_query(title, artist)
    cache_key = (norm_title, norm_artist, album, int(duration_sec or 0))
    cached = _LYRICS_CACHE.get(cache_key)
    if cached and (time.time() - cached['ts']) < _LYRICS_TTL:
        return cached['data']

    attempts = []
    if norm_artist and duration_sec and duration_sec > 0:
        attempts.append({
            'track_name': norm_title,
            'artist_name': norm_artist,
            'album_name': album or 'Unknown Album',
            'duration': int(duration_sec),
        })
    if artist and duration_sec and duration_sec > 0 and (norm_title != title or norm_artist != artist):
        attempts.append({
            'track_name': title,
            'artist_name': artist,
            'album_name': album or 'Unknown Album',
            'duration': int(duration_sec),
        })
    if norm_artist:
        attempts.append({'track_name': norm_title, 'artist_name': norm_artist})
    if artist and artist != norm_artist:
        attempts.append({'track_name': title, 'artist_name': artist})
    attempts.append({'track_name': norm_title or title})

    data = None
    seen_urls = set()
    for params in attempts:
        url = 'https://lrclib.net/api/get?' + urlencode(params)
        if url in seen_urls:
            continue
        seen_urls.add(url)
        hit = _lrclib_http_json(url)
        if hit and (_lrclib_has_synced(hit) or hit.get('plainLyrics')):
            data = hit
            if _lrclib_has_synced(hit):
                break

    if not data or not _lrclib_has_synced(data):
        for params in attempts[:4]:
            url = 'https://lrclib.net/api/search?' + urlencode(params)
            if url in seen_urls:
                continue
            seen_urls.add(url)
            results = _lrclib_http_json(url) or []
            if isinstance(results, list):
                pick = _lrclib_pick_best(results, duration_sec)
                if pick and (_lrclib_has_synced(pick) or not data):
                    data = pick
                    if _lrclib_has_synced(pick):
                        break

    if data:
        _LYRICS_CACHE[cache_key] = {'data': data, 'ts': time.time()}
    return data


def _lyrics_from_lrclib(remote):
    if not remote:
        return None
    synced_text = (remote.get('syncedLyrics') or '').strip()
    plain_text = (remote.get('plainLyrics') or '').strip()
    if synced_text and _looks_like_lrc(synced_text):
        lines, plain = _parse_lrc(synced_text)
        return {
            'synced': bool(lines),
            'lines': lines,
            'plain': plain or plain_text or synced_text,
            'source': 'lrclib',
        }
    if plain_text:
        return {'synced': False, 'lines': [], 'plain': plain_text, 'source': 'lrclib'}
    return None


def _lyrics_from_raw(raw, source):
    if not raw or not str(raw).strip():
        return None
    text = str(raw).strip()
    if _looks_like_lrc(text):
        lines, plain = _parse_lrc(text)
        return {
            'synced': bool(lines),
            'lines': lines,
            'plain': plain or text,
            'source': source,
        }
    return {'synced': False, 'lines': [], 'plain': text, 'source': source}


def _estimate_synced_lines(plain_text, duration_sec):
    """Evenly space plain lyric lines across the track so the karaoke UI always works."""
    raw_lines = [ln.strip() for ln in (plain_text or '').splitlines() if ln.strip()]
    if not raw_lines:
        return []
    if len(raw_lines) == 1 and len(raw_lines[0]) > 120:
        raw_lines = [s.strip() for s in re.split(r'(?<=[.!?])\s+', raw_lines[0]) if s.strip()]
    if not raw_lines or not duration_sec or duration_sec <= 0:
        return []
    duration_ms = int(duration_sec) * 1000
    lead_ms = min(int(duration_ms * 0.04), 8000)
    tail_ms = min(int(duration_ms * 0.06), 12000)
    usable = max(duration_ms - lead_ms - tail_ms, duration_ms // 2)
    step = max(usable // max(len(raw_lines), 1), 1200)
    return [
        {'timeMs': lead_ms + (idx * step), 'text': line}
        for idx, line in enumerate(raw_lines)
    ]


def _lyrics_finalize(payload, duration_sec):
    """Ensure every lyrics payload with text uses the synced karaoke shape when possible."""
    if not payload:
        return payload
    if payload.get('lines'):
        return payload
    plain = (payload.get('plain') or '').strip()
    lines = _estimate_synced_lines(plain, duration_sec)
    if not lines:
        return payload
    out = dict(payload)
    out['lines'] = lines
    out['synced'] = True
    out['estimated'] = True
    return out


def _lyrics_quality(payload):
    """Higher = better. Real LRC beats estimated spacing beats plain block."""
    if not payload:
        return -1
    if payload.get('lines'):
        if payload.get('estimated'):
            return 2
        return 3
    if (payload.get('plain') or '').strip():
        return 1
    return 0


def _lyrics_pick_best(*candidates):
    best = None
    best_q = -1
    for item in candidates:
        if not item:
            continue
        q = _lyrics_quality(item)
        if q > best_q:
            best = item
            best_q = q
    return best


def _lyrics_cache_file(path):
    key = hashlib.sha256((path or '').encode('utf-8')).hexdigest()
    return os.path.join(LYRICS_CACHE_DIR, f'{key}.json')


def _lyrics_cache_read(path):
    if not path:
        return None
    fp = _lyrics_cache_file(path)
    if not os.path.isfile(fp):
        return None
    try:
        with open(fp, encoding='utf-8') as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            return None
        if data.get('path') and data.get('path') != path:
            return None
        return data
    except Exception as e:
        print(f'lyrics cache read {fp}: {e}', flush=True)
        return None


def _lyrics_cache_write(path, payload):
    if not path or not payload:
        return
    try:
        os.makedirs(LYRICS_CACHE_DIR, exist_ok=True)
        record = {
            'path': path,
            'synced': bool(payload.get('synced')),
            'lines': payload.get('lines') or [],
            'plain': payload.get('plain') or '',
            'source': payload.get('source'),
            'estimated': bool(payload.get('estimated')),
            'fetchedAt': int(time.time()),
        }
        tmp = _lyrics_cache_file(path) + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as fh:
            json.dump(record, fh, ensure_ascii=False)
        os.replace(tmp, _lyrics_cache_file(path))
    except Exception as e:
        print(f'lyrics cache write {path}: {e}', flush=True)


def _lyrics_track_meta(path, duration_sec=None, title=None, artist=None, album=None):
    row = {}
    if path:
        row = db_one(
            'SELECT title, artist, album, duration_seconds FROM songs_cache WHERE path = ?',
            [path],
        ) or {}
    fname = os.path.splitext(os.path.basename(path or ''))[0]
    title = (title or row.get('title') or fname).strip()
    artist = (artist or row.get('artist') or '').strip()
    album = (album or row.get('album') or '').strip()
    dur = duration_sec if duration_sec is not None else row.get('duration_seconds')
    try:
        dur = int(float(dur)) if dur else None
    except (TypeError, ValueError):
        dur = None
    if not dur and path:
        ms = _duration_ms_for_path(path)
        if ms > 0:
            dur = max(ms // 1000, 1)
    return title, artist, album, dur


def _lyrics_payload(path, duration_sec=None, title=None, artist=None, album=None):
    """Resolve lyrics with a single karaoke shape; prefer real LRC, estimate plain as fallback."""
    title, artist, album, dur = _lyrics_track_meta(path, duration_sec, title, artist, album)

    sidecar = _lyrics_from_raw(_sidecar_lyrics(path), 'lrc') if path and os.path.isfile(path) else None
    cached = _lyrics_cache_read(path)
    has_real_sync = lambda p: p and p.get('lines') and not p.get('estimated')

    lrclib = None
    if not has_real_sync(sidecar) and not has_real_sync(cached):
        lrclib = _lyrics_from_lrclib(_fetch_lrclib(title, artist, album, dur))

    embedded = None
    if path and os.path.isfile(path):
        embedded = _lyrics_from_raw(_embedded_lyrics(path), 'embedded')

    best = _lyrics_pick_best(sidecar, lrclib, cached, embedded)
    result = _lyrics_finalize(best, dur) if best else None
    if result and result.get('lines'):
        prev = _lyrics_finalize(cached, dur) if cached else None
        if _lyrics_quality(result) >= _lyrics_quality(prev):
            _lyrics_cache_write(path, result)
        return result

    if cached:
        upgraded = _lyrics_finalize(cached, dur)
        if upgraded.get('lines'):
            if _lyrics_quality(upgraded) > _lyrics_quality(cached):
                _lyrics_cache_write(path, upgraded)
            return upgraded

    return {'synced': False, 'lines': [], 'plain': '', 'source': None}


def _lyrics_cache_has_karaoke(path):
    cached = _lyrics_cache_read(path)
    return bool(cached and cached.get('lines'))


def prefetch_lyrics_for_path(path, duration_sec=None, title=None, artist=None, album=None, force=False):
    """Fetch and persist lyrics for one track; returns payload and whether cache changed."""
    if force and path:
        fp = _lyrics_cache_file(path)
        if os.path.isfile(fp):
            try:
                os.remove(fp)
            except OSError:
                pass
    before = _lyrics_cache_read(path)
    payload = _lyrics_payload(path, duration_sec, title=title, artist=artist, album=album)
    after = _lyrics_cache_read(path)
    changed = after != before
    return payload, changed


@app.route('/api/lyrics')
def lyrics():
    """Lyrics for a track: sidecar .lrc, disk cache, LRCLIB, then embedded."""
    path = (request.args.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    duration = request.args.get('duration')
    duration_sec = None
    if duration:
        try:
            duration_sec = int(float(duration))
        except (TypeError, ValueError):
            pass
    title = (request.args.get('title') or '').strip() or None
    artist = (request.args.get('artist') or '').strip() or None
    album = (request.args.get('album') or '').strip() or None
    return jsonify(_lyrics_payload(path, duration_sec, title=title, artist=artist, album=album))


MUSIC_VIDEO_CACHE_PATH = os.path.join(DATA_DIR, 'music_video_cache.json')
_MUSIC_VIDEO_CACHE_LOCK = threading.Lock()


def _music_video_cache_load():
    try:
        with open(MUSIC_VIDEO_CACHE_PATH, encoding='utf-8') as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return {}


def _music_video_cache_save(data):
    try:
        tmp = MUSIC_VIDEO_CACHE_PATH + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as fh:
            json.dump(data, fh)
        os.replace(tmp, MUSIC_VIDEO_CACHE_PATH)
    except OSError as e:
        print(f'music video cache write: {e}', flush=True)


def _music_video_cache_key(title, artist):
    # v4 — stricter title/duration scoring (bump to invalidate cache).
    return f"v4|{(artist or '').strip().lower()}|{title.strip().lower()}"


def _music_video_search_queries(artist, title):
    title = (title or '').strip()
    artist = (artist or '').strip()
    queries = []
    if artist:
        queries.extend([
            f'{artist} {title} official music video',
            f'{artist} {title} music video vevo',
            f'{artist} - {title} official video',
        ])
    if title:
        queries.append(f'{title} official music video')
    seen = set()
    out = []
    for q in queries:
        q = q.strip()
        if q and q.lower() not in seen:
            seen.add(q.lower())
            out.append(q)
    return out


_MUSIC_VIDEO_BAD_TITLE = (
    'lyric video', 'lyrics video', 'lyrics', 'official audio', 'audio only',
    'visualizer', 'visualiser', 'static', 'still image', 'picture video',
    'provided to youtube', 'auto-generated', 'topic -', 'full album',
    '1 hour', '10 hours', '8 hours', 'loop', 'slowed', 'reverb', 'nightcore',
    'cover', 'karaoke', 'instrumental', 'sped up', '8d audio',
    'live at', 'live from', 'full concert', 'concert footage', 'tv performance',
    'the tonight show', 'late night', 'unplugged', 'acoustic version', 'acoustic session',
    'behind the scenes', 'making of', 'reaction', 'review', 'reading', 'storytime',
    'interview', 'documentary', 'trailer', 'teaser', 'announcement',
)
_MUSIC_VIDEO_GOOD_TITLE = (
    'official music video', 'official video', '(official video)', '[official video]',
    'music video', '(video)', ' - video', ' vevo',
)


def _music_video_normalize(text):
    import re
    t = (text or '').lower()
    t = re.sub(r'[^\w\s]', ' ', t)
    t = re.sub(r'\s+', ' ', t).strip()
    for noise in (
        'official music video', 'official video', 'music video', 'video', 'hd', 'vevo',
        'remaster', 'remastered', '4k', '1080p',
    ):
        t = t.replace(noise, ' ')
    return re.sub(r'\s+', ' ', t).strip()


def _music_video_compact(text):
    return _music_video_normalize(text).replace(' ', '')


def _music_video_artist_tokens(artist):
    tokens = set(_music_video_normalize(artist).split())
    tokens -= {'the', 'and', 'a', 'an'}
    return {t for t in tokens if t}


def _music_video_artist_matches(artist, picked_title, channel=None):
    if not (artist or '').strip():
        return True
    blob = f"{_music_video_normalize(picked_title)} {_music_video_normalize(channel or '')}"
    tokens = _music_video_artist_tokens(artist)
    if not tokens:
        return True
    return all(tok in blob for tok in tokens)


def _music_video_extract_song_title(picked_title, artist=None):
    import re
    raw = (picked_title or '').strip()
    t = re.sub(r'\([^)]*\)', '', raw)
    t = re.sub(r'\[[^\]]*\]', '', t).strip()
    for sep in (' - ', ' – ', ' — ', ' | ', '|'):
        if sep in t:
            left, right = t.split(sep, 1)
            left_n = _music_video_normalize(left)
            right_n = _music_video_normalize(right)
            if artist:
                artist_n = _music_video_normalize(artist)
                if artist_n and artist_n in left_n:
                    return right_n
                if artist_n and artist_n in right_n:
                    return left_n
            return right_n or left_n
    if artist:
        artist_n = _music_video_normalize(artist)
        whole = _music_video_normalize(t)
        if artist_n and whole.startswith(artist_n):
            rest = whole[len(artist_n):].strip()
            if rest:
                return rest
    return _music_video_normalize(t)


def _music_video_title_matches(title, picked_title):
    title_n = _music_video_normalize(title)
    if not title_n:
        return True
    picked_song = _music_video_extract_song_title(picked_title)
    if not picked_song:
        return False
    if title_n in picked_song or picked_song in title_n:
        if title_n == picked_song:
            return True
        if len(title_n.split()) >= 2:
            return True
        if len(picked_song.split()) == 1:
            return title_n == picked_song
        return False
    if _music_video_compact(title) and _music_video_compact(title) in _music_video_compact(picked_song):
        return True
    words = [w for w in title_n.split() if len(w) >= 3]
    if not words and title_n:
        words = title_n.split()
    return bool(words) and all(w in picked_song for w in words)


def _music_video_score(artist, title, picked_title, duration_sec=None, channel=None, track_duration_sec=None):
    t = (picked_title or '').lower()
    title_l = (title or '').strip().lower()
    title_ok = _music_video_title_matches(title, picked_title)
    score = 0
    if not title_ok:
        score -= 70
        if (artist or '').strip() and _music_video_artist_matches(artist, picked_title, channel):
            score -= 35
    elif title_l and title_l in t:
        score += 32
    else:
        score += 18
    for phrase in _MUSIC_VIDEO_GOOD_TITLE:
        if phrase in t:
            score += 40
            break
    if 'music video' in t or 'official video' in t:
        score += 25
    if _music_video_artist_matches(artist, picked_title, channel):
        score += 14
    elif (artist or '').strip():
        score -= 18
    if 'vevo' in t or (channel and 'vevo' in channel.lower()):
        score += 20
    for bad in _MUSIC_VIDEO_BAD_TITLE:
        if bad in t:
            score -= 45
    if duration_sec is not None:
        try:
            dur = int(duration_sec)
        except (TypeError, ValueError):
            dur = None
        if dur is not None:
            if dur < 45:
                score -= 35
            elif dur < 90:
                score -= 10
            elif 90 <= dur <= 720:
                score += 8
            elif dur > 900 and 'live' not in t:
                score -= 12
            if track_duration_sec is not None:
                try:
                    track_d = int(track_duration_sec)
                except (TypeError, ValueError):
                    track_d = None
                if track_d and track_d > 0:
                    delta = abs(track_d - dur)
                    if delta <= 8:
                        score += 28
                    elif delta <= 18:
                        score += 14
                    elif delta <= 35:
                        score += 6
                    elif delta > 75:
                        score -= 22
    return score


def _music_video_pick_best(artist, title, candidates, track_duration_sec=None):
    """Pick highest-scoring candidate: list of (video_id, picked_title, duration_sec, channel)."""
    scored = []
    for row in candidates:
        if len(row) >= 4:
            vid, picked, dur, channel = row[0], row[1], row[2], row[3]
        else:
            vid, picked, dur, channel = row[0], row[1], row[2] if len(row) > 2 else None, None
        if not vid:
            continue
        s = _music_video_score(artist, title, picked, dur, channel, track_duration_sec)
        scored.append((s, vid, picked, _music_video_title_matches(title, picked)))
    if not scored:
        return None, None
    title_ok = [row for row in scored if row[3]]
    pool = title_ok if title_ok else scored
    best = max(pool, key=lambda row: row[0])
    if not best[3] and best[0] < 35:
        return None, None
    if best[0] < -20:
        return None, None
    return best[1], best[2]


def _music_video_query(artist, title):
    if artist:
        return f'{artist} {title} official music video'.strip()
    return f'{title} official music video'.strip()


def _music_video_youtube_id(raw):
    import urllib.parse
    if not raw:
        return None
    raw = raw.strip()
    if len(raw) == 11 and raw.replace('-', '').replace('_', '').isalnum():
        return raw
    if 'v=' in raw:
        return urllib.parse.parse_qs(urllib.parse.urlparse(raw).query).get('v', [None])[0]
    if raw.startswith('/watch?v='):
        return raw.split('v=', 1)[-1].split('&', 1)[0]
    return None


def _music_video_from_override(artist, title):
    overrides = load_config().get('musicVideoOverrides') or {}
    if not isinstance(overrides, dict):
        return None, None
    key = _music_video_cache_key(title, artist or '')
    hit = overrides.get(key)
    if isinstance(hit, str):
        vid = _music_video_youtube_id(hit)
        return (vid, title) if vid else (None, None)
    if isinstance(hit, dict):
        vid = _music_video_youtube_id(hit.get('videoId') or hit.get('id'))
        return (vid, hit.get('title')) if vid else (None, None)
    return None, None


def _music_video_from_youtube_api(artist, title, track_duration_sec=None):
    import urllib.error
    import urllib.parse
    import urllib.request
    api_key = (load_config().get('youtubeApiKey') or '').strip()
    if not api_key:
        return None, None
    query = _music_video_query(artist, title)
    params = urllib.parse.urlencode({
        'part': 'snippet',
        'q': query,
        'type': 'video',
        'maxResults': '8',
        'key': api_key,
    })
    url = f'https://www.googleapis.com/youtube/v3/search?{params}'
    req = urllib.request.Request(url, headers={'User-Agent': 'BockMedia/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=14) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        print(f'music video youtube api search failed: {e}', flush=True)
        return None, None
    candidates = []
    for item in data.get('items') or []:
        vid = (item.get('id') or {}).get('videoId')
        snippet = item.get('snippet') or {}
        picked = snippet.get('title')
        if vid:
            candidates.append((vid, picked, None, snippet.get('channelTitle')))
    return _music_video_pick_best(artist, title, candidates, track_duration_sec)


def _music_video_ytdlp_search_rows(artist, title):
    import shutil
    if not shutil.which('yt-dlp'):
        return []
    rows = []
    seen = set()
    for query in _music_video_search_queries(artist, title):
        cmd = [
            'yt-dlp', '--flat-playlist',
            '--print', '%(id)s\t%(title)s\t%(duration)s\t%(channel)s',
            '--no-warnings', '--no-update', f'ytsearch12:{query}',
        ]
        cookies = _music_video_cookies_path()
        if cookies:
            cmd[1:1] = ['--cookies', cookies]
        out = _music_video_ytdlp_run(cmd, timeout=40)
        if out is None:
            continue
        for line in (out.stdout or '').splitlines():
            parts = line.split('\t', 3)
            if len(parts) < 2:
                continue
            vid = _music_video_youtube_id(parts[0])
            if not vid or vid in seen:
                continue
            seen.add(vid)
            picked = parts[1].strip() or None
            dur = None
            if len(parts) > 2 and parts[2].strip().isdigit():
                dur = int(parts[2].strip())
            channel = parts[3].strip() if len(parts) > 3 else None
            rows.append((vid, picked, dur, channel))
    return rows


def _music_video_from_ytdlp(artist, title, track_duration_sec=None):
    candidates = _music_video_ytdlp_search_rows(artist, title)
    vid, picked = _music_video_pick_best(artist, title, candidates, track_duration_sec)
    if vid:
        return vid, picked
    return None, None


def _music_video_from_piped_base(base, artist, title, track_duration_sec=None):
    import urllib.error
    import urllib.parse
    import urllib.request
    query = _music_video_query(artist, title)
    url = f"{base.rstrip('/')}/search?{urllib.parse.urlencode({'q': query, 'filter': 'videos'})}"
    req = urllib.request.Request(url, headers={'User-Agent': 'BockMedia/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=14) as resp:
            data = json.loads(resp.read().decode('utf-8'))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
        return None, None
    candidates = []
    for item in data.get('items') or []:
        if item.get('type') not in ('stream', 'video'):
            continue
        vid = _music_video_youtube_id(item.get('url') or '')
        if vid:
            candidates.append((vid, item.get('title'), None))
    return _music_video_pick_best(artist, title, candidates, track_duration_sec)


def _music_video_piped_bases():
    cfg = load_config()
    bases = []
    single = (cfg.get('pipedApiBase') or '').strip()
    if single:
        bases.append(single.rstrip('/'))
    extra = cfg.get('pipedApiBases') or []
    if isinstance(extra, list):
        for b in extra:
            if isinstance(b, str) and b.strip():
                bases.append(b.strip().rstrip('/'))
    if not bases:
        bases = [
            'https://pipedapi.kavin.rocks',
            'https://api.piped.yt',
            'https://pipedapi-libre.kavin.rocks',
        ]
    seen = set()
    out = []
    for b in bases:
        if b not in seen:
            seen.add(b)
            out.append(b)
    return out


def _music_video_from_piped(artist, title, track_duration_sec=None):
    for base in _music_video_piped_bases():
        vid, picked = _music_video_from_piped_base(base, artist, title, track_duration_sec)
        if vid:
            return vid, picked
    return None, None


def _music_video_lookup(artist, title, track_duration_sec=None):
    for fn in (
        lambda: _music_video_from_override(artist, title),
        lambda: _music_video_from_youtube_api(artist, title, track_duration_sec),
        lambda: _music_video_from_ytdlp(artist, title, track_duration_sec),
        lambda: _music_video_from_piped(artist, title, track_duration_sec),
    ):
        vid, picked = fn()
        if vid:
            return vid, picked
    return None, None


def _music_video_payload(title, artist=None, track_duration_sec=None):
    title = (title or '').strip()
    if not title:
        return {'videoId': None, 'title': None}
    artist = (artist or '').strip() or None
    key = _music_video_cache_key(title, artist or '')
    with _MUSIC_VIDEO_CACHE_LOCK:
        cached = _music_video_cache_load()
        hit = cached.get(key)
        if hit:
            if hit.get('videoId'):
                _music_video_warm_stream(hit['videoId'])
            return hit
    vid, picked_title = _music_video_lookup(artist or '', title, track_duration_sec)
    payload = {'videoId': vid, 'title': picked_title}
    if vid:
        with _MUSIC_VIDEO_CACHE_LOCK:
            cached = _music_video_cache_load()
            cached[key] = payload
            _music_video_cache_save(cached)
        _music_video_warm_stream(vid)
    return payload


def _music_video_warm_stream(video_id):
    """Resolve googlevideo URL in the background so the proxy is hot when the client connects."""
    video_id = (video_id or '').strip()
    if not video_id or not _MUSIC_VIDEO_ID_RE.fullmatch(video_id):
        return
    if _music_video_stream_cache_get(video_id):
        return
    if not _music_video_cookies_path() or not shutil.which('yt-dlp'):
        return

    def _run():
        try:
            _music_video_direct_stream_url(video_id)
        except Exception as e:
            print(f'music video warm {video_id}: {e}', flush=True)

    threading.Thread(target=_run, name=f'mv-warm-{video_id[:8]}', daemon=True).start()


@app.route('/api/music-video')
def music_video():
    """Resolve a YouTube music-video id for artist/title (cached; muted embed on clients)."""
    title = (request.args.get('title') or '').strip()
    if not title:
        return jsonify({'error': 'title required'}), 400
    artist = (request.args.get('artist') or '').strip() or None
    duration_raw = (request.args.get('durationSec') or request.args.get('duration') or '').strip()
    track_duration_sec = None
    if duration_raw.isdigit():
        track_duration_sec = int(duration_raw)
    return jsonify(_music_video_payload(title, artist, track_duration_sec))


_MUSIC_VIDEO_ID_RE = re.compile(r'^[\w-]{11}$')
_MUSIC_VIDEO_STREAM_CACHE = {}
_MUSIC_VIDEO_STREAM_CACHE_LOCK = threading.Lock()
_MUSIC_VIDEO_STREAM_TTL_SEC = 2 * 3600


def _music_video_cookies_path():
    cfg = (load_config().get('ytDlpCookiesPath') or '').strip()
    if cfg and os.path.isfile(cfg):
        return cfg
    default = os.path.join(DATA_DIR, 'youtube-cookies.txt')
    if os.path.isfile(default):
        return default
    return None


def _music_video_ytdlp_env():
    env = os.environ.copy()
    deno_dir = os.path.expanduser('~/.deno/bin')
    if os.path.isdir(deno_dir):
        env['PATH'] = deno_dir + os.pathsep + env.get('PATH', '')
    return env


def _music_video_ytdlp_cmd(video_id, *extra):
    deno_bin = os.path.expanduser('~/.deno/bin/deno')
    cmd = ['yt-dlp', '--no-update', '--no-warnings']
    if os.path.isfile(deno_bin):
        cmd.extend(['--js-runtimes', f'deno:{deno_bin}'])
    cookies = _music_video_cookies_path()
    if cookies:
        cmd.extend(['--cookies', cookies])
    cmd.extend(extra)
    cmd.append(f'https://www.youtube.com/watch?v={video_id}')
    return cmd


def _music_video_ytdlp_run(cmd, *, timeout=45):
    try:
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            env=_music_video_ytdlp_env(),
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        print(f'music video yt-dlp: {e}', flush=True)
        return None


def _music_video_stream_cache_get(video_id):
    with _MUSIC_VIDEO_STREAM_CACHE_LOCK:
        hit = _MUSIC_VIDEO_STREAM_CACHE.get(video_id)
        if hit and hit.get('expires', 0) > time.time():
            return hit.get('url')
    return None


def _music_video_stream_cache_set(video_id, url):
    with _MUSIC_VIDEO_STREAM_CACHE_LOCK:
        _MUSIC_VIDEO_STREAM_CACHE[video_id] = {
            'url': url,
            'expires': time.time() + _MUSIC_VIDEO_STREAM_TTL_SEC,
        }


def _music_video_direct_stream_url(video_id):
    cached = _music_video_stream_cache_get(video_id)
    if cached:
        return cached
    # Prefer smaller progressive MP4 first — faster first frame through the proxy.
    format_specs = [
        'best[ext=mp4][height<=480][protocol=https][vcodec!=none][acodec!=none]/'
        'best[ext=mp4][height<=720][protocol=https][vcodec!=none][acodec!=none]/'
        'best[ext=mp4][protocol=https][vcodec!=none][acodec!=none]/22/18/b',
    ]
    for fmt in format_specs:
        cmd = _music_video_ytdlp_cmd(video_id, '-f', fmt, '-g')
        out = _music_video_ytdlp_run(cmd)
        if out is None:
            continue
        urls = []
        for line in (out.stdout or '').splitlines():
            line = line.strip()
            if line.startswith('http'):
                urls.append(line)
        if len(urls) == 1 and '.m3u8' not in urls[0] and 'manifest' not in urls[0]:
            _music_video_stream_cache_set(video_id, urls[0])
            return urls[0]
        if out.stderr:
            print(f'music video stream url ({fmt}): {out.stderr.strip()[:280]}', flush=True)
    return None


def _music_video_play_reason():
    if not shutil.which('yt-dlp'):
        return 'yt-dlp is not installed on the server'
    if not _music_video_cookies_path():
        return (
            'YouTube blocked anonymous access — export browser cookies to '
            f'{os.path.join(DATA_DIR, "youtube-cookies.txt")} (see scripts/youtube_cookies.sh)'
        )
    if not os.path.isfile(os.path.expanduser('~/.deno/bin/deno')):
        return 'Install Deno on the server for YouTube stream extraction (~/.deno/bin/deno)'
    return 'Could not resolve a playable stream for this video'


def _music_video_can_stream(video_id):
    """True when yt-dlp can extract a stream for this id (check only; do not give URL to clients)."""
    return _music_video_direct_stream_url(video_id) is not None


@app.route('/api/music-video/<video_id>/play')
def music_video_play(video_id):
    """Return a LAN-proxied stream URL for ExoPlayer (googlevideo URLs are IP-bound to the server)."""
    video_id = (video_id or '').strip()
    if not _MUSIC_VIDEO_ID_RE.fullmatch(video_id):
        return jsonify({'error': 'bad video id'}), 400
    if not _music_video_cookies_path() or not shutil.which('yt-dlp'):
        return jsonify({'ready': False, 'reason': _music_video_play_reason()}), 503
    if not _music_video_can_stream(video_id):
        return jsonify({'ready': False, 'reason': _music_video_play_reason()}), 503
    play_url = f'/api/music-video/{video_id}/proxy'
    return jsonify({'ready': True, 'playUrl': play_url, 'proxied': True})


@app.route('/api/music-video/<video_id>/proxy', methods=['GET', 'HEAD'])
def music_video_proxy(video_id):
    """Range-aware reverse proxy — googlevideo URLs are IP-bound to this server."""
    import urllib.error
    import urllib.request

    video_id = (video_id or '').strip()
    if not _MUSIC_VIDEO_ID_RE.fullmatch(video_id):
        return jsonify({'error': 'bad video id'}), 400
    if not _music_video_cookies_path():
        return jsonify({'error': _music_video_play_reason()}), 503
    upstream_url = _music_video_direct_stream_url(video_id)
    if not upstream_url:
        return jsonify({'error': _music_video_play_reason()}), 503

    req_headers = {'User-Agent': 'Mozilla/5.0 (compatible; BockMedia/1.0)', 'Accept': '*/*'}
    range_header = request.headers.get('Range')
    if range_header:
        req_headers['Range'] = range_header
    upstream_req = urllib.request.Request(upstream_url, headers=req_headers, method=request.method)
    try:
        upstream = urllib.request.urlopen(upstream_req, timeout=120)
    except urllib.error.HTTPError as e:
        body = e.read()
        return Response(body, status=e.code, headers=dict(e.headers.items()))
    except urllib.error.URLError as e:
        print(f'music video proxy upstream failed: {e}', flush=True)
        return jsonify({'error': 'upstream stream failed'}), 502

    hop_by_hop = {
        'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
        'te', 'trailers', 'transfer-encoding', 'upgrade',
    }
    out_headers = {
        k: v for k, v in upstream.headers.items()
        if k.lower() not in hop_by_hop
    }

    if request.method == 'HEAD':
        upstream.close()
        return Response('', status=upstream.status, headers=out_headers)

    def generate():
        try:
            while True:
                chunk = upstream.read(65536)
                if not chunk:
                    break
                yield chunk
        finally:
            upstream.close()

    return Response(
        stream_with_context(generate()),
        status=upstream.status,
        headers=out_headers,
    )


@app.route('/api/songs')
def songs():
    page = int(request.args.get('page', 1))
    limit = int(request.args.get('limit', 100))
    search = request.args.get('search', '')
    artist = request.args.get('artist', '')
    album = request.args.get('album', '')
    genre = (request.args.get('genre') or '').strip()
    sort = (request.args.get('sort') or 'track').strip().lower()
    order = (request.args.get('order') or 'asc').strip().lower()
    desc = order == 'desc'
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
    if genre:
        conditions.append('LOWER(COALESCE(genre,"")) LIKE ?')
        params.append(f'%{genre.lower()}%')

    where = ' AND '.join(conditions)
    if sort == 'added':
        order_sql = f'first_seen_at {"DESC" if desc else "ASC"}, title COLLATE NOCASE ASC'
    elif sort == 'artist':
        order_sql = f'artist COLLATE NOCASE {"DESC" if desc else "ASC"}, title COLLATE NOCASE ASC'
    elif sort == 'album':
        order_sql = f'album COLLATE NOCASE {"DESC" if desc else "ASC"}, title COLLATE NOCASE ASC'
    elif sort == 'title':
        order_sql = f'title COLLATE NOCASE {"DESC" if desc else "ASC"}'
    else:
        order_sql = (
            f'CAST(COALESCE(NULLIF(track_number, ""), "0") AS INTEGER) {"DESC" if desc else "ASC"}, '
            f'title COLLATE NOCASE ASC'
        )

    rows = db_query(
        f'SELECT id, title, artist, album, genre, year, duration_seconds, bitrate, track_number, path, first_seen_at '
        f'FROM songs_cache WHERE {where} '
        f'ORDER BY {order_sql} LIMIT ? OFFSET ?',
        params + [limit, offset]
    )
    total_row = db_one(
        f'SELECT COUNT(*) as total FROM songs_cache WHERE {where}',
        params
    )
    items = []
    for row in rows:
        r = dict(row)
        try:
            r['duration_seconds'] = _song_duration_seconds(r.get('path'), r.get('duration_seconds'))
        except Exception:
            try:
                r['duration_seconds'] = int(float(r.get('duration_seconds') or 0))
            except (TypeError, ValueError):
                r['duration_seconds'] = 0
        items.append(r)
    return jsonify({'items': items, 'total': total_row.get('total', 0)})

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
    'continueAfterQueue':   'ContinueAfterQueue',
}

@app.route('/api/auth/info')
def auth_info():
    """Public login hints (no password)."""
    return jsonify({
        'requirePassword': _api_auth_required(),
        'username': _web_username(),
        'allowOpenLanMedia': _allow_open_lan_media(),
        'mediaAuthRequired': (
            _is_lan_request()
            and not _allow_open_lan_media()
            and bool(_media_signing_secret())
        ),
    })

@app.route('/api/media/sign')
def media_sign():
    """Return HMAC-signed /stream/ or /artwork/ URL for web img/audio tags."""
    if not (_allow_open_lan_media() or _basic_auth_ok() or _mobile_api_token_ok()):
        if _credentials_configured():
            return _auth_required()
        return jsonify({'error': 'forbidden'}), 403
    raw = (request.args.get('path') or '').strip()
    if not raw.startswith('/stream/') and not raw.startswith('/artwork/'):
        return jsonify({'error': 'path must start with /stream/ or /artwork/'}), 400
    parsed = urlparse(raw)
    signed = _append_media_sig(parsed.path, parsed.query or None)
    exp = None
    for k, v in parse_qsl(signed.split('?', 1)[-1] if '?' in signed else ''):
        if k == 'expires':
            exp = int(v)
            break
    return jsonify({'url': signed, 'expires': exp})

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

DEVICES_PATH = os.path.join(DATA_DIR, 'devices.json')

@contextlib.contextmanager
def _cross_process_flock(lock_path, *, shared=False):
    """fcntl flock for JSON files touched from multiple processes (gunicorn + scripts)."""
    os.makedirs(os.path.dirname(lock_path) or '.', exist_ok=True)
    fh = open(lock_path, 'w')
    try:
        fcntl.flock(fh.fileno(), fcntl.LOCK_SH if shared else fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
        fh.close()

def _atomic_json_write(path, data, **dump_kwargs):
    """Write JSON atomically: write to a unique .tmp then os.replace.

    The temp name is unique per write (pid + thread + random) so concurrent
    writers — e.g. two members of a device group whose Echoes hit the skill at
    the same instant — never share a temp file and clobber each other's replace.
    """
    if os.path.basename(path) in _MEMBER_DATA_BASENAMES:
        try:
            bock_member_backup.maybe_backup(path, DATA_DIR)
        except Exception as ex:
            print(f'[BACKUP] skipped {path}: {ex}', flush=True)
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
    # Skip while play intents are pending — two Echo Shows share fingerprints and
    # we'd fold the new room onto whichever speaker was active most recently.
    if looks_auto and cur.get('fingerprint') and not _play_intents_pending(now):
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

def _entry_serial(entry, primary, store):
    """Serial bound to this device (checking its primary too)."""
    serial = (entry or {}).get('serial')
    if serial:
        return serial
    if primary:
        return ((store or {}).get(primary) or {}).get('serial')
    return None


def _live_alexa_name(entry, primary, store):
    """Current Alexa-app room name via the device's bound hardware serial.

    The friendly name ("Teen's Room") lives only in the unofficial-API/serial
    identity space — the custom-skill `deviceId` never carries it. Resolving
    through the live roster means Alexa-app renames appear immediately and a
    correlated device never falls back to "Echo XXXXXX".
    """
    serial = _entry_serial(entry, primary, store)
    if not serial:
        return ''
    return _alexa_name_for_serial(serial) or ''


def _device_label(device_id):
    """Human-readable device name for now-playing / history."""
    if not device_id or device_id == 'default':
        return 'default'
    if _is_msp_pseudo(device_id):
        label = _queue_target_label(_msp_queue_from_device_id(device_id))
        return label or MSP_DEVICE_NAME
    store = _load_devices()
    primary = _resolve_device_id(device_id, store)
    entry = store.get(primary) or store.get(device_id) or {}
    live = _live_alexa_name(entry, primary, store)
    if live:
        stored = (entry.get('name') or '').strip()
        if stored and stored != live:
            entry['name'] = live
            store[primary] = entry
            try:
                _save_devices(store)
            except Exception:
                pass
        return live
    name = (entry.get('name') or '').strip()
    auto_primary = f"echo {primary[-6:]}".lower()
    auto_raw = f"echo {device_id[-6:]}".lower()
    if name and name.lower() not in (auto_primary, auto_raw):
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
            'name':        _live_alexa_name(e, did, data) or e.get('name') or did[-6:],
            'lastSeen':    e.get('lastSeen'),
            'firstSeen':   e.get('firstSeen'),
            'fingerprint': e.get('fingerprint') or '',
            'platform':    e.get('platform') or ('alexa' if did.startswith('amzn1.') else 'unknown'),
            'connectCount': e.get('connectCount', 0),
            'downloadCount': e.get('downloadCount', 0),
        })
    result.sort(key=lambda x: x.get('lastSeen') or 0, reverse=True)
    return jsonify(result)


def _relabel_devices_from_roster():
    """Persist current Alexa-app room names onto device entries by serial.

    A device's friendly name ("Teen's Room") lives only in the unofficial-API
    roster, keyed by hardware serial. This backfills it onto any entry whose
    serial we've correlated, so stale/auto "Echo XXXXXX" names are replaced
    even if the live roster is later unavailable. Returns entries updated.
    """
    try:
        import alexa_remote
        if not alexa_remote.is_configured():
            return 0
        roster = alexa_remote.list_devices() or []
    except Exception:
        return 0
    by_serial = {d.get('serial'): d.get('name')
                 for d in roster if d.get('serial') and d.get('name')}
    if not by_serial:
        return 0
    store = _load_devices()
    updated = 0
    for _did, e in store.items():
        serial = e.get('serial')
        if not serial:
            continue
        live = by_serial.get(serial)
        if live and (e.get('name') or '') != live:
            e['name'] = live
            updated += 1
    if updated:
        _save_devices(store)
        with _HOUSEHOLD_LOCK:
            h = _load_household()
            if _sync_default_room_owners(h, store):
                _save_household(h)
    return updated


@app.route('/api/devices/relabel', methods=['POST'])
def relabel_devices():
    """Backfill persisted device names from the live Alexa roster (by serial)."""
    return jsonify({'ok': True, 'updated': _relabel_devices_from_roster()})


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

# ── Household members (profiles), bindings & attribution ─────────────────────
# A "member" is a person in the household (Parent, Teen, Guest). Members own taste,
# history, recommendations, playlists and messages. They are *bound* to devices
# for attribution: a phone install → its active member; an Echo → the room's
# default member (Amazon never tells a skill who is speaking). This is an
# attribution + policy layer, NOT a security boundary — only parent actions are
# PIN-gated. Everything persists locally in DATA_DIR. Zero third-party sharing.

HOUSEHOLD_PATH = os.path.join(DATA_DIR, 'household.json')
_HOUSEHOLD_LOCK = threading.Lock()

_VALID_ROLES = ('parent', 'kid')


def _household_defaults():
    return {'members': [], 'clientBindings': {}, 'deviceOwners': {}, 'phoneBindings': {}}


def _load_household():
    try:
        with open(HOUSEHOLD_PATH) as f:
            data = json.load(f)
    except Exception:
        return _household_defaults()
    if not isinstance(data, dict):
        return _household_defaults()
    base = _household_defaults()
    base.update({k: data.get(k, base[k]) for k in base})
    if not isinstance(base['members'], list):
        base['members'] = []
    if not isinstance(base['clientBindings'], dict):
        base['clientBindings'] = {}
    if not isinstance(base['deviceOwners'], dict):
        base['deviceOwners'] = {}
    if not isinstance(base.get('phoneBindings'), dict):
        base['phoneBindings'] = {}
    return base


def _save_household(data):
    _atomic_json_write(HOUSEHOLD_PATH, data)


def _slug(name):
    s = re.sub(r'[^a-z0-9]+', '-', (name or '').strip().lower()).strip('-')
    return s or 'member'


def _gen_member_id(name, members):
    base = f'p-{_slug(name)}'
    existing = {m.get('id') for m in members}
    if base not in existing:
        return base
    i = 2
    while f'{base}-{i}' in existing:
        i += 1
    return f'{base}-{i}'


def _member_by_id(member_id, household=None):
    if not member_id:
        return None
    h = household or _load_household()
    for m in h.get('members', []):
        if m.get('id') == member_id:
            return m
    return None


def _member_id_for_room_name(device_name, members):
    """Map a room label like \"Teen's Room\" to household member p-teen."""
    dn = (device_name or '').strip().lower()
    if not dn or not members:
        return None
    for m in members:
        name = (m.get('name') or '').strip()
        if not name:
            continue
        first = name.split()[0].lower()
        if len(first) < 2:
            continue
        markers = (
            f"{first}'s",
            f"{first}s room",
            f"{first}s bedroom",
            f"{first}s echo",
            f"{first} room",
            f"{first} bedroom",
        )
        if any(mk in dn for mk in markers):
            return m.get('id')
    return None


def _sync_default_room_owners(household, store=None):
    """Persist room → kid profile bindings inferred from Alexa device names."""
    store = store if store is not None else _load_devices()
    members = household.get('members') or []
    if not members:
        return False
    owners = household.setdefault('deviceOwners', {})
    changed = False
    # Normalize legacy owner keys (alias ids) onto canonical primary device ids.
    normalized = {}
    for did, mid in list(owners.items()):
        primary = _resolve_device_id(did, store)
        if primary and mid:
            normalized[primary] = mid
    if normalized != owners:
        owners.clear()
        owners.update(normalized)
        changed = True
    seen = set()
    for did, entry in store.items():
        if not isinstance(entry, dict) or entry.get('aliasOf'):
            continue
        primary = _resolve_device_id(did, store)
        if primary in seen:
            continue
        seen.add(primary)
        label = (entry.get('name') or '').strip()
        if not label:
            label = _live_alexa_name(entry, primary, store) or ''
        mid = _member_id_for_room_name(label, members)
        if not mid or not _member_by_id(mid, household):
            continue
        if owners.get(primary) != mid:
            owners[primary] = mid
            changed = True
    return changed


def _member_label(member_id, household=None):
    m = _member_by_id(member_id, household)
    return (m or {}).get('name') or ''


def _public_member(m):
    """Member without secret fields (pinHash)."""
    return {
        'id': m.get('id'),
        'name': m.get('name'),
        'role': m.get('role') or 'kid',
        'color': m.get('color'),
        'avatar': m.get('avatar'),
        'hasPin': bool(m.get('pinHash')),
        'createdAt': m.get('createdAt'),
    }


def _hash_pin(pin):
    salt = os.urandom(16)
    dk = hashlib.pbkdf2_hmac('sha256', pin.encode('utf-8'), salt, 100_000)
    return 'pbkdf2_sha256$100000$' + base64.b64encode(salt).decode() + '$' + base64.b64encode(dk).decode()


def _verify_pin(pin, stored):
    try:
        algo, iters, salt_b64, dk_b64 = (stored or '').split('$')
        if algo != 'pbkdf2_sha256':
            return False
        salt = base64.b64decode(salt_b64)
        dk = base64.b64decode(dk_b64)
        test = hashlib.pbkdf2_hmac('sha256', (pin or '').encode('utf-8'), salt, int(iters))
        return hmac.compare_digest(test, dk)
    except Exception:
        return False


def _parent_pin_ok(member_id, pin, household=None):
    """True if member is a parent and the PIN matches (or no parent has a PIN
    set yet — first-run grace so the house isn't locked out)."""
    h = household or _load_household()
    m = _member_by_id(member_id, h)
    if not m or (m.get('role') or 'kid') != 'parent':
        return False
    if not m.get('pinHash'):
        return True
    return _verify_pin(pin, m.get('pinHash'))


def member_for_client(client_id, household=None):
    """Resolve the member bound to a phone/tablet install."""
    did = _client_device_id(client_id) if client_id else None
    if not did:
        return None
    h = household or _load_household()
    return h.get('clientBindings', {}).get(did)


def _normalize_phone_id(raw):
    pid = (raw or '').strip().lower()
    if not pid or len(pid) < 8:
        return ''
    return pid[:128]


def member_for_phone(phone_id, household=None):
    pid = _normalize_phone_id(phone_id)
    if not pid:
        return None
    h = household or _load_household()
    return (h.get('phoneBindings') or {}).get(pid)


def _rebind_client_from_phone(client_id, phone_id):
    """After reinstall: map new clientId → member via stable phone hardware id."""
    pid = _normalize_phone_id(phone_id)
    if not pid:
        return None
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        member_id = (h.get('phoneBindings') or {}).get(pid)
        if not member_id or not _member_by_id(member_id, h):
            return None
        did = _client_device_id(client_id)
        if not did:
            return None
        h.setdefault('clientBindings', {})[did] = member_id
        _save_household(h)
        return member_id


def member_for_device(device_id, household=None):
    """Resolve the default member for an Echo/room (alias-aware)."""
    if not device_id or device_id == 'default':
        return None
    h = household or _load_household()
    owners = h.get('deviceOwners', {})
    primary = _resolve_device_id(device_id)
    mid = owners.get(primary) or owners.get(device_id)
    if not mid:
        for oid, omid in owners.items():
            if _resolve_device_id(oid) == primary:
                mid = omid
                break
    if mid:
        return mid
    store = _load_devices()
    entry = store.get(primary) or store.get(device_id) or {}
    label = (entry.get('name') or '').strip()
    if not label:
        label = _live_alexa_name(entry, primary, store) or ''
    return _member_id_for_room_name(label, h.get('members', []))


def resolve_play_member(*, device_id=None, client_id=None, explicit_member=None,
                        household=None):
    """Attribute a play to a household member.

    Priority: explicit (app says "this is me") → phone install → room default.
    Returns a member id or '' when unknown (household-level only).
    """
    h = household or _load_household()
    if explicit_member and _member_by_id(explicit_member, h):
        return explicit_member
    if client_id:
        m = member_for_client(client_id, h)
        if m:
            return m
    if device_id:
        m = member_for_device(device_id, h)
        if m:
            return m
    return ''


def _ratings_member_from_request():
    """Resolve household member for star ratings (explicit memberId → client binding)."""
    try:
        body = request.get_json(silent=True) or {}
        explicit = (request.args.get('memberId') or body.get('memberId') or '').strip() or None
        client_id = (request.args.get('clientId') or body.get('clientId') or '').strip() or None
    except RuntimeError:
        body = {}
        explicit = None
        client_id = None
    if not isinstance(body, dict):
        body = {}
    if explicit:
        member_id = explicit
    else:
        member_id = resolve_play_member(client_id=client_id, explicit_member=None) or ''
    if member_id:
        bock_ratings.migrate_legacy_to_member(RATINGS_PATH, member_id, _atomic_json_write)
    return member_id or ''


@app.route('/api/household')
def household_get():
    """Members (public), client/device bindings with friendly labels."""
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        device_store = _load_devices()
        if _sync_default_room_owners(h, device_store):
            _save_household(h)
        owners = []
        for did, mid in h.get('deviceOwners', {}).items():
            owners.append({
                'deviceId': did,
                'deviceName': _device_label(did),
                'memberId': mid,
                'memberName': _member_label(mid, h),
            })
        clients = []
        for did, mid in h.get('clientBindings', {}).items():
            entry = device_store.get(did) or {}
            clients.append({
                'clientDeviceId': did,
                'deviceName': entry.get('name') or did,
                'platform': entry.get('platform'),
                'memberId': mid,
                'memberName': _member_label(mid, h),
            })
        return jsonify({
            'members': [_public_member(m) for m in h.get('members', [])],
            'deviceOwners': owners,
            'clientBindings': clients,
        })


@app.route('/api/household/members', methods=['POST'])
def household_create_member():
    body = request.get_json(silent=True) or {}
    name = (body.get('name') or '').strip()
    if not name:
        return jsonify({'error': 'name required'}), 400
    role = (body.get('role') or 'kid').strip().lower()
    if role not in _VALID_ROLES:
        return jsonify({'error': f'role must be one of {_VALID_ROLES}'}), 400
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        member = {
            'id': _gen_member_id(name, h['members']),
            'name': name,
            'role': role,
            'color': (body.get('color') or '').strip() or None,
            'avatar': (body.get('avatar') or '').strip() or None,
            'pinHash': None,
            'createdAt': time.time(),
        }
        h['members'].append(member)
        _save_household(h)
    return jsonify(_public_member(member)), 201


@app.route('/api/household/members/<member_id>', methods=['PUT', 'DELETE'])
def household_modify_member(member_id):
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        m = _member_by_id(member_id, h)
        if not m:
            return jsonify({'error': 'not_found'}), 404
        if request.method == 'DELETE':
            h['members'] = [x for x in h['members'] if x.get('id') != member_id]
            h['clientBindings'] = {k: v for k, v in h['clientBindings'].items() if v != member_id}
            h['deviceOwners'] = {k: v for k, v in h['deviceOwners'].items() if v != member_id}
            _save_household(h)
            return jsonify({'ok': True})
        body = request.get_json(silent=True) or {}
        if 'name' in body:
            name = (body.get('name') or '').strip()
            if not name:
                return jsonify({'error': 'name cannot be empty'}), 400
            m['name'] = name
        if 'role' in body:
            role = (body.get('role') or '').strip().lower()
            if role not in _VALID_ROLES:
                return jsonify({'error': f'role must be one of {_VALID_ROLES}'}), 400
            m['role'] = role
        if 'color' in body:
            m['color'] = (body.get('color') or '').strip() or None
        if 'avatar' in body:
            m['avatar'] = (body.get('avatar') or '').strip() or None
        _save_household(h)
        return jsonify(_public_member(m))


@app.route('/api/household/members/<member_id>/pin', methods=['POST'])
def household_member_pin(member_id):
    """Set (verify=false) or verify (verify=true) a parent's PIN.

    Setting a PIN the first time is allowed; replacing an existing PIN requires
    the current PIN in `currentPin`.
    """
    body = request.get_json(silent=True) or {}
    pin = (str(body.get('pin') or '')).strip()
    if body.get('verify'):
        h = _load_household()
        m = _member_by_id(member_id, h)
        if not m:
            return jsonify({'error': 'not_found'}), 404
        return jsonify({'ok': _verify_pin(pin, m.get('pinHash'))})
    if len(pin) < 4:
        return jsonify({'error': 'pin must be at least 4 digits'}), 400
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        m = _member_by_id(member_id, h)
        if not m:
            return jsonify({'error': 'not_found'}), 404
        if m.get('role') != 'parent':
            return jsonify({'error': 'only parents can have a PIN'}), 400
        if m.get('pinHash') and not _verify_pin(str(body.get('currentPin') or ''), m['pinHash']):
            return jsonify({'error': 'current pin required'}), 403
        m['pinHash'] = _hash_pin(pin)
        _save_household(h)
    return jsonify({'ok': True, 'hasPin': True})


@app.route('/api/clients/bind', methods=['POST'])
def household_bind_client():
    body = request.get_json(silent=True) or {}
    client_id = (body.get('clientId') or '').strip()
    member_id = (body.get('memberId') or '').strip()
    phone_id = _normalize_phone_id(body.get('phoneId'))
    did = _client_device_id(client_id)
    if not did:
        return jsonify({'error': 'clientId required'}), 400
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        if member_id and not _member_by_id(member_id, h):
            return jsonify({'error': 'unknown memberId'}), 400
        if member_id:
            h['clientBindings'][did] = member_id
            if phone_id:
                h.setdefault('phoneBindings', {})[phone_id] = member_id
        else:
            h['clientBindings'].pop(did, None)
        _save_household(h)
    return jsonify({'ok': True, 'clientDeviceId': did, 'memberId': member_id or None})


CLIENT_PREFS_PATH = os.path.join(DATA_DIR, 'client_prefs.json')


@app.route('/api/clients/prefs', methods=['GET'])
def client_prefs_get():
    client_id = (request.args.get('clientId') or '').strip()
    member_id = (request.args.get('memberId') or '').strip() or None
    did = _client_device_id(client_id)
    if not did and not member_id:
        return jsonify({'error': 'clientId or memberId required'}), 400
    return jsonify(bock_client_prefs.get_prefs(
        CLIENT_PREFS_PATH, member_id=member_id, client_device_id=did or None,
    ))


@app.route('/api/clients/prefs', methods=['PUT'])
def client_prefs_put():
    body = request.get_json(silent=True) or {}
    client_id = (body.get('clientId') or '').strip()
    member_id = (body.get('memberId') or '').strip() or None
    did = _client_device_id(client_id)
    try:
        out = bock_client_prefs.put_prefs(
            CLIENT_PREFS_PATH,
            member_id=member_id,
            client_device_id=did or None,
            member_prefs=body.get('memberPrefs'),
            client_prefs=body.get('clientPrefs'),
            atomic_write=_atomic_json_write,
        )
    except ValueError as e:
        return jsonify({'error': str(e)}), 400
    return jsonify({'ok': True, **out})


@app.route('/api/devices/<device_id>/owner', methods=['POST', 'DELETE'])
def household_device_owner(device_id):
    with _HOUSEHOLD_LOCK:
        h = _load_household()
        primary = _resolve_device_id(device_id)
        if request.method == 'DELETE':
            h['deviceOwners'].pop(primary, None)
            h['deviceOwners'].pop(device_id, None)
            _save_household(h)
            return jsonify({'ok': True})
        body = request.get_json(silent=True) or {}
        member_id = (body.get('memberId') or '').strip()
        if not _member_by_id(member_id, h):
            return jsonify({'error': 'unknown memberId'}), 400
        h['deviceOwners'][primary] = member_id
        _save_household(h)
    return jsonify({'ok': True, 'deviceId': primary, 'memberId': member_id})


# ── Kid-safe room policies ───────────────────────────────────────────────────
# Per-room rules enforced server-side (so they hold for phone, voice and
# routines alike): allow-list of playlists, explicit-content block, volume cap,
# and quiet hours. Keyed by the room's canonical id (primary skill deviceId when
# known, else hardware serial). Policy edits require a parent PIN.

ROOM_POLICY_PATH = os.path.join(DATA_DIR, 'room_policies.json')
_ROOM_POLICY_LOCK = threading.Lock()


def _load_room_policies():
    try:
        with open(ROOM_POLICY_PATH) as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _save_room_policies(data):
    _atomic_json_write(ROOM_POLICY_PATH, data)


def _room_key(identifier):
    """Canonical room key for a serial or deviceId: primary skill deviceId when
    we've correlated one, otherwise the hardware serial / raw id."""
    if not identifier:
        return ''
    store = _load_devices()
    prim = _resolve_device_id(identifier, store)
    if prim in store:
        return prim
    by_serial = _primary_by_serial(identifier, store)
    return by_serial or identifier


def _policy_for(identifier):
    return _load_room_policies().get(_room_key(identifier), {}) or {}


def _is_explicit_path(path):
    """Best-effort explicit flag from songs_cache; False if unknown/unavailable."""
    if not path:
        return False
    try:
        row = db_one('SELECT explicit FROM songs_cache WHERE path = ?', [path])
        return bool(row and row.get('explicit'))
    except Exception:
        return False


def _parse_hhmm(s):
    try:
        h, m = (s or '').split(':')
        return int(h) * 60 + int(m)
    except Exception:
        return None


def _in_quiet_hours(policy, now=None):
    """True if `now` falls inside any configured quiet-hours window."""
    windows = policy.get('quietHours') or []
    if not windows:
        return False
    now = now or datetime.datetime.now()
    minute_of_day = now.hour * 60 + now.minute
    weekday = now.weekday()  # Mon=0
    for w in windows:
        days = w.get('days')
        if days and weekday not in days:
            continue
        start = _parse_hhmm(w.get('from'))
        end = _parse_hhmm(w.get('to'))
        if start is None or end is None:
            continue
        if start <= end:
            if start <= minute_of_day < end:
                return True
        else:  # overnight window, e.g. 20:30 -> 07:00
            if minute_of_day >= start or minute_of_day < end:
                return True
    return False


def _policy_check_play(policy, *, kind=None, playlist_id=None, path=None,
                       now=None):
    """Return (ok, reason) for a play attempt against a room policy."""
    if not policy or not policy.get('safe'):
        return True, ''
    if _in_quiet_hours(policy, now):
        return False, 'quiet_hours'
    if policy.get('allowExplicit') is False and _is_explicit_path(path):
        return False, 'explicit_blocked'
    allow = policy.get('allowPlaylistIds')
    if allow:
        if (kind or 'playlist') != 'playlist' or not playlist_id or playlist_id not in allow:
            return False, 'not_in_allowlist'
    return True, ''


def _clamp_volume_for(identifier, volume):
    """Clamp a requested volume to the room's maxVolume (if any)."""
    policy = _policy_for(identifier)
    cap = policy.get('maxVolume') if policy.get('safe') else None
    if isinstance(cap, int) and volume > cap:
        return cap, True
    return volume, False


def _serial_for_room_key(key):
    """Hardware serial usable with alexa_remote for a room key, or None."""
    if not key:
        return None
    entry = _load_devices().get(key) or {}
    if entry.get('serial'):
        return entry['serial']
    if not _is_client_device(key) and not str(key).startswith('amzn1.'):
        return key  # key is itself a serial
    return None


def _volume_cap_loop():
    """Periodically pull safe-room volumes back under their cap (so a voice
    'louder' can't exceed it). No-ops unless caps are configured + Alexa is set."""
    while True:
        time.sleep(60)
        try:
            policies = _load_room_policies()
            caps = {k: v for k, v in policies.items()
                    if v.get('safe') and isinstance(v.get('maxVolume'), int)}
            if not caps:
                continue
            import alexa_remote
            if not alexa_remote.is_configured():
                continue
            for key, pol in caps.items():
                serial = _serial_for_room_key(key)
                if not serial:
                    continue
                try:
                    cur = alexa_remote.get_volume(serial)
                    if isinstance(cur, int) and cur > pol['maxVolume']:
                        alexa_remote.set_volume(serial, pol['maxVolume'])
                        print(f"[KID-SAFE] clamped {key} volume {cur}->{pol['maxVolume']}", flush=True)
                except Exception:
                    continue
        except Exception:
            continue


def _public_policy(policy):
    return {
        'safe': bool(policy.get('safe')),
        'allowPlaylistIds': policy.get('allowPlaylistIds') or [],
        'allowExplicit': policy.get('allowExplicit', True),
        'maxVolume': policy.get('maxVolume'),
        'quietHours': policy.get('quietHours') or [],
        'requireApproval': bool(policy.get('requireApproval')),
        'normalizeLoudness': policy.get('normalizeLoudness', True),
    }


@app.route('/api/devices/<device_id>/policy', methods=['GET', 'POST', 'DELETE'])
def device_policy(device_id):
    key = _room_key(device_id)
    if request.method == 'GET':
        return jsonify({'deviceId': key, **_public_policy(_policy_for(device_id))})
    body = request.get_json(silent=True) or {}
    # Parent PIN gate for any change.
    if not _parent_pin_ok(body.get('memberId'), str(body.get('pin') or '')):
        return jsonify({'error': 'parent_pin_required'}), 403
    with _ROOM_POLICY_LOCK:
        policies = _load_room_policies()
        if request.method == 'DELETE':
            policies.pop(key, None)
            _save_room_policies(policies)
            return jsonify({'ok': True})
        cur = policies.get(key, {}) or {}
        if 'safe' in body:
            cur['safe'] = bool(body['safe'])
        if 'allowPlaylistIds' in body:
            ids = body.get('allowPlaylistIds') or []
            cur['allowPlaylistIds'] = [str(x) for x in ids] if isinstance(ids, list) else []
        if 'allowExplicit' in body:
            cur['allowExplicit'] = bool(body['allowExplicit'])
        if 'maxVolume' in body:
            mv = body.get('maxVolume')
            cur['maxVolume'] = max(0, min(100, int(mv))) if mv is not None else None
        if 'quietHours' in body:
            cur['quietHours'] = body.get('quietHours') or []
        if 'requireApproval' in body:
            cur['requireApproval'] = bool(body['requireApproval'])
        if 'normalizeLoudness' in body:
            cur['normalizeLoudness'] = bool(body['normalizeLoudness'])
        policies[key] = cur
        _save_room_policies(policies)
    return jsonify({'deviceId': key, **_public_policy(policies[key])})


# ── Household requests — shared "Up Next" per room ───────────────────────────
# Anyone in the house adds a track to a room's up-next from their phone. In a
# kid-safe room with requireApproval, requests wait as "queued" until a parent
# approves; otherwise they're "approved" immediately. Approved requests splice
# into the room's playback at the next track boundary.

REQUESTS_PATH = os.path.join(DATA_DIR, 'requests.json')
_REQUESTS_LOCK = threading.Lock()


def _load_requests():
    try:
        with open(REQUESTS_PATH) as f:
            data = json.load(f)
        if isinstance(data, dict) and isinstance(data.get('rooms'), dict):
            return data
    except Exception:
        pass
    return {'rooms': {}}


def _save_requests(data):
    _atomic_json_write(REQUESTS_PATH, data)


def _room_request_list(key, data=None):
    data = data or _load_requests()
    return ((data.get('rooms', {}).get(key)) or {}).get('queue', [])


def _request_public(r, household=None):
    return {
        'id': r.get('id'),
        'path': r.get('path'),
        'track': r.get('track'),
        'artist': r.get('artist'),
        'byMemberId': r.get('byMemberId'),
        'byMemberName': _member_label(r.get('byMemberId'), household),
        'status': r.get('status'),
        'ts': r.get('ts'),
    }


def _room_upnext_public(key, household=None):
    """Pending/approved requests for a room (for now-playing UI)."""
    h = household or _load_household()
    return [_request_public(r, h) for r in _room_request_list(key)
            if r.get('status') in ('queued', 'approved')]


def _consume_next_request(room_key):
    """Pop (FIFO) the next approved request for a room. Returns its path or None."""
    if not room_key:
        return None
    with _REQUESTS_LOCK:
        data = _load_requests()
        room = data.get('rooms', {}).get(room_key)
        if not room:
            return None
        for r in room.get('queue', []):
            if r.get('status') == 'approved':
                r['status'] = 'done'
                _save_requests(data)
                return r.get('path')
    return None


@app.route('/api/rooms/<device_id>/queue')
def room_queue(device_id):
    key = _room_key(device_id)
    h = _load_household()
    return jsonify({
        'deviceId': key,
        'deviceName': _device_label(key),
        'queue': [_request_public(r, h) for r in _room_request_list(key)],
    })


@app.route('/api/rooms/<device_id>/requests', methods=['POST'])
def room_add_request(device_id):
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    member_id = resolve_play_member(device_id=device_id,
                                    client_id=(body.get('clientId') or '').strip(),
                                    explicit_member=(body.get('memberId') or '').strip())
    key = _room_key(device_id)
    policy = _policy_for(device_id)
    if policy.get('safe') and policy.get('allowExplicit') is False and _is_explicit_path(path):
        return jsonify({'error': 'explicit_blocked', 'code': 'explicit_blocked'}), 403
    track = (body.get('track') or '').strip()
    artist = (body.get('artist') or '').strip()
    if not track:
        title, art, _album, _ = track_metadata_fast(path)
        track, artist = title, (artist or art)
    needs_approval = bool(policy.get('safe') and policy.get('requireApproval'))
    item = {
        'id': 'rq-' + uuid.uuid4().hex[:10],
        'path': path,
        'track': track,
        'artist': artist or None,
        'byMemberId': member_id or None,
        'status': 'queued' if needs_approval else 'approved',
        'ts': time.time(),
    }
    with _REQUESTS_LOCK:
        data = _load_requests()
        room = data['rooms'].setdefault(key, {'queue': []})
        room.setdefault('queue', []).append(item)
        _save_requests(data)
    return jsonify(_request_public(item)), 201


@app.route('/api/rooms/<device_id>/requests/<rid>/approve', methods=['POST'])
def room_approve_request(device_id, rid):
    body = request.get_json(silent=True) or {}
    if not _parent_pin_ok(body.get('memberId'), str(body.get('pin') or '')):
        return jsonify({'error': 'parent_pin_required'}), 403
    key = _room_key(device_id)
    with _REQUESTS_LOCK:
        data = _load_requests()
        for r in _room_request_list(key, data):
            if r.get('id') == rid:
                r['status'] = 'approved'
                _save_requests(data)
                return jsonify(_request_public(r))
    return jsonify({'error': 'not_found'}), 404


@app.route('/api/rooms/<device_id>/requests/<rid>', methods=['DELETE'])
def room_delete_request(device_id, rid):
    key = _room_key(device_id)
    with _REQUESTS_LOCK:
        data = _load_requests()
        room = data.get('rooms', {}).get(key) or {}
        q = room.get('queue', [])
        new_q = [r for r in q if r.get('id') != rid]
        if len(new_q) == len(q):
            return jsonify({'error': 'not_found'}), 404
        room['queue'] = new_q
        _save_requests(data)
    return jsonify({'ok': True})


@app.route('/api/rooms/<device_id>/requests/reorder', methods=['POST'])
def room_reorder_requests(device_id):
    body = request.get_json(silent=True) or {}
    order = body.get('order') or []
    if not isinstance(order, list):
        return jsonify({'error': 'order must be a list'}), 400
    key = _room_key(device_id)
    with _REQUESTS_LOCK:
        data = _load_requests()
        room = data.get('rooms', {}).get(key) or {}
        q = room.get('queue', [])
        rank = {rid: i for i, rid in enumerate(order)}
        q.sort(key=lambda r: rank.get(r.get('id'), len(order)))
        room['queue'] = q
        data.setdefault('rooms', {})[key] = room
        _save_requests(data)
    return jsonify({'ok': True, 'queue': [_request_public(r) for r in q]})


# ── Playlist ownership & sharing ─────────────────────────────────────────────
# A sidecar (so ServerPlaylists.xml stays untouched) records who owns a playlist
# and who can see it. Legacy playlists with no meta are treated as household-
# visible so nothing disappears.

PLAYLIST_META_PATH = os.path.join(DATA_DIR, 'playlist_meta.json')
_PLAYLIST_META_LOCK = threading.Lock()


def _load_playlist_meta():
    try:
        with open(PLAYLIST_META_PATH) as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _save_playlist_meta(data):
    _atomic_json_write(PLAYLIST_META_PATH, data)


def _playlist_visible_to(meta_entry, member_id):
    if not meta_entry:
        return True  # legacy = household-visible
    owner = meta_entry.get('ownerMemberId')
    if owner and owner == member_id:
        return True
    vis = meta_entry.get('visibility') or 'household'
    if vis == 'household':
        return True
    if vis == 'shared':
        return member_id in (meta_entry.get('sharedWith') or [])
    return False  # private, non-owner


def _public_playlist_meta(entry, household=None):
    if not entry:
        return {'ownerMemberId': None, 'ownerName': '', 'visibility': 'household',
                'sharedWith': [], 'daily': False}
    return {
        'ownerMemberId': entry.get('ownerMemberId'),
        'ownerName': _member_label(entry.get('ownerMemberId'), household),
        'visibility': entry.get('visibility') or 'household',
        'sharedWith': entry.get('sharedWith') or [],
        'daily': bool(entry.get('daily')),
        'dailyRecipe': entry.get('dailyRecipe'),
    }


def _set_playlist_owner(playlist_id, member_id, visibility='household'):
    if not playlist_id:
        return
    with _PLAYLIST_META_LOCK:
        meta = _load_playlist_meta()
        cur = meta.get(playlist_id, {})
        cur.setdefault('createdAt', time.time())
        if member_id:
            cur['ownerMemberId'] = member_id
        cur.setdefault('visibility', visibility)
        cur.setdefault('sharedWith', [])
        meta[playlist_id] = cur
        _save_playlist_meta(meta)


@app.route('/api/playlists/<playlist_id>/share', methods=['POST'])
def share_playlist(playlist_id):
    body = request.get_json(silent=True) or {}
    to_members = body.get('toMemberIds') or []
    if not isinstance(to_members, list) or not to_members:
        return jsonify({'error': 'toMemberIds required'}), 400
    household = _load_household()
    to_members = [m for m in to_members if _member_by_id(m, household)]
    with _PLAYLIST_META_LOCK:
        meta = _load_playlist_meta()
        cur = meta.get(playlist_id, {})
        actor = resolve_play_member(client_id=(body.get('clientId') or '').strip(),
                                    explicit_member=(body.get('memberId') or '').strip(),
                                    household=household)
        if actor and not cur.get('ownerMemberId'):
            cur['ownerMemberId'] = actor
        shared = set(cur.get('sharedWith') or [])
        shared.update(to_members)
        cur['sharedWith'] = sorted(shared)
        if shared:
            cur['visibility'] = 'shared'
        elif (cur.get('visibility') or 'household') == 'private':
            cur['visibility'] = 'shared'
        cur.setdefault('createdAt', time.time())
        meta[playlist_id] = cur
        _save_playlist_meta(meta)
    # Drop a note in each recipient's message inbox (P5).
    try:
        name = _msp_playlist_by_id(playlist_id)[0] or 'a playlist'
        for m in to_members:
            _post_message(from_member=cur.get('ownerMemberId'), to_member=m,
                          scope='direct', text=f'shared "{name}" with you',
                          attach={'type': 'playlist', 'id': playlist_id})
    except Exception:
        pass
    return jsonify({'ok': True, 'playlistId': playlist_id,
                    **_public_playlist_meta(meta[playlist_id], household)})


@app.route('/api/playlists/<playlist_id>/visibility', methods=['POST'])
def set_playlist_visibility(playlist_id):
    body = request.get_json(silent=True) or {}
    vis = (body.get('visibility') or '').strip().lower()
    if vis not in ('private', 'household', 'shared'):
        return jsonify({'error': 'visibility must be private|household|shared'}), 400
    with _PLAYLIST_META_LOCK:
        meta = _load_playlist_meta()
        cur = meta.get(playlist_id, {})
        cur['visibility'] = vis
        cur.setdefault('sharedWith', [])
        cur.setdefault('createdAt', time.time())
        meta[playlist_id] = cur
        _save_playlist_meta(meta)
    return jsonify({'ok': True, **_public_playlist_meta(meta[playlist_id])})


@app.route('/api/playlists/<playlist_id>/copy', methods=['POST'])
def copy_playlist(playlist_id):
    body = request.get_json(silent=True) or {}
    name, source = _msp_playlist_by_id(playlist_id)
    if not source:
        return jsonify({'error': 'not_found'}), 404
    tracks = _tracks_from_source(source)
    if not tracks:
        return jsonify({'error': 'playlist has no tracks'}), 400
    actor = resolve_play_member(client_id=(body.get('clientId') or '').strip(),
                                explicit_member=(body.get('memberId') or '').strip())
    new_name = (body.get('name') or '').strip() or f'{name} (copy)'
    new_pid = str(uuid.uuid4())
    result = _persist_playlist(new_pid, new_name, tracks, create=True)
    if not result:
        return jsonify({'error': 'copy_failed'}), 500
    _set_playlist_owner(new_pid, actor, visibility='private')
    return jsonify({**result, **_public_playlist_meta(_load_playlist_meta().get(new_pid))}), 201


# ── Music messages (family chat about music) ─────────────────────────────────
# Lightweight, music-anchored messaging between household members. Not a general
# chat app: each message is text plus an optional track/album/playlist attachment.

MESSAGES_PATH = os.path.join(DATA_DIR, 'messages.jsonl')
_MESSAGES_LOCK = threading.Lock()


def _post_message(*, from_member, to_member=None, scope='household', text='',
                  attach=None):
    msg = {
        'id': 'm-' + uuid.uuid4().hex[:10],
        'fromMemberId': from_member or None,
        'toMemberId': to_member or None,
        'scope': scope or 'household',
        'text': text or '',
        'attach': attach or None,
        'ts': time.time(),
        'readBy': [],
    }
    with _MESSAGES_LOCK:
        with open(MESSAGES_PATH, 'a') as f:
            f.write(json.dumps(msg) + '\n')
    return msg


def _read_messages(limit=1000):
    out = []
    try:
        with open(MESSAGES_PATH) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except Exception:
                    continue
    except Exception:
        return []
    return out[-limit:]


def _message_visible_to(m, member_id):
    scope = m.get('scope') or 'household'
    if scope == 'household' or scope.startswith('room:'):
        return True
    return m.get('toMemberId') == member_id or m.get('fromMemberId') == member_id


def _public_message(m, household=None):
    return {
        'id': m.get('id'),
        'fromMemberId': m.get('fromMemberId'),
        'fromName': _member_label(m.get('fromMemberId'), household),
        'toMemberId': m.get('toMemberId'),
        'toName': _member_label(m.get('toMemberId'), household),
        'scope': m.get('scope'),
        'text': m.get('text'),
        'attach': m.get('attach'),
        'ts': m.get('ts'),
        'readBy': m.get('readBy') or [],
    }


@app.route('/api/messages')
def messages_list():
    member = (request.args.get('member') or '').strip()
    if not member and (request.args.get('clientId') or '').strip():
        member = member_for_client(request.args.get('clientId').strip()) or ''
    household = _load_household()
    msgs = _read_messages()
    visible = [m for m in msgs if _message_visible_to(m, member)] if member else msgs
    unread = sum(1 for m in visible
                 if member and member not in (m.get('readBy') or [])
                 and m.get('fromMemberId') != member)
    return jsonify({
        'items': [_public_message(m, household) for m in visible],
        'unread': unread,
    })


@app.route('/api/messages', methods=['POST'])
def messages_post():
    body = request.get_json(silent=True) or {}
    from_member = resolve_play_member(client_id=(body.get('clientId') or '').strip(),
                                      explicit_member=(body.get('fromMemberId') or '').strip())
    to_member = (body.get('toMemberId') or '').strip() or None
    scope = (body.get('scope') or '').strip() or ('direct' if to_member else 'household')
    text = (body.get('text') or '').strip()
    attach = body.get('attach') if isinstance(body.get('attach'), dict) else None
    if not text and not attach:
        return jsonify({'error': 'text or attach required'}), 400
    msg = _post_message(from_member=from_member, to_member=to_member,
                        scope=scope, text=text, attach=attach)
    return jsonify(_public_message(msg)), 201


@app.route('/api/messages/<msg_id>/read', methods=['POST'])
def messages_mark_read(msg_id):
    body = request.get_json(silent=True) or {}
    member = resolve_play_member(client_id=(body.get('clientId') or '').strip(),
                                 explicit_member=(body.get('memberId') or '').strip())
    if not member:
        return jsonify({'error': 'memberId required'}), 400
    with _MESSAGES_LOCK:
        msgs = _read_messages(limit=100000)
        found = False
        for m in msgs:
            if m.get('id') == msg_id:
                rb = set(m.get('readBy') or [])
                rb.add(member)
                m['readBy'] = sorted(rb)
                found = True
                break
        if found:
            tmp = MESSAGES_PATH + '.tmp'
            with open(tmp, 'w') as f:
                for m in msgs:
                    f.write(json.dumps(m) + '\n')
            os.replace(tmp, MESSAGES_PATH)
    return jsonify({'ok': found})


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
    member_id = resolve_play_member(client_id=client_id,
                                    explicit_member=(body.get('memberId') or '').strip())

    if event == 'connect':
        _record_client_connect(did)
        restored = _rebind_client_from_phone(client_id, body.get('phoneId'))
        return jsonify({
            'ok': True,
            'deviceId': did,
            'memberId': restored,
        })

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
            'memberId': member_id or None,
            'date': now_iso,
        })
        _write_client_np_state(did, {
            'track': track,
            'artist': artist,
            'album': album,
            'filepath': filepath,
            'playlist': (body.get('playlist') or '').strip() or None,
            'playlistId': (body.get('playlistId') or body.get('playlist_id') or '').strip() or None,
            'sourceLabel': (body.get('sourceLabel') or body.get('source_label') or '').strip() or None,
            'playing': True,
            'paused': False,
            'offset_ms': 0,
            'duration_ms': int(body.get('duration_ms') or body.get('durationMs') or 0),
        }, platform=platform)
        return jsonify({'ok': True, 'deviceId': did})

    if event == 'playback':
        _write_client_np_state(did, body, platform=platform)
        bock_continue.update_from_playback(
            PLAYBACK_RESUME_PATH, member_id, body,
        )
        return jsonify({'ok': True, 'deviceId': did})

    # download
    title = (body.get('collectionTitle') or body.get('title') or '').strip()
    kind = (body.get('collectionKind') or body.get('kind') or '').strip()
    track_count = int(body.get('trackCount') or body.get('tracks') or 0)
    append_download_history({
        'deviceId': did,
        'device': label,
        'platform': platform or None,
        'memberId': member_id or None,
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
    device_id_str = request.args.get('deviceId', '').strip()
    member_str = request.args.get('member', '').strip()
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
    device_store = _load_devices()
    rows = _filter_history_by_device(rows, device_id_str, device_store)
    household = _load_household()
    rows = _filter_history_by_member(rows, member_str, household)
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


@app.route('/api/analytics/household')
def analytics_household():
    """Family overview: plays per member, per room, platform split, leaderboard."""
    from collections import Counter
    from_str = request.args.get('from', '').strip()
    to_str = request.args.get('to', '').strip()
    from_dt = to_dt = None
    try:
        if from_str:
            from_dt = datetime.datetime.fromisoformat(from_str)
        if to_str:
            to_dt = datetime.datetime.fromisoformat(to_str).replace(hour=23, minute=59, second=59)
    except Exception:
        pass
    rows = _filter_history_rows(_read_stream_history(), from_dt, to_dt)
    rows = [r for r in rows if not r.get('test')
            and r.get('deviceId', '') not in ('DEVICE_ALPHA', 'DEVICE_BETA')]
    household = _load_household()
    device_store = _load_devices()
    per_member = Counter()
    per_room = Counter()
    per_platform = Counter()
    for r in rows:
        mid = _row_member(r, household) or 'unattributed'
        per_member[mid] += 1
        did = r.get('deviceId') or ''
        per_room[_device_label(did) if did else (r.get('device') or 'Unknown')] += 1
        per_platform[_row_platform(r) or 'unknown'] += 1

    def _named(counter):
        out = []
        for k, v in counter.most_common():
            if k == 'unattributed':
                out.append({'memberId': None, 'name': 'Unattributed', 'plays': v})
            else:
                out.append({'memberId': k, 'name': _member_label(k, household) or k, 'plays': v})
        return out

    members = [_public_member(m) for m in household.get('members', [])]
    pl_meta = _load_playlist_meta()
    shares = [{'playlistId': pid, 'ownerName': _member_label(e.get('ownerMemberId'), household),
               'sharedWith': [_member_label(x, household) for x in (e.get('sharedWith') or [])]}
              for pid, e in pl_meta.items() if e.get('sharedWith')]
    return jsonify({
        'totalPlays': len(rows),
        'members': members,
        'byMember': _named(per_member),
        'byRoom': [{'room': k, 'plays': v} for k, v in per_room.most_common()],
        'byPlatform': [{'platform': k, 'plays': v} for k, v in per_platform.most_common()],
        'leaderboard': _named(per_member)[:10],
        'shares': shares,
        'dateRange': {'from': from_str, 'to': to_str},
    })


@app.route('/api/analytics')
def analytics():
    from collections import Counter, defaultdict
    from_str = request.args.get('from', '').strip()
    to_str   = request.args.get('to',   '').strip()
    device_id_str = request.args.get('deviceId', '').strip()
    member_str = request.args.get('member', '').strip()
    platform_str = request.args.get('platform', '').strip()
    cache_key = (from_str, to_str, device_id_str, member_str, platform_str)
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

    device_store = _load_devices()
    rows = _filter_history_by_device(rows, device_id_str, device_store)
    household = _load_household()
    rows = _filter_history_by_member(rows, member_str, household)
    rows = _filter_history_by_platform(rows, platform_str)

    total = len(rows)
    download_rows = _filter_history_rows(_read_download_history(), from_dt, to_dt)
    download_rows = _filter_history_by_device(download_rows, device_id_str, device_store)
    download_rows = _filter_history_by_member(download_rows, member_str, household)
    download_rows = _filter_history_by_platform(download_rows, platform_str)
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
        'dateRange': {'from': from_str, 'to': to_str, 'deviceId': device_id_str},
    }
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
    def _device_label(row):
        did = row.get('deviceId') or ''
        if did:
            primary = _resolve_device_id(did, device_store)
            entry = device_store.get(primary)
            if entry:
                live = _live_alexa_name(entry, primary, device_store)
                return live or entry.get('name') or row.get('device') or primary[-6:]
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

    top_artists = [{'name': k, 'count': v}                    for k, v in artist_ctr.most_common(50)]
    top_albums  = [{'name': k[0], 'artist': k[1], 'count': v} for k, v in album_ctr.most_common(50)]
    top_tracks  = [{'name': k[0], 'artist': k[1], 'count': v} for k, v in track_ctr.most_common(50)]
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
        'dateRange': {'from': from_str, 'to': to_str, 'deviceId': device_id_str},
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
    playlist = (body.get('playlist') or '').strip() or None
    playlist_id = (body.get('playlistId') or body.get('playlist_id') or '').strip() or None
    source_label = (body.get('sourceLabel') or body.get('source_label') or '').strip() or None
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
        'playlist': playlist,
        'playlistId': playlist_id,
        'sourceLabel': source_label,
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

def _filter_history_by_device(rows, device_id, device_store=None):
    """Keep rows for one primary deviceId (alias-aware)."""
    if not device_id:
        return rows
    if device_store is None:
        device_store = _load_devices()
    target = _resolve_device_id(device_id, device_store)
    if not target:
        return rows
    filtered = []
    for r in rows:
        did = r.get('deviceId') or ''
        primary = _resolve_device_id(did, device_store) if did else ''
        if primary == target:
            filtered.append(r)
    return filtered

def _member_for_did(did, household=None):
    """Member attributed to a deviceId: client install binding or Echo owner."""
    if not did:
        return ''
    h = household or _load_household()
    if _is_client_device(did):
        return h.get('clientBindings', {}).get(did, '') or ''
    return member_for_device(did, h) or ''


def _row_member(row, household=None):
    """Member for a history row: explicit memberId, else the device's owner."""
    mid = row.get('memberId')
    if mid:
        return mid
    return _member_for_did(row.get('deviceId') or '', household)


def _row_platform(row):
    """Platform for a row, inferring 'alexa' for legacy rows on Echo devices."""
    p = (row.get('platform') or '').strip().lower()
    if p:
        return p
    did = row.get('deviceId') or ''
    if did and not _is_client_device(did) and did != 'default':
        return 'alexa'
    return ''


def _filter_history_by_member(rows, member_id, household=None):
    if not member_id:
        return rows
    h = household or _load_household()
    return [r for r in rows if _row_member(r, h) == member_id]


def _filter_history_by_platform(rows, platform):
    if not platform:
        return rows
    p = platform.strip().lower()
    return [r for r in rows if _row_platform(r) == p]


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
                'name': _live_alexa_name(entry, primary, device_store) or entry.get('name') or _device_label(primary),
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


_DEFAULT_ALEXA_PUBLIC_URL = 'https://your-tunnel.example.com'

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
                return jsonify({'error': 'publicUrl must be a tunnel hostname (e.g. https://your-tunnel.example.com), not a raw IP'}), 400
        cfg.update(data)
        with open(CONFIG_PATH, 'w') as f:
            json.dump(cfg, f, indent=2)
        return jsonify({'ok': True})
    cfg = load_config()
    if _mobile_api_only():
        return jsonify(_redact_config(cfg))
    return jsonify(cfg)

# ── Audio Streaming ───────────────────────────────────────────────────────────

MUSIC_ROOT = os.environ.get('OURMEDIA_MUSIC_ROOT', _DEMO_MUSIC_ROOT)
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

def _stream_loudness_mode():
    """Effective loudness mode for this /stream request."""
    mode = bock_loudness.normalize_mode_from_pref(get_pref('ReplayGain', 'off'))
    norm_q = (request.args.get('normalize') or '').strip()
    if norm_q == '1':
        return mode if mode != 'off' else 'loudnorm'
    if norm_q == '0':
        return None
    return mode if mode != 'off' else None


def _db_path_keys(full_path):
    """Lookup keys for songs_cache (absolute or relative)."""
    rel = full_path.lstrip('/')
    return [full_path, rel, '/' + rel]


def _stream_audio_filters(full_path, ffmpeg_bin):
    mode = _stream_loudness_mode()
    if not mode:
        return None, None, None
    row = None
    for key in _db_path_keys(full_path):
        row = db_one(
            'SELECT path, replaygain_track_db, replaygain_album_db, loudness_lufs '
            'FROM songs_cache WHERE path = ?',
            [key],
        )
        if row:
            break
    db_path = (row or {}).get('path') or full_path
    gain = bock_loudness.gain_db_for_path(db_one, db_path, mode)
    # Never fall back to realtime `loudnorm` analysis on a normal stream: it spawns a
    # CPU-bound ffmpeg per request and melts the server when many un-analyzed tracks
    # play back-to-back. Un-analyzed tracks stream as-is; run the loudness analyze job
    # to populate replaygain values for cheap volume-filter normalization instead.
    af = bock_loudness.ffmpeg_af_filter(mode, gain, use_loudnorm_fallback=False)
    return af, mode, gain


# Cap concurrent live transcodes so a burst of plays can't saturate every core.
# Each ffmpeg is already niced, but unbounded fan-out still melts the box; when at
# the cap, native files fall back to serving original bytes instead of queueing.
_MAX_STREAM_TRANSCODES = max(2, (os.cpu_count() or 4) // 2)
_stream_transcode_sem = threading.BoundedSemaphore(_MAX_STREAM_TRANSCODES)


@app.route('/stream/<path:filepath>')
def stream_audio(filepath):
    title = (request.args.get('title') or '').strip() or None
    artist = (request.args.get('artist') or '').strip() or None
    full_path = _resolve_library_path('/' + filepath.lstrip('/'), title=title, artist=artist)
    if not full_path:
        return 'Not found', 404
    ext = os.path.splitext(full_path)[1].lower()

    # Optional client-requested transcode bitrate (kbps). Low-bandwidth clients
    # (e.g. cellular downloads) pass ?br=128 to pull a small MP3 instead of the
    # full-size original, syncing a playlist in a fraction of the bytes.
    br = (request.args.get('br') or '').strip()
    req_bitrate = max(48, min(int(br), 320)) if br.isdigit() else None

    is_flac_ext = ext in TRANSCODE_EXTS
    transcode = req_bitrate is not None or is_flac_ext
    af_filter, _norm_mode, gain_db = _stream_audio_filters(full_path, get_pref('FFmpegLocation', '').strip() or 'ffmpeg')
    # Attenuation-only on native files: serve the original MP3/M4A and let clients
    # lower volume — avoids a per-play ffmpeg transcode that stalls/fails under load.
    gain_only = (
        af_filter and af_filter.startswith('volume=')
        and ext in NATIVE_EXTS and req_bitrate is None
        and gain_db is not None and gain_db <= 0
    )
    if gain_only:
        af_filter = None
    if af_filter and not transcode:
        transcode = True
    # Non-native formats still require FlacSupport unless the client explicitly
    # asked for a bitrate (an intentional download/transcode request).
    if transcode and is_flac_ext and req_bitrate is None and get_pref('FlacSupport', '').lower() != 'true':
        return 'Transcoding not enabled', 415
    if transcode and not _ffmpeg_available():
        if is_flac_ext:
            return 'Transcoding not available', 415
        transcode = False  # native file with no ffmpeg → serve original bytes

    if transcode:
        # Non-blocking grab; FLAC must transcode so it waits briefly, native files
        # at capacity just serve original bytes so playback never stalls.
        acquired = _stream_transcode_sem.acquire(blocking=False)
        if not acquired and is_flac_ext:
            acquired = _stream_transcode_sem.acquire(timeout=15)
        if not acquired:
            transcode = False

    if transcode:
        ffmpeg_bin = get_pref('FFmpegLocation', '').strip() or 'ffmpeg'
        bitrate    = str(req_bitrate) if req_bitrate is not None \
            else (get_pref('TranscodeBitrate', '128').strip() or '128')
        cmd = bock_loudness.nice_prefix(level=10, io_idle=False) + [
            ffmpeg_bin, '-threads', '1', '-i', full_path, '-ar', '44100', '-ac', '2',
        ]
        if af_filter:
            cmd += ['-af', af_filter]
        cmd += ['-b:a', f'{bitrate}k', '-write_xing', '0', '-f', 'mp3', '-flush_packets', '1', '-']
        def _generate():
            try:
                proc = subprocess.Popen(
                    cmd,
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
            finally:
                _stream_transcode_sem.release()
        return Response(_generate(), mimetype='audio/mpeg',
                        headers={'Accept-Ranges': 'none'})

    mime = 'audio/mpeg' if ext == '.mp3' else 'audio/mp4' if ext == '.m4a' else 'audio/aac'
    accel = _xaccel_send(full_path, mime)
    if accel is not None:
        if gain_db is not None:
            accel.headers['X-Bock-ReplayGain-Db'] = f'{gain_db:.2f}'
        return accel
    resp = send_file(full_path, mimetype=mime, conditional=True)
    if gain_db is not None:
        resp.headers['X-Bock-ReplayGain-Db'] = f'{gain_db:.2f}'
    return resp

_ART_MIME_BY_EXT = {
    '.jpg':  'image/jpeg', '.jpeg': 'image/jpeg',
    '.png':  'image/png',  '.webp': 'image/webp',
}

def _resolve_artwork_abs_path(filepath):
    """Map /artwork/ URL path to an on-disk image (library, artwork_cache, static)."""
    rel = (filepath or '').lstrip('/')
    if rel.startswith('artwork_cache/'):
        return os.path.abspath(os.path.join(ARTWORK_CACHE, rel[len('artwork_cache/'):]))
    resolved = _resolve_library_path('/' + rel)
    if resolved and os.path.isfile(resolved):
        return os.path.abspath(resolved)
    under_here = os.path.join(HERE, rel)
    if os.path.isfile(under_here):
        return os.path.abspath(under_here)
    return os.path.abspath('/' + rel)

@app.route('/artwork/<path:filepath>')
def serve_artwork(filepath):
    abs_path = _resolve_artwork_abs_path(filepath)
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

def file_to_stream_url(filepath, **query):
    rel = filepath.lstrip('/')
    path = f"/stream/{quote(rel, safe='/')}"
    signed = _append_media_sig(path, query or None)
    return f"{get_public_url()}{signed}"

def file_to_artwork_url(filepath, **query):
    rel = filepath.lstrip('/')
    path = f"/artwork/{quote(rel, safe='/')}"
    signed = _append_media_sig(path, query or None)
    return f"{get_public_url()}{signed}"

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
_QUEUES_FLOCK_PATH = os.path.join(HERE, '.queues.lock')

def _load_queues():
    with _QUEUES_LOCK:
        with _cross_process_flock(_QUEUES_FLOCK_PATH):
            try:
                with open(QUEUES_PATH) as f:
                    return json.load(f)
            except:
                return {}

def _save_queues(queues):
    with _QUEUES_LOCK:
        with _cross_process_flock(_QUEUES_FLOCK_PATH):
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
# Alexa emits PlaybackNearlyFinished when this interval is set on the stream.
_NP_PROGRESS_REPORT_MS = 15000

def _resolve_queue_tracks(entry):
    """Materialize track list for a queue entry (lazy playlist refs or inline tracks)."""
    if not entry.get('lazy'):
        return entry.get('tracks') or []
    pid = entry.get('playlist_id')
    source = entry.get('source')
    if not source and pid:
        _, source = _msp_playlist_by_id(pid)
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
                return None
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
        return None


def _upcoming_tracks_for_token(token, limit=5):
    """Next tracks in the active queue for Now Playing UI."""
    data = decode_token(token) or {}
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

def _m3u_has_track(m3u_path, track_path):
    """True when track_path is already listed (path-normalized, no substring false positives)."""
    if not m3u_path or not track_path:
        return False
    target = os.path.normpath(track_path)
    return any(os.path.normpath(p) == target for p in parse_m3u(m3u_path, verify_exists=False))

# ── Playlist library (create / merge / sort / AI) ────────────────────────────

PLAYLISTS_XML = os.path.join(DATA_DIR, 'ServerPlaylists.xml')
BOCK_PLAYLIST_DIR = os.path.join(
    os.environ.get('OURMEDIA_MUSIC_ROOT', _DEMO_MUSIC_ROOT),
    'exportedPlaylists', 'bockmedia',
)
BOCK_SOURCE_NAME = 'bockmedia'
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
    from playlist_xml_lock import playlist_xml_lock
    ET.register_namespace('xsd', _XSD)
    ET.register_namespace('xsi', _XSI)
    with playlist_xml_lock(DATA_DIR, shared=True):
        return ET.parse(PLAYLISTS_XML)


def _save_playlists_tree(tree):
    from playlist_xml_lock import playlist_xml_lock
    with playlist_xml_lock(DATA_DIR, exclusive=True):
        _backup_playlists_xml()
        tmp = PLAYLISTS_XML + '.tmp'
        tree.write(tmp, xml_declaration=True, encoding='utf-8')
        os.replace(tmp, PLAYLISTS_XML)


def _write_m3u_file(path, track_paths):
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.{os.urandom(4).hex()}.tmp"
    try:
        with open(tmp, 'w', encoding='utf-8') as f:
            f.write('#EXTM3U\n')
            for p in track_paths:
                if p and os.path.isfile(p):
                    f.write(p + '\n')
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def _append_m3u_track(m3u_path, track_path):
    """Append a track if not already present (path-normalized, no substring match)."""
    if not m3u_path or not track_path:
        return False
    if _m3u_has_track(m3u_path, track_path):
        return False
    with open(m3u_path, 'a', encoding='utf-8') as f:
        f.write(f'\n{track_path}')
    return True


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


def _music_root_abs():
    return os.path.abspath(MUSIC_ROOT)


def _resolve_library_path(path, title=None, artist=None):
    """Map a playlist/library path to an on-disk file under MUSIC_ROOT.

    Plex .m3u entries and songs_cache can drift apart when files move; prefer
    the indexed path when the stored path no longer exists.
    """
    if not path or not str(path).strip():
        return None
    raw = str(path).strip()
    abs_path = os.path.abspath(raw if raw.startswith('/') else '/' + raw)
    root = _music_root_abs()
    if abs_path.startswith(root) and os.path.isfile(abs_path):
        return abs_path
    base = os.path.basename(abs_path)
    if not base:
        return None
    rows = db_query(
        'SELECT path, title, artist FROM songs_cache WHERE path LIKE ? LIMIT 50',
        [f'%/{base}'],
    ) or []
    if title:
        tl = str(title).strip().lower()
        for row in rows:
            if (row.get('title') or '').strip().lower() == tl:
                p = row.get('path')
                if p:
                    ap = os.path.abspath(p)
                    if ap.startswith(root) and os.path.isfile(ap):
                        return ap
    if artist:
        al = str(artist).strip().lower()
        for row in rows:
            if (row.get('artist') or '').strip().lower() == al:
                p = row.get('path')
                if p:
                    ap = os.path.abspath(p)
                    if ap.startswith(root) and os.path.isfile(ap):
                        return ap
    for row in rows:
        p = row.get('path')
        if p:
            ap = os.path.abspath(p)
            if ap.startswith(root) and os.path.isfile(ap):
                return ap
    return None


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
        title = row.get('title') or fname
        artist = row.get('artist') or ''
        canonical = _resolve_library_path(path, title=title, artist=artist) or path
        out.append({
            'path': canonical,
            'title': title,
            'artist': artist,
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
    """Write ServerPlaylists.xml entry first, then .m3u (crash-safe ordering)."""
    paths = [p for p in track_paths if p and os.path.isfile(p)]
    m3u_path = _m3u_path_for_bock(pid, name)
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
    _write_m3u_file(m3u_path, paths)
    _invalidate_playlist_cover(pid)
    _save_playlist_cover_cache()
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


# pid -> cover_path. Persisted to disk so art survives restarts with no mount I/O
# (each .m3u open is ~60ms on the network mount). Covers only change when a playlist's
# tracks change, so we invalidate explicitly on edit rather than stat()-ing per request.
_PLAYLIST_COVER_CACHE = {}
_PLAYLIST_COVER_CACHE_LOCK = threading.Lock()
_PLAYLIST_COVER_CACHE_PATH = os.path.join(DATA_DIR, 'playlist_cover_cache.json')
_playlist_cover_cache_loaded = False
_playlist_cover_cache_dirty = False


def _load_playlist_cover_cache():
    global _playlist_cover_cache_loaded
    with _PLAYLIST_COVER_CACHE_LOCK:
        if _playlist_cover_cache_loaded:
            return
        _playlist_cover_cache_loaded = True
        try:
            with open(_PLAYLIST_COVER_CACHE_PATH) as f:
                data = json.load(f)
            if isinstance(data, dict):
                _PLAYLIST_COVER_CACHE.update({k: v for k, v in data.items() if isinstance(v, str)})
        except Exception:
            pass


def _save_playlist_cover_cache():
    global _playlist_cover_cache_dirty
    with _PLAYLIST_COVER_CACHE_LOCK:
        if not _playlist_cover_cache_dirty:
            return
        snapshot = dict(_PLAYLIST_COVER_CACHE)
        _playlist_cover_cache_dirty = False
    try:
        _atomic_json_write(_PLAYLIST_COVER_CACHE_PATH, snapshot)
    except Exception:
        pass


def _playlist_cover_fast(pid, source, compute=True):
    """First-track cover path for a playlist (cached, persisted).

    Lets /api/playlists return artPath inline so home/library tiles render art from the
    cached list with no separate cover fetch (fast, survives navigation, any network).
    compute=False returns only an already-cached value (no disk read), bounding latency.
    """
    if not pid:
        return None
    _load_playlist_cover_cache()
    with _PLAYLIST_COVER_CACHE_LOCK:
        if pid in _PLAYLIST_COVER_CACHE:
            return _PLAYLIST_COVER_CACHE[pid] or None
    if not compute or not source or not os.path.isfile(source):
        return None
    paths = _m3u_first_paths(source, limit=8)
    path = paths[0] if paths else None
    global _playlist_cover_cache_dirty
    with _PLAYLIST_COVER_CACHE_LOCK:
        _PLAYLIST_COVER_CACHE[pid] = path or ''
        _playlist_cover_cache_dirty = True
    return path


def _invalidate_playlist_cover(pid):
    global _playlist_cover_cache_dirty
    if not pid:
        return
    with _PLAYLIST_COVER_CACHE_LOCK:
        if pid in _PLAYLIST_COVER_CACHE:
            del _PLAYLIST_COVER_CACHE[pid]
            _playlist_cover_cache_dirty = True


# Bound how many uncached covers a single /api/playlists call computes inline so the
# first cold request stays responsive; a background thread warms the remainder.
_PLAYLIST_COVER_INLINE_BUDGET = 150
_playlist_cover_warm_started = False
_playlist_cover_warm_lock = threading.Lock()


def _warm_playlist_covers():
    """Populate the cover cache for every playlist (first-track art) in the background.

    Throttled so it never saturates the media mount and starve live requests.
    """
    try:
        root = _load_playlists_tree().getroot()
    except Exception:
        return
    for entry in root.findall('Entry'):
        key = entry.find('Key')
        if key is None:
            continue
        pid = xml_text(key, 'ID')
        source = xml_text(key, 'SourceID')
        if not (pid and source):
            continue
        with _PLAYLIST_COVER_CACHE_LOCK:
            already = pid in _PLAYLIST_COVER_CACHE
        if already:
            continue  # loaded from disk — no I/O, no throttle needed
        try:
            _playlist_cover_fast(pid, source, compute=True)
        except Exception:
            pass
        time.sleep(0.02)  # throttle disk reads so warm never starves live requests
    _save_playlist_cover_cache()


def _start_playlist_cover_warm():
    global _playlist_cover_warm_started
    with _playlist_cover_warm_lock:
        if _playlist_cover_warm_started:
            return
        _playlist_cover_warm_started = True
    threading.Thread(target=_warm_playlist_covers, daemon=True, name='cover-warm').start()


def _playlist_collage_paths_from_tree(root, playlist_id, limit=4):
    """Up to [limit] distinct track paths from the start of a playlist — for 2×2 collage tiles."""
    key, _entry = _find_playlist_key(root, playlist_id)
    if key is None:
        return []
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    if not source:
        return []
    paths = []
    seen = set()
    for path in _m3u_first_paths(source, limit=max(limit * 12, 24)):
        if not path or path in seen:
            continue
        seen.add(path)
        paths.append(path)
        if len(paths) >= limit:
            break
    return paths


def _playlist_cover_path_from_tree(root, playlist_id, avoid_paths=None):
    """First track in the playlist file — same art users see when they open the playlist."""
    key, _entry = _find_playlist_key(root, playlist_id)
    if key is None:
        return None
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    if not source:
        return None
    avoid = set(avoid_paths or [])
    for path in _m3u_first_paths(source, limit=24):
        if path not in avoid:
            return path
    return None


@app.route('/api/playlists/<playlist_id>/cover')
def playlist_cover(playlist_id):
    """Fast cover art path for a playlist — reads only the first few .m3u entries."""
    rated_stars = bock_ratings.parse_rated_playlist_id(playlist_id)
    if rated_stars is not None:
        member_id = _ratings_member_from_request()
        songs = bock_ratings.songs_at_stars(RATINGS_PATH, rated_stars, member_id=member_id)
        art_path = songs[0]['id'] if songs else None
        return jsonify({'playlistId': playlist_id, 'path': art_path})
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
    collages = {}
    used_paths = set()
    rated_member_id = _ratings_member_from_request()
    for pid in ids:
        rated_stars = bock_ratings.parse_rated_playlist_id(pid)
        if rated_stars is not None:
            songs = bock_ratings.songs_at_stars(
                RATINGS_PATH, rated_stars, member_id=rated_member_id)
            paths = [s['id'] for s in songs[:4] if s.get('id')]
            if paths:
                collages[pid] = paths
                covers[pid] = paths[0]
                used_paths.add(paths[0])
            continue
        collage = _playlist_collage_paths_from_tree(root, pid)
        if collage:
            collages[pid] = collage
            pick = next((p for p in collage if p not in used_paths), collage[0])
            covers[pid] = pick
            used_paths.add(pick)
            continue
        path = _playlist_cover_path_from_tree(root, pid, avoid_paths=used_paths)
        if path:
            covers[pid] = path
            collages[pid] = [path]
            used_paths.add(path)
    return jsonify({'covers': covers, 'collages': collages})


@app.route('/api/playlists/<playlist_id>')
def playlist_detail(playlist_id):
    page = max(1, int(request.args.get('page', 1)))
    limit = min(max(int(request.args.get('limit', 100)), 1), 500)
    sort_by = (request.args.get('sortBy') or 'title').strip().lower()
    order = (request.args.get('order') or 'asc').strip().lower()
    if sort_by in ('track',):
        sort_by = 'title'
    if sort_by not in ('title', 'artist', 'album', 'path', 'updated'):
        sort_by = 'title'
    if order not in ('asc', 'desc'):
        order = 'asc'
    q = (request.args.get('q') or '').strip()

    rated_stars = bock_ratings.parse_rated_playlist_id(playlist_id)
    if rated_stars is not None:
        member_id = _ratings_member_from_request()
        detail = bock_ratings.rated_playlist_detail(
            RATINGS_PATH, rated_stars, page=page, limit=limit,
            sort_by=sort_by, order=order, q=q, member_id=member_id,
        )
        paths = [t['path'] for t in detail['tracks'] if t.get('path')]
        detail['tracks'] = _enrich_track_paths(paths)
        return jsonify(detail)

    if sort_by not in ('title', 'artist', 'album', 'path'):
        sort_by = 'title'

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
        enriched = _playlist_all_tracks_enriched(playlist_id, source)
        meta_by_path = {t['path']: t for t in enriched if t.get('path')}
        ordered_paths = [
            p for p in ordered_paths
            if q in (meta_by_path.get(p, {}).get('title') or '').lower()
            or q in (meta_by_path.get(p, {}).get('artist') or '').lower()
            or q in (meta_by_path.get(p, {}).get('album') or '').lower()
            or q in os.path.basename(p).lower()
            or q in os.path.splitext(os.path.basename(p))[0].lower()
        ]
    total = len(ordered_paths)
    start = (page - 1) * limit
    page_paths = ordered_paths[start:start + limit]
    tracks = _enrich_track_paths(page_paths)

    ext_meta = _load_playlist_meta().get(playlist_id) or {}
    household = _load_household()

    return jsonify({
        **meta,
        'tracks': tracks,
        'total': total,
        'page': page,
        'limit': limit,
        'sortBy': sort_by,
        'order': order,
        'q': q or None,
        'daily': bool(ext_meta.get('daily')),
        'dailyRecipe': ext_meta.get('dailyRecipe'),
        **_public_playlist_meta(ext_meta, household),
    })


def _tracks_from_source(source):
    # Plex-exported .m3u paths are trusted; skip per-line stat() on network mounts.
    return parse_m3u(source, verify_exists=False) if source and os.path.isfile(source) else []


def _tracks_for_playlist(playlist_id, source, member_id=''):
    """Load playlist tracks using the enriched cache when an id is known."""
    rated = _resolve_rated_playlist(playlist_id, member_id)
    if rated:
        return rated[1]
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
    owner = resolve_play_member(client_id=(body.get('clientId') or '').strip(),
                                explicit_member=(body.get('memberId') or '').strip())
    _set_playlist_owner(pid, owner, visibility=(body.get('visibility') or 'household'))
    return jsonify({**result, **_public_playlist_meta(_load_playlist_meta().get(pid))}), 201


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
            np = os.path.normpath(p)
            if np not in {os.path.normpath(x) for x in merged_paths}:
                merged_paths.append(p)
    if not merged_paths:
        return jsonify({'error': 'no_tracks'}), 400
    if target_id:
        key, _ = _find_playlist_key(root, target_id)
        if key is None:
            return jsonify({'error': 'target_not_found'}), 404
        existing = _tracks_from_source(xml_text(key, 'SourceID'))
        for p in merged_paths:
            if os.path.normpath(p) not in {os.path.normpath(x) for x in existing}:
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
        count = len([p for p in track_paths if os.path.isfile(p)])
        _update_playlist_key(key, name, source, count)
        _save_playlists_tree(tree)
        _write_m3u_file(source, track_paths)
        _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
        _invalidate_playlist_cover(playlist_id)
        return jsonify({'id': playlist_id, 'name': name, 'trackCount': len(track_paths)})
    result = _persist_playlist(playlist_id, name, track_paths, create=False)
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    _invalidate_playlist_cover(playlist_id)
    return jsonify(result)


@app.route('/api/playlists/<playlist_id>/sort', methods=['POST'])
def sort_playlist(playlist_id):
    body = request.get_json(silent=True) or {}
    field = (body.get('by') or body.get('field') or body.get('sortBy') or 'title').strip().lower()
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
        _update_playlist_key(key, meta.get('name'), source, len(sorted_paths))
        _save_playlists_tree(tree)
        _write_m3u_file(source, sorted_paths)
        result = {'id': playlist_id, 'name': meta.get('name'), 'trackCount': len(sorted_paths)}
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    _invalidate_playlist_cover(playlist_id)
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
        _update_playlist_key(key, meta.get('name'), source, len(paths))
        _save_playlists_tree(tree)
        _write_m3u_file(source, paths)
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    _invalidate_playlist_cover(playlist_id)
    return jsonify({'ok': True, 'trackCount': len(paths)})


@app.route('/api/playlists/<playlist_id>/tracks/move', methods=['POST'])
def move_playlist_track(playlist_id):
    """Move one track to a new index in the playlist (manual reorder)."""
    body = request.get_json(silent=True) or {}
    path = (body.get('path') or '').strip()
    if not path:
        return jsonify({'error': 'path required'}), 400
    try:
        to_index = int(body.get('toIndex'))
    except (TypeError, ValueError):
        return jsonify({'error': 'toIndex required'}), 400
    tree = _load_playlists_tree()
    key, _ = _find_playlist_key(tree.getroot(), playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    paths = list(_playlist_paths_cached(playlist_id, source))
    if path not in paths:
        return jsonify({'error': 'track_not_in_playlist'}), 404
    from_index = paths.index(path)
    to_index = max(0, min(to_index, len(paths) - 1))
    if from_index != to_index:
        paths.pop(from_index)
        paths.insert(to_index, path)
    if meta.get('sourceName') == BOCK_SOURCE_NAME:
        _persist_playlist(playlist_id, meta.get('name'), paths, create=False)
    elif source:
        _update_playlist_key(key, meta.get('name'), source, len(paths))
        _save_playlists_tree(tree)
        _write_m3u_file(source, paths)
    else:
        return jsonify({'error': 'not_editable'}), 403
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    _invalidate_playlist_cover(playlist_id)
    return jsonify({'ok': True, 'fromIndex': from_index, 'toIndex': to_index, 'trackCount': len(paths)})


def _continue_after_queue_mode():
    try:
        tree = ET.parse(os.path.join(DATA_DIR, 'Preferences.xml'))
        root = tree.getroot()
        el = root.find('ContinueAfterQueue')
        return ((el.text if el is not None else None) or 'off').strip().lower()
    except Exception:
        return 'off'


def _continue_after_queue_paths(data, tracks, current_idx):
    """Return extra track paths to append when a queue ends, or None."""
    mode = _continue_after_queue_mode()
    if mode in ('', 'off', 'false', '0', 'none'):
        return None
    if not tracks or current_idx < 0 or current_idx >= len(tracks):
        return None
    last_path = tracks[current_idx]
    if not last_path or not os.path.isfile(last_path):
        return None
    try:
        import bock_resonance
        seed_row = db_one('SELECT * FROM songs_cache WHERE path = ?', [last_path]) or {}
        if mode in ('artist', 'artist_radio', 'artist radio'):
            artist = (seed_row.get('artist') or '').strip()
            if not artist:
                return None
            _seed, rows = bock_resonance.build_mix(
                db_query, db_one, 'artist', artist=artist, limit=30)
        elif data.get('playlist_id'):
            _seed, rows = bock_resonance.build_mix(
                db_query, db_one, 'playlist',
                playlist_paths=tracks, limit=30)
        elif (data.get('context') or '').lower().startswith('album') or seed_row.get('album'):
            _seed, rows = bock_resonance.build_mix(
                db_query, db_one, 'album',
                album=seed_row.get('album'), artist=seed_row.get('artist'), limit=30)
        else:
            _seed, rows = bock_resonance.build_mix(
                db_query, db_one, 'song', path=last_path, limit=30)
    except Exception as e:
        print(f'[continue-after-queue] failed: {e}', flush=True)
        return None
    seen = set(tracks)
    extra = [
        r['path'] for r in rows
        if r.get('path') and r['path'] not in seen and os.path.isfile(r['path'])
    ]
    return extra[:30] or None


def _try_continue_queue(data, tracks, idx):
    extra = _continue_after_queue_paths(data, tracks, idx)
    if not extra:
        return None
    new_tracks = (tracks + extra)[:_QUEUE_TRACK_LIMIT]
    next_idx = idx + 1
    if next_idx >= len(new_tracks):
        return None
    base = {k: v for k, v in data.items()
            if k not in ('qid', 'lazy', 'source', 'stopAt', 'stopAfterIdx')}
    base.update({'tracks': new_tracks, 'idx': next_idx, 'loop': False})
    return new_tracks[next_idx], encode_token(base)


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
    import bock_play_counts
    rules = rules if isinstance(rules, list) else []
    clauses, params = ['path IS NOT NULL'], []
    order = 'RANDOM()'
    limit = 50
    play_min = play_max = None
    stale_days = None
    member_top = None
    album_like = path_contains = decade = None
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
        elif rtype == 'album' and val:
            album_like = str(val).lower()
        elif rtype == 'path_contains' and val:
            path_contains = str(val).lower()
        elif rtype == 'year_min' and val is not None:
            clauses.append('CAST(year AS INTEGER) >= ?')
            params.append(int(val))
        elif rtype == 'year_max' and val is not None:
            clauses.append('CAST(year AS INTEGER) <= ?')
            params.append(int(val))
        elif rtype == 'decade' and val is not None:
            decade = int(val)
        elif rtype == 'play_count_min' and val is not None:
            play_min = int(val)
        elif rtype == 'play_count_max' and val is not None:
            play_max = int(val)
        elif rtype == 'not_played_since_days' and val is not None:
            stale_days = int(val)
        elif rtype == 'member_top' and val:
            member_top = str(val)
        elif rtype == 'limit' and val:
            limit = min(max(int(val), 1), 500)
        elif rtype == 'order':
            ov = str(val).lower()
            if ov == 'title':
                order = 'title COLLATE NOCASE'
            elif ov == 'artist':
                order = 'artist COLLATE NOCASE'
            elif ov == 'random':
                order = 'RANDOM()'
            elif ov == 'date_added':
                order = 'first_seen_at DESC'
    if album_like:
        clauses.append('LOWER(COALESCE(album,"")) LIKE ?')
        params.append(f'%{album_like}%')
    if path_contains:
        clauses.append('LOWER(path) LIKE ?')
        params.append(f'%{path_contains}%')
    if decade is not None:
        y0, y1 = decade, decade + 9
        clauses.append('CAST(year AS INTEGER) BETWEEN ? AND ?')
        params.extend([y0, y1])
    sql = (
        'SELECT path FROM songs_cache WHERE ' + ' AND '.join(clauses) +
        f' ORDER BY {order} LIMIT ?'
    )
    params.append(limit * 4)
    rows = db_query(sql, params) or []
    counts_data = bock_play_counts.load_counts(PLAY_COUNTS_PATH)
    path_counts = counts_data.get('paths') or {}
    if member_top:
        path_counts = (counts_data.get('byMember') or {}).get(member_top) or path_counts
    seen, paths = set(), []
    stale_cutoff = None
    if stale_days:
        import datetime
        stale_cutoff = time.time() - stale_days * 86400
    for r in rows:
        p = r.get('path')
        if not p or not os.path.isfile(p) or p in seen:
            continue
        pc = int(path_counts.get(p, 0))
        if play_min is not None and pc < play_min:
            continue
        if play_max is not None and pc > play_max:
            continue
        if stale_days is not None and pc > 0:
            continue
        seen.add(p)
        paths.append(p)
        if len(paths) >= limit:
            break
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


# ── Daily auto-generated playlists ────────────────────────────────────────────

DAILY_STATE_PATH = os.path.join(DATA_DIR, 'daily_playlists.json')
_DAILY_LOCK = threading.Lock()
_daily_scheduler_started = False


def _merge_playlist_meta(pid, updates, member_id=None):
    if not pid:
        return
    with _PLAYLIST_META_LOCK:
        meta = _load_playlist_meta()
        cur = dict(meta.get(pid) or {})
        cur.update(updates or {})
        if member_id:
            cur['ownerMemberId'] = member_id
            cur['dailyMemberId'] = member_id
            cur.setdefault('visibility', 'private')
        meta[pid] = cur
        _save_playlist_meta(meta)


def _daily_member_ids():
    h = _load_household()
    mids = [m.get('id') for m in (h.get('members') or []) if m.get('id')]
    return mids or ['household']


def _play_counts_for_member(member_id):
    import bock_play_counts
    data = bock_play_counts.load_counts(PLAY_COUNTS_PATH) or {}
    mid = (member_id or 'household').strip() or 'household'
    by = (data.get('byMember') or {})
    return by.get(mid) or data.get('paths') or {}


def _regenerate_daily_playlists(force=False, member_id=None):
    """Rebuild today's daily playlists (idempotent within a day unless forced)."""
    import bock_daily
    with _DAILY_LOCK:
        mids = [member_id] if member_id else _daily_member_ids()
        counts_by = {mid: _play_counts_for_member(mid) for mid in mids}

        def set_meta(pid, meta):
            mid = (meta or {}).get('dailyMemberId') or 'household'
            _merge_playlist_meta(pid, meta, member_id=mid if mid != 'household' else None)

        return bock_daily.regenerate_all(
            state_path=DAILY_STATE_PATH,
            member_ids=mids,
            db_query=db_query,
            persist_playlist=_persist_playlist,
            new_id=lambda: str(uuid.uuid4()),
            set_meta=set_meta,
            play_counts_by_member=counts_by,
            force=force,
        )


def _daily_scheduler_loop():
    # Regenerate shortly after boot, then check hourly for date rollover.
    time.sleep(20)
    while True:
        try:
            _regenerate_daily_playlists()
        except Exception as e:
            print(f'daily playlist scheduler error: {e}', flush=True)
        time.sleep(3600)


def _start_daily_scheduler():
    global _daily_scheduler_started
    if _daily_scheduler_started:
        return
    _daily_scheduler_started = True
    threading.Thread(target=_daily_scheduler_loop, daemon=True, name='daily-playlists').start()


@app.route('/api/daily-playlists')
def api_daily_playlists():
    import bock_daily
    member = (request.args.get('member') or '').strip()
    if not member:
        client_id = (request.args.get('clientId') or '').strip()
        member = member_for_client(client_id) or 'household'
    else:
        member = member or 'household'
    state = bock_daily.load_state(DAILY_STATE_PATH, member)
    if bock_daily.is_stale(state):
        try:
            _regenerate_daily_playlists(member_id=member)
        except Exception as e:
            print(f'daily playlist generate error: {e}', flush=True)
    return jsonify(bock_daily.list_daily(DAILY_STATE_PATH, member))


@app.route('/api/daily-playlists/refresh', methods=['POST'])
def api_daily_playlists_refresh():
    state = _regenerate_daily_playlists(force=True)
    return jsonify({'ok': True, 'generatedAt': (state or {}).get('generatedAt')})


@app.route('/api/daily-playlists/<playlist_id>/save', methods=['POST'])
def api_daily_playlist_save(playlist_id):
    """Keep a daily playlist forever: drop it from the daily set and clear the
    daily flag so the regenerator never overwrites it again."""
    import bock_daily
    root = _load_playlists_tree().getroot()
    key, _entry = _find_playlist_key(root, playlist_id)
    if key is None:
        return jsonify({'error': 'not_found'}), 404

    body = request.get_json(silent=True) or {}
    new_name = (body.get('name') or '').strip()
    if not new_name:
        cur = xml_text(key, 'Name') or 'Saved playlist'
        new_name = cur[len(bock_daily.NAME_PREFIX):].strip() if cur.startswith(bock_daily.NAME_PREFIX) else cur
        new_name = f'{new_name}'.strip() or 'Saved playlist'

    # Rename in place (keeps the same id, tracks, and m3u contents).
    meta = _playlist_meta_from_key(key)
    source = meta.get('source') or ''
    paths = _playlist_paths_cached(playlist_id, source) if source else []
    _persist_playlist(playlist_id, new_name, paths, create=False)

    bock_daily.detach_saved(DAILY_STATE_PATH, playlist_id)
    _merge_playlist_meta(playlist_id, {'daily': False, 'savedFromDaily': True})
    _PLAYLIST_TRACKS_CACHE.pop(playlist_id, None)
    _invalidate_playlist_cover(playlist_id)
    return jsonify({'ok': True, 'id': playlist_id, 'name': new_name, 'trackCount': len(paths)})


@app.route('/api/playlists/ai', methods=['POST'])
def ai_playlist():
    """Legacy alias for Mix Muse."""
    body = request.get_json(silent=True) or {}
    prompt = (body.get('prompt') or '').strip()
    if not prompt:
        return jsonify({'error': 'prompt required'}), 400
    max_tracks = min(max(int(body.get('maxTracks') or 25), 1), 80)
    save = bool(body.get('save'))
    try:
        import bock_mix_muse
        candidates = bock_mix_muse.candidates_for_prompt(db_query, prompt, limit=400)
        ai_name, paths = bock_mix_muse.pick_tracks(prompt, candidates, max_tracks, load_config)
    except ValueError as e:
        code = str(e)
        status = 503 if 'not_configured' in code else 400
        return jsonify({'error': code.replace('llm_', 'claude_')}), status
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
        tree = _load_playlists_tree()
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
_NP_FLOCK_PATH = os.path.join(HERE, '.nowplaying_state.lock')
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

def _msp_queue_from_device_id(device_id):
    if not _is_msp_pseudo(device_id):
        return ''
    prefix = MSP_DEVICE_ID + ':'
    if device_id.startswith(prefix):
        return device_id[len(prefix):]
    return ''

def _queue_target_label(queue_id):
    """Room label for an MSP queue (from play intent / live roster)."""
    if not queue_id:
        return ''
    entry = (_load_queues() or {}).get(queue_id) or {}
    serial = (entry.get('target_serial') or '').strip()
    live = _alexa_name_for_serial(serial) if serial else ''
    if live:
        return live
    return (entry.get('target_name') or '').strip()


def _msp_room_key_for_queue(queue_id):
    """Room device key for household up-next on an MSP playback queue."""
    if not queue_id:
        return ''
    entry = (_load_queues() or {}).get(queue_id) or {}
    serial = (entry.get('target_serial') or '').strip()
    if serial:
        return _room_key(serial)
    target = (entry.get('target_name') or '').strip().lower()
    if target:
        for did, dev in _load_devices().items():
            if (dev.get('name') or '').strip().lower() == target:
                return _room_key(did)
    return ''

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
    with _NP_LOCK:
        with _cross_process_flock(_NP_FLOCK_PATH, shared=True):
            try:
                with open(NP_STATE_PATH) as f:
                    data = json.load(f)
            except Exception:
                return {}
            if isinstance(data, dict) and 'devices' in data:
                return data
    return {}

def _write_all_np(payload):
    with _NP_LOCK:
        with _cross_process_flock(_NP_FLOCK_PATH):
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
        playing = bool(st.get('playing'))
        paused = bool(st.get('paused')) and not playing
        if not playing and not paused:
            # Idle rows (stopped / resume window) — drop once the UI would hide them.
            if ts and now - ts > _NP_STOPPED_VISIBLE_SECONDS:
                continue
            if not ts and not st.get('token'):
                continue
        if not playing and now - ts > _NP_DEVICE_TTL_SECONDS:
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
        payload = _canonicalize_np(_prune_np(payload))
        _write_all_np(payload)
    _np_on_state_written(did, data)

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
    _np_on_state_written(did, data)

# ── Track-end watcher (Echo Show often skips AudioPlayer lifecycle events) ────
_NP_ADVANCE_WATCH = {}
_NP_WATCH_LOCK = threading.RLock()

def _np_cancel_advance_watch(device_id):
    if not device_id or device_id == 'default':
        return
    with _NP_WATCH_LOCK:
        ent = _NP_ADVANCE_WATCH.pop(device_id, None)
    if ent and ent.get('timer'):
        ent['timer'].cancel()

def _np_serial_for_device(device_id):
    store = _load_devices()
    primary = _resolve_device_id(device_id, store)
    return (store.get(primary) or {}).get('serial') or ''

def _np_advance_watch_fire(watch):
    did = watch.get('device_id')
    token = watch.get('token')
    path = watch.get('path')
    with _NP_WATCH_LOCK:
        if _NP_ADVANCE_WATCH.get(did) is not watch:
            return
        _NP_ADVANCE_WATCH.pop(did, None)
    st = read_np_state_for_device(did) or {}
    if st.get('token') != token or st.get('filepath') != path:
        return
    if not st.get('playing') or st.get('paused'):
        return
    serial = _np_serial_for_device(did)
    if not serial:
        print(f'[NP WATCH] no serial for device {did[-12:]}', flush=True)
        return
    print(f'[NP WATCH] lifecycle silent — remote next serial={serial} token={token!r}', flush=True)
    try:
        import alexa_remote
        alexa_remote.device_control(serial, 'next')
    except Exception as ex:
        print(f'[NP WATCH] remote next failed: {ex}', flush=True)

def _np_schedule_advance_watch(device_id, state):
    """Fallback auto-advance when Alexa never sends NearlyFinished/Finished."""
    _np_cancel_advance_watch(device_id)
    if not state or not state.get('playing') or state.get('paused'):
        return
    token = state.get('token') or ''
    path = state.get('filepath') or ''
    if not token or not path:
        return
    data = decode_token(token) or {}
    tracks = data.get('tracks') or []
    idx = int(data.get('idx') or 0)
    if not tracks or (idx + 1 >= len(tracks) and not data.get('loop')):
        return
    duration_ms = int(state.get('duration_ms') or 0) or _duration_ms_for_path(path)
    if duration_ms <= 0:
        duration_ms = 180_000
    offset_ms = int(state.get('offset_ms') or 0)
    remaining_s = max(8.0, (duration_ms - offset_ms) / 1000.0 + 4.0)
    watch = {'token': token, 'path': path, 'device_id': device_id}

    def _fire():
        _np_advance_watch_fire(watch)

    t = threading.Timer(remaining_s, _fire)
    t.daemon = True
    watch['timer'] = t
    with _NP_WATCH_LOCK:
        _NP_ADVANCE_WATCH[device_id] = watch
    t.start()

def _np_on_state_written(device_id, data):
    if not device_id or device_id == 'default':
        return
    if data:
        _np_schedule_advance_watch(device_id, data)
    else:
        _np_cancel_advance_watch(device_id)

def remove_np_state():
    payload = _canonicalize_np(_read_all_np() or {'devices': {}})
    payload.get('devices', {}).pop(_np_device_id(), None)
    _write_all_np(payload)

_clear_nowplaying_on_boot()

# Backfill friendly room names from the live Alexa roster on boot (off-thread so
# a slow/absent alexapy login never blocks startup).
def _relabel_devices_on_boot():
    try:
        n = _relabel_devices_from_roster()
        if n:
            print(f"[DEVICE RELABEL] updated {n} name(s) from Alexa roster", flush=True)
    except Exception as e:
        print(f"[DEVICE RELABEL] skipped: {e}", flush=True)

threading.Thread(target=_relabel_devices_on_boot, daemon=True,
                 name='device-relabel').start()

# Keep kid-safe rooms under their volume cap even after voice "louder".
threading.Thread(target=_volume_cap_loop, daemon=True,
                 name='kid-safe-volume').start()

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
_NP_STOPPED_VISIBLE_SECONDS = 3 * 60   # show idle rows briefly (resume window)


def _np_event_token_matches_state(event_token, state):
    """True when an AudioPlayer lifecycle event belongs to the row we're tracking."""
    if not event_token or not state:
        return False
    return event_token == state.get('token')

def _np_stalled_after_enqueue(event_token, state):
    """NearlyFinished ENQUEUE advanced state but Alexa never started the next track."""
    if not event_token or not state:
        return False
    state_token = state.get('token', '')
    if ':' not in event_token or ':' not in state_token:
        return False
    eqid, eidx_s = event_token.split(':', 1)
    sqid, sidx_s = state_token.split(':', 1)
    if eqid != sqid:
        return False
    try:
        return int(sidx_s) == int(eidx_s) + 1
    except ValueError:
        return False

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

def _refresh_np_expiry(payload):
    """Expire stale rows and prune idle devices. Returns (payload, changed)."""
    before = json.dumps(payload.get('devices', {}), sort_keys=True, default=str)
    _expire_stale_playing(payload)
    _expire_stale_client_playback(payload)
    payload = _canonicalize_np(_prune_np(payload))
    after = json.dumps(payload.get('devices', {}), sort_keys=True, default=str)
    return payload, before != after

def _sleep_info_for_token(token):
    """Describe an armed sleep timer / stop-after-N for a now-playing token,
    or None. Used to badge the row in the web Now Playing UI."""
    if not token or ':' not in token:
        return None
    data = decode_token(token) or {}
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
    now = time.time()
    with _NP_LOCK:
        payload = _read_all_np() or {'devices': {}}
        payload, changed = _refresh_np_expiry(payload)
        if changed:
            _write_all_np(payload)
    devices = payload.get('devices', {})
    known = set(_load_devices().keys())
    device_store = _load_devices()
    # Mobile apps pass viewerClientId to hide other phones/tablets; web omits it.
    viewer_client_id = (request.args.get('viewerClientId') or '').strip()
    mobile_view = bool(viewer_client_id)
    items = []
    for did, st in devices.items():
        playing = bool(st.get('playing'))
        paused = bool(st.get('paused')) and not playing
        ts = st.get('timestamp') or 0
        recently_stopped = (
            not playing and not paused and st.get('token')
            and ts and now - ts < _NP_STOPPED_VISIBLE_SECONDS
        )
        if not playing and not paused and not recently_stopped:
            continue
        if mobile_view and _is_client_device(did):
            continue
        if did == 'default' or (did not in known and not _is_msp_pseudo(did)):
            continue
        duration_ms = st.get('duration_ms') or 0
        if not duration_ms and st.get('filepath'):
            duration_ms = _duration_ms_for_path(st.get('filepath'))
        token = st.get('token') or ''
        token_data = decode_token(token) or {}
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
            'year':       st.get('year') or _year_for_path(st.get('filepath')),
            'filepath':   st.get('filepath'),
            'timestamp':  st.get('timestamp'),
            'duration_ms': duration_ms,
            'offset_ms':   st.get('offset_ms') or 0,
            'paused':     paused,
            'stopped':    not playing and not paused,
            'shuffle':    bool(token_data.get('shuffle')),
            'sleep':      _sleep_info_for_token(token),
            'upcoming':   _upcoming_tracks_for_token(token, limit=5),
            'playlist': src.get('playlist'),
            'playlistId': src.get('playlistId'),
            'context': src.get('context'),
            'sourceLabel': src.get('sourceLabel'),
            'platform': platform,
            'upNext': _room_upnext_public(_room_key(did)),
        })
    # Collapse rows that resolve to the same physical Echo (a rotated deviceId
    # can yield two rows for one speaker). Keep the most recently active.
    by_serial = {}
    deduped = []
    for it in items:
        primary = _resolve_device_id(it['deviceId'], device_store)
        serial = _entry_serial(device_store.get(primary), primary, device_store)
        if not serial:
            deduped.append(it)
            continue
        idx = by_serial.get(serial)
        if idx is None:
            by_serial[serial] = len(deduped)
            deduped.append(it)
        elif (it.get('timestamp') or 0) > (deduped[idx].get('timestamp') or 0):
            deduped[idx] = it
    items = deduped

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
               title=None, subtitle=None, artwork_url=None,
               filepath=None, supported_interfaces=None, device_id=None):
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

    ifaces = supported_interfaces
    if ifaces is None:
        ifaces = getattr(g, 'supported_interfaces', None) or {}
    dev_id = device_id if device_id is not None else getattr(g, 'device_id', None)
    try:
        apl_directives, apl_on = alexa_apl.play_apl_directives(
            filepath, offset_ms, title, subtitle, ifaces, dev_id,
        )
    except Exception as ex:
        print(f'[APL] play_apl_directives skipped: {ex}', flush=True)
        apl_directives, apl_on = [], False
    audio_item['stream']['progressReportingIntervalInMilliseconds'] = (
        alexa_apl.PROGRESS_REPORT_MS if apl_on else _NP_PROGRESS_REPORT_MS
    )

    directives = list(apl_directives) + [{
        'type': 'AudioPlayer.Play',
        'playBehavior': play_behavior,
        'audioItem': audio_item,
    }]
    resp = {
        'version': '1.0',
        'response': {
            'directives': directives,
            'shouldEndSession': True,
        },
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
                      title=title, subtitle=subtitle, artwork_url=artwork_url,
                      filepath=path)

def _np_eager_track_state(path, token, prev_state=None):
    """Refresh Now Playing metadata when issuing the next/prev track (before PlaybackStarted)."""
    state = dict(prev_state or read_np_state() or {})
    title, artist, album, _ = track_metadata_fast(path)
    src = _np_source_fields(token, _np_device_id())
    write_np_state({
        **state,
        'track': title,
        'artist': artist,
        'album': album,
        'filepath': path,
        'token': token,
        'playing': True,
        'paused': False,
        'timestamp': time.time(),
        'duration_ms': _duration_ms_for_path(path),
        'offset_ms': 0,
        **src,
    })

def _np_advance_at_boundary(token, *, previous_token=None, play_behavior='ENQUEUE'):
    """Advance the active queue at a track boundary (NearlyFinished / Finished fallback).

    Returns an Alexa Play response, or None when the queue has ended or a sleep timer fired.
    """
    data = decode_token(token) or {}
    tracks = data.get('tracks', [])
    if not tracks:
        print(f'[ALEXA] queue advance skipped: empty tracks token={token!r}', flush=True)
        return None
    idx = data.get('idx', 0)
    next_idx = idx + 1
    stop_at = data.get('stopAt')
    stop_after_idx = data.get('stopAfterIdx')
    if stop_at and time.time() >= float(stop_at):
        return None
    if stop_after_idx is not None and next_idx > int(stop_after_idx):
        return None
    qid = data.get('qid')
    if qid:
        _touch_queue(qid)
    req_path = _consume_next_request(_room_key(_np_device_id()))
    if req_path and os.path.isfile(req_path):
        new_tracks = (tracks[:next_idx] + [req_path] + tracks[next_idx:])[:_QUEUE_TRACK_LIMIT]
        req_token = encode_token({**data, 'tracks': new_tracks, 'idx': next_idx})
        if play_behavior != 'ENQUEUE':
            _np_eager_track_state(req_path, req_token, prev_state=read_np_state())
        return _np_play_path(req_path, req_token,
                            previous_token=previous_token or token,
                            play_behavior=play_behavior)
    if next_idx >= len(tracks):
        if data.get('loop'):
            next_idx = 0
        else:
            continued = _try_continue_queue(data, tracks, idx)
            if continued:
                next_path, next_token = continued
                if play_behavior != 'ENQUEUE':
                    _np_eager_track_state(next_path, next_token, prev_state=read_np_state())
                return _np_play_path(next_path, next_token,
                                     previous_token=previous_token or token,
                                     play_behavior=play_behavior)
            return None
    if next_idx < len(tracks):
        next_path = tracks[next_idx]
        next_token = encode_token({**data, 'idx': next_idx})
        # ENQUEUE at NearlyFinished: keep state on the current track until
        # PlaybackStarted for the next one — otherwise Finished fallback is
        # ignored when Alexa fails to start the enqueued stream.
        if play_behavior != 'ENQUEUE':
            _np_eager_track_state(next_path, next_token, prev_state=read_np_state())
        return _np_play_path(next_path, next_token,
                             previous_token=previous_token or token,
                             play_behavior=play_behavior)
    return None

def _np_queue_limit_reached(data, next_idx, *, playback_controller=False):
    """Sleep timer / stop-after-N guard shared by auto-advance and manual skip."""
    stop_at = data.get('stopAt')
    if stop_at and time.time() >= float(stop_at):
        write_np_state(None)
        msg = "Stopping playback."
        return alexa_empty() if playback_controller else alexa_speak(msg)
    stop_after_idx = data.get('stopAfterIdx')
    if stop_after_idx is not None and next_idx > int(stop_after_idx):
        write_np_state(None)
        msg = "Stopping after the requested number of songs."
        return alexa_empty() if playback_controller else alexa_speak(msg)
    return None

def _np_skip_next(playback_controller=False):
    """Advance to the next track in the active device queue."""
    state = read_np_state() or {}
    token = state.get('token', '')
    if not token:
        return alexa_empty() if playback_controller else alexa_speak("Nothing is playing.")
    data = decode_token(token) or {}
    tracks = data.get('tracks', [])
    idx = data.get('idx', 0)
    if not tracks:
        return alexa_empty() if playback_controller else alexa_speak("There are no more tracks.")
    next_idx = idx + 1
    if next_idx >= len(tracks):
        if data.get('loop'):
            next_idx = 0
        else:
            write_np_state(None)
            msg = "There are no more tracks."
            return alexa_empty() if playback_controller else alexa_speak(msg)
    blocked = _np_queue_limit_reached(data, next_idx, playback_controller=playback_controller)
    if blocked:
        return blocked
    next_path = tracks[next_idx]
    next_token = encode_token({**data, 'idx': next_idx})
    _np_eager_track_state(next_path, next_token, prev_state=state)
    return _np_play_path(next_path, next_token)

def _np_skip_previous(playback_controller=False):
    """Go back to the previous track in the active device queue."""
    state = read_np_state() or {}
    token = state.get('token', '')
    if not token:
        return alexa_empty() if playback_controller else alexa_speak("Nothing is playing.")
    data = decode_token(token) or {}
    tracks = data.get('tracks', [])
    if not tracks:
        return alexa_empty() if playback_controller else alexa_speak("Nothing to go back to.")
    idx = data.get('idx', 0)
    prev_idx = max(idx - 1, 0)
    prev_path = tracks[prev_idx]
    prev_token = encode_token({**data, 'idx': prev_idx})
    _np_eager_track_state(prev_path, prev_token, prev_state=state)
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
                      title=title, subtitle=subtitle, artwork_url=artwork_url,
                      filepath=path)

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

def _song_duration_seconds(path, db_seconds=None):
    """Seconds of play time for API rows — DB value, else mutagen via _duration_ms_for_path."""
    try:
        dur = int(float(db_seconds or 0))
    except (TypeError, ValueError):
        dur = 0
    if dur > 0:
        return dur
    ms = _duration_ms_for_path(path)
    return max(0, ms // 1000)

def _year_for_path(path):
    """Release year (int) for a file path from songs_cache, else None."""
    if not path:
        return None
    row = db_one('SELECT year FROM songs_cache WHERE path = ?', [path]) or {}
    raw = str(row.get('year') or '').strip()
    if not raw:
        return None
    # Values may be "1998", "1998-05-01", etc. — keep the leading 4-digit year.
    digits = raw[:4]
    return int(digits) if digits.isdigit() else None

def track_metadata(path):
    """Return (title, artist, album, artwork_url) for a file path."""
    row = db_one('SELECT title, artist, album FROM songs_cache WHERE path = ?', [path])
    fname = os.path.splitext(os.path.basename(path))[0]
    title   = row.get('title', fname) or fname
    artist  = row.get('artist') or None
    album   = row.get('album') or None
    art_path = find_artwork(path)
    artwork_url = file_to_artwork_url(art_path) if art_path else None
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
    try:
        return _start_playing_impl(
            tracks, shuffle=shuffle, speech=speech, loop=loop,
            playlist=playlist, playlist_id=playlist_id, context=context, source=source,
        )
    except Exception as ex:
        import traceback
        print(f'[ALEXA] start_playing failed: {ex}\n{traceback.format_exc()}', flush=True)
        return alexa_speak("Sorry, I couldn't start playback right now.")


def _start_playing_impl(tracks, shuffle=False, speech=None, loop=False,
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
        qid = _store_queue(
            queue, shuffle=shuffle, loop=loop,
            playlist=playlist, playlist_id=playlist_id, context=context,
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
                    'playing': True, 'paused': False, 'timestamp': time.time(),
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
        from playlist_xml_lock import playlist_xml_lock
        with playlist_xml_lock(DATA_DIR, shared=True):
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
        _attach_queue_play_target(qid)
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
        if name == 'GetNextItem':
            room_key = _msp_room_key_for_queue(queue_id)
            req_path = _consume_next_request(room_key)
            if req_path and os.path.isfile(req_path):
                tracks = list(tracks)
                insert_at = idx + 1
                tracks.insert(insert_at, req_path)
                tracks = tracks[:_QUEUE_TRACK_LIMIT]
                _update_queue_flags(queue_id, tracks=tracks)
                return _msp_event('Alexa.Audio.PlayQueue', f'{name}.Response', header,
                                  {'isQueueFinished': False,
                                   'item': _msp_build_item(queue_id, insert_at, tracks, content_id)})
        nxt = idx + 1 if name == 'GetNextItem' else idx - 1
        if name == 'GetNextItem' and nxt >= len(tracks) and not loop:
            token_data = {
                'qid': queue_id,
                'tracks': tracks,
                'idx': idx,
                'loop': loop,
                'shuffle': q.get('shuffle', False),
                'playlist_id': content_id,
                'playlist': q.get('playlist'),
                'context': q.get('context'),
            }
            continued = _try_continue_queue(token_data, tracks, idx)
            if continued:
                _next_path, next_token = continued
                new_data = decode_token(next_token) or {}
                new_tracks = new_data.get('tracks') or tracks
                new_idx = int(new_data.get('idx', idx + 1))
                _update_queue_flags(queue_id, tracks=new_tracks)
                return _msp_event('Alexa.Audio.PlayQueue', f'{name}.Response', header,
                                    {'isQueueFinished': False,
                                     'item': _msp_build_item(queue_id, new_idx, new_tracks, content_id)})
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
                'memberId': resolve_play_member(device_id=_np_device_id()) or None,
                'platform': 'alexa',
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
    g.supported_interfaces = supported_ifaces
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
        data  = decode_token(token) or {}
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        path = track_title = artist = album = None
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
                'memberId': resolve_play_member(device_id=_np_device_id()) or None,
                'platform': 'alexa',
                'playlist': src.get('playlist'),
                'sourceLabel': src.get('sourceLabel'),
                'date':     datetime.datetime.now().isoformat(timespec='seconds'),
            }
            # Identify/test sweeps shouldn't pollute analytics.
            dev_serial = (_load_devices().get(_np_device_id()) or {}).get('serial')
            if _is_test_serial(dev_serial):
                entry['test'] = True
            append_stream_history(entry)
        apl_dirs = alexa_apl.playback_started_directives(
            path, track_title, artist, album,
            supported_ifaces, g.device_id,
            offset_ms=int(req.get('offsetInMilliseconds') or 0),
        )
        if apl_dirs:
            return jsonify({'version': '1.0', 'response': {'directives': apl_dirs}})
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackProgressReport':
        return alexa_apl.progress_report_response(req, supported_ifaces, g.device_id)

    if rtype == 'AudioPlayer.PlaybackNearlyFinished':
        token = req.get('token', '')
        advanced = _np_advance_at_boundary(token, previous_token=token, play_behavior='ENQUEUE')
        if advanced is not None:
            return advanced
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackFinished':
        # NearlyFinished often starts the next track before Finished arrives for
        # the old one — ignore stale Finished events so multi-room playback stays
        # visible and the active row keeps playing=True.
        state = read_np_state() or {}
        event_token = req.get('token', '')
        if _np_event_token_matches_state(event_token, state):
            advanced = _np_advance_at_boundary(event_token, play_behavior='REPLACE_ALL')
            if advanced is not None:
                return advanced
        elif _np_stalled_after_enqueue(event_token, state) and state.get('filepath'):
            advanced = _np_play_path(state['filepath'], state['token'],
                                     play_behavior='REPLACE_ALL')
            if advanced is not None:
                return advanced
        state['playing'] = False
        write_np_state(state)
        return alexa_empty()

    if rtype == 'AudioPlayer.PlaybackFailed':
        token = req.get('token', '')
        data  = decode_token(token) or {}
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        next_idx = idx + 1
        blocked = _np_queue_limit_reached(data, next_idx, playback_controller=True)
        if blocked:
            return blocked
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
        if state and _np_event_token_matches_state(req.get('token', ''), state):
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

    if rtype in ('ExceptionEncountered', 'System.ExceptionEncountered'):
        err = req.get('error') or {}
        print(
            f'[ALEXA EXCEPTION] type={err.get("type")} message={err.get("message")!r} '
            f'failed_req={((err.get("failedRequest") or {}).get("type"))!r}',
            flush=True,
        )
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
        try:
            return _alexa_intent_request(req)
        except Exception as ex:
            import traceback
            print(f'[ALEXA INTENT ERROR] {ex}\n{traceback.format_exc()}', flush=True)
            return alexa_speak(
                'Sorry, something went wrong. Please try again.',
                end_session=True,
            )

    return alexa_speak(
        "Sorry, I couldn't process that Alexa request. Please try again.",
        end_session=True
    )


def _alexa_intent_request(req):
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
            return _start_playlist_token_entry(token_entry, shuffle=True)
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
        try:
            token_raw = sv('FileToken')
            pl_entry = _consume_play_playlist_token(token_raw)
            if pl_entry:
                if pl_entry.get('kind') == 'album':
                    return _start_album_token_entry(pl_entry)
                return _start_playlist_token_entry(pl_entry)
            entry = _consume_play_file_token(token_raw)
            if entry and entry.get('path') and os.path.isfile(entry['path']):
                label = entry['title']
                if entry.get('artist'):
                    label = f"{label} by {entry['artist']}"
                return start_playing([entry['path']], speech=f"Playing {label}.",
                                    context=f'Song · {label}')
            return alexa_speak(
                "Sorry, that play link expired or was not found. "
                "Try playing again from the Bock Media app.",
            )
        except Exception as ex:
            import traceback
            print(f'[ALEXA] PlayFileTokenIntent failed: {ex}\n{traceback.format_exc()}', flush=True)
            return alexa_speak(
                "Sorry, I couldn't start playback. Check the Bock Media server logs.",
            )

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
        added = False
        try:
            added = _append_m3u_track(source, current_path)
            if added:
                tree = _load_playlists_tree()
                root = tree.getroot()
                src_norm = os.path.normpath(source)
                for entry in root.findall('Entry'):
                    key = entry.find('Key')
                    if key is None:
                        continue
                    if os.path.normpath(xml_text(key, 'SourceID') or '') != src_norm:
                        continue
                    tc = key.find('TrackCount')
                    count = int((tc.text if tc is not None else '0') or 0) + 1
                    if tc is None:
                        tc = ET.SubElement(key, 'TrackCount')
                    tc.text = str(count)
                    _save_playlists_tree(tree)
                    break
        except Exception as e:
            print(f'AddToPlaylist error: {e}', flush=True)
            if not plex_ok and not added:
                return alexa_speak(f"Sorry, I couldn't add to {name}.")
        if not added and not plex_ok:
            return alexa_speak(f"That track is already in {name}.")
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
        data  = decode_token(token) or {}
        tracks = data.get('tracks', [])
        idx    = data.get('idx', 0)
        if current_path:
            add_ignored(current_path)
        next_idx = idx + 1
        blocked = _np_queue_limit_reached(data, next_idx)
        if blocked:
            return blocked
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
            data = decode_token(state['token']) or {}
            _update_queue_flags(data.get('qid'), loop=True)
        return alexa_speak("Loop mode on.")

    elif iname == 'AMAZON.LoopOffIntent':
        state = read_np_state() or {}
        if state.get('token'):
            data = decode_token(state['token']) or {}
            _update_queue_flags(data.get('qid'), loop=False)
        return alexa_speak("Loop mode off.")

    # ── Shuffle (built-in Alexa intents as aliases) ────────────────────
    elif iname == 'AMAZON.ShuffleOnIntent':
        state = read_np_state() or {}
        token = state.get('token', '')
        if token:
            data = decode_token(token) or {}
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
            data = decode_token(state['token']) or {}
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


# ── Run ──────────────────────────────────────────────────────────────────────

try:
    bock_loudness.ensure_songs_cache_columns(get_db_rw, db_query)
    import bock_search_ext
    bock_search_ext.ensure_fts(get_db_rw, db_query)
    # Backfill first_seen_at from file mtime where missing.
    rows = db_query(
        'SELECT path FROM songs_cache WHERE first_seen_at IS NULL AND path IS NOT NULL LIMIT 5000'
    ) or []
    if rows:
        conn = get_db_rw()
        try:
            for r in rows:
                p = r.get('path')
                if p and os.path.isfile(p):
                    ts = datetime.datetime.fromtimestamp(os.path.getmtime(p)).strftime('%Y-%m-%d')
                    conn.execute('UPDATE songs_cache SET first_seen_at=? WHERE path=?', [ts, p])
            conn.commit()
        finally:
            conn.close()
except Exception as e:
    print(f'[startup] schema init: {e}', flush=True)

register_bock_routes(app, globals())

_start_automation_scheduler()
_start_device_discovery_scheduler()
_start_daily_scheduler()
_start_playlist_cover_warm()

def _warn_insecure_lan_config():
    """Log once at startup when LAN is fully open with no credentials."""
    try:
        if _credentials_configured():
            return
        if _allow_open_lan_api() and _allow_open_lan_media():
            print(
                'SECURITY: LAN API and media are open with no WebPassword or mobileApi.token — '
                'any device on your Wi-Fi can read the library and trigger playback. '
                'Set credentials in Settings or disable allowOpenLanApi/allowOpenLanMedia in config.json.',
                flush=True,
            )
    except Exception:
        pass

_warn_insecure_lan_config()

if __name__ == '__main__':
    apply_logging()
    port = int(os.environ.get('PORT', 3001))
    print(f'Bock Media running at http://localhost:{port}')
    app.run(host='0.0.0.0', port=port, debug=False)
