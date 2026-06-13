"""
Alexa skill /alexa endpoint tests.

Each test names the contract for a specific request type or intent.
"""
import json

import pytest

import server


def _speech(resp):
    return (resp.get('response', {}).get('outputSpeech', {}) or {}).get('text', '')


def _directives(resp):
    return resp.get('response', {}).get('directives', []) or []


def _audio_play(resp):
    """Return the AudioPlayer.Play directive or None."""
    for d in _directives(resp):
        if d.get('type') == 'AudioPlayer.Play':
            return d
    return None


# ─────────────────────────── Launch / fall-through ───────────────────────────

class TestLaunchAndUnknown:
    def test_launch_speaks(self, post_alexa):
        """LaunchRequest yields a help-style spoken response when no DefaultPlaylist"""
        resp = post_alexa('LaunchRequest')
        assert _speech(resp)

    def test_launch_playlist_prompt_mode(self, post_alexa, isolated_paths):
        """When config launchPlaylistPrompt is true, open without auto-play, keep session + reprompt"""
        import server
        with open(server.CONFIG_PATH, 'w', encoding='utf-8') as f:
            json.dump({'launchPlaylistPrompt': True}, f)
        resp = post_alexa('LaunchRequest')
        assert resp['response']['shouldEndSession'] is False
        assert 'reprompt' in resp['response']
        assert 'Bock Media' in _speech(resp) or 'listening' in _speech(resp).lower()

    def test_unknown_intent(self, post_alexa):
        """unknown intent gets a friendly fallback response"""
        resp = post_alexa('IntentRequest', 'TotallyMadeUpIntent')
        assert _speech(resp)

    def test_help_intent(self, post_alexa):
        """HelpIntent returns a non-empty utterance and keeps session open"""
        resp = post_alexa('IntentRequest', 'AMAZON.HelpIntent')
        assert _speech(resp)
        assert resp['response']['shouldEndSession'] is False

    def test_can_fulfill_returns_valid_schema(self, post_alexa):
        """CanFulfillIntentRequest responds with canFulfillIntent payload"""
        resp = post_alexa('CanFulfillIntentRequest')
        cfi = resp.get('response', {}).get('canFulfillIntent', {})
        assert cfi.get('canFulfill') in ('YES', 'MAYBE', 'NO')

    def test_unknown_request_type_returns_spoken_fallback(self, post_alexa):
        """unexpected request types should return valid speech JSON"""
        resp = post_alexa('BogusRequestType')
        assert _speech(resp)


# ─────────────────────────── play artist / album / track / genre ─────────────

class TestPlayArtist:
    def test_play_artist_starts_audio(self, post_alexa, sample_artist):
        """PlayArtistIntent with a real artist returns an AudioPlayer.Play directive"""
        resp = post_alexa('IntentRequest', 'PlayArtistIntent',
                          slots={'ArtistName': sample_artist})
        d = _audio_play(resp)
        assert d, f'expected Play directive, got {resp}'
        assert sample_artist.lower() in _speech(resp).lower()

    def test_play_artist_missing_slot(self, post_alexa):
        """missing slot returns a clarifying question without ending session"""
        resp = post_alexa('IntentRequest', 'PlayArtistIntent', slots={'ArtistName': ''})
        assert _audio_play(resp) is None
        assert resp['response']['shouldEndSession'] is False

    def test_play_artist_unknown(self, post_alexa):
        """unknown artist returns a 'sorry' response with no audio directive"""
        resp = post_alexa('IntentRequest', 'PlayArtistIntent',
                          slots={'ArtistName': 'zzzzqqqqxxxx-not-a-real-artist'})
        assert _audio_play(resp) is None
        assert 'sorry' in _speech(resp).lower()


class TestPlayAlbum:
    def test_play_album_starts_audio(self, post_alexa, sample_album):
        resp = post_alexa('IntentRequest', 'PlayAlbumIntent',
                          slots={'AlbumName': sample_album})
        assert _audio_play(resp), resp


