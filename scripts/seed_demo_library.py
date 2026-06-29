#!/usr/bin/env python3
"""Seed fixtures/demo-data/ with fictional library + household data for demos and screenshots.

No real names, IPs, or credentials. Safe to commit and publish.

Usage:
  python3 scripts/seed_demo_library.py
  OURMEDIA_DATA_DIR=fixtures/demo-data OURMEDIA_DB_PATH=fixtures/demo-data/songs_cache.db python3 server.py
"""
from __future__ import annotations

import json
import os
import sqlite3
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
OUT = os.path.join(REPO, 'fixtures', 'demo-data')
MUSIC_ROOT = os.path.join(OUT, 'music')


def _write_xml(path: str, root: ET.Element) -> None:
    ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


def _seed_db(db_path: str) -> None:
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    if os.path.isfile(db_path):
        os.remove(db_path)
    conn = sqlite3.connect(db_path)
    conn.execute('''
        CREATE TABLE songs_cache (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            path TEXT UNIQUE,
            title TEXT,
            artist TEXT,
            album TEXT,
            album_artist TEXT,
            genre TEXT,
            year INTEGER,
            duration_seconds REAL,
            bitrate INTEGER,
            track_number TEXT,
            first_seen_at TEXT
        )
    ''')
    tracks = [
        ('Morning Light', 'Demo Artist', 'Sample Album', 'Rock', 2019, 245),
        ('Neon Drive', 'Demo Artist', 'Sample Album', 'Rock', 2019, 198),
        ('City Rain', 'Fictional Jazz Trio', 'Blue Hour', 'Jazz', 2020, 312),
        ('Soft Focus', 'Fictional Jazz Trio', 'Blue Hour', 'Jazz', 2020, 287),
        ('Pulse Wave', 'Synth Collective', 'Digital Dreams', 'Electronic', 2021, 221),
        ('Static Bloom', 'Synth Collective', 'Digital Dreams', 'Electronic', 2021, 256),
        ('Open Road', 'Highway Echo', 'Road Trip', 'Rock', 2018, 203),
        ('Coastal Wind', 'Highway Echo', 'Road Trip', 'Rock', 2018, 234),
        ('Focus Flow', 'Study Beats', 'Deep Work', 'Ambient', 2022, 180),
        ('Night Mode', 'Study Beats', 'Deep Work', 'Ambient', 2022, 195),
        ('Demo Song', 'Sample Band', 'Greatest Hits', 'Pop', 2017, 210),
        ('Chart Example', 'Sample Band', 'Greatest Hits', 'Pop', 2017, 189),
    ]
    now = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
    for i, (title, artist, album, genre, year, dur) in enumerate(tracks, start=1):
        genre_dir = genre.lower().replace(' ', '-')
        path = f'demo/music/{genre_dir}/{artist.replace(" ", "_")}/{i:02d}-{title.replace(" ", "_")}.mp3'
        conn.execute(
            '''INSERT INTO songs_cache
               (path, title, artist, album, album_artist, genre, year, duration_seconds, bitrate, track_number, first_seen_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)''',
            (path, title, artist, album, artist, genre, year, dur, 320, str(i), now),
        )
    conn.commit()
    conn.close()


def _seed_playlists_xml(data_dir: str) -> None:
    root = ET.Element('ArrayOfEntry')
    pl_dir = os.path.join(data_dir, 'playlists')
    os.makedirs(pl_dir, exist_ok=True)
    playlist_ids = [
        ('pl-road-trip', 'Road Trip Demo', 'road-trip.m3u'),
        ('pl-daily-mix', 'Daily Mix Demo', 'daily-mix.m3u'),
        ('pl-focus', 'Focus Playlist', 'focus.m3u'),
    ]
    lines = [
        ['demo/music/rock/Highway_Echo/07-Open_Road.mp3', 'demo/music/rock/Highway_Echo/08-Coastal_Wind.mp3'],
        ['demo/music/pop/Sample_Band/11-Demo_Song.mp3', 'demo/music/electronic/Synth_Collective/05-Pulse_Wave.mp3'],
        ['demo/music/ambient/Study_Beats/09-Focus_Flow.mp3', 'demo/music/ambient/Study_Beats/10-Night_Mode.mp3'],
    ]
    for (pid, name, fname), paths in zip(playlist_ids, lines):
        m3u = os.path.join(pl_dir, fname)
        with open(m3u, 'w', encoding='utf-8') as fh:
            fh.write('#EXTM3U\n')
            for p in paths:
                fh.write(p + '\n')
        entry = ET.SubElement(root, 'Entry')
        key = ET.SubElement(entry, 'Key')
        ET.SubElement(key, 'ID').text = pid
        ET.SubElement(key, 'Name').text = name
        ET.SubElement(key, 'SourceID').text = m3u
    _write_xml(os.path.join(data_dir, 'ServerPlaylists.xml'), root)


