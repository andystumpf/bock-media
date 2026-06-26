// Bock Media frontend app

const AUTH_STORAGE_KEY = 'bockmedia_auth';
let _requirePassword = false;

async function refreshAuthInfo() {
  const info = await fetch('/api/auth/info').then(r => r.json()).catch(() => ({}));
  _requirePassword = !!info.requirePassword;
  return info;
}

function authRequired() {
  return _requirePassword;
}

function getStoredAuth() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY) || sessionStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    const a = JSON.parse(raw);
    return (a && a.user && a.pass) ? a : null;
  } catch { return null; }
}

function storeAuth(user, pass, remember) {
  const payload = JSON.stringify({ user, pass });
  sessionStorage.removeItem(AUTH_STORAGE_KEY);
  localStorage.removeItem(AUTH_STORAGE_KEY);
  if (remember) localStorage.setItem(AUTH_STORAGE_KEY, payload);
  else sessionStorage.setItem(AUTH_STORAGE_KEY, payload);
}

function clearStoredAuth() {
  localStorage.removeItem(AUTH_STORAGE_KEY);
  sessionStorage.removeItem(AUTH_STORAGE_KEY);
}

function authHeaders() {
  if (!authRequired()) return {};
  const a = getStoredAuth();
  if (!a) return {};
  return { Authorization: 'Basic ' + btoa(unescape(encodeURIComponent(a.user + ':' + a.pass))) };
}

function authFetch(input, init = {}) {
  const headers = { ...authHeaders(), ...(init.headers || {}) };
  return fetch(input, { ...init, headers });
}

const API = (path) => authFetch(path).then(r => {
  if (r.status === 401 && authRequired()) { showLoginModal(); return null; }
  return r.json();
}).catch(() => null);

const POST = (path, body) => authFetch(path, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', ...authHeaders() },
  body: JSON.stringify(body),
}).then(r => {
  if (r.status === 401 && authRequired()) { showLoginModal(); return null; }
  return r.json();
}).catch(() => null);

function showLoginModal() {
  if (document.getElementById('login-overlay')) return;
  const overlay = document.createElement('div');
  overlay.id = 'login-overlay';
  overlay.className = 'modal-overlay';
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:380px">
      <div style="text-align:center;margin-bottom:12px">
        <img src="img/bock-logo-mark.png?v=2" alt="" width="48" height="48" style="border-radius:50%">
      </div>
      <h3 style="margin-top:0"><i class="fa fa-lock"></i> Sign in</h3>
      <p class="hint" style="margin:0 0 12px">Bock Media console login</p>
      <div class="settings-row" style="flex-direction:column;align-items:stretch;gap:8px">
        <input type="text" id="login-user" class="settings-input" placeholder="Username" autocomplete="username">
        <input type="password" id="login-pass" class="settings-input" placeholder="Password" autocomplete="current-password">
        <label style="font-size:13px;display:flex;align-items:center;gap:8px;cursor:pointer">
          <input type="checkbox" id="login-remember" checked> Remember me
        </label>
      </div>
      <p id="login-error" class="hint" style="color:#c0392b;display:none;margin:8px 0 0"></p>
      <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:16px">
        <button class="btn-sm btn-primary" id="login-submit"><i class="fa fa-right-to-bracket"></i> Sign in</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  fetch('/api/auth/info').then(r => r.json()).then(info => {
    const u = document.getElementById('login-user');
    if (u && info && info.username) u.value = info.username;
  }).catch(() => {});
  const saved = getStoredAuth();
  if (saved) {
    document.getElementById('login-user').value = saved.user;
    document.getElementById('login-pass').value = saved.pass;
  }
  document.getElementById('login-submit').onclick = () => submitLogin();
  document.getElementById('login-pass').onkeydown = (e) => { if (e.key === 'Enter') submitLogin(); };
}

async function submitLogin() {
  const user = (document.getElementById('login-user') || {}).value || '';
  const pass = (document.getElementById('login-pass') || {}).value || '';
  const remember = !!(document.getElementById('login-remember') || {}).checked;
  const errEl = document.getElementById('login-error');
  if (!user || !pass) {
    if (errEl) { errEl.textContent = 'Enter username and password'; errEl.style.display = ''; }
    return;
  }
  storeAuth(user, pass, remember);
  const r = await authFetch('/api/health');
  if (r.ok) {
    document.getElementById('login-overlay')?.remove();
    const hash = window.location.hash.replace('#', '') || 'dashboard';
    navigate(hash);
    refreshCurrentTrack();
    return;
  }
  clearStoredAuth();
  if (errEl) { errEl.textContent = 'Invalid username or password'; errEl.style.display = ''; }
}

async function ensureAuth() {
  await refreshAuthInfo();
  document.getElementById('login-overlay')?.remove();
  if (!authRequired()) return;
  if (getStoredAuth()) {
    const r = await authFetch('/api/health');
    if (r.ok) return;
    clearStoredAuth();
  }
  showLoginModal();
  return new Promise((resolve) => {
    const iv = setInterval(() => {
      if (!authRequired() || (getStoredAuth() && !document.getElementById('login-overlay'))) {
        clearInterval(iv);
        resolve();
      }
    }, 200);
  });
}

function signOut() {
  clearStoredAuth();
  if (authRequired()) showLoginModal();
}

// Format helpers
function fmtNum(n) { return Number(n || 0).toLocaleString(); }

function artworkUrl(filepath, sizePx) {
  if (!filepath) return null;
  const rel = String(filepath).replace(/^\/+/, '');
  const encoded = rel.split('/').map(seg => encodeURIComponent(seg)).join('/');
  const q = sizePx ? `?size=${sizePx}` : '';
  return `/artwork/${encoded}${q}`;
}

const _artistPortraitCache = new Map();
async function resolveArtistPortraitPath(name) {
  const key = String(name || '').trim().toLowerCase();
  if (!key) return null;
  if (_artistPortraitCache.has(key)) return _artistPortraitCache.get(key);
  try {
    const res = await authFetch(`/api/artist-portrait?artist=${encodeURIComponent(name)}`);
    if (!res?.ok) {
      _artistPortraitCache.set(key, null);
      return null;
    }
    const data = await res.json();
    const path = data.art_path || null;
    _artistPortraitCache.set(key, path);
    return path;
  } catch {
    _artistPortraitCache.set(key, null);
    return null;
  }
}

function upgradeArtistPortraitImages(items) {
  (items || []).slice(0, 24).forEach((a) => {
    if ((a.art_path || '').includes('artist-portrait-')) return;
    resolveArtistPortraitPath(a.artist).then((path) => {
      if (!path) return;
      const url = artworkUrl(path, 256);
      if (!url) return;
      document.querySelectorAll('.spotify-artist-card').forEach((card) => {
        const title = card.querySelector('.spotify-card-title');
        if (!title || title.textContent !== a.artist) return;
        const wrap = card.querySelector('.spotify-card-media .spotify-card-art');
        const img = wrap?.querySelector('img');
        if (img) img.src = url;
      });
    });
  });
}

function spotifyCardArtHtml(artPath, seed, icon, sizePx = 384, opts) {
  const unplayed = opts && opts.unplayed;
  const url = artPath ? artworkUrl(artPath, sizePx) : null;
  const grad = spotifyGradient(seed || '');
  const ic = icon || 'fa-music';
  const badge = unplayed ? '<span class="unplayed-album-badge" aria-label="Unplayed"></span>' : '';
  if (!url) {
    return `<div class="spotify-card-art" style="background:${grad}"><i class="fa ${ic}"></i>${badge}</div>`;
  }
  return `<div class="spotify-card-art spotify-card-art-img" style="background:${grad}">
    <img src="${escHtml(url)}" alt="" loading="lazy"
      onerror="this.closest('.spotify-card-art').classList.add('spotify-card-art-fallback');this.remove();">
    ${badge}
  </div>`;
}

function spotifyShortcutArtHtml(artPath, seed, icon) {
  const url = artPath ? artworkUrl(artPath, 128) : null;
  const grad = spotifyGradient(seed || '');
  const ic = icon || 'fa-music';
  if (!url) {
    return `<span class="spotify-shortcut-art" style="background:${grad}"><i class="fa ${ic}"></i></span>`;
  }
  return `<span class="spotify-shortcut-art spotify-shortcut-art-img" style="background:${grad}">
    <img src="${escHtml(url)}" alt="" loading="lazy"
      onerror="this.closest('.spotify-shortcut-art').classList.add('spotify-shortcut-art-fallback');this.remove();">
  </span>`;
}

function playlistRowArtHtml(playlistId, coverPath, seed, isAudiobook) {
  const icon = isAudiobook ? 'fa-book' : 'fa-list';
  const url = coverPath ? artworkUrl(coverPath, 96) : null;
  const grad = spotifyGradient(seed || playlistId || '');
  if (!url) {
    return `<div class="pl-row-art" style="background:${grad}"><i class="fa ${icon}"></i></div>`;
  }
  return `<div class="pl-row-art pl-row-art-img" style="background:${grad}">
    <img src="${escHtml(url)}" alt="" loading="lazy"
      onerror="this.closest('.pl-row-art').classList.add('pl-row-art-fallback');this.remove();">
  </div>`;
}

async function fetchPlaylistCovers(ids) {
  const unique = [...new Set((ids || []).filter(Boolean))];
  if (!unique.length) return {};
  const out = {};
  for (let i = 0; i < unique.length; i += 200) {
    const chunk = unique.slice(i, i + 200);
    try {
      const r = await authFetch('/api/playlists/covers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids: chunk }),
      });
      if (!r.ok) continue;
      const data = await r.json().catch(() => ({}));
      Object.assign(out, (data && data.covers) || {});
    } catch { /* try next chunk */ }
  }
  return out;
}

async function fetchPlaylistsCached(search = '') {
  const q = (search || '').trim().toLowerCase();
  if (!q && typeof WebCache !== 'undefined') {
    const cached = WebCache.getPlaylistsIfFresh();
    if (cached) return cached;
  }
  const data = await API(`/api/playlists?page=1&limit=2000&search=${encodeURIComponent(search)}`).catch(() => ({ items: [] }));
  const items = (data && data.items) || [];
  if (!q && typeof WebCache !== 'undefined' && items.length) WebCache.setPlaylists(items);
  return items;
}

async function refreshHomeCovers(feed, existingCovers = {}) {
  // Every playlist tile in the feed needs its cover — home cards no longer carry a
  // history-seeded artPath, so a tile without a fetched cover renders as a placeholder.
  // fetchPlaylistCovers chunks the request, so requesting the full set is cheap.
  const ids = [...new Set((feed.sections || [])
    .flatMap((s) => (s.cards || []).map((c) => c.playlistId).filter(Boolean)))].slice(0, 400);
  const missing = ids.filter((id) => !existingCovers[id]);
  if (!missing.length) return existingCovers;
  const fetched = await fetchPlaylistCovers(missing);
  return { ...existingCovers, ...fetched };
}

function prefetchCriticalApis() {
  if (authRequired() && !getStoredAuth()) return;
  setTimeout(() => {
    API('/api/dashboard/quick').catch(() => {});
    fetchPlaylistsCached('').catch(() => {});
    ensureAlexaRemoteStatus().catch(() => {});
  }, 50);
}

let _chartJsPromise = null;
function ensureChartJs() {
  if (typeof Chart !== 'undefined') return Promise.resolve(true);
  if (_chartJsPromise) return _chartJsPromise;
  _chartJsPromise = new Promise((resolve) => {
    const s = document.createElement('script');
    s.src = 'https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js';
    s.crossOrigin = 'anonymous';
    s.onload = () => resolve(true);
    s.onerror = () => resolve(false);
    document.head.appendChild(s);
  });
  return _chartJsPromise;
}

function warmBackgroundCaches() {
  if (typeof WebCache === 'undefined' || WebCache.peekSearchBrowse()) return;
  setTimeout(() => {
    Promise.all([
      API('/api/dashboard/quick').catch(() => ({ recent: [], favorites: [] })),
      API('/api/genres?limit=16').catch(() => ({ items: [] })),
      fetchPlaylistsCached('').catch(() => []),
      API('/api/library/new?since=30d&limit=12').catch(() => ({ albums: [] })),
    ]).then(([quick, genres, playlists, newAlbums]) => {
      WebCache.putSearchBrowse({ quick, genres, playlists, newAlbums });
    }).catch(() => {});
  }, 1500);
}

function npArtworkHtml(filepath) {
  const url = artworkUrl(filepath);
  if (!url) {
    return '<div class="np-artwork np-artwork-fallback" aria-hidden="true"><i class="fa fa-compact-disc"></i></div>';
  }
  return `<div class="np-artwork" aria-hidden="true">
    <img src="${escHtml(url)}" alt="" loading="lazy" class="np-artwork-img"
      onerror="this.closest('.np-artwork').classList.add('np-artwork-fallback');this.remove();">
  </div>`;
}

function time24ToParts(time24) {
  const parts = (time24 || '08:00').split(':');
  const h24 = parseInt(parts[0], 10) || 0;
  const minute = parseInt(parts[1], 10) || 0;
  const ampm = h24 >= 12 ? 'PM' : 'AM';
  let hour = h24 % 12;
  if (hour === 0) hour = 12;
  return { hour, minute, ampm };
}

