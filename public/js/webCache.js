/**
 * Web session + disk cache — mirrors Android HomeFeedCache / HomeCachePersistence /
 * LibrarySessionCache for fast home paint and fewer duplicate API calls.
 */
(function (root) {
  'use strict';

  const HOME_DISK_KEY = 'bock_home_cache_v1';
  const LIB_DISK_KEY = 'bock_library_cache_v1';
  const SEARCH_DISK_KEY = 'bock_search_cache_v1';
  const HOME_DISK_TTL_MS = 24 * 60 * 60 * 1000;
  const LIB_DISK_TTL_MS = 6 * 60 * 60 * 1000;
  const SEARCH_DISK_TTL_MS = 6 * 60 * 60 * 1000;
  const HOME_SESSION_TTL_MS = 10 * 60 * 1000;
  const LIB_SESSION_TTL_MS = 10 * 60 * 1000;
  const PLAYLIST_TTL_MS = 10 * 60 * 1000;
  const SEARCH_BROWSE_TTL_MS = 10 * 60 * 1000;
  const FEED_LAYOUT_VERSION = 1;
  const MOOD_SECTION_MIN = 8;

  let homeMem = null;
  let lastHomeLoadMs = 0;
  let libMem = null;
  let lastLibLoadMs = 0;
  let playlistsMem = null;
  let searchBrowseMem = null;

  function now() { return Date.now(); }

  function hasCurrentHomeLayout(feed) {
    if (!feed || !feed.sections || !feed.sections.length) return false;
    return feed.sections.filter((s) => s.kind === 'Mood').length >= MOOD_SECTION_MIN;
  }

  function peekHome() {
    if (!homeMem) return null;
    return { feed: homeMem.feed, covers: homeMem.covers || {} };
  }

  function putHome(feed, covers) {
    if (!feed || !feed.sections || !feed.sections.length) return;
    if (!hasCurrentHomeLayout(feed)) return;
    homeMem = {
      feed,
      covers: covers || (homeMem && homeMem.covers) || {},
      at: now(),
    };
  }

  function markHomeLoaded() {
    lastHomeLoadMs = now();
  }

  function shouldSkipHomeReload() {
    if (!homeMem || !hasCurrentHomeLayout(homeMem.feed)) return false;
    return now() - lastHomeLoadMs < HOME_SESSION_TTL_MS;
  }

  function saveHomeToDisk(feed, covers) {
    if (!feed || !feed.sections || !feed.sections.length) return;
    if (!hasCurrentHomeLayout(feed)) return;
    try {
      localStorage.setItem(HOME_DISK_KEY, JSON.stringify({
        savedAt: now(),
        feedVersion: FEED_LAYOUT_VERSION,
        sections: feed.sections,
        covers: covers || {},
      }));
    } catch { /* quota */ }
  }

  function loadHomeFromDisk() {
    try {
      const raw = localStorage.getItem(HOME_DISK_KEY);
      if (!raw) return null;
      const dto = JSON.parse(raw);
      if (!dto || dto.feedVersion < FEED_LAYOUT_VERSION) return null;
      if (now() - (dto.savedAt || 0) > HOME_DISK_TTL_MS) return null;
      const feed = { sections: dto.sections || [] };
      if (!hasCurrentHomeLayout(feed)) return null;
      return { feed, covers: dto.covers || {} };
    } catch {
      return null;
    }
  }

  function hydrateHomeFromDisk() {
    if (homeMem) return peekHome();
    const snap = loadHomeFromDisk();
    if (snap) putHome(snap.feed, snap.covers);
    return snap;
  }

  function peekLibrary() {
    if (!libMem) return null;
    if (now() - libMem.at > LIB_SESSION_TTL_MS) return null;
    return libMem.data;
  }

  function putLibrary(data) {
    if (!data || !data.playlists) return;
    libMem = { data, at: now() };
    try {
      localStorage.setItem(LIB_DISK_KEY, JSON.stringify({
        savedAt: now(),
        playlists: data.playlists,
        smart: data.smart || [],
        folders: data.folders || [],
        genres: data.genres || [],
      }));
    } catch { /* quota */ }
  }

  function markLibraryLoaded() {
    lastLibLoadMs = now();
  }

  function shouldSkipLibraryReload() {
    if (!libMem || !libMem.data) return false;
    return now() - lastLibLoadMs < LIB_SESSION_TTL_MS;
  }

  function hydrateLibraryFromDisk() {
    if (libMem) return peekLibrary();
    try {
      const raw = localStorage.getItem(LIB_DISK_KEY);
      if (!raw) return null;
      const dto = JSON.parse(raw);
      if (now() - (dto.savedAt || 0) > LIB_DISK_TTL_MS) return null;
      const data = {
        playlists: dto.playlists || [],
        smart: dto.smart || [],
        folders: dto.folders || [],
        genres: dto.genres || [],
      };
      if (!data.playlists.length) return null;
      libMem = { data, at: now() };
      return data;
    } catch {
      return null;
    }
  }

  function getPlaylistsIfFresh() {
    if (!playlistsMem) return null;
    if (now() - playlistsMem.at > PLAYLIST_TTL_MS) return null;
    return playlistsMem.items;
  }

  function setPlaylists(items) {
    if (!items || !items.length) return;
    playlistsMem = { items, at: now() };
  }

  function peekSearchBrowse() {
    if (!searchBrowseMem) return null;
    if (now() - searchBrowseMem.at > SEARCH_BROWSE_TTL_MS) return null;
    return searchBrowseMem.data;
  }

  function putSearchBrowse(data) {
    if (!data) return;
    searchBrowseMem = { data, at: now() };
    try {
      localStorage.setItem(SEARCH_DISK_KEY, JSON.stringify({
        savedAt: now(),
        quick: data.quick,
        genres: data.genres,
        playlists: data.playlists,
        newAlbums: data.newAlbums,
        playlistCovers: data.playlistCovers || {},
      }));
    } catch { /* quota */ }
  }

  function hydrateSearchFromDisk() {
    if (searchBrowseMem) return peekSearchBrowse();
    try {
      const raw = localStorage.getItem(SEARCH_DISK_KEY);
      if (!raw) return null;
      const dto = JSON.parse(raw);
      if (now() - (dto.savedAt || 0) > SEARCH_DISK_TTL_MS) return null;
      const data = {
        quick: dto.quick,
        genres: dto.genres,
        playlists: dto.playlists || [],
        newAlbums: dto.newAlbums,
        playlistCovers: dto.playlistCovers || {},
      };
      searchBrowseMem = { data, at: now() };
      return data;
    } catch {
      return null;
    }
  }

  function visibleHomeCoverIds(feed, limit = 32) {
    const ids = new Set();
    for (const sec of (feed && feed.sections) || []) {
      for (const card of sec.cards.slice(0, 8)) {
        if (card.playlistId) ids.add(card.playlistId);
        if (ids.size >= limit) return [...ids];
      }
    }
    return [...ids];
  }

  const api = {
    FEED_LAYOUT_VERSION,
    hasCurrentHomeLayout,
    peekHome,
    putHome,
    markHomeLoaded,
    shouldSkipHomeReload,
    saveHomeToDisk,
    loadHomeFromDisk,
    hydrateHomeFromDisk,
    peekLibrary,
    putLibrary,
    markLibraryLoaded,
    shouldSkipLibraryReload,
    hydrateLibraryFromDisk,
    getPlaylistsIfFresh,
    setPlaylists,
    peekSearchBrowse,
    putSearchBrowse,
    hydrateSearchFromDisk,
    visibleHomeCoverIds,
  };

  root.WebCache = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
