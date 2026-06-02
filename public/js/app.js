// Bock Media frontend app

const API = (path) => fetch(path).then(r => r.json()).catch(() => null);
const POST = (path, body) => fetch(path, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
}).then(r => r.json()).catch(() => null);

// Format helpers
function fmtNum(n) { return Number(n || 0).toLocaleString(); }
function fmtDuration(secs) {
  if (!secs) return '—';
  const m = Math.floor(secs / 60), s = secs % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}
function fmtDate(d) {
  if (!d) return '—';
  try { return new Date(d).toLocaleDateString(); } catch { return d; }
}
function fmtDateTime(d) {
  if (!d) return '—';
  try { return new Date(d).toLocaleString(); } catch { return d; }
}
function escHtml(s) {
  return String(s || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

/** Row action button — icon only; kind: play | run | edit | merge | delete | muted */
function actionBtn({ kind, onclick, title, icon, extraClass, dataAttrs }) {
  const cls = ['action-btn', `action-${kind}`, extraClass].filter(Boolean).join(' ');
  return `<button type="button" class="${cls}" onclick="${onclick}"${dataAttrs || ''} title="${escHtml(title)}" aria-label="${escHtml(title)}"><i class="fa fa-${icon}"></i></button>`;
}

function rowActions(...buttons) {
  const html = buttons.filter(Boolean).join('');
  return html ? `<td class="row-actions-cell"><div class="row-actions">${html}</div></td>` : '<td></td>';
}

// Router
const routes = {};
let currentRoute = '';

function register(name, fn) { routes[name] = fn; }

function navigate(hash) {
  const [route, ...rest] = (hash || 'dashboard').split('/');
  const params = rest.join('/');
  currentRoute = route;

  document.querySelectorAll('.nav-link').forEach(a => {
    a.classList.toggle('active', a.getAttribute('href') === `#${route}`);
  });

  const fn = routes[route];
  if (fn) fn(params);
  else renderPage('Not Found', '<div class="empty-state"><i class="fa fa-question-circle"></i><p>Page not found.</p></div>');
}

function renderPage(title, html) {
  document.getElementById('page-title').textContent = title;
  document.getElementById('main-content').innerHTML = html;
}

function loading() {
  document.getElementById('main-content').innerHTML = '<div class="spinner-wrap"><div class="spinner"></div></div>';
}

// Pagination helper
function buildPagination(total, page, limit, onPage) {
  const totalPages = Math.ceil(total / limit);
  if (totalPages <= 1) return '';

  const start = (page - 1) * limit + 1;
  const end = Math.min(page * limit, total);

  let pages = '';
  const radius = 2;
  for (let i = 1; i <= totalPages; i++) {
    if (i === 1 || i === totalPages || (i >= page - radius && i <= page + radius)) {
      pages += `<button class="page-btn ${i === page ? 'active' : ''}" onclick="(${onPage})(${i})">${i}</button>`;
    } else if (i === page - radius - 1 || i === page + radius + 1) {
      pages += `<span style="padding:4px 6px;color:#99a">…</span>`;
    }
  }

  return `
    <div class="pagination-bar">
      <span>Showing ${fmtNum(start)}–${fmtNum(end)} of ${fmtNum(total)}</span>
      <div class="page-btns">
        <button class="page-btn" onclick="(${onPage})(${page - 1})" ${page <= 1 ? 'disabled' : ''}><i class="fa fa-chevron-left"></i></button>
        ${pages}
        <button class="page-btn" onclick="(${onPage})(${page + 1})" ${page >= totalPages ? 'disabled' : ''}><i class="fa fa-chevron-right"></i></button>
      </div>
    </div>`;
}

// Search helper - returns debounced input HTML
function searchInput(placeholder, id) {
  return `<input type="text" id="${id}" placeholder="${placeholder}" oninput="window._searchDebounce && clearTimeout(window._searchDebounce); window._searchDebounce = setTimeout(() => window._onSearch && window._onSearch(this.value), 350)">`;
}

// ── Dashboard ────────────────────────────────────────────────────────────────
// Voice commands routed through the custom skill ("ask Bock Media to ..."). Each
// maps to a real intent in skill/interaction_model.json. [bracketed] = fill-in.
const VOICE_SUGGESTIONS = [
  'Alexa, ask Bock Media to play my [playlist] playlist',
  'Alexa, ask Bock Media to mix my [playlist] playlist',
  'Alexa, ask Bock Media to play music by [artist]',
  'Alexa, ask Bock Media to play the album [album]',
  'Alexa, ask Bock Media to play the song [song] by [artist]',
  'Alexa, ask Bock Media to play [genre] music',
  "Alexa, ask Bock Media what's playing",
];

// Spoken without "ask" once Bock Media is the active audio player (in-playback
// controls handled by the AMAZON.* built-in intents).
const PLAYBACK_CONTROLS = [
  'Alexa, pause',
  'Alexa, resume',
  'Alexa, next',
  'Alexa, previous',
  'Alexa, shuffle on',
  'Alexa, shuffle off',
  'Alexa, loop on',
  'Alexa, loop off',
  'Alexa, stop',
];

// Hands-free, no "ask" prefix. These only work after you create an Alexa Routine
// (Alexa app -> Routines -> When you say [phrase] -> Music -> Bock Media). Tip:
// use "mix" over "shuffle" to avoid Amazon Music / Spotify intercepting.
const ROUTINE_SUGGESTIONS = [
  'Alexa, play [playlist] on Bock Media',
  'Alexa, play [playlist]   (after you map the phrase in a Routine)',
];

let _dashPage = 1;

register('dashboard', async () => {
  _dashPage = 1;
  loading();
  await loadDashboard();
});

async function loadDashboard() {
  const [summary, recentData] = await Promise.all([
    API('/api/summary'),
    API(`/api/recent?page=${_dashPage}&limit=10`),
  ]);

  const s = summary || {};
  const { items: recentItems = [], total: recentTotal = 0 } = recentData || {};

  const recentRows = recentItems.map(r => `
    <tr>
      <td style="color:#555">${escHtml(r.heard)}</td>
      <td style="color:${r.success ? '#2eaa5a' : '#e44'}">${escHtml(r.found)}</td>
    </tr>`).join('');

  const cmdRow = (s, icon, color) =>
    `<tr><td><i class="fa ${icon}" style="color:${color};margin-right:8px;font-size:11px"></i>${escHtml(s)}</td></tr>`;
  const sectionRow = (label) =>
    `<tr><td style="padding-top:10px;font-size:11px;font-weight:600;color:#7a8aa8;text-transform:uppercase;letter-spacing:.04em">${escHtml(label)}</td></tr>`;

  const voiceRows = [
    sectionRow('Start playback'),
    ...VOICE_SUGGESTIONS.map(s => cmdRow(s, 'fa-microphone', '#e99d1a')),
    sectionRow('While music is playing'),
    ...PLAYBACK_CONTROLS.map(s => cmdRow(s, 'fa-sliders', '#30426a')),
  ].join('');

  const routineRows = ROUTINE_SUGGESTIONS.map(s =>
    cmdRow(s, 'fa-bolt', '#7c4dbd')
  ).join('');

  const recentPager = buildPagination(recentTotal, _dashPage, 10, (p) => { _dashPage = p; loadDashboard(); });

  document.getElementById('page-title').textContent = 'Dashboard';
  document.getElementById('main-content').innerHTML = `
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon blue"><i class="fa fa-music"></i></div>
        <div>
          <div class="stat-value">${fmtNum(s.songs)}</div>
          <div class="stat-label">Songs</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon teal"><i class="fa fa-folder-open"></i></div>
        <div>
          <div class="stat-value">${fmtNum(s.watchFolders)}</div>
          <div class="stat-label">Watch Folders</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><i class="fa fa-compact-disc"></i></div>
        <div>
          <div class="stat-value">${fmtNum(s.albums)}</div>
          <div class="stat-label">Albums</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><i class="fa fa-microphone"></i></div>
        <div>
          <div class="stat-value">${fmtNum(s.artists)}</div>
          <div class="stat-label">Artists</div>
        </div>
      </div>
    </div>

    <div style="display:grid;grid-template-columns:1fr 1fr;gap:20px">
      <div class="card">
        <div class="card-header">
          <h3><i class="fa fa-microphone"></i> Voice Commands</h3>
        </div>
        <table class="data-table">
          <tbody>${voiceRows}</tbody>
        </table>
      </div>

      <div class="card">
        <div class="card-header">
          <h3><i class="fa fa-bolt"></i> Hands-free (Alexa Routines)</h3>
        </div>
        <div class="page-desc" style="margin:0 0 6px">
          No "ask" prefix needed. Set up in the Alexa app: <b>Routines → When you say [phrase] → Music → Bock Media</b>. Routines bypass the music-provider arbitration that otherwise sends "play" to Amazon Music or Spotify.
        </div>
        <table class="data-table">
          <tbody>${routineRows}</tbody>
        </table>
      </div>
    </div>

    <div class="card" style="margin-top:20px">
      <div class="card-header">
        <h3><i class="fa fa-history"></i> Recent Alexa Play Requests</h3>
        <button onclick="loadDashboard()" style="background:none;border:none;color:#30426a;cursor:pointer;font-size:12px">
          <i class="fa fa-rotate-right"></i> Refresh
        </button>
      </div>
      ${recentRows ? `
      <table class="data-table">
        <thead><tr><th>Heard</th><th>Found</th></tr></thead>
        <tbody>${recentRows}</tbody>
      </table>
      ${recentPager}` : `<div class="empty-state"><i class="fa fa-history"></i><p>No recent play requests.</p></div>`}
    </div>`;
}

// ── Now Playing ──────────────────────────────────────────────────────────────
let _npPage = 1;
let _npPollTimer = null;

register('nowplaying', async () => {
  _npPage = 1;
  loading();
  clearInterval(_npPollTimer);
  await loadNowPlaying();
  _npPollTimer = setInterval(async () => {
    const data = await API('/api/nowplaying_devices');
    window._npItems = (data && data.items) || [];
    window._npControlsAvailable = !!(data && data.controlsAvailable);
    const card = document.getElementById('np-current-card');
    if (card) card.outerHTML = buildCurrentCard(window._npItems, window._npControlsAvailable);
    refreshCurrentTrack();
    npLoadVolumes();
  }, 5000);
});

function npResolveSerial(d) {
  const devs = window._alexaDevices || [];
  const name = (d.deviceName || '').toLowerCase();
  const match = devs.find(x => (x.name || '').toLowerCase() === name);
  return match ? match.serial : '';
}

function npCanControl(d, controlsAvailable) {
  // Transport needs a real Alexa serial. Auto-named rows (e.g. "Echo MT7SEE")
  // that don't match an Alexa device name can't be controlled — rename them on
  // the Devices tab to match the Echo's name in the Alexa app.
  return !!(controlsAvailable && d.deviceName
    && !String(d.deviceId || '').startsWith('msp-')
    && npResolveSerial(d));
}

function npDeviceIdClass(deviceId) {
  return 'np-dev-' + String(deviceId || '').replace(/[^a-zA-Z0-9]/g, '_');
}

function buildDeviceRow(d, controlsAvailable = false) {
  const devAttr = ` data-device-id="${escHtml(d.deviceId)}"`;
  const shuffleCls = npDeviceIdClass(d.deviceId);
  const controls = npCanControl(d, controlsAvailable) ? `
    <div class="np-controls row-actions">
      ${actionBtn({ kind: 'muted', onclick: "npControlEl(this,'previous')", title: 'Previous', icon: 'backward-step', dataAttrs: devAttr })}
      ${actionBtn({ kind: 'play', onclick: "npControlEl(this,'play')", title: 'Play', icon: 'play', dataAttrs: devAttr })}
      ${actionBtn({ kind: 'muted', onclick: "npControlEl(this,'pause')", title: 'Pause', icon: 'pause', dataAttrs: devAttr })}
      ${actionBtn({ kind: 'muted', onclick: "npControlEl(this,'next')", title: 'Next', icon: 'forward-step', dataAttrs: devAttr })}
      ${actionBtn({ kind: 'muted', onclick: 'npToggleShuffleEl(this)', title: 'Shuffle', icon: 'shuffle', extraClass: `np-shuffle-btn np-shuffle-${shuffleCls}`, dataAttrs: devAttr })}
      ${actionBtn({ kind: 'delete', onclick: "npControlEl(this,'stop')", title: 'Stop', icon: 'stop', dataAttrs: devAttr })}
    </div>` : '';
  const pausedBadge = d.paused ? '<span class="np-paused-badge">Paused</span>' : '';
  const canControl = npCanControl(d, controlsAvailable);
  window._npVolume = window._npVolume || {};
  const knownVol = window._npVolume[d.deviceId];
  const vol = (knownVol == null) ? 50 : knownVol;
  const volume = canControl ? `
    <div class="np-volume">
      <i class="fa fa-volume-low np-volume-icon"></i>
      <input type="range" class="np-volume-slider" min="0" max="100" value="${vol}"
        oninput="npVolumeEl(this)" onchange="npVolumeEl(this)" ${devAttr}>
      <span class="np-volume-val">${knownVol == null ? '—' : knownVol}</span>
    </div>` : '';
  return `
    <div class="np-device-row${d.paused ? ' np-device-paused' : ''}">
      <div class="np-device-main">
        <div class="np-device-icon"><i class="fa fa-music"></i></div>
        <div class="np-device-meta">
          <div class="np-track">${escHtml(d.track || '—')} ${pausedBadge}</div>
          ${d.artist ? `<div class="np-artist">${escHtml(d.artist)}</div>` : ''}
          ${d.album ? `<div class="np-album">${escHtml(d.album)}</div>` : ''}
          <div class="np-device-label">Device: ${escHtml(d.deviceName || (d.deviceId || '').slice(-12) || 'default')}</div>
        </div>
      </div>
      ${controls}
      ${volume}
    </div>`;
}

let _npVolumeTimers = {};
function npVolumeEl(slider) {
  const deviceId = slider && slider.dataset && slider.dataset.deviceId;
  if (!deviceId) return;
  const d = (window._npItems || []).find(x => x.deviceId === deviceId);
  if (!d) return;
  const serial = npResolveSerial(d);
  if (!serial) return;
  const volume = parseInt(slider.value, 10);
  // Persist immediately so the 5s poll re-render keeps this value.
  window._npVolume = window._npVolume || {};
  window._npVolume[deviceId] = volume;
  const valEl = slider.parentElement && slider.parentElement.querySelector('.np-volume-val');
  if (valEl) valEl.textContent = volume;
  // Debounce: only send after the user pauses dragging.
  clearTimeout(_npVolumeTimers[deviceId]);
  _npVolumeTimers[deviceId] = setTimeout(async () => {
    try {
      const res = await fetch('/api/alexa_remote/volume', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ serial, device: d.deviceName, volume }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        showToast(data.error || 'Volume failed', true);
      }
    } catch (e) {
      showToast(e.message || 'Volume failed', true);
    }
  }, 350);
}

// Fetch the real current volume for controllable devices we don't yet know,
// then update the slider in place. Runs once per device (not on every poll) so
// it never hammers the unofficial API or fights the user mid-drag.
async function npLoadVolumes() {
  window._npVolume = window._npVolume || {};
  const items = (window._npItems || []).filter(d =>
    npCanControl(d, window._npControlsAvailable) && window._npVolume[d.deviceId] == null);
  for (const d of items) {
    const serial = npResolveSerial(d);
    if (!serial) continue;
    try {
      const data = await API(`/api/alexa_remote/volume?serial=${encodeURIComponent(serial)}`);
      const v = data && data.volume;
      if (v == null) continue;
      window._npVolume[d.deviceId] = v;
      // User may be dragging — only seed sliders still showing the placeholder.
      const cls = npDeviceIdClass(d.deviceId);
      document.querySelectorAll(`.np-volume-slider[data-device-id="${cssEsc(d.deviceId)}"]`).forEach(sl => {
        sl.value = v;
        const valEl = sl.parentElement && sl.parentElement.querySelector('.np-volume-val');
        if (valEl) valEl.textContent = v;
      });
    } catch (_) { /* leave at placeholder */ }
  }
}

function cssEsc(s) {
  return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/"/g, '\\"');
}

async function npControlEl(btn, action) {
  const deviceId = btn && btn.dataset && btn.dataset.deviceId;
  if (!deviceId) return;
  await npControl(deviceId, action);
}

async function npControl(deviceId, action) {
  const d = (window._npItems || []).find(x => x.deviceId === deviceId);
  if (!d || !d.deviceName) return;
  const serial = npResolveSerial(d);
  if (!serial) {
    return showToast(`Can't control "${d.deviceName}" — rename it on the Devices tab to match the Echo's Alexa name.`, true);
  }
  try {
    const res = await fetch('/api/alexa_remote/control', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId: d.deviceId,
        device: d.deviceName,
        serial,
        action,
      }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const msg = /not_authenticated/.test(data.error || '')
        ? 'Alexa session expired — re-run scripts/alexa_login.py'
        : (data.error || 'Control failed');
      return showToast(msg, true);
    }
    const fresh = await API('/api/nowplaying_devices');
    window._npItems = (fresh && fresh.items) || window._npItems;
    const card = document.getElementById('np-current-card');
    if (card) card.outerHTML = buildCurrentCard(window._npItems, window._npControlsAvailable);
    npLoadVolumes();
  } catch (e) {
    showToast(e.message || 'Control failed', true);
  }
}

