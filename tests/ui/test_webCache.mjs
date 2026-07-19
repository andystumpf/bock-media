import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';

const REPO = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function loadWebCache() {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'http://localhost/',
    runScripts: 'dangerously',
  });
  const script = dom.window.document.createElement('script');
  script.textContent = readFileSync(path.join(REPO, 'public', 'js', 'webCache.js'), 'utf8');
  dom.window.document.body.appendChild(script);
  return dom.window.WebCache;
}

const sampleFeed = () => ({
  sections: [
    { id: 'recently-created', kind: 'RecentlyCreated', title: 'Recently Created', cards: [{ playlistId: 'p0', title: 'New' }] },
    { kind: 'Mood', title: 'Dinner', cards: [{ playlistId: 'p1', title: 'A' }] },
    ...Array.from({ length: 8 }, (_, i) => ({ kind: 'Mood', title: `M${i}`, cards: [] })),
    { id: 'more-playlists', kind: 'Playlists', title: 'More playlists', cards: [] },
  ],
});

test('WebCache stores current-layout home feed for fast paint', () => {
  const WebCache = loadWebCache();
  const feed = sampleFeed();
  WebCache.putHome(feed, { p1: '/art.jpg' });
  WebCache.markHomeLoaded();
  assert.ok(WebCache.peekHome());
  // Home always refreshes in the background — cache is paint-only.
  assert.equal(WebCache.shouldSkipHomeReload(), false);
});

test('WebCache putHome rejects legacy feed layouts', () => {
  const WebCache = loadWebCache();
  const legacy = {
    sections: [{ kind: 'Mood', title: 'Dinner', cards: [{ playlistId: 'p1' }] }],
  };
  WebCache.putHome(legacy, { p1: '/art.jpg' });
  assert.equal(WebCache.peekHome(), null);
});

test('WebCache visibleHomeCoverIds caps at limit', () => {
  const WebCache = loadWebCache();
  const feed = {
    sections: Array.from({ length: 10 }, (_, i) => ({
      kind: 'Mood',
      cards: [{ playlistId: `p${i}` }],
    })),
  };
  const ids = WebCache.visibleHomeCoverIds(feed, 5);
  assert.equal(ids.length, 5);
});

test('WebCache library session cache', () => {
  const WebCache = loadWebCache();
  WebCache.putLibrary({
    playlists: [{ id: '1', name: 'Test' }],
    smart: [],
    folders: [],
    genres: [],
    covers: { 1: '/art.jpg' },
  });
  WebCache.markLibraryLoaded();
  const snap = WebCache.peekLibrary();
  assert.equal(snap.playlists.length, 1);
  assert.ok(WebCache.shouldSkipLibraryReload());
});

test('WebCache library reload not skipped without covers', () => {
  const WebCache = loadWebCache();
  WebCache.putLibrary({ playlists: [{ id: '1', name: 'Test' }], smart: [], folders: [], genres: [] });
  WebCache.markLibraryLoaded();
  assert.equal(WebCache.shouldSkipLibraryReload(), false);
});

test('WebCache playlist session cache', () => {
  const WebCache = loadWebCache();
  WebCache.setPlaylists([{ id: '1', name: 'Test' }]);
  const items = WebCache.getPlaylistsIfFresh();
  assert.equal(items.length, 1);
  assert.equal(items[0].name, 'Test');
});
