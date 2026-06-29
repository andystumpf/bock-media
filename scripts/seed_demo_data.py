#!/usr/bin/env python3
"""Seed a self-contained demo dataset so you can run Bock Media and explore
every page without a real music library or any Alexa hardware.

It creates, under a base directory (default: ./demo-data):
  - music_organizer.db   SQLite index (table `songs_cache`) of fictional tracks
  - ServerPlaylists.xml   a handful of demo playlists
  - WatchFolders.xml      one watch folder pointing at the (empty) music root
  - Preferences.xml       library settings shown on the Settings page

…and, in the repo root (where server.py keeps its runtime state):
  - devices.json          a few named Echoes + one unnamed device
  - automations.json      sample scheduled jobs (powers Automation screenshots)
  - streaming_history.jsonl  ~30 days of synthetic listens (powers Analytics)
  - nowplaying_state.json a couple of "currently playing" rows

Usage:
    python3 scripts/seed_demo_data.py                 # writes ./demo-data
    python3 scripts/seed_demo_data.py --base /tmp/bm  # custom location

Then run the server against it:
    OURMEDIA_DATA_DIR=$PWD/demo-data \
    OURMEDIA_DB_PATH=$PWD/demo-data/music_organizer.db \
    OURMEDIA_MUSIC_ROOT=$PWD/demo-data/music \
    PORT=3001 python3 server.py

NOTE: This data is entirely fictional. The track files do not need to exist on
disk — the library pages read from the SQLite index only.
"""
import argparse
import datetime as dt
import json
import os
import random
import sqlite3
import uuid

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Fictional library --------------------------------------------------------
LIBRARY = [
    ("The Midnight Echoes", "Neon Horizon",   2019, "Synthwave", [
        "Afterglow", "City Lights", "Velocity", "Chrome Hearts", "Night Drive"]),
    ("The Midnight Echoes", "Static Bloom",   2022, "Synthwave", [
        "Static Bloom", "Parallel", "Ghost Signal", "Reverie"]),
    ("Solar Fields Trio",   "Open Sky",       2015, "Jazz", [
        "Sunrise Waltz", "Blue Coast", "Slow Train", "Harbor Lights", "Last Call"]),
    ("Solar Fields Trio",   "Late Set",       2018, "Jazz", [
        "Midnight Blue", "Two Cents", "After Hours"]),
    ("Neon Cassette",       "Analog Dreams",  1987, "Pop", [
        "Heartline", "Plastic Summer", "Forever Young Tonight", "Replay"]),
    ("River & Stone",       "Tall Pines",     2011, "Folk", [
        "Tall Pines", "Riverbed", "Old Highway", "Lantern", "Homeward"]),
    ("River & Stone",       "Driftwood",      2014, "Folk", [
        "Driftwood", "Salt Air", "Gravel Road"]),
    ("Velvet Hours",        "Slow Burn",      2020, "R&B", [
        "Slow Burn", "Honey", "Indigo", "Closer"]),
    ("Paper Planes",        "Altitude",       2009, "Rock", [
        "Takeoff", "Tailwind", "Freefall", "Cloudbank", "Touchdown"]),
    ("Aurora Skies",        "Polar",          2024, "Ambient", [
        "Aurora", "Glacier", "Stillness", "Long Night"]),
    ("Crimson Avenue",      "Downtown",       1995, "Rock", [
        "Downtown Lights", "Brick & Mortar", "Last Train", "Neon Sign", "Closing Time"]),
    ("Crimson Avenue",      "Uptown",         1998, "Rock", [
        "Uptown", "Marquee", "Sidewalk"]),
    ("Glass Animals Co.",   "Terrarium",      2017, "Electronic", [
        "Fern", "Moss", "Canopy", "Dewpoint"]),
    ("The Lantern Club",    "Embers",         2013, "Indie", [
        "Embers", "Matchstick", "Smoke Signals", "Firefly"]),
]

PLAYLISTS = [
    ("Morning Coffee", 24, False),
    ("Road Trip", 58, True),
    ("Focus Flow", 31, False),
    ("Late Night Jazz", 18, False),
    ("Workout Energy", 42, True),
    ("Sunday Chill", 27, False),
    ("90s Throwback", 36, True),
]

