/**
 * Artwork blob cache — fast repeat paints across routes (Now Playing, home, library).
 * Keys are canonical /artwork/ paths (no HMAC sig) so signed LAN URLs still dedupe.
 */
(function (root) {
  'use strict';

  const CACHE_NAME = 'bockmedia-art-v1';
  const MEM = new Map();
  const INFLIGHT = new Map();

  function pathKey(filepath, sizePx) {
    if (!filepath) return '';
    const rel = String(filepath).replace(/^\/+/, '');
    const encoded = rel.split('/').map((seg) => encodeURIComponent(seg)).join('/');
    const q = sizePx ? `?size=${sizePx}` : '';
    return `/artwork/${encoded}${q}`;
  }

  function peek(key) {
    return key ? (MEM.get(key) || null) : null;
  }

  function canonicalRequest(key) {
    return new Request(key, { credentials: 'same-origin' });
  }

  function upgradeDom(key, blobUrl) {
    if (!key || !blobUrl) return;
    document.querySelectorAll('img[data-art-key]').forEach((img) => {
      if (img.dataset.artKey === key && img.src !== blobUrl) img.src = blobUrl;
    });
  }

  function applyNetworkUrl(key, url) {
    upgradeDom(key, url);
    prefetch(key, url).catch(() => {});
  }

  async function store(key, response) {
    const blob = await response.blob();
    const prev = MEM.get(key);
    if (prev && prev.startsWith('blob:')) URL.revokeObjectURL(prev);
    const blobUrl = URL.createObjectURL(blob);
    MEM.set(key, blobUrl);
    try {
      const cache = await caches.open(CACHE_NAME);
      await cache.put(canonicalRequest(key), new Response(blob, {
        headers: { 'Content-Type': response.headers.get('Content-Type') || 'image/jpeg' },
      }));
    } catch { /* quota / private mode */ }
    return blobUrl;
  }

  async function loadFromCacheApi(key) {
    try {
      const cache = await caches.open(CACHE_NAME);
      const resp = await cache.match(canonicalRequest(key));
      if (!resp) return null;
      return store(key, resp);
    } catch {
      return null;
    }
  }

  async function prefetch(key, fetchUrl) {
    if (!key) return null;
    const hit = MEM.get(key);
    if (hit) return hit;
    if (INFLIGHT.has(key)) return INFLIGHT.get(key);
    const job = (async () => {
      const fromDisk = await loadFromCacheApi(key);
      if (fromDisk) return fromDisk;
      const url = fetchUrl || key;
      const resp = await fetch(url, { credentials: 'same-origin' });
      if (!resp.ok) throw new Error(`art ${resp.status}`);
      return store(key, resp);
    })().finally(() => INFLIGHT.delete(key));
    INFLIGHT.set(key, job);
    return job;
  }

  async function warmPaths(filepaths, sizes) {
    const sizeList = sizes && sizes.length ? sizes : [undefined];
    const keys = new Set();
    for (const fp of (filepaths || []).filter(Boolean)) {
      for (const sz of sizeList) {
        keys.add(pathKey(fp, sz));
      }
    }
    const list = [...keys].slice(0, 96);
    await Promise.all(list.map((k) => prefetch(k).catch(() => null)));
  }

  async function resolveUrl(key, signFn) {
    if (!key) return null;
    const cached = peek(key);
    if (cached) return cached;
    let fetchUrl = key;
    if (typeof signFn === 'function') {
      fetchUrl = await signFn(key);
      if (!fetchUrl) return null;
    }
    try {
      return await prefetch(key, fetchUrl);
    } catch {
      return fetchUrl;
    }
  }

  root.ArtCache = {
    CACHE_NAME,
    pathKey,
    peek,
    prefetch,
    warmPaths,
    resolveUrl,
    upgradeDom,
    applyNetworkUrl,
  };
})(typeof globalThis !== 'undefined' ? globalThis : window);
