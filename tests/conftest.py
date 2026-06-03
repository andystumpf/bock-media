"""
Shared test fixtures.

Strategy: import server.py once, then monkeypatch its module-level
file path globals to point inside a per-test tmp directory so nothing
under the project (or under the library data dir) is modified.
"""
import os
import sys
import json
import shutil
import sqlite3
import xml.etree.ElementTree as ET

import pytest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

import server  # noqa: E402


# ─────────────────────────── helpers ─────────────────────────────────────────

REAL_DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', '/home/plex/.bockmedia')
REAL_DB_PATH = os.environ.get('OURMEDIA_DB_PATH', '/mnt/bock/Music/music_organizer.db')

# XML files in the data dir we want to copy into tmp so tests can read them.
MMA_XML_FILES = [
    'Preferences.xml',
    'WatchFolders.xml',
    'ServerPlaylists.xml',
    'Devices.xml',
    'PlaylistHistory.xml',
    'Messages.xml',
]


def _seed_mma(tmp_dir):
    os.makedirs(tmp_dir, exist_ok=True)
    for name in MMA_XML_FILES:
        src = os.path.join(REAL_DATA_DIR, name)
        dst = os.path.join(tmp_dir, name)
        if os.path.exists(src):
            shutil.copy2(src, dst)
        else:
            ET.ElementTree(ET.Element('Root')).write(dst, xml_declaration=True, encoding='utf-8')
    os.makedirs(os.path.join(tmp_dir, 'ImageCache'), exist_ok=True)
    return tmp_dir


# ─────────────────────────── fixtures ────────────────────────────────────────

@pytest.fixture
def isolated_paths(tmp_path, monkeypatch):
    """Redirect every writable file path in `server` into tmp_path."""
    state_dir = tmp_path / 'state'
    state_dir.mkdir()
    mma_dir = tmp_path / 'mma'
    _seed_mma(str(mma_dir))

    monkeypatch.setattr(server, 'HERE', str(state_dir))
    monkeypatch.setattr(server, 'DATA_DIR', str(mma_dir))
    monkeypatch.setattr(server, 'DEVICES_PATH', str(state_dir / 'devices.json'))
    monkeypatch.setattr(server, 'STREAM_HISTORY_PATH', str(state_dir / 'streaming_history.jsonl'))
    monkeypatch.setattr(server, 'CONFIG_PATH', str(state_dir / 'config.json'))
    monkeypatch.setattr(server, 'QUEUES_PATH', str(state_dir / 'queues.json'))
    monkeypatch.setattr(server, 'NP_STATE_PATH', str(state_dir / 'nowplaying_state.json'))
    monkeypatch.setattr(server, 'SELECTED_PATH', str(state_dir / 'selected_state.json'))
    monkeypatch.setattr(server, 'IGNORE_PATH', str(state_dir / 'ignored_tracks.json'))
    monkeypatch.setattr(server, 'DEVICE_GROUPS_PATH', str(state_dir / 'device_groups.json'))
    monkeypatch.setattr(server, 'HEALTH_STATE_PATH', str(state_dir / 'health_state.json'))
    monkeypatch.setattr(server, 'LOG_PATH', str(state_dir / 'server.log'))
    return tmp_path


@pytest.fixture
def client(isolated_paths):
    """Flask test client with isolated state."""
    server.app.config['TESTING'] = True
    with server.app.test_client() as c:
        yield c


# Real-DB row probes — used to make Alexa intent tests resilient.

@pytest.fixture(scope='session')
def db_conn():
    if not os.path.exists(REAL_DB_PATH):
        pytest.skip('songs_cache DB not available')
    conn = sqlite3.connect(f'file:{REAL_DB_PATH}?mode=ro', uri=True)
    conn.row_factory = sqlite3.Row
    return conn


@pytest.fixture(scope='session')
def sample_artist(db_conn):
    row = db_conn.execute(
        "SELECT artist FROM songs_cache "
        "WHERE artist IS NOT NULL AND artist != '' "
        "AND path IS NOT NULL AND SUBSTR(path,-4)='.mp3' "
        "GROUP BY artist HAVING COUNT(*) > 1 LIMIT 1"
    ).fetchone()
    if not row:
        pytest.skip('no artist with mp3s in DB')
    return row['artist']


