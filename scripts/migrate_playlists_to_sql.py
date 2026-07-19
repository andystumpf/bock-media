#!/usr/bin/env python3
"""Import bockmedia playlists from m3u into playlist_tracks (phase 0 migration)."""
import argparse
import os
import sys
import xml.etree.ElementTree as ET

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO not in sys.path:
    sys.path.insert(0, REPO)

import bock_playlists
import server


def _xml_text(el, tag):
    child = el.find(tag)
    return (child.text or '').strip() if child is not None else ''


def main():
    parser = argparse.ArgumentParser(description='Migrate bockmedia playlists to SQL storage')
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    bock_playlists.ensure_schema(server.get_db_rw)
    tree = ET.parse(server.PLAYLISTS_XML)
    root = tree.getroot()
    migrated = skipped = 0

    for entry in root.findall('Entry'):
        key = entry.find('Key')
        if key is None:
            continue
        source_name = _xml_text(key, 'SourceName').lower()
        if source_name != 'bockmedia':
            continue
        pid = _xml_text(key, 'Key')
        name = _xml_text(key, 'Name')
        m3u = _xml_text(key, 'SourceID')
        if not pid or not m3u or not os.path.isfile(m3u):
            skipped += 1
            continue
        paths = server.parse_m3u(m3u, verify_exists=False)
        if args.dry_run:
            print(f'[dry-run] {name} ({pid}): {len(paths)} tracks')
            migrated += 1
            continue
        from playlist_xml_lock import playlist_xml_lock
        with playlist_xml_lock(server.DATA_DIR, shared=True):
            bock_playlists.import_from_m3u(
                server.get_db_rw, pid, name, m3u, paths, source_kind='bockmedia',
            )
        print(f'migrated {name} ({pid}): {len(paths)} tracks')
        migrated += 1

    print(f'done: migrated={migrated} skipped={skipped}')


if __name__ == '__main__':
    main()
