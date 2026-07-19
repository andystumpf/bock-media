/**
 * Spotify-style shell: 3-column layout, sidebar collapse, profile/notifications.
 */
(function (root) {
  'use strict';

  const PREF = {
    sidebarCollapsed: 'bock_shell_sidebar_collapsed',
    rightPanelW: 'bock_shell_right_panel_w',
    rightPanelTab: 'bock_shell_right_panel_tab',
    rightPanelOpen: 'bock_shell_right_panel_open',
    sidebarFilter: 'bock_shell_sidebar_filter',
    sidebarSort: 'bock_shell_sidebar_sort',
  };

  let sidebarPlaylists = [];
  let sidebarFilter = localStorage.getItem(PREF.sidebarFilter) || 'playlists';
  let sidebarSort = localStorage.getItem(PREF.sidebarSort) || 'recents';

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function applyLayoutPrefs() {
    const collapsed = localStorage.getItem(PREF.sidebarCollapsed) === '1';
    document.body.classList.toggle('sidebar-collapsed', collapsed);
    const rw = parseInt(localStorage.getItem(PREF.rightPanelW) || '350', 10);
    if (rw >= 280 && rw <= 480) {
      document.documentElement.style.setProperty('--right-panel-w', `${rw}px`);
    }
    const open = localStorage.getItem(PREF.rightPanelOpen) !== '0';
    document.body.classList.toggle('right-panel-open', open);
    document.body.classList.toggle('right-panel-hidden', !open);
    document.getElementById('topbar-right-panel')?.classList.toggle('active', open);
  }

  function toggleSidebarCollapse() {
    const next = !document.body.classList.contains('sidebar-collapsed');
    document.body.classList.toggle('sidebar-collapsed', next);
    localStorage.setItem(PREF.sidebarCollapsed, next ? '1' : '0');
  }

  function initResizeHandles() {
    const handle = document.getElementById('shell-resize-right');
    const panel = document.getElementById('spotify-right-panel');
    if (!handle || !panel) return;
    let dragging = false;
    handle.addEventListener('mousedown', (e) => {
      dragging = true;
      e.preventDefault();
    });
    root.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      const w = Math.max(280, Math.min(480, window.innerWidth - e.clientX - 8));
      document.documentElement.style.setProperty('--right-panel-w', `${w}px`);
    });
    root.addEventListener('mouseup', () => {
      if (!dragging) return;
      dragging = false;
      const w = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--right-panel-w'), 10);
      if (w) localStorage.setItem(PREF.rightPanelW, String(w));
    });
  }

  function closeDropdowns(except) {
    ['profile-dropdown', 'notifications-panel'].forEach((id) => {
      if (except === id) return;
      document.getElementById(id)?.classList.add('hidden');
    });
  }

  function closeProfileDropdown() {
    document.getElementById('profile-dropdown')?.classList.add('hidden');
  }

  function positionProfileDropdown(anchor) {
    const menu = document.getElementById('profile-dropdown');
    if (!menu || !anchor) return;
    const rect = anchor.getBoundingClientRect();
    const mw = menu.offsetWidth || 260;
    let left = Math.min(rect.right - mw, window.innerWidth - mw - 8);
    let top = rect.bottom + 8;
    left = Math.max(8, left);
    if (top + menu.offsetHeight > window.innerHeight - 8) {
      top = Math.max(8, rect.top - menu.offsetHeight - 8);
    }
    menu.style.top = `${top}px`;
    menu.style.left = `${left}px`;
  }

  function toggleProfileDropdown(anchorEl) {
    const menu = document.getElementById('profile-dropdown');
    if (!menu) return;
    const willOpen = menu.classList.contains('hidden');
    closeDropdowns(willOpen ? 'profile-dropdown' : null);
    if (!willOpen) {
      closeProfileDropdown();
      return;
    }
    renderProfileDropdown();
    menu.classList.remove('hidden');
    positionProfileDropdown(anchorEl || document.getElementById('topbar-profile'));
  }

  function toggleNotificationsPanel() {
    const el = document.getElementById('notifications-panel');
    if (!el) return;
    const open = el.classList.contains('hidden');
    closeDropdowns(open ? 'notifications-panel' : null);
    el.classList.toggle('hidden', !open);
    if (open) renderNotifications();
  }

  async function renderNotifications() {
    const body = document.getElementById('notifications-body');
    if (!body) return;
    body.innerHTML = '<p class="hint">Loading…</p>';
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      const after = localStorage.getItem('bock_follow_notif_last_seen') || '';
      const afterQs = after ? `&after=${encodeURIComponent(after)}` : '';
      const [followedNew, libNew, messages] = await Promise.all([
        fetchFn(`/api/notifications/followed?since=30d&limit=12${afterQs}`, { credentials: 'same-origin' }).then((r) => r.json()).catch(() => ({})),
        fetchFn('/api/library/new?since=14d&limit=8', { credentials: 'same-origin' }).then((r) => r.json()).catch(() => ({})),
        fetchFn('/api/messages?limit=8', { credentials: 'same-origin' }).then((r) => r.json()).catch(() => ({})),
      ]);
      const followedAlbums = followedNew.albums || [];
      const albums = libNew.albums || [];
      const msgs = messages.items || messages.messages || [];
      const timestamps = followedAlbums.map((a) => a.first_seen_at).filter(Boolean);
      if (timestamps.length) {
        localStorage.setItem('bock_follow_notif_last_seen', timestamps.sort().slice(-1)[0]);
      }
      updateNotificationBadge(followedNew.unreadCount || 0);
      if (!followedAlbums.length && !albums.length && !msgs.length) {
        body.innerHTML = `<div class="notifications-empty"><i class="fa fa-check"></i><p>You're all caught up</p></div>`;
        return;
      }
      let html = '';
      if (followedAlbums.length) {
        html += `<div class="notifications-section"><h4>Artists you follow</h4>${followedAlbums.map((a) =>
          `<a href="#album/${encodeURIComponent(a.artist || '')}/${encodeURIComponent(a.album || '')}" class="notifications-item">
            <span class="notifications-item-title">${esc(a.album)}</span>
            <span class="notifications-item-sub">${esc(a.artist)} · New in library</span>
          </a>`).join('')}</div>`;
      }
      if (albums.length) {
        html += `<div class="notifications-section"><h4>New in your library</h4>${albums.map((a) =>
          `<a href="#album/${encodeURIComponent(a.artist || '')}/${encodeURIComponent(a.album || '')}" class="notifications-item">
            <span class="notifications-item-title">${esc(a.album)}</span>
            <span class="notifications-item-sub">${esc(a.artist)}</span>
          </a>`).join('')}</div>`;
      }
      if (msgs.length) {
        html += `<div class="notifications-section"><h4>Messages</h4>${msgs.map((m) =>
          `<div class="notifications-item"><span class="notifications-item-title">${esc(m.text || m.message || 'Update')}</span></div>`).join('')}</div>`;
      }
      body.innerHTML = html;
    } catch {
      body.innerHTML = `<div class="notifications-empty"><i class="fa fa-check"></i><p>You're all caught up</p></div>`;
    }
  }

  function updateNotificationBadge(count) {
    const btn = document.getElementById('topbar-notifications');
    if (!btn) return;
    let badge = btn.querySelector('.notif-badge');
    if (count > 0) {
      if (!badge) {
        badge = document.createElement('span');
        badge.className = 'notif-badge';
        btn.appendChild(badge);
      }
      badge.textContent = count > 9 ? '9+' : String(count);
    } else if (badge) {
      badge.remove();
    }
  }

  async function refreshNotificationBadge() {
    try {
      const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
      const after = localStorage.getItem('bock_follow_notif_last_seen') || '';
      const afterQs = after ? `&after=${encodeURIComponent(after)}` : '';
      const data = await fetchFn(`/api/notifications/followed?since=30d&limit=1${afterQs}`, { credentials: 'same-origin' }).then((r) => r.json());
      updateNotificationBadge(data.unreadCount || 0);
    } catch { /* ignore */ }
  }

  function renderProfileDropdown() {
    const members = (root._household && root._household.members) || [];
    const active = typeof root.activeMemberId === 'function' ? root.activeMemberId() : localStorage.getItem('bock_active_member') || '';
    const memberHtml = members.length
      ? `<div class="profile-dropdown-section"><span class="profile-dropdown-label">Switch profile</span>${members.map((m) =>
        `<button type="button" class="profile-dropdown-item${m.id === active ? ' active' : ''}" data-member="${esc(m.id)}">${esc(m.name)}</button>`).join('')}</div><div class="profile-dropdown-divider"></div>`
      : '';
    const adminLinks = [
      ['#rooms', 'Rooms', 'house'],
      ['#devices', 'Alexa Devices', 'headphones'],
      ['#family', 'Family', 'users'],
      ['#analytics', 'Analytics', 'chart-bar'],
      ['#watchfolders', 'Watch Folders', 'folder-open'],
      ['#automation', 'Automations', 'clock'],
      ['#settings', 'Settings', 'gear'],
    ];
    const adminHtml = adminLinks.map(([href, label, icon]) =>
      `<a href="${href}" class="profile-dropdown-item"><i class="fa fa-${icon}"></i>${esc(label)}</a>`).join('');
    const menu = document.getElementById('profile-dropdown');
    if (!menu) return;
    menu.innerHTML = `
      ${memberHtml}
      <a href="#settings" class="profile-dropdown-item"><i class="fa fa-user"></i>Account</a>
      <a href="#dashboard" class="profile-dropdown-item"><i class="fa fa-clock-rotate-left"></i>Recents</a>
      <a href="#download" class="profile-dropdown-item"><i class="fa fa-download"></i>Download app</a>
      <div class="profile-dropdown-divider"></div>
      ${adminHtml}
      <div class="profile-dropdown-divider"></div>
      <button type="button" class="profile-dropdown-item" id="profile-logout"><i class="fa fa-right-from-bracket"></i>Log out</button>
      <div class="profile-dropdown-updates"><i class="fa fa-check"></i> You're all caught up</div>`;
    menu.querySelectorAll('[data-member]').forEach((btn) => {
      btn.addEventListener('click', () => {
        localStorage.setItem('bock_active_member', btn.getAttribute('data-member') || '');
        if (typeof root.ClientPrefsSync !== 'undefined') root.ClientPrefsSync.schedulePush?.();
        closeProfileDropdown();
        if (typeof root.navigate === 'function') root.navigate(root.location.hash.replace('#', '') || 'dashboard');
      });
    });
    menu.querySelectorAll('a.profile-dropdown-item').forEach((link) => {
      link.addEventListener('click', () => closeProfileDropdown());
    });
    menu.querySelector('#profile-logout')?.addEventListener('click', () => {
      sessionStorage.removeItem('bock_auth');
      root.location.reload();
    });
  }

  const FILTER_ROUTES = {
    playlists: 'playlists',
    artists: 'artists',
    albums: 'albums',
  };

  function routeRoot() {
    return (root.location.hash || '').replace('#', '').split('/')[0] || 'dashboard';
  }

  function filterForRoute(route) {
    const r = (route || routeRoot()).split('/')[0];
    if (r === 'artists' || r === 'artist') return 'artists';
    if (r === 'albums' || r === 'album') return 'albums';
    if (r === 'playlists' || r === 'liked') return 'playlists';
    if (r === 'library') return sidebarFilter || 'playlists';
    return null;
  }

  function syncSidebarFilterChips(filter) {
    document.querySelectorAll('.sidebar-filter-chip').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.filter === filter);
    });
  }

  function syncSidebarFilterFromRoute(route) {
    const mapped = filterForRoute(route);
    if (!mapped) return;
    sidebarFilter = mapped;
    localStorage.setItem(PREF.sidebarFilter, mapped);
    syncSidebarFilterChips(mapped);
  }

  function updateSidebarSortLabel() {
    const btn = document.getElementById('sidebar-sort-toggle');
    if (!btn) return;
    btn.innerHTML = sidebarSort === 'name'
      ? '<i class="fa fa-arrow-down-a-z"></i> A–Z'
      : '<i class="fa fa-clock"></i> Recents';
  }

  function sidebarEmptyMessage() {
    if (sidebarFilter === 'artists') return 'No artists yet';
    if (sidebarFilter === 'albums') return 'No albums yet';
    return 'No playlists yet';
  }

  function renderSidebarPlaylists(items, covers) {
    const el = document.getElementById('sidebar-playlist-list');
    if (!el) return;
    sidebarPlaylists = items || [];
    const route = (root.location.hash || '').replace('#', '');
    const filtered = sortSidebarItems(filterSidebarItems(sidebarPlaylists));
    const likedCount = root._likedCount || '';
    const likedActive = route === 'liked' ? ' active' : '';
    const showLiked = sidebarFilter === 'playlists';
    const likedTile = showLiked
      ? `<a href="#liked" class="sidebar-pl-item sidebar-liked-tile${likedActive}" title="Liked Songs">
      <span class="sidebar-pl-art sidebar-liked-art"><i class="fa fa-heart"></i></span>
      <span class="sidebar-pl-text"><span class="sidebar-pl-name">Liked Songs</span><span class="sidebar-pl-sub">Playlist${likedCount ? ` · ${likedCount} songs` : ''}</span></span>
    </a>`
      : '';
    const rows = filtered.map((p) => {
      const href = `#playlists/detail/${encodeURIComponent(p.id)}`;
      const active = route === `playlists/detail/${p.id}` ? ' active' : '';
      const cover = (covers || {})[p.id];
      const artUrl = cover && typeof root.artworkUrl === 'function' ? root.artworkUrl(cover, 96) : null;
      const art = artUrl
        ? `<img src="${esc(artUrl)}" alt="" loading="lazy">`
        : `<i class="fa fa-list"></i>`;
      const count = p.trackCount ?? p.track_count ?? p.tracks ?? '';
      const sub = count ? `Playlist · ${count} songs` : 'Playlist';
      return `<a href="${href}" class="sidebar-pl-item${active}" title="${esc(p.name)}">
        <span class="sidebar-pl-art">${art}</span>
        <span class="sidebar-pl-text"><span class="sidebar-pl-name">${esc(p.name)}</span><span class="sidebar-pl-sub">${esc(sub)}</span></span>
      </a>`;
    }).join('');
    const empty = filtered.length ? '' : `<p class="hint sidebar-empty">${sidebarEmptyMessage()}</p>`;
    el.innerHTML = likedTile + rows + empty;
  }

  function renderSidebarArtists(items) {
    const el = document.getElementById('sidebar-playlist-list');
    if (!el) return;
    const route = (root.location.hash || '').replace('#', '');
    const list = sortSidebarItems(items || []);
    const rows = list.map((a) => {
      const name = a.artist || a.name || '';
      if (!name) return '';
      const href = `#artist/${encodeURIComponent(name)}`;
      const active = route === `artist/${encodeURIComponent(name)}` ? ' active' : '';
      const artPath = a.art_path || a.path;
      const artUrl = artPath && typeof root.artworkUrl === 'function' ? root.artworkUrl(artPath, 96) : null;
      const art = artUrl
        ? `<img src="${esc(artUrl)}" alt="" loading="lazy">`
        : '<i class="fa fa-microphone"></i>';
      const count = a.track_count ?? a.trackCount ?? '';
      const sub = count ? `Artist · ${count} songs` : 'Artist';
      return `<a href="${href}" class="sidebar-pl-item${active}" title="${esc(name)}">
        <span class="sidebar-pl-art sidebar-pl-art-round">${art}</span>
        <span class="sidebar-pl-text"><span class="sidebar-pl-name">${esc(name)}</span><span class="sidebar-pl-sub">${esc(sub)}</span></span>
      </a>`;
    }).join('');
    el.innerHTML = rows || `<p class="hint sidebar-empty">${sidebarEmptyMessage()}</p>`;
  }

  function renderSidebarAlbums(items) {
    const el = document.getElementById('sidebar-playlist-list');
    if (!el) return;
    const route = (root.location.hash || '').replace('#', '');
    const list = sortSidebarItems(items || []);
    const rows = list.map((a) => {
      const album = a.album || a.name || '';
      const artist = a.artist || '';
      if (!album) return '';
      const href = `#album/${encodeURIComponent(artist)}/${encodeURIComponent(album)}`;
      const active = route.startsWith(`album/${encodeURIComponent(artist)}/${encodeURIComponent(album)}`) ? ' active' : '';
      const artPath = a.path || a.art_path;
      const artUrl = artPath && typeof root.artworkUrl === 'function' ? root.artworkUrl(artPath, 96) : null;
      const art = artUrl
        ? `<img src="${esc(artUrl)}" alt="" loading="lazy">`
        : '<i class="fa fa-compact-disc"></i>';
      const sub = artist ? `Album · ${artist}` : 'Album';
      return `<a href="${href}" class="sidebar-pl-item${active}" title="${esc(album)}">
        <span class="sidebar-pl-art">${art}</span>
        <span class="sidebar-pl-text"><span class="sidebar-pl-name">${esc(album)}</span><span class="sidebar-pl-sub">${esc(sub)}</span></span>
      </a>`;
    }).join('');
    el.innerHTML = rows || `<p class="hint sidebar-empty">${sidebarEmptyMessage()}</p>`;
  }

  async function refreshSidebarContent() {
    const el = document.getElementById('sidebar-playlist-list');
    if (!el) return;
    syncSidebarFilterFromRoute(routeRoot());
    updateSidebarSortLabel();
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const api = typeof root.API === 'function'
      ? root.API
      : (path) => fetchFn(path, { credentials: 'same-origin' }).then((r) => r.json()).catch(() => null);

    if (sidebarFilter === 'artists') {
      const data = await api('/api/artists?page=1&limit=80');
      renderSidebarArtists((data && data.items) || []);
      return;
    }
    if (sidebarFilter === 'albums') {
      const data = await api('/api/albums?page=1&limit=80');
      renderSidebarAlbums((data && data.items) || []);
      return;
    }

    try {
      const items = typeof root.fetchPlaylistsCached === 'function'
        ? await root.fetchPlaylistsCached('')
        : ((await api('/api/playlists?page=1&limit=200&fields=summary'))?.items || []);
      const slice = items || [];
      const ids = slice.map((p) => p.id).filter(Boolean);
      const covers = ids.length && typeof root.fetchPlaylistCovers === 'function'
        ? await root.fetchPlaylistCovers(ids)
        : {};
      if (typeof root.ensureArtworkSigned === 'function') {
        await root.ensureArtworkSigned(Object.values(covers), [96]);
      }
      try {
        const fav = await api('/api/favorites');
        root._likedCount = (fav?.items || fav?.songs || []).length;
      } catch { root._likedCount = ''; }
      renderSidebarPlaylists(slice, covers);
    } catch {
      el.innerHTML = `<p class="hint sidebar-empty">${sidebarEmptyMessage()}</p>`;
    }
  }

  function filterSidebarItems(items) {
    return items || [];
  }

  function sortSidebarItems(items) {
    const list = [...(items || [])];
    const label = (item) => item.name || item.artist || item.album || '';
    if (sidebarSort === 'name') {
      list.sort((a, b) => label(a).localeCompare(label(b)));
    }
    return list;
  }

  function renderSidebarLibrary(items, covers) {
    sidebarFilter = 'playlists';
    syncSidebarFilterChips('playlists');
    renderSidebarPlaylists(items, covers);
  }

  function setSidebarFilter(filter) {
    const next = FILTER_ROUTES[filter] ? filter : 'playlists';
    sidebarFilter = next;
    localStorage.setItem(PREF.sidebarFilter, next);
    syncSidebarFilterChips(next);
    const target = FILTER_ROUTES[next];
    if (routeRoot() !== target) {
      root.location.hash = target;
      return;
    }
    refreshSidebarContent();
  }

  function toggleRightPanel(force) {
    const open = force != null ? !!force : document.body.classList.contains('right-panel-hidden');
    document.body.classList.toggle('right-panel-open', open);
    document.body.classList.toggle('right-panel-hidden', !open);
    localStorage.setItem(PREF.rightPanelOpen, open ? '1' : '0');
    document.getElementById('topbar-right-panel')?.classList.toggle('active', open);
    if (open && typeof root.RightPanel !== 'undefined') root.RightPanel.refresh();
  }

  function init() {
    applyLayoutPrefs();
    initResizeHandles();
    renderProfileDropdown();

    document.getElementById('sidebar-collapse-btn')?.addEventListener('click', toggleSidebarCollapse);
    document.getElementById('sidebar-create-btn')?.addEventListener('click', () => {
      if (typeof root.openCreatePlaylistModal === 'function') root.openCreatePlaylistModal();
      else root.location.hash = 'playlists';
    });
    document.getElementById('topbar-home')?.addEventListener('click', () => { root.location.hash = 'dashboard'; });
    document.getElementById('topbar-profile')?.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleProfileDropdown(e.currentTarget);
    });
    document.getElementById('account-menu-btn')?.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleProfileDropdown(e.currentTarget);
    });
    document.getElementById('topbar-notifications')?.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleNotificationsPanel();
    });
    refreshNotificationBadge();
    document.getElementById('topbar-right-panel')?.addEventListener('click', () => toggleRightPanel());

    document.querySelectorAll('.sidebar-filter-chip').forEach((btn) => {
      btn.addEventListener('click', () => setSidebarFilter(btn.dataset.filter || 'playlists'));
    });
    document.getElementById('sidebar-library-search')?.addEventListener('input', (e) => {
      const q = (e.target.value || '').toLowerCase();
      document.querySelectorAll('#sidebar-playlist-list .sidebar-pl-item').forEach((row) => {
        const name = (row.querySelector('.sidebar-pl-name')?.textContent || '').toLowerCase();
        row.style.display = !q || name.includes(q) ? '' : 'none';
      });
    });
    document.getElementById('sidebar-sort-toggle')?.addEventListener('click', () => {
      sidebarSort = sidebarSort === 'recents' ? 'name' : 'recents';
      localStorage.setItem(PREF.sidebarSort, sidebarSort);
      updateSidebarSortLabel();
      refreshSidebarContent();
    });

    syncSidebarFilterFromRoute(routeRoot());
    updateSidebarSortLabel();
    refreshSidebarContent();

    root.addEventListener('click', (e) => {
      if (!e.target.closest('#profile-dropdown, #topbar-profile, #notifications-panel, #topbar-notifications')) {
        closeDropdowns();
      }
    });

    root.addEventListener('keydown', (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        document.getElementById('topbar-search-q')?.focus();
      }
    });
  }

  root.ShellLayout = {
    init,
    renderSidebarLibrary,
    renderProfileDropdown,
    toggleRightPanel,
    toggleNotificationsPanel,
    toggleProfileDropdown,
    closeProfileDropdown,
    setSidebarFilter,
    syncSidebarFilterFromRoute,
    refreshSidebarContent,
  };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
