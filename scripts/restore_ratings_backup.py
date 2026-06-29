#!/usr/bin/env python3
"""Find and restore ratings.json / legacy favorites.json backups (run on the NAS).

Usage:
  cd ~/Documents/github/ourMedia
  python3 scripts/restore_ratings_backup.py --dry-run
  python3 scripts/restore_ratings_backup.py --member p-andy --apply
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import bock_ratings  # noqa: E402

DATA_DIR = Path(os.environ.get('OURMEDIA_DATA_DIR', os.path.expanduser('~/.bockmedia')))
RATINGS_PATH = DATA_DIR / 'ratings.json'
LEGACY_RATINGS_PATH = HERE / 'ratings.json'
FAVORITES_PATH = DATA_DIR / 'favorites.json'


def _load_json(path: Path):
    try:
        with open(path, encoding='utf-8') as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return None


def _rating_count(path: Path) -> int:
    data = _load_json(path)
    if not data:
        return 0
    doc = bock_ratings._normalize_doc(data)
    total = 0
    for bucket in (doc.get('members') or {}).values():
        items = bucket.get('items') if isinstance(bucket, dict) else None
        if isinstance(items, dict):
            total += sum(
                1 for row in items.values()
                if isinstance(row, dict) and int(row.get('stars') or 0) >= 1
            )
    return total


def _favorites_count(path: Path) -> int:
    data = _load_json(path)
    if isinstance(data, list):
        return len(data)
    return 0


def discover_candidates(extra: list[str]) -> list[Path]:
    patterns = [
        RATINGS_PATH,
        FAVORITES_PATH,
        HERE / 'ratings.json.bak',
        HERE / 'favorites.json.bak',
    ]
    for pat in ('ratings.json.*', 'favorites.json.*', 'ratings.json@*', 'favorites.json@*'):
        patterns.extend(HERE.glob(pat))
    snap = HERE / '@Snapshot'
    if snap.is_dir():
        for child in snap.iterdir():
            for name in ('ratings.json', 'favorites.json'):
                p = child / name
                if p.is_file():
                    patterns.append(p)
    trash_ratings = Path.home() / '.local/share/Trash/files/ratings.json'
    if trash_ratings.is_file():
        patterns.append(trash_ratings)
    legacy_alexa = Path.home() / '.MyMediaForAlexa/ratings.json'
    if legacy_alexa.is_file():
        patterns.append(legacy_alexa)
    backup_root = DATA_DIR / 'member_data_backups'
    if backup_root.is_dir():
        patterns.extend(sorted(backup_root.glob('ratings.json.*.bak'), reverse=True))
    seen = set()
    out = []
    for p in patterns + [Path(x) for x in extra]:
        p = Path(p).expanduser().resolve()
        if p.is_file() and p not in seen:
            seen.add(p)
            out.append(p)
    return sorted(out, key=lambda p: p.stat().st_mtime, reverse=True)


def import_favorites_list(items: list, member_id: str, ratings_path: Path, apply: bool) -> int:
    added = 0
    for row in items:
        if not isinstance(row, dict):
            continue
        path = (row.get('path') or '').strip()
        if not path:
            continue
        if apply:
            bock_ratings.set_rating(
                str(ratings_path), 'song', path, 5, None,
                title=row.get('title') or row.get('track'),
                artist=row.get('artist'),
                album=row.get('album'),
                member_id=member_id,
            )
        added += 1
    return added


def merge_ratings_doc(doc: dict, member_id: str, ratings_path: Path, apply: bool) -> int:
    """Merge all member buckets + legacy into target member (union by rating key)."""
    norm = bock_ratings._normalize_doc(doc)
    merged = {}
    for bucket in (norm.get('members') or {}).values():
        items = bucket.get('items') if isinstance(bucket, dict) else None
        if not isinstance(items, dict):
            continue
        for key, row in items.items():
            if isinstance(row, dict) and int(row.get('stars') or 0) >= 1:
                merged[key] = row
    if not merged:
        return 0
    if apply:
        target = bock_ratings._load_doc(str(ratings_path))
        dest = bock_ratings._member_items(target, member_id)
        dest.update(merged)
        bock_ratings._save_doc(str(ratings_path), target, None)
    return len(merged)


def main():
    ap = argparse.ArgumentParser(description='Restore Bock Media star ratings from backups')
    ap.add_argument('--member', default='p-andy', help='Household member id (default: p-andy)')
    ap.add_argument('--apply', action='store_true', help='Write merged ratings (default: dry-run)')
    ap.add_argument('--dry-run', action='store_true', help='Show candidates only')
    ap.add_argument('extra', nargs='*', help='Extra backup file paths to consider')
    args = ap.parse_args()
    apply = args.apply and not args.dry_run

    print(f'Current ratings: {RATINGS_PATH} ({_rating_count(RATINGS_PATH)} rated items)')
    if FAVORITES_PATH.is_file():
        print(f'Legacy favorites: {FAVORITES_PATH} ({_favorites_count(FAVORITES_PATH)} paths)')

    candidates = discover_candidates(args.extra)
    if not candidates:
        print('No backup candidates found.')
        return 1

    print('\nCandidates (newest first):')
    best = None
    best_score = (_rating_count(RATINGS_PATH), _favorites_count(FAVORITES_PATH))
    for p in candidates:
        rc = _rating_count(p) if 'favorites' not in p.name else 0
        fc = _favorites_count(p) if 'favorites' in p.name else 0
        score = (rc, fc)
        tag = 'ratings' if rc else ('favorites' if fc else 'empty')
        mtime = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(p.stat().st_mtime))
        print(f'  {mtime}  {tag:9}  ratings={rc} favorites={fc}  {p}')
        if score > best_score:
            best_score = score
            best = p

    if not best or best_score == (0, 0):
        print('\nNo non-empty backup found. Try Synology File Station → ourMedia → Previous Versions.')
        return 1

    print(f'\nBest backup: {best} (score ratings={best_score[0]} favorites={best_score[1]})')
    if not apply:
        print('Dry-run. Re-run with --apply to restore into', args.member)
        return 0

    stamp = time.strftime('%Y%m%d-%H%M%S')
    if RATINGS_PATH.is_file():
        bak = RATINGS_PATH.with_suffix(f'.json.pre-restore-{stamp}')
        shutil.copy2(RATINGS_PATH, bak)
        print(f'Backed up current ratings → {bak}')

    if 'favorites' in best.name:
        data = _load_json(best)
        n = import_favorites_list(data if isinstance(data, list) else [], args.member, RATINGS_PATH, True)
    else:
        data = _load_json(best)
        n = merge_ratings_doc(data if isinstance(data, dict) else {}, args.member, RATINGS_PATH, True)

    print(f'Restored {n} entries into member {args.member!r}. Restart: sudo systemctl restart ourmedia')
    print(f'Verify: curl -s -H "Authorization: Bearer …" …/api/playlists/rated-stars-5?memberId={args.member}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
