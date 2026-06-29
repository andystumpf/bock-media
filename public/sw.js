/* Bock Media shell cache — API responses are never cached here. */
const CACHE = 'bockmedia-shell-v9';
const SHELL = ['/', '/index.html', '/css/style.css', '/css/shell.css?v=24', '/js/app.js?v=70', '/js/boot.js?v=4', '/js/homeFeed.js?v=4', '/manifest.json'];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))),
    ).then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/stream/') || url.pathname.startsWith('/artwork/')) {
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