function partsToTime24(hour12, minute, ampm) {
  let h = parseInt(hour12, 10) || 12;
  const m = parseInt(minute, 10) || 0;
  if (ampm === 'AM') {
    if (h === 12) h = 0;
  } else if (h !== 12) {
    h += 12;
  }
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function formatTime12(time24) {
  const { hour, minute, ampm } = time24ToParts(time24);
  return `${hour}:${String(minute).padStart(2, '0')} ${ampm}`;
}

function autoTimeSelectHtml(time24) {
  const { hour, minute, ampm } = time24ToParts(time24);
  const hours = Array.from({ length: 12 }, (_, i) => i + 1).map(h =>
    `<option value="${h}" ${h === hour ? 'selected' : ''}>${h}</option>`).join('');
  const mins = Array.from({ length: 60 }, (_, i) => i).map(m =>
    `<option value="${m}" ${m === minute ? 'selected' : ''}>${String(m).padStart(2, '0')}</option>`).join('');
  return `
    <div class="auto-time-row">
      <select id="auto-time-hour" class="settings-input">${hours}</select>
      <span class="auto-time-sep">:</span>
      <select id="auto-time-min" class="settings-input">${mins}</select>
      <select id="auto-time-ampm" class="settings-input">
        <option value="AM" ${ampm === 'AM' ? 'selected' : ''}>AM</option>
        <option value="PM" ${ampm === 'PM' ? 'selected' : ''}>PM</option>
      </select>
    </div>`;
}

function autoCollectTime24() {
  const hour = (document.getElementById('auto-time-hour') || {}).value;
  const minute = (document.getElementById('auto-time-min') || {}).value;
  const ampm = (document.getElementById('auto-time-ampm') || {}).value;
  return partsToTime24(hour, minute, ampm);
}
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

const BOTTOM_TAB_ROUTES = {
  home: ['dashboard', 'rooms', 'analytics', 'routines'],
  nowplaying: ['nowplaying'],
  search: ['search'],
  library: ['library', 'playlists', 'artists', 'albums', 'songs', 'watchfolders', 'genres'],
  automations: ['automation'],
};

function bottomTabForRoute(route) {
  const root = (route || 'dashboard').split('/')[0];
  for (const [tab, names] of Object.entries(BOTTOM_TAB_ROUTES)) {
    if (names.includes(root)) return tab;
  }
  return null;
}

function greeting() {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

function updateShellForRoute(route) {
  const root = (route || 'dashboard').split('/')[0];
  document.body.classList.toggle('route-nowplaying', root === 'nowplaying');
  document.body.classList.toggle('route-home', root === 'dashboard');
  document.body.classList.toggle('route-search', root === 'search');
  const tab = bottomTabForRoute(root);
  document.querySelectorAll('.bottom-nav-link').forEach((a) => {
    a.classList.toggle('active', tab && a.dataset.tab === tab);
  });
  document.querySelectorAll('.sidebar-nav-item').forEach((a) => {
    const href = (a.getAttribute('href') || '').replace('#', '').split('/')[0];
    const libRoutes = ['library', 'playlists', 'artists', 'albums', 'songs', 'genres', 'watchfolders'];
    const active = href === root
      || (a.dataset.tab === tab && tab !== null)
      || (a.dataset.tab === 'library' && libRoutes.includes(root));
    a.classList.toggle('active', active);
  });
  updateTopbarNavButtons();
  if (root !== 'search') syncTopbarSearch('');
  closeAppDrawer();
}

function spotifyGradient(seed) {
  const g = [
    'linear-gradient(135deg,#5038a0,#283248)',
    'linear-gradient(135deg,#1db954,#148a08)',
    'linear-gradient(135deg,#509bf5,#1e3264)',
    'linear-gradient(135deg,#e91429,#8c1932)',
    'linear-gradient(135deg,#8d67ab,#5038a0)',
    'linear-gradient(135deg,#d84000,#8c331a)',
  ];
  let h = 0;
  for (let i = 0; i < (seed || '').length; i++) h = (h + seed.charCodeAt(i)) % g.length;
  return g[h];
}

function spotifyShortcut(title, href, icon, seed, artPath) {
  return `<a href="${href}" class="spotify-shortcut">
    ${spotifyShortcutArtHtml(artPath, seed || title, icon)}
    <span class="spotify-shortcut-title">${escHtml(title)}</span>
  </a>`;
}

function homeCardIndex(feed, cardId) {
  for (let si = 0; si < (feed.sections || []).length; si++) {
    const ci = feed.sections[si].cards.findIndex((c) => c.id === cardId);
    if (ci >= 0) return { si, ci };
  }
  return null;
}

function homeSectionFilterGroups(kind) {
  if (kind === 'Radio') return 'all radio';
  if (kind === 'DailyMixes' || kind === 'TopMixes') return 'all radio music';
  if (kind === 'RecentPlaylists' || kind === 'MorePlaylists' || kind === 'ExploreThemes') return 'all playlists';
  if (kind === 'JumpBackIn' || kind === 'Favorites' || kind === 'Discover') return 'all music';
  if (kind === 'Mood') return 'all music playlists';
  return 'all';
}

function tileDownloadOnclick(playTarget) {
  if (!playTarget || !['playlist', 'album', 'artist'].includes(playTarget.kind)) return null;
  const enc = encodeURIComponent(JSON.stringify(playTarget));
  return `openOfflineDownloadHint('${enc}')`;
}

function openOfflineDownloadHint(encoded) {
  let target = null;
  try { target = JSON.parse(decodeURIComponent(encoded)); } catch (_) { /* ignore */ }
  const name = (target && target.name) || 'this collection';
  showToast(`Save “${name}” offline in the Android or iOS app.`);
  if (window.location.hash !== '#download') window.location.hash = 'download';
}
window.openOfflineDownloadHint = openOfflineDownloadHint;

function spotifyHomeQuickCard(card, artPath, playOnclick) {
  const attrs = playOnclick
    ? `href="#" role="button" onclick="event.preventDefault();${playOnclick}"`
    : 'href="#search"';
  return `<a ${attrs} class="home-quick-card">
    ${spotifyShortcutArtHtml(artPath, card.title, HomeFeed.cardIcon(card))}
    <span class="home-quick-title">${escHtml(card.title)}</span>
  </a>`;
}

function spotifyHomeRadioCard(card, si, ci, artPath) {
  const grad = spotifyGradient(card.title);
  const url = artPath ? artworkUrl(artPath, 256) : null;
  const play = HomeFeed.cardPlayOpts(card) ? `homeFeedPlay(${si}, ${ci})` : null;
  const download = tileDownloadOnclick(card.playTarget);
  const playFab = play
    ? `<button type="button" class="spotify-play-fab" onclick="event.preventDefault();event.stopPropagation();${play}" aria-label="Play"><i class="fa fa-play"></i></button>`
    : '';
  const downloadFab = download
    ? `<button type="button" class="spotify-download-fab" onclick="event.preventDefault();event.stopPropagation();${download}" aria-label="Download for offline"><i class="fa fa-download"></i></button>`
    : '';
  const discInner = url
    ? `<img src="${escHtml(url)}" alt="" loading="lazy" onerror="this.parentElement.classList.add('home-radio-disc-fallback');this.remove();">`
    : `<i class="fa ${HomeFeed.cardIcon(card)}"></i>`;
  return `<a href="${HomeFeed.cardHref(card)}" class="spotify-card home-radio-card">
    <div class="spotify-card-media">
      <div class="home-radio-stage" style="background:${grad}">
        <span class="home-radio-badge">RADIO</span>
        <div class="home-radio-disc">${discInner}</div>
        ${downloadFab}
        ${playFab}
      </div>
    </div>
    <div class="spotify-card-title">${escHtml(card.title)}</div>
    <div class="spotify-card-sub">${escHtml(card.subtitle || '')}</div>
  </a>`;
}

function spotifyHomeDailyMixCard(card, si, ci, artPath) {
  const play = HomeFeed.cardPlayOpts(card) ? `homeFeedPlay(${si}, ${ci})` : null;
  const download = tileDownloadOnclick(card.playTarget);
  return spotifyMediaCard(
    card.title,
    card.subtitle || '',
    HomeFeed.cardHref(card),
    HomeFeed.cardIcon(card),
    card.title,
    play,
    artPath,
    { downloadOnclick: download, dailyMix: true },
  );
}

function spotifyHomeSectionCard(card, secKind, si, ci, artPath) {
  if (secKind === 'Radio' || card.playTarget?.kind === 'radio') {
    return spotifyHomeRadioCard(card, si, ci, artPath);
  }
  if (secKind === 'DailyMixes') {
    return spotifyHomeDailyMixCard(card, si, ci, artPath);
  }
  const play = HomeFeed.cardPlayOpts(card) ? `homeFeedPlay(${si}, ${ci})` : null;
  const download = tileDownloadOnclick(card.playTarget);
  return spotifyMediaCard(
    card.title,
    card.subtitle || '',
    HomeFeed.cardHref(card),
    HomeFeed.cardIcon(card),
    card.title,
    play,
    artPath,
    { downloadOnclick: download },
  );
}

function spotifyHomeSection(title, showAllHref, innerHtml, filterGroups) {
  const link = showAllHref
    ? `<a href="${showAllHref}" class="spotify-section-link">Show all</a>` : '';
  return `<section class="spotify-section spotify-home-section" data-home-groups="${filterGroups}">
    <div class="spotify-section-header">
      <h2 class="spotify-section-title home-greeting">${escHtml(title)}</h2>${link}
    </div>
    <div class="spotify-carousel library-playlist-grid home-tile-grid">${innerHtml}</div>
  </section>`;
}

function setupHomeFilterDelegation() {
  if (window._homeFilterDelegation) return;
  window._homeFilterDelegation = true;
  document.addEventListener('click', (e) => {
    const btn = e.target.closest('.home-filter[data-home-filter]');
    if (!btn) return;
    const filter = btn.getAttribute('data-home-filter') || 'all';
    document.querySelectorAll('.home-filter').forEach((b) => {
      b.classList.toggle('active', b === btn);
    });
    document.querySelectorAll('[data-home-groups]').forEach((sec) => {
      const groups = (sec.getAttribute('data-home-groups') || '').split(/\s+/);
      if (filter === 'podcasts' || filter === 'audiobooks') {
        sec.hidden = true;
        return;
      }
      sec.hidden = filter !== 'all' && !groups.includes(filter);
    });
    const quick = document.querySelector('.home-quick-grid');
    if (quick) {
      quick.hidden = filter !== 'all' && filter !== 'music' && filter !== 'playlists'
        && filter !== 'podcasts' && filter !== 'audiobooks';
    }
  });
}

function spotifyMediaCard(title, subtitle, href, icon, seed, playOnclick, artPath, opts) {
  const o = opts || {};
  const play = playOnclick
    ? `<button type="button" class="spotify-play-fab" onclick="event.preventDefault();event.stopPropagation();${playOnclick}" aria-label="Play"><i class="fa fa-play"></i></button>`
    : '';
  const download = o.downloadOnclick
    ? `<button type="button" class="spotify-download-fab" onclick="event.preventDefault();event.stopPropagation();${o.downloadOnclick}" aria-label="Download for offline"><i class="fa fa-download"></i></button>`
    : '';
  let artHtml = spotifyCardArtHtml(artPath, seed || title, icon || 'fa-music', 384, o);
  if (o.dailyMix) {
    const mixBadge = (title || '').replace(/^daily mix/i, 'Daily Mix').slice(0, 24);
    artHtml = artHtml.replace('class="spotify-card-art', `class="spotify-card-art home-daily-art" data-mix="${escHtml(mixBadge)}"`);
  }
  return `<a href="${href}" class="spotify-card">
    <div class="spotify-card-media">
      ${artHtml}
      ${download}
      ${play}
    </div>
    <div class="spotify-card-title">${escHtml(title)}</div>
    <div class="spotify-card-sub">${escHtml(subtitle || '')}</div>
  </a>`;
}

function spotifySection(title, showAllHref, innerHtml, opts) {
  const home = opts && opts.home;
  const link = showAllHref
    ? `<a href="${showAllHref}" class="spotify-section-link">Show all</a>` : '';
  return `<section class="spotify-section${home ? ' spotify-home-section' : ''}">
    <div class="spotify-section-header">
      <h2 class="spotify-section-title">${escHtml(title)}</h2>${link}
    </div>
    <div class="spotify-carousel">${innerHtml}</div>
  </section>`;
}

function openAppDrawer() {
  document.getElementById('app-drawer')?.classList.add('open');
  document.getElementById('app-backdrop')?.classList.remove('hidden');
}

function closeAppDrawer() {
  document.getElementById('app-drawer')?.classList.remove('open');
  document.getElementById('app-backdrop')?.classList.add('hidden');
}

function libraryFiltersHtml(active) {
  const tabs = [
    ['library', '#library', 'Playlists'],
    ['artists', '#artists', 'Artists'],
    ['albums', '#albums', 'Albums'],
    ['songs', '#songs', 'Songs'],
    ['genres', '#genres', 'Genres'],
    ['watchfolders', '#watchfolders', 'Sources'],
  ];
  return `<div class="library-filters">${tabs.map(([id, href, label]) =>
    `<a href="${href}" class="library-filter${active === id ? ' active' : ''}">${escHtml(label)}</a>`).join('')}</div>`;
}

function folderCardHtml(f) {
  const statusClass = (f.status || '').toLowerCase() === 'scanning' ? 'scanning'
    : (f.status || '').toLowerCase() === 'queued' ? 'queued'
    : (f.status || '').toLowerCase() === 'done' ? 'done' : 'gray';
  return `
    <div class="folder-card folder-card-compact">
      <div class="folder-icon"><i class="fa fa-folder-open"></i></div>
      <div class="folder-info">
        ${f.label ? `<span class="folder-label">${escHtml(f.label)}</span>` : ''}
        <div class="folder-path">${escHtml(f.path)}</div>
        <div class="folder-meta">
          ${f.identifiedFiles > 0 ? `<span><i class="fa fa-music"></i> ${fmtNum(f.identifiedFiles)} tracks</span>` : ''}
          ${f.playlists > 0 ? `<span><i class="fa fa-list"></i> ${fmtNum(f.playlists)} playlists</span>` : ''}
          ${f.errors > 0 ? `<span style="color:#e91429"><i class="fa fa-triangle-exclamation"></i> ${f.errors} errors</span>` : ''}
          <span><i class="fa fa-layer-group"></i> ${escHtml(f.type || 'folder')}</span>
        </div>
      </div>
      <div><span class="status-dot ${statusClass}">${escHtml(f.status || '—')}</span></div>
    </div>`;
}

async function loadLibrary(opts = {}) {
  if (typeof WebCache !== 'undefined' && WebCache.shouldSkipLibraryReload()) return;
  const [plItems, smartData, folders, genres, remote] = await Promise.all([
    fetchPlaylistsCached(''),
    API('/api/smart_playlists').catch(() => ({ items: [] })),
    API('/api/watchfolders').catch(() => []),
    API('/api/genres?limit=12').catch(() => ({ items: [] })),
    ensureAlexaRemoteStatus().catch(() => ({})),
  ]);
  window._libPlaylists = plItems || [];
  window._libSmart = (smartData && smartData.items) || [];
  window._libFolders = Array.isArray(folders) ? folders : [];
  window._libGenres = (genres && genres.items) || [];
  window._libRemote = remote || {};
  const coverIds = [
    ...window._libPlaylists.map((p) => p.id),
    ...window._libSmart.map((s) => s.linkedPlaylistId),
  ].filter(Boolean);
  window._libCovers = coverIds.length ? await fetchPlaylistCovers(coverIds) : {};
  if (typeof WebCache !== 'undefined') {
    WebCache.putLibrary({
      playlists: window._libPlaylists,
      smart: window._libSmart,
      folders: window._libFolders,
      genres: window._libGenres,
    });
    WebCache.markLibraryLoaded();
  }
  renderLibraryPage();
}

function renderLibraryPage() {
  const playlists = window._libPlaylists || [];
  const smart = window._libSmart || [];
  const folders = window._libFolders || [];
  const genres = window._libGenres || [];
  const remote = window._libRemote || {};
  const covers = window._libCovers || {};
  const canPlay = true;

  const cards = playlists.map((p, i) => spotifyMediaCard(
    p.name,
    `${fmtNum(p.trackCount || 0)} tracks`,
    `#playlists/detail/${encodeURIComponent(p.id)}`,
    'fa-list',
    p.name,
    canPlay ? `libPlayPlaylist(${i})` : null,
    p.artPath || covers[p.id],
    { downloadOnclick: tileDownloadOnclick({ kind: 'playlist', id: p.id, name: p.name }) },
  )).join('');

  const smartCards = smart.map((s) => {
    const href = s.linkedPlaylistId
      ? `#playlists/detail/${encodeURIComponent(s.linkedPlaylistId)}`
      : '#playlists';
    const art = s.linkedPlaylistId ? covers[s.linkedPlaylistId] : null;
    return spotifyMediaCard(
      s.name,
      `${fmtNum(s.trackCount || 0)} tracks · smart`,
      href,
      'fa-wand-magic-sparkles',
      s.name,
      null,
      art,
    );
  }).join('');

  const genreCards = genres.map((g) =>
    spotifyMediaCard(
      g.name,
      `${fmtNum(g.track_count || 0)} tracks`,
      '#search',
      'fa-tag',
      g.name,
      null,
    ).replace('class="spotify-card"', `class="spotify-card genre-tile" data-genre="${escHtml(g.name)}"`)).join('');

  const folderCards = folders.map((f) => folderCardHtml(f)).join('');

  const sections = [];
  if (playlists.length) {
    sections.push(`<section class="spotify-section">
      <div class="spotify-section-header">
        <h2 class="spotify-section-title home-greeting">Playlists (${fmtNum(playlists.length)})</h2>
        <a href="#playlists" class="spotify-section-link">Manage</a>
      </div>
      <div class="spotify-carousel library-playlist-grid">${cards}</div>
    </section>`);
  }
  if (smart.length) {
    sections.push(`<section class="spotify-section">
      <div class="spotify-section-header">
        <h2 class="spotify-section-title home-greeting">Smart playlists (${fmtNum(smart.length)})</h2>
        <a href="#playlists" class="spotify-section-link">Manage rules</a>
      </div>
      <div class="spotify-carousel library-playlist-grid">${smartCards}</div>
    </section>`);
  }
  if (genres.length) {
    sections.push(`<section class="spotify-section">
      <div class="spotify-section-header">
        <h2 class="spotify-section-title home-greeting">Top genres</h2>
        <a href="#genres" class="spotify-section-link">Show all</a>
      </div>
      <div class="spotify-carousel library-playlist-grid">${genreCards}</div>
    </section>`);
  }
  if (folders.length) {
    sections.push(`<section class="spotify-section">
      <div class="spotify-section-header">
        <h2 class="spotify-section-title home-greeting">Library sources (${fmtNum(folders.length)})</h2>
        <a href="#watchfolders" class="spotify-section-link">Details</a>
      </div>
      <div class="folder-card-list">${folderCards}</div>
    </section>`);
  }
  if (!sections.length) {
    sections.push(`<div class="empty-state"><i class="fa fa-book"></i><p>Your library is empty — add watch folders or import playlists.</p></div>`);
  }

  renderPage('Your Library', spotifyBrowsePage('Your Library', libraryFiltersHtml('library'), sections.join('')), { header: false });
}

async function libPlayPlaylist(i) {
  const p = (window._libPlaylists || [])[i];
  if (!p) return;
  try {
    await startPlayback({ kind: 'playlist', name: p.name, id: p.id });
  } catch (e) {
    showToast(e.message || 'Play failed', true);
  }
}
window.libPlayPlaylist = libPlayPlaylist;

async function loadGenresPage() {
  loading();
  const data = await API('/api/genres?limit=200');
  const genres = (data && data.items) || [];
  const tiles = genres.length
    ? genres.map((g) =>
      spotifyMediaCard(
        g.name,
        `${fmtNum(g.track_count || 0)} tracks`,
        '#search',
        'fa-tag',
        g.name,
        null,
      ).replace('class="spotify-card"', `class="spotify-card genre-tile" data-genre="${escHtml(g.name)}"`)).join('')
    : '<div class="empty-state"><i class="fa fa-tag"></i><p>No genres indexed yet.</p></div>';
  renderPage('Genres', `${libraryFiltersHtml('genres')}<div class="library-playlist-grid">${tiles}</div>`);
}

function navigate(hash) {
  const [route, ...rest] = (hash || 'dashboard').split('/');
  const params = rest.join('/');
  currentRoute = route;
  updateShellForRoute(route);
  if (route === 'playlists' || route === 'library') refreshSidebarPlaylists();

  document.querySelectorAll('.drawer-link').forEach((a) => {
    const href = (a.getAttribute('href') || '').replace('#', '').split('/')[0];
    a.classList.toggle('active', href === route);
  });

  const fn = routes[route];
  if (fn) {
    Promise.resolve(fn(params)).catch((err) => routeErrorPage(route, err));
  } else {
    renderPage('Not Found', '<div class="empty-state"><i class="fa fa-question-circle"></i><p>Page not found.</p></div>');
  }
}

function setPageTitle(title) {
  const pt = document.getElementById('page-title');
  if (pt) pt.textContent = title || '';
}

function syncTopbarSearch(q) {
  const inp = document.getElementById('topbar-search-q');
  if (inp && document.activeElement !== inp) inp.value = q || '';
}

function updateTopbarNavButtons() {
  const back = document.getElementById('topbar-back');
  const fwd = document.getElementById('topbar-forward');
  if (back) back.disabled = !(window.history && window.history.length > 1);
  if (fwd) fwd.disabled = true;
}

async function refreshSidebarPlaylists() {
  const el = document.getElementById('sidebar-playlist-list');
  if (!el) return;
  try {
    const items = await fetchPlaylistsCached('');
    const slice = items || [];
    const ids = slice.map((p) => p.id).filter(Boolean);
    const covers = ids.length ? await fetchPlaylistCovers(ids) : {};
    const route = (window.location.hash || '').replace('#', '');
    el.innerHTML = slice.map((p) => {
      const href = `#playlists/detail/${encodeURIComponent(p.id)}`;
      const active = route === `playlists/detail/${p.id}` ? ' active' : '';
      const cover = covers[p.id];
      const art = cover
        ? `<img src="${escHtml(artworkUrl(cover, 96))}" alt="" loading="lazy">`
        : `<i class="fa fa-list"></i>`;
      return `<a href="${href}" class="sidebar-pl-item${active}"><span class="sidebar-pl-art">${art}</span><span>${escHtml(p.name)}</span></a>`;
    }).join('') || '<p class="hint" style="padding:8px;font-size:12px;color:var(--text-muted)">No playlists yet</p>';
  } catch {
    el.innerHTML = '';
  }
}

function toggleQueuePanel(force) {
  const panel = document.getElementById('spotify-queue');
  if (!panel) return;
  const open = force != null ? !!force : panel.classList.contains('hidden');
  panel.classList.toggle('hidden', !open);
  document.body.classList.toggle('queue-open', open);
  const btn = document.getElementById('np-bar-queue');
  if (btn) btn.classList.toggle('active', open);
  if (open) renderQueuePanel();
}

function renderQueuePanel() {
  const body = document.getElementById('spotify-queue-body');
  if (!body) return;
  if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
    const st = WebPlayback.getState();
    const t = st.current;
    if (!t) {
      body.innerHTML = '<p class="hint" style="padding:12px;color:var(--text-muted)">Nothing playing</p>';
      return;
    }
    const nowArt = artworkUrl(t.path);
    const nowHtml = `<div class="queue-now-playing">
      <div class="queue-track now">
        <div class="queue-track-art">${nowArt ? `<img src="${escHtml(nowArt)}" alt="">` : ''}</div>
        <div class="queue-track-meta">
          <div class="queue-track-title">${escHtml(t.title || '—')}</div>
          <div class="queue-track-artist">${escHtml(t.artist || '')}</div>
        </div>
      </div>
    </div>`;
    const upcoming = WebPlayback.upcoming(20);
    const src = st.sourceLabel || st.playlist || '';
    const nextLabel = src ? `Next from: ${src}` : 'Next up';
    const nextHtml = upcoming.length
      ? `<div class="queue-section-label">${escHtml(nextLabel)}</div>${upcoming.map((tr) => {
        const url = tr.path ? artworkUrl(tr.path) : null;
        return `<div class="queue-track">
          <div class="queue-track-art">${url ? `<img src="${escHtml(url)}" alt="" loading="lazy">` : ''}</div>
          <div class="queue-track-meta">
            <div class="queue-track-title">${escHtml(tr.title || 'Track')}</div>
            <div class="queue-track-artist">${escHtml(tr.artist || '')}</div>
          </div>
        </div>`;
      }).join('')}`
      : '<p class="hint" style="padding:8px;font-size:12px;color:var(--text-muted)">Queue ends after this track</p>';
    body.innerHTML = nowHtml + nextHtml;
    return;
  }
  const items = window._npItems || [];
  const d = npPrimaryDevice(items, window._npControlsAvailable);
  if (!d) {
    body.innerHTML = '<p class="hint" style="padding:12px;color:var(--text-muted)">Nothing playing</p>';
    return;
  }
  const nowArt = artworkUrl(d.filepath);
  const nowHtml = `<div class="queue-now-playing">
    <div class="queue-track now">
      <div class="queue-track-art">${nowArt ? `<img src="${escHtml(nowArt)}" alt="">` : ''}</div>
      <div class="queue-track-meta">
        <div class="queue-track-title">${escHtml(d.track || '—')}</div>
        <div class="queue-track-artist">${escHtml(d.artist || '')}</div>
      </div>
    </div>
  </div>`;
  const upcoming = d.upcoming || [];
  const src = d.sourceLabel || d.playlist || d.context || '';
  const nextLabel = src ? `Next from: ${src}` : 'Next up';
  const nextHtml = upcoming.length
    ? `<div class="queue-section-label">${escHtml(nextLabel)}</div>${upcoming.map((t) => {
      const url = t.path ? artworkUrl(t.path) : null;
      return `<div class="queue-track">
        <div class="queue-track-art">${url ? `<img src="${escHtml(url)}" alt="" loading="lazy">` : ''}</div>
        <div class="queue-track-meta">
          <div class="queue-track-title">${escHtml(t.title || 'Track')}</div>
          <div class="queue-track-artist">${escHtml(t.artist || '')}</div>
        </div>
      </div>`;
    }).join('')}`
    : '<p class="hint" style="padding:8px;font-size:12px;color:var(--text-muted)">Queue ends after this track</p>';
  body.innerHTML = nowHtml + nextHtml;
}

function spotifyBrowsePage(title, filtersHtml, innerHtml) {
  return `<div class="spotify-browse-page">
    ${pageHeaderHtml(title)}
    ${filtersHtml || ''}
    ${innerHtml}
  </div>`;
}

function pageHeaderHtml(title) {
  if (!title) return '';
  return `<div class="spotify-page-header"><h1 class="home-greeting">${escHtml(title)}</h1></div>`;
}

function renderPage(title, html, opts = {}) {
  setPageTitle(title);
  const mc = document.getElementById('main-content');
  if (mc) mc.classList.remove('home-active');
  const body = opts.header === false ? (html || '') : pageHeaderHtml(title) + (html || '');
  if (mc) mc.innerHTML = body;
}

function routeErrorPage(route, err) {
  console.error(`Route failed: ${route}`, err);
  renderPage('Something went wrong', `
    <div class="empty-state">
      <i class="fa fa-triangle-exclamation"></i>
      <p>Could not load this page.</p>
      <p class="hint">${escHtml((err && err.message) || 'Unknown error')}</p>
      <button class="btn-sm btn-primary" onclick="navigate('dashboard')">Go home</button>
    </div>`);
  showToast('Failed to load page', true);
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

// The custom skill's invocation name (collision-safe; see project rules).
const BOCK_INVOCATION = 'bock media';

// Build the exact phrase to put in an Alexa Routine's custom-action box. We use
// "start"/"mix" (NOT play/shuffle) because the music domain hijacks play/shuffle
// + a music-like name before our custom skill is considered.
function buildRoutinePhrase(playlist, shuffle) {
  const verb = shuffle ? 'mix' : 'start';
  return `ask ${BOCK_INVOCATION} to ${verb} the ${playlist} playlist`;
}

register('routines', async () => {
  loading();
  const data = await API('/api/playlists?page=1&limit=500&search=');
  window._routinePlaylists = (data && data.items) || [];
  renderRoutinesBuilder();
});

function renderRoutinesBuilder() {
  const pls = window._routinePlaylists || [];
  const options = pls.map(p => `<option value="${escHtml(p.name)}">${escHtml(p.name)}</option>`).join('');
  renderPage('Routines', `
    <div class="page-desc">
      Amazon doesn't let apps create Routines, so this builds the exact wording for you.
      Pick a playlist and trigger phrase, copy the generated line, then paste it into the
      Alexa app under <b>More &rarr; Routines &rarr; +</b>. We use <b>start</b>/<b>mix</b> (never
      play/shuffle) so the music providers don't hijack the command.
    </div>
    <div class="card" style="max-width:640px">
      <div class="card-header"><h3><i class="fa fa-bolt"></i> Routine Builder</h3></div>
      <div class="card-body">
        ${pls.length ? `
        <label style="display:block;margin:4px 0 4px;font-size:13px;color:#888">Trigger phrase (what you say)</label>
        <input id="rt-trigger" class="settings-input" style="width:100%" placeholder="play my morning music" value="play my morning music">

        <label style="display:block;margin:14px 0 4px;font-size:13px;color:#888">Playlist</label>
        <select id="rt-playlist" class="settings-input" style="width:100%">${options}</select>

        <label style="display:block;margin:14px 0 4px;font-size:13px;color:#888">
          <input type="checkbox" id="rt-shuffle"> Shuffle (uses "mix")
        </label>

        <div style="margin-top:16px">
          <button class="btn-sm btn-primary" onclick="updateRoutineOutput()"><i class="fa fa-wand-magic-sparkles"></i> Generate</button>
        </div>

        <div id="rt-output" style="margin-top:18px"></div>
        ` : `<p class="hint">No playlists found yet.</p>`}
      </div>
    </div>`);
  if (pls.length) updateRoutineOutput();
}

function updateRoutineOutput() {
  const trigger = (document.getElementById('rt-trigger').value || '').trim() || 'play my music';
  const playlist = document.getElementById('rt-playlist').value;
  const shuffle = document.getElementById('rt-shuffle').checked;
  const phrase = buildRoutinePhrase(playlist, shuffle);
  const out = document.getElementById('rt-output');
  out.innerHTML = `
    <div class="rt-steps">
      <ol style="margin:0;padding-left:20px;line-height:1.8">
        <li>Open the <b>Alexa app</b> &rarr; <b>More</b> &rarr; <b>Routines</b> &rarr; <b>+</b>.</li>
        <li><b>When this happens</b> &rarr; <b>Voice</b> &rarr; type: <code>${escHtml(trigger)}</code></li>
        <li><b>Add action</b> &rarr; <b>Custom</b> &rarr; paste the line below.</li>
        <li>(Optional) set the device(s) the routine should run on.</li>
        <li>Save. Then say: <b>"Alexa, ${escHtml(trigger)}"</b></li>
      </ol>
      <div class="rt-phrase-box">
        <code id="rt-phrase">${escHtml(phrase)}</code>
        <button class="btn-sm btn-default" onclick="copyRoutinePhrase()"><i class="fa fa-copy"></i> Copy</button>
      </div>
    </div>`;
}

function copyRoutinePhrase() {
  const text = (document.getElementById('rt-phrase') || {}).textContent || '';
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => showToast('Copied'), () => showToast('Copy failed', true));
  } else {
    showToast('Copy not supported');
  }
}

let _dashPage = 1;

async function dashReplayRecent(i) {
  const r = (window._dashQuickRecent || [])[i];
  if (!r) return;
  const opts = songPlayOpts({ title: r.track, artist: r.artist, path: r.filepath, track: r.track });
  if (opts) return startPlayback(opts);
}

async function dashContinuePlay() {
  const r = window._dashContinue;
  if (!r || !r.filepath) return;
  const opts = songPlayOpts({ title: r.track, artist: r.artist, path: r.filepath, track: r.track });
  if (opts) return startPlayback(opts);
}

async function dashPlayFavorite(i) {
  const r = (window._dashQuickFavs || [])[i];
  if (!r || !r.path) return;
  const opts = songPlayOpts(r);
  if (opts) return startPlayback(opts);
}

async function dashRemoveFavorite(i) {
  const r = (window._dashQuickFavs || [])[i];
  if (!r || !r.path) return;
  await fetch('/api/favorites', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ path: r.path }) });
  showToast('Removed from favorites');
  loadDashboard();
}

async function homePlayTrack(path, title, artist) {
  const opts = songPlayOpts({ path, title, artist, track: title });
  if (opts) return startPlayback(opts);
}

async function homePlayAlbum(album, artist) {
  if (!album) return;
  return startPlayback({ kind: 'album', name: album, artist: artist || '' });
}

async function homePlayNewAlbum(i) {
  const a = (window._homeNewAlbums || [])[i];
  if (!a || !a.album) return;
  return homePlayAlbum(a.album, a.artist || '');
}

window.homePlayNewAlbum = homePlayNewAlbum;

async function homePlayPlaylist(i) {
  const p = (window._homePlaylists || [])[i];
  if (!p) return;
  return startPlayback({ kind: 'playlist', name: p.name, id: p.id });
}

async function homePlayDiscover(sectionIdx, trackIdx) {
  const sec = (window._homeDiscover || [])[sectionIdx];
  const t = sec && sec.tracks && sec.tracks[trackIdx];
  if (!t || !t.path) return;
  return homePlayTrack(t.path, t.title || t.track, t.artist);
}

window.homePlayTrack = homePlayTrack;
window.homePlayAlbum = homePlayAlbum;
window.homePlayPlaylist = homePlayPlaylist;
window.homePlayDiscover = homePlayDiscover;

function homeTrackCard(track, playOnclick) {
  const title = track.title || track.track || 'Track';
  const sub = track.artist || track.album || '';
  const art = track.path || track.filepath || null;
  return spotifyMediaCard(title, sub, '#search', 'fa-music', title, playOnclick, art);
}

function homeAlbumCard(album, artist, path, playOnclick) {
  return spotifyMediaCard(album, artist || 'Album', '#albums', 'fa-compact-disc', album, playOnclick, path);
}

async function homeFeedPlay(sectionIdx, cardIdx) {
  const card = window._homeFeed?.sections?.[sectionIdx]?.cards?.[cardIdx];
  if (!card) return;
  const opts = HomeFeed.cardPlayOpts(card);
  if (opts) return startPlayback(opts);
}
window.homeFeedPlay = homeFeedPlay;

async function homeQuickPlay(cardId) {
  const feed = window._homeFeed;
  if (!feed || !cardId) return;
  for (const sec of feed.sections) {
    const card = sec.cards.find((c) => c.id === cardId);
    if (!card) continue;
    const opts = HomeFeed.cardPlayOpts(card);
    if (opts) return startPlayback(opts);
    return;
  }
}
window.homeQuickPlay = homeQuickPlay;

function homeSectionShowAll(kind) {
  if (kind === 'JumpBackIn') return '#nowplaying';
  if (kind === 'Favorites') return '#search';
  if (kind === 'ExploreThemes') return '#genres';
  if (kind === 'Discover') return '#search';
  return '#playlists';
}

register('dashboard', async () => {
  _dashPage = 1;
  let painted = !!window.__BOOT_HOME_PAINTED__;
  if (typeof WebCache !== 'undefined') {
    const mem = WebCache.peekHome();
    if (mem) {
      renderDashboardUI(mem.feed, mem.covers || {}, window._plRemote || {});
      painted = true;
    } else if (!painted) {
      const disk = WebCache.loadHomeFromDisk();
      if (disk) {
        WebCache.putHome(disk.feed, disk.covers);
        renderDashboardUI(disk.feed, disk.covers || {}, window._plRemote || {});
        painted = true;
      }
    }
  }
  if (!painted) loading();
  await loadDashboard({ hadCache: painted });
});

function discoverWeeklyCardsFromApi(discover) {
  const discoverTracks = (discover && discover.sections && discover.sections[0] && discover.sections[0].tracks) || [];
  return discoverTracks.slice(0, 12).map((t) => {
    const path = t.path;
    if (!path) return null;
    return {
      id: `dw-${path}`,
      title: t.title || path,
      subtitle: (discover.sections[0] && discover.sections[0].reason) || 'Discover Weekly',
      artPath: path,
      playTarget: { kind: 'song', path, title: t.title || path },
      kind: 'Discover',
    };
  }).filter(Boolean);
}

function renderDashboardUI(feed, covers, remote, opts = {}) {
  window._homeFeed = feed;
  const coverFor = (id) => (id && covers[id]) || null;
  const artForCard = (card) => (card.playlistId && coverFor(card.playlistId)) || card.artPath || null;

  const shortcutCards = HomeFeed.homeShortcutCards(feed, 8)
    .filter(HomeFeed.eligibleForHomeShortcut);
  const quickHtml = shortcutCards.map((card) => {
    const play = HomeFeed.cardPlayOpts(card)
      ? `homeQuickPlay(${JSON.stringify(card.id)})`
      : null;
    return spotifyHomeQuickCard(card, artForCard(card), play);
  }).join('');

  const sectionsHtml = feed.sections.map((sec, si) => {
    const cards = sec.cards.slice(0, 12).map((card, ci) =>
      spotifyHomeSectionCard(card, sec.kind, si, ci, artForCard(card))).join('');
    if (!cards) return '';
    return spotifyHomeSection(
      sec.title,
      homeSectionShowAll(sec.kind),
      cards,
      homeSectionFilterGroups(sec.kind),
    );
  }).filter(Boolean);

  const refreshHint = opts.refreshing
    ? '<span class="home-refresh-indicator" aria-live="polite">Updating…</span>'
    : '';

  setPageTitle('Home');
  const mc = document.getElementById('main-content');
  if (mc) mc.classList.add('home-active');
  mc.innerHTML = `
    <div class="home-page">
      <div class="home-top">
        <div class="home-filters" role="tablist" aria-label="Filter home feed">
          <button type="button" class="home-filter active" data-home-filter="all">All</button>
          <button type="button" class="home-filter" data-home-filter="music">Music</button>
          <button type="button" class="home-filter" data-home-filter="playlists">Playlists</button>
          <button type="button" class="home-filter" data-home-filter="podcasts">Podcasts</button>
          <button type="button" class="home-filter" data-home-filter="audiobooks">Audiobooks</button>
          <button type="button" class="home-filter" data-home-filter="radio">Radio</button>
          ${refreshHint}
        </div>
        ${quickHtml ? `<div class="home-quick-grid">${quickHtml}</div>` : ''}
      </div>
      <div class="home-sections">${sectionsHtml.length
    ? sectionsHtml.join('')
    : '<div class="home-empty"><p>Play something from Search or Library to personalize your home feed.</p></div>'}</div>
    </div>`;
}

async function loadDashboard(opts = {}) {
  if (typeof WebCache !== 'undefined' && WebCache.shouldSkipHomeReload()) {
    warmBackgroundCaches();
    return;
  }

  const shuffleSeed = HomeFeed.dayOfYear();
  const hadCache = !!(opts && opts.hadCache);
  if (hadCache && typeof WebCache !== 'undefined') {
    const peek = WebCache.peekHome();
    if (peek) renderDashboardUI(peek.feed, peek.covers || {}, window._plRemote || {}, { refreshing: true });
  }

  const analyticsP = Promise.race([
    API('/api/analytics').catch(() => null),
    new Promise((resolve) => setTimeout(() => resolve(null), 4000)),
  ]);

  const [
    historyData,
    allPlaylists,
    smartData,
    dashboard,
    continueData,
    remote,
  ] = await Promise.all([
    API('/api/nowplaying?page=1&limit=150').catch(() => ({ items: [] })),
    fetchPlaylistsCached(''),
    API('/api/smart_playlists').catch(() => ({ items: [] })),
    API('/api/dashboard/quick').catch(() => ({ recent: [], favorites: [] })),
    API('/api/continue').catch(() => null),
    ensureAlexaRemoteStatus().catch(() => ({})),
  ]);
  window._plRemote = remote;

  const history = (historyData && historyData.items) || [];
  let covers = (typeof WebCache !== 'undefined' && WebCache.peekHome()?.covers) || {};
  const partialFeed = HomeFeed.compose({
    history,
    analytics: null,
    allPlaylists,
    smartPlaylists: (smartData && smartData.items) || [],
    favorites: (dashboard && dashboard.favorites) || [],
    dashboard,
    libraryGenres: [],
    shuffleSeed,
    continueResume: continueData && continueData.resume,
    releaseRadarLabel: null,
    releaseRadarArtPath: null,
    discoverWeeklyCards: [],
  });
  renderDashboardUI(partialFeed, covers, remote, { refreshing: true });

  const needFavorites = !(dashboard && dashboard.favorites && dashboard.favorites.length);
  const [analytics, genres, libraryNew, discover, favData] = await Promise.all([
    analyticsP,
    API('/api/genres?limit=40').catch(() => ({ items: [] })),
    API('/api/library/new?since=7d&limit=50').catch(() => ({ albums: [] })),
    API('/api/recommendations/discover-weekly').catch(() => ({ sections: [] })),
    needFavorites ? API('/api/favorites').catch(() => []) : Promise.resolve([]),
  ]);

  const newAlbums = (libraryNew && libraryNew.albums) || [];
  const releaseLabel = newAlbums.length
    ? `Added this week · ${newAlbums.length} album${newAlbums.length === 1 ? '' : 's'}`
    : null;
  const favorites = (dashboard && dashboard.favorites && dashboard.favorites.length)
    ? dashboard.favorites
    : (Array.isArray(favData) ? favData : []);

  const feed = HomeFeed.compose({
    history,
    analytics,
    allPlaylists,
    smartPlaylists: (smartData && smartData.items) || [],
    favorites,
    dashboard,
    libraryGenres: (genres && genres.items) || [],
    shuffleSeed,
    continueResume: continueData && continueData.resume,
    releaseRadarLabel: releaseLabel,
    releaseRadarArtPath: newAlbums[0]?.path || null,
    discoverWeeklyCards: discoverWeeklyCardsFromApi(discover),
  });

  renderDashboardUI(feed, covers, remote);
  if (typeof WebCache !== 'undefined') {
    WebCache.putHome(feed, covers);
    WebCache.markHomeLoaded();
  }

  refreshHomeCovers(feed, covers).then((newCovers) => {
    if (typeof WebCache !== 'undefined') {
      WebCache.putHome(feed, newCovers);
      WebCache.saveHomeToDisk(feed, newCovers);
    }
    if (currentRoute === 'dashboard') renderDashboardUI(feed, newCovers, remote);
  }).catch(() => {});

  warmBackgroundCaches();
}

// ── Service health card ──────────────────────────────────────────────────────
let _healthTimer = null;

function healthChip(label, state, detail) {
  // state: true=ok(green), false=bad(red), null/undefined=unknown(grey)
  let cls = 'health-chip unknown', icon = 'fa-circle-question';
  if (state === true)  { cls = 'health-chip ok';  icon = 'fa-circle-check'; }
  if (state === false) { cls = 'health-chip bad'; icon = 'fa-circle-xmark'; }
  return `<span class="${cls}" title="${escHtml(detail || '')}"><i class="fa ${icon}"></i> ${escHtml(label)}</span>`;
}

function fmtAgo(secs) {
  if (secs == null) return 'never';
  if (secs < 60) return `${secs}s ago`;
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  if (secs < 86400) return `${Math.floor(secs / 3600)}h ago`;
  return `${Math.floor(secs / 86400)}d ago`;
}

