"""Tests for Mix Muse local curation."""
import bock_mix_muse
import bock_mix_muse_local


def test_local_pick_from_keywords():
    candidates = [
        {'path': '/a.mp3', 'title': 'Blue Monday', 'artist': 'New Order', 'album': 'Singles', 'genre': 'new wave', 'year': '1983'},
        {'path': '/b.mp3', 'title': 'Random Rock', 'artist': 'Other Band', 'album': 'Vol 1', 'genre': 'rock', 'year': '2010'},
        {'path': '/c.mp3', 'title': 'Temptation', 'artist': 'New Order', 'album': 'Singles', 'genre': 'new wave', 'year': '1987'},
    ]
    name, paths = bock_mix_muse_local.pick_tracks_local('new wave 80s', candidates, 5)
    assert 'Mix Muse' in name
    assert '/a.mp3' in paths
    assert '/c.mp3' in paths
    assert '/b.mp3' not in paths


def test_local_resonance_seed_scoring():
    seed = {'path': '/seed.mp3', 'title': 'Seed', 'artist': 'Artist A', 'album': 'Al', 'genre': 'jazz', 'year': '1975', 'duration_seconds': 240}
    candidates = [
        seed,
        {'path': '/j1.mp3', 'title': 'Jazz One', 'artist': 'Artist B', 'album': 'X', 'genre': 'jazz', 'year': '1976', 'duration_seconds': 250},
        {'path': '/r1.mp3', 'title': 'Rock One', 'artist': 'Artist C', 'album': 'Y', 'genre': 'rock', 'year': '1990', 'duration_seconds': 200},
    ]
    name, paths = bock_mix_muse_local.pick_tracks_local('smooth evening jazz', candidates, 3, seed_row=seed)
    assert paths[0] == '/j1.mp3'


def test_curate_playlist_local_mode():
    def load_config():
        return {'mixMuse': {'provider': 'local'}}
    candidates = [
        {'path': '/x.mp3', 'title': 'Ambient Drift', 'artist': 'Stars', 'album': 'Sky', 'genre': 'ambient', 'year': '2020'},
        {'path': '/y.mp3', 'title': 'Heavy Metal', 'artist': 'Loud', 'album': 'Crush', 'genre': 'metal', 'year': '2020'},
    ]
    name, paths, mode = bock_mix_muse.curate_playlist('ambient drift', candidates, 5, load_config)
    assert mode == 'local'
    assert paths == ['/x.mp3']


def test_status_supports_local():
    def load_config():
        return {}
    st = bock_mix_muse.status(load_config)
    assert st['supportsLocal'] is True
    assert st['configured'] is True
    assert st['mode'] == 'local'


def test_curate_falls_back_when_llm_fails(monkeypatch):
    def load_config():
        return {'claude': {'apiKey': 'test-key'}}

    def boom(*_a, **_k):
        raise OSError('network fail')

    monkeypatch.setattr(bock_mix_muse, 'pick_tracks', boom)
    candidates = [
        {'path': '/x.mp3', 'title': 'Ambient Drift', 'artist': 'Stars', 'album': 'Sky', 'genre': 'ambient', 'year': '2020'},
    ]
    _name, paths, mode = bock_mix_muse.curate_playlist('calm morning', candidates, 5, load_config)
    assert mode == 'local'
    assert paths == ['/x.mp3']


def test_excludes_christmas_for_calm_prompt():
    candidates = [
        {'path': '/x.mp3', 'title': 'Silent Night', 'artist': 'Choir', 'album': 'Christmas', 'genre': 'holiday', 'year': '2020'},
        {'path': '/a.mp3', 'title': 'Morning Dew', 'artist': 'A', 'album': 'Calm', 'genre': 'ambient', 'year': '2020'},
    ]
    _name, paths = bock_mix_muse_local.pick_tracks_local('calm weekday morning', candidates, 5)
    assert paths == ['/a.mp3']


def test_mood_hint_prefers_ambient_for_calm():
    candidates = [
        {'path': '/a.mp3', 'title': 'Drift', 'artist': 'A', 'album': 'X', 'genre': 'ambient', 'year': '2020'},
        {'path': '/m.mp3', 'title': 'Crush', 'artist': 'B', 'album': 'Y', 'genre': 'metal', 'year': '2020'},
    ]
    _name, paths = bock_mix_muse_local.pick_tracks_local('calm summer morning', candidates, 2)
    assert paths[0] == '/a.mp3'
