/**
 * Browser playback — stream from /stream/ on this server (Spotify-style laptop listening).
 * Supports crossfade between tracks when ClientPrefsSync.crossfadeSeconds > 0.
 */
(function (root) {
  'use strict';

  function path2name(p) {
    const base = String(p || '').split('/').pop() || 'Track';
    const dot = base.lastIndexOf('.');
    return dot > 0 ? base.slice(0, dot) : base;
  }

  function encodeMediaPath(filepath) {
    return String(filepath || '').replace(/^\/+/, '').split('/')
      .map((seg) => encodeURIComponent(seg).replace(/%20/g, '%20')).join('/');
  }

  async function streamUrl(track, normalize) {
    const path = typeof track === 'string' ? track : track?.path;
    if (!path) return null;
    const params = new URLSearchParams();
    if (typeof track === 'object' && track) {
      if (track.title) params.set('title', track.title);
      if (track.artist) params.set('artist', track.artist);
    }
    if (normalize === false) params.set('normalize', '0');
    const qs = params.toString();
    const rel = `/stream/${encodeMediaPath(path)}${qs ? `?${qs}` : ''}`;
    if (typeof root.signMediaPath === 'function') {
      return root.signMediaPath(rel);
    }
    return rel;
  }

  function dbToLinear(db) {
    if (db == null || db >= 0) return 1;
    return Math.pow(10, db / 20);
  }

  function getCrossfadeSeconds() {
    if (typeof root.ClientPrefsSync !== 'undefined') {
      return root.ClientPrefsSync.getCrossfadeSeconds?.() ?? 0;
    }
    return 0;
  }

  function crossfadeProgress(elapsedSec, durationSec) {
    if (!durationSec || durationSec <= 0) return 1;
    return Math.min(1, Math.max(0, elapsedSec / durationSec));
  }

  function crossfadeVolumes(progress, masterVolume) {
    const p = Math.min(1, Math.max(0, progress));
    const vol = Math.max(0, Math.min(1, masterVolume));
    return { outgoing: vol * (1 - p), incoming: vol * p };
  }

  function shouldStartCrossfade(remainingSec, crossfadeSec, crossfading, hasNext) {
    if (crossfading || !hasNext) return false;
    const xf = Math.max(0, crossfadeSec || 0);
    if (xf <= 0) return false;
    if (!Number.isFinite(remainingSec) || remainingSec <= 0.05) return false;
    return remainingSec <= xf;
  }

  async function replayGainDbForTrack(track) {
    if (track.replayGainDb != null) return track.replayGainDb;
    if (!track?.path) return null;
    try {
      const meta = await apiGet(`/api/songs/${encodeMediaPath(track.path)}/audio-meta`);
      return meta.replaygainTrackDb ?? null;
    } catch (_) {
      return null;
    }
  }

  async function apiGet(url) {
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const res = await fetchFn(url, { credentials: 'same-origin' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }

  async function apiPost(url, body) {
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const res = await fetchFn(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      credentials: 'same-origin',
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }

  async function tryContinueAfterQueue() {
    if (typeof root.ClientPrefsSync === 'undefined') return false;
    const mode = root.ClientPrefsSync.getContinueAfterQueue?.() || 'off';
    if (!mode || mode === 'off') return false;
    const idx = currentIndex();
    const track = state.tracks[idx];
    if (!track?.path) return false;
    const body = { maxTracks: 30 };
    if (mode === 'artist_radio') {
      body.seedKind = 'artist';
      body.artist = track.artist || '';
      if (!body.artist) return false;
    } else if (state.playlistId) {
      body.seedKind = 'playlist';
      body.playlistId = state.playlistId;
    } else {
      body.seedKind = 'song';
      body.path = track.path;
    }
    try {
      const data = await apiPost('/api/resonance/radio', body);
      const rows = data.tracks || [];
      if (!rows.length) return false;
      const existing = new Set(state.tracks.map((t) => t.path));
      const extra = rows.map(trackFromRow).filter((t) => t && !existing.has(t.path));
      if (!extra.length) return false;
      state.tracks = state.tracks.concat(extra);
      if (state.shuffle) {
        state.order = shuffleOrder(state.tracks.length, idx);
        state.orderPos = 0;
      } else {
        state.orderPos = idx + 1;
      }
      await loadIndex(currentIndex());
      return true;
    } catch (_) {
      return false;
    }
  }

  function trackFromRow(row) {
    const path = row.path || row.filepath;
    if (!path) return null;
    return {
      path,
      title: row.title || row.track || path2name(path),
      artist: row.artist || '',
      album: row.album || '',
      year: row.year || null,
      durationMs: Math.max(0, (row.duration_seconds ?? row.duration ?? 0) * 1000),
    };
  }

  async function resolvePlaylist(id) {
    const tracks = [];
    let page = 1;
    let total = Infinity;
    while (tracks.length < total) {
      const d = await apiGet(`/api/playlists/${encodeURIComponent(id)}?page=${page}&limit=200&sortBy=original`);
      total = d.total || tracks.length;
      const batch = (d.tracks || []).map(trackFromRow).filter(Boolean);
      if (!batch.length) break;
      tracks.push(...batch);
      if (batch.length < 200 || tracks.length >= total) break;
      page += 1;
    }
    return tracks;
  }

  async function resolvePagedSongs(params) {
    const tracks = [];
    let page = 1;
    let total = Infinity;
    const qs = new URLSearchParams(params);
    while (tracks.length < total) {
      qs.set('page', String(page));
      qs.set('limit', '200');
      const d = await apiGet(`/api/songs?${qs}`);
      total = d.total || tracks.length;
      const batch = (d.items || []).map(trackFromRow).filter(Boolean);
      if (!batch.length) break;
      tracks.push(...batch);
      if (batch.length < 200 || tracks.length >= total) break;
      page += 1;
    }
    return tracks;
  }

  async function resolveQueue(opts) {
    const kind = opts.kind || 'song';
    if (kind === 'playlist') {
      const id = opts.id;
      if (!id) throw new Error('Playlist id required');
      const tracks = await resolvePlaylist(id);
      if (!tracks.length) throw new Error('No playable tracks in playlist');
      return {
        tracks,
        sourceLabel: opts.name ? `Playlist · ${opts.name}` : 'Playlist',
        playlistId: id,
        playlist: opts.name || '',
      };
    }
    if (kind === 'artist') {
      const tracks = await resolvePagedSongs({ artist: opts.name });
      if (!tracks.length) throw new Error('No tracks for artist');
      return { tracks, sourceLabel: `Artist · ${opts.name}` };
    }
    if (kind === 'album') {
      const tracks = await resolvePagedSongs({
        album: opts.name,
        artist: opts.artist || '',
      });
      if (!tracks.length) throw new Error('No tracks for album');
      return { tracks, sourceLabel: `Album · ${opts.name}` };
    }
    if (kind === 'genre') {
      const genre = (opts.name || '').trim();
      if (!genre) throw new Error('Genre required');
      let tracks = await resolvePagedSongs({ genre });
      if (!tracks.length) throw new Error(`No tracks for ${genre}`);
      const seed = tracks[Math.floor(Math.random() * tracks.length)];
      if (seed?.path) {
        try {
          const data = await apiPost('/api/resonance/radio', { seedKind: 'song', path: seed.path, maxTracks: 80 });
          const mixed = (data.tracks || []).map(trackFromRow).filter(Boolean);
          if (mixed.length) tracks = mixed;
        } catch (_) { /* fall back to genre pool */ }
      }
      return { tracks, sourceLabel: `${genre} Radio`, shuffle: true };
    }
    const one = trackFromRow({ path: opts.path, title: opts.name, artist: opts.artist });
    if (!one) throw new Error('Track path required');
    return { tracks: [one], sourceLabel: one.title };
  }

  function shuffleOrder(n, start) {
    const order = [];
    for (let i = 0; i < n; i++) order.push(i);
    for (let i = order.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [order[i], order[j]] = [order[j], order[i]];
    }
    if (start >= 0 && start < n) {
      const pos = order.indexOf(start);
      if (pos > 0) {
        order.splice(pos, 1);
        order.unshift(start);
      }
    }
    return order;
  }

  const state = {
    active: false,
    tracks: [],
    order: [],
    orderPos: 0,
    shuffle: false,
    repeat: 'off',
    sourceLabel: '',
    playlist: '',
    playlistId: null,
    playing: false,
    volume: 0.85,
    errorCount: 0,
    lastError: null,
  };

  let audio = new Audio();
  audio.preload = 'auto';
  let incomingAudio = null;
  let crossfading = false;
  let crossfadeTimer = null;
  let crossfadeStartedAt = 0;
  let crossfadeDuration = 0;
  let suppressEnded = false;

  const listeners = new Set();
  let loadToken = 0;
  let loadChain = Promise.resolve();
  let playToken = 0;

  function ensureIncomingAudio() {
    if (!incomingAudio) {
      incomingAudio = new Audio();
      incomingAudio.preload = 'auto';
      bindIncomingEvents(incomingAudio);
    }
    return incomingAudio;
  }

  function isPlayInterrupted(err) {
    return err && (err.name === 'AbortError' || /interrupted by a new load/i.test(String(err.message || '')));
  }

  function waitForCanPlay(el, token) {
    if (el.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) return Promise.resolve();
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (fn, arg) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        el.removeEventListener('canplay', onReady);
        el.removeEventListener('error', onErr);
        fn(arg);
      };
      const onReady = () => finish(resolve);
      const onErr = () => finish(reject, new Error('Stream failed to load'));
      el.addEventListener('canplay', onReady, { once: true });
      el.addEventListener('error', onErr, { once: true });
      const timer = setTimeout(() => {
        if (token !== loadToken) finish(resolve);
        else if (el.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) finish(resolve);
        else finish(reject, new Error('Stream load timeout'));
      }, 45000);
    });
  }

  function cancelCrossfade() {
    if (crossfadeTimer) {
      clearInterval(crossfadeTimer);
      crossfadeTimer = null;
    }
    crossfading = false;
    crossfadeStartedAt = 0;
    crossfadeDuration = 0;
    suppressEnded = false;
    if (incomingAudio) {
      incomingAudio.pause();
      incomingAudio.removeAttribute('src');
      incomingAudio.load();
    }
    audio.volume = state.volume;
  }

  function hasNextTrack() {
    if (!state.tracks.length) return false;
    if (state.shuffle && state.order.length) {
      return state.orderPos < state.order.length - 1;
    }
    return state.orderPos < state.tracks.length - 1;
  }

  function advanceQueueIndex() {
    if (state.shuffle && state.order.length) {
      if (state.orderPos < state.order.length - 1) {
        state.orderPos += 1;
        return true;
      }
      return false;
    }
    if (state.orderPos < state.tracks.length - 1) {
      state.orderPos += 1;
      return true;
    }
    return false;
  }

  async function prepareTrackOnElement(el, idx, token, { autoplay = true, volume = null } = {}) {
    const track = state.tracks[idx];
    if (!track || token !== loadToken) return false;
    const gainDb = await replayGainDbForTrack(track);
    const clientGain = gainDb != null && gainDb <= 0;
    let url = await streamUrl(track, !clientGain);
    if (!url) return false;

    el.pause();
    el.src = url;
    el.dataset.loadToken = String(token);
    el.load();
    const baseVol = volume == null ? state.volume : volume;
    el.volume = baseVol * dbToLinear(clientGain ? gainDb : 0);

    try {
      await waitForCanPlay(el, token);
      if (token !== loadToken) return false;
      if (autoplay) {
        await el.play();
        if (token !== loadToken) {
          el.pause();
          return false;
        }
      }
      return { track, clientGain, gainDb };
    } catch (e) {
      if (token !== loadToken || isPlayInterrupted(e)) return false;
      if (!clientGain && url.indexOf('normalize=0') < 0) {
        url = await streamUrl(track, false);
        el.src = url;
        el.load();
        el.volume = baseVol;
        try {
          await waitForCanPlay(el, token);
          if (token !== loadToken) return false;
          if (autoplay) {
            await el.play();
            if (token !== loadToken) {
              el.pause();
              return false;
            }
          }
          return { track, clientGain: false, gainDb: null };
        } catch (retryErr) {
          if (token !== loadToken || isPlayInterrupted(retryErr)) return false;
          throw retryErr;
        }
      }
      throw e;
    }
  }

  async function loadIndexInner(idx, token) {
    cancelCrossfade();
    const result = await prepareTrackOnElement(audio, idx, token, { autoplay: true, volume: state.volume });
    if (!result) return false;
    state.playing = true;
    state.errorCount = 0;
    state.lastError = null;
    syncMediaSession(result.track);
    notify();
    return true;
  }

  function loadIndex(idx) {
    const token = ++loadToken;
    loadChain = loadChain
      .then(() => loadIndexInner(idx, token))
      .catch((e) => {
        if (token !== loadToken || isPlayInterrupted(e)) return false;
        state.playing = false;
        state.lastError = e.message || 'Playback failed';
        notify();
        throw e;
      });
    return loadChain;
  }

  async function startCrossfade(overlapSec) {
    if (crossfading || !hasNextTrack()) return;
    const token = loadToken;
    crossfading = true;
    crossfadeDuration = Math.max(0.05, overlapSec);
    crossfadeStartedAt = performance.now();
    suppressEnded = true;

    if (!advanceQueueIndex()) {
      crossfading = false;
      suppressEnded = false;
      return;
    }

    const nextIdx = currentIndex();
    const nextTrack = state.tracks[nextIdx];
    const incoming = ensureIncomingAudio();

    try {
      const result = await prepareTrackOnElement(incoming, nextIdx, token, {
        autoplay: true,
        volume: 0,
      });
      if (!result || token !== loadToken) {
        cancelCrossfade();
        return;
      }
      syncMediaSession(nextTrack);
      notify();

      crossfadeTimer = setInterval(() => {
        if (!crossfading || token !== loadToken) {
          cancelCrossfade();
          return;
        }
        const elapsed = (performance.now() - crossfadeStartedAt) / 1000;
        const progress = crossfadeProgress(elapsed, crossfadeDuration);
        const vols = crossfadeVolumes(progress, state.volume);
        audio.volume = vols.outgoing;
        incoming.volume = vols.incoming;
        notify();
        if (progress >= 1) completeCrossfade(token);
      }, 50);
    } catch (_) {
      cancelCrossfade();
      state.orderPos = Math.max(0, state.orderPos - 1);
      if (state.shuffle && state.order.length) {
        const cur = currentIndex();
        state.orderPos = Math.max(0, state.order.indexOf(cur));
      }
    }
  }

  function completeCrossfade(token) {
    if (crossfadeTimer) {
      clearInterval(crossfadeTimer);
      crossfadeTimer = null;
    }
    if (!crossfading || token !== loadToken) return;

    audio.pause();
    audio.removeAttribute('src');
    audio.load();

    const outgoing = audio;
    audio = incomingAudio;
    incomingAudio = outgoing;
    audio.volume = state.volume;

    crossfading = false;
    crossfadeStartedAt = 0;
    crossfadeDuration = 0;
    suppressEnded = false;
    state.playing = !audio.paused;
    state.errorCount = 0;
    state.lastError = null;
    notify();
  }

  function checkCrossfadeTrigger() {
    if (crossfading) return;
    const xf = getCrossfadeSeconds();
    if (!shouldStartCrossfade(getRemainingSec(), xf, crossfading, hasNextTrack())) return;
    startCrossfade(Math.min(xf, getRemainingSec())).catch(() => cancelCrossfade());
  }

  function getRemainingSec() {
    const dur = audio.duration;
    if (!dur || !isFinite(dur)) return Infinity;
    return Math.max(0, dur - (audio.currentTime || 0));
  }

  function notify() {
    listeners.forEach((fn) => { try { fn(getPublicState()); } catch (_) { /* */ } });
  }

  function getPublicState() {
    const idx = currentIndex();
    const t = state.tracks[idx];
    const el = crossfading && incomingAudio && incomingAudio.src ? incomingAudio : audio;
    return {
      active: state.active,
      tracks: state.tracks.slice(),
      index: idx,
      current: t || null,
      playing: state.playing,
      shuffle: state.shuffle,
      repeat: state.repeat,
      sourceLabel: state.sourceLabel,
      playlist: state.playlist,
      playlistId: state.playlistId,
      positionMs: Math.round((el.currentTime || 0) * 1000),
      durationMs: el.duration && isFinite(el.duration)
        ? Math.round(el.duration * 1000)
        : (t && t.durationMs) || 0,
      volume: state.volume,
      lastError: state.lastError,
      crossfading,
    };
  }

  function currentIndex() {
    if (!state.tracks.length) return 0;
    if (state.shuffle && state.order.length) return state.order[state.orderPos] ?? 0;
    return state.orderPos;
  }

  function upcoming(limit = 20) {
    const out = [];
    if (!state.tracks.length) return out;
    if (state.shuffle && state.order.length) {
      for (let i = state.orderPos + 1; i < state.order.length && out.length < limit; i++) {
        out.push(state.tracks[state.order[i]]);
      }
    } else {
      for (let i = state.orderPos + 1; i < state.tracks.length && out.length < limit; i++) {
        out.push(state.tracks[i]);
      }
    }
    return out;
  }

  function syncMediaSession(track) {
    if (!('mediaSession' in navigator) || !track) return;
    try {
      const art = track.path ? `/artwork/${encodeMediaPath(track.path)}?size=384` : '';
      navigator.mediaSession.metadata = new MediaMetadata({
        title: track.title || 'Track',
        artist: track.artist || '',
        album: track.album || '',
        artwork: art ? [{ src: art, sizes: '384x384', type: 'image/jpeg' }] : [],
      });
      navigator.mediaSession.playbackState = state.playing ? 'playing' : 'paused';
    } catch (_) { /* */ }
  }

  function bindMediaSessionHandlers() {
    if (!('mediaSession' in navigator)) return;
    const act = (fn) => {
      try { navigator.mediaSession.setActionHandler(fn, handlers[fn]); } catch (_) { /* */ }
    };
    const handlers = {
      play: () => playCurrent(),
      pause: () => pause(),
      previoustrack: () => prev(),
      nexttrack: () => next(),
    };
    act('play');
    act('pause');
    act('previoustrack');
    act('nexttrack');
  }

  async function playCurrent() {
    if (!state.tracks.length) return;
    try {
      if (audio.src && audio.paused && audio.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
        await audio.play();
        if (crossfading && incomingAudio?.src) await incomingAudio.play().catch(() => {});
        state.playing = true;
      } else {
        await loadIndex(currentIndex());
      }
      notify();
    } catch (e) {
      if (!isPlayInterrupted(e)) notify();
      if (!isPlayInterrupted(e)) throw e;
    }
  }

  function pause() {
    audio.pause();
    if (incomingAudio) incomingAudio.pause();
    state.playing = false;
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    notify();
  }

  function toggle() {
    if (state.playing) pause();
    else playCurrent().catch(() => { /* surfaced via notify */ });
  }

  async function next() {
    cancelCrossfade();
    if (!state.tracks.length) return;
    if (state.repeat === 'one') {
      audio.currentTime = 0;
      try { await audio.play(); state.playing = true; notify(); } catch (_) { /* */ }
      return;
    }
    const atEnd = state.shuffle
      ? (state.orderPos >= state.order.length - 1)
      : (state.orderPos >= state.tracks.length - 1);
    if (atEnd && state.repeat === 'all') {
      state.orderPos = 0;
      if (state.shuffle && state.order.length) {
        const idx = currentIndex();
        state.order = shuffleOrder(state.tracks.length, idx);
        state.orderPos = 0;
      }
      await loadIndex(currentIndex());
      return;
    }
    if (state.shuffle && state.order.length) {
      if (state.orderPos < state.order.length - 1) state.orderPos += 1;
      else if (!(await tryContinueAfterQueue())) return pause();
    } else if (state.orderPos < state.tracks.length - 1) {
      state.orderPos += 1;
    } else if (!(await tryContinueAfterQueue())) {
      return pause();
    }
    await loadIndex(currentIndex());
  }

  async function prev() {
    cancelCrossfade();
    if (audio.currentTime > 3) {
      audio.currentTime = 0;
      notify();
      return;
    }
    if (!state.tracks.length) return;
    if (state.shuffle && state.order.length) {
      if (state.orderPos > 0) state.orderPos -= 1;
    } else if (state.orderPos > 0) {
      state.orderPos -= 1;
    }
    try {
      await loadIndex(currentIndex());
    } catch (e) {
      notify();
      throw e;
    }
  }

  function seekRatio(ratio) {
    const el = crossfading && incomingAudio?.src ? incomingAudio : audio;
    const dur = el.duration;
    if (!dur || !isFinite(dur)) return;
    el.currentTime = Math.max(0, Math.min(dur, dur * ratio));
    notify();
  }

  function setVolume(v) {
    state.volume = Math.max(0, Math.min(1, v / 100));
    if (crossfading && incomingAudio) {
      const elapsed = crossfadeStartedAt
        ? (performance.now() - crossfadeStartedAt) / 1000
        : 0;
      const progress = crossfadeProgress(elapsed, crossfadeDuration);
      const vols = crossfadeVolumes(progress, state.volume);
      audio.volume = vols.outgoing;
      incomingAudio.volume = vols.incoming;
    } else {
      audio.volume = state.volume;
    }
    notify();
  }

  function setShuffle(on) {
    cancelCrossfade();
    state.shuffle = !!on;
    const idx = currentIndex();
    state.order = state.shuffle ? shuffleOrder(state.tracks.length, idx) : [];
    state.orderPos = state.shuffle ? 0 : idx;
    if (state.shuffle && state.order.length) {
      state.orderPos = state.order.indexOf(idx);
      if (state.orderPos < 0) state.orderPos = 0;
    }
    notify();
  }

  function setRepeat(mode) {
    const modes = ['off', 'all', 'one'];
    if (mode === undefined || mode === null) {
      const i = modes.indexOf(state.repeat);
      state.repeat = modes[(i + 1) % modes.length];
    } else {
      state.repeat = modes.includes(mode) ? mode : 'off';
    }
    notify();
  }

  function cycleRepeat() {
    setRepeat();
  }

  async function play(opts, playOpts = {}) {
    const token = ++playToken;
    loadToken += 1;
    cancelCrossfade();
    const resolved = await resolveQueue(opts);
    if (token !== playToken) return;
    let startIndex = playOpts.startIndex ?? opts.startIndex ?? 0;
    if (playOpts.fromPath) {
      const at = resolved.tracks.findIndex((t) => t.path === playOpts.fromPath);
      if (at < 0) throw new Error('Track not found in playlist');
      startIndex = at;
    }
    startIndex = Math.max(0, Math.min(startIndex, resolved.tracks.length - 1));
    state.tracks = resolved.tracks;
    state.sourceLabel = resolved.sourceLabel || '';
    state.playlist = resolved.playlist || opts.name || '';
    state.playlistId = resolved.playlistId || opts.id || null;
    state.shuffle = !!(playOpts.shuffle ?? opts.shuffle);
    state.order = state.shuffle ? shuffleOrder(state.tracks.length, startIndex) : [];
    state.orderPos = state.shuffle ? 0 : startIndex;
    if (state.shuffle && state.order.length) {
      state.orderPos = state.order.indexOf(startIndex);
      if (state.orderPos < 0) state.orderPos = 0;
    }
    state.active = true;
    state.errorCount = 0;
    bindMediaSessionHandlers();
    document.body.classList.add('web-playing');
    await loadIndex(currentIndex());
  }

  async function seekToUpcomingOffset(qi) {
    cancelCrossfade();
    if (!state.tracks.length) return;
    const offset = Math.max(0, parseInt(qi, 10) || 0);
    if (state.shuffle && state.order.length) {
      const pos = state.orderPos + 1 + offset;
      if (pos >= state.order.length) return;
      state.orderPos = pos;
    } else {
      const idx = state.orderPos + 1 + offset;
      if (idx >= state.tracks.length) return;
      state.orderPos = idx;
    }
    await loadIndex(currentIndex());
  }

  async function seekToIndex(index) {
    cancelCrossfade();
    if (index < 0 || index >= state.tracks.length) return;
    state.orderPos = state.shuffle ? Math.max(0, state.order.indexOf(index)) : index;
    if (state.shuffle && state.orderPos < 0) state.orderPos = 0;
    await loadIndex(currentIndex());
  }

  function stop() {
    loadToken += 1;
    cancelCrossfade();
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    state.active = false;
    state.tracks = [];
    state.playing = false;
    document.body.classList.remove('web-playing');
    notify();
  }

  function bindPrimaryEvents(el) {
    el.addEventListener('timeupdate', () => {
      checkCrossfadeTrigger();
      notify();
    });
    el.addEventListener('ended', () => {
      if (suppressEnded || crossfading) return;
      next().catch(() => { /* */ });
    });
    el.addEventListener('pause', () => {
      if (!el.ended && !crossfading) {
        state.playing = false;
        notify();
      }
    });
    el.addEventListener('play', () => {
      if (!crossfading) {
        state.playing = true;
        notify();
      }
    });
    el.addEventListener('error', () => {
      if (el.dataset.loadToken !== String(loadToken)) return;
      state.playing = false;
      state.lastError = 'Could not load audio stream';
      notify();
    });
  }

  function bindIncomingEvents(el) {
    el.addEventListener('timeupdate', () => {
      if (crossfading) notify();
    });
    el.addEventListener('ended', () => {
      if (!crossfading) return;
      completeCrossfade(loadToken);
    });
  }

  bindPrimaryEvents(audio);

  const CrossfadeHelpers = {
    crossfadeProgress,
    crossfadeVolumes,
    shouldStartCrossfade,
  };

  const api = {
    get active() { return state.active; },
    getState: getPublicState,
    upcoming,
    onChange(fn) { listeners.add(fn); return () => listeners.delete(fn); },
    play,
    toggle,
    next,
    prev,
    pause,
    playCurrent,
    seekRatio,
    setVolume,
    setShuffle,
    setRepeat,
    cycleRepeat,
    seekToIndex,
    seekToUpcomingOffset,
    stop,
    streamUrl,
    CrossfadeHelpers,
  };

  root.WebPlayback = api;
  root.WebPlaybackCrossfade = CrossfadeHelpers;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
