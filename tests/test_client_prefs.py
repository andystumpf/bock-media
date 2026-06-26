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
    # Legacy client write promotes to member for reinstall survival.
    assert out['memberPrefs']['lastDevice'] == 'Office Echo'


def test_reinstall_new_client_same_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    bock_client_prefs.put_prefs(
        path, member_id='p-emma',
        member_prefs={
            'activeMemberId': 'p-emma',
            'downloadWifiOnly': True,
            'lastDevice': 'Kitchen Echo',
            'pinnedDevices': ['Kitchen Echo', 'Office Echo'],
        },
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='client-new')
    assert out['merged']['downloadWifiOnly'] is True
    assert out['merged']['activeMemberId'] == 'p-emma'
    assert out['merged']['lastDevice'] == 'Kitchen Echo'
    assert out['merged']['pinnedDevices'] == ['Kitchen Echo', 'Office Echo']


def test_member_prefs_win_over_client(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    bock_client_prefs.put_prefs(
        path, member_id='p-a', client_device_id='c-old',
        member_prefs={'lastDevice': 'Bedroom'},
        client_prefs={'lastDevice': 'Kitchen'},
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-a', client_device_id='c-old')
    assert out['merged']['lastDevice'] == 'Bedroom'