function buildHealthCard(h, remote) {
  if (!h) return '';
  const latency = h.publicLatencyMs != null ? `${h.publicLatencyMs}ms` : '—';
  const skill = h.skillTesting === true ? true : (h.skillTesting === false ? false : null);
  const chips = [
    healthChip('Backend', h.backendHttp, 'Local Flask responding'),
    healthChip('Tunnel', h.tunnelReachable, `Public endpoint (${latency}, status ${h.publicStatus ?? '—'})`),
    healthChip('Alexa session', h.alexaAuth, 'alexapy login valid (Play on device / controls)'),
    healthChip('Skill testing', skill, 'Developer testing enablement'),
    h.plexConfigured ? healthChip('Plex sync', h.plexReachable, 'Plex two-way playlist sync') : '',
  ].filter(Boolean).join('');
  const stale = h.watchdogFresh === false
    ? `<span class="health-stale" title="Watchdog snapshot is stale or missing">watchdog ${fmtAgo(h.watchdogAgeSeconds)}</span>`
    : '';
  const needLogin = remote && remote.configured && remote.authenticated === false;
  const host = window.location.hostname || 'localhost';
  const loginBtn = needLogin
    ? `<div style="margin-top:12px;padding-top:12px;border-top:1px solid #eef1f6">
        <p class="hint" style="margin:0 0 8px">Alexa session expired. Re-login so Play on device and automations work.</p>
        <button class="btn-sm btn-primary" onclick="startAlexaLogin()"><i class="fa fa-key"></i> Start browser login</button>
        <a class="btn-sm btn-default" href="#settings" style="margin-left:8px">Settings</a>
      </div>`
    : '';
  return `
    <div class="card health-card">
      <div class="card-header" style="display:flex;align-items:center;justify-content:space-between">
        <h3><i class="fa fa-heart-pulse"></i> Service Health</h3>
        <span class="health-meta">uptime ${fmtAgo(h.uptimeSeconds)} · last Alexa hit ${fmtAgo(h.lastAlexaHitAgo)} ${stale}</span>
      </div>
      <div class="card-body health-chips">${chips}${loginBtn}</div>
    </div>`;
}

async function loadHealth() {
  const wrap = document.getElementById('health-card-wrap');
  if (!wrap) return;
  const [h, remote] = await Promise.all([
    API('/api/health'),
    ensureAlexaRemoteStatus().catch(() => ({})),
  ]);
  wrap.innerHTML = buildHealthCard(h, remote);
  clearTimeout(_healthTimer);
  // Re-poll only while the dashboard is mounted.
  _healthTimer = setTimeout(() => {
    if (document.getElementById('health-card-wrap')) loadHealth();
  }, 30000);
}

function buildPlaybackCard(pb, remote) {
  if (!pb) return '';
  const ar = pb.alexaRemote || {};
  const authOk = ar.authenticated === true;
  const cfgOk = ar.configured === true;
  const tips = (pb.tips || []).map(t => `<li>${escHtml(t)}</li>`).join('');
  const host = window.location.hostname || 'localhost';
  const loginBlock = cfgOk && !authOk
    ? `<p class="hint" style="margin:8px 0 0"><code>python3 scripts/alexa_login.py --proxy --host ${escHtml(host)} --port 3005</code></p>`
    : '';
  return `
    <div class="card" style="margin-bottom:20px">
      <div class="card-header"><h3><i class="fa fa-tower-broadcast"></i> Playback</h3></div>
      <div class="card-body">
        <p style="margin:0 0 8px">
          Web play: <b>${cfgOk ? (authOk ? `${ar.deviceCount || 0} Echoes ready` : 'login required') : 'not configured'}</b>
          · Skill testing: <b>${pb.skillTesting === true ? 'on' : (pb.skillTesting === false ? 'off' : 'unknown')}</b>
        </p>
        <ul class="hint" style="margin:0;padding-left:18px">${tips}</ul>
        ${loginBlock}
        <a href="#rooms" class="btn-sm btn-default" style="margin-top:10px;display:inline-block"><i class="fa fa-house"></i> Room dashboard</a>
      </div>
    </div>`;
}

async function loadPlaybackCard() {
  const wrap = document.getElementById('playback-card-wrap');
  if (!wrap) return;
  const [pb, remote] = await Promise.all([
    API('/api/playback/status').catch(() => null),
    ensureAlexaRemoteStatus().catch(() => ({})),
  ]);
  wrap.innerHTML = buildPlaybackCard(pb, remote);
}

// ── Now Playing ──────────────────────────────────────────────────────────────
let _npPage = 1;
let _npPollTimer = null;
let _npTickTimer = null;
let _npGlobalTickTimer = null;

register('rooms', async () => {
  loading();
  await loadRooms();
  if (!window._roomsPoll) {
    window._roomsPoll = setInterval(() => {
      if (currentRoute === 'rooms') loadRooms(true);
    }, 8000);
  }
});

async function loadRooms(quiet) {
  const [data, remote] = await Promise.all([
    API('/api/rooms'),
    ensureAlexaRemoteStatus(),
  ]);
  const rooms = (data && data.rooms) || [];
  const canPlay = !!(data && data.controlsAvailable) && !!(remote && remote.configured);
  const cards = rooms.map(r => {
    const np = r.nowPlaying;
    const npHtml = np
      ? `<div class="room-np-track">${escHtml(np.track || '—')}</div>
         ${np.artist ? `<div class="room-np-meta">${escHtml(np.artist)}</div>` : ''}
         ${(np.sourceLabel || np.playlist) ? `<div class="room-np-pl"><i class="fa fa-list"></i> ${escHtml(np.sourceLabel || np.playlist)}</div>` : ''}
         ${np.paused ? '<span class="np-paused-badge">Paused</span>' : ''}`
      : '<div class="room-np-idle">Idle</div>';
    const autos = (r.automations || []).slice(0, 3).map(a =>
      `<div class="room-auto">${escHtml(a.time || '')} · ${escHtml(a.playlistName || '')}${a.enabled === false ? ' (off)' : ''}</div>`
    ).join('') || '<div class="room-auto text-muted">No automations</div>';
    const playBtn = canPlay && r.serial && !r.pseudo
      ? `<button class="btn-sm btn-default" onclick="roomQuickPlay('${escHtml(r.serial)}','${escHtml(r.name)}')"><i class="fa fa-play"></i> Play…</button>`
      : '';
    return `
      <div class="room-card${r.pseudo ? ' room-pseudo' : ''}">
        <div class="room-card-head">
          <i class="fa fa-${r.pseudo ? 'tower-broadcast' : 'volume-high'}"></i>
          <strong>${escHtml(r.name)}</strong>
        </div>
        <div class="room-card-body">${npHtml}</div>
        <div class="room-card-foot">
          <div class="room-autos-label">Schedules</div>
          ${autos}
          <div style="margin-top:8px;display:flex;gap:6px;flex-wrap:wrap">
            ${playBtn}
            <a href="#automation" class="btn-sm btn-default">Automations</a>
            <a href="#nowplaying" class="btn-sm btn-default">Now Playing</a>
          </div>
        </div>
      </div>`;
  }).join('');
  const inner = cards || '<div class="empty-state"><p>No Echo devices found — sign in via Settings &amp; more → Alexa Remote.</p></div>';
  if (quiet) {
    const grid = document.querySelector('.rooms-grid');
    if (grid) grid.outerHTML = `<div class="rooms-grid">${inner}</div>`;
  } else {
    renderPage('Rooms', `
      <div class="rooms-grid">${inner}</div>`);
  }
}

async function roomQuickPlay(serial, name) {
  const pl = prompt(`Playlist to start on ${name}:`, '');
  if (!pl || !pl.trim()) return;
  try {
    const res = await fetch('/api/playlists/play', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ device: serial, name: pl.trim(), kind: 'playlist' }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      if (isAlexaAuthError(data.error || '')) {
        handleAlexaAuthFailure();
        return showToast('Alexa session expired — sign in to continue', true);
      }
      const msg = data.error || 'Play failed';
      return showToast(msg, true);
    }
    showToast(`Playing on ${data.device || name}`);
    loadRooms(true);
  } catch (e) {
    showToast(e.message || 'Play failed', true);
  }
}

register('nowplaying', async () => {
  _npPage = 1;
  loading();
  clearInterval(_npPollTimer);
  clearInterval(_npTickTimer);
  await loadNowPlaying();
  _npPollTimer = setInterval(async () => {
    await refreshCurrentTrack();
  }, 2000);
  if (!_npGlobalTickTimer) {
    _npGlobalTickTimer = setInterval(npTickTimes, 1000);
  }
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

function npFmtSec(sec) {
  sec = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}

// Elapsed seconds since the track started: server reports offset_ms at the time
// it last wrote `timestamp`, so we extrapolate in real time while playing.
function npElapsedSec(d) {
  const base = (d.offset_ms || 0) / 1000;
  const since = d.paused ? 0 : Math.max(0, Date.now() / 1000 - (d.timestamp || 0));
  let elapsed = base + (d.timestamp ? since : 0);
  const dur = (d.duration_ms || 0) / 1000;
  if (dur) elapsed = Math.min(elapsed, dur);
  return elapsed;
}

function npTimeText(d) {
  const dur = (d.duration_ms || 0) / 1000;
  const cur = npFmtSec(npElapsedSec(d));
  return dur ? ` &nbsp; ${cur} / ${npFmtSec(dur)}` : ` &nbsp; ${cur}`;
}

function npProgressPct(d) {
  const dur = (d.duration_ms || 0) / 1000;
  if (!dur) return 0;
  return Math.min(100, (npElapsedSec(d) / dur) * 100);
}

function npProgressHtml(d) {
  const dur = (d.duration_ms || 0) / 1000;
  if (!dur) return '';
  const pct = npProgressPct(d);
  return `<div class="np-progress" data-device-id="${escHtml(d.deviceId)}"><div class="np-progress-fill" style="width:${pct.toFixed(1)}%"></div></div>`;
}

function npUpcomingHtml(d) {
  const up = d.upcoming || [];
  if (!up.length) return '';
  const rows = up.map((t, i) =>
    `<li>${i + 2}. ${escHtml(t.title || '—')}${t.artist ? ' — ' + escHtml(t.artist) : ''}</li>`
  ).join('');
  return `<div class="np-upcoming"><div class="np-upcoming-label">Up next</div><ol>${rows}</ol></div>`;
}

// Tick the time displays in place (smooth) between the 5s data polls.
function npTickTimes() {
  if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
    updateWebPlayerBarProgress(WebPlayback.getState());
    return;
  }
  const items = window._npItems || [];
  document.querySelectorAll('.np-time').forEach(el => {
    const d = items.find(x => x.deviceId === el.dataset.deviceId);
    if (d) el.innerHTML = npTimeText(d);
  });
  document.querySelectorAll('.np-progress').forEach(el => {
    const d = items.find(x => x.deviceId === el.dataset.deviceId);
    if (!d) return;
    const fill = el.querySelector('.np-progress-fill');
    if (fill) fill.style.width = `${npProgressPct(d).toFixed(1)}%`;
  });
  const primary = npPrimaryDevice(items, window._npControlsAvailable);
  if (primary && !primary.paused) updatePlayerBarProgress(primary);
}

function npPrimaryDevice(items, controlsAvailable) {
  const list = items || [];
  const controllable = list.find(d => npCanControl(d, controlsAvailable));
  return controllable || list[0] || null;
}

function npRefreshNowPlayingUI() {
  const card = document.getElementById('np-current-card');
  if (card) card.outerHTML = buildCurrentCard(window._npItems, window._npControlsAvailable);
  renderPlayerBar();
  if (!document.getElementById('spotify-queue')?.classList.contains('hidden')) renderQueuePanel();
  npLoadVolumes();
}

function updatePlayerBarProgress(d) {
  if (!d) return;
  const cur = document.getElementById('np-bar-time-curr');
  const dur = document.getElementById('np-bar-time-dur');
  const fill = document.getElementById('np-bar-progress-fill');
  const elapsed = npElapsedSec(d);
  const durSec = (d.duration_ms || 0) / 1000;
  if (cur) cur.textContent = npFmtSec(elapsed);
  if (dur) dur.textContent = durSec ? npFmtSec(durSec) : '0:00';
  if (fill) fill.style.width = `${npProgressPct(d).toFixed(1)}%`;
}

function updateWebPlayerBarProgress(st) {
  if (!st) return;
  const cur = document.getElementById('np-bar-time-curr');
  const dur = document.getElementById('np-bar-time-dur');
  const fill = document.getElementById('np-bar-progress-fill');
  const posSec = (st.positionMs || 0) / 1000;
  const durSec = (st.durationMs || 0) / 1000;
  if (cur) cur.textContent = npFmtSec(posSec);
  if (dur) dur.textContent = durSec ? npFmtSec(durSec) : '0:00';
  if (fill) fill.style.width = durSec ? `${Math.min(100, (posSec / durSec) * 100).toFixed(1)}%` : '0%';
}

function renderWebPlayerBar(st) {
  const bar = document.getElementById('now-playing-bar');
  if (!bar) return;
  st = st || (typeof WebPlayback !== 'undefined' ? WebPlayback.getState() : null);
  if (!st || !st.active || !st.current) return;
  const t = st.current;
  bar.classList.remove('hidden');
  bar.dataset.mode = 'web';
  bar.dataset.deviceId = '';

  const txt = document.getElementById('np-track-text');
  const sub = document.getElementById('np-artist-text');
  if (txt) txt.textContent = t.title || '—';
  if (sub) {
    const src = st.sourceLabel ? ` · ${st.sourceLabel}` : '';
    sub.textContent = (t.artist || '') + src;
  }

  const artImg = document.getElementById('np-art');
  const artFallback = bar.querySelector('.player-art-fallback');
  const url = artworkUrl(t.path);
  if (artImg) {
    if (url) {
      artImg.src = url;
      artImg.hidden = false;
      if (artFallback) artFallback.style.display = 'none';
      artImg.onerror = () => {
        artImg.hidden = true;
        if (artFallback) artFallback.style.display = '';
      };
    } else {
      artImg.removeAttribute('src');
      artImg.hidden = true;
      if (artFallback) artFallback.style.display = '';
    }
  }

  const playBtn = document.getElementById('np-mini-play');
  if (playBtn) {
    playBtn.disabled = false;
    const icon = playBtn.querySelector('i');
    if (icon) icon.className = st.playing ? 'fa fa-pause' : 'fa fa-play';
    playBtn.setAttribute('aria-label', st.playing ? 'Pause' : 'Play');
  }

  bar.querySelectorAll('#np-bar-shuffle, #np-bar-prev, #np-bar-next').forEach((btn) => {
    btn.disabled = false;
    btn.classList.remove('disabled');
  });
  const shuffleBtn = document.getElementById('np-bar-shuffle');
  if (shuffleBtn) shuffleBtn.classList.toggle('active', !!st.shuffle);

  updateWebPlayerBarProgress(st);

  const volWrap = document.getElementById('np-bar-volume-wrap');
  const volSlider = document.getElementById('np-bar-volume');
  const volBtn = document.getElementById('np-bar-volume-btn');
  if (volWrap) volWrap.classList.remove('hidden');
  if (volSlider) {
    volSlider.disabled = false;
    if (!window._playerBarVolumeDragging) {
      volSlider.value = Math.round((st.volume ?? 0.85) * 100);
    }
  }
  if (volBtn) volBtn.disabled = false;

  const favBtn = document.getElementById('np-bar-favorite');
  if (favBtn) {
    favBtn.disabled = !t.path;
    favBtn.dataset.favPath = t.path || '';
    favBtn.dataset.favTitle = t.title || '';
    favBtn.dataset.favArtist = t.artist || '';
    favBtn.style.visibility = t.path ? '' : 'hidden';
  }

  ['np-bar-repeat', 'np-bar-sleep'].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.style.display = 'none';
  });
}

function renderPlayerBar() {
  const bar = document.getElementById('now-playing-bar');
  if (!bar) return;
  if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
    renderWebPlayerBar(WebPlayback.getState());
    return;
  }
  bar.dataset.mode = 'alexa';
  ['np-bar-repeat', 'np-bar-sleep'].forEach((id) => {
    const el = document.getElementById(id);
    if (el) el.style.display = '';
  });
  const items = window._npItems || [];
  const controlsAvailable = window._npControlsAvailable;
  const d = npPrimaryDevice(items, controlsAvailable);

  if (!items.length || !d) {
    bar.classList.add('hidden');
    bar.dataset.deviceId = '';
    return;
  }
  bar.classList.remove('hidden');
  bar.dataset.deviceId = d.deviceId || '';

  const txt = document.getElementById('np-track-text');
  const sub = document.getElementById('np-artist-text');
  const track = d.track || '—';
  const artist = d.artist || '';
  if (txt) {
    txt.textContent = items.length === 1 ? track : `${track} (+${items.length - 1} more)`;
  }
  if (sub) {
    const devLabel = items.length > 1
      ? ` · ${items.length} devices`
      : (d.deviceName ? ` · ${d.deviceName}` : '');
    sub.textContent = artist ? `${artist}${devLabel}` : devLabel.replace(/^ · /, '');
  }

  const artImg = document.getElementById('np-art');
  const artFallback = bar.querySelector('.player-art-fallback');
  const url = artworkUrl(d.filepath);
  if (artImg) {
    if (url) {
      artImg.src = url;
      artImg.hidden = false;
      if (artFallback) artFallback.style.display = 'none';
      artImg.onerror = () => {
        artImg.hidden = true;
        if (artFallback) artFallback.style.display = '';
      };
    } else {
      artImg.removeAttribute('src');
      artImg.hidden = true;
      if (artFallback) artFallback.style.display = '';
    }
  }

  const canControl = npCanControl(d, controlsAvailable);
  const setDeviceId = (sel) => {
    bar.querySelectorAll(sel).forEach((btn) => {
      btn.dataset.deviceId = d.deviceId || '';
      btn.disabled = !canControl;
      btn.classList.toggle('disabled', !canControl);
    });
  };
  setDeviceId('#np-bar-shuffle, #np-bar-prev, #np-bar-next, #np-bar-repeat, #np-bar-sleep');

  const playBtn = document.getElementById('np-mini-play');
  if (playBtn) {
    playBtn.dataset.deviceId = d.deviceId || '';
    playBtn.disabled = !canControl;
    const icon = playBtn.querySelector('i');
    if (icon) icon.className = d.paused ? 'fa fa-play' : 'fa fa-pause';
    playBtn.setAttribute('aria-label', d.paused ? 'Play' : 'Pause');
  }

  const shuffleBtn = document.getElementById('np-bar-shuffle');
  if (shuffleBtn) {
    window._npShuffle = window._npShuffle || {};
    shuffleBtn.classList.toggle('active', !!window._npShuffle[d.deviceId]);
  }

  updatePlayerBarProgress(d);

  const volWrap = document.getElementById('np-bar-volume-wrap');
  const volSlider = document.getElementById('np-bar-volume');
  const volBtn = document.getElementById('np-bar-volume-btn');
  if (volWrap) volWrap.classList.toggle('hidden', !canControl);
  if (volSlider) {
    volSlider.dataset.deviceId = d.deviceId || '';
    volSlider.disabled = !canControl;
    if (!window._playerBarVolumeDragging) {
      window._npVolume = window._npVolume || {};
      const knownVol = window._npVolume[d.deviceId];
      volSlider.value = knownVol == null ? 50 : knownVol;
    }
  }
  if (volBtn) volBtn.disabled = !canControl;

  const favBtn = document.getElementById('np-bar-favorite');
  if (favBtn) {
    favBtn.dataset.deviceId = d.deviceId || '';
    favBtn.disabled = !d.filepath;
    favBtn.style.visibility = d.filepath ? '' : 'hidden';
  }
}

async function refreshCurrentTrack() {
  const [data, remote] = await Promise.all([
    API('/api/nowplaying_devices'),
    ensureAlexaRemoteStatus().catch(() => ({})),
  ]);
  window._npItems = (data && data.items) || [];
  window._npControlsAvailable = !!(data && data.controlsAvailable) && !!(remote && remote.configured);
  if (window._npControlsAvailable && window._npItems.length) {
    await ensureAlexaDevices().catch(() => []);
  }
  renderPlayerBar();
  const card = document.getElementById('np-current-card');
  if (card) card.outerHTML = buildCurrentCard(window._npItems, window._npControlsAvailable);
  if (!document.getElementById('spotify-queue')?.classList.contains('hidden')) renderQueuePanel();
  npLoadVolumes();
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
      ${actionBtn({ kind: 'muted', onclick: 'npOpenSleepEl(this)', title: 'Sleep timer', icon: 'moon', dataAttrs: devAttr })}
      ${d.filepath ? actionBtn({ kind: 'muted', onclick: 'npFavoriteEl(this)', title: 'Add to favorites', icon: 'star', dataAttrs: devAttr }) : ''}
      ${d.filepath ? actionBtn({ kind: 'muted', onclick: 'npNeverAgainEl(this)', title: 'Never play this song again', icon: 'ban', dataAttrs: devAttr }) : ''}
      ${actionBtn({ kind: 'delete', onclick: "npControlEl(this,'stop')", title: 'Stop', icon: 'stop', dataAttrs: devAttr })}
    </div>` : '';
  const sleepBadge = d.sleep ? `<span class="np-sleep-badge" title="Sleep timer armed"><i class="fa fa-moon"></i> ${
    d.sleep.type === 'time' ? `${d.sleep.remainingMin}m` : `${d.sleep.remaining} left`}</span>` : '';
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
        ${npArtworkHtml(d.filepath)}
        <div class="np-device-meta">
          <div class="np-track">${escHtml(d.track || '—')} ${sleepBadge}</div>
          ${d.artist ? `<div class="np-artist">${escHtml(d.artist)}</div>` : ''}
          ${d.album ? `<div class="np-album">${escHtml(d.album)}</div>` : ''}
          ${(d.sourceLabel || d.playlist) ? `<div class="np-playlist"><i class="fa fa-list"></i> ${escHtml(d.sourceLabel || d.playlist)}</div>` : ''}
          <div class="np-device-label">Device: ${escHtml(d.deviceName || (d.deviceId || '').slice(-12) || 'default')}${d.platform ? ` <span class="badge" style="font-size:10px;text-transform:uppercase">${escHtml(d.platform)}</span>` : (String(d.deviceId || '').startsWith('client-') ? ' <span class="badge" style="font-size:10px">mobile</span>' : '')}<span class="np-time" data-device-id="${escHtml(d.deviceId)}">${npTimeText(d)}</span></div>
          ${npProgressHtml(d)}
          ${npUpcomingHtml(d)}
        </div>
      </div>
      ${controls}
      ${volume}
    </div>`;
}

async function npFavoriteEl(btn) {
  const deviceId = btn && btn.dataset && btn.dataset.deviceId;
  const d = (window._npItems || []).find(x => x.deviceId === deviceId);
  if (!d || !d.filepath) return;
  const res = await fetch('/api/favorites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: d.filepath, title: d.track, artist: d.artist, album: d.album }),
  });
  if (res.ok) showToast(`Starred "${d.track || 'track'}"`);
  else showToast((await res.json().catch(() => ({}))).error || 'Failed', true);
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
      if (isAlexaAuthError(data.error || '')) {
        handleAlexaAuthFailure();
        return showToast('Alexa session expired — sign in to continue', true);
      }
      return showToast(data.error || 'Control failed', true);
    }
    const fresh = await API('/api/nowplaying_devices');
    window._npItems = (fresh && fresh.items) || window._npItems;
    npRefreshNowPlayingUI();
  } catch (e) {
    showToast(e.message || 'Control failed', true);
  }
}

async function npNeverAgainEl(btn) {
  const deviceId = btn && btn.dataset && btn.dataset.deviceId;
  const d = (window._npItems || []).find(x => x.deviceId === deviceId);
  if (!d || !d.filepath) return;
  if (!confirm(`Never play "${d.track || 'this song'}" again? It will be skipped in future playback.`)) return;
  const res = await fetch('/api/ignored', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path: d.filepath }),
  });
  if (!res.ok) return showToast('Failed to ignore track', true);
  showToast(`"${d.track || 'Song'}" won't play again`);
  // Skip it now on the device if we can control it.
  if (npCanControl(d, window._npControlsAvailable)) npControl(deviceId, 'next');
}

function npOpenSleepEl(btn) {
  const deviceId = btn && btn.dataset && btn.dataset.deviceId;
  if (!deviceId) return;
  npOpenSleep(deviceId);
}

function npOpenSleep(deviceId) {
  const d = (window._npItems || []).find(x => x.deviceId === deviceId);
  const armed = d && d.sleep;
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay sleep-modal';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  const opt = (label, payload) =>
    `<button class="btn-sm btn-default sleep-opt" data-payload='${JSON.stringify(payload)}'>${label}</button>`;
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:360px">
      <h3 style="margin-top:0"><i class="fa fa-moon"></i> Sleep timer</h3>
      <p class="hint" style="margin:0 0 10px">Playback stops at the end of the current song.</p>
      <div class="sleep-opts">
        ${opt('15 min', { minutes: 15 })}
        ${opt('30 min', { minutes: 30 })}
        ${opt('45 min', { minutes: 45 })}
        ${opt('60 min', { minutes: 60 })}
        ${opt('After this song', { songs: 1 })}
        ${opt('After 3 songs', { songs: 3 })}
      </div>
      <div style="display:flex;gap:8px;justify-content:space-between;margin-top:16px">
        ${armed ? `<button class="cancel-btn" id="sleep-cancel">Cancel timer</button>` : '<span></span>'}
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Close</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelectorAll('.sleep-opt').forEach(b => {
    b.onclick = () => npSetSleep(deviceId, JSON.parse(b.dataset.payload), overlay);
  });
  const cancel = overlay.querySelector('#sleep-cancel');
  if (cancel) cancel.onclick = () => npSetSleep(deviceId, {}, overlay);
}

async function npSetSleep(deviceId, payload, overlay) {
  const res = await fetch('/api/nowplaying/sleep', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deviceId, ...payload }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    return showToast(data.error === 'nothing_playing' ? 'Nothing is playing on that device' : (data.error || 'Failed'), true);
  }
  if (overlay) overlay.remove();
  showToast(payload.minutes ? `Sleeping in ${payload.minutes} min`
    : payload.songs ? `Stopping after ${payload.songs} song${payload.songs === 1 ? '' : 's'}`
    : 'Sleep timer cancelled');
  const fresh = await API('/api/nowplaying_devices');
  window._npItems = (fresh && fresh.items) || window._npItems;
  npRefreshNowPlayingUI();
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
  btn.classList.toggle('active', on);
  renderPlayerBar();
}

// Map a now-playing row to the device group it belongs to (by serial), so
// multi-room playback of the same track collapses into one parent row.
function npGroupNameForItem(d) {
  const serial = npResolveSerial(d);
  if (!serial) return '';
  const g = (window._deviceGroups || []).find(grp =>
    (grp.members || []).some(m => m.serial === serial));
  return g ? g.name : '';
}

// Collapse rows that share a device group AND the same track into a group
// entry; everything else stays a single row. Preserves input order.
function groupNowPlaying(list) {
  const out = [];
  const byKey = new Map();
  for (const d of list) {
    const gname = npGroupNameForItem(d);
    const track = (d.track || '').trim();
    const key = gname && track ? `${gname}\u0000${track}` : null;
    if (!key) { out.push({ type: 'single', item: d }); continue; }
    if (byKey.has(key)) {
      byKey.get(key).members.push(d);
    } else {
      const entry = { type: 'group', name: gname, track,
                      artist: d.artist, album: d.album, members: [d] };
      byKey.set(key, entry);
      out.push(entry);
    }
  }
  // A "group" of one isn't a group — demote back to a single row.
  return out.map(e => (e.type === 'group' && e.members.length < 2)
    ? { type: 'single', item: e.members[0] } : e);
}

function buildGroupRow(g, controlsAvailable) {
  const sub = g.members.map(d => buildDeviceRow(d, controlsAvailable)).join('');
  const groupArt = g.members[0] ? npArtworkHtml(g.members[0].filepath) : '';
  return `
    <div class="np-group">
      <div class="np-group-header">
        ${groupArt}
        <div class="np-group-header-text">
          <div class="np-group-header-top">
            <i class="fa fa-layer-group"></i>
            <span class="np-group-name">${escHtml(g.name)}</span>
            <span class="np-group-count">${g.members.length} speakers</span>
          </div>
          <span class="np-group-track">${escHtml(g.track || '—')}${g.artist ? ' — ' + escHtml(g.artist) : ''}${g.members[0] && (g.members[0].sourceLabel || g.members[0].playlist) ? ' · <i class="fa fa-list"></i> ' + escHtml(g.members[0].sourceLabel || g.members[0].playlist) : ''}</span>
        </div>
      </div>
      <div class="np-group-members">${sub}</div>
    </div>`;
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
  const entries = groupNowPlaying(list);
  const body = entries.map(e => e.type === 'group'
    ? buildGroupRow(e, controlsAvailable)
    : buildDeviceRow(e.item, controlsAvailable)).join('');
  return `
    <div class="card" id="np-current-card" style="border-left:4px solid #e99d1a;margin-bottom:20px">
      <div class="card-body">
        ${header}
        ${body}
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
    // Device groups drive group-aware Now Playing (collapse multi-room playback).
    if (!window._deviceGroups) {
      const g = await API('/api/device_groups').catch(() => null);
      window._deviceGroups = (g && g.items) || [];
    }
  }

  const currentCard = buildCurrentCard(window._npItems, window._npControlsAvailable);

  const rows = items.map(e => `
    <tr>
      <td>${escHtml(e.track || '—')}</td>
      <td class="text-muted">${escHtml(e.artist || '—')}</td>
      <td class="text-muted">${escHtml(e.sourceLabel || e.playlist || '—')}</td>
      <td><span class="badge">${escHtml(e.device || '—')}</span></td>
      <td class="text-muted" style="font-size:11px">${fmtDateTime(e.date)}</td>
    </tr>`).join('');

  renderPage('Now Playing', `
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
        <thead><tr><th>Track</th><th>Artist</th><th>Source</th><th>Device</th><th>Date</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
      ${buildPagination(total, _npPage, 25, (p) => { _npPage = p; loadNowPlaying(); })}
      ` : `<div class="empty-state"><i class="fa fa-play"></i><p>No streaming history found.</p></div>`}
    </div>`);
  npLoadVolumes();
  renderPlayerBar();
}


