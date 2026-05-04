"""
HTTP endpoint tests via Flask test client.
"""
import json
import os

import pytest
import server


# ─────────────────────────── static / index ──────────────────────────────────

class TestStatic:
    def test_index_serves_html(self, client):
        """GET / returns the SPA shell"""
        rv = client.get('/')
        assert rv.status_code == 200
        assert b'<html' in rv.data.lower() or b'<!doctype' in rv.data.lower()

    def test_static_app_js_served(self, client):
        """JS bundle is served from /js/app.js"""
        rv = client.get('/js/app.js')
        assert rv.status_code == 200
        assert b'register(' in rv.data


# ─────────────────────────── summary / browse ────────────────────────────────

class TestBrowse:
    def test_summary_shape(self, client):
        """/api/summary returns counts dict"""
        data = client.get('/api/summary').get_json()
        assert isinstance(data, dict)
        assert 'songs' in data
        assert 'artists' in data
        assert 'albums' in data
        assert isinstance(data['songs'], int)

    def test_artists_paginated(self, client):
        """/api/artists returns paginated rows + total"""
        data = client.get('/api/artists?page=1&limit=3').get_json()
        assert 'items' in data
        assert 'total' in data
        assert len(data['items']) <= 3

    def test_albums_paginated(self, client):
        data = client.get('/api/albums?page=1&limit=3').get_json()
        assert 'items' in data and 'total' in data

    def test_songs_paginated(self, client):
        data = client.get('/api/songs?page=1&limit=3').get_json()
        assert 'items' in data and 'total' in data


# ─────────────────────────── settings / config / clearcache ──────────────────

class TestConfig:
    def test_config_get(self, client):
        """/api/config GET returns dict"""
        data = client.get('/api/config').get_json()
        assert isinstance(data, dict)

    def test_config_post_persists(self, client):
        """/api/config POST writes to disk and is read back"""
        rv = client.post('/api/config', data=json.dumps({'publicUrl': 'https://x.example'}),
                         content_type='application/json')
        assert rv.status_code == 200
        data = client.get('/api/config').get_json()
        assert data.get('publicUrl') == 'https://x.example'

    def test_localip_returns_ip(self, client):
        """/api/localip returns a string IP"""
        ip = client.get('/api/localip').get_json().get('ip')
        assert isinstance(ip, str) and len(ip) >= 7


class TestSettings:
    def test_settings_get_dict(self, client):
        data = client.get('/api/settings').get_json()
        assert isinstance(data, dict)

    def test_settings_post_round_trip(self, client):
        """settings POST values are reflected on next GET (key contract is camelCase)"""
        rv = client.post('/api/settings',
                         data=json.dumps({'defaultPlaylist': 'TestPlaylist'}),
                         content_type='application/json')
        assert rv.status_code == 200
        data = client.get('/api/settings').get_json()
        assert data.get('defaultPlaylist') == 'TestPlaylist'


class TestClearCache:
    def test_clearcache_returns_ok(self, client):
        rv = client.post('/api/clearcache')
        assert rv.status_code == 200
        body = rv.get_json()
        assert body.get('ok') is True or 'error' not in body


# ─────────────────────────── devices CRUD ────────────────────────────────────

class TestDevicesApi:
    def test_initially_empty(self, client):
        """devices list starts empty when isolated"""
        assert client.get('/api/devices').get_json() == []

    def test_register_via_alexa_then_list(self, client, post_alexa):
        """auto-registers a device on any /alexa request and shows it in /api/devices"""
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id='amzn1.ask.device.HELLOTEST')
        data = client.get('/api/devices').get_json()
        assert any(d['deviceId'] == 'amzn1.ask.device.HELLOTEST' for d in data)

    def test_rename(self, client, post_alexa):
        """POST /api/devices/<id> renames the device"""
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id='amzn1.ask.device.RENAMER')
        rv = client.post('/api/devices/amzn1.ask.device.RENAMER',
                         data=json.dumps({'name': 'Garage'}),
                         content_type='application/json')
        assert rv.status_code == 200 and rv.get_json().get('ok') is True
        data = client.get('/api/devices').get_json()
        assert any(d['deviceId'] == 'amzn1.ask.device.RENAMER' and d['name'] == 'Garage' for d in data)

    def test_rename_unknown_404(self, client):
        """rename on unknown device returns 404"""
        rv = client.post('/api/devices/UNKNOWN', data=json.dumps({'name': 'x'}),
                         content_type='application/json')
        assert rv.status_code == 404

    def test_rename_empty_name_400(self, client):
        rv = client.post('/api/devices/UNKNOWN', data=json.dumps({'name': ''}),
                         content_type='application/json')
        assert rv.status_code == 400

    def test_delete(self, client, post_alexa):
        """DELETE /api/devices/<id> removes the device"""
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id='amzn1.ask.device.DELETER')
        rv = client.delete('/api/devices/amzn1.ask.device.DELETER')
        assert rv.status_code == 200
        assert all(d['deviceId'] != 'amzn1.ask.device.DELETER'
                   for d in client.get('/api/devices').get_json())

    def test_delete_unknown_is_idempotent(self, client):
        """deleting an unknown device returns ok"""
        rv = client.delete('/api/devices/UNKNOWN')
        assert rv.status_code == 200


