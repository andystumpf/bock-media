import json

import bock_home_defaults


def test_policy_from_config_defaults():
    p = bock_home_defaults.policy_from_config({})
    assert p['playlistsScope'] == 'household'
    assert p['playlistLimit'] == 2000


def test_save_and_load_pins(tmp_path):
    data_dir = str(tmp_path)
    pins = [
        {'sectionId': 'browse-genres', 'playlistId': 'pl-1', 'playlistName': 'Rock Mix'},
        {'sectionId': 'mood-dinner', 'playlistId': 'pl-2', 'playlistName': 'Dinner'},
    ]
    saved = bock_home_defaults.save(data_dir, section_pins=pins)
    assert saved['sectionPins']
    loaded = bock_home_defaults.load(data_dir)
    assert len(loaded['sectionPins']) == 2
    assert loaded['sectionPins'][0]['sectionId'] == 'browse-genres'


def test_normalize_pins_dedupes():
    raw = [
        {'sectionId': 'a', 'playlistId': '1', 'playlistName': 'A'},
        {'sectionId': 'a', 'playlistId': '1', 'playlistName': 'A dup'},
    ]
    out = bock_home_defaults._normalize_pins(raw)
    assert len(out) == 1
