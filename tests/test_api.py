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

    def test_artist_portrait_endpoint(self, client, monkeypatch, tmp_path):
        monkeypatch.setattr(server, 'ARTWORK_CACHE', str(tmp_path))
        monkeypatch.setattr(
            server.bock_artist_art,
            'resolve_portrait',
            lambda *a, **k: {
                'artist': 'Radiohead',
                'art_path': 'artwork_cache/artist-portrait-test.jpg',
                'source': 'deezer',
                'cached': True,
            },
        )
        data = client.get('/api/artist-portrait?artist=Radiohead').get_json()
        assert data['art_path'] == 'artwork_cache/artist-portrait-test.jpg'
        assert data['source'] == 'deezer'

    def test_albums_paginated(self, client):
        data = client.get('/api/albums?page=1&limit=3').get_json()
        assert 'items' in data and 'total' in data

    def test_albums_sort_year(self, client):
        data = client.get('/api/albums?page=1&limit=3&sort=year').get_json()
        assert 'items' in data
        if data['items']:
            assert 'year' in data['items'][0]

    def test_artists_sort_tracks(self, client):
        data = client.get('/api/artists?page=1&limit=3&sort=tracks&order=desc').get_json()
        assert 'items' in data

    def test_albums_sort_added(self, client):
        data = client.get('/api/albums?page=1&limit=3&sort=added&order=desc').get_json()
        assert 'items' in data

    def test_songs_sort_title(self, client):
        data = client.get('/api/songs?page=1&limit=3&sort=title&order=asc').get_json()
        assert 'items' in data

    def test_genres_sort_name(self, client):
        data = client.get('/api/genres?limit=5&sort=name&order=asc').get_json()
        assert 'items' in data
        assert len(data['items']) <= 5

    def test_genres_list(self, client):
        data = client.get('/api/genres?limit=5').get_json()
        assert 'items' in data and 'total' in data
        assert len(data['items']) <= 5

    def test_playlist_cover_fast(self, client, monkeypatch):
        import xml.etree.ElementTree as ET

        monkeypatch.setattr(
            server,
            '_load_playlists_tree',
            lambda: ET.ElementTree(ET.Element('playlists')),
        )
        monkeypatch.setattr(
            server,
            '_find_playlist_key',
            lambda root, pid: ('key1', None) if pid == 'pl-1' else (None, None),
        )
        monkeypatch.setattr(server, '_playlist_meta_from_key', lambda key: {'source': '/fake/list.m3u'})
        monkeypatch.setattr(server, '_m3u_first_paths', lambda source, limit=12: ['/music/a.mp3', '/music/b.mp3'])
        monkeypatch.setattr(
            server,
            'db_query',
            lambda sql, params=(): [{'path': '/music/a.mp3'}],
        )
        data = client.get('/api/playlists/pl-1/cover').get_json()
        assert data.get('path') == '/music/a.mp3'

    def test_playlist_covers_batch(self, client, monkeypatch):
        import xml.etree.ElementTree as ET

        monkeypatch.setattr(
            server,
            '_load_playlists_tree',
            lambda: ET.ElementTree(ET.Element('playlists')),
        )

        def fake_find(root, pid):
            return ('key1', None) if pid in ('pl-1', 'pl-2') else (None, None)

        monkeypatch.setattr(server, '_find_playlist_key', fake_find)
        monkeypatch.setattr(
            server,
            '_playlist_meta_from_key',
            lambda key: {'source': '/fake/list.m3u', 'name': 'Classical Era Mix'},
        )
        monkeypatch.setattr(
            server,
            '_m3u_first_paths',
            lambda source, limit=12: ['/music/classical.mp3', '/music/country.mp3'],
        )

        def fake_db_query(sql, params=()):
            rows = {
                '/music/classical.mp3': {
                    'path': '/music/classical.mp3',
                    'genre': 'Classical',
                    'artist': 'Mozart',
                    'album': 'Symphonies',
                    'track': 'No. 40',
                },
                '/music/country.mp3': {
                    'path': '/music/country.mp3',
                    'genre': 'Country',
                    'artist': 'Willie Nelson',
                    'album': 'Red Headed Stranger',
                    'track': 'Blue Eyes',
                },
            }
            return [rows[p] for p in params if p in rows]

        monkeypatch.setattr(server, 'db_query', fake_db_query)
        data = client.post('/api/playlists/covers', json={'ids': ['pl-1', 'pl-2', 'missing']}).get_json()
        assert data['covers']['pl-1'] == '/music/classical.mp3'
        assert data['covers']['pl-2'] == '/music/country.mp3'
        assert data['collages']['pl-1'] == ['/music/classical.mp3', '/music/country.mp3']
        assert data['collages']['pl-2'] == ['/music/classical.mp3', '/music/country.mp3']
        assert 'missing' not in data['covers']

    def test_playlist_cover_uses_first_track_in_order(self, client, monkeypatch):
        """Cover art is the first .m3u entry, not a keyword-scored match deeper in the list."""
        import xml.etree.ElementTree as ET

        monkeypatch.setattr(
            server,
            '_load_playlists_tree',
            lambda: ET.ElementTree(ET.Element('playlists')),
        )
        monkeypatch.setattr(server, '_find_playlist_key', lambda root, pid: ('key1', None))
        monkeypatch.setattr(
            server,
            '_playlist_meta_from_key',
            lambda key: {'source': '/fake/classical.m3u', 'name': 'Classical Era Mix'},
        )
        monkeypatch.setattr(
            server,
            '_m3u_first_paths',
            lambda source, limit=12: ['/music/country.mp3', '/music/classical.mp3'],
        )

        def fake_db_query(sql, params=()):
            rows = {
                '/music/classical.mp3': {
                    'path': '/music/classical.mp3',
                    'genre': 'Classical',
                    'artist': 'Bach',
                    'album': 'Goldberg',
                    'track': 'Aria',
                },
                '/music/country.mp3': {
                    'path': '/music/country.mp3',
                    'genre': 'Country',
                    'artist': 'Dolly Parton',
                    'album': 'Hits',
                    'track': 'Jolene',
                },
            }
            return [rows[p] for p in params if p in rows]

        monkeypatch.setattr(server, 'db_query', fake_db_query)
        data = client.get('/api/playlists/pl-1/cover').get_json()
        assert data.get('path') == '/music/country.mp3'

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
        data = client.get('/api/nowplaying_devices').get_json()
        assert data['items'] == []
        assert 'controlsAvailable' in data

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

    def test_resolve_library_path_by_basename(self, tmp_path, monkeypatch):
        import server
        root = tmp_path / 'music'
        monkeypatch.setattr(server, 'MUSIC_ROOT', str(root))
        real = root / 'Artist' / 'Album' / 'Chicago.mp3'
        real.parent.mkdir(parents=True)
        real.write_bytes(b'ID3')
        stale = root / 'exportedPlaylists' / 'Chicago.mp3'

        def fake_db_query(sql, params=()):
            if 'songs_cache' in sql and params:
                return [{'path': str(real), 'title': 'Chicago', 'artist': 'Chicago'}]
            return []

        monkeypatch.setattr(server, 'db_query', fake_db_query)
        resolved = server._resolve_library_path(str(stale), title='Chicago', artist='Chicago')
        assert resolved == str(real.resolve())

    def test_artwork_missing_file(self, client):
        rv = client.get('/artwork/no/such/file.jpg')
        assert rv.status_code in (404, 403, 400)


