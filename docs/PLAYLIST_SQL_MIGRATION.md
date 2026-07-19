# Playlist SQL Migration Plan

**Goal:** Move playlist *membership* (ordered track lists) into SQLite while keeping the REST API contract unchanged for web, Android, iOS, and Alexa. Follow a phased approach: **Bock-native playlists first**, Plex-synced playlists mirrored into SQL second, virtual playlists unchanged.

**Status:** Planning — no migration started.

**Related docs:** `docs/HOUSEHOLD_PLAN.md` (playlist ownership/sharing), `RUNBOOK.md` (data paths), `scripts/sync_plex_playlists.py` (Plex → m3u today).

---

## 1. Executive summary

Today playlist tracks live in **`.m3u` files** indexed by **`ServerPlaylists.xml`**. That works for read-mostly Plex exports but creates pain when:

- Adding/removing one track rewrites an entire large file (Android loads all pages, then `PUT`s the full list).
- Server and Plex sync race on XML/m3u (torn reads — see `SRV-07`, `SRV-08` in [`BUG_REPORT.md`](dev/BUG_REPORT.md)).
- Virtual playlists (`rated-stars-N`) and smart/daily playlists already bypass m3u — special cases multiply.

**Recommendation:** Use **`music_organizer.db`** (same DB as `songs_cache`) for playlist membership. Keep **`ServerPlaylists.xml` as the catalog index** during transition (id, name, source type, track count). Optionally keep **`.m3u` as an export/interop artifact** for Plex and debugging, not as the source of truth for Bock-owned lists.

**Non-goals (initial phases):**

- Replacing Plex as the authority for Plex-origin playlists (sync still pulls from Plex).
- Changing mobile/web API shapes (clients keep using `/api/playlists/*`).
- Migrating `playlist_meta.json` (ownership/visibility) or `ratings.json` into SQL in v1 (can follow later).

---

## 2. Current architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Clients (Web / Android / iOS)                                  │
│  GET/PUT /api/playlists/:id  ·  tracks/remove  ·  tracks/move │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│  server.py                                                      │
│  _playlist_paths_cached() → parse_m3u(source)                 │
│  _persist_playlist() → _write_m3u + ServerPlaylists.xml         │
│  _tracks_for_playlist() (+ rated-stars virtual path)            │
└───────┬──────────────────────────────┬──────────────────────────┘
        │                              │
        ▼                              ▼
 ServerPlaylists.xml              .m3u files
 (~22 MB index)                   exportedPlaylists/plex/
                                  exportedPlaylists/bockmedia/
        │
        ▼
 playlists_index.json (mtime sidecar for fast list)

Parallel stores (not m3u):
  playlist_meta.json   — owner, visibility, daily recipe
  ratings.json         — rated-stars-N virtual playlists
  queues.json          — Alexa active play queues (runtime)
```

### 2.1 Playlist sources today

| Source | `SourceName` in XML | Tracks stored | Editable via API | Authority |
|--------|---------------------|---------------|------------------|-----------|
| Plex sync | `plex` | `.m3u` in `exportedPlaylists/plex/` | Local m3u yes; Plex sync may overwrite | Plex |
| Bock-created / AI / merge | `bockmedia` | `.m3u` in `exportedPlaylists/bockmedia/` | Full CRUD | Bock |
| Rated songs | *(no XML row)* | `ratings.json` | N/A (virtual) | Per-member ratings |
| Smart / daily | varies | computed or meta-driven | refresh endpoints | Server rules |

### 2.2 What already works (m3u)

- `POST /api/playlists` — create (writes m3u + XML).
- `PUT /api/playlists/:id` — replace track list.
- `POST .../tracks/remove`, `.../tracks/move`, `.../sort`.
- Alexa `AddToPlaylistIntent` — Plex writeback + append to m3u.
- Android `addPlaylistTrack()` — fetch all pages, append, full `PUT`.

### 2.3 What already hurts

- **Scale:** parse/cache entire m3u; 22 MB XML on many code paths.
- **Concurrency:** `_msp_playlist_by_id()` and `summary()` read XML without always holding `playlist_xml_lock`.
- **Consistency:** Plex cron rebuilds m3u; local edits can be lost or duplicated until dedupe.
- **Partial updates:** no `INSERT`/`DELETE` at position — always full list rewrite.

---

## 3. Target architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Clients — unchanged REST contract                              │
└────────────────────────────┬────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│  server.py                                                      │
│  _playlist_tracks(playlist_id, *, member_id='')  ← single entry │
│    ├─ virtual: rated-stars / smart / daily                      │
│    ├─ sql:     playlist_tracks table                            │
│    └─ legacy:  parse_m3u (fallback / Plex mirror lag)           │
│  _playlist_tracks_write() for mutations                         │
└───────┬──────────────────────────────┬──────────────────────────┘
        │                              │
        ▼                              ▼
 music_organizer.db                 ServerPlaylists.xml
 playlist_tracks (+ meta)           catalog index only
 playlist_sources                   optional m3u export
        │
        ▼ (optional)
 .m3u export for Plex/debug/interop
```

