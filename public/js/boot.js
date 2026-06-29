/**
 * Instant shell paint before app.js parses — reads home disk cache synchronously.
 */
(function () {
  'use strict';

  const HOME_DISK_KEY = 'bock_home_cache_v1';
  const HOME_DISK_TTL_MS = 24 * 60 * 60 * 1000;
  const MOOD_MIN = 8;

  function bootGradient(seed) {
    const palette = [
      ['#5038a0', '#283248'], ['#8d67ab', '#1db954'], ['#ba5d07', '#e91429'],
      ['#148a08', '#282828'], ['#509bf5', '#121212'], ['#e8115b', '#5038a0'],
    ];
    let h = 0;
    const s = String(seed || '');
    for (let i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
    const pair = palette[Math.abs(h) % palette.length];
    return `linear-gradient(135deg,${pair[0]},${pair[1]})`;
  }

  function artUrl(path) {
    if (!path) return null;
    const rel = String(path).replace(/^\/+/, '');
    const encoded = rel.split('/').map((seg) => encodeURIComponent(seg)).join('/');
    return `/artwork/${encoded}?size=384`;
  }

  function loadHomeSnap() {
    try {
      const raw = localStorage.getItem(HOME_DISK_KEY);
      if (!raw) return null;
      const dto = JSON.parse(raw);
      if (!dto || Date.now() - (dto.savedAt || 0) > HOME_DISK_TTL_MS) return null;
      const sections = dto.sections || [];
      if (sections.filter((s) => s.kind === 'Mood').length < MOOD_MIN) return null;
      return { sections, covers: dto.covers || {} };
    } catch {
      return null;
    }
  }

  function bootQuickCard(card, covers) {
    const grad = bootGradient(card.title);
    const cover = card.playlistId && covers[card.playlistId];
    const url = card.artPath ? artUrl(card.artPath) : (cover ? artUrl(cover) : null);
    const art = url
      ? `<span class="spotify-shortcut-art spotify-shortcut-art-img" style="background:${grad}"><img src="${url}" alt="" loading="eager"></span>`
      : `<span class="spotify-shortcut-art" style="background:${grad}"></span>`;
    return `<div class="home-quick-card boot-card">${art}<span class="home-quick-title">${card.title || ''}</span></div>`;
  }

  function bootSectionCard(card, covers, kind) {
    const grad = bootGradient(card.title);
    const cover = card.playlistId && covers[card.playlistId];
    const url = (cover ? artUrl(cover) : null) || (card.artPath ? artUrl(card.artPath) : null);
    if (kind === 'Radio' || card.playTarget?.kind === 'radio') {
      const disc = url
        ? `<img src="${url}" alt="" loading="eager">`
        : '';
      return `<div class="spotify-card spotify-home-card home-radio-card boot-card">
        <div class="spotify-card-media"><div class="home-radio-stage" style="background:${grad}">
          <span class="home-radio-badge">RADIO</span><div class="home-radio-disc">${disc}</div>
        </div></div>
        <div class="spotify-card-title">${card.title || ''}</div>
        <div class="spotify-card-sub">${card.subtitle || ''}</div>
      </div>`;
    }
    const art = url
      ? `<div class="spotify-card-art spotify-card-art-img" style="background:${grad}"><img src="${url}" alt="" loading="eager"></div>`
      : `<div class="spotify-card-art" style="background:${grad}"></div>`;
    return `<div class="spotify-card spotify-home-card boot-card">
      <div class="spotify-card-media">${art}</div>
      <div class="spotify-card-title">${card.title || ''}</div>
      <div class="spotify-card-sub">${card.subtitle || ''}</div>
    </div>`;
  }

  function collectQuickCards(sections) {
    const mixKinds = new Set(['TopMixes', 'Mood', 'DailyMixes', 'ExploreThemes', 'RecentPlaylists']);
    function isAutomation(name) {
      return /^Automations(\s|$|-)/i.test(String(name || '').trim());
    }
    function eligible(card) {
      const t = card && card.playTarget;
      if (!t || t.kind === 'album' || t.kind === 'song') return false;
      if (t.kind === 'playlist') return !isAutomation(t.name);
      if (t.kind === 'artist' || t.kind === 'radio') return mixKinds.has(card.kind);
      return false;
    }
    const jump = sections.find((s) => s.kind === 'JumpBackIn');
    const out = [];
    const seen = new Set();
    for (const c of (jump && jump.cards) || []) {
      if (!eligible(c) || seen.has(c.id)) continue;
      seen.add(c.id);
      out.push(c);
      if (out.length >= 8) return out;
    }
    for (const sec of sections) {
      if (!mixKinds.has(sec.kind)) continue;
      for (const c of sec.cards || []) {
        if (!eligible(c) || seen.has(c.id)) continue;
        seen.add(c.id);
        out.push(c);
        if (out.length >= 8) return out;
      }
    }
    return out;
  }

  function paintHome(snap) {
    const mc = document.getElementById('main-content');
    if (!mc) return false;
    const quick = collectQuickCards(snap.sections).map((c) => bootQuickCard(c, snap.covers)).join('');
    const sections = snap.sections.slice(0, 10).map((sec) => {
      const cards = (sec.cards || []).slice(0, 8).map((c) => bootSectionCard(c, snap.covers, sec.kind)).join('');
      if (!cards) return '';
      return `<section class="spotify-section spotify-home-section" data-home-groups="all">
        <div class="spotify-section-header"><h2 class="spotify-section-title home-greeting">${sec.title || ''}</h2></div>
        <div class="spotify-carousel">${cards}</div>
      </section>`;
    }).filter(Boolean).join('');
    mc.classList.add('home-active');
    mc.innerHTML = `<div class="home-page boot-home">
      <div class="home-top">
        <div class="home-filters">
          <button type="button" class="home-filter active" data-home-filter="all">All</button>
          <button type="button" class="home-filter" data-home-filter="music">Music</button>
          <button type="button" class="home-filter" data-home-filter="playlists">Playlists</button>
          <button type="button" class="home-filter" data-home-filter="radio">Radio</button>
        </div>
        ${quick ? `<div class="home-quick-grid">${quick}</div>` : ''}
      </div>
      <div class="home-sections">${sections}</div>
    </div>`;
    return true;
  }

  const route = (location.hash || '#dashboard').replace('#', '').split('/')[0] || 'dashboard';
  if (route === 'dashboard') document.body.classList.add('route-home');
  if (route !== 'dashboard') return;
  const snap = loadHomeSnap();
  if (snap && paintHome(snap)) window.__BOOT_HOME_PAINTED__ = true;
})();