register('library', async () => {
  let painted = false;
  if (typeof WebCache !== 'undefined') {
    const snap = WebCache.peekLibrary() || WebCache.hydrateLibraryFromDisk();
    if (snap) {
      window._libPlaylists = snap.playlists || [];
      window._libSmart = snap.smart || [];
      window._libFolders = snap.folders || [];
      window._libGenres = snap.genres || [];
      window._libRemote = window._plRemote || window._libRemote || {};
      renderLibraryPage();
      painted = true;
    }
  }
  if (!painted) loading();
  await loadLibrary({ hadCache: painted });
});

register('genres', async () => {
  await loadGenresPage();
});

// ── Playlists ────────────────────────────────────────────────────────────────
let _plPage = 1, _plSearch = '', _plMergeSel = new Set(), _plDetailId = null, _plFolderFilter = '';
let _plDetailSort = { by: 'title', order: 'asc' };
let _plDetailPage = 1;
let _plDetailQ = '';
const _plDetailPageSize = 100;
let _plListSort = { by: 'name', order: 'asc' };
const _plPageSize = 100;
let _plAllCache = null;
let _plAllCacheSearch = null;

function plSortPlaylistsInMemory(list, by, order) {
  const desc = order === 'desc';
  const out = (list || []).slice();
  if (by === 'trackCount') {
    out.sort((a, b) => {
      const d = (a.trackCount || 0) - (b.trackCount || 0);
      return desc ? -d : d;
    });
  } else {
    out.sort((a, b) => {
      const d = (a.name || '').localeCompare(b.name || '', undefined, { sensitivity: 'base' });
      return desc ? -d : d;
    });
  }
  return out;
}

function plSortIndicator(active, order) {
  if (!active) return '<span class="pl-sort-icon">↕</span>';
  return order === 'desc' ? '<span class="pl-sort-icon active">↓</span>' : '<span class="pl-sort-icon active">↑</span>';
}

function plListSort(by) {
  if (_plListSort.by === by) {
    _plListSort.order = _plListSort.order === 'asc' ? 'desc' : 'asc';
  } else {
    _plListSort = { by, order: 'asc' };
  }
  _plPage = 1;
  const label = by === 'trackCount' ? 'Tracks' : 'Name';
  const arrow = _plListSort.order === 'desc' ? 'Z→A' : 'A→Z';
  showToast(`Sorted by ${label} (${arrow})`);
  renderPlaylistsPage();
}
window.plListSort = plListSort;

function plSortTracksInMemory(tracks, by, order) {
  const desc = order === 'desc';
  const out = (tracks || []).slice();
  const key = by === 'title' ? 'title' : by;
  out.sort((a, b) => {
    const av = (a[key] || '').toString();
    const bv = (b[key] || '').toString();
    const d = av.localeCompare(bv, undefined, { sensitivity: 'base' });
    return desc ? -d : d;
  });
  return out;
}

function plSortDetailCol(by) {
  const field = by === 'track' ? 'title' : by;
  const order = (_plDetailSort.by === field && _plDetailSort.order === 'asc') ? 'desc' : 'asc';
  plApplyDetailSort(field, order);
}
window.plSortDetailCol = plSortDetailCol;

function plApplyDetailSort(by, order) {
  _plDetailSort = { by, order };
  _plDetailPage = 1;
  const label = by === 'title' ? 'Track' : (by.charAt(0).toUpperCase() + by.slice(1));
  showToast(`Sorting by ${label} (${order === 'desc' ? 'Z→A' : 'A→Z'})…`);
  loadPlaylistDetailPage(true).then(() => plPersistDetailSort(by, order));
}

async function plPersistDetailSort(by, order) {
  const id = _plDetailId || (window._plDetail && window._plDetail.id);
  if (!id) return;
  try {
    const res = await fetch(`/api/playlists/${encodeURIComponent(id)}/sort`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ by, order }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      showToast(data.error || 'Could not save sort to playlist file', true);
    }
  } catch (e) {
    showToast(e.message || 'Could not save sort', true);
  }
}

function setupPlaylistSortDelegation() {
  if (window._plSortDelegation) return;
  window._plSortDelegation = true;
  document.addEventListener('click', (e) => {
    if (currentRoute !== 'playlists') return;
    const listTh = e.target.closest('th[data-pl-sort]');
    if (listTh) {
      e.preventDefault();
      e.stopPropagation();
      plListSort(listTh.getAttribute('data-pl-sort'));
      return;
    }
    const detailTh = e.target.closest('th[data-pl-sort-col]');
    if (detailTh) {
      e.preventDefault();
      e.stopPropagation();
      plSortDetailCol(detailTh.getAttribute('data-pl-sort-col'));
    }
  }, true);
}

register('playlists', async (params) => {
  if (params && params.startsWith('detail/')) {
    _plDetailId = params.slice(7);
    _plDetailPage = 1;
    _plDetailQ = '';
    _plDetailSort = { by: 'title', order: 'asc' };
    loading();
    document.getElementById('main-content').innerHTML =
      '<div class="spinner-wrap"><div class="spinner"></div><p style="text-align:center;color:#888;margin-top:12px">Loading playlist…</p></div>';
    return loadPlaylistDetail(_plDetailId);
  }
  _plDetailId = null;
  if (params) { _plPage = 1; _plSearch = ''; }
  loading();
  await loadPlaylists();
});

function plToggleMerge(id, checked) {
  if (checked) _plMergeSel.add(id);
  else _plMergeSel.delete(id);
}

function openNewPlaylistModal() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:420px">
      <h3 style="margin-top:0"><i class="fa fa-plus"></i> New playlist</h3>
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">Name</label>
      <input type="text" id="pl-new-name" class="settings-input" style="width:100%" placeholder="My playlist">
      <p class="hint" style="margin:12px 0 0">Starts empty — open it to add tracks from Search, or merge other playlists.</p>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
        <button class="save-btn" id="pl-new-go">Create</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#pl-new-go').onclick = async () => {
    const name = overlay.querySelector('#pl-new-name').value.trim();
    if (!name) return showToast('Name required', true);
    const res = await fetch('/api/playlists', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, tracks: [] }),
    });
    const data = await res.json().catch(() => ({}));
    overlay.remove();
    if (!res.ok) return showToast(data.error || 'Create failed', true);
    showToast(`Created "${data.name}"`);
    window.location.hash = `playlists/detail/${data.id}`;
  };
  overlay.querySelector('#pl-new-name').focus();
}

function openSmartPlaylistModal() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:440px">
      <h3 style="margin-top:0"><i class="fa fa-wand-magic-sparkles"></i> Smart playlist</h3>
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">Name</label>
      <input type="text" id="sp-name" class="settings-input" style="width:100%" placeholder="Evening jazz">
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">Genre contains</label>
      <input type="text" id="sp-genre" class="settings-input" style="width:100%" placeholder="jazz">
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">Artist contains</label>
      <input type="text" id="sp-artist" class="settings-input" style="width:100%" placeholder="">
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">Max tracks</label>
      <input type="number" id="sp-limit" class="settings-input" style="width:100%" value="40" min="1" max="500">
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
        <button class="save-btn" id="sp-go">Create & refresh</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#sp-go').onclick = async () => {
    const name = overlay.querySelector('#sp-name').value.trim();
    if (!name) return showToast('Name required', true);
    const rules = [{ type: 'limit', value: parseInt(overlay.querySelector('#sp-limit').value, 10) || 40 }];
    const genre = overlay.querySelector('#sp-genre').value.trim();
    const artist = overlay.querySelector('#sp-artist').value.trim();
    if (genre) rules.unshift({ type: 'genre', value: genre });
    if (artist) rules.unshift({ type: 'artist', value: artist });
    const res = await fetch('/api/smart_playlists', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, rules, refresh: true }),
    });
    const data = await res.json().catch(() => ({}));
    overlay.remove();
    if (!res.ok) return showToast(data.error || 'Failed', true);
    showToast(`Smart playlist "${data.name}" — ${fmtNum(data.trackCount)} tracks`);
    _plAllCache = null;
    loadPlaylists();
  };
}

async function refreshSmartPlaylist(id) {
  const res = await fetch(`/api/smart_playlists/${encodeURIComponent(id)}/refresh`, { method: 'POST' });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) return showToast(data.error || 'Refresh failed', true);
  showToast(`Refreshed — ${fmtNum(data.trackCount)} tracks`);
  _plAllCache = null;
  loadPlaylists();
}

async function deleteSmartPlaylist(id) {
  if (!confirm('Delete this smart playlist rule? (Linked playlist file is kept.)')) return;
  const res = await fetch(`/api/smart_playlists/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) return showToast('Delete failed', true);
  loadPlaylists();
}

function openMergePlaylistsModal() {
  const ids = [..._plMergeSel];
  if (ids.length < 2) return showToast('Select at least 2 playlists (checkboxes)', true);
  const names = ids.map(id => (window._playlists || []).find(p => p.id === id)?.name || id);
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:480px">
      <h3 style="margin-top:0"><i class="fa fa-code-merge"></i> Merge playlists</h3>
      <p class="hint" style="margin:0 0 10px">Merging: ${names.map(n => escHtml(n)).join(', ')}</p>
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">New playlist name (optional)</label>
      <input type="text" id="pl-merge-name" class="settings-input" style="width:100%" placeholder="Combined playlist">
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
        <button class="save-btn" id="pl-merge-go">Merge</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#pl-merge-go').onclick = async () => {
    const name = overlay.querySelector('#pl-merge-name').value.trim();
    const res = await fetch('/api/playlists/merge', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sourceIds: ids, name: name || undefined }),
    });
    const data = await res.json().catch(() => ({}));
    overlay.remove();
    if (!res.ok) return showToast(data.error || 'Merge failed', true);
    _plMergeSel.clear();
    showToast(`Merged into "${data.name}" (${data.trackCount} tracks)`);
    window.location.hash = `playlists/detail/${data.id}`;
  };
}

function openAiPlaylistModal() {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:520px">
      <h3 style="margin-top:0"><i class="fa fa-wand-magic-sparkles"></i> Mix Muse</h3>
      <p class="hint" style="margin:0 0 8px">Describe the vibe — Claude or OpenAI picks tracks from your library. Set <code>claude.apiKey</code> and/or <code>openai.apiKey</code> in config.json.</p>
      <label style="display:block;margin:8px 0 4px;font-size:13px;color:#888">Prompt</label>
      <textarea id="pl-ai-prompt" class="settings-input" rows="3" style="width:100%" placeholder="Upbeat yacht rock for a summer drive…"></textarea>
      <label style="display:block;margin:12px 0 4px;font-size:13px;color:#888">Playlist name (optional)</label>
      <input type="text" id="pl-ai-name" class="settings-input" style="width:100%">
      <label style="display:block;margin:12px 0 4px;font-size:13px;color:#888">Max tracks</label>
      <input type="number" id="pl-ai-max" class="settings-input" value="25" min="5" max="80" style="width:80px">
      <div id="pl-ai-preview" style="margin-top:12px;max-height:200px;overflow:auto;font-size:12px"></div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px;flex-wrap:wrap">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
        <button class="btn-sm btn-default" id="pl-ai-preview-btn">Preview</button>
        <button class="save-btn" id="pl-ai-save">Create playlist</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  const preview = () => overlay.querySelector('#pl-ai-preview');
  overlay.querySelector('#pl-ai-preview-btn').onclick = async () => {
    const prompt = overlay.querySelector('#pl-ai-prompt').value.trim();
    if (!prompt) return showToast('Enter a prompt', true);
    preview().innerHTML = '<i class="fa fa-spinner fa-spin"></i> Thinking…';
    const res = await fetch('/api/playlists/ai', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt,
        name: overlay.querySelector('#pl-ai-name').value.trim() || undefined,
        maxTracks: parseInt(overlay.querySelector('#pl-ai-max').value, 10) || 25,
        save: false,
      }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      preview().innerHTML = `<span style="color:#c44">${escHtml(data.error || data.detail || 'Failed')}</span>`;
      return;
    }
    window._plAiPreview = data;
    if (!overlay.querySelector('#pl-ai-name').value.trim()) overlay.querySelector('#pl-ai-name').value = data.name || '';
    preview().innerHTML = `<b>${escHtml(data.name)}</b> — ${data.trackCount} tracks<ul style="margin:6px 0;padding-left:18px">${
      (data.tracks || []).slice(0, 15).map(t => `<li>${escHtml(t.title)}${t.artist ? ' — ' + escHtml(t.artist) : ''}</li>`).join('')
    }${data.trackCount > 15 ? '<li>…</li>' : ''}</ul>`;
  };
  overlay.querySelector('#pl-ai-save').onclick = async () => {
    const prompt = overlay.querySelector('#pl-ai-prompt').value.trim();
    if (!prompt) return showToast('Enter a prompt', true);
    const res = await fetch('/api/playlists/ai', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        prompt,
        name: overlay.querySelector('#pl-ai-name').value.trim() || undefined,
        maxTracks: parseInt(overlay.querySelector('#pl-ai-max').value, 10) || 25,
        save: true,
      }),
    });
    const data = await res.json().catch(() => ({}));
    overlay.remove();
    if (!res.ok) return showToast(data.error || data.detail || 'Mix Muse failed', true);
    showToast(`Created "${data.name}" (${data.trackCount} tracks)`);
    window.location.hash = `playlists/detail/${data.id}`;
  };
}

async function openAcquireIdeasModal(opts = {}) {
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  const seedLabel = opts.artist || opts.album || opts.title || 'your library';
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:640px">
      <h3 style="margin-top:0"><i class="fa fa-binoculars"></i> Music to seek out</h3>
      <p class="hint" style="margin:0 0 12px">Artists similar to <b>${escHtml(seedLabel)}</b> that are <em>not</em> in your library — via MusicBrainz tags and relationships.</p>
      <div id="acquire-loading" class="spinner-wrap"><div class="spinner"></div></div>
      <div id="acquire-results" style="max-height:420px;overflow:auto"></div>
      <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Close</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  const results = overlay.querySelector('#acquire-results');
  const loading = overlay.querySelector('#acquire-loading');
  let url = '/api/acquire/explore';
  let fetchOpts = { method: 'GET' };
  if (opts.explore === false || opts.seedKind || opts.artist || opts.path || opts.album) {
    fetchOpts = {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        seedKind: opts.seedKind || (opts.artist ? 'artist' : 'album'),
        path: opts.path || undefined,
        album: opts.album || undefined,
        artist: opts.artist || undefined,
        limit: opts.limit || 24,
      }),
    };
    url = '/api/acquire/suggest';
  }
  try {
    const res = await fetch(url, fetchOpts);
    const data = await res.json().catch(() => ({}));
    loading.style.display = 'none';
    if (!res.ok) {
      results.innerHTML = `<p class="hint" style="color:#c66">${escHtml(data.error || data.detail || 'Lookup failed')}</p>`;
      return;
    }
    const items = data.suggestions || [];
    if (!items.length) {
      results.innerHTML = `<p class="hint">${escHtml(data.note || 'No new artists found — your library may already cover this niche.')}</p>`;
      return;
    }
    results.innerHTML = `<ul class="acquire-list" style="list-style:none;margin:0;padding:0">${items.map((a) => {
      const reasons = (a.reasons || []).slice(0, 2).join(' · ');
      const tags = (a.tags || []).slice(0, 4).map(t => `<span class="badge">${escHtml(t)}</span>`).join(' ');
      const link = a.musicbrainzUrl
        ? `<a href="${escHtml(a.musicbrainzUrl)}" target="_blank" rel="noopener" class="btn-sm btn-default" style="margin-left:8px">MusicBrainz</a>`
        : '';
      return `<li style="padding:10px 0;border-bottom:1px solid rgba(255,255,255,.06)">
        <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px">
          <div><strong>${escHtml(a.name)}</strong>
            ${reasons ? `<div class="hint" style="margin-top:4px">${escHtml(reasons)}</div>` : ''}
            ${tags ? `<div style="margin-top:6px;display:flex;gap:4px;flex-wrap:wrap">${tags}</div>` : ''}
          </div>${link}
        </div></li>`;
    }).join('')}</ul>`;
  } catch (e) {
    loading.style.display = 'none';
    results.innerHTML = '<p class="hint" style="color:#c66">Could not reach MusicBrainz — try again later.</p>';
  }
}
window.openAcquireIdeasModal = openAcquireIdeasModal;

async function loadPlaylistDetail(id) {
  _plDetailId = id;
  const remote = await ensureAlexaRemoteStatus();
  window._plRemote = remote || {};
  await loadPlaylistDetailPage(false);
}

async function loadPlaylistDetailPage(quiet) {
  const id = _plDetailId;
  if (!id) return;
  if (!quiet) {
    const mc = document.getElementById('main-content');
    if (mc) mc.style.opacity = '0.6';
  }
  const q = `page=${_plDetailPage}&limit=${_plDetailPageSize}&sortBy=${encodeURIComponent(_plDetailSort.by)}&order=${encodeURIComponent(_plDetailSort.order)}${_plDetailQ ? '&q=' + encodeURIComponent(_plDetailQ) : ''}`;
  const data = await fetch(`/api/playlists/${encodeURIComponent(id)}?${q}`).then(r => r.json()).catch(() => null);
  if (!data || data.error) {
    renderPage('Playlist', '<div class="empty-state"><p>Playlist not found.</p></div>');
    return;
  }
  window._plDetail = data;
  renderPlaylistDetailBody();
  const mc = document.getElementById('main-content');
  if (mc) mc.style.opacity = '1';
}

function renderPlaylistDetailBody() {
  const data = window._plDetail;
  if (!data) return;
  const remote = window._plRemote || {};
  const canPlay = true;
  const tracks = data.tracks || [];
  const total = data.total != null ? data.total : tracks.length;
  const pageSize = data.limit || _plDetailPageSize;
  const page = data.page || _plDetailPage;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  if (_plDetailPage > totalPages) _plDetailPage = totalPages;
  const start = (page - 1) * pageSize;
  const pageTracks = tracks;
  const sortBtn = (label, by, order) => {
    const active = _plDetailSort.by === by && _plDetailSort.order === order;
    return `<button class="btn-sm btn-default${active ? ' active' : ''}" onclick="plApplyDetailSort('${by}','${order}')">${label}</button>`;
  };
  const sortLabel = _plDetailSort.by === 'title' ? 'Track' : (_plDetailSort.by.charAt(0).toUpperCase() + _plDetailSort.by.slice(1));
  const sortArrow = _plDetailSort.order === 'desc' ? '↓' : '↑';
  const trackRows = pageTracks.map((t, i) => {
    const globalIdx = start + i;
    const pathArg = JSON.stringify(t.path || '');
    const canReorder = !_plDetailQ && _plDetailSort.by === 'title' && _plDetailSort.order === 'asc';
    const reorderBtns = canReorder ? `
      <button class="btn-sm btn-default" onclick="plMoveTrackAt(${pathArg}, ${globalIdx - 1})" ${globalIdx <= 0 ? 'disabled' : ''} title="Move up">↑</button>
      <button class="btn-sm btn-default" onclick="plMoveTrackAt(${pathArg}, ${globalIdx + 1})" ${globalIdx >= total - 1 ? 'disabled' : ''} title="Move down">↓</button>` : '';
    return `
    <tr data-idx="${globalIdx}" data-path="${escHtml(t.path || '')}" ${canReorder ? 'draggable="true"' : ''}
      ondragstart="plDragStart(event)" ondragover="plDragOver(event)" ondrop="plDropTrack(event)">
      <td class="text-muted" style="width:36px">${globalIdx + 1}</td>
      <td>${escHtml(t.title || '—')}</td>
      <td class="text-muted">${escHtml(t.artist || '—')}</td>
      <td class="text-muted">${escHtml(t.album || '—')}</td>
      <td class="text-muted">${fmtDuration(t.duration_seconds)}</td>
      <td>${reorderBtns}${rowActions(
        canPlay ? actionBtn({ kind: 'play', onclick: `plPlayTrackAt(${pathArg})`, title: 'Play', icon: 'play' }) : '',
        data.editable !== false ? actionBtn({ kind: 'delete', onclick: `plRemoveTrackAt(${pathArg})`, title: 'Remove from playlist', icon: 'trash' }) : ''
      )}</td>
    </tr>`;
  }).join('');

  renderPage(data.name || 'Playlist', `
    <div class="pl-detail-header">
      <div>
        <a href="#playlists" class="btn-sm btn-default" style="margin-right:8px"><i class="fa fa-arrow-left"></i> All playlists</a>
        <span style="font-size:18px;font-weight:600">${escHtml(data.name)}</span>
        <span class="badge orange" style="margin-left:8px">${fmtNum(total)} tracks</span>
      </div>
      <div style="display:flex;gap:6px;flex-wrap:wrap">
        ${canPlay ? `<button class="btn-sm btn-primary" onclick="plPlayPlaylist()"><i class="fa fa-play"></i> Play</button>` : ''}
        ${data.daily ? `<button class="btn-sm btn-success" onclick="plSaveDaily()" title="Keep this daily mix in your library"><i class="fa fa-bookmark"></i> Save to library</button>` : ''}
        ${actionBtn({ kind: 'edit', onclick: 'plRenameDetail()', title: 'Rename', icon: 'pen' })}
        ${data.sourceName === 'bockmedia' ? actionBtn({ kind: 'delete', onclick: 'plDeleteDetail()', title: 'Delete playlist', icon: 'trash' }) : ''}
      </div>
    </div>
    ${data.daily ? `<p class="hint" style="margin:0 0 12px"><i class="fa fa-rotate"></i> Fresh daily mix — these songs change every day. Save it to keep today's set in your library.</p>` : ''}
    <p class="page-desc" style="margin:0 0 12px">
      Sorted by <b>${sortLabel}</b> ${sortArrow} — page ${_plDetailPage} of ${totalPages}
      (showing ${start + 1}–${Math.min(start + pageSize, total)}). Click column headers to re-sort.
    </p>
    <div class="search-bar" style="margin-bottom:12px">
      <input type="search" placeholder="Search in playlist…" value="${escHtml(_plDetailQ)}"
        oninput="clearTimeout(window._pldq);window._pldq=setTimeout(()=>{plDetailFilter(this.value)},400)">
    </div>
    ${!_plDetailQ && _plDetailSort.by === 'title' && _plDetailSort.order === 'asc' ? `
    <p class="hint" style="margin:0 0 8px">Drag rows or use ↑↓ to reorder. Sorting saves to the playlist file.</p>` : ''}
    <div class="card">
      <div class="card-header"><h3><i class="fa fa-sort"></i> Sort tracks</h3></div>
      <div class="card-body pl-sort-bar">
        ${sortBtn('Track A→Z', 'title', 'asc')}
        ${sortBtn('Track Z→A', 'title', 'desc')}
        ${sortBtn('Artist A→Z', 'artist', 'asc')}
        ${sortBtn('Artist Z→A', 'artist', 'desc')}
        ${sortBtn('Album A→Z', 'album', 'asc')}
        ${sortBtn('Album Z→A', 'album', 'desc')}
      </div>
    </div>
    <div class="card">
      <table class="data-table pl-tracks-table">
        <thead><tr>
          <th>#</th>
          <th class="pl-sort-th" data-pl-sort-col="track" title="Sort by track">Track ${plSortIndicator(_plDetailSort.by === 'title', _plDetailSort.order)}</th>
          <th class="pl-sort-th" data-pl-sort-col="artist" title="Sort by artist">Artist ${plSortIndicator(_plDetailSort.by === 'artist', _plDetailSort.order)}</th>
          <th class="pl-sort-th" data-pl-sort-col="album" title="Sort by album">Album ${plSortIndicator(_plDetailSort.by === 'album', _plDetailSort.order)}</th>
          <th>Length</th>
          <th></th>
        </tr></thead>
        <tbody>${trackRows || '<tr><td colspan="6" class="text-muted">No tracks</td></tr>'}</tbody>
      </table>
      ${buildPagination(total, _plDetailPage, _plDetailPageSize, (p) => { _plDetailPage = p; loadPlaylistDetailPage(true); })}
    </div>`, { header: false });
}

function plDetailFilter(val) {
  _plDetailQ = (val || '').trim();
  _plDetailPage = 1;
  loadPlaylistDetailPage(true);
}

let _plDragPath = null;
function plDragStart(e) {
  _plDragPath = e.currentTarget.getAttribute('data-path');
  e.dataTransfer.effectAllowed = 'move';
}
function plDragOver(e) {
  if (!_plDragPath) return;
  e.preventDefault();
  e.dataTransfer.dropEffect = 'move';
}
async function plDropTrack(e) {
  e.preventDefault();
  const toIdx = parseInt(e.currentTarget.getAttribute('data-idx'), 10);
  const path = _plDragPath;
  _plDragPath = null;
  if (!path || Number.isNaN(toIdx)) return;
  await plMoveTrackAt(path, toIdx);
}
window.plDragStart = plDragStart;
window.plDragOver = plDragOver;
window.plDropTrack = plDropTrack;

async function plMoveTrackAt(path, toIndex) {
  const d = window._plDetail;
  if (!d || !path) return;
  try {
    const res = await fetch(`/api/playlists/${encodeURIComponent(d.id)}/tracks/move`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ path, toIndex }),
    });
    if (!res.ok) {
      const data = await res.json().catch(() => ({}));
      showToast(data.error || 'Could not reorder track', true);
      return;
    }
    await loadPlaylistDetailPage(true);
  } catch (e) {
    showToast(e.message || 'Could not reorder track', true);
  }
}
window.plMoveTrackAt = plMoveTrackAt;

async function plSortDetail(by, order) {
  plApplyDetailSort(by, order);
}

function plPlayPlaylist() {
  const d = window._plDetail;
  if (d) startPlayback({ kind: 'playlist', name: d.name, id: d.id });
}

function plPlayTrackAt(path) {
  const d = window._plDetail;
  if (!d) return;
  startPlayback({ kind: 'playlist', name: d.name, id: d.id }, { fromPath: path });
}

async function plRemoveTrackAt(path) {
  const d = window._plDetail;
  if (!d || d.editable === false) return showToast('This playlist cannot be edited here', true);
  const res = await fetch(`/api/playlists/${encodeURIComponent(d.id)}/tracks/remove`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  });
  if (!res.ok) return showToast((await res.json().catch(() => ({}))).error || 'Failed', true);
  showToast('Track removed');
  await loadPlaylistDetailPage(true);
}

async function plRenameDetail() {
  const d = window._plDetail;
  if (!d) return;
  const name = prompt('Playlist name', d.name);
  if (!name || name === d.name) return;
  const res = await fetch('/api/playlists/rename', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id: d.id, name }),
  });
  if (!res.ok) return showToast((await res.json().catch(() => ({}))).error || 'Rename failed', true);
  loadPlaylistDetail(d.id);
}

async function plSaveDaily() {
  const d = window._plDetail;
  if (!d) return;
  const suggested = (d.name || '').replace(/^Daily Mix · /, '').trim() || d.name;
  const name = prompt('Save this daily mix as', suggested);
  if (name === null) return;
  const res = await fetch(`/api/daily-playlists/${encodeURIComponent(d.id)}/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(name.trim() ? { name: name.trim() } : {}),
  });
  if (!res.ok) return showToast((await res.json().catch(() => ({}))).error || 'Save failed', true);
  showToast('Saved to your library');
  loadPlaylistDetail(d.id);
}
window.plSaveDaily = plSaveDaily;

async function plDeleteDetail() {
  const d = window._plDetail;
  if (!d || !confirm(`Delete "${d.name}"?`)) return;
  const res = await fetch(`/api/playlists/${encodeURIComponent(d.id)}`, { method: 'DELETE' });
  if (!res.ok) return showToast((await res.json().catch(() => ({}))).error || 'Delete failed', true);
  showToast('Deleted');
  window.location.hash = 'playlists';
}

