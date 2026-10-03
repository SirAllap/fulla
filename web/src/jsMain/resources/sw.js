// SPDX-License-Identifier: GPL-3.0-or-later
//
// Makes Fulla work without a connection: every file of the app is kept as it
// was last fetched, and used when the network is not there. Online, the
// network answers first, so a new version arrives the next time the page opens.
// Nothing outside this site is ever fetched or kept.

const CACHE = 'fulla-shell';
const SHELL = ['./', 'index.html', 'fulla.js', 'styles.css', 'archivo.woff2', 'manifest.webmanifest', 'icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request).then((hit) => hit || caches.match('index.html'))),
  );
});
