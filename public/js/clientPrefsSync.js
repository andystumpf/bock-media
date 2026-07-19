/**
 * Web ClientPrefsSync — mirrors mobile profile prefs via /api/clients/prefs.
 */
(function (root) {
  'use strict';

  const CLIENT_ID_KEY = 'bock_client_id';
  const PREF = {
    searchAllLibraries: 'bock_pref_search_all_libraries',
    searchSourcePath: 'bock_pref_search_source_path',
    lastDevice: 'bock_pref_last_device',
    pinnedDevices: 'bock_pref_pinned_devices',
    continueAfterQueue: 'bock_pref_continue_after_queue',
    crossfadeSeconds: 'bock_pref_crossfade_seconds',
    nowPlayingVideo: 'bock_pref_now_playing_video',
    libraryViewMode: 'bock_pref_library_view_mode',
    librarySortBy: 'bock_pref_library_sort_by',
    librarySortOrder: 'bock_pref_library_sort_order',
    libraryTab: 'bock_pref_library_tab',
  };
  const SEARCH_RECENTS_KEY = 'searchRecentSelections';
  const ENGAGEMENT_KEY = 'bock_home_tile_engagement';
  const STALE_MS = 4 * 24 * 60 * 60 * 1000;

  let pushTimer = null;
  let pulling = false;

  function clientId() {
    let id = localStorage.getItem(CLIENT_ID_KEY);
    if (!id) {
      id = (root.crypto && crypto.randomUUID)
        ? crypto.randomUUID()
        : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
      localStorage.setItem(CLIENT_ID_KEY, id);
    }
    return id;
  }

  function clientDeviceId() {
    const cid = clientId().trim();
    return cid ? `client-${cid}` : '';
  }

  function memberId() {
    if (typeof root.activeMemberId === 'function') return root.activeMemberId() || '';
    return localStorage.getItem('bock_active_member') || '';
  }

  function loadSearchSelections() {
    try {
      const raw = localStorage.getItem(SEARCH_RECENTS_KEY);
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  }

  function saveSearchSelections(items) {
    try {
      localStorage.setItem(SEARCH_RECENTS_KEY, JSON.stringify((items || []).slice(0, 12)));
    } catch { /* quota */ }
  }

  function getSearchAllLibraries() {
    return localStorage.getItem(PREF.searchAllLibraries) !== '0';
  }

  function setSearchAllLibraries(all, { push = true } = {}) {
    localStorage.setItem(PREF.searchAllLibraries, all ? '1' : '0');
    if (push) schedulePush();
  }

  function getSearchSourcePath() {
    return localStorage.getItem(PREF.searchSourcePath) || '';
  }

  function setSearchSourcePath(path, { push = true } = {}) {
    const v = (path || '').trim();
    if (v) localStorage.setItem(PREF.searchSourcePath, v);
    else localStorage.removeItem(PREF.searchSourcePath);
    if (push) schedulePush();
  }

  function getLastDevice() {
    return localStorage.getItem(PREF.lastDevice) || '';
  }

  function setLastDevice(serial, { push = true } = {}) {
    const v = (serial || '').trim();
    if (v) localStorage.setItem(PREF.lastDevice, v);
    else localStorage.removeItem(PREF.lastDevice);
    if (push) schedulePush();
  }

  function getPinnedDevices() {
    try {
      const raw = localStorage.getItem(PREF.pinnedDevices);
      const arr = raw ? JSON.parse(raw) : [];
      return Array.isArray(arr) ? arr.filter(Boolean) : [];
    } catch {
      return [];
    }
  }

  function setPinnedDevices(list, { push = true } = {}) {
    const cleaned = [...new Set((list || []).map((s) => String(s).trim()).filter(Boolean))];
    if (cleaned.length) localStorage.setItem(PREF.pinnedDevices, JSON.stringify(cleaned));
    else localStorage.removeItem(PREF.pinnedDevices);
    if (push) schedulePush();
  }

  function isPinned(serial) {
    return getPinnedDevices().includes(serial);
  }

  function togglePinned(serial) {
    const s = (serial || '').trim();
    if (!s) return getPinnedDevices();
    const set = new Set(getPinnedDevices());
    if (set.has(s)) set.delete(s);
    else set.add(s);
    const next = [...set];
    setPinnedDevices(next);
    return next;
  }

  function getContinueAfterQueue() {
    return localStorage.getItem(PREF.continueAfterQueue) || 'off';
  }

  function setContinueAfterQueue(value, { push = true } = {}) {
    const v = (value || 'off').trim() || 'off';
    localStorage.setItem(PREF.continueAfterQueue, v);
    if (push) schedulePush();
  }

  function getCrossfadeSeconds() {
    const raw = parseInt(localStorage.getItem(PREF.crossfadeSeconds) || '0', 10);
    if (!Number.isFinite(raw)) return 0;
    return Math.min(20, Math.max(0, raw));
  }

  function setCrossfadeSeconds(seconds, { push = true } = {}) {
    const v = Math.min(20, Math.max(0, parseInt(seconds, 10) || 0));
    localStorage.setItem(PREF.crossfadeSeconds, String(v));
    if (push) schedulePush();
  }

  function getNowPlayingVideo() {
    return localStorage.getItem(PREF.nowPlayingVideo) === '1';
  }

  function setNowPlayingVideo(on, { push = true } = {}) {
    localStorage.setItem(PREF.nowPlayingVideo, on ? '1' : '0');
    if (push) schedulePush();
  }

  function getLibraryViewMode() {
    const v = localStorage.getItem(PREF.libraryViewMode);
    return v === 'list' ? 'list' : 'grid';
  }

  function setLibraryViewMode(mode, { push = true } = {}) {
    localStorage.setItem(PREF.libraryViewMode, mode === 'list' ? 'list' : 'grid');
    if (push) schedulePush();
  }

  function getLibrarySortBy() {
    const v = localStorage.getItem(PREF.librarySortBy);
    if (v === 'trackCount' || v === 'recents') return v;
    return 'name';
  }

  function setLibrarySortBy(by, { push = true } = {}) {
    const v = by === 'trackCount' || by === 'recents' ? by : 'name';
    localStorage.setItem(PREF.librarySortBy, v);
    if (push) schedulePush();
  }

  function getLibrarySortOrder() {
    return localStorage.getItem(PREF.librarySortOrder) === 'desc' ? 'desc' : 'asc';
  }

  function setLibrarySortOrder(order, { push = true } = {}) {
    localStorage.setItem(PREF.librarySortOrder, order === 'desc' ? 'desc' : 'asc');
    if (push) schedulePush();
  }

  function getLibraryTab() {
    return localStorage.getItem(PREF.libraryTab) || 'library';
  }

  function setLibraryTab(tab, { push = true } = {}) {
    const allowed = ['library', 'playlists', 'artists', 'albums', 'songs', 'genres', 'watchfolders', 'all', 'downloaded'];
    const v = (tab || '').trim().toLowerCase();
    if (!allowed.includes(v)) return;
    const route = v === 'all' ? 'library' : v === 'downloaded' ? 'library' : v;
    localStorage.setItem(PREF.libraryTab, route);
    if (push) schedulePush();
  }

  function applyLibraryListSortFromPrefs() {
    const by = getLibrarySortBy();
    const order = getLibrarySortOrder();
    if (by === 'trackCount' || by === 'name') {
      root._plListSort = { by, order };
    }
  }

  function persistLibraryListSort() {
    if (!root._plListSort) return;
    setLibrarySortBy(root._plListSort.by, { push: true });
    setLibrarySortOrder(root._plListSort.order, { push: false });
    schedulePush();
  }

  function loadEngagement() {
    try {
      const raw = localStorage.getItem(ENGAGEMENT_KEY);
      return raw ? JSON.parse(raw) : {};
    } catch {
      return {};
    }
  }

  function saveEngagement(map, { push = true } = {}) {
    try {
      if (!map || !Object.keys(map).length) localStorage.removeItem(ENGAGEMENT_KEY);
      else localStorage.setItem(ENGAGEMENT_KEY, JSON.stringify(map));
      if (push) schedulePush();
    } catch { /* quota */ }
  }

  function exportEngagementJson() {
    const map = loadEngagement();
    return Object.keys(map).length ? JSON.stringify(map) : null;
  }

  function importEngagementJson(raw, { push = false } = {}) {
    if (!raw || !String(raw).trim()) return;
    try {
      const incoming = JSON.parse(raw);
      if (!incoming || typeof incoming !== 'object') return;
      const cur = loadEngagement();
      for (const [id, entry] of Object.entries(incoming)) {
        if (!entry || typeof entry !== 'object') continue;
        const prev = cur[id] || {};
        const firstSeenMs = Math.min(
          Number(prev.firstSeenMs) || Infinity,
          Number(entry.firstSeenMs) || Date.now(),
        );
        const lastA = Number(prev.lastSelectedMs) || 0;
        const lastB = Number(entry.lastSelectedMs) || 0;
        cur[id] = {
          firstSeenMs: Number.isFinite(firstSeenMs) ? firstSeenMs : Date.now(),
          lastSelectedMs: Math.max(lastA, lastB) || undefined,
        };
        if (!cur[id].lastSelectedMs) delete cur[id].lastSelectedMs;
      }
      saveEngagement(cur, { push });
    } catch { /* bad json */ }
  }

  function noteCardsPresent(cardIds, { push = true } = {}) {
    const ids = (cardIds || []).filter(Boolean);
    if (!ids.length) return;
    const now = Date.now();
    const map = loadEngagement();
    let changed = false;
    for (const id of ids) {
      if (!map[id]) {
        map[id] = { firstSeenMs: now };
        changed = true;
      }
    }
    if (changed) saveEngagement(map, { push });
  }

  function recordTileSelection(cardId, { push = true } = {}) {
    const id = (cardId || '').trim();
    if (!id) return;
    const now = Date.now();
    const map = loadEngagement();
    const prev = map[id];
    map[id] = prev
      ? { ...prev, lastSelectedMs: now }
      : { firstSeenMs: now, lastSelectedMs: now };
    saveEngagement(map, { push });
  }

  function collectMemberPrefs() {
    const mid = memberId();
    const prefs = {
      searchAllLibraries: getSearchAllLibraries(),
      searchSelections: loadSearchSelections(),
      rememberMe: !!localStorage.getItem('bockmedia_auth'),
    };
    const src = getSearchSourcePath();
    if (src) prefs.searchSourcePath = src;
    if (mid) {
      prefs.activeMemberId = mid;
      const last = getLastDevice();
      if (last) prefs.lastDevice = last;
      const pinned = getPinnedDevices();
      if (pinned.length) prefs.pinnedDevices = pinned;
      const cont = getContinueAfterQueue();
      prefs.continueAfterQueue = cont || 'off';
      prefs.crossfadeSeconds = getCrossfadeSeconds();
      prefs.nowPlayingVideo = getNowPlayingVideo();
      const engagement = exportEngagementJson();
      if (engagement) prefs.homeTileEngagement = engagement;
      const tab = getLibraryTab();
      if (tab) prefs.libraryTab = tab;
      prefs.libraryViewMode = getLibraryViewMode();
      prefs.librarySortBy = getLibrarySortBy();
      prefs.librarySortOrder = getLibrarySortOrder();
    }
    return prefs;
  }

  function applyMerged(merged) {
    if (!merged || typeof merged !== 'object') return false;
    let changed = false;
    if ('searchAllLibraries' in merged) {
      setSearchAllLibraries(merged.searchAllLibraries !== false, { push: false });
      changed = true;
    }
    if ('searchSourcePath' in merged) {
      const p = merged.searchSourcePath;
      setSearchSourcePath(typeof p === 'string' ? p : '', { push: false });
      changed = true;
    }
    if (Array.isArray(merged.searchSelections)) {
      saveSearchSelections(merged.searchSelections);
      changed = true;
    }
    if (typeof merged.lastDevice === 'string' && merged.lastDevice.trim()) {
      setLastDevice(merged.lastDevice.trim(), { push: false });
      changed = true;
    }
    if (Array.isArray(merged.pinnedDevices)) {
      setPinnedDevices(merged.pinnedDevices, { push: false });
      changed = true;
    }
    if (typeof merged.activeMemberId === 'string' && merged.activeMemberId.trim() && !memberId()) {
      if (typeof root.setActiveMember === 'function') root.setActiveMember(merged.activeMemberId.trim());
      changed = true;
    }
    if (typeof merged.continueAfterQueue === 'string') {
      setContinueAfterQueue(merged.continueAfterQueue.trim() || 'off', { push: false });
      changed = true;
    }
    if ('crossfadeSeconds' in merged) {
      const raw = merged.crossfadeSeconds;
      const n = typeof raw === 'number' ? raw : parseInt(String(raw || '0'), 10);
      setCrossfadeSeconds(Number.isFinite(n) ? n : 0, { push: false });
      changed = true;
    }
    if ('nowPlayingVideo' in merged) {
      setNowPlayingVideo(merged.nowPlayingVideo === true, { push: false });
      changed = true;
    }
    if (typeof merged.homeTileEngagement === 'string' && merged.homeTileEngagement.trim()) {
      importEngagementJson(merged.homeTileEngagement);
      changed = true;
    }
    if (typeof merged.libraryViewMode === 'string' && merged.libraryViewMode.trim()) {
      setLibraryViewMode(merged.libraryViewMode.trim(), { push: false });
      changed = true;
    }
    if (typeof merged.librarySortBy === 'string' && merged.librarySortBy.trim()) {
      setLibrarySortBy(merged.librarySortBy.trim(), { push: false });
      changed = true;
    }
    if (typeof merged.librarySortOrder === 'string' && merged.librarySortOrder.trim()) {
      setLibrarySortOrder(merged.librarySortOrder.trim(), { push: false });
      changed = true;
    }
    if (typeof merged.libraryTab === 'string' && merged.libraryTab.trim()) {
      setLibraryTab(merged.libraryTab.trim(), { push: false });
      changed = true;
    }
    if ('librarySortBy' in merged || 'librarySortOrder' in merged) {
      applyLibraryListSortFromPrefs();
    }
    return changed;
  }

  function memberExists(id, hh) {
    if (!id) return false;
    return (hh?.members || []).some((m) => m.id === id);
  }

  function clearActiveMember() {
    if (typeof root.setActiveMember === 'function') root.setActiveMember('');
    else localStorage.removeItem('bock_active_member');
  }

  function reconcileActiveMember(hh) {
    const mid = memberId();
    if (mid && !memberExists(mid, hh)) {
      clearActiveMember();
      return true;
    }
    return false;
  }

  async function bindClient(mid) {
    if (!mid || typeof root.authFetch !== 'function') return;
    const res = await root.authFetch('/api/clients/bind', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        clientId: clientId(),
        memberId: mid,
        platform: 'web',
      }),
    });
    if (res.status !== 400) return;
    const data = await res.json().catch(() => ({}));
    if ((data.error || '') === 'unknown memberId') clearActiveMember();
  }

  async function restoreActiveMember(hh) {
    if (memberId() || typeof root.API !== 'function') return false;
    hh = hh || await root.API('/api/household');
    if (!hh) return false;
    const did = clientDeviceId();
    const bindings = hh.clientBindings || [];
    const fromBinding = bindings.find((b) => b.clientDeviceId === did)?.memberId;
    if (fromBinding && memberExists(fromBinding, hh) && typeof root.setActiveMember === 'function') {
      root.setActiveMember(fromBinding);
      return true;
    }
    const members = hh.members || [];
    if (members.length === 1 && members[0].id && typeof root.setActiveMember === 'function') {
      root.setActiveMember(members[0].id);
      return true;
    }
    return false;
  }

  async function pullAndApply() {
    if (pulling || typeof root.API !== 'function') return;
    pulling = true;
    try {
      const hh = await root.API('/api/household');
      root._household = hh;
      if (typeof root.renderProfileDropdown === 'function') root.renderProfileDropdown();
      reconcileActiveMember(hh);
      const restored = await restoreActiveMember(hh);
      const mid = memberId();
      const q = new URLSearchParams({ clientId: clientId() });
      if (mid) q.set('memberId', mid);
      const data = await root.API(`/api/clients/prefs?${q}`);
      applyMerged(data?.merged || {});
      if (mid) await bindClient(mid);
      if (restored && typeof root.WebCache !== 'undefined' && root.WebCache.invalidateHome) {
        root.WebCache.invalidateHome();
      }
    } finally {
      pulling = false;
    }
  }

  async function push() {
    if (typeof root.authFetch !== 'function') return;
    const mid = memberId();
    const body = {
      clientId: clientId(),
      memberPrefs: collectMemberPrefs(),
    };
    if (mid) body.memberId = mid;
    await root.authFetch('/api/clients/prefs', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
  }

  function schedulePush() {
    clearTimeout(pushTimer);
    pushTimer = setTimeout(() => { push().catch(() => {}); }, 900);
  }

  async function onActiveMemberChanged(id) {
    await bindClient(id || memberId()).catch(() => {});
    await push().catch(() => {});
    await pullAndApply().catch(() => {});
  }

  /** Append to search API URLs — `&source=…` when scoped to a watch folder. */
  function searchScopeSuffix() {
    if (getSearchAllLibraries()) return '';
    const src = getSearchSourcePath().trim();
    if (!src) return '';
    return `&source=${encodeURIComponent(src)}`;
  }

  function searchScopeBarHtml() {
    return '';
  }

  root.ClientPrefsSync = {
    clientId,
    clientDeviceId,
    pullAndApply,
    push,
    schedulePush,
    onActiveMemberChanged,
    getSearchAllLibraries,
    setSearchAllLibraries,
    getSearchSourcePath,
    setSearchSourcePath,
    getLastDevice,
    setLastDevice,
    getPinnedDevices,
    setPinnedDevices,
    isPinned,
    togglePinned,
    getContinueAfterQueue,
    setContinueAfterQueue,
    getCrossfadeSeconds,
    setCrossfadeSeconds,
    getNowPlayingVideo,
    setNowPlayingVideo,
    noteCardsPresent,
    recordTileSelection,
    getLibraryViewMode,
    setLibraryViewMode,
    getLibrarySortBy,
    setLibrarySortBy,
    getLibrarySortOrder,
    setLibrarySortOrder,
    getLibraryTab,
    setLibraryTab,
    applyLibraryListSortFromPrefs,
    persistLibraryListSort,
    searchScopeSuffix,
    searchScopeBarHtml,
    applyMerged,
  };

  function bootSync() {
    if (typeof root.API !== 'function') return;
    root.ClientPrefsSync.pullAndApply().catch(() => {});
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootSync);
  } else {
    bootSync();
  }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
