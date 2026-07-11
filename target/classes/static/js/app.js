// UAM Drone Path Control & Safety System
// Frontend Script - Ho Chi Minh City Area

let map;
let startMarker = null;
let endMarker = null;
let pathLine = null;
let activePickMode = null; // 'start' or 'end'
let currentRoutePoints = [];

// Drone Simulation Variables
let simMarker = null;
let simInterval = null;
let isSimulating = false;

// Default API Base URL (dynamic helper for dev/prod)
const API_BASE = window.location.origin;

const CITIES = {
    hcm: { name: "TP. Hồ Chí Minh", center: [10.7769, 106.7009], zoom: 15 },
    hanoi: { name: "Hà Nội", center: [21.0285, 105.8542], zoom: 15 },
    danang: { name: "Đà Nẵng", center: [16.0544, 108.2022], zoom: 15 },
    singapore: { name: "Singapore", center: [1.3521, 103.8198], zoom: 14 },
    tokyo: { name: "Tokyo", center: [35.6762, 139.6503], zoom: 14 },
    newyork: { name: "New York", center: [40.7128, -74.0060], zoom: 14 }
};

// Initialize App on DOM Loaded
document.addEventListener('DOMContentLoaded', () => {
    initMap();
    setupEventHandlers();
    loadNFZsAndBuildings();
    updateOnlineStatus();
    window.addEventListener('online', updateOnlineStatus);
    window.addEventListener('offline', updateOnlineStatus);
    
    // Load initial weather for default center HCMC
    fetchRealWeather(10.7769, 106.7009);
});

// 1. Initialize Map
function initMap() {
    // Center of District 1, HCMC
    const hcmCenter = [10.7769, 106.7009];
    map = L.map('map', {
        zoomControl: false,
        attributionControl: false
    }).setView(hcmCenter, 15);

    // CartoDB Dark Matter tile provider (Premium look dark mode)
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
        maxZoom: 20
    }).addTo(map);

    // Add zoom control in top right
    L.control.zoom({ position: 'topright' }).addTo(map);

    // Click handler for coordinates picking
    map.on('click', (e) => {
        if (activePickMode === 'start') {
            setStartPoint(e.latlng.lat, e.latlng.lng);
            setActivePickMode(null);
        } else if (activePickMode === 'end') {
            setEndPoint(e.latlng.lat, e.latlng.lng);
            setActivePickMode(null);
        }
    });

    // Reload buildings, NFZs, and live weather when the user moves or zooms the map
    map.on('moveend', () => {
        loadNFZsAndBuildings();
        const center = map.getCenter();
        fetchRealWeather(center.lat, center.lng);
    });
}

// 2. Fetch and Render Obstacles (Buildings & No-Fly Zones)
function loadNFZsAndBuildings() {
    if (!map) return;
    
    // Load No-Fly Zones
    fetch(`${API_BASE}/api/nfzs`)
        .then(res => res.json())
        .then(nfzs => {
            if (window.nfzLayers) {
                window.nfzLayers.forEach(l => map.removeLayer(l));
            }
            window.nfzLayers = [];
            nfzs.forEach(nfz => {
                const circle = L.circle([nfz.centerLatitude, nfz.centerLongitude], {
                    radius: nfz.radiusMeters,
                    color: '#ff3366',
                    fillColor: '#ff3366',
                    fillOpacity: 0.25,
                    weight: 2
                })
                .bindTooltip(`<strong>VÙNG CẤM BAY (NFZ):</strong><br>${nfz.name}<br>Trần bay cấm: ${nfz.maxAltitude}m AGL`, { permanent: false, direction: 'top' })
                .addTo(map);
                window.nfzLayers.push(circle);
            });
        })
        .catch(err => console.error("Không thể tải vùng cấm bay:", err));

    // Get current view bounds
    const bounds = map.getBounds();
    const southWest = bounds.getSouthWest();
    const northEast = bounds.getNorthEast();

    // Load Buildings
    const renderBuildings = (buildings) => {
        if (window.buildingLayers) {
            window.buildingLayers.forEach(l => map.removeLayer(l));
        }
        window.buildingLayers = [];
        
        buildings.forEach(building => {
            const latlngs = building.polygon.map(pt => [pt.latitude, pt.longitude]);
            const color = building.height > 100 ? '#ffaa00' : '#0088ff';
            const fillOpacity = building.height > 100 ? 0.4 : 0.25;

            const poly = L.polygon(latlngs, {
                color: color,
                fillColor: color,
                fillOpacity: fillOpacity,
                weight: 1.5
            })
            .bindTooltip(`<strong>${building.name}</strong><br>Độ cao: ${building.height}m<br>Độ cao an toàn bay: ${Math.round(building.height + 15)}m`, { sticky: true })
            .addTo(map);
            window.buildingLayers.push(poly);
        });
    };

    // Attempt to load offline cache buildings first if offline
    if (!navigator.onLine) {
        const cached = localStorage.getItem('offline_buildings_data');
        if (cached) {
            try {
                const buildings = JSON.parse(cached);
                const filtered = buildings.filter(b => {
                    if (!b.polygon || b.polygon.length === 0) return false;
                    const firstPt = b.polygon[0];
                    return bounds.contains([firstPt.latitude, firstPt.longitude]);
                });
                console.log(`Loaded ${filtered.length} filtered buildings from offline cache.`);
                renderBuildings(filtered);
                return;
            } catch(e) {}
        }
    }

    const url = `${API_BASE}/api/buildings?minLat=${southWest.lat}&minLon=${southWest.lng}&maxLat=${northEast.lat}&maxLon=${northEast.lng}`;
    fetch(url)
        .then(res => res.json())
        .then(buildings => {
            renderBuildings(buildings);
            
            // Cache loaded buildings to local storage so they are always available as fallback
            let existing = [];
            const cached = localStorage.getItem('offline_buildings_data');
            if (cached) {
                try { existing = JSON.parse(cached); } catch(e) {}
            }
            const merged = [...existing];
            const existingIds = new Set(existing.map(b => b.id));
            buildings.forEach(b => {
                if (!existingIds.has(b.id)) {
                    merged.push(b);
                }
            });
            localStorage.setItem('offline_buildings_data', JSON.stringify(merged));
        })
        .catch(err => {
            console.error("Không thể tải thông tin tòa nhà trực tuyến, thử offline:", err);
            const cached = localStorage.getItem('offline_buildings_data');
            if (cached) {
                try {
                    const buildings = JSON.parse(cached);
                    const filtered = buildings.filter(b => {
                        if (!b.polygon || b.polygon.length === 0) return false;
                        const firstPt = b.polygon[0];
                        return bounds.contains([firstPt.latitude, firstPt.longitude]);
                    });
                    renderBuildings(filtered);
                } catch(e) {}
            }
        });
}

