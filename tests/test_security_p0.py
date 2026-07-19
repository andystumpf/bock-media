"""P0 security — production tunnel ignores open-LAN without credentials."""
import json

import server


def _write_config(isolated_paths, **mobile_api):
    cfg_path = isolated_paths / 'state' / 'config.json'
    cfg = {'publicUrl': 'https://bock.example.com', 'mobileApi': mobile_api}
    cfg_path.write_text(json.dumps(cfg), encoding='utf-8')
    server._config_cache = cfg  # noqa: SLF001
    server._config_mtime = cfg_path.stat().st_mtime


def test_open_lan_ignored_when_tunnel_without_credentials(client, isolated_paths):
    _write_config(
        isolated_paths,
        token='',
        allowOpenLanApi=True,
        allowOpenLanMedia=True,
    )
    assert server._production_tunnel_configured() is True
    assert server._allow_open_lan_api() is False
    assert server._allow_open_lan_media() is False
    rv = client.get('/api/summary', headers={'Host': '192.168.1.1:3001'})
    assert rv.status_code == 403


def test_health_includes_security_audit(client, isolated_paths):
    _write_config(
        isolated_paths,
        token='',
        allowOpenLanApi=True,
        allowOpenLanMedia=True,
    )
    data = client.get('/api/health').get_json()
    assert data.get('productionTunnel') is True
    assert data.get('insecureConfig') is True
    assert any(w.get('id') == 'tunnel_without_credentials' for w in (data.get('securityWarnings') or []))


def test_music_video_cookies_stale_when_missing():
    stale, hint = server._music_video_cookies_stale({'cookiesPresent': False})
    assert stale is True
    assert 'youtube_cookies' in (hint or '')
