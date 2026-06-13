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
  OURMEDIA_PLEX_URL          http://localhost:32400
  OURMEDIA_PLEX_TOKEN        (else read from OURMEDIA_PLEX_PREFS)
  OURMEDIA_PLEX_PREFS        /var/lib/plexmediaserver/.../Preferences.xml
  OURMEDIA_DATA_DIR          /home/plex/.bockmedia      (holds ServerPlaylists.xml)
  OURMEDIA_MUSIC_ROOT        /mnt/bock/Music
  OURMEDIA_PLEX_PLAYLIST_DIR <MUSIC_ROOT>/exportedPlaylists/plex  (.m3u output)
"""
import argparse
import datetime as _dt
import json
import os
import re
import shutil
import sys
import uuid
import xml.etree.ElementTree as ET
from urllib.parse import quote
from urllib.request import urlopen
from xml.sax.saxutils import escape

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)
from playlist_xml_lock import playlist_xml_lock

PLEX_URL   = os.environ.get('OURMEDIA_PLEX_URL', 'http://localhost:32400').rstrip('/')
DATA_DIR   = os.environ.get('OURMEDIA_DATA_DIR', '/home/plex/.bockmedia')
MUSIC_ROOT = os.environ.get('OURMEDIA_MUSIC_ROOT', '/mnt/bock/Music')
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


def plex_get(path, token):
    url = f'{PLEX_URL}{path}{"&" if "?" in path else "?"}X-Plex-Token={quote(token)}'
    with urlopen(url, timeout=30) as r:
        return ET.fromstring(r.read())


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
            })
    return out


def fetch_tracks(rating_key, token):
    root = plex_get(f'/playlists/{rating_key}/items', token)
    paths = []
    for tr in root.findall('.//Track'):
        part = tr.find('.//Part')
        f = part.get('file') if part is not None else None
        if f and os.path.splitext(f)[1].lower() in AUDIO_EXTS:
            paths.append(f)
    return paths


def safe_filename(name):
    s = re.sub(r'[^\w\-. ]', '_', name).strip() or 'playlist'
    return s[:120]


def m3u_path_for(name, rating_key):
    return os.path.join(PLAYLIST_DIR, f'{safe_filename(name)}.{rating_key}.m3u')


def write_m3u(path, tracks):
    with open(path, 'w', encoding='utf-8') as f:
        f.write('#EXTM3U\n')
        for t in tracks:
            f.write(t + '\n')


def stable_id(rating_key):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f'plex-playlist:{rating_key}'))


def build_entry_xml(name, rating_key, m3u_path, track_count):
    now = _dt.datetime.now().astimezone().isoformat()
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
    ) % (XSI, stable_id(rating_key), str(uuid.uuid4()), escape(name), now,
         track_count, now, escape(m3u_path), SOURCE_MARKER)


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

    created = updated = unchanged = skipped = 0
    new_state = {}
    entries = []          # (name, xml_string)
    synced_names = set()

    for p in pls:
        key, title, leaf, upd = p['key'], p['title'], p['leafCount'], p['updatedAt']
        m3u = m3u_path_for(title, key)
        prev = state.get(key)
        changed = (prev is None
                   or prev.get('updatedAt') != upd
                   or prev.get('leafCount') != leaf
                   or prev.get('m3u') != m3u
                   or not os.path.isfile(m3u))

        if changed:
            tracks = [t for t in fetch_tracks(key, token) if os.path.isfile(t)]
            if len(tracks) < args.min_tracks:
                skipped += 1
                # Dropped below the threshold: delete the stale .m3u and leave
                # it out of new_state so its <Entry> is pruned from the XML.
                if not args.dry_run and prev and prev.get('m3u') and os.path.isfile(prev['m3u']):
                    try: os.remove(prev['m3u'])
                    except OSError: pass
                continue
            track_count = len(tracks)
            if not args.dry_run:
                # Plex title may have changed -> remove the old m3u path.
                if prev and prev.get('m3u') and prev['m3u'] != m3u and os.path.isfile(prev['m3u']):
                    try: os.remove(prev['m3u'])
                    except OSError: pass
                write_m3u(m3u, tracks)
            if prev is None:
                created += 1; log(f'  + CREATE  {title}  ({track_count} tracks)')
            else:
                updated += 1; log(f'  ~ UPDATE  {title}  ({track_count} tracks)')
        else:
            track_count = prev.get('trackCount', leaf)
            unchanged += 1

        new_state[key] = {'updatedAt': upd, 'leafCount': leaf, 'm3u': m3u,
                          'name': title, 'trackCount': track_count}
        entries.append((title, build_entry_xml(title, key, m3u, track_count)))
        synced_names.add(title.lower())

    # ── DELETE: playlists removed from Plex -> drop their .m3u ──────────────
    deleted = 0
    for key, prev in state.items():
        if key not in live_keys:
            deleted += 1
            log(f'  - DELETE  {prev.get("name", key)}')
            mp = prev.get('m3u')
            if mp and os.path.isfile(mp) and not args.dry_run:
                try: os.remove(mp)
                except OSError: pass

    vlog(f'Plex playlists: {len(pls)} (created {created}, updated {updated}, '
         f'unchanged {unchanged}, skipped {skipped}, deleted {deleted}).')

    # ── Merge into ServerPlaylists.xml ──────────────────────────────────────
    ET.register_namespace('xsd', XSD)
    ET.register_namespace('xsi', XSI)
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
                                or removed_plex != len(entries))
        if args.dry_run:
            log('DRY RUN — no files written.')
            return 0

        wrote_xml = False
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
        # Save the cache only after the XML is on disk, so a crash can't leave
        # the cache ahead of the XML (which would skip the re-sync next run).
        save_state(new_state)

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