// 3. Coordinate Picker Logic
function setActivePickMode(mode) {
    activePickMode = mode;
    
    // Reset button states
    document.getElementById('btn-pick-start').classList.remove('active');
    document.getElementById('btn-pick-end').classList.remove('active');
    
    if (mode === 'start') {
        document.getElementById('btn-pick-start').classList.add('active');
        document.getElementById('map').style.cursor = 'crosshair';
    } else if (mode === 'end') {
        document.getElementById('btn-pick-end').classList.add('active');
        document.getElementById('map').style.cursor = 'crosshair';
    } else {
        document.getElementById('map').style.cursor = '';
    }
}

function setStartPoint(lat, lon) {
    const formatted = `${lat.toFixed(6)}, ${lon.toFixed(6)}`;
    document.getElementById('start-coords').value = formatted;
    
    if (startMarker) {
        startMarker.setLatLng([lat, lon]);
    } else {
        const startIcon = L.divIcon({
            className: 'start-marker-icon',
            html: '<i class="fa-solid fa-location-crosshairs" style="color: #00ffcc; font-size: 24px; text-shadow: 0 0 8px #00ffcc;"></i>',
            iconSize: [24, 24],
            iconAnchor: [12, 12]
        });
        startMarker = L.marker([lat, lon], { icon: startIcon }).addTo(map);
    }
    
    // Fill telemetry initially
    document.getElementById('tele-lat').innerText = lat.toFixed(6);
    document.getElementById('tele-lon').innerText = lon.toFixed(6);
}

function setEndPoint(lat, lon) {
    const formatted = `${lat.toFixed(6)}, ${lon.toFixed(6)}`;
    document.getElementById('end-coords').value = formatted;
    
    if (endMarker) {
        endMarker.setLatLng([lat, lon]);
    } else {
        const endIcon = L.divIcon({
            className: 'end-marker-icon',
            html: '<i class="fa-solid fa-flag-checkered" style="color: #ff3366; font-size: 24px; text-shadow: 0 0 8px #ff3366;"></i>',
            iconSize: [24, 24],
            iconAnchor: [12, 12]
        });
        endMarker = L.marker([lat, lon], { icon: endIcon }).addTo(map);
    }
}

