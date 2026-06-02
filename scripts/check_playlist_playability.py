#!/usr/bin/env python3
"""check_playlist_playability.py — verify every track in every Bock Media
playlist is actually playable, not merely present.

Source of truth is the same file the Alexa skill reads: ServerPlaylists.xml
(each <Entry> -> <SourceID> .m3u -> track file paths). Every UNIQUE track is
checked once (deduped across playlists) so the ~476k references collapse to the
real file set.

Strategy (fast by default):
  The music_organizer.db `songs_cache` already lists every file the library
  indexer successfully read/decoded, so DB-presence is a strong, INSTANT proxy
  for "playable" — no per-file I/O needed for those. Only tracks the DB can't
  vouch for get a filesystem stat, and (with --deep) an ffprobe decode test.

Per-track status:
  ok           in songs_cache (indexer decoded it) — or ffprobe-confirmed
  missing      file not on disk
  unsupported  extension not in the skill's SUPPORTED_EXTS
  empty        zero-byte file
  unindexed    exists + supported, but not in songs_cache (suspect; --deep probes it)
  corrupt      (--deep) ffprobe found no decodable audio stream

Usage:
  python3 scripts/check_playlist_playability.py                 # fast, seconds
  python3 scripts/check_playlist_playability.py --deep          # ffprobe the suspect/unindexed tracks
  python3 scripts/check_playlist_playability.py --deep-all      # ffprobe EVERY track (very slow, hours)
  python3 scripts/check_playlist_playability.py --report /tmp/unplayable.txt

Config (env): OURMEDIA_DATA_DIR, OURMEDIA_DB_PATH, OURMEDIA_FFPROBE
"""
import argparse
import os
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

DATA_DIR = os.environ.get('OURMEDIA_DATA_DIR', '/home/youruser/.bockmedia')
DB_PATH = os.environ.get('OURMEDIA_DB_PATH', '/srv/music/music_organizer.db')
SERVER_PLAYLISTS = os.path.join(DATA_DIR, 'ServerPlaylists.xml')
FFPROBE = os.environ.get('OURMEDIA_FFPROBE', 'ffprobe')

SUPPORTED_EXTS = {'.mp3', '.m4a', '.aac', '.flac', '.wma', '.wav', '.ogg', '.aif', '.aiff'}


def parse_m3u(path):
    out = []
    try:
        with open(path, 'r', encoding='utf-8', errors='replace') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#'):
                    out.append(line)
    except Exception:
        pass
    return out


def collect_tracks():
    tree = ET.parse(SERVER_PLAYLISTS)
    track_pls = defaultdict(list)
    missing_src = []
    for e in tree.getroot().findall('Entry'):
        key = e.find('Key')
        if key is None:
            continue
        name = (key.findtext('Name') or '').strip() or '(unnamed)'
        src = (key.findtext('SourceID') or '').strip()
        if not src or not os.path.isfile(src):
            missing_src.append((name, src or '(no SourceID)'))
            continue
        for t in parse_m3u(src):
            track_pls[t].append(name)
    return track_pls, missing_src


def load_db_paths():
    con = sqlite3.connect(f'file:{DB_PATH}?mode=ro', uri=True)
    try:
        rows = con.execute(
            'SELECT path FROM songs_cache WHERE path IS NOT NULL AND path != ""'
        ).fetchall()
    finally:
        con.close()
    return {r[0] for r in rows}


def ffprobe_ok(path):
    try:
        out = subprocess.run(
            [FFPROBE, '-v', 'error', '-select_streams', 'a',
             '-show_entries', 'stream=codec_type', '-of', 'csv=p=0', path],
            capture_output=True, text=True, timeout=30)
        return out.returncode == 0 and 'audio' in (out.stdout or '')
    except Exception:
        return False


def fs_status(path):
    """Filesystem-level classification for a track NOT confirmed by the DB."""
    if not os.path.isfile(path):
        return 'missing'
    if os.path.splitext(path)[1].lower() not in SUPPORTED_EXTS:
        return 'unsupported'
    try:
        if os.path.getsize(path) == 0:
            return 'empty'
    except OSError:
        return 'missing'
    return 'unindexed'


