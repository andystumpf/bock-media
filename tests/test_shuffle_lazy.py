"""Lazy queue shuffle uses shuffle_seed — stable order across decode_token calls."""
import server


def test_lazy_shuffle_seed_stable(isolated_paths, monkeypatch):
    paths = ['/fixtures/a.mp3', '/fixtures/b.mp3', '/fixtures/c.mp3', '/fixtures/d.mp3']
    monkeypatch.setattr(server, '_playlist_paths_cached', lambda pid, src: list(paths))
    monkeypatch.setattr(server, '_filter_ignored_queue', lambda q: q)
    monkeypatch.setattr(server, 'normalize_track_queue_fast', lambda q: q)

    qid = server._store_queue_lazy('pl-1', 'm3u', shuffle=True, shuffle_seed=4242)
    first = server.decode_token(f'{qid}:0')
    second = server.decode_token(f'{qid}:0')
    assert first is not None and second is not None
    assert first['tracks'] == second['tracks']
    assert first['tracks'] != paths  # shuffled

    server._update_queue_flags(qid, shuffle=True, shuffle_seed=9999, tracks=None)
    reshuffled = server.decode_token(f'{qid}:0')
    assert reshuffled['tracks'] != first['tracks']
