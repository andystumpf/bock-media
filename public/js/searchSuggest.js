/**
 * Topbar search autocomplete with keyboard navigation.
 */
(function (root) {
  'use strict';

  const RECENTS_KEY = 'bock_search_recents';
  let items = [];
  let activeIdx = -1;
  let timer = null;

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function loadRecents() {
    try { return JSON.parse(localStorage.getItem(RECENTS_KEY) || '[]'); } catch { return []; }
  }

  function saveRecent(q) {
    const v = (q || '').trim();
    if (!v) return;
    const list = loadRecents().filter((x) => x.toLowerCase() !== v.toLowerCase());
    list.unshift(v);
    localStorage.setItem(RECENTS_KEY, JSON.stringify(list.slice(0, 8)));
  }

  function hide() {
    document.getElementById('search-suggest-dropdown')?.classList.add('hidden');
    activeIdx = -1;
  }

  function show() {
    document.getElementById('search-suggest-dropdown')?.classList.remove('hidden');
  }

  function renderDropdown(data, q) {
    const el = document.getElementById('search-suggest-dropdown');
    if (!el) return;
    items = [];
    let html = `<div class="search-suggest-hints"><span><i class="fa fa-arrow-up"></i><i class="fa fa-arrow-down"></i> Navigate</span><span><kbd>Enter</kbd> Search</span></div>`;
    const recents = loadRecents().filter((r) => !q || r.toLowerCase().includes(q.toLowerCase())).slice(0, 4);
    recents.forEach((r) => {
      items.push({ type: 'recent', label: r, href: `#search` });
      html += `<button type="button" class="search-suggest-item" data-idx="${items.length - 1}"><i class="fa fa-clock-rotate-left"></i><span>${esc(r)}</span></button>`;
    });
    (data.suggestions || data.queries || []).slice(0, 4).forEach((s) => {
      const label = typeof s === 'string' ? s : s.label || s.query || '';
      items.push({ type: 'query', label, href: '#search' });
      html += `<button type="button" class="search-suggest-item" data-idx="${items.length - 1}"><i class="fa fa-magnifying-glass"></i><span>${esc(label)}</span></button>`;
    });
    const songs = data.songs || [];
    const albums = data.albums || [];
    [...albums.slice(0, 3), ...songs.slice(0, 4)].forEach((row) => {
      const isAlbum = !!row.album && !row.title;
      const title = row.title || row.album || '';
      const artist = row.artist || '';
      const href = isAlbum
        ? `#album/${encodeURIComponent(artist)}/${encodeURIComponent(title)}`
        : row.path ? `#search` : `#artist/${encodeURIComponent(artist)}`;
      items.push({ type: isAlbum ? 'album' : 'song', label: title, sub: artist, href, row });
      const art = row.path && typeof root.artworkUrl === 'function' ? root.artworkUrl(row.path, 48) : '';
      html += `<button type="button" class="search-suggest-item search-suggest-entity" data-idx="${items.length - 1}">
        ${art ? `<img src="${esc(art)}" alt="" class="search-suggest-art">` : '<i class="fa fa-music"></i>'}
        <span><strong>${esc(title)}</strong><small>${esc(artist)}</small></span>
        <i class="fa fa-plus search-suggest-add"></i>
      </button>`;
    });
    el.innerHTML = html;
    el.querySelectorAll('.search-suggest-item').forEach((btn) => {
      btn.addEventListener('mousedown', (e) => {
        e.preventDefault();
        selectItem(parseInt(btn.dataset.idx, 10));
      });
    });
    show();
    highlight(0);
  }

  function highlight(idx) {
    activeIdx = idx;
    document.querySelectorAll('.search-suggest-item').forEach((el, i) => {
      el.classList.toggle('active', i === idx);
    });
  }

  function selectItem(idx) {
    const item = items[idx];
    if (!item) return;
    hide();
    const inp = document.getElementById('topbar-search-q');
    if (item.type === 'recent' || item.type === 'query') {
      if (inp) inp.value = item.label;
      root._lastSearchQ = item.label;
      saveRecent(item.label);
      root.location.hash = 'search';
      if (typeof root.libSearchDebounced === 'function') root.libSearchDebounced(item.label);
      return;
    }
    if (item.href && item.href !== '#search') {
      root.location.hash = item.href.replace('#', '');
      return;
    }
    if (inp) inp.value = item.label;
    root._lastSearchQ = item.label;
    saveRecent(item.label);
    root.location.hash = 'search';
    if (typeof root.libSearchDebounced === 'function') root.libSearchDebounced(item.label);
  }

  async function fetchSuggest(q) {
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const scope = typeof root.searchScopeQuery === 'function' ? root.searchScopeQuery() : '';
    if (q.length >= 3) {
      return fetchFn(
        `/api/search?q=${encodeURIComponent(q)}&limit=6&fast=1${scope}`,
        { credentials: 'same-origin' },
      ).then((r) => r.json()).catch(() => ({}));
    }
    return fetchFn(
      `/api/search/suggest?q=${encodeURIComponent(q)}${scope}`,
      { credentials: 'same-origin' },
    ).then((r) => r.json()).catch(() => ({}));
  }

  function onInput(q) {
    clearTimeout(timer);
    if (!q.trim()) {
      renderDropdown({ suggestions: loadRecents().map((r) => ({ query: r })) }, '');
      return;
    }
    timer = setTimeout(async () => {
      const data = await fetchSuggest(q.trim());
      renderDropdown(data, q.trim());
    }, 180);
  }

  function init() {
    const inp = document.getElementById('topbar-search-q');
    if (!inp) return;
    inp.addEventListener('focus', () => onInput(inp.value));
    inp.addEventListener('input', () => onInput(inp.value));
    inp.addEventListener('keydown', (e) => {
      const count = document.querySelectorAll('.search-suggest-item').length;
      if (e.key === 'ArrowDown') { e.preventDefault(); highlight(Math.min(count - 1, activeIdx + 1)); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); highlight(Math.max(0, activeIdx - 1)); }
      else if (e.key === 'Enter' && activeIdx >= 0 && !document.getElementById('search-suggest-dropdown')?.classList.contains('hidden')) {
        e.preventDefault();
        selectItem(activeIdx);
      } else if (e.key === 'Escape') hide();
    });
    inp.addEventListener('blur', () => setTimeout(hide, 150));
    document.getElementById('topbar-search-clear')?.addEventListener('click', () => {
      inp.value = '';
      root._lastSearchQ = '';
      hide();
      inp.focus();
    });
  }

  root.SearchSuggest = { init, saveRecent, hide };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
