import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { bootstrap, bottomTabForRoute } from './helpers.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function readShellCss() {
  return readFileSync(path.join(__dirname, '..', '..', 'public', 'css', 'shell.css'), 'utf8');
}

test('shell has Spotify layout regions', () => {
  const { document } = bootstrap();
  assert.ok(document.getElementById('app-shell'));
  assert.ok(document.querySelector('.spotify-sidebar'));
  assert.ok(document.getElementById('bottom-nav'));
  assert.ok(document.getElementById('now-playing-bar'));
  assert.ok(document.getElementById('app-drawer'));
});

test('bottom nav exposes four primary tabs matching shell', () => {
  const { document } = bootstrap();
  const tabs = [...document.querySelectorAll('.bottom-nav-link')].map((a) => a.dataset.tab);
  assert.deepEqual(tabs, ['home', 'nowplaying', 'search', 'library']);
});

test('drawer links include secondary routes', () => {
  const { document } = bootstrap();
  const hrefs = [...document.querySelectorAll('.drawer-link')].map((a) => a.getAttribute('href'));
  assert.ok(hrefs.includes('#settings'));
  assert.ok(hrefs.includes('#devices'));
  assert.ok(hrefs.includes('#family'));
  assert.ok(hrefs.includes('#analytics'));
});

test('mini player uses hidden class when idle', async () => {
  const { window, document } = bootstrap({
    fetchImpl: async () => ({ ok: true, json: async () => ({ items: [] }) }),
  });
  await window.refreshCurrentTrack();
  const bar = document.getElementById('now-playing-bar');
  assert.ok(bar.classList.contains('hidden'));
  assert.ok(bar.classList.contains('spotify-player'));
});

test('mini player shows track and artist when playing', async () => {
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

test('account menu opens profile dropdown', () => {
  const { document } = bootstrap();
  const menu = document.getElementById('profile-dropdown');
  assert.ok(menu.classList.contains('hidden'));
  document.getElementById('account-menu-btn').click();
  assert.equal(menu.classList.contains('hidden'), false);
  document.getElementById('topbar-profile').click();
  assert.equal(menu.classList.contains('hidden'), true);
});

test('bottomTabForRoute maps routes like Android tabs', () => {
  assert.equal(bottomTabForRoute('dashboard'), 'home');
  assert.equal(bottomTabForRoute('search'), 'search');
  assert.equal(bottomTabForRoute('playlists'), 'library');
  assert.equal(bottomTabForRoute('playlists/detail/abc'), 'library');
  assert.equal(bottomTabForRoute('automation'), 'automations');
  assert.equal(bottomTabForRoute('settings'), null);
});

test('stylesheet defines Spotify design tokens', () => {
  const css = readShellCss();
  assert.match(css, /--bock-green:\s*#1[Dd][Bb]954/);
  assert.match(css, /--spotify-bg:\s*#121212|--spotify-black:\s*#000000/);
  assert.match(css, /--spotify-elevated:\s*#282828/);
  assert.match(css, /\.spotify-sidebar/);
  assert.match(css, /\.spotify-player/);
  assert.match(css, /\.spotify-search-field/);
  assert.match(css, /\.library-tile/);
});
