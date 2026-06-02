#!/usr/bin/env python3
"""Spike: prove the two pieces Phase 6a (Plex write-back) depends on.

  1. file path  -> Plex track ratingKey  (so an AddToPlaylist of a track we
     know by file path can be mapped to a Plex item)
  2. PUT /playlists/<id>/items?uri=server://...  (actually add it to a Plex
     playlist, the write-back primitive)

Read-only by default (lookup + machine id). Pass --write <playlistRatingKey>
to actually add the resolved track to that playlist and then remove it again,
proving the round-trip without leaving a mess.

Usage:
  python3 scripts/plex_twoway_spike.py --path "/srv/music/.../song.mp3"
  python3 scripts/plex_twoway_spike.py --path "..." --write <playlistRatingKey>
"""
import argparse
import os
import re
import sys
from urllib.parse import quote
from urllib.request import urlopen, Request
import xml.etree.ElementTree as ET

PLEX_URL = os.environ.get('OURMEDIA_PLEX_URL', 'http://localhost:32400').rstrip('/')
PREFS = os.environ.get(
    'OURMEDIA_PLEX_PREFS',
    '/var/lib/plexmediaserver/Library/Application Support/Plex Media Server/Preferences.xml')
MUSIC_SECTION = os.environ.get('OURMEDIA_PLEX_MUSIC_SECTION', '12')


def token():
    t = os.environ.get('OURMEDIA_PLEX_TOKEN')
    if t:
        return t
    with open(PREFS, encoding='utf-8', errors='replace') as f:
        m = re.search(r'PlexOnlineToken="([^"]+)"', f.read())
    return m.group(1) if m else None


def _url(path, tok):
    return f'{PLEX_URL}{path}{"&" if "?" in path else "?"}X-Plex-Token={quote(tok)}'


def get(path, tok):
    with urlopen(_url(path, tok), timeout=30) as r:
        return ET.fromstring(r.read())


def put(path, tok):
    req = Request(_url(path, tok), method='PUT')
    with urlopen(req, timeout=30) as r:
        body = r.read()
        return ET.fromstring(body) if body.strip() else None


def delete(path, tok):
    req = Request(_url(path, tok), method='DELETE')
    with urlopen(req, timeout=30) as r:
        r.read()


def machine_id(tok):
    return get('/', tok).get('machineIdentifier')


def track_ratingkey_for_path(path, tok):
    """Find a Plex track whose media Part file == path. Search by the file's
    base title to keep the result set small, then match on exact file path."""
    base = os.path.splitext(os.path.basename(path))[0]
    root = get(f'/library/sections/{MUSIC_SECTION}/search?type=10&query={quote(base)}', tok)
    for tr in root.findall('.//Track'):
        for part in tr.findall('.//Part'):
            if part.get('file') == path:
                return tr.get('ratingKey')
    # Fallback: title may differ from filename; scan parts in the result set.
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--path', required=True, help='Track file path to resolve')
    ap.add_argument('--write', help='Playlist ratingKey to add+remove the track on')
    args = ap.parse_args()

    tok = token()
    if not tok:
        print('FATAL: no Plex token'); return 2
    mid = machine_id(tok)
    print('machineIdentifier:', mid)

    rk = track_ratingkey_for_path(args.path, tok)
    print('track ratingKey:', rk)
    if not rk:
        print('LOOKUP FAILED — could not map file path to a Plex track')
        return 1
    print('LOOKUP OK')

    if not args.write:
        print('(read-only; pass --write <playlistRatingKey> to prove the PUT)')
        return 0

    pl = args.write
    before = int(get(f'/playlists/{pl}', tok).find('Playlist').get('leafCount') or 0)
    uri = f'server://{mid}/com.plexapp.plugins.library/library/metadata/{rk}'
    put(f'/playlists/{pl}/items?uri={quote(uri, safe="")}', tok)
    after = int(get(f'/playlists/{pl}', tok).find('Playlist').get('leafCount') or 0)
    print(f'leafCount before={before} after={after}')
    if after <= before:
        print('PUT did not increase leafCount — add may have failed (or dup)')
        return 1
    print('PUT OK — track added')

    # Clean up: remove the playlist item we just added.
    items = get(f'/playlists/{pl}/items', tok)
    removed = False
    for tr in items.findall('.//Track'):
        if tr.get('ratingKey') == rk:
            item_id = tr.get('playlistItemID')
            if item_id:
                delete(f'/playlists/{pl}/items/{item_id}', tok)
                removed = True
                break
    print('cleanup removed test item:', removed)
    return 0


if __name__ == '__main__':
    sys.exit(main())