class TestNewFeatures:
    def test_search_short_query(self, client):
        data = client.get('/api/search?q=a').get_json()
        assert data['playlists'] == [] and data['songs'] == []

    def test_search_returns_shape(self, client):
        data = client.get('/api/search?q=test&limit=5').get_json()
        assert 'playlists' in data and 'songs' in data

    def test_search_songs_match_title_not_album(self, client, monkeypatch):
        """Album name matches belong in albums section, not as every track in songs."""
        calls = []

        def recording_db_query(sql, params=()):
            calls.append((sql, params))
            if 'GROUP BY album' in sql:
                return [{'album': 'Mamma Mia', 'artist': 'ABBA', 'art_path': '/c.mp3'}]
            if 'GROUP BY' in sql and 'genre' in sql:
                return []
            if 'GROUP BY' in sql:
                return []
            if 'FROM songs_cache' in sql or 'songs_fts' in sql:
                return [
                    {'title': 'Waterloo', 'artist': 'ABBA', 'album': 'Mamma Mia!', 'path': '/a.mp3'},
                    {'title': 'Waterloo - From Mamma Mia! Here We Go Again', 'artist': 'ABBA',
                     'album': 'Mamma Mia!', 'path': '/b.mp3'},
                    {'title': 'Mamma Mia', 'artist': 'ABBA', 'album': '[2001] ABBA', 'path': '/c.mp3'},
                    {'title': 'Dancing Queen', 'artist': 'ABBA', 'album': 'Mamma Mia! Mania', 'path': '/d.mp3'},
                ]
            return []

        monkeypatch.setattr(server, 'db_query', recording_db_query)
        monkeypatch.setattr('bock_search.fts_songs_ranked', lambda *a, **k: recording_db_query(
            'FROM songs_cache', ()))
        monkeypatch.setattr(server, '_load_playlist_entries', lambda: [])

        data = client.get('/api/search?q=mamma&limit=30').get_json()
        assert len(data['albums']) == 1
        assert data['albums'][0]['name'] == 'Mamma Mia'
        assert [s['title'] for s in data['songs']] == ['Mamma Mia']
        assert 'counts' in data
        assert 'preview' in data


