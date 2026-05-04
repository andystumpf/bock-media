// UI tests for public/js/app.js using jsdom + Node's built-in test runner.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname  = path.dirname(__filename);

// Build a jsdom window with the SPA shell, load app.js, and return
// references to the window/document and any module-scoped helpers.
function bootstrap({ fetchImpl } = {}) {
  const html = `
    <!doctype html>
    <html><body>
      <span id="page-title"></span>
      <div id="main-content"></div>
      <div id="now-playing-bar" style="display:none">
        <span id="np-track-text"></span>
      </div>
      <span id="user-label"></span>
      <span id="server-label"></span>
      <button id="sidebar-toggle"></button>
      <div id="sidebar-wrapper"></div>
      <div id="content-wrapper"></div>
      <div id="toast"></div>
      <nav><a class="nav-link" href="#dashboard">d</a></nav>
      <ul></ul>
    </body></html>`;
  const dom = new JSDOM(html, { url: 'http://localhost/', runScripts: 'dangerously' });
  const { window } = dom;
  window.fetch = fetchImpl || (async () => ({ ok: true, json: async () => ({}) }));
  window.confirm = () => true;
  window.alert = () => {};
  window.setInterval = () => 0;

  let src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'app.js'), 'utf8');
  // Skip the unconditional init() call; tests drive functions directly.
  src = src.replace(/\binit\(\);?\s*$/m, '');
  // Inject as <script> so identifiers like `document` resolve to window.document.
  const script = window.document.createElement('script');
  script.textContent = src;
  window.document.body.appendChild(script);
  return { window, document: window.document, dom };
}


// ─────────────────────────── pure helpers ────────────────────────────────────

test('escHtml escapes html-significant chars', () => {
  const { window } = bootstrap();
  assert.equal(window.escHtml('<a href="x">&"'), '&lt;a href=&quot;x&quot;&gt;&amp;&quot;');
});

test('escHtml stringifies null/undefined safely', () => {
  const { window } = bootstrap();
  assert.equal(window.escHtml(null), '');
  assert.equal(window.escHtml(undefined), '');
});

test('fmtNum formats with locale separators', () => {
  const { window } = bootstrap();
  assert.equal(window.fmtNum(1234567), (1234567).toLocaleString());
});

test('fmtNum handles falsy', () => {
  const { window } = bootstrap();
  assert.equal(window.fmtNum(null), '0');
});

test('fmtDateTime returns dash for empty', () => {
  const { window } = bootstrap();
  assert.equal(window.fmtDateTime(''), '—');
});


// ─────────────────────────── now-playing rendering ───────────────────────────

test('buildCurrentCard with no items shows empty state', () => {
  const { window } = bootstrap();
  const html = window.buildCurrentCard([]);
  assert.match(html, /Nothing is currently playing/);
});

test('buildCurrentCard with multiple devices shows count and rows', () => {
  const { window } = bootstrap();
  const html = window.buildCurrentCard([
    { deviceId: 'a', deviceName: 'Kitchen',  track: 'Song A', artist: 'Art A' },
    { deviceId: 'b', deviceName: 'Garage',   track: 'Song B', artist: 'Art B' },
  ]);
  assert.match(html, /Now Playing \(2\)/);
  assert.match(html, /Song A/);
  assert.match(html, /Song B/);
  assert.match(html, /Kitchen/);
  assert.match(html, /Garage/);
});

test('buildDeviceRow includes device name and falls back to deviceId tail', () => {
  const { window } = bootstrap();
  const named = window.buildDeviceRow({ deviceName: 'Garage', track: 't', artist: 'a' });
  assert.match(named, /Device: Garage/);
  const fallback = window.buildDeviceRow({ deviceId: 'amzn1.ask.device.ABCDEFGHIJ', track: 't' });
  // contract: when no deviceName, show last 12 chars of the deviceId
  assert.match(fallback, /Device: e\.ABCDEFGHIJ/);
});


// ─────────────────────────── refreshCurrentTrack ─────────────────────────────

test('refreshCurrentTrack hides bar when no devices playing', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({ items: [] }) }),
  });
  await window.refreshCurrentTrack();
  assert.equal(document.getElementById('now-playing-bar').style.display, 'none');
});

test('refreshCurrentTrack shows single device label', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({
      items: [{ deviceId: 'a', track: 'Hey', artist: 'Pixies' }],
    }) }),
  });
  await window.refreshCurrentTrack();
  assert.equal(document.getElementById('now-playing-bar').style.display, 'flex');
  assert.equal(document.getElementById('np-track-text').textContent, 'Hey — Pixies');
});

test('refreshCurrentTrack shows +N when multiple', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({
      items: [
        { deviceId: 'a', track: 'A', artist: 'X' },
        { deviceId: 'b', track: 'B', artist: 'Y' },
        { deviceId: 'c', track: 'C', artist: 'Z' },
      ],
    }) }),
  });
  await window.refreshCurrentTrack();
  assert.match(document.getElementById('np-track-text').textContent, /\(\+2 more\)$/);
});


// ─────────────────────────── devices page ────────────────────────────────────

test('renderDevices shows empty state when none', () => {
  const { window, document } = bootstrap();
  window._devices = [];
  window.renderDevices();
  assert.match(document.getElementById('main-content').innerHTML, /No devices yet/);
});

test('renderDevices shows row per device with edit + trash buttons', () => {
  const { window, document } = bootstrap();
  window._devices = [
    { deviceId: 'a', name: 'Kitchen', lastSeen: 1700000000 },
    { deviceId: 'b', name: 'Garage',  lastSeen: 1700000001 },
  ];
  window.renderDevices();
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Kitchen/);
  assert.match(html, /Garage/);
  assert.match(html, /fa-pencil/);
  assert.match(html, /fa-trash/);
  assert.match(html, /Alexa Devices \(2\)/);
});

test('saveDevice POSTs the new name and updates local state', async () => {
  const calls = [];
  const { window, document } = bootstrap({
    fetchImpl: async (url, opts) => {
      calls.push({ url, opts });
      return { ok: true, json: async () => ({ ok: true }) };
    },
  });
  window._devices = [{ deviceId: 'amzn1.ask.device.X', name: 'Old', lastSeen: 0 }];
  window.renderDevices();
  window.startEditDevice(0);
  document.getElementById('dev-input-0').value = 'Living Room';
  await window.saveDevice(0);
  assert.equal(window._devices[0].name, 'Living Room');
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /\/api\/devices\/amzn1\.ask\.device\.X/);
  assert.equal(calls[0].opts.method, 'POST');
});

test('deleteDevice calls DELETE and removes from local list', async () => {
  const calls = [];
  const { window } = bootstrap({
    fetchImpl: async (url, opts) => {
      calls.push({ url, method: opts && opts.method });
      return { ok: true, json: async () => ({ ok: true }) };
    },
  });
  window._devices = [
    { deviceId: 'amzn1.ask.device.A', name: 'A' },
    { deviceId: 'amzn1.ask.device.B', name: 'B' },
  ];
  window.renderDevices();
  await window.deleteDevice(0);
  assert.equal(window._devices.length, 1);
  assert.equal(window._devices[0].deviceId, 'amzn1.ask.device.B');
  assert.equal(calls[0].method, 'DELETE');
});
