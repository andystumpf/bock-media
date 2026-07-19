"""Register Spotify-parity API routes on the Flask app."""
import json
import os
import time

from flask import jsonify, request, Response

import bock_acquire
import bock_artist_top_tracks
import bock_continue
import bock_discover
import bock_folders
import bock_handoff
import bock_home
import bock_loudness
import bock_listen_agent
import bock_mix_muse
import bock_perf
import bock_play_counts
import bock_resonance
import bock_search
import bock_search_ext


def resolve_library_artist_name(db_query, db_one, requested):
    """Map a requested artist label to the canonical name stored in songs_cache."""
    import re
    from urllib.parse import unquote

    req = unquote(str(requested or '')).strip().replace('+', ' ')
    if not req:
        return ''

    candidates = [req]
    stripped = re.sub(r'^the\s+', '', req, flags=re.I).strip()
    if stripped and stripped not in candidates:
        candidates.append(stripped)
    if not req.lower().startswith('the '):
        with_the = f'The {req}'
        if with_the not in candidates:
            candidates.append(with_the)

    best_name = None
    best_count = -1
    for candidate in candidates:
        row = db_one(
            'SELECT artist, COUNT(*) AS cnt FROM songs_cache '
            'WHERE LOWER(TRIM(artist)) = LOWER(?) AND path IS NOT NULL AND path != "" '
            'GROUP BY artist ORDER BY cnt DESC LIMIT 1',
            [candidate],
        )
        if not row or not row.get('artist'):
            continue
        count = int(row.get('cnt') or 0)
        if count > best_count:
            best_count = count
            best_name = row['artist']
    if best_name:
        return best_name

    req_key = bock_acquire._artist_key(req)
    if not req_key:
        return req

    rows = db_query(
        'SELECT artist, COUNT(*) AS cnt FROM songs_cache '
        'WHERE artist IS NOT NULL AND TRIM(artist) != "" '
        'AND path IS NOT NULL AND path != "" '
        'GROUP BY artist',
    ) or []
    matches = [r for r in rows if bock_acquire._artist_key(r.get('artist')) == req_key]
    if not matches:
        return req
    matches.sort(key=lambda r: -(int(r.get('cnt') or 0)))
    return matches[0]['artist']


def _cache_artist_matches(requested, cached_artist):
    """True when a music-video cache key artist matches the requested library artist."""
    import re
    req = (requested or '').strip().lower()
    ca = (cached_artist or '').strip().lower()
    if not req:
        return True
    if not ca:
        return False
    if req == ca or req in ca or ca in req:
        return True
    strip = lambda s: re.sub(r'^the\s+', '', s, flags=re.I).strip()
    return strip(req) == strip(ca)