class TestLibrarySearchSongMatch:
    def test_excludes_album_only_and_soundtrack_suffix(self):
        m = server._library_search_song_match
        assert not m('mamma', 'Waterloo', 'Mamma Mia!')
        assert not m('mamma', 'Dancing Queen', 'Mamma Mia! Mania')
        assert not m('mamma', 'Waterloo - From Mamma Mia! Here We Go Again', 'Mamma Mia!')
        assert m('mamma', 'Mamma Mia', '[2001] ABBA')
        assert m('mamma', 'Mamma Mia - Radio Version', '[2010] Hits')

    def test_plex_sync_status(self, client):
        data = client.get('/api/plex_sync/status').get_json()
        assert 'playlistCount' in data and 'logPath' in data

    def test_favorites_crud(self, client, isolated_paths):
        rv = client.post('/api/favorites', data=json.dumps({'path': '/tmp/x.mp3', 'title': 'T'}),
                         content_type='application/json')
        assert rv.status_code == 200
        items = client.get('/api/favorites').get_json()['items']
        assert any(x['path'] == '/tmp/x.mp3' for x in items)
        client.delete('/api/favorites', data=json.dumps({'path': '/tmp/x.mp3'}),
                      content_type='application/json')
        assert not client.get('/api/favorites').get_json()['items']

    def test_ratings_isolated_by_member(self, client, isolated_paths):
        song = '/music/shared.mp3'
        rv = client.put('/api/ratings', data=json.dumps({
            'kind': 'song', 'id': song, 'stars': 5, 'memberId': 'p-andy',
        }), content_type='application/json')
        assert rv.status_code == 200
        rv = client.put('/api/ratings', data=json.dumps({
            'kind': 'song', 'id': song, 'stars': 2, 'memberId': 'p-emma',
        }), content_type='application/json')
        assert rv.status_code == 200
        andy = client.get('/api/ratings?memberId=p-andy').get_json()['items']
        emma = client.get('/api/ratings?memberId=p-emma').get_json()['items']
        assert any(x['id'] == song and x['stars'] == 5 for x in andy)
        assert any(x['id'] == song and x['stars'] == 2 for x in emma)
        assert client.get(f'/api/ratings/lookup?kind=song&id={song}&memberId=p-andy').get_json()['stars'] == 5

    def test_dashboard_quick(self, client):
        data = client.get('/api/dashboard/quick').get_json()
        assert 'recent' in data and 'favorites' in data

    def test_analytics_export_csv(self, client, isolated_paths):
        rv = client.get('/api/analytics/export')
        assert rv.status_code == 200
        assert b'track' in rv.data and b'date' in rv.data

    def test_create_and_detail_playlist(self, client, isolated_paths, tmp_path, monkeypatch):
        import server
        pl_xml = tmp_path / 'ServerPlaylists.xml'
        pl_xml.write_text(
            '<?xml version="1.0" encoding="utf-8"?><ArrayOfEntry xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"></ArrayOfEntry>',
            encoding='utf-8',
        )
        bock_dir = tmp_path / 'bock'
        monkeypatch.setattr(server, 'PLAYLISTS_XML', str(pl_xml))
        monkeypatch.setattr(server, 'DATA_DIR', str(tmp_path))
        monkeypatch.setattr(server, 'BOCK_PLAYLIST_DIR', str(bock_dir))
        rv = client.post('/api/playlists', data=json.dumps({'name': 'Test PL', 'tracks': []}),
                         content_type='application/json')
        assert rv.status_code == 201
        pid = rv.get_json()['id']
        detail = client.get(f'/api/playlists/{pid}').get_json()
        assert detail['name'] == 'Test PL'
        sort_rv = client.post(f'/api/playlists/{pid}/sort',
                              data=json.dumps({'by': 'title', 'order': 'asc'}),
                              content_type='application/json')
        assert sort_rv.status_code == 200

    def test_create_smart_playlist_android_rules_body(self, client, isolated_paths, tmp_path, monkeypatch):
        import server
        smart_path = tmp_path / 'smart_playlists.json'
        smart_path.write_text('[]', encoding='utf-8')
        monkeypatch.setattr(server, 'SMART_PLAYLISTS_PATH', str(smart_path))
        monkeypatch.setattr(server, '_refresh_smart_playlist', lambda item: (item, {'ok': True}))
        body = {
            'name': 'Evening Jazz',
            'rules': [
                {'type': 'genre', 'value': 'jazz'},
                {'type': 'limit', 'value': 40},
            ],
            'refresh': True,
        }
        rv = client.post('/api/smart_playlists', data=json.dumps(body), content_type='application/json')
        assert rv.status_code == 201
        data = rv.get_json()
        assert data['name'] == 'Evening Jazz'
        assert len(data.get('rules', [])) == 2


