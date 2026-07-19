/**
 * Spotify-style album page.
 */
(function (root) {
  'use strict';

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function fmtDur(secs) {
    if (!secs) return '0:00';
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  function fmtTotal(secs) {
    if (!secs) return '';
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m} min ${s} sec`;
  }

  function artUrl(path, size) {
    return typeof root.artworkUrl === 'function' ? root.artworkUrl(path, size) : null;
  }

  function extractDominantColor(img, callback) {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 1;
      canvas.height = 1;
      const ctx = canvas.getContext('2d');
      ctx.drawImage(img, 0, 0, 1, 1);
      const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
      callback(`rgb(${r},${g},${b})`);
    } catch {
      callback('#5038a0');
    }
  }

  function songsQuery(artistName, albumName) {
    const q = new URLSearchParams({
      artist: artistName,
      album: albumName,
      limit: '200',
    });
    if (typeof root.ClientPrefsSync !== 'undefined') {
      const cid = root.ClientPrefsSync.clientId?.();
      if (cid) q.set('clientId', cid);
    }
    return q.toString();
  }

  async function render(artistName, albumName) {
    const main = document.getElementById('main-content');
    if (!main) return;
    const artist = String(artistName || '').trim();
    const album = String(albumName || '').trim();
    if (!album) throw new Error('Album not found');
    main.innerHTML = '<div class="spinner-wrap"><div class="spinner"></div></div>';
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const res = await fetchFn(`/api/songs?${songsQuery(artist, album)}`, { credentials: 'same-origin' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    const tracks = data.items || [];
    if (!tracks.length) throw new Error('Album not found');
    root._songs = tracks;
    const year = tracks[0].year || '';
    const artPath = tracks[0].path;
    const art = artUrl(artPath, 512);
    const totalSec = tracks.reduce((a, t) => a + (t.duration_seconds || 0), 0);
    let mvMap = {};
    try {
      const chk = await fetchFn('/api/music-video/check', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ tracks: tracks.map((t) => ({ title: t.title, artist: t.artist })) }),
      }).then((r) => r.json());
      mvMap = chk.available || {};
    } catch { /* */ }

    const rows = tracks.map((t, i) => {
      const mvKey = `${t.title}|${t.artist}`;
      const hasMv = mvMap[mvKey];
      const liked = t.liked ? ' liked' : '';
      return `<div class="album-track-row" oncontextmenu="ContextMenu.showTrack(event,${JSON.stringify(t).replace(/"/g, '&quot;')})">
        <span class="album-track-num">${t.track_number || i + 1}</span>
        <button type="button" class="album-track-play" onclick="playSongAt(${i})"><i class="fa fa-play"></i></button>
        <div class="album-track-meta"><span class="album-track-title">${esc(t.title)}</span>${hasMv ? ' <span class="badge-mv"><i class="fa fa-video"></i></span>' : ''}${t.explicit ? ' <span class="badge-e">E</span>' : ''}</div>
        <button type="button" class="album-track-like${liked}" onclick="toggleTrackLike('${esc(t.path)}','${esc(t.title)}','${esc(t.artist)}')"><i class="fa${t.liked ? ' fa-check' : '-regular fa-heart'}"></i></button>
        <span class="album-track-dur">${fmtDur(t.duration_seconds)}</span>
        <button type="button" class="album-track-menu" onclick="ContextMenu.showTrack(event,${JSON.stringify(t).replace(/"/g, '&quot;')})"><i class="fa fa-ellipsis"></i></button>
      </div>`;
    }).join('');

    main.innerHTML = `
      <div class="entity-hero album-hero" id="album-hero">
        ${art ? `<img class="album-hero-art" src="${esc(art)}" alt="" id="album-hero-img">` : ''}
        <div class="entity-hero-meta">
          <span class="entity-eyebrow">Album</span>
          <h1 class="entity-title">${esc(albumName)}</h1>
          <p class="entity-sub">
            <a href="#artist/${encodeURIComponent(artistName)}" class="entity-artist-link">${esc(artistName)}</a>
            · ${esc(String(year))} · ${tracks.length} songs, ${fmtTotal(totalSec)}
          </p>
        </div>
      </div>
      <div class="entity-actions">
        <button type="button" class="entity-play-btn" onclick="startPlayback({kind:'album',name:${JSON.stringify(albumName)},artist:${JSON.stringify(artistName)}})"><i class="fa fa-play"></i></button>
        <button type="button" class="entity-action-btn" onclick="startPlayback({kind:'album',name:${JSON.stringify(albumName)},artist:${JSON.stringify(artistName)},shuffle:true})"><i class="fa fa-shuffle"></i></button>
        <button type="button" class="entity-action-btn" title="Save to library"><i class="fa fa-plus"></i></button>
        <button type="button" class="entity-action-btn" onclick="ContextMenu.showAlbum(event,${JSON.stringify({ album: albumName, artist: artistName })})"><i class="fa fa-ellipsis"></i></button>
      </div>
      <div class="album-track-list">${rows}</div>
      <section class="entity-section" id="album-more-section"></section>`;

    const heroImg = document.getElementById('album-hero-img');
    if (heroImg) {
      heroImg.onload = () => extractDominantColor(heroImg, (color) => {
        document.getElementById('album-hero')?.style.setProperty('--album-tint', color);
      });
      if (heroImg.complete) heroImg.onload();
    }

    try {
      const more = await fetchFn(`/api/albums?artist=${encodeURIComponent(artistName)}&limit=20`, { credentials: 'same-origin' }).then((r) => r.json());
      const cards = (more.items || []).filter((a) => a.album !== albumName).slice(0, 8).map((a) => {
        const u = artUrl(a.path || a.art_path, 256);
        return `<a href="#album/${encodeURIComponent(a.artist || artistName)}/${encodeURIComponent(a.album)}" class="spotify-card">
          <div class="spotify-card-media">${u ? `<img src="${esc(u)}" alt="">` : ''}
            <button type="button" class="spotify-play-fab" onclick="event.preventDefault();startPlayback({kind:'album',name:${JSON.stringify(a.album)},artist:${JSON.stringify(a.artist || artistName)}})"><i class="fa fa-play"></i></button>
          </div>
          <div class="spotify-card-title">${esc(a.album)}</div>
          <div class="spotify-card-sub">${esc(a.year || '')}</div>
        </a>`;
      }).join('');
      const sec = document.getElementById('album-more-section');
      if (sec && cards) {
        sec.innerHTML = `<div class="spotify-section-header"><h2>More by ${esc(artistName)}</h2><a href="#artists" class="spotify-section-link">See discography</a></div><div class="spotify-browse-grid">${cards}</div>`;
      }
    } catch { /* */ }

    if (typeof root.setPageTitle === 'function') root.setPageTitle(albumName);
  }

  root.AlbumPage = { render };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
