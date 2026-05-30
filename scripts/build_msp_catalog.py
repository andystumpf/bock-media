#!/usr/bin/env python3
"""Build an Alexa Music Skill catalog (AMAZON.MusicPlaylist) from the
MyMediaForAlexa ServerPlaylists.xml.

Each catalog entity's `id` equals the playlist's stable ServerPlaylists ID, so
that the `entityId` Alexa returns in GetPlayableContent maps directly back to a
playlist via server.py's `_msp_playlist_by_id`.

Usage:
    python3 scripts/build_msp_catalog.py [--out skill/catalog_playlists.json]

Catalog reference:
    https://developer.amazon.com/en-US/docs/alexa/music-skills/catalog-reference.html
"""
import argparse
import datetime
import json
import os
import re
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MMA_PATH = os.environ.get('MMA_PATH', '/home/plex/.MyMediaForAlexa')
PLAYLISTS_XML = os.path.join(MMA_PATH, 'ServerPlaylists.xml')

# Words that add no entity-resolution value and only pollute the voice model.
_NOISE = re.compile(r'\b(playlist|mix|radio|collection|complete)\b', re.IGNORECASE)


def _xml_text(node, tag):
    el = node.find(tag)
    return (el.text or '').strip() if el is not None and el.text else ''


def _alternate_names(name):
    """Derive a few spoken aliases so Alexa resolves casual phrasings."""
    alts = set()
    stripped = _NOISE.sub('', name).strip()
    stripped = re.sub(r'\s{2,}', ' ', stripped)
    if stripped and stripped.lower() != name.lower():
        alts.add(stripped)
    # Drop a leading article.
    no_article = re.sub(r'^(the|a)\s+', '', name, flags=re.IGNORECASE).strip()
    if no_article and no_article.lower() != name.lower():
        alts.add(no_article)
    return [a for a in alts if a]


def build_catalog():
    tree = ET.parse(PLAYLISTS_XML)
    now = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')
    entities = []
    seen = set()
    for entry in tree.getroot().findall('Entry'):
        key = entry.find('Key')
        if key is None:
            continue
        pid = _xml_text(key, 'ID')
        name = _xml_text(key, 'Name')
        if not pid or not name or pid in seen:
            continue
        seen.add(pid)
        entity = {
            'id': pid,
            'names': [{'language': 'en', 'value': name[:512]}],
            'popularity': {'default': 75},
            'lastUpdatedTime': now,
            'locales': [{'country': 'US', 'language': 'en'}],
        }
        alts = _alternate_names(name)
        if alts:
            entity['alternateNames'] = [{'language': 'en', 'values': alts}]
        entities.append(entity)
    return {
        'type': 'AMAZON.MusicPlaylist',
        'version': 2.0,
        'locales': [{'country': 'US', 'language': 'en'}],
        'entities': entities,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(HERE, 'skill', 'catalog_playlists.json'))
    args = ap.parse_args()
    catalog = build_catalog()
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)
    print(f'Wrote {len(catalog["entities"])} playlist entities to {args.out}')


if __name__ == '__main__':
    main()