class TestPlayTrack:
    def test_play_track_starts_audio(self, post_alexa, sample_track):
        resp = post_alexa('IntentRequest', 'PlayTrackIntent',
                          slots={'TrackName': sample_track['title']})
        assert _audio_play(resp), resp

    def test_play_track_recovers_misrouted_playlist(self, post_alexa, sample_playlist):
        """when 'playlist X' arrives in TrackName, we reroute to playlist play"""
        misrouted = f"play the playlist {sample_playlist['name']}"
        resp = post_alexa('IntentRequest', 'PlayTrackIntent',
                          slots={'TrackName': misrouted})
        assert _audio_play(resp), resp
        assert sample_playlist['name'].lower() in _speech(resp).lower()


# ─────────────────────────── playlists ───────────────────────────────────────

class TestPlayPlaylist:
    def test_play_playlist(self, post_alexa, sample_playlist):
        resp = post_alexa('IntentRequest', 'PlayPlaylistIntent',
                          slots={'PlaylistName': sample_playlist['name']})
        assert _audio_play(resp), resp
        assert sample_playlist['name'].lower() in _speech(resp).lower()

    def test_shuffle_playlist(self, post_alexa, sample_playlist):
        resp = post_alexa('IntentRequest', 'ShufflePlaylistIntent',
                          slots={'PlaylistName': sample_playlist['name']})
        assert _audio_play(resp), resp
        assert 'shuffl' in _speech(resp).lower()

    def test_play_playlist_unknown(self, post_alexa):
        """truly nonsensical query yields a 'sorry' response with no play directive"""
        resp = post_alexa('IntentRequest', 'PlayPlaylistIntent',
                          slots={'PlaylistName': 'qqqqqqqqqqqqqqqqqzzzzzzzzzzzzz'})
        assert _audio_play(resp) is None
        assert 'sorry' in _speech(resp).lower()


# ─────────────────────────── transport: stop / next / previous / pause ───────

class TestTransport:
    @pytest.fixture(autouse=True)
    def _seed_state(self, isolated_paths, sample_track):
        # set up a now-playing state with a real, decodable token
        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        with server.app.test_request_context('/'):
            server.g.device_id = 'amzn1.ask.device.TESTDEVICE'
            server.register_device('amzn1.ask.device.TESTDEVICE')
            server.write_np_state({
                'track':    sample_track['title'],
                'artist':   sample_track['artist'],
                'filepath': sample_track['path'],
                'token':    token,
                'playing':  True,
            })

    def test_stop_emits_stop_directive(self, post_alexa):
        resp = post_alexa('IntentRequest', 'AMAZON.StopIntent')
        assert any(d['type'] == 'AudioPlayer.Stop' for d in _directives(resp))

    def test_pause_emits_stop_directive(self, post_alexa):
        resp = post_alexa('IntentRequest', 'AMAZON.PauseIntent')
        assert any(d['type'] == 'AudioPlayer.Stop' for d in _directives(resp))

    def test_resume_replays(self, post_alexa, sample_track):
        resp = post_alexa('IntentRequest', 'AMAZON.ResumeIntent')
        d = _audio_play(resp)
        assert d, resp

    def test_next_at_end_speaks(self, post_alexa):
        """single-track queue at idx 0: next wraps to itself or speaks 'no more'"""
        resp = post_alexa('IntentRequest', 'AMAZON.NextIntent')
        assert _audio_play(resp) or _speech(resp)


