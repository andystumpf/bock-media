import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test('webPlayback exposes repeat controls', () => {
  const src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'webPlayback.js'), 'utf8');
  assert.match(src, /setRepeat/);
  assert.match(src, /cycleRepeat/);
  assert.match(src, /repeat: 'off'/);
});

test('shortcuts module binds keyboard handlers', () => {
  const src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'shortcuts.js'), 'utf8');
  assert.match(src, /addEventListener\('keydown'/);
  assert.match(src, /cycleRepeat/);
});

test('context menu module exports track menu', () => {
  const src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'contextMenu.js'), 'utf8');
  assert.match(src, /showTrack/);
  assert.match(src, /showAlbum/);
});
