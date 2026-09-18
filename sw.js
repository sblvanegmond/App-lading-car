/**
 * Service worker: makes the app installable and usable without a connection,
 * and shows the morning briefing where the platform allows it.
 */

const CACHE = 'laadmoment-v2';

const SHELL = [
  './',
  'index.html',
  'styles.css',
  'manifest.webmanifest',
  'js/app.js',
  'js/api.js',
  'js/config.js',
  'js/ics.js',
  'js/briefing.js',
  'js/planner.js',
  'js/pricing.js',
  'js/solar.js',
  'js/ui.js',
  'icons/icon-192.png',
  'icons/icon-512.png',
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
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  // Prices and weather must never come from the cache: the whole point is
  // fresh numbers. The app itself keeps a timestamped copy in localStorage.
  if (url.origin !== self.location.origin) return;

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(() => cached);
      // Cache first for a snappy start, refresh in the background.
      return cached ?? network;
    }),
  );
});

/**
 * A real push message from the notifier. This is what makes the morning
 * briefing arrive without opening the app, on Android and on an iPhone where
 * the app sits on the home screen.
 */
self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = { body: event.data ? event.data.text() : '' };
  }

  const title = payload.title || 'Laadmoment';
  const body = payload.body || 'Open de app voor het laadplan van vandaag.';

  event.waitUntil(
    self.registration.showNotification(title, {
      body,
      icon: 'icons/icon-192.png',
      badge: 'icons/icon-192.png',
      tag: payload.tag || 'laadmoment-ochtend',
      // Replacing an earlier briefing should not buzz a second time.
      renotify: false,
      data: { url: payload.url || './' },
    }),
  );
});

/**
 * Android with periodic background sync can wake the app up. Everywhere else
 * this never fires, which is why the app also shows the briefing on open and
 * offers a calendar event with an alarm.
 */
self.addEventListener('periodicsync', (event) => {
  if (event.tag !== 'ochtendplan') return;
  event.waitUntil(
    self.registration.showNotification('Laadmoment', {
      body: 'Open de app voor het beste laadmoment van vandaag.',
      icon: 'icons/icon-192.png',
      badge: 'icons/icon-192.png',
      tag: 'laadmoment-ochtend',
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = event.notification.data?.url || './';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ('focus' in client) return client.focus();
      }
      return self.clients.openWindow(target);
    }),
  );
});
