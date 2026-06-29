"""Unit tests for bock_acquire (MusicBrainz acquire ideas)."""
import json

import bock_acquire


def _fake_mb(path):
    if path.startswith('/artist?') and 'tag%3A' in path:
        if 'alternative' in path:
            return {
                'artists': [
                    {'id': 'mbid-a1', 'name': 'Pixies', 'score': 12},
                    {'id': 'mbid-a2', 'name': 'Radiohead', 'score': 8},
                ],
            }
        if 'rock' in path:
            return {
                'artists': [{'id': 'mbid-a3', 'name': 'Sonic Youth', 'score': 9}],
            }
    if path.startswith('/artist?'):
        return {
            'artists': [{'id': 'mbid-seed', 'name': 'Radiohead', 'score': 100}],
        }
    if path.startswith('/artist/mbid-seed'):
        return {
            'id': 'mbid-seed',
            'name': 'Radiohead',
            'tags': [{'name': 'alternative rock', 'count': 10}, {'name': 'rock', 'count': 5}],
            'genres': [{'name': 'alternative rock', 'count': 3}],
            'relations': [
                {
                    'type': 'member of band',
                    'artist': {'id': 'mbid-related', 'name': 'Atoms for Peace'},
                },
            ],
        }
    return None


def test_norm_and_owned():
    owned = {'radiohead', 'beatles', 'war'}
    assert bock_acquire.is_owned('Radiohead', owned)
    assert bock_acquire.is_owned('Beatles', owned)
    assert bock_acquire.is_owned('The Beatles', owned)
    assert bock_acquire.is_owned('War', owned)
    assert not bock_acquire.is_owned('Pixies', owned)
    assert not bock_acquire.is_owned('Warrant', owned)
    assert not bock_acquire.is_owned('Fairground Attraction', owned)
    assert not bock_acquire.is_owned('', owned)
    assert not bock_acquire.is_owned('   ', owned)
    assert not bock_acquire.is_owned('!!!', owned)


def test_owned_artists_uses_artist_key():
    owned = bock_acquire.owned_artists(lambda sql, params=None: [
        {'a': 'The Beatles'},
        {'a': 'Radiohead'},
    ])
    assert owned == {'beatles', 'radiohead'}
    assert bock_acquire.is_owned('The Beatles', owned)
    assert bock_acquire.is_owned('Beatles', owned)


def test_add_candidate_caps_tags_on_merge():
    candidates = {}
    bock_acquire._add_candidate(candidates, 'Pixies', 'mbid-1', 'tag a', ['rock'], 1.0)
    for i in range(12):
        bock_acquire._add_candidate(
            candidates, 'Pixies', 'mbid-1', f'reason {i}',
            [f'tag-{i}'], 0.5,
        )
    assert len(candidates['pixies']['tags']) <= 8


def test_cache_get_accepts_legacy_entry_without_ts(tmp_path):
    import os
    cache_path = bock_acquire._cache_path(str(tmp_path))
    payload = {
        'entries': {
            'seed:radiohead:5': {'data': {'source': 'musicbrainz', 'suggestions': []}},
        },
    }
    os.makedirs(str(tmp_path), exist_ok=True)
    with open(cache_path, 'w', encoding='utf-8') as f:
        json.dump(payload, f)

    assert bock_acquire._cache_get(str(tmp_path), 'seed:radiohead:5') == payload['entries']['seed:radiohead:5']['data']


def test_collect_filters_owned(monkeypatch):
    monkeypatch.setattr(bock_acquire, '_mb_fetch', _fake_mb)
    owned = {'radiohead', 'atoms for peace'}
    suggestions, resolved, _ = bock_acquire._collect_from_mb('Radiohead', 'TestUA/1.0', owned, 10)
    names = {s['name'] for s in suggestions}
    assert 'Radiohead' not in names
    assert 'Atoms for Peace' not in names
    assert 'Pixies' in names or 'Sonic Youth' in names
    assert resolved['mbid'] == 'mbid-seed'


def test_suggest_for_seed_cached(tmp_path, monkeypatch):
    monkeypatch.setattr(bock_acquire, '_mb_fetch', _fake_mb)

    def db_query(sql, params=None):
        return [{'artist': 'Radiohead'}]

    def db_one(sql, params=None):
        return None

    def load_config():
        return {'acquire': {'enabled': True}}

    result = bock_acquire.suggest_for_seed(
        db_query, db_one, load_config, str(tmp_path), None,
        seed_kind='artist', artist='Radiohead', limit=5,
    )
    assert result.get('source') == 'musicbrainz'
    assert result['seed']['artist'] == 'Radiohead'
    assert isinstance(result['suggestions'], list)
    assert all(not s.get('inLibrary') for s in result['suggestions'])

    result2 = bock_acquire.suggest_for_seed(
        db_query, db_one, load_config, str(tmp_path), None,
        seed_kind='artist', artist='Radiohead', limit=5,
    )
    assert result2 == result
