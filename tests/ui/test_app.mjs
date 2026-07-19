// UI tests for public/js/app.js using jsdom + Node's built-in test runner.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bootstrap } from './helpers.mjs';


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


// ─────────────────────────── routines builder ────────────────────────────────

test('buildRoutinePhrase uses collision-safe start/mix verbs', () => {
  const { window } = bootstrap();
  assert.equal(window.buildRoutinePhrase('Yacht Rock', false), 'ask bock media to start the Yacht Rock playlist');
  assert.equal(window.buildRoutinePhrase('Yacht Rock', true), 'ask bock media to mix the Yacht Rock playlist');
  // Never the hijack-prone verbs
  assert.doesNotMatch(window.buildRoutinePhrase('X', false), /\bplay\b/);
  assert.doesNotMatch(window.buildRoutinePhrase('X', true), /\bshuffle\b/);
});

test('renderRoutinesBuilder + updateRoutineOutput produces steps and a phrase', () => {
  const { window, document } = bootstrap();
  window._routinePlaylists = [{ name: 'Morning' }, { name: 'Yacht Rock' }];
  window.renderRoutinesBuilder();
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Routine Builder/);
  assert.match(html, /ask bock media to start the Morning playlist/);
  // Toggle shuffle + regenerate
  document.getElementById('rt-shuffle').checked = true;
  document.getElementById('rt-playlist').value = 'Yacht Rock';
  window.updateRoutineOutput();
  assert.match(document.getElementById('rt-phrase').textContent, /mix the Yacht Rock playlist/);
});


// ─────────────────────────── ignore / never play again ───────────────────────

test('buildDeviceRow shows never-again button only when filepath present', () => {
  const { window } = bootstrap();
  window._npControlsAvailable = true;
  window._alexaDevices = [{ name: 'Kitchen', serial: 'S1' }];
  const withPath = window.buildDeviceRow({ deviceId: 'a', deviceName: 'Kitchen', track: 't', filepath: '/m/x.mp3' }, true);
  assert.match(withPath, /npNeverAgainEl/);
  const noPath = window.buildDeviceRow({ deviceId: 'a', deviceName: 'Kitchen', track: 't' }, true);
  assert.doesNotMatch(noPath, /npNeverAgainEl/);
});

test('loadIgnoredPanel renders empty state and items', async () => {
  let payload = { items: [] };
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => payload }),
  });
  const card = document.createElement('div');
  card.innerHTML = '<div id="an-ignored-body"></div>';
  document.body.appendChild(card);
  await window.loadIgnoredPanel();
  assert.match(document.getElementById('an-ignored-body').innerHTML, /No ignored tracks/);

  payload = { items: [{ path: '/m/x.mp3', title: 'Bad Song', artist: 'Nope' }] };
  await window.loadIgnoredPanel();
  const html = document.getElementById('an-ignored-body').innerHTML;
  assert.match(html, /Bad Song/);
  assert.match(html, /Allow again/);
});


// ─────────────────────────── sleep timer badge ───────────────────────────────

test('buildDeviceRow shows a sleep badge when a timer is armed', () => {
  const { window } = bootstrap();
  const time = window.buildDeviceRow({ deviceId: 'a', deviceName: 'Kitchen', track: 't', sleep: { type: 'time', remainingMin: 25 } });
  assert.match(time, /np-sleep-badge/);
  assert.match(time, /25m/);
  const songs = window.buildDeviceRow({ deviceId: 'a', deviceName: 'Kitchen', track: 't', sleep: { type: 'songs', remaining: 3 } });
  assert.match(songs, /3 left/);
});

test('buildDeviceRow has no sleep badge without a timer', () => {
  const { window } = bootstrap();
  const html = window.buildDeviceRow({ deviceId: 'a', deviceName: 'Kitchen', track: 't' });
  assert.doesNotMatch(html, /np-sleep-badge/);
});


// ─────────────────────────── group-aware now playing ─────────────────────────

