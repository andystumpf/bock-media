#!/usr/bin/env python3
"""Merge duplicate 'Best ITALIAN hits of all time' playlists on the NAS."""
from __future__ import annotations

import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path

DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', '/home/plex/.bockmedia')
MUSIC_ROOT = os.environ.get('OURMEDIA_MUSIC_ROOT', '/mnt/bock/Music')
XML = os.path.join(DATA_DIR, 'ServerPlaylists.xml')
FILE_M3U = os.path.join(MUSIC_ROOT, 'exportedPlaylists', 'Best ITALIAN hits of all time.m3u')
PLEX_M3U = os.path.join(MUSIC_ROOT, 'exportedPlaylists', 'plex', 'Best ITALIAN hits of all time__.2118385.m3u')
FILE_ID = 'dfac46d3-5aac-493c-9e02-c3aaa3eee435'
PLEX_ID = '1c36c5e5-8e3b-5284-8257-6f2517d8a45f'
PLEX_RATING_KEY = 2118385
CANONICAL_NAME = 'Best Italian hits of all time'
PLEX_PREFS = os.environ.get(
    'OURMEDIA_PLEX_PREFS',
    '/var/lib/plexmediaserver/Library/Application Support/Plex Media Server/Preferences.xml',
)
PLEX_URL = os.environ.get('OURMEDIA_PLEX_URL', 'http://127.0.0.1:32400')


def log(msg: str) -> None:
    print(msg, flush=True)


def plex_token() -> str:
    tok = os.environ.get('OURMEDIA_PLEX_TOKEN')
    if tok:
        return tok
    text = Path(PLEX_PREFS).read_text(encoding='utf-8', errors='replace')
    m = re.search(r'PlexOnlineToken="([^"]+)"', text)
    if not m:
        raise SystemExit('No Plex token')
    return m.group(1)


def read_m3u(path: str) -> list[str]:
    paths: list[str] = []
    for line in Path(path).read_text(encoding='utf-8', errors='replace').splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            paths.append(line)
    return paths


def write_m3u(path: str, tracks: list[str]) -> None:
    with open(path, 'w', encoding='utf-8') as f:
        f.write('#EXTM3U\n')
        for track in tracks:
            f.write(track + '\n')


def merge_paths(*sources: str) -> list[str]:
    merged: list[str] = []
    seen: set[str] = set()
    for src in sources:
        for path in read_m3u(src):
            if path in seen:
                continue
            if not os.path.isfile(path):
                log(f'  skip missing: {path}')
                continue
            seen.add(path)
            merged.append(path)
    return merged


def update_server_playlists_xml(track_count: int) -> None:
    backup = XML + '.bak-merge-' + datetime.now().strftime('%Y%m%d%H%M%S')
    shutil.copy2(XML, backup)
    log(f'Backed up {XML} -> {backup}')

    tree = ET.parse(XML)
    root = tree.getroot()
    removed = False
    for entry in list(root.findall('Entry')):
        key = entry.find('Key')
        if key is None:
            continue
        pid = (key.findtext('ID') or '').strip()
        if pid == FILE_ID:
            root.remove(entry)
            removed = True
            log(f'Removed file-source entry {FILE_ID}')
            continue
        if pid == PLEX_ID:
            for tag, val in (
                ('Name', CANONICAL_NAME),
                ('TrackCount', str(track_count)),
                ('LastUsed', datetime.now().astimezone().isoformat()),
            ):
                el = key.find(tag)
                if el is None:
                    el = ET.SubElement(key, tag)
                el.text = val
            log(f'Updated plex entry {PLEX_ID} -> {CANONICAL_NAME} ({track_count} tracks)')

    if not removed:
        log(f'WARNING: file entry {FILE_ID} not found in XML')

    tree.write(XML, encoding='utf-8', xml_declaration=True)


def resolve_plex_track(plex, path: str):
    base = os.path.basename(path)
    title = os.path.splitext(base)[0]
    for query in (title, base):
        hits = plex.search(query, mediatype='track', limit=20)
        for hit in hits:
            part = hit.media[0].parts[0] if hit.media else None
            if part and part.file == path:
                return hit
            if part and os.path.basename(part.file or '') == base:
                return hit
    return None


def sync_plex_playlist(merged: list[str]) -> None:
    from plexapi.server import PlexServer

    plex = PlexServer(PLEX_URL, plex_token())
    playlist = plex.fetchItem(PLEX_RATING_KEY)
    old_title = playlist.title
    if old_title != CANONICAL_NAME:
        playlist.editTitle(CANONICAL_NAME)
        log(f'Renamed Plex playlist {PLEX_RATING_KEY}: {old_title!r} -> {CANONICAL_NAME!r}')

    existing = {t.ratingKey for t in playlist.items()}
    to_add = []
    for path in merged:
        track = resolve_plex_track(plex, path)
        if track is None:
            log(f'  Plex track not found for {path}')
            continue
        if track.ratingKey not in existing:
            to_add.append(track)
            existing.add(track.ratingKey)

    if to_add:
        playlist.addItems(to_add)
        log(f'Added {len(to_add)} tracks to Plex playlist {PLEX_RATING_KEY}')
    else:
        log('Plex playlist already contains all resolvable merged tracks')

    # Remove duplicate Plex playlist with plain name (if Spotify sync created one)
    for pl in plex.playlists():
        title = (pl.title or '').strip()
        if title == 'Best ITALIAN hits of all time' and pl.ratingKey != PLEX_RATING_KEY:
            log(f'Deleting duplicate Plex playlist {pl.ratingKey} ({title!r})')
            pl.delete()


def cleanup_file_exports() -> None:
    for path in (FILE_M3U, FILE_M3U + '.bak'):
        if os.path.isfile(path):
            os.remove(path)
            log(f'Deleted {path}')


def main() -> int:
    if not os.path.isfile(PLEX_M3U):
        log(f'Missing plex m3u: {PLEX_M3U}')
        return 1
    sources = [PLEX_M3U]
    if os.path.isfile(FILE_M3U):
        sources.append(FILE_M3U)
    merged = merge_paths(*sources)
    log(f'Merged {len(merged)} unique tracks')

    write_m3u(PLEX_M3U, merged)
    log(f'Wrote {PLEX_M3U}')

    update_server_playlists_xml(len(merged))
    cleanup_file_exports()
    sync_plex_playlist(merged)

    # Refresh plex sync state so cron does not revert title
    state_path = os.path.join(MUSIC_ROOT, 'exportedPlaylists', 'plex', '.plex_sync_state.json')
    if os.path.isfile(state_path):
        import json

        state = json.loads(Path(state_path).read_text(encoding='utf-8'))
        old_key = str(PLEX_RATING_KEY)
        if old_key in state:
            state[old_key]['title'] = CANONICAL_NAME
            state[old_key]['leafCount'] = len(merged)
            Path(state_path).write_text(json.dumps(state, indent=2) + '\n', encoding='utf-8')
            log('Updated .plex_sync_state.json title/count')

    log('Done.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