async function ensureAlexaRemoteStatus() {
  if (window._alexaRemote) return window._alexaRemote;
  try {
    window._alexaRemote = await API('/api/alexa_remote/status') || { available: false };
  } catch (e) {
    window._alexaRemote = { available: false, configured: false };
  }
  return window._alexaRemote;
}

function invalidateAlexaRemoteStatus() {
  window._alexaRemote = null;
}

function isAlexaAuthError(err) {
  const msg = typeof err === 'string' ? err : (err && (err.message || err.error || err.code)) || '';
  return /not_authenticated|session expired|alexa session/i.test(String(msg));
}

function handleAlexaAuthFailure() {
  invalidateAlexaRemoteStatus();
  _bannerDismissed = false;
  refreshGlobalBanner();
  openAlexaReLoginModal(true);
}

function closeAlexaReLoginModal() {
  clearInterval(_alexaLoginPoll);
  const overlay = document.getElementById('alexa-relogin-overlay');
  if (overlay) overlay.remove();
}

async function openAlexaReLoginModal(autoStart = false) {
  closeAlexaReLoginModal();
  const overlay = document.createElement('div');
  overlay.id = 'alexa-relogin-overlay';
  overlay.className = 'modal-overlay alexa-relogin-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) closeAlexaReLoginModal(); };
  overlay.innerHTML = `
    <div class="modal-box alexa-relogin-box">
      <h3 style="margin-top:0"><i class="fa fa-key"></i> Sign in to Amazon Alexa</h3>
      <p class="hint" style="margin:0 0 12px">Your Alexa session expired. Sign in so Play on device, Now Playing, and automations work again.</p>
      <div id="alexa-login-panel"><p class="hint" style="margin:0">Starting login…</p></div>
      <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:12px">
        <button type="button" class="cancel-btn" onclick="closeAlexaReLoginModal()">Close</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  const remote = await ensureAlexaRemoteStatus().catch(() => ({}));
  updateAlexaLoginPanel({ ...remote, status: remote.authenticated ? 'success' : 'idle' });
  if (autoStart && remote.authenticated !== true) await startAlexaLogin();
}
window.openAlexaReLoginModal = openAlexaReLoginModal;
window.closeAlexaReLoginModal = closeAlexaReLoginModal;

let _alexaLoginPoll = null;

function alexaLoginStatusLabel(st) {
  const s = st || 'idle';
  if (s === 'waiting' || s === 'starting') return 'Waiting for sign-in…';
  if (s === 'success') return 'Logged in — session saved';
  if (s === 'error') return 'Login failed';
  if (s === 'stopped') return 'Cancelled';
  return 'Not started';
}

function updateAlexaLoginPanel(st) {
  const panel = document.getElementById('alexa-login-panel');
  if (!panel || !st) return;
  const status = st.status || st.loginStatus || 'idle';
  const url = st.url || st.loginUrl;
  const err = st.error || st.loginError;
  const auth = st.authenticated === true;
  const waiting = status === 'waiting' || status === 'starting';
  panel.innerHTML = `
    <p style="margin:0 0 8px">
      Status: <b>${auth ? 'Connected' : escHtml(alexaLoginStatusLabel(status))}</b>
      ${auth ? ` · ${st.deviceCount != null ? st.deviceCount + ' Echoes' : ''}` : ''}
    </p>
    ${err ? `<p style="color:#c44;font-size:13px;margin:0 0 8px">${escHtml(err)}</p>` : ''}
    ${waiting && url ? `<p class="hint" style="margin:0 0 8px">Open this URL on the same network, sign in to Amazon, and choose <b>password</b> if passkey is offered:</p>
      <a class="btn-sm btn-primary" href="${escHtml(url)}" target="_blank" rel="noopener" style="margin-bottom:8px;display:inline-block"><i class="fa fa-key"></i> Open Amazon login</a>
      <code style="display:block;font-size:11px;word-break:break-all;margin-bottom:8px">${escHtml(url)}</code>` : ''}
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      ${!auth && !waiting
        ? `<button class="btn-sm btn-primary" onclick="startAlexaLogin()"><i class="fa fa-key"></i> Start browser login</button>`
        : ''}
      ${waiting ? `<button class="btn-sm btn-default" onclick="stopAlexaLogin()">Cancel</button>` : ''}
      ${auth ? `<button class="btn-sm btn-default" onclick="startAlexaLogin()"><i class="fa fa-rotate"></i> Re-login</button>` : ''}
    </div>`;
}

async function pollAlexaLogin() {
  clearInterval(_alexaLoginPoll);
  _alexaLoginPoll = setInterval(async () => {
    const st = await API('/api/alexa_remote/login').catch(() => null);
    if (!st) return;
    updateAlexaLoginPanel(st);
    if (st.authenticated === true) {
      clearInterval(_alexaLoginPoll);
      invalidateAlexaRemoteStatus();
      _bannerDismissed = false;
      showToast('Alexa login successful');
      closeAlexaReLoginModal();
      refreshGlobalBanner();
      if (document.getElementById('alexa-login-panel')) {
        const remote = await ensureAlexaRemoteStatus();
        updateAlexaLoginPanel({ ...remote, ...st, authenticated: true });
      }
      loadHealth();
      return;
    }
    if (st.status === 'success') {
      clearInterval(_alexaLoginPoll);
      invalidateAlexaRemoteStatus();
      _bannerDismissed = false;
      showToast('Alexa login successful');
      closeAlexaReLoginModal();
      refreshGlobalBanner();
      return;
    }
    if (st.status === 'error' || st.status === 'stopped') {
      clearInterval(_alexaLoginPoll);
      showToast(st.error || 'Login cancelled', st.status === 'error');
    }
  }, 2000);
}

async function startAlexaLogin() {
  const host = window.location.hostname;
  const res = await fetch('/api/alexa_remote/login/start', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(host ? { host } : {}),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    return showToast(data.error || 'Could not start login', true);
  }
  updateAlexaLoginPanel(data);
  pollAlexaLogin();
  // Wait for the OAuth proxy to bind before opening the browser tab.
  for (let i = 0; i < 20; i++) {
    if (data.portReady || data.status === 'waiting') break;
    await new Promise(r => setTimeout(r, 500));
    const st = await API('/api/alexa_remote/login').catch(() => null);
    if (!st) break;
    Object.assign(data, st);
    updateAlexaLoginPanel(st);
    if (st.status === 'error' || st.status === 'stopped') {
      return showToast(st.error || 'Login failed to start', true);
    }
    if (st.portReady || st.status === 'waiting') break;
  }
  if (data.url) window.open(data.url, '_blank', 'noopener');
}

async function stopAlexaLogin() {
  clearInterval(_alexaLoginPoll);
  await fetch('/api/alexa_remote/login/stop', { method: 'POST' });
  const st = await API('/api/alexa_remote/login').catch(() => ({}));
  updateAlexaLoginPanel(st);
  showToast('Login cancelled');
}

function buildAlexaRemoteSettingsSection(remote, localIp) {
  if (!remote || !remote.available) {
    return `<div class="settings-section">
      <h4>Alexa Remote — Play on device</h4>
      <p class="hint" style="color:#c44">alexapy is not installed on this server (<code>pip3 install --user alexapy "aiohttp>=3.10,&lt;3.11"</code>).</p>
    </div>`;
  }
  if (!remote.configured) {
    return `<div class="settings-section">
      <h4>Alexa Remote — Play on device</h4>
      <p class="hint">Add <code>alexaRemote.email</code> and <code>alexaRemote.password</code> to <code>config.json</code>, then use browser login below.</p>
    </div>`;
  }
  const auth = remote.authenticated === true;
  const host = remote.loginProxyHost || localIp || window.location.hostname;
  const port = remote.loginProxyPort || 3005;
  return `<div class="settings-section">
    <h4>Alexa Remote — Play on device</h4>
    <p class="hint">Sign in to Amazon so the web UI can start playlists on a specific Echo and run automations. Uses a short-lived login page on this machine (${escHtml(host)}:${port}).</p>
    <div id="alexa-login-panel"></div>
    <p class="hint" style="margin-top:10px;font-size:11px">CLI fallback: <code>python3 scripts/alexa_login.py --proxy --host ${escHtml(host)} --port ${port}</code></p>
  </div>`;
}

async function loadPlaylists(showSpinner) {
  if (showSpinner && document.querySelector('.playlists-table')) {
    document.querySelector('.playlists-table').style.opacity = '0.55';
  }
  const searchKey = (_plSearch || '').trim().toLowerCase();
  const needFetch = !_plAllCache || _plAllCacheSearch !== searchKey;
  const [listData, remoteStatus, smartData, folderData] = await Promise.all([
    needFetch
      ? API(`/api/playlists?page=1&limit=10000&search=${encodeURIComponent(_plSearch)}`)
      : Promise.resolve(null),
    ensureAlexaRemoteStatus(),
    API('/api/smart_playlists').catch(() => ({ items: [] })),
    API('/api/playlist_folders').catch(() => ({ folders: [], assignments: {} })),
  ]);
  window._plFolders = (folderData && folderData.folders) || [];
  window._plFolderAssignments = (folderData && folderData.assignments) || {};
  window._smartPlaylists = (smartData && smartData.items) || [];
  if (needFetch) {
    _plAllCache = (listData && listData.items) || [];
    _plAllCacheSearch = searchKey;
  }
  window._plRemote = remoteStatus || window._plRemote || {};
  renderPlaylistsPage();
  const pageIds = (window._playlists || []).map((p) => p.id).filter(Boolean);
  const smartIds = (window._smartPlaylists || []).map((s) => s.linkedPlaylistId).filter(Boolean);
  const coverIds = [...new Set(pageIds.concat(smartIds))];
  if (coverIds.length) {
    const covers = await fetchPlaylistCovers(coverIds);
    window._plCovers = { ...(window._plCovers || {}), ...covers };
    renderPlaylistsPage();
  }
}

function renderPlaylistsPage() {
  const remote = window._plRemote || {};
  const canPlay = true;
  const sorted = plSortPlaylistsInMemory(_plAllCache || [], _plListSort.by, _plListSort.order)
    .filter(p => {
      if (!_plFolderFilter) return true;
      const assignments = window._plFolderAssignments || {};
      return assignments[p.id] === _plFolderFilter;
    });
  const total = sorted.length;
  const start = (_plPage - 1) * _plPageSize;
  const items = sorted.slice(start, start + _plPageSize);
  window._playlists = items;
  const covers = window._plCovers || {};

  const rows = items.map((p, i) => {
    const isLibraryPlaylist = !p.source || p.source.includes('MyMedia');
    const art = playlistRowArtHtml(p.id, covers[p.id], p.name, p.isAudioBook);
    const srcDisplay = p.sourceName === 'bockmedia'
      ? '<span class="badge green">Custom</span>'
      : (p.source && p.source.includes('plex') ? '<span class="badge">Plex</span>' : (
        isLibraryPlaylist ? '<span class="badge orange">Bock Media</span>' : '<span class="badge">File</span>'));
    const checked = _plMergeSel.has(p.id) ? 'checked' : '';
    const mergeCb = p.id
      ? `<input type="checkbox" class="pl-merge-cb" ${checked} onchange="plToggleMerge('${escHtml(p.id)}', this.checked)">`
      : '';
    const editBtn = p.id
      ? actionBtn({ kind: 'edit', onclick: `startEditPlaylist(${i})`, title: 'Rename playlist', icon: 'pen' })
      : '';
    const viewBtn = p.id
      ? actionBtn({ kind: 'muted', onclick: `window.location.hash='playlists/detail/${escHtml(p.id)}'`, title: 'View tracks', icon: 'list' })
      : '';
    const playBtn = canPlay
      ? actionBtn({ kind: 'play', onclick: `openPlayMenu(${i})`, title: 'Play', icon: 'play' })
      : '';
    const nameCell = p.id
      ? `<a href="#playlists/detail/${escHtml(p.id)}" class="pl-name-link">${escHtml(p.name)}</a>`
      : `<span class="pl-name-text">${escHtml(p.name)}</span>`;
    return `
    <tr id="pl-row-${i}">
      <td class="pl-col-merge" style="width:28px">${mergeCb}</td>
      <td class="pl-col-art">${art}</td>
      <td class="pl-col-name">${nameCell}</td>
      <td class="pl-col-source">${srcDisplay}</td>
      <td class="pl-col-tracks"><span class="badge orange">${fmtNum(p.trackCount)}</span></td>
      ${rowActions(playBtn, viewBtn, editBtn)}
    </tr>`;
  }).join('');

  const sortLabel = _plListSort.by === 'trackCount' ? 'Tracks' : 'Name';
  const sortArrow = _plListSort.order === 'desc' ? '↓' : '↑';
  const smart = window._smartPlaylists || [];
  const smartRows = smart.map(s => `
    <tr>
      <td class="pl-col-art">${s.linkedPlaylistId
    ? playlistRowArtHtml(s.linkedPlaylistId, covers[s.linkedPlaylistId], s.name, false)
    : playlistRowArtHtml(s.id, null, s.name, false)}</td>
      <td><strong>${escHtml(s.name)}</strong></td>
      <td class="text-muted">${fmtNum(s.trackCount || 0)}</td>
      <td class="text-muted" style="font-size:11px">${s.lastRefresh ? fmtDateTime(s.lastRefresh) : '—'}</td>
      <td>${rowActions(
        s.linkedPlaylistId ? actionBtn({ kind: 'muted', onclick: `window.location.hash='playlists/detail/${escHtml(s.linkedPlaylistId)}'`, title: 'Open', icon: 'list' }) : '',
        actionBtn({ kind: 'edit', onclick: `refreshSmartPlaylist('${escHtml(s.id)}')`, title: 'Refresh from rules', icon: 'rotate' }),
        actionBtn({ kind: 'delete', onclick: `deleteSmartPlaylist('${escHtml(s.id)}')`, title: 'Delete', icon: 'trash' })
      )}</td>
    </tr>`).join('');

  const folderChips = (window._plFolders || []).map(f =>
    `<button class="btn-sm btn-default" onclick="_plFolderFilter='${escHtml(f.id)}';renderPlaylistsPage()">${escHtml(f.name)}</button>`
  ).join(' ');
  const plCards = items.map((p, i) => {
    const art = covers[p.id] || null;
    const play = canPlay && p.id ? `openPlayMenu(${i})` : null;
    return spotifyMediaCard(
      p.name,
      `${fmtNum(p.trackCount || 0)} tracks`,
      p.id ? `#playlists/detail/${encodeURIComponent(p.id)}` : '#playlists',
      'fa-list',
      p.name,
      play,
      art,
      { downloadOnclick: p.id ? tileDownloadOnclick({ kind: 'playlist', id: p.id, name: p.name }) : null },
    );
  }).join('');

  const smartCards = smart.map((s) => {
    const href = s.linkedPlaylistId
      ? `#playlists/detail/${encodeURIComponent(s.linkedPlaylistId)}`
      : '#playlists';
    const art = s.linkedPlaylistId ? covers[s.linkedPlaylistId] : null;
    return spotifyMediaCard(
      s.name,
      `${fmtNum(s.trackCount || 0)} tracks · Smart`,
      href,
      'fa-wand-magic-sparkles',
      s.name,
      null,
      art,
    );
  }).join('');

  renderPage('Playlists', spotifyBrowsePage('Playlists', libraryFiltersHtml('library'), `
    <div class="spotify-browse-toolbar">
      <input type="search" class="spotify-browse-search" placeholder="Search playlists…" value="${escHtml(_plSearch)}"
        oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_plSearch=this.value;_plPage=1;_plAllCache=null;loadPlaylists()},350)">
      <button class="btn-sm btn-primary" onclick="openNewPlaylistModal()"><i class="fa fa-plus"></i> Create</button>
      <button class="btn-sm btn-default" onclick="openMergePlaylistsModal()">Merge</button>
      <button class="btn-sm btn-default" onclick="openAiPlaylistModal()">Mix Muse</button>
      <button class="btn-sm btn-default" onclick="openSmartPlaylistModal()">Smart playlist</button>
    </div>
    ${folderChips ? `<div class="home-filters" style="margin-bottom:16px">${folderChips}
      <button type="button" class="home-filter${_plFolderFilter ? '' : ' active'}" onclick="_plFolderFilter='';renderPlaylistsPage()">All</button></div>` : ''}
    ${smartCards ? spotifySection('Smart playlists', '#playlists', smartCards) : ''}
    ${plCards ? `<section class="spotify-section"><div class="spotify-section-header"><h2 class="spotify-section-title">Your playlists</h2><span class="hint">${fmtNum(total)} total · sorted by ${sortLabel} ${sortArrow}</span></div><div class="spotify-carousel library-playlist-grid">${plCards}</div></section>` : '<div class="empty-state"><i class="fa fa-list"></i><p>No playlists found.</p></div>'}
    ${buildPagination(total, _plPage, _plPageSize, (p) => { _plPage = p; renderPlaylistsPage(); })}
  `), { header: false });
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
  if (p) startPlayback({ kind: 'playlist', name: p.name, id: p.id });
}

// Generic "Play on a device" picker. opts: {kind, name, id?, shuffle?(bool, default allowed)}
function songPlayOpts(s) {
  if (!s) return null;
  const path = s.path || s.filepath || '';
  const title = s.title || s.track || (path ? path2name(path) : '');
  if (!title && !path) return null;
  return { kind: 'song', name: title, artist: s.artist || '', path };
}

async function playMedia(opts, playOpts = {}) {
  if (playOpts.target === 'speaker') return playOnDevice(opts);
  if (typeof WebPlayback === 'undefined') {
    return playOnDevice(opts);
  }
  try {
    await WebPlayback.play(opts, playOpts);
  } catch (e) {
    if (e && e.name === 'AbortError') return;
    if (/interrupted by a new load/i.test(String(e.message || ''))) return;
    showToast(e.message || 'Playback failed', true);
    throw e;
  }
}
window.playMedia = playMedia;

async function showPlayChooser(opts, playOpts = {}) {
  if (playOpts.target === 'speaker') return playOnDevice(opts);
  if (playOpts.target === 'browser') return playMedia(opts, playOpts);
  const remote = await ensureAlexaRemoteStatus().catch(() => ({}));
  const hasAlexa = !!(remote && remote.configured);
  const hasBrowser = typeof WebPlayback !== 'undefined';
  if (!hasBrowser) return playOnDevice(opts);
  if (!hasAlexa) return playMedia(opts, playOpts);

  const label = escHtml(opts.name || 'media');
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box">
      <h3 style="margin-top:0"><i class="fa fa-play"></i> Play "${label}"</h3>
      <p class="hint" style="margin:0 0 14px">Listen on this device or send to a speaker.</p>
      <div style="display:flex;flex-direction:column;gap:10px">
        <button class="save-btn" id="play-here" style="width:100%;justify-content:center;display:flex;align-items:center;gap:8px">
          <i class="fa fa-laptop"></i> Play here
        </button>
        <button class="cancel-btn" id="play-speaker" style="width:100%;justify-content:center;display:flex;align-items:center;gap:8px">
          <i class="fa fa-volume-high"></i> Play on speaker…
        </button>
      </div>
      <div style="display:flex;justify-content:flex-end;margin-top:14px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Cancel</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#play-here').onclick = () => {
    overlay.remove();
    playMedia(opts, playOpts).catch((err) => showToast(err.message || 'Playback failed', true));
  };
  overlay.querySelector('#play-speaker').onclick = () => {
    overlay.remove();
    playOnDevice(opts).catch((err) => showToast(err.message || 'Failed to start playback', true));
  };
}
window.showPlayChooser = showPlayChooser;

async function startPlayback(opts, playOpts = {}) {
  return showPlayChooser(opts, playOpts);
}
window.startPlayback = startPlayback;

async function playOnDevice(opts) {
  const { kind, name, id, artist, path } = opts;
  const allowShuffle = opts.shuffle !== false && kind !== 'song';
  let devices;
  try {
    [devices] = await Promise.all([ensureAlexaDevices(), ensureDeviceGroups()]);
  } catch (e) {
    if (isAlexaAuthError(e)) {
      handleAlexaAuthFailure();
      return showToast('Alexa session expired — sign in to continue', true);
    }
    return showToast(e.message || 'Failed to load devices', true);
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
      <p class="hint" style="margin:0 0 8px">Pick a speaker or a <b>group</b> to play on every member at once.</p>
      <label style="display:block;margin:12px 0 4px;font-size:13px;color:#888">Device or group</label>
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
      body: JSON.stringify({ kind, id, name, artist: artist || '', path: path || '', device, shuffle }),
    });
    const data = await res.json().catch(() => ({}));
    overlay.remove();
    if (res.ok) showToast(`Playing "${name}" on ${data.device || 'device'}`);
    else showToast(data.error || 'Failed to start playback', true);
  };
}

function playArtistAt(i) {
  const a = (window._artists || [])[i];
  if (a) startPlayback({ kind: 'artist', name: a.artist });
}
function playAlbumAt(i) {
  const a = (window._albums || [])[i];
  if (a) startPlayback({ kind: 'album', name: a.album, artist: a.artist || '' });
}
function playSongAt(i) {
  const opts = songPlayOpts((window._songs || [])[i]);
  if (opts) startPlayback(opts);
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
  const canPlay = true;

  const cards = items.map((a, i) => {
    const artPath = a.art_path || null;
    const play = canPlay ? `event.stopPropagation();playArtistAt(${i})` : null;
    const playFab = play
      ? `<button type="button" class="spotify-play-fab" onclick="${play}" aria-label="Play"><i class="fa fa-play"></i></button>`
      : '';
    const art = spotifyCardArtHtml(artPath, a.artist, 'fa-microphone', 256)
      .replace('class="spotify-card-art', 'class="spotify-card-art spotify-card-art-round');
    return `<a href="#songs/artist/${encodeURIComponent(a.artist)}" class="spotify-artist-card">
      <div class="spotify-card-media">${art}${playFab}</div>
      <div class="spotify-card-title">${escHtml(a.artist)}</div>
      <div class="spotify-card-sub">Artist · ${fmtNum(a.track_count)} songs</div>
    </a>`;
  }).join('');

  renderPage('Artists', spotifyBrowsePage('Artists', libraryFiltersHtml('artists'), `
    <div class="spotify-browse-toolbar">
      <input type="search" class="spotify-browse-search" placeholder="Search artists…" value="${escHtml(_arSearch)}"
        oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_arSearch=this.value;_arPage=1;loadArtists()},350)">
      <span class="hint">${fmtNum(total)} artists</span>
    </div>
    ${cards ? `<div class="spotify-browse-grid artists">${cards}</div>` : '<div class="empty-state"><i class="fa fa-microphone"></i><p>No artists found.</p></div>'}
    ${buildPagination(total, _arPage, 50, (p) => { _arPage = p; loadArtists(); })}
  `), { header: false });
  requestAnimationFrame(() => upgradeArtistPortraitImages(items));
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
  const url = `/api/albums?page=${_alPage}&limit=50&search=${encodeURIComponent(_alSearch)}&artist=${encodeURIComponent(_alArtist)}`;
  let res;
  try {
    res = await authFetch(url);
  } catch {
    res = null;
  }
  if (!res || !res.ok) {
    if (res && res.status === 503) {
      renderPage('Albums', `
        ${libraryFiltersHtml('albums')}
        <div class="empty-state">
          <div class="spinner-wrap"><div class="spinner"></div></div>
          <p>Indexing albums for the first time… this usually takes under a minute.</p>
        </div>`);
      setTimeout(() => loadAlbums(), 4000);
      return;
    }
    renderPage('Albums', `${libraryFiltersHtml('albums')}<div class="empty-state"><i class="fa fa-compact-disc"></i><p>Could not load albums — try again in a moment.</p><button class="btn-sm btn-primary" onclick="loadAlbums()">Retry</button></div>`);
    return;
  }
  const data = await res.json().catch(() => null);
  if (!data) {
    renderPage('Albums', `${libraryFiltersHtml('albums')}<div class="empty-state"><i class="fa fa-compact-disc"></i><p>Could not load albums.</p></div>`);
    return;
  }
  const remote = await ensureAlexaRemoteStatus().catch(() => ({}));
  const { items = [], total = 0 } = data;
  window._albums = items;
  const canPlay = true;

  const cards = items.map((a, i) => {
    const unplayed = a.played === false;
    const play = canPlay ? `event.stopPropagation();playAlbumAt(${i})` : null;
    return spotifyMediaCard(
      a.album + (unplayed ? ' · New' : ''),
      `${a.artist || 'Unknown'} · ${fmtNum(a.track_count)} songs`,
      `#songs/album/${encodeURIComponent(a.album)}`,
      'fa-compact-disc',
      a.album,
      play,
      a.art_path,
      { unplayed },
    );
  }).join('');

  const backLink = _alArtist ? `<a href="#artists" class="spotify-section-link" style="display:inline-block;margin-bottom:12px"><i class="fa fa-arrow-left"></i> Back to Artists</a>` : '';
  const pageTitle = _alArtist ? `Albums · ${_alArtist}` : 'Albums';
  renderPage(pageTitle, spotifyBrowsePage(pageTitle, libraryFiltersHtml('albums'), `
    ${backLink}
    <div class="spotify-browse-toolbar">
      <input type="search" class="spotify-browse-search" placeholder="Search albums…" value="${escHtml(_alSearch)}"
        oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_alSearch=this.value;_alPage=1;loadAlbums()},350)">
      <span class="hint">${fmtNum(total)} albums</span>
    </div>
    ${cards ? `<div class="spotify-browse-grid">${cards}</div>` : '<div class="empty-state"><i class="fa fa-compact-disc"></i><p>No albums found.</p></div>'}
    ${buildPagination(total, _alPage, 50, (p) => { _alPage = p; loadAlbums(); })}
  `), { header: false });
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
  const canPlay = true;

  const rows = items.map((s, i) => {
    const title = s.title || path2name(s.path);
    const playBtn = canPlay
      ? `<button type="button" class="spotify-play-fab" style="position:static;opacity:1;width:32px;height:32px" onclick="playSongAt(${i})" aria-label="Play"><i class="fa fa-play"></i></button>`
      : '';
    return `<div class="spotify-track-row">
      <span class="text-muted">${s.track_number || i + 1}</span>
      <span class="track-title">${escHtml(title)}</span>
      <span>${escHtml(s.album || '—')}</span>
      <span>${playBtn}</span>
    </div>`;
  }).join('');

  let backLink = '';
  if (_soAlbum) backLink = `<a href="#albums" class="spotify-section-link" style="display:inline-block;margin-bottom:12px"><i class="fa fa-arrow-left"></i> Back to Albums</a>`;
  else if (_soArtist) backLink = `<a href="#artists" class="spotify-section-link" style="display:inline-block;margin-bottom:12px"><i class="fa fa-arrow-left"></i> Back to Artists</a>`;

  let pageTitle = 'Songs';
  if (_soAlbum) pageTitle = _soAlbum;
  else if (_soArtist) pageTitle = _soArtist;

  renderPage(pageTitle, spotifyBrowsePage(pageTitle, libraryFiltersHtml('songs'), `
    ${backLink}
    <div class="spotify-browse-toolbar">
      <input type="search" class="spotify-browse-search" placeholder="Search in list…" value="${escHtml(_soSearch)}"
        oninput="clearTimeout(window._sd);window._sd=setTimeout(()=>{_soSearch=this.value;_soPage=1;loadSongs()},350)">
      <span class="hint">${fmtNum(total)} tracks · ${escHtml(_soArtist || _soAlbum || 'All songs')}</span>
    </div>
    ${rows ? `<div class="spotify-track-list">
      <div class="spotify-track-row-head"><span>#</span><span>Title</span><span>Album</span><span></span></div>
      ${rows}
    </div>` : '<div class="empty-state"><i class="fa fa-music"></i><p>No songs found.</p></div>'}
    ${buildPagination(total, _soPage, 100, (p) => { _soPage = p; loadSongs(); })}
  `), { header: false });
}

function path2name(p) {
  if (!p) return '—';
  return p.split('/').pop().replace(/\.[^.]+$/, '');
}

