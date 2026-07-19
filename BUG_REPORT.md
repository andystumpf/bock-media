# Bug report status (July 2026)

Prior bug hunt (June 2026) closed ~110/121 tracked items. See `docs/BUG_HUNT_BACKLOG.md` for the live queue.

## Recently addressed (v2.6.118)

- Music video reliability — cookie export fallbacks, stale-cookie client UX, proxy retry
- Security — credentials audit UI, external-access startup guard, rate limiting, Alexa password migration script
- Playlist SQL phase 0–1 — CRUD, migration script, server resolver, tracks/add API; Plex SQL mirror (phase 2)
- Android background crossfade — policy test + shared gating
- Web lyrics panel; automation DST via `config.timezone`
- Settings security warnings on web, Android, iOS

## Open / deferred

- Echo Show APL lyrics — enable `alexaAplLyrics.enabled` on NAS when ready (`docs/ECHO_SHOW_APL_LYRICS.md`)
- MSP production / UK distribution — operational (`docs/AMAZON_UK.md`)
- Playlist SQL phase 4 — deprecate `ServerPlaylists.xml` hot path after dual-write stable
- Local phone repeat/loop — explicit product deferral

Run a fresh pass with `docs/BUG_HUNT_PROMPT.md` before the next release milestone.
