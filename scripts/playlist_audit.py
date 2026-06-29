#!/usr/bin/env python3
"""
playlist_audit.py — Audit every M3U playlist for broken track paths, then
optionally repair them in-place using the songs_cache database as the ground truth.

Fix strategy (per broken track, in order):
  1. Exact case-insensitive filename match in DB with one candidate  → fixed
  2. Multiple candidates → pick the one with the most-similar full path  → fixed (*)
  3. No candidates in DB at all                                         → unfixable

  (*) Marked as "best-guess" in the report.

Usage
-----
  # Dry run — report only, touch nothing
  python3 scripts/playlist_audit.py

  # Repair all playlists in-place (backs up each changed file as <name>.m3u.bak)
  python3 scripts/playlist_audit.py --fix

  # Check/repair a single playlist
  python3 scripts/playlist_audit.py --playlist "Baroque Music.m3u"
  python3 scripts/playlist_audit.py --fix --playlist "Baroque Music.m3u"

  # Show every fixed/unfixable track (not just the summary)
  python3 scripts/playlist_audit.py --verbose
"""

import argparse
import os
import shutil
import sqlite3
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from difflib import SequenceMatcher
from pathlib import Path

_MUSIC_ROOT  = os.environ.get('OURMEDIA_MUSIC_ROOT', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data', 'music'))
PLAYLIST_DIR = Path(os.environ.get('OURMEDIA_PLAYLIST_DIR', os.path.join(_MUSIC_ROOT, 'exportedPlaylists')))
DB_PATH      = Path(os.environ.get('OURMEDIA_DB_PATH', os.path.join(_MUSIC_ROOT, 'music_organizer.db')))
DATA_DIR     = os.environ.get('OURMEDIA_DATA_DIR', os.path.join(os.path.dirname(__file__), '..', 'fixtures', 'demo-data'))
SERVER_PLAYLISTS = Path(DATA_DIR) / 'ServerPlaylists.xml'


def targets_from_xml():
    """Audit exactly what the Alexa skill serves: every <Entry> in
    ServerPlaylists.xml, resolved via its <SourceID> .m3u. Returns
    (existing_m3u_paths, missing) where missing = [(playlist_name, source)]."""
    tree = ET.parse(SERVER_PLAYLISTS)
    existing, missing = [], []
    for e in tree.getroot().findall('Entry'):
        key = e.find('Key')
        if key is None:
            continue
        name = (key.findtext('Name') or '').strip() or '(unnamed)'
        src = (key.findtext('SourceID') or '').strip()
        if not src:
            missing.append((name, '(no SourceID)'))
        elif Path(src).is_file():
            existing.append(Path(src))
        else:
            missing.append((name, src))
    return existing, missing

# ── helpers ──────────────────────────────────────────────────────────────────

def build_index() -> dict[str, list[str]]:
    """
    Load songs_cache into {lowercase_basename: [full_path, ...]} so we can
    resolve a broken filename to its current location in O(1).
    Only includes paths where the file actually exists on disk.
    """
    idx: dict[str, list[str]] = defaultdict(list)
    con = sqlite3.connect(str(DB_PATH))
    try:
        rows = con.execute(
            'SELECT path FROM songs_cache WHERE path IS NOT NULL AND path != ""'
        ).fetchall()
    finally:
        con.close()

    print(f'  Building index from {len(rows):,} DB entries …', end=' ', flush=True)
    for (path,) in rows:
        idx[os.path.basename(path).lower()].append(path)
    print(f'done. ({len(idx):,} unique filenames indexed)')
    return dict(idx)


# Single shared cache so the same path is only stat'd once across all playlists
_exists_cache: dict[str, bool] = {}

def _exists(path: str) -> bool:
    if path not in _exists_cache:
        _exists_cache[path] = os.path.exists(path)
    return _exists_cache[path]


def _similarity(a: str, b: str) -> float:
    return SequenceMatcher(None, a.lower(), b.lower()).ratio()


def best_candidate(candidates: list[str], original_path: str) -> str:
    """When there are multiple files with the same name, pick the one whose
    full path is most similar to the original (preserves album / artist context)."""
    if len(candidates) == 1:
        return candidates[0]
    return max(candidates, key=lambda c: _similarity(original_path, c))


def parse_m3u(path: Path) -> tuple[list[str], list[tuple[int, str]]]:
    """Return (all_lines, [(line_index, track_path), ...]) for non-comment lines."""
    with open(path, encoding='utf-8', errors='replace') as f:
        lines = f.readlines()
    tracks = []
    for i, line in enumerate(lines):
        stripped = line.rstrip('\r\n')
        if stripped.startswith('#') or not stripped.strip():
            continue
        tracks.append((i, stripped.strip()))
    return lines, tracks


# ── per-playlist audit ────────────────────────────────────────────────────────

class TrackResult:
    __slots__ = ('original', 'resolved', 'status', 'n_candidates')
    def __init__(self, original, resolved=None, status='ok', n_candidates=0):
        self.original    = original
        self.resolved    = resolved      # new path if fixed, else None
        self.status      = status        # 'ok' | 'fixed' | 'unfixable'
        self.n_candidates = n_candidates # >1 means best-guess was used


def audit_playlist(m3u_path: Path, index: dict, fix: bool) -> tuple[list[TrackResult], bool]:
    """
    Audit one playlist.  Returns (results, rewrote).
    rewrote=True only when --fix is on and at least one line was changed.
    """
    lines, tracks = parse_m3u(m3u_path)
    results: list[TrackResult] = []
    new_lines = list(lines)
    rewrote = False

    for line_idx, track in tracks:
        if _exists(track):
            results.append(TrackResult(track, status='ok'))
            continue

        basename_key = os.path.basename(track).lower()
        # Filter to candidates that actually exist on disk (DB can have stale entries)
        candidates   = [p for p in index.get(basename_key, []) if _exists(p)]

        if not candidates:
            results.append(TrackResult(track, status='unfixable'))
        else:
            resolved = best_candidate(candidates, track)
            results.append(TrackResult(
                track, resolved=resolved,
                status='fixed', n_candidates=len(candidates),
            ))
            if fix:
                new_lines[line_idx] = resolved + '\n'
                rewrote = True

    if fix and rewrote:
        bak = m3u_path.with_suffix('.m3u.bak')
        if not bak.exists():
            shutil.copy2(str(m3u_path), str(bak))
        with open(str(m3u_path), 'w', encoding='utf-8') as f:
            f.writelines(new_lines)

    return results, rewrote


# ── reporting ─────────────────────────────────────────────────────────────────

def fmt_pct(n, total):
    return f'{n / total * 100:.1f}%' if total else '—'


def print_report(playlist_results: list[tuple[Path, list[TrackResult], bool]],
                 fix: bool, verbose: bool, missing_m3u: list[tuple[str, str]] | None = None):
    total_playlists   = len(playlist_results)
    clean_playlists   = 0
    repaired_playlists = 0
    broken_playlists  = 0   # had at least one unfixable track

    total_tracks    = 0
    ok_tracks       = 0
    fixed_tracks    = 0
    bestguess_tracks = 0    # fixed but from multiple candidates
    unfixable_tracks = 0

    unfixable_detail: list[tuple[str, str]] = []   # (playlist_name, track_path)

    for m3u_path, results, rewrote in playlist_results:
        n_ok    = sum(1 for r in results if r.status == 'ok')
        n_fixed = sum(1 for r in results if r.status == 'fixed')
        n_bad   = sum(1 for r in results if r.status == 'unfixable')
        n_guess = sum(1 for r in results if r.status == 'fixed' and r.n_candidates > 1)

        total_tracks     += len(results)
        ok_tracks        += n_ok
        fixed_tracks     += n_fixed
        bestguess_tracks += n_guess
        unfixable_tracks += n_bad

        if n_bad == 0 and n_fixed == 0:
            clean_playlists += 1
        elif n_bad == 0:
            repaired_playlists += 1
        else:
            broken_playlists += 1

        for r in results:
            if r.status == 'unfixable':
                unfixable_detail.append((m3u_path.name, r.original))

    # ── header ─────────────────────────────────────────────────────────────
    W = 70
    print()
    print('=' * W)
    print('  PLAYLIST AUDIT REPORT' + ('  [DRY RUN — no files changed]' if not fix else '  [CHANGES APPLIED]'))
    print('=' * W)

    empty_playlists = [m.name for m, results, _ in playlist_results
                       if results and all(r.status != 'ok' for r in results)]

    print(f'\nScanned : {total_playlists:>6,} playlists   |   {total_tracks:>7,} track references')
    if missing_m3u:
        print(f'        : {len(missing_m3u):>6,} served playlists whose .m3u file is MISSING')
    if empty_playlists:
        print(f'        : {len(empty_playlists):>6,} playlists with ZERO playable tracks')
    print()

    if missing_m3u:
        print('-' * W)
        print(f'Missing .m3u files ({len(missing_m3u)}) — playlist is listed but has no source file:')
        for name, src in sorted(missing_m3u)[:80]:
            print(f'  ✗ {name}  →  {src}')
        if len(missing_m3u) > 80:
            print(f'  … and {len(missing_m3u) - 80} more')
        print()

    if empty_playlists:
        print('-' * W)
        print(f'Empty playlists ({len(empty_playlists)}) — every track is dead (would play nothing):')
        for name in sorted(empty_playlists)[:80]:
            print(f'  ✗ {name}')
        if len(empty_playlists) > 80:
            print(f'  … and {len(empty_playlists) - 80} more')
        print()

    # ── playlist-level summary ──────────────────────────────────────────────
    print('Playlist summary:')
    print(f'  ✓ Fully clean   : {clean_playlists:>5,}  ({fmt_pct(clean_playlists, total_playlists)} of playlists)')
    print(f'  ~ Needed fixes  : {repaired_playlists + broken_playlists:>5,}  ({fmt_pct(repaired_playlists + broken_playlists, total_playlists)} of playlists)')
    print(f'  ✗ Still broken  : {broken_playlists:>5,}  ({fmt_pct(broken_playlists, total_playlists)} of playlists, have unfixable tracks)')
    print()

    # ── track-level summary ─────────────────────────────────────────────────
    print('Track summary:')
    print(f'  ✓ OK / playable   : {ok_tracks:>7,}  ({fmt_pct(ok_tracks, total_tracks)})')
    if fix:
        print(f'  ~ Repaired        : {fixed_tracks:>7,}  ({fmt_pct(fixed_tracks, total_tracks)})')
    else:
        print(f'  ~ Would repair    : {fixed_tracks:>7,}  ({fmt_pct(fixed_tracks, total_tracks)})')
    if bestguess_tracks:
        print(f'      (best-guess   : {bestguess_tracks:>7,}  — multiple candidates, closest path chosen)')
    print(f'  ✗ Unfixable       : {unfixable_tracks:>7,}  ({fmt_pct(unfixable_tracks, total_tracks)}, file not found in DB)')
    print()

    # ── per-playlist detail for playlists that had broken tracks ───────────
    if verbose:
        print('-' * W)
        print('Per-playlist detail (playlists with any broken track):')
        print()
        for m3u_path, results, rewrote in sorted(playlist_results, key=lambda x: x[0].name):
            bad    = [r for r in results if r.status in ('fixed', 'unfixable')]
            if not bad:
                continue
            n_fixed = sum(1 for r in bad if r.status == 'fixed')
            n_bad   = sum(1 for r in bad if r.status == 'unfixable')
            tag     = '[REPAIRED]' if (fix and rewrote) else ('[WOULD FIX]' if n_bad == 0 else '[PARTIAL]' if n_fixed else '[BROKEN]')
            print(f'  {m3u_path.name}  {tag}')
            for r in results:
                if r.status == 'fixed':
                    guess = ' (best-guess)' if r.n_candidates > 1 else ''
                    if verbose:
                        print(f'    {"~FIX":<8} {os.path.basename(r.original)}')
                        print(f'           → {r.resolved}{guess}')
                elif r.status == 'unfixable':
                    print(f'    {"✗MISS":<8} {r.original}')
            print()

    # ── unfixable track list ─────────────────────────────────────────────────
    if unfixable_detail:
        print('-' * W)
        print(f'Unfixable tracks ({unfixable_tracks:,}) — not found anywhere in the music DB:')
        print()
        last_pl = None
        for pl_name, track_path in sorted(unfixable_detail, key=lambda x: x[0]):
            if pl_name != last_pl:
                print(f'  [{pl_name}]')
                last_pl = pl_name
            print(f'    ✗ {os.path.basename(track_path)}')
        print()

    print('=' * W)
    if not fix:
        print('  Run with --fix to repair playlists in-place.')
    else:
        print(f'  Modified playlists backed up as <name>.m3u.bak before changes.')
    print('=' * W)
    print()


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--fix',      action='store_true',
                        help='Repair broken paths in-place (default: dry run)')
    parser.add_argument('--playlist', metavar='NAME',
                        help='Only audit this one playlist filename (e.g. "Baroque Music.m3u")')
    parser.add_argument('--verbose',  action='store_true',
                        help='Show each fixed/unfixable track per playlist')
    parser.add_argument('--from-xml', action='store_true',
                        help='Audit exactly what the skill serves (every ServerPlaylists.xml entry)')
    args = parser.parse_args()

    if not DB_PATH.is_file():
        sys.exit(f'Database not found: {DB_PATH}')

    missing_m3u: list[tuple[str, str]] = []
    # Collect playlists to scan
    if args.from_xml:
        if not SERVER_PLAYLISTS.is_file():
            sys.exit(f'ServerPlaylists.xml not found: {SERVER_PLAYLISTS}')
        m3u_files, missing_m3u = targets_from_xml()
        if not m3u_files and not missing_m3u:
            sys.exit('No playlists found in ServerPlaylists.xml')
    elif args.playlist:
        target = PLAYLIST_DIR / args.playlist
        if not target.is_file():
            sys.exit(f'Playlist not found: {target}')
        m3u_files = [target]
    else:
        if not PLAYLIST_DIR.is_dir():
            sys.exit(f'Playlist dir not found: {PLAYLIST_DIR}')
        m3u_files = sorted(PLAYLIST_DIR.rglob('*.m3u'))  # recurse into plex/ etc.
        if not m3u_files:
            sys.exit(f'No .m3u files found in {PLAYLIST_DIR}')

    print(f'\nPlaylist Audit  —  {"REPAIRING" if args.fix else "DRY RUN"}'
          f'{"  [from ServerPlaylists.xml]" if args.from_xml else ""}')
    print(f'Playlists : {SERVER_PLAYLISTS if args.from_xml else PLAYLIST_DIR}')
    print(f'Database  : {DB_PATH}')
    print()

    index = build_index()

    playlist_results = []
    total = len(m3u_files)
    for i, m3u_path in enumerate(m3u_files, 1):
        if total > 1:
            pct = i / total * 100
            print(f'\r  Scanning {i}/{total}  ({pct:.0f}%)  {m3u_path.name[:50]:<50}',
                  end='', flush=True)
        results, rewrote = audit_playlist(m3u_path, index, fix=args.fix)
        playlist_results.append((m3u_path, results, rewrote))

    if total > 1:
        print()  # newline after progress line

    print_report(playlist_results, fix=args.fix, verbose=args.verbose, missing_m3u=missing_m3u)


if __name__ == '__main__':
    main()
