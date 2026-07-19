#!/usr/bin/env node
/**
 * Snapshot the current home playlist categorization to ~/.bockmedia/home_defaults.json
 * on the server (via SSH) or locally (--local-out).
 *
 * Usage:
 *   node scripts/snapshot_home_defaults.mjs
 *   BASE=http://your-server.local:5000 node scripts/snapshot_home_defaults.mjs
 *   node scripts/snapshot_home_defaults.mjs --local-out /tmp/home_defaults.json
 */
import { createRequire } from 'module';
import { readFileSync, writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
import { spawnSync } from 'child_process';

const require = createRequire(import.meta.url);
const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');

const BASE = (process.env.BASE || 'http://127.0.0.1:5000').replace(/\/$/, '');
const localOut = process.argv.includes('--local-out')
  ? process.argv[process.argv.indexOf('--local-out') + 1]
  : null;
const NAS = process.env.NAS || 'user@your-server.local';
const DATA_REMOTE = process.env.DATA_REMOTE || '~/.bockmedia';

function loadMobileToken() {
  if (process.env.BOCK_MOBILE_TOKEN) return process.env.BOCK_MOBILE_TOKEN.trim();
  try {
    const cfg = JSON.parse(readFileSync(join(ROOT, 'config.json'), 'utf8'));
    return ((cfg.mobileApi || {}).token || '').trim();
  } catch {
    return '';
  }
}

const MOBILE_TOKEN = loadMobileToken();

async function fetchJson(path) {
  const headers = {};
  if (MOBILE_TOKEN) headers['X-BockMedia-Token'] = MOBILE_TOKEN;
  const res = await fetch(`${BASE}${path}`, { headers });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json();
}

function dayOfYear() {
  const now = new Date();
  const start = new Date(now.getFullYear(), 0, 0);
  return Math.floor((now - start) / 86400000);
}

function loadHomeFeed() {
  const src = readFileSync(join(ROOT, 'public/js/homeFeed.js'), 'utf8');
  const sandbox = { console, Math, Date, Set, Map, RegExp };
  const fn = new Function('root', `${src}\nreturn root.HomeFeed;`);
  const g = typeof globalThis !== 'undefined' ? globalThis : {};
  fn(g);
  return g.HomeFeed;
}

function sectionPinsFromFeed(feed) {
  const pins = [];
  const now = Date.now();
  for (const sec of feed.sections || []) {
    for (const card of sec.cards || []) {
      const pt = card.playTarget || {};
      const pid = card.playlistId || (pt.kind === 'playlist' ? pt.id : null);
      if (!pid) continue;
      pins.push({
        sectionId: sec.id,
        playlistId: pid,
        playlistName: card.title || '',
        pinnedAtMs: now,
      });
    }
  }
  return pins;
}

async function main() {
  const HomeFeed = loadHomeFeed();
  const [home, playlists, analytics] = await Promise.all([
    fetchJson('/api/home?deferred=1&playlistLimit=2000&genreLimit=80&historyLimit=150'),
    fetchJson('/api/playlists?page=1&limit=2000&fields=summary&inlineCovers=0'),
    fetchJson('/api/analytics').catch(() => null),
  ]);

  const allPlaylists = playlists.items || (home.playlists && home.playlists.items) || [];
  const genres = (home.genres && home.genres.items) || [];
  const dashboard = home.dashboard || { favorites: [] };
  const shuffleSeed = dayOfYear();

  const feed = HomeFeed.compose({
    history: (home.history && home.history.items) || [],
    analytics,
    allPlaylists,
    smartPlaylists: (home.smartPlaylists && home.smartPlaylists.items) || [],
    favorites: dashboard.favorites || [],
    dashboard,
    libraryGenres: genres,
    shuffleSeed,
    continueResume: home.continue && home.continue.resume,
    releaseRadarLabel: null,
    releaseRadarArtPath: null,
    discoverWeeklyCards: [],
    ratedSongItems: [],
  });

  const payload = {
    version: 1,
    savedAt: new Date().toISOString(),
    policy: {
      playlistsScope: 'household',
      playlistLimit: 2000,
      genreLimit: 80,
    },
    sectionPins: sectionPinsFromFeed(feed),
  };

  if (localOut) {
    writeFileSync(localOut, JSON.stringify(payload, null, 2));
    console.log(`Wrote ${localOut} (${payload.sectionPins.length} pins, ${feed.sections.length} sections)`);
    return;
  }

  const tmp = '/tmp/home_defaults.json';
  writeFileSync(tmp, JSON.stringify(payload, null, 2));
  const remotePath = `${DATA_REMOTE}/home_defaults.json`;
  const scp = spawnSync('scp', [tmp, `${NAS}:${remotePath}`], { stdio: 'inherit' });
  if (scp.status !== 0) process.exit(scp.status || 1);
  console.log(`Saved home defaults to ${NAS}:${remotePath}`);
  console.log(`  sections: ${feed.sections.length}, pins: ${payload.sectionPins.length}`);
  console.log(`  playlists in catalog: ${allPlaylists.length}, genres: ${genres.length}`);
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