class TestClientAnalytics:
    def test_connect_registers_device(self, client, isolated_paths):
        rv = client.post('/api/clients/report', data=json.dumps({
            'clientId': 'abc-123-test',
            'platform': 'android',
            'deviceName': 'Pixel Test',
            'event': 'connect',
        }), content_type='application/json')
        assert rv.status_code == 200
        assert rv.get_json()['deviceId'] == 'client-abc-123-test'
        devices = client.get('/api/devices').get_json()
        match = next(d for d in devices if d['deviceId'] == 'client-abc-123-test')
        assert match['platform'] == 'android'
        assert match['connectCount'] == 1

    def test_play_appends_stream_history(self, client, isolated_paths):
        rv = client.post('/api/clients/report', data=json.dumps({
            'clientId': 'play-test-id',
            'platform': 'ios',
            'deviceName': 'iPhone Test',
            'event': 'play',
            'track': 'Test Song',
            'artist': 'Test Artist',
            'filepath': '/music/test.mp3',
        }), content_type='application/json')
        assert rv.status_code == 200
        analytics = client.get('/api/analytics').get_json()
        assert analytics['totalPlays'] == 1
        breakdown = analytics['deviceBreakdown']
        row = next(d for d in breakdown if d['deviceId'] == 'client-play-test-id')
        assert row['plays'] == 1
        assert row['platform'] == 'ios'

    def test_download_recorded(self, client, isolated_paths):
        rv = client.post('/api/clients/report', data=json.dumps({
            'clientId': 'dl-test-id',
            'platform': 'android',
            'event': 'download',
            'collectionTitle': 'Road Trip',
            'collectionKind': 'playlist',
            'trackCount': 12,
        }), content_type='application/json')
        assert rv.status_code == 200
        analytics = client.get('/api/analytics').get_json()
        row = next(d for d in analytics['deviceBreakdown'] if d['deviceId'] == 'client-dl-test-id')
        assert row['downloads'] == 1

    def test_playback_updates_now_playing(self, client, isolated_paths):
        cid = 'np-playback-test'
        rv = client.post('/api/clients/report', data=json.dumps({
            'clientId': cid,
            'platform': 'ios',
            'deviceName': 'iPhone NP',
            'event': 'playback',
            'track': 'Live Track',
            'artist': 'Artist',
            'filepath': '/music/live.mp3',
            'playing': True,
            'paused': False,
            'offset_ms': 12000,
            'duration_ms': 180000,
        }), content_type='application/json')
        assert rv.status_code == 200
        did = f'client-{cid}'
        all_items = client.get('/api/nowplaying_devices').get_json()['items']
        row = next(i for i in all_items if i['deviceId'] == did)
        assert row['track'] == 'Live Track'
        assert row['platform'] == 'ios'
        mobile = client.get(f'/api/nowplaying_devices?viewerClientId={cid}').get_json()['items']
        assert all(not i['deviceId'].startswith('client-') for i in mobile)

    def test_connect_debounced_within_hour(self, client, isolated_paths):
        body = json.dumps({
            'clientId': 'debounce-id',
            'platform': 'android',
            'event': 'connect',
        })
        client.post('/api/clients/report', data=body, content_type='application/json')
        client.post('/api/clients/report', data=body, content_type='application/json')
        devices = client.get('/api/devices').get_json()
        match = next(d for d in devices if d['deviceId'] == 'client-debounce-id')
        assert match['connectCount'] == 1

    def test_analytics_device_filter(self, client, isolated_paths):
        for cid, track in (('device-a', 'Song A'), ('device-b', 'Song B')):
            rv = client.post('/api/clients/report', data=json.dumps({
                'clientId': cid,
                'platform': 'android',
                'deviceName': f'Phone {cid[-1].upper()}',
                'event': 'play',
                'track': track,
                'artist': 'Artist',
                'filepath': f'/music/{track.lower().replace(" ", "")}.mp3',
            }), content_type='application/json')
            assert rv.status_code == 200
        all_analytics = client.get('/api/analytics').get_json()
        assert all_analytics['totalPlays'] == 2
        one = client.get('/api/analytics?deviceId=client-device-a').get_json()
        assert one['totalPlays'] == 1
        assert one['topTracks'][0]['name'] == 'Song A'
        assert one['dateRange']['deviceId'] == 'client-device-a'


