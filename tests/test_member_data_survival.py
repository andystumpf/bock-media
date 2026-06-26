"""Tests for member data backups and phone reinstall binding."""
import json
import time

import bock_member_backup
import server


def test_maybe_backup_rotates(tmp_path):
    path = tmp_path / 'ratings.json'
    path.write_text('{"v": 1}', encoding='utf-8')
    data_dir = tmp_path / 'data'
    bock_member_backup.maybe_backup(str(path), str(data_dir))
    time.sleep(1.1)
    bock_member_backup.maybe_backup(str(path), str(data_dir))
    backups = list((data_dir / 'member_data_backups').glob('ratings.json.*.bak'))
    assert len(backups) >= 2


def test_rebind_client_from_phone(tmp_path, monkeypatch):
    monkeypatch.setattr(server, 'DATA_DIR', str(tmp_path))
    monkeypatch.setattr(server, 'HOUSEHOLD_PATH', str(tmp_path / 'household.json'))
    phone = 'abc123deadbeef'
    (tmp_path / 'household.json').write_text(json.dumps({
        'members': [{'id': 'p-andy', 'name': 'Andy', 'role': 'parent'}],
        'clientBindings': {},
        'deviceOwners': {},
        'phoneBindings': {phone: 'p-andy'},
    }), encoding='utf-8')
    mid = server._rebind_client_from_phone('new-client-uuid', phone)
    assert mid == 'p-andy'
    h = json.loads((tmp_path / 'household.json').read_text(encoding='utf-8'))
    assert h['clientBindings']['client-new-client-uuid'] == 'p-andy'
