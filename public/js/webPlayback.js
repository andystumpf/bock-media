/**
 * Browser playback — stream from /stream/ on this server (Spotify-style laptop listening).
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
    const res = await fetch(url, { credentials: 'same-origin' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }

  function trackFromRow(row) {
    const path = row.path || row.filepath;
    if (!path) return null;
    return {
      path,
      title: row.title || row.track || path2name(path),
      artist: row.artist || '',
      album: row.album || '',
      durationMs: Math.max(0, (row.duration_seconds ?? row.duration ?? 0) * 1000),
    };
  }

  async function resolvePlaylist(id) {
    const tracks = [];
    let page = 1;
    let total = Infinity;
    while (tracks.length < total) {
      const d = await apiGet(`/api/playlists/${encodeURIComponent(id)}?page=${page}&limit=200`);
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
    sourceLabel: '',
    playlist: '',
    playlistId: null,
    playing: false,
    volume: 0.85,
    errorCount: 0,
    lastError: null,
  };

  const audio = new Audio();
  audio.preload = 'auto';
  const listeners = new Set();
  /** Bumped on each new load — stale play() promises are ignored. */
  let loadToken = 0;
  /** Serializes load/play so rapid skips or double-clicks don't interrupt each other. */
  let loadChain = Promise.resolve();
  let playToken = 0;

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

  async function loadIndexInner(idx, token) {
    const track = state.tracks[idx];
    if (!track || token !== loadToken) return false;
    const gainDb = await replayGainDbForTrack(track);
    const clientGain = gainDb != null && gainDb <= 0;
    let url = await streamUrl(track, !clientGain);
    if (!url) return false;
    audio.pause();
    audio.src = url;
    audio.dataset.loadToken = String(token);
    audio.load();
    audio.volume = state.volume * dbToLinear(clientGain ? gainDb : 0);
    try {
      await waitForCanPlay(audio, token);
      if (token !== loadToken) return false;
      await audio.play();
      if (token !== loadToken) {
        audio.pause();
        return false;
      }
      state.playing = true;
      state.errorCount = 0;
      state.lastError = null;
      syncMediaSession(track);
      notify();
      return true;
    } catch (e) {
      if (token !== loadToken || isPlayInterrupted(e)) return false;
      // Fallback: original file without server-side transcode.
      if (!clientGain && url.indexOf('normalize=0') < 0) {
        url = await streamUrl(track, false);
        audio.src = url;
        audio.load();
        audio.volume = state.volume;
        try {
          await waitForCanPlay(audio, token);
          if (token !== loadToken) return false;
          await audio.play();
          if (token !== loadToken) {
            audio.pause();
            return false;
          }
          state.playing = true;
          state.errorCount = 0;
          state.lastError = null;
          syncMediaSession(track);
          notify();
          return true;
        } catch (retryErr) {
          if (token !== loadToken || isPlayInterrupted(retryErr)) return false;
          e = retryErr;
        }
      }
      state.playing = false;
      state.lastError = e.message || 'Playback failed';
      notify();
      throw e;
    }
  }

  function loadIndex(idx) {
    const token = ++loadToken;
    loadChain = loadChain
      .then(() => loadIndexInner(idx, token))
      .catch((e) => {
        if (token !== loadToken || isPlayInterrupted(e)) return false;
        throw e;
      });
    return loadChain;
  }

  function notify() {
    listeners.forEach((fn) => { try { fn(getPublicState()); } catch (_) { /* */ } });
  }

  function getPublicState() {
    const idx = currentIndex();
    const t = state.tracks[idx];
    return {
      active: state.active,
      tracks: state.tracks.slice(),
      index: idx,
      current: t || null,
      playing: state.playing,
      shuffle: state.shuffle,
      sourceLabel: state.sourceLabel,
      playlist: state.playlist,
      playlistId: state.playlistId,
      positionMs: Math.round((audio.currentTime || 0) * 1000),
      durationMs: audio.duration && isFinite(audio.duration)
        ? Math.round(audio.duration * 1000)
        : (t && t.durationMs) || 0,
      volume: state.volume,
      lastError: state.lastError,
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
    state.playing = false;
    if ('mediaSession' in navigator) navigator.mediaSession.playbackState = 'paused';
    notify();
  }

  function toggle() {
    if (state.playing) pause();
    else playCurrent().catch(() => { /* surfaced via notify */ });
  }

  async function next() {
    if (!state.tracks.length) return;
    if (state.shuffle && state.order.length) {
      if (state.orderPos < state.order.length - 1) state.orderPos += 1;
      else return pause();
    } else if (state.orderPos < state.tracks.length - 1) {
      state.orderPos += 1;
    } else return pause();
    await loadIndex(currentIndex());
  }

  async function prev() {
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
    const dur = audio.duration;
    if (!dur || !isFinite(dur)) return;
    audio.currentTime = Math.max(0, Math.min(dur, dur * ratio));
    notify();
  }

  function setVolume(v) {
    state.volume = Math.max(0, Math.min(1, v / 100));
    audio.volume = state.volume;
    notify();
  }

  function setShuffle(on) {
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

  async function play(opts, playOpts = {}) {
    const token = ++playToken;
    loadToken += 1;
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

  async function seekToIndex(index) {
    if (index < 0 || index >= state.tracks.length) return;
    state.orderPos = state.shuffle ? Math.max(0, state.order.indexOf(index)) : index;
    if (state.shuffle && state.orderPos < 0) state.orderPos = 0;
    await loadIndex(currentIndex());
  }

  function stop() {
    loadToken += 1;
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    state.active = false;
    state.tracks = [];
    state.playing = false;
    document.body.classList.remove('web-playing');
    notify();
  }

  audio.addEventListener('timeupdate', () => notify());
  audio.addEventListener('ended', () => { next().catch(() => { /* */ }); });
  audio.addEventListener('pause', () => {
    if (!audio.ended) {
      state.playing = false;
      notify();
    }
  });
  audio.addEventListener('play', () => {
    state.playing = true;
    notify();
  });
  audio.addEventListener('error', () => {
    if (audio.dataset.loadToken !== String(loadToken)) return;
    state.playing = false;
    state.lastError = 'Could not load audio stream';
    notify();
  });

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
    seekToIndex,
    stop,
    streamUrl,
  };

  root.WebPlayback = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