async function npToggleShuffleEl(btn) {
  const deviceId = btn && btn.dataset && btn.dataset.deviceId;
  if (!deviceId) return;
  const d = (window._npItems || []).find(x => x.deviceId === deviceId);
  if (!d || !d.deviceName) return;
  window._npShuffle = window._npShuffle || {};
  const on = !window._npShuffle[deviceId];
  window._npShuffle[deviceId] = on;
  await npControl(deviceId, on ? 'shuffle_on' : 'shuffle_off');
  btn.classList.toggle('shuffle-on', on);
}

function buildCurrentCard(items, controlsAvailable = false) {
  const list = Array.isArray(items) ? items : [];
  if (!list.length) {
    return `
      <div class="card" id="np-current-card" style="border-left:4px solid #dde3ee;margin-bottom:20px">
        <div class="card-body" style="display:flex;align-items:center;gap:16px">
          <div style="font-size:32px;color:#ccd3e0"><i class="fa fa-music"></i></div>
          <div>
            <div style="font-size:11px;text-transform:uppercase;letter-spacing:.5px;color:#aab;margin-bottom:4px">Now Playing</div>
            <div style="font-size:15px;color:#aab;font-style:italic">Nothing is currently playing</div>
            <div style="font-size:12px;color:#bbc;margin-top:2px">Ask Alexa to play a playlist, artist, or album</div>
          </div>
        </div>
      </div>`;
  }
  const header = `<div class="np-card-header">Now Playing (${list.length})</div>`;
  return `
    <div class="card" id="np-current-card" style="border-left:4px solid #e99d1a;margin-bottom:20px">
      <div class="card-body">
        ${header}
        ${list.map(d => buildDeviceRow(d, controlsAvailable)).join('')}
      </div>
    </div>`;
}

async function loadNowPlaying() {
  const [npDevices, histData, remote] = await Promise.all([
    API('/api/nowplaying_devices'),
    API(`/api/nowplaying?page=${_npPage}&limit=25`),
    ensureAlexaRemoteStatus(),
  ]);
  const { items = [], total = 0 } = histData || {};
  window._npItems = (npDevices && npDevices.items) || [];
  window._npControlsAvailable = !!(npDevices && npDevices.controlsAvailable) && !!(remote && remote.configured);
  if (window._npControlsAvailable) {
    await ensureAlexaDevices().catch(() => []);
  }

  const currentCard = buildCurrentCard(window._npItems, window._npControlsAvailable);

  const rows = items.map(e => `
    <tr>
      <td>${escHtml(e.track || '—')}</td>
      <td class="text-muted">${escHtml(e.artist || '—')}</td>
      <td><span class="badge">${escHtml(e.device || '—')}</span></td>
      <td class="text-muted" style="font-size:11px">${fmtDateTime(e.date)}</td>
    </tr>`).join('');

  document.getElementById('page-title').textContent = 'Now Playing';
  document.getElementById('main-content').innerHTML = `
    ${currentCard}
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-history"></i> Streaming History (${fmtNum(total)})</h3>
        <button onclick="loadNowPlaying()" style="background:none;border:none;color:#30426a;cursor:pointer;font-size:12px">
          <i class="fa fa-rotate-right"></i> Refresh
        </button>
      </div>
      ${rows ? `
      <table class="data-table np-table">
        <thead><tr><th>Track</th><th>Artist</th><th>Device</th><th>Date</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
      ${buildPagination(total, _npPage, 25, (p) => { _npPage = p; loadNowPlaying(); })}
      ` : `<div class="empty-state"><i class="fa fa-play"></i><p>No streaming history found.</p></div>`}
    </div>`;
  npLoadVolumes();
}

async function refreshCurrentTrack() {
  const data = await API('/api/nowplaying_devices');
  const bar = document.getElementById('now-playing-bar');
  const txt = document.getElementById('np-track-text');
  if (!bar || !txt) return;
  const items = (data && data.items) || [];
  if (items.length) {
    const labels = items.map(c => c.artist ? `${c.track} — ${c.artist}` : c.track);
    txt.textContent = labels.length === 1
      ? labels[0]
      : `${labels[0]} (+${labels.length - 1} more)`;
    bar.style.display = 'flex';
  } else {
    bar.style.display = 'none';
  }
}


// ── Playlists ────────────────────────────────────────────────────────────────
let _plPage = 1, _plSearch = '';
register('playlists', async (params) => {
  if (params) { _plPage = 1; _plSearch = ''; }
  loading();
  await loadPlaylists();
});

async function ensureAlexaRemoteStatus() {
  if (window._alexaRemote) return window._alexaRemote;
  try {
    window._alexaRemote = await API('/api/alexa_remote/status') || { available: false };
  } catch (e) {
    window._alexaRemote = { available: false, configured: false };
  }
  return window._alexaRemote;
}

async function loadPlaylists() {
  const [data, remote] = await Promise.all([
    API(`/api/playlists?page=${_plPage}&limit=100&search=${encodeURIComponent(_plSearch)}`),
    ensureAlexaRemoteStatus(),
  ]);
  const { items = [], total = 0 } = data || {};
  window._playlists = items;
  const canPlay = !!(remote && remote.configured);

  const rows = items.map((p, i) => {
    // SourceID in the library XML still tags indexed playlists as "MyMedia";
    // match the raw data value, but display it under the Bock Media brand.
    const isLibraryPlaylist = !p.source || p.source.includes('MyMedia');
    const typeIcon = p.isAudioBook
      ? `<i class="fa fa-book" title="Audiobook" style="color:#7c4dbd"></i>`
      : `<i class="fa fa-music" title="Music" style="color:#e99d1a"></i>`;
    const srcDisplay = p.source
      ? `<span class="source-path" title="${escHtml(p.source)}">${escHtml(p.source)}</span>`
      : '<span class="text-muted">—</span>';
    const typeBadge = isLibraryPlaylist
      ? '<span class="badge orange">Bock Media</span>'
      : '<span class="badge">File</span>';
    const editBtn = p.id
      ? actionBtn({ kind: 'edit', onclick: `startEditPlaylist(${i})`, title: 'Rename playlist', icon: 'pen' })
      : '';
    const playBtn = canPlay
      ? actionBtn({ kind: 'play', onclick: `openPlayMenu(${i})`, title: 'Play on a device', icon: 'play' })
      : '';
    return `
    <tr id="pl-row-${i}">
      <td style="width:32px;text-align:center">${typeIcon}</td>
      <td><span class="pl-name-text">${escHtml(p.name)}</span></td>
      <td>${srcDisplay}</td>
      <td>${typeBadge}</td>
      <td><span class="badge orange">${fmtNum(p.trackCount)}</span></td>
      ${rowActions(playBtn, editBtn)}
    </tr>`;
  }).join('');

  document.getElementById('page-title').textContent = 'Playlists';
  document.getElementById('main-content').innerHTML = `
    <div class="page-desc">
      This page shows playlists that have been indexed by Bock Media. File based playlists (M3U, M3U8, PLS) are automatically indexed if Bock Media finds them during a scan of a Watch Folder. Use the pencil icon to rename a playlist — Alexa will recognize the new name immediately.
    </div>
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-list"></i> Playlists (${fmtNum(total)})</h3>
        <div class="search-bar" style="margin:0">
          <input type="text" placeholder="Search playlists…" value="${escHtml(_plSearch)}"
            oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_plSearch=this.value;_plPage=1;loadPlaylists()},350)">
        </div>
      </div>
      ${rows ? `
      <table class="data-table playlists-table">
        <thead><tr><th></th><th>Name</th><th>Source</th><th>Type</th><th>Tracks</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>` : `<div class="empty-state"><i class="fa fa-list"></i><p>No playlists found.</p></div>`}
      ${buildPagination(total, _plPage, 100, (p) => { _plPage = p; loadPlaylists(); })}
    </div>`;
}

function startEditPlaylist(i) {
  const p = (window._playlists || [])[i];
  const row = document.getElementById(`pl-row-${i}`);
  if (!p || !row) return;
  const nameCell = row.querySelector('.pl-name-text');
  if (!nameCell) return;
  nameCell.outerHTML = `
    <span class="edit-row" style="display:flex;gap:6px;align-items:center">
      <input id="pl-input-${i}" type="text" class="settings-input" value="${escHtml(p.name)}" style="flex:1">
      <button class="save-btn" onclick="savePlaylistRename(${i})">Save</button>
      <button class="cancel-btn" onclick="loadPlaylists()">Cancel</button>
    </span>`;
  document.getElementById(`pl-input-${i}`).focus();
}

