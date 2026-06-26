"""Unit tests for bock_client_prefs."""
import bock_client_prefs


def test_member_and_client_merge(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    bock_client_prefs.put_prefs(
        path, member_id='p-andy', client_device_id='client-abc',
        member_prefs={'crossfadeSeconds': 5, 'rememberMe': True},
        client_prefs={'lastDevice': 'Office Echo'},
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-andy', client_device_id='client-abc')
    assert out['merged']['crossfadeSeconds'] == 5
    assert out['merged']['lastDevice'] == 'Office Echo'


def test_reinstall_new_client_same_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    bock_client_prefs.put_prefs(
        path, member_id='p-emma',
        member_prefs={'activeMemberId': 'p-emma', 'downloadWifiOnly': True},
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='client-new')
    assert out['merged']['downloadWifiOnly'] is True
    assert out['merged']['activeMemberId'] == 'p-emma'
