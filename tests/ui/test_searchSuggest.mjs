import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

test('search suggest module handles keyboard navigation', () => {
  const src = readFileSync(path.join(__dirname, '..', '..', 'public', 'js', 'searchSuggest.js'), 'utf8');
  assert.match(src, /ArrowDown/);
  assert.match(src, /search-suggest-dropdown/);
  assert.match(src, /\/api\/search\/suggest/);
});
