#!/usr/bin/env python3
"""Seed a self-contained demo dataset so you can run Bock Media and explore
every page without a real music library or any Alexa hardware.

It creates, under a base directory (default: ./demo-data):
  - music_organizer.db   SQLite index (table `songs_cache`) of well-known tracks
  - ServerPlaylists.xml   demo playlists (with real .m3u track lists)
  - playlists/*.m3u       the playlist track lists
  - WatchFolders.xml      one watch folder pointing at the music root
  - Preferences.xml       library settings shown on the Settings page
  - household.json        demo family profiles (parent / kid / guest)
  - devices.json          a few named Echoes + one unnamed device
  - automations.json      sample scheduled jobs (powers Automation screenshots)

…and, in the state dir (repo root by default — where server.py keeps runtime state):
  - streaming_history.jsonl  ~30 days of synthetic listens (powers Analytics)
  - nowplaying_state.json a couple of "currently playing" rows

The library uses real, well-known artists/albums/tracks so artwork resolves to
real album art at runtime via the iTunes Search API tier — but the *listening
history, devices, and household are entirely synthetic*. No personal data.

Usage:
    python3 scripts/seed_demo_data.py                 # writes ./demo-data
    python3 scripts/seed_demo_data.py --base /tmp/bm  # custom location
    python3 scripts/seed_demo_data.py --write-audio   # also render playable
                                                      # (silent) tagged MP3s

Then run the server against it:
    OURMEDIA_DATA_DIR=$PWD/demo-data \
    OURMEDIA_DB_PATH=$PWD/demo-data/music_organizer.db \
    OURMEDIA_MUSIC_ROOT=$PWD/demo-data/music \
    PORT=3001 python3 server.py

Without --write-audio the track files do not exist on disk — library pages read
from the SQLite index only. With --write-audio (needs ffmpeg) every track is a
real, silent, ID3-tagged MP3 so /stream, phone playback, and artwork resolution
all work end-to-end.
"""
import argparse
import datetime as dt
import json
import os
import random
import shutil
import sqlite3
import subprocess
import uuid
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Demo library — real, well-known albums (metadata only; audio is generated
# silence). Artwork resolves via the iTunes Search API at runtime.
LIBRARY = [
    ("Fleetwood Mac", "Rumours", 1977, "Rock", [
        "Second Hand News", "Dreams", "Never Going Back Again", "Don't Stop",
        "Go Your Own Way", "Songbird", "The Chain", "You Make Loving Fun"]),
    ("Michael Jackson", "Thriller", 1982, "Pop", [
        "Wanna Be Startin' Somethin'", "Thriller", "Beat It", "Billie Jean",
        "Human Nature", "P.Y.T. (Pretty Young Thing)"]),
    ("Eagles", "Hotel California", 1976, "Rock", [
        "Hotel California", "New Kid in Town", "Life in the Fast Lane",
        "Wasted Time", "Try and Love Again"]),
    ("Miles Davis", "Kind of Blue", 1959, "Jazz", [
        "So What", "Freddie Freeloader", "Blue in Green", "All Blues",
        "Flamenco Sketches"]),
    ("Daft Punk", "Random Access Memories", 2013, "Electronic", [
        "Give Life Back to Music", "The Game of Love", "Instant Crush",
        "Lose Yourself to Dance", "Get Lucky", "Doin' It Right"]),
    ("Toto", "Toto IV", 1982, "Rock", [
        "Rosanna", "Make Believe", "I Won't Hold You Back", "Africa"]),
    ("Daryl Hall & John Oates", "Private Eyes", 1981, "Pop", [
        "Private Eyes", "I Can't Go for That (No Can Do)", "Did It in a Minute",
        "Head Above Water"]),
    ("Steely Dan", "Aja", 1977, "Rock", [
        "Black Cow", "Aja", "Deacon Blues", "Peg", "Home at Last", "Josie"]),
    ("Norah Jones", "Come Away with Me", 2002, "Jazz", [
        "Don't Know Why", "Seven Years", "Come Away with Me", "Turn Me On",
        "The Nearness of You"]),
    ("Johnny Cash", "At Folsom Prison", 1968, "Country", [
        "Folsom Prison Blues", "Dark as the Dungeon", "I Still Miss Someone",
        "Cocaine Blues", "Jackson"]),
    ("Nirvana", "Nevermind", 1991, "Rock", [
        "Smells Like Teen Spirit", "Come as You Are", "Lithium", "In Bloom",
        "Something in the Way"]),
    ("Pearl Jam", "Ten", 1991, "Rock", [
        "Daughter - Remastered", "Alive", "Even Flow", "Black", "Jeremy"]),
    ("Adele", "21", 2011, "Pop", [
        "Rolling in the Deep", "Rumour Has It", "Set Fire to the Rain",
        "Someone Like You"]),
    ("Bob Marley & The Wailers", "Legend", 1984, "Reggae", [
        "Is This Love", "No Woman, No Cry", "Could You Be Loved",
        "Three Little Birds", "Buffalo Soldier", "Jamming"]),
    ("Queen", "A Night at the Opera", 1975, "Rock", [
        "You're My Best Friend", "'39", "Bohemian Rhapsody", "Love of My Life"]),
    ("The Beach Boys", "Pet Sounds", 1966, "Pop", [
        "Wouldn't It Be Nice", "Sloop John B", "God Only Knows", "Caroline, No"]),
    ("Stevie Wonder", "Songs in the Key of Life", 1976, "R&B", [
        "Sir Duke", "I Wish", "Knocks Me Off My Feet", "Isn't She Lovely", "As"]),
    ("Amy Winehouse", "Back to Black", 2006, "R&B", [
        "Rehab", "You Know I'm No Good", "Back to Black",
        "Love Is a Losing Game", "Tears Dry on Their Own"]),
    ("Dire Straits", "Brothers in Arms", 1985, "Rock", [
        "So Far Away", "Money for Nothing", "Walk of Life", "Your Latest Trick",
        "Brothers in Arms"]),
    ("Gillian Welch", "Soul Journey", 2003, "Folk", [
        "Look at Miss Ohio", "Make Me a Pallet on Your Floor",
        "Wayside / Back in Time", "One Little Song", "Wrecking Ball"]),
    ("Kacey Musgraves", "Golden Hour", 2018, "Country", [
        "Slow Burn", "Butterflies", "Space Cowboy", "Golden Hour", "Rainbow"]),
    ("Tame Impala", "Currents", 2015, "Alternative", [
        "Let It Happen", "The Moment", "Eventually",
        "The Less I Know the Better", "New Person, Same Old Mistakes"]),
]

# Playlists: (name, shuffle, [(artist, title), …]) — resolved to seeded tracks.
PLAYLIST_DEFS = [
    ("Morning Coffee", False, [
        ("Norah Jones", "Don't Know Why"), ("Norah Jones", "Come Away with Me"),
        ("Fleetwood Mac", "Songbird"), ("The Beach Boys", "God Only Knows"),
        ("Stevie Wonder", "Isn't She Lovely"), ("Kacey Musgraves", "Golden Hour"),
        ("Gillian Welch", "Look at Miss Ohio"), ("Bob Marley & The Wailers", "Three Little Birds"),
        ("Norah Jones", "The Nearness of You"), ("Kacey Musgraves", "Rainbow"),
    ]),
    ("Road Trip", True, [
        ("Fleetwood Mac", "Go Your Own Way"), ("Eagles", "Life in the Fast Lane"),
        ("Toto", "Africa"), ("Dire Straits", "Walk of Life"),
        ("Toto", "Rosanna"), ("Dire Straits", "Money for Nothing"),
        ("Eagles", "Hotel California"), ("Queen", "You're My Best Friend"),
        ("Tame Impala", "Let It Happen"), ("Johnny Cash", "Folsom Prison Blues"),
        ("Fleetwood Mac", "The Chain"), ("Steely Dan", "Josie"),
    ]),
    ("Yacht Rock", False, [
        ("Daryl Hall & John Oates", "Private Eyes"),
        ("Daryl Hall & John Oates", "I Can't Go for That (No Can Do)"),
        ("Steely Dan", "Peg"), ("Steely Dan", "Black Cow"),
        ("Toto", "Rosanna"), ("Toto", "Africa"),
        ("Eagles", "New Kid in Town"), ("Steely Dan", "Deacon Blues"),
    ]),
    ("Focus Flow", False, [
        ("Miles Davis", "So What"), ("Miles Davis", "Blue in Green"),
        ("Tame Impala", "Eventually"), ("Miles Davis", "Flamenco Sketches"),
        ("Norah Jones", "Seven Years"), ("Tame Impala", "The Moment"),
        ("Miles Davis", "All Blues"),
    ]),
    ("Late Night Jazz", False, [
        ("Miles Davis", "So What"), ("Miles Davis", "Freddie Freeloader"),
        ("Norah Jones", "Turn Me On"), ("Miles Davis", "Blue in Green"),
        ("Norah Jones", "The Nearness of You"), ("Amy Winehouse", "Love Is a Losing Game"),
    ]),
    ("Workout Energy", True, [
        ("Michael Jackson", "Beat It"), ("Michael Jackson", "Billie Jean"),
        ("Daft Punk", "Get Lucky"), ("Daft Punk", "Lose Yourself to Dance"),
        ("Adele", "Rolling in the Deep"), ("Tame Impala", "The Less I Know the Better"),
        ("Michael Jackson", "Wanna Be Startin' Somethin'"), ("Daft Punk", "Doin' It Right"),
    ]),
    ("Sunday Chill", False, [
        ("Fleetwood Mac", "Dreams"), ("The Beach Boys", "God Only Knows"),
        ("Norah Jones", "Come Away with Me"), ("Kacey Musgraves", "Slow Burn"),
        ("Miles Davis", "Blue in Green"), ("Gillian Welch", "One Little Song"),
        ("Bob Marley & The Wailers", "Is This Love"), ("Stevie Wonder", "As"),
    ]),
    ("90s Throwback", True, [
        ("Nirvana", "Smells Like Teen Spirit"), ("Pearl Jam", "Alive"),
        ("Nirvana", "Come as You Are"), ("Pearl Jam", "Even Flow"),
        ("Nirvana", "Lithium"), ("Pearl Jam", "Black"),
        ("Nirvana", "In Bloom"), ("Pearl Jam", "Jeremy"),
    ]),
]

# Deterministic small playlist for mobile UI tests (see shared/fixtures/ui_test_manifest.json).
UITEST_PLAYLIST = ("UITest Small", False, [
    ("Gillian Welch", "Look at Miss Ohio"), ("Gillian Welch", "One Little Song"),
    ("Gillian Welch", "Wrecking Ball"), ("Norah Jones", "Don't Know Why"),
    ("Fleetwood Mac", "Dreams"),
])

HOUSEHOLD = {
    "members": [
        {"id": "p-alex",  "name": "Alex",  "role": "parent"},
        {"id": "p-jamie", "name": "Jamie", "role": "kid"},
        {"id": "p-guest", "name": "Guest", "role": "guest"},
    ],
    "deviceOwners": {},
}

DEVICES = [
    ("Kitchen Show", "G2A0...K1TC", True),
    ("Living Room", "G091...LVRM", True),
    ("Office", "H4B2...OFFC", True),
    ("Bedroom", "J7C5...BDRM", True),
    ("Echo 7K2L9P", None, False),   # intentionally unnamed -> Fix-my-devices demo
]


def music_root(base):
    return os.path.join(base, "music")


# Public-safe path prefix used in the SQLite index + WatchFolders.xml so
# screenshots never show a personal home directory. Files are written under
# music_root(base); a symlink at this prefix (created by --write-audio /
# capture scripts) makes streaming + status checks work locally.
PUBLIC_MUSIC_ROOT = "/Users/Shared/bock-media/music"


def ensure_public_music_symlink(base):
    """Point PUBLIC_MUSIC_ROOT at the real demo music dir when possible."""
    real = music_root(base)
    os.makedirs(real, exist_ok=True)
    parent = os.path.dirname(PUBLIC_MUSIC_ROOT)
    try:
        os.makedirs(parent, exist_ok=True)
        if os.path.islink(PUBLIC_MUSIC_ROOT) or os.path.exists(PUBLIC_MUSIC_ROOT):
            if os.path.realpath(PUBLIC_MUSIC_ROOT) == os.path.realpath(real):
                return
            if os.path.islink(PUBLIC_MUSIC_ROOT):
                os.unlink(PUBLIC_MUSIC_ROOT)
        os.symlink(real, PUBLIC_MUSIC_ROOT)
    except OSError as e:
        print(f"note: could not create {PUBLIC_MUSIC_ROOT} → {real}: {e}")


def build_tracks(base):
    """Return list of dicts mirroring songs_cache rows.

    On-disk files live under music_root(base); indexed paths use
    PUBLIC_MUSIC_ROOT so the UI never shows a personal home directory.
    """
    rows = []
    sid = 1
    for artist, album, year, genre, titles in LIBRARY:
        for i, title in enumerate(titles, 1):
            safe = lambda s: s.replace("/", "-")
            rel = os.path.join(safe(artist), safe(album), f"{i:02d} {safe(title)}.mp3")
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
                "path": os.path.join(PUBLIC_MUSIC_ROOT, rel),
                "_disk_path": os.path.join(music_root(base), rel),
            })
            sid += 1
    return rows


