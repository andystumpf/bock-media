#!/usr/bin/env python3
"""Sync Plex audio playlists into the catalog Bock Media reads (near real-time CRUD).

Bock Media resolves every playlist from a single file -- ``ServerPlaylists.xml``
(My Media's export). This script pulls all audio playlists from a local Plex
server, writes one ``.m3u`` per playlist (the format ``parse_m3u`` in
``server.py`` expects), and merges matching ``<Entry>`` rows into
``ServerPlaylists.xml``. ``server.py`` re-reads that file on every request, so
changes appear with no restart.

It is idempotent and cheap to run on a tight cron (default: every 5 min):
  * A state cache (``.plex_sync_state.json``) records each playlist's Plex
    ``updatedAt``/track count, so unchanged playlists are NOT re-fetched -- a
    no-op run is one Plex API call plus an XML rewrite.
  * CREATE  -> new Plex playlist -> new .m3u + new <Entry>.
  * UPDATE  -> changed updatedAt/leafCount -> .m3u + <Entry> rebuilt.
  * DELETE  -> playlist gone from Plex -> its .m3u and <Entry> are pruned.
  * Plex-sourced rows are tagged ``<SourceName>plex</SourceName>``; My Media's
    own ``file`` rows are preserved. On a name collision Plex wins, keeping the
    Plex library authoritative.
  * A timestamped ``.bak`` of ServerPlaylists.xml is written before each save
    that actually changes something.

After playlists change, refresh the voice catalog (slower; cron nightly) so
"...on bock media" resolves new names:
    python3 scripts/build_msp_catalog.py
    python3 scripts/upload_msp_catalog.py --catalog-id <CID> --file skill/catalog_playlists.json

Config (env, with defaults):
  OURMEDIA_PLEX_URL          http://127.0.0.1:32400
  OURMEDIA_PLEX_TOKEN        (else read from OURMEDIA_PLEX_PREFS)
  OURMEDIA_PLEX_PREFS        /var/lib/plexmediaserver/.../Preferences.xml
  OURMEDIA_DATA_DIR          ~/.bockmedia if present, else fixtures/demo-data
  OURMEDIA_MUSIC_ROOT        config.json musicRoot, else /mnt/bock/Music, else fixtures
  OURMEDIA_PLEX_PLAYLIST_DIR <MUSIC_ROOT>/exportedPlaylists/plex  (.m3u output)

Cron should call scripts/run_plex_playlist_sync.sh so production paths are set.
"""
import argparse
import datetime as _dt
import json
import os
import re
import shutil
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from urllib.error import URLError
from urllib.parse import quote
from urllib.request import urlopen
from xml.sax.saxutils import escape

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)
from playlist_xml_lock import playlist_xml_lock

_FIXTURES_DATA = os.path.join(REPO_ROOT, 'fixtures', 'demo-data')
_FIXTURES_MUSIC = os.path.join(_FIXTURES_DATA, 'music')


def _resolve_data_dir():
    if v := os.environ.get('OURMEDIA_DATA_DIR'):
        return v
    home_xml = os.path.join(os.path.expanduser('~'), '.bockmedia', 'ServerPlaylists.xml')
    if os.path.isfile(home_xml):
        return os.path.dirname(home_xml)
    return _FIXTURES_DATA


def _resolve_music_root(data_dir):
    if v := os.environ.get('OURMEDIA_MUSIC_ROOT'):
        return v
    cfg = os.path.join(data_dir, 'config.json')
    try:
        with open(cfg, encoding='utf-8') as f:
            c = json.load(f)
        mr = (c.get('musicRoot') or c.get('music_root') or '').strip()
        if mr and os.path.isdir(mr):
            return mr
    except Exception:
        pass
    for cand in ('/mnt/bock/Music', os.path.join(os.path.expanduser('~'), 'Music')):
        if os.path.isdir(cand):
            return cand
    return _FIXTURES_MUSIC


DATA_DIR = _resolve_data_dir()
MUSIC_ROOT = _resolve_music_root(DATA_DIR)
PLEX_URL = os.environ.get('OURMEDIA_PLEX_URL', 'http://127.0.0.1:32400').rstrip('/')
PLAYLIST_DIR = os.environ.get(
    'OURMEDIA_PLEX_PLAYLIST_DIR', os.path.join(MUSIC_ROOT, 'exportedPlaylists', 'plex'))