// 4. Calculate Safety Route API Call
function generateRoute() {
    if (!startMarker || !endMarker) {
        alert("Vui lòng thiết lập cả điểm xuất phát và điểm đến bằng cách nhấn vào nút tọa độ.");
        return;
    }

    const startLatLng = startMarker.getLatLng();
    const endLatLng = endMarker.getLatLng();
    const targetAlt = parseFloat(document.getElementById('target-altitude').value);

    const payload = {
        start: {
            latitude: startLatLng.lat,
            longitude: startLatLng.lng,
            altitude: targetAlt
        },
        end: {
            latitude: endLatLng.lat,
            longitude: endLatLng.lng,
            altitude: targetAlt
        },
        windSpeed: window.currentLiveWindSpeed !== undefined ? window.currentLiveWindSpeed : null,
        windDirection: window.currentLiveWindDirection !== undefined ? window.currentLiveWindDirection : null
    };

    // Show loading state
    const btn = document.getElementById('btn-generate-route');
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> ĐANG TÍNH TOÁN...';
    btn.disabled = true;

    fetch(`${API_BASE}/api/route`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    })
    .then(res => res.json())
    .then(data => {
        btn.innerHTML = '<i class="fa-solid fa-sliders"></i> TÍNH TOÁN ĐƯỜNG BAY AN TOÀN';
        btn.disabled = false;

        if (data.status === "SUCCESS") {
            currentRoutePoints = data.path;
            
            // Draw path line with glowing animated dash array
            if (pathLine) {
                map.removeLayer(pathLine);
            }

            const latlngs = currentRoutePoints.map(pt => [pt.latitude, pt.longitude]);
            pathLine = L.polyline(latlngs, {
                color: '#39ff14',
                weight: 4,
                opacity: 0.8,
                dashArray: '10, 8',
                lineJoin: 'round'
            }).addTo(map);

            // Shift dash array offset to simulate motion flow!
            let offset = 0;
            const animateDash = () => {
                if (!pathLine) return;
                offset = (offset - 1) % 18;
                pathLine.setStyle({ dashOffset: offset.toString() });
                requestAnimationFrame(animateDash);
            };
            animateDash();

            // Fit map bounds to show route
            map.fitBounds(pathLine.getBounds(), { padding: [50, 50] });

            // Update Telemetry Panel
            document.getElementById('tele-wind-speed').innerText = `${data.windSpeed} m/s`;
            document.getElementById('tele-wind-dir').innerText = `${data.windDirection}°`;
            document.getElementById('tele-gps-dop').innerText = `± ${data.gpsDop} m`;
            
            // Format distance (m or km)
            const dist = data.totalDistance;
            const distText = dist >= 1000 ? `${(dist / 1000).toFixed(2)} km` : `${Math.round(dist)} m`;
            document.getElementById('tele-distance').innerText = distText;

            // Format ETA (duration)
            const durationSec = data.totalDuration;
            const min = Math.floor(durationSec / 60);
            const sec = Math.round(durationSec % 60);
            const etaText = min > 0 ? `${min}m ${sec}s` : `${sec}s`;
            document.getElementById('tele-eta').innerText = etaText;

            // Average Speed
            document.getElementById('tele-avg-speed').innerText = `${data.averageSpeed} m/s`;

            document.getElementById('hud-weather').innerText = `${data.weatherCondition} | Gió ${data.windSpeed}m/s`;

            // Enable action buttons
            document.getElementById('btn-simulate').disabled = false;
            document.getElementById('btn-export-plan').disabled = false;
            document.getElementById('btn-export-kml').disabled = false;

            // Reset simulation if running
            stopSimulation();
        } else {
            showToast('⚠️ ' + (data.message || 'Lỗi tính toán đường bay. Thử lại!'), 'error');
        }
    })
    .catch(err => {
        btn.innerHTML = '<i class="fa-solid fa-sliders"></i> TÍNH TOÁN ĐƯỜNG BAY AN TOÀN';
        btn.disabled = false;
        showToast('❌ Lỗi kết nối server. Kiểm tra Spring Boot đang chạy chưa!', 'error');
        console.error(err);
    });
}

// 5. Real-time Telemetry Flight Simulation
function toggleSimulation() {
    if (isSimulating) {
        stopSimulation();
    } else {
        startSimulation();
    }
}

