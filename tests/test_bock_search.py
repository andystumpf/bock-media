"""Unit tests for bock_search unified library search."""
import bock_search


class TestScoreText:
    def test_exact_match(self):
        assert bock_search.score_text('pink floyd', 'Pink Floyd') == 1.0

    def test_prefix_match(self):
        assert bock_search.score_text('pink', 'Pink Floyd') >= 0.85

    def test_no_match(self):
        assert bock_search.score_text('zzzz', 'Pink Floyd') < 0.5


class TestPrefixFuzzy:
    def test_rem_matches_dotted_artist(self):
        assert bock_search.field_matches_query('rem', 'R.E.M.')
        assert bock_search.field_matches_query('REM', 'R.E.M.')
        assert bock_search.score_prefix('rem', 'R.E.M.') >= 0.95

    def test_rem_not_mid_word(self):
        assert not bock_search.field_matches_query('rem', 'Abbas Premjee')
        assert not bock_search.field_matches_query('rem', 'Premjee')

    def test_word_prefix_only(self):
        assert bock_search.field_matches_query('rem', 'Rembrandts')
        assert bock_search.field_matches_query('rem', 'Remix')
        assert not bock_search.field_matches_query('rem', 'Greatest Premium Hits')

    def test_song_match_by_artist(self):
        assert bock_search.library_search_song_match(
            'rem', 'Losing My Religion', 'Out of Time', artist='R.E.M.',
        )


class TestSongMatch:
    def test_album_noise_excluded(self):
        m = bock_search.library_search_song_match
        assert not m('mamma', 'Waterloo', 'Mamma Mia!')
        assert m('mamma', 'Mamma Mia', '[2001] ABBA')


class TestRunSearch:
    def test_empty_query(self):
        out = bock_search.run_search(db_query=lambda *a, **k: [], db_one=lambda *a, **k: None, q='a')
        assert out['songs'] == []
        assert out['counts'] == {}

    def test_section_filter(self, monkeypatch):
        def fake_db_query(sql, params=()):
            if 'GROUP BY album' in sql:
                return []
            if 'GROUP BY' in sql and 'genre' not in sql:
                return [{'artist': 'ABBA', 'art_path': '/a.mp3'}]
            if 'songs_cache' in sql or 'songs_fts' in sql:
                return []
            return []

        monkeypatch.setattr(bock_search, 'fts_songs_ranked', lambda *a, **k: [])
        out = bock_search.run_search(
            db_query=fake_db_query,
            db_one=lambda *a, **k: None,
            q='abba',
            section='artists',
            load_playlist_entries_fn=lambda: [],
            score_playlist_fn=bock_search.score_text,
            load_smart_playlists_fn=lambda: [],
        )
        assert out['artists']
        assert out['songs'] == []


class TestAlbumSearch:
    def test_bracketed_album_title(self):
        rows = [
            {'album': '[1991] Gish (Deluxe Edition)', 'artist': 'The Smashing Pumpkins', 'art_path': '/a.mp3'},
        ]

        def fake_db(sql, params=()):
            return rows

        out = bock_search.search_albums(fake_db, 'gish', 10)
        assert len(out) == 1
        assert 'Gish' in out[0]['name']

    def test_albums_by_artist_word_match(self):
        rows = [
            {'album': 'Siamese Dream', 'artist': 'The Smashing Pumpkins', 'art_path': '/b.mp3'},
            {'album': '[1991] Gish (Deluxe Edition)', 'artist': 'The Smashing Pumpkins', 'art_path': '/a.mp3'},
            {'album': 'Abbas Premjee', 'artist': 'Abbas Premjee', 'art_path': '/c.mp3'},
        ]

        def fake_db(sql, params=()):
            return rows

        out = bock_search.search_albums(fake_db, 'smashing', 10)
        names = {a['name'] for a in out}
        assert any('Gish' in n for n in names)
        assert any('Siamese' in n for n in names)
        assert 'Abbas Premjee' not in names

    def test_rem_not_mid_word_album(self):
        rows = [
            {'album': 'Abbas Premjee', 'artist': 'Abbas Premjee', 'art_path': '/c.mp3'},
            {'album': 'Out of Time', 'artist': 'R.E.M.', 'art_path': '/d.mp3'},
        ]

        def fake_db(sql, params=()):
            if 'GROUP BY album' in sql:
                return rows
            return []

        out = bock_search.search_albums(fake_db, 'rem', 10)
        names = {a['name'] for a in out}
        assert 'Abbas Premjee' not in names
        assert 'Out of Time' in names


class TestPins:
    def test_save_and_load(self, tmp_path):
        bock_search.configure(str(tmp_path))
        bock_search.save_pins([{'kind': 'genre', 'title': 'Jazz', 'name': 'Jazz'}])
        pins = bock_search.load_pins()
        assert len(pins) == 1
        assert pins[0]['kind'] == 'genre'
