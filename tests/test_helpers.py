"""
Pure helper-function tests. No Flask client needed.

Each test names the intent/contract it verifies in its docstring.
"""
import os
import json
import time

import pytest

import server


# ─────────────────────────── normalize_spoken_value ──────────────────────────

class TestNormalizeSpokenValue:
    def test_empty(self):
        """empty/None becomes empty string"""
        assert server.normalize_spoken_value('') == ''
        assert server.normalize_spoken_value(None) == ''

    def test_passthrough(self):
        """clean phrase returned unchanged"""
        assert server.normalize_spoken_value('yacht rock') == 'yacht rock'

    def test_strips_alexa_prefix(self):
        """invocation prefix bleed-through stripped"""
        assert server.normalize_spoken_value('alexa ask local media to play yacht rock') == 'play yacht rock'

    def test_strips_repeated(self):
        """multiple stacked prefixes stripped"""
        v = 'ask local media local media yacht rock'
        assert server.normalize_spoken_value(v) == 'yacht rock'

    def test_preserves_internal_words(self):
        """does not strip the word 'local' if not part of an invocation prefix"""
        assert server.normalize_spoken_value('a local hero') == 'a local hero'


# ─────────────────────────── token encode/decode ─────────────────────────────

class TestTokenCodec:
    def test_round_trip(self, isolated_paths):
        """encoded token round-trips to the original payload"""
        tracks = ['/a.mp3', '/b.mp3', '/c.mp3']
        token = server.encode_token({'tracks': tracks, 'idx': 1, 'shuffle': True, 'loop': False})
        assert ':' in token
        decoded = server.decode_token(token)
        assert decoded['tracks'] == tracks
        assert decoded['idx'] == 1
        assert decoded['shuffle'] is True

    def test_short_form(self, isolated_paths):
        """token format is <qid>:<idx>"""
        token = server.encode_token({'tracks': ['/a.mp3'], 'idx': 0})
        qid, idx = token.split(':', 1)
        assert idx == '0'
        assert len(qid) > 0
        assert len(token) < 100  # well under Alexa 1024 char limit

    def test_decode_unknown_qid_returns_empty(self, isolated_paths):
        """missing/expired qid yields None"""
        assert server.decode_token('NOSUCHQID:0') is None

    def test_decode_garbage(self, isolated_paths):
        """malformed token yields None"""
        assert server.decode_token('not-a-real-token') is None
        assert server.decode_token('') is None

    def test_advance_idx_reuses_qid(self, isolated_paths):
        """passing qid + new idx does not re-store the queue"""
        token1 = server.encode_token({'tracks': ['/a.mp3', '/b.mp3'], 'idx': 0})
        qid = token1.split(':')[0]
        token2 = server.encode_token({'qid': qid, 'idx': 1, 'tracks': []})
        assert token2.split(':')[0] == qid
        assert token2.split(':')[1] == '1'


# ─────────────────────────── device store ────────────────────────────────────

class TestDevices:
    def test_load_empty(self, isolated_paths):
        """fresh store is empty dict"""
        assert server._load_devices() == {}

    def test_register_creates(self, isolated_paths):
        """register adds an entry with first/lastSeen and default name"""
        server.register_device('amzn1.ask.device.AAA111')
        store = server._load_devices()
        assert 'amzn1.ask.device.AAA111' in store
        e = store['amzn1.ask.device.AAA111']
        assert e['name']
        assert e['firstSeen'] <= e['lastSeen']

    def test_register_updates_lastseen(self, isolated_paths):
        """re-register only bumps lastSeen, preserves firstSeen"""
        server.register_device('id1')
        first = server._load_devices()['id1']['firstSeen']
        time.sleep(0.01)
        server.register_device('id1')
        store = server._load_devices()
        assert store['id1']['firstSeen'] == first
        assert store['id1']['lastSeen'] >= first

    def test_register_ignores_empty(self, isolated_paths):
        """empty device id is a no-op"""
        server.register_device('')
        server.register_device(None)
        assert server._load_devices() == {}

    def test_friendly_name_returns_stored(self, isolated_paths):
        """device_friendly_name returns the stored name"""
        server.register_device('id1', default_name='Kitchen')
        assert server.device_friendly_name('id1') == 'Kitchen'

    def test_friendly_name_unknown(self, isolated_paths):
        """unknown device id returns empty string"""
        assert server.device_friendly_name('UNKNOWN') == ''


# ─────────────────────────── streaming history ───────────────────────────────