**Principle:** Every consumer asks `_playlist_tracks(id)` — never `parse_m3u()` directly except inside that module and the Plex sync script.

---

## 4. Database schema

Add tables to **`OURMEDIA_DB_PATH`** (default `fixtures/demo-data/songs_cache.db`), alongside `songs_cache`.

### 4.1 `playlist_sources`

One row per playlist id (mirrors XML catalog; enables SQL without dropping XML immediately).

```sql
CREATE TABLE IF NOT EXISTS playlist_sources (
  id            TEXT PRIMARY KEY,          -- same id as ServerPlaylists.xml / API
  name          TEXT NOT NULL,
  source_kind   TEXT NOT NULL,             -- 'plex' | 'bockmedia' | 'virtual'
  storage       TEXT NOT NULL DEFAULT 'm3u', -- 'm3u' | 'sql' | 'virtual'
  m3u_path      TEXT,                      -- nullable when storage='sql'
  track_count   INTEGER NOT NULL DEFAULT 0,
  xml_synced_at REAL,                      -- last import from XML/m3u
  updated_at    REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_playlist_sources_kind ON playlist_sources(source_kind);
CREATE INDEX IF NOT EXISTS idx_playlist_sources_storage ON playlist_sources(storage);
```

### 4.2 `playlist_tracks`

Ordered membership. `position` is 0-based, dense after normalization.

```sql
CREATE TABLE IF NOT EXISTS playlist_tracks (
  playlist_id   TEXT NOT NULL,
  position        INTEGER NOT NULL,
  path            TEXT NOT NULL,
  added_at        REAL,
  added_by_member TEXT,
  PRIMARY KEY (playlist_id, position),
  FOREIGN KEY (playlist_id) REFERENCES playlist_sources(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_playlist_tracks_path ON playlist_tracks(path);
CREATE UNIQUE INDEX IF NOT EXISTS idx_playlist_tracks_unique
  ON playlist_tracks(playlist_id, path);   -- optional: prevent dupes per playlist
```

### 4.3 Migration bookkeeping

```sql
CREATE TABLE IF NOT EXISTS schema_migrations (
  name        TEXT PRIMARY KEY,
  applied_at  REAL NOT NULL
);
```

**Note:** `playlist_meta.json` (owner, visibility) stays JSON for phase 1–3. Phase 4+ can add `playlist_meta` columns or table if sharing work lands first.

### 4.4 Integrity rules

- `path` must reference a row in `songs_cache` **softly** (warn on import, don’t hard-FK — files can lag scan).
- After any mutation: recompute `playlist_sources.track_count` and invalidate `_PLAYLIST_TRACKS_CACHE`.
- Transactions: `DELETE` + bulk `INSERT` or row-level `INSERT`/`DELETE`/`UPDATE position` inside one transaction.

---

## 5. Storage strategy by playlist type

| Type | Phase 1 | Phase 2+ | m3u role |
|------|---------|----------|----------|
| **Bock-native** (`bockmedia`) | **SQL source of truth**; generate m3u on write (optional) | drop m3u generation when stable | export only |
| **Plex** (`plex`) | unchanged (m3u) | sync script **dual-writes** SQL + m3u | Plex authority; SQL = fast read |
| **Rated** (`rated-stars-N`) | unchanged (ratings.json) | optional SQL materialized view | none |
| **Smart / daily** | unchanged (computed) | cache snapshots in SQL if needed | none |

