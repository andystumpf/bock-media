"""Tests for bock_home.build_home_payload."""
import bock_home


def _payload(**overrides):
    base = {
        'member': '',
        'history_mtime': 1.0,
        'deferred': True,
        'read_stream_history': lambda: [],
        'filter_history_rows': lambda rows, a, b: rows,
        'load_favorites': lambda: [],
        'load_smart_playlists': lambda: {'items': []},
        'load_playlist_summaries': lambda member='', limit=500: {'items': [], 'total': 0},
        'load_genres': lambda limit=40: {'items': [], 'total': 0},
        'library_new': lambda: {},
        'discover_weekly': lambda member='': {},
        'continue_listening': lambda member='': {},
    }
    base.update(overrides)
    return bock_home.build_home_payload(**base)


class TestHomeHistoryScoping:
    def test_filters_history_by_member(self):
        rows = [
            {'track': 'Old Parent', 'memberId': 'p-parent', 'playlist': 'Parent A', 'filepath': '/a.mp3'},
            {'track': 'Kid Song', 'memberId': 'p-kid', 'playlist': 'Kid Mix', 'filepath': '/b.mp3'},
            {'track': 'New Parent', 'memberId': 'p-parent', 'playlist': 'Parent B', 'filepath': '/c.mp3'},
        ]

        def filter_for_member(history, member):
            return [r for r in history if r.get('memberId') == member]

        data = _payload(
            member='p-parent',
            read_stream_history=lambda: list(rows),
            filter_history_for_member=filter_for_member,
        )
        history = data['history']['items']
        assert len(history) == 2
        assert history[0]['playlist'] == 'Parent B'
        assert history[1]['playlist'] == 'Parent A'
        assert all(r.get('memberId') == 'p-parent' for r in history)

    def test_dashboard_recent_includes_playlist(self):
        rows = [
            {'track': 'T1', 'memberId': 'p-parent', 'playlist': 'Road Trip', 'filepath': '/a.mp3'},
        ]
        data = _payload(read_stream_history=lambda: list(rows))
        recent = data['dashboard']['recent']
        assert recent[0]['playlist'] == 'Road Trip'


class TestHomeListeningSummary:
    def test_listening_summary_from_history(self):
        bock_home.bust_home_cache()
        rows = [
            {'artist': 'Artist A', 'filepath': '/music/a.flac', 'memberId': 'household'},
            {'artist': 'Artist A', 'filepath': '/music/a.flac', 'memberId': 'household'},
            {'artist': 'Artist B', 'filepath': '/music/b.flac', 'memberId': 'household'},
        ]

        def fake_db_query(sql, params=()):
            assert 'songs_cache' in sql
            return [{'path': '/music/a.flac', 'genre': 'Rock'}, {'path': '/music/b.flac', 'genre': 'Jazz'}]

        data = _payload(
            read_stream_history=lambda: list(rows),
            db_query=fake_db_query,
        )
        summary = data['listeningSummary']
        assert summary['topArtists'][0]['name'] == 'Artist A'
        assert summary['topArtists'][0]['count'] == 2
        assert {g['name'] for g in summary['topGenres']} == {'Rock', 'Jazz'}
