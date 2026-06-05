"""Unofficial Alexa remote control via alexapy.

Lets the web UI start playback on a *specific* Echo by injecting a text command
that runs the Bock Media skill (e.g. "ask bock media to play the yacht rock
playlist"). This is the only practical way to push playback to a named device,
since Amazon gives skills/MSP no API to initiate playback on a chosen Echo.

Auth is established once via scripts/alexa_login.py, which writes a session
cookie pickle under <DATA_DIR>/.storage/. All calls here reuse that session and
never need credentials at request time (beyond what's in config.json).

NOTE: Amazon has no official API; alexapy is reverse-engineered and can break
without warning. Cookies also expire periodically — re-run scripts/alexa_login.py
if calls start returning not_authenticated.
"""
import asyncio
import json
import os
import socket
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', os.path.expanduser('~/.bockmedia'))
CONFIG_PATH = os.path.join(HERE, 'config.json')

# Device families that can render audio (exclude Fire TV remotes, the app, etc.)
_PLAYABLE_FAMILIES = {'ECHO', 'ROOK', 'KNIGHT', 'WHA', 'FIRE_TV', 'TABLET'}


class AlexaRemoteError(RuntimeError):
    """Raised for expected, user-actionable failures (not configured, no auth…)."""


def _config():
    try:
        with open(CONFIG_PATH) as f:
            return json.load(f)
    except Exception:
        return {}


def cfg():
    return _config().get('alexaRemote') or {}


def _cookie_session_exists():
    c = cfg()
    email = c.get('email')
    if not email:
        return False
    base = os.path.join(DATA_DIR, '.storage')
    for ext in ('pickle', 'txt'):
        if os.path.isfile(os.path.join(base, f'alexa_media.{email}.{ext}')):
            return True
    return False


def is_configured():
    """Configured if we can log in: either creds (form login) or an imported
    cookie session (passkey users who can't automate the login form)."""
    c = cfg()
    if not c.get('email'):
        return False
    return bool(c.get('password')) or _cookie_session_exists()


# is_configured() only proves credentials/cookie EXIST, not that the session is
# still valid (cookies expire). is_authenticated() actually attempts a login to
# confirm, but that is a network round-trip — so the result is cached. NEVER call
# this inline in a request hot path; the health-check timer refreshes it.
_AUTH_CACHE = {'ts': 0.0, 'ok': None}
_AUTH_CACHE_TTL = 120.0


def invalidate_auth_cache():
    """Force the next is_authenticated() probe to re-check the session."""
    _AUTH_CACHE['ts'] = 0.0
    _AUTH_CACHE['ok'] = None


def is_authenticated(max_age=_AUTH_CACHE_TTL):
    """Cached check that the saved Alexa session is still valid. Returns
    True/False, or None if not configured. Swallows all errors -> False."""
    if not is_configured():
        return None
    import time as _time
    now = _time.time()
    if _AUTH_CACHE['ok'] is not None and (now - _AUTH_CACHE['ts']) < max_age:
        return _AUTH_CACHE['ok']
    async def _probe():
        login = await _login_from_cookie()  # raises if not authenticated
        await login.close()
        return True
    try:
        ok = run(_probe())
    except Exception:
        ok = False
    _AUTH_CACHE['ts'] = now
    _AUTH_CACHE['ok'] = ok
    return ok


def _outputpath(filename):
    return os.path.join(DATA_DIR, filename)


def run(coro):
    """Run a coroutine on a throwaway event loop (Flask is sync)."""
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coro)
    finally:
        try:
            loop.run_until_complete(loop.shutdown_asyncgens())
        except Exception:
            pass
        loop.close()


def make_login(debug=False):
    """Build an AlexaLogin from config (does not log in)."""
    from alexapy import AlexaLogin
    c = cfg()
    if not c.get('email'):
        raise AlexaRemoteError('not_configured')
    os.makedirs(os.path.join(DATA_DIR, '.storage'), exist_ok=True)
    return AlexaLogin(
        url=c.get('url', 'amazon.com'),
        email=c['email'],
        password=c.get('password') or '',  # blank is fine for cookie-import sessions
        outputpath=_outputpath,
        debug=debug,
        otp_secret=(c.get('otpSecret') or '').replace(' ', ''),
    )


async def _login_from_cookie():
    """Log in using the saved cookie session. Raises if not authenticated."""
    login = make_login()
    cookies = await login.load_cookie()
    await login.login(cookies=cookies)
    if not await login.test_loggedin():
        await login.close()
        raise AlexaRemoteError('not_authenticated')
    return login


class _Device:
    """Minimal shim exposing the attributes AlexaAPI reads off a device."""
    def __init__(self, d):
        self._device_type = d.get('deviceType')
        self.device_serial_number = d.get('serialNumber')
        self._locale = None
        self._customer_id = d.get('deviceOwnerCustomerId')