function startSimulation() {
    if (currentRoutePoints.length === 0) return;
    
    isSimulating = true;
    const btn = document.getElementById('btn-simulate');
    btn.innerHTML = '<i class="fa-solid fa-stop"></i> DỪNG MÔ PHỎNG';
    btn.classList.add('active');

    // Setup animated drone marker — SVG directional triangle
    const droneIcon = L.divIcon({
        className: 'drone-pulse-marker',
        html: `<svg id="drone-svg-icon" xmlns="http://www.w3.org/2000/svg" width="36" height="36" viewBox="0 0 36 36">
                 <defs>
                   <filter id="glow">
                     <feGaussianBlur stdDeviation="2.5" result="blur"/>
                     <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
                   </filter>
                 </defs>
                 <!-- Outer glow ring -->
                 <circle cx="18" cy="18" r="16" fill="none" stroke="#00ffcc" stroke-width="1"
                         stroke-dasharray="4 3" opacity="0.5">
                   <animateTransform attributeName="transform" type="rotate"
                     from="0 18 18" to="360 18 18" dur="3s" repeatCount="indefinite"/>
                 </circle>
                 <!-- Triangle arrow pointing up (heading 0° = North) -->
                 <polygon points="18,4 30,30 18,24 6,30"
                          fill="#00ffcc" stroke="#003322" stroke-width="1.2"
                          filter="url(#glow)" opacity="0.95"/>
                 <!-- Center dot -->
                 <circle cx="18" cy="18" r="2.5" fill="#ffffff" opacity="0.9"/>
               </svg>`,
        iconSize: [36, 36],
        iconAnchor: [18, 18]
    });

    if (simMarker) map.removeLayer(simMarker);
    
    const startPt = currentRoutePoints[0];
    simMarker = L.marker([startPt.latitude, startPt.longitude], { icon: droneIcon }).addTo(map);

    let idx = 0;
    simInterval = setInterval(() => {
        if (idx >= currentRoutePoints.length) {
            stopSimulation();
            showToast('✅ Mô phỏng bay hoàn thành! Drone đã cập bến an toàn.', 'success');
            return;
        }

        const pt = currentRoutePoints[idx];
        simMarker.setLatLng([pt.latitude, pt.longitude]);
        map.panTo([pt.latitude, pt.longitude]);

        // Rotate the SVG triangle to face current heading
        const svgEl = simMarker.getElement()?.querySelector('svg polygon');
        if (svgEl) {
            const heading = pt.heading || 0;
            svgEl.style.transformOrigin = '18px 18px';
            svgEl.style.transform = `rotate(${heading}deg)`;
        }

        // Update real-time Telemetry Panels
        document.getElementById('tele-lat').innerText = pt.latitude.toFixed(6);
        document.getElementById('tele-lon').innerText = pt.longitude.toFixed(6);
        document.getElementById('tele-alt').innerText = `${Math.round(pt.altitude)} m AGL`;
        document.getElementById('tele-heading').innerText = `${Math.round(pt.heading)}°`;
        
        idx++;
    }, 400); // Step every 400ms
}

function stopSimulation() {
    isSimulating = false;
    const btn = document.getElementById('btn-simulate');
    if (btn) {
        btn.innerHTML = '<i class="fa-solid fa-play"></i> MÔ PHỎNG BAY AUTO';
        btn.classList.remove('active');
    }
    
    if (simInterval) {
        clearInterval(simInterval);
        simInterval = null;
    }
    if (simMarker) {
        map.removeLayer(simMarker);
        simMarker = null;
    }
}

