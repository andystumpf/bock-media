# MusicBrainz Picard — tagging the long tail

ourMedia cannot invent metadata for files with empty embedded tags (~46k tracks).
**Picard** looks up MusicBrainz and writes tags into the audio files. Then
`scripts/after_picard.sh` copies those tags into `songs_cache` (without changing
your `[YEAR] Album Name` album column in the DB).

## Prerequisites

- Picard installed (this host: `picard --version` → 2.7.x from Ubuntu)
- **Backup** the music library before bulk saves
- `config.json` / DB paths unchanged

## 1. Build a work queue

```bash
cd ~/Documents/github/ourMedia
python3 scripts/picard_queue.py --fast    # recommended on large/NFS libraries
```

Outputs under `~/.bockmedia/` (or `OURMEDIA_DATA_DIR`):

| File | Purpose |
|------|---------|
| `picard-queue-paths.txt` | Every untagged path (one per line) |
| `picard-queue-dirs.tsv` | Parent folders ranked by count — **work through these** |

Options:

```bash
python3 scripts/picard_queue.py --mode genre          # only missing genre
python3 scripts/picard_queue.py --mode album_artist   # only missing album artist
python3 scripts/picard_queue.py --limit-dirs 30       # top 30 folders only
```

## 2. Picard settings (once) — Picard 2.7

Open the settings dialog: menu bar **Options → Options…** (not the top-level “Options” actions like Save). You get a window with a **tree on the left** and settings on the right.

**There is no “enable writing genre/album artist” switch.** Picard writes standard tags when you **File → Save** after a successful lookup. You only configure *what* gets written and *what* to preserve:

| Left tree (Options…) | What to set |
|----------------------|-------------|
| **Before Tagging** | Leave **Clear existing tags** **unchecked** (or you wipe genre, comments, etc.). Optionally list tags to preserve under “Preserve these tags…”. |
| **Genres** | Check **Use genres from MusicBrainz** (and optionally “Fall back on album’s artists genres…”). Set max genres (e.g. 1–3) if you want. |
| **Metadata** | Optional: adjust **Preferred release types** (favor Album over Compilation, etc.). |
| **Advanced → Matching** | Optional: lower **Cluster** / raise **File** threshold if lookups fail often. |

**Album artist, track number, and date** are written automatically on Save when Picard matches a release — no extra checkbox.

Skip **Options → Enable CD Lookup** / heavy plugins unless you need them.

## 3. Tag a batch (GUI — Picard 2.7)

Ubuntu’s Picard 2.7 has **no** `CLUSTER` / `SAVE_MATCHED` CLI (those need 2.9+). Use the GUI:

1. Open `~/.bockmedia/picard-queue-dirs.tsv` and pick a folder (start with the largest count).
2. Picard → **File → Add folder…** → choose that directory.
3. Select all files in the left **Clusters** pane → **Tools → Cluster**.
4. Select clusters → **Tools → Lookup** (or **Lookup in Browser** for hard cases).
5. When albums appear on the right, review matches (green = good).
6. **File → Save** (or Ctrl+S) to write tags to disk.
7. Repeat for the next folder in the TSV.

Work in **folders**, not all 46k files at once — Picard and MusicBrainz rate limits will choke on a single giant load.

### Optional: upgrade Picard for batch CLI

```bash
# Example: Flatpak (often newer than apt)
flatpak install flathub org.musicbrainz.Picard
flatpak run org.musicbrainz.Picard -e "LOAD /path/to/your/music/SomeArtist" \
  -e FROM_FILE ~/Documents/github/ourMedia/scripts/picard/commands.txt
```

See `scripts/picard/commands.txt` for the command sequence.

## 4. Sync into ourMedia

After each batch (or when done for the day):

```bash
cd ~/Documents/github/ourMedia
chmod +x scripts/after_picard.sh
./scripts/after_picard.sh
```

This runs:

- `backfill_genres.py` — genre + year from files → DB  
- `backfill_metadata.py` — album_artist, track_number, disc_number, year (missing only, **DB only**)  
- `audit_metadata.py --no-tags` — coverage report  

**Album names in the DB stay** `[YEAR] …` from the indexer; Picard’s clean album name lives in the **file tags** only unless you later opt into `--fields album` (not recommended here).

## 5. Verify in the app

```bash
python3 scripts/audit_metadata.py --no-tags
curl -s "http://127.0.0.1:3001/api/albums?limit=10" | python3 -m json.tool
```

Smart playlists and AI search use `genre` from `songs_cache` — they improve as Picard + `after_picard.sh` progress.

## 6. Schedule

Run **after** `music_organizer` index scans. Optional weekly:

```cron
# After Picard sessions (manual), refresh DB from tags:
30 6 * * 0 cd /opt/bock-media && ./scripts/after_picard.sh >> ~/.bockmedia/picard-sync.log 2>&1
```

## Tips

- **Compilations** — Picard sets album artist to “Various Artists”; our backfill copies that into `album_artist` only.
- **No match** — leave untagged; ourMedia cannot fill from MusicBrainz without Picard saving first.
- **Rate limits** — pause between large lookups; use smaller folders from `picard-queue-dirs.tsv`.
