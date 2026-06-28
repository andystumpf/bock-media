"""Music video lookup API."""
import json
from unittest.mock import patch

import server


def test_music_video_requires_title(client):
    rv = client.get('/api/music-video')
    assert rv.status_code == 400


def test_music_video_cached(client, tmp_path, monkeypatch):
    cache = tmp_path / 'music_video_cache.json'
    cache.write_text(
        json.dumps({'v4|staind|outside': {'videoId': 'abc123', 'title': 'Staind - Outside'}}),
        encoding='utf-8',
    )
    monkeypatch.setattr(server, 'MUSIC_VIDEO_CACHE_PATH', str(cache))
    rv = client.get('/api/music-video', query_string={'title': 'Outside', 'artist': 'Staind'})
    assert rv.status_code == 200
    assert rv.get_json()['videoId'] == 'abc123'


def test_music_video_piped_search(client, tmp_path, monkeypatch):
    monkeypatch.setattr(server, 'MUSIC_VIDEO_CACHE_PATH', str(tmp_path / 'mv.json'))
    monkeypatch.setattr(server, '_music_video_from_ytdlp', lambda a, t, d=None: (None, None))
    monkeypatch.setattr(server, '_music_video_from_youtube_api', lambda a, t, d=None: (None, None))
    monkeypatch.setattr(server, '_music_video_from_override', lambda a, t, d=None: (None, None))

    class FakeResp:
        def read(self):
            return json.dumps({
                'items': [{
                    'type': 'stream',
                    'url': '/watch?v=xyz789abcde',
                    'title': 'Staind - Outside (Official Music Video)',
                }],
            }).encode()

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

    with patch('urllib.request.urlopen', return_value=FakeResp()):
        rv = client.get('/api/music-video', query_string={'title': 'Outside', 'artist': 'Staind'})
    assert rv.status_code == 200
    assert rv.get_json()['videoId'] == 'xyz789abcde'


def test_music_video_ytdlp_search(client, tmp_path, monkeypatch):
    monkeypatch.setattr(server, 'MUSIC_VIDEO_CACHE_PATH', str(tmp_path / 'mv.json'))
    monkeypatch.setattr(server, '_music_video_from_override', lambda a, t, d=None: (None, None))
    monkeypatch.setattr(server, '_music_video_from_youtube_api', lambda a, t, d=None: (None, None))
    monkeypatch.setattr(server, '_music_video_from_piped', lambda a, t, d=None: (None, None))
    monkeypatch.setattr(
        server, '_music_video_from_ytdlp',
        lambda a, t, d=None: ('abc123xyz01', 'Artist - Song (Official Music Video)'),
    )
    rv = client.get('/api/music-video', query_string={'title': '1979', 'artist': 'Smashing Pumpkins'})
    assert rv.status_code == 200
    assert rv.get_json()['videoId'] == 'abc123xyz01'


def test_music_video_score_prefers_official_mv():
    s_official = server._music_video_score(
        'Matchbox Twenty', '3AM', 'Matchbox Twenty - 3AM (Official Music Video)', 240,
    )
    s_audio = server._music_video_score(
        'Matchbox Twenty', '3AM', 'Matchbox Twenty - 3AM (Official Audio)', 240,
    )
    s_lyrics = server._music_video_score(
        'Matchbox Twenty', '3AM', 'Matchbox Twenty - 3AM (Lyric Video)', 240,
    )
    assert s_official > s_audio
    assert s_official > s_lyrics


def test_music_video_pick_best():
    candidates = [
        ('aaaa1111111', 'Artist - Song (Official Audio)', 200),
        ('bbbb2222222', 'Artist - Song (Official Music Video)', 220),
        ('cccc3333333', 'Artist - Song (Lyric Video)', 210),
    ]
    vid, title = server._music_video_pick_best('Artist', 'Song', candidates)
    assert vid == 'bbbb2222222'
    assert 'Official Music Video' in (title or '')


def test_music_video_pick_best_title_match():
    candidates = [
        ('clKAdQnwJ7A', "Matchbox Twenty - If You're Gone (Official Video)", 276, 'Matchbox Twenty'),
        ('xn9NE2gQCnU', 'Duncan Sheik - Barely Breathing (Official Video)', 250, 'Duncan Sheik'),
    ]
    vid, title = server._music_video_pick_best('Matchbox Twenty', 'Barely Breathing', candidates)
    assert vid == 'xn9NE2gQCnU'


def test_music_video_pick_best_same_artist_wrong_song():
    candidates = [
        ('bbbb2222222', 'Smashing Pumpkins - Tonight, Tonight (Official Music Video)', 260, 'SmashingPumpkinsVEVO'),
        ('aaaa1111111', 'Smashing Pumpkins - 1979 (Official Music Video)', 265, 'SmashingPumpkinsVEVO'),
    ]
    vid, _ = server._music_video_pick_best('Smashing Pumpkins', '1979', candidates, track_duration_sec=266)
    assert vid == 'aaaa1111111'


def test_music_video_duration_prefers_closer_match():
    candidates = [
        ('aaaa1111111', 'Artist - Song (Official Music Video)', 180, 'ArtistVEVO'),
        ('bbbb2222222', 'Artist - Song (Official Music Video)', 240, 'ArtistVEVO'),
    ]
    vid, _ = server._music_video_pick_best('Artist', 'Song', candidates, track_duration_sec=238)
    assert vid == 'bbbb2222222'


def test_music_video_rejects_live_without_title_match():
    candidates = [
        ('aaaa1111111', 'Artist - Other Song (Live at Wembley)', 3600, 'ArtistVEVO'),
    ]
    vid, _ = server._music_video_pick_best('Artist', 'Song', candidates)
    assert vid is None


def test_music_video_play_requires_cookies(client, monkeypatch):
    monkeypatch.setattr(server, '_music_video_cookies_path', lambda: None)
    rv = client.get('/api/music-video/4aeETEoNfOg/play')
    assert rv.status_code == 503
    body = rv.get_json()
    assert body['ready'] is False
    assert 'cookies' in (body.get('reason') or '').lower()


def test_music_video_play_direct(client, monkeypatch):
    monkeypatch.setattr(server, '_music_video_cookies_path', lambda: '/tmp/cookies.txt')
    monkeypatch.setattr(server.shutil, 'which', lambda x: '/usr/bin/yt-dlp' if x == 'yt-dlp' else None)
    monkeypatch.setattr(server, '_music_video_can_stream', lambda vid: True)
    rv = client.get('/api/music-video/4aeETEoNfOg/play')
    assert rv.status_code == 200
    body = rv.get_json()
    assert body['ready'] is True
    assert body['proxied'] is True
    assert body['playUrl'].endswith('/proxy')
