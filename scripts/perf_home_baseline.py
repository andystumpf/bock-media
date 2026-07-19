#!/usr/bin/env python3
"""Measure home-refresh API burst latency (p50/p95 over N runs).

Usage:
  python scripts/perf_home_baseline.py [--base http://127.0.0.1:5050] [--runs 5]

Mirrors client fan-out: playlists, genres, dashboard/quick, nowplaying, continue,
smart_playlists, library/new, discover-weekly, analytics.
"""
from __future__ import annotations

import argparse
import statistics
import time
import urllib.error
import urllib.request


HOME_ENDPOINTS = (
    '/api/nowplaying?page=1&limit=150',
    '/api/playlists?page=1&limit=500&fields=summary',
    '/api/smart_playlists',
    '/api/dashboard/quick',
    '/api/continue',
    '/api/genres?limit=40',
    '/api/library/new?since=7d&limit=50',
    '/api/recommendations/discover-weekly',
    '/api/analytics',
    '/api/home?deferred=1',
)


def fetch_ms(base: str, path: str, timeout: float = 30.0) -> float:
    url = base.rstrip('/') + path
    start = time.perf_counter()
    req = urllib.request.Request(url, headers={'Accept': 'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        resp.read()
    return (time.perf_counter() - start) * 1000.0


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(len(ordered) * pct / 100.0)))
    return ordered[idx]


def main() -> None:
    parser = argparse.ArgumentParser(description='Home refresh perf baseline')
    parser.add_argument('--base', default='http://127.0.0.1:5050')
    parser.add_argument('--runs', type=int, default=5)
    args = parser.parse_args()

    per_endpoint: dict[str, list[float]] = {ep: [] for ep in HOME_ENDPOINTS}
    burst_totals: list[float] = []

    for run in range(args.runs):
        burst_start = time.perf_counter()
        for ep in HOME_ENDPOINTS:
            try:
                ms = fetch_ms(args.base, ep)
                per_endpoint[ep].append(ms)
            except (urllib.error.URLError, TimeoutError) as exc:
                print(f'run {run + 1} {ep}: ERROR {exc}')
        burst_totals.append((time.perf_counter() - burst_start) * 1000.0)

    print(f'\nHome burst baseline ({args.runs} runs, base={args.base})\n')
    print(f'{"Endpoint":<55} {"p50 ms":>8} {"p95 ms":>8}')
    print('-' * 73)
    for ep in HOME_ENDPOINTS:
        vals = per_endpoint[ep]
        if not vals:
            print(f'{ep:<55} {"—":>8} {"—":>8}')
            continue
        print(f'{ep:<55} {percentile(vals, 50):>8.1f} {percentile(vals, 95):>8.1f}')
    print('-' * 73)
    print(f'{"Sequential burst total":<55} {percentile(burst_totals, 50):>8.1f} {percentile(burst_totals, 95):>8.1f}')
    if burst_totals:
        print(f'\nMean burst total: {statistics.mean(burst_totals):.1f} ms')


if __name__ == '__main__':
    main()
