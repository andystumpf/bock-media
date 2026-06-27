"""Security sprint #18 — LAN API auth, media signatures, tunnel header trust."""
import json

import pytest

import server


def _write_config(isolated_paths, **mobile_api):
    cfg_path = isolated_paths / 'state' / 'config.json'
    cfg = {'mobileApi': mobile_api}
    cfg_path.write_text(json.dumps(cfg), encoding='utf-8')
    server._config_cache = cfg  # noqa: SLF001
    server._config_mtime = cfg_path.stat().st_mtime


def _set_web_password(isolated_paths, password='secret'):
    prefs = isolated_paths / 'mma' / 'Preferences.xml'
    text = prefs.read_text(encoding='utf-8')
    if '<WebPassword>' in text:
        import re
        text = re.sub(r'<WebPassword>.*?</WebPassword>', f'<WebPassword>{password}</WebPassword>', text)
    else:
        text = text.replace('</Root>', f'  <WebPassword>{password}</WebPassword>\n</Root>')
    prefs.write_text(text, encoding='utf-8')


class TestTunnelTrust:
    def test_cf_headers_from_lan_client_not_tunnel(self, client):
        rv = client.get(
            '/api/health',
            headers={
                'Host': '192.168.1.187:3001',
                'Cf-Connecting-Ip': '203.0.113.1',
                'Cf-Ray': 'fake',
            },
            environ_overrides={'REMOTE_ADDR': '192.168.1.50'},
        )
        assert rv.status_code == 200

    def test_cf_headers_on_loopback_is_tunnel(self, client):
        rv = client.get(
            '/stream/mnt/Music/x.mp3',
            headers={
                'Host': 'alexa.example.com',
                'Cf-Connecting-Ip': '203.0.113.1',
                'Cf-Ray': 'abc',
            },
            environ_overrides={'REMOTE_ADDR': '127.0.0.1'},
        )
        # Tunnel alexa path — unsigned stream allowed
        assert rv.status_code in (404, 415)


class TestLanApiAuth:
    def test_lan_post_blocked_without_credentials(self, client, isolated_paths):
        _write_config(
            isolated_paths,
            token='mobile-secret',
            allowOpenLanApi=False,
            allowOpenLanMedia=True,
        )
        rv = client.post('/api/clearcache', json={}, headers={'Host': '192.168.1.1:3001'})
        assert rv.status_code == 401

    def test_lan_post_with_mobile_token(self, client, isolated_paths):
        _write_config(
            isolated_paths,
            token='mobile-secret',
            allowOpenLanApi=False,
            allowOpenLanMedia=True,
        )
        rv = client.post(
            '/api/clearcache',
            json={},
            headers={'Host': '192.168.1.1:3001', 'Authorization': 'Bearer mobile-secret'},
        )
        assert rv.status_code == 200

    def test_lan_post_opt_in_open_api(self, client, isolated_paths):
        _write_config(
            isolated_paths,
            token='mobile-secret',
            allowOpenLanApi=True,
            allowOpenLanMedia=True,
        )
        rv = client.post('/api/clearcache', json={}, headers={'Host': '192.168.1.1:3001'})
        assert rv.status_code == 200


class TestMediaAuth:
    def test_unsigned_stream_blocked_on_lan(self, client, isolated_paths):
        _write_config(
            isolated_paths,
            token='mobile-secret',
            allowOpenLanApi=True,
            allowOpenLanMedia=False,
        )
        rv = client.get('/stream/mnt/Music/x.mp3', headers={'Host': '192.168.1.1:3001'})
        assert rv.status_code == 401

    def test_signed_stream_allowed_on_lan(self, client, isolated_paths, monkeypatch):
        _write_config(
            isolated_paths,
            token='mobile-secret',
            allowOpenLanApi=True,
            allowOpenLanMedia=False,
        )
        signed = server._append_media_sig('/stream/mnt/Music/x.mp3')
        rv = client.get(signed, headers={'Host': '192.168.1.1:3001'})
        assert rv.status_code in (404, 415)

    def test_media_sign_endpoint(self, client, isolated_paths):
        _write_config(
            isolated_paths,
            token='mobile-secret',
            allowOpenLanApi=True,
            allowOpenLanMedia=False,
        )
        rv = client.get(
            '/api/media/sign?path=/artwork/mnt/Music/cover.jpg',
            headers={'Host': '192.168.1.1:3001', 'Authorization': 'Bearer mobile-secret'},
        )
        assert rv.status_code == 200
        data = rv.get_json()
        assert data['url'].startswith('/artwork/')
        assert 'sig=' in data['url']


class TestMediaSignature:
    def test_round_trip(self, isolated_paths):
        signed = server._append_media_sig('/stream/a/b.mp3', {'title': 'T'})
        path, _, qs = signed.partition('?')
        with server.app.test_request_context(f'{signed}', headers={'Host': '192.168.1.1:3001'}):
            assert server._verify_media_signature() is True

    def test_tampered_sig_rejected(self, isolated_paths):
        signed = server._append_media_sig('/stream/a/b.mp3')
        bad = signed.replace('sig=', 'sig=deadbeef')
        with server.app.test_request_context(bad, headers={'Host': '192.168.1.1:3001'}):
            assert server._verify_media_signature() is False