async function savePlaylistRename(i) {
  const p = (window._playlists || [])[i];
  const input = document.getElementById(`pl-input-${i}`);
  if (!p || !input) return;
  const newName = input.value.trim();
  if (!newName || newName === p.name) { loadPlaylists(); return; }
  const res = await fetch('/api/playlists/rename', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: p.id, name: newName }),
  });
  if (res.ok) {
    showToast(`Renamed to "${newName}"`);
    loadPlaylists();
  } else {
    const err = await res.json().catch(() => ({}));
    showToast(err.error || 'Failed to rename', true);
  }
}

async function ensureAlexaDevices(force) {
  if (window._alexaDevices && !force) return window._alexaDevices;
  const res = await fetch('/api/alexa_remote/devices');
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'Failed to load devices');
  window._alexaDevices = data.devices || [];
  return window._alexaDevices;
}

async function ensureDeviceGroups(force) {
  if (window._deviceGroups && !force) return window._deviceGroups;
  const data = await API('/api/device_groups').catch(() => null);
  window._deviceGroups = (data && data.items) || [];
  return window._deviceGroups;
}

function openPlayMenu(i) {
  const p = (window._playlists || [])[i];
  if (p) playOnDevice({ kind: 'playlist', name: p.name, id: p.id });
}

// Generic "Play on a device" picker. opts: {kind, name, id?, shuffle?(bool, default allowed)}
async function playOnDevice(opts) {
  const { kind, name, id } = opts;
  const allowShuffle = opts.shuffle !== false && kind !== 'song';
  let devices;
  try {
    [devices] = await Promise.all([ensureAlexaDevices(), ensureDeviceGroups()]);
  } catch (e) {
    const msg = /not_authenticated/.test(e.message)
      ? 'Alexa session expired — re-run scripts/alexa_login.py'
      : (e.message || 'Failed to load devices');
    return showToast(msg, true);
  }
  if (!devices.length) return showToast('No Alexa devices found', true);

  const deviceOpts = deviceSelectOptions(devices);
  const shuffleRow = allowShuffle
    ? `<label style="display:flex;align-items:center;gap:8px;margin:14px 0">
        <input type="checkbox" id="play-shuffle"> Shuffle
      </label>`
    : '';
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box">
      <h3 style="margin-top:0"><i class="fa fa-play"></i> Play "${escHtml(name)}"</h3>
      <label style="display:block;margin:12px 0 4px;font-size:13px;color:#888">Device</label>
      <select id="play-device" class="settings-input" style="width:100%">${deviceOpts}</select>
      ${shuffleRow}
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:8px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
        <button class="save-btn" id="play-go">Play</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#play-go').onclick = async () => {
    const device = overlay.querySelector('#play-device').value;
    const shuffleEl = overlay.querySelector('#play-shuffle');
    const shuffle = shuffleEl ? shuffleEl.checked : false;
    const btn = overlay.querySelector('#play-go');
    btn.disabled = true; btn.textContent = 'Sending…';
    const res = await fetch('/api/alexa_remote/play', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ kind, id, name, device, shuffle }),
    });
    const data = await res.json().catch(() => ({}));
    overlay.remove();
    if (res.ok) showToast(`Playing "${name}" on ${data.device || 'device'}`);
    else showToast(data.error || 'Failed to start playback', true);
  };
}

function playArtistAt(i) {
  const a = (window._artists || [])[i];
  if (a) playOnDevice({ kind: 'artist', name: a.artist });
}
function playAlbumAt(i) {
  const a = (window._albums || [])[i];
  if (a) playOnDevice({ kind: 'album', name: a.album });
}
function playSongAt(i) {
  const s = (window._songs || [])[i];
  if (s) playOnDevice({ kind: 'song', name: s.title || path2name(s.path) });
}

// ── Artists ──────────────────────────────────────────────────────────────────
let _arPage = 1, _arSearch = '';
register('artists', async () => {
  _arPage = 1; _arSearch = '';
  loading();
  await loadArtists();
});

async function loadArtists() {
  const [data, remote] = await Promise.all([
    API(`/api/artists?page=${_arPage}&limit=50&search=${encodeURIComponent(_arSearch)}`),
    ensureAlexaRemoteStatus(),
  ]);
  const { items = [], total = 0 } = data || {};
  window._artists = items;
  const canPlay = !!(remote && remote.configured);

  const rows = items.map((a, i) => `
    <tr class="clickable" onclick="window.location='#songs/artist/${encodeURIComponent(a.artist)}'">
      <td><i class="fa fa-microphone" style="color:#e99d1a;margin-right:8px"></i>${escHtml(a.artist)}</td>
      <td><span class="badge">${fmtNum(a.album_count)}</span></td>
      <td><span class="badge orange">${fmtNum(a.track_count)}</span></td>
      ${rowActions(canPlay ? actionBtn({ kind: 'play', onclick: `event.stopPropagation();playArtistAt(${i})`, title: 'Play on a device', icon: 'play' }) : '')}
    </tr>`).join('');

  document.getElementById('page-title').textContent = 'Artists';
  document.getElementById('main-content').innerHTML = `
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-microphone"></i> Artists (${fmtNum(total)})</h3>
      </div>
      <div class="card-body" style="padding-bottom:8px">
        <div class="search-bar">
          <input type="text" placeholder="Search artists…" value="${escHtml(_arSearch)}"
            oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_arSearch=this.value;_arPage=1;loadArtists()},350)">
          <span class="result-count">${fmtNum(total)} artists</span>
        </div>
      </div>
      ${rows ? `
      <table class="data-table artists-table">
        <thead><tr><th>Artist</th><th>Albums</th><th>Songs</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>` : `<div class="empty-state"><i class="fa fa-microphone"></i><p>No artists found.</p></div>`}
      ${buildPagination(total, _arPage, 50, (p) => { _arPage = p; loadArtists(); })}
    </div>`;
}

// ── Albums ───────────────────────────────────────────────────────────────────
let _alPage = 1, _alSearch = '', _alArtist = '';
register('albums', async (params) => {
  _alPage = 1;
  _alArtist = params ? decodeURIComponent(params) : '';
  _alSearch = '';
  loading();
  await loadAlbums();
});

async function loadAlbums() {
  const [data, remote] = await Promise.all([
    API(`/api/albums?page=${_alPage}&limit=50&search=${encodeURIComponent(_alSearch)}&artist=${encodeURIComponent(_alArtist)}`),
    ensureAlexaRemoteStatus(),
  ]);
  const { items = [], total = 0 } = data || {};
  window._albums = items;
  const canPlay = !!(remote && remote.configured);

  const rows = items.map((a, i) => `
    <tr class="clickable" onclick="window.location='#songs/album/${encodeURIComponent(a.album)}'">
      <td><i class="fa fa-compact-disc" style="color:#e99d1a;margin-right:8px"></i>${escHtml(a.album)}</td>
      <td class="text-muted">${escHtml(a.artist || '—')}</td>
      <td><span class="badge orange">${fmtNum(a.track_count)}</span></td>
      ${rowActions(canPlay ? actionBtn({ kind: 'play', onclick: `event.stopPropagation();playAlbumAt(${i})`, title: 'Play on a device', icon: 'play' }) : '')}
    </tr>`).join('');

  const backLink = _alArtist ? `<span class="back-link" onclick="window.location='#artists'"><i class="fa fa-arrow-left"></i> Back to Artists</span><br>` : '';

  document.getElementById('page-title').textContent = _alArtist ? `Albums · ${_alArtist}` : 'Albums';
  document.getElementById('main-content').innerHTML = `
    ${backLink}
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-compact-disc"></i> ${_alArtist ? escHtml(_alArtist) + ' — ' : ''}Albums (${fmtNum(total)})</h3>
      </div>
      <div class="card-body" style="padding-bottom:8px">
        <div class="search-bar">
          <input type="text" placeholder="Search albums…" value="${escHtml(_alSearch)}"
            oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_alSearch=this.value;_alPage=1;loadAlbums()},350)">
          <span class="result-count">${fmtNum(total)} albums</span>
        </div>
      </div>
      ${rows ? `
      <table class="data-table albums-table">
        <thead><tr><th>Album</th><th>Artist</th><th>Songs</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>` : `<div class="empty-state"><i class="fa fa-compact-disc"></i><p>No albums found.</p></div>`}
      ${buildPagination(total, _alPage, 50, (p) => { _alPage = p; loadAlbums(); })}
    </div>`;
}

// ── Songs ────────────────────────────────────────────────────────────────────
let _soPage = 1, _soSearch = '', _soArtist = '', _soAlbum = '';
register('songs', async (params) => {
  _soPage = 1; _soSearch = '';
  _soArtist = ''; _soAlbum = '';

  if (params) {
    const [type, value] = params.split('/');
    if (type === 'artist') _soArtist = decodeURIComponent(value || '');
    if (type === 'album') _soAlbum = decodeURIComponent(value || '');
  }

  loading();
  await loadSongs();
});

async function loadSongs() {
  const [data, remote] = await Promise.all([
    API(`/api/songs?page=${_soPage}&limit=100&search=${encodeURIComponent(_soSearch)}&artist=${encodeURIComponent(_soArtist)}&album=${encodeURIComponent(_soAlbum)}`),
    ensureAlexaRemoteStatus(),
  ]);
  const { items = [], total = 0 } = data || {};
  window._songs = items;
  const canPlay = !!(remote && remote.configured);

  const rows = items.map((s, i) => `
    <tr>
      <td class="text-muted" style="width:40px;text-align:right">${s.track_number || ''}</td>
      <td>${escHtml(s.title || path2name(s.path))}</td>
      <td class="text-muted">${escHtml(s.artist || '—')}</td>
      <td class="text-muted">${escHtml(s.album || '—')}</td>
      <td class="text-muted">${escHtml(s.genre || '—')}</td>
      <td class="text-muted">${s.year || '—'}</td>
      <td class="text-muted">${fmtDuration(s.duration_seconds)}</td>
      ${rowActions(canPlay ? actionBtn({ kind: 'play', onclick: `playSongAt(${i})`, title: 'Play on a device', icon: 'play' }) : '')}
    </tr>`).join('');

  let backLink = '';
  if (_soAlbum) backLink = `<span class="back-link" onclick="window.location='#albums'"><i class="fa fa-arrow-left"></i> Back to Albums</span><br>`;
  else if (_soArtist) backLink = `<span class="back-link" onclick="window.location='#artists'"><i class="fa fa-arrow-left"></i> Back to Artists</span><br>`;

  let pageTitle = 'Songs';
  if (_soAlbum) pageTitle = `Songs · ${_soAlbum}`;
  else if (_soArtist) pageTitle = `Songs · ${_soArtist}`;
  document.getElementById('page-title').textContent = pageTitle;

  document.getElementById('main-content').innerHTML = `
    ${backLink}
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-music"></i> ${_soAlbum ? escHtml(_soAlbum) + ' — ' : _soArtist ? escHtml(_soArtist) + ' — ' : ''}Songs (${fmtNum(total)})</h3>
      </div>
      <div class="card-body" style="padding-bottom:8px">
        <div class="search-bar">
          <input type="text" placeholder="Search songs, artists, albums…" value="${escHtml(_soSearch)}"
            oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_soSearch=this.value;_soPage=1;loadSongs()},350)">
          <span class="result-count">${fmtNum(total)} tracks</span>
        </div>
      </div>
      ${rows ? `
      <table class="data-table songs-table">
        <thead><tr><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Genre</th><th>Year</th><th>Duration</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>` : `<div class="empty-state"><i class="fa fa-music"></i><p>No songs found.</p></div>`}
      ${buildPagination(total, _soPage, 100, (p) => { _soPage = p; loadSongs(); })}
    </div>`;
}

function path2name(p) {
  if (!p) return '—';
  return p.split('/').pop().replace(/\.[^.]+$/, '');
}

// ── Watch Folders ────────────────────────────────────────────────────────────
register('watchfolders', async () => {
  loading();
  const folders = await API('/api/watchfolders') || [];

  const cards = folders.map(f => {
    const statusClass = (f.status || '').toLowerCase() === 'scanning' ? 'scanning'
      : (f.status || '').toLowerCase() === 'queued' ? 'queued'
      : (f.status || '').toLowerCase() === 'done' ? 'done' : 'gray';

    return `
    <div class="folder-card">
      <div class="folder-icon"><i class="fa fa-folder-open"></i></div>
      <div class="folder-info">
        ${f.label ? `<span class="folder-label">${escHtml(f.label)}</span>` : ''}
        <div class="folder-path">${escHtml(f.path)}</div>
        <div class="folder-meta">
          ${f.identifiedFiles > 0 ? `<span><i class="fa fa-music"></i> ${fmtNum(f.identifiedFiles)} tracks</span>` : ''}
          ${f.playlists > 0 ? `<span><i class="fa fa-list"></i> ${fmtNum(f.playlists)} playlists</span>` : ''}
          ${f.identifiedFiles === 0 && f.playlists === 0 ? `<span style="color:#999"><i class="fa fa-music"></i> 0 identified</span>` : ''}
          <span><i class="fa fa-layer-group"></i> ${escHtml(f.type)}</span>
          ${f.errors > 0 ? `<span style="color:#e44"><i class="fa fa-triangle-exclamation"></i> ${f.errors} errors</span>` : ''}
        </div>
      </div>
      <div><span class="status-dot ${statusClass}">${escHtml(f.status)}</span></div>
    </div>`;
  }).join('');

  renderPage('Watch Folders', `
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-folder-open"></i> Watch Folders</h3>
      </div>
      <div class="card-body">
        ${cards || '<div class="empty-state"><i class="fa fa-folder"></i><p>No watch folders configured.</p></div>'}
      </div>
    </div>`);
});

