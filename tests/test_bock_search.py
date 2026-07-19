"""Unit tests for bock_search unified library search."""
import sqlite3

import pytest

import bock_search
import bock_search_ext


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


class TestArtistSearch:
    def test_smash_matches_the_smashing_pumpkins(self):
        rows = [
            {'artist': 'Smashing Pumpkins', 'art_path': '/a.mp3', 'albums': 2},
            {'artist': 'The Smashing Pumpkins', 'art_path': '/b.mp3', 'albums': 10},
            {'artist': 'The Offspring', 'art_path': '/c.mp3', 'albums': 5},
        ]

        def fake_db(sql, params=()):
            if 'albums_agg' in sql:
                return []
            if 'GROUP BY' in sql and 'artist' in sql:
                matched = []
                for row in rows:
                    artist = (row['artist'] or '').lower()
                    for pat in params:
                        if not isinstance(pat, str):
                            continue
                        p = pat.lower()
                        if p.endswith('%') and not p.startswith('%'):
                            if artist.startswith(p[:-1]):
                                matched.append(row)
                                break
                        elif p.startswith('%') and p.endswith('%'):
                            if p[1:-1] in artist:
                                matched.append(row)
                                break
                return matched
            return []

        out = bock_search.search_artists(fake_db, 'smash', 10)
        names = {a['artist'] for a in out}
        assert 'The Smashing Pumpkins' in names
        assert 'The Offspring' not in names

    def _memory_db(self):
        import sqlite3
        conn = sqlite3.connect(':memory:')
        conn.row_factory = sqlite3.Row
        conn.execute(
            'CREATE TABLE songs_cache (rowid INTEGER PRIMARY KEY, title TEXT, artist TEXT, '
            'album TEXT, path TEXT, album_artist TEXT, genre TEXT)'
        )
        conn.execute(
            "INSERT INTO songs_cache VALUES "
            "(1, 'Learn to Fly', 'Foo Fighters', 'There Is Nothing Left to Lose', '/a.mp3', '', 'Rock'), "
            "(2, 'Learning to Breathe', 'Switchfoot', 'Learning to Breathe', '/b.mp3', '', 'Rock')"
        )
        conn.commit()

        def db_query(sql, params=()):
            cur = conn.execute(sql, params)
            return [dict(r) for r in cur.fetchall()]

        return db_query

    def test_learn_to_field_match(self):
        assert bock_search.field_matches_query('learn to', 'Learn to Fly')

    def test_typing_tolerance_extends_valid_prefix(self):
        assert bock_search.field_matches_query('rem', 'R.E.M.')
        assert bock_search.field_matches_query('reme', 'R.E.M.')
        assert not bock_search.field_matches_query('prem', 'R.E.M.')

    def test_learn_to_fts_fallback_without_fts_table(self):
        db_query = self._memory_db()
        rows = bock_search.fts_songs_ranked(db_query, 'learn to', 10)
        titles = {r['title'] for r in rows}
        assert 'Learn to Fly' in titles
        assert 'Learning to Breathe' not in titles

    def test_compact_prefix_clause(self):
        clause, params = bock_search._song_field_match_clause('learn to', ('title',))
        assert 'REPLACE' in clause
        assert 'learnto%' in params


class TestPins:
    @pytest.fixture(autouse=True)
    def _restore_pins_path(self):
        # configure() mutates the module global — restore it so later test
        # files (test_api pins endpoints) don't read this class's tmp files.
        old = bock_search.PINS_PATH
        yield
        bock_search.PINS_PATH = old

    def test_save_and_load(self, tmp_path):
        bock_search.configure(str(tmp_path))
        bock_search.save_pins([{'kind': 'genre', 'title': 'Jazz', 'name': 'Jazz'}])
        pins = bock_search.load_pins()
        assert len(pins) == 1
        assert pins[0]['kind'] == 'genre'

    def test_per_member_pins(self, tmp_path):
        bock_search.configure(str(tmp_path))
        bock_search.save_pins([{'kind': 'genre', 'title': 'Legacy', 'name': 'Legacy'}])
        prefs = str(tmp_path / 'client_prefs.json')
        bock_search.save_pins_for_member(prefs, 'p-andy', [
            {'kind': 'artist', 'title': 'R.E.M.', 'name': 'R.E.M.'},
        ])
        bock_search.save_pins_for_member(prefs, 'p-emma', [
            {'kind': 'genre', 'title': 'Pop', 'name': 'Pop'},
        ])
        assert bock_search.load_pins_for_member(prefs, 'p-andy')[0]['name'] == 'R.E.M.'
        assert bock_search.load_pins_for_member(prefs, 'p-emma')[0]['name'] == 'Pop'
        migrated = bock_search.load_pins_for_member(prefs, 'p-jack')
        assert migrated[0]['name'] == 'Legacy'


