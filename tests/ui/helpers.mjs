// Shared bootstrap for jsdom UI tests — mirrors public/index.html shell.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.join(__dirname, '..', '..');

export function readWebCacheJs() {
  return readFileSync(path.join(REPO, 'public', 'js', 'webCache.js'), 'utf8');
}

export function readHomeFeedJs() {
  return readFileSync(path.join(REPO, 'public', 'js', 'homeFeed.js'), 'utf8');
}

export function readAppJs() {
  let src = readFileSync(path.join(REPO, 'public', 'js', 'app.js'), 'utf8');
  return src.replace(/\binit\(\);?\s*$/m, '');
}

export function shellHtml(extra = '') {
  return `<!doctype html><html><head></head><body>
    <div id="app-shell" class="spotify-app">
      <aside class="spotify-sidebar">
        <a href="#dashboard" class="sidebar-nav-item" data-tab="home"><span>Home</span></a>
        <a href="#search" class="sidebar-nav-item" data-tab="search"><span>Search</span></a>
        <a href="#library" class="sidebar-nav-item" data-tab="library"><span>Library</span></a>
      </aside>
      <aside id="app-drawer" class="app-drawer"><nav class="drawer-nav">
        <a href="#devices" class="drawer-link">Devices</a>
        <a href="#family" class="drawer-link">Family</a>
        <a href="#analytics" class="drawer-link">Analytics</a>
        <a href="#settings" class="drawer-link">Settings</a>
      </nav></aside>
      <div id="app-backdrop" class="app-backdrop hidden"></div>
      <div class="spotify-main-column">
        <div id="global-banner"></div>
        <span id="page-title" hidden></span>
        <main id="main-content" class="spotify-main-view page-content"></main>
      </div>
      <footer id="now-playing-bar" class="spotify-player hidden">
        <span id="np-track-text" class="player-track-name"></span>
        <span id="np-artist-text" class="player-track-artist"></span>
        <img id="np-art" alt="" hidden>
        <div class="player-art-fallback"></div>
        <button id="np-mini-play"></button>
        <button id="np-bar-prev"></button>
        <button id="np-bar-next"></button>
        <button id="np-bar-shuffle"></button>
        <button id="np-bar-stop"></button>
        <button id="np-bar-favorite"></button>
        <button id="np-bar-sleep"></button>
        <span id="np-bar-time-curr"></span>
        <span id="np-bar-time-dur"></span>
        <div id="np-bar-progress-fill"></div>
        <div id="np-bar-volume-wrap"><input id="np-bar-volume" class="np-volume-slider"></div>
      </footer>
      <nav id="bottom-nav" class="bottom-nav">
        <a href="#dashboard" class="bottom-nav-link" data-tab="home"><span class="bottom-nav-label">Home</span></a>
        <a href="#search" class="bottom-nav-link" data-tab="search"><span class="bottom-nav-label">Search</span></a>
        <a href="#library" class="bottom-nav-link" data-tab="library"><span class="bottom-nav-label">Library</span></a>
        <a href="#automation" class="bottom-nav-link" data-tab="automations"><span class="bottom-nav-label">Automations</span></a>
      </nav>
    </div>
    <button id="account-menu-btn"></button>
    ${extra}
  </body></html>`;
}

export function bootstrap({ fetchImpl, htmlExtra = '' } = {}) {
  const dom = new JSDOM(shellHtml(htmlExtra), { url: 'http://localhost/', runScripts: 'dangerously' });
  const { window } = dom;
  window.fetch = fetchImpl || (async () => ({ ok: true, json: async () => ({}) }));
  window.confirm = () => true;
  window.alert = () => {};
  window.setInterval = () => 0;
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });

  const cacheScript = window.document.createElement('script');
  cacheScript.textContent = readWebCacheJs();
  window.document.body.appendChild(cacheScript);

  const feedScript = window.document.createElement('script');
  feedScript.textContent = readHomeFeedJs();
  window.document.body.appendChild(feedScript);

  const script = window.document.createElement('script');
  script.textContent = readAppJs();
  window.document.body.appendChild(script);
  return { window, document: window.document, dom };
}

export function bottomTabForRoute(route) {
  const root = (route || 'dashboard').split('/')[0];
  if (['dashboard', 'nowplaying', 'rooms', 'analytics', 'routines'].includes(root)) return 'home';
  if (root === 'search') return 'search';
  if (['library', 'playlists', 'artists', 'albums', 'songs', 'watchfolders', 'genres'].includes(root)) return 'library';
  if (['automation'].includes(root)) return 'automations';
  return null;
}
