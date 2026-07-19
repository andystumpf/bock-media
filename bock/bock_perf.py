"""Server-Timing instrumentation and perf helpers."""
import functools
import time

from flask import g, request


def server_timing_header(*parts: str) -> dict:
    """Return headers dict with Server-Timing value."""
    if not parts:
        return {}
    return {'Server-Timing': ', '.join(parts)}


def timed_route(name: str):
    """Decorator: record handler duration in g.perf_timings and Server-Timing header."""

    def decorator(fn):
        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            start = time.perf_counter()
            try:
                return fn(*args, **kwargs)
            finally:
                ms = (time.perf_counter() - start) * 1000.0
                timings = getattr(g, 'perf_timings', None)
                if timings is None:
                    timings = []
                    g.perf_timings = timings
                timings.append((name, ms))

        return wrapper

    return decorator


def attach_server_timing(response):
    """Flask after_request hook: append Server-Timing from g.perf_timings."""
    timings = getattr(g, 'perf_timings', None) or []
    if not timings:
        return response
    parts = [f'{name};dur={ms:.2f}' for name, ms in timings]
    existing = response.headers.get('Server-Timing')
    if existing:
        parts.insert(0, existing)
    response.headers['Server-Timing'] = ', '.join(parts)
    return response


def perf_budget_ms(route_name: str, default: float = 500.0) -> float:
    """Soft CI budgets (ms) per hot route."""
    budgets = {
        'playlists': 500.0,
        'genres': 100.0,
        'home': 800.0,
        'dashboard_quick': 150.0,
        'summary': 100.0,
        'library_new': 200.0,
    }
    return budgets.get(route_name, default)