---

## 6. Phased implementation

### Phase 0 — Foundation (no user-visible change)

**Deliverables**

1. `bock_playlists.py` (new module):
   - `ensure_schema(get_db_rw)`
   - `tracks(playlist_id, *, offset, limit, member_id)` → `list[str]`
   - `track_count(playlist_id)`
   - `replace_tracks(playlist_id, paths)`
   - `append_track`, `remove_track`, `move_track`
   - `import_from_m3u(playlist_id, m3u_path)` / `export_to_m3u(playlist_id, m3u_path)`

2. `scripts/migrate_playlists_to_sql.py`:
   - Read `ServerPlaylists.xml` under `playlist_xml_lock`
   - For each `bockmedia` entry: load m3u → `playlist_tracks` + `playlist_sources.storage='sql'`
   - Idempotent; `--dry-run`; logs counts and orphans

3. Unit tests: round-trip import/export; position ordering; duplicate handling.

**Exit criteria:** Migration script runs clean on NAS; tests pass in CI.

---

### Phase 1 — Bock-native reads/writes via SQL

**Server changes** (`server.py`)

| Area | Change |
|------|--------|
| `_tracks_for_playlist()` | Delegate to `bock_playlists.tracks()` when `storage='sql'` |
| `_persist_playlist()` | Write SQL first; optionally `_write_m3u_file()` for backward compat |
| `create_playlist` / `update_playlist` | Set `storage='sql'` for new Bock playlists |
| `remove_playlist_track` / `move_playlist_track` | Row-level SQL ops (no full read-modify-write of giant lists) |
| `_playlist_paths_cached()` | Thin wrapper over `_playlist_tracks()` or deprecated |
| Cache invalidation | Drop in-memory cache key on SQL mutation |

**New API (optional, backward compatible)**

- `POST /api/playlists/:id/tracks/add` — `{ path }` append one track (Android can stop full-list `PUT`).

**Clients**

- **No required changes** if existing endpoints behave identically.
- **Recommended:** Android `addPlaylistTrack()` switch to `tracks/add` when available (performance).

**Alexa**

- `_start_playing_impl` / queue build already use `_tracks_for_playlist()` — works once resolver is unified.
- Play tokens for app-initiated plays: materialize paths at token registration (already done for rated).

**Exit criteria:** Create/edit/reorder/remove on Bock playlists without touching m3u; Alexa play and app play-on-device work; Plex playlists unaffected.

---

### Phase 2 — Plex mirror into SQL (read path)

**`scripts/sync_plex_playlists.py` changes**

After writing each `.m3u`:

```text
import_from_m3u(plex_playlist_id, m3u_path)
UPDATE playlist_sources SET storage='m3u', m3u_path=?, track_count=?, xml_synced_at=?
```

**`server.py`**

- `_playlist_tracks()`: for `plex` + SQL rows present, read SQL (fast); if SQL stale vs m3u mtime, re-import.
- Staleness: compare `playlist_sources.xml_synced_at` with m3u mtime.

**Exit criteria:** Playlist detail API latency improves on large Plex lists; Plex sync still authoritative; no client changes.

---

### Phase 3 — Plex writes and Alexa add-to-playlist

| Flow | Today | Target |
|------|-------|--------|
| Web/API edit Plex playlist m3u | rewrite file | SQL + m3u dual-write |
| Alexa `AddToPlaylistIntent` | Plex API + append m3u | Plex API + SQL append + m3u append |
| Plex sync UPDATE | overwrite m3u | overwrite m3u + `replace_tracks()` from m3u |

**Conflict rule:** On sync UPDATE, **Plex wins** — SQL replaced from rebuilt m3u (same as today’s semantics).

**Exit criteria:** Add/remove on Plex lists consistent after sync; no duplicate tracks after dedupe.

---

### Phase 4 — Catalog index slim-down (optional)

Long-term: reduce dependence on 22 MB XML.

1. `playlist_sources` becomes catalog of record; `playlists_index.json` built from SQL.
2. `sync_plex_playlists.py` updates SQL + JSON index; XML write optional/deprecated.
3. `build_msp_catalog.py` reads from SQL/JSON index instead of parsing full XML.
4. Coordinate with My Media / legacy indexer if anything else reads `ServerPlaylists.xml`.

