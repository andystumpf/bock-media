"""Fast-path caches for playlist catalog and library summary stats."""
import json
import os
import tempfile
import time

PLAYLISTS_INDEX_NAME = 'playlists_index.json'
SUMMARY_CACHE_NAME = 'summary_cache.json'
SUMMARY_TTL = 60.0


def playlists_index_path(data_dir):
    return os.path.join(data_dir, PLAYLISTS_INDEX_NAME)


def summary_cache_path(data_dir):
    return os.path.join(data_dir, SUMMARY_CACHE_NAME)


def _atomic_json_write(path, payload):
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path) or '.', prefix='.cache-', suffix='.tmp')
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(payload, f, separators=(',', ':'))
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def write_playlists_index(data_dir, items, xml_mtime=None):
    """Persist playlist metadata sidecar (written after XML changes)."""
    path = playlists_index_path(data_dir)
    mtime = xml_mtime
    if mtime is None:
        xml_path = os.path.join(data_dir, 'ServerPlaylists.xml')
        try:
            mtime = os.path.getmtime(xml_path)
        except OSError:
            mtime = time.time()
    _atomic_json_write(path, {'mtime': mtime, 'items': items})


def read_playlists_index(data_dir, xml_path):
    """Return items list if sidecar matches XML mtime, else None."""
    idx_path = playlists_index_path(data_dir)
    try:
        xml_mtime = os.path.getmtime(xml_path)
        with open(idx_path, encoding='utf-8') as f:
            data = json.load(f)
        if data.get('mtime') == xml_mtime and isinstance(data.get('items'), list):
            return data['items']
    except (OSError, json.JSONDecodeError, TypeError, ValueError):
        pass
    return None


def read_summary_cache(data_dir):
    path = summary_cache_path(data_dir)
    try:
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
        if (time.time() - float(data.get('ts') or 0)) < SUMMARY_TTL:
            return data.get('stats')
    except (OSError, json.JSONDecodeError, TypeError, ValueError):
        pass
    return None


def write_summary_cache(data_dir, stats):
    _atomic_json_write(summary_cache_path(data_dir), {'ts': time.time(), 'stats': stats})


def rebuild_playlists_index_from_xml(data_dir, xml_path=None):
    """Parse ServerPlaylists.xml and write the sidecar index."""
    import xml.etree.ElementTree as ET

    from playlist_xml_lock import playlist_xml_lock

    xml_path = xml_path or os.path.join(data_dir, 'ServerPlaylists.xml')
    with playlist_xml_lock(data_dir, shared=True):
        tree = ET.parse(xml_path)
    items = []
    for entry in tree.getroot().findall('Entry'):
        key = entry.find('Key')
        if key is None:
            continue
        source = key.findtext('SourceID') or ''
        try:
            track_count = int(key.findtext('TrackCount') or 0)
        except (TypeError, ValueError):
            track_count = 0
        items.append({
            'id': key.findtext('ID') or '',
            'name': key.findtext('Name') or '',
            'trackCount': track_count,
            'shuffle': (key.findtext('Shuffle') or '') == 'true',
            'loop': (key.findtext('Loop') or '') == 'true',
            'createDate': key.findtext('CreateDate') or '',
            'lastUsed': key.findtext('LastUsed') or '',
            'source': source,
            'sourceName': key.findtext('SourceName') or '',
            'isAudioBook': (key.findtext('IsAudioBook') or '') == 'true',
            'editable': bool(source and os.path.isfile(source)),
        })
    write_playlists_index(data_dir, items, os.path.getmtime(xml_path))
    return items
