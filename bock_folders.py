"""Playlist folder organization (separate from ServerPlaylists.xml)."""
import json
import os
import threading
import uuid

_LOCK = threading.Lock()


def load_folders(path):
    with _LOCK:
        if not os.path.isfile(path):
            return {'folders': [], 'assignments': {}}
        try:
            with open(path) as f:
                data = json.load(f)
            if not isinstance(data, dict):
                return {'folders': [], 'assignments': {}}
            data.setdefault('folders', [])
            data.setdefault('assignments', {})
            return data
        except Exception:
            return {'folders': [], 'assignments': {}}


def save_folders(path, data):
    with _LOCK:
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        tmp = path + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, path)


def folder_tree(data):
    folders = sorted(data.get('folders') or [], key=lambda f: (f.get('order', 0), f.get('name', '')))
    assignments = data.get('assignments') or {}
    return {'folders': folders, 'assignments': assignments}


def create_folder(data, name, parent_id=None):
    name = (name or '').strip()
    if not name:
        return None, 'name required'
    folders = data.get('folders') or []
    if parent_id:
        parent = next((f for f in folders if f.get('id') == parent_id), None)
        if not parent:
            return None, 'parent not found'
        if parent.get('parentId'):
            return None, 'max depth exceeded'
    fid = 'f-' + str(uuid.uuid4())[:8]
    order = max([f.get('order', 0) for f in folders] + [-1]) + 1
    entry = {'id': fid, 'name': name, 'parentId': parent_id, 'order': order}
    folders.append(entry)
    data['folders'] = folders
    return entry, None


def update_folder(data, folder_id, body):
    folders = data.get('folders') or []
    entry = next((f for f in folders if f.get('id') == folder_id), None)
    if not entry:
        return None, 'not found'
    if 'name' in body and body.get('name'):
        entry['name'] = str(body['name']).strip()
    if 'order' in body:
        entry['order'] = int(body['order'])
    if 'parentId' in body:
        pid = body.get('parentId')
        if pid:
            parent = next((f for f in folders if f.get('id') == pid), None)
            if not parent or parent.get('parentId'):
                return None, 'invalid parent'
        entry['parentId'] = pid
    return entry, None


def delete_folder(data, folder_id):
    folders = [f for f in (data.get('folders') or []) if f.get('id') != folder_id]
    data['folders'] = folders
    assignments = data.get('assignments') or {}
    for pid, fid in list(assignments.items()):
        if fid == folder_id:
            assignments.pop(pid, None)
    data['assignments'] = assignments
    return True


def assign_playlist(data, playlist_id, folder_id):
    assignments = data.get('assignments') or {}
    if folder_id:
        assignments[str(playlist_id)] = str(folder_id)
    else:
        assignments.pop(str(playlist_id), None)
    data['assignments'] = assignments


def enrich_playlist_item(item, assignments):
    item = dict(item)
    item['folderId'] = assignments.get(item.get('id'))
    return item