// ── Devices ──────────────────────────────────────────────────────────────────
register('devices', async () => {
  loading();
  const [devices, mc, groups, remote] = await Promise.all([
    API('/api/devices'),
    API('/api/devices/merge_candidates'),
    API('/api/device_groups'),
    ensureAlexaRemoteStatus(),
  ]);
  window._devices = devices || [];
  window._mergeCandidates = (mc && mc.candidates) || [];
  window._deviceGroups = (groups && groups.items) || [];
  window._devicesRemoteConfigured = !!(remote && remote.configured);
  if (window._devicesRemoteConfigured) await ensureAlexaDevices().catch(() => []);
  renderDevices();
});

function renderDevices() {
  const devices = window._devices || [];
  const rows = devices.map((d, i) => `
    <li id="dev-row-${i}">
      <span class="device-icon-col"><i class="fa fa-headphones"></i></span>
      <span class="device-name-text">${escHtml(d.name)}</span>
      <span class="device-last-seen" style="font-size:11px;color:#9aa;margin-left:8px">${d.lastSeen ? 'Last seen ' + fmtDateTime(new Date(d.lastSeen * 1000).toISOString()) : ''}</span>
      <div class="row-actions">
        ${actionBtn({ kind: 'edit', onclick: `startEditDevice(${i})`, title: 'Edit name', icon: 'pen' })}
        ${actionBtn({ kind: 'merge', onclick: `startMergeDevice(${i})`, title: 'Merge into another device', icon: 'code-branch' })}
        ${actionBtn({ kind: 'delete', onclick: `deleteDevice(${i})`, title: 'Remove device', icon: 'trash' })}
      </div>
    </li>`).join('');

  const candidates = window._mergeCandidates || [];
  const candHtml = candidates.length ? `
    <div class="card" style="margin-bottom:16px;border-left:3px solid #e6a14e">
      <div class="card-header">
        <h3 style="color:#9a6520"><i class="fa fa-triangle-exclamation"></i> Likely Duplicates (${candidates.length})</h3>
      </div>
      <div class="card-body" style="padding:8px 16px 14px">
        <p class="hint" style="margin:0 0 8px">Alexa sometimes rotates a device's id. These auto-named entries look like rotated copies of an existing device. Merge to fold past streams in and route future events automatically.</p>
        <ul class="device-list" style="margin:0">
          ${candidates.map(c => `
            <li>
              <span class="device-icon-col"><i class="fa fa-code-branch"></i></span>
              <span class="device-name-text">
                <b>${escHtml(c.sourceName)}</b>
                <span style="font-size:11px;color:#aab">(${c.sourceStreams} stream${c.sourceStreams===1?'':'s'})</span>
                <i class="fa fa-arrow-right" style="margin:0 6px;color:#aab"></i>
                <b>${escHtml(c.targetName)}</b>
                <span style="font-size:11px;color:#aab;margin-left:8px">
                  ${c.fingerprintMatch ? '<i class="fa fa-check-circle" style="color:#2eaa5a"></i> capability match' : ''}
                  · gap ${c.gapHours}h · score ${c.score}
                </span>
              </span>
              <button class="save-btn" onclick="acceptMergeCandidate('${escHtml(c.sourceId)}','${escHtml(c.targetId)}','${escHtml(c.targetName)}')">Merge</button>
              <button class="cancel-btn" onclick="dismissMergeCandidate('${escHtml(c.sourceId)}')">Not a duplicate</button>
            </li>`).join('')}
        </ul>
      </div>
    </div>` : '';

  renderPage('Alexa Devices', `
    <div class="page-desc">
      Devices auto-register the first time they stream music via Bock Media. Rename them with the pencil icon, or remove them with the trash icon. If Alexa rotated the deviceId for a device you already named (a duplicate appears), use the merge icon to fold it into the original — history and analytics will follow.
    </div>
    ${candHtml}
    ${renderDeviceGroupsCard()}
    ${renderSpeakersCard()}
    <div class="card">
      <div class="card-header" style="display:flex;align-items:center;justify-content:space-between">
        <h3><i class="fa fa-headphones"></i> Alexa Devices (${devices.length})</h3>
        ${window._devicesRemoteConfigured
          ? `<button id="identify-btn" class="btn-sm btn-primary" onclick="startIdentify()"><i class="fa fa-volume-high"></i> Identify devices</button>`
          : ''}
      </div>
      <div id="identify-status" style="display:none;padding:8px 16px;font-size:12px;color:#556;border-bottom:1px solid #eef2f8"></div>
      ${rows
        ? `<ul class="device-list">${rows}</ul>`
        : `<div class="empty-state"><i class="fa fa-headphones"></i><p>No devices yet — start streaming from an Echo to register it.</p></div>`}
    </div>`);
}

function renderSpeakersCard() {
  if (!window._devicesRemoteConfigured) return '';
  const speakers = (window._alexaDevices || []).filter(s => s.serial);
  if (!speakers.length) return '';
  const rows = speakers.map(s => `
    <li>
      <span class="device-icon-col"><i class="fa fa-volume-high"></i></span>
      <span class="device-name-text">${escHtml(s.name)}${s.online ? '' : ' <span style="font-size:11px;color:#c66">(offline)</span>'}</span>
      <div class="row-actions">
        ${actionBtn({ kind: 'play', onclick: `testDevice('${escHtml(s.serial)}', ${JSON.stringify(s.name)})`, title: 'Play a short test clip here', icon: 'play' })}
      </div>
    </li>`).join('');
  return `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-volume-high"></i> Speakers (${speakers.length})</h3></div>
      <div class="card-body" style="padding:8px 16px 4px">
        <p class="hint" style="margin:0 0 8px">Press <i class="fa fa-play"></i> to play a short clip on a speaker so you can hear which room it is. It auto-names the matching device in the list below.</p>
        <ul class="device-list" style="margin:0">${rows}</ul>
      </div>
    </div>`;
}

async function testDevice(serial, name) {
  const res = await fetch('/api/devices/test', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ serial, name }),
  });
  const d = await res.json().catch(() => ({}));
  if (res.ok && d.ok) {
    showToast(`Testing ${d.device || name}…`);
    setTimeout(async () => {
      window._devices = await API('/api/devices') || [];
      renderDevices();
    }, 11000);
  } else {
    showToast('Test failed: ' + (d.error || res.status), true);
  }
}

function renderDeviceGroupsCard() {
  const groups = window._deviceGroups || [];
  const configured = window._devicesRemoteConfigured;
  const headerBtn = configured
    ? `<button class="btn-sm btn-primary" onclick="openGroupEditor()"><i class="fa fa-plus"></i> New group</button>`
    : '';
  if (!configured) {
    return `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-layer-group"></i> Device Groups</h3></div>
      <div class="card-body">
        <p class="hint" style="margin:0">Alexa remote control is not configured. Add <code>alexaRemote</code> credentials in <code>config.json</code> and run <code>scripts/alexa_login.py --proxy</code> to create groups.</p>
      </div>
    </div>`;
  }
  const rows = groups.map(g => {
    const memberNames = (g.members || []).map(m => escHtml(m.name || m.serial)).join(', ');
    return `
    <li>
      <span class="device-icon-col"><i class="fa fa-layer-group"></i></span>
      <span class="device-name-text">
        <b>${escHtml(g.name)}</b>
        <span style="font-size:11px;color:#9aa;margin-left:6px">${(g.members || []).length} device${(g.members || []).length === 1 ? '' : 's'}</span>
        <div class="auto-list-meta">${memberNames || '<span style="color:#c66">no devices</span>'}</div>
      </span>
      <div class="row-actions">
        ${actionBtn({ kind: 'edit', onclick: `openGroupEditor('${escHtml(g.id)}')`, title: 'Edit group', icon: 'pen' })}
        ${actionBtn({ kind: 'delete', onclick: `deleteGroup('${escHtml(g.id)}')`, title: 'Delete group', icon: 'trash' })}
      </div>
    </li>`;
  }).join('');
  return `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header" style="display:flex;align-items:center;justify-content:space-between">
        <h3><i class="fa fa-layer-group"></i> Device Groups (${groups.length})</h3>
        ${headerBtn}
      </div>
      <div class="card-body" style="padding:8px 16px 14px">
        <p class="hint" style="margin:0 0 8px">Group several Echoes so you can play a playlist or schedule an automation on all of them at once. Groups appear in every device picker.</p>
        ${rows ? `<ul class="device-list" style="margin:0">${rows}</ul>` : `<div class="empty-state" style="padding:16px"><i class="fa fa-layer-group"></i><p>No groups yet.</p></div>`}
      </div>
    </div>`;
}

async function openGroupEditor(groupId) {
  let devices;
  try {
    devices = await ensureAlexaDevices();
  } catch (e) {
    return showToast(e.message || 'Failed to load devices', true);
  }
  if (!devices.length) return showToast('No Alexa devices found', true);
  const group = groupId ? (window._deviceGroups || []).find(g => g.id === groupId) : null;
  const selected = new Set((group ? group.members : []).map(m => m.serial));
  const checkboxes = devices.map(d => `
    <label class="group-dev-option">
      <input type="checkbox" value="${escHtml(d.serial)}" data-name="${escHtml(d.name)}" ${selected.has(d.serial) ? 'checked' : ''}>
      <span>${escHtml(d.name)}${d.online ? '' : ' <span style="color:#aab;font-size:11px">(offline)</span>'}</span>
    </label>`).join('');
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box">
      <h3 style="margin-top:0"><i class="fa fa-layer-group"></i> ${group ? 'Edit group' : 'New group'}</h3>
      <label style="display:block;margin:12px 0 4px;font-size:13px;color:#888">Group name</label>
      <input id="group-name" class="settings-input" style="width:100%" placeholder="Up and Downstairs" value="${escHtml(group ? group.name : '')}">
      <label style="display:block;margin:14px 0 4px;font-size:13px;color:#888">Devices</label>
      <div class="group-dev-list">${checkboxes}</div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
        <button class="save-btn" id="group-save">${group ? 'Update' : 'Create'}</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#group-name').focus();
  overlay.querySelector('#group-save').onclick = () => saveGroup(overlay, group ? group.id : null);
}

async function saveGroup(overlay, groupId) {
  const name = overlay.querySelector('#group-name').value.trim();
  if (!name) return showToast('Enter a group name', true);
  const members = [...overlay.querySelectorAll('.group-dev-list input:checked')]
    .map(cb => ({ serial: cb.value, name: cb.dataset.name || cb.value }));
  if (!members.length) return showToast('Select at least one device', true);
  const btn = overlay.querySelector('#group-save');
  btn.disabled = true; btn.textContent = 'Saving…';
  const url = groupId ? `/api/device_groups/${encodeURIComponent(groupId)}` : '/api/device_groups';
  const res = await fetch(url, {
    method: groupId ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, members }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    btn.disabled = false; btn.textContent = groupId ? 'Update' : 'Create';
    return showToast(data.error || 'Failed to save group', true);
  }
  overlay.remove();
  showToast(groupId ? 'Group updated' : 'Group created');
  const groups = await API('/api/device_groups');
  window._deviceGroups = (groups && groups.items) || [];
  renderDevices();
}

async function deleteGroup(id) {
  if (!confirm('Delete this group? Devices themselves are not affected.')) return;
  const res = await fetch(`/api/device_groups/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) return showToast('Failed to delete group', true);
  window._deviceGroups = (window._deviceGroups || []).filter(g => g.id !== id);
  showToast('Group deleted');
  renderDevices();
}

// Build <option>s for a device <select>, with a Groups optgroup on top.
function deviceSelectOptions(devices, { includeGroups = true, selectedValue = '' } = {}) {
  const groups = window._deviceGroups || [];
  let html = '';
  if (includeGroups && groups.length) {
    html += `<optgroup label="Groups">` + groups.map(g => {
      const val = `group:${g.id}`;
      return `<option value="${escHtml(val)}" data-name="${escHtml(g.name)}" ${val === selectedValue ? 'selected' : ''}>${escHtml(g.name)} (${(g.members || []).length})</option>`;
    }).join('') + `</optgroup>`;
  }
  const devOpts = devices.map(d =>
    `<option value="${escHtml(d.serial)}" data-name="${escHtml(d.name)}" ${d.serial === selectedValue ? 'selected' : ''}>${escHtml(d.name)}${d.online ? '' : ' (offline)'}</option>`
  ).join('');
  html += includeGroups && groups.length ? `<optgroup label="Devices">${devOpts}</optgroup>` : devOpts;
  return html;
}

async function acceptMergeCandidate(sourceId, targetId, targetName) {
  if (!confirm(`Merge into "${targetName}"?\n\nAll past streams from this rotated id will be re-attributed, and any future events will route there automatically.`)) return;
  const res = await fetch(`/api/devices/${encodeURIComponent(sourceId)}/merge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ target: targetId }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    alert('Merge failed: ' + (body.error || res.status));
    return;
  }
  const result = await res.json();
  showToast(`Merged into ${targetName} — ${result.historyRowsRewritten || 0} history rows updated`);
  const [devices, mc] = await Promise.all([
    API('/api/devices'),
    API('/api/devices/merge_candidates'),
  ]);
  window._devices = devices || [];
  window._mergeCandidates = (mc && mc.candidates) || [];
  renderDevices();
}

