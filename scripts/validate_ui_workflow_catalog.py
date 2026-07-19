#!/usr/bin/env python3
"""Validate shared/ui-test-workflows/catalog.yaml against known tags and fixtures."""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    yaml = None

REPO = Path(__file__).resolve().parents[1]
CATALOG = REPO / 'shared/ui-test-workflows/catalog.yaml'
MANIFEST = REPO / 'shared/fixtures/ui_test_manifest.json'
ANDROID_TAGS = REPO / 'android/app/src/main/kotlin/com/bockmedia/console/ui/testing/BockTestTags.kt'
IOS_TAGS = REPO / 'ios/BockMediaUITests/BockTestTags.swift'

REQUIRED_KEYS = {'id', 'tier', 'area', 'platforms'}


def load_catalog():
    if yaml is None:
        print('PyYAML required: pip install pyyaml', file=sys.stderr)
        sys.exit(2)
    data = yaml.safe_load(CATALOG.read_text(encoding='utf-8'))
    if not isinstance(data, list):
        raise SystemExit(f'{CATALOG}: expected top-level list')
    return data


def extract_tags(path: Path) -> set[str]:
    text = path.read_text(encoding='utf-8')
    tags = set()
    for line in text.splitlines():
        line = line.strip()
        if 'const val ' in line and '= "bock_' in line:
            part = line.split('=', 1)[1].strip().strip('"')
            tags.add(part)
        if line.startswith('static let ') and '= "bock_' in line:
            part = line.split('=', 1)[1].strip().strip('"')
            tags.add(part)
    return tags


def fixture_keys(manifest: dict) -> set[str]:
    out = set()
    for section, val in manifest.items():
        if isinstance(val, dict) and 'resolveBy' not in val and 'name' not in val:
            for key in val:
                out.add(f'{section}.{key}')
    return out


def main() -> int:
    entries = load_catalog()
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    tags = extract_tags(ANDROID_TAGS) | extract_tags(IOS_TAGS)
    fixtures = fixture_keys(manifest)
    ids = set()
    errors = []

    for i, entry in enumerate(entries):
        label = entry.get('id', f'#{i}')
        missing = REQUIRED_KEYS - set(entry)
        if missing:
            errors.append(f'{label}: missing keys {sorted(missing)}')
        wid = entry.get('id')
        if wid in ids:
            errors.append(f'duplicate id: {wid}')
        ids.add(wid)

        for step in entry.get('steps') or []:
            if isinstance(step, dict):
                if 'assert_tag' in step:
                    tag = step['assert_tag']
                    if isinstance(tag, str) and not tag.startswith('bock_'):
                        errors.append(f'{label}: assert_tag must be bock_* got {tag!r}')
                    elif isinstance(tag, str) and tag not in tags and '{' not in tag:
                        errors.append(f'{label}: unknown tag {tag!r} (add to BockTestTags)')

        for fx in entry.get('fixtures') or []:
            if fx not in fixtures:
                errors.append(f'{label}: unknown fixture {fx!r}')

    if errors:
        print(f'Catalog validation failed ({len(errors)} issues):', file=sys.stderr)
        for e in errors:
            print(f'  - {e}', file=sys.stderr)
        return 1

    tiers = {}
    for e in entries:
        tiers[e['tier']] = tiers.get(e['tier'], 0) + 1
    print(f'OK: {len(entries)} workflows — tiers {dict(sorted(tiers.items()))}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