**Risk:** Highest — only after phases 1–3 stable for weeks.

---

## 7. Client impact matrix

| Surface | API change? | Work required |
|---------|-------------|---------------|
| **Web** (`public/js/app.js`) | No | None (optional: use `tracks/add`) |
| **Android** | No | Optional: `tracks/add`, stop full-list PUT |
| **iOS** | No | Same as Android |
| **Alexa custom skill** | No | Server-only resolver |
| **Alexa MSP** | No | Catalog build may read new index in phase 4 |
| **Plex sync script** | N/A | Phases 2–3 |
| **Voice catalog** | No until phase 4 | `scripts/build_msp_catalog.py` |

Contract preserved:

```json
GET /api/playlists/:id → { "id", "name", "tracks": [...], "total", "page", "limit" }
PUT /api/playlists/:id → { "tracks": ["path", ...] }
POST /api/playlists/:id/tracks/remove → { "path" }
POST /api/playlists/:id/tracks/move → { "path", "toIndex" }
```

---

## 8. Server refactor map

Functions to consolidate (grep targets):

| Current | Replace with |
|---------|--------------|
| `parse_m3u()` direct calls in playlist CRUD | `bock_playlists.*` |
| `_playlist_paths_cached()` | `_playlist_tracks()` |
| `_tracks_from_source()` | internal to `bock_playlists` |
| `_tracks_for_playlist()` | thin facade + virtual/rated branches |
| `_persist_playlist()` | SQL write + optional m3u export |
| `_msp_playlist_by_id()` | return `(name, storage_hint)`; tracks via `_playlist_tracks()` |
| `_store_queue` / Alexa advance | unchanged queue shape (list of paths) |

**Virtual playlists** (keep separate branch in resolver):

- `rated-stars-N` → `bock_ratings.songs_at_stars()` (member-aware)
- Smart/daily → existing recipe handlers

---

## 9. Migration & rollback

### 9.1 One-time migration (Bock-native)

```bash
# On NAS, after deploy
python3 scripts/migrate_playlists_to_sql.py --dry-run
python3 scripts/migrate_playlists_to_sql.py
```

Per playlist:

1. Backup m3u (`*.bak` timestamp).
2. Import tracks into `playlist_tracks`.
3. Set `playlist_sources.storage='sql'`.
4. Verify `track_count` matches m3u line count.

### 9.2 Rollback

If phase 1 fails:

1. Set `storage='m3u'` for affected ids in `playlist_sources` (or delete rows).
2. m3u files unchanged if dual-write was enabled.
3. Server falls back to `parse_m3u(m3u_path)`.

### 9.3 Backup before cutover

- `ServerPlaylists.xml` — script already writes `.bak` on sync.
- `music_organizer.db` — snapshot/copy before bulk import.
- `exportedPlaylists/bockmedia/` — tarball.

---

## 10. Testing strategy

### 10.1 Unit tests (`tests/test_playlists_sql.py`)

- Schema creation idempotent.
- Import m3u → SQL → export m3u path equality.
- append/remove/move preserve order.
- Duplicate path policy (reject or dedupe — document choice in phase 0).

### 10.2 Integration tests (`tests/test_api.py`)

- `POST /api/playlists` → `GET` detail track order.
- remove/move on Bock playlist.
- Play-on-device token includes materialized paths (regression for rated + SQL lists).

### 10.3 Regression suite (existing)

- `tests/test_regressions.py` — Alexa queue advance, play tokens.
- `tests/test_ratings.py` — rated virtual playlists unchanged.

### 10.4 Manual NAS checklist

- [ ] Create Bock playlist from web; play on Kitchen Show.
- [ ] Add track from Android; verify order on web.
- [ ] Plex playlist still lists/plays after sync cron.
- [ ] “Add to playlist” via Alexa on Plex list.
- [ ] Large playlist (>500 tracks) pagination performance.

---

