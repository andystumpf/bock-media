# Household Plan — Profiles, Shared Queue, Kid-Safe Rooms, Sharing & Messages

Status: proposed. Owner: family/home deployment (`192.168.1.187`).

Turns ideas #10 (family member profiles), #11 (household requests/queue), and
#12 (kid-safe rooms) into a buildable plan, plus the two extras you asked for:
**playlist sharing** and **music messages**, with **all activity flowing into
analytics** so you can see how the family uses the app.

Everything stays on your server. Zero third-party data sharing.

---

## 0. Context & goals

Real household:

- **You (Andy)** — WFH, listen on the office Echo all day; in the car you play
  from your phone.
- **Kids** — iPhones + iPads, an Echo in each room (e.g. Emma's Room).
- **Son** — plays from his phone for pre-game basketball.

What we want:

1. **Per-person profiles** — separate taste, history, recommendations.
2. **Shared "up next" per room** — anyone adds to what's playing in a room from
   their phone.
3. **Kid-safe rooms** — per-room allow-lists, explicit-content block, volume
   caps, quiet hours.
4. **Share playlists** with each other.
5. **Music messages** — "check this out" notes between family members.
6. **Family analytics** — every play (Alexa + phone) attributed to a person,
   room, and platform, with a household overview.

Design principle: **family-trust, not enterprise auth.** Lightweight profile
switching, optional parent PIN — no per-track logins.

---

## 1. Current architecture (what we build on)

| Area | Today | Reference |
|------|-------|-----------|
| Auth | One shared `mobileApi.token` (Bearer) + admin Basic (`WebPassword`). No user concept. | `server.py:_basic_auth_ok`, `check_auth` |
| Client identity | `client-<uuid>` per install; `register_client_device`; `/api/clients/report` (connect/play/download/playback). | `server.py:register_client_device`, `/api/clients/report` |
| Alexa identity | `devices.json` keyed by skill `deviceId`, now correlated to hardware `serial` → live room name. | `server.py:register_device`, `_device_label` |
| Analytics | `streaming_history.jsonl` (≤5000 rows). Per-`deviceId` filter (`?deviceId=`). No person dimension. | `server.py:/api/analytics`, `append_stream_history` |
| Playlists | `ServerPlaylists.xml` + Bock `.m3u`. Global, no owner. Create/merge/update/sort. | `server.py:/api/playlists*`, `_persist_playlist` |
| Playback | `start_playing`/queues; play to Echo by serial via `alexa_remote`. Per-room now-playing state. | `server.py:start_playing`, `/api/alexa_play` |
| Storage | JSON/JSONL files in `DATA_DIR` (`~/.bockmedia`), atomic writes. | `server.py:_atomic_json_write` |

There is an **unbuilt** identity design in `docs/IOS_BUILD_PLAN.md §10`
(`accounts.json`, `clients.json`, per-account tokens, `accountId`/`platform` on
history rows). This plan adopts and extends it as its foundation.

---

## 2. Foundation — Household members (profiles)

### 2.1 Data model — `~/.bockmedia/household.json`

```json
{
  "members": [
    { "id": "p-andy", "name": "Andy", "role": "parent",
      "color": "#3B82F6", "avatar": "andy.png", "pinHash": "…", "createdAt": 0 },
    { "id": "p-emma", "name": "Emma", "role": "kid",
      "color": "#EC4899", "avatar": null, "createdAt": 0 },
    { "id": "p-jack", "name": "Jack", "role": "kid", "color": "#10B981" }
  ]
}
```

- `role`: `parent` | `kid`. Parents manage members, room policies, approvals.
- `pinHash`: optional. Only parents need one (to leave kid-safe context or edit
  policies). Kids switch freely; trust model.

### 2.2 Binding identities → members

Two binding tables (in `household.json` or a sibling `bindings.json`):

| Binding | Meaning | Source |
|---------|---------|--------|
| `client-<uuid>` → `memberId` | Whose phone/tablet this install is. | App chooses profile on first launch / Settings. |
| Echo `deviceId`(primary) → `memberId` | A room's **default listener** (e.g. Emma's Room → Emma). | Parent assigns in Devices UI. |

Attribution rules for a play:

1. **Phone/tablet play** → the install's **active profile** (a phone can host
   multiple profiles; default is the install owner). Car playback = your phone =
   you.
2. **Alexa play** → the **room's default member** (kids' room Echoes map to that
   kid). Shared rooms (office/kitchen) can map to "Household" or be left
   unattributed → counts at household level only.
3. **Manual override** — app lets a user say "this is me" to re-tag the current
   room session for the next N minutes.

