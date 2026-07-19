/**
 * Global Spotify-style context menus.
 */
(function (root) {
  'use strict';

  let menuEl = null;

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function ensureMenu() {
    if (menuEl) return menuEl;
    menuEl = document.createElement('div');
    menuEl.id = 'context-menu';
    menuEl.className = 'context-menu hidden';
    document.body.appendChild(menuEl);
    root.addEventListener('click', () => hide());
    root.addEventListener('scroll', () => hide(), true);
    return menuEl;
  }

  function hide() {
    ensureMenu().classList.add('hidden');
  }

  function show(x, y, items) {
    const el = ensureMenu();
    el.innerHTML = items.map((it) => {
      if (it.divider) return '<div class="context-menu-divider"></div>';
      return `<button type="button" class="context-menu-item${it.danger ? ' danger' : ''}" data-action="${esc(it.action)}">${it.icon ? `<i class="fa fa-${it.icon}"></i>` : ''}${esc(it.label)}</button>`;
    }).join('');
    el.classList.remove('hidden');
    el.style.left = `${Math.min(x, window.innerWidth - 240)}px`;
    el.style.top = `${Math.min(y, window.innerHeight - el.offsetHeight - 8)}px`;
    el.querySelectorAll('.context-menu-item').forEach((btn, i) => {
      const item = items.filter((x) => !x.divider)[i];
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        hide();
        item?.onClick?.();
      });
    });
  }

  function showTrack(e, track) {
    e.preventDefault();
    e.stopPropagation();
    show(e.clientX, e.clientY, [
      { label: 'Add to queue', icon: 'list-ul', onClick: () => root.WebPlayback?.play?.({ kind: 'song', path: track.path, title: track.title, artist: track.artist }, { append: true }) },
      { label: 'Go to artist', icon: 'user', onClick: () => { root.location.hash = `artist/${encodeURIComponent(track.artist || '')}`; } },
      { label: 'Go to album', icon: 'compact-disc', onClick: () => { root.location.hash = `album/${encodeURIComponent(track.artist || '')}/${encodeURIComponent(track.album || '')}`; } },
      { label: track.liked ? 'Remove from Liked Songs' : 'Save to Liked Songs', icon: 'heart', onClick: () => root.toggleTrackLike?.(track.path, track.title, track.artist) },
      { label: 'Start radio', icon: 'radio', onClick: async () => {
        const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
        const data = await fetchFn('/api/resonance/radio', { method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin', body: JSON.stringify({ seedKind: 'song', path: track.path }) }).then((r) => r.json());
        if (data.tracks?.length) root.startPlayback?.({ kind: 'queue', tracks: data.tracks, name: 'Radio' });
      }},
      { label: 'Share', icon: 'link', onClick: () => {
        const url = `${root.location.origin}${root.location.pathname}#album/${encodeURIComponent(track.artist || '')}/${encodeURIComponent(track.album || '')}`;
        navigator.clipboard?.writeText(url);
        root.toast?.('Link copied');
      }},
    ]);
  }

  function showArtist(e, data) {
    e.preventDefault();
    e.stopPropagation();
    show(e.clientX, e.clientY, [
      { label: 'Start radio', icon: 'radio', onClick: () => root.startPlayback?.({ kind: 'artist', name: data.artist, shuffle: true }) },
      { label: 'Share', icon: 'link', onClick: () => navigator.clipboard?.writeText(`${root.location.href.split('#')[0]}#artist/${encodeURIComponent(data.artist)}`) },
    ]);
  }

  function showAlbum(e, data) {
    e.preventDefault();
    e.stopPropagation();
    show(e.clientX, e.clientY, [
      { label: 'Play album', icon: 'play', onClick: () => root.startPlayback?.({ kind: 'album', name: data.album, artist: data.artist }) },
      { label: 'Go to artist', icon: 'user', onClick: () => { root.location.hash = `artist/${encodeURIComponent(data.artist || '')}`; } },
      { label: 'Share', icon: 'link', onClick: () => navigator.clipboard?.writeText(`${root.location.href.split('#')[0]}#album/${encodeURIComponent(data.artist)}/${encodeURIComponent(data.album)}`) },
    ]);
  }

  function initDragDrop() {
    document.addEventListener('dragstart', (e) => {
      const row = e.target.closest('[data-drag-path]');
      if (!row) return;
      e.dataTransfer.setData('text/bock-path', row.getAttribute('data-drag-path') || '');
      e.dataTransfer.setData('text/bock-title', row.getAttribute('data-drag-title') || '');
      e.dataTransfer.setData('text/bock-artist', row.getAttribute('data-drag-artist') || '');
    });
    document.querySelectorAll('.sidebar-pl-item[href*="playlists/detail"]').forEach((el) => {
      el.addEventListener('dragover', (e) => e.preventDefault());
      el.addEventListener('drop', async (e) => {
        e.preventDefault();
        const path = e.dataTransfer.getData('text/bock-path');
        const id = (el.getAttribute('href') || '').split('/').pop();
        if (!path || !id) return;
        const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
        await fetchFn(`/api/playlists/${encodeURIComponent(id)}/tracks/add`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'same-origin',
          body: JSON.stringify({ path }),
        });
        root.toast?.('Added to playlist');
      });
    });
  }

  root.ContextMenu = { showTrack, showArtist, showAlbum, hide, initDragDrop };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