def _seed_preferences(data_dir: str) -> None:
    root = ET.Element('Preferences')
    prefs = {
        'Label': 'Bock Media Demo',
        'DefaultPlaylist': 'Road Trip Demo',
        'RequirePassword': 'false',
        'WebUsername': 'demo',
        'WebPassword': '',
    }
    for tag, val in prefs.items():
        el = ET.SubElement(root, tag)
        el.text = val
    _write_xml(os.path.join(data_dir, 'Preferences.xml'), root)
    wf = ET.Element('WatchFolders')
    _write_xml(os.path.join(data_dir, 'WatchFolders.xml'), wf)


def _seed_json_files(data_dir: str) -> None:
    household = {
        'members': [
            {'id': 'p-parent', 'name': 'Parent', 'role': 'parent'},
            {'id': 'p-teen', 'name': 'Teen', 'role': 'kid'},
            {'id': 'p-guest', 'name': 'Guest', 'role': 'guest'},
        ],
        'deviceOwners': {
            'demo-kitchen': 'p-parent',
            'demo-office': 'p-teen',
        },
    }
    with open(os.path.join(data_dir, 'household.json'), 'w', encoding='utf-8') as fh:
        json.dump(household, fh, indent=2)

    config = {
        'publicUrl': 'https://your-tunnel.example.com',
        'launchPlaylistPrompt': False,
        'mobileApi': {
            'token': 'DEMO_TOKEN_REPLACE_IN_PRODUCTION',
            'allowExternalAccess': False,
            'allowOpenLanApi': True,
            'allowOpenLanMedia': True,
        },
        'appAbout': {
            'githubPublic': 'https://github.com/andystumpf/bock-media',
        },
    }
    with open(os.path.join(data_dir, 'config.json'), 'w', encoding='utf-8') as fh:
        json.dump(config, fh, indent=2)

    history = []
    for i, row in enumerate([
        ('Morning Light', 'Demo Artist', 'Road Trip Demo'),
        ('City Rain', 'Fictional Jazz Trio', 'Daily Mix Demo'),
        ('Pulse Wave', 'Synth Collective', 'Focus Playlist'),
    ]):
        history.append({
            'ts': f'2026-06-{20 + i}T12:00:00Z',
            'track': row[0],
            'artist': row[1],
            'playlist': row[2],
            'device': 'Kitchen Demo',
            'memberId': 'p-parent',
        })
    with open(os.path.join(data_dir, 'streaming_history.jsonl'), 'w', encoding='utf-8') as fh:
        for line in history:
            fh.write(json.dumps(line) + '\n')


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(os.path.join(OUT, 'ImageCache'), exist_ok=True)
    _seed_db(os.path.join(OUT, 'songs_cache.db'))
    _seed_playlists_xml(OUT)
    _seed_preferences(OUT)
    _seed_json_files(OUT)
    readme = os.path.join(OUT, 'README.md')
    with open(readme, 'w', encoding='utf-8') as fh:
        fh.write('''# Demo data (fictional)

Run the server against this fixture:

```bash
python3 scripts/seed_demo_library.py   # refresh
export OURMEDIA_DATA_DIR="$PWD/fixtures/demo-data"
export OURMEDIA_DB_PATH="$PWD/fixtures/demo-data/songs_cache.db"
python3 server.py
```

Open http://127.0.0.1:3001/ — all artist/album names are synthetic.
''')
    print(f'Seeded {OUT}')


if __name__ == '__main__':
    main()