PREFS_PATH = os.environ.get(
    'OURMEDIA_PLEX_PREFS',
    '/var/lib/plexmediaserver/Library/Application Support/Plex Media Server/Preferences.xml')
SERVER_PLAYLISTS = os.path.join(DATA_DIR, 'ServerPlaylists.xml')
STATE_PATH = os.path.join(PLAYLIST_DIR, '.plex_sync_state.json')

XSI = 'http://www.w3.org/2001/XMLSchema-instance'
XSD = 'http://www.w3.org/2001/XMLSchema'
SOURCE_MARKER = 'plex'
AUDIO_EXTS = {'.mp3', '.m4a', '.flac', '.ogg', '.opus', '.wav', '.aac', '.wma', '.aiff', '.alac'}


def log(*a):
    print(*a, flush=True)


def plex_token():
    tok = os.environ.get('OURMEDIA_PLEX_TOKEN')
    if tok:
        return tok
    try:
        with open(PREFS_PATH, 'r', encoding='utf-8', errors='replace') as f:
            m = re.search(r'PlexOnlineToken="([^"]+)"', f.read())
            if m:
                return m.group(1)
    except Exception as e:
        log(f'! could not read Plex token from {PREFS_PATH}: {e}')
    return None


def plex_get(path, token, *, timeout=60, retries=3):
    url = f'{PLEX_URL}{path}{"&" if "?" in path else "?"}X-Plex-Token={quote(token)}'
    last_err = None
    for attempt in range(retries):
        try:
            with urlopen(url, timeout=timeout) as r:
                return ET.fromstring(r.read())
        except (TimeoutError, URLError, OSError) as ex:
            last_err = ex
            if attempt + 1 < retries:
                time.sleep(min(2 ** attempt, 8))
    raise last_err


def fetch_audio_playlists(token):
    root = plex_get('/playlists?playlistType=audio', token)
    out = []
    for p in root.findall('Playlist'):
        title = (p.get('title') or '').strip()
        key = p.get('ratingKey')
        if title and key:
            out.append({
                'key': key,
                'title': title,
                'leafCount': int(p.get('leafCount') or 0),
                'updatedAt': p.get('updatedAt') or p.get('addedAt') or '',
                'addedAt': p.get('addedAt') or '',
            })
    return out


def plex_ts_to_iso(ts):
    """Plex addedAt/updatedAt are unix seconds (string)."""
    if not ts:
        return ''
    try:
        sec = int(ts)
        return _dt.datetime.fromtimestamp(sec, _dt.timezone.utc).astimezone().isoformat()
    except (TypeError, ValueError, OSError):
        return ''


def load_existing_create_dates():
    """Map playlist ID -> CreateDate already in ServerPlaylists.xml."""
    dates = {}
    try:
        tree = ET.parse(SERVER_PLAYLISTS)
        for entry in tree.getroot().findall('Entry'):
            key = entry.find('Key')
            if key is None:
                continue
            pid = (key.findtext('ID') or '').strip()
            cd = (key.findtext('CreateDate') or '').strip()
            if pid and cd:
                dates[pid] = cd
    except Exception:
        pass
    return dates


def resolve_create_date(rating_key, plex_row, prev, existing_dates):
    """Prefer Plex addedAt (when the playlist was created in Plex)."""
    plex_cd = plex_ts_to_iso(plex_row.get('addedAt'))
    if plex_cd:
        return plex_cd
    pid = stable_id(rating_key)
    if prev and prev.get('createDate'):
        return prev['createDate']
    if pid in existing_dates:
        return existing_dates[pid]
    return _dt.datetime.now().astimezone().isoformat()


def _tracks_from_container(root):
    paths = []
    for tr in root.findall('.//Track'):
        part = tr.find('.//Part')
        f = part.get('file') if part is not None else None
        if f and os.path.splitext(f)[1].lower() in AUDIO_EXTS:
            paths.append(f)
    return paths


def fetch_tracks(rating_key, token, leaf_count=0):
    """Fetch playlist tracks; paginate large playlists so Plex can respond quickly."""
    page_size = 200
    start = 0
    paths = []
    while True:
        path = (
            f'/playlists/{rating_key}/items'
            f'?X-Plex-Container-Start={start}&X-Plex-Container-Size={page_size}'
        )
        root = plex_get(path, token, timeout=120)
        batch = _tracks_from_container(root)
        if not batch:
            break
        paths.extend(batch)
        start += len(batch)
        total = int(root.get('totalSize') or root.get('size') or leaf_count or 0)
        if total and start >= total:
            break
        if len(batch) < page_size:
            break
    return paths