class TestLyricsApi:
    def test_lrclib_search_fallback(self, client, isolated_paths, monkeypatch):
        import server
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'plainLyrics': 'Line one\nLine two',
            'syncedLyrics': '',
        })
        rv = client.get('/api/lyrics', query_string={
            'path': '/music/test/song.mp3',
            'title': 'Test Song',
            'artist': 'Test Artist',
            'album': 'Test Album',
            'duration': '180',
        })
        assert rv.status_code == 200
        data = rv.get_json()
        assert 'Line one' in data['plain']
        assert data['source'] == 'lrclib'
        assert len(data['lines']) >= 2
        assert data['estimated'] is True

    def test_plain_lyrics_get_estimated_karaoke_lines(self, client, isolated_paths, monkeypatch):
        import server
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'plainLyrics': 'First line\nSecond line',
            'syncedLyrics': '',
        })
        rv = client.get('/api/lyrics', query_string={
            'path': '/music/track.mp3',
            'title': 'Track',
            'artist': 'Artist',
            'duration': '120',
        })
        assert rv.status_code == 200
        data = rv.get_json()
        assert len(data['lines']) == 2
        assert data['estimated'] is True

    def test_real_sync_beats_estimated(self, client, isolated_paths, monkeypatch):
        import server
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'syncedLyrics': '[00:05.00]Real line',
            'plainLyrics': 'Real line',
        })
        rv = client.get('/api/lyrics', query_string={
            'path': '/music/track.mp3',
            'title': 'Track',
            'artist': 'Artist',
            'duration': '180',
        })
        data = rv.get_json()
        assert data['lines'][0]['timeMs'] == 5000
        assert not data.get('estimated')

    def test_lyrics_does_not_require_file_on_disk(self, client, isolated_paths, monkeypatch):
        import server
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'plainLyrics': 'Remote lyrics',
            'syncedLyrics': '',
        })
        rv = client.get('/api/lyrics', query_string={
            'path': '/missing/on/disk.mp3',
            'title': 'Remote',
            'artist': 'Artist',
            'duration': '200',
        })
        assert rv.status_code == 200
        assert rv.get_json()['plain'] == 'Remote lyrics'

    def test_lrclib_synced_preferred_over_plain_embedded(self, client, isolated_paths, monkeypatch, tmp_path):
        import server
        audio = tmp_path / 'song.mp3'
        audio.write_bytes(b'ID3')
        monkeypatch.setattr(server, '_embedded_lyrics', lambda _p: 'Plain embedded only')
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'syncedLyrics': '[00:05.00]Synced line\n[00:10.00]Next line',
            'plainLyrics': 'Synced line\nNext line',
        })
        rv = client.get('/api/lyrics', query_string={'path': str(audio)})
        assert rv.status_code == 200
        data = rv.get_json()
        assert data['synced'] is True
        assert len(data['lines']) == 2
        assert data['lines'][0]['text'] == 'Synced line'
        assert data['source'] == 'lrclib'

    def test_lyrics_cache_persists(self, client, isolated_paths, monkeypatch, tmp_path):
        import server
        monkeypatch.setattr(server, 'LYRICS_CACHE_DIR', str(tmp_path / 'lyrics_cache'))
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'syncedLyrics': '[00:01.00]Hello',
            'plainLyrics': 'Hello',
        })
        path = '/music/cached/song.mp3'
        rv = client.get('/api/lyrics', query_string={
            'path': path,
            'title': 'Hello',
            'artist': 'Artist',
            'duration': '120',
        })
        assert rv.status_code == 200
        assert rv.get_json()['synced'] is True
        cached = server._lyrics_cache_read(path)
        assert cached is not None
        assert cached['synced'] is True
        assert cached['lines'][0]['text'] == 'Hello'


