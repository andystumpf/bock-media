import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test('artist page module registers render API', () => {
  const src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'artistPage.js'), 'utf8');
  assert.match(src, /ArtistPage/);
  assert.match(src, /\/api\/artists\//);
  assert.match(src, /Popular/);
});

test('album page module registers render API', () => {
  const src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'albumPage.js'), 'utf8');
  assert.match(src, /AlbumPage/);
  assert.match(src, /music-video\/check/);
  assert.match(src, /More by/);
});
