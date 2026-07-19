#!/usr/bin/env python3
"""Sanitize a Bock Media tree before publishing to the public repo.

Run against an export directory (not the live private checkout). Replaces
home-lab defaults with placeholders and fails if known secret patterns remain.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

TEXT_SUFFIXES = {
    '.py', '.sh', '.md', '.json', '.yaml', '.yml', '.kt', '.swift',
    '.mjs', '.js', '.css', '.html', '.xml', '.plist', '.txt', '.kts',
    '.gradle', '.properties', '.example', '.toml', '.ini', '.mdc',
}

SKIP_DIRS = {'.git', '.pytest_cache', '__pycache__', 'node_modules', '.gradle'}

REPLACEMENTS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r'plex@192\.168\.1\.187'), 'user@your-server.local'),
    (re.compile(r'http://192\.168\.1\.187:3001'), 'http://your-server.local:3001'),
    (re.compile(r'http://192\.168\.1\.187:5000'), 'http://your-server.local:5000'),
    (re.compile(r'192\.168\.1\.187'), 'your-server.local'),
    (re.compile(r'~/bock-media'), '~/bock-media'),
    (re.compile(r'/opt/bock-media(?:/\.publish-export)?'), '/opt/bock-media'),
    (re.compile(r'/opt/bock-media/'), '/opt/bock-media/'),
    (re.compile(r'pytest-of-demo'), 'pytest-of-demo'),
    (re.compile(r'https://github\.com/andystumpf/ourMedia'), 'https://github.com/andystumpf/bock-media'),
    (re.compile(r'https://github\.com/ourMedia\b'), 'https://github.com/andystumpf/bock-media'),
]

BLOCK_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ('GitHub PAT', re.compile(r'ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}')),
    ('OpenAI key', re.compile(r'sk-[A-Za-z0-9]{20,}')),
    ('AWS key', re.compile(r'AKIA[0-9A-Z]{16}')),
    ('Private key', re.compile(r'BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY')),
    ('Home NAS host', re.compile(r'192\.168\.1\.187')),
    ('Local home path', re.compile(r'/Users/andymac')),
    ('Local username', re.compile(r'andymac')),
    ('Private repo URL', re.compile(r'github\.com/andystumpf/ourMedia')),
    ('Home NAS SSH', re.compile(r'plex@192\.168\.1\.187')),
]

ALLOWLIST_SUBSTRINGS = (
    'sanitize_for_public.py',
    'publish_public_repo.sh',
    'demo-only-not-a-real-login',
    'GENERATE_AND_SET_LOCALLY',
    'SET_LOCALLY',
    'YOUR_CUSTOM_SKILL_ID',
    'YOUR_MSP_SKILL_ID',
    'YOUR_CATALOG_ID',
    'demo@example.com',
    'your-server.local',
    'your-domain.example.com',
    'your-tunnel.example.com',
)


def _is_text_file(path: Path) -> bool:
    if path.suffix in TEXT_SUFFIXES:
        return True
    if path.name in {'Dockerfile', 'LICENSE', 'Makefile'}:
        return True
    return False


def sanitize_tree(root: Path) -> int:
    changed = 0
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if not _is_text_file(path):
            continue
        try:
            original = path.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            continue
        updated = original
        for pattern, repl in REPLACEMENTS:
            updated = pattern.sub(repl, updated)
        if updated != original:
            path.write_text(updated, encoding='utf-8')
            changed += 1
    return changed


def scan_tree(root: Path) -> list[str]:
    findings: list[str] = []
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if not _is_text_file(path):
            continue
        try:
            text = path.read_text(encoding='utf-8', errors='replace')
        except OSError:
            continue
        rel = path.relative_to(root).as_posix()
        for label, pattern in BLOCK_PATTERNS:
            for match in pattern.finditer(text):
                snippet = match.group(0)
                if any(token in rel or token in snippet for token in ALLOWLIST_SUBSTRINGS):
                    continue
                if label == 'Local username' and '/music/' in text and 'andy.mp3' in text:
                    continue
                findings.append(f'{rel}: {label} -> {snippet[:80]}')
    return findings


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print('usage: sanitize_for_public.py <export-root>', file=sys.stderr)
        return 2
    root = Path(argv[1]).resolve()
    if not root.is_dir():
        print(f'not a directory: {root}', file=sys.stderr)
        return 2
    changed = sanitize_tree(root)
    findings = scan_tree(root)
    print(f'sanitized {changed} file(s)')
    if findings:
        print('SECURITY SCAN FAILED — unresolved sensitive content:', file=sys.stderr)
        for item in findings[:50]:
            print(f'  - {item}', file=sys.stderr)
        if len(findings) > 50:
            print(f'  ... and {len(findings) - 50} more', file=sys.stderr)
        return 1
    print('security scan passed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main(sys.argv))
