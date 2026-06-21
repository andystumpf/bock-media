"""Register Spotify-parity API routes on the Flask app."""
from flask import jsonify, request, Response

import bock_continue
import bock_discover
import bock_folders
import bock_handoff
import bock_loudness
import bock_play_counts
import bock_search_ext


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
        body = request.get_json(silent=True) or {}
        ffmpeg_bin = get_pref('FFmpegLocation', '').strip() or 'ffmpeg'
        ok = bock_loudness.run_analyze_job(
            g['get_db_rw'], db_query, db_one, MUSIC_ROOT, ffmpeg_bin,
            force=bool(body.get('force')), limit=body.get('limit'),
        )
        if not ok:
            return jsonify({'error': 'already_running'}), 409
        return jsonify({'ok': True})

    @app.route('/api/library/analyze-loudness/status')
    def api_analyze_loudness_status():
        return jsonify(bock_loudness.analyze_status())

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
        since = (request.args.get('since') or '7d').strip().lower()
        days = 7
        if since.endswith('d') and since[:-1].isdigit():
            days = int(since[:-1])
        limit = min(max(int(request.args.get('limit', 50) or 50), 1), 200)
        import datetime
        cutoff = (datetime.datetime.now() - datetime.timedelta(days=days)).strftime('%Y-%m-%d')
        tracks = db_query(
            'SELECT title, artist, album, path, first_seen_at FROM songs_cache '
            'WHERE first_seen_at >= ? AND path IS NOT NULL ORDER BY first_seen_at DESC LIMIT ?',
            [cutoff, limit],
        ) or []
        albums = db_query(
            'SELECT album, artist, MIN(path) as path, MIN(first_seen_at) as first_seen_at '
            'FROM songs_cache WHERE first_seen_at >= ? AND album != "" '
            'GROUP BY album, artist ORDER BY first_seen_at DESC LIMIT ?',
            [cutoff, limit],
        ) or []
        return jsonify({'since': since, 'tracks': tracks, 'albums': albums, 'playlists': []})

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

    @app.route('/api/search/suggest')
    def api_search_suggest():
        q = (request.args.get('q') or '').strip()
        if len(q) < 1:
            return jsonify({'query': q, 'songs': [], 'playlists': [], 'artists': [], 'albums': []})
        bock_search_ext.ensure_fts(g['get_db_rw'], db_query)
        pl_names = [{'id': pid, 'name': name} for pid, name, _ in _load_playlist_entries()]
        smart = [{'id': s.get('id'), 'name': s.get('name')} for s in _load_smart_playlists()]
        devices = []
        try:
            import alexa_remote
            devices = [d.get('name', '') for d in (alexa_remote.list_devices() or [])]
        except Exception:
            pass
        payload = bock_search_ext.suggest_payload(db_query, q, pl_names, devices, smart)
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
            file_to_stream_url=lambda p: file_to_stream_url(p) + '?normalize=1'
            if _loudness_mode() != 'off' else file_to_stream_url(p),
            alexa_play_fn=_play,
            alexa_serial_for=_alexa_serial,
            encode_token_fn=encode_token,
        )
        if not result.get('ok'):
            return jsonify(result), 400
        return jsonify(result)

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
        })
