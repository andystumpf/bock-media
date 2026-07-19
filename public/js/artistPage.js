/**
 * Spotify-style artist page.
 */
(function (root) {
  'use strict';

  function esc(s) {
    return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function fmtNum(n) {
    if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M`;
    if (n >= 1000) return `${Math.round(n / 1000)}K`;
    return String(n || 0);
  }

  function fmtDur(secs) {
    if (!secs) return '—';
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  function artUrl(path, size) {
    return typeof root.artworkUrl === 'function' ? root.artworkUrl(path, size) : null;
  }

  function trackRow(tr, i, expanded) {
    const play = typeof root.playSongAt === 'function'
      ? `playSongAt(${i})`
      : `startPlayback({kind:'song',path:${JSON.stringify(tr.path)},title:${JSON.stringify(tr.title)},artist:${JSON.stringify(tr.artist)}})`;
    const liked = tr.liked ? ' liked' : '';
    const count = tr.playCount ? fmtNum(tr.playCount) : '';
    return `<div class="artist-track-row" oncontextmenu="ContextMenu.showTrack(event,${JSON.stringify(tr).replace(/"/g, '&quot;')})">
      <span class="artist-track-num">${i + 1}</span>
      <button type="button" class="artist-track-play" onclick="${play}"><i class="fa fa-play"></i></button>
      <div class="artist-track-meta">
        <span class="artist-track-title">${esc(tr.title)}</span>
        ${tr.album ? `<span class="artist-track-album">${esc(tr.album)}</span>` : ''}
      </div>
      <span class="artist-track-plays">${count ? `${count} plays` : ''}</span>
      <button type="button" class="artist-track-like${liked}" onclick="toggleTrackLike('${esc(tr.path)}','${esc(tr.title)}','${esc(tr.artist)}')"><i class="fa${tr.liked ? ' fa-check' : '-regular fa-heart'}"></i></button>
      <span class="artist-track-dur">${fmtDur(tr.duration_seconds)}</span>
      <button type="button" class="artist-track-menu" onclick="ContextMenu.showTrack(event,${JSON.stringify(tr).replace(/"/g, '&quot;')})"><i class="fa fa-ellipsis"></i></button>
    </div>`;
  }

  function clientQuery() {
    const q = new URLSearchParams();
    if (typeof root.ClientPrefsSync !== 'undefined') {
      const cid = root.ClientPrefsSync.clientId?.();
      if (cid) q.set('clientId', cid);
    }
    const s = q.toString();
    return s ? `?${s}` : '';
  }

  async function render(artistName) {
    const main = document.getElementById('main-content');
    if (!main) return;
    const name = String(artistName || '').trim();
    if (!name) throw new Error('Artist not found');
    main.innerHTML = '<div class="spinner-wrap"><div class="spinner"></div></div>';
    const fetchFn = typeof root.authFetch === 'function' ? root.authFetch : fetch;
    const res = await fetchFn(`/api/artists/${encodeURIComponent(name)}${clientQuery()}`, { credentials: 'same-origin' });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || `Request failed (${res.status})`);
    const portrait = `/api/artist-portrait?artist=${encodeURIComponent(name)}`;
    const tracks = data.topTracks || [];
    root._songs = tracks;
    let showAll = false;
    const renderTracks = (expanded) => {
      const slice = expanded ? tracks : tracks.slice(0, 5);
      return slice.map((t, i) => trackRow(t, i, expanded)).join('')
        + (tracks.length > 5 && !expanded ? `<button type="button" class="artist-see-more" id="artist-see-more">See more</button>` : '');
    };
    const albums = (data.albums || []).map((a) => {
      const url = artUrl(a.path, 256);
      return `<a href="#album/${encodeURIComponent(a.artist || name)}/${encodeURIComponent(a.album)}" class="spotify-card">
        <div class="spotify-card-media">${url ? `<img src="${esc(url)}" alt="">` : ''}
          <button type="button" class="spotify-play-fab" onclick="event.preventDefault();startPlayback({kind:'album',name:${JSON.stringify(a.album)},artist:${JSON.stringify(a.artist || name)}})"><i class="fa fa-play"></i></button>
        </div>
        <div class="spotify-card-title">${esc(a.album)}</div>
        <div class="spotify-card-sub">${esc(a.year || '')}</div>
      </a>`;
    }).join('');
    const fans = (data.similarArtists || []).map((a) =>
      `<a href="#artist/${encodeURIComponent(a.artist)}" class="spotify-artist-card spotify-card">
        <div class="spotify-card-media spotify-card-art-round">${artUrl(a.path, 256) ? `<img src="${esc(artUrl(a.path, 256))}" alt="">` : '<i class="fa fa-microphone"></i>'}</div>
        <div class="spotify-card-title">${esc(a.artist)}</div>
      </a>`).join('');

    main.innerHTML = `
      <div class="entity-hero artist-hero">
        <img class="artist-portrait" src="${esc(portrait)}" alt="">
        <div class="entity-hero-meta">
          <span class="entity-eyebrow">Artist</span>
          <h1 class="entity-title">${esc(name)}</h1>
          <p class="entity-sub"><span class="verified-badge"><i class="fa fa-check-circle"></i> Verified library</span> · ${fmtNum(data.totalPlays || 0)} plays · ${data.trackCount || 0} songs</p>
        </div>
      </div>
      <div class="entity-actions">
        <button type="button" class="entity-play-btn" onclick="startPlayback({kind:'artist',name:${JSON.stringify(name)}})"><i class="fa fa-play"></i></button>
        <button type="button" class="entity-action-btn" onclick="startPlayback({kind:'artist',name:${JSON.stringify(name)},shuffle:true})"><i class="fa fa-shuffle"></i></button>
        <button type="button" class="entity-action-btn entity-follow${data.followed ? ' active' : ''}" id="artist-follow-btn">${data.followed ? 'Following' : 'Follow'}</button>
        <button type="button" class="entity-action-btn" onclick="ContextMenu.showArtist(event,${JSON.stringify({ artist: name })})"><i class="fa fa-ellipsis"></i></button>
      </div>
      <section class="entity-section"><h2>Popular</h2><div class="artist-track-list" id="artist-tracks">${renderTracks(false)}</div></section>
      ${albums ? `<section class="entity-section"><div class="spotify-section-header"><h2>Discography</h2></div><div class="spotify-browse-grid">${albums}</div></section>` : ''}
      ${fans ? `<section class="entity-section"><h2>Fans also like</h2><div class="spotify-browse-grid artists">${fans}</div></section>` : ''}`;

    document.getElementById('artist-see-more')?.addEventListener('click', () => {
      document.getElementById('artist-tracks').innerHTML = renderTracks(true);
    });
    document.getElementById('artist-follow-btn')?.addEventListener('click', async () => {
      const btn = document.getElementById('artist-follow-btn');
      const stars = btn?.classList.contains('active') ? 0 : 3;
      await fetchFn('/api/ratings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ kind: 'artist', id: name, stars }),
      });
      btn?.classList.toggle('active', stars > 0);
      if (btn) btn.textContent = stars > 0 ? 'Following' : 'Follow';
    });
    if (typeof root.setPageTitle === 'function') root.setPageTitle(name);
  }

  root.ArtistPage = { render };
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : {});
