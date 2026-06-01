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


def play_text(target, text):
    """Speak `text` to the Echo identified by serial or accountName `target`."""
    return run(_play_text(target, text))
