import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bootstrap } from './helpers.mjs';

test('search route renders browse content from API when empty query', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async (url) => {
      const u = String(url);
      if (u.includes('/api/dashboard/quick')) {
        return { ok: true, json: async () => ({ recent: [{ track: 'Song A', artist: 'Band' }], favorites: [] }) };
      }
      if (u.includes('/api/playlists')) {
        return { ok: true, json: async () => ({ items: [{ id: '1', name: 'Chill', trackCount: 3 }] }) };
      }
      if (u.includes('/api/genres')) {
        return { ok: true, json: async () => ({ items: [{ name: 'Jazz', track_count: 9, art_path: '/music/jazz.mp3' }] }) };
      }
      if (u.includes('/api/library/new')) {
        return { ok: true, json: async () => ({ albums: [{ name: 'Fresh Album', path: '/music/new.mp3' }] }) };
      }
      if (u.includes('/api/playlists/covers')) {
        return { ok: true, json: async () => ({ covers: { '1': '/music/cover.mp3' } }) };
      }
      return { ok: true, json: async () => ({}) };
    },
  });
  await window.routes.search('');
  await new Promise((r) => setTimeout(r, 50));
  const results = document.getElementById('lib-search-results').innerHTML;
  assert.match(results, /Song A/);
  assert.match(results, /Chill/);
  assert.match(results, /Jazz/);
  assert.match(results, /Browse all|New Releases/);
  assert.match(results, /search-hit-art|search-genre-tile/);
});

test('libSearchDebounced stores query', () => {
  const { window } = bootstrap();
  window.libSearchDebounced('abba');
  assert.equal(window._lastSearchQ, 'abba');
});

test('libSearchHit includes play button when playable', () => {
  const { window } = bootstrap();
  const html = window.libSearchHit({
    kind: 'artist',
    titleHtml: 'ABBA',
    playOpts: { kind: 'artist', name: 'ABBA' },
    showPlay: true,
  });
  assert.match(html, /lib-search-play/);
  assert.match(html, /search-hit/);
  assert.match(html, /search-hit-art-round/);
});

test('libSearchLink escapes text', () => {
  const { window } = bootstrap();
  const html = window.libSearchLink('#artists', '<script>');
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('libSearchPlayOptsFromBtn parses data attributes', () => {
  const { window, document } = bootstrap();
  const host = document.createElement('div');
  host.innerHTML = `<button class="lib-search-play" data-play-kind="playlist" data-play-name="Morning" data-play-id="abc"></button>`;
  const btn = host.querySelector('.lib-search-play');
  const opts = window.libSearchPlayOptsFromBtn(btn);
  assert.equal(opts.kind, 'playlist');
  assert.equal(opts.name, 'Morning');
  assert.equal(opts.id, 'abc');
});
