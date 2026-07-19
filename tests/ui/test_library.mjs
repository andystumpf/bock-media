import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bootstrap } from './helpers.mjs';

const plFetch = async (url) => {
  const u = String(url);
  if (u.includes('/api/playlists/covers')) {
    return { ok: true, json: async () => ({ covers: { '1': '/music/a.mp3', '2': '/music/b.mp3' } }) };
  }
  if (u.includes('/api/playlists')) {
    return { ok: true, json: async () => ({
      items: [
        { id: '1', name: 'Morning Mix', trackCount: 12 },
        { id: '2', name: 'Evening Jazz', trackCount: 40 },
      ],
      total: 2,
    }) };
  }
  if (u.includes('/api/smart_playlists')) {
    return { ok: true, json: async () => ({ items: [{ name: 'Auto Jazz', trackCount: 5, linkedPlaylistId: '2' }] }) };
  }
  if (u.includes('/api/watchfolders')) {
    return { ok: true, json: async () => [{ path: '/music', trackCount: 100, identifiedFiles: 100, type: 'music', status: 'done' }] };
  }
  if (u.includes('/api/genres')) {
    return { ok: true, json: async () => ({ items: [{ name: 'Rock', track_count: 42 }] }) };
  }
  if (u.includes('/api/alexa_remote')) {
    return { ok: true, json: async () => ({ configured: false }) };
  }
  return { ok: true, json: async () => ({}) };
};

test('library route loads live playlists, smart playlists, folders, genres', async () => {
  const { window, document } = bootstrap({ fetchImpl: plFetch });
  await window.routes.library('');
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Morning Mix/);
  assert.match(html, /Auto Jazz/);
  assert.match(html, /\/music/);
  assert.match(html, /Rock/);
  assert.doesNotMatch(html, /Browse and play/);
  assert.doesNotMatch(html, /library-tile/);
});

test('library shows empty state when no data', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async (url) => {
      if (String(url).includes('/api/playlists')) {
        return { ok: true, json: async () => ({ items: [], total: 0 }) };
      }
      if (String(url).includes('/api/smart_playlists')) return { ok: true, json: async () => ({ items: [] }) };
      if (String(url).includes('/api/watchfolders')) return { ok: true, json: async () => [] };
      if (String(url).includes('/api/genres')) return { ok: true, json: async () => ({ items: [] }) };
      return plFetch(url);
    },
  });
  await window.routes.library('');
  assert.match(document.getElementById('main-content').innerHTML, /library is empty/);
});

test('genres route renders live genre cards', async () => {
  const { window, document } = bootstrap({ fetchImpl: plFetch });
  await window.routes.genres('');
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Rock/);
  assert.match(html, /spotify-card/);
  assert.match(html, /library-filter/);
});

test('playlists route renders card grid with playlists', async () => {
  const { window, document } = bootstrap({ fetchImpl: plFetch });
  await window.routes.playlists('');
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Morning Mix/);
  assert.match(html, /library-playlist-grid/);
  assert.match(html, /spotify-card/);
});