// 6. Drone Waypoint File Exporter API Call
function exportPlan(format) {
    if (currentRoutePoints.length === 0) {
        showToast('⚠️ Chưa có đường bay. Hãy tính toán đường bay trước!', 'warning');
        return;
    }

    const btnId = format === 'kml' ? 'btn-export-kml' : 'btn-export-plan';
    const btn = document.getElementById(btnId);
    const originalHtml = btn.innerHTML;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang xuất...';
    btn.disabled = true;

    // Ensure all points have only the fields FlightPoint Java model expects
    const sanitizedPoints = currentRoutePoints.map(pt => ({
        latitude:  pt.latitude  || 0,
        longitude: pt.longitude || 0,
        altitude:  pt.altitude  || 50,
        heading:   pt.heading   || 0,
        speed:     pt.speed     || 5
    }));

    fetch(`${API_BASE}/api/export?format=${format}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(sanitizedPoints)
    })
    .then(res => {
        if (res.ok) return res.blob();
        return res.text().then(txt => { throw new Error(txt || 'Server error ' + res.status); });
    })
    .then(blob => {
        const fileName = format === 'kml' ? 'drone_route_hcm.kml' : 'drone_mission_hcm.plan';
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
        showToast(`✅ Đã xuất ${fileName} thành công!`, 'success');
    })
    .catch(err => {
        console.error('Export error:', err);
        showToast(`❌ Lỗi xuất file: ${err.message}`, 'error');
    })
    .finally(() => {
        btn.innerHTML = originalHtml;
        btn.disabled = false;
    });
}

// Toast notification helper
function showToast(message, type = 'info') {
    const existing = document.getElementById('uam-toast');
    if (existing) existing.remove();

    const colors = {
        success: { bg: 'rgba(57,255,20,0.15)',  border: 'rgba(57,255,20,0.4)',  text: '#39ff14' },
        error:   { bg: 'rgba(255,60,60,0.15)',  border: 'rgba(255,60,60,0.4)',  text: '#ff6b6b' },
        warning: { bg: 'rgba(255,170,0,0.15)',  border: 'rgba(255,170,0,0.4)',  text: '#ffaa00' },
        info:    { bg: 'rgba(0,200,255,0.15)',  border: 'rgba(0,200,255,0.4)',  text: '#00c8ff' }
    };
    const c = colors[type] || colors.info;
    const toast = document.createElement('div');
    toast.id = 'uam-toast';
    toast.style.cssText = [
        'position:fixed', 'bottom:80px', 'right:24px', 'z-index:99999',
        `background:${c.bg}`, `border:1px solid ${c.border}`, `color:${c.text}`,
        'padding:12px 18px', 'border-radius:8px', 'font-size:0.82rem',
        'font-family:var(--font-body)', 'max-width:320px', 'backdrop-filter:blur(8px)',
        'box-shadow:0 4px 20px rgba(0,0,0,0.4)', 'transition:opacity 0.4s ease',
        'letter-spacing:0.02em', 'line-height:1.4'
    ].join(';');
    toast.innerText = message;
    document.body.appendChild(toast);
    setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 400); }, 3500);
}

// 7. Event Handlers Binding
function setupEventHandlers() {
    document.getElementById('btn-pick-start').addEventListener('click', () => setActivePickMode('start'));
    document.getElementById('btn-pick-end').addEventListener('click', () => setActivePickMode('end'));
    document.getElementById('btn-generate-route').addEventListener('click', generateRoute);
    document.getElementById('btn-simulate').addEventListener('click', toggleSimulation);
    document.getElementById('btn-export-plan').addEventListener('click', () => exportPlan('plan'));
    document.getElementById('btn-export-kml').addEventListener('click', () => exportPlan('kml'));
    
    // Offline Downloader events
    document.getElementById('btn-draw-bbox').addEventListener('click', toggleBboxDrawing);
    document.getElementById('btn-start-download').addEventListener('click', startOfflineDownload);

    // Map Search listeners
    const searchInput = document.getElementById('map-search-input');
    const clearSearchBtn = document.getElementById('btn-clear-search');
    
    if (searchInput && clearSearchBtn) {
        searchInput.addEventListener('input', debounce((e) => {
            performSearch(e.target.value);
        }, 300));
        
        clearSearchBtn.addEventListener('click', () => {
            searchInput.value = '';
            document.getElementById('search-results').classList.add('hidden');
            clearSearchBtn.style.display = 'none';
            if (searchMarker) {
                map.removeLayer(searchMarker);
                searchMarker = null;
            }
        });
    }
    
    document.addEventListener('click', (e) => {
        const results = document.getElementById('search-results');
        if (results && !e.target.closest('.map-search-container')) {
            results.classList.add('hidden');
        }
    });
    
    // Setup manual coordinates input parsing
    setupCoordinateInputs();

    // City Selector change listener
    const citySelector = document.getElementById('city-selector');
    if (citySelector) {
        citySelector.addEventListener('change', (e) => {
            const cityKey = e.target.value;
            const city = CITIES[cityKey];
            if (city) {
                map.flyTo(city.center, city.zoom);
            }
        });
    }
}

// 8. Online/Offline UI Synchronization
function updateOnlineStatus() {
    const badge = document.querySelector('.status-badge');
    const headerStatus = document.getElementById('hud-weather');
    if (navigator.onLine) {
        badge.innerHTML = '<span class="dot"></span>ONLINE';
        badge.style.color = 'var(--color-green)';
        const dot = badge.querySelector('.dot');
        if (dot) {
            dot.style.backgroundColor = 'var(--color-green)';
            dot.style.boxShadow = '0 0 8px var(--color-green)';
        }
    } else {
        badge.innerHTML = '<span class="dot" style="background-color: var(--color-red); box-shadow: 0 0 8px var(--color-red);"></span>OFFLINE MODE';
        badge.style.color = 'var(--color-red)';
        if (headerStatus) {
            headerStatus.innerText = "Ngoại tuyến (Offline)";
        }
    }
}

// 9. Bounding Box Drawing Controls
let selectionRectangle = null;
let selectionPoints = [];
let isDrawingBbox = false;

function toggleBboxDrawing() {
    if (isDrawingBbox) {
        cancelBboxDrawing();
    } else {
        startBboxDrawing();
    }
}

function startBboxDrawing() {
    isDrawingBbox = true;
    selectionPoints = [];
    if (selectionRectangle) {
        map.removeLayer(selectionRectangle);
        selectionRectangle = null;
    }
    
    document.getElementById('btn-draw-bbox').innerHTML = '<i class="fa-solid fa-xmark"></i> Hủy chọn';
    document.getElementById('btn-draw-bbox').classList.add('active');
    document.getElementById('map').style.cursor = 'cell';
    document.getElementById('btn-start-download').disabled = true;
    
    map.on('click', onBboxMapClick);
    map.on('mousemove', onBboxMapMouseMove);
}

function cancelBboxDrawing() {
    isDrawingBbox = false;
    document.getElementById('btn-draw-bbox').innerHTML = '<i class="fa-solid fa-vector-square"></i> Chọn vùng tải';
    document.getElementById('btn-draw-bbox').classList.remove('active');
    document.getElementById('map').style.cursor = '';
    
    map.off('click', onBboxMapClick);
    map.off('mousemove', onBboxMapMouseMove);
    
    if (selectionRectangle) {
        map.removeLayer(selectionRectangle);
        selectionRectangle = null;
    }
    selectionPoints = [];
    document.getElementById('btn-start-download').disabled = true;
}

function onBboxMapClick(e) {
    if (selectionPoints.length === 0) {
        selectionPoints.push(e.latlng);
        selectionRectangle = L.rectangle([e.latlng, e.latlng], {
            color: '#00ffcc',
            weight: 2,
            fillColor: '#00ffcc',
            fillOpacity: 0.15,
            dashArray: '5, 5'
        }).addTo(map);
    } else {
        selectionPoints.push(e.latlng);
        const bounds = L.latLngBounds(selectionPoints[0], selectionPoints[1]);
        selectionRectangle.setBounds(bounds);
        selectionRectangle.setStyle({ dashArray: null, color: '#39ff14', fillOpacity: 0.2 });
        
        isDrawingBbox = false;
        document.getElementById('btn-draw-bbox').innerHTML = '<i class="fa-solid fa-vector-square"></i> Chọn vùng tải';
        document.getElementById('btn-draw-bbox').classList.remove('active');
        document.getElementById('map').style.cursor = '';
        
        map.off('click', onBboxMapClick);
        map.off('mousemove', onBboxMapMouseMove);
        
        document.getElementById('btn-start-download').disabled = false;
    }
}

function onBboxMapMouseMove(e) {
    if (selectionPoints.length === 1 && selectionRectangle) {
        selectionRectangle.setBounds(L.latLngBounds(selectionPoints[0], e.latlng));
    }
}

// 10. Slippy Map Tile Math & Downloader
function lon2tile(lon, zoom) {
    return Math.floor((lon + 180) / 360 * Math.pow(2, zoom));
}

function lat2tile(lat, zoom) {
    return Math.floor((1 - Math.log(Math.tan(lat * Math.PI / 180) + 1 / Math.cos(lat * Math.PI / 180)) / Math.PI) / 2 * Math.pow(2, zoom));
}

async function startOfflineDownload() {
    if (!selectionRectangle) return;
    
    const bounds = selectionRectangle.getBounds();
    const southWest = bounds.getSouthWest();
    const northEast = bounds.getNorthEast();

    const progressContainer = document.getElementById('download-progress-container');
    const progressBar = document.getElementById('download-progress-bar');
    const statusText = document.getElementById('download-status-text');
    const percentText = document.getElementById('download-percent-text');
    
    progressContainer.classList.remove('hidden');
    progressBar.style.width = '0%';
    statusText.innerText = 'Đang tính toán mảnh bản đồ...';
    percentText.innerText = '0%';
    
    document.getElementById('btn-start-download').disabled = true;
    document.getElementById('btn-draw-bbox').disabled = true;
    
    // Build Tile list (Zoom 14 to 17) — one URL per tile, random subdomain
    const urlsToDownload = [];
    const minZoom = 14;
    const maxZoom = 17;
    const SUBDOMAINS = ['a', 'b', 'c'];

    for (let z = minZoom; z <= maxZoom; z++) {
        const xMin = lon2tile(southWest.lng, z);
        const xMax = lon2tile(northEast.lng, z);
        const yMin = lat2tile(northEast.lat, z);
        const yMax = lat2tile(southWest.lat, z);

        const xStart = Math.min(xMin, xMax);
        const xEnd   = Math.max(xMin, xMax);
        const yStart = Math.min(yMin, yMax);
        const yEnd   = Math.max(yMin, yMax);

        for (let x = xStart; x <= xEnd; x++) {
            for (let y = yStart; y <= yEnd; y++) {
                // Use one subdomain per tile (load-balance randomly, no duplicates)
                const sub = SUBDOMAINS[(x + y) % 3];
                urlsToDownload.push(`https://${sub}.basemaps.cartocdn.com/dark_all/${z}/${x}/${y}.png`);
            }
        }
    }

    // Safety: warn if tile count is huge
    if (urlsToDownload.length > 3000) {
        showToast(`⚠️ Vùng quá lớn (${urlsToDownload.length} mảnh). Chọn vùng nhỏ hơn để tải nhanh hơn.`, 'warning');
    }
    
    // Download buildings first
    statusText.innerText = 'Tải dữ liệu tòa nhà...';
    try {
        const bRes = await fetch(`${API_BASE}/api/buildings?minLat=${southWest.lat}&minLon=${southWest.lng}&maxLat=${northEast.lat}&maxLon=${northEast.lng}`);
        if (bRes.ok) {
            const buildings = await bRes.json();
            let existing = [];
            const cached = localStorage.getItem('offline_buildings_data');
            if (cached) {
                try { existing = JSON.parse(cached); } catch(e) {}
            }
            const merged = [...existing];
            const existingIds = new Set(existing.map(b => b.id));
            buildings.forEach(b => {
                if (!existingIds.has(b.id)) {
                    merged.push(b);
                }
            });
            localStorage.setItem('offline_buildings_data', JSON.stringify(merged));
        }
    } catch (e) {
        console.warn("Lỗi tải tòa nhà offline:", e);
    }
    
    // Cache map tiles
    if (urlsToDownload.length === 0) {
        statusText.innerText = 'Không tìm thấy mảnh bản đồ nào!';
        document.getElementById('btn-draw-bbox').disabled = false;
        return;
    }
    
    const cache = await caches.open('offline-map-tiles');
    let completedCount = 0;
    let successCount = 0;
    const totalTiles = urlsToDownload.length;
    const batchSize = 10;

    for (let i = 0; i < totalTiles; i += batchSize) {
        const batch = urlsToDownload.slice(i, i + batchSize);
        await Promise.all(batch.map(async (url) => {
            try {
                const response = await fetch(url, { mode: 'no-cors' });
                // opaque response (status 0, type 'opaque') is still cacheable
                await cache.put(url, response);
                successCount++;
            } catch (err) { /* skip failed tiles silently */ }
            completedCount++;
        }));

        const percent = Math.min(Math.round((completedCount / totalTiles) * 100), 100);
        progressBar.style.width = `${percent}%`;
        percentText.innerText = `${percent}%`;
        statusText.innerText = `Đang tải: ${completedCount}/${totalTiles} | OK: ${successCount}`;
        await new Promise(r => setTimeout(r, 20));

    statusText.innerText = `✅ Tải xong ${successCount}/${totalTiles} mảnh bản đồ!`;

    percentText.innerText = '100%';
    progressBar.style.width = '100%';
    
    document.getElementById('btn-draw-bbox').disabled = false;
    progressBar.style.background = 'var(--color-green)';
    
    setTimeout(() => {
        progressContainer.classList.add('hidden');
        progressBar.style.background = '';
        cancelBboxDrawing();
        // Force refresh to reload the map offline features
        loadNFZsAndBuildings();
    }, 2500);
}