// ── Watch Folders ────────────────────────────────────────────────────────────
register('watchfolders', async () => {
  loading();
  const folders = await API('/api/watchfolders') || [];

  const cards = folders.map(f => folderCardHtml(f)).join('');

  renderPage('Watch Folders', `
    ${libraryFiltersHtml('watchfolders')}
    <div class="card">
      <div class="card-header">
        <h3><i class="fa fa-folder-open"></i> Watch Folders (${fmtNum(folders.length)})</h3>
      </div>
      <div class="card-body folder-card-list">
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

function isAppClient(d) {
  return (d && d.platform === 'android') || (d && (d.deviceId || '').startsWith('client-'));
}

function renderDevices() {
  const devices = window._devices || [];
  // Keep the Alexa list clean: phone/app clients (which can re-register on each
  // reinstall) live in their own section instead of crowding the Echo devices.
  const alexaDevices = devices.filter(d => !isAppClient(d));
  const rows = devices.map((d, i) => isAppClient(d) ? '' : `
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
    ${renderAppClientsCard()}
    <div class="card">
      <div class="card-header" style="display:flex;align-items:center;justify-content:space-between">
        <h3><i class="fa fa-headphones"></i> Alexa Devices (${alexaDevices.length})</h3>
        <div style="display:flex;gap:8px;align-items:center">
          ${window._devicesRemoteConfigured && unnamedCount(alexaDevices) > 0
            ? `<button class="btn-sm btn-primary" onclick="startFixDevices()"><i class="fa fa-wand-magic-sparkles"></i> Fix my devices (${unnamedCount(alexaDevices)})</button>`
            : ''}
          ${window._devicesRemoteConfigured
            ? `<button id="identify-btn" class="btn-sm btn-default" onclick="startIdentify()"><i class="fa fa-volume-high"></i> Identify all</button>`
            : ''}
        </div>
      </div>
      <div id="identify-status" style="display:none;padding:8px 16px;font-size:12px;color:#556;border-bottom:1px solid #eef2f8"></div>
      ${rows
        ? `<ul class="device-list">${rows}</ul>`
        : `<div class="empty-state"><i class="fa fa-headphones"></i><p>No devices yet — start streaming from an Echo to register it.</p></div>`}
    </div>`);
}

function renderAppClientsCard() {
  const devices = window._devices || [];
  // Preserve original indices so edit/delete actions hit the right entry.
  const clients = devices.map((d, i) => ({ d, i })).filter(x => isAppClient(x.d));
  if (!clients.length) return '';
  const rows = clients.map(({ d, i }) => `
    <li id="dev-row-${i}">
      <span class="device-icon-col"><i class="fa fa-mobile-screen"></i></span>
      <span class="device-name-text">${escHtml(d.name)}</span>
      <span class="device-last-seen" style="font-size:11px;color:#9aa;margin-left:8px">${d.lastSeen ? 'Last seen ' + fmtDateTime(new Date(d.lastSeen * 1000).toISOString()) : ''}</span>
      <div class="row-actions">
        ${actionBtn({ kind: 'edit', onclick: `startEditDevice(${i})`, title: 'Edit name', icon: 'pen' })}
        ${actionBtn({ kind: 'delete', onclick: `deleteDevice(${i})`, title: 'Remove device', icon: 'trash' })}
      </div>
    </li>`).join('');
  return `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-mobile-screen"></i> App Clients (${clients.length})</h3></div>
      <div class="card-body" style="padding:8px 16px 4px">
        <p class="hint" style="margin:0 0 8px">Phones and tablets running the app. A fresh install or reset registers a new client — safe to remove stale entries here.</p>
        <ul class="device-list" style="margin:0">${rows}</ul>
      </div>
    </div>`;
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

// An auto-name is the placeholder we assign on first contact: "Echo AB12CD".
function isAutoName(name) {
  return !name || /^Echo [A-Za-z0-9]{6}$/.test((name || '').trim());
}

function unnamedCount(devices) {
  return (devices || []).filter(d => isAutoName(d.name)).length;
}

// ── Guided "Fix my devices" ───────────────────────────────────────────────────
// Walks online speakers one at a time: plays a short clip on each so the user
// can hear the room, then names the matching device. Naming a device via a
// test play reuses the serial-correlation path (the room name becomes the
// device name automatically), so this also folds rotated ids onto the room.
async function startFixDevices() {
  let speakers;
  try {
    speakers = (await ensureAlexaDevices()).filter(s => s.serial && s.online);
  } catch (e) {
    return showToast(e.message || 'Failed to load speakers', true);
  }
  if (!speakers.length) return showToast('No online speakers found', true);
  window._fix = { queue: speakers, idx: 0 };
  renderFixStep();
}

function renderFixStep() {
  const fx = window._fix;
  if (!fx) return;
  document.querySelectorAll('.modal-overlay.fix-modal').forEach(o => o.remove());
  if (fx.idx >= fx.queue.length) {
    showToast('All speakers reviewed');
    refreshDevicesThenRender();
    return;
  }
  const s = fx.queue[fx.idx];
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay fix-modal';
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  overlay.innerHTML = `
    <div class="modal-box">
      <h3 style="margin-top:0"><i class="fa fa-wand-magic-sparkles"></i> Fix my devices</h3>
      <p class="hint" style="margin:0 0 10px">Speaker ${fx.idx + 1} of ${fx.queue.length}. Press <b>Play here</b> to hear which room this is, then give it a name.</p>
      <div style="font-weight:600;margin-bottom:6px"><i class="fa fa-volume-high" style="color:#e99d1a"></i> ${escHtml(s.name)}</div>
      <button class="btn-sm btn-default" id="fix-play"><i class="fa fa-play"></i> Play here</button>
      <label style="display:block;margin:14px 0 4px;font-size:13px;color:#888">Room name</label>
      <input id="fix-name" class="settings-input" style="width:100%" value="${escHtml(s.name)}">
      <div style="display:flex;gap:8px;justify-content:space-between;margin-top:16px">
        <button class="cancel-btn" onclick="this.closest('.modal-overlay').remove()">Close</button>
        <div style="display:flex;gap:8px">
          <button class="cancel-btn" id="fix-skip">Skip</button>
          <button class="save-btn" id="fix-save">Save &amp; next</button>
        </div>
      </div>
    </div>`;
  document.body.appendChild(overlay);
  overlay.querySelector('#fix-play').onclick = () => fixPlayClip(s);
  overlay.querySelector('#fix-skip').onclick = () => { fx.idx++; renderFixStep(); };
  overlay.querySelector('#fix-save').onclick = () => fixSaveAndNext(overlay, s);
  const nameInput = overlay.querySelector('#fix-name');
  nameInput.focus();
  nameInput.select();
}

async function fixPlayClip(s) {
  // Play under the current room-name guess so correlation can bind immediately.
  const name = (document.getElementById('fix-name') || {}).value || s.name;
  await fetch('/api/devices/test', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ serial: s.serial, name }),
  }).catch(() => {});
  showToast(`Playing on ${s.name}…`);
}

async function fixSaveAndNext(overlay, s) {
  const name = (overlay.querySelector('#fix-name').value || '').trim();
  if (!name) return showToast('Enter a room name', true);
  const btn = overlay.querySelector('#fix-save');
  btn.disabled = true; btn.textContent = 'Saving…';
  // Naming via a test play: the room name becomes the device name through the
  // serial-correlation path, and a brief clip confirms the right speaker.
  const res = await fetch('/api/devices/test', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ serial: s.serial, name }),
  });
  const d = await res.json().catch(() => ({}));
  if (!res.ok || !d.ok) {
    btn.disabled = false; btn.textContent = 'Save & next';
    return showToast('Failed: ' + (d.error || res.status), true);
  }
  window._fix.idx++;
  renderFixStep();
}

async function refreshDevicesThenRender() {
  window._devices = await API('/api/devices') || [];
  renderDevices();
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
        <p class="hint" style="margin:0">Alexa remote control is not configured. Add <code>alexaRemote</code> credentials in <code>config.json</code>, then sign in from Settings.</p>
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
  const volEnabled = editing && editing.volume != null && editing.volume !== undefined;
  const volValue = volEnabled ? editing.volume : 30;
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
            ${autoTimeSelectHtml(editing ? (editing.time || '08:00') : '08:00')}
          </div>
          <div class="auto-field" style="grid-column:1/-1">
            <label style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
              <input type="checkbox" id="auto-volume-enable" ${volEnabled ? 'checked' : ''}
                onchange="autoToggleVolumeField()"> Set volume before playlist starts
            </label>
            <div class="np-volume" id="auto-volume-row" style="${volEnabled ? '' : 'opacity:0.45'}">
              <i class="fa fa-volume-low np-volume-icon"></i>
              <input type="range" id="auto-volume" class="np-volume-slider" min="0" max="100" value="${volValue}"
                ${volEnabled ? '' : 'disabled'} oninput="autoUpdateVolumeLabel(); autoEnableVolumeOnSlide()">
              <span class="np-volume-val" id="auto-volume-val">${volValue}</span>
            </div>
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
        <p class="hint" style="margin:0">Alexa remote control is not configured. Add <code>alexaRemote</code> credentials in <code>config.json</code>, then sign in from Settings.</p>
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
      <td>${escHtml(formatTime12(a.time))}</td>
      <td>${escHtml(autoDaysLabel(a.days))}${a.shuffle ? ' · shuffle' : ''}${a.volume != null ? ` · vol ${a.volume}` : ''}</td>
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

  renderPage('Automation', `
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
    </div>`);

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

function autoToggleVolumeField() {
  const on = !!(document.getElementById('auto-volume-enable') || {}).checked;
  const slider = document.getElementById('auto-volume');
  const row = document.getElementById('auto-volume-row');
  if (slider) slider.disabled = !on;
  if (row) row.style.opacity = on ? '' : '0.45';
}

function autoEnableVolumeOnSlide() {
  const cb = document.getElementById('auto-volume-enable');
  if (cb && !cb.checked) {
    cb.checked = true;
    autoToggleVolumeField();
  }
}

function autoUpdateVolumeLabel() {
  const slider = document.getElementById('auto-volume');
  const valEl = document.getElementById('auto-volume-val');
  if (slider && valEl) valEl.textContent = slider.value;
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
    time: autoCollectTime24(),
    days: autoCollectDays(),
    shuffle: !!(document.getElementById('auto-shuffle') || {}).checked,
    enabled: !!(document.getElementById('auto-enabled') || {}).checked,
    volume: (document.getElementById('auto-volume-enable') || {}).checked
      ? parseInt((document.getElementById('auto-volume') || {}).value, 10)
      : null,
  };
  if (!body.playlistName) return showToast('Select a playlist', true);
  if (!body.playlistId) return showToast('Pick a playlist from search results', true);
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
function releaseKindLabel(kind) {
  return { feat: 'New', fix: 'Fixed', improve: 'Improved', chore: 'Update' }[kind] || 'Update';
}

function renderReleaseNotesHtml(releases) {
  if (!releases || !releases.length) return '<p class="hint">No release notes yet.</p>';
  return releases.map((rel, i) => {
    const items = (rel.items || []).filter(it => it.text).map(it =>
      `<li><span class="release-tag release-tag-${escHtml(it.kind || 'improve')}">${escHtml(releaseKindLabel(it.kind))}</span> ${escHtml(it.text)}</li>`
    ).join('');
    if (!items) return '';
    const date = rel.date ? ` · ${escHtml(rel.date)}` : '';
    const cls = i === 0 ? 'release-block release-latest' : 'release-block release-older';
    return `<div class="${cls}"><h4>Version ${escHtml(rel.version)}${date}</h4><ul class="release-list">${items}</ul></div>`;
  }).join('');
}

register('download', async () => {
  const info = await API('/api/app/info') || {};
  const android = info.android || {};
  const ios = info.ios || {};
  const releases = info.releases || [];
  const latest = releases[0];
  const highlightItems = (latest?.items || []).slice(0, 6).filter(it => it.text);
  const androidMeta = android.available
    ? `Build ${escHtml(android.version)}${android.sizeMb != null ? ` · ${android.sizeMb} MB` : ''}`
    : 'APK not on server yet';
  const iosMeta = ios.available
    ? `Build ${escHtml(ios.version)}${ios.sizeMb != null ? ` · ${ios.sizeMb} MB` : ''}`
    : 'IPA not on server yet';
  setPage(`
    <div class="card">
      <div class="card-header"><h3><i class="fa fa-mobile-screen"></i> Mobile app</h3></div>
      <div class="card-body app-download-page">
        <p class="hint">Install Bock Media on your phone. Use your server URL and Mobile API token from Settings.</p>
        ${highlightItems.length ? `
        <div class="release-highlights">
          <h4>Latest${latest?.version ? ` (v${escHtml(latest.version)})` : ''}</h4>
          <ul>${highlightItems.map(it => `<li>${escHtml(it.text)}</li>`).join('')}</ul>
        </div>` : ''}
        <div class="app-download-grid">
          <section class="app-download-platform">
            <h4><i class="fa fa-android"></i> Android</h4>
            <p class="hint">${androidMeta}</p>
            ${android.available && android.downloadHref
              ? `<a class="btn-sm btn-primary" href="${escHtml(android.downloadHref)}"><i class="fa fa-download"></i> Download APK</a>`
              : '<p class="hint">Build with <code>./gradlew assembleRelease</code> and copy to <code>.bockmedia/bockmedia-console.apk</code>.</p>'}
            <ol class="hint app-install-steps">
              <li>Download and install the APK</li>
              <li>Allow installs from browser if prompted</li>
              <li>Open app → enter server URL + Mobile API token</li>
            </ol>
          </section>
          <section class="app-download-platform">
            <h4><i class="fa fa-apple"></i> iPhone</h4>
            <p class="hint">${iosMeta}</p>
            ${ios.available && ios.otaHref
              ? `<a class="btn-sm btn-primary" href="${escHtml(ios.otaHref)}"><i class="fa fa-download"></i> Install on iPhone</a>`
              : ''}
            ${ios.available && ios.downloadHref
              ? `<a class="btn-sm btn-default" href="${escHtml(ios.downloadHref)}" style="margin-left:8px"><i class="fa fa-download"></i> Download IPA</a>`
              : '<p class="hint">Archive in Xcode and copy IPA to <code>.bockmedia/bockmedia-console.ipa</code>.</p>'}
            <ol class="hint app-install-steps">
              <li>Use Safari for over-the-air install, or sideload the IPA</li>
              <li>Trust the developer in Settings if prompted</li>
              <li>Open app → enter server URL + Mobile API token</li>
            </ol>
          </section>
        </div>
        <div class="release-notes-panel">
          <h4>Release notes</h4>
          ${renderReleaseNotesHtml(releases)}
        </div>
        <p class="hint" style="margin-top:16px">Standalone download page: <a href="/app" target="_blank" rel="noopener">/app</a> (may require app-download password).</p>
      </div>
    </div>`);
});

register('settings', async () => {
  loading();
  const [s, cfg, ipData, health, remote] = await Promise.all([
    API('/api/settings') || {},
    API('/api/config'),
    API('/api/localip'),
    API('/api/health').catch(() => null),
    ensureAlexaRemoteStatus().catch(() => ({})),
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
          <h4>Continue listening</h4>
          <p class="hint">When an album, playlist, or queue finishes on Alexa (and local apps), start similar songs or artist radio.</p>
          <div class="settings-row">
            <select id="s-continue-queue" class="settings-input">
              <option value="off" ${(settings.continueAfterQueue || 'off') === 'off' ? 'selected' : ''}>Stop</option>
              <option value="similar" ${settings.continueAfterQueue === 'similar' ? 'selected' : ''}>Similar songs</option>
              <option value="artist_radio" ${settings.continueAfterQueue === 'artist_radio' ? 'selected' : ''}>Artist radio</option>
            </select>
            <button class="btn-sm btn-primary" onclick="saveSetting('continueAfterQueue', document.getElementById('s-continue-queue').value)">Set</button>
          </div>
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
          <h4>Loudness normalization</h4>
          <p class="hint">Analyze your library once, then normalize playback on streams and phone downloads.</p>
          <div class="settings-row">
            <label style="font-size:12px;color:#667;min-width:140px">Mode</label>
            <select id="s-replay-gain" class="settings-input">
              <option value="off" ${(settings.replayGain || 'off') === 'off' ? 'selected' : ''}>Off</option>
              <option value="track" ${settings.replayGain === 'track' || settings.replayGain === 'true' ? 'selected' : ''}>Track</option>
              <option value="album" ${settings.replayGain === 'album' ? 'selected' : ''}>Album</option>
              <option value="loudnorm" ${settings.replayGain === 'loudnorm' ? 'selected' : ''}>EBU −14 LUFS</option>
            </select>
            <button class="btn-sm btn-primary" onclick="saveSetting('replayGain', document.getElementById('s-replay-gain').value)">Set</button>
          </div>
          <div class="settings-row" style="margin-top:8px">
            <button class="btn-sm btn-default" onclick="startLoudnessAnalyze()"><i class="fa fa-wave-square"></i> Analyze library</button>
            <span id="loudness-status" class="hint" style="margin-left:8px"></span>
          </div>
        </div>

        <div class="settings-section">
          <h4>Alexa Artwork and Metadata</h4>
          <p class="hint">Enables rich display on Echo Show and Echo Spot devices.</p>
          ${toggle('s-art', 'Send Album Artwork (Echo Show / Spot)', chk(settings.sendAlbumArt))}
          ${toggle('s-meta', 'Send Track Metadata (title, artist)', chk(settings.sendMetadata))}
        </div>

        <div class="settings-section">
          <h4>Plex Playlist Sync</h4>
          <p class="hint">Playlists are pulled from Plex every 5 minutes, and voice "add this to &lt;playlist&gt;" writes back to Plex. Status: ${
            !health ? '<span style="color:#9aa">unknown</span>'
            : !health.plexConfigured ? '<span style="color:#9aa">not configured</span>'
            : health.plexReachable ? '<span style="color:#1f8a4c;font-weight:600">connected</span>'
            : '<span style="color:#c0392b;font-weight:600">unreachable</span>'}</p>
        </div>

        <div class="settings-section" style="opacity:.6">
          <h4>Watch Folder Scanning <span style="font-size:11px;font-weight:600;color:#9a6520;background:#fdf1e3;border:1px solid #e6a14e;border-radius:3px;padding:1px 6px;margin-left:6px">LEGACY</span></h4>
          <p class="hint">Not used by this server. The original My Media scanner is stalled; playlists are now kept current by the Plex sync (<code>scripts/sync_plex_playlists.py</code>, every 5 min). These toggles are kept for reference only and have no effect.</p>
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
          <h4>Admin Account</h4>
          <p class="hint">Password is required only for external access (cellular / port-forward). The LAN address (e.g. 192.168.x.x) is open. Does not affect Alexa streaming.</p>
          ${toggle('s-pass', 'Require password for external access', requirePw, 'togglePasswordField(this.checked)')}
          <div id="s-pass-fields" style="${requirePw ? '' : 'display:none'}; margin-top:10px">
            <div class="settings-row">
              <label style="font-size:12px;color:#667;min-width:100px">Username</label>
              <input type="text" id="s-web-username" class="settings-input" value="${escHtml(settings.webUsername || 'morejava')}" autocomplete="username">
              <button class="btn-sm btn-primary" onclick="saveWebUsername()">Set</button>
            </div>
            <div class="settings-row" style="margin-top:8px">
              <label style="font-size:12px;color:#667;min-width:100px">New Password</label>
              <input type="password" id="s-web-password" class="settings-input" placeholder="Enter password" autocomplete="new-password">
              <button class="btn-sm btn-primary" onclick="savePassword()">Set</button>
            </div>
            <div class="settings-row" style="margin-top:8px">
              <button class="btn-sm btn-default" type="button" onclick="signOut()"><i class="fa fa-right-from-bracket"></i> Sign out</button>
            </div>
          </div>
        </div>

        <div class="settings-section">
          <h4>Mobile API token</h4>
          <p class="hint">Used by the Android app on cellular / port-forward. Stored in <code>config.json</code> → <code>mobileApi.token</code>.</p>
          <div class="settings-row">
            <input type="text" id="s-mobile-token" class="settings-input wide" value="${escHtml((cfg.mobileApi && cfg.mobileApi.token) || '')}" placeholder="Bearer token">
            <button class="btn-sm btn-primary" onclick="saveMobileApiToken()">Save</button>
          </div>
        </div>

        <div class="settings-section">
          <h4>Listening IP Address</h4>
          <p class="hint">The IP address Alexa devices use to reach this server on your local network.</p>
          <span class="ip-display"><i class="fa fa-network-wired" style="color:#e99d1a"></i> ${escHtml(localIp)}</span>
        </div>

        ${buildAlexaRemoteSettingsSection(remote, localIp)}

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
  updateAlexaLoginPanel(remote);
  const loginSt = remote.loginStatus || 'idle';
  if (loginSt === 'waiting' || loginSt === 'starting') pollAlexaLogin();
});

async function saveSetting(key, value) {
  const result = await POST('/api/settings', { [key]: value });
  if (result && result.ok) showToast('Setting saved');
  else showToast('Save failed', true);
}

async function startLoudnessAnalyze() {
  const el = document.getElementById('loudness-status');
  if (el) el.textContent = 'Starting…';
  const r = await POST('/api/library/analyze-loudness', {});
  if (!r || r.error) {
    if (el) el.textContent = r?.error || 'Failed';
    showToast('Analyze failed', true);
    return;
  }
  const poll = async () => {
    const st = await API('/api/library/analyze-loudness/status') || {};
    if (el) el.textContent = st.running
      ? `Analyzing ${st.processed || 0}/${st.total || 0}…`
      : `Done · ${st.processed || 0} processed`;
    if (st.running) setTimeout(poll, 2000);
  };
  poll();
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
    const user = (document.getElementById('s-web-username') || {}).value || '';
    const remember = !!localStorage.getItem(AUTH_STORAGE_KEY);
    if (user) storeAuth(user, pw, remember);
    showToast('Password set');
  } else {
    showToast('Failed to set password', true);
  }
}

async function saveWebUsername() {
  const user = ((document.getElementById('s-web-username') || {}).value || '').trim();
  if (!user) { showToast('Enter a username', true); return; }
  const r = await POST('/api/settings', { webUsername: user });
  if (r && r.ok) {
    const a = getStoredAuth();
    if (a) storeAuth(user, a.pass, !!localStorage.getItem(AUTH_STORAGE_KEY));
    showToast('Username saved');
  } else {
    showToast('Failed to save username', true);
  }
}

async function saveMobileApiToken() {
  const token = ((document.getElementById('s-mobile-token') || {}).value || '').trim();
  if (!token) { showToast('Enter a token', true); return; }
  const r = await POST('/api/config', { mobileApi: { token } });
  if (r && r.ok) showToast('Mobile API token saved');
  else showToast('Failed to save token', true);
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
  const hasDeviceActivity = (data?.deviceBreakdown || []).some(d =>
    (d.plays || 0) + (d.downloads || 0) + (d.connects || 0) > 0);
  if (!data || (!data.totalPlays && !hasDeviceActivity)) {
    renderPage('Analytics', `
      <div class="card" style="margin-bottom:20px">${_anDatePickerHtml(data)}</div>
      <div class="empty-state"><i class="fa fa-chart-bar"></i><p>No device activity yet.</p>
        <p style="font-size:12px;margin-top:8px">Play music on Alexa, Android, or iOS — or download offline on Android — to build analytics.</p></div>`);
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
  try {
    await ensureChartJs();
    _initAnalyticsCharts(data);
    if (Object.keys(data.playsPerDay || {}).length >= 7) _initEntityActivityCharts(data);
  } catch (e) {
    console.error('Analytics charts failed:', e);
  }
  loadIgnoredPanel();
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
    <a class="btn-sm btn-default" href="${anExportUrl()}" style="margin-left:auto"><i class="fa fa-download"></i> Export CSV</a>
  </div>`;
}

function anExportUrl() {
  let url = '/api/analytics/export?';
  if (_anFrom) url += `from=${encodeURIComponent(_anFrom)}&`;
  if (_anTo) url += `to=${encodeURIComponent(_anTo)}&`;
  return url;
}

register('analytics', async () => { _anFrom = ''; _anTo = ''; await _loadAnalytics(); });

// ── Library search ───────────────────────────────────────────────────────────
let _searchTimer = null;

register('search', async () => {
  const cached = typeof WebCache !== 'undefined'
    ? (WebCache.peekSearchBrowse() || WebCache.hydrateSearchFromDisk())
    : null;
  if (!cached) loading();
  renderPage('Search', `
    <div class="search-page search-page-topbar">
      <div id="lib-search-results" class="search-results"></div>
    </div>`, { header: false });
  syncTopbarSearch(window._lastSearchQ || '');
  const q = (window._lastSearchQ || '').trim();
  if (q.length >= 2) {
    const el = document.getElementById('lib-search-results');
    const scope = searchScopeQuery();
    const cacheKey = `${q}\0${scope}`;
    if (el && window._lastSearchRunKey === cacheKey && window._lastSearchData) {
      renderSearchResultsUI(el, window._lastSearchData, q);
    }
    libSearchDebounced(q);
  } else if (q.length === 1) {
    libSearchDebounced(q);
  } else if (cached) {
    const el = document.getElementById('lib-search-results');
    if (el) renderSearchBrowseUI(el, cached, window._plRemote || {});
    loadSearchBrowse();
  } else {
    await loadSearchBrowse();
  }
});

function searchThumbHtml(kind, artPath, seed, icon, unplayed) {
  const round = kind === 'artist';
  const cls = `search-hit-art${round ? ' search-hit-art-round' : ''}`;
  const url = artPath ? artworkUrl(artPath, 96) : null;
  const grad = spotifyGradient(seed || '');
  const ic = icon || 'fa-music';
  const badge = unplayed ? '<span class="unplayed-album-badge" aria-label="Unplayed"></span>' : '';
  if (!url) {
    return `<span class="${cls}" style="background:${grad}"><i class="fa ${ic}"></i>${badge}</span>`;
  }
  return `<span class="${cls} search-hit-art-img" style="background:${grad}">
    <img src="${escHtml(url)}" alt="" loading="lazy"
      onerror="this.closest('.search-hit-art').classList.add('search-hit-art-fallback');this.remove();">
    ${badge}
  </span>`;
}

function searchGenreTileHtml(genre) {
  const name = genre.name || '';
  const artPath = genre.art_path || genre.path;
  const url = artPath ? artworkUrl(artPath, 256) : null;
  const grad = spotifyGradient(name);
  const style = url
    ? `background-image:url('${escHtml(url)}');background-size:cover;background-position:center;`
    : `background:${grad};`;
  return `<button type="button" class="search-genre-tile" style="${style}" data-genre="${escHtml(name)}">
    <span class="search-genre-tile-label home-greeting">${escHtml(name)}</span>
  </button>`;
}

function searchNewReleasesTile(albums) {
  const first = (albums && albums[0]) || null;
  const artPath = first?.path || first?.art_path;
  const url = artPath ? artworkUrl(artPath, 256) : null;
  const grad = spotifyGradient(first?.name || 'New');
  const style = url
    ? `background-image:url('${escHtml(url)}');background-size:cover;background-position:center;`
    : `background:${grad};`;
  return `<a href="#albums" class="search-browse-tile search-new-releases-tile" style="${style}">
    <span class="search-browse-tile-icon"><i class="fa fa-bolt"></i></span>
    <span class="search-browse-tile-label home-greeting">New Releases</span>
  </a>`;
}

async function searchPlayPlaylist(id, name) {
  return startPlayback({ kind: 'playlist', id, name });
}
window.searchPlayPlaylist = searchPlayPlaylist;

function libSearchHit(opts) {
  if (typeof opts === 'string') {
    opts = {
      kind: 'song',
      titleHtml: opts,
      playOpts: arguments[2],
      extraActions: arguments[3] || '',
      showPlay: !!arguments[4],
    };
  }
  const {
    kind = 'song',
    titleHtml = '',
    subtitle = '',
    artPath = null,
    seed = '',
    icon = null,
    playOpts = null,
    extraActions = '',
    showPlay = false,
    href = null,
    unplayed = false,
  } = opts;
  const kindIcons = {
    playlist: 'fa-list',
    artist: 'fa-microphone',
    album: 'fa-compact-disc',
    song: 'fa-music',
    genre: 'fa-tag',
    room: 'fa-volume-high',
  };
  const thumb = searchThumbHtml(kind, artPath, seed || (playOpts && playOpts.name) || titleHtml, icon || kindIcons[kind] || 'fa-music', kind === 'album' && unplayed);
  let playBtn = '';
  if (showPlay && playOpts) {
    const idAttr = playOpts.id ? ` data-play-id="${escHtml(playOpts.id)}"` : '';
    const artistAttr = playOpts.artist ? ` data-play-artist="${escHtml(playOpts.artist)}"` : '';
    const pathAttr = playOpts.path ? ` data-play-path="${escHtml(playOpts.path)}"` : '';
    playBtn = `<button type="button" class="action-btn action-play lib-search-play" data-play-kind="${escHtml(playOpts.kind)}" data-play-name="${escHtml(playOpts.name)}"${idAttr}${artistAttr}${pathAttr} title="Play" aria-label="Play"><i class="fa fa-play"></i></button>`;
  }
  const subHtml = subtitle ? `<span class="search-hit-sub">${subtitle}</span>` : '';
  const mainInner = `<span class="search-hit-title home-greeting">${titleHtml}</span>${subHtml}`;
  const main = href
    ? `<a href="${href}" class="search-hit-main">${mainInner}</a>`
    : `<div class="search-hit-main">${mainInner}</div>`;
  return `<div class="search-hit search-hit-${kind}">${thumb}${main}<div class="search-hit-actions row-actions">${extraActions}${playBtn}</div></div>`;
}

function searchScopeQuery() {
  return '';
}

function searchScopeBarHtml() {
  return '';
}
window.searchScopeBarHtml = searchScopeBarHtml;

const SEARCH_RECENTS_KEY = 'searchRecentSelections';

function loadSearchRecentSelections() {
  try {
    const raw = localStorage.getItem(SEARCH_RECENTS_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch (_) {
    return [];
  }
}

function persistSearchRecentSelections(items) {
  try { localStorage.setItem(SEARCH_RECENTS_KEY, JSON.stringify(items.slice(0, 12))); } catch (_) {}
}

function addSearchRecentSelection(item) {
  const title = (item.title || '').trim();
  if (!title) return;
  const key = [item.kind, item.id, item.path, title, item.artist].join('|');
  const next = loadSearchRecentSelections().filter((x) => (
    [x.kind, x.id, x.path, x.title, x.artist].join('|') !== key
  ));
  next.unshift({ ...item, title });
  persistSearchRecentSelections(next);
}

function removeSearchRecentSelection(item) {
  const key = [item.kind, item.id, item.path, item.title, item.artist].join('|');
  persistSearchRecentSelections(loadSearchRecentSelections().filter((x) => (
    [x.kind, x.id, x.path, x.title, x.artist].join('|') !== key
  )));
}

function clearSearchRecentSelections() {
  try { localStorage.removeItem(SEARCH_RECENTS_KEY); } catch (_) {}
}

window.addSearchRecentSelection = addSearchRecentSelection;

function renderSearchBrowseUI(el, data, remote) {
  const recents = loadSearchRecentSelections();
  const pinItems = data.pins || [];
  window._searchBrowsePins = pinItems;
  const sec = (title, body, extraHeader) => body
    ? `<section class="spotify-section search-browse-section"><div class="spotify-section-header" style="display:flex;align-items:center;justify-content:space-between;gap:12px"><h2 class="spotify-section-title home-greeting">${escHtml(title)}</h2>${extraHeader || ''}</div>${body}</section>`
    : '';

  const linkRow = (label, onclick) => `<button type="button" class="search-browse-link-row" onclick="${onclick}"><span>${escHtml(label)}</span><i class="fa fa-chevron-right"></i></button>`;

  const auralBody = `<div class="search-browse-list">
    ${linkRow('Top Artists', 'openSearchRanking(\'artists\')')}
    ${linkRow('Top Albums', 'openSearchRanking(\'albums\')')}
    ${linkRow('Top Tracks', 'openSearchRanking(\'tracks\')')}
    ${linkRow('Best Of…', 'openSearchRanking(\'bestof\')')}
    ${pinItems.map((p, i) => linkRow(p.title || p.name || 'Shortcut', `openSearchPinByIndex(${i})`)).join('')}
  </div>`;

  const sonicBody = `<div class="search-browse-list">
    ${linkRow('Sonic Adventure', 'openAcquireIdeasModal({explore:true})')}
    ${linkRow('Sonic Sage', 'openMixMuseModal && openMixMuseModal()')}
  </div>`;

  const recentBody = recents.length
    ? `<div class="search-browse-list">${recents.map((r, i) => {
      window._searchBrowseRecents = recents;
      const sub = r.subtitle ? `<span class="text-muted">${escHtml(r.subtitle)}</span>` : '';
      return `<div class="search-browse-recent-row">
        <button type="button" class="search-browse-recent-main" onclick="openSearchRecentByIndex(${i})">
          <strong>${escHtml(r.title)}</strong>${sub}
        </button>
        <button type="button" class="btn-sm btn-default" onclick="removeSearchRecentByIndex(${i});loadSearchBrowse();" aria-label="Remove"><i class="fa fa-times"></i></button>
      </div>`;
    }).join('')}</div>`
    : '';

  el.innerHTML = [
    searchScopeBarHtml(),
    sec('Aural fixations', auralBody, '<button type="button" class="btn-sm btn-default" onclick="editSearchPins()">Edit</button>'),
    sec('Sonic explorations', sonicBody),
    recentBody ? sec('Recent', recentBody, recents.length ? '<button type="button" class="btn-sm btn-default" onclick="clearSearchRecentSelections();loadSearchBrowse();">Clear</button>' : '') : '',
  ].filter(Boolean).join('') || '<div class="empty-state"><p>Nothing in your library yet.</p></div>';
}

async function openSearchRanking(kind, periodDelta) {
  const el = document.getElementById('lib-search-results');
  if (!el) return;
  window._searchRankingKind = kind;
  if (kind === 'bestof') {
    window._searchRankingYear = window._searchRankingYear ?? new Date().getFullYear();
    if (periodDelta != null) window._searchRankingYear += periodDelta;
  } else {
    window._searchRankingWeek = window._searchRankingWeek ?? 0;
    if (periodDelta != null) window._searchRankingWeek += periodDelta;
  }
  el.innerHTML = '<div class="spinner-wrap"><div class="spinner"></div></div>';
  let from = '';
  let to = '';
  let periodHeader = '';
  if (kind === 'bestof') {
    const y = window._searchRankingYear;
    from = `${y}-01-01`;
    to = `${y}-12-31`;
    const canForward = y < new Date().getFullYear();
    periodHeader = `<div class="search-ranking-period">
      <button type="button" class="btn-sm btn-default" onclick="openSearchRanking('bestof', -1)"><i class="fa fa-chevron-left"></i></button>
      <div><strong>${y}</strong></div>
      <button type="button" class="btn-sm btn-default" ${canForward ? '' : 'disabled'} onclick="openSearchRanking('bestof', 1)"><i class="fa fa-chevron-right"></i></button>
    </div>`;
  } else {
    const offset = window._searchRankingWeek;
    const range = weekRangeForOffset(offset);
    from = range.start;
    to = range.end;
    const canForward = offset < 0;
    periodHeader = `<div class="search-ranking-period">
      <button type="button" class="btn-sm btn-default" onclick="openSearchRanking('${kind}', -1)"><i class="fa fa-chevron-left"></i></button>
      <div><strong>Weekly</strong><div class="hint">${escHtml(range.label)}</div></div>
      <button type="button" class="btn-sm btn-default" ${canForward ? '' : 'disabled'} onclick="openSearchRanking('${kind}', 1)"><i class="fa fa-chevron-right"></i></button>
    </div>`;
  }
  const qs = from ? `?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}` : '';
  const analytics = await API(`/api/analytics${qs}`).catch(() => null);
  if (!analytics) {
    el.innerHTML = `${searchScopeBarHtml()}<p class="hint" style="padding:12px">Analytics unavailable.</p>`;
    return;
  }
  const rows = kind === 'artists' ? analytics.topArtists
    : kind === 'albums' ? analytics.topAlbums
      : kind === 'tracks' ? analytics.topTracks
        : analytics.topTracks;
  const title = kind === 'artists' ? 'Top Artists'
    : kind === 'albums' ? 'Top Albums'
      : kind === 'tracks' ? 'Top Tracks'
        : 'Best Of…';
  const playLabel = (n) => (n === 1 ? '1 play' : `${n} plays`);
  const body = (rows || []).slice(0, 50).map((row, index) => {
    const name = row.name || row.label || '—';
    const artist = row.artist || '';
    const plays = playLabel(row.count || 0);
    let headline = name;
    let subtitle = '';
    let thumbKind = 'artist';
    if (kind === 'albums') {
      headline = artist || name;
      subtitle = artist ? name : '';
      thumbKind = 'album';
    } else if (kind === 'tracks' || kind === 'bestof') {
      headline = name;
      subtitle = artist;
      thumbKind = 'album';
    } else {
      thumbKind = 'artist';
    }
    const seed = kind === 'albums' ? name : (artist || name);
    const artPath = row.path || null;
    const thumb = searchThumbHtml(thumbKind, artPath, seed, 'fa-music', false);
    const href = kind === 'artists' ? `#songs/artist/${encodeURIComponent(name)}`
      : kind === 'albums' ? `#songs/album/${encodeURIComponent(name)}`
        : null;
    const main = href
      ? `<a href="${href}" class="search-ranking-main"><div class="search-ranking-text"><strong>${escHtml(headline)}</strong>${subtitle ? `<span>${escHtml(subtitle)}</span>` : ''}<span class="hint">${escHtml(plays)}</span></div></a>`
      : `<div class="search-ranking-main"><div class="search-ranking-text"><strong>${escHtml(headline)}</strong>${subtitle ? `<span>${escHtml(subtitle)}</span>` : ''}<span class="hint">${escHtml(plays)}</span></div></div>`;
    return `<div class="search-ranking-row"><span class="search-ranking-rank">${index + 1}</span>${thumb}${main}</div>`;
  }).join('');
  el.innerHTML = `${searchScopeBarHtml()}
    <section class="search-browse-section">
      <button type="button" class="btn-sm btn-default" onclick="loadSearchBrowse()" style="margin:8px 12px"><i class="fa fa-arrow-left"></i> Back</button>
      <h2 class="search-section-title home-greeting" style="padding:0 12px">${escHtml(title)}</h2>
      ${periodHeader}
      <div class="search-browse-list search-ranking-list">${body || '<p class="hint" style="padding:12px">No data yet.</p>'}</div>
    </section>`;
}

function weekRangeForOffset(offset) {
  const now = new Date();
  const day = now.getDay();
  const mondayOffset = day === 0 ? -6 : 1 - day;
  const monday = new Date(now);
  monday.setHours(0, 0, 0, 0);
  monday.setDate(now.getDate() + mondayOffset + offset * 7);
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  const fmt = (d) => d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
  return { start: monday.toISOString().slice(0, 10), end: sunday.toISOString().slice(0, 10), label: `${fmt(monday)} - ${fmt(sunday)}` };
}
window.weekRangeForOffset = weekRangeForOffset;
window.openSearchRanking = openSearchRanking;

function openSearchRecentSelection(item) {
  if (!item) return;
  switch (item.kind) {
    case 'artist': location.hash = `#songs/artist/${encodeURIComponent(item.title)}`; break;
    case 'album': location.hash = `#songs/album/${encodeURIComponent(item.title)}`; break;
    case 'playlist': if (item.id) location.hash = `#playlists/detail/${encodeURIComponent(item.id)}`; break;
    case 'genre': location.hash = '#genres'; break;
    case 'song': if (item.path) playSong(item.path, item.title, item.artist); break;
    default: break;
  }
}
window.openSearchRecentSelection = openSearchRecentSelection;
window.removeSearchRecentSelection = removeSearchRecentSelection;
window.clearSearchRecentSelections = clearSearchRecentSelections;

async function editSearchPins() {
  const cur = (await API('/api/search/pins').catch(() => ({ pins: [] }))).pins || [];
  const raw = prompt('Edit shortcuts (JSON array of {kind,title,name,id,artist,path})', JSON.stringify(cur, null, 2));
  if (raw == null) return;
  let pins;
  try { pins = JSON.parse(raw); } catch (_) { alert('Invalid JSON'); return; }
  await fetch('/api/search/pins', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pins }),
  });
  loadSearchBrowse();
}
window.editSearchPins = editSearchPins;

function openSearchRecentByIndex(index) {
  openSearchRecentSelection((window._searchBrowseRecents || [])[index]);
}
window.openSearchRecentByIndex = openSearchRecentByIndex;

function removeSearchRecentByIndex(index) {
  removeSearchRecentSelection((window._searchBrowseRecents || [])[index]);
}
window.removeSearchRecentByIndex = removeSearchRecentByIndex;

function openSearchPinByIndex(index) {
  openSearchPin((window._searchBrowsePins || [])[index]);
}
window.openSearchPinByIndex = openSearchPinByIndex;

function openSearchPin(pin) {
  const title = pin.title || pin.name || 'Shortcut';
  switch ((pin.kind || '').toLowerCase()) {
    case 'playlist': if (pin.id) location.hash = `#playlists/detail/${encodeURIComponent(pin.id)}`; break;
    case 'genre': playArtistRadio(pin.name || title); break;
    case 'artist': location.hash = `#songs/artist/${encodeURIComponent(pin.name || title)}`; break;
    case 'album': location.hash = `#songs/album/${encodeURIComponent(pin.name || title)}`; break;
    default: if (pin.path) playSong(pin.path, title); break;
  }
}
window.openSearchPin = openSearchPin;

async function loadSearchBrowse() {
  const el = document.getElementById('lib-search-results');
  if (!el) return;
  const cached = typeof WebCache !== 'undefined' ? WebCache.peekSearchBrowse() : null;
  if (cached) {
    renderSearchBrowseUI(el, cached, window._plRemote || {});
  } else {
    el.innerHTML = '<div class="spinner-wrap"><div class="spinner"></div></div>';
  }
  const [quick, genres, plItems, newAlbums, remote, pins] = await Promise.all([
    cached?.quick ? Promise.resolve(cached.quick) : API('/api/dashboard/quick').catch(() => ({ recent: [], favorites: [] })),
    cached?.genres ? Promise.resolve(cached.genres) : API('/api/genres?limit=16').catch(() => ({ items: [] })),
    cached?.playlists ? Promise.resolve(cached.playlists) : fetchPlaylistsCached('').catch(() => []),
    cached?.newAlbums ? Promise.resolve(cached.newAlbums) : API('/api/library/new?since=30d&limit=12').catch(() => ({ albums: [] })),
    ensureAlexaRemoteStatus().catch(() => ({})),
    API('/api/search/pins').catch(() => ({ pins: [] })),
  ]);
  const plIds = plItems.slice(0, 12).map((p) => p.id).filter(Boolean);
  const playlistCovers = plIds.length ? await fetchPlaylistCovers(plIds) : {};
  const data = { quick, genres, playlists: plItems, newAlbums, playlistCovers, pins: pins?.pins || [] };
  if (typeof WebCache !== 'undefined') WebCache.putSearchBrowse(data);
  renderSearchBrowseUI(el, data, remote);
}
window.loadSearchBrowse = loadSearchBrowse;

function libSearchDebounced(q) {
  window._lastSearchQ = q;
  clearTimeout(_searchTimer);
  _searchTimer = setTimeout(() => {
    const trimmed = (q || '').trim();
    if (trimmed.length === 1) libSearchSuggest(trimmed);
    else libSearchRun(trimmed);
  }, 280);
}

async function libSearchSuggest(q) {
  const el = document.getElementById('lib-search-results');
  if (!el) return;
  el.innerHTML = '<div class="spinner-wrap"><div class="spinner"></div></div>';
  const data = await API(`/api/search/suggest?q=${encodeURIComponent(q)}`).catch(() => null);
  if (!data) {
    el.innerHTML = '<p class="hint">Suggestions unavailable.</p>';
    return;
  }
  const rows = [];
  const pushRows = (kind, items, mapFn) => {
    for (const item of items || []) {
      rows.push(mapFn(item));
      if (rows.length >= 12) return;
    }
  };
  pushRows('playlist', data.playlists, (p) => libSearchHit({
    kind: 'playlist',
    titleHtml: escHtml(p.name),
    href: p.id ? `#playlists/detail/${encodeURIComponent(p.id)}` : null,
    playOpts: p.id ? { kind: 'playlist', name: p.name, id: p.id } : null,
    showPlay: !!p.id,
  }));
  pushRows('artist', data.artists, (a) => libSearchHit({
    kind: 'artist',
    titleHtml: escHtml(a.name),
    artPath: a.path,
    seed: a.name,
    href: `#songs/artist/${encodeURIComponent(a.name)}`,
    playOpts: { kind: 'artist', name: a.name },
    showPlay: true,
  }));
  pushRows('album', data.albums, (a) => libSearchHit({
    kind: 'album',
    titleHtml: escHtml(a.name),
    subtitle: a.artist ? escHtml(a.artist) : '',
    artPath: a.path,
    seed: a.name,
    unplayed: a.played === false,
    href: `#songs/album/${encodeURIComponent(a.name)}`,
    playOpts: { kind: 'album', name: a.name, artist: a.artist || '' },
    showPlay: true,
  }));
  pushRows('song', data.songs, (s) => libSearchHit({
    kind: 'song',
    titleHtml: escHtml(s.title || 'Track'),
    subtitle: s.artist ? escHtml(s.artist) : '',
    artPath: s.path,
    seed: s.title,
    playOpts: { kind: 'song', name: s.title, artist: s.artist || '', path: s.path || '' },
    showPlay: !!(s.path),
  }));
  el.innerHTML = rows.length
    ? `<section class="search-suggest-section"><h2 class="search-section-title home-greeting">Suggestions</h2><div class="search-browse-list">${rows.join('')}</div></section>`
    : '<p class="hint">Keep typing to search your library.</p>';
}

function libSearchPlayOptsFromBtn(btn) {
  const kind = btn.getAttribute('data-play-kind');
  const name = btn.getAttribute('data-play-name');
  const id = btn.getAttribute('data-play-id');
  const artist = btn.getAttribute('data-play-artist') || '';
  const path = btn.getAttribute('data-play-path') || '';
  if (!kind || !name) return null;
  const opts = { kind, name };
  if (id) opts.id = id;
  if (artist) opts.artist = artist;
  if (path) opts.path = path;
  return opts;
}

function libSearchLink(href, text) {
  return `<a href="${href}" class="search-hit-link">${escHtml(text)}</a>`;
}

function setupSearchDelegation() {
  if (window._searchDelegation) return;
  window._searchDelegation = true;
  document.addEventListener('click', (e) => {
    const playBtn = e.target.closest('.lib-search-play');
    if (playBtn) {
      e.preventDefault();
      e.stopPropagation();
      const opts = libSearchPlayOptsFromBtn(playBtn);
      if (!opts) return showToast('Invalid search result', true);
      startPlayback(opts).catch((err) => showToast(err.message || 'Playback failed', true));
      return;
    }
    const starBtn = e.target.closest('.lib-search-star');
    if (starBtn) {
      e.preventDefault();
      e.stopPropagation();
      const path = starBtn.getAttribute('data-fav-path');
      const title = starBtn.getAttribute('data-fav-title');
      const artist = starBtn.getAttribute('data-fav-artist') || '';
      if (!path) return;
      libFavorite(path, title, artist);
      return;
    }
    const showAllBtn = e.target.closest('.search-show-all');
    if (showAllBtn) {
      e.preventDefault();
      const section = showAllBtn.getAttribute('data-section');
      const q = showAllBtn.getAttribute('data-q');
      if (section && q) libSearchExpandSection(section, q);
    }
  });
}

async function libSearchRun(q) {
  const el = document.getElementById('lib-search-results');
  if (!el) return;
  q = (q || '').trim();
  if (q.length < 2) {
    loadSearchBrowse();
    return;
  }
  const scope = searchScopeQuery();
  const cacheKey = `${q}\0${scope}`;
  if (window._lastSearchRunKey === cacheKey && window._lastSearchData) {
    renderSearchResultsUI(el, window._lastSearchData, q);
    return;
  }
  el.innerHTML = `${searchScopeBarHtml()}<div class="spinner-wrap"><div class="spinner"></div></div>`;
  const data = await API(`/api/search?q=${encodeURIComponent(q)}&limit=30&preview=5${scope}`).catch(() => null);
  if (!data) {
    el.innerHTML = `${searchScopeBarHtml()}<p class="hint" style="padding:12px">Search failed — try again.</p>`;
    return;
  }
  window._lastSearchRunKey = cacheKey;
  window._lastSearchData = data;
  renderSearchResultsUI(el, data, q);
}
window.libSearchRun = libSearchRun;

async function libSearchExpandSection(section, q) {
  const scope = searchScopeQuery();
  const data = await API(`/api/search?q=${encodeURIComponent(q)}&section=${encodeURIComponent(section)}&limit=50&preview=50${scope}`).catch(() => null);
  if (!data) return;
  window._lastSearchExpanded = window._lastSearchExpanded || {};
  window._lastSearchExpanded[section] = data;
  const el = document.getElementById('lib-search-results');
  if (el && window._lastSearchData) renderSearchResultsUI(el, window._lastSearchData, q);
}

function renderSearchResultsUI(el, data, q) {
  const plIds = (data.playlists || []).map((p) => p.id).filter(Boolean);
  const smartIds = (data.smartPlaylists || []).map((s) => s.id).filter(Boolean);
  const expanded = window._lastSearchExpanded || {};
  const preview = data.preview || 5;

  const songRows = (items) => (items || []).map((s) => {
    const star = `<button type="button" class="action-btn action-muted lib-search-star" data-fav-path="${escHtml(s.path || '')}" data-fav-title="${escHtml(s.title || '')}" data-fav-artist="${escHtml(s.artist || '')}" title="Star" aria-label="Star"><i class="fa fa-star"></i></button>`;
    return libSearchHit({
      kind: 'song',
      titleHtml: escHtml(s.title),
      subtitle: s.artist ? escHtml(s.artist) : (s.album ? escHtml(s.album) : ''),
      artPath: s.path,
      seed: s.title,
      playOpts: { kind: 'song', name: s.title, artist: s.artist || '', path: s.path || '' },
      extraActions: star,
      showPlay: !!(s.path),
    });
  }).join('');

  const playlistRows = (items) => (items || []).map((p) => libSearchHit({
    kind: 'playlist',
    titleHtml: escHtml(p.name),
    artPath: p.id ? (window._searchPlaylistCovers || {})[p.id] : null,
    seed: p.name,
    href: p.id ? `#playlists/detail/${encodeURIComponent(p.id)}` : null,
    playOpts: p.id ? { kind: 'playlist', name: p.name, id: p.id } : null,
    showPlay: !!p.id,
  })).join('');

  const smartRows = (items) => (items || []).map((s) => libSearchHit({
    kind: 'playlist',
    titleHtml: `${escHtml(s.name)} <span class="text-muted">· smart</span>`,
    artPath: s.id ? (window._searchPlaylistCovers || {})[s.id] : null,
    seed: s.name,
    href: s.id ? `#playlists/detail/${encodeURIComponent(s.id)}` : null,
    playOpts: s.id ? { kind: 'playlist', name: s.name, id: s.id } : null,
    showPlay: !!s.id,
  })).join('');

  const artistRows = (items) => (items || []).map((a) => libSearchHit({
    kind: 'artist',
    titleHtml: escHtml(a.name),
    artPath: a.path,
    seed: a.name,
    href: `#songs/artist/${encodeURIComponent(a.name)}`,
    playOpts: { kind: 'artist', name: a.name },
    showPlay: true,
  })).join('');

  const albumRows = (items) => (items || []).map((a) => libSearchHit({
    kind: 'album',
    titleHtml: escHtml(a.name),
    subtitle: a.artist ? escHtml(a.artist) : '',
    artPath: a.path,
    seed: a.name,
    href: `#songs/album/${encodeURIComponent(a.name)}`,
    playOpts: { kind: 'album', name: a.name, artist: a.artist || '' },
    showPlay: true,
  })).join('');

  const genreRows = (items) => (items || []).map((g) => libSearchHit({
    kind: 'genre',
    titleHtml: escHtml(g.name),
    artPath: g.path,
    seed: g.name,
    href: '#genres',
    showPlay: false,
  })).join('');

  const sectionTitle = (key) => ({
    songs: 'Tracks',
    artists: 'Artists',
    albums: 'Albums',
    similar: 'Sonically similar',
    smartPlaylists: 'Smart playlists',
    messages: 'Messages',
    radios: 'Radio',
  }[key] || key.charAt(0).toUpperCase() + key.slice(1));

  const sectionHtml = (key, rowsFn, items) => {
    if (!items || !items.length) return '';
    const total = (data.counts && data.counts[key]) || items.length;
    const body = expanded[key] ? rowsFn(expanded[key][key] || items) : rowsFn(items);
    const more = !expanded[key] && total > preview
      ? `<button type="button" class="btn-sm btn-default search-show-all" data-section="${escHtml(key)}" data-q="${escHtml(q)}">Show all ${total}</button>`
      : '';
    return `<section class="search-results-section"><h2 class="search-section-title home-greeting">${escHtml(sectionTitle(key))}</h2><div class="search-browse-list">${body}</div>${more}</section>`;
  };

  Promise.all([
    plIds.length || smartIds.length ? fetchPlaylistCovers([...new Set(plIds.concat(smartIds))]) : Promise.resolve({}),
  ]).then(([covers]) => {
    window._searchPlaylistCovers = covers || {};
    const radio = (data.radios || []).map((r) => libSearchHit({
      kind: 'song',
      titleHtml: escHtml(r.displayTitle || `${r.name} Radio`),
      subtitle: 'Radio',
      artPath: r.path,
      seed: r.name,
      playOpts: { kind: 'artist', name: r.name || '' },
      showPlay: true,
    })).join('');

    const body = [
      sectionHtml('songs', songRows, data.songs),
      sectionHtml('artists', artistRows, data.artists),
      sectionHtml('albums', albumRows, data.albums),
      sectionHtml('radios', () => radio, data.radios),
      sectionHtml('similar', songRows, data.similar),
      sectionHtml('playlists', playlistRows, data.playlists),
      sectionHtml('smartPlaylists', smartRows, data.smartPlaylists),
      sectionHtml('genres', genreRows, data.genres),
      sectionHtml('messages', songRows, data.messages),
    ].filter(Boolean).join('');
    el.innerHTML = `${searchScopeBarHtml()}${body || '<p class="hint" style="padding:12px">No matches.</p>'}`;
  });
}
window.renderSearchResultsUI = renderSearchResultsUI;
window.libSearchExpandSection = libSearchExpandSection;

async function libFavorite(path, title, artist) {
  const res = await fetch('/api/favorites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, title, artist }),
  });
  if (res.ok) showToast(`Starred "${title}"`);
  else showToast('Failed', true);
}

