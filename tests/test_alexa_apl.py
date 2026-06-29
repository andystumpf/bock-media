"""Echo Show APL lyrics tests."""
import json

import alexa_apl
import server


class TestAlexaAplHelpers:
    def test_active_lyric_index(self):
        lines = [
            {'timeMs': 0, 'text': 'a'},
            {'timeMs': 5000, 'text': 'b'},
            {'timeMs': 10000, 'text': 'c'},
        ]
        assert alexa_apl.active_lyric_index(lines, 0) == 0
        assert alexa_apl.active_lyric_index(lines, 4999) == 0
        assert alexa_apl.active_lyric_index(lines, 5000) == 1
        assert alexa_apl.active_lyric_index(lines, 99999) == 2

    def test_device_supports_apl_from_interfaces(self):
        assert alexa_apl.device_supports_apl({'Alexa.Presentation.APL': {}, 'AudioPlayer': {}})
        assert not alexa_apl.device_supports_apl({'AudioPlayer': {}})

    def test_build_document_caps_lines(self):
        lines = [{'timeMs': i * 1000, 'text': f'line {i}'} for i in range(100)]
        doc = alexa_apl.build_lyrics_apl_document('Title', 'Artist', lines, 5)
        scroll = doc['items'][0]['items'][2]['item']['items']
        assert len(scroll) == alexa_apl.MAX_APL_LINES


class TestAlexaAplPlay:
    def _play_artist(self, client, monkeypatch, isolated_paths, sample_artist, apl=False):
        cfg = isolated_paths / 'state' / 'config.json'
        cfg.write_text(json.dumps({'alexaAplLyrics': {'enabled': True}}))
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'syncedLyrics': '[00:00.00]Line one\n[00:05.00]Line two',
            'plainLyrics': 'Line one\nLine two',
        })
        body = {
            'version': '1.0',
            'context': {
                'System': {
                    'device': {
                        'deviceId': 'amzn1.ask.device.SHOW1',
                        'supportedInterfaces': {
                            'AudioPlayer': {},
                            **({'Alexa.Presentation.APL': {}} if apl else {}),
                        },
                    },
                    'application': {'applicationId': server.EXPECTED_SKILL_APP_ID},
                    'user': {'userId': 'amzn1.ask.account.TEST'},
                },
                'AudioPlayer': {'playerActivity': 'IDLE'},
            },
            'request': {
                'type': 'IntentRequest',
                'requestId': 'test-request',
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
        return rv.get_json()

    def test_apl_disabled_no_render_document(self, client, monkeypatch, isolated_paths, sample_artist):
        cfg = isolated_paths / 'state' / 'config.json'
        cfg.write_text(json.dumps({'alexaAplLyrics': {'enabled': False}}))
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'syncedLyrics': '[00:00.00]Line one\n[00:05.00]Line two',
            'plainLyrics': 'Line one\nLine two',
        })
        resp = self._play_artist(client, monkeypatch, isolated_paths, sample_artist, apl=True)
        types = [d.get('type') for d in resp.get('response', {}).get('directives', [])]
        assert 'Alexa.Presentation.APL.RenderDocument' not in types

    def test_apl_enabled_includes_render_document(self, client, monkeypatch, isolated_paths, sample_artist):
        resp = self._play_artist(client, monkeypatch, isolated_paths, sample_artist, apl=True)
        directives = resp.get('response', {}).get('directives', [])
        types = [d.get('type') for d in directives]
        assert 'Alexa.Presentation.APL.RenderDocument' in types
        assert 'AudioPlayer.Play' in types
        play = next(d for d in directives if d.get('type') == 'AudioPlayer.Play')
        stream = play['audioItem']['stream']
        assert stream.get('progressReportingIntervalInMilliseconds') == 1000

    def test_progress_report_returns_apl(self, client, isolated_paths, monkeypatch, sample_artist):
        cfg = isolated_paths / 'state' / 'config.json'
        cfg.write_text(json.dumps({'alexaAplLyrics': {'enabled': True}}))
        monkeypatch.setattr(server, '_fetch_lrclib', lambda *a, **k: {
            'syncedLyrics': '[00:00.00]Hello\n[00:05.00]World',
            'plainLyrics': 'Hello\nWorld',
        })
        play_resp = self._play_artist(client, monkeypatch, isolated_paths, sample_artist, apl=True)
        play = next(d for d in play_resp['response']['directives'] if d['type'] == 'AudioPlayer.Play')
        token = play['audioItem']['stream']['token']
        body = {
            'version': '1.0',
            'context': {
                'System': {
                    'device': {
                        'deviceId': 'amzn1.ask.device.SHOW1',
                        'supportedInterfaces': {'AudioPlayer': {}, 'Alexa.Presentation.APL': {}},
                    },
                    'application': {'applicationId': server.EXPECTED_SKILL_APP_ID},
                },
            },
            'request': {
                'type': 'AudioPlayer.PlaybackProgressReport',
                'requestId': 'prog-1',
                'token': token,
                'offsetInMilliseconds': 6000,
            },
        }
        rv = client.post('/alexa', data=json.dumps(body), content_type='application/json')
        assert rv.status_code == 200
        data = rv.get_json()
        dirs = data.get('response', {}).get('directives', [])
        assert any(d.get('type') == 'Alexa.Presentation.APL.RenderDocument' for d in dirs)