def write_audio_files(tracks, seconds=30):
    """Render each track as a silent, ID3-tagged MP3 so /stream, phone playback
    and the artwork pipeline work end-to-end. Requires ffmpeg."""
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise SystemExit("--write-audio requires ffmpeg on PATH")

    def render(t):
        disk = t.get("_disk_path") or t["path"]
        os.makedirs(os.path.dirname(disk), exist_ok=True)
        if os.path.isfile(disk):
            return
        subprocess.run([
            ffmpeg, "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
            "-t", str(seconds),
            "-codec:a", "libmp3lame", "-q:a", "9",
            "-metadata", f"title={t['title']}",
            "-metadata", f"artist={t['artist']}",
            "-metadata", f"album_artist={t['artist']}",
            "-metadata", f"album={t['album']}",
            "-metadata", f"genre={t['genre']}",
            "-metadata", f"date={t['year']}",
            "-metadata", f"track={t['track_number']}",
            "-id3v2_version", "3",
            disk,
        ], check=True)

    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(render, tracks))


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
    rows = [{k: v for k, v in t.items() if k != "_disk_path"} for t in tracks]
    con.executemany(
        "INSERT INTO songs_cache (id,title,artist,album_artist,album,genre,"
        "year,duration_seconds,bitrate,track_number,path) VALUES "
        "(:id,:title,:artist,:album_artist,:album,:genre,:year,"
        ":duration_seconds,:bitrate,:track_number,:path)", rows)
    con.commit()
    con.close()
    return db_path


