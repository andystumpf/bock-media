"""Server-backed mobile preferences — per household member and per install."""
import json
import os
import threading
import time

PREFS_VERSION = 1
_MEMBER_KEYS = frozenset({
    'searchAllLibraries',
    'searchSourcePath',
    'downloadWifiOnly',
    'crossfadeSeconds',
    'continueAfterQueue',
    'rememberMe',
    'activeMemberId',
    'searchSelections',
    'homeTileEngagement',
    'lastDevice',
    'pinnedDevices',
    'offlineDownloads',
    'searchPins',
    'librarySortBy',
    'librarySortOrder',
    'libraryViewMode',
    'libraryTab',
})
_CLIENT_KEYS = frozenset()
_DEVICE_PREF_KEYS = frozenset({'lastDevice', 'pinnedDevices'})

_LOCK = threading.Lock()


def prefs_path(data_dir):
    return os.path.join(data_dir, 'client_prefs.json')


def _load(path):
    with _LOCK:
        try:
            with open(path, encoding='utf-8') as fh:
                data = json.load(fh)
            if not isinstance(data, dict):
                return {'members': {}, 'clients': {}}
            data.setdefault('members', {})
            data.setdefault('clients', {})
            return data
        except OSError:
            return {'members': {}, 'clients': {}}
        except json.JSONDecodeError:
            return {'members': {}, 'clients': {}}


def _save(path, data, atomic_write):
    payload = {
        'members': data.get('members') or {},
        'clients': data.get('clients') or {},
    }
    with _LOCK:
        if atomic_write:
            atomic_write(path, payload)
        else:
            os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
            with open(path, 'w', encoding='utf-8') as fh:
                json.dump(payload, fh, indent=2)


def _bucket(data, scope, key):
    root = data.setdefault(scope, {})
    row = root.get(key)
    if not isinstance(row, dict):
        row = {'prefs': {}, 'updatedAt': 0}
        root[key] = row
    if not isinstance(row.get('prefs'), dict):
        row['prefs'] = {}
    return row


def _merge(member_prefs, client_prefs):
    """Member profile prefs win over legacy per-install client keys."""
    merged = dict(client_prefs or {})
    merged.update(member_prefs or {})
    return merged


def _promote_legacy_device_prefs(data, member_id, client_prefs):
    """Copy lastDevice/pinnedDevices from a client bucket onto the member once."""
    if not member_id or not isinstance(client_prefs, dict):
        return False
    member_row = _bucket(data, 'members', member_id)
    mp = member_row['prefs']
    changed = False
    for key in _DEVICE_PREF_KEYS:
        if key in client_prefs and key not in mp:
            mp[key] = client_prefs[key]
            changed = True
    if changed:
        member_row['updatedAt'] = time.time()
    return changed


def get_prefs(path, member_id=None, client_device_id=None):
    data = _load(path)
    member_prefs = {}
    client_prefs = {}
    if member_id:
        row = (data.get('members') or {}).get(member_id) or {}
        member_prefs = row.get('prefs') if isinstance(row.get('prefs'), dict) else {}
    if client_device_id:
        row = (data.get('clients') or {}).get(client_device_id) or {}
        client_prefs = row.get('prefs') if isinstance(row.get('prefs'), dict) else {}
        if _promote_legacy_device_prefs(data, member_id, client_prefs):
            _save(path, data, None)
            row = (data.get('members') or {}).get(member_id) or {}
            member_prefs = row.get('prefs') if isinstance(row.get('prefs'), dict) else {}
    return {
        'v': PREFS_VERSION,
        'memberId': member_id or None,
        'clientDeviceId': client_device_id or None,
        'memberPrefs': member_prefs,
        'clientPrefs': client_prefs,
        'merged': _merge(member_prefs, client_prefs),
    }


def put_prefs(path, member_id=None, client_device_id=None,
              member_prefs=None, client_prefs=None, atomic_write=None):
    if not member_id and not client_device_id:
        raise ValueError('memberId or clientDeviceId required')
    data = _load(path)
    now = time.time()
    if member_id and isinstance(member_prefs, dict):
        row = _bucket(data, 'members', member_id)
        cleaned = {k: member_prefs[k] for k in _MEMBER_KEYS if k in member_prefs}
        row['prefs'].update(cleaned)
        row['updatedAt'] = now
    if client_device_id and isinstance(client_prefs, dict):
        row = _bucket(data, 'clients', client_device_id)
        cleaned = {k: client_prefs[k] for k in _CLIENT_KEYS if k in client_prefs}
        if cleaned:
            row['prefs'].update(cleaned)
            row['updatedAt'] = now
        if member_id:
            member_row = _bucket(data, 'members', member_id)
            for key in _DEVICE_PREF_KEYS:
                if key in client_prefs and key not in member_row['prefs']:
                    member_row['prefs'][key] = client_prefs[key]
            member_row['updatedAt'] = now
    _save(path, data, atomic_write)
    return get_prefs(path, member_id=member_id, client_device_id=client_device_id)
