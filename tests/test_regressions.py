"""
Regression tests for defects found in production (2026-06).

Each class maps to a real bug: stuck Now Playing rows, device correlation,
collision-safe play verbs, cache-busting, etc.
"""
import json
import os
import time

import pytest

import server


def _register(client, post_alexa, device_id, name=None):
    post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id=device_id)
    if name:
        client.post(f'/api/devices/{device_id}',
                    data=json.dumps({'name': name}),
                    content_type='application/json')


def _seed_playing(post_alexa, sample_track, device_id):
    token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
    post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=device_id)
    return token


# ─────────────────────────── stuck Now Playing ───────────────────────────────

class TestStuckNowPlaying:
    """PlaybackStopped must not mark paused; stop must remove rows; paused expires."""

    def test_playback_stopped_does_not_set_paused(self, client, post_alexa, sample_track, isolated_paths):
        """Regression: stop/replace events were leaving permanent paused rows."""
        did = 'amzn1.ask.device.STOPNOPAUSE'
        _register(client, post_alexa, did, 'Kitchen Show')
        token = _seed_playing(post_alexa, sample_track, did)
        post_alexa('AudioPlayer.PlaybackStopped', token=token, device_id=did)

        st = server.read_np_state_for_device(did)
        assert st is not None, 'existing row should remain for resume'
        assert st.get('playing') is False
        assert not st.get('paused'), 'PlaybackStopped must not set paused'

        items = client.get('/api/nowplaying_devices').get_json()['items']
        # Recently-stopped rows stay visible for resume, flagged stopped.
        rows = [i for i in items if i['deviceId'] == did]
        assert all(i['stopped'] and not i['paused'] for i in rows), \
            'idle row must be flagged stopped, not paused/playing'

    def test_playback_stopped_without_prior_state_is_noop(self, client, post_alexa, isolated_paths):
        """Regression: identify/test sweeps created trackless stuck rows."""
        did = 'amzn1.ask.device.GHOST'
        _register(client, post_alexa, did)
        token = server.encode_token({'tracks': ['/no/such.mp3'], 'idx': 0})
        post_alexa('AudioPlayer.PlaybackStopped', token=token, device_id=did)

        assert server.read_np_state_for_device(did) is None
        assert client.get('/api/nowplaying_devices').get_json()['items'] == []

    def test_playback_finished_ignores_stale_token(self, client, post_alexa, sample_track, isolated_paths):
        """Regression: Finished for track N must not clear playing on track N+1."""
        did = 'amzn1.ask.device.FINISHSTALE'
        _register(client, post_alexa, did, 'Office Show')
        t0 = server.encode_token({'tracks': [sample_track['path'], sample_track['path']], 'idx': 0})
        t1 = server.encode_token({'tracks': [sample_track['path'], sample_track['path']], 'idx': 1})
        post_alexa('AudioPlayer.PlaybackStarted', token=t1, device_id=did)
        post_alexa('AudioPlayer.PlaybackFinished', token=t0, device_id=did)
        st = server.read_np_state_for_device(did)
        assert st is not None
        assert st.get('playing') is True

    def test_stop_intent_removes_now_playing_row(self, client, post_alexa, sample_track, isolated_paths):
        """Regression: stop left playing=False rows that never expired."""
        did = 'amzn1.ask.device.STOPREMOVE'
        _register(client, post_alexa, did, 'Office Show')
        _seed_playing(post_alexa, sample_track, did)

        resp = post_alexa('IntentRequest', 'AMAZON.StopIntent', device_id=did)
        assert any(d['type'] == 'AudioPlayer.Stop'
                   for d in resp.get('response', {}).get('directives', []) or [])

        assert server.read_np_state_for_device(did) is None
        assert client.get('/api/nowplaying_devices').get_json()['items'] == []

    def test_pause_intent_keeps_paused_row(self, client, post_alexa, sample_track, isolated_paths):
        """Pause is explicit — row stays visible as paused."""
        did = 'amzn1.ask.device.PAUSEDROW'
        _register(client, post_alexa, did, 'Garage')
        _seed_playing(post_alexa, sample_track, did)
        post_alexa('IntentRequest', 'AMAZON.PauseIntent', device_id=did)

        items = client.get('/api/nowplaying_devices').get_json()['items']
        row = next(i for i in items if i['deviceId'] == did)
        assert row['paused'] is True

    def test_stale_paused_row_expires(self, client, isolated_paths, monkeypatch):
        """Regression: paused rows with no resume lingered forever."""
        did = 'amzn1.ask.device.STALEPAUSE'
        server.register_device(did, default_name='Old Room')
        stale_ts = time.time() - server._NP_PAUSED_STALE_SECONDS - 10
        server.write_np_state_for_device(did, {
            'track': 'Good',
            'artist': 'Better Than Ezra',
            'playing': False,
            'paused': True,
            'timestamp': stale_ts,
            'token': 'q1:0',
        })

        client.get('/api/nowplaying_devices')
        assert client.get('/api/nowplaying_devices').get_json()['items'] == []

    def test_stop_after_blocks_skip(self, client, post_alexa, sample_track, isolated_paths):
        """Stop-after-N must apply to SkipIntent, not only natural track boundaries."""
        did = 'amzn1.ask.device.STOPAFTERSKIP'
        _register(client, post_alexa, did, 'Kitchen')
        tracks = [sample_track['path'], sample_track['path']]
        token = server.encode_token({'tracks': tracks, 'idx': 0, 'stopAfterIdx': 0})
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        post_alexa('IntentRequest', 'SkipIntent', device_id=did)
        assert server.read_np_state_for_device(did) is None

    def test_skip_at_last_track_does_not_wrap(self, client, post_alexa, sample_track, isolated_paths):
        """Non-looping queues must stop at the end, not restart at track 0."""
        did = 'amzn1.ask.device.SKIPEND'
        _register(client, post_alexa, did, 'Office')
        tracks = [sample_track['path'], sample_track['path']]
        token = server.encode_token({'tracks': tracks, 'idx': 1})
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        post_alexa('IntentRequest', 'SkipIntent', device_id=did)
        assert server.read_np_state_for_device(did) is None

    def test_stop_after_last_track_skip_does_not_wrap(self, client, post_alexa, sample_track, isolated_paths):
        """Stop-after-N on the last track must not modulo-wrap to track 0."""
        did = 'amzn1.ask.device.STOPWRAP'
        _register(client, post_alexa, did, 'Bedroom')
        tracks = [sample_track['path'], sample_track['path'], sample_track['path']]
        token = server.encode_token({'tracks': tracks, 'idx': 2, 'stopAfterIdx': 2})
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        post_alexa('IntentRequest', 'SkipIntent', device_id=did)
        assert server.read_np_state_for_device(did) is None


