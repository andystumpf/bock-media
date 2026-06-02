#!/usr/bin/env python3
"""ourMedia health probe — run on a 1-minute systemd timer.

Writes a snapshot to health_state.json (read by GET /api/health and the web
dashboard) and appends a WARN line to health.log on a DOWN transition. No
external push — alerts surface as an in-UI banner/card only.

Signals:
  * backend       — `systemctl is-active ourmedia`
  * tunnel        — `systemctl is-active ourmedia-tunnel-named`
  * backendHttp   — local GET /api/summary returns 200
  * tunnelReachable — public /alexa returns ANY HTTP status (the tunnel is up;
                      403 from Alexa signature verification still counts)
  * alexaAuth     — alexapy session still valid (cached); None if not configured
"""
import datetime
import json
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
STATE_PATH = os.path.join(REPO, 'health_state.json')
LOG_PATH = os.path.join(REPO, 'health.log')
CONFIG_PATH = os.path.join(REPO, 'config.json')

BACKEND_URL = 'http://127.0.0.1:3001/api/summary'


def _public_url():
    try:
        with open(CONFIG_PATH) as f:
            url = (json.load(f) or {}).get('publicUrl') or ''
        return url.rstrip('/') or 'https://your-domain.example.com'
    except Exception:
        return 'https://your-domain.example.com'


def _is_active(unit):
    try:
        out = subprocess.run(['systemctl', 'is-active', unit],
                             capture_output=True, text=True, timeout=10)
        return out.stdout.strip() == 'active'
    except Exception:
        return False


def _http_ok(url, timeout=5, method='GET', data=b''):
    """Return (reachable, status, latency_ms). reachable=True for ANY HTTP
    response, including 4xx/5xx (the endpoint answered)."""
    start = time.time()
    req = urllib.request.Request(url, method=method, data=data or None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return True, r.status, int((time.time() - start) * 1000)
    except urllib.error.HTTPError as e:
        return True, e.code, int((time.time() - start) * 1000)
    except Exception:
        return False, None, None


def _alexa_auth():
    try:
        sys.path.insert(0, REPO)
        import alexa_remote
        return alexa_remote.is_authenticated()
    except Exception:
        return None


def _read_prev():
    try:
        with open(STATE_PATH) as f:
            return json.load(f)
    except Exception:
        return {}


def _log(msg):
    line = f'{datetime.datetime.now().isoformat(timespec="seconds")} {msg}\n'
    try:
        with open(LOG_PATH, 'a') as f:
            f.write(line)
    except Exception:
        pass


def main():
    prev = _read_prev()
    backend = _is_active('ourmedia')
    tunnel = _is_active('ourmedia-tunnel-named')
    backend_http, _, _ = _http_ok(BACKEND_URL)
    tunnel_reachable, alexa_status, latency = _http_ok(_public_url() + '/alexa',
                                                       method='POST', data=b'{}')
    alexa_auth = _alexa_auth()

    state = {
        'ts': time.time(),
        'backend': backend,
        'tunnel': tunnel,
        'backendHttp': backend_http,
        'tunnelReachable': tunnel_reachable,
        'publicStatus': alexa_status,
        'publicLatencyMs': latency,
        'alexaAuth': alexa_auth,
    }

    # WARN on a healthy->unhealthy transition (avoid log spam while still down).
    for key, label in (('tunnelReachable', 'tunnel/public endpoint'),
                       ('backendHttp', 'backend HTTP'),
                       ('tunnel', 'tunnel service'),
                       ('backend', 'backend service')):
        if prev.get(key) and not state.get(key):
            _log(f'WARN {label} went DOWN (was up at previous check)')
    if prev.get('alexaAuth') and state.get('alexaAuth') is False:
        _log('WARN alexapy session expired (not_authenticated) — re-run scripts/alexa_login.py')

    tmp = STATE_PATH + '.tmp'
    with open(tmp, 'w') as f:
        json.dump(state, f)
    os.replace(tmp, STATE_PATH)
    print(json.dumps(state))


if __name__ == '__main__':
    main()
