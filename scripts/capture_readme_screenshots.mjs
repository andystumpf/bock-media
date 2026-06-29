#!/usr/bin/env node
/**
 * Regenerate README screenshots (img/screenshots/) from fixtures/demo-data.
 *
 *   node scripts/capture_readme_screenshots.mjs [--port 3033]
 */
import { chromium } from 'playwright';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import http from 'node:http';

const REPO = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(REPO, 'img', 'screenshots');
const DEMO = path.join(REPO, 'fixtures', 'demo-data');
const port = Number(process.argv.find((a, i) => process.argv[i - 1] === '--port') || 3033);
const base = `http://127.0.0.1:${port}`;

const py = fs.existsSync(path.join(REPO, '.venv/bin/python'))
  ? path.join(REPO, '.venv/bin/python')
  : 'python3';

function waitForServer(ms = 30000) {
  const deadline = Date.now() + ms;
  return new Promise((resolve, reject) => {
    const tick = () => {
      http.get(`${base}/api/summary`, (res) => {
        res.resume();
        if (res.statusCode === 200) resolve();
        else if (Date.now() > deadline) reject(new Error('server timeout'));
        else setTimeout(tick, 300);
      }).on('error', () => {
        if (Date.now() > deadline) reject(new Error('server timeout'));
        else setTimeout(tick, 300);
      });
    };
    tick();
  });
}

function seedDemo() {
  const r = spawnSync(py, [
    path.join(REPO, 'scripts', 'seed_demo_data.py'),
    '--base', DEMO,
    '--state-dir', DEMO,
    '--config',
    '--alexa-remote',
  ], { cwd: REPO, stdio: 'inherit' });
  if (r.status !== 0) throw new Error('seed_demo_data.py failed');
  // server.py reads streaming history from repo root
  const hist = path.join(DEMO, 'streaming_history.jsonl');
  if (fs.existsSync(hist)) {
    fs.copyFileSync(hist, path.join(REPO, 'streaming_history.jsonl'));
  }
}

async function shot(page, hash, file, waitMs = 5000) {
  await page.goto(`${base}/#${hash}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(waitMs);
  await page.waitForSelector('#content, .page-title, .spotify-home', { timeout: 15000 }).catch(() => {});
  await page.screenshot({ path: path.join(OUT, file), fullPage: true });
  console.log('  ', file);
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  console.log('Seeding demo data…');
  seedDemo();

  let child = null;
  try {
    await waitForServer(1500);
  } catch {
    child = spawn(py, ['server.py'], {
      cwd: REPO,
      env: {
        ...process.env,
        PORT: String(port),
        OURMEDIA_DATA_DIR: DEMO,
        OURMEDIA_DB_PATH: path.join(DEMO, 'music_organizer.db'),
        OURMEDIA_MUSIC_ROOT: path.join(DEMO, 'music'),
      },
      stdio: 'ignore',
    });
    await waitForServer();
  }

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();

  console.log('Capturing pages…');
  await shot(page, 'dashboard', 'dashboard.png', 6500);
  await shot(page, 'nowplaying', 'nowplaying.png');
  await shot(page, 'analytics', 'analytics.png', 7000);
  await shot(page, 'playlists', 'playlists.png');
  await shot(page, 'songs', 'songs.png');
  await shot(page, 'artists', 'artists.png');
  await shot(page, 'albums', 'albums.png');
  await shot(page, 'devices', 'devices.png');
  await shot(page, 'routines', 'routines.png');
  await shot(page, 'watchfolders', 'watchfolders.png');
  await shot(page, 'settings', 'settings.png');
  await shot(page, 'family', 'family.png');
  await shot(page, 'library', 'library.png');

  await ctx.close();
  await browser.close();
  if (child) child.kill('SIGTERM');

  // Automation sub-shots (form + list with alexaRemote)
  spawnSync('node', [path.join(REPO, 'scripts', 'capture_automation_screenshots.mjs'), '--port', String(port)], {
    cwd: REPO,
    stdio: 'inherit',
    env: {
      ...process.env,
      OURMEDIA_DATA_DIR: DEMO,
      OURMEDIA_DB_PATH: path.join(DEMO, 'music_organizer.db'),
      OURMEDIA_MUSIC_ROOT: path.join(DEMO, 'music'),
    },
  });

  console.log('Done →', OUT);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
