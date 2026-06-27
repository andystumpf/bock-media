import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { readHomeFeedJs } from './helpers.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const fixtureDir = path.join(__dirname, '..', '..', 'shared', 'fixtures', 'home_feed');

function loadHomeFeed() {
  const src = readHomeFeedJs();
  const mod = { exports: {} };
  // eslint-disable-next-line no-new-func
  new Function('module', 'globalThis', src)(mod, globalThis);
  return mod.exports;
}

test('compose matches Android section groups from library-only input', () => {
  const HomeFeed = loadHomeFeed();
  const playlists = Array.from({ length: 60 }, (_, i) => ({
    id: `pl-${i + 1}`,
    name: `Playlist ${i + 1}`,
    trackCount: (i + 1) * 2,
  }));
  const feed = HomeFeed.compose({
    history: [],
    analytics: null,
    allPlaylists: playlists,
    smartPlaylists: [],
    favorites: [],
    dashboard: null,
    libraryGenres: [],
    shuffleSeed: 42,
  });
  assert.ok(feed.sections.length > 0);
  const moodCount = feed.sections.filter((s) => s.kind === 'Mood').length;
  assert.ok(moodCount >= 9, `expected mood rows, got ${moodCount}`);
  const titles = feed.sections.map((s) => s.title);
  assert.ok(titles.includes('Jump back in'));
  assert.ok(titles.includes('Your top mixes'));
  assert.ok(titles.some((t) => /Recent playlists|More playlists|Daily mixes/.test(t)));
});

test('compose excludes Automations playlists from feed', () => {
  const HomeFeed = loadHomeFeed();
  const input = JSON.parse(readFileSync(path.join(fixtureDir, 'input.json'), 'utf8'));
  const feed = HomeFeed.compose({
    ...input,
    allPlaylists: input.allPlaylists.map((p) => ({ ...p, tracks: p.trackCount })),
    libraryGenres: [],
  });
  const allTitles = feed.sections.flatMap((s) => s.cards.map((c) => c.title));
  assert.ok(!allTitles.some((t) => t.startsWith('Automations')));
});

test('homeShortcutCards excludes tracks and albums from Jump back in', () => {
  const HomeFeed = loadHomeFeed();
  const input = JSON.parse(readFileSync(path.join(fixtureDir, 'input.json'), 'utf8'));
  const feed = HomeFeed.compose({
    ...input,
    dashboard: {
      recent: [
        { playlist: 'Road Trip', path: '/m/a.mp3', track: 'Song A', artist: 'Artist A' },
        { path: '/m/d.mp3', track: 'Song D', artist: 'Artist D' },
      ],
    },
    allPlaylists: input.allPlaylists.map((p) => ({ ...p, tracks: p.trackCount })),
    libraryGenres: [],
  });
  const jump = feed.sections.find((s) => s.kind === 'JumpBackIn');
  assert.ok(jump?.cards.some((c) => c.playTarget.kind === 'song'), 'fixture should include a song in Jump back in');
  const shortcuts = HomeFeed.homeShortcutCards(feed, 8);
  assert.ok(shortcuts.every((c) => c.playTarget.kind !== 'song' && c.playTarget.kind !== 'album'));
  assert.ok(shortcuts.every((c) => {
    if (c.playTarget.kind === 'playlist') return true;
    return ['TopMixes', 'Mood', 'DailyMixes', 'ExploreThemes', 'RecentPlaylists'].includes(c.kind)
      && (c.playTarget.kind === 'artist' || c.playTarget.kind === 'radio');
  }));
});

test('applyTileRotation replaces stale tiles after four days', () => {
  const HomeFeed = loadHomeFeed();
  HomeFeed.__testEngagement.reset();
  const input = {
    allPlaylists: [
      { id: 'pl-1', name: 'Playlist 1', trackCount: 12 },
      { id: 'pl-2', name: 'Playlist 2', trackCount: 12 },
      { id: 'pl-3', name: 'Playlist 3', trackCount: 12 },
    ],
    shuffleSeed: 7,
  };
  const feed = {
    sections: [{
      id: 'top-mixes',
      title: 'Your top mixes',
      kind: 'TopMixes',
      cards: [{
        id: 'pl-pl-1',
        title: 'Playlist 1',
        subtitle: 'Suggested mix',
        playlistId: 'pl-1',
        playTarget: { kind: 'playlist', id: 'pl-1', name: 'Playlist 1' },
        kind: 'TopMixes',
      }],
    }],
  };
  const now = Date.now();
  HomeFeed.__testEngagement.put('pl-pl-1', {
    firstSeenMs: now - HomeFeed.__testEngagement.STALE_MS - 60_000,
  });
  const rotated = HomeFeed.applyTileRotation(feed, input, now);
  const next = rotated.sections[0].cards[0];
  assert.notEqual(next.id, 'pl-pl-1');
  assert.equal(next.kind, 'TopMixes');
  assert.ok(['pl-pl-2', 'pl-pl-3'].includes(next.id));
});

test('compose includes explore theme for matching playlist', () => {
  const HomeFeed = loadHomeFeed();
  const feed = HomeFeed.compose({
    history: [],
    analytics: null,
    allPlaylists: [
      { id: 'fr-1', name: 'French Favorites', trackCount: 42 },
      { id: 'it-1', name: 'Italian Classics', trackCount: 30 },
    ],
    smartPlaylists: [],
    favorites: [],
    dashboard: null,
    libraryGenres: [],
    shuffleSeed: 1,
  });
  const explore = feed.sections.find((s) => s.kind === 'ExploreThemes');
  assert.ok(explore);
  assert.ok(explore.cards.length > 0);
});