# ─────────────────────────── device correlation ──────────────────────────────

class TestPlayIntentCorrelation:
    """Web UI play → PlaybackStarted should auto-name rotated deviceIds."""

    def test_single_play_intent_names_device(self, isolated_paths):
        did = 'amzn1.ask.device.NEWROTATED01'
        server.register_device(did)  # auto-name Echo ...01
        server._record_play_intent([('SERIAL-KITCHEN', 'Kitchen Show')])
        assert server._correlate_play_intent(did) is True
        store = server._load_devices()
        assert store[did]['name'] == 'Kitchen Show'
        assert store[did]['serial'] == 'SERIAL-KITCHEN'

    def test_group_play_suppresses_correlation(self, isolated_paths):
        did = 'amzn1.ask.device.NEWROTATED02'
        server.register_device(did)
        server._record_play_intent([
            ('SERIAL-A', 'Kitchen Show'),
            ('SERIAL-B', 'Office Show'),
        ])
        assert server._correlate_play_intent(did) is False

    def test_serial_not_adopted_as_display_name(self, isolated_paths):
        """Regression: _expand_play_targets once passed serial as the room name."""
        serial = 'G8M0XG11147503LB'
        did = 'amzn1.ask.device.NEWROTATED03'
        server.register_device(did)
        server._record_play_intent([(serial, serial)])
        assert server._correlate_play_intent(did) is False
        assert server._load_devices()[did]['name'].startswith('Echo ')

    def test_already_named_device_not_relabeled(self, isolated_paths):
        did = 'amzn1.ask.device.NAMEDALREADY'
        server.register_device(did, default_name='Guest bathroom')
        server._record_play_intent([('SERIAL-X', 'Kitchen Show')])
        assert server._correlate_play_intent(did) is False
        assert server._load_devices()[did]['name'] == 'Guest bathroom'

    def test_already_named_device_records_serial(self, isolated_paths):
        """Even when we don't relabel, capture the serial for future rotations."""
        did = 'amzn1.ask.device.NAMEDSERIAL'
        server.register_device(did, default_name='Office')
        server._record_play_intent([('SERIAL-OFFICE', 'Office')])
        server._correlate_play_intent(did)
        assert server._load_devices()[did]['serial'] == 'SERIAL-OFFICE'

    def test_fifo_correlates_back_to_back_plays(self, isolated_paths):
        """Two quick plays on different rooms each bind their own deviceId."""
        kitchen = 'amzn1.ask.device.KITCHENNEW01'
        office = 'amzn1.ask.device.OFFICENEW01'
        server.register_device(kitchen)
        server.register_device(office)
        server._record_play_intent([('SERIAL-K', 'Kitchen Show')])
        server._record_play_intent([('SERIAL-O', 'Office show')])
        assert server._correlate_play_intent(kitchen) is True
        store = server._load_devices()
        assert store[kitchen]['name'] == 'Kitchen Show'
        assert store[kitchen]['serial'] == 'SERIAL-K'
        assert server._correlate_play_intent(office) is True
        store = server._load_devices()
        assert store[office]['name'] == 'Office show'
        assert store[office]['serial'] == 'SERIAL-O'

    def test_auto_merge_skipped_while_play_intent_pending(self, isolated_paths):
        """Echo Shows share fingerprints — don't fold a new room onto the active one."""
        office = 'amzn1.ask.device.OFFICEPRIMARY'
        kitchen_new = 'amzn1.ask.device.KITCHENROT01'
        server.register_device(office, default_name='Office show')
        store = server._load_devices()
        store[office]['fingerprint'] = 'AudioPlayer,Display'
        store[office]['lastSeen'] = time.time()
        server._save_devices(store)
        server._record_play_intent([('SERIAL-KITCHEN', 'Kitchen Show')])
        server.register_device(
            kitchen_new,
            supported_interfaces={'AudioPlayer': {}, 'Display': {}},
        )
        assert server._load_devices()[kitchen_new].get('aliasOf') is None

    def test_correlate_peels_wrong_auto_merge(self, isolated_paths):
        office = 'amzn1.ask.device.OFFICEPRIMARY'
        kitchen_new = 'amzn1.ask.device.KITCHENROT02'
        server.register_device(office, default_name='Office show')
        store = server._load_devices()
        server._alias_to(kitchen_new, office, store)
        server._save_devices(store)
        server._record_play_intent([('SERIAL-KITCHEN', 'Kitchen Show')])
        assert server._correlate_play_intent(kitchen_new) is True
        store = server._load_devices()
        assert store[kitchen_new].get('aliasOf') is None
        assert store[kitchen_new]['name'] == 'Kitchen Show'
        assert store[kitchen_new]['serial'] == 'SERIAL-KITCHEN'


