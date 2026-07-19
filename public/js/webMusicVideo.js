/**
 * Browser music video — muted overlay synced to WebPlayback (library audio).
 */
(function (root) {
  'use strict';

  const cache = new Map();
  let cookiesStale = false;
  let healthPollTimer = null;

  async function pollMusicVideoHealth() {
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      const res = await fetchFn('/api/health', { credentials: 'same-origin' });
      if (!res.ok) return;
      const data = await res.json().catch(() => ({}));
      cookiesStale = !!(data.musicVideo && data.musicVideo.cookiesStale);
    } catch (_) { /* offline */ }
  }

  function startHealthPoll() {
    if (healthPollTimer) return;
    pollMusicVideoHealth();
    healthPollTimer = setInterval(pollMusicVideoHealth, 60000);
  }

  function trackKey(track) {
    if (!track) return '';
    return `${(track.title || '').trim()}\x00${(track.artist || '').trim()}`;
  }

  async function apiGet(url) {
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const res = await fetchFn(url, { credentials: 'same-origin' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    return data;
  }

  async function resolvePlayUrl(path) {
    if (!path) return null;
    if (/^https?:\/\//i.test(path)) return path;
    if (typeof root.signMediaPath === 'function') {
      return root.signMediaPath(path);
    }
    return path;
  }

  function showVideoEnabled() {
    return typeof root.ClientPrefsSync !== 'undefined'
      && root.ClientPrefsSync.getNowPlayingVideo?.();
  }

  function ensureDom() {
    return {
      wrap: document.getElementById('np-music-video-wrap'),
      video: document.getElementById('np-music-video'),
      heroVideo: document.getElementById('web-np-video'),
      art: document.getElementById('np-art'),
      artFallback: document.querySelector('#now-playing-bar .player-art-fallback'),
      heroArt: document.querySelector('#web-np-hero .web-np-hero-art-img'),
    };
  }

  async function prepare(track) {
    if (!track?.title) return { error: 'No track' };
    const key = trackKey(track);
    const prev = cache.get(key);
    if (prev && (prev.playUrl || prev.error) && !prev.loading) return prev;

    const entry = { loading: true, key };
    cache.set(key, entry);
    try {
      const qs = new URLSearchParams({
        title: track.title || '',
        artist: track.artist || '',
        wait: '8',
      });
      if (track.durationMs) {
        qs.set('durationSec', String(Math.max(1, Math.round(track.durationMs / 1000))));
      }
      const data = await apiGet(`/api/music-video?${qs}`);
      entry.videoId = data.videoId || null;
      entry.playUrl = data.playUrl || null;
      entry.streamReady = !!data.streamReady;
      entry.loading = false;
      if (!entry.playUrl) {
        entry.error = data.streamReason || data.reason || 'Video unavailable';
      }
    } catch (e) {
      entry.loading = false;
      entry.error = e.message || 'Video lookup failed';
    }
    return entry;
  }

  function setArtVisible(visible) {
    const { art, artFallback, heroArt } = ensureDom();
    if (art) art.hidden = !visible || !art.getAttribute('src');
    if (artFallback) artFallback.style.display = visible && art?.hidden ? '' : 'none';
    if (heroArt) heroArt.hidden = !visible;
    document.body.classList.toggle('np-video-playing', !visible);
  }

  function hideVideoLayer() {
    const { wrap, video, heroVideo } = ensureDom();
    if (wrap) wrap.classList.add('hidden');
    document.body.classList.remove('np-video-active');
    document.body.classList.remove('np-video-playing');
    for (const el of [video, heroVideo]) {
      if (el) {
        el.pause();
        el.removeAttribute('src');
        delete el.dataset.src;
        el.hidden = true;
      }
    }
    setArtVisible(true);
  }

  function revealVideo(video, artEls) {
    if (!video || !video.src) return;
    video.hidden = false;
    for (const el of artEls) {
      if (el) el.hidden = true;
    }
    document.body.classList.add('np-video-playing');
  }

  function syncPlayState(video, playing, srcChanged) {
    if (!playing) {
      video.pause();
      return;
    }
    if (video.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) {
      if (video.paused) video.play().catch(() => {});
      return;
    }
    if (srcChanged) {
      video.addEventListener('canplay', () => {
        if (!video.paused) return;
        video.play().catch(() => {});
      }, { once: true });
    }
  }

  async function applyVideoToElement(video, url, st, artEls) {
    if (!video) return;
    if (!url) {
      video.pause();
      video.hidden = true;
      setArtVisible(true);
      return;
    }

    const srcChanged = video.dataset.src !== url;
    if (srcChanged) {
      video.hidden = true;
      setArtVisible(true);
      video.dataset.src = url;
      video.src = url;
      video.muted = true;
      video.playsInline = true;
      video.setAttribute('playsinline', '');
      video.load();
      const reveal = () => revealVideo(video, artEls);
      video.addEventListener('playing', reveal, { once: true });
      video.addEventListener('loadeddata', reveal, { once: true });
    }

    const posSec = Math.max(0, (st.positionMs || 0) / 1000);
    if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA
      && Math.abs(video.currentTime - posSec) > 0.35) {
      try { video.currentTime = posSec; } catch (_) { /* seek while loading */ }
    }

    syncPlayState(video, st.playing, srcChanged);

    if (video.hidden && video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
      revealVideo(video, artEls);
    }
  }

  async function sync(st) {
    const enabled = showVideoEnabled();
    if (!enabled || !st?.active || !st.current) {
      hideVideoLayer();
      return;
    }
    startHealthPoll();
    if (cookiesStale) {
      hideVideoLayer();
      return;
    }

    const { wrap, video, heroVideo, art, heroArt } = ensureDom();
    if (!wrap || !video) {
      await applyVideoToElement(heroVideo, null, st, [heroArt]);
      return;
    }

    wrap.classList.remove('hidden');
    document.body.classList.add('np-video-active');

    const track = st.current;
    const entry = await prepare(track);
    const playUrl = entry.playUrl ? await resolvePlayUrl(entry.playUrl) : null;
    const artEls = [art, heroArt];
    await applyVideoToElement(video, playUrl, st, artEls);
    await applyVideoToElement(heroVideo, playUrl, st, artEls);
  }

  function prefetchNext(st) {
    if (!showVideoEnabled() || typeof root.WebPlayback === 'undefined') return;
    const upcoming = root.WebPlayback.upcoming?.(1) || [];
    if (upcoming[0]) prepare(upcoming[0]).catch(() => {});
  }

  function clearCache() {
    cache.clear();
    cookiesStale = false;
  }

  function bindVideoLoop() {
    for (const id of ['np-music-video', 'web-np-video']) {
      const video = document.getElementById(id);
      if (!video || video.dataset.loopBound) continue;
      video.dataset.loopBound = '1';
      video.addEventListener('ended', () => {
        if (!showVideoEnabled()) return;
        try {
          video.currentTime = 0;
          if (!video.paused) video.play().catch(() => {});
        } catch (_) { /* */ }
      });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bindVideoLoop);
  } else {
    bindVideoLoop();
  }

  root.WebMusicVideo = {
    prepare,
    sync,
    hideVideoLayer,
    prefetchNext,
    clearCache,
    showVideoEnabled,
  };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
