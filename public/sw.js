/* Bock Media shell + artwork cache — API responses are never cached here. */
const CACHE = 'bockmedia-shell-v10';
const ART_CACHE = 'bockmedia-art-v1';
const SHELL = ['/', '/index.html', '/css/style.css', '/css/shell.css?v=27', '/js/app.js?v=81', '/js/artCache.js?v=1', '/js/boot.js?v=4', '/js/homeFeed.js?v=4', '/js/clientPrefsSync.js?v=6', '/manifest.json'];

function artworkCacheKey(url) {
  const u = new URL(url);
  if (!u.pathname.startsWith('/artwork/')) return url;
  const size = u.searchParams.get('size');
  return `${u.origin}${u.pathname}${size ? `?size=${size}` : ''}`;
}

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE && k !== ART_CACHE).map((k) => caches.delete(k))),
    ).then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.pathname.startsWith('/artwork/') && e.request.method === 'GET') {
    const key = artworkCacheKey(e.request.url);
    const cacheReq = new Request(key, { credentials: 'same-origin' });
    e.respondWith(
      caches.open(ART_CACHE).then(async (cache) => {
        const cached = await cache.match(cacheReq);
        const network = fetch(e.request).then((resp) => {
          if (resp.ok) cache.put(cacheReq, resp.clone());
          return resp;
        }).catch(() => null);
        return cached || network || cache.match(cacheReq) || fetch(e.request);
      }),
    );
    return;
  }
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/stream/')) {
    return;
  }
  if (e.request.method !== 'GET') return;
  // Network-first so the shell revalidates on every load; cache is only a fallback.
  e.respondWith(
    fetch(e.request).then((resp) => {
      if (resp.ok && url.origin === self.location.origin) {
        const copy = resp.clone();
        caches.open(CACHE).then((cache) => cache.put(e.request, copy));
      }
      return resp;
    }).catch(() => caches.match(e.request)),
  );
});