class TestSerialIndexedAliasing:
    """A rotated deviceId reporting a known hardware serial folds onto its room."""

    def test_serial_first_aliases_rotated_id(self, isolated_paths):
        old = 'amzn1.ask.device.KITCHENORIG'
        server.register_device(old, default_name='Kitchen Show')
        # bind the serial to the original room
        server._record_play_intent([('SERIAL-K', 'Kitchen Show')])
        server._correlate_play_intent(old)
        assert server._load_devices()[old]['serial'] == 'SERIAL-K'

        # Alexa rotates the id; a later play for the SAME serial uses a NEW name
        new = 'amzn1.ask.device.KITCHENROT'
        server.register_device(new)
        server._record_play_intent([('SERIAL-K', 'Totally Different Label')])
        assert server._correlate_play_intent(new) is True
        store = server._load_devices()
        assert store[new]['aliasOf'] == old           # folded onto original
        assert server._resolve_device_id(new) == old

    def test_primary_by_serial_skips_aliases(self, isolated_paths):
        a = 'amzn1.ask.device.AAAA'
        b = 'amzn1.ask.device.BBBB'
        server.register_device(a, default_name='Den')
        store = server._load_devices()
        store[a]['serial'] = 'S1'
        store[b] = {'aliasOf': a, 'name': 'Den', 'serial': 'S1'}
        server._save_devices(store)
        assert server._primary_by_serial('S1') == a


# ─────────────────────────── collision-safe play verbs ─────────────────────────

