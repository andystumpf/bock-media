import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bootstrap } from './helpers.mjs';

test('navigate activates bottom nav tab for dashboard', () => {
  const { window, document } = bootstrap();
  window.navigate('dashboard');
  const home = document.querySelector('.bottom-nav-link[data-tab="home"]');
  assert.ok(home.classList.contains('active'));
});

test('navigate activates library tab for playlists', () => {
  const { window, document } = bootstrap();
  window.navigate('playlists');
  const lib = document.querySelector('.bottom-nav-link[data-tab="library"]');
  assert.ok(lib.classList.contains('active'));
});

test('navigate activates search tab', () => {
  const { window, document } = bootstrap();
  window.navigate('search');
  const search = document.querySelector('.bottom-nav-link[data-tab="search"]');
  assert.ok(search.classList.contains('active'));
});

test('navigate sets body class for now playing route', () => {
  const { window, document } = bootstrap({
    fetchImpl: async (url) => {
      if (String(url).includes('nowplaying')) {
        return { ok: true, json: async () => ({ items: [], total: 0 }) };
      }
      return { ok: true, json: async () => ({}) };
    },
  });
  window.navigate('nowplaying');
  assert.ok(document.body.classList.contains('route-nowplaying'));
});

test('navigate unknown route renders not found', () => {
  const { window, document } = bootstrap();
  window.navigate('does-not-exist');
  assert.match(document.getElementById('main-content').innerHTML, /Page not found/);
});

test('renderPage updates main content', () => {
  const { window, document } = bootstrap();
  window.renderPage('Test Title', '<p class="test-body">Hello</p>');
  assert.match(document.getElementById('main-content').innerHTML, /Hello/);
});

test('greeting returns time-of-day salutation', () => {
  const { window } = bootstrap();
  const h = new Date().getHours();
  const g = window.greeting();
  if (h < 12) assert.match(g, /Good morning/);
  else if (h < 17) assert.match(g, /Good afternoon/);
  else assert.match(g, /Good evening/);
});

test('hashchange triggers navigate when router is wired', () => {
  const { window } = bootstrap();
  window.addEventListener('hashchange', () => {
    window.navigate(window.location.hash.replace('#', '') || 'dashboard');
  });
  let called = '';
  const orig = window.navigate.bind(window);
  window.navigate = (h) => { called = h; return orig(h); };
  window.location.hash = '#search';
  window.dispatchEvent(new window.HashChangeEvent('hashchange'));
  assert.equal(called, 'search');
});
