#!/usr/bin/env python3
"""Prune dead tracks from Bock Media playlists; optionally drop a playlist from the catalog.

Reads check_playlist_playability.py report (status\\tpath\\tpl1|pl2…).
Does not modify excluded playlists' .m3u files (default: "Ethan and dad movies").

  python3 scripts/prune_unplayable.py --report /tmp/unplayable.txt --dry-run
  python3 scripts/prune_unplayable.py --report /tmp/unplayable.txt --fix
  python3 scripts/prune_unplayable.py --drop-playlist "Ethan and dad movies" --fix

Config (env): OURMEDIA_DATA_DIR, OURMEDIA_DB_PATH
"""
import argparse
import datetime as _dt
import os
import shutil
import sys
import xml.etree.ElementTree as ET

DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', '/home/plex/.bockmedia')
SERVER_PLAYLISTS = os.path.join(DATA_DIR, 'ServerPlaylists.xml')

# Never edit these playlists' .m3u (may be non-music / Plex movie lists).
DEFAULT_EXCLUDE = {'Ethan and dad movies'}


XSI = 'http://www.w3.org/2001/XMLSchema-instance'
XSD = 'http://www.w3.org/2001/XMLSchema'


def _write_xml(tree):
    ET.register_namespace('xsd', XSD)
    ET.register_namespace('xsi', XSI)
    bak = f'{SERVER_PLAYLISTS}.{_dt.datetime.now():%Y%m%d-%H%M%S}.bak'
    shutil.copy2(SERVER_PLAYLISTS, bak)
    tree.write(SERVER_PLAYLISTS, encoding='utf-8', xml_declaration=True)
    print(f'  wrote {SERVER_PLAYLISTS}  (backup {bak})')
    return bak


def _playlist_map():
    """name -> (entry_el, key_el, m3u_path)."""
    tree = ET.parse(SERVER_PLAYLISTS)
    root = tree.getroot()
    out = {}
    for entry in root.findall('Entry'):
        key = entry.find('Key')
        if key is None:
            continue
        name = (key.findtext('Name') or '').strip()
        m3u = (key.findtext('SourceID') or '').strip()
        if name:
            out[name] = (entry, key, m3u)
    return tree, out


def drop_from_catalog(name, *, dry_run):
    tree, pl_map = _playlist_map()
    if name not in pl_map:
        print(f'  playlist not in catalog: {name!r}')
        return 0
    entry, key, m3u = pl_map[name]
    print(f'  drop catalog entry: {name!r}  (m3u left on disk: {m3u})')
    if dry_run:
        return 1
    tree.getroot().remove(entry)
    _write_xml(tree)
    return 1


def load_report(path, prune_statuses):
    """Return {playlist_name: set(dead_paths)}."""
    by_pl = {}
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split('\t')
            if len(parts) < 3:
                continue
            status, track, pl_blob = parts[0], parts[1], parts[2]
            if status not in prune_statuses:
                continue
            for pl in pl_blob.split('|'):
                pl = pl.strip()
                if pl:
                    by_pl.setdefault(pl, set()).add(track)
    return by_pl


def _read_m3u(path):
    lines = []
    tracks = []
    try:
        with open(path, encoding='utf-8', errors='replace') as f:
            for line in f:
                lines.append(line)
                s = line.strip()
                if s and not s.startswith('#'):
                    tracks.append(s)
    except OSError as e:
        return None, None, str(e)
    return lines, tracks, None


def _write_m3u(path, lines):
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        f.writelines(lines)
    os.replace(tmp, path)


def prune_m3us(by_pl, exclude, *, dry_run):
    _, pl_map = _playlist_map()
    changed_pls = []
    removed_total = 0
    for pl_name, dead in sorted(by_pl.items()):
        if pl_name in exclude:
            print(f'  skip (excluded): {pl_name!r}  ({len(dead)} dead refs)')
            continue
        if pl_name not in pl_map:
            print(f'  skip (not in catalog): {pl_name!r}')
            continue
        _entry, _key, m3u = pl_map[pl_name]
        if not m3u or not os.path.isfile(m3u):
            print(f'  skip (no m3u): {pl_name!r}  -> {m3u}')
            continue
        lines, tracks, err = _read_m3u(m3u)
        if err:
            print(f'  skip (read error): {pl_name!r}: {err}')
            continue
        dead_here = dead & set(tracks)
        if not dead_here:
            continue
        new_lines = []
        removed = 0
        for line in lines:
            s = line.strip()
            if s and not s.startswith('#') and s in dead_here:
                removed += 1
                continue
            new_lines.append(line)
        print(f'  {pl_name!r}: remove {removed} track(s) from {m3u}')
        if not dry_run:
            bak = m3u + '.bak'
            if not os.path.isfile(bak):
                shutil.copy2(m3u, bak)
            _write_m3u(m3u, new_lines)
        removed_total += removed
        changed_pls.append(pl_name)

    if changed_pls and not dry_run:
        tree, pl_map = _playlist_map()
        for pl_name in changed_pls:
            entry, key, m3u = pl_map[pl_name]
            _, tracks, _ = _read_m3u(m3u)
            if tracks is None:
                continue
            tc = key.find('TrackCount')
            if tc is not None:
                tc.text = str(len(tracks))
        _write_xml(tree)

    return len(changed_pls), removed_total


def main():
    ap = argparse.ArgumentParser(description='Prune unplayable playlist refs from Bock Media.')
    ap.add_argument('--report', default='/tmp/unplayable.txt', help='Report from check_playlist_playability.py')
    ap.add_argument('--fix', action='store_true', help='Apply changes (default: dry-run)')
    ap.add_argument('--dry-run', action='store_true', help='Report only')
    ap.add_argument('--drop-playlist', action='append', default=[],
                    help='Remove playlist from ServerPlaylists.xml (m3u untouched)')
    ap.add_argument('--exclude-playlist', action='append', default=[],
                    help='Do not edit this playlist m3u (default: Ethan and dad movies)')
    ap.add_argument('--status', action='append', default=['missing'],
                    help='Report statuses to prune (default: missing)')
    args = ap.parse_args()
    dry_run = not args.fix or args.dry_run

    if not os.path.isfile(SERVER_PLAYLISTS):
        print(f'FATAL: {SERVER_PLAYLISTS} not found', file=sys.stderr)
        return 2

    exclude = DEFAULT_EXCLUDE | set(args.exclude_playlist)

    if args.drop_playlist:
        n = 0
        for name in args.drop_playlist:
            n += drop_from_catalog(name, dry_run=dry_run)
        if n == 0 and not args.report:
            return 0

    if not os.path.isfile(args.report):
        if args.drop_playlist:
            return 0
        print(f'FATAL: report not found: {args.report}', file=sys.stderr)
        return 2

    by_pl = load_report(args.report, set(args.status))
    print(f'Report: {args.report}  statuses={args.status}  dry_run={dry_run}')
    print(f'Excluded from m3u edits: {sorted(exclude)}')
    changed, removed = prune_m3us(by_pl, exclude, dry_run=dry_run)
    print(f'done: playlists_changed={changed} tracks_removed={removed} dry_run={dry_run}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