test('groupNowPlaying collapses same-group same-track rows', () => {
  const { window } = bootstrap();
  window._alexaDevices = [
    { name: 'Kitchen', serial: 'S1' },
    { name: 'Office', serial: 'S2' },
    { name: 'Garage', serial: 'S3' },
  ];
  window._deviceGroups = [{ name: 'Downstairs', members: [{ serial: 'S1' }, { serial: 'S2' }] }];
  const grouped = window.groupNowPlaying([
    { deviceId: 'a', deviceName: 'Kitchen', track: 'Song A', artist: 'X' },
    { deviceId: 'b', deviceName: 'Office', track: 'Song A', artist: 'X' },
    { deviceId: 'c', deviceName: 'Garage', track: 'Song B', artist: 'Y' },
  ]);
  assert.equal(grouped.length, 2);
  const group = grouped.find(e => e.type === 'group');
  assert.ok(group, 'expected a group entry');
  assert.equal(group.name, 'Downstairs');
  assert.equal(group.members.length, 2);
  assert.ok(grouped.find(e => e.type === 'single' && e.item.deviceName === 'Garage'));
});

test('groupNowPlaying does not group different tracks in same group', () => {
  const { window } = bootstrap();
  window._alexaDevices = [{ name: 'Kitchen', serial: 'S1' }, { name: 'Office', serial: 'S2' }];
  window._deviceGroups = [{ name: 'Downstairs', members: [{ serial: 'S1' }, { serial: 'S2' }] }];
  const grouped = window.groupNowPlaying([
    { deviceId: 'a', deviceName: 'Kitchen', track: 'Song A' },
    { deviceId: 'b', deviceName: 'Office', track: 'Song B' },
  ]);
  assert.equal(grouped.filter(e => e.type === 'group').length, 0);
  assert.equal(grouped.length, 2);
});

test('buildCurrentCard renders a group header for multi-room playback', () => {
  const { window } = bootstrap();
  window._alexaDevices = [{ name: 'Kitchen', serial: 'S1' }, { name: 'Office', serial: 'S2' }];
  window._deviceGroups = [{ name: 'Downstairs', members: [{ serial: 'S1' }, { serial: 'S2' }] }];
  const html = window.buildCurrentCard([
    { deviceId: 'a', deviceName: 'Kitchen', track: 'Song A', artist: 'X' },
    { deviceId: 'b', deviceName: 'Office', track: 'Song A', artist: 'X' },
  ]);
  assert.match(html, /np-group-header/);
  assert.match(html, /Downstairs/);
  assert.match(html, /2 speakers/);
});


// ─────────────────────────── refreshCurrentTrack ─────────────────────────────

test('refreshCurrentTrack hides bar when no devices playing', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({ items: [] }) }),
  });
  await window.refreshCurrentTrack();
  assert.ok(document.getElementById('now-playing-bar').classList.contains('hidden'));
});

test('refreshCurrentTrack shows single device label', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({
      items: [{ deviceId: 'a', track: 'Hey', artist: 'Pixies' }],
    }) }),
  });
  await window.refreshCurrentTrack();
  const bar = document.getElementById('now-playing-bar');
  assert.ok(!bar.classList.contains('hidden'));
  assert.equal(document.getElementById('np-track-text').textContent, 'Hey');
  assert.equal(document.getElementById('np-artist-text').textContent, 'Pixies');
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
  assert.match(document.getElementById('np-track-text').textContent, /A \(\+2 more\)$/);
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
  assert.match(html, /fa-pen/);
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
  const saveCall = calls.find((c) => c.url.includes('/api/devices/amzn1.ask.device.X'));
  assert.ok(saveCall, `expected device save fetch, got: ${calls.map((c) => c.url).join(', ')}`);
  assert.equal(saveCall.opts.method, 'POST');
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
  const deleteCall = calls.find((c) => c.method === 'DELETE');
  assert.ok(deleteCall, `expected device DELETE fetch, got: ${calls.map((c) => c.method || 'GET').join(', ')}`);
  assert.match(deleteCall.url, /\/api\/devices\/amzn1\.ask\.device\.A/);
});


// ─────────────────────────── fix-my-devices flow ─────────────────────────────

test('isAutoName detects placeholder echo names', () => {
  const { window } = bootstrap();
  assert.equal(window.isAutoName('Echo AB12CD'), true);
  assert.equal(window.isAutoName(''), true);
  assert.equal(window.isAutoName('Kitchen Show'), false);
});

test('unnamedCount counts only auto-named devices', () => {
  const { window } = bootstrap();
  assert.equal(window.unnamedCount([
    { name: 'Echo ABCDEF' }, { name: 'Kitchen' }, { name: 'Echo 123456' },
  ]), 2);
});