class TestCollisionSafePlayText:
    """Remote play must use start/mix, not play/shuffle (Spotify hijack)."""

    def test_playlist_uses_start(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        text = server._build_play_text('playlist', 'yacht rock', shuffle=False)
        assert 'ask bock media to start' in text
        assert ' to play ' not in text

    def test_playlist_shuffle_uses_mix(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        text = server._build_play_text('playlist', 'yacht rock', shuffle=True)
        assert 'ask bock media to mix' in text
        assert 'shuffle' not in text

    def test_song_with_path_uses_file_token(self, isolated_paths, monkeypatch, tmp_path):
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        f = tmp_path / 'dancing.mp3'
        f.write_bytes(b'x')
        text = server._build_play_text('song', 'Dancing Queen', shuffle=False,
                                       artist='ABBA', path=str(f))
        assert 'ask bock media to start file token' in text
        assert 'playlist' not in text

    def test_song_without_path_uses_the_song_phrase(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        text = server._build_play_text('song', 'Dancing Queen', shuffle=False, artist='ABBA')
        assert 'ask bock media to start the song Dancing Queen by ABBA' in text

    def test_album_uses_token_not_fuzzy_phrase(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        text = server._build_play_text('album', 'Rumours', shuffle=False, artist='Fleetwood Mac')
        assert 'ask bock media to start album token' in text
        assert 'the album Rumours' not in text

    def test_playlist_with_id_uses_file_token(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        monkeypatch.setattr(server, '_msp_playlist_by_id', lambda pid: ('Mamma Mia', '/x/mamma.m3u'))
        text = server._build_play_text(
            'playlist', 'Mamma Mia', shuffle=False,
            playlist_id='PL123', playlist_source='/x/mamma.m3u',
        )
        assert 'ask bock media to start file token' in text
        assert 'the Mamma Mia playlist' not in text


class TestPlaylistAlbumCollision:
    """Titles like 'Mamma Mia' can exist as both a playlist and an album."""

    def test_play_album_intent_prefers_album_over_playlist(self, post_alexa, isolated_paths, monkeypatch, sample_track):
        entry = ('PL-MAMMA', 'Mamma Mia', '/tmp/mamma.m3u')
        monkeypatch.setattr(server, 'best_playlist_entry', lambda q: entry if q.strip().lower() == 'mamma mia' else None)
        monkeypatch.setattr(server, '_score_playlist', lambda q, n: 1.0 if q.strip().lower() == n.strip().lower() else 0.0)
        monkeypatch.setattr(server.os.path, 'isfile', lambda p: True)
        monkeypatch.setattr(server, 'fuzzy_find_album', lambda q: 'Mamma Mia!' if q.strip().lower() == 'mamma mia' else None)
        monkeypatch.setattr(
            server,
            '_album_tracks_for_play',
            lambda album, artist=None, shuffle=False, limit=50: [sample_track['path']],
        )
        resp = post_alexa('IntentRequest', 'PlayAlbumIntent', slots={'AlbumName': 'Mamma Mia'})
        from tests.test_alexa import _audio_play, _speech
        assert _audio_play(resp), resp
        assert 'album' in _speech(resp).lower()

    def test_play_album_token_uses_exact_album_and_artist(self, post_alexa, isolated_paths, monkeypatch, sample_track):
        token = server._register_play_album_token('Mamma Mia!', artist='Benny Andersson', shuffle=False)
        paths = []
        monkeypatch.setattr(
            server,
            '_album_tracks_for_play',
            lambda album, artist=None, shuffle=False, limit=50: paths.append((album, artist)) or [sample_track['path']],
        )
        resp = post_alexa('IntentRequest', 'PlayFileTokenIntent', slots={'FileToken': f'album token {token}'})
        from tests.test_alexa import _audio_play
        assert _audio_play(resp), resp
        assert paths == [('Mamma Mia!', 'Benny Andersson')]

    def test_play_playlist_token_from_app(self, post_alexa, isolated_paths, monkeypatch, sample_playlist, sample_track):
        pid = 'PL-APP-TEST'
        m3u = sample_playlist['source']
        monkeypatch.setattr(
            server,
            '_msp_playlist_by_id',
            lambda p: (sample_playlist['name'], m3u) if p == pid else (None, None),
        )
        monkeypatch.setattr(
            server,
            '_playlist_paths_cached',
            lambda playlist_id, source: [sample_track['path']],
        )
        token = server._register_play_playlist_token(
            pid, sample_playlist['name'], m3u, shuffle=False,
        )
        resp = post_alexa(
            'IntentRequest',
            'PlayFileTokenIntent',
            slots={'FileToken': f'playlist token {token}'},
        )
        from tests.test_alexa import _audio_play
        assert _audio_play(resp), resp

    def test_play_rated_playlist_token_starts_audio(self, post_alexa, isolated_paths, monkeypatch, sample_track, tmp_path):
        ratings_path = str(tmp_path / 'ratings.json')
        monkeypatch.setattr(server, 'RATINGS_PATH', ratings_path)
        import bock_ratings
        bock_ratings.set_rating(
            ratings_path, 'song', sample_track['path'], 5, None,
            title='Rated', member_id='andy',
        )
        token = server._register_play_playlist_token(
            'rated-stars-5', '5 stars', '', shuffle=False,
            tracks=[sample_track['path']],
        )
        resp = post_alexa(
            'IntentRequest',
            'PlayFileTokenIntent',
            slots={'FileToken': f'file token {token}'},
        )
        from tests.test_alexa import _audio_play
        assert _audio_play(resp), resp

    def test_build_play_text_rated_playlist_materializes_tracks(self, isolated_paths, monkeypatch, sample_track, tmp_path):
        ratings_path = str(tmp_path / 'ratings.json')
        monkeypatch.setattr(server, 'RATINGS_PATH', ratings_path)
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        monkeypatch.setattr(server, '_ratings_member_from_request', lambda: 'andy')
        import bock_ratings
        bock_ratings.set_rating(
            ratings_path, 'song', sample_track['path'], 4, None,
            title='Starred', member_id='andy',
        )
        text = server._build_play_text(
            'playlist', '4★ songs', shuffle=False,
            playlist_id='rated-stars-4', playlist_source='',
        )
        assert 'ask bock media to start file token' in text
        digits = __import__('re').sub(r'[^0-9]', '', text.split('token', 1)[1])
        entry = server._consume_play_playlist_token(digits)
        assert entry and entry.get('tracks') == [sample_track['path']]


# ─────────────────────────── static cache busting ──────────────────────────────

class TestCacheBusting:
    """Regression: browser cached stale app.js after UI changes."""

    def test_index_no_cache(self, client):
        rv = client.get('/')
        assert rv.headers.get('Cache-Control') == 'no-cache, must-revalidate'

    def test_app_js_no_cache(self, client):
        rv = client.get('/js/app.js')
        assert rv.headers.get('Cache-Control') == 'no-cache, must-revalidate'


# ─────────────────────────── plex two-way sync ─────────────────────────────────

class TestPlexClient:
    def test_ratingkey_from_synced_m3u(self):
        import plex_client
        src = '/mnt/bock/Music/exportedPlaylists/plex/Daily Music.123456.m3u'
        assert plex_client.playlist_ratingkey_from_source(src) == '123456'

    def test_ratingkey_none_for_mymedia_playlist(self):
        import plex_client
        assert plex_client.playlist_ratingkey_from_source('/x/some_playlist.m3u') is None
        assert plex_client.playlist_ratingkey_from_source('') is None

    def test_add_track_falls_back_without_token(self, monkeypatch):
        import plex_client
        monkeypatch.setattr(plex_client, 'token', lambda: None)
        assert plex_client.add_track_to_playlist('123', '/x/a.mp3') is False

    def test_status_unconfigured(self, monkeypatch):
        import plex_client
        monkeypatch.setattr(plex_client, 'token', lambda: None)
        s = plex_client.status()
        assert s['configured'] is False and s['reachable'] is False


class TestAddToPlaylistWriteBack:
    def test_plex_writeback_attempted_for_plex_playlist(self, client, post_alexa, sample_track, isolated_paths, tmp_path, monkeypatch):
        import plex_client
        # A Plex-sourced .m3u (ratingKey-encoded filename)
        m3u = tmp_path / 'My List.987.m3u'
        m3u.write_text('#EXTM3U\n')
        monkeypatch.setattr(server, 'fuzzy_find_playlist', lambda q: ('My List', str(m3u)))
        calls = {}
        monkeypatch.setattr(plex_client, 'add_track_to_playlist',
                            lambda rk, path: calls.setdefault('args', (rk, path)) or True)
        # Set up a now-playing track on the request device.
        did = 'amzn1.ask.device.ADDDEV'
        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        server.write_np_state_for_device(did, {'filepath': sample_track['path'],
                                               'track': 'X', 'token': token, 'playing': True})
        post_alexa('IntentRequest', 'AddToPlaylistIntent',
                   slots={'PlaylistName': 'my list'},
                   device_id=did)
        assert calls.get('args') == ('987', sample_track['path'])
        # Local .m3u also appended (instant reflection)
        assert sample_track['path'] in m3u.read_text()


# ─────────────────────────── ignore / never play again ─────────────────────────

class TestIgnoreIntegration:
    def test_crud_add_list_remove(self, client, isolated_paths):
        client.post('/api/ignored', json={'path': '/music/a.mp3'})
        client.post('/api/ignored', json={'path': '/music/b.mp3'})
        items = client.get('/api/ignored').get_json()['items']
        paths = {it['path'] for it in items}
        assert paths == {'/music/a.mp3', '/music/b.mp3'}
        r = client.delete('/api/ignored', json={'path': '/music/a.mp3'})
        assert r.get_json()['ok'] is True
        items = client.get('/api/ignored').get_json()['items']
        assert {it['path'] for it in items} == {'/music/b.mp3'}

    def test_add_requires_path(self, client, isolated_paths):
        assert client.post('/api/ignored', json={}).status_code == 400

    def test_remove_unknown_is_falsey(self, client, isolated_paths):
        r = client.delete('/api/ignored', json={'path': '/nope.mp3'})
        assert r.get_json()['ok'] is False

    def test_filter_ignored_queue_drops_ignored(self, isolated_paths):
        server.add_ignored('/x/1.mp3')
        out = server._filter_ignored_queue(['/x/1.mp3', '/x/2.mp3', '/x/3.mp3'])
        assert out == ['/x/2.mp3', '/x/3.mp3']

    def test_filter_ignored_queue_all_ignored_falls_back(self, isolated_paths):
        server.add_ignored('/x/only.mp3')
        # Don't fail silently: a fully-ignored queue still plays.
        assert server._filter_ignored_queue(['/x/only.mp3']) == ['/x/only.mp3']

    def test_filter_ignored_queue_no_ignores_noop(self, isolated_paths):
        q = ['/x/1.mp3', '/x/2.mp3']
        assert server._filter_ignored_queue(q) == q


# ─────────────────────────── sleep timer / stop-after-N ────────────────────────

class TestSleepTimer:
    def test_stop_after_n_songs_halts_enqueue(self, post_alexa, sample_tracks, isolated_paths):
        """stop-after-N stops enqueuing once the boundary is crossed."""
        qid = server._store_queue(sample_tracks, shuffle=False, loop=False)
        # currently playing idx 0; stop after 2 songs -> last allowed idx = 1
        server._set_queue_stop(qid, songs=2, current_idx=0)
        # boundary at idx 0 -> next_idx 1 (<= 1): should enqueue
        r1 = post_alexa('AudioPlayer.PlaybackNearlyFinished', token=f'{qid}:0')
        assert r1.get('response', {}).get('directives'), 'should enqueue track 1'
        # boundary at idx 1 -> next_idx 2 (> 1): should stop
        r2 = post_alexa('AudioPlayer.PlaybackNearlyFinished', token=f'{qid}:1')
        assert not r2.get('response', {}).get('directives'), 'should stop after song 2'

    def test_time_sleep_in_past_halts_enqueue(self, post_alexa, sample_tracks, isolated_paths):
        import time as _t
        qid = server._store_queue(sample_tracks, shuffle=False, loop=False)
        with server._QUEUES_LOCK:
            q = server._load_queues(); q[qid]['stopAt'] = _t.time() - 1; server._save_queues(q)
        r = post_alexa('AudioPlayer.PlaybackNearlyFinished', token=f'{qid}:0')
        assert not r.get('response', {}).get('directives')

    def test_future_timer_still_enqueues(self, post_alexa, sample_tracks, isolated_paths):
        import time as _t
        qid = server._store_queue(sample_tracks, shuffle=False, loop=False)
        with server._QUEUES_LOCK:
            q = server._load_queues(); q[qid]['stopAt'] = _t.time() + 3600; server._save_queues(q)
        r = post_alexa('AudioPlayer.PlaybackNearlyFinished', token=f'{qid}:0')
        assert r.get('response', {}).get('directives')

    def test_set_queue_stop_clears(self, isolated_paths, sample_tracks):
        qid = server._store_queue(sample_tracks)
        server._set_queue_stop(qid, minutes=30)
        assert server._load_queues()[qid].get('stopAt')
        server._set_queue_stop(qid)  # clear
        assert 'stopAt' not in server._load_queues()[qid]
        assert 'stopAfterIdx' not in server._load_queues()[qid]

    def test_sleep_endpoint_requires_active_queue(self, client, isolated_paths):
        r = client.post('/api/nowplaying/sleep', json={'deviceId': 'amzn1.ask.device.NOPE', 'minutes': 30})
        assert r.status_code == 409

    def test_sleep_endpoint_arms_on_active_device(self, client, post_alexa, sample_tracks, isolated_paths):
        did = 'amzn1.ask.device.SLEEPDEV'
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id=did)
        qid = server._store_queue(sample_tracks)
        post_alexa('AudioPlayer.PlaybackStarted', token=f'{qid}:0', device_id=did)
        r = client.post('/api/nowplaying/sleep', json={'deviceId': did, 'minutes': 30})
        assert r.status_code == 200
        assert r.get_json()['sleep']['type'] == 'time'


# ─────────────────────────── queue auto-advance ────────────────────────────────

class TestQueueAutoAdvance:
    @staticmethod
    def _fake_tracks(tmp_path, n=3):
        paths = []
        for i in range(n):
            p = tmp_path / f'track{i}.mp3'
            p.write_bytes(b'fake')
            paths.append(str(p))
        return paths

    def test_lazy_queue_nearly_finished_advances(self, post_alexa, isolated_paths, tmp_path, monkeypatch):
        paths = self._fake_tracks(tmp_path, 3)
        monkeypatch.setattr(server, '_playlist_paths_cached', lambda pid, src: paths)
        qid = server._store_queue_lazy('PL-LAZY', '/fake/playlist.m3u', shuffle=False)
        r = post_alexa('AudioPlayer.PlaybackNearlyFinished', token=f'{qid}:0')
        play = next(
            (d for d in r.get('response', {}).get('directives', []) if d.get('type') == 'AudioPlayer.Play'),
            None,
        )
        assert play, r
        assert play['playBehavior'] == 'ENQUEUE'
        assert play['audioItem']['stream']['token'] == f'{qid}:1'

    def test_nearly_finished_keeps_state_on_current_track(self, post_alexa, isolated_paths, tmp_path):
        """ENQUEUE must not advance NP state early — Finished fallback depends on it."""
        did = 'amzn1.ask.device.NFSTATE'
        paths = self._fake_tracks(tmp_path, 3)
        token = server.encode_token({'tracks': paths, 'idx': 0})
        server.register_device(did)
        server.write_np_state_for_device(did, {
            'filepath': paths[0],
            'token': token,
            'playing': True,
        })
        post_alexa('AudioPlayer.PlaybackNearlyFinished', token=token, device_id=did)
        st = server.read_np_state_for_device(did)
        assert st['token'] == token

    def test_finished_after_failed_enqueue_advances(self, post_alexa, isolated_paths, tmp_path):
        """NearlyFinished ENQUEUE + failed playback → Finished still advances."""
        from tests.test_alexa import _audio_play
        did = 'amzn1.ask.device.ENQFAIL'
        paths = self._fake_tracks(tmp_path, 2)
        token = server.encode_token({'tracks': paths, 'idx': 0})
        server.register_device(did)
        server.write_np_state_for_device(did, {
            'filepath': paths[0],
            'token': token,
            'playing': True,
        })
        post_alexa('AudioPlayer.PlaybackNearlyFinished', token=token, device_id=did)
        resp = post_alexa('AudioPlayer.PlaybackFinished', token=token, device_id=did)
        play = _audio_play(resp)
        assert play, resp
        assert play['playBehavior'] == 'REPLACE_ALL'
        assert play['audioItem']['stream']['token'].endswith(':1')

    def test_finished_fallback_advances_queue(self, post_alexa, isolated_paths, tmp_path):
        """When NearlyFinished never fires, Finished should still advance."""
        from tests.test_alexa import _audio_play
        did = 'amzn1.ask.device.FINADV'
        paths = self._fake_tracks(tmp_path, 2)
        token = server.encode_token({'tracks': paths, 'idx': 0})
        server.register_device(did)
        server.write_np_state_for_device(did, {
            'filepath': paths[0],
            'token': token,
            'playing': True,
        })
        resp = post_alexa('AudioPlayer.PlaybackFinished', token=token, device_id=did)
        play = _audio_play(resp)
        assert play, resp
        assert play['playBehavior'] == 'REPLACE_ALL'
        assert play['audioItem']['stream']['token'].endswith(':1')

    def test_advance_watch_fires_remote_next_when_stuck(self, isolated_paths, tmp_path, monkeypatch):
        import time as _time
        paths = self._fake_tracks(tmp_path, 3)
        token = server.encode_token({'tracks': paths, 'idx': 0})
        did = 'amzn1.ask.device.WATCH'
        server.register_device(did)
        store = server._load_devices()
        store[did]['serial'] = 'TESTSERIAL123'
        server._save_devices(store)
        server.write_np_state_for_device(did, {
            'filepath': paths[0],
            'token': token,
            'playing': True,
            'duration_ms': 5000,
            'timestamp': _time.time(),
        })
        calls = []
        monkeypatch.setattr(server, 'read_np_state_for_device', lambda d: {
            'filepath': paths[0], 'token': token, 'playing': True,
        })
        import alexa_remote
        monkeypatch.setattr(alexa_remote, 'device_control', lambda s, a: calls.append((s, a)))
        watch = server._NP_ADVANCE_WATCH[did]
        server._np_advance_watch_fire(watch)
        assert calls == [('TESTSERIAL123', 'next')]

    def test_play_sets_progress_report_without_apl(self, client, sample_artist, isolated_paths):
        body = {
            'version': '1.0',
            'context': {
                'System': {
                    'device': {
                        'deviceId': 'amzn1.ask.device.AUDIOONLY',
                        'supportedInterfaces': {'AudioPlayer': {}},
                    },
                    'application': {'applicationId': server.EXPECTED_SKILL_APP_ID},
                    'user': {'userId': 'amzn1.ask.account.TEST'},
                },
            },
            'request': {
                'type': 'IntentRequest',
                'requestId': 'prog-test',
                'locale': 'en-US',
                'intent': {
                    'name': 'PlayArtistIntent',
                    'confirmationStatus': 'NONE',
                    'slots': {
                        'ArtistName': {'name': 'ArtistName', 'value': sample_artist, 'confirmationStatus': 'NONE'},
                    },
                },
            },
        }
        rv = client.post('/alexa', data=json.dumps(body), content_type='application/json')
        assert rv.status_code == 200
        play = next(
            (d for d in rv.get_json().get('response', {}).get('directives', [])
             if d.get('type') == 'AudioPlayer.Play'),
            None,
        )
        assert play
        assert play['audioItem']['stream'].get('progressReportingIntervalInMilliseconds') == server._NP_PROGRESS_REPORT_MS


# ─────────────────────────── service health endpoint ───────────────────────────

class TestHealthEndpoint:
    def test_missing_state_is_unknown_not_500(self, client, isolated_paths):
        """No watchdog snapshot yet -> unknowns, never a 500."""
        data = client.get('/api/health').get_json()
        assert data['watchdogFresh'] is False
        assert data['tunnel'] is None
        assert data['skillTesting'] == 'unknown'
        assert data['uptimeSeconds'] >= 0

    def test_fresh_state_surfaces_signals(self, client, isolated_paths):
        import time as _t
        with open(server.HEALTH_STATE_PATH, 'w') as f:
            json.dump({'ts': _t.time(), 'backend': True, 'tunnel': False,
                       'backendHttp': True, 'tunnelReachable': False,
                       'publicLatencyMs': 90, 'publicStatus': 403,
                       'alexaAuth': True}, f)
        data = client.get('/api/health').get_json()
        assert data['watchdogFresh'] is True
        assert data['tunnel'] is False
        assert data['tunnelReachable'] is False
        assert data['alexaAuth'] is True

    def test_stale_state_marked_not_fresh(self, client, isolated_paths):
        import time as _t
        with open(server.HEALTH_STATE_PATH, 'w') as f:
            json.dump({'ts': _t.time() - 9999, 'tunnel': True}, f)
        data = client.get('/api/health').get_json()
        assert data['watchdogFresh'] is False
        assert data['tunnel'] is None  # stale -> not trusted

    def test_last_alexa_hit_tracked(self, client, post_alexa, isolated_paths):
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id='amzn1.ask.device.HITTRACK')
        data = client.get('/api/health').get_json()
        assert data['lastAlexaHit'] is not None
        assert data['lastAlexaHitAgo'] is not None


# ─────────────────────────── silent device discovery ─────────────────────────

class TestSilentDeviceDiscovery:
    def test_silent_correlation_path_under_music_root(self, isolated_paths, monkeypatch, tmp_path):
        music = tmp_path / 'music'
        music.mkdir()
        bundled = os.path.join(os.path.dirname(server.__file__), 'assets', 'silent-correlation.mp3')
        if not os.path.isfile(bundled):
            pytest.skip('silent-correlation.mp3 asset missing')
        monkeypatch.setattr(server, 'MUSIC_ROOT', str(music))
        path = server._ensure_silent_correlation_path()
        assert path.startswith(str(music))
        assert os.path.isfile(path)

    def test_build_silent_correlation_uses_file_token(self, isolated_paths, monkeypatch, tmp_path):
        music = tmp_path / 'music'
        music.mkdir()
        silent = music / '.bock' / 'silent-correlation.mp3'
        silent.parent.mkdir(parents=True)
        silent.write_bytes(b'ID3')
        monkeypatch.setattr(server, 'MUSIC_ROOT', str(music))
        monkeypatch.setattr(server, '_alexa_alias', lambda: 'bock media')
        text = server._build_silent_correlation_text()
        assert 'ask bock media to start file token' in text

    def test_device_needs_discovery_unbound_always(self, isolated_paths):
        assert server._device_needs_discovery('S-NEW', {}, stale_days=7, only_stale=True)

    def test_device_needs_discovery_respects_stale(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_primary_by_serial', lambda s, store=None: 'amzn1.ask.device.X')
        monkeypatch.setattr(server, '_load_discovery_state',
                            lambda: {'serials': {'S1': time.time()}})
        assert not server._device_needs_discovery('S1', {}, stale_days=7, only_stale=True)
        monkeypatch.setattr(server, '_load_discovery_state',
                            lambda: {'serials': {'S1': time.time() - 8 * 86400}})
        assert server._device_needs_discovery('S1', {}, stale_days=7, only_stale=True)

    def test_discover_endpoint_disabled(self, client, isolated_paths, monkeypatch):
        cfg = server.CONFIG_PATH
        os.makedirs(os.path.dirname(cfg), exist_ok=True)
        with open(cfg, 'w') as f:
            json.dump({
                'deviceDiscovery': {'enabled': False},
                'mobileApi': {'allowOpenLanApi': True, 'allowOpenLanMedia': True},
            }, f)
        server._config_mtime = 0.0
        resp = client.post('/api/devices/discover', json={})
        assert resp.status_code == 400
        assert resp.get_json()['code'] == 'disabled'

    def test_discover_force_when_disabled(self, client, isolated_paths, monkeypatch):
        cfg = server.CONFIG_PATH
        os.makedirs(os.path.dirname(cfg), exist_ok=True)
        with open(cfg, 'w') as f:
            json.dump({
                'deviceDiscovery': {'enabled': False},
                'mobileApi': {'allowOpenLanApi': True, 'allowOpenLanMedia': True},
            }, f)
        server._config_mtime = 0.0
        import alexa_remote
        monkeypatch.setattr(alexa_remote, 'list_devices', lambda: [])
        resp = client.post('/api/devices/discover', json={'force': True})
        assert resp.status_code == 200
        assert resp.get_json()['total'] == 0


# ─────────────────────────── identify/test analytics exclusion ─────────────────

class TestTestPlayExclusion:
    def test_test_serial_tags_history_and_excluded(self, client, post_alexa, sample_track, isolated_paths):
        """A play on a serial marked as 'test' is tagged and kept out of analytics + history."""
        did = 'amzn1.ask.device.TESTSWEEP'
        # Register and attach a serial to the device, mark that serial as test.
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id=did)
        store = server._load_devices()
        store[did]['serial'] = 'SERIAL-SWEEP'
        server._save_devices(store)
        server._mark_test_serial('SERIAL-SWEEP')

        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)

        rows = server._read_stream_history()
        assert rows, 'history row should be written'
        assert rows[-1].get('test') is True

        # Excluded from the history list endpoint
        hist = client.get('/api/nowplaying?page=1&limit=10').get_json()
        assert all(not it.get('test') for it in hist['items'])
        assert hist['total'] == 0

    def test_normal_play_not_tagged(self, client, post_alexa, sample_track, isolated_paths):
        did = 'amzn1.ask.device.NORMALPLAY'
        post_alexa('IntentRequest', 'AMAZON.HelpIntent', device_id=did)
        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        rows = server._read_stream_history()
        assert rows and not rows[-1].get('test')


# ─────────────────────────── alexapy auth status ───────────────────────────────

class TestAlexaRemoteStatus:
    def test_status_reports_authenticated(self, client, monkeypatch):
        import alexa_remote
        monkeypatch.setattr(alexa_remote, 'is_configured', lambda: True)
        monkeypatch.setattr(alexa_remote, 'is_authenticated', lambda *a, **k: True)
        data = client.get('/api/alexa_remote/status?probe=1').get_json()
        assert data['configured'] is True
        assert data['authenticated'] is True

    def test_status_reports_expired_session(self, client, monkeypatch):
        import alexa_remote
        monkeypatch.setattr(alexa_remote, 'is_configured', lambda: True)
        monkeypatch.setattr(alexa_remote, 'is_authenticated', lambda *a, **k: False)
        data = client.get('/api/alexa_remote/status?probe=1').get_json()
        assert data['configured'] is True
        assert data['authenticated'] is False

    def test_status_not_configured(self, client, monkeypatch):
        import alexa_remote
        monkeypatch.setattr(alexa_remote, 'is_configured', lambda: False)
        data = client.get('/api/alexa_remote/status').get_json()
        assert data['configured'] is False
        assert data['authenticated'] is None


# ─────────────────────────── skip/back intents ─────────────────────────────────

class TestSkipBackIntents:
    def test_skip_intent_with_active_queue(self, client, post_alexa, sample_track, isolated_paths):
        did = 'amzn1.ask.device.SKIPTEST'
        _register(client, post_alexa, did)
        token = server.encode_token({
            'tracks': [sample_track['path'], sample_track['path']],
            'idx': 0,
        })
        with server.app.test_request_context('/'):
            server.g.device_id = did
            server.g.raw_device_id = did
            server.write_np_state({
                'track': sample_track['title'],
                'artist': sample_track['artist'],
                'filepath': sample_track['path'],
                'token': token,
                'playing': True,
            })
        resp = post_alexa('IntentRequest', 'SkipIntent', device_id=did)
        directives = resp.get('response', {}).get('directives', []) or []
        speech = (resp.get('response', {}).get('outputSpeech', {}) or {}).get('text', '')
        assert directives or speech, 'SkipIntent should advance or speak'

    def test_remote_next_does_not_optimistically_advance_queue(
        self, client, sample_track, isolated_paths, monkeypatch,
    ):
        """Remote skip uses voice → SkipIntent; optimistic idx advance caused double-skip."""
        import alexa_remote
        monkeypatch.setattr(alexa_remote, 'device_control', lambda *a, **k: {'spoken': True})

        did = 'amzn1.ask.device.REMOTENEXT'
        paths = [sample_track['path'], sample_track['path'], sample_track['path']]
        token = server.encode_token({'tracks': paths, 'idx': 0})
        server.register_device(did, default_name='Office Show')
        server.write_np_state_for_device(did, {
            'track': sample_track['title'],
            'artist': sample_track['artist'],
            'filepath': paths[0],
            'token': token,
            'playing': True,
        })

        resp = client.post('/api/alexa_remote/control', json={
            'deviceId': did,
            'serial': 'SERIAL-OFFICE',
            'action': 'next',
        })
        assert resp.status_code == 200

        st = server.read_np_state_for_device(did)
        assert st is not None
        data = server.decode_token(st['token']) or {}
        assert data.get('idx') == 0, 'control must not advance idx before SkipIntent runs'

    def test_skip_intent_eagerly_updates_now_playing(
        self, client, post_alexa, sample_track, isolated_paths,
    ):
        """SkipIntent must update NP metadata immediately, not only on PlaybackStarted."""
        did = 'amzn1.ask.device.SKIPEAGER'
        paths = [sample_track['path'], sample_track['path']]
        _register(client, post_alexa, did)
        token = server.encode_token({'tracks': paths, 'idx': 0})
        with server.app.test_request_context('/'):
            server.g.device_id = did
            server.g.raw_device_id = did
            server.write_np_state({
                'track': 'First',
                'filepath': paths[0],
                'token': token,
                'playing': True,
            })
        post_alexa('IntentRequest', 'SkipIntent', device_id=did)
        st = server.read_np_state_for_device(did)
        assert st is not None
        data = server.decode_token(st['token']) or {}
        assert data.get('idx') == 1
        assert st.get('filepath') == paths[1]


# ─────────────────────────── Office Show NP label ────────────────────────────

class TestOfficeShowNpLabel:
    """Now Playing must show the correct room (live roster > stale devices.json)."""

    def test_device_label_prefers_live_roster_over_stale_name(self, isolated_paths, monkeypatch):
        did = 'amzn1.ask.device.OFFICELABEL'
        server.register_device(did, default_name='Kitchen Show')
        store = server._load_devices()
        store[did]['serial'] = 'SERIAL-OFFICE'
        server._save_devices(store)
        monkeypatch.setattr(
            server, '_alexa_name_for_serial',
            lambda s: 'Office Show' if s == 'SERIAL-OFFICE' else '',
        )
        assert server._device_label(did) == 'Office Show'
        assert server._load_devices()[did]['name'] == 'Office Show'

    def test_office_show_np_row_uses_live_name(
        self, client, post_alexa, isolated_paths, monkeypatch,
    ):
        did = 'amzn1.ask.device.OFFICENP01'
        path = '/music/office/test.mp3'
        server.register_device(did, default_name='Kitchen Show')
        store = server._load_devices()
        store[did]['serial'] = 'SERIAL-OFFICE'
        server._save_devices(store)
        monkeypatch.setattr(
            server, '_alexa_name_for_serial',
            lambda s: 'Office Show' if s == 'SERIAL-OFFICE' else '',
        )
        token = server.encode_token({'tracks': [path], 'idx': 0})
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        items = client.get('/api/nowplaying_devices').get_json()['items']
        row = next(i for i in items if i['deviceId'] == did)
        assert row['deviceName'] == 'Office Show'

    def test_msp_pseudo_labeled_from_play_intent(self, client, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_name_for_serial', lambda s: '')
        server._record_play_intent([('SERIAL-OFFICE', 'Office Show')])
        qid = server._store_queue(['/music/song.mp3'], playlist='Test')
        server._attach_queue_play_target(qid)
        did = f'{server.MSP_DEVICE_ID}:{qid}'
        server.write_np_state_for_device(did, {
            'track': 'Song',
            'playing': True,
            'token': f'{qid}:0',
            'timestamp': time.time(),
        })
        items = client.get('/api/nowplaying_devices').get_json()['items']
        row = next(i for i in items if i['deviceId'] == did)
        assert row['deviceName'] == 'Office Show'

    def test_concurrent_msp_streams_distinct_labels(self, client, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_name_for_serial', lambda s: '')
        server._record_play_intent([('SERIAL-KITCHEN', 'Kitchen Show')])
        q_k = server._store_queue(['/music/k.mp3'])
        server._attach_queue_play_target(q_k)
        server._record_play_intent([('SERIAL-OFFICE', 'Office Show')])
        q_o = server._store_queue(['/music/o.mp3'])
        server._attach_queue_play_target(q_o)
        did_k = f'{server.MSP_DEVICE_ID}:{q_k}'
        did_o = f'{server.MSP_DEVICE_ID}:{q_o}'
        server.write_np_state_for_device(did_k, {
            'track': 'Kitchen track',
            'playing': True,
            'token': f'{q_k}:0',
            'timestamp': time.time(),
        })
        server.write_np_state_for_device(did_o, {
            'track': 'Office track',
            'playing': True,
            'token': f'{q_o}:0',
            'timestamp': time.time() + 1,
        })
        items = client.get('/api/nowplaying_devices').get_json()['items']
        names = {i['deviceId']: i['deviceName'] for i in items}
        assert names[did_k] == 'Kitchen Show'
        assert names[did_o] == 'Office Show'
