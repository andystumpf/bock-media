"""Tests for Listen Agent intent parsing and resolution."""
import bock_listen_agent


def test_parse_intent_local_album():
    intent = bock_listen_agent.parse_intent_local('Play the Album Siamese Dream')
    assert intent['intent'] == 'album'
    assert intent['album'] == 'Siamese Dream'
    assert intent['shuffle'] is False


def test_parse_intent_local_artist_top():
    intent = bock_listen_agent.parse_intent_local('play top songs from Steely Dan')
    assert intent['intent'] == 'artist_top'
    assert intent['artist'] == 'Steely Dan'


def test_parse_intent_local_play_artist_defaults_mood_without_db():
    intent = bock_listen_agent.parse_intent_local('play Steely Dan')
    assert intent['intent'] == 'mood'


def test_infer_intent_play_artist(sample_track):
    import server
    intent = bock_listen_agent.infer_intent_from_library(
        'play Steely Dan', server.db_query,
    )
    if intent:
        assert intent['intent'] == 'artist_top'
        assert intent.get('artist')


def test_clean_listen_prompt():
    assert bock_listen_agent._clean_listen_prompt('play Steely Dan') == 'Steely Dan'
    assert bock_listen_agent._clean_listen_prompt('Play the Album Siamese Dream') == 'Siamese Dream'


def test_core_album_title():
    f = bock_listen_agent._core_album_title
    assert f('[1993] Siamese Dream (2011 - Remaster)') == 'siamese dream'
    assert f('[1993] Siamese Dream (Deluxe Edition)') == 'siamese dream'
    assert f('Siamese Dream') == 'siamese dream'


def _fake_db(rows_by_marker):
    """db_query stub keyed on SQL substrings."""
    def db_query(sql, params=None):
        for marker, fn in rows_by_marker.items():
            if marker in sql:
                return fn(sql, params or [])
        return []
    return db_query


def test_lookup_artist_prefers_main_artist_over_collab():
    artists = {
        'Steely Dan': 196,
        'Marian McPartland with guest Steely Dan': 1,
        'Steely Dan, Carolyn Leonhart': 2,
    }

    def counts(sql, params):
        return [{'n': artists.get(params[0], 0)}]

    def exact(sql, params):
        ql = params[0]
        hits = [a for a in artists if a.lower() == ql]
        return [{'artist': hits[0]}] if hits else []

    db = _fake_db({
        'COUNT(*) AS n': counts,
        'LOWER(artist) = ?': exact,
        'LOWER(album_artist) = ?': lambda s, p: [],
    })
    assert bock_listen_agent.lookup_artist(db, 'play Steely Dan') == 'Steely Dan'


def _album_db(albums, monkeypatch):
    def like_albums(sql, params):
        ql = params[0].strip('%')
        return [
            {'name': name, 'artist': artist, 'n': n}
            for name, (artist, n) in albums.items() if ql in name.lower()
        ]

    def count_album(sql, params):
        return [{'n': albums.get(params[0], ('', 0))[1]}]

    monkeypatch.setattr(
        bock_listen_agent.bock_search, 'search_albums', lambda *a, **k: [],
    )
    return _fake_db({
        'LOWER(album) LIKE ?': like_albums,
        'WHERE album = ? AND path IS NOT NULL': count_album,
    })


def test_lookup_album_prefers_plain_title_over_deluxe(monkeypatch):
    db = _album_db({
        'Siamese Dream': ('Smashing Pumpkins', 9),
        '[1993] Siamese Dream (2011 - Remaster)': ('The Smashing Pumpkins', 2),
        '[1993] Siamese Dream (Deluxe Edition)': ('The Smashing Pumpkins', 32),
    }, monkeypatch)
    hit = bock_listen_agent.lookup_album(db, 'Play the Album Siamese Dream')
    assert hit is not None
    assert hit[0] == 'Siamese Dream'
    assert hit[1] == 'Smashing Pumpkins'


def test_lookup_album_falls_back_to_fullest_variant(monkeypatch):
    db = _album_db({
        '[1993] Siamese Dream (2011 - Remaster)': ('The Smashing Pumpkins', 2),
        '[1993] Siamese Dream (Deluxe Edition)': ('The Smashing Pumpkins', 32),
    }, monkeypatch)
    hit = bock_listen_agent.lookup_album(db, 'Play the Album Siamese Dream')
    assert hit is not None
    assert hit[0] == '[1993] Siamese Dream (Deluxe Edition)'


def test_artist_top_uses_play_counts_first():
    rows = [
        {'title': 'Deep Cut', 'album': 'Album A', 'path': '/a.mp3'},
        {'title': 'Big Hit', 'album': 'Album A', 'path': '/b.mp3'},
        {'title': 'Big Hit', 'album': 'Greatest Hits', 'path': '/b2.mp3'},
    ]

    def library(sql, params):
        return rows

    db = _fake_db({'FROM songs_cache': library})
    plays = {'/b.mp3': 12, '/a.mp3': 1, '/b2.mp3': 0}

    def enrich(items):
        return [{**dict(r), 'playCount': plays.get(r['path'], 0)} for r in items]

    paths = bock_listen_agent._artist_top_paths(db, enrich, 'X', 2)
    assert paths[0] == '/b.mp3'
    assert len(paths) == 2


def test_artist_top_heuristic_prefers_compilation_repeats():
    rows = [
        {'title': 'Obscure Track', 'album': 'Album A', 'path': '/o.mp3'},
        {'title': 'Single', 'album': 'Album A', 'path': '/s1.mp3'},
        {'title': 'Single', 'album': 'The Greatest Hits', 'path': '/s2.mp3'},
    ]
    db = _fake_db({'FROM songs_cache': lambda s, p: rows})

    def enrich(items):
        return [{**dict(r), 'playCount': 0} for r in items]

    paths = bock_listen_agent._artist_top_paths(db, enrich, 'X', 1)
    assert paths == ['/s1.mp3']


def test_resolve_artist_top(monkeypatch, sample_track):
    import server

    def enrich(rows):
        return [{**dict(r), 'playCount': 0} for r in rows]

    intent = {'intent': 'artist_top', 'artist': sample_track['artist'], 'limit': 5, 'shuffle': False}
    name, paths, shuffle, kind = bock_listen_agent.resolve_intent(
        intent,
        'play top songs',
        server.db_query,
        lambda: {},
        lambda p: [{'path': x, 'title': x} for x in p],
        enrich,
        fuzzy_artist=server.fuzzy_find_artist,
    )
    assert kind == 'artist_top'
    assert shuffle is False
    assert sample_track['artist'] in name
    assert paths


def test_resolve_album(monkeypatch, sample_track):
    import server

    rows = server.db_query(
        'SELECT DISTINCT album FROM songs_cache WHERE album IS NOT NULL AND album != "" '
        'AND path IS NOT NULL ' + server._streamable_ext_sql() + ' LIMIT 1',
    ) or []
    if not rows:
        import pytest
        pytest.skip('no album with playable tracks in fixture DB')
    album = rows[0]['album']
    intent = {'intent': 'album', 'album': album, 'shuffle': False}
    name, paths, shuffle, kind = bock_listen_agent.resolve_intent(
        intent,
        f'play album {album}',
        server.db_query,
        lambda: {},
        lambda p: [{'path': x, 'title': x} for x in p],
        lambda rows: rows,
        fuzzy_album=server.fuzzy_find_album,
        album_tracks_fn=server._album_tracks_for_play,
    )
    assert kind == 'album'
    assert shuffle is False
    assert album in name
    assert paths