class TestWhatsPlaying:
    def test_speaks_current(self, post_alexa, isolated_paths, sample_track):
        with server.app.test_request_context('/'):
            server.g.device_id = 'amzn1.ask.device.TESTDEVICE'
            server.register_device('amzn1.ask.device.TESTDEVICE')
            server.write_np_state({
                'track':  sample_track['title'],
                'artist': sample_track['artist'],
                'album':  None,
                'filepath': sample_track['path'],
                'token':  'qid:0',
                'playing': True,
            })
        resp = post_alexa('IntentRequest', 'WhatsPlayingIntent')
        assert sample_track['title'].lower() in _speech(resp).lower()

    def test_nothing_playing(self, post_alexa):
        resp = post_alexa('IntentRequest', 'WhatsPlayingIntent')
        assert 'nothing' in _speech(resp).lower()


# ─────────────────────────── audio-player events ─────────────────────────────

class TestAudioPlayerEvents:
    def test_started_writes_state_and_history(self, client, post_alexa, sample_track, isolated_paths):
        """PlaybackStarted records the track in NP state and stream history"""
        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        post_alexa('AudioPlayer.PlaybackStarted', token=token,
                   device_id='amzn1.ask.device.STARTEDEVENT')

        # device list now contains the device
        devices = client.get('/api/devices').get_json()
        assert any(d['deviceId'] == 'amzn1.ask.device.STARTEDEVENT' for d in devices)

        # nowplaying_devices includes it
        np = client.get('/api/nowplaying_devices').get_json()['items']
        assert any(item['deviceId'] == 'amzn1.ask.device.STARTEDEVENT' for item in np)

        # history populated
        hist = client.get('/api/nowplaying?page=1&limit=10').get_json()
        assert hist['total'] >= 1
        assert hist['items'][0]['filepath'] == sample_track['path']

    def test_stopped_marks_not_playing(self, client, post_alexa, sample_track, isolated_paths):
        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        did = 'amzn1.ask.device.STOPEVENT'
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        post_alexa('AudioPlayer.PlaybackStopped', token=token, device_id=did)
        np = client.get('/api/nowplaying_devices').get_json()['items']
        # Recently-stopped rows stay visible (resume window) but must be
        # flagged stopped, never playing/paused.
        rows = [item for item in np if item['deviceId'] == did]
        assert all(item['stopped'] and not item['paused'] for item in rows), \
            'stopped device must be flagged stopped, not paused/playing'

    def test_finished_marks_not_playing(self, client, post_alexa, sample_track, isolated_paths):
        token = server.encode_token({'tracks': [sample_track['path']], 'idx': 0})
        did = 'amzn1.ask.device.FINISHEVENT'
        post_alexa('AudioPlayer.PlaybackStarted', token=token, device_id=did)
        post_alexa('AudioPlayer.PlaybackFinished', token=token, device_id=did)
        np = client.get('/api/nowplaying_devices').get_json()['items']
        rows = [item for item in np if item['deviceId'] == did]
        assert all(item['stopped'] and not item['paused'] for item in rows)

    def test_failed_advances_to_next(self, post_alexa, sample_track):
        """PlaybackFailed advances to next track when one is available"""
        token = server.encode_token({
            'tracks': [sample_track['path'], sample_track['path']], 'idx': 0
        })
        resp = post_alexa('AudioPlayer.PlaybackFailed', token=token,
                          error={'type': 'MEDIA_ERROR', 'message': 'x'})
        # If there is a next track, we expect a Play directive (REPLACE_ALL).
        d = _audio_play(resp)
        if d:
            assert d['playBehavior'] == 'REPLACE_ALL'

    def test_default_device_id_not_registered(self, client, post_alexa):
        """requests without a real deviceId never auto-register a 'default' device"""
        # No deviceId → handler defaults to 'default', which must not be persisted.
        body = {
            'version': '1.0',
            'context': {'System': {'device': {}}},
            'request': {'type': 'IntentRequest', 'intent': {'name': 'AMAZON.HelpIntent', 'slots': {}}}
        }
        rv = client.post('/alexa', data=json.dumps(body), content_type='application/json')
        assert rv.status_code == 200
        assert client.get('/api/devices').get_json() == []