## 11. Risks & mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Plex sync overwrites local SQL edits | Lost adds | Plex wins on sync; same as today; log sync version |
| XML/m3u/SQL drift | Wrong track list | mtime-based re-import; `track_count` sanity checks |
| NAS DB on network mount | Slow writes | Batch inserts; transactions; index only `playlist_id` |
| My Media legacy reads XML | Break external tools | Keep XML updated through phase 3; phase 4 is explicit |
| Android full-PUT race | Lost concurrent edits | Phase 1 `tracks/add`; optimistic versioning later |
| Rated songs member context | Empty play on device | Keep member_id on play API (done in 2.6.24) |

---

## 12. Performance expectations

| Operation | m3u today | SQL target |
|-----------|-----------|------------|
| Add 1 track to 2k list | Read 2k + write 2k paths | `INSERT` one row + bump count |
| Playlist detail page 1 | Parse full m3u (cached) | `SELECT ... LIMIT/OFFSET` |
| Alexa start play | `_playlist_paths_cached` | `_playlist_tracks` (same cache layer) |
| Plex sync | write m3u + XML | + bulk SQL import (async ok) |

---

## 13. Documentation & ops updates

When each phase ships, update:

- `RUNBOOK.md` — backup paths, migration script, rollback.
- `README.md` — playlist storage paragraph.
- [`BUG_REPORT.md`](dev/BUG_REPORT.md) — close SRV-07/SRV-08 when XML read races reduced.
- `shared/api-contract/api-contract.yaml` — add optional `tracks/add` if implemented.

---

## 14. Suggested timeline

| Phase | Scope | Estimate |
|-------|-------|----------|
| **0** | Schema + module + migration script + tests | 2–3 days |
| **1** | Bock-native SQL CRUD + server resolver | 3–5 days |
| **2** | Plex mirror read path | 2–3 days |
| **3** | Dual-write + Alexa add + sync UPDATE | 3–4 days |
| **4** | XML deprecation / catalog from SQL | 1–2 weeks (optional) |

Phases 0–1 deliver most user value. Phases 2–3 align Plex behavior. Phase 4 is cleanup.

---

## 15. Success criteria

1. **Functional:** Bock playlists support add/remove/reorder without full-file rewrites; all clients work without release coordination.
2. **Alexa:** Play playlist, auto-advance, add-to-playlist unchanged from user perspective.
3. **Plex:** Sync cron does not regress; Plex remains authority for Plex lists.
4. **Performance:** Playlist detail p95 improves for Bock lists >200 tracks (measure before/after on NAS).
5. **Operability:** One-command migration + documented rollback; CI covers SQL module and API integration.

---

## 16. Open decisions (resolve in Phase 0 kickoff)

1. **Duplicate tracks per playlist:** unique index or allow repeats (Plex sometimes duplicates)?
2. **m3u dual-write duration:** forever for Bock, or drop after N releases?
3. **Partial path validation on insert:** reject missing files vs accept (current m3u often trusts paths)?
4. **Phase 4 XML deprecation:** confirm nothing outside ourMedia reads `ServerPlaylists.xml` on the NAS.

---

## Appendix A — File touch list

| File | Phases |
|------|--------|
| `bock_playlists.py` | 0–3 (new) |
| `server.py` | 1–3 |
| `scripts/migrate_playlists_to_sql.py` | 0 (new) |
| `scripts/sync_plex_playlists.py` | 2–3 |
| `scripts/build_msp_catalog.py` | 4 |
| `catalog_cache.py` | 2, 4 |
| `tests/test_playlists_sql.py` | 0 (new) |
| `tests/test_api.py` | 1 |
| `android/.../BockMediaRepository.kt` | 1 optional |
| `public/js/app.js` | 1 optional |

## Appendix B — Example resolver (sketch)

```python
def _playlist_tracks(playlist_id, *, member_id='', offset=0, limit=None):
    rated = _resolve_rated_playlist(playlist_id, member_id)
    if rated:
        paths = rated[1]
    elif bock_playlists.is_sql_backed(playlist_id):
        paths = bock_playlists.tracks(playlist_id)
    else:
        meta = _playlist_meta_from_id(playlist_id)
        paths = bock_playlists.tracks_from_m3u(meta['source'])  # legacy
    if offset or limit:
        return paths[offset:(offset + limit) if limit else None]
    return paths
```

This sketch is the intended end state for `server.py` — implement in `bock_playlists.py` first, then swap call sites incrementally.
