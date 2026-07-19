"""MSP queue flag scaffolding."""
import server


def test_set_shuffle_updates_queue(isolated_paths):
    qid = server._store_queue(['/a.mp3', '/b.mp3'], shuffle=False, loop=False)
    server._update_queue_flags(qid, shuffle=True, shuffle_seed=42)
    entry = server._load_queues().get(qid) or {}
    assert entry.get('shuffle') is True
    assert entry.get('shuffle_seed') == 42