async function dismissMergeCandidate(sourceId) {
  await fetch(`/api/devices/${encodeURIComponent(sourceId)}/dismiss_candidate`, { method: 'POST' });
  window._mergeCandidates = (window._mergeCandidates || []).filter(c => c.sourceId !== sourceId);
  renderDevices();
}

let _identifyPoll = null;
async function startIdentify() {
  if (!confirm('Play a short test clip on each Echo, one at a time?\n\nEach plays for a few seconds then stops. Devices get auto-named as the test moves room to room. Listen to confirm which room is which.')) return;
  const btn = document.getElementById('identify-btn');
  if (btn) { btn.disabled = true; }
  const res = await fetch('/api/devices/identify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
  if (!res.ok) {
    const b = await res.json().catch(() => ({}));
    showToast('Identify failed: ' + (b.error || res.status), true);
    if (btn) btn.disabled = false;
    return;
  }
  const info = await res.json();
  showToast(`Identifying ${info.total} devices on "${info.playlist}" (~${info.etaSeconds}s)`);
  if (_identifyPoll) clearInterval(_identifyPoll);
  _identifyPoll = setInterval(pollIdentify, 1500);
  pollIdentify();
}

async function pollIdentify() {
  const el = document.getElementById('identify-status');
  const st = await API('/api/devices/identify/status');
  if (!st) return;
  if (el) {
    el.style.display = '';
    const cur = st.running ? `▶ Playing on <b>${escHtml(st.current || '…')}</b>` : '✓ Done';
    el.innerHTML = `${cur} — ${st.done}/${st.total}${st.errors && st.errors.length ? ` · ${st.errors.length} skipped` : ''}`;
  }
  if (!st.running) {
    if (_identifyPoll) { clearInterval(_identifyPoll); _identifyPoll = null; }
    const devices = await API('/api/devices') || [];
    window._devices = devices;
    const mc = await API('/api/devices/merge_candidates');
    window._mergeCandidates = (mc && mc.candidates) || [];
    renderDevices();
    showToast(`Identify complete — ${st.done} devices`);
  }
}

async function deleteDevice(i) {
  const d = (window._devices || [])[i];
  if (!d) return;
  if (!confirm(`Remove "${d.name}" from the device list?`)) return;
  const res = await fetch(`/api/devices/${encodeURIComponent(d.deviceId)}`, { method: 'DELETE' });
  if (res.ok) {
    window._devices.splice(i, 1);
    renderDevices();
  } else {
    alert('Failed to remove device.');
  }
}

function startEditDevice(i) {
  const d = (window._devices || [])[i];
  if (!d) return;
  const row = document.getElementById(`dev-row-${i}`);
  if (!row) return;
  row.innerHTML = `
    <span class="device-icon-col"><i class="fa fa-headphones"></i></span>
    <div class="edit-row">
      <input id="dev-input-${i}" type="text" value="${escHtml(d.name)}" class="settings-input">
      <button class="save-btn" onclick="saveDevice(${i})">Save</button>
      <button class="cancel-btn" onclick="renderDevices()">Cancel</button>
    </div>`;
  document.getElementById(`dev-input-${i}`).focus();
}

async function saveDevice(i) {
  const d = (window._devices || [])[i];
  const input = document.getElementById(`dev-input-${i}`);
  if (!d || !input) return;
  const newName = input.value.trim();
  if (!newName) return;
  const res = await fetch(`/api/devices/${encodeURIComponent(d.deviceId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: newName }),
  });
  if (res.ok) {
    window._devices[i].name = newName;
    renderDevices();
  } else {
    alert('Failed to save device name.');
  }
}

function startMergeDevice(i) {
  const devices = window._devices || [];
  const src = devices[i];
  if (!src) return;
  const others = devices.filter((_, j) => j !== i);
  if (!others.length) {
    alert('No other devices to merge into.');
    return;
  }
  const row = document.getElementById(`dev-row-${i}`);
  if (!row) return;
  const opts = others.map(o => `<option value="${escHtml(o.deviceId)}">${escHtml(o.name)}</option>`).join('');
  row.innerHTML = `
    <span class="device-icon-col"><i class="fa fa-code-branch"></i></span>
    <div class="edit-row" style="gap:6px">
      <span style="font-size:12px;color:#556">Merge <b>${escHtml(src.name)}</b> into:</span>
      <select id="dev-merge-${i}" class="settings-input" style="min-width:160px">${opts}</select>
      <button class="save-btn" onclick="confirmMergeDevice(${i})">Merge</button>
      <button class="cancel-btn" onclick="renderDevices()">Cancel</button>
    </div>`;
}

async function confirmMergeDevice(i) {
  const src = (window._devices || [])[i];
  const sel = document.getElementById(`dev-merge-${i}`);
  if (!src || !sel || !sel.value) return;
  const targetId = sel.value;
  const targetName = (sel.options[sel.selectedIndex] || {}).text || '';
  if (!confirm(`Merge "${src.name}" into "${targetName}"?\n\nAll past streams from "${src.name}" will be reattributed to "${targetName}", and any future events from this id will route there automatically.`)) {
    renderDevices();
    return;
  }
  const res = await fetch(`/api/devices/${encodeURIComponent(src.deviceId)}/merge`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ target: targetId }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    alert('Merge failed: ' + (body.error || res.status));
    renderDevices();
    return;
  }
  const result = await res.json();
  showToast(`Merged into ${targetName} — ${result.historyRowsRewritten || 0} history rows updated`);
  const refreshed = await API('/api/devices') || [];
  window._devices = refreshed;
  renderDevices();
}

// ── Automation ───────────────────────────────────────────────────────────────
const AUTO_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const AUTO_DAY_PRESETS = {
  daily: [0, 1, 2, 3, 4, 5, 6],
  weekdays: [0, 1, 2, 3, 4],
  weekends: [5, 6],
};

function autoDaysLabel(days) {
  const d = days || [];
  if (d.length === 7) return 'Daily';
  if (d.length === 5 && d.every(x => x < 5)) return 'Weekdays';
  if (d.length === 2 && d.includes(5) && d.includes(6)) return 'Weekends';
  return d.map(i => AUTO_DAYS[i] || '?').join(', ');
}

function autoPresetFromDays(days) {
  const key = JSON.stringify([...(days || [])].sort((a, b) => a - b));
  for (const [name, preset] of Object.entries(AUTO_DAY_PRESETS)) {
    if (key === JSON.stringify([...preset].sort((a, b) => a - b))) return name;
  }
  return 'custom';
}

register('automation', async () => {
  loading();
  await loadAutomation();
});

async function loadAutomation() {
  const [data, remote, alexaDevs] = await Promise.all([
    API('/api/automations'),
    ensureAlexaRemoteStatus(),
    ensureAlexaRemoteStatus().then(r => r && r.configured ? ensureAlexaDevices().catch(() => []) : []),
    ensureDeviceGroups().catch(() => []),
  ]);
  window._automations = (data && data.items) || [];
  window._autoAlexaDevices = alexaDevs || [];
  renderAutomation(remote);
}

function renderAutomation(remote) {
  const canPlay = !!(remote && remote.configured);
  const items = window._automations || [];
  const devs = window._autoAlexaDevices || [];
  const editing = window._autoEditing;

  const devOpts = deviceSelectOptions(devs, { selectedValue: editing ? (editing.device || '') : '' });

  const preset = editing ? autoPresetFromDays(editing.days) : 'weekdays';
  const selectedDays = editing ? (editing.days || []) : AUTO_DAY_PRESETS.weekdays;
  const dayChips = AUTO_DAYS.map((label, i) => {
    const on = selectedDays.includes(i);
    return `<label class="auto-day-chip${on ? ' checked' : ''}">
      <input type="checkbox" value="${i}" ${on ? 'checked' : ''} onchange="autoSyncDayPreset()"> ${label}
    </label>`;
  }).join('');

  const formTitle = editing ? 'Edit automation' : 'New automation';
  const formHtml = canPlay ? `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-plus-circle"></i> ${formTitle}</h3></div>
      <div class="card-body">
        <div class="auto-form-grid">
          <div class="auto-field">
            <label>Label (optional)</label>
            <input type="text" id="auto-name" class="settings-input" placeholder="Morning music" value="${escHtml(editing ? (editing.name || '') : '')}">
          </div>
          <div class="auto-field auto-pl-wrap">
            <label>Playlist</label>
            <input type="text" id="auto-pl-search" class="settings-input" placeholder="Search playlists…" autocomplete="off"
              value="${escHtml(editing ? (editing.playlistName || '') : '')}"
              oninput="autoSearchPlaylists(this.value)"
              onfocus="autoSearchPlaylists(this.value)">
            <div id="auto-pl-results" class="auto-pl-results"></div>
            <input type="hidden" id="auto-pl-id" value="${escHtml(editing ? (editing.playlistId || '') : '')}">
            <input type="hidden" id="auto-pl-name" value="${escHtml(editing ? (editing.playlistName || '') : '')}">
          </div>
          <div class="auto-field">
            <label>Device</label>
            <select id="auto-device" class="settings-input">
              <option value="">Select a device…</option>
              ${devOpts}
            </select>
          </div>
          <div class="auto-field">
            <label>Time</label>
            <input type="time" id="auto-time" class="settings-input" value="${escHtml(editing ? (editing.time || '08:00') : '08:00')}">
          </div>
          <div class="auto-field" style="grid-column:1/-1">
            <label>Repeat</label>
            <div class="radio-group" style="margin-bottom:8px">
              ${['daily', 'weekdays', 'weekends', 'custom'].map(p => `
                <label class="radio-option">
                  <input type="radio" name="auto-preset" value="${p}" ${preset === p ? 'checked' : ''} onchange="autoApplyDayPreset('${p}')">
                  ${p.charAt(0).toUpperCase() + p.slice(1)}
                </label>`).join('')}
            </div>
            <div id="auto-day-row" class="auto-day-row" style="${preset === 'custom' ? '' : 'display:none'}">${dayChips}</div>
          </div>
          <div class="auto-field">
            <label style="display:flex;align-items:center;gap:8px;margin-top:20px">
              <input type="checkbox" id="auto-shuffle" ${editing && editing.shuffle ? 'checked' : ''}> Shuffle
            </label>
          </div>
          <div class="auto-field">
            <label style="display:flex;align-items:center;gap:8px;margin-top:20px">
              <input type="checkbox" id="auto-enabled" ${!editing || editing.enabled !== false ? 'checked' : ''}> Enabled
            </label>
          </div>
        </div>
        <div style="display:flex;gap:8px;margin-top:16px">
          <button class="btn-sm btn-primary" onclick="saveAutomation()">${editing ? 'Update' : 'Add automation'}</button>
          ${editing ? `<button class="btn-sm btn-default" onclick="cancelEditAutomation()">Cancel</button>` : ''}
        </div>
      </div>
    </div>` : `
    <div class="card" style="margin-bottom:16px;border-left:3px solid #e6a14e">
      <div class="card-body">
        <p class="hint" style="margin:0">Alexa remote control is not configured. Add <code>alexaRemote</code> credentials in <code>config.json</code> and run <code>scripts/alexa_login.py --proxy</code> to enable scheduled playback.</p>
      </div>
    </div>`;

  const rows = items.map((a, i) => {
    const status = a.lastRunStatus
      ? `<span class="${/^ok/.test(a.lastRunStatus) ? 'auto-status-ok' : 'auto-status-err'}">${escHtml(a.lastRunStatus)}</span>`
      : '—';
    const lastRun = a.lastRunAt ? fmtDateTime(new Date(a.lastRunAt * 1000).toISOString()) : '—';
    return `
    <tr class="${a.enabled === false ? 'text-muted' : ''}">
      <td>
        <strong>${escHtml(a.name)}</strong>
        <div class="auto-list-meta">${escHtml(a.playlistName)} · ${escHtml(a.deviceName || a.device)}</div>
      </td>
      <td>${escHtml(a.time)}</td>
      <td>${escHtml(autoDaysLabel(a.days))}${a.shuffle ? ' · shuffle' : ''}</td>
      <td>${lastRun}</td>
      <td>${status}</td>
      ${rowActions(
        canPlay ? actionBtn({ kind: 'run', onclick: `runAutomationNow('${escHtml(a.id)}')`, title: 'Run now', icon: 'bolt' }) : '',
        actionBtn({
          kind: 'muted',
          onclick: `toggleAutomation('${escHtml(a.id)}', ${a.enabled !== false})`,
          title: a.enabled !== false ? 'Disable' : 'Enable',
          icon: a.enabled !== false ? 'pause' : 'play',
        }),
        actionBtn({ kind: 'edit', onclick: `editAutomation(${i})`, title: 'Edit automation', icon: 'pen' }),
        actionBtn({ kind: 'delete', onclick: `deleteAutomation('${escHtml(a.id)}')`, title: 'Delete automation', icon: 'trash' }),
      )}
    </tr>`;
  }).join('');

  document.getElementById('page-title').textContent = 'Automation';
  document.getElementById('main-content').innerHTML = `
    <div class="page-desc">
      Schedule playlists to start on a specific Echo at a set time. Uses the same remote-control path as the Playlists page (<code>start</code>/<code>mix</code> commands via alexapy).
    </div>
    ${formHtml}
    <div class="card">
      <div class="card-header"><h3><i class="fa fa-clock"></i> Scheduled automations (${items.length})</h3></div>
      ${rows ? `
      <table class="data-table automation-table">
        <thead><tr><th>Automation</th><th>Time</th><th>Schedule</th><th>Last run</th><th>Status</th><th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table>` : `<div class="empty-state"><i class="fa fa-clock"></i><p>No automations yet.</p></div>`}
    </div>`;

  if (editing && editing.device) {
    const sel = document.getElementById('auto-device');
    if (sel) sel.value = editing.device;
  }
}

let _autoPlTimer = null;
async function autoSearchPlaylists(q) {
  clearTimeout(_autoPlTimer);
  const box = document.getElementById('auto-pl-results');
  if (!box) return;
  _autoPlTimer = setTimeout(async () => {
    const query = (q || '').trim();
    if (query.length < 1) { box.innerHTML = ''; return; }
    const data = await API(`/api/playlists?search=${encodeURIComponent(query)}&limit=25&page=1`) || {};
    const items = data.items || [];
    box.innerHTML = items.map((p, idx) => `
      <div class="auto-pl-item" onclick="autoPickPlaylistIdx(${idx})">
        ${escHtml(p.name)}<small>${fmtNum(p.trackCount)} tracks</small>
      </div>`).join('') || `<div class="auto-pl-item" style="color:#889;cursor:default">No playlists found</div>`;
    window._autoPlResults = items;
  }, 250);
}

function autoPickPlaylistIdx(i) {
  const p = (window._autoPlResults || [])[i];
  if (!p) return;
  autoPickPlaylist(p.id, p.name);
}

function autoPickPlaylist(id, name) {
  const search = document.getElementById('auto-pl-search');
  const idEl = document.getElementById('auto-pl-id');
  const nameEl = document.getElementById('auto-pl-name');
  const box = document.getElementById('auto-pl-results');
  if (search) search.value = name;
  if (idEl) idEl.value = id;
  if (nameEl) nameEl.value = name;
  if (box) box.innerHTML = '';
}

function autoApplyDayPreset(preset) {
  const row = document.getElementById('auto-day-row');
  if (row) row.style.display = preset === 'custom' ? '' : 'none';
  const days = AUTO_DAY_PRESETS[preset];
  if (!days) return;
  row.querySelectorAll('input[type=checkbox]').forEach(cb => {
    cb.checked = days.includes(parseInt(cb.value, 10));
    cb.parentElement.classList.toggle('checked', cb.checked);
  });
}

function autoSyncDayPreset() {
  const row = document.getElementById('auto-day-row');
  if (!row) return;
  row.querySelectorAll('.auto-day-chip').forEach(chip => {
    const cb = chip.querySelector('input');
    chip.classList.toggle('checked', cb && cb.checked);
  });
  const days = [...row.querySelectorAll('input:checked')].map(cb => parseInt(cb.value, 10)).sort((a, b) => a - b);
  const preset = autoPresetFromDays(days);
  const radio = document.querySelector(`input[name=auto-preset][value="${preset}"]`);
  if (radio) radio.checked = true;
  row.style.display = preset === 'custom' ? '' : 'none';
}

function autoCollectDays() {
  const preset = (document.querySelector('input[name=auto-preset]:checked') || {}).value || 'weekdays';
  if (preset !== 'custom') return AUTO_DAY_PRESETS[preset] || AUTO_DAY_PRESETS.weekdays;
  const row = document.getElementById('auto-day-row');
  return [...(row ? row.querySelectorAll('input:checked') : [])].map(cb => parseInt(cb.value, 10));
}

async function saveAutomation() {
  const editing = window._autoEditing;
  const deviceSel = document.getElementById('auto-device');
  const device = deviceSel ? deviceSel.value : '';
  const deviceName = deviceSel && deviceSel.selectedIndex >= 0
    ? (deviceSel.options[deviceSel.selectedIndex].dataset.name || deviceSel.options[deviceSel.selectedIndex].text)
    : '';
  const playlistName = (document.getElementById('auto-pl-name') || {}).value
    || (document.getElementById('auto-pl-search') || {}).value || '';
  const body = {
    name: (document.getElementById('auto-name') || {}).value || '',
    playlistId: (document.getElementById('auto-pl-id') || {}).value || '',
    playlistName: playlistName.trim(),
    device,
    deviceName: (deviceName || '').replace(/ \(offline\)$/, ''),
    time: (document.getElementById('auto-time') || {}).value || '',
    days: autoCollectDays(),
    shuffle: !!(document.getElementById('auto-shuffle') || {}).checked,
    enabled: !!(document.getElementById('auto-enabled') || {}).checked,
  };
  if (!body.playlistName) return showToast('Select a playlist', true);
  if (!body.device) return showToast('Select a device', true);
  if (!body.days.length) return showToast('Select at least one day', true);

  const url = editing ? `/api/automations/${encodeURIComponent(editing.id)}` : '/api/automations';
  const res = await fetch(url, {
    method: editing ? 'PUT' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) return showToast(data.error || 'Failed to save', true);
  window._autoEditing = null;
  showToast(editing ? 'Automation updated' : 'Automation created');
  await loadAutomation();
}

function editAutomation(i) {
  window._autoEditing = (window._automations || [])[i] || null;
  loadAutomation();
}

function cancelEditAutomation() {
  window._autoEditing = null;
  loadAutomation();
}

async function deleteAutomation(id) {
  if (!confirm('Delete this automation?')) return;
  const res = await fetch(`/api/automations/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    return showToast(data.error || 'Delete failed', true);
  }
  showToast('Automation deleted');
  await loadAutomation();
}

async function toggleAutomation(id, currentlyEnabled) {
  const auto = (window._automations || []).find(a => a.id === id);
  if (!auto) return;
  const res = await fetch(`/api/automations/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...auto, enabled: !currentlyEnabled }),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    return showToast(data.error || 'Update failed', true);
  }
  await loadAutomation();
}

async function runAutomationNow(id) {
  const res = await fetch(`/api/automations/${encodeURIComponent(id)}/run`, { method: 'POST' });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) return showToast(data.error || 'Run failed', true);
  showToast(`Started on ${data.device || 'device'}`);
  await loadAutomation();
}

// ── Settings ─────────────────────────────────────────────────────────────────
register('settings', async () => {
  loading();
  const [s, cfg, ipData] = await Promise.all([
    API('/api/settings') || {},
    API('/api/config'),
    API('/api/localip'),
  ]);
  const settings = s || {};
  const publicUrl = (cfg || {}).publicUrl || '';
  const localIp   = (ipData || {}).ip || '—';

  const chk = (val) => val === 'true' || val === true || val === '1' || val === 1;

  const toggle = (id, label, checked, onchange='') => `
    <div class="toggle-wrap">
      <label class="toggle">
        <input type="checkbox" id="${id}" ${checked ? 'checked' : ''} ${onchange ? `onchange="${onchange}"` : ''}>
        <span class="toggle-slider"></span>
      </label>
      <span class="toggle-label">${label}</span>
    </div>`;

  const requirePw = chk(settings.requirePassword);

  renderPage('Settings', `
    <div class="card">
      <div class="card-header"><h3><i class="fa fa-cog"></i> General</h3></div>
      <div class="card-body">

        <div class="settings-section">
          <h4>Default Playlist</h4>
          <p class="hint">Played automatically when you open Bock Media without specifying a playlist, artist, or track.</p>
          <div class="settings-row">
            <input type="text" id="s-default-pl" class="settings-input wide" value="${escHtml(settings.defaultPlaylist || '')}">
            <button class="btn-sm btn-primary" onclick="saveSetting('defaultPlaylist', document.getElementById('s-default-pl').value)">Set</button>
          </div>
          ${toggle('s-default-pl-shuffle', 'Shuffle default playlist', chk(settings.defaultPlaylistShuffle))}
        </div>

        <div class="settings-section">
          <h4>Additional Audio File Formats — FLAC, WMA, WAV, OGG, AIF</h4>
          <p class="hint">These formats require real-time transcoding through FFmpeg before streaming to Alexa.</p>
          ${toggle('s-flac', 'Enable Transcoding Support', chk(settings.flacSupport))}
          <div class="settings-row" style="margin-top:8px">
            <label style="font-size:12px;color:#667;min-width:140px">FFmpeg Binary Path</label>
            <input type="text" id="s-ffmpeg" class="settings-input wide" value="${escHtml(settings.ffmpegLocation || '')}">
            <button class="btn-sm btn-default" onclick="saveSetting('ffmpegLocation', document.getElementById('s-ffmpeg').value)">Set</button>
          </div>
          <div class="settings-row" style="margin-top:8px">
            <label style="font-size:12px;color:#667;min-width:140px">Transcode Bitrate</label>
            <input type="number" id="s-bitrate" class="settings-input narrow" value="${escHtml(settings.transcodeBitrate || '128')}">
            <span style="font-size:12px;color:#667">kbps</span>
          </div>
        </div>

        <div class="settings-section">
          <h4>Alexa Artwork and Metadata</h4>
          <p class="hint">Enables rich display on Echo Show and Echo Spot devices.</p>
          ${toggle('s-art', 'Send Album Artwork (Echo Show / Spot)', chk(settings.sendAlbumArt))}
          ${toggle('s-meta', 'Send Track Metadata (title, artist)', chk(settings.sendMetadata))}
        </div>

        <div class="settings-section">
          <h4>Watch Folder Scanning</h4>
          <p class="hint">These settings are read by the background scanner service that keeps the music index up to date.</p>
          ${toggle('s-autoscan', 'Enable Watch Folder Autoscan', !chk(settings.suppressAutoScan))}
          ${toggle('s-autoimport', 'Automatically Import Playlists', chk(settings.autoImportPlaylists))}
          <div class="settings-row" style="margin-top:8px">
            <label style="font-size:12px;color:#667;min-width:180px">Ignore Folders Containing</label>
            <input type="text" id="s-ignore" class="settings-input" value="${escHtml(settings.scanIgnoreFiles || '.mmaignore')}" placeholder=".mmaignore">
          </div>
          <div class="settings-row" style="margin-top:8px">
            <button class="btn-sm btn-default" onclick="clearImageCache()"><i class="fa fa-image"></i> Clear Artwork Cache</button>
          </div>
        </div>

        <div class="settings-section">
          <h4>Logging</h4>
          <p class="hint">Writes a rotating log to <code>server.log</code> in the Bock Media directory. Changes take effect on next server restart.</p>
          ${toggle('s-log', 'Enable Logging', chk(settings.verboseLogging))}
        </div>

        <div class="settings-section">
          <h4>Admin Account Password</h4>
          <p class="hint">Protects the web console with HTTP Basic Auth. Username is always <strong>admin</strong>. Does not affect Alexa streaming.</p>
          ${toggle('s-pass', 'Require password for web console', requirePw, 'togglePasswordField(this.checked)')}
          <div id="s-pass-fields" style="${requirePw ? '' : 'display:none'}; margin-top:10px">
            <div class="settings-row">
              <label style="font-size:12px;color:#667;min-width:100px">New Password</label>
              <input type="password" id="s-web-password" class="settings-input" placeholder="Enter password" autocomplete="new-password">
              <button class="btn-sm btn-primary" onclick="savePassword()">Set</button>
            </div>
          </div>
        </div>

        <div class="settings-section">
          <h4>Listening IP Address</h4>
          <p class="hint">The IP address Alexa devices use to reach this server on your local network.</p>
          <span class="ip-display"><i class="fa fa-network-wired" style="color:#e99d1a"></i> ${escHtml(localIp)}</span>
        </div>

        <div class="settings-section">
          <h4>Account</h4>
          <p class="hint">The user and server instance this console is paired with.</p>
          <div class="settings-row" style="flex-wrap:wrap;gap:10px">
            <span class="ip-display"><i class="fa fa-user" style="color:#e99d1a"></i> ${escHtml(settings.pairedUser || 'local')}</span>
            <span class="ip-display"><i class="fa fa-laptop" style="color:#e99d1a"></i> ${escHtml(settings.label || '—')}</span>
          </div>
        </div>

        <div class="settings-section">
          <h4>Media Server Label</h4>
          <p class="hint">Identifies this server instance. Shown in Alexa responses.</p>
          <div class="settings-row">
            <input type="text" id="s-label" class="settings-input" value="${escHtml(settings.label || '')}">
            <button class="btn-sm btn-primary" onclick="saveSetting('label', document.getElementById('s-label').value)">Set</button>
          </div>
        </div>

        <div class="settings-section">
          <h4>Public URL — Alexa Skill Endpoint</h4>
          <p class="hint">Required for Alexa to reach this server. Use a Cloudflare named tunnel for a permanent hostname; quick tunnels rotate.</p>
          <div class="settings-row" style="margin-bottom:8px">
            <code style="background:#f4f6f9;padding:6px 10px;border-radius:4px;font-size:12px;flex:1">cloudflared tunnel run ourmedia  # named tunnel (fixed URL)</code>
          </div>
          <div class="settings-row">
            <input type="text" id="s-public-url" class="settings-input wide" value="${escHtml(publicUrl)}" placeholder="https://alexa.example.com">
            <button class="btn-sm btn-primary" onclick="savePublicUrl(document.getElementById('s-public-url').value)">Save</button>
          </div>
          ${publicUrl ? `<p style="font-size:12px;color:#2eaa5a;margin-top:8px"><i class="fa fa-circle-check"></i> Alexa endpoint: <code>${escHtml(publicUrl)}/alexa</code></p>` : ''}
          ${toggle('s-launch-pl-prompt', 'After “Open Bock Media,” ask which playlist (recommended if Alexa keeps opening Spotify instead of this skill)', chk((cfg || {}).launchPlaylistPrompt), 'saveLaunchPlaylistPrompt()')}
          <p class="hint" style="margin-top:6px">When enabled, say: <b>Alexa, open bock media</b> — then answer with only a playlist name, or <b>mix</b> and the name. That stays inside the skill and avoids Spotify.</p>
        </div>

        <div class="settings-row" style="padding-top:8px;border-top:1px solid #eef2f8;margin-top:4px">
          <button class="btn-sm btn-primary" onclick="saveAllSettings()"><i class="fa fa-floppy-disk"></i> Save Settings</button>
        </div>
      </div>
    </div>`);
});

async function saveSetting(key, value) {
  const result = await POST('/api/settings', { [key]: value });
  if (result && result.ok) showToast('Setting saved');
  else showToast('Save failed', true);
}

async function saveAllSettings() {
  const payload = {};
  const fields = {
    'defaultPlaylist':        's-default-pl',
    'ffmpegLocation':         's-ffmpeg',
    'transcodeBitrate':       's-bitrate',
    'label':                  's-label',
    'scanIgnoreFiles':        's-ignore',
  };
  for (const [key, id] of Object.entries(fields)) {
    const el = document.getElementById(id);
    if (el) payload[key] = el.value;
  }
  const toggles = {
    'flacSupport':             's-flac',
    'sendAlbumArt':            's-art',
    'sendMetadata':            's-meta',
    'autoImportPlaylists':     's-autoimport',
    'requirePassword':         's-pass',
    'verboseLogging':          's-log',
    'defaultPlaylistShuffle':  's-default-pl-shuffle',
  };
  for (const [key, id] of Object.entries(toggles)) {
    const el = document.getElementById(id);
    if (el) payload[key] = el.checked ? 'true' : 'false';
  }
  // suppressAutoScan is inverted
  const autoscanEl = document.getElementById('s-autoscan');
  if (autoscanEl) payload['suppressAutoScan'] = autoscanEl.checked ? 'false' : 'true';

  const result = await POST('/api/settings', payload);
  if (result && result.ok) showToast('Settings saved');
  else showToast('Save failed', true);
}

function togglePasswordField(show) {
  const el = document.getElementById('s-pass-fields');
  if (el) el.style.display = show ? '' : 'none';
}

async function savePassword() {
  const pw = (document.getElementById('s-web-password') || {}).value || '';
  if (!pw) { showToast('Enter a password first', true); return; }
  const r = await POST('/api/settings', { webPassword: pw, requirePassword: 'true' });
  if (r && r.ok) {
    document.getElementById('s-web-password').value = '';
    showToast('Password set');
  } else {
    showToast('Failed to set password', true);
  }
}

async function savePublicUrl(url) {
  url = (url || '').trim();
  if (url && !url.startsWith('https://')) {
    showToast('URL must start with https://', true); return;
  }
  const r = await POST('/api/config', { publicUrl: url });
  if (r && r.ok) {
    showToast(url ? 'Public URL saved' : 'Public URL cleared');
    navigate('settings');
  } else {
    showToast('Failed to save URL', true);
  }
}

async function saveLaunchPlaylistPrompt() {
  const el = document.getElementById('s-launch-pl-prompt');
  const r = await POST('/api/config', { launchPlaylistPrompt: !!(el && el.checked) });
  if (r && r.ok) showToast('Alexa launch preference saved');
  else showToast('Save failed', true);
}

async function clearImageCache() {
  const result = await POST('/api/clearcache', {});
  if (result && result.ok) showToast(`Image cache cleared (${result.deleted} items removed)`);
  else showToast('Failed to clear cache', true);
}

// ── Analytics ─────────────────────────────────────────────────────────────

let _anFrom = '', _anTo = '';
window._anCharts = window._anCharts || {};

async function _loadAnalytics() {
  Object.values(window._anCharts || {}).forEach(c => { try { c && c.destroy(); } catch {} });
  window._anCharts = {};
  loading();
  let url = '/api/analytics';
  const params = [];
  if (_anFrom) params.push(`from=${_anFrom}`);
  if (_anTo)   params.push(`to=${_anTo}`);
  if (params.length) url += '?' + params.join('&');
  const data = await API(url);
  if (!data || !data.totalPlays) {
    renderPage('Analytics', `
      <div class="card" style="margin-bottom:20px">${_anDatePickerHtml(data)}</div>
      <div class="empty-state"><i class="fa fa-chart-bar"></i><p>No streaming history yet.</p>
        <p style="font-size:12px;margin-top:8px">Start playing music through Alexa to build analytics.</p></div>`);
    _restoreDateInputs();
    return;
  }
  window._anData = data;
  // Trim leading all-zero days from the day series so the chart starts at first play
  if (!_anFrom && !_anTo && data.activity && data.activity.day) {
    const first = data.activity.day.findIndex(r => r.count > 0);
    if (first > 1) data.activity.day = data.activity.day.slice(first - 1);
  }
  renderPage('Analytics', _buildAnalyticsHTML(data));
  _restoreDateInputs();
  _initAnalyticsCharts(data);
  if (Object.keys(data.playsPerDay || {}).length >= 7) _initEntityActivityCharts(data);
}

function _restoreDateInputs() {
  const fi = document.getElementById('an-date-from');
  const ti = document.getElementById('an-date-to');
  if (fi) fi.value = _anFrom;
  if (ti) ti.value = _anTo;
}

function _anDatePickerHtml(data) {
  const dr = (data || {}).dateRange || {};
  const activeFilter = dr.from || dr.to;
  return `<div class="card-body" style="display:flex;align-items:center;gap:12px;flex-wrap:wrap;padding:12px 16px">
    <span style="font-size:13px;font-weight:600;color:#556">Date Range</span>
    <input type="date" id="an-date-from" style="padding:4px 8px;border:1px solid #ccd;border-radius:4px;font-size:12px" onchange="_anFrom=this.value;_loadAnalytics()">
    <span style="font-size:12px;color:#aab">to</span>
    <input type="date" id="an-date-to" style="padding:4px 8px;border:1px solid #ccd;border-radius:4px;font-size:12px" onchange="_anTo=this.value;_loadAnalytics()">
    ${activeFilter ? `<button class="btn-sm btn-default" onclick="_anFrom='';_anTo='';_loadAnalytics()"><i class="fa fa-times"></i> Clear</button>` : ''}
    ${activeFilter ? `<span style="font-size:11px;color:#e99d1a"><i class="fa fa-filter"></i> Filtered</span>` : ''}
  </div>`;
}

register('analytics', async () => { _anFrom = ''; _anTo = ''; await _loadAnalytics(); });

function _buildAnalyticsHTML(d) {
  const sk  = d.listeningStreak  || {current: d.currentStreak || 0, longest: d.longestStreak || 0};
  const cov = d.catalogCoverage  || {};
  const rr  = d.repeatRate       || {};
  const mad = d.mostActiveDay;
  const hasGenres  = (d.topGenres  || []).length > 0;
  const hasDecades = (d.topDecades || []).length > 0;
  const uniqueDays = Object.keys(d.playsPerDay || {}).length;

  return `
    <div class="card" style="margin-bottom:20px">${_anDatePickerHtml(d)}</div>

    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-icon blue"><i class="fa fa-headphones"></i></div>
        <div class="stat-info"><div class="stat-value">${fmtNum(d.totalPlays)}</div><div class="stat-label">Total Plays</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon orange"><i class="fa fa-fire"></i></div>
        <div class="stat-info">
          <div class="stat-value">${sk.current || 0}<span style="font-size:13px;font-weight:400;color:#778"> day streak</span></div>
          <div class="stat-label">Current Streak</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><i class="fa fa-trophy"></i></div>
        <div class="stat-info">
          <div class="stat-value">${sk.longest || 0}<span style="font-size:13px;font-weight:400;color:#778"> days</span></div>
          <div class="stat-label">Longest Streak</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><i class="fa fa-compact-disc"></i></div>
        <div class="stat-info">
          <div class="stat-value">${(cov.heard > 0 && cov.pct < 0.1) ? '&lt;&nbsp;0.1' : (cov.pct || 0)}<span style="font-size:13px;font-weight:400;color:#778">%</span></div>
          <div class="stat-label">Catalog Heard &middot; ${fmtNum(cov.heard || 0)}/${fmtNum(cov.total || 0)}</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon teal"><i class="fa fa-rotate"></i></div>
        <div class="stat-info">
          <div class="stat-value">${rr.pct || 0}<span style="font-size:13px;font-weight:400;color:#778">%</span></div>
          <div class="stat-label">Repeat Rate</div>
        </div>
      </div>
      ${mad ? `
      <div class="stat-card">
        <div class="stat-icon orange"><i class="fa fa-calendar-day"></i></div>
        <div class="stat-info">
          <div class="stat-value">${fmtNum(mad.count)}</div>
          <div class="stat-label">Best Day &middot; ${escHtml(mad.date)}</div>
        </div>
      </div>` : ''}
    </div>

    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-chart-line"></i> Activity Over Time</h3>
        <div style="display:flex;gap:6px">
          <button class="an-period-btn active" onclick="setAnalyticsPeriod('day')">Day</button>
          <button class="an-period-btn" onclick="setAnalyticsPeriod('week')">Week</button>
          <button class="an-period-btn" onclick="setAnalyticsPeriod('month')">Month</button>
          <button class="an-period-btn" onclick="setAnalyticsPeriod('year')">Year</button>
        </div>
      </div>
      <div class="card-body"><canvas id="an-activity" height="70"></canvas></div>
    </div>

    <div class="an-grid-2">
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-clock"></i> Hour of Day</h3></div>
        <div class="card-body" style="padding-top:10px"><canvas id="an-hour" height="120"></canvas></div>
      </div>
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-calendar-week"></i> Day of Week</h3></div>
        <div class="card-body" style="padding-top:10px"><canvas id="an-dow" height="120"></canvas></div>
      </div>
    </div>

    <div class="card">
      <div class="card-header"><h3><i class="fa fa-table-cells"></i> Listening Heatmap &mdash; Hour &times; Day of Week</h3></div>
      <div class="card-body">${_buildHeatmapHTML(d.heatmap)}</div>
    </div>

    <div class="an-grid-2">
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-microphone"></i> Top Artists</h3></div>
        <div class="card-body an-hbar-wrap"><canvas id="an-artists"></canvas></div>
      </div>
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-compact-disc"></i> Top Albums</h3></div>
        <div class="card-body an-hbar-wrap"><canvas id="an-albums"></canvas></div>
      </div>
    </div>

    <div class="an-grid-2">
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-music"></i> Top Tracks</h3></div>
        <div class="card-body an-hbar-wrap"><canvas id="an-tracks"></canvas></div>
      </div>
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-headphones"></i> Top Devices</h3></div>
        <div class="card-body an-hbar-wrap"><canvas id="an-devices"></canvas></div>
      </div>
    </div>

    ${hasGenres || hasDecades ? `
    <div class="an-grid-2">
      ${hasGenres ? `
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-tag"></i> Top Genres</h3></div>
        <div class="card-body" style="display:flex;justify-content:center;padding:20px 20px 24px">
          <canvas id="an-genres" style="max-height:300px;max-width:300px"></canvas>
        </div>
      </div>` : '<div></div>'}
      ${hasDecades ? `
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-calendar"></i> By Decade</h3></div>
        <div class="card-body" style="padding-top:10px"><canvas id="an-decades" height="120"></canvas></div>
      </div>` : '<div></div>'}
    </div>` : ''}

    ${uniqueDays >= 7 ? `
    <div class="an-grid-2">
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-chart-line"></i> Artist Activity Over Time</h3></div>
        <div class="card-body"><canvas id="an-artist-chart" height="160"></canvas></div>
      </div>
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-chart-line"></i> Album Activity Over Time</h3></div>
        <div class="card-body"><canvas id="an-album-chart" height="160"></canvas></div>
      </div>
    </div>
    <div class="an-grid-2">
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-chart-line"></i> Track Activity Over Time</h3></div>
        <div class="card-body"><canvas id="an-track-chart" height="160"></canvas></div>
      </div>
      <div class="card">
        <div class="card-header"><h3><i class="fa fa-chart-line"></i> Device Activity Over Time</h3></div>
        <div class="card-body"><canvas id="an-device-chart" height="160"></canvas></div>
      </div>
    </div>` : ''}
`;
}

function _buildHeatmapHTML(matrix) {
  if (!matrix || !matrix.length) return '<p style="color:#aab;font-size:13px">No data</p>';
  const days   = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  const maxVal = Math.max(1, ...matrix.map(row => Math.max(...row)));
  let html = '<div class="an-heatmap">';
  html += '<div></div>' + days.map(d => `<div class="an-hm-label">${d}</div>`).join('');
  for (let h = 0; h < 24; h++) {
    const lbl = h === 0 ? '12a' : h < 12 ? `${h}a` : h === 12 ? '12p' : `${h - 12}p`;
    html += `<div class="an-hm-label" style="justify-content:flex-end;padding-right:6px">${lbl}</div>`;
    for (let d = 0; d < 7; d++) {
      const v     = (matrix[h] || [])[d] || 0;
      const alpha = v > 0 ? (0.1 + (v / maxVal) * 0.85).toFixed(2) : 0;
      const bg    = v > 0 ? `rgba(48,66,106,${alpha})` : '#f0f3f8';
      html += `<div class="an-hm-cell" style="background:${bg}" title="${lbl} ${days[d]}: ${v} play${v !== 1 ? 's' : ''}"></div>`;
    }
  }
  html += '</div>';
  return html;
}

function _initAnalyticsCharts(d) {
  const C = {
    navy: '#30426a', orange: '#e99d1a', green: '#2eaa5a',
    purple: '#7c4dbd', teal: '#1a9ba1',
  };
  const xTick = { color: '#778', font: { size: 11 } };
  const yTick = { color: '#778', font: { size: 11 } };
  const noLegend = { legend: { display: false } };

  // Activity line chart with hover tooltip
  const actSeries = (d.activity || {}).day || [];
  const actCanvas = document.getElementById('an-activity');
  if (actCanvas) {
    window._anCharts.activity = new Chart(actCanvas, {
      type: 'line',
      data: {
        labels: actSeries.map(r => r.label),
        datasets: [{
          label: 'Plays',
          data: actSeries.map(r => r.count),
          borderColor: C.navy,
          backgroundColor: 'rgba(48,66,106,0.08)',
          fill: true, tension: 0.3,
          pointRadius: actSeries.length > 60 ? 0 : 3,
          pointHoverRadius: 5,
        }],
      },
      options: {
        plugins: { legend: { display: false }, tooltip: { mode: 'index', intersect: false } },
        interaction: { mode: 'index', intersect: false },
        scales: {
          x: { grid: { display: false }, ticks: xTick },
          y: { grid: { color: '#f0f3f8' }, ticks: yTick, beginAtZero: true },
        },
      },
    });
  }

  // Hour of day
  const hourCanvas = document.getElementById('an-hour');
  if (hourCanvas && d.hourOfDay) {
    window._anCharts.hour = new Chart(hourCanvas, {
      type: 'bar',
      data: {
        labels: d.hourOfDay.map(r => {
          const h = r.hour;
          return h === 0 ? '12a' : h < 12 ? `${h}a` : h === 12 ? '12p' : `${h - 12}p`;
        }),
        datasets: [{ label: 'Plays', data: d.hourOfDay.map(r => r.count), backgroundColor: C.navy, borderRadius: 3 }],
      },
      options: {
        plugins: noLegend,
        scales: {
          x: { grid: { display: false }, ticks: xTick },
          y: { grid: { color: '#f0f3f8' }, ticks: yTick, beginAtZero: true },
        },
      },
    });
  }

  // Day of week
  const dowCanvas = document.getElementById('an-dow');
  if (dowCanvas && d.dayOfWeek) {
    window._anCharts.dow = new Chart(dowCanvas, {
      type: 'bar',
      data: {
        labels: d.dayOfWeek.map(r => r.day),
        datasets: [{ label: 'Plays', data: d.dayOfWeek.map(r => r.count), backgroundColor: C.orange, borderRadius: 3 }],
      },
      options: {
        plugins: noLegend,
        scales: {
          x: { grid: { display: false }, ticks: xTick },
          y: { grid: { color: '#f0f3f8' }, ticks: yTick, beginAtZero: true },
        },
      },
    });
  }

  // Horizontal bar with hover-tooltip showing full label + artist
  function hbar(id, items, color) {
    const el = document.getElementById(id);
    if (!el) return;
    if (!items || !items.length) {
      const wrap = el.closest('.an-hbar-wrap');
      if (wrap) wrap.innerHTML = '<p style="color:#aab;font-size:13px;padding:16px 0">No data yet</p>';
      return;
    }
    window._anCharts[id] = new Chart(el, {
      type: 'bar',
      data: {
        labels: items.map(r => {
          const n = r.name || '';
          return n.length > 26 ? n.slice(0, 26) + '…' : n;
        }),
        datasets: [{ data: items.map(r => r.count), backgroundColor: color, borderRadius: 3 }],
      },
      options: {
        indexAxis: 'y',
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: ctx => (items[ctx[0].dataIndex] || {}).name || '',
              label: ctx => {
                const item = items[ctx.dataIndex] || {};
                return item.artist ? ` ${ctx.raw} plays — ${item.artist}` : ` ${ctx.raw} plays`;
              },
            },
          },
        },
        scales: {
          x: { grid: { color: '#f0f3f8' }, ticks: xTick, beginAtZero: true },
          y: { grid: { display: false }, ticks: { color: '#333', font: { size: 11 } } },
        },
      },
    });
  }

  hbar('an-artists', d.topArtists, C.navy);
  hbar('an-albums',  d.topAlbums,  C.purple);
  hbar('an-tracks',  d.topTracks,  C.teal);
  hbar('an-devices', d.topDevices, C.orange);

  // Genres doughnut
  if ((d.topGenres || []).length) {
    const el = document.getElementById('an-genres');
    if (el) {
      const PAL = [C.navy, C.orange, C.green, C.purple, C.teal,
                   '#e44040', '#e8734a', '#3a8fc9', '#9bc13a', '#b54ab5'];
      window._anCharts.genres = new Chart(el, {
        type: 'doughnut',
        data: {
          labels: d.topGenres.map(r => r.name),
          datasets: [{
            data: d.topGenres.map(r => r.count),
            backgroundColor: PAL, borderWidth: 2, borderColor: '#fff',
          }],
        },
        options: {
          cutout: '60%',
          plugins: {
            legend: { position: 'bottom', labels: { font: { size: 11 }, color: '#333', boxWidth: 12, padding: 10 } },
          },
        },
      });
    }
  }

  // Decades bar
  if ((d.topDecades || []).length) {
    const el = document.getElementById('an-decades');
    if (el) {
      window._anCharts.decades = new Chart(el, {
        type: 'bar',
        data: {
          labels: d.topDecades.map(r => r.decade),
          datasets: [{ label: 'Plays', data: d.topDecades.map(r => r.count), backgroundColor: C.green, borderRadius: 3 }],
        },
        options: {
          plugins: noLegend,
          scales: {
            x: { grid: { display: false }, ticks: xTick },
            y: { grid: { color: '#f0f3f8' }, ticks: yTick, beginAtZero: true },
          },
        },
      });
    }
  }
}

function setAnalyticsPeriod(period) {
  const d = window._anData;
  if (!d || !window._anCharts.activity) return;
  document.querySelectorAll('.an-period-btn').forEach(b => {
    b.classList.toggle('active', b.textContent.toLowerCase() === period);
  });
  const series = (d.activity || {})[period] || [];
  const chart  = window._anCharts.activity;
  chart.data.labels = series.map(r => r.label);
  chart.data.datasets[0].data = series.map(r => r.count);
  chart.data.datasets[0].pointRadius = series.length > 60 ? 0 : 3;
  chart.update();
}

const _AN_COLORS = ['#4e91e6','#e6914e','#4ec74e','#e64e4e','#a44ee6','#e6c84e','#4ec7c7','#e64ea4','#91e64e','#4e4ee6'];

function _buildEntityChart(canvasId, seriesMap) {
  const canvas = document.getElementById(canvasId);
  if (!canvas) return;
  const allDays = [...new Set(Object.values(seriesMap).flatMap(m => Object.keys(m)))].sort();
  if (!allDays.length) return;
  const datasets = Object.entries(seriesMap).map(([label, dayCounts], i) => ({
    label,
    data: allDays.map(d => dayCounts[d] || 0),
    borderColor: _AN_COLORS[i % _AN_COLORS.length],
    backgroundColor: _AN_COLORS[i % _AN_COLORS.length] + '22',
    tension: 0.3, fill: false, pointRadius: 3,
  }));
  window._anCharts[canvasId] = new Chart(canvas, {
    type: 'line',
    data: { labels: allDays, datasets },
    options: {
      responsive: true,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { position: 'bottom', labels: { font: { size: 11 } } },
        tooltip: { mode: 'index', intersect: false },
      },
      scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
    },
  });
}

function _initEntityActivityCharts(data) {
  const ea = (data.entity_activity || {});
  _buildEntityChart('an-artist-chart', ea.artists || {});
  _buildEntityChart('an-album-chart',  ea.albums  || {});
  _buildEntityChart('an-track-chart',  ea.tracks  || {});
  _buildEntityChart('an-device-chart', ea.devices || {});
}

function showToast(msg, isError = false) {
  let t = document.getElementById('toast');
  if (!t) {
    t = document.createElement('div');
    t.id = 'toast';
    t.style.cssText = 'position:fixed;bottom:24px;right:24px;padding:10px 18px;border-radius:6px;font-size:13px;z-index:9999;box-shadow:0 2px 8px rgba(0,0,0,0.2);transition:opacity 0.3s';
    document.body.appendChild(t);
  }
  t.textContent = msg;
  t.style.background = isError ? '#c02020' : '#30426a';
  t.style.color = '#fff';
  t.style.opacity = '1';
  clearTimeout(t._timer);
  t._timer = setTimeout(() => { t.style.opacity = '0'; }, 3000);
}

// ── Sidebar toggle ───────────────────────────────────────────────────────────
function isMobileLayout() {
  return window.matchMedia('(max-width: 768px)').matches;
}

function closeMobileSidebar() {
  document.getElementById('sidebar-wrapper').classList.remove('mobile-open');
  document.body.classList.remove('sidebar-open');
}

document.getElementById('sidebar-toggle').addEventListener('click', () => {
  const sidebar = document.getElementById('sidebar-wrapper');
  if (isMobileLayout()) {
    sidebar.classList.toggle('mobile-open');
    document.body.classList.toggle('sidebar-open', sidebar.classList.contains('mobile-open'));
    return;
  }
  sidebar.classList.toggle('collapsed');
  document.getElementById('content-wrapper').classList.toggle('expanded');
});

document.querySelectorAll('.sidebar-nav a').forEach(a => {
  a.addEventListener('click', () => {
    if (isMobileLayout()) closeMobileSidebar();
  });
});

document.body.addEventListener('click', (e) => {
  if (!isMobileLayout() || !document.body.classList.contains('sidebar-open')) return;
  if (e.target.closest('#sidebar-wrapper') || e.target.closest('#sidebar-toggle')) return;
  closeMobileSidebar();
});

window.addEventListener('resize', () => {
  if (!isMobileLayout()) closeMobileSidebar();
});

// ── Init ─────────────────────────────────────────────────────────────────────
async function init() {
  // Stop Now Playing poll when navigating away from it
  window.addEventListener('hashchange', () => {
    const hash = window.location.hash.replace('#', '');
    if (!hash.startsWith('nowplaying')) {
      clearInterval(_npPollTimer);
      _npPollTimer = null;
    }
    navigate(hash);
  });

  // Initial route
  const hash = window.location.hash.replace('#', '') || 'dashboard';
  navigate(hash);

  // Global header poll — update "now playing" bar every 6s regardless of page
  refreshCurrentTrack();
  setInterval(refreshCurrentTrack, 6000);
}

init();
