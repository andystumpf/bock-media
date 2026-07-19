"""Room queue HTTP API — add, approve, visibility in now playing."""
import server


def _seed_household(isolated_paths):
    server._save_household({
        'members': [
            {'id': 'p-parent', 'name': 'Parent', 'role': 'parent', 'pinHash': server._hash_pin('1234')},
            {'id': 'p-jack', 'name': 'Jack', 'role': 'kid'},
        ],
        'clientBindings': {},
        'deviceOwners': [],
    })


def test_add_room_request_approved_by_default(isolated_paths, client, monkeypatch):
    _seed_household(isolated_paths)
    monkeypatch.setattr(server, '_policy_for', lambda _: {'safe': False})

    resp = client.post('/api/rooms/kitchen-echo/requests', json={
        'path': '/music/song.flac',
        'track': 'Test Song',
        'artist': 'Artist',
        'memberId': 'p-jack',
    })
    assert resp.status_code == 201
    body = resp.get_json()
    assert body['status'] == 'approved'
    assert body['track'] == 'Test Song'
    assert body['byMemberName'] == 'Jack'

    queue = client.get('/api/rooms/kitchen-echo/queue').get_json()['queue']
    assert len(queue) == 1
    assert queue[0]['id'] == body['id']


def test_kid_safe_room_queues_until_approved(isolated_paths, client, monkeypatch):
    _seed_household(isolated_paths)
    monkeypatch.setattr(server, '_policy_for', lambda _: {
        'safe': True,
        'requireApproval': True,
        'allowExplicit': True,
    })

    resp = client.post('/api/rooms/kitchen-echo/requests', json={
        'path': '/music/song.flac',
        'track': 'Queued Song',
        'memberId': 'p-jack',
    })
    assert resp.status_code == 201
    assert resp.get_json()['status'] == 'queued'

    rid = resp.get_json()['id']
    approve = client.post(f'/api/rooms/kitchen-echo/requests/{rid}/approve', json={
        'memberId': 'p-parent',
        'pin': '1234',
    })
    assert approve.status_code == 200
    assert approve.get_json()['status'] == 'approved'


def test_concurrent_room_requests(isolated_paths, client, monkeypatch):
    _seed_household(isolated_paths)
    monkeypatch.setattr(server, '_policy_for', lambda _: {'safe': False})
    for i in range(3):
        client.post('/api/rooms/kitchen-echo/requests', json={
            'path': f'/music/song{i}.flac',
            'track': f'Song {i}',
            'memberId': 'p-jack' if i % 2 else 'p-andy',
        })
    queue = client.get('/api/rooms/kitchen-echo/queue').get_json()['queue']
    assert len(queue) == 3
    ids = [r['id'] for r in queue]
    rev = list(reversed(ids))
    client.post('/api/rooms/kitchen-echo/requests/reorder', json={'order': rev})
    after = client.get('/api/rooms/kitchen-echo/queue').get_json()['queue']
    assert [r['id'] for r in after] == rev


def test_nowplaying_includes_upnext(isolated_paths, client, monkeypatch):
    _seed_household(isolated_paths)
    monkeypatch.setattr(server, '_policy_for', lambda _: {'safe': False})
    client.post('/api/rooms/kitchen-echo/requests', json={
        'path': '/music/song.flac',
        'track': 'Kitchen Pick',
        'memberId': 'p-jack',
    })
    import json
    from pathlib import Path
    devices_path = Path(server.DEVICES_PATH)
    devices_path.write_text(json.dumps({
        'kitchen-echo': {'name': 'Kitchen', 'deviceId': 'kitchen-echo'},
    }), encoding='utf-8')
    server._write_all_np({
        'devices': {
            'kitchen-echo': {
                'playing': True,
                'track': 'Now',
                'artist': 'Playing',
                'timestamp': server.time.time(),
                'token': 't1',
            },
        },
    })

    np = client.get('/api/nowplaying_devices').get_json()
    item = next(i for i in np['items'] if i.get('deviceId') == 'kitchen-echo')
    assert len(item.get('upNext') or []) == 1
    assert item['upNext'][0]['track'] == 'Kitchen Pick'


def test_msp_room_key_for_queue_uses_target_serial(isolated_paths, monkeypatch):
    import json
    from pathlib import Path
    qid = 'q-msp-room'
    server._save_queues({
        qid: {'tracks': ['/a.mp3'], 'target_serial': 'SER-KITCHEN', 'ts': server.time.time()},
    })
    Path(server.DEVICES_PATH).write_text(json.dumps({
        'kitchen-echo': {'name': 'Kitchen', 'serial': 'SER-KITCHEN'},
    }), encoding='utf-8')
    assert server._msp_room_key_for_queue(qid) == 'kitchen-echo'


def test_msp_get_next_splices_room_request(isolated_paths, client, monkeypatch, tmp_path):
    _seed_household(isolated_paths)
    monkeypatch.setattr(server, '_policy_for', lambda _: {'safe': False})
    import json
    from pathlib import Path
    Path(server.DEVICES_PATH).write_text(json.dumps({
        'kitchen-echo': {'name': 'Kitchen', 'serial': 'SER-KITCHEN'},
    }), encoding='utf-8')
    main = tmp_path / 'now.flac'
    room = tmp_path / 'room.flac'
    main.write_bytes(b'1')
    room.write_bytes(b'2')
    client.post('/api/rooms/kitchen-echo/requests', json={
        'path': str(room),
        'track': 'Room Pick',
        'memberId': 'p-jack',
    })
    qid = 'q-splice'
    server._save_queues({
        qid: {
            'tracks': [str(main), str(main)],
            'target_serial': 'SER-KITCHEN',
            'ts': server.time.time(),
        },
    })
    room_key = server._msp_room_key_for_queue(qid)
    req_path = server._consume_next_request(room_key)
    assert req_path == str(room)
    tracks = list(server._load_queues()[qid]['tracks'])
    tracks.insert(1, req_path)
    server._update_queue_flags(qid, tracks=tracks)
    assert server._load_queues()[qid]['tracks'][1] == str(room)