// 11. Debounce helper
function debounce(func, wait) {
    let timeout;
    return function(...args) {
        clearTimeout(timeout);
        timeout = setTimeout(() => func.apply(this, args), wait);
    };
}

// 12. Address & Landmark Geocoding Search
let searchMarker = null;

async function performSearch(query) {
    const resultsDiv = document.getElementById('search-results');
    const clearBtn = document.getElementById('btn-clear-search');
    
    if (!resultsDiv || !clearBtn) return;
    
    if (!query || query.trim() === '') {
        resultsDiv.classList.add('hidden');
        clearBtn.style.display = 'none';
        return;
    }
    
    clearBtn.style.display = 'block';
    
    let searchQuery = query;
    if (!query.toLowerCase().includes('ho chi minh') && !query.toLowerCase().includes('hcm')) {
        searchQuery = query + ", Ho Chi Minh City";
    }
    
    try {
        const url = `https://nominatim.openstreetmap.org/search?format=json&q=${encodeURIComponent(searchQuery)}&viewbox=106.55,10.65,106.85,10.90&bounded=1&limit=5`;
        const res = await fetch(url, {
            headers: { 'Accept-Language': 'vi,en' }
        });
        
        if (!res.ok) throw new Error("Search API error");
        
        const data = await res.json();
        renderSearchResults(data);
    } catch (err) {
        console.error("Geocoding failed:", err);
    }
}