def register(app, g):
    """g is server module globals()."""

    get_db = g['get_db']
    db_query = g['db_query']
    db_one = g['db_one']
    get_pref = g['get_pref']
    get_public_url = g['get_public_url']
    DATA_DIR = g['DATA_DIR']
    MUSIC_ROOT = g['MUSIC_ROOT']
    STREAM_HISTORY_PATH = g['STREAM_HISTORY_PATH']
    _ffmpeg_available = g['_ffmpeg_available']
    _load_household = g['_load_household']
    _load_playlist_entries = g['_load_playlist_entries']
    _load_smart_playlists = g['_load_smart_playlists']
    _atomic_json_write = g.get('_atomic_json_write')
    read_np_state_for_device = g['read_np_state_for_device']
    file_to_stream_url = g['file_to_stream_url']
    alexa_play = g['alexa_play']
    _serial_for_room_key = g['_serial_for_room_key']
    encode_token = g['encode_token']
    track_metadata_fast = g['track_metadata_fast']
    _refresh_smart_playlist = g['_refresh_smart_playlist']
    _save_smart_playlists = g['_save_smart_playlists']
    _paths_for_smart_rules = g['_paths_for_smart_rules']
    _persist_playlist = g['_persist_playlist']
    _enrich_track_paths = g['_enrich_track_paths']
    _playlist_paths_cached = g['_playlist_paths_cached']
    load_config = g['load_config']
    import uuid as _uuid

    PLAYLIST_FOLDERS_PATH = g['PLAYLIST_FOLDERS_PATH']
    PLAYBACK_RESUME_PATH = g['PLAYBACK_RESUME_PATH']
    RECOMMENDATIONS_CACHE_PATH = g['RECOMMENDATIONS_CACHE_PATH']
    PLAY_COUNTS_PATH = g['PLAY_COUNTS_PATH']

    def _paths():
        return (
            g['PLAYLIST_FOLDERS_PATH'],
            g['PLAYBACK_RESUME_PATH'],
            g['RECOMMENDATIONS_CACHE_PATH'],
            g['PLAY_COUNTS_PATH'],
        )

    def _loudness_mode():
        return bock_loudness.normalize_mode_from_pref(get_pref('ReplayGain', 'off'))

    @app.route('/api/library/analyze-loudness', methods=['POST'])
    def api_analyze_loudness():
        import datetime
        body = request.get_json(silent=True) or {}
        ffmpeg_bin = get_pref('FFmpegLocation', '').strip() or 'ffmpeg'
        paths = body.get('paths')
        days = body.get('days')
        if days and not paths:
            try:
                days_n = max(1, int(days))
            except (TypeError, ValueError):
                return jsonify({'error': 'invalid_days'}), 400
            from_dt = datetime.datetime.now() - datetime.timedelta(days=days_n)
            hist_rows = g['_filter_history_rows'](g['_read_stream_history'](), from_dt, None)
            paths = sorted({
                fp for r in hist_rows if not r.get('test')
                for fp in [r.get('filepath') or r.get('path')]
                if fp
            })
        ok = bock_loudness.run_analyze_job(
            g['get_db_rw'], db_query, db_one, MUSIC_ROOT, ffmpeg_bin,
            force=bool(body.get('force')), limit=body.get('limit'), paths=paths,
        )
        if not ok:
            return jsonify({'error': 'already_running'}), 409
        return jsonify({'ok': True, 'queued': len(paths) if paths else None, 'days': days})

    @app.route('/api/library/analyze-loudness/status')
    def api_analyze_loudness_status():
        return jsonify(bock_loudness.analyze_status())

    @app.route('/api/library/analyze-loudness/cancel', methods=['POST'])
    def api_analyze_loudness_cancel():
        return jsonify({'cancelled': bock_loudness.cancel_analyze_job()})

    @app.route('/api/songs/<path:song_path>/audio-meta')
    def api_song_audio_meta(song_path):
        full = '/' + song_path.lstrip('/')
        rel_row = db_one('SELECT path FROM songs_cache WHERE path = ? OR path = ?', [full, song_path])
        path = rel_row.get('path') if rel_row else full
        base = get_public_url().rstrip('/')
        return jsonify(bock_loudness.audio_meta_for_path(db_one, path, base, _loudness_mode()))

    @app.route('/api/playlist_folders')
    def api_list_folders():
        folders_path, _, _, _ = _paths()
        data = bock_folders.load_folders(folders_path)
        return jsonify(bock_folders.folder_tree(data))

    @app.route('/api/playlist_folders', methods=['POST'])
    def api_create_folder():
        folders_path, _, _, _ = _paths()
        body = request.get_json() or {}
        data = bock_folders.load_folders(folders_path)
        entry, err = bock_folders.create_folder(data, body.get('name'), body.get('parentId'))
        if err:
            return jsonify({'error': err}), 400
        bock_folders.save_folders(folders_path, data)
        return jsonify(entry), 201

    @app.route('/api/playlist_folders/<folder_id>', methods=['PUT'])
    def api_update_folder(folder_id):
        folders_path, _, _, _ = _paths()
        body = request.get_json() or {}
        data = bock_folders.load_folders(folders_path)
        entry, err = bock_folders.update_folder(data, folder_id, body)
        if err:
            return jsonify({'error': err}), 404 if err == 'not found' else 400
        bock_folders.save_folders(folders_path, data)
        return jsonify(entry)

    @app.route('/api/playlist_folders/<folder_id>', methods=['DELETE'])
    def api_delete_folder(folder_id):
        folders_path, _, _, _ = _paths()
        data = bock_folders.load_folders(folders_path)
        bock_folders.delete_folder(data, folder_id)
        bock_folders.save_folders(folders_path, data)
        return jsonify({'ok': True})

    @app.route('/api/playlists/<playlist_id>/folder', methods=['POST'])
    def api_playlist_folder(playlist_id):
        folders_path, _, _, _ = _paths()
        body = request.get_json() or {}
        data = bock_folders.load_folders(folders_path)
        bock_folders.assign_playlist(data, playlist_id, body.get('folderId'))
        bock_folders.save_folders(folders_path, data)
        return jsonify({'ok': True, 'folderId': body.get('folderId')})

    @app.route('/api/library/new')
    def api_library_new():
        import bock_library_new
        import bock_ratings
        since = (request.args.get('since') or '7d').strip()
        limit = min(max(int(request.args.get('limit', 50) or 50), 1), 200)
        after = (request.args.get('after') or '').strip() or None
        followed_only = request.args.get('followed', '').strip().lower() in ('1', 'true', 'yes')
        artists = None
        if followed_only:
            member = g['_ratings_member_from_request']()
            followed = bock_ratings.list_followed_artists(g['RATINGS_PATH'], member)
            artists = [a['name'] for a in followed]
        payload = bock_library_new.library_new_payload(
            db_query, since=since, limit=limit, artists=artists, after=after,
        )
        payload['followedOnly'] = followed_only
        return jsonify(payload)

    @app.route('/api/followed-artists')
    def api_followed_artists():
        import bock_ratings
        member = g['_ratings_member_from_request']()
        artists = bock_ratings.list_followed_artists(g['RATINGS_PATH'], member)
        return jsonify({'artists': artists, 'memberId': member or None})

    @app.route('/api/notifications/followed')
    def api_notifications_followed():
        import bock_library_new
        import bock_ratings
        member = g['_ratings_member_from_request']()
        since = (request.args.get('since') or '30d').strip()
        after = (request.args.get('after') or '').strip() or None
        limit = min(max(int(request.args.get('limit', 50) or 50), 1), 200)
        followed = bock_ratings.list_followed_artists(g['RATINGS_PATH'], member)
        artist_names = [a['name'] for a in followed]
        payload = bock_library_new.library_new_payload(
            db_query, since=since, limit=limit, artists=artist_names, after=after,
        )
        unread = len(payload.get('albums') or []) + len(payload.get('tracks') or [])
        return jsonify({
            'since': payload.get('since'),
            'albums': payload.get('albums') or [],
            'tracks': payload.get('tracks') or [],
            'unreadCount': unread,
            'followedCount': len(followed),
            'followedArtists': artist_names,
        })

    @app.route('/api/continue')
    def api_continue():
        _, resume_path, _, _ = _paths()
        member = (request.args.get('member') or '').strip()
        if not member and request.args.get('clientId'):
            member = g.get('member_for_client', lambda x: None)(request.args.get('clientId').strip()) or ''
        return jsonify(bock_continue.get_continue(resume_path, member, db_one))

    @app.route('/api/continue/<resume_id>', methods=['DELETE'])
    def api_continue_dismiss(resume_id):
        _, resume_path, _, _ = _paths()
        bock_continue.dismiss(resume_path, resume_id)
        return jsonify({'ok': True})

    @app.route('/api/recommendations/discover-weekly')
    def api_discover_weekly():
        _, _, cache_path, _ = _paths()
        member = (request.args.get('member') or '').strip() or 'household'
        return jsonify(bock_discover.get_discover_weekly(cache_path, member))

    @app.route('/api/recommendations/discover-weekly/refresh', methods=['POST'])
    def api_discover_refresh():
        _, _, cache_path, _ = _paths()
        h = _load_household()
        mids = [m.get('id') for m in (h.get('members') or []) if m.get('id')] or ['household']
        cache = bock_discover.run_weekly_job(
            cache_path, db_query, STREAM_HISTORY_PATH, mids,
        )
        return jsonify({'ok': True, 'generatedAt': cache.get('generatedAt')})

    def _pins_member():
        body = request.get_json(silent=True) or {}
        if not isinstance(body, dict):
            body = {}
        explicit = (
            request.args.get('memberId') or request.args.get('member') or
            body.get('memberId') or body.get('member') or ''
        ).strip() or None
        if explicit:
            return explicit
        client_id = (request.args.get('clientId') or body.get('clientId') or '').strip() or None
        return g['resolve_play_member'](client_id=client_id, explicit_member=None) or ''

    @app.route('/api/search/pins')
    def api_search_pins_get():
        member_id = _pins_member()
        pins = bock_search.load_pins_for_member(
            g['CLIENT_PREFS_PATH'], member_id, g.get('_atomic_json_write'),
        )
        return jsonify({'pins': pins, 'memberId': member_id or None})

    @app.route('/api/search/pins', methods=['PUT'])
    def api_search_pins_put():
        body = request.get_json(silent=True) or {}
        pins = body.get('pins')
        if not isinstance(pins, list):
            return jsonify({'error': 'pins array required'}), 400
        member_id = _pins_member()
        cleaned = bock_search.save_pins_for_member(
            g['CLIENT_PREFS_PATH'], member_id, pins, g.get('_atomic_json_write'),
        )
        return jsonify({'ok': True, 'pins': cleaned, 'memberId': member_id or None})

    _suggest_playlist_cache = {'at': 0.0, 'pl': [], 'smart': []}

    def _cached_suggest_playlists(ttl_sec=90):
        now = time.time()
        if now - _suggest_playlist_cache['at'] < ttl_sec:
            return _suggest_playlist_cache['pl'], _suggest_playlist_cache['smart']
        pl = [{'id': pid, 'name': name} for pid, name, _ in _load_playlist_entries()]
        smart = [{'id': s.get('id'), 'name': s.get('name')} for s in _load_smart_playlists()]
        _suggest_playlist_cache.update(at=now, pl=pl, smart=smart)
        return pl, smart

    _response_cache_get = g.get('_response_cache_get')
    _response_cache_put = g.get('_response_cache_put')

    @app.route('/api/search/suggest')
    def api_search_suggest():
        q = (request.args.get('q') or '').strip()
        if len(q) < 1:
            return jsonify({'query': q, 'songs': [], 'playlists': [], 'artists': [], 'albums': []})
        cache_key = f'suggest?{request.query_string.decode("utf-8", "ignore")}'
        if _response_cache_get:
            cached = _response_cache_get(cache_key)
            if cached is not None:
                resp = jsonify(cached)
                resp.headers['Cache-Control'] = 'private, max-age=30'
                return resp
        pl_names, smart = _cached_suggest_playlists()
        payload = bock_search_ext.suggest_payload(db_query, q, pl_names, [], smart)
        if _response_cache_put:
            _response_cache_put(cache_key, payload)
        resp = jsonify(payload)
        resp.headers['Cache-Control'] = 'private, max-age=30'
        return resp

    @app.route('/api/smart_playlists/refresh_all', methods=['POST'])
    def api_smart_refresh_all():
        items = _load_smart_playlists()
        out = []
        for i, item in enumerate(items):
            if item.get('enabled', True):
                item, _ = _refresh_smart_playlist(item)
                items[i] = item
                out.append({'id': item.get('id'), 'trackCount': item.get('trackCount')})
        _save_smart_playlists(items)
        return jsonify({'ok': True, 'refreshed': out})

    @app.route('/api/play_counts/rebuild', methods=['POST'])
    def api_rebuild_play_counts():
        _, _, _, counts_path = _paths()
        data = bock_play_counts.rebuild_from_history(STREAM_HISTORY_PATH, counts_path)
        return jsonify({'ok': True, 'paths': len((data or {}).get('paths', {}))})

    def _member_from_request():
        member = (request.args.get('member') or '').strip()
        if not member and (request.args.get('clientId') or '').strip():
            member = member_for_client(request.args.get('clientId').strip()) or ''
        return member

    def _enrich_song_rows(rows, member=''):
        if not rows:
            return rows
        import bock_ratings
        counts = bock_play_counts.load_counts(PLAY_COUNTS_PATH)
        path_counts = counts.get('paths') or {}
        member_counts = (counts.get('byMember') or {}).get(member or 'household', {})
        if member and member in (counts.get('byMember') or {}):
            member_counts = counts['byMember'][member]
        ratings_list = bock_ratings.list_ratings(g['RATINGS_PATH'], member) if member else []
        song_ratings = {}
        for r in ratings_list:
            if (r.get('kind') or 'song') != 'song':
                continue
            pid = r.get('id') or r.get('path')
            if pid:
                song_ratings[pid] = int(r.get('stars') or 0)
        out = []
        for row in rows:
            r = dict(row)
            path = r.get('path') or ''
            r['playCount'] = int(member_counts.get(path) or path_counts.get(path) or 0)
            stars = song_ratings.get(path, 0)
            r['rating'] = stars
            r['liked'] = stars >= 5
            raw_dur = r.get('duration_seconds')
            if raw_dur is not None:
                try:
                    dur = int(float(raw_dur))
                    r['duration_seconds'] = dur if dur > 0 else None
                except (TypeError, ValueError):
                    r['duration_seconds'] = None
            out.append(r)
        return out

    @app.route('/api/artists/<path:artist_name>/top-tracks')
    def api_artist_top_tracks(artist_name):
        artist = resolve_library_artist_name(db_query, db_one, artist_name)
        if not artist:
            return jsonify({'error': 'artist required'}), 400
        limit = min(max(int(request.args.get('limit') or 10), 1), 50)
        member = _member_from_request()
        items, _source = bock_artist_top_tracks.resolve_artist_top_tracks(
            artist,
            db_query,
            lambda rows: _enrich_song_rows(rows, member),
            member,
            limit,
            load_config,
        )
        return jsonify({'artist': artist, 'items': items, 'total': len(items)})

    @app.route('/api/artists/<path:artist_name>')
    def api_artist_detail(artist_name):
        artist = resolve_library_artist_name(db_query, db_one, artist_name)
        if not artist:
            return jsonify({'error': 'artist required'}), 400
        member = _member_from_request()
        ratings_member = g['_ratings_member_from_request']()
        stats = db_one(
            'SELECT COUNT(*) as track_count, COUNT(DISTINCT album) as album_count '
            'FROM songs_cache WHERE artist = ? AND path IS NOT NULL',
            [artist],
        ) or {}
        albums = db_query(
            'SELECT album, artist, MIN(path) as path, MIN(year) as year, COUNT(*) as track_count, '
            'MAX(first_seen_at) as first_seen_at '
            'FROM songs_cache WHERE artist = ? AND album != "" AND path IS NOT NULL '
            'GROUP BY album, artist ORDER BY year DESC, album COLLATE NOCASE ASC LIMIT 200',
            [artist],
        ) or []
        top_tracks, top_tracks_source = bock_artist_top_tracks.resolve_artist_top_tracks(
            artist,
            db_query,
            lambda rows: _enrich_song_rows(rows, member),
            member,
            10,
            load_config,
        )
        total_plays = sum(t.get('playCount', 0) for t in top_tracks)
        import bock_ratings
        artist_rating = bock_ratings.get_artist_rating(g['RATINGS_PATH'], artist, ratings_member)
        similar = []
        if top_tracks:
            seed_path = top_tracks[0].get('path')
            seed = bock_resonance.fetch_seed_row(db_one, db_query, 'song', path=seed_path)
            if seed:
                sim_rows = bock_resonance.similar_tracks(db_query, seed, limit=16)
                seen = {artist.lower()}
                for row in sim_rows:
                    a = (row.get('artist') or '').strip()
                    if a and a.lower() not in seen:
                        seen.add(a.lower())
                        similar.append({'artist': a, 'path': row.get('path')})
        appears_on = db_query(
            'SELECT s.album, MIN(s.path) as path, MIN(s.year) as year, COUNT(*) as track_count '
            'FROM songs_cache s '
            'WHERE s.artist = ? AND s.path IS NOT NULL AND s.album != "" '
            'AND EXISTS ('
            '  SELECT 1 FROM songs_cache o '
            '  WHERE o.album = s.album AND o.path IS NOT NULL '
            '  AND LOWER(TRIM(o.artist)) != LOWER(?)'
            ') '
            'GROUP BY s.album '
            'ORDER BY year DESC, album COLLATE NOCASE ASC LIMIT 12',
            [artist, artist],
        ) or []
        first_added = db_one(
            'SELECT MIN(first_seen_at) as first_seen FROM songs_cache '
            'WHERE artist = ? AND path IS NOT NULL',
            [artist],
        ) or {}
        decade_row = db_one(
            'SELECT CAST(year / 10 AS INT) * 10 as decade, COUNT(*) as cnt FROM songs_cache '
            'WHERE artist = ? AND year > 1900 GROUP BY decade ORDER BY cnt DESC LIMIT 1',
            [artist],
        ) or {}
        genre_counts = {}
        for row in top_tracks:
            genre_name = (row.get('genre') or '').strip()
            if genre_name:
                genre_counts[genre_name] = genre_counts.get(genre_name, 0) + 1
        top_genres = [name for name, _ in sorted(genre_counts.items(), key=lambda x: -x[1])[:8]]
        for album in albums:
            raw_year = album.get('year')
            if raw_year is not None:
                try:
                    album['year'] = int(float(raw_year))
                except (TypeError, ValueError):
                    album['year'] = None
            raw_tc = album.get('track_count')
            if raw_tc is not None:
                try:
                    album['track_count'] = int(float(raw_tc))
                except (TypeError, ValueError):
                    album['track_count'] = 0
        for album in appears_on:
            raw_year = album.get('year')
            if raw_year is not None:
                try:
                    album['year'] = int(float(raw_year))
                except (TypeError, ValueError):
                    album['year'] = None
            raw_tc = album.get('track_count')
            if raw_tc is not None:
                try:
                    album['track_count'] = int(float(raw_tc))
                except (TypeError, ValueError):
                    album['track_count'] = 0
        return jsonify({
            'artist': artist,
            'trackCount': stats.get('track_count') or 0,
            'albumCount': stats.get('album_count') or 0,
            'totalPlays': total_plays,
            'followed': artist_rating >= 3,
            'rating': artist_rating,
            'topTracks': top_tracks[:10],
            'topTracksSource': top_tracks_source,
            'albums': albums,
            'similarArtists': similar,
            'appearsOn': appears_on,
            'about': {
                'firstAdded': first_added.get('first_seen'),
                'topDecade': decade_row.get('decade'),
                'topGenres': top_genres,
            },
        })

    @app.route('/api/music-video/related')
    def api_music_video_related():
        artist = (request.args.get('artist') or '').strip()
        title = (request.args.get('title') or '').strip()
        limit = min(max(int(request.args.get('limit') or 12), 1), 24)
        cache_path = g.get('MUSIC_VIDEO_CACHE_PATH') or os.path.join(DATA_DIR, 'music_video_cache.json')
        artist_matches = g.get('_music_video_artist_matches')
        items = []
        seen_vids = set()
        try:
            if os.path.isfile(cache_path):
                with open(cache_path) as f:
                    cached = json.load(f)
                if isinstance(cached, dict):
                    for cache_key, entry in cached.items():
                        if not isinstance(entry, dict):
                            continue
                        key_parts = str(cache_key).split('|', 2)
                        if len(key_parts) >= 3:
                            key_artist = key_parts[1]
                            track_title = key_parts[2]
                        else:
                            key_artist = (entry.get('artist') or '').strip()
                            track_title = ''
                        if artist and not _cache_artist_matches(artist, key_artist):
                            continue
                        if title and track_title and track_title == title.strip().lower():
                            continue
                        vid = entry.get('videoId') or entry.get('id')
                        if not vid or vid in seen_vids:
                            continue
                        picked_title = (entry.get('title') or '').strip()
                        if artist and artist_matches and picked_title:
                            if not artist_matches(artist, picked_title):
                                continue
                        seen_vids.add(vid)
                        items.append({
                            'videoId': vid,
                            'title': picked_title or track_title.replace('_', ' ').title(),
                            'artist': artist,
                            'thumbnail': entry.get('thumbnail') or f'https://i.ytimg.com/vi/{vid}/hqdefault.jpg',
                        })
                        if len(items) >= limit:
                            break
        except Exception:
            pass
        return jsonify({'items': items[:limit]})

    @app.route('/api/music-video/check', methods=['POST'])
    def api_music_video_check():
        body = request.get_json(silent=True) or {}
        tracks = body.get('tracks') or []
        cache_path = g.get('MUSIC_VIDEO_CACHE_PATH') or os.path.join(DATA_DIR, 'music_video_cache.json')
        cached = {}
        try:
            if os.path.isfile(cache_path):
                with open(cache_path) as f:
                    cached = json.load(f) or {}
        except Exception:
            cached = {}
        mv_key_fn = g.get('_music_video_cache_key')
        available = {}
        for tr in tracks[:100]:
            if not isinstance(tr, dict):
                continue
            t_title = (tr.get('title') or '').strip()
            t_artist = (tr.get('artist') or '').strip()
            key_label = f'{t_title}|{t_artist}'
            cache_key = mv_key_fn(t_title, t_artist) if mv_key_fn else f'v4|{t_artist}|{t_title}'
            entry = cached.get(cache_key) if isinstance(cached, dict) else None
            available[key_label] = bool(entry and (entry.get('videoId') or entry.get('id')))
        return jsonify({'available': available})

    @app.route('/api/playback/handoff', methods=['POST'])
    def api_playback_handoff():
        body = request.get_json() or {}
        from_dev = (body.get('fromDeviceId') or '').strip()
        to_dev = (body.get('toDeviceId') or '').strip()
        offset_ms = int(body.get('offsetMs') or body.get('offset_ms') or 0)
        ctx = body.get('context') or {}

        def _alexa_serial(dev):
            return _serial_for_room_key(g.get('_room_key', lambda x: x)(dev))

        def _play(url, token, offset_ms=0, device_serial=None, **kw):
            alexa_play(url, token, offset_ms=offset_ms, device_id=device_serial)

        if not ctx.get('path') and ctx.get('filepath'):
            ctx['path'] = ctx['filepath']
        if not ctx.get('path') and body.get('filepath'):
            ctx['path'] = body.get('filepath')

        result = bock_handoff.handoff_payload(
            from_dev, to_dev, offset_ms, ctx,
            read_np=lambda d: read_np_state_for_device(d),
            file_to_stream_url=lambda p: file_to_stream_url(p, normalize=1)
            if _loudness_mode() != 'off' else file_to_stream_url(p),
            alexa_play_fn=_play,
            alexa_serial_for=_alexa_serial,
            encode_token_fn=encode_token,
        )
        if not result.get('ok'):
            return jsonify(result), 400
        return jsonify(result)

    @app.route('/api/mix-muse/status')
    def api_mix_muse_status():
        return jsonify(bock_mix_muse.status(load_config))

    @app.route('/api/mix-muse/playlist', methods=['POST'])
    def api_mix_muse_playlist():
        body = request.get_json(silent=True) or {}
        prompt = (body.get('prompt') or '').strip()
        if not prompt:
            return jsonify({'error': 'prompt required'}), 400
        max_tracks = min(max(int(body.get('maxTracks') or 25), 1), 80)
        save = bool(body.get('save'))
        try:
            candidates = bock_mix_muse.candidates_for_prompt(db_query, prompt)
            ai_name, paths, mode = bock_mix_muse.curate_playlist(
                prompt, candidates, max_tracks, load_config, body.get('provider'),
                db_query=db_query,
            )
        except ValueError as e:
            code = str(e)
            status = 503 if 'not_configured' in code else 400
            return jsonify({'error': code}), status
        except Exception as e:
            return jsonify({'error': 'mix_muse_request_failed', 'detail': str(e)}), 502
        name = (body.get('name') or '').strip() or ai_name
        tracks = _enrich_track_paths(paths)
        out = {'name': name, 'tracks': tracks, 'trackCount': len(tracks), 'source': f'mix-muse-{mode}', 'mode': mode}
        if save:
            pid = str(_uuid.uuid4())
            saved = _persist_playlist(pid, name, paths, create=True)
            out.update(saved or {})
            out['playlistId'] = pid
        return jsonify(out)

    @app.route('/api/listen-agent/status')
    def api_listen_agent_status():
        return jsonify(bock_listen_agent.status(load_config))

    @app.route('/api/listen-agent/play', methods=['POST'])
    def api_listen_agent_play():
        body = request.get_json(silent=True) or {}
        prompt = (body.get('prompt') or '').strip()
        if not prompt:
            return jsonify({'error': 'prompt_required'}), 400
        try:
            out = bock_listen_agent.play_from_prompt(
                prompt,
                db_query=db_query,
                load_config_fn=load_config,
                enrich_paths_fn=_enrich_track_paths,
                enrich_song_rows_fn=lambda rows: _enrich_song_rows(rows, _member_from_request()),
                fuzzy_artist=g.get('fuzzy_find_artist'),
                fuzzy_album=g.get('fuzzy_find_album'),
                album_tracks_fn=g.get('_album_tracks_for_play'),
                best_playlist_fn=g.get('best_playlist_entry'),
                parse_m3u_fn=g.get('parse_m3u'),
                fuzzy_track_fn=g.get('fuzzy_find_track'),
            )
        except ValueError as e:
            code = str(e)
            status = 404 if code.endswith('_not_found') or code == 'no_tracks_found' else 400
            return jsonify({'error': code}), status
        except Exception as e:
            return jsonify({'error': 'listen_agent_failed', 'detail': str(e)}), 502
        return jsonify(out)

    @app.route('/api/mix-muse/similar', methods=['POST'])
    def api_mix_muse_similar():
        body = request.get_json(silent=True) or {}
        seed_kind = (body.get('seedKind') or body.get('kind') or 'song').strip().lower()
        path = (body.get('path') or body.get('filepath') or '').strip()
        album = (body.get('album') or '').strip()
        artist = (body.get('artist') or '').strip()
        playlist_id = (body.get('playlistId') or body.get('playlist_id') or '').strip()
        user_prompt = (body.get('prompt') or '').strip()
        max_tracks = min(max(int(body.get('maxTracks') or 25), 1), 80)
        save = bool(body.get('save'))
        playlist_paths = None
        if seed_kind == 'playlist' and playlist_id:
            for pid, _name, src in _load_playlist_entries():
                if pid == playlist_id:
                    playlist_paths = _playlist_paths_cached(pid, src)
                    break
            if not playlist_paths:
                return jsonify({'error': 'playlist_not_found'}), 404
        try:
            if seed_kind in ('song', 'album', 'playlist'):
                pool, prompt, seed_row = bock_mix_muse.candidates_for_seed(
                    db_query, seed_kind, path=path or None, album=album or None,
                    artist=artist or None, playlist_paths=playlist_paths,
                )
            else:
                return jsonify({'error': 'invalid seedKind'}), 400
            if user_prompt:
                prompt = user_prompt
            ai_name, paths, mode = bock_mix_muse.curate_playlist(
                prompt, pool, max_tracks, load_config, body.get('provider'),
                seed_row=seed_row, db_query=db_query,
            )
        except ValueError as e:
            code = str(e)
            status = 503 if 'not_configured' in code else 400
            return jsonify({'error': code}), status
        except Exception as e:
            return jsonify({'error': 'mix_muse_request_failed', 'detail': str(e)}), 502
        default_name = f"Mix Muse · {(seed_row or {}).get('title') or album or 'Similar'}"
        name = (body.get('name') or '').strip() or ai_name or default_name
        tracks = _enrich_track_paths(paths)
        out = {
            'name': name, 'tracks': tracks, 'trackCount': len(tracks),
            'source': f'mix-muse-{mode}', 'mode': mode, 'prompt': prompt, 'seedKind': seed_kind,
        }
        if save:
            pid = str(_uuid.uuid4())
            saved = _persist_playlist(pid, name, paths, create=True)
            out.update(saved or {})
            out['playlistId'] = pid
        return jsonify(out)

    def _resonance_body(body):
        seed_kind = (body.get('seedKind') or body.get('kind') or 'song').strip().lower()
        path = (body.get('path') or body.get('filepath') or '').strip()
        album = (body.get('album') or '').strip()
        artist = (body.get('artist') or '').strip()
        playlist_id = (body.get('playlistId') or body.get('playlist_id') or '').strip()
        limit = min(max(int(body.get('maxTracks') or body.get('limit') or 30), 5), 80)
        playlist_paths = None
        if seed_kind == 'playlist' and playlist_id:
            for pid, _name, src in _load_playlist_entries():
                if pid == playlist_id:
                    playlist_paths = _playlist_paths_cached(pid, src)
                    break
            if not playlist_paths:
                raise ValueError('playlist_not_found')
        seed, rows = bock_resonance.build_mix(
            db_query, db_one, seed_kind, path=path or None, album=album or None,
            artist=artist or None, playlist_paths=playlist_paths, limit=limit,
        )
        paths = [r['path'] for r in rows if r.get('path')]
        title = bock_resonance.mix_title(seed, seed_kind)
        return seed_kind, seed, paths, rows, title

    @app.route('/api/resonance/mix', methods=['POST'])
    def api_resonance_mix():
        body = request.get_json(silent=True) or {}
        save = bool(body.get('save', True))
        try:
            seed_kind, seed, paths, rows, title = _resonance_body(body)
        except ValueError as e:
            return jsonify({'error': str(e)}), 404 if str(e) == 'playlist_not_found' else 400
        name = (body.get('name') or '').strip() or title
        tracks = _enrich_track_paths(paths)
        out = {
            'name': name, 'tracks': tracks, 'trackCount': len(tracks),
            'source': 'resonance', 'seedKind': seed_kind,
            'seed': {'path': seed.get('path'), 'title': seed.get('title'), 'artist': seed.get('artist')},
        }
        if save:
            pid = str(_uuid.uuid4())
            saved = _persist_playlist(pid, name, paths, create=True)
            out.update(saved or {})
            out['playlistId'] = pid
        return jsonify(out)

    @app.route('/api/resonance/radio', methods=['POST'])
    def api_resonance_radio():
        body = request.get_json(silent=True) or {}
        try:
            seed_kind, seed, paths, rows, title = _resonance_body(body)
        except ValueError as e:
            return jsonify({'error': str(e)}), 404 if str(e) == 'playlist_not_found' else 400
        tracks = _enrich_track_paths(paths)
        return jsonify({
            'name': title,
            'tracks': tracks,
            'trackCount': len(tracks),
            'source': 'resonance',
            'shuffle': True,
            'seedKind': seed_kind,
            'seed': {'path': seed.get('path'), 'title': seed.get('title'), 'artist': seed.get('artist')},
        })

    @app.route('/api/resonance/similar')
    def api_resonance_similar():
        path = (request.args.get('path') or '').strip()
        if not path:
            return jsonify({'error': 'path required'}), 400
        limit = min(max(int(request.args.get('limit') or 20), 1), 50)
        seed = bock_resonance.fetch_seed_row(db_one, db_query, 'song', path=path)
        if not seed:
            return jsonify({'error': 'not_found'}), 404
        rows = bock_resonance.similar_tracks(db_query, seed, limit=limit)
        return jsonify({
            'seed': {'path': seed.get('path'), 'title': seed.get('title'), 'artist': seed.get('artist')},
            'tracks': _enrich_track_paths([r['path'] for r in rows if r.get('path')]),
        })

    def _acquire_limit(default=20, cap=40):
        return min(max(int(request.args.get('limit') or default), 1), cap)

    @app.route('/api/acquire/status')
    def api_acquire_status():
        return jsonify(bock_acquire.status(load_config))

    @app.route('/api/acquire/suggest')
    def api_acquire_suggest_get():
        artist = (request.args.get('artist') or '').strip()
        if not artist:
            return jsonify({'error': 'artist required'}), 400
        limit = _acquire_limit()
        out = bock_acquire.suggest_for_seed(
            db_query, db_one, load_config, DATA_DIR, _atomic_json_write,
            seed_kind='artist', artist=artist, limit=limit,
        )
        if out.get('error') == 'acquire_disabled':
            return jsonify(out), 503
        if out.get('error') == 'seed_not_found':
            return jsonify(out), 404
        return jsonify(out)

    @app.route('/api/acquire/suggest', methods=['POST'])
    def api_acquire_suggest_post():
        body = request.get_json(silent=True) or {}
        seed_kind = (body.get('seedKind') or body.get('kind') or 'artist').strip().lower()
        path = (body.get('path') or body.get('filepath') or '').strip()
        album = (body.get('album') or '').strip()
        artist = (body.get('artist') or '').strip()
        playlist_id = (body.get('playlistId') or body.get('playlist_id') or '').strip()
        limit = min(max(int(body.get('limit') or 20), 1), 40)
        playlist_paths = None
        if seed_kind == 'playlist' and playlist_id:
            for pid, _name, src in _load_playlist_entries():
                if pid == playlist_id:
                    playlist_paths = _playlist_paths_cached(pid, src)
                    break
            if not playlist_paths:
                return jsonify({'error': 'playlist_not_found'}), 404
        out = bock_acquire.suggest_for_seed(
            db_query, db_one, load_config, DATA_DIR, _atomic_json_write,
            seed_kind=seed_kind, path=path or None, album=album or None,
            artist=artist or None, playlist_paths=playlist_paths, limit=limit,
        )
        if out.get('error') == 'acquire_disabled':
            return jsonify(out), 503
        if out.get('error') == 'seed_not_found':
            return jsonify(out), 404
        return jsonify(out)

    @app.route('/api/acquire/explore')
    def api_acquire_explore():
        limit = _acquire_limit(default=24, cap=40)
        out = bock_acquire.explore_library(
            db_query, db_one, load_config, DATA_DIR, _atomic_json_write, limit=limit,
        )
        if out.get('error') == 'acquire_disabled':
            return jsonify(out), 503
        if out.get('error') == 'library_empty':
            return jsonify(out), 404
        return jsonify(out)

    @app.route('/api/config/features')
    def api_feature_flags():
        return jsonify({
            'handoff': True,
            'discoverWeekly': True,
            'loudnessNormalization': _loudness_mode() != 'off',
            'playlistFolders': True,
            'continueListening': True,
            'unifiedSearch': True,
            'drivingMode': True,
            'mixMuse': bock_mix_muse.status(load_config).get('configured', False),
            'listenAgent': bock_listen_agent.status(load_config).get('configured', False),
            'resonance': True,
            'acquireIdeas': bock_acquire.status(load_config).get('enabled', True),
            'unifiedHome': True,
        })

    _read_stream_history = g['_read_stream_history']
    _genres_items = g['_genres_items']
    _playlist_summaries_for_home = g['_playlist_summaries_for_home']
    _recently_created_playlists_for_home = g['_recently_created_playlists_for_home']
    _rated_songs_as_favorites = g['_rated_songs_as_favorites']
    member_for_client = g['member_for_client']
    RATINGS_PATH = g['RATINGS_PATH']

    def _home_member():
        member = (request.args.get('member') or request.args.get('memberId') or '').strip()
        if not member and (request.args.get('clientId') or '').strip():
            member = member_for_client(request.args.get('clientId').strip()) or ''
        return member

    def _history_mtime():
        try:
            path = g['STREAM_HISTORY_PATH']
            return os.path.getmtime(path) if os.path.isfile(path) else 0.0
        except OSError:
            return 0.0

    def _household_mtime():
        try:
            path = g.get('HOUSEHOLD_PATH') or ''
            return os.path.getmtime(path) if path and os.path.isfile(path) else 0.0
        except OSError:
            return 0.0

    def _library_new_payload(since='7d', limit=50, artists=None):
        import bock_library_new
        return bock_library_new.library_new_payload(
            db_query, since=since, limit=limit, artists=artists,
        )

    def _followed_library_new_payload(since='14d', limit=24):
        import bock_ratings
        member = _home_member()
        followed = bock_ratings.list_followed_artists(RATINGS_PATH, member)
        if not followed:
            return {'since': since, 'tracks': [], 'albums': [], 'playlists': [], 'followedOnly': True}
        return _library_new_payload(
            since=since,
            limit=limit,
            artists=[a['name'] for a in followed],
        )

    @app.route('/api/home')
    @bock_perf.timed_route('home')
    def api_home():
        import bock_uitest
        injected = bock_uitest.uitest_fail_response('home')
        if injected is not None:
            return injected
        member = _home_member()
        deferred = request.args.get('deferred', '1') != '0'
        playlist_limit = min(max(int(request.args.get('playlistLimit') or 500), 1), 2000)
        genre_limit = min(max(int(request.args.get('genreLimit') or 40), 1), 200)
        history_limit = min(max(int(request.args.get('historyLimit') or 150), 1), 500)
        _, resume_path, cache_path, _ = _paths()

        def load_favorites():
            return g['_load_favorites'](member)

        ratings_items = None
        analytics_payload = None
        include_ratings = request.args.get('includeRatings', '0') == '1'
        if not deferred or include_ratings:
            import bock_ratings
            ratings_items = bock_ratings.list_ratings(g['RATINGS_PATH'], member)

        import bock_home_defaults
        home_defaults = bock_home_defaults.load(DATA_DIR, load_config())

        def _home_genres(limit=40):
            items = _genres_items(limit=limit or genre_limit)
            return {'items': items, 'total': len(items)}

        payload = bock_home.build_home_payload(
            member=member,
            history_mtime=_history_mtime(),
            household_mtime=_household_mtime(),
            deferred=deferred,
            read_stream_history=_read_stream_history,
            filter_history_rows=g.get('_filter_history_rows'),
            filter_history_for_member=lambda rows, mid: g['_filter_history_by_member'](
                rows, mid, g['_load_household'](),
            ),
            load_favorites=load_favorites,
            load_smart_playlists=_load_smart_playlists,
            load_playlist_summaries=lambda member='', limit=500: _playlist_summaries_for_home(
                member_filter=member, limit=limit,
            ),
            load_recently_created_playlists=lambda member='', limit=10: _recently_created_playlists_for_home(
                member_filter=member, limit=limit,
            ),
            load_genres=_home_genres,
            library_new=lambda: _library_new_payload(),
            followed_library_new=lambda: _followed_library_new_payload(),
            discover_weekly=lambda member='': bock_discover.get_discover_weekly(cache_path, member or 'household'),
            continue_listening=lambda member='': bock_continue.get_continue(resume_path, member, db_one),
            analytics_payload=analytics_payload,
            ratings_items=ratings_items,
            db_query=db_query,
            playlist_limit=playlist_limit,
            genre_limit=genre_limit,
            history_limit=history_limit,
            home_defaults=home_defaults,
        )
        return jsonify(payload)

    @app.route('/api/perf/home-burst')
    def api_perf_home_burst():
        """CI/diagnostic: sequential timing of home refresh endpoints."""
        client = app.test_client()
        paths = (
            '/api/home?deferred=1',
            '/api/playlists?page=1&limit=500&fields=summary',
            '/api/genres?limit=40',
            '/api/dashboard/quick',
            '/api/nowplaying?page=1&limit=150',
            '/api/smart_playlists',
            '/api/library/new?since=7d&limit=50',
            '/api/analytics',
        )
        rows = []
        for path in paths:
            t0 = time.perf_counter()
            resp = client.get(path)
            ms = (time.perf_counter() - t0) * 1000.0
            rows.append({
                'path': path,
                'status': resp.status_code,
                'ms': round(ms, 2),
                'budgetMs': bock_perf.perf_budget_ms(path.split('?')[0].split('/')[-1].replace('-', '_')),
            })
        return jsonify({'endpoints': rows})