> Why room-default for Alexa: Amazon does not expose per-utterance voice
> identity to skills. Room→member mapping is the reliable, private substitute
> and matches reality (it really is Emma's room).

### 2.3 APIs

| Endpoint | Purpose |
|----------|---------|
| `GET /api/household` | Members + bindings + room policies. |
| `POST /api/household/members` | Create/rename/delete a member (parent). |
| `POST /api/household/members/{id}/pin` | Set/verify parent PIN. |
| `POST /api/clients/bind` | `{clientId, memberId}` — bind an install. |
| `POST /api/devices/{deviceId}/owner` | Assign a room's default member. |

### 2.4 Client changes

- **First launch / Settings → Who's using this?** profile picker (avatars).
- Persist active `memberId` next to `clientId` (Keychain / DataStore). Already
  have `ClientIdStore.swift`; add `ActiveProfileStore`.
- Send `memberId` on every `/api/clients/report` and play event.

### 2.5 Auth posture

Keep the shared `mobileApi.token` for transport security (LAN + tunnel). Profile
is an **attribution + policy** layer, not a security boundary, **except**
parent-only actions (policy edits, approvals, leaving kid-safe mode) which
require the parent PIN. This avoids per-account token churn while protecting the
few sensitive operations.

---

## 3. Per-person taste, history & recommendations

### 3.1 Extend the history row

Add to every `append_stream_history(...)` write and `/api/clients/report` play:

```json
{ "...": "...", "deviceId": "...", "device": "Emma's Room",
  "memberId": "p-emma", "platform": "alexa|ios|android|web",
  "playSource": "alexa|local|remote", "date": "…" }
```

- Backfill: existing rows get `memberId` resolved at read time via the
  deviceId→owner binding (no rewrite needed); new rows store it explicitly.
- Keep the 5000-row cap but **roll older rows into a compacted monthly
  aggregate** (`history_rollup.json`) so per-person stats survive truncation
  (you lose long-term history today at 5000 rows).

### 3.2 Analytics filters

Extend `/api/analytics` (already takes `?deviceId=`) with:

- `?member=p-emma` — one person across all their devices.
- `?platform=ios|android|alexa` — already partially designed in IOS_BUILD_PLAN.
- `?room=<deviceId>` — unchanged, now also resolvable by member.

### 3.3 Recommendations (local, explainable)

Per-member, computed server-side from that member's rows:

- **Heavy rotation** / **Rediscover** (loved then dormant) / **Unheard in your
  library**. Each rec carries a `reason` string ("You played this 12× in March").
- Surface in Home feed via existing `HomeFeedComposer` with a `memberId` scope.

---

## 4. Household requests — shared "Up Next" per room

### 4.1 Concept

Each room (Echo or a phone session) has a live **request queue** layered on top
of the active playback queue. Anyone in the house adds tracks from their phone;
they play after the current track. In kid-safe rooms, requests need parent
approval (see §5).

### 4.2 Data model — `~/.bockmedia/requests.json`

```json
{
  "rooms": {
    "amzn1.ask.device…": {
      "queue": [
        { "id": "rq-1", "path": "/mnt/bock/Music/…flac",
          "track": "…", "artist": "…", "byMemberId": "p-jack",
          "status": "queued|approved|playing|done|rejected",
          "ts": 0 }
      ]
    }
  }
}
```

### 4.3 APIs

| Endpoint | Purpose |
|----------|---------|
| `GET /api/rooms/{deviceId}/queue` | Current up-next for a room. |
| `POST /api/rooms/{deviceId}/requests` | `{path|playlistId, memberId}` add request. |
| `POST /api/rooms/{deviceId}/requests/{id}/approve` | Parent approves (kid-safe). |
| `DELETE /api/rooms/{deviceId}/requests/{id}` | Remove/skip a request. |
| `POST /api/rooms/{deviceId}/requests/reorder` | Drag to reorder. |

### 4.4 Playback integration

- When the current track ends (Alexa `PlaybackNearlyFinished`/`Finished` or MSP
  queue events, see `_msp_handle_event`), splice the next **approved** request
  into the active queue before falling through to the normal next track.
- For phone-local sessions, `LocalPlaybackController` consumes the same room
  queue.
- Now-playing payload (`/api/nowplaying_devices`) gains `upNext: [...]` so all
  clients render the shared queue.

### 4.5 UX

- "Add to <Room>" from any track/album/playlist row → pick a room you can see.
- Room card shows up-next with requester avatars. Reorder/skip for owners/parents.

---

## 5. Kid-safe rooms

### 5.1 Per-room policy — `~/.bockmedia/room_policies.json`

```json
{
  "amzn1.ask.device…": {
    "safe": true,
    "ownerMemberId": "p-emma",
    "allowPlaylistIds": ["…", "…"],
    "allowExplicit": false,
    "maxVolume": 60,
    "quietHours": [{ "days": [0,1,2,3,4], "from": "20:30", "to": "07:00" }],
    "requireApproval": true
  }
}
```

### 5.2 Enforcement points (server-side, authoritative)

| Rule | Hook |
|------|------|
| Allow-list only | `/api/alexa_play`, `start_playing`, MSP `Initiate`, request approval — reject content not in `allowPlaylistIds` (or not matching allowed artists/tags). |
| Block explicit | Same hooks; check track `explicit` tag from `songs_cache`. |
| Volume cap | `/api/alexa_volume` / `alexa_remote.set_volume` — clamp to `maxVolume`; also re-clamp on a poll so voice "louder" can't exceed it. |
| Quiet hours | Play hooks return a friendly refusal; scheduled automations skip; optional auto-stop at window start. |
| Approval | Requests in §4 enter `queued` (not `approved`) → parent push to approve. |

Enforcement is **server-side** so it holds regardless of client (phone, voice,
routine). Parent PIN required to toggle `safe`/edit policy.

### 5.3 Parent controls UI

- Devices → a room → **Kid-safe** panel: toggle, owner, allowed playlists
  (multi-select), explicit switch, max volume slider, quiet-hours editor.
- "Pending requests" badge with one-tap approve/reject.

---

## 6. Playlist sharing

### 6.1 Ownership & visibility

Add an owner/visibility sidecar (don't bloat `ServerPlaylists.xml`):
`~/.bockmedia/playlist_meta.json`:

```json
{ "<playlistId>": { "ownerMemberId": "p-andy",
  "visibility": "private|household|shared",
  "sharedWith": ["p-jack"], "createdAt": 0 } }
```

- `/api/playlists` gains `?member=` and respects visibility (you see your own +
  household + shared-with-you).
- New playlists (`POST /api/playlists`) stamp `ownerMemberId` from the caller's
  active profile.

### 6.2 Share actions

| Endpoint | Purpose |
|----------|---------|
| `POST /api/playlists/{id}/share` | `{toMemberIds[]}` → adds to their inbox (§7) + visibility. |
| `POST /api/playlists/{id}/copy` | Fork a shared playlist into your own (independent edits). |

### 6.3 UX

- Playlist overflow → **Share with…** (family member picker).
- "Shared with me" shelf in Library / Home.

---

## 7. Music messages (family chat about music)

Lightweight, music-anchored messaging — not a general chat app.

### 7.1 Model — `~/.bockmedia/messages.jsonl` (append-only)

```json
{ "id": "m-1", "fromMemberId": "p-jack", "toMemberId": "p-andy|null",
  "scope": "direct|household|room:<deviceId>",
  "text": "pregame 🔥", "attach": { "type": "track|album|playlist",
  "path|id": "…" }, "ts": 0, "readBy": ["p-andy"] }
```

### 7.2 APIs

| Endpoint | Purpose |
|----------|---------|
| `GET /api/messages?member=p-andy` | Inbox + household thread. |
| `POST /api/messages` | Send (text + optional music attachment). |
| `POST /api/messages/{id}/read` | Mark read. |

### 7.3 UX & notifications

- "Send to family" from any track/album/playlist (attaches it).
- Inbox tab / bell badge. Tapping an attachment plays or opens it.
- Reuse existing notification plumbing (iOS `DownloadNotifications` pattern,
  Android `NowPlayingNotificationManager`) for a "New from Jack" push.

---

## 8. Family analytics dashboard

Built on the per-member history (§3). New views:

- **Household overview** — total listening time this week, plays per member,
  most-active room, busiest hours, platform split (Alexa vs phones).
- **Per-member** — top artists/tracks/albums, streaks, rediscovery, growth.
- **Per-room** — what each room plays; kid-safe compliance (blocked attempts).
- **Family leaderboard** — fun, opt-out-able: who listened most, most-shared
  playlist, most-played family track.
- **"Sharing graph"** — who shares with whom (from §6/§7 events).

APIs: extend `/api/analytics` with `member`/`platform`/`room`; add
`GET /api/analytics/household` for the overview rollup. Web console gets the
dashboard; apps get a "Family" tab.

---

## 9. Privacy & data ownership

- All members, bindings, policies, messages, requests live in `~/.bockmedia`.
  Nothing leaves the server. No analytics SDKs.
- Messages and per-person history are **household-internal**; parents can see
  kids' activity (it's a family server), but the design avoids exposing one
  kid's data to another beyond opt-in leaderboards.
- PIN-gated parent actions; backups covered by existing RUNBOOK backup list
  (add the new JSON files to it).

---

## 10. Phased rollout

| Phase | Deliverable | Depends on |
|-------|-------------|-----------|
| **P0 — Identity** | `household.json`, member CRUD, client/device bindings, profile picker in apps, `memberId` on report/history. | — |
| **P1 — Attribution & analytics** | History rows carry member/platform; `/api/analytics?member=&platform=`; monthly rollup; per-member Home recs. | P0 |
| **P2 — Kid-safe rooms** | `room_policies.json`, server-side enforcement (allow-list, explicit, volume cap, quiet hours), parent UI. | P0 |
| **P3 — Requests / shared up-next** | `requests.json`, room queue APIs, playback splice, `upNext` in now-playing, approval flow ties into P2. | P0, P2 |
| **P4 — Playlist sharing** | `playlist_meta.json`, ownership/visibility, share/copy, "shared with me". | P0 |
| **P5 — Music messages + family dashboard** | `messages.jsonl`, inbox + notifications, household analytics overview + leaderboard. | P0, P1, P4 |

Ship P0→P1 first (immediate value: family analytics) then P2 (safety) before the
social layers.

---

## 11. New/changed APIs (summary)

```
GET    /api/household
POST   /api/household/members            POST /api/household/members/{id}/pin
POST   /api/clients/bind                 POST /api/devices/{deviceId}/owner
POST   /api/devices/{deviceId}/policy    (kid-safe; parent PIN)
GET    /api/rooms/{deviceId}/queue       POST /api/rooms/{deviceId}/requests
POST   /api/rooms/{deviceId}/requests/{id}/approve
DELETE /api/rooms/{deviceId}/requests/{id}
GET    /api/playlists?member=            POST /api/playlists/{id}/share|copy
GET    /api/messages?member=             POST /api/messages   POST /api/messages/{id}/read
GET    /api/analytics?member=&platform=&room=
GET    /api/analytics/household
```

## 12. Client work (iOS / Android / web)

- **Profile picker + active-profile store** (both apps + web session).
- **"Add to room" / shared up-next** UI on track rows + room cards.
- **Kid-safe parent panel** (web first, then apps).
- **Share + inbox/messages** UI; notification hooks.
- **Family analytics tab** (reuse Swift Charts / Vico components).
- Send `memberId` everywhere; respect visibility/policy errors gracefully.

## 13. New data files (add to RUNBOOK backups)

```
~/.bockmedia/household.json        members + bindings
~/.bockmedia/room_policies.json    kid-safe policies
~/.bockmedia/requests.json         shared up-next per room
~/.bockmedia/playlist_meta.json    ownership/visibility
~/.bockmedia/messages.jsonl        music messages
~/.bockmedia/history_rollup.json   long-term per-member aggregates
```

## 14. Acceptance criteria (per phase)

- **P0**: Each install picks a profile; plays show the right person in
  `/api/clients/report`; Emma's Room Alexa plays attribute to Emma.
- **P1**: `/api/analytics?member=p-jack` returns Jack-only stats; rollup keeps
  >5000-row history per member.
- **P2**: Voice "louder" on Emma's Room cannot exceed `maxVolume`; non-allowed
  playlist is refused on phone, voice, and routine; quiet hours block at 20:30.
- **P3**: Jack adds a track to the Kitchen from his phone; it plays next; in a
  kid-safe room it waits for approval.
- **P4**: You share a playlist with Jack; it appears in his "Shared with me"; his
  copy edits don't change yours.
- **P5**: Jack sends you a track with "pregame 🔥"; you get a notification; the
  household dashboard shows family totals and a leaderboard.

## 15. Risks & open decisions

- **Alexa per-person attribution** is room-based, not voice-based (platform
  limit). Decision: accept room-default + manual override. (Optional later:
  Amazon Voice Profiles if/when accessible via the unofficial API.)
- **Profiles are trust-based, not secure logins.** Only parent actions are
  PIN-gated. Confirm this is acceptable vs. full per-member tokens.
- **Explicit tagging** depends on `songs_cache` having an `explicit` flag —
  audit coverage; may need a tagging pass.
- **Shared up-next on Alexa** depends on splicing into the skill/MSP queue at
  track boundaries; verify timing against `PlaybackNearlyFinished`.
- **History cap**: introduce rollup before enabling per-member long-term stats,
  or early data is lost at 5000 rows.

---

### Open question for you
Should shared rooms (office, kitchen) attribute Alexa plays to **you**, to a
**"Household" bucket**, or stay **unattributed**? This affects how your WFH
office listening shows up in the family dashboard.