DEVICES = [
    ("Kitchen Show", "G2A0...K1TC", True),
    ("Living Room", "G091...LVRM", True),
    ("Office", "H4B2...OFFC", True),
    ("Bedroom", "J7C5...BDRM", True),
    ("Echo 7K2L9P", None, False),   # intentionally unnamed -> Fix-my-devices demo
]


def music_root(base):
    return os.path.join(base, "music")


def build_tracks(base):
    """Return list of dicts mirroring songs_cache rows."""
    root = music_root(base)
    rows = []
    sid = 1
    for artist, album, year, genre, titles in LIBRARY:
        for i, title in enumerate(titles, 1):
            safe = lambda s: s.replace("/", "-")
            path = os.path.join(root, safe(artist), safe(album),
                                 f"{i:02d} {safe(title)}.mp3")
            rows.append({
                "id": sid,
                "title": title,
                "artist": artist,
                "album_artist": artist,
                "album": album,
                "genre": genre,
                "year": year,
                "duration_seconds": random.randint(150, 330),
                "bitrate": random.choice([256, 320, 320, 1000]),
                "track_number": i,
                "path": path,
            })
            sid += 1
    return rows


def write_db(base, tracks):
    db_path = os.path.join(base, "music_organizer.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    con = sqlite3.connect(db_path)
    con.execute("""
        CREATE TABLE songs_cache (
            id INTEGER PRIMARY KEY,
            title TEXT, artist TEXT, album_artist TEXT, album TEXT,
            genre TEXT, year INTEGER, duration_seconds INTEGER,
            bitrate INTEGER, track_number INTEGER, path TEXT
        )""")
    con.executemany(
        "INSERT INTO songs_cache (id,title,artist,album_artist,album,genre,"
        "year,duration_seconds,bitrate,track_number,path) VALUES "
        "(:id,:title,:artist,:album_artist,:album,:genre,:year,"
        ":duration_seconds,:bitrate,:track_number,:path)", tracks)
    con.commit()
    con.close()
    return db_path


def _el(tag, text):
    return f"  <{tag}>{text}</{tag}>"


def write_playlists_xml(base):
    now = dt.datetime.now()
    entries = []
    for i, (name, count, shuffle) in enumerate(PLAYLISTS):
        created = (now - dt.timedelta(days=200 - i * 12)).isoformat()
        used = (now - dt.timedelta(days=random.randint(0, 9))).isoformat()
        pid = str(uuid.uuid5(uuid.NAMESPACE_DNS, "demo-playlist:" + name))
        entries.append(f"""<Entry>
 <Key>
  <Name>{name}</Name>
  <ID>{pid}</ID>
  <TrackCount>{count}</TrackCount>
  <Shuffle>{'true' if shuffle else 'false'}</Shuffle>
  <Loop>false</Loop>
  <CreateDate>{created}</CreateDate>
  <LastUsed>{used}</LastUsed>
  <SourceID>demo</SourceID>
  <IsAudioBook>false</IsAudioBook>
 </Key>
</Entry>""")
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<ServerPlaylists>\n'
           + "\n".join(entries) + "\n</ServerPlaylists>\n")
    with open(os.path.join(base, "ServerPlaylists.xml"), "w") as f:
        f.write(xml)


def write_watchfolders_xml(base):
    root = music_root(base)
    os.makedirs(root, exist_ok=True)
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<WatchFolders>
 <WatchFolder>
  <Guid>{uuid.uuid4()}</Guid>
  <Path>{root}</Path>
  <Label>Music Library</Label>
  <Count>{sum(len(a[4]) for a in LIBRARY)}</Count>
  <Errors>0</Errors>
  <Type>music</Type>
 </WatchFolder>
</WatchFolders>
"""
    with open(os.path.join(base, "WatchFolders.xml"), "w") as f:
        f.write(xml)


def write_preferences_xml(base):
    xml = """<?xml version="1.0" encoding="utf-8"?>