@pytest.fixture(scope='session')
def sample_album(db_conn):
    row = db_conn.execute(
        "SELECT album FROM songs_cache "
        "WHERE album IS NOT NULL AND album != '' "
        "AND path IS NOT NULL AND SUBSTR(path,-4)='.mp3' "
        "GROUP BY album HAVING COUNT(*) > 1 LIMIT 1"
    ).fetchone()
    if not row:
        pytest.skip('no album with mp3s in DB')
    return row['album']


@pytest.fixture(scope='session')
def sample_track(db_conn):
    row = db_conn.execute(
        "SELECT title, artist, path FROM songs_cache "
        "WHERE title IS NOT NULL AND title != '' "
        "AND path IS NOT NULL AND SUBSTR(path,-4)='.mp3' LIMIT 1"
    ).fetchone()
    if not row:
        pytest.skip('no track in DB')
    return dict(row)


@pytest.fixture
def sample_tracks(sample_track):
    """A few real, on-disk track paths for queue/sleep-timer tests."""
    p = sample_track['path']
    return [p, p, p, p]


@pytest.fixture(scope='session')
def sample_playlist():
    """First playlist from real ServerPlaylists.xml whose m3u has at least one playable track."""
    spl = os.path.join(REAL_DATA_DIR, 'ServerPlaylists.xml')
    if not os.path.exists(spl):
        pytest.skip('ServerPlaylists.xml not available')
    tree = ET.parse(spl)
    for e in tree.getroot().findall('Entry'):
        k = e.find('Key')
        if k is None:
            continue
        name = k.findtext('Name')
        src = k.findtext('SourceID')
        if not (name and src and os.path.isfile(src)):
            continue
        # Confirm at least one referenced track actually exists on disk.
        try:
            with open(src) as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    track = line if os.path.isabs(line) else os.path.normpath(os.path.join(os.path.dirname(src), line))
                    if os.path.isfile(track):
                        return {'name': name, 'source': src, 'track': track}
        except Exception:
            continue
    pytest.skip('no playlist with playable tracks found')


@pytest.fixture(autouse=True)
def reset_play_intent_state():
    """Clear play-intent correlation globals so tests don't leak into each other."""
    with server._PLAY_INTENT_LOCK:
        server._PLAY_INTENTS.clear()
        server._PLAY_GROUP_UNTIL = 0.0
    if hasattr(server, '_TEST_SERIALS'):
        server._TEST_SERIALS.clear()
    yield
    with server._PLAY_INTENT_LOCK:
        server._PLAY_INTENTS.clear()
        server._PLAY_GROUP_UNTIL = 0.0
    if hasattr(server, '_TEST_SERIALS'):
        server._TEST_SERIALS.clear()



def _alexa_envelope(rtype, intent=None, slots=None, device_id='amzn1.ask.device.TESTDEVICE',
                   token=None, error=None):
    body = {
        'version': '1.0',
        'context': {
            'System': {
                'device': {
                    'deviceId': device_id,
                    'supportedInterfaces': {'AudioPlayer': {}},
                },
                'application': {'applicationId': server.EXPECTED_SKILL_APP_ID},
                'user': {'userId': 'amzn1.ask.account.TEST'},
            },
            'AudioPlayer': {'playerActivity': 'IDLE'},
        },
        'request': {
            'type': rtype,
            'requestId': 'test-request',
            'locale': 'en-US',
        },
    }
    if intent:
        body['request']['intent'] = {
            'name': intent,
            'confirmationStatus': 'NONE',
            'slots': {
                k: {'name': k, 'value': v, 'confirmationStatus': 'NONE'}
                for k, v in (slots or {}).items()
            },
        }
    if token is not None:
        body['request']['token'] = token
    if error:
        body['request']['error'] = error
    return body


@pytest.fixture
def alexa_request():
    """Returns a builder callable: alexa_request('IntentRequest', 'PlayPlaylistIntent', {'PlaylistName': 'foo'})."""
    return _alexa_envelope


@pytest.fixture
def post_alexa(client, alexa_request):
    """Helper that posts an Alexa envelope and returns parsed JSON."""
    def _do(rtype, intent=None, slots=None, device_id='amzn1.ask.device.TESTDEVICE', token=None, error=None):
        body = alexa_request(rtype, intent, slots, device_id, token, error)
        rv = client.post('/alexa', data=json.dumps(body), content_type='application/json')
        assert rv.status_code == 200, rv.data
        return rv.get_json()
    return _do
