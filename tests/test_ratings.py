"""Unit tests for bock_ratings."""
import bock_ratings


def test_set_and_get_rating(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(path, 'song', '/music/a.mp3', 4, None, title='Track A')
    assert bock_ratings.get_rating(path, 'song', '/music/a.mp3') == 4
    items = bock_ratings.list_ratings(path)
    assert len(items) == 1
    assert items[0]['stars'] == 4


def test_clear_rating(tmp_path):
    path = str(tmp_path / 'ratings.json')
    bock_ratings.set_rating(path, 'album', 'Abbey Road|Beatles', 5, None, title='Abbey Road', artist='Beatles')
    bock_ratings.set_rating(path, 'album', 'Abbey Road|Beatles', 0, None)
    assert bock_ratings.get_rating(path, 'album', 'Abbey Road|Beatles') == 0
    assert bock_ratings.list_ratings(path) == []