<Preferences>
 <Label>Bock Media</Label>
 <PairedUser>demo</PairedUser>
 <DefaultPlaylist>Morning Coffee</DefaultPlaylist>
 <DefaultPlaylistShuffle>true</DefaultPlaylistShuffle>
 <WatchFolderPollHours>6</WatchFolderPollHours>
 <TranscodeBitrate>320</TranscodeBitrate>
 <FFmpegLocation>/usr/bin/ffmpeg</FFmpegLocation>
 <FlacSupport>true</FlacSupport>
 <ReplayGain>false</ReplayGain>
 <RequirePassword>false</RequirePassword>
 <AutoImportPlaylists>true</AutoImportPlaylists>
 <SuppressAutoScan>false</SuppressAutoScan>
 <SendAlbumArt>true</SendAlbumArt>
 <SendMetadata>true</SendMetadata>
 <VerboseLogging>false</VerboseLogging>
 <ScanIgnoreFiles>false</ScanIgnoreFiles>
 <BypassProxy>false</BypassProxy>
 <AllowExternalAccess>true</AllowExternalAccess>
 <WebPassword></WebPassword>
</Preferences>
"""
    with open(os.path.join(base, "Preferences.xml"), "w") as f:
        f.write(xml)


def write_devices(state_dir):
    now = dt.datetime.now().timestamp()
    store = {}
    for name, serial, named in DEVICES:
        did = "amzn1.ask.device." + uuid.uuid4().hex.upper()[:52]
        entry = {
            "name": name,
            "firstSeen": now - random.randint(20, 90) * 86400,
            "lastSeen": now - random.randint(0, 3) * 3600,
        }
        if serial:
            entry["serial"] = serial
        if not named:
            # leave name as the auto-generated style so the UI flags it
            entry["name"] = name
            entry["auto"] = True
        store[did] = entry
    path = os.path.join(state_dir, "devices.json")
    with open(path, "w") as f:
        json.dump(store, f, indent=2)
    return store


def write_history(state_dir, tracks, devices):
    """~30 days of synthetic listens, weighted toward evenings/weekends."""
    named_devices = [(did, e["name"]) for did, e in devices.items()
                     if not e.get("auto")]
    rows = []
    now = dt.datetime.now()
    for d in range(30):
        day = now - dt.timedelta(days=d)
        # more plays on weekends
        n = random.randint(4, 9) + (4 if day.weekday() >= 5 else 0)
        for _ in range(n):
            t = random.choice(tracks)
            did, dname = random.choice(named_devices)
            hour = random.choices(range(24),
                                  weights=[1,1,1,1,1,2,4,6,5,4,4,5,6,5,4,5,
                                           7,9,11,12,10,8,5,3])[0]
            ts = day.replace(hour=hour, minute=random.randint(0, 59),
                             second=random.randint(0, 59), microsecond=0)
            rows.append({
                "date": ts.isoformat(),
                "track": t["title"],
                "artist": t["artist"],
                "album": t["album"],
                "filepath": t["path"],
                "device": dname,
                "deviceId": did,
                "genre": t["genre"],
                "year": t["year"],
            })
    rows.sort(key=lambda r: r["date"])
    path = os.path.join(state_dir, "streaming_history.jsonl")
    with open(path, "w") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")
    return len(rows)


def write_nowplaying(state_dir, tracks, devices):
    named = [(did, e["name"]) for did, e in devices.items() if not e.get("auto")]
    now = dt.datetime.now().timestamp()
    devmap = {}
    picks = random.sample(tracks, 3)
    states = [(True, False), (True, False), (False, True)]  # 2 playing, 1 paused
    for (did, _name), t, (playing, paused) in zip(named, picks, states):
        devmap[did] = {
            "track": t["title"],
            "artist": t["artist"],
            "album": t["album"],
            "filepath": t["path"],
            "timestamp": now - random.randint(20, 200),
            "playing": playing,
            "paused": paused,
            "token": f"{uuid.uuid4().hex[:8]}:0",
        }
    path = os.path.join(state_dir, "nowplaying_state.json")
    with open(path, "w") as f:
        json.dump({"devices": devmap}, f, indent=2)


def write_automations(state_dir, devices):
    """Sample scheduled jobs for the Automation page (screenshots + demo browsing)."""
    named = [
        (e.get("serial") or did, e["name"])
        for did, e in devices.items()
        if not e.get("auto") and e.get("serial")
    ]
    now = dt.datetime.now().timestamp()
    specs = [
        ("Morning Coffee", "Morning Coffee", "07:30", [0, 1, 2, 3, 4], False, True, "ok start"),
        ("Weekend road trip", "Road Trip", "17:00", [5, 6], True, True, "ok mix"),
        ("Late night jazz", "Late Night Jazz", "22:15", list(range(7)), False, False, None),
    ]
    items = []
    for i, (label, pl, time_str, days, shuffle, enabled, status) in enumerate(specs):
        serial, dname = named[i % len(named)]
        item = {
            "id": str(uuid.uuid4()),
            "name": label,
            "enabled": enabled,
            "playlistId": "",
            "playlistName": pl,
            "device": serial,
            "deviceName": dname,
            "shuffle": shuffle,
            "time": time_str,
            "days": days,
            "createdAt": now - 86400 * 12,
            "updatedAt": now,
        }
        if status:
            item["lastRunAt"] = now - 1800
            item["lastRunStatus"] = status
        items.append(item)
    path = os.path.join(state_dir, "automations.json")
    with open(path, "w") as f:
        json.dump(items, f, indent=2)
    return len(items)


def write_demo_config(state_dir, *, alexa_remote=False):
    """A non-secret demo config.json so the Settings page shows sensible values.
    (config.json is gitignored, so this never gets committed.)"""
    cfg = {
        "publicUrl": "https://your-domain.example.com",
        "launchPlaylistPrompt": True,
        "identifyPlaylist": "",
    }
    if alexa_remote:
        cfg["alexaRemote"] = {
            "url": "amazon.com",
            "email": "demo@example.com",
            "password": "demo-only-not-a-real-login",
        }
    with open(os.path.join(state_dir, "config.json"), "w") as f:
        json.dump(cfg, f, indent=2)


def main():
    ap = argparse.ArgumentParser(description="Seed Bock Media demo data")
    ap.add_argument("--base", default=os.path.join(HERE, "demo-data"),
                    help="directory for the demo DB + XML (OURMEDIA_DATA_DIR)")
    ap.add_argument("--state-dir", default=HERE,
                    help="where runtime state files go (repo root by default)")
    ap.add_argument("--config", action="store_true",
                    help="also write a demo config.json (gitignored)")
    ap.add_argument("--alexa-remote", action="store_true",
                    help="with --config, include demo alexaRemote creds so Automation UI shows the create form")
    args = ap.parse_args()

    random.seed(42)
    os.makedirs(args.base, exist_ok=True)

    tracks = build_tracks(args.base)
    db = write_db(args.base, tracks)
    write_playlists_xml(args.base)
    write_watchfolders_xml(args.base)
    write_preferences_xml(args.base)
    devices = write_devices(args.state_dir)
    n_auto = write_automations(args.state_dir, devices)
    n_hist = write_history(args.state_dir, tracks, devices)
    write_nowplaying(args.state_dir, tracks, devices)
    if args.config:
        write_demo_config(args.state_dir, alexa_remote=args.alexa_remote)

    print(f"Seeded demo data:")
    print(f"  data dir : {args.base}")
    print(f"  db       : {db}  ({len(tracks)} tracks)")
    print(f"  playlists: {len(PLAYLISTS)}")
    print(f"  devices  : {len(devices)}  (state in {args.state_dir})")
    print(f"  automations: {n_auto}")
    print(f"  history  : {n_hist} listens")
    print()
    print("Run the server against it:")
    print(f"  OURMEDIA_DATA_DIR={args.base} \\")
    print(f"  OURMEDIA_DB_PATH={db} \\")
    print(f"  OURMEDIA_MUSIC_ROOT={music_root(args.base)} \\")
    print(f"  PORT=3001 python3 server.py")


if __name__ == "__main__":
    main()