def safe_filename(name):
    s = re.sub(r'[^\w\-. ]', '_', name).strip() or 'playlist'
    return s[:120]


def m3u_path_for(name, rating_key):
    return os.path.join(PLAYLIST_DIR, f'{safe_filename(name)}.{rating_key}.m3u')


def write_m3u(path, tracks):
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write('#EXTM3U\n')
        for t in tracks:
            f.write(t + '\n')
    os.replace(tmp, path)


def referenced_m3u_paths(root):
    paths = set()
    for entry in root.findall('Entry'):
        key = entry.find('Key')
        if key is None:
            continue
        src = (key.findtext('SourceID') or '').strip()
        if src.lower().endswith('.m3u'):
            paths.add(os.path.normpath(src))
    return paths


def prune_orphan_m3us(root, playlist_dir):
    """Drop .m3u files under playlist_dir not listed in ServerPlaylists.xml."""
    if not os.path.isdir(playlist_dir):
        return 0
    valid = referenced_m3u_paths(root)
    removed = 0
    for fn in os.listdir(playlist_dir):
        if not fn.lower().endswith('.m3u'):
            continue
        full = os.path.normpath(os.path.join(playlist_dir, fn))
        if full in valid:
            continue
        try:
            os.remove(full)
            removed += 1
            log(f'  - ORPHAN  {fn}')
        except OSError:
            pass
    return removed


def stable_id(rating_key):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f'plex-playlist:{rating_key}'))


def mirror_playlists_to_sql(pending_m3u_writes, new_state):
    """Phase 2: mirror Plex playlist tracks into playlist_tracks (read path via SQL)."""
    if not pending_m3u_writes:
        return
    try:
        import bock_playlists
        import server
        bock_playlists.ensure_schema(server.get_db_rw)
        m3u_to_key = {
            os.path.normpath(v['m3u']): k
            for k, v in new_state.items() if v.get('m3u')
        }
        for m3u_path, tracks in pending_m3u_writes.items():
            plex_key = m3u_to_key.get(os.path.normpath(m3u_path))
            if not plex_key:
                continue
            if len(tracks) > 2000:
                continue
            pid = stable_id(plex_key)
            name = new_state[plex_key].get('name') or 'Plex playlist'
            bock_playlists.import_from_m3u(
                server.get_db_rw, pid, name, m3u_path, tracks, source_kind='plex',
            )
        log(f'SQL mirror: {len(pending_m3u_writes)} playlist(s)')
    except Exception as ex:
        log(f'SQL mirror skipped: {ex}')


def build_entry_xml(name, rating_key, m3u_path, track_count, create_date=None, last_used=None):
    now = _dt.datetime.now().astimezone().isoformat()
    cd = create_date or now
    lu = last_used or now
    return (
        '<Entry xmlns:xsi="%s">\n'
        '    <Key xsi:type="Playlist">\n'
        '      <ID>%s</ID>\n'
        '      <MediaClientID>%s</MediaClientID>\n'
        '      <Name>%s</Name>\n'
        '      <Shuffle>false</Shuffle>\n'
        '      <Loop>false</Loop>\n'
        '      <Temporary>false</Temporary>\n'
        '      <CreateDate>%s</CreateDate>\n'
        '      <Type>File</Type>\n'
        '      <IsAudioBook>false</IsAudioBook>\n'
        '      <TrackCount>%d</TrackCount>\n'
        '      <LastUsed>%s</LastUsed>\n'
        '      <DeviceID />\n'
        '      <SearchHash />\n'
        '      <SourceID>%s</SourceID>\n'
        '      <SourceName>%s</SourceName>\n'
        '    </Key>\n'
        '    <Value xsi:type="ArrayOfGuid" />\n'
        '  </Entry>'
    ) % (XSI, stable_id(rating_key), str(uuid.uuid4()), escape(name), cd,
         track_count, lu, escape(m3u_path), SOURCE_MARKER)


