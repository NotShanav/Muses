// sw.js - caches only the app shell (this page's own HTML, CSS, JS, icons, manifest). It never touches
// /api/* (audio, art, JSON, events) or /pair. Pages are fetched network-first so the server can renew
// the session cookie on every page load; the cache is only the fallback when the server is unreachable.
// Bump VERSION on every change to the web UI: a new version installs in the background and the page
// offers "Reload" (see js/pwa.js).
const VERSION = 'muses-web-5.0.0';
const SHELL = [
  '/',
  '/app.css',
  '/app.js',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  '/icons/icon-64.png',
  '/index.html',
  '/js/api.js',
  '/js/boot.js',
  '/js/dom.js',
  '/js/events.js',
  '/js/mediasession.js',
  '/js/mini.js',
  '/js/pages/home.js',
  '/js/pages/library.js',
  '/js/pages/now.js',
  '/js/pages/playlist.js',
  '/js/pages/search.js',
  '/js/pages/settings.js',
  '/js/player.js',
  '/js/pwa.js',
  '/js/router.js',
  '/js/searchbox.js',
  '/js/theme.js',
  '/js/ui.js',
  '/js/util.js',
  '/js/voice-core.js',
  '/js/voice-worklet.js',
  '/js/voice.js',
  '/manifest.webmanifest'
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(VERSION).then((cache) => cache.addAll(SHELL)));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('muses-web-') && k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('message', (event) => {
  if (event.data === 'skip') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || req.headers.has('range')) return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname === '/pair') return;
  event.respondWith(networkFirst(req));
});

async function networkFirst(req) {
  try {
    const res = await fetch(req);
    if (res.status === 200) {
      const copy = res.clone();
      caches.open(VERSION).then((cache) => cache.put(req, copy)).catch(() => {});
    }
    return res;
  } catch (err) {
    const hit = await caches.match(req, { ignoreSearch: true });
    if (hit) return hit;
    if (req.mode === 'navigate') {
      const shell = await caches.match('/');
      if (shell) return shell;
    }
    throw err;
  }
}
