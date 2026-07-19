"""Household home layout defaults — policy + section pins persisted on the NAS."""
import json
import os
import time

DEFAULT_VERSION = 1
DEFAULT_POLICY = {
    'playlistsScope': 'household',
    'playlistLimit': 2000,
    'genreLimit': 80,
}


def defaults_path(data_dir):
    return os.path.join(data_dir, 'home_defaults.json')


def policy_from_config(config):
    """Merge config.json home block over built-in defaults."""
    cfg = (config or {}).get('home') if isinstance(config, dict) else {}
    if not isinstance(cfg, dict):
        cfg = {}
    out = dict(DEFAULT_POLICY)
    scope = (cfg.get('playlistsScope') or cfg.get('playlists_scope') or '').strip().lower()
    if scope in ('household', 'member'):
        out['playlistsScope'] = scope
    for key, cfg_key in (
        ('playlistLimit', 'playlistLimit'),
        ('genreLimit', 'genreLimit'),
    ):
        raw = cfg.get(cfg_key) if cfg.get(cfg_key) is not None else cfg.get(
            'playlist_limit' if key == 'playlistLimit' else 'genre_limit'
        )
        if raw is not None:
            try:
                out[key] = int(raw)
            except (TypeError, ValueError):
                pass
    out['playlistLimit'] = max(1, min(out['playlistLimit'], 2000))
    out['genreLimit'] = max(1, min(out['genreLimit'], 200))
    return out


def load(data_dir, config=None):
    """Return merged policy + optional section pins from disk."""
    policy = policy_from_config(config)
    path = defaults_path(data_dir)
    pins = []
    saved_at = None
    version = DEFAULT_VERSION
    try:
        with open(path, encoding='utf-8') as fh:
            raw = json.load(fh) or {}
        if isinstance(raw.get('policy'), dict):
            for k, v in raw['policy'].items():
                if k in policy and v is not None:
                    policy[k] = v
        pins = _normalize_pins(raw.get('sectionPins') or raw.get('section_pins') or [])
        saved_at = raw.get('savedAt') or raw.get('saved_at')
        version = int(raw.get('version') or DEFAULT_VERSION)
    except (OSError, json.JSONDecodeError, TypeError, ValueError):
        pass
    return {
        'version': version,
        'savedAt': saved_at,
        'policy': policy,
        'sectionPins': pins,
    }


def save(data_dir, *, policy=None, section_pins=None, atomic_write=None):
    """Write home_defaults.json (used by snapshot script / admin API)."""
    existing = load(data_dir)
    payload = {
        'version': DEFAULT_VERSION,
        'savedAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
        'policy': dict(existing['policy']),
        'sectionPins': list(existing['sectionPins']),
    }
    if isinstance(policy, dict):
        payload['policy'].update({k: policy[k] for k in DEFAULT_POLICY if k in policy})
    if section_pins is not None:
        payload['sectionPins'] = _normalize_pins(section_pins)
    path = defaults_path(data_dir)
    if atomic_write:
        atomic_write(path, payload)
    else:
        os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
        with open(path, 'w', encoding='utf-8') as fh:
            json.dump(payload, fh, indent=2)
    return payload


def _normalize_pins(raw):
    out = []
    seen = set()
    for row in raw or []:
        if not isinstance(row, dict):
            continue
        section_id = (row.get('sectionId') or row.get('section_id') or '').strip()
        playlist_id = (row.get('playlistId') or row.get('playlist_id') or '').strip()
        if not section_id or not playlist_id:
            continue
        key = (section_id, playlist_id)
        if key in seen:
            continue
        seen.add(key)
        out.append({
            'sectionId': section_id,
            'playlistId': playlist_id,
            'playlistName': (row.get('playlistName') or row.get('playlist_name') or '').strip(),
            'pinnedAtMs': int(row.get('pinnedAtMs') or row.get('pinned_at_ms') or 0),
        })
    return out


def pins_for_clients(defaults):
    """Lightweight payload for /api/home."""
    d = defaults or {}
    return {
        'version': d.get('version', DEFAULT_VERSION),
        'savedAt': d.get('savedAt'),
        'policy': d.get('policy') or dict(DEFAULT_POLICY),
        'sectionPins': d.get('sectionPins') or [],
    }