def _el(tag, text):
    return f"  <{tag}>{text}</{tag}>"


def _track_index(tracks):
    return {(t["artist"].lower(), t["title"].lower()): t for t in tracks}


def write_playlists_xml(base, tracks, include_uitest=True):
    """Write ServerPlaylists.xml plus one .m3u per playlist so detail pages,
    covers, and playback all resolve real tracks."""
    now = dt.datetime.now()
    index = _track_index(tracks)
    pl_dir = os.path.join(base, "playlists")
    os.makedirs(pl_dir, exist_ok=True)

    defs = list(PLAYLIST_DEFS) + ([UITEST_PLAYLIST] if include_uitest else [])
    entries = []
    for i, (name, shuffle, picks) in enumerate(defs):
        paths = []
        for artist, title in picks:
            t = index.get((artist.lower(), title.lower()))
            if t:
                paths.append(t["path"])
        slug = "".join(c if c.isalnum() else "-" for c in name.lower()).strip("-")
        m3u_path = os.path.join(pl_dir, f"{slug}.m3u")
        with open(m3u_path, "w") as f:
            f.write("#EXTM3U\n")
            for p in paths:
                f.write(p + "\n")

        created = (now - dt.timedelta(days=200 - i * 12)).isoformat()
        used = (now - dt.timedelta(days=random.randint(0, 9))).isoformat()
        pid = str(uuid.uuid5(uuid.NAMESPACE_DNS, "demo-playlist:" + name))
        entries.append(f"""<Entry>
 <Key>
  <Name>{name}</Name>
  <ID>{pid}</ID>
  <TrackCount>{len(paths)}</TrackCount>
  <Shuffle>{'true' if shuffle else 'false'}</Shuffle>
  <Loop>false</Loop>
  <CreateDate>{created}</CreateDate>
  <LastUsed>{used}</LastUsed>
  <SourceID>{m3u_path}</SourceID>
  <SourceName>bockmedia</SourceName>
  <IsAudioBook>false</IsAudioBook>
 </Key>
</Entry>""")
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<ServerPlaylists>\n'
           + "\n".join(entries) + "\n</ServerPlaylists>\n")
    with open(os.path.join(base, "ServerPlaylists.xml"), "w") as f:
        f.write(xml)
    return len(defs)


