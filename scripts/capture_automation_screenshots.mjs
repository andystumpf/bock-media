#!/usr/bin/env node
/**
 * Regenerate Automation README screenshots from the demo dataset.
 *
 *   node scripts/capture_automation_screenshots.mjs [--port 3033]
 *
 * Expects demo data already seeded (see README Screenshots). Starts the server
 * if nothing is listening on the port.
 */
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
import http from 'node:http';

const REPO = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(REPO, 'img', 'screenshots');
const DATA_DIR = process.env.OURMEDIA_DATA_DIR || path.join(REPO, 'fixtures', 'demo-data');
const port = Number(process.argv.find((a, i) => process.argv[i - 1] === '--port') || 3033);
const base = `http://127.0.0.1:${port}`;

function waitForServer(ms = 20000) {
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

async function main() {
  fs.mkdirSync(OUT, { recursive: true });

  let child = null;
  try {
    await waitForServer(1500);
  } catch {
    const py = fs.existsSync(path.join(REPO, '.venv/bin/python'))
      ? path.join(REPO, '.venv/bin/python')
      : 'python3';
    child = spawn(py, ['server.py'], {
      cwd: REPO,
      env: {
        ...process.env,
        PORT: String(port),
        OURMEDIA_DATA_DIR: DATA_DIR,
        OURMEDIA_DB_PATH: path.join(DATA_DIR, 'music_organizer.db'),
        OURMEDIA_MUSIC_ROOT: path.join(DATA_DIR, 'music'),
      },
      stdio: 'ignore',
    });
    await waitForServer();
  }

  const cfgPath = path.join(DATA_DIR, 'config.json');
  const cfgBackup = fs.existsSync(cfgPath) ? fs.readFileSync(cfgPath, 'utf8') : null;
  const browser = await chromium.launch();

  async function freshPage() {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await ctx.newPage();
    // Hide the expired-Alexa-session banner (demo creds have no live session).
    await page.addInitScript(() => {
      const style = document.createElement('style');
      style.textContent = '.global-alert{display:none !important}';
      document.addEventListener('DOMContentLoaded', () => document.head.appendChild(style));
    });
    return { ctx, page };
  }

  // Setup required (no alexaRemote; hide seeded automations for empty state)
  const autoPath = path.join(DATA_DIR, 'automations.json');
  const autoBackup = fs.existsSync(autoPath) ? fs.readFileSync(autoPath, 'utf8') : null;
  if (autoBackup) fs.unlinkSync(autoPath);
  if (cfgBackup) {
    const cfg = JSON.parse(cfgBackup);
    delete cfg.alexaRemote;
    fs.writeFileSync(cfgPath, JSON.stringify(cfg, null, 2));
  }
  {
    const { ctx, page } = await freshPage();
    await page.goto(`${base}/#automation`, { waitUntil: 'networkidle' });
    await page.waitForSelector('.page-desc');
    await page.screenshot({ path: path.join(OUT, 'automation-setup.png'), fullPage: true });
    await ctx.close();
  }
  if (autoBackup) fs.writeFileSync(autoPath, autoBackup);
  if (cfgBackup) fs.writeFileSync(cfgPath, cfgBackup);

  // Full page with create form + scheduled list (fresh context avoids cached remote status)
  const { ctx, page } = await freshPage();
  await page.goto(`${base}/#automation`, { waitUntil: 'networkidle' });
  await page.waitForSelector('.auto-form-grid', { timeout: 15000 });
  await page.screenshot({ path: path.join(OUT, 'automation.png'), fullPage: true });

  const form = page.locator('.auto-form-grid').first();
  await form.screenshot({ path: path.join(OUT, 'automation-new.png') });
  await page.locator('#auto-pl-search').fill('morning');
  if (await page.waitForSelector('.auto-pl-results button', { timeout: 8000 }).catch(() => null)) {
    await form.screenshot({ path: path.join(OUT, 'automation-create.png') });
  }

  const table = page.locator('.automation-table');
  await table.screenshot({ path: path.join(OUT, 'automation-list.png') });
  await ctx.close();

  await browser.close();
  if (child) child.kill('SIGTERM');

  console.log('Wrote screenshots to', OUT);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