class TestAppDownload:
    def test_app_download_requires_auth(self, client, isolated_paths):
        import json
        cfg = isolated_paths / 'state' / 'config.json'
        cfg.write_text(json.dumps({
            'appDownload': {'username': 'morejava', 'password': 'test-dl-pass'},
        }))
        assert client.get('/app').status_code == 401
        assert client.get('/download/bockmedia-console.apk').status_code == 401
        assert client.get('/download/bockmedia-console.ipa').status_code == 401
        assert client.get('/download/bockmedia-console-ios.plist').status_code == 401

    def test_app_download_with_basic_auth(self, client, isolated_paths):
        import json
        cfg = isolated_paths / 'state' / 'config.json'
        cfg.write_text(json.dumps({
            'appDownload': {'username': 'morejava', 'password': 'test-dl-pass'},
            'publicUrl': 'https://alexa.example.test',
        }))
        apk = isolated_paths / 'mma' / 'bockmedia-console.apk'
        apk.write_bytes(b'PK\x03\x04fake')
        ipa = isolated_paths / 'mma' / 'bockmedia-console.ipa'
        ipa.write_bytes(b'PK\x03\x04fake-ipa')
        auth = ('morejava', 'test-dl-pass')
        rv = client.get('/app', auth=auth)
        assert rv.status_code == 200
        assert b'Download APK' in rv.data
        assert b"iPhone" in rv.data
        assert b'Install on iPhone' in rv.data
        assert b'release-notes' in rv.data
        assert b'tag-feat' in rv.data or b'tag-fix' in rv.data
        dl = client.get('/download/bockmedia-console.apk', auth=auth)
        assert dl.status_code == 200
        assert dl.headers.get('Content-Type', '').startswith('application/vnd.android')
        ios = client.get('/download/bockmedia-console.ipa', auth=auth)
        assert ios.status_code == 200
        manifest = client.get('/download/bockmedia-console-ios.plist', auth=auth)
        assert manifest.status_code == 200
        assert b'com.bockmedia.console' in manifest.data
        assert b'bockmedia-console.ipa' in manifest.data

    def test_app_info_api(self, client, isolated_paths):
        import json
        cfg = isolated_paths / 'state' / 'config.json'
        cfg.write_text(json.dumps({'publicUrl': 'https://alexa.example.test'}))
        apk = isolated_paths / 'mma' / 'bockmedia-console.apk'
        apk.write_bytes(b'PK\x03\x04fake')
        sidecar = isolated_paths / 'mma' / 'bockmedia-console.version'
        sidecar.write_text('2.4.2', encoding='utf-8')
        data = client.get('/api/app/info').get_json()
        assert data['android']['available'] is True
        assert data['android']['version'] == '2.4.2'
        assert data['android']['downloadHref'].startswith('/download/bockmedia-console.apk?t=')
        assert data['releases'][0]['version']  # latest from app-release-notes.json
        assert any('Mix Muse' in (it.get('text') or '') for rel in data['releases'] for it in rel.get('items', []))

    def test_app_info_uses_sidecar_over_gradle(self, client, isolated_paths):
        import json
        (isolated_paths / 'state' / 'config.json').write_text('{}')
        (isolated_paths / 'mma' / 'bockmedia-console.apk').write_bytes(b'PK\x03\x04fake')
        (isolated_paths / 'mma' / 'bockmedia-console.version').write_text('9.9.9', encoding='utf-8')
        data = client.get('/api/app/info').get_json()
        assert data['android']['version'] == '9.9.9'


