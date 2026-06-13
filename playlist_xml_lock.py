"""Cross-process lock for ServerPlaylists.xml (server + Plex sync script)."""
import contextlib
import fcntl
import os


def lock_path(data_dir):
    return os.path.join(data_dir, '.ServerPlaylists.xml.lock')


@contextlib.contextmanager
def playlist_xml_lock(data_dir, exclusive=False, shared=False):
    """fcntl flock: shared for reads, exclusive for writes."""
    if exclusive and shared:
        raise ValueError('exclusive and shared are mutually exclusive')
    os.makedirs(data_dir, exist_ok=True)
    with open(lock_path(data_dir), 'w') as fh:
        mode = fcntl.LOCK_EX if exclusive or not shared else fcntl.LOCK_SH
        fcntl.flock(fh.fileno(), mode)
        try:
            yield
        finally:
            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