def write_watchfolders_xml(base):
    root = music_root(base)
    os.makedirs(root, exist_ok=True)
    xml = f"""<?xml version="1.0" encoding="utf-8"?>
<WatchFolders>
 <WatchFolder>
  <Guid>{uuid.uuid4()}</Guid>
  <Path>{PUBLIC_MUSIC_ROOT}</Path>
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


def write_household(base, devices):
    """Demo family profiles + room ownership (powers Family page and the
    mobile "Who's listening?" picker)."""
    household = json.loads(json.dumps(HOUSEHOLD))  # deep copy
    named = [did for did, e in devices.items() if not e.get("auto")]
    if len(named) >= 2:
        household["deviceOwners"] = {named[0]: "p-alex", named[2 % len(named)]: "p-jamie"}
    with open(os.path.join(base, "household.json"), "w") as f:
        json.dump(household, f, indent=2)


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
    index = _track_index(tracks)
    wanted = [("Fleetwood Mac", "Dreams"), ("Daft Punk", "Get Lucky"),
              ("Miles Davis", "So What")]
    picks = [index[(a.lower(), t.lower())] for a, t in wanted if (a.lower(), t.lower()) in index]
    while len(picks) < 3:
        picks.append(random.choice(tracks))
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


def write_demo_config(state_dir, *, alexa_remote=False, mobile_token="demo"):
    """A non-secret demo config.json so the Settings page shows sensible values.
    (config.json is gitignored, so this never gets committed.)"""
    cfg = {
        "publicUrl": "https://your-domain.example.com",
        "launchPlaylistPrompt": True,
        "identifyPlaylist": "",
        # Let the demo server accept LAN API reads and the mobile apps connect.
        "mobileApi": {
            "token": mobile_token,
            "allowExternalAccess": False,
            "allowOpenLanApi": True,
            "allowOpenLanMedia": True,
        },
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
    ap.add_argument("--mobile-token", default="demo",
                    help="mobileApi.token written with --config (default: demo)")
    ap.add_argument("--write-audio", action="store_true",
                    help="render every track as a silent tagged MP3 (needs ffmpeg)")
    ap.add_argument("--skip-uitest-playlist", action="store_true",
                    help="omit the 'UITest Small' playlist (for screenshots)")
    args = ap.parse_args()

    random.seed(42)
    os.makedirs(args.base, exist_ok=True)
    ensure_public_music_symlink(args.base)

    tracks = build_tracks(args.base)
    db = write_db(args.base, tracks)
    n_pl = write_playlists_xml(args.base, tracks,
                               include_uitest=not args.skip_uitest_playlist)
    write_watchfolders_xml(args.base)
    write_preferences_xml(args.base)
    devices = write_devices(args.base)
    write_household(args.base, devices)
    n_auto = write_automations(args.base, devices)
    n_hist = write_history(args.state_dir, tracks, devices)
    write_nowplaying(args.state_dir, tracks, devices)
    if args.write_audio:
        write_audio_files(tracks)
    if args.config:
        # server.py reads config.json from OURMEDIA_DATA_DIR
        write_demo_config(args.base, alexa_remote=args.alexa_remote,
                          mobile_token=args.mobile_token)

    print(f"Seeded demo data:")
    print(f"  data dir : {args.base}")
    print(f"  db       : {db}  ({len(tracks)} tracks)")
    print(f"  playlists: {n_pl}")
    print(f"  devices  : {len(devices)}")
    print(f"  automations: {n_auto}")
    print(f"  history  : {n_hist} listens  (state in {args.state_dir})")
    if args.write_audio:
        print(f"  audio    : {len(tracks)} silent tagged MP3s under {music_root(args.base)}")
    print()
    print("Run the server against it:")
    print(f"  OURMEDIA_DATA_DIR={args.base} \\")
    print(f"  OURMEDIA_DB_PATH={db} \\")
    print(f"  OURMEDIA_MUSIC_ROOT={music_root(args.base)} \\")
    print(f"  PORT=3001 python3 server.py")


if __name__ == "__main__":
    main()
