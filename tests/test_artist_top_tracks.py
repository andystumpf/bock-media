"""Tests for bock_artist_top_tracks (Spotify/Deezer popular track ordering)."""
import bock_artist_top_tracks


def _enrich(rows):
    return [dict(r, playCount=0, rating=0, liked=False) for r in rows]


def test_match_refs_preserves_chart_order():
    def db_query(sql, params=None):
        return [
            {
                'id': '1', 'title': 'Paranoid Android', 'artist': 'Radiohead', 'album': 'OK Computer',
                'genre': 'Rock', 'year': 1997, 'duration_seconds': 400, 'track_number': 3,
                'path': '/music/radiohead/ok/paranoid.flac',
            },
            {
                'id': '2', 'title': 'Karma Police', 'artist': 'Radiohead', 'album': 'OK Computer',
                'genre': 'Rock', 'year': 1997, 'duration_seconds': 260, 'track_number': 6,
                'path': '/music/radiohead/ok/karma.flac',
            },
        ]

    refs = [
        {'title': 'Karma Police', 'album': 'OK Computer'},
        {'title': 'Paranoid Android', 'album': 'OK Computer'},
    ]
    out = bock_artist_top_tracks.match_refs_to_library(
        'Radiohead', refs, db_query, _enrich,
    )
    assert [t['title'] for t in out] == ['Karma Police', 'Paranoid Android']


def test_match_refs_skips_missing_library_tracks():
    def db_query(sql, params=None):
        return [
            {
                'id': '1', 'title': 'Creep', 'artist': 'Radiohead', 'album': 'Pablo Honey',
                'genre': 'Rock', 'year': 1993, 'duration_seconds': 240, 'track_number': 1,
                'path': '/music/radiohead/creep.flac',
            },
        ]

    refs = [
        {'title': 'Fake Plastic Trees', 'album': 'The Bends'},
        {'title': 'Creep', 'album': 'Pablo Honey'},
    ]
    out = bock_artist_top_tracks.match_refs_to_library(
        'Radiohead', refs, db_query, _enrich,
    )
    assert len(out) == 1
    assert out[0]['title'] == 'Creep'


def test_resolve_uses_spotify_when_configured(monkeypatch):
    def db_query(sql, params=None):
        return [
            {
                'id': '1', 'title': 'Song A', 'artist': 'Artist X', 'album': 'Album',
                'genre': 'Pop', 'year': 2020, 'duration_seconds': 200, 'track_number': 1,
                'path': '/music/a.flac',
            },
        ]

    monkeypatch.setattr(
        bock_artist_top_tracks,
        'fetch_external_top_refs',
        lambda artist, limit, load_config_fn: ([{'title': 'Song A', 'album': 'Album'}], 'spotify'),
    )
    tracks, source = bock_artist_top_tracks.resolve_artist_top_tracks(
        'Artist X', db_query, _enrich, limit=5, load_config_fn=lambda: {},
    )
    assert source == 'spotify'
    assert tracks[0]['path'] == '/music/a.flac'
    assert tracks[0]['playCount'] == 0


def test_spotify_config_rejects_placeholder_credentials():
    cfg = {
        'spotify': {
            'enabled': True,
            'clientId': 'SET_LOCALLY',
            'clientSecret': 'SET_LOCALLY',
        },
    }
    sc = bock_artist_top_tracks.spotify_config(lambda: cfg)
    assert sc['enabled'] is False


def test_match_refs_enriches_only_matched_rows():
    enrich_calls = []

    def db_query(sql, params=None):
        return [
            {
                'id': str(i), 'title': f'Track {i}', 'artist': 'Big Artist', 'album': 'Album',
                'genre': 'Pop', 'year': 2020, 'duration_seconds': 200, 'track_number': i,
                'path': f'/music/t{i}.flac',
            }
            for i in range(50)
        ]

    def enrich(rows):
        enrich_calls.append(len(rows))
        return [dict(r, playCount=0, rating=0, liked=False) for r in rows]

    refs = [{'title': 'Track 1', 'album': 'Album'}]
    out = bock_artist_top_tracks.match_refs_to_library('Big Artist', refs, db_query, enrich)
    assert len(out) == 1
    assert enrich_calls == [1]


def test_resolve_falls_back_to_local_plays(monkeypatch):
    def db_query(sql, params=None):
        return [
            {
                'id': '1', 'title': 'Low', 'artist': 'Artist X', 'album': 'A',
                'genre': 'Pop', 'year': 2020, 'duration_seconds': 200, 'track_number': 1,
                'path': '/music/low.flac',
            },
            {
                'id': '2', 'title': 'High', 'artist': 'Artist X', 'album': 'B',
                'genre': 'Pop', 'year': 2021, 'duration_seconds': 210, 'track_number': 1,
                'path': '/music/high.flac',
            },
        ]

    def enrich(rows):
        counts = {'/music/high.flac': 99, '/music/low.flac': 1}
        return [
            dict(r, playCount=counts.get(r['path'], 0), rating=0, liked=False)
            for r in rows
        ]

    monkeypatch.setattr(
        bock_artist_top_tracks,
        'fetch_external_top_refs',
        lambda artist, limit, load_config_fn: (None, None),
    )
    tracks, source = bock_artist_top_tracks.resolve_artist_top_tracks(
        'Artist X', db_query, enrich, limit=5, load_config_fn=lambda: {},
    )
    assert source == 'local'
    assert tracks[0]['title'] == 'High'
