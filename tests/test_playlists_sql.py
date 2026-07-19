"""Playlist SQL schema (phase 0–1)."""
import bock_playlists


def test_ensure_schema_creates_tables(isolated_paths, monkeypatch):
    import server
    bock_playlists.ensure_schema(server.get_db_rw)
    rows = server.db_query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name IN "
        "('playlist_sources', 'playlist_tracks', 'schema_migrations')"
    )
    names = {r['name'] for r in rows}
    assert 'playlist_sources' in names
    assert 'playlist_tracks' in names
    assert 'schema_migrations' in names
    mig = server.db_one(
        "SELECT name FROM schema_migrations WHERE name=?",
        [bock_playlists.SCHEMA_MIGRATION],
    )
    assert mig.get('name') == bock_playlists.SCHEMA_MIGRATION


def test_is_sql_backed_false_when_missing(isolated_paths):
    import server
    bock_playlists.ensure_schema(server.get_db_rw)
    assert bock_playlists.is_sql_backed('no-such-playlist', server.db_query) is False


def test_crud_round_trip(isolated_paths, tmp_path):
    import server
    bock_playlists.ensure_schema(server.get_db_rw)
    pid = 'test-playlist-1'
    paths = ['/music/a.flac', '/music/b.flac', '/music/c.flac']
    m3u = str(tmp_path / 'test.m3u')
    bock_playlists.import_from_m3u(
        server.get_db_rw, pid, 'Test', m3u, paths, source_kind='bockmedia',
    )
    assert bock_playlists.is_sql_backed(pid, server.db_one)
    assert bock_playlists.tracks(pid, server.db_query) == paths
    assert bock_playlists.append_track(server.get_db_rw, server.db_one, pid, '/music/d.flac')
    assert len(bock_playlists.tracks(pid, server.db_query)) == 4
    assert bock_playlists.remove_track(
        server.get_db_rw, server.db_query, server.db_one, pid, '/music/b.flac',
    )
    assert bock_playlists.tracks(pid, server.db_query) == ['/music/a.flac', '/music/c.flac', '/music/d.flac']
    assert bock_playlists.move_track(
        server.get_db_rw, server.db_query, server.db_one, pid, '/music/d.flac', 0,
    )
    assert bock_playlists.tracks(pid, server.db_query)[0] == '/music/d.flac'


def test_migrate_playlists_includes_plex(isolated_paths, tmp_path, monkeypatch):
    import server
    import xml.etree.ElementTree as ET

    bock_playlists.ensure_schema(server.get_db_rw)
    m3u = tmp_path / 'plex.m3u'
    m3u.write_text('#EXTM3U\n/music/plex-a.flac\n', encoding='utf-8')
    pid = 'plex-playlist-1'
    # The shared demo DB persists between runs — drop any earlier import so
    # the migration below has work to do.
    conn = server.get_db_rw()
    conn.execute('DELETE FROM playlist_sources WHERE id=?', [pid])
    conn.execute('DELETE FROM playlist_tracks WHERE playlist_id=?', [pid])
    conn.commit()
    conn.close()
    root = ET.Element('ServerPlaylists')
    entry = ET.SubElement(root, 'Entry')
    key = ET.SubElement(entry, 'Key')
    ET.SubElement(key, 'ID').text = pid
    ET.SubElement(key, 'Name').text = 'Plex Mix'
    ET.SubElement(key, 'SourceID').text = str(m3u)
    ET.SubElement(key, 'SourceName').text = 'plex'
    tree = ET.ElementTree(root)
    monkeypatch.setattr(server, '_load_playlists_tree', lambda: tree)
    monkeypatch.setattr(server, 'parse_m3u', lambda path, verify_exists=False: ['/music/plex-a.flac'])
    assert server._migrate_playlists_to_sql() == 1
    assert bock_playlists.is_sql_backed(pid, server.db_one)
    assert bock_playlists.tracks(pid, server.db_query) == ['/music/plex-a.flac']


def test_tracks_page_sort_and_search(isolated_paths, tmp_path):
    import server
    bock_playlists.ensure_schema(server.get_db_rw)
    pid = 'sort-search-test'
    paths = ['/music/z.flac', '/music/a.flac', '/music/m.flac']
    m3u = str(tmp_path / 'sort.m3u')
    bock_playlists.import_from_m3u(
        server.get_db_rw, pid, 'Sort', m3u, paths, source_kind='bockmedia',
    )
    conn = server.get_db_rw()
    conn.executemany(
        'INSERT OR REPLACE INTO songs_cache(path, title, artist, album) VALUES (?,?,?,?)',
        [
            ('/music/z.flac', 'Zebra', 'Zed', 'Zoo'),
            ('/music/a.flac', 'Alpha', 'Amy', 'Animals'),
            ('/music/m.flac', 'Middle', 'Mike', 'Mix'),
        ],
    )
    conn.commit()
    conn.close()

    by_title, total = bock_playlists.tracks_page(
        pid, server.db_query, db_one=server.db_one,
        sort_by='title', order='asc', offset=0, limit=10,
    )
    assert total == 3
    assert by_title[0] == '/music/a.flac'

    filtered, ftotal = bock_playlists.tracks_page(
        pid, server.db_query, db_one=server.db_one,
        sort_by='title', order='asc', offset=0, limit=10, q='zebra',
    )
    assert ftotal == 1
    assert filtered == ['/music/z.flac']


def test_playlist_paths_cached_uses_sql(isolated_paths, tmp_path, monkeypatch):
    import server
    import bock_playlists
    bock_playlists.ensure_schema(server.get_db_rw)
    pid = 'sql-paths-test'
    paths = [f'/music/{c}.flac' for c in 'abcde']
    m3u = str(tmp_path / 'paths.m3u')
    bock_playlists.import_from_m3u(
        server.get_db_rw, pid, 'Paths', m3u, paths, source_kind='bockmedia',
    )
    monkeypatch.setattr(server, 'parse_m3u', lambda *a, **k: (_ for _ in ()).throw(AssertionError('m3u parse')))
    assert server._playlist_paths_cached(pid, m3u) == paths
    assert server._playlist_paths_cached(pid, m3u, offset=1, limit=2) == paths[1:3]

