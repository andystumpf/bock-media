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


def test_offline_downloads_persist_per_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    downloads = [
        {'id': 'pl-yacht', 'title': 'Yacht Rock', 'kind': 'playlist', 'sourcePlaylistId': 'yacht'},
        {'id': 'pl-chill', 'title': 'Chill Mix', 'kind': 'playlist', 'sourcePlaylistId': 'chill'},
    ]
    bock_client_prefs.put_prefs(
        path,
        member_id='p-andy',
        member_prefs={'offlineDownloads': downloads},
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-andy', client_device_id='client-new')
    assert out['merged']['offlineDownloads'] == downloads
    emma = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='client-new')
    assert 'offlineDownloads' not in emma['merged']


def test_search_pins_persist_per_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    andy_pins = [{'kind': 'genre', 'title': 'Jazz', 'name': 'Jazz'}]
    bock_client_prefs.put_prefs(
        path,
        member_id='p-andy',
        member_prefs={'searchPins': andy_pins},
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-andy', client_device_id='client-new')
    assert out['merged']['searchPins'] == andy_pins
    emma = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='client-new')
    assert 'searchPins' not in emma['merged']


def test_continue_after_queue_per_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    bock_client_prefs.put_prefs(
        path, member_id='p-andy',
        member_prefs={'continueAfterQueue': 'similar'},
    )
    bock_client_prefs.put_prefs(
        path, member_id='p-emma',
        member_prefs={'continueAfterQueue': 'artist_radio'},
    )
    andy = bock_client_prefs.get_prefs(path, member_id='p-andy', client_device_id='c1')
    emma = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='c2')
    assert andy['merged']['continueAfterQueue'] == 'similar'
    assert emma['merged']['continueAfterQueue'] == 'artist_radio'


def test_home_tile_engagement_per_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    raw = '{"card-a":{"firstSeenMs":1000,"lastSelectedMs":2000}}'
    bock_client_prefs.put_prefs(
        path, member_id='p-andy',
        member_prefs={'homeTileEngagement': raw},
    )
    out = bock_client_prefs.get_prefs(path, member_id='p-andy', client_device_id='c1')
    assert out['merged']['homeTileEngagement'] == raw
    emma = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='c2')
    assert 'homeTileEngagement' not in emma['merged']


def test_library_prefs_per_member(tmp_path):
    path = str(tmp_path / 'client_prefs.json')
    andy = {
        'libraryTab': 'artists',
        'libraryViewMode': 'grid',
        'librarySortBy': 'name',
        'librarySortOrder': 'asc',
    }
    emma = {
        'libraryTab': 'playlists',
        'libraryViewMode': 'list',
        'librarySortBy': 'trackCount',
        'librarySortOrder': 'desc',
    }
    bock_client_prefs.put_prefs(path, member_id='p-andy', member_prefs=andy)
    bock_client_prefs.put_prefs(path, member_id='p-emma', member_prefs=emma)
    out_andy = bock_client_prefs.get_prefs(path, member_id='p-andy', client_device_id='c1')
    out_emma = bock_client_prefs.get_prefs(path, member_id='p-emma', client_device_id='c2')
    assert out_andy['merged']['libraryTab'] == 'artists'
    assert out_andy['merged']['libraryViewMode'] == 'grid'
    assert out_andy['merged']['librarySortBy'] == 'name'
    assert out_emma['merged']['librarySortBy'] == 'trackCount'
    assert out_emma['merged']['librarySortOrder'] == 'desc'
