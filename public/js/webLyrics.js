/**
 * Browser Now Playing lyrics — synced to WebPlayback position.
 */
(function (root) {
  'use strict';

  let showing = false;
  let cacheKey = '';
  let lyrics = null;
  let loading = false;
  let offsetMs = 0;

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  async function fetchLyrics(track, durationSec) {
    if (!track?.path && !track?.title) return null;
    const qs = new URLSearchParams();
    if (track.path) qs.set('path', track.path);
    if (track.title) qs.set('title', track.title);
    if (track.artist) qs.set('artist', track.artist);
    if (track.album) qs.set('album', track.album);
    if (durationSec) qs.set('durationSec', String(durationSec));
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const res = await fetchFn(`/api/lyrics?${qs}`, { credentials: 'same-origin' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || 'Lyrics unavailable');
    return data;
  }

  function activeIndex(lines, positionMs) {
    if (!lines?.length) return -1;
    let lo = 0;
    let hi = lines.length - 1;
    let ans = -1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if ((lines[mid].timeMs || 0) <= positionMs) {
        ans = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return ans;
  }

  function renderPanel(st) {
    const host = document.getElementById('web-np-lyrics');
    if (!host) return;
    if (!showing || !st?.current) {
      host.classList.add('hidden');
      host.innerHTML = '';
      return;
    }
    host.classList.remove('hidden');
    if (loading) {
      host.innerHTML = '<p class="hint">Loading lyrics…</p>';
      return;
    }
    const lines = lyrics?.lines || [];
    if (!lines.length && !(lyrics?.plain)) {
      host.innerHTML = '<p class="hint">No lyrics found for this track.</p>';
      return;
    }
    const pos = (st.positionMs || 0) + offsetMs;
    const active = activeIndex(lines, pos);
    if (lines.length) {
      host.innerHTML = `<ul class="web-np-lyrics-list">${lines.map((ln, i) =>
        `<li class="web-np-lyric-line${i === active ? ' active' : ''}">${esc(ln.text || '')}</li>`,
      ).join('')}</ul>`;
      const activeEl = host.querySelector('.web-np-lyric-line.active');
      if (activeEl) activeEl.scrollIntoView({ block: 'center', behavior: 'smooth' });
    } else {
      host.innerHTML = `<pre class="web-np-lyrics-plain">${esc(lyrics.plain)}</pre>`;
    }
  }

  async function prepare(track, durationSec) {
    const key = `${track?.path || ''}|${track?.title || ''}|${track?.artist || ''}`;
    if (key === cacheKey && lyrics) return;
    cacheKey = key;
    lyrics = null;
    loading = true;
    try {
      lyrics = await fetchLyrics(track, durationSec);
    } catch (_) {
      lyrics = { lines: [], plain: '' };
    }
    loading = false;
  }

  async function sync(st) {
    if (!showing || !st?.active || !st.current) {
      renderPanel(st);
      return;
    }
    const durSec = st.durationMs ? Math.round(st.durationMs / 1000) : 0;
    await prepare(st.current, durSec > 0 ? durSec : null);
    renderPanel(st);
  }

  function toggle() {
    showing = !showing;
    if (!showing) {
      cacheKey = '';
      lyrics = null;
    }
    if (typeof root.WebPlayback !== 'undefined') {
      sync(root.WebPlayback.getState());
    }
  }

  function isShowing() { return showing; }

  root.WebLyrics = { sync, toggle, isShowing, setOffset: (ms) => { offsetMs = ms; } };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