class TestParityFeatures:
    def test_feature_flags(self, client):
        data = client.get('/api/config/features').get_json()
        assert data.get('handoff') is True
        assert data.get('loudnessNormalization') is not None

    def test_playlist_folders_crud(self, client):
        rv = client.post('/api/playlist_folders', json={'name': 'Kids'})
        assert rv.status_code == 201
        fid = rv.get_json()['id']
        tree = client.get('/api/playlist_folders').get_json()
        assert any(f['id'] == fid for f in tree.get('folders', []))
        rv = client.delete(f'/api/playlist_folders/{fid}')
        assert rv.status_code == 200

    def test_continue_empty(self, client):
        data = client.get('/api/continue').get_json()
        assert 'resume' in data
        assert 'recent' in data

    def test_library_new(self, client):
        data = client.get('/api/library/new?since=7d&limit=5').get_json()
        assert 'albums' in data
        assert 'tracks' in data

    def test_discover_weekly(self, client):
        data = client.get('/api/recommendations/discover-weekly').get_json()
        assert 'sections' in data

    def test_search_suggest(self, client):
        data = client.get('/api/search/suggest?q=a').get_json()
        assert 'songs' in data
        assert 'playlists' in data

    def test_search_extended_buckets(self, client):
        data = client.get('/api/search?q=ab').get_json()
        assert 'genres' in data
        assert 'smartPlaylists' in data
        assert 'radios' in data
        assert 'similar' in data
        assert 'counts' in data
        assert 'preview' in data

    def test_search_pins(self, client, isolated_paths):
        rv = client.put('/api/search/pins', json={
            'pins': [{'kind': 'genre', 'title': 'Jazz', 'name': 'Jazz'}],
            'memberId': 'p-andy',
        })
        assert rv.status_code == 200
        data = client.get('/api/search/pins?memberId=p-andy').get_json()
        assert data['pins'][0]['name'] == 'Jazz'
        emma = client.get('/api/search/pins?memberId=p-emma').get_json()
        assert emma['pins'] == []

    def test_analyze_loudness_status(self, client):
        data = client.get('/api/library/analyze-loudness/status').get_json()
        assert 'running' in data

    def test_handoff_missing_track(self, client):
        rv = client.post('/api/playback/handoff', json={
            'fromDeviceId': 'local-phone',
            'toDeviceId': 'local-phone',
            'offsetMs': 0,
            'context': {},
        })
        assert rv.status_code == 400

    def test_smart_refresh_all(self, client):
        data = client.post('/api/smart_playlists/refresh_all').get_json()
        assert data.get('ok') is True


