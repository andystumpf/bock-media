# Echo Show APL Lyrics

Karaoke-style lyrics on Echo Show (and other APL-capable screens) while the custom skill streams via `AudioPlayer`.

## How it works

1. **Play** — When `alexaAplLyrics.enabled` is true and the device supports `Alexa.Presentation.APL`, `AudioPlayer.Play` is paired with `Alexa.Presentation.APL.RenderDocument` showing synced lines (same `/api/lyrics` pipeline as mobile).
2. **Sync** — `progressReportingIntervalInMilliseconds: 1000` on the stream; `AudioPlayer.PlaybackProgressReport` re-renders the document with the active line bold/larger.
3. **Enqueue** — Tracks queued via `PlaybackNearlyFinished` also get lyrics on `PlaybackStarted` when the initial play response did not include APL.

**Not supported:** Echo Dot (no screen), Echo Spot 2024 (no skill APL), MSP music skill path (`/music`), audio-only metadata path without APL.

## Enable (after deploy)

### 1. Merge & deploy server code

Pull the branch on the server and restart `ourmedia`:

```bash
sudo systemctl restart ourmedia
```

### 2. Update Alexa skill manifest (required)

The custom skill must declare the APL interface. In-repo template: `skill/manifest.development.json`.

```bash
cd skill
ask smapi update-skill-manifest \
  --skill-id amzn1.ask.skill.YOUR_CUSTOM_SKILL_ID \
  --stage development \
  --manifest file:manifest.development.json
```

Re-enable the skill on each Echo Show (Alexa app → Skills → Bock Media → Disable → Enable).

### 3. Turn on the feature flag

In `~/.bockmedia/config.json` (or `OURMEDIA_DATA_DIR/config.json`):

```json
"alexaAplLyrics": {
  "enabled": true
}
```

Or one-shot env var:

```bash
OURMEDIA_APL_LYRICS=1
```

Restart `ourmedia` after config change.

### 4. Verify

On an Echo Show: *“Alexa, ask bock media to play …”* (custom skill, not MSP). Screen should show scrolling lyrics; active line is bold white.

Check server logs for `[ALEXA] type=AudioPlayer.PlaybackProgressReport` after enable.

---

## Rollback plan

Use the **fastest** step that restores normal playback. Each step is independent.

### Level 1 — Instant (no redeploy)

**Disable the feature flag** (keeps code deployed, zero APL directives):

```json
"alexaAplLyrics": { "enabled": false }
```

Or:

```bash
OURMEDIA_APL_LYRICS=0
sudo systemctl restart ourmedia
```

Playback reverts to title/artist/album art only. Safe default if anything breaks.

### Level 2 — Server code rollback

If disabling the flag is not enough (e.g. bad import crash):

```bash
cd ~/Documents/github/ourMedia
git checkout main -- server.py alexa_apl.py
sudo systemctl restart ourmedia
```

Or revert the merge commit and restart.

### Level 3 — Skill manifest rollback

If Echo Show behaves oddly after adding APL to the manifest (blank screen, skill errors):

1. Remove the `ALEXA_PRESENTATION_APL` block from `skill/manifest.development.json` (restore `AUDIO_PLAYER` only).
2. Push manifest via `ask smapi update-skill-manifest` (same command as enable).
3. Re-enable skill on devices.

Audio playback does **not** require APL; removing the interface only removes screen lyrics.

### Level 4 — Full revert

1. Level 1 (disable flag)
2. Level 3 (manifest without APL)
3. Level 2 or `git revert` of the feature PR
4. Confirm: play playlist, skip, pause/resume, sleep timer on Echo Show and audio-only Echo

### What to watch after enable

| Symptom | Likely cause | Rollback step |
|--------|----------------|---------------|
| No lyrics, playback OK | Flag off, no APL on device, or no lyric data | Enable flag; check `/api/lyrics` for track |
| Playback fails | Bad directive payload | Level 1, then Level 2 |
| Screen frozen / skill error | APL document issue | Level 1 immediately |
| Progress stutter every 1s | Expected (progress reports); tune `PROGRESS_REPORT_MS` in `alexa_apl.py` | Level 1 if unacceptable |

---

## Files

| File | Role |
|------|------|
| `alexa_apl.py` | APL documents, feature flag, progress handler |
| `server.py` | `alexa_play`, `PlaybackProgressReport`, `PlaybackStarted` hooks |
| `skill/manifest.development.json` | APL interface declaration |
| `config.json` → `alexaAplLyrics.enabled` | Runtime toggle |

## Tests

```bash
python3 -m pytest tests/test_alexa_apl.py tests/test_alexa.py -q
```
