"""Simple in-memory rate limiter for failed API auth attempts."""
import threading
import time

_LOCK = threading.Lock()
_BUCKETS = {}
_WINDOW_SEC = 60.0
_MAX_FAILS = 30


def record_auth_failure(client_key):
    now = time.time()
    with _LOCK:
        bucket = _BUCKETS.get(client_key, [])
        bucket = [t for t in bucket if now - t < _WINDOW_SEC]
        bucket.append(now)
        _BUCKETS[client_key] = bucket
        return len(bucket)


def is_blocked(client_key):
    now = time.time()
    with _LOCK:
        bucket = _BUCKETS.get(client_key, [])
        bucket = [t for t in bucket if now - t < _WINDOW_SEC]
        _BUCKETS[client_key] = bucket
        return len(bucket) >= _MAX_FAILS