def _playable(d):
    if not (d.get('serialNumber') and d.get('deviceType') and d.get('accountName')):
        return False
    caps = d.get('capabilities') or []
    if 'AUDIO_PLAYER' not in caps and d.get('deviceFamily') not in _PLAYABLE_FAMILIES:
        return False
    # Skip "this device" app entries / unconfigured slots.
    return d.get('deviceFamily') != 'UNKNOWN'


async def _list_devices():
    from alexapy import AlexaAPI
    login = await _login_from_cookie()
    try:
        raw = await AlexaAPI.get_devices(login) or []
        out = []
        for d in raw:
            if not _playable(d):
                continue
            out.append({
                'name': d.get('accountName'),
                'serial': d.get('serialNumber'),
                'type': d.get('deviceType'),
                'family': d.get('deviceFamily'),
                'online': bool(d.get('online')),
            })
        out.sort(key=lambda x: (not x['online'], (x['name'] or '').lower()))
        return out
    finally:
        await login.close()


def list_devices():
    return run(_list_devices())


def _match(devices, target):
    """Match a target by serial first, then case-insensitive accountName."""
    t = (target or '').strip()
    if not t:
        return None
    for d in devices:
        if d.get('serialNumber') == t:
            return d
    tl = t.lower()
    for d in devices:
        if (d.get('accountName') or '').strip().lower() == tl:
            return d
    return None


async def _play_text(target, text):
    from alexapy import AlexaAPI
    login = await _login_from_cookie()
    try:
        devices = await AlexaAPI.get_devices(login) or []
        dev = _match(devices, target)
        if not dev:
            raise AlexaRemoteError('device_not_found')
        api = AlexaAPI(_Device(dev), login)
        # run_custom is decorated to swallow exceptions and returns None, so we
        # can only surface auth/lookup errors (handled above), not Alexa-side ones.
        await api.run_custom(text)
        return {'device': dev.get('accountName'), 'serial': dev.get('serialNumber'),
                'text': text}
    finally:
        await login.close()


async def _set_volume(target, level):
    """Set device volume. `level` is 0-100; alexapy wants 0.0-1.0."""
    from alexapy import AlexaAPI
    login = await _login_from_cookie()
    try:
        devices = await AlexaAPI.get_devices(login) or []
        dev = _match(devices, target)
        if not dev:
            raise AlexaRemoteError('device_not_found')
        api = AlexaAPI(_Device(dev), login)
        await api.set_volume(max(0.0, min(1.0, level / 100.0)))
        return {'device': dev.get('accountName'), 'serial': dev.get('serialNumber'),
                'volume': int(level)}
    finally:
        await login.close()


def set_volume(target, level):
    """Set volume (0-100) on the Echo identified by serial or accountName."""
    return run(_set_volume(target, level))


async def _get_volume(target):
    """Read current volume (0-100) from the device's player state, or None."""
    from alexapy import AlexaAPI
    login = await _login_from_cookie()
    try:
        devices = await AlexaAPI.get_devices(login) or []
        dev = _match(devices, target)
        if not dev:
            raise AlexaRemoteError('device_not_found')
        api = AlexaAPI(_Device(dev), login)
        state = await api.get_state() or {}
        vol = (((state.get('playerInfo') or {}).get('volume')) or {}).get('volume')
        return int(vol) if vol is not None else None
    finally:
        await login.close()


def get_volume(target):
    """Return current volume 0-100 for the Echo, or None if unavailable."""
    return run(_get_volume(target))


def play_text(target, text):
    """Speak `text` to the Echo identified by serial or accountName `target`."""
    return run(_play_text(target, text))


# Voice phrases for transport controls on a device with an active Bock Media
# AudioPlayer session. Native alexapy pause/next/etc. use /api/np/command and
# only affect Amazon Music / Spotify — not custom-skill playback.
_TRANSPORT_TEXT = {
    'pause': 'pause',
    'play': 'resume',
    'stop': 'stop',
    'next': 'skip',
    'previous': 'go back',
    'shuffle_on': 'shuffle on',
    'shuffle_off': 'shuffle off',
}

# Actions that may be issued while the target device has LOST audio focus (e.g.
# resume/stop after a pause cleared the session). A bare media verb ("resume",
# "stop") gets routed by Amazon's media domain to the household's last-active
# device, not the one we targeted — so it must be an explicit skill invocation
# ("ask <alias> to resume") to deterministically hit the intended Echo, like the
# play-on-device path.
_EXPLICIT_INVOCATION_ACTIONS = {'play', 'stop', 'next', 'previous'}


