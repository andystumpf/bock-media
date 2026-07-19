import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bootstrap } from './helpers.mjs';

const ROUTES = [
  'dashboard',
  'search',
  'library',
  'genres',
  'playlists',
  'playlists/detail/test-pl',
  'artists',
  'albums',
  'songs',
  'artist/Test%20Artist',
  'album/Test%20Artist/Test%20Album',
  'liked',
  'nowplaying',
  'rooms',
  'routines',
  'watchfolders',
  'devices',
  'automation',
  'settings',
  'analytics',
  'family',
];

function jsonResponse(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

async function mockFetch(url) {
  const u = String(url);

  if (u.includes('/api/summary')) return jsonResponse({ songs: 1, albums: 1, artists: 1, watchFolders: 1 });
  if (u.includes('/api/recent')) return jsonResponse({ items: [], total: 0 });
  if (u.includes('/api/dashboard/quick')) return jsonResponse({ recent: [], favorites: [] });
  if (u.includes('/api/favorites')) return jsonResponse({ items: [{ title: 'Song', artist: 'Artist', path: '/a.mp3' }] });
  if (u.includes('/api/library/new')) return jsonResponse({ albums: [] });
  if (u.includes('/api/recommendations/discover-weekly')) return jsonResponse({ sections: [] });
  if (u.includes('/api/plex_sync')) return jsonResponse(null);
  if (u.includes('/api/continue')) return jsonResponse(null);
  if (u.includes('/api/health')) return jsonResponse({ backendHttp: true, uptimeSeconds: 60 });
  if (u.includes('/api/playback/status')) return jsonResponse({ items: [] });
  if (u.includes('/api/alexa_remote/status')) return jsonResponse({ configured: false, available: false });
  if (u.includes('/api/genres')) return jsonResponse({ items: [{ name: 'Rock', track_count: 10 }] });
  if (u.includes('/api/playlists/covers')) return jsonResponse({ covers: { p0: '/music/a.mp3' } });
  if (u.includes('/api/playlists/test-pl')) {
    return jsonResponse({ id: 'test-pl', name: 'Test', tracks: [], total: 0, page: 1, limit: 100, editable: true });
  }
  if (u.includes('/api/playlists')) {
    return jsonResponse({
      items: Array.from({ length: 15 }, (_, i) => ({ id: `p${i}`, name: `Playlist ${i}`, trackCount: 10 + i })),
      total: 15,
    });
  }
  if (u.includes('/api/smart_playlists')) return jsonResponse({ items: [] });
  if (u.includes('/api/smart_playlists')) return jsonResponse({ items: [] });
  if (u.includes('/api/playlist_folders')) return jsonResponse({ folders: [], assignments: {} });
  if (u.match(/\/api\/artists\/[^/?]+(\/top-tracks)?/)) {
    return jsonResponse({
      artist: 'Test Artist',
      topTracks: [{ title: 'Song', artist: 'Test Artist', album: 'Test Album', path: '/a.mp3', duration_seconds: 180 }],
      albums: [{ album: 'Test Album', artist: 'Test Artist', path: '/a.mp3', year: 2020 }],
      trackCount: 1,
      totalPlays: 5,
      items: [{ title: 'Song', artist: 'Test Artist', path: '/a.mp3' }],
    });
  }
  if (u.includes('/api/artists')) return jsonResponse({ items: [{ artist: 'Artist', album_count: 1, track_count: 5 }], total: 1 });
  if (u.includes('/api/albums')) return jsonResponse({ items: [{ album: 'Album', artist: 'Artist', track_count: 5 }], total: 1 });
  if (u.includes('/api/music-video/check')) return jsonResponse({ available: {} });
  if (u.includes('/api/songs')) return jsonResponse({ items: [{ title: 'Song', artist: 'Test Artist', album: 'Test Album', path: '/a.mp3', duration_seconds: 180 }], total: 1 });
  if (u.includes('/api/nowplaying_devices')) return jsonResponse({ items: [], controlsAvailable: false });
  if (u.includes('/api/nowplaying')) return jsonResponse({ items: [], total: 0 });
  if (u.includes('/api/rooms')) return jsonResponse({ rooms: [{ name: 'Kitchen', nowPlaying: null, automations: [] }], controlsAvailable: false });
  if (u.includes('/api/watchfolders')) return jsonResponse([{ path: '/music', trackCount: 100 }]);
  if (u.includes('/api/devices/merge_candidates')) return jsonResponse({ candidates: [] });
  if (u.includes('/api/devices')) return jsonResponse([{ deviceId: 'd1', name: 'Echo', platform: 'alexa' }]);
  if (u.includes('/api/device_groups')) return jsonResponse({ items: [] });
  if (u.includes('/api/automations')) return jsonResponse({ items: [] });
  if (u.includes('/api/settings')) return jsonResponse({});
  if (u.includes('/api/config')) return jsonResponse({ publicUrl: '' });
  if (u.includes('/api/localip')) return jsonResponse({ ip: '127.0.0.1' });
  if (u.includes('/api/analytics/household')) return jsonResponse({ totalPlays: 0, byMember: [], byPlatform: [] });
  if (u.includes('/api/analytics')) {
    return jsonResponse({
      totalPlays: 5,
      activity: { day: [{ label: 'Mon', count: 5 }] },
      hourOfDay: [{ hour: 12, count: 1 }],
      dayOfWeek: [{ day: 'Mon', count: 1 }],
      deviceBreakdown: [{ plays: 5 }],
    });
  }
  if (u.includes('/api/household')) return jsonResponse({ members: [], deviceOwners: [], clientBindings: [] });
  if (u.includes('/api/messages')) return jsonResponse({ items: [] });
  if (u.includes('/api/auth/info')) return jsonResponse({});
  return jsonResponse({});
}

async function waitForContent(ms = 120) {
  await new Promise((r) => setTimeout(r, ms));
}

test('route navigation does not trigger HTTP 400 API calls', async () => {
  const bad = [];
  const trackingFetch = async (url, init) => {
    const res = await mockFetch(url, init);
    if (res.status === 400) bad.push(String(url));
    return res;
  };
  const { window, document } = bootstrap({ fetchImpl: trackingFetch });
  window.localStorage.setItem('bock_active_member', 'stale-member-id');

  for (const hash of ROUTES) {
    window.navigate(hash);
    await waitForContent(150);
  }
  assert.deepEqual(bad, [], `unexpected 400 responses: ${bad.join(', ')}`);
});

test('all registered routes render main content (not stuck on spinner)', async () => {
  const { window, document } = bootstrap({ fetchImpl: mockFetch });

  for (const hash of ROUTES) {
    window.navigate(hash);
    await waitForContent(150);
    const html = document.getElementById('main-content').innerHTML;
    assert.ok(html.length > 20, `${hash}: main-content empty`);
    assert.doesNotMatch(html, /^<div class="spinner-wrap">/, `${hash}: still loading spinner`);
  }
});

test('route error shows empty state instead of eternal spinner', async () => {
  const { window, document } = bootstrap({ fetchImpl: mockFetch });
  window.routes._route_fail_test = async () => { throw new Error('Network down'); };
  window.navigate('_route_fail_test');
  await waitForContent(80);
  assert.match(document.getElementById('main-content').innerHTML, /Could not load this page/);
});

test('analytics fetch failure shows error state, not "No device activity"', async () => {
  const failingFetch = async (url) => {
    if (String(url).includes('/api/analytics')) return { ok: false, status: 401, json: async () => { throw new Error('not json'); } };
    return mockFetch(url);
  };
  const { window, document } = bootstrap({ fetchImpl: failingFetch });
  window.navigate('analytics');
  await waitForContent(200);
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /Couldn't load analytics/);
  assert.doesNotMatch(html, /No device activity yet/);
});

test('dashboard renders home feed sections after load', async () => {
  const { window, document } = bootstrap({ fetchImpl: mockFetch });
  await window.routes.dashboard('');
  await waitForContent(150);
  const html = document.getElementById('main-content').innerHTML;
  assert.match(html, /home-greeting/);
  assert.match(html, /spotify-section/);
});