function renderSearchResults(results) {
    const resultsDiv = document.getElementById('search-results');
    if (!resultsDiv) return;
    resultsDiv.innerHTML = '';
    
    if (results.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'search-result-item';
        empty.innerText = 'Không tìm thấy địa điểm nào.';
        resultsDiv.appendChild(empty);
        resultsDiv.classList.remove('hidden');
        return;
    }
    
    results.forEach(item => {
        const row = document.createElement('div');
        row.className = 'search-result-item';
        const displayName = item.display_name.split(',')[0] + (item.display_name.split(',')[1] ? ', ' + item.display_name.split(',')[1] : '');
        row.innerText = displayName;
        row.title = item.display_name;
        
        row.addEventListener('click', () => {
            selectSearchResult(item);
        });
        resultsDiv.appendChild(row);
    });
    
    resultsDiv.classList.remove('hidden');
}

function selectSearchResult(item) {
    const resultsDiv = document.getElementById('search-results');
    if (resultsDiv) resultsDiv.classList.add('hidden');
    document.getElementById('map-search-input').value = item.display_name.split(',')[0];
    
    const lat = parseFloat(item.lat);
    const lon = parseFloat(item.lon);
    
    map.flyTo([lat, lon], 16);
    
    if (searchMarker) {
        map.removeLayer(searchMarker);
    }
    
    const searchIcon = L.divIcon({
        className: 'search-marker-icon',
        html: '<i class="fa-solid fa-location-dot" style="color: #ffaa00; font-size: 28px; text-shadow: 0 0 8px #ffaa00;"></i>',
        iconSize: [28, 28],
        iconAnchor: [14, 28]
    });
    
    const popupContent = `
        <div style="font-family: var(--font-body); font-size: 0.85rem; padding: 5px; color: var(--color-text-primary);">
            <strong style="color: var(--color-accent);">${item.display_name.split(',')[0]}</strong><br>
            <span style="font-size: 0.75rem; color: var(--color-text-secondary); display: block; margin-top: 3px; margin-bottom: 8px;">${lat.toFixed(6)}, ${lon.toFixed(6)}</span>
            <div style="display: flex; gap: 6px;">
                <button class="action-btn btn-primary" onclick="window.setPointAFromSearch(${lat}, ${lon})" style="padding: 6px 8px; font-size: 0.75rem; flex: 1; min-width: 90px; color:#0b0c10;">Đặt Điểm A</button>
                <button class="action-btn btn-secondary" onclick="window.setPointBFromSearch(${lat}, ${lon})" style="padding: 6px 8px; font-size: 0.75rem; flex: 1; min-width: 90px; border-color: var(--color-green); color: var(--color-green); background: rgba(57, 255, 20, 0.1);">Đặt Điểm B</button>
            </div>
        </div>
    `;
    
    searchMarker = L.marker([lat, lon], { icon: searchIcon })
        .addTo(map)
        .bindPopup(popupContent, { minWidth: 200 })
        .openPopup();
}

