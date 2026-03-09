var CACHE_NAME = 'geovault-static-v1'

var STATIC_ASSETS = [
    '/favicon.ico',
    '/images/logo.svg',
    '/apple-touch-icon.png',
    '/pwa-72x72.png',
    '/pwa-96x96.png',
    '/pwa-128x128.png',
    '/pwa-144x144.png',
    '/pwa-192x192.png',
    '/pwa-384x384.png',
    '/pwa-512x512.png',
    '/maskable-icon-72x72.png',
    '/maskable-icon-96x96.png',
    '/maskable-icon-128x128.png',
    '/maskable-icon-144x144.png',
    '/maskable-icon-192x192.png',
    '/maskable-icon-384x384.png',
    '/maskable-icon-512x512.png',
    '/manifest.webmanifest'
]

self.addEventListener('install', function (event) {
    event.waitUntil(
        caches.open(CACHE_NAME).then(function (cache) {
            return cache.addAll(STATIC_ASSETS)
        })
    )
    self.skipWaiting()
})

self.addEventListener('activate', function (event) {
    event.waitUntil(
        caches.keys().then(function (names) {
            return Promise.all(
                names.filter(function (n) { return n !== CACHE_NAME })
                    .map(function (n) { return caches.delete(n) })
            )
        }).then(function () {
            return self.clients.claim()
        })
    )
})

self.addEventListener('fetch', function (event) {
    if (event.request.method !== 'GET') return

    var url = new URL(event.request.url)
    if (url.origin !== self.location.origin) return
    if (STATIC_ASSETS.indexOf(url.pathname) === -1) return

    event.respondWith(
        caches.match(event.request).then(function (cached) {
            return cached || fetch(event.request)
        })
    )
})
