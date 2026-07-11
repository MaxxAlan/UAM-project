const CACHE_NAME = 'uam-offline-cache-v2';
const TILE_CACHE  = 'offline-map-tiles';
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/css/style.css',
  '/js/app.js',
  'https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;700&family=Orbitron:wght@400;700;900&display=swap',
  'https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'
];

// Install: pre-cache critical shell assets
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      console.log('[Service Worker] Pre-caching static assets');
      return cache.addAll(STATIC_ASSETS);
    })
  );
  self.skipWaiting();
});

// Activate: clear old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME && key !== TILE_CACHE && !key.startsWith('uam-api-')) {
            console.log('[Service Worker] Deleting old cache:', key);
            return caches.delete(key);
          }
        })
      );
    })
  );
  self.clients.claim();
});

// Fetch: intercept requests
self.addEventListener('fetch', (event) => {
  const requestUrl = new URL(event.request.url);

  // Strategy for Map Tiles (CartoDB) — cache-first, network fallback
  if (requestUrl.host.includes('basemaps.cartocdn.com') || requestUrl.host.includes('tile.openstreetmap.org')) {
    event.respondWith(
      caches.open(TILE_CACHE).then((cache) => {
        return cache.match(event.request).then((cachedResponse) => {
          // Return cached (even opaque) response immediately if available
          if (cachedResponse) {
            return cachedResponse;
          }
          // Network-first: fetch with no-cors to allow cross-origin caching
          return fetch(event.request, { mode: 'no-cors' }).then((networkResponse) => {
            // Cache opaque responses (status 0) too — they're valid for offline use
            cache.put(event.request, networkResponse.clone());
            return networkResponse;
          }).catch(() => {
            // Offline fallback: return transparent 1x1 PNG
            const transparentPixel = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==';
            const bytes = Uint8Array.from(atob(transparentPixel), c => c.charCodeAt(0));
            return new Response(bytes, { status: 200, headers: { 'Content-Type': 'image/png' } });
          });
        });
      })
    );
    return;
  }

  // Strategy for API calls (Network-first, fallback to cache/offline response)
  if (requestUrl.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          // Cache successful API responses
          if (response.status === 200) {
            const responseClone = response.clone();
            caches.open('uam-api-cache').then((cache) => {
              cache.put(event.request, responseClone);
            });
          }
          return response;
        })
        .catch(() => {
          // If offline, serve from cache
          return caches.match(event.request).then((cachedResponse) => {
            if (cachedResponse) {
              return cachedResponse;
            }
            // Fallback response for offline API failures
            return new Response(JSON.stringify({
              status: "OFFLINE_FALLBACK",
              message: "Đang chạy ở chế độ ngoại tuyến không có dữ liệu cache."
            }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' }
            });
          });
        })
    );
    return;
  }

  // Default Stale-While-Revalidate strategy for static resources
  event.respondWith(
    caches.match(event.request).then((cachedResponse) => {
      const fetchPromise = fetch(event.request).then((networkResponse) => {
        if (networkResponse && networkResponse.status === 200) {
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, networkResponse.clone());
          });
        }
        return networkResponse;
      }).catch(() => {
        // Silent catch for network failure when offline
      });

      return cachedResponse || fetchPromise;
    })
  );
});
