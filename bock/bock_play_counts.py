"""Play count aggregates for smart playlist rules."""
import json
import os
import threading
from collections import Counter

_LOCK = threading.Lock()


def load_counts(path):
    with _LOCK:
        if not os.path.isfile(path):
            return {}
        try:
            with open(path) as f:
                data = json.load(f)
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}


def save_counts(path, data):
    with _LOCK:
        tmp = path + '.tmp'
        with open(tmp, 'w') as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, path)


def rebuild_from_history(history_path, out_path):
    counts = Counter()
    member_counts = {}
    if not os.path.isfile(history_path):
        save_counts(out_path, {'paths': {}, 'byMember': {}})
        return
    with open(history_path) as f:
        for line in f:
            try:
                ev = json.loads(line)
            except Exception:
                continue
            fp = ev.get('filepath') or ev.get('path')
            if not fp:
                continue
            counts[fp] += 1
            mid = ev.get('memberId') or 'household'
            member_counts.setdefault(mid, Counter())[fp] += 1
    data = {
        'paths': dict(counts),
        'byMember': {k: dict(v) for k, v in member_counts.items()},
    }
    save_counts(out_path, data)
    return data