class TestStreamHistory:
    def test_append_and_read(self, isolated_paths):
        """history is append-only, returned in insertion order"""
        server.append_stream_history({'track': 'a', 'date': '2026-05-03T00:00:00'})
        server.append_stream_history({'track': 'b', 'date': '2026-05-03T00:00:01'})
        rows = server._read_stream_history()
        assert [r['track'] for r in rows] == ['a', 'b']

    def test_read_empty(self, isolated_paths):
        """missing file yields empty list"""
        assert server._read_stream_history() == []

    def test_cap_keeps_last_n(self, isolated_paths):
        """when over the cap, oldest rows are pruned and file rewritten"""
        server._STREAM_HISTORY_MAX = 5
        try:
            for i in range(10):
                server.append_stream_history({'track': f't{i}'})
            rows = server._read_stream_history()
            assert len(rows) == 5
            assert rows[0]['track'] == 't5'
            assert rows[-1]['track'] == 't9'
        finally:
            server._STREAM_HISTORY_MAX = 5000


# ─────────────────────────── now-playing per device ──────────────────────────

class TestNowPlaying:
    def test_write_requires_real_device(self, isolated_paths):
        """no real device id -> write_np_state is a no-op"""
        with server.app.test_request_context('/'):
            server.g.device_id = 'default'
            server.write_np_state({'track': 'x', 'playing': True})
        assert server._read_all_np() == {}

    def test_per_device_isolation(self, isolated_paths):
        """different deviceIds get independent now-playing slots"""
        with server.app.test_request_context('/'):
            server.g.device_id = 'devA'
            server.write_np_state({'track': 'songA', 'playing': True})
        with server.app.test_request_context('/'):
            server.g.device_id = 'devB'
            server.write_np_state({'track': 'songB', 'playing': True})

        with server.app.test_request_context('/'):
            server.g.device_id = 'devA'
            assert server.read_np_state()['track'] == 'songA'
        with server.app.test_request_context('/'):
            server.g.device_id = 'devB'
            assert server.read_np_state()['track'] == 'songB'

    def test_read_np_state_uses_merged_primary(self, isolated_paths):
        """NextIntent on a merged alias device id must find primary's np slot."""
        primary = 'dev-primary'
        alias = 'dev-alias-rotated'
        server._save_devices({
            primary: {'name': 'Office Show', 'firstSeen': 1, 'lastSeen': 2},
            alias: {'aliasOf': primary, 'name': 'Office Show', 'firstSeen': 3, 'lastSeen': 4},
        })
        with server.app.test_request_context('/'):
            server.g.device_id = primary
            server.g.raw_device_id = primary
            server.write_np_state({'track': 'Wildflower', 'token': 'q1:0', 'playing': True})
        with server.app.test_request_context('/'):
            server.g.device_id = primary
            server.g.raw_device_id = alias
            st = server.read_np_state()
            assert st is not None
            assert st['track'] == 'Wildflower'

    def test_remove_clears_only_that_device(self, isolated_paths):
        """remove_np_state removes only the current device's slot"""
        with server.app.test_request_context('/'):
            server.g.device_id = 'devA'
            server.write_np_state({'track': 'songA', 'playing': True})
        with server.app.test_request_context('/'):
            server.g.device_id = 'devB'
            server.write_np_state({'track': 'songB', 'playing': True})
        with server.app.test_request_context('/'):
            server.g.device_id = 'devA'
            server.remove_np_state()
        with server.app.test_request_context('/'):
            server.g.device_id = 'devA'
            assert server.read_np_state() is None
        with server.app.test_request_context('/'):
            server.g.device_id = 'devB'
            assert server.read_np_state()['track'] == 'songB'

    def test_prune_removes_old_idle(self, isolated_paths):
        """non-playing devices older than TTL are pruned"""
        payload = {'devices': {
            'old': {'playing': False, 'timestamp': 0},
            'recent': {'playing': True, 'timestamp': time.time()},
        }}
        out = server._prune_np(payload)
        assert 'old' not in out['devices']
        assert 'recent' in out['devices']


# ─────────────────────────── selected state ──────────────────────────────────

class TestSelected:
    def test_round_trip(self, isolated_paths):
        """write_selected then read_selected returns same dict"""
        server.write_selected({'type': 'album', 'name': 'X'})
        assert server.read_selected() == {'type': 'album', 'name': 'X'}

    def test_empty(self, isolated_paths):
        """missing file returns None"""
        assert server.read_selected() is None


# ─────────────────────────── ignored list ────────────────────────────────────

class TestIgnoredList:
    def test_add_and_get(self, isolated_paths):
        """adding the same path twice keeps single entry"""
        server.add_ignored('/a.mp3')
        server.add_ignored('/a.mp3')
        server.add_ignored('/b.mp3')
        assert sorted(server.get_ignored()) == ['/a.mp3', '/b.mp3']