function _platformIcon(platform) {
  const p = (platform || '').toLowerCase();
  if (p === 'android') return '<i class="fa fa-android" style="color:#3ddc84"></i>';
  if (p === 'ios') return '<i class="fa fa-apple" style="color:#555"></i>';
  if (p === 'alexa') return '<i class="fa fa-volume-up" style="color:#00caff"></i>';
  return '<i class="fa fa-circle-question" style="color:#aab"></i>';
}

function _formatLastSeen(ts) {
  if (!ts) return '—';
  const sec = Math.max(0, Math.floor(Date.now() / 1000 - ts));
  if (sec < 60) return 'just now';
  if (sec < 3600) return `${Math.floor(sec / 60)}m ago`;
  if (sec < 86400) return `${Math.floor(sec / 3600)}h ago`;
  return `${Math.floor(sec / 86400)}d ago`;
}

function _buildDeviceBreakdownHTML(devices) {
  const rows = (devices || []).filter(d =>
    (d.plays || 0) + (d.downloads || 0) + (d.connects || 0) > 0);
  if (!rows.length) return '';
  return `
    <div class="card" style="margin-bottom:20px">
      <div class="card-header"><h3><i class="fa fa-mobile-screen"></i> Device Activity</h3></div>
      <div class="card-body" style="padding:0;overflow-x:auto">
        <table class="data-table" style="width:100%;font-size:13px">
          <thead>
            <tr>
              <th style="text-align:left;padding:10px 14px">Device</th>
              <th>Platform</th>
              <th>Connects</th>
              <th>Plays</th>
              <th>Downloads</th>
              <th>Last seen</th>
            </tr>
          </thead>
          <tbody>
            ${rows.map(d => `
              <tr>
                <td style="padding:10px 14px;font-weight:600">${escHtml(d.name || d.deviceId || '—')}</td>
                <td style="text-align:center">${_platformIcon(d.platform)} <span style="font-size:11px;color:#778">${escHtml((d.platform || 'unknown').toUpperCase())}</span></td>
                <td style="text-align:center">${fmtNum(d.connects || 0)}</td>
                <td style="text-align:center">${fmtNum(d.plays || 0)}</td>
                <td style="text-align:center">${fmtNum(d.downloads || 0)}</td>
                <td style="text-align:center;color:#778;font-size:12px">${_formatLastSeen(d.lastSeen)}</td>
              </tr>`).join('')}
          </tbody>
        </table>
      </div>
    </div>`;
}

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

    ${_buildDeviceBreakdownHTML(d.deviceBreakdown)}

    <div class="card" style="margin-top:20px">
      <div class="card-header"><h3><i class="fa fa-ban"></i> Never Play Again</h3></div>
      <div class="card-body" id="an-ignored-body">
        <p class="hint" style="margin:0">Loading…</p>
      </div>
    </div>
