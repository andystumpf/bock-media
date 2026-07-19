import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { JSDOM } from 'jsdom';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.join(__dirname, '..', '..');

function mockLocalStorage(window) {
  const store = new Map();
  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: {
      getItem(k) { return store.has(k) ? store.get(k) : null; },
      setItem(k, v) { store.set(k, String(v)); },
      removeItem(k) { store.delete(k); },
      clear() { store.clear(); },
    },
  });
}

function installBrowserMocks(window) {
  mockLocalStorage(window);
  window.HTMLMediaElement = window.HTMLMediaElement || {
    HAVE_CURRENT_DATA: 2,
    HAVE_FUTURE_DATA: 3,
  };
  window.Audio = class MockAudio {
    constructor() {
      this.volume = 1;
      this.preload = '';
      this.paused = true;
      this.currentTime = 0;
      this.duration = 0;
      this.readyState = 0;
      this.src = '';
      this.dataset = {};
    }
    addEventListener() {}
    removeEventListener() {}
    play() { this.paused = false; return Promise.resolve(); }
    pause() { this.paused = true; }
    load() {}
  };
  if (!window.crypto?.randomUUID) {
    Object.defineProperty(window, 'crypto', {
      configurable: true,
      value: { randomUUID: () => 'test-uuid' },
    });
  }
}

function loadModules(window, { fetchImpl } = {}) {
  installBrowserMocks(window);
  window.fetch = fetchImpl || window.fetch;
  window.authFetch = window.fetch;

  for (const file of ['clientPrefsSync.js', 'webPlayback.js', 'webMusicVideo.js']) {
    const src = readFileSync(path.join(REPO, 'public', 'js', file), 'utf8');
    vm.runInContext(src, window);
  }
}

test('WebMusicVideo prepare caches playUrl from API', async (t) => {
  const dom = new JSDOM(`<!doctype html><html><body>
    <div id="np-music-video-wrap"></div>
    <video id="np-music-video"></video>
    <img id="np-music-video-thumb">
  </body></html>`, { url: 'http://localhost/', runScripts: 'outside-only' });
  const { window } = dom;
  // Close the window when done — webMusicVideo's health-poll setInterval would
  // otherwise keep the node:test process alive until the CI job times out.
  t.after(() => window.close());
  loadModules(window, {
    fetchImpl: async (url) => ({
      ok: true,
      json: async () => ({
        videoId: 'abc123',
        playUrl: '/api/music-video/abc123/proxy',
        thumbnailUrl: 'https://i.ytimg.com/vi/abc123/hqdefault.jpg',
        streamReady: true,
      }),
    }),
  });

  window.ClientPrefsSync.setNowPlayingVideo(true, { push: false });
  const entry = await window.WebMusicVideo.prepare({
    title: 'Song',
    artist: 'Artist',
    durationMs: 180000,
  });
  assert.equal(entry.videoId, 'abc123');
  assert.equal(entry.playUrl, '/api/music-video/abc123/proxy');

  await window.WebMusicVideo.sync({
    active: true,
    playing: true,
    positionMs: 1000,
    current: { title: 'Song', artist: 'Artist', durationMs: 180000 },
  });
  const video = window.document.getElementById('np-music-video');
  assert.match(video.src, /music-video\/abc123\/proxy/);
  assert.equal(video.muted, true);
});