test('renderDevices shows Fix my devices button when there are unnamed devices', () => {
  const { window, document } = bootstrap();
  window._devicesRemoteConfigured = true;
  window._devices = [{ deviceId: 'a', name: 'Echo ABCDEF' }, { deviceId: 'b', name: 'Kitchen' }];
  window.renderDevices();
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Fix my devices \(1\)/);
});

test('renderFixStep renders progress and advances on skip', () => {
  const { window, document } = bootstrap();
  window._fix = { queue: [
    { serial: 'S1', name: 'Kitchen', online: true },
    { serial: 'S2', name: 'Office', online: true },
  ], idx: 0 };
  window.renderFixStep();
  let box = document.querySelector('.fix-modal .modal-box');
  assert.match(box.innerHTML, /Speaker 1 of 2/);
  assert.match(box.innerHTML, /Kitchen/);
  document.getElementById('fix-skip').click();
  box = document.querySelector('.fix-modal .modal-box');
  assert.match(box.innerHTML, /Speaker 2 of 2/);
  assert.match(box.innerHTML, /Office/);
});


// ─────────────────────────── service health card ─────────────────────────────

test('buildHealthCard renders ok/bad/unknown chips', () => {
  const { window } = bootstrap();
  const html = window.buildHealthCard({
    uptimeSeconds: 120, lastAlexaHitAgo: 30, watchdogFresh: true,
    backendHttp: true, tunnelReachable: false, alexaAuth: true,
    skillTesting: 'unknown', publicLatencyMs: 90, publicStatus: 403,
  });
  assert.match(html, /Service Health/);
  assert.match(html, /health-chip ok[^"]*"[^>]*>\s*<i[^>]*><\/i> Backend/);
  assert.match(html, /health-chip bad/);   // tunnel down
  assert.match(html, /health-chip unknown/); // skill testing unknown
});

test('buildHealthCard shows a Plex chip only when configured', () => {
  const { window } = bootstrap();
  const withPlex = window.buildHealthCard({
    uptimeSeconds: 1, watchdogFresh: true, backendHttp: true, tunnelReachable: true,
    alexaAuth: true, skillTesting: 'unknown', plexConfigured: true, plexReachable: false,
  });
  assert.match(withPlex, /Plex sync/);
  const noPlex = window.buildHealthCard({
    uptimeSeconds: 1, watchdogFresh: true, backendHttp: true, tunnelReachable: true,
    alexaAuth: true, skillTesting: 'unknown', plexConfigured: false,
  });
  assert.doesNotMatch(noPlex, /Plex sync/);
});

test('buildHealthCard shows stale watchdog note when not fresh', () => {
  const { window } = bootstrap();
  const html = window.buildHealthCard({
    uptimeSeconds: 10, lastAlexaHitAgo: null, watchdogFresh: false,
    watchdogAgeSeconds: 9999, backendHttp: null, tunnelReachable: null,
    alexaAuth: null, skillTesting: 'unknown',
  });
  assert.match(html, /watchdog/);
  assert.match(html, /last Alexa hit never/);
});


// ─────────────────────────── settings legacy notice ──────────────────────────

test('settings page marks Watch Folder Scanning as legacy/unused', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({}) }),
  });
  window.navigate('settings');
  // settings route is async; flush microtasks until it renders
  for (let i = 0; i < 20 && !/Watch Folder Scanning/.test(document.getElementById('main-content').innerHTML); i++) {
    await new Promise(r => setTimeout(r, 0));
  }
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Watch Folder Scanning/);
  assert.match(html, /LEGACY/);
  assert.match(html, /Plex sync/i);
});


// ─────────────────────────── global auth banner ──────────────────────────────

test('refreshGlobalBanner shows warning when configured but session expired', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({
      available: true, configured: true, authenticated: false,
    }) }),
  });
  const el = document.getElementById('global-banner');
  await window.refreshGlobalBanner();
  assert.match(el.innerHTML, /session expired/i);
});

test('refreshGlobalBanner stays empty when authenticated', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({
      available: true, configured: true, authenticated: true,
    }) }),
  });
  const el = document.getElementById('global-banner');
  await window.refreshGlobalBanner();
  assert.equal(el.innerHTML, '');
});

test('refreshGlobalBanner stays empty when not configured', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({
      available: true, configured: false, authenticated: null,
    }) }),
  });
  const el = document.getElementById('global-banner');
  await window.refreshGlobalBanner();
  assert.equal(el.innerHTML, '');
});
