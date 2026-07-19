#!/usr/bin/env node
/**
 * Regenerate README screenshots (img/screenshots/) from the generated demo
 * dataset (demo-data/, gitignored).
 *
 *   npm install && npx playwright install chromium
 *   node scripts/capture_readme_screenshots.mjs [--port 3033]
 *
 * Seeds a realistic demo library (real artists/albums so album art resolves
 * via the iTunes Search API), boots the server against it, and captures every
 * console page. Repo-root runtime state files (streaming_history.jsonl,
 * nowplaying_state.json, queues.json) are backed up and restored afterwards.
 */
import { chromium } from 'playwright';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import http from 'node:http';

const REPO = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(REPO, 'img', 'screenshots');
const DEMO = process.env.OURMEDIA_DEMO_DIR || path.join(REPO, 'demo-data');
const port = Number(process.argv.find((a, i) => process.argv[i - 1] === '--port') || 3033);
const base = `http://127.0.0.1:${port}`;

const py = fs.existsSync(path.join(REPO, '.venv/bin/python'))
  ? path.join(REPO, '.venv/bin/python')
  : 'python3';

// The iTunes artwork fallback needs working TLS; the python.org macOS build
// ships without system certs, so point urllib at certifi when available.
function sslCertFile() {
  const r = spawnSync(py, ['-c', 'import certifi; print(certifi.where())'], { encoding: 'utf8' });
  return r.status === 0 ? r.stdout.trim() : undefined;
}

const PUBLIC_MUSIC_ROOT = '/Users/Shared/bock-media/music';
const serverEnv = {
  ...process.env,
  PORT: String(port),
  OURMEDIA_DATA_DIR: DEMO,
  OURMEDIA_DB_PATH: path.join(DEMO, 'music_organizer.db'),
  // Prefer the public-safe symlink so Watch Folders never shows a home path.
  OURMEDIA_MUSIC_ROOT: fs.existsSync(PUBLIC_MUSIC_ROOT) ? PUBLIC_MUSIC_ROOT : path.join(DEMO, 'music'),
};
const cert = sslCertFile();
if (cert) serverEnv.SSL_CERT_FILE = cert;

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
    '--write-audio',
    '--skip-uitest-playlist',
  ], { cwd: REPO, stdio: 'inherit' });
  if (r.status !== 0) throw new Error('seed_demo_data.py failed');
}

// server.py reads these runtime state files from the repo root.
const ROOT_STATE = ['streaming_history.jsonl', 'nowplaying_state.json', 'queues.json'];

function swapInDemoState() {
  const backups = {};
  for (const name of ROOT_STATE) {
    const rootPath = path.join(REPO, name);
    backups[name] = fs.existsSync(rootPath) ? fs.readFileSync(rootPath) : null;
    const demoPath = path.join(DEMO, name);
    if (fs.existsSync(demoPath)) fs.copyFileSync(demoPath, rootPath);
    else if (name === 'queues.json') fs.writeFileSync(rootPath, '{}');
  }
  return backups;
}

function restoreRootState(backups) {
  for (const name of ROOT_STATE) {
    const rootPath = path.join(REPO, name);
    if (backups[name] === null) fs.rmSync(rootPath, { force: true });
    else fs.writeFileSync(rootPath, backups[name]);
  }
}

// Demo alexaRemote creds have no live Amazon session — hide the expired-session
// banner (and the Settings security note it triggers) so captures show the
// normal steady state.
const HIDE_CSS = '.global-alert{display:none !important}.settings-security-warn{display:none !important}';

async function shot(page, hash, file, waitMs = 5000) {
  await page.goto(`${base}/#${hash}`, { waitUntil: 'domcontentloaded' });
  await page.addStyleTag({ content: HIDE_CSS }).catch(() => {});
  await page.waitForTimeout(waitMs);
  await page.waitForSelector('#content, .page-title, .spotify-home', { timeout: 15000 }).catch(() => {});
  await page.screenshot({ path: path.join(OUT, file), fullPage: true });
  console.log('  ', file);
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  console.log('Seeding demo data…');
  seedDemo();
  const backups = swapInDemoState();

  let child = null;
  try {
    try {
      await waitForServer(1500);
    } catch {
      child = spawn(py, ['server.py'], { cwd: REPO, env: serverEnv, stdio: 'ignore' });
      await waitForServer();
    }

    const browser = await chromium.launch();
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await ctx.newPage();

    // Warmup: visit artwork-heavy pages once so the iTunes art cache fills
    // before the real captures (first resolve takes a few seconds per album).
    console.log('Warming artwork cache…');
    for (const hash of ['dashboard', 'library', 'playlists', 'albums', 'artists', 'songs', 'nowplaying']) {
      await page.goto(`${base}/#${hash}`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(6000);
    }

    console.log('Capturing pages…');
    await shot(page, 'dashboard', 'dashboard.png', 8000);
    await shot(page, 'nowplaying', 'nowplaying.png', 6000);
    await shot(page, 'analytics', 'analytics.png', 7000);
    await shot(page, 'playlists', 'playlists.png', 6000);
    await shot(page, 'songs', 'songs.png');
    await shot(page, 'artists', 'artists.png', 6000);
    await shot(page, 'albums', 'albums.png', 6000);
    await shot(page, 'devices', 'devices.png');
    await shot(page, 'routines', 'routines.png');
    await shot(page, 'watchfolders', 'watchfolders.png');
    await shot(page, 'settings', 'settings.png');
    await shot(page, 'family', 'family.png');

    // "Your Library" restores the last-viewed tab per client — use a fresh
    // context so it opens on the default All view.
    {
      const libCtx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      const libPage = await libCtx.newPage();
      await shot(libPage, 'library', 'library.png', 7000);
      await libCtx.close();
    }

    // Search results (type a query into the topbar search)
    await page.goto(`${base}/#search`, { waitUntil: 'domcontentloaded' });
    await page.addStyleTag({ content: HIDE_CSS }).catch(() => {});
    await page.waitForTimeout(2500);
    const searchBox = page.locator('#topbar-search-q');
    if (await searchBox.count()) {
      await searchBox.fill('fleetwood');
      await searchBox.press('Enter');
      await page.waitForTimeout(5000);
      // Close the suggestion dropdown so it doesn't linger in the capture.
      await page.addStyleTag({ content: '.search-suggest-dropdown{display:none !important}' }).catch(() => {});
      await page.screenshot({ path: path.join(OUT, 'search.png'), fullPage: true });
      console.log('   search.png');
    }

    await ctx.close();
    await browser.close();
    if (child) child.kill('SIGTERM');
    child = null;

    // Automation sub-shots (form + list with alexaRemote)
    spawnSync('node', [path.join(REPO, 'scripts', 'capture_automation_screenshots.mjs'), '--port', String(port)], {
      cwd: REPO,
      stdio: 'inherit',
      env: serverEnv,
    });
  } finally {
    if (child) child.kill('SIGTERM');
    restoreRootState(backups);
    console.log('Restored repo-root state files.');
  }

  console.log('Done →', OUT);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
