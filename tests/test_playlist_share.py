"""Playlist sharing — visibility and share API."""
import server


def test_share_playlist_makes_visible_to_recipient(isolated_paths, client, monkeypatch):
    pid = 'pregame-mix-id'
    server._save_household({
        'members': [
            {'id': 'p-parent', 'name': 'Parent', 'role': 'parent'},
            {'id': 'p-guest', 'name': 'Guest', 'role': 'parent'},
            {'id': 'p-teen', 'name': 'Teen', 'role': 'kid'},
        ],
        'clientBindings': {},
        'deviceOwners': {},
    })
    monkeypatch.setattr(server, '_msp_playlist_by_id', lambda _: ('Pregame Mix', '/fake.m3u'))

    resp = client.post(f'/api/playlists/{pid}/share', json={
        'toMemberIds': ['p-guest'],
        'memberId': 'p-parent',
    })
    assert resp.status_code == 200
    body = resp.get_json()
    assert body['ok'] is True
    assert 'p-guest' in body['sharedWith']

    meta = server._load_playlist_meta()[pid]
    assert meta['ownerMemberId'] == 'p-parent'
    assert meta['visibility'] == 'shared'
    assert server._playlist_visible_to(meta, 'p-guest')
    assert not server._playlist_visible_to(meta, 'p-teen')


def test_private_daily_mix_hidden_from_other_members(isolated_paths):
    meta = {
        'ownerMemberId': 'p-parent',
        'visibility': 'private',
        'daily': True,
    }
    assert server._playlist_visible_to(meta, 'p-parent')
    assert not server._playlist_visible_to(meta, 'p-guest')
