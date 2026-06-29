"""Bug hunt sprint (#17) — queue locks, m3u dedup, playlist XML locking."""
import json
import os
import xml.etree.ElementTree as ET

import server


def test_m3u_has_track_path_normalized(isolated_paths, tmp_path):
    m3u = tmp_path / 'list.m3u'
    track = tmp_path / 'Artist' / 'Album' / 'song.flac'
    track.parent.mkdir(parents=True)
    track.write_bytes(b'x')
    m3u.write_text(f'{track}\n', encoding='utf-8')
    assert server._m3u_has_track(str(m3u), str(track))
    assert not server._m3u_has_track(str(m3u), str(tmp_path / 'Artist' / 'Album' / 'other.flac'))


def test_m3u_has_track_no_substring_false_positive(isolated_paths, tmp_path):
    m3u = tmp_path / 'list.m3u'
    a = tmp_path / 'music' / 'a.mp3'
    ab = tmp_path / 'music' / 'ab.mp3'
    a.parent.mkdir(parents=True)
    a.write_bytes(b'1')
    ab.write_bytes(b'2')
    m3u.write_text(f'{a}\n', encoding='utf-8')
    assert server._m3u_has_track(str(m3u), str(a))
    assert not server._m3u_has_track(str(m3u), str(ab))


def test_queue_save_uses_flock(isolated_paths, monkeypatch):
    flock_calls = []

    def fake_flock(fileno, op):
        flock_calls.append(op)

    monkeypatch.setattr(server.fcntl, 'flock', fake_flock)
    server._save_queues({'q1': {'tracks': ['/x.mp3'], 'ts': 1.0}})
    assert flock_calls, 'queue save should acquire cross-process flock'


def test_append_m3u_track_dedupes(isolated_paths, tmp_path):
    m3u = tmp_path / 'list.m3u'
    track = tmp_path / 'song.flac'
    track.write_bytes(b'x')
    m3u.write_text(f'{track}\n', encoding='utf-8')
    assert server._append_m3u_track(str(m3u), str(track)) is False
    other = tmp_path / 'other.flac'
    other.write_bytes(b'y')
    assert server._append_m3u_track(str(m3u), str(other)) is True
    assert server._m3u_has_track(str(m3u), str(other))


def test_write_m3u_file_atomic(isolated_paths, tmp_path):
    out = tmp_path / 'pl.m3u'
    track = tmp_path / 'a.mp3'
    track.write_bytes(b'1')
    server._write_m3u_file(str(out), [str(track)])
    assert out.read_text(encoding='utf-8').startswith('#EXTM3U')
    assert str(track) in out.read_text(encoding='utf-8')
    assert not list(tmp_path.glob('*.tmp')), 'temp m3u should be replaced'


def test_persist_playlist_writes_xml_before_m3u(isolated_paths, tmp_path, monkeypatch):
    order = []
    root = ET.Element('Playlists')
    tree = ET.ElementTree(root)
    monkeypatch.setattr(server, '_load_playlists_tree', lambda: tree)
    monkeypatch.setattr(server, 'BOCK_PLAYLIST_DIR', str(tmp_path))
    monkeypatch.setattr(server, '_save_playlists_tree', lambda tree: order.append('xml'))
    monkeypatch.setattr(server, '_write_m3u_file', lambda path, paths: order.append('m3u'))
    monkeypatch.setattr(server, '_invalidate_playlist_cover', lambda pid: None)
    monkeypatch.setattr(server, '_save_playlist_cover_cache', lambda: None)
    track = tmp_path / 't.mp3'
    track.write_bytes(b'1')
    pid = 'test-pl-1'
    server._persist_playlist(pid, 'Test PL', [str(track)], create=True)
    assert order == ['xml', 'm3u']


def test_sync_prune_orphan_m3us(isolated_paths, tmp_path):
    from scripts.sync_plex_playlists import prune_orphan_m3us, referenced_m3u_paths

    pl_dir = tmp_path / 'plex'
    pl_dir.mkdir()
    keep = pl_dir / 'Good.123.m3u'
    orphan = pl_dir / 'Stale.456.m3u'
    keep.write_text('#EXTM3U\n', encoding='utf-8')
    orphan.write_text('#EXTM3U\n', encoding='utf-8')
    root = ET.Element('Playlists')
    entry = ET.SubElement(root, 'Entry')
    key = ET.SubElement(entry, 'Key')
    ET.SubElement(key, 'SourceID').text = str(keep)
    assert str(keep) in referenced_m3u_paths(root)
    removed = prune_orphan_m3us(root, str(pl_dir))
    assert removed == 1
    assert keep.is_file()
    assert not orphan.is_file()
