import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bootstrap } from './helpers.mjs';

test('dashboard route includes home greeting and Android-style sections', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async (url) => {
      const u = String(url);
      if (u.includes('/api/playlists')) {
        return { ok: true, json: async () => ({ items: Array.from({ length: 12 }, (_, i) => ({ id: `p${i}`, name: `Playlist ${i}`, trackCount: 10 + i })) }) };
      }
      if (u.includes('/api/nowplaying')) return { ok: true, json: async () => ({ items: [], total: 0 }) };
      if (u.includes('/api/dashboard/quick')) return { ok: true, json: async () => ({ recent: [], favorites: [] }) };
      if (u.includes('/api/favorites')) return { ok: true, json: async () => [] };
      if (u.includes('/api/smart_playlists')) return { ok: true, json: async () => ({ items: [] }) };
      if (u.includes('/api/genres')) return { ok: true, json: async () => ({ items: [] }) };
      if (u.includes('/api/continue')) return { ok: true, json: async () => ({}) };
      if (u.includes('/api/library/new')) return { ok: true, json: async () => ({ albums: [] }) };
      if (u.includes('/api/recommendations/discover-weekly')) return { ok: true, json: async () => ({ sections: [] }) };
      if (u.includes('/api/health')) return { ok: true, json: async () => ({ ok: true }) };
      if (u.includes('/api/nowplaying_devices')) return { ok: true, json: async () => ({ items: [] }) };
      return { ok: true, json: async () => ({}) };
    },
  });
  await window.routes.dashboard('');
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /home-page/);
  assert.match(html, /home-filter|home-quick-grid/);
  assert.match(html, /Jump back in|Your top mixes|Recent playlists|Dinner &amp; entertaining/);
  assert.match(html, /spotify-home-section|spotify-section/);
});

test('buildHealthCard still renders on dashboard load', async () => {
  const { window } = bootstrap({
    fetchImpl: async (url) => {
      if (String(url).includes('/api/health')) {
        return { ok: true, json: async () => ({
          uptimeSeconds: 60, watchdogFresh: true, backendHttp: true,
          tunnelReachable: true, alexaAuth: true, skillTesting: 'unknown',
        }) };
      }
      return { ok: true, json: async () => ({ items: [], total: 0, songs: 0 }) };
    },
  });
  const html = window.buildHealthCard({
    uptimeSeconds: 60, watchdogFresh: true, backendHttp: true,
    tunnelReachable: true, alexaAuth: true, skillTesting: 'unknown',
  });
  assert.match(html, /Service Health/);
});
