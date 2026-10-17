/**
 * Service worker: makes the app installable and lets it open without a
 * connection. The coach itself needs the network, so the strategy is
 * network first, with the cached copy as the fallback.
 */

const CACHE = 'coach-v1';

const SHELL = [
  './',
  'index.html',
  'styles.css',
  'manifest.webmanifest',
  'js/app.js',
  'js/claude.js',
  'js/coach.js',
  'js/config.js',
  'js/crypto.js',
  'js/data.js',
  'js/format.js',
  'js/github.js',
  'js/markdown.js',
  'js/nutrition.js',
  'js/store.js',
  'js/ui.js',
  'icons/icon-192.png',
  'icons/apple-touch-icon.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith('coach-') && key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  // Claude, GitHub and the CDN are never served from this cache.
  if (new URL(request.url).origin !== self.location.origin) return;

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached ?? caches.match('index.html'))),
  );
});
