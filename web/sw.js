// Service worker: cache-first cho vỏ ứng dụng để chạy offline. Đổi VERSION khi sửa file để làm mới cache.
const VERSION = 'pulsemaster-v1';
const ASSETS = [
  './', './index.html', './style.css', './manifest.webmanifest',
  './js/core.js', './js/app.js', './js/haptic.js', './js/webble.js',
  './data/tcm_28_pulses_haptic.json',
  './icons/icon-192.png', './icons/icon-512.png', './icons/maskable-512.png', './icons/icon.svg',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((res) => {
    if (res.ok && new URL(e.request.url).origin === location.origin) { const copy = res.clone(); caches.open(VERSION).then((c) => c.put(e.request, copy)); }
    return res;
  }).catch(() => caches.match('./index.html'))));
});
