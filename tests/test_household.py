"""
Household plan tests: profiles, attribution, kid-safe policies, requests,
playlist visibility, and messages. Pure logic plus light file-backed flows.

Each test names the contract it verifies in its docstring.
"""
import datetime

import pytest

import server


# ─────────────────────────────── PIN hashing ─────────────────────────────────

class TestPin:
    def test_round_trip(self):
        """a hashed PIN verifies against the original and nothing else"""
        h = server._hash_pin('1234')
        assert server._verify_pin('1234', h)
        assert not server._verify_pin('0000', h)

    def test_garbage_hash_is_safe(self):
        """malformed stored hash never raises, returns False"""
        assert server._verify_pin('1234', 'nonsense') is False
        assert server._verify_pin('1234', '') is False


# ─────────────────────────── member id generation ────────────────────────────

class TestMemberIds:
    def test_slug(self):
        assert server._slug("Emma's Room") == 'emma-s-room'
        assert server._slug('   ') == 'member'

    def test_unique(self):
        """duplicate names get distinct ids"""
        members = [{'id': 'p-jack'}, {'id': 'p-jack-2'}]
        assert server._gen_member_id('Jack', members) == 'p-jack-3'
        assert server._gen_member_id('Andy', members) == 'p-andy'


# ─────────────────────────────── quiet hours ─────────────────────────────────

class TestQuietHours:
    def test_normal_window(self):
        """inside a daytime window matches, outside does not"""
        pol = {'quietHours': [{'days': [0, 1, 2, 3, 4, 5, 6], 'from': '13:00', 'to': '14:00'}]}
        assert server._in_quiet_hours(pol, datetime.datetime(2026, 6, 20, 13, 30))
        assert not server._in_quiet_hours(pol, datetime.datetime(2026, 6, 20, 12, 30))

    def test_overnight_window(self):
        """a window crossing midnight matches before and after 00:00"""
        pol = {'quietHours': [{'days': list(range(7)), 'from': '20:30', 'to': '07:00'}]}
        assert server._in_quiet_hours(pol, datetime.datetime(2026, 6, 20, 22, 0))
        assert server._in_quiet_hours(pol, datetime.datetime(2026, 6, 20, 6, 0))
        assert not server._in_quiet_hours(pol, datetime.datetime(2026, 6, 20, 12, 0))

    def test_day_scoped(self):
        """window only applies on listed weekdays"""
        # 2026-06-20 is a Saturday (weekday 5)
        pol = {'quietHours': [{'days': [0], 'from': '00:00', 'to': '23:59'}]}
        assert not server._in_quiet_hours(pol, datetime.datetime(2026, 6, 20, 12, 0))


# ──────────────────────────── policy enforcement ─────────────────────────────

class TestPolicyCheck:
    def test_unsafe_room_allows_all(self):
        ok, reason = server._policy_check_play({'safe': False}, kind='playlist', playlist_id='x')
        assert ok and reason == ''

    def test_allowlist(self):
        """safe room only permits whitelisted playlists"""
        pol = {'safe': True, 'allowPlaylistIds': ['good']}
        ok, _ = server._policy_check_play(pol, kind='playlist', playlist_id='good')
        assert ok
        ok, reason = server._policy_check_play(pol, kind='playlist', playlist_id='bad')
        assert not ok and reason == 'not_in_allowlist'

    def test_allowlist_blocks_non_playlist(self):
        """safe room with an allow-list refuses ad-hoc song/artist plays"""
        pol = {'safe': True, 'allowPlaylistIds': ['good']}
        ok, reason = server._policy_check_play(pol, kind='song', path='/x.flac')
        assert not ok and reason == 'not_in_allowlist'

    def test_quiet_hours_blocks(self):
        pol = {'safe': True, 'quietHours': [{'days': list(range(7)), 'from': '00:00', 'to': '23:59'}]}
        ok, reason = server._policy_check_play(pol, kind='playlist', playlist_id='x',
                                               now=datetime.datetime(2026, 6, 20, 12, 0))
        assert not ok and reason == 'quiet_hours'


class TestVolumeClamp:
    def test_clamp(self, isolated_paths):
        """a capped safe room clamps requested volume; uncapped passes through"""
        server._save_room_policies({'echo-1': {'safe': True, 'maxVolume': 60}})
        assert server._clamp_volume_for('echo-1', 80) == (60, True)
        assert server._clamp_volume_for('echo-1', 50) == (50, False)
        assert server._clamp_volume_for('echo-other', 80) == (80, False)


# ─────────────────────────────── attribution ─────────────────────────────────

