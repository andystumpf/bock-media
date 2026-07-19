"""Rotate backups for household member data (ratings, prefs, household)."""
import os
import shutil
import time

BACKUP_DIR_NAME = 'member_data_backups'
KEEP = 30


def backup_dir(data_dir):
    return os.path.join(data_dir, BACKUP_DIR_NAME)


def maybe_backup(path, data_dir=None):
    """Copy path to data_dir/member_data_backups/ before overwrite. Keeps last KEEP copies."""
    if not path or not os.path.isfile(path):
        return None
    root = data_dir or os.path.dirname(path)
    dest_root = backup_dir(root)
    os.makedirs(dest_root, exist_ok=True)
    base = os.path.basename(path)
    stamp = time.strftime('%Y%m%d-%H%M%S')
    dest = os.path.join(dest_root, f'{base}.{stamp}.bak')
    shutil.copy2(path, dest)
    _prune(dest_root, base)
    return dest


def _prune(dest_root, base):
    prefix = f'{base}.'
    files = sorted(
        (f for f in os.listdir(dest_root) if f.startswith(prefix) and f.endswith('.bak')),
        reverse=True,
    )
    for old in files[KEEP:]:
        try:
            os.remove(os.path.join(dest_root, old))
        except OSError:
            pass
