/**
 * Right panel: Now Playing, Queue, Connect device picker.
 */
(function (root) {
  'use strict';

  let activeTab = localStorage.getItem('bock_shell_right_panel_tab') || 'nowplaying';
  let activeDeviceId = 'web';

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function fmtDur(secs) {
    if (!secs) return '0:00';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  function artUrl(path) {
    return typeof root.artworkUrl === 'function' ? root.artworkUrl(path) : null;
  }

  function setTab(tab) {
    activeTab = tab;
    localStorage.setItem('bock_shell_right_panel_tab', tab);
    document.querySelectorAll('.right-panel-tab').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.tab === tab);
    });
    refresh();
  }

  async function renderNowPlaying(body) {
    const st = typeof root.WebPlayback !== 'undefined' ? root.WebPlayback.getState() : null;
    if (!st || !st.active || !st.current) {
      body.innerHTML = '<p class="hint right-panel-empty">Start playing to see Now Playing</p>';
      return;
    }
    const t = st.current;
    const url = artUrl(t.path);
    const liked = t.liked ? ' liked' : '';
    let relatedHtml = '';
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      const rel = await fetchFn(`/api/music-video/related?artist=${encodeURIComponent(t.artist || '')}&title=${encodeURIComponent(t.title || '')}&limit=6`, { credentials: 'same-origin' }).then((r) => r.json()).catch(() => ({ items: [] }));
      const items = rel.items || [];
      if (items.length) {
        relatedHtml = `<div class="right-panel-section"><h4>Related music videos</h4><div class="right-panel-mv-row">${items.map((v) =>
          `<a href="#nowplaying" class="right-panel-mv-card" data-video="${esc(v.videoId)}">
            <img src="${esc(v.thumbnail || '')}" alt=""><span>${esc(v.title)}</span>
          </a>`).join('')}</div></div>`;
      }
    } catch { /* optional */ }

    body.innerHTML = `
      <div class="right-panel-np">
        <div class="right-panel-np-art">${url ? `<img src="${esc(url)}" alt="">` : '<i class="fa fa-music"></i>'}</div>
        <h3 class="right-panel-np-title">${esc(t.title)}</h3>
        <p class="right-panel-np-artist">${esc(t.artist)}</p>
        <button type="button" class="right-panel-like${liked}" onclick="toggleTrackLike('${esc(t.path)}','${esc(t.title)}','${esc(t.artist)}')"><i class="fa${t.liked ? ' fa-check' : '-regular fa-heart'}"></i></button>
      </div>
      ${relatedHtml}`;
  }

  function renderQueue(body) {
    if (typeof root.renderQueuePanel === 'function') {
      body.innerHTML = '<div id="right-panel-queue-inner" class="spotify-queue-body"></div>';
      const inner = document.getElementById('right-panel-queue-inner');
      const old = document.getElementById('spotify-queue-body');
      if (old && inner) {
        const prevId = old.id;
        old.id = 'spotify-queue-body-temp';
        inner.id = 'spotify-queue-body';
        root.renderQueuePanel();
        const cur = document.getElementById('spotify-queue-body');
        if (cur) cur.id = 'spotify-queue-body';
        const temp = document.getElementById('spotify-queue-body-temp');
        if (temp) temp.id = prevId;
        inner.id = 'right-panel-queue-inner';
        body.innerHTML = '';
        body.appendChild(cur || inner);
      } else {
        root.renderQueuePanel();
      }
      return;
    }
    body.innerHTML = '<p class="hint">Queue empty</p>';
  }

  async function renderConnect(body) {
    body.innerHTML = '<p class="hint">Loading devices…</p>';
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      const data = await fetchFn('/api/alexa_remote/devices', { credentials: 'same-origin' }).then((r) => r.json()).catch(() => ({ devices: [] }));
      const devices = data.devices || data.items || [];
      const webActive = activeDeviceId === 'web' || (typeof root.WebPlayback !== 'undefined' && root.WebPlayback.active);
      let html = `<button type="button" class="connect-device${webActive ? ' active' : ''}" data-device="web">
        <i class="fa fa-laptop"></i><span>This web browser</span></button>`;
      html += devices.map((d) => {
        const id = d.serialNumber || d.deviceId || d.id || '';
        const name = d.accountName || d.name || id;
        const active = activeDeviceId === id ? ' active' : '';
        return `<button type="button" class="connect-device${active}" data-device="${esc(id)}">
          <i class="fa fa-speaker"></i><span>${esc(name)}</span></button>`;
      }).join('');
      body.innerHTML = `<div class="connect-panel"><h4>Connect to a device</h4>${html}</div>`;
      body.querySelectorAll('.connect-device').forEach((btn) => {
        btn.addEventListener('click', () => handoffToDevice(btn.getAttribute('data-device')));
      });
    } catch {
      body.innerHTML = '<p class="hint">Could not load devices</p>';
    }
  }

  async function handoffToDevice(deviceId) {
    if (!deviceId || deviceId === 'web') {
      activeDeviceId = 'web';
      refresh();
      return;
    }
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      const st = typeof root.WebPlayback !== 'undefined' ? root.WebPlayback.getState() : null;
      await fetchFn('/api/playback/handoff', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          fromDeviceId: 'web',
          toDeviceId: deviceId,
          path: st?.current?.path,
          positionSec: st?.positionSec || 0,
        }),
      });
      activeDeviceId = deviceId;
      if (typeof root.WebPlayback !== 'undefined') root.WebPlayback.pause?.();
      refresh();
    } catch (e) {
      if (typeof root.toast === 'function') root.toast(e.message || 'Handoff failed');
    }
  }

  function refresh() {
    const body = document.getElementById('right-panel-body');
    if (!body) return;
    if (activeTab === 'queue') renderQueue(body);
    else if (activeTab === 'connect') renderConnect(body);
    else renderNowPlaying(body);
  }

  function init() {
    document.querySelectorAll('.right-panel-tab').forEach((btn) => {
      btn.addEventListener('click', () => setTab(btn.dataset.tab || 'nowplaying'));
    });
    document.getElementById('right-panel-close')?.addEventListener('click', () => {
      if (typeof root.ShellLayout !== 'undefined') root.ShellLayout.toggleRightPanel(false);
    });
    document.getElementById('np-bar-connect')?.addEventListener('click', (e) => {
      e.stopPropagation();
      if (typeof root.ShellLayout !== 'undefined') root.ShellLayout.toggleRightPanel(true);
      setTab('connect');
    });
    if (typeof root.WebPlayback !== 'undefined') {
      root.WebPlayback.onChange(() => refresh());
    }
    refresh();
  }

  root.RightPanel = { init, refresh, setTab, handoffToDevice };
  root.toggleTrackLike = root.toggleTrackLike || async function toggleTrackLike(path, title, artist) {
    if (!path) return;
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      await fetchFn('/api/favorites', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ path, title, artist }),
      });
      refresh();
    } catch { /* */ }
  };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