class TestAttribution:
    def test_room_name_inference(self):
        members = [
            {'id': 'p-emma', 'name': 'Emma', 'role': 'kid'},
            {'id': 'p-ethan', 'name': 'Ethan', 'role': 'kid'},
            {'id': 'p-noah', 'name': 'Noah', 'role': 'kid'},
        ]
        assert server._member_id_for_room_name("Emma's Room", members) == 'p-emma'
        assert server._member_id_for_room_name("Ethan's Echo Dot", members) == 'p-ethan'
        assert server._member_id_for_room_name("Noah's Bedroom", members) == 'p-noah'
        assert server._member_id_for_room_name('Kitchen Show', members) is None

    def test_sync_default_room_owners(self, isolated_paths):
        h = {
            'members': [
                {'id': 'p-emma', 'name': 'Emma', 'role': 'kid'},
                {'id': 'p-ethan', 'name': 'Ethan', 'role': 'kid'},
            ],
            'clientBindings': {},
            'deviceOwners': {},
        }
        store = {
            'echo-emma': {'name': "Emma's Room"},
            'echo-ethan': {'name': "Ethan's Echo Dot"},
            'echo-kitchen': {'name': 'Kitchen Show'},
        }
        assert server._sync_default_room_owners(h, store) is True
        assert h['deviceOwners']['echo-emma'] == 'p-emma'
        assert h['deviceOwners']['echo-ethan'] == 'p-ethan'
        assert 'echo-kitchen' not in h['deviceOwners']

    def test_priority(self, isolated_paths):
        """explicit > phone install > room default"""
        server._save_household({
            'members': [{'id': 'p-andy', 'name': 'Andy', 'role': 'parent'},
                        {'id': 'p-emma', 'name': 'Emma', 'role': 'kid'}],
            'clientBindings': {'client-abc': 'p-andy'},
            'deviceOwners': {'echo-emma': 'p-emma'},
        })
        # explicit wins
        assert server.resolve_play_member(device_id='echo-emma', client_id='abc',
                                          explicit_member='p-andy') == 'p-andy'
        # phone install over room
        assert server.resolve_play_member(device_id='echo-emma', client_id='abc') == 'p-andy'
        # room default
        assert server.resolve_play_member(device_id='echo-emma') == 'p-emma'
        # unknown -> empty
        assert server.resolve_play_member(device_id='echo-nope') == ''

    def test_row_member_infers_owner(self, isolated_paths):
        """a history row without memberId resolves via the device owner"""
        server._save_household({'members': [{'id': 'p-emma', 'name': 'Emma', 'role': 'kid'}],
                                'clientBindings': {}, 'deviceOwners': {'echo-emma': 'p-emma'}})
        assert server._row_member({'deviceId': 'echo-emma'}) == 'p-emma'
        assert server._row_member({'deviceId': 'echo-emma', 'memberId': 'p-x'}) == 'p-x'


# ──────────────────────────── playlist visibility ────────────────────────────

class TestVisibility:
    def test_legacy_is_household_visible(self):
        assert server._playlist_visible_to(None, 'p-jack')

    def test_private(self):
        meta = {'ownerMemberId': 'p-andy', 'visibility': 'private'}
        assert server._playlist_visible_to(meta, 'p-andy')
        assert not server._playlist_visible_to(meta, 'p-jack')

    def test_shared(self):
        meta = {'ownerMemberId': 'p-andy', 'visibility': 'shared', 'sharedWith': ['p-jack']}
        assert server._playlist_visible_to(meta, 'p-jack')
        assert not server._playlist_visible_to(meta, 'p-emma')


# ─────────────────────────── request queue (P3) ──────────────────────────────

class TestRequests:
    def test_consume_fifo(self, isolated_paths):
        """approved requests are consumed in order; queued ones are skipped"""
        server._save_requests({'rooms': {'echo-1': {'queue': [
            {'id': 'a', 'path': '/1.flac', 'status': 'queued'},
            {'id': 'b', 'path': '/2.flac', 'status': 'approved'},
            {'id': 'c', 'path': '/3.flac', 'status': 'approved'},
        ]}}})
        assert server._consume_next_request('echo-1') == '/2.flac'
        assert server._consume_next_request('echo-1') == '/3.flac'
        assert server._consume_next_request('echo-1') is None  # only 'queued' left

    def test_upnext_excludes_done(self, isolated_paths):
        server._save_requests({'rooms': {'echo-1': {'queue': [
            {'id': 'a', 'path': '/1.flac', 'status': 'done'},
            {'id': 'b', 'path': '/2.flac', 'status': 'approved'},
        ]}}})
        up = server._room_upnext_public('echo-1')
        assert [r['id'] for r in up] == ['b']


# ─────────────────────────────── messages (P5) ───────────────────────────────

class TestMessages:
    def test_post_and_visibility(self, isolated_paths):
        """direct messages are visible to sender and recipient only"""
        server._post_message(from_member='p-jack', to_member='p-andy',
                             scope='direct', text='pregame')
        msgs = server._read_messages()
        assert len(msgs) == 1
        m = msgs[0]
        assert server._message_visible_to(m, 'p-andy')
        assert server._message_visible_to(m, 'p-jack')
        assert not server._message_visible_to(m, 'p-emma')

    def test_household_visible_to_all(self):
        m = {'scope': 'household', 'fromMemberId': 'p-andy'}
        assert server._message_visible_to(m, 'p-emma')