window.setPointAFromSearch = function(lat, lon) {
    setStartPoint(lat, lon);
    if (searchMarker) {
        map.removeLayer(searchMarker);
        searchMarker = null;
    }
};

window.setPointBFromSearch = function(lat, lon) {
    setEndPoint(lat, lon);
    if (searchMarker) {
        map.removeLayer(searchMarker);
        searchMarker = null;
    }
};

// 13. Manual Coordinates Typing Handler
function setupCoordinateInputs() {
    const parseAndSet = (inputId, setterFunc) => {
        const input = document.getElementById(inputId);
        if (!input) return;
        input.addEventListener('change', () => {
            const val = input.value;
            const parts = val.split(/[,\s]+/).map(p => parseFloat(p.trim())).filter(p => !isNaN(p));
            if (parts.length === 2) {
                const [lat, lon] = parts;
                if (lat >= 10.0 && lat <= 11.5 && lon >= 106.0 && lon <= 107.5) {
                    setterFunc(lat, lon);
                    map.panTo([lat, lon]);
                } else {
                    alert("Tọa độ nằm ngoài phạm vi TP.HCM (Vĩ độ: 10.0-11.5, Kinh độ: 106.0-107.5).");
                }
            } else if (val.trim() !== "") {
                alert("Định dạng tọa độ không hợp lệ. Vui lòng nhập: Vĩ độ, Kinh độ (ví dụ: 10.7712, 106.7001)");
            }
        });
    };
    
    parseAndSet('start-coords', setStartPoint);
    parseAndSet('end-coords', setEndPoint);
}

// 14. Real-time Weather Integration (Open-Meteo API)
window.currentLiveWindSpeed = 3.5;
window.currentLiveWindDirection = 210;

const WEATHER_CODES = {
    0: "Trời quang",
    1: "Ít mây",
    2: "Bán u ám",
    3: "U ám",
    45: "Sương mù",
    48: "Sương mù đóng băng",
    51: "Mưa phùn nhẹ",
    53: "Mưa phùn vừa",
    55: "Mưa phùn đặc",
    61: "Mưa nhẹ",
    63: "Mưa vừa",
    65: "Mưa to",
    80: "Mưa rào nhẹ",
    81: "Mưa rào vừa",
    82: "Mưa rào lớn",
    95: "Dông sét",
    96: "Dông kèm mưa đá"
};

async function fetchRealWeather(lat, lon) {
    const hudWeather = document.getElementById('hud-weather');
    const teleWindSpeed = document.getElementById('tele-wind-speed');
    const teleWindDir = document.getElementById('tele-wind-dir');
    
    if (!navigator.onLine) {
        if (hudWeather) hudWeather.innerText = "Ngoại tuyến (Offline)";
        return;
    }
    
    try {
        const url = `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lon}&current_weather=true`;
        const res = await fetch(url);
        if (!res.ok) throw new Error("Weather API failed");
        
        const data = await res.json();
        const current = data.current_weather;
        if (current) {
            const temp = current.temperature;
            const windSpeedKmh = current.windspeed;
            const windSpeedMps = Math.round((windSpeedKmh / 3.6) * 10) / 10;
            const windDir = Math.round(current.winddirection);
            const code = current.weathercode;
            
            let weatherText = WEATHER_CODES[code] || "Có mây";
            
            // Save global weather values
            window.currentLiveWindSpeed = windSpeedMps;
            window.currentLiveWindDirection = windDir;
            
            // Update UI elements
            if (hudWeather) {
                hudWeather.innerText = `${weatherText} | ${temp}°C`;
            }
            if (teleWindSpeed) {
                teleWindSpeed.innerText = `${windSpeedMps} m/s`;
            }
            if (teleWindDir) {
                teleWindDir.innerText = `${windDir}°`;
            }
            
            console.log(`Live weather loaded for [${lat}, ${lon}]: ${weatherText}, Temp: ${temp}°C, Wind: ${windSpeedMps} m/s at ${windDir}°`);
        }
    } catch (e) {
        console.warn("Lỗi tải thời tiết thời gian thực:", e);
        if (hudWeather) hudWeather.innerText = "Không thể tải thời tiết";
    }
}
