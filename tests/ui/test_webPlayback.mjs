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

function loadWebPlayback(window) {
  const src = readFileSync(path.join(REPO, 'public', 'js', 'webPlayback.js'), 'utf8');
  vm.runInContext(src, window);
  return window.WebPlaybackCrossfade;
}

function loadClientPrefs(window) {
  const src = readFileSync(path.join(REPO, 'public', 'js', 'clientPrefsSync.js'), 'utf8');
  vm.runInContext(src, window);
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
      this._listeners = {};
    }
    addEventListener(type, fn) { (this._listeners[type] ||= []).push(fn); }
    removeEventListener(type, fn) {
      this._listeners[type] = (this._listeners[type] || []).filter((f) => f !== fn);
    }
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
  window.fetch = window.fetch || (async () => ({ ok: true, json: async () => ({}) }));
}

test('crossfadeProgress clamps 0..1', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', runScripts: 'outside-only' });
  const { window } = dom;
  installBrowserMocks(window);
  const helpers = loadWebPlayback(window);
  assert.equal(helpers.crossfadeProgress(0, 5), 0);
  assert.equal(helpers.crossfadeProgress(2.5, 5), 0.5);
  assert.equal(helpers.crossfadeProgress(5, 5), 1);
  assert.equal(helpers.crossfadeProgress(10, 5), 1);
});

test('crossfadeVolumes ramps outgoing down and incoming up', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', runScripts: 'outside-only' });
  const { window } = dom;
  installBrowserMocks(window);
  const helpers = loadWebPlayback(window);
  const start = helpers.crossfadeVolumes(0, 0.8);
  assert.equal(start.outgoing, 0.8);
  assert.equal(start.incoming, 0);
  const mid = helpers.crossfadeVolumes(0.5, 0.8);
  assert.equal(mid.outgoing, 0.4);
  assert.equal(mid.incoming, 0.4);
  const end = helpers.crossfadeVolumes(1, 0.8);
  assert.equal(end.outgoing, 0);
  assert.equal(end.incoming, 0.8);
});

test('shouldStartCrossfade respects prefs and queue', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', runScripts: 'outside-only' });
  const { window } = dom;
  installBrowserMocks(window);
  const helpers = loadWebPlayback(window);
  assert.equal(helpers.shouldStartCrossfade(3, 0, false, true), false);
  assert.equal(helpers.shouldStartCrossfade(3, 5, false, false), false);
  assert.equal(helpers.shouldStartCrossfade(3, 5, true, true), false);
  assert.equal(helpers.shouldStartCrossfade(3, 5, false, true), true);
  assert.equal(helpers.shouldStartCrossfade(8, 5, false, true), false);
});

test('ClientPrefsSync crossfade and video round-trip', () => {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', runScripts: 'outside-only' });
  const { window } = dom;
  installBrowserMocks(window);
  loadClientPrefs(window);

  window.ClientPrefsSync.setCrossfadeSeconds(8, { push: false });
  window.ClientPrefsSync.setNowPlayingVideo(true, { push: false });
  assert.equal(window.ClientPrefsSync.getCrossfadeSeconds(), 8);
  assert.equal(window.ClientPrefsSync.getNowPlayingVideo(), true);

  const changed = window.ClientPrefsSync.applyMerged({
    crossfadeSeconds: 12,
    nowPlayingVideo: false,
  });
  assert.equal(changed, true);
  assert.equal(window.ClientPrefsSync.getCrossfadeSeconds(), 12);
  assert.equal(window.ClientPrefsSync.getNowPlayingVideo(), false);

  window.ClientPrefsSync.setCrossfadeSeconds(99, { push: false });
  assert.equal(window.ClientPrefsSync.getCrossfadeSeconds(), 20);
});