def main():
    ap = argparse.ArgumentParser(description='Verify every Bock playlist track is playable.')
    ap.add_argument('--deep', action='store_true',
                    help='ffprobe the suspect (unindexed) tracks to confirm/deny playability.')
    ap.add_argument('--deep-all', action='store_true',
                    help='ffprobe EVERY track, ignoring the DB shortcut (very slow).')
    ap.add_argument('--workers', type=int, default=48, help='Parallel ffprobe workers.')
    ap.add_argument('--report', metavar='PATH', default='/tmp/unplayable.txt',
                    help='Write the full unplayable-track list here.')
    args = ap.parse_args()

    if not os.path.isfile(SERVER_PLAYLISTS):
        sys.exit(f'ServerPlaylists.xml not found: {SERVER_PLAYLISTS}')

    print(f'Reading playlists from {SERVER_PLAYLISTS} …', flush=True)
    track_pls, missing_src = collect_tracks()
    tracks = list(track_pls)
    total_refs = sum(len(v) for v in track_pls.values())
    n_playlists = len({p for pls in track_pls.values() for p in pls})
    print(f'  {len(tracks):,} unique tracks across {total_refs:,} references '
          f'in {n_playlists:,} playlists.', flush=True)
    if missing_src:
        print(f'  {len(missing_src)} playlists have a missing/empty .m3u source.', flush=True)

    results = {}
    t0 = time.time()

    if args.deep_all:
        print(f'Probing EVERY track with ffprobe ({args.workers} workers) — this is slow …', flush=True)
        done = 0
        with ThreadPoolExecutor(max_workers=args.workers) as ex:
            for path, ok in zip(tracks, ex.map(ffprobe_ok, tracks)):
                results[path] = 'ok' if ok else fs_status(path)
                done += 1
                if done % 2000 == 0:
                    print(f'\r  {done:,}/{len(tracks):,} ({done/max(time.time()-t0,0.001):.0f}/s)',
                          end='', flush=True)
        print()
    else:
        print('Cross-referencing songs_cache (decoded-by-indexer = playable) …', flush=True)
        db_paths = load_db_paths()
        print(f'  {len(db_paths):,} files known-good in songs_cache.', flush=True)
        suspects = []
        for path in tracks:
            if path in db_paths:
                results[path] = 'ok'
            else:
                st = fs_status(path)
                results[path] = st
                if st == 'unindexed':
                    suspects.append(path)

        if args.deep and suspects:
            print(f'Deep-probing {len(suspects):,} unindexed tracks with ffprobe '
                  f'({args.workers} workers) …', flush=True)
            done = 0
            with ThreadPoolExecutor(max_workers=args.workers) as ex:
                for path, ok in zip(suspects, ex.map(ffprobe_ok, suspects)):
                    results[path] = 'ok' if ok else 'corrupt'
                    done += 1
                    if done % 1000 == 0:
                        print(f'\r  {done:,}/{len(suspects):,}', end='', flush=True)
            print()

    by_status = defaultdict(list)
    for path, status in results.items():
        by_status[status].append(path)
    bad_order = ('missing', 'unsupported', 'empty', 'corrupt', 'unindexed')
    unplayable = {s: by_status[s] for s in bad_order if by_status.get(s)}
    n_unplayable = sum(len(v) for v in unplayable.values())
    n_ok = len(by_status.get('ok', []))

    bad_playlists = defaultdict(lambda: defaultdict(int))
    for status, paths in unplayable.items():
        for p in paths:
            for pl in track_pls[p]:
                bad_playlists[pl][status] += 1

    W = 70
    print('\n' + '=' * W)
    print('  PLAYABILITY REPORT')
    print('=' * W)
    print(f'\nUnique tracks : {len(tracks):>8,}')
    if tracks:
        print(f'  OK / playable : {n_ok:>8,}  ({n_ok/len(tracks)*100:.2f}%)')
        print(f'  Unplayable    : {n_unplayable:>8,}  ({n_unplayable/len(tracks)*100:.2f}%)')
    for s in bad_order:
        if by_status.get(s):
            note = '  (exists but never indexed — run with --deep to ffprobe)' if s == 'unindexed' else ''
            print(f'      {s:<12}: {len(by_status[s]):>8,}{note}')
    print(f'\nPlaylists with >=1 unplayable track: {len(bad_playlists):,}')

    if bad_playlists:
        print('\n' + '-' * W)
        print('Most-affected playlists (top 30):')
        for pl, counts in sorted(bad_playlists.items(), key=lambda kv: -sum(kv[1].values()))[:30]:
            detail = ', '.join(f'{k}:{v}' for k, v in counts.items())
            print(f'  {sum(counts.values()):>4}  {pl}   ({detail})')

    if args.report and unplayable:
        with open(args.report, 'w', encoding='utf-8') as f:
            for status in bad_order:
                for p in sorted(by_status.get(status, [])):
                    f.write(f'{status}\t{p}\t{"|".join(track_pls[p])}\n')
        print(f'\nFull unplayable list -> {args.report} ({n_unplayable:,} rows)')

    print('=' * W)
    return 0


if __name__ == '__main__':
    sys.exit(main())