async def _device_control(target, action, alias='bock media'):
    verb = _TRANSPORT_TEXT.get(action)
    if not verb:
        raise AlexaRemoteError('invalid_action')
    if action in _EXPLICIT_INVOCATION_ACTIONS:
        text = f"ask {alias} to {verb}"
    else:
        text = verb
    result = await _play_text(target, text)
    result['action'] = action
    return result


def device_control(target, action, alias='bock media'):
    """Send pause/play/next/previous/shuffle to an Echo by serial or name."""
    return run(_device_control(target, action, alias))


# ── Browser proxy login (Settings UI / scripts/alexa_login.py --proxy) ────────

_LOGIN_PROXY = {
    'status': 'idle',   # idle | waiting | success | error | stopped
    'error': None,
    'url': None,
    'host': None,
    'port': None,
    'started_at': None,
}
_LOGIN_LOCK = threading.Lock()
_LOGIN_CANCEL = threading.Event()
_LOGIN_THREAD = None


def lan_ip():
    """Best-effort LAN address for the proxy login page."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return '127.0.0.1'


def proxy_login_state():
    """Snapshot of in-process proxy login (for API polling)."""
    with _LOGIN_LOCK:
        return {
            'status': _LOGIN_PROXY['status'],
            'error': _LOGIN_PROXY['error'],
            'url': _LOGIN_PROXY['url'],
            'host': _LOGIN_PROXY['host'],
            'port': _LOGIN_PROXY['port'],
            'startedAt': _LOGIN_PROXY['started_at'],
        }


def _set_proxy(**kwargs):
    with _LOGIN_LOCK:
        _LOGIN_PROXY.update(kwargs)


async def _proxy_login_async(host, port):
    from alexapy import AlexaProxy

    _LOGIN_CANCEL.clear()
    base_url = f'http://{host}:{port}'
    _set_proxy(status='waiting', error=None, url=base_url, host=host, port=port,
               started_at=time.time())
    login = make_login(debug=bool(os.environ.get('ALEXA_DEBUG')))
    proxy = AlexaProxy(login, base_url)
    try:
        await proxy.start_proxy(host='0.0.0.0')
        for _ in range(600):
            if _LOGIN_CANCEL.is_set():
                _set_proxy(status='stopped', error=None)
                return
            if getattr(login, 'access_token', None):
                break
            await asyncio.sleep(1)
        else:
            _set_proxy(status='error', error='Timed out waiting for login (10 min).')
            return

        await login.login()
        ok = await login.test_loggedin()
        if ok:
            await login.save_cookiefile()
            invalidate_auth_cache()
            _set_proxy(status='success', error=None)
        else:
            _set_proxy(status='error', error='Token captured but session test failed.')
    except Exception as e:
        _set_proxy(status='error', error=str(e))
    finally:
        try:
            await proxy.stop_proxy()
        except Exception:
            pass
        try:
            await login.close()
        except Exception:
            pass


def _proxy_login_thread(host, port):
    global _LOGIN_THREAD
    try:
        run(_proxy_login_async(host, port))
    finally:
        with _LOGIN_LOCK:
            if _LOGIN_PROXY['status'] == 'waiting':
                _LOGIN_PROXY['status'] = 'stopped'
        _LOGIN_THREAD = None


def start_proxy_login(host=None, port=None):
    """Start the alexapy OAuth proxy on a background thread. Returns state dict."""
    if not is_configured():
        raise AlexaRemoteError('not_configured')
    if not cfg().get('password'):
        raise AlexaRemoteError(
            'password_required — add alexaRemote.password in config.json '
            '(keep your passkey; choose password at Amazon sign-in in the browser).'
        )

    global _LOGIN_THREAD
    with _LOGIN_LOCK:
        if _LOGIN_PROXY['status'] == 'waiting' and _LOGIN_THREAD and _LOGIN_THREAD.is_alive():
            return proxy_login_state()

    host = (host or cfg().get('loginProxyHost') or lan_ip()).strip()
    port = int(port or cfg().get('loginProxyPort') or 3005)

    _LOGIN_CANCEL.clear()
    _set_proxy(status='starting', error=None, url=f'http://{host}:{port}',
               host=host, port=port, started_at=time.time())
    _LOGIN_THREAD = threading.Thread(
        target=_proxy_login_thread, args=(host, port),
        daemon=True, name='alexa-proxy-login',
    )
    _LOGIN_THREAD.start()
    # Brief pause so the proxy can bind before the UI opens the URL.
    time.sleep(0.6)
    with _LOGIN_LOCK:
        if _LOGIN_PROXY['status'] == 'starting':
            _LOGIN_PROXY['status'] = 'waiting'
    return proxy_login_state()


def stop_proxy_login():
    """Cancel an in-progress proxy login."""
    _LOGIN_CANCEL.set()
    _set_proxy(status='stopped', error=None)
    return proxy_login_state()
