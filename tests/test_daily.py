"""Unit tests for the daily auto-generated playlist engine (bock_daily)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import bock_daily


def _fake_db(rows):
    def db_query(sql, params=()):
        s = sql.lower()
        if 'group by lower(genre)' in s:
            return [{'genre': 'Rock', 'c': 50}, {'genre': 'Jazz', 'c': 20}]
        if 'like ?' in s and 'genre' in s and 'count(' not in s:
            like = [p for p in params if isinstance(p, str) and p.startswith('%')]
            needle = (like[0].strip('%').lower() if like else '')
            return [r for r in rows if needle in (r.get('genre') or '').lower()]
        if 'year' in s and '<=' in s:
            cutoff = [p for p in params if isinstance(p, int)]
            c = cutoff[0] if cutoff else 9999
            return [r for r in rows if r.get('year') and int(r['year']) <= c]
        return list(rows)
    return db_query


SAMPLE = [
    {'path': f'/m/{i}.mp3', 'year': 1990 + (i % 30), 'genre': 'Rock' if i % 2 else 'Jazz'}
    for i in range(60)
]


def test_regenerate_creates_playlist_per_recipe(tmp_path):
    state_path = str(tmp_path / 'daily.json')
    created = {}
    metas = {}
    ids = iter(f'pid-{i}' for i in range(100))

    def persist(pid, name, paths, create=False):
        created[pid] = {'name': name, 'paths': list(paths), 'create': create}
        return {'id': pid, 'name': name, 'trackCount': len(paths)}

    state = bock_daily.regenerate(
        state_path=state_path,
        db_query=_fake_db(SAMPLE),
        persist_playlist=persist,
        new_id=lambda: next(ids),
        set_meta=lambda pid, m: metas.__setitem__(pid, m),
        play_counts={},
        today='2026-06-24',
        target=10,
        file_exists=lambda p: True,
        member_id='household',
        force=True,
    )

    assert state['date'] == '2026-06-24'
    assert len(state['recipes']) >= 4
    # Every persisted daily playlist carries the prefix + daily meta flag.
    assert all(v['name'].startswith(bock_daily.NAME_PREFIX) for v in created.values())
    assert all(m['daily'] for m in metas.values())


def test_regenerate_reuses_playlist_id_next_day(tmp_path):
    state_path = str(tmp_path / 'daily.json')
    metas = {}
    ids = iter(f'pid-{i}' for i in range(100))

    def persist(pid, name, paths, create=False):
        return {'id': pid, 'name': name, 'trackCount': len(paths)}

    common = dict(
        state_path=state_path,
        db_query=_fake_db(SAMPLE),
        persist_playlist=persist,
        new_id=lambda: next(ids),
        set_meta=lambda pid, m: metas.__setitem__(pid, m),
        play_counts={},
        target=10,
        file_exists=lambda p: True,
    )
    day1 = bock_daily.regenerate(today='2026-06-24', member_id='household', force=True, **common)
    day2 = bock_daily.regenerate(today='2026-06-25', member_id='household', force=True, **common)

    for key, entry in day1['recipes'].items():
        assert day2['recipes'][key]['playlistId'] == entry['playlistId']


def test_seeded_selection_changes_daily(tmp_path):
    """Same recipe yields a different track order on a different date."""
    rng_a = bock_daily._seeded_rng('2026-06-24', 'rotation')
    rng_b = bock_daily._seeded_rng('2026-06-25', 'rotation')
    rows_a = list(range(50))
    rows_b = list(range(50))
    rng_a.shuffle(rows_a)
    rng_b.shuffle(rows_b)
    assert rows_a != rows_b


def test_detach_saved_removes_from_set(tmp_path):
    state_path = str(tmp_path / 'daily.json')
    ids = iter(f'pid-{i}' for i in range(100))

    def persist(pid, name, paths, create=False):
        return {'id': pid, 'name': name, 'trackCount': len(paths)}

    state = bock_daily.regenerate(
        state_path=state_path,
        db_query=_fake_db(SAMPLE),
        persist_playlist=persist,
        new_id=lambda: next(ids),
        set_meta=lambda pid, m: None,
        play_counts={},
        today='2026-06-24',
        target=10,
        file_exists=lambda p: True,
        member_id='household',
        force=True,
    )
    some_key = next(iter(state['recipes']))
    pid = state['recipes'][some_key]['playlistId']

    hit = bock_daily.detach_saved(state_path, pid)
    assert hit == some_key
    after = bock_daily.list_daily(state_path, 'household')
    assert all(item['playlistId'] != pid for item in after['items'])


def test_per_member_daily_playlists_are_separate(tmp_path):
    state_path = str(tmp_path / 'daily.json')
    created = {}

    def persist(pid, name, paths, create=False):
        created.setdefault(pid, {'name': name, 'paths': list(paths), 'member': None})
        return {'id': pid, 'name': name, 'trackCount': len(paths)}

    common = dict(
        state_path=state_path,
        db_query=_fake_db(SAMPLE),
        persist_playlist=persist,
        new_id=lambda: f'pid-{len(created)}',
        set_meta=lambda pid, m: created.setdefault(pid, {}).update({'meta': m}),
        play_counts={},
        today='2026-06-24',
        target=10,
        file_exists=lambda p: True,
        force=True,
    )
    andy = bock_daily.regenerate(member_id='p-andy', **common)
    emma = bock_daily.regenerate(member_id='p-emma', **common)
    andy_pid = andy['recipes']['discovery']['playlistId']
    emma_pid = emma['recipes']['discovery']['playlistId']
    assert andy_pid != emma_pid
    listed = bock_daily.list_daily(state_path, 'p-andy')
    assert listed['memberId'] == 'p-andy'
    assert any(i['playlistId'] == andy_pid for i in listed['items'])
    assert all(i['playlistId'] != emma_pid for i in listed['items'])
