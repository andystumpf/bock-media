import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.join(__dirname, '..', '..');

test('shell css defines 3-column layout tokens', () => {
  const css = readFileSync(path.join(REPO, 'public', 'css', 'shell.css'), 'utf8');
  assert.match(css, /--right-panel-w/);
  assert.match(css, /\.spotify-right-panel/);
  assert.match(css, /grid-template-areas:[\s\S]*sidebar main right/);
});

test('index html includes new shell modules', () => {
  const html = readFileSync(path.join(REPO, 'public', 'index.html'), 'utf8');
  assert.match(html, /shellLayout\.js/);
  assert.match(html, /rightPanel\.js/);
  assert.match(html, /artistPage\.js/);
  assert.match(html, /albumPage\.js/);
  assert.match(html, /searchSuggest\.js/);
  assert.match(html, /spotify-right-panel/);
  assert.match(html, /profile-dropdown/);
});
