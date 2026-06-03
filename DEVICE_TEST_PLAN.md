# Bock Media — Real-Device Test Plan

Manual test checklist for features that require a physical Echo (the Alexa
simulator can't exercise `AudioPlayer` boundaries, multi-room, device identity,
or playback initiation). Ordered by priority / novelty.

Invocation name: **bock media**. Collision-safe verbs are **start** / **mix**
(never `play` / `shuffle`, which the music providers hijack).

Run the web console alongside each step to confirm state:
`#dashboard` (Service Health), `#nowplaying`, `#analytics`, `#devices`,
`#playlists`, `#routines`.

---

## 1. Sleep timer / stop-after-N  (highest priority — brand new)

- [ ] Start a playlist: *"Alexa, ask bock media to start the [playlist] playlist."*
- [ ] *"Alexa, ask bock media to stop after this song."* → expect spoken
      *"Okay, I'll stop after 1 song"*; current song finishes, then playback
      **stops** (does not advance). `#nowplaying` shows the moon badge while armed.
- [ ] *"...stop after 3 songs"* → plays through song 3, then stops.
- [ ] *"...set a sleep timer for 2 minutes"* (use a short value) → the song
      playing when 2 min elapses finishes, then stops.
- [ ] *"...cancel the sleep timer"* mid-timer → playback continues normally.
- [ ] Web UI: moon button on a playing row → "15 min" / "After this song" /
      "Cancel timer"; confirm the badge updates.

## 2. AddToPlaylist → Plex write-back

- [ ] While a song is playing: *"Alexa, ask bock media to add this to the
      [playlist] playlist."* → expect *"Added [track] to [playlist]."*
- [ ] Open that playlist in **Plex** → confirm the track is now there (new
      two-way path).
- [ ] Confirm it also appears immediately in `#playlists` in the web UI.

## 3. "Never play again" / ignore

- [ ] Playing a song you don't mind ignoring → `#nowplaying` → ban (🚫) button →
      confirm; it should skip to the next track.
- [ ] Re-play the same playlist → verify that track is **skipped**.
- [ ] `#analytics` → "Never Play Again" panel → confirm it's listed → "Allow
      again" → replay and confirm it's back.

## 4. Fix my devices + identify  (plays clips on real Echoes)

- [ ] `#devices` → if any show "Echo XXXXXX" → **Fix my devices (N)** button.
- [ ] For each: "Play here" → confirm the **correct physical Echo** chimes →
      name the room → Save & next.
- [ ] Confirm renamed devices persist and the unnamed counter drops.
- [ ] "Identify all" → confirm each room plays the short clip in sequence.
- [ ] Verify identify/test clips do **not** appear in `#analytics` history.

## 5. Group-aware Now Playing  (multi-room)

- [ ] `#devices` → create a Device Group with 2+ speakers (or use an Alexa
      multi-room group).
- [ ] Start the same playlist on both rooms.
- [ ] `#nowplaying` → confirm they **collapse into one group row**
      ("Downstairs · 2 speakers") with member sub-rows, not two separate rows.

## 6. Serial-indexed device identity  (rotation resilience)

- [ ] Note a named device's current behavior.
- [ ] Use "Play on device" or a test clip on that Echo a few times over a day.
- [ ] If Alexa rotates its deviceId, confirm it **auto-folds back** onto the
      same room (no new "Echo XXXX" duplicate; history stays attached).

## 7. Routines builder  (paste-into-app workflow)

- [ ] `#routines` → pick a playlist + trigger phrase → Generate → Copy.
- [ ] Alexa app → More → Routines → + → "When you say" = your phrase →
      Custom action = paste the line → set device → Save.
- [ ] Say *"Alexa, [your phrase]"* → confirm hands-free playback starts (no
      "ask" prefix needed).

## 8. Service Health card  (passive)

- [ ] `#dashboard` → confirm Service Health chips are green (Backend, Tunnel,
      Alexa session, Plex).
- [ ] Leave it ~2 min, issue a command, confirm "last Alexa hit" updates.

---

## Triage if something fails

- `tail -f server.log` and watch for `[ALEXA]`, `[DEVICE CORRELATE]`,
  `[DEVICE AUTO-MERGE]` lines.
- Check the Service Health card on `#dashboard`.
- Stack status:
  ```bash
  systemctl is-active ourmedia ourmedia-tunnel-named ourmedia-health.timer
  ```
- Note which step number failed when reporting.
