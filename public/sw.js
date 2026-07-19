/* Bock Media shell + artwork cache — API responses are never cached here. */
const SHELL = ['/', '/index.html', '/css/style.css', '/css/shell.css?v=35', '/css/dark-theme.css?v=6', '/js/app.js?v=89', '/js/artCache.js?v=1', '/js/boot.js?v=6', '/js/homeFeed.js?v=6', '/js/webPlayback.js?v=9', '/js/shellLayout.js?v=4', '/js/rightPanel.js?v=1', '/js/artistPage.js?v=2', '/js/albumPage.js?v=2', '/js/searchSuggest.js?v=1', '/js/contextMenu.js?v=1', '/js/shortcuts.js?v=1', '/js/webMusicVideo.js?v=4', '/js/webLyrics.js?v=2', '/js/clientPrefsSync.js?v=9', '/manifest.json'];
const CACHE = 'bockmedia-shell-v17';
const ART_CACHE = 'bockmedia-art-v1';

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