`;
}

async function loadIgnoredPanel() {
  const body = document.getElementById('an-ignored-body');
  if (!body) return;
  const data = await API('/api/ignored');
  const items = (data && data.items) || [];
  if (!items.length) {
    body.innerHTML = `<p class="hint" style="margin:0">No ignored tracks. Use the <i class="fa fa-ban"></i> button in Now Playing to never play a song again.</p>`;
    return;
  }
  body.innerHTML = `
    <ul class="device-list" style="margin:0">
      ${items.map(it => `
        <li>
          <span class="device-icon-col"><i class="fa fa-ban" style="color:#c0392b"></i></span>
          <span class="device-name-text">
            <b>${escHtml(it.title || (it.path || '').split('/').pop())}</b>
            ${it.artist ? `<span style="font-size:11px;color:#9aa;margin-left:6px">${escHtml(it.artist)}</span>` : ''}
          </span>
          <div class="row-actions">
            <button class="btn-sm btn-default" onclick="unignoreTrack('${escHtml(encodeURIComponent(it.path))}')">Allow again</button>
          </div>
        </li>`).join('')}
    </ul>`;
}

async function unignoreTrack(encodedPath) {
  const path = decodeURIComponent(encodedPath);
  const res = await fetch('/api/ignored', {
    method: 'DELETE', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  });
  if (!res.ok) return showToast('Failed', true);
  showToast('Track allowed again');
  loadIgnoredPanel();
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
      const bg    = v > 0 ? `rgba(80,155,245,${alpha})` : '#282828';
      html += `<div class="an-hm-cell" style="background:${bg}" title="${lbl} ${days[d]}: ${v} play${v !== 1 ? 's' : ''}"></div>`;
    }
  }
  html += '</div>';
  return html;
}

function _initAnalyticsCharts(d) {
  if (typeof Chart === 'undefined') {
    console.error('Chart.js is not loaded — analytics charts skipped');
    return;
  }
  const gridColor = '#282828';
  const C = {
    navy: '#509bf5', orange: '#e99d1a', green: '#1db954',
    purple: '#b49bc8', teal: '#1db954',
  };
  const xTick = { color: '#b3b3b3', font: { size: 11 } };
  const yTick = { color: '#b3b3b3', font: { size: 11 } };
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
          backgroundColor: 'rgba(80,155,245,0.15)',
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
          y: { grid: { color: gridColor }, ticks: yTick, beginAtZero: true },
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
          y: { grid: { color: gridColor }, ticks: yTick, beginAtZero: true },
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
          y: { grid: { color: gridColor }, ticks: yTick, beginAtZero: true },
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
          x: { grid: { color: gridColor }, ticks: xTick, beginAtZero: true },
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
            y: { grid: { color: gridColor }, ticks: yTick, beginAtZero: true },
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
  if (!canvas || typeof Chart === 'undefined') return;
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
  t.style.background = isError ? '#e91429' : '#282828';
  t.style.color = '#fff';
  t.style.opacity = '1';
  clearTimeout(t._timer);
  t._timer = setTimeout(() => { t.style.opacity = '0'; }, 3000);
}

function setupPlayerBarListeners() {
  if (window._playerBarListeners) return;
  window._playerBarListeners = true;

  const play = (deviceId, action) => {
    if (deviceId) npControl(deviceId, action);
  };

  document.getElementById('np-mini-play')?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
      WebPlayback.toggle();
      return;
    }
    const deviceId = e.currentTarget.dataset.deviceId;
    const d = (window._npItems || []).find(x => x.deviceId === deviceId);
    if (!deviceId || !d) return;
    play(deviceId, d.paused ? 'play' : 'pause');
  });

  document.getElementById('np-bar-prev')?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
      WebPlayback.prev().catch(() => {});
      return;
    }
    play(e.currentTarget.dataset.deviceId, 'previous');
  });

  document.getElementById('np-bar-next')?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
      WebPlayback.next().catch(() => {});
      return;
    }
    play(e.currentTarget.dataset.deviceId, 'next');
  });

  document.getElementById('np-bar-shuffle')?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
      const st = WebPlayback.getState();
      WebPlayback.setShuffle(!st.shuffle);
      return;
    }
    npToggleShuffleEl(e.currentTarget);
  });

  document.getElementById('np-bar-favorite')?.addEventListener('click', (e) => {
    e.stopPropagation();
    if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
      const btn = e.currentTarget;
      const path = btn.dataset.favPath;
      if (!path) return;
      libFavorite(path, btn.dataset.favTitle || '', btn.dataset.favArtist || '');
      return;
    }
    npFavoriteEl(e.currentTarget);
  });

  document.getElementById('np-bar-sleep')?.addEventListener('click', (e) => {
    e.stopPropagation();
    const deviceId = e.currentTarget.dataset.deviceId;
    if (deviceId) npOpenSleep(deviceId);
  });

  const vol = document.getElementById('np-bar-volume');
  if (vol) {
    const dragOn = () => { window._playerBarVolumeDragging = true; };
    const dragOff = () => { window._playerBarVolumeDragging = false; };
    vol.addEventListener('mousedown', dragOn);
    vol.addEventListener('touchstart', dragOn);
    vol.addEventListener('mouseup', dragOff);
    vol.addEventListener('touchend', dragOff);
    vol.addEventListener('input', () => {
      if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
        WebPlayback.setVolume(parseInt(vol.value, 10));
        return;
      }
      npVolumeEl(vol);
    });
    vol.addEventListener('change', () => {
      if (typeof WebPlayback !== 'undefined' && WebPlayback.active) {
        WebPlayback.setVolume(parseInt(vol.value, 10));
        return;
      }
      npVolumeEl(vol);
    });
  }

  document.querySelector('.player-progress-bar')?.addEventListener('click', (e) => {
    if (typeof WebPlayback === 'undefined' || !WebPlayback.active) return;
    e.stopPropagation();
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    WebPlayback.seekRatio(ratio);
  });
}

function setupShellListeners() {
  setupPlayerBarListeners();
  document.getElementById('account-menu-btn')?.addEventListener('click', openAppDrawer);
  document.querySelector('.sidebar-user-btn')?.addEventListener('click', openAppDrawer);
  document.getElementById('topbar-settings')?.addEventListener('click', openAppDrawer);
  document.getElementById('topbar-profile')?.addEventListener('click', openAppDrawer);
  document.getElementById('topbar-back')?.addEventListener('click', () => window.history.back());
  document.getElementById('spotify-queue-close')?.addEventListener('click', () => toggleQueuePanel(false));
  document.getElementById('np-bar-queue')?.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleQueuePanel();
  });
  document.getElementById('np-bar-repeat')?.addEventListener('click', (e) => {
    e.stopPropagation();
    const deviceId = e.currentTarget.dataset.deviceId;
    if (deviceId) npControl(deviceId, 'loop');
    e.currentTarget.classList.toggle('active');
  });
  const topSearch = document.getElementById('topbar-search-q');
  const topForm = document.getElementById('topbar-search-form');
  topSearch?.addEventListener('input', () => {
    const q = topSearch.value;
    window._lastSearchQ = q;
    if (currentRoute !== 'search') window.location.hash = 'search';
    libSearchDebounced(q);
  });
  topForm?.addEventListener('submit', (e) => {
    e.preventDefault();
    if (currentRoute !== 'search') window.location.hash = 'search';
    libSearchDebounced(topSearch?.value || '');
  });
  document.getElementById('topbar-search-browse')?.addEventListener('click', () => {
    window.location.hash = 'search';
    loadSearchBrowse();
  });
  document.getElementById('drawer-close')?.addEventListener('click', closeAppDrawer);
  document.getElementById('app-backdrop')?.addEventListener('click', closeAppDrawer);
  document.getElementById('now-playing-bar')?.addEventListener('click', (e) => {
    if (e.target.closest('.player-center, .player-right, .player-like, .player-transport, .player-btn, .player-volume-wrap, .player-volume-slider')) return;
    window.location.hash = 'nowplaying';
  });
  document.querySelectorAll('.drawer-link').forEach((a) => {
    a.addEventListener('click', () => closeAppDrawer());
  });
  document.addEventListener('click', (e) => {
    const genreTile = e.target.closest('.search-genre-tile[data-genre]');
    if (genreTile) {
      e.preventDefault();
      const g = genreTile.getAttribute('data-genre') || '';
      window._lastSearchQ = g;
      const inp = document.getElementById('lib-search-q');
      if (inp) inp.value = g;
      libSearchRun(g);
      return;
    }
    const tile = e.target.closest('.genre-tile[data-genre]');
    if (tile) window._lastSearchQ = tile.getAttribute('data-genre') || '';
  });
}

// Expose routes for UI tests (assigned after all register() calls below)

// ── Global banner (alexapy session expiry, etc.) ─────────────────────────────
let _bannerDismissed = false;

async function refreshGlobalBanner() {
  const el = document.getElementById('global-banner');
  if (!el || _bannerDismissed) return;
  const s = await API('/api/alexa_remote/status');
  // Only warn when remote control is configured but the session has expired.
  if (s && s.configured && s.authenticated === false) {
    el.innerHTML = `
      <div class="global-alert">
        <i class="fa fa-triangle-exclamation"></i>
        <span class="global-alert-text">Alexa session expired — Play on device and Now Playing controls won't work until you sign in again.</span>
        <button type="button" class="btn-sm btn-primary global-alert-signin" onclick="openAlexaReLoginModal(true)">
          <i class="fa fa-key"></i> Sign in to Amazon
        </button>
        <button type="button" class="global-alert-dismiss" onclick="dismissBanner()" title="Dismiss"><i class="fa fa-xmark"></i></button>
      </div>`;
  } else {
    el.innerHTML = '';
  }
}

function dismissBanner() {
  _bannerDismissed = true;
  const el = document.getElementById('global-banner');
  if (el) el.innerHTML = '';
}

// ── Family (household profiles, room ownership, kid-safe, analytics) ──────────

function activeMemberId() { return localStorage.getItem('bock_active_member') || ''; }
function setActiveMember(id) {
  if (id) localStorage.setItem('bock_active_member', id);
  else localStorage.removeItem('bock_active_member');
}
function parentPin() { return sessionStorage.getItem('bock_parent_pin') || ''; }

function memberById(id) {
  return (window._household?.members || []).find(m => m.id === id) || null;
}

function memberChip(m, opts = {}) {
  const color = m.color || '#7c8aa5';
  const initial = (m.name || '?').trim().charAt(0).toUpperCase();
  return `<span class="member-chip" style="display:inline-flex;align-items:center;gap:6px">
    <span style="width:22px;height:22px;border-radius:50%;background:${escHtml(color)};color:#fff;display:inline-flex;align-items:center;justify-content:center;font-size:11px;font-weight:600">${escHtml(initial)}</span>
    <span>${escHtml(m.name)}</span>${m.role === 'parent' ? ' <i class="fa fa-user-shield" style="color:#9a6520;font-size:11px" title="Parent"></i>' : ''}
  </span>`;
}

function memberOptions(selected, includeNone = true) {
  const members = window._household?.members || [];
  const none = includeNone ? `<option value="" ${!selected ? 'selected' : ''}>— Unattributed —</option>` : '';
  return none + members.map(m =>
    `<option value="${escHtml(m.id)}" ${m.id === selected ? 'selected' : ''}>${escHtml(m.name)}</option>`).join('');
}

register('family', async () => {
  loading();
  const [household, devices, remote] = await Promise.all([
    API('/api/household'),
    API('/api/devices'),
    ensureAlexaRemoteStatus(),
  ]);
  window._household = household || { members: [], deviceOwners: [], clientBindings: [] };
  window._familyDevices = (devices || []).filter(d => !isAppClient(d));
  window._familyRemote = !!(remote && remote.configured);
  let alexaList = [];
  if (window._familyRemote) alexaList = await ensureAlexaDevices().catch(() => []);
  window._familyAlexa = alexaList || [];
  const hh = await API('/api/analytics/household');
  window._familyStats = hh || null;
  renderFamily();
});

function renderFamily() {
  const h = window._household;
  const members = h.members || [];
  const owners = {};
  (h.deviceOwners || []).forEach(o => { owners[o.deviceId] = o.memberId; });

  const acting = activeMemberId();
  const actingSel = `
    <div class="card" style="margin-bottom:16px">
      <div class="card-body" style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
        <span class="hint" style="margin:0"><i class="fa fa-user"></i> Acting as</span>
        <select class="settings-input" id="acting-member" onchange="onActingChange(this.value)">${memberOptions(acting)}</select>
        <span class="hint" style="margin:0">— used when sharing playlists, sending messages, or approving requests.</span>
      </div>
    </div>`;

  const membersHtml = `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-users"></i> Members (${members.length})</h3></div>
      <div class="card-body">
        ${members.length ? `<ul class="device-list" style="margin:0 0 12px">${members.map(m => `
          <li>
            <span style="flex:1">${memberChip(m)}</span>
            <select class="settings-input" style="max-width:110px" onchange="updateMemberRole('${escHtml(m.id)}', this.value)">
              <option value="kid" ${m.role !== 'parent' ? 'selected' : ''}>Kid</option>
              <option value="parent" ${m.role === 'parent' ? 'selected' : ''}>Parent</option>
            </select>
            ${m.role === 'parent' ? actionBtn({ kind: 'edit', onclick: `setMemberPin('${escHtml(m.id)}')`, title: m.hasPin ? 'Change PIN' : 'Set PIN', icon: 'key' }) : ''}
            ${actionBtn({ kind: 'delete', onclick: `deleteMember('${escHtml(m.id)}','${escHtml(m.name)}')`, title: 'Remove member', icon: 'trash' })}
          </li>`).join('')}</ul>` : '<p class="hint" style="margin:0 0 12px">No members yet. Add the people in your household.</p>'}
        <div class="settings-row" style="gap:8px">
          <input type="text" id="new-member-name" class="settings-input" placeholder="Name (e.g. Emma)" style="max-width:200px">
          <select id="new-member-role" class="settings-input" style="max-width:110px">
            <option value="kid">Kid</option><option value="parent">Parent</option>
          </select>
          <button class="btn-sm btn-primary" onclick="addMember()"><i class="fa fa-plus"></i> Add</button>
        </div>
      </div>
    </div>`;

  const rooms = (window._familyDevices || []);
  const roomsHtml = `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-house"></i> Rooms — owner &amp; kid-safe</h3></div>
      <div class="card-body">
        <p class="hint" style="margin:0 0 10px">Assign a room to a person (e.g. Office Show → Andy). Leave community rooms (Kitchen, Living Room, Basement, Garage) <b>Unattributed</b>. Kid-safe settings need a parent PIN.</p>
        ${rooms.length ? `<ul class="device-list" style="margin:0">${rooms.map(d => `
          <li style="flex-wrap:wrap;gap:8px">
            <span class="device-icon-col"><i class="fa fa-headphones"></i></span>
            <span class="device-name-text" style="flex:1">${escHtml(d.name)}</span>
            <select class="settings-input" style="max-width:160px" onchange="setRoomOwner('${escHtml(d.deviceId)}', this.value)">${memberOptions(owners[d.deviceId])}</select>
            <button class="btn-sm btn-default" onclick="openKidSafe('${escHtml(d.deviceId)}','${escHtml(d.name)}')"><i class="fa fa-shield-halved"></i> Kid-safe</button>
          </li>`).join('')}</ul>` : `<p class="hint" style="margin:0">${window._familyRemote ? 'No Echo devices found.' : 'Connect Alexa (Devices tab) to manage rooms.'}</p>`}
      </div>
    </div>`;

  renderPage('Family', actingSel + membersHtml + roomsHtml + renderFamilyStats() + renderFamilyMessages());
  loadFamilyMessages();
}

function renderFamilyStats() {
  const s = window._familyStats;
  if (!s) return '';
  const bm = (s.byMember || []).slice(0, 8);
  const bp = s.byPlatform || [];
  const maxPlays = Math.max(1, ...bm.map(x => x.plays));
  return `
    <div class="card" style="margin-bottom:16px">
      <div class="card-header"><h3><i class="fa fa-chart-simple"></i> Family activity (${fmtNum(s.totalPlays)} plays)</h3></div>
      <div class="card-body">
        ${bm.length ? bm.map(x => `
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <span style="width:120px;font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escHtml(x.name)}</span>
            <div style="flex:1;background:#eef2f8;border-radius:6px;height:14px;overflow:hidden">
              <div style="width:${Math.round(x.plays / maxPlays * 100)}%;height:100%;background:#30426a"></div>
            </div>
            <span style="width:50px;text-align:right;font-size:12px;color:#556">${fmtNum(x.plays)}</span>
          </div>`).join('') : '<p class="hint" style="margin:0">No attributed plays yet.</p>'}
        ${bp.length ? `<p class="hint" style="margin:10px 0 0">By platform: ${bp.map(p => `${escHtml(p.platform)} ${fmtNum(p.plays)}`).join(' · ')}</p>` : ''}
      </div>
    </div>`;
}

function renderFamilyMessages() {
  return `
    <div class="card">
      <div class="card-header"><h3><i class="fa fa-comment-music"></i> Music messages</h3></div>
      <div class="card-body">
        <div class="settings-row" style="gap:8px;margin-bottom:12px">
          <select id="msg-to" class="settings-input" style="max-width:160px"><option value="">Whole household</option>${memberOptions('', false)}</select>
          <input type="text" id="msg-text" class="settings-input" placeholder="Say something about music…" style="flex:1">
          <button class="btn-sm btn-primary" onclick="sendFamilyMessage()"><i class="fa fa-paper-plane"></i> Send</button>
        </div>
        <div id="family-messages"><div class="spinner-wrap"><div class="spinner"></div></div></div>
      </div>
    </div>`;
}

async function loadFamilyMessages() {
  const me = activeMemberId();
  const data = await API(`/api/messages${me ? `?member=${encodeURIComponent(me)}` : ''}`);
  const el = document.getElementById('family-messages');
  if (!el) return;
  const items = (data && data.items) || [];
  if (!items.length) { el.innerHTML = '<p class="hint" style="margin:0">No messages yet.</p>'; return; }
  el.innerHTML = items.slice().reverse().slice(0, 30).map(m => {
    const att = m.attach ? ` <span class="hint" style="margin:0"><i class="fa fa-${m.attach.type === 'playlist' ? 'list' : 'music'}"></i> ${escHtml(m.attach.type)}</span>` : '';
    const to = m.toName ? ` → ${escHtml(m.toName)}` : (m.scope === 'household' ? ' → all' : '');
    return `<div style="padding:6px 0;border-bottom:1px solid #f0f3f8">
      <b style="font-size:13px">${escHtml(m.fromName || 'Someone')}</b><span class="hint" style="margin:0">${to}</span>
      <span style="font-size:13px;margin-left:6px">${escHtml(m.text || '')}</span>${att}
      <span class="hint" style="margin:0;float:right">${m.ts ? fmtDateTime(new Date(m.ts * 1000).toISOString()) : ''}</span>
    </div>`;
  }).join('');
}

function onActingChange(id) { setActiveMember(id); loadFamilyMessages(); }

async function addMember() {
  const name = (document.getElementById('new-member-name') || {}).value?.trim();
  const role = (document.getElementById('new-member-role') || {}).value || 'kid';
  if (!name) return showToast('Enter a name', true);
  const r = await POST('/api/household/members', { name, role });
  if (r && r.id) { showToast(`Added ${r.name}`); navigate('family'); }
  else showToast((r && r.error) || 'Failed', true);
}

async function updateMemberRole(id, role) {
  const r = await authFetch(`/api/household/members/${encodeURIComponent(id)}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  }).then(x => x.json()).catch(() => null);
  if (r && r.id) { showToast('Updated'); navigate('family'); }
  else showToast((r && r.error) || 'Failed', true);
}

async function deleteMember(id, name) {
  if (!confirm(`Remove ${name}? Their bindings are cleared (history stays).`)) return;
  const r = await authFetch(`/api/household/members/${encodeURIComponent(id)}`, { method: 'DELETE' })
    .then(x => x.json()).catch(() => null);
  if (r && r.ok) { showToast('Removed'); navigate('family'); }
  else showToast('Failed', true);
}

async function setMemberPin(id) {
  const pin = prompt('Set a 4+ digit parent PIN:');
  if (!pin) return;
  const m = memberById(id);
  const body = { pin };
  if (m && m.hasPin) { const cur = prompt('Current PIN:'); if (cur) body.currentPin = cur; }
  const r = await POST(`/api/household/members/${encodeURIComponent(id)}/pin`, body);
  if (r && r.ok) { sessionStorage.setItem('bock_parent_pin', pin); showToast('PIN set'); navigate('family'); }
  else showToast((r && r.error) || 'Failed', true);
}

async function setRoomOwner(deviceId, memberId) {
  if (memberId) {
    const r = await POST(`/api/devices/${encodeURIComponent(deviceId)}/owner`, { memberId });
    showToast(r && r.ok ? 'Owner set' : ((r && r.error) || 'Failed'), !(r && r.ok));
  } else {
    const r = await authFetch(`/api/devices/${encodeURIComponent(deviceId)}/owner`, { method: 'DELETE' })
      .then(x => x.json()).catch(() => null);
    showToast(r && r.ok ? 'Set to unattributed' : 'Failed', !(r && r.ok));
  }
}

async function ensureParentPin() {
  let pin = parentPin();
  if (pin) return pin;
  pin = prompt('Parent PIN (required for kid-safe changes):') || '';
  if (pin) sessionStorage.setItem('bock_parent_pin', pin);
  return pin;
}

async function openKidSafe(deviceId, name) {
  const me = activeMemberId();
  if (!me || (memberById(me) || {}).role !== 'parent') {
    return showToast('Switch "Acting as" to a parent first.', true);
  }
  const [pol, pls] = await Promise.all([
    API(`/api/devices/${encodeURIComponent(deviceId)}/policy`),
    API('/api/playlists?limit=500'),
  ]);
  const playlists = (pls && pls.items) || [];
  const allow = new Set(pol.allowPlaylistIds || []);
  const overlay = document.createElement('div');
  overlay.className = 'modal-overlay';
  overlay.id = 'kidsafe-overlay';
  overlay.innerHTML = `
    <div class="modal-box" style="max-width:520px;max-height:85vh;overflow:auto">
      <h3 style="margin-top:0"><i class="fa fa-shield-halved"></i> Kid-safe — ${escHtml(name)}</h3>
      <label style="display:flex;align-items:center;gap:8px;margin:8px 0"><input type="checkbox" id="ks-safe" ${pol.safe ? 'checked' : ''}> Enable kid-safe for this room</label>
      <label style="display:flex;align-items:center;gap:8px;margin:8px 0"><input type="checkbox" id="ks-explicit" ${pol.allowExplicit === false ? '' : 'checked'}> Allow explicit content</label>
      <label style="display:flex;align-items:center;gap:8px;margin:8px 0"><input type="checkbox" id="ks-approval" ${pol.requireApproval ? 'checked' : ''}> Require approval for requests</label>
      <div style="margin:10px 0">
        <label class="hint" style="margin:0">Max volume: <b id="ks-vol-label">${pol.maxVolume ?? 'none'}</b></label>
        <input type="range" id="ks-vol" min="0" max="100" value="${pol.maxVolume ?? 100}" style="width:100%" oninput="document.getElementById('ks-vol-label').textContent=this.value">
        <label style="font-size:12px"><input type="checkbox" id="ks-vol-on" ${typeof pol.maxVolume === 'number' ? 'checked' : ''}> Enforce volume cap</label>
      </div>
      <div style="margin:10px 0">
        <label class="hint" style="margin:0 0 6px;display:block">Quiet hours (optional)</label>
        <div style="display:flex;gap:8px;align-items:center">
          <input type="time" id="ks-qh-from" class="settings-input" value="${escHtml((pol.quietHours?.[0]?.from) || '20:30')}">
          <span>to</span>
          <input type="time" id="ks-qh-to" class="settings-input" value="${escHtml((pol.quietHours?.[0]?.to) || '07:00')}">
          <label style="font-size:12px"><input type="checkbox" id="ks-qh-on" ${pol.quietHours?.length ? 'checked' : ''}> on</label>
        </div>
      </div>
      <div style="margin:10px 0">
        <label class="hint" style="margin:0 0 6px;display:block">Allowed playlists (empty = all allowed in safe mode is blocked)</label>
        <div style="max-height:180px;overflow:auto;border:1px solid #eef2f8;border-radius:6px;padding:8px">
          ${playlists.map(p => `<label style="display:block;font-size:13px;padding:2px 0"><input type="checkbox" class="ks-pl" value="${escHtml(p.id)}" ${allow.has(p.id) ? 'checked' : ''}> ${escHtml(p.name)}</label>`).join('') || '<span class="hint">No playlists</span>'}
        </div>
      </div>
      <div style="display:flex;justify-content:flex-end;gap:8px;margin-top:14px">
        <button class="cancel-btn" onclick="document.getElementById('kidsafe-overlay').remove()">Cancel</button>
        <button class="save-btn" onclick="saveKidSafe('${escHtml(deviceId)}')">Save</button>
      </div>
    </div>`;
  document.body.appendChild(overlay);
}

async function saveKidSafe(deviceId) {
  const pin = await ensureParentPin();
  if (!pin) return showToast('PIN required', true);
  const volOn = document.getElementById('ks-vol-on').checked;
  const qhOn = document.getElementById('ks-qh-on').checked;
  const body = {
    memberId: activeMemberId(),
    pin,
    safe: document.getElementById('ks-safe').checked,
    allowExplicit: document.getElementById('ks-explicit').checked,
    requireApproval: document.getElementById('ks-approval').checked,
    maxVolume: volOn ? parseInt(document.getElementById('ks-vol').value, 10) : null,
    quietHours: qhOn ? [{ days: [0, 1, 2, 3, 4, 5, 6], from: document.getElementById('ks-qh-from').value, to: document.getElementById('ks-qh-to').value }] : [],
    allowPlaylistIds: Array.from(document.querySelectorAll('.ks-pl:checked')).map(c => c.value),
  };
  const r = await POST(`/api/devices/${encodeURIComponent(deviceId)}/policy`, body);
  if (r && r.deviceId) { showToast('Kid-safe saved'); document.getElementById('kidsafe-overlay')?.remove(); }
  else if (r && r.error === 'parent_pin_required') { sessionStorage.removeItem('bock_parent_pin'); showToast('Wrong PIN', true); }
  else showToast((r && r.error) || 'Failed', true);
}

async function sendFamilyMessage() {
  const text = (document.getElementById('msg-text') || {}).value?.trim();
  const to = (document.getElementById('msg-to') || {}).value || '';
  if (!text) return showToast('Type a message', true);
  const r = await POST('/api/messages', {
    fromMemberId: activeMemberId(), toMemberId: to || null,
    scope: to ? 'direct' : 'household', text,
  });
  if (r && r.id) { document.getElementById('msg-text').value = ''; loadFamilyMessages(); }
  else showToast((r && r.error) || 'Failed', true);
}

// ── Init ─────────────────────────────────────────────────────────────────────
function installAuthFetch() {
  if (window._bockAuthFetchInstalled) return;
  window._bockAuthFetchInstalled = true;
  const nativeFetch = window.fetch.bind(window);
  window.fetch = (input, init = {}) => {
    const headers = { ...authHeaders(), ...(init.headers || {}) };
    return nativeFetch(input, { ...init, headers });
  };
}

async function init() {
  installAuthFetch();
  if (typeof WebCache !== 'undefined') {
    WebCache.hydrateHomeFromDisk();
    WebCache.hydrateLibraryFromDisk();
    WebCache.hydrateSearchFromDisk();
  }

  window.addEventListener('hashchange', () => {
    const hash = window.location.hash.replace('#', '');
    if (!hash.startsWith('nowplaying')) {
      clearInterval(_npPollTimer);
      _npPollTimer = null;
      clearInterval(_npTickTimer);
      _npTickTimer = null;
    }
    navigate(hash);
  });

  setupPlaylistSortDelegation();
  setupSearchDelegation();
  setupHomeFilterDelegation();
  setupShellListeners();

  if (typeof WebPlayback !== 'undefined') {
    WebPlayback.onChange((st) => {
      if (!st.active) {
        renderPlayerBar();
        return;
      }
      renderWebPlayerBar(st);
      if (!document.getElementById('spotify-queue')?.classList.contains('hidden')) {
        renderQueuePanel();
      }
    });
  }

  // Initial route
  const hash = window.location.hash.replace('#', '') || 'dashboard';
  navigate(hash);

  refreshAuthInfo().then(() => ensureAuth()).catch(() => {});

  refreshCurrentTrack();
  setInterval(refreshCurrentTrack, 6000);
  refreshSidebarPlaylists();
  setInterval(refreshSidebarPlaylists, 120000);
  if (!_npGlobalTickTimer) {
    _npGlobalTickTimer = setInterval(npTickTimes, 1000);
  }

  refreshGlobalBanner();
  setInterval(refreshGlobalBanner, 120000);
  warmBackgroundCaches();
  prefetchCriticalApis();
}

window.routes = routes;
init();