# ─────────────────────────── stream URL helper ───────────────────────────────

class TestFileToStreamUrl:
    def test_uses_public_url(self, isolated_paths, monkeypatch):
        """builds <publicUrl>/stream/<encoded path>"""
        monkeypatch.setattr(server, 'get_public_url', lambda: 'https://x.example')
        url = server.file_to_stream_url('/mnt/Music/Foo Bar/Track.mp3')
        assert url == 'https://x.example/stream/mnt/Music/Foo%20Bar/Track.mp3'


# ─────────────────────────── streamability check ─────────────────────────────

class TestCanStream:
    def test_missing_file(self, isolated_paths):
        """missing files are not streamable"""
        assert server.can_stream_track('/no/such/file.mp3') is False

    def test_native_mp3(self, isolated_paths, sample_track):
        """real .mp3 from DB is always streamable"""
        if not os.path.isfile(sample_track['path']):
            pytest.skip('sample track file not present')
        assert server.can_stream_track(sample_track['path']) is True

    def test_unsupported_extension(self, isolated_paths, tmp_path):
        """unsupported extension is not streamable"""
        f = tmp_path / 'x.txt'
        f.write_text('hi')
        assert server.can_stream_track(str(f)) is False


# ─────────────────────────── playlist parser ─────────────────────────────────

class TestParseM3U:
    def test_skips_missing_and_comments(self, isolated_paths, tmp_path, sample_track):
        """parse_m3u keeps only existing supported files; skips comments and missing"""
        m3u = tmp_path / 'pl.m3u'
        m3u.write_text(
            "#EXTM3U\n"
            "/no/such/file.mp3\n"
            f"{sample_track['path']}\n"
        )
        out = server.parse_m3u(str(m3u))
        assert sample_track['path'] in out
        assert '/no/such/file.mp3' not in out


# ─────────────────────────── playlist fuzzy scoring ──────────────────────────

class TestPlaylistFuzzy:
    def test_filler_words_stripped(self):
        assert server._pl_tokens('the yacht rock playlist') == ['yacht', 'rock']

    def test_ampersand_normalized(self):
        assert 'and' in server._norm_pl('R&B hits')

    def test_exact_match_scores_one(self):
        assert server._score_playlist('yacht rock', 'Yacht Rock') == 1.0

    def test_spoken_with_filler_beats_unrelated(self):
        yacht = server._score_playlist('mix the yacht rock playlist', 'Yacht Rock')
        daily = server._score_playlist('mix the yacht rock playlist', 'Daily Music')
        assert yacht > daily

    def test_best_playlist_entry_real(self, sample_playlist):
        entry = server.best_playlist_entry(sample_playlist['name'])
        assert entry is not None
        assert entry[1] == sample_playlist['name']


# ─────────────────────────── device groups ───────────────────────────────────

class TestDeviceGroups:
    def test_expand_single_device(self, isolated_paths, monkeypatch):
        monkeypatch.setattr(server, '_alexa_name_for_serial', lambda s: 'Kitchen Show')
        targets = server._expand_play_targets('SERIAL123')
        assert targets == [('SERIAL123', 'Kitchen Show')]

    def test_expand_group(self, isolated_paths):
        gid = 'grp-1'
        server._save_device_groups([{
            'id': gid,
            'name': 'Up and Downstairs',
            'members': [
                {'serial': 'S1', 'name': 'Kitchen Show'},
                {'serial': 'S2', 'name': 'Office Show'},
            ],
        }])
        targets = server._expand_play_targets(f'group:{gid}')
        assert len(targets) == 2
        assert targets[0][0] == 'S1'

    def test_expand_unknown_group_raises(self, isolated_paths):
        with pytest.raises(ValueError, match='group_not_found'):
            server._expand_play_targets('group:missing')


class TestAutoMerge:
    def test_skips_bare_audioplayer_fingerprint(self, isolated_paths):
        store = {
            'kitchen': {'name': 'Kitchen Show', 'lastSeen': time.time(), 'fingerprint': 'AudioPlayer'},
        }
        assert server._auto_merge_target('new-id', 'AudioPlayer', store) is None

    def test_skips_ambiguous_fingerprint(self, isolated_paths):
        now = time.time()
        store = {
            'kitchen': {'name': 'Kitchen Show', 'lastSeen': now, 'fingerprint': 'AudioPlayer,Display'},
            'office':  {'name': 'Office Show',  'lastSeen': now - 3600, 'fingerprint': 'AudioPlayer,Display'},
        }
        assert server._auto_merge_target('new-id', 'AudioPlayer,Display', store) is None