class TestDiscoveryFeatures:
    def test_mix_muse_status(self, client):
        data = client.get('/api/mix-muse/status').get_json()
        assert 'configured' in data
        assert 'provider' in data

    def test_feature_flags_discovery(self, client):
        data = client.get('/api/config/features').get_json()
        assert data.get('resonance') is True
        assert 'mixMuse' in data

    def test_resonance_similar_requires_path(self, client):
        rv = client.get('/api/resonance/similar')
        assert rv.status_code == 400

    def test_resonance_mix_invalid_seed(self, client):
        rv = client.post('/api/resonance/mix', json={'seedKind': 'song', 'path': '/nonexistent/track.mp3', 'save': False})
        assert rv.status_code in (400, 404)

    def test_resonance_radio_invalid_seed(self, client):
        rv = client.post('/api/resonance/radio', json={'seedKind': 'song', 'path': '/nonexistent/track.mp3'})
        assert rv.status_code in (400, 404)

    def test_resonance_similar(self, client, sample_track):
        from urllib.parse import quote
        path = quote(sample_track['path'], safe='')
        rv = client.get(f'/api/resonance/similar?path={path}')
        assert rv.status_code == 200
        data = rv.get_json()
        assert 'tracks' in data
        assert 'seed' in data

    def test_resonance_mix(self, client, sample_track):
        rv = client.post('/api/resonance/mix', json={
            'seedKind': 'song',
            'path': sample_track['path'],
            'save': False,
            'maxTracks': 10,
        })
        assert rv.status_code == 200
        data = rv.get_json()
        assert data.get('source') == 'resonance'
        assert 'tracks' in data

    def test_resonance_radio(self, client, sample_track):
        rv = client.post('/api/resonance/radio', json={
            'seedKind': 'song',
            'path': sample_track['path'],
            'maxTracks': 10,
        })
        assert rv.status_code == 200
        data = rv.get_json()
        assert data.get('shuffle') is True
        assert 'tracks' in data

    def test_acquire_status(self, client):
        data = client.get('/api/acquire/status').get_json()
        assert data.get('source') == 'musicbrainz'
        assert 'enabled' in data

    def test_acquire_suggest_requires_artist(self, client):
        assert client.get('/api/acquire/suggest').status_code == 400

    def test_acquire_suggest_mocked(self, client, sample_track, monkeypatch):
        import bock_acquire

        def fake_suggest(**kwargs):
            return {
                'source': 'musicbrainz',
                'seed': {'artist': kwargs.get('artist') or 'Test Artist'},
                'suggestions': [{'name': 'New Band', 'inLibrary': False, 'reasons': ['Shared tag: rock']}],
            }

        monkeypatch.setattr(bock_acquire, 'suggest_for_seed', lambda *a, **k: fake_suggest(**k))
        rv = client.get('/api/acquire/suggest?artist=Test%20Artist')
        assert rv.status_code == 200
        data = rv.get_json()
        assert data['suggestions'][0]['name'] == 'New Band'

        rv2 = client.post('/api/acquire/suggest', json={
            'seedKind': 'song',
            'path': sample_track['path'],
        })
        assert rv2.status_code == 200

    def test_acquire_explore_mocked(self, client, monkeypatch):
        import bock_acquire

        monkeypatch.setattr(
            bock_acquire, 'explore_library',
            lambda *a, **k: {'source': 'musicbrainz', 'suggestions': [], 'seed': {'kind': 'library'}},
        )
        rv = client.get('/api/acquire/explore')
        assert rv.status_code == 200

    def test_feature_flags_acquire(self, client):
        data = client.get('/api/config/features').get_json()
        assert 'acquireIdeas' in data