# ─────────────────────────── now-playing endpoints ───────────────────────────

class TestNowPlayingApi:
    def test_currenttrack_empty(self, client):
        """/api/currenttrack on cold start is empty dict"""
        assert client.get('/api/currenttrack').get_json() == {}

    def test_devices_endpoint_empty(self, client):
        """/api/nowplaying_devices on cold start has no items"""
        assert client.get('/api/nowplaying_devices').get_json() == {'items': []}

    def test_history_empty(self, client):
        """/api/nowplaying (history) on cold start has 0 total"""
        data = client.get('/api/nowplaying?page=1&limit=10').get_json()
        assert data == {'items': [], 'total': 0}

    def test_filters_unregistered_devices(self, client, isolated_paths):
        """/api/nowplaying_devices excludes devices not in devices.json"""
        with server.app.test_request_context('/'):
            server.g.device_id = 'rogue-id'
            server.write_np_state({'track': 'X', 'playing': True})
        # rogue-id was never registered, so it should not appear
        data = client.get('/api/nowplaying_devices').get_json()
        assert all(item['deviceId'] != 'rogue-id' for item in data['items'])


# ─────────────────────────── selected / playlists / recent ───────────────────

class TestSelectedApi:
    def test_round_trip(self, client):
        rv = client.post('/api/selected',
                         data=json.dumps({'type': 'track', 'name': 'Foo'}),
                         content_type='application/json')
        assert rv.status_code == 200
        data = client.get('/api/selected').get_json()
        assert data == {'type': 'track', 'name': 'Foo'}


class TestPlaylistsApi:
    def test_returns_list(self, client):
        """/api/playlists returns a list of playlist entries"""
        data = client.get('/api/playlists').get_json()
        assert isinstance(data, dict) or isinstance(data, list)

    def test_rename_persists_and_alexa_finds_new_name(self, client):
        """rename → /api/playlists shows new name AND fuzzy_find_playlist resolves it"""
        items = client.get('/api/playlists').get_json().get('items') or []
        target = next((p for p in items if p.get('id')), None)
        if not target:
            pytest.skip('no playlists with id available')
        old_name = target['name']
        new_name = 'pytest renamed playlist'
        rv = client.post('/api/playlists/rename', data=json.dumps({'id': target['id'], 'name': new_name}),
                         content_type='application/json')
        assert rv.status_code == 200
        # listing reflects new name
        items2 = client.get('/api/playlists').get_json().get('items') or []
        assert any(p['id'] == target['id'] and p['name'] == new_name for p in items2)
        # Alexa fuzzy lookup hits the new name
        name, src = server.fuzzy_find_playlist(new_name)
        assert name == new_name
        assert src == target['source']
        # restore
        client.post('/api/playlists/rename', data=json.dumps({'id': target['id'], 'name': old_name}),
                    content_type='application/json')

    def test_rename_unknown_id_404(self, client):
        rv = client.post('/api/playlists/rename', data=json.dumps({'id': 'NOPE', 'name': 'x'}),
                         content_type='application/json')
        assert rv.status_code == 404

    def test_rename_empty_name_400(self, client):
        rv = client.post('/api/playlists/rename', data=json.dumps({'id': 'x', 'name': ''}),
                         content_type='application/json')
        assert rv.status_code == 400


class TestRecentApi:
    def test_returns_dict_with_items(self, client):
        data = client.get('/api/recent?page=1&limit=5').get_json()
        assert 'items' in data


# ─────────────────────────── stream/artwork 404 ──────────────────────────────

class TestStreamRoutes:
    def test_stream_missing_file(self, client):
        rv = client.get('/stream/no/such/file.mp3')
        assert rv.status_code in (404, 403, 400)

    def test_artwork_missing_file(self, client):
        rv = client.get('/artwork/no/such/file.jpg')
        assert rv.status_code in (404, 403, 400)