def load_state():
    try:
        with open(STATE_PATH, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(state):
    tmp = STATE_PATH + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(state, f)
    os.replace(tmp, STATE_PATH)


def main():
    ap = argparse.ArgumentParser(description='Sync Plex audio playlists into Bock Media.')
    ap.add_argument('--dry-run', action='store_true', help='Report only; do not write any files.')
    ap.add_argument('--force', action='store_true', help='Re-fetch every playlist (ignore cache).')
    ap.add_argument('--min-tracks', type=int, default=1, help='Skip playlists with fewer tracks.')
    ap.add_argument('--max-tracks', type=int, default=10000,
                    help='Skip Plex playlists larger than this (e.g. All Music smart view).')
    ap.add_argument('--quiet', action='store_true', help='Only log changes/summary (good for cron).')
    args = ap.parse_args()

    def vlog(*a):
        if not args.quiet:
            log(*a)

    token = plex_token()
    if not token:
        log('FATAL: no Plex token (set OURMEDIA_PLEX_TOKEN or grant read on Preferences.xml)')
        return 2
    if not os.path.isfile(SERVER_PLAYLISTS):
        log(f'FATAL: {SERVER_PLAYLISTS} not found')
        return 2

    vlog(f'Plex:   {PLEX_URL}')
    vlog(f'Target: {SERVER_PLAYLISTS}')
    if not args.dry_run:
        os.makedirs(PLAYLIST_DIR, exist_ok=True)

    state = {} if args.force else load_state()
    pls = fetch_audio_playlists(token)
    live_keys = {p['key'] for p in pls}
    existing_create_dates = load_existing_create_dates()
    now_iso = _dt.datetime.now().astimezone().isoformat()

    created = updated = unchanged = skipped = 0
    new_state = {}
    entries = []          # (name, xml_string)
    synced_names = set()
    pending_m3u_writes = {}   # path -> tracks (deferred until XML is committed)
    pending_m3u_removals = set()
    dates_refreshed = 0

    for p in pls:
        key, title, leaf, upd = p['key'], p['title'], p['leafCount'], p['updatedAt']
        m3u = m3u_path_for(title, key)
        prev = state.get(key)
        changed = (prev is None
                   or prev.get('updatedAt') != upd
                   or prev.get('leafCount') != leaf
                   or prev.get('m3u') != m3u
                   or not os.path.isfile(m3u))

        if leaf > args.max_tracks:
            skipped += 1
            log(f'  - SKIP  {title}  ({leaf} tracks > max {args.max_tracks})')
            if prev:
                track_count = prev.get('trackCount', leaf)
                keep_m3u = prev.get('m3u') or m3u
                create_date = resolve_create_date(key, p, prev, existing_create_dates)
                new_state[key] = {**prev, 'createDate': create_date}
                if keep_m3u and os.path.isfile(keep_m3u):
                    entries.append((title, build_entry_xml(
                        title, key, keep_m3u, track_count, create_date=create_date, last_used=now_iso)))
                    synced_names.add(title.lower())
            continue

        create_date = resolve_create_date(key, p, prev, existing_create_dates)
        if existing_create_dates.get(stable_id(key)) != create_date:
            dates_refreshed += 1

        if changed:
            try:
                tracks = fetch_tracks(key, token, leaf)
            except Exception as ex:
                log(f'  ! SKIP  {title}  ({ex})')
                skipped += 1
                if prev:
                    track_count = prev.get('trackCount', leaf)
                    keep_m3u = prev.get('m3u') or m3u
                    new_state[key] = {**prev, 'createDate': create_date}
                    if keep_m3u and os.path.isfile(keep_m3u):
                        entries.append((title, build_entry_xml(
                            title, key, keep_m3u, track_count, create_date=create_date, last_used=now_iso)))
                        synced_names.add(title.lower())
                continue
            if len(tracks) < args.min_tracks:
                skipped += 1
                # Dropped below the threshold: defer removal until XML is committed.
                if not args.dry_run and prev and prev.get('m3u'):
                    pending_m3u_removals.add(os.path.normpath(prev['m3u']))
                continue
            track_count = len(tracks)
            if not args.dry_run:
                if prev and prev.get('m3u') and os.path.normpath(prev['m3u']) != os.path.normpath(m3u):
                    pending_m3u_removals.add(os.path.normpath(prev['m3u']))
                pending_m3u_writes[os.path.normpath(m3u)] = tracks
            if prev is None:
                created += 1; log(f'  + CREATE  {title}  ({track_count} tracks)')
            else:
                updated += 1; log(f'  ~ UPDATE  {title}  ({track_count} tracks)')
        else:
            track_count = prev.get('trackCount', leaf)
            unchanged += 1

        new_state[key] = {'updatedAt': upd, 'leafCount': leaf, 'm3u': m3u,
                          'name': title, 'trackCount': track_count, 'createDate': create_date}
        entries.append((title, build_entry_xml(
            title, key, m3u, track_count, create_date=create_date, last_used=now_iso)))
        synced_names.add(title.lower())

    # ── DELETE: playlists removed from Plex -> drop their .m3u ──────────────
    deleted = 0
    for key, prev in state.items():
        if key not in live_keys:
            deleted += 1
            log(f'  - DELETE  {prev.get("name", key)}')
            mp = prev.get('m3u')
            if mp and not args.dry_run:
                pending_m3u_removals.add(os.path.normpath(mp))

    vlog(f'Plex playlists: {len(pls)} (created {created}, updated {updated}, '
         f'unchanged {unchanged}, skipped {skipped}, deleted {deleted}).')

    # ── Merge into ServerPlaylists.xml ──────────────────────────────────────
    ET.register_namespace('xsd', XSD)
    ET.register_namespace('xsi', XSI)
    wrote_xml = False
    orphans = 0
    with playlist_xml_lock(DATA_DIR, exclusive=True):
        tree = ET.parse(SERVER_PLAYLISTS)
        root = tree.getroot()

        removed_plex = removed_dupe = kept = 0
        for entry in list(root.findall('Entry')):
            key = entry.find('Key')
            if key is None:
                continue
            src = (key.findtext('SourceName') or '').strip().lower()
            nm = (key.findtext('Name') or '').strip().lower()
            if src == SOURCE_MARKER:
                root.remove(entry); removed_plex += 1
            elif nm in synced_names:
                root.remove(entry); removed_dupe += 1
            else:
                kept += 1

        for _, xml_str in entries:
            root.append(ET.fromstring(xml_str))

        vlog(f'Existing rows: kept {kept} My-Media, replaced {removed_dupe} name-dupes, '
             f'rebuilt {removed_plex} prior Plex rows.')

        changed_anything = bool(created or updated or deleted or removed_dupe
                                or removed_plex != len(entries) or dates_refreshed > 0)
        if args.dry_run:
            log('DRY RUN — no files written.')
            return 0

        if changed_anything or removed_plex != len(entries):
            bak = f'{SERVER_PLAYLISTS}.{_dt.datetime.now():%Y%m%d-%H%M%S}.bak'
            shutil.copy2(SERVER_PLAYLISTS, bak)
            # Atomic write (temp file + rename) so a crash never leaves a
            # truncated ServerPlaylists.xml behind.
            tmp = SERVER_PLAYLISTS + '.tmp'
            tree.write(tmp, xml_declaration=True, encoding='utf-8')
            os.replace(tmp, SERVER_PLAYLISTS)
            wrote_xml = True
            log(f'Wrote ServerPlaylists.xml ({len(root.findall("Entry"))} entries). Backup: {bak}')
        else:
            vlog('No playlist changes; ServerPlaylists.xml left untouched.')

        # Orphan prune is quick (directory scan only) — keep under the lock so
        # the XML view matches what we just wrote.
        orphans = prune_orphan_m3us(root, PLAYLIST_DIR)

    # Heavy I/O (network-mount .m3u writes, SQL mirror) runs after the lock is
    # released so live /api/playlists reads are not blocked for minutes.
    if pending_m3u_removals or pending_m3u_writes:
        for rm in pending_m3u_removals:
            if os.path.isfile(rm):
                try:
                    os.remove(rm)
                except OSError:
                    pass
        for m3u_path, tracks in pending_m3u_writes.items():
            write_m3u(m3u_path, tracks)
    if orphans:
        log(f'Pruned {orphans} orphan .m3u file(s).')
    # Save the cache only after the XML is on disk, so a crash can't leave
    # the cache ahead of the XML (which would skip the re-sync next run).
    save_state(new_state)
    if pending_m3u_writes:
        mirror_playlists_to_sql(pending_m3u_writes, new_state)

    # Sidecar rebuild takes its own shared lock — must run after the exclusive
    # lock above is released or it would deadlock against ourselves.
    if wrote_xml:
        try:
            import catalog_cache
            catalog_cache.rebuild_playlists_index_from_xml(DATA_DIR, SERVER_PLAYLISTS)
        except Exception as ex:
            log(f'playlist index sidecar: {ex}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
