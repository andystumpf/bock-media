"""
Regression tests for defects found in production (2026-06).

Each class maps to a real bug: stuck Now Playing rows, device correlation,
collision-safe play verbs, cache-busting, etc.
"""
import json
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
        assert all(i['deviceId'] != did for i in items), 'idle row must not appear in UI'

    def test_playback_stopped_without_prior_state_is_noop(self, client, post_alexa, isolated_paths):
        """Regression: identify/test sweeps created trackless stuck rows."""
        did = 'amzn1.ask.device.GHOST'
        _register(client, post_alexa, did)
        token = server.encode_token({'tracks': ['/no/such.mp3'], 'idx': 0})
        post_alexa('AudioPlayer.PlaybackStopped', token=token, device_id=did)

        assert server.read_np_state_for_device(did) is None
        assert client.get('/api/nowplaying_devices').get_json()['items'] == []

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


# ─────────────────────────── static cache busting ──────────────────────────────

class TestCacheBusting:
    """Regression: browser cached stale app.js after UI changes."""

    def test_index_no_cache(self, client):
        rv = client.get('/')
        assert rv.headers.get('Cache-Control') == 'no-cache, must-revalidate'

    def test_app_js_no_cache(self, client):
        rv = client.get('/js/app.js')
        assert rv.headers.get('Cache-Control') == 'no-cache, must-revalidate'


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
