#!/usr/bin/env python3
"""Fail CI / Render build if the Spotify-style web shell is missing or stale."""
from __future__ import annotations

import os
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
PUBLIC = os.path.join(REPO, 'public')

REQUIRED = [
    'index.html',
    'css/shell.css',
    'css/style.css',
    'css/dark-theme.css',
    'js/app.js',
    'js/boot.js',
    'js/webCache.js',
    'js/homeFeed.js',
    'js/webPlayback.js',
    'js/clientPrefsSync.js',
]


def main() -> int:
    errors: list[str] = []
    for rel in REQUIRED:
        path = os.path.join(PUBLIC, rel)
        if not os.path.isfile(path):
            errors.append(f'missing {rel}')
    index_path = os.path.join(PUBLIC, 'index.html')
    if os.path.isfile(index_path):
        html = open(index_path, encoding='utf-8').read()
        if 'spotify-app' not in html:
            errors.append('index.html is not the Spotify-style shell (no spotify-app)')
        if 'Bock Media Console' in html and 'spotify-sidebar' not in html:
            errors.append('index.html looks like the legacy console layout')
    shell = os.path.join(PUBLIC, 'css', 'shell.css')
    if os.path.isfile(shell):
        size = os.path.getsize(shell)
        if size < 30000:
            errors.append(f'css/shell.css too small ({size} bytes; expected ~45k)')
    app_js = os.path.join(PUBLIC, 'js', 'app.js')
    if os.path.isfile(app_js):
        lines = sum(1 for _ in open(app_js, encoding='utf-8', errors='replace'))
        if lines < 5000:
            errors.append(f'js/app.js too short ({lines} lines; new UI is ~7700+)')

    if errors:
        print('Web UI verification failed:', file=sys.stderr)
        for err in errors:
            print(f'  - {err}', file=sys.stderr)
        return 1
    print('Web UI assets OK (Spotify-style shell)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
