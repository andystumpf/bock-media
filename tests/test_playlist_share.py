"""Playlist sharing — visibility and share API."""
import server


def test_share_playlist_makes_visible_to_recipient(isolated_paths, client, monkeypatch):
    pid = 'pregame-mix-id'
    server._save_household({
        'members': [
            {'id': 'p-andy', 'name': 'Andy', 'role': 'parent'},
            {'id': 'p-jack', 'name': 'Jack', 'role': 'parent'},
            {'id': 'p-emma', 'name': 'Emma', 'role': 'kid'},
        ],
        'clientBindings': {},
        'deviceOwners': {},
    })
    monkeypatch.setattr(server, '_msp_playlist_by_id', lambda _: ('Pregame Mix', '/fake.m3u'))

    resp = client.post(f'/api/playlists/{pid}/share', json={
        'toMemberIds': ['p-jack'],
        'memberId': 'p-andy',
    })
    assert resp.status_code == 200
    body = resp.get_json()
    assert body['ok'] is True
    assert 'p-jack' in body['sharedWith']

    meta = server._load_playlist_meta()[pid]
    assert meta['ownerMemberId'] == 'p-andy'
    assert meta['visibility'] == 'shared'
    assert server._playlist_visible_to(meta, 'p-jack')
    assert not server._playlist_visible_to(meta, 'p-emma')


def test_private_daily_mix_hidden_from_other_members(isolated_paths):
    meta = {
        'ownerMemberId': 'p-andy',
        'visibility': 'private',
        'daily': True,
    }
    assert server._playlist_visible_to(meta, 'p-andy')
    assert not server._playlist_visible_to(meta, 'p-jack')