class TestGenreSearch:
    """Regression: "french" + All must surface genre-tagged tracks (plan phase 1)."""

    @pytest.fixture
    def genre_db(self, tmp_path):
        db = tmp_path / 'songs.db'
        conn = sqlite3.connect(str(db))
        conn.execute(
            'CREATE TABLE songs_cache (rowid INTEGER PRIMARY KEY, title TEXT, artist TEXT, '
            'album TEXT, path TEXT, album_artist TEXT, genre TEXT)'
        )
        conn.executemany(
            'INSERT INTO songs_cache VALUES (?,?,?,?,?,?,?)',
            [
                (1, 'Ne me quitte pas', 'Jacques Brel', 'La Valse à Mille Temps',
                 '/music/french/brel.mp3', '', 'French'),
                (2, 'La Vie en Rose', 'Édith Piaf', 'Voix',
                 '/music/french/piaf.mp3', '', 'Chanson française'),
                (3, 'Zombie', 'The Cranberries', 'No Need to Argue',
                 '/music/rock/zombie.mp3', '', 'Rock'),
            ],
        )
        conn.commit()
        conn.close()

        def get_db_rw():
            c = sqlite3.connect(str(db))
            c.row_factory = sqlite3.Row
            return c

        def db_query(sql, params=()):
            c = get_db_rw()
            try:
                return [dict(r) for r in c.execute(sql, params).fetchall()]
            finally:
                c.close()

        def db_one(sql, params=()):
            rows = db_query(sql, params)
            return rows[0] if rows else None

        return get_db_rw, db_query, db_one

    def _build_fts(self, get_db_rw, db_query, db_one):
        bock_search_ext._FTS_TABLE_READY = False
        bock_search_ext._FTS_LAST_SYNC = 0.0
        bock_search_ext.ensure_fts(get_db_rw, db_query, db_one)

    def test_genre_bucket_direct_hit(self, genre_db):
        _, db_query, _ = genre_db
        names = {r['genre'] for r in bock_search.search_genres(db_query, 'french', 10)}
        assert 'French' in names

    def test_genre_bucket_diacritic_fold(self, genre_db):
        # Query without accents must match the stored accented tag.
        _, db_query, _ = genre_db
        names = {r['genre'] for r in bock_search.search_genres(db_query, 'francais', 10)}
        assert 'Chanson française' in names

    def test_genre_bucket_translation_alias(self, genre_db):
        # "french" should also surface tags stored in French spelling.
        _, db_query, _ = genre_db
        names = {r['genre'] for r in bock_search.search_genres(db_query, 'french', 10)}
        assert 'Chanson française' in names

    def test_song_hit_via_genre_tag(self, genre_db):
        # Title/artist contain no "french" — only the genre tag matches.
        get_db_rw, db_query, db_one = genre_db
        self._build_fts(get_db_rw, db_query, db_one)
        out = bock_search.run_search(
            db_query=db_query, db_one=db_one, q='french',
            load_playlist_entries_fn=lambda: [],
            score_playlist_fn=bock_search.score_text,
            load_smart_playlists_fn=lambda: [],
        )
        assert 'Ne me quitte pas' in {s['title'] for s in out['songs']}
        assert 'French' in {g['name'] for g in out['genres']}

    def test_song_hit_via_genre_without_fts(self, genre_db):
        # LIKE fallback (no songs_fts table) must also consult genre.
        _, db_query, _ = genre_db
        rows = bock_search.fts_songs_ranked(db_query, 'french', 10)
        assert 'Ne me quitte pas' in {r['title'] for r in rows}

    def test_fts_schema_upgrade_adds_genre(self, genre_db):
        # Pre-existing FTS table without the genre column gets rebuilt.
        get_db_rw, db_query, db_one = genre_db
        conn = get_db_rw()
        conn.execute(
            'CREATE VIRTUAL TABLE songs_fts USING fts5('
            'title, artist, album, path, content=songs_cache, content_rowid=rowid, '
            'tokenize="unicode61 remove_diacritics 2")'
        )
        conn.execute(
            'INSERT INTO songs_fts(rowid, title, artist, album, path) '
            'SELECT rowid, title, artist, album, path FROM songs_cache'
        )
        conn.commit()
        conn.close()
        self._build_fts(get_db_rw, db_query, db_one)
        row = db_one("SELECT sql FROM sqlite_master WHERE name='songs_fts'")
        assert 'genre' in (row['sql'] or '')
        rows = db_query(
            "SELECT s.title FROM songs_fts f JOIN songs_cache s ON s.rowid = f.rowid "
            "WHERE songs_fts MATCH 'french*'"
        )
        assert rows

    def test_scoped_source_excludes_other_folders(self, genre_db):
        get_db_rw, db_query, db_one = genre_db
        self._build_fts(get_db_rw, db_query, db_one)
        kwargs = dict(
            db_query=db_query, db_one=db_one, q='zombie',
            load_playlist_entries_fn=lambda: [],
            score_playlist_fn=bock_search.score_text,
            load_smart_playlists_fn=lambda: [],
        )
        scoped = bock_search.run_search(source='/music/french', **kwargs)
        assert scoped['songs'] == []
        all_libs = bock_search.run_search(source=None, **kwargs)
        assert 'Zombie' in {s['title'] for s in all_libs['songs']}
        same_folder = bock_search.run_search(source='/music/rock', **kwargs)
        assert 'Zombie' in {s['title'] for s in same_folder['songs']}


class TestFuzzyMatching:
    def test_fold_diacritics(self):
        assert bock_search._fold('Beyoncé') == 'beyonce'
        assert bock_search.score_folded('beyonce', 'Beyoncé') >= 0.95

    def test_ampersand_and(self):
        assert bock_search.score_folded('earth and fire', 'Earth & Fire') >= 0.95
        assert bock_search.score_folded('earth fire', 'Earth & Fire') >= 0.72

    def test_substring_mid_word(self):
        assert bock_search.score_substring('1979', '1979 - Remastered') > 0
        assert bock_search.score_substring('oasi', 'Oasis') > 0

    def test_fuzzy_typo(self):
        assert bock_search.score_fuzzy('smashing pumkins', 'The Smashing Pumpkins') >= 0.35

    def test_word_order_tokens(self):
        assert bock_search.score_folded('pumpkins smashing', 'The Smashing Pumpkins') >= 0.72

    def test_strict_rem_still_blocks_mid_word(self):
        assert not bock_search.field_matches_query('rem', 'Abbas Premjee')
        assert bock_search.score_substring('rem', 'Abbas Premjee') == 0.0
