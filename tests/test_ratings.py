"""Unit tests for bock_ratings."""
import bock_ratings


def test_set_and_get_rating(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(path, 'song', '/music/a.mp3', 4, None, title='Track A', member_id='p-a')
    assert bock_ratings.get_rating(path, 'song', '/music/a.mp3', 'p-a') == 4
    assert bock_ratings.get_rating(path, 'song', '/music/a.mp3', 'p-b') == 0
    items = bock_ratings.list_ratings(path, 'p-a')
    assert len(items) == 1
    assert items[0]['stars'] == 4


def test_rated_playlist_detail(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(path, 'song', '/music/a.mp3', 5, None, title='A', member_id='p-a')
    bock_ratings.set_rating(path, 'song', '/music/b.mp3', 5, None, title='B', member_id='p-a')
    bock_ratings.set_rating(path, 'song', '/music/c.mp3', 2, None, title='C', member_id='p-a')
    detail = bock_ratings.rated_playlist_detail(path, 5, member_id='p-a')
    assert detail['id'] == 'rated-stars-5'
    assert detail['total'] == 2
    assert len(detail['tracks']) == 2
    assert bock_ratings.parse_rated_playlist_id('rated-stars-3') == 3


def test_clear_rating(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(
        path, 'album', 'Abbey Road|Beatles', 5, None,
        title='Abbey Road', artist='Beatles', member_id='p-a',
    )
    bock_ratings.set_rating(path, 'album', 'Abbey Road|Beatles', 0, None, member_id='p-a')
    assert bock_ratings.get_rating(path, 'album', 'Abbey Road|Beatles', 'p-a') == 0
    assert bock_ratings.list_ratings(path, 'p-a') == []


def test_per_member_isolation(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(path, 'song', '/music/shared.mp3', 5, None, member_id='p-a')
    bock_ratings.set_rating(path, 'song', '/music/shared.mp3', 2, None, member_id='p-b')
    assert bock_ratings.get_rating(path, 'song', '/music/shared.mp3', 'p-a') == 5
    assert bock_ratings.get_rating(path, 'song', '/music/shared.mp3', 'p-b') == 2


def test_legacy_format_migrates_on_read(tmp_path):
    path = tmp_path / 'ratings.json'
    path.write_text(
        '{"items": {"song:/music/old.mp3": {"kind": "song", "id": "/music/old.mp3", "stars": 4}}}',
        encoding='utf-8',
    )
    assert bock_ratings.get_rating(str(path), 'song', '/music/old.mp3', '') == 4


def test_migrate_legacy_to_member(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(path, 'song', '/music/old.mp3', 4, None, member_id='')
    assert bock_ratings.migrate_legacy_to_member(path, 'p-a', None)
    assert bock_ratings.get_rating(path, 'song', '/music/old.mp3', 'p-a') == 4
    assert bock_ratings.get_rating(path, 'song', '/music/old.mp3', '') == 0
