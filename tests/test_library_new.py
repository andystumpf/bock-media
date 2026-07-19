"""Tests for bock_library_new."""
import bock_library_new


def test_artist_filter_empty():
    sql, params = bock_library_new._artist_filter_clause([])
    assert sql == 'AND 0'
    assert params == []


def test_artist_filter_clause():
    sql, params = bock_library_new._artist_filter_clause(['Radiohead', 'Beck'])
    assert 'IN (?,?)' in sql
    assert params == ['beck', 'radiohead']
