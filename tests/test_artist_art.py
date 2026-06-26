"""Unit tests for bock_artist_art (artist portraits)."""
import os

import bock_artist_art


def test_portrait_cache_file_stable():
    a = bock_artist_art.portrait_cache_file('/tmp/cache', 'The Beatles')
    b = bock_artist_art.portrait_cache_file('/tmp/cache', 'Beatles')
    assert a == b
    assert a.endswith('.jpg')
    assert 'artist-portrait-' in a


def test_cached_portrait_rel_path(tmp_path):
    name = 'Radiohead'
    cache = bock_artist_art.portrait_cache_file(str(tmp_path), name)
    with open(cache, 'wb') as fh:
        fh.write(b'fake')
    rel = bock_artist_art.cached_portrait_rel_path(name, str(tmp_path))
    assert rel == f'artwork_cache/{os.path.basename(cache)}'


def test_resolve_portrait_uses_deezer_first(tmp_path, monkeypatch):
    cfg = {'artistArt': {'enabled': True}, 'acquire': {'userAgent': 'TestAgent/1.0'}}
    monkeypatch.setattr(bock_artist_art, '_neg_cache', set())
    monkeypatch.setattr(
        bock_artist_art,
        '_deezer_portrait_url',
        lambda name, ua: 'https://cdn-images.dzcdn.net/images/artist/xl.jpg',
    )
    monkeypatch.setattr(bock_artist_art, '_itunes_portrait_url', lambda *a, **k: None)

    def fake_download(url, dest, ua):
        with open(dest, 'wb') as fh:
            fh.write(b'\xff\xd8\xff' + b'0' * 600)
        return True

    monkeypatch.setattr(bock_artist_art, '_download_url', fake_download)
    out = bock_artist_art.resolve_portrait('Radiohead', str(tmp_path), lambda: cfg)
    assert out is not None
    assert out['source'] == 'deezer'
    assert out['art_path'].startswith('artwork_cache/artist-portrait-')


def test_resolve_portrait_itunes_fallback(tmp_path, monkeypatch):
    cfg = {'artistArt': {'enabled': True}, 'acquire': {'userAgent': 'TestAgent/1.0'}}
    monkeypatch.setattr(bock_artist_art, '_neg_cache', set())
    monkeypatch.setattr(bock_artist_art, '_deezer_portrait_url', lambda *a, **k: None)
    monkeypatch.setattr(
        bock_artist_art,
        '_itunes_portrait_url',
        lambda name, ua: 'https://is1-ssl.mzstatic.com/image/thumb/1000x1000bb.jpg',
    )

    def fake_download(url, dest, ua):
        with open(dest, 'wb') as fh:
            fh.write(b'\xff\xd8\xff' + b'1' * 600)
        return True

    monkeypatch.setattr(bock_artist_art, '_download_url', fake_download)
    out = bock_artist_art.resolve_portrait('ABBA', str(tmp_path), lambda: cfg)
    assert out is not None
    assert out['source'] == 'itunes'


def test_resolve_portrait_library_track_when_remote_misses(tmp_path, monkeypatch):
    cfg = {'artistArt': {'enabled': True}, 'acquire': {'userAgent': 'TestAgent/1.0'}}
    monkeypatch.setattr(bock_artist_art, '_neg_cache', set())
    monkeypatch.setattr(bock_artist_art, '_itunes_portrait_url', lambda *a, **k: None)
    monkeypatch.setattr(bock_artist_art, '_deezer_portrait_url', lambda *a, **k: None)

    def db_query(sql, params):
        return [{'art_path': '/music/obscure/track.mp3'}]

    out = bock_artist_art.resolve_portrait(
        'Obscure Band',
        str(tmp_path),
        lambda: cfg,
        db_query=db_query,
    )
    assert out == {
        'artist': 'Obscure Band',
        'art_path': '/music/obscure/track.mp3',
        'source': 'library',
        'cached': False,
    }
