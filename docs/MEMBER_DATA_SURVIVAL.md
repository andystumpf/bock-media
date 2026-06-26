# Member data — what survives reinstall

All **ratings, star playlists, and app settings** for each household profile live on the **NAS** (`~/.bockmedia/`), not on the phone. Uninstalling the app only clears local cache and profile selection on the device.

## Server files (source of truth)

| File | Contents |
|------|----------|
| `~/.bockmedia/ratings.json` | Star ratings per member (`p-andy`, …) |
| `~/.bockmedia/client_prefs.json` | Settings, search pins, last Echo, pinned devices |
| `~/.bockmedia/household.json` | Members, phone→profile bindings, Echo owners |
| `~/.bockmedia/member_data_backups/` | Automatic rotating backups (last 30 writes per file) |

## After reinstall

1. Sign in with the same server URL + mobile API token.
2. **Pick your profile** when prompted (Family → active profile, or the first-launch picker).
3. The app sends a stable **phone id** (Android ID / iOS vendor id) so the server can re-link this install to your profile.
4. Settings pull from `client_prefs.json`; ratings load from `ratings.json` for that `memberId`.

## Do not store member data in the git repo

`ratings.json` must live under `~/.bockmedia/` only. Never commit or `git pull` over it.

## Restore from backup

```bash
ls ~/.bockmedia/member_data_backups/
python3 scripts/restore_ratings_backup.py --dry-run ~/.bockmedia/member_data_backups/ratings.json.YYYYMMDD-HHMMSS.bak
python3 scripts/restore_ratings_backup.py --member p-andy --apply /path/to/backup.bak
sudo systemctl restart ourmedia
```

Synology **File Station → Previous Versions** on `ratings.json` / `client_prefs.json` if automatic backups are not enough.

## Synology recommendation

Enable **Hyper Backup** or **Snapshot Replication** for `/home/plex/.bockmedia/` (daily).
