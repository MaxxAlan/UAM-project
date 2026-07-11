package com.uam.uam_core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uam.uam_core.model.Building;
import com.uam.uam_core.model.FlightPoint;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class OverpassService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Multi-city in-memory cache: cityKey → parsed building list */
    private static final Map<String, List<Building>> cityCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object CACHE_LOCK = new Object();

    /**
     * City registry: maps city key → [min_lon, min_lat, max_lon, max_lat] bounding box.
     * Must match keys used by data_builder.py CITIES dict.
     */
    private static final Map<String, double[]> CITY_BBOXES = new java.util.LinkedHashMap<>();
    static {
        // Việt Nam
        CITY_BBOXES.put("hcm",          new double[]{106.55, 10.65, 106.85, 10.90});
        CITY_BBOXES.put("hanoi",        new double[]{105.70, 20.85, 106.00, 21.10});
        CITY_BBOXES.put("danang",       new double[]{108.05, 15.95, 108.30, 16.15});
        CITY_BBOXES.put("haiphong",     new double[]{106.55, 20.78, 106.80, 20.95});
        CITY_BBOXES.put("cantho",       new double[]{105.70, 10.00, 105.85, 10.12});
        CITY_BBOXES.put("nhatrang",     new double[]{109.10, 12.20, 109.25, 12.30});
        CITY_BBOXES.put("vungtau",      new double[]{107.02, 10.30, 107.15, 10.45});
        CITY_BBOXES.put("hue",          new double[]{107.53, 16.41, 107.65, 16.52});
        // Đông Nam Á
        CITY_BBOXES.put("singapore",    new double[]{103.60,  1.20, 104.05,  1.50});
        CITY_BBOXES.put("bangkok",      new double[]{100.30, 13.55, 100.90, 14.00});
        CITY_BBOXES.put("kuala_lumpur", new double[]{101.58,  3.05, 101.80,  3.25});
        CITY_BBOXES.put("jakarta",      new double[]{106.65, -6.35, 106.95, -6.10});
        CITY_BBOXES.put("manila",       new double[]{120.90, 14.45, 121.10, 14.70});
        // Đông Bắc Á
        CITY_BBOXES.put("tokyo",        new double[]{139.55, 35.55, 139.90, 35.80});
        CITY_BBOXES.put("seoul",        new double[]{126.80, 37.45, 127.15, 37.70});
        CITY_BBOXES.put("hong_kong",    new double[]{114.00, 22.20, 114.30, 22.55});
        CITY_BBOXES.put("shanghai",     new double[]{121.30, 31.05, 121.65, 31.35});
        // Châu Âu / Khác
        CITY_BBOXES.put("london",       new double[]{ -0.30, 51.40,   0.10, 51.60});
        CITY_BBOXES.put("paris",        new double[]{  2.20, 48.78,   2.45, 48.93});
        CITY_BBOXES.put("dubai",        new double[]{ 55.10, 25.05,  55.40, 25.30});
        CITY_BBOXES.put("new_york",     new double[]{-74.05, 40.65, -73.90, 40.80});
    }

    private static final String[] OVERPASS_API_URLS = {
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    };

    // Default Bounding Box around District 1, HCMC if none is provided
    private static final double DEFAULT_MIN_LAT = 10.7700;
    private static final double DEFAULT_MIN_LON = 106.6900;
    private static final double DEFAULT_MAX_LAT = 10.7850;
    private static final double DEFAULT_MAX_LON = 106.7100;

    public List<Building> getBuildingsInDefaultArea() {
        return getBuildings(DEFAULT_MIN_LAT, DEFAULT_MIN_LON, DEFAULT_MAX_LAT, DEFAULT_MAX_LON);
    }

    /**
     * Fetches building geometries and heights from OSM Overpass API inside a bounding box.
     */
    public List<Building> getBuildings(double minLat, double minLon, double maxLat, double maxLon) {
        String query = String.format(
                "[out:json][timeout:25];\n" +
                "(\n" +
                "  way[\"building\"](%f,%f,%f,%f);\n" +
                ");\n" +
                "out body;\n" +
                ">;\n" +
                "out skel qt;",
                minLat, minLon, maxLat, maxLon
        );

        for (String apiUrl : OVERPASS_API_URLS) {
            try {
                System.out.println("Fetching buildings from Overpass API mirror: " + apiUrl);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) UAM-DronePathFinder/1.0 (contact: admin@uam-project.local)")
                        .POST(HttpRequest.BodyPublishers.ofString("data=" + java.net.URLEncoder.encode(query, "UTF-8")))
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    System.out.println("Successfully fetched buildings from " + apiUrl);
                    return parseOverpassResponse(response.body());
                } else {
                    System.err.println("Mirror " + apiUrl + " returned status code: " + response.statusCode());
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch from mirror " + apiUrl + ": " + e.getMessage());
            }
        }

        // Fallback to offline pre-fetched JSON for resilience when all network mirrors are down
        System.err.println("All Overpass API mirrors failed or offline. Falling back to local database...");
        return getOfflineFallbackBuildings(minLat, minLon, maxLat, maxLon);
    }

    /** Clears all city caches. Next request will reload from disk. */
    public void clearCache() {
        cityCache.clear();
        System.out.println("[Cache] All city building caches cleared.");
    }

    /** Clears cache for a specific city key. */
    public void clearCacheForCity(String cityKey) {
        cityCache.remove(cityKey);
        System.out.println("[Cache] Cleared cache for city: " + cityKey);
    }

    /**
     * Loads pre-fetched offline building dataset and filters by bbox.
     * Auto-detects which city file to use based on bbox overlap.
     * Each city file is cached independently in memory.
     */
    public List<Building> getOfflineFallbackBuildings(double minLat, double minLon, double maxLat, double maxLon) {
        // Determine which city file best matches the requested area
        String matchedCity = detectCityKey(minLat, minLon, maxLat, maxLon);

        if (matchedCity == null) {
            System.out.println("[Offline] No matching city data file for bbox. Using mock buildings.");
            return getMockBuildings(minLat, minLon, maxLat, maxLon);
        }

        System.out.println("[Offline] Using city dataset: " + matchedCity);

        // Load city into cache if not already
        if (!cityCache.containsKey(matchedCity)) {
            synchronized (CACHE_LOCK) {
                if (!cityCache.containsKey(matchedCity)) {
                    String resourcePath = "/static/data/buildings_" + matchedCity + "_offline.json";
                    try (java.io.InputStream is = getClass().getResourceAsStream(resourcePath)) {
                        if (is != null) {
                            String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            JsonNode root = objectMapper.readTree(json);
                            List<Building> buildings = root.isArray()
                                    ? parseOvertureFormat(root)
                                    : parseOverpassResponse(json);
                            cityCache.put(matchedCity, buildings);
                            System.out.println("[Cache] Loaded " + buildings.size() + " buildings for '" + matchedCity + "'.");
                        } else {
                            System.err.println("[Offline] File not found: " + resourcePath + ". Run: python scripts/data_builder.py --city " + matchedCity);
                            cityCache.put(matchedCity, Collections.emptyList());
                        }
                    } catch (Exception ex) {
                        System.err.println("[Offline] Failed to load " + matchedCity + ": " + ex.getMessage());
                        cityCache.put(matchedCity, Collections.emptyList());
                    }
                }
            }
        }

        List<Building> allBuildings = cityCache.get(matchedCity);
        List<Building> filtered = new ArrayList<>();
        for (Building b : allBuildings) {
            if (isBuildingInBbox(b, minLat, minLon, maxLat, maxLon)) {
                filtered.add(b);
                if (filtered.size() >= 2000) break;
            }
        }
        System.out.println("[Offline] Found " + filtered.size() + " buildings in area.");
        return filtered.isEmpty() ? getMockBuildings(minLat, minLon, maxLat, maxLon) : filtered;
    }

    /**
     * Detects which city data file best covers the given bbox.
     * Returns the city key (e.g. "hcm", "hanoi") or null if none match.
     */
    private String detectCityKey(double minLat, double minLon, double maxLat, double maxLon) {
        String best = null;
        double bestOverlap = 0;
        for (Map.Entry<String, double[]> entry : CITY_BBOXES.entrySet()) {
            double[] b = entry.getValue(); // [min_lon, min_lat, max_lon, max_lat]
            // Compute overlap area
            double overlapLon = Math.min(maxLon, b[2]) - Math.max(minLon, b[0]);
            double overlapLat = Math.min(maxLat, b[3]) - Math.max(minLat, b[1]);
            if (overlapLon > 0 && overlapLat > 0) {
                double overlap = overlapLon * overlapLat;
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = entry.getKey();
                }
            }
        }
        return best;
    }


    /**
     * Parses Overture Maps flat JSON array format produced by data_builder.py.
     * Each element: { "id": "...", "name": "...", "height": 12.0,
     *                 "polygon": [{"latitude": .., "longitude": .., "altitude": 0.0}, ...] }
     */
    private List<Building> parseOvertureFormat(JsonNode root) {
        List<Building> buildings = new ArrayList<>();
        for (JsonNode node : root) {
            try {
                String id   = node.path("id").asText("ov-" + buildings.size());
                String name = node.path("name").asText("Tòa nhà " + id.substring(Math.max(0, id.length()-6)));
                double height = node.path("height").asDouble(12.0);

                JsonNode polygonNode = node.path("polygon");
                List<FlightPoint> polygon = new ArrayList<>();
                if (polygonNode.isArray()) {
                    for (JsonNode pt : polygonNode) {
                        double lat = pt.path("latitude").asDouble();
                        double lon = pt.path("longitude").asDouble();
                        double alt = pt.path("altitude").asDouble(0.0);
                        polygon.add(new FlightPoint(lat, lon, alt));
                    }
                }

                if (polygon.size() >= 3) {
                    buildings.add(new Building(id, name, polygon, height));
                }
            } catch (Exception ignored) {}
        }
        System.out.println("[OvertureParser] Loaded " + buildings.size() + " buildings from Overture format.");
        return buildings;
    }

    private boolean isBuildingInBbox(Building building, double minLat, double minLon, double maxLat, double maxLon) {
        if (building.getPolygon() == null || building.getPolygon().isEmpty()) {
            return false;
        }
        double bMinLat = Double.MAX_VALUE;
        double bMaxLat = -Double.MAX_VALUE;
        double bMinLon = Double.MAX_VALUE;
        double bMaxLon = -Double.MAX_VALUE;
        for (FlightPoint pt : building.getPolygon()) {
            bMinLat = Math.min(bMinLat, pt.getLatitude());
            bMaxLat = Math.max(bMaxLat, pt.getLatitude());
            bMinLon = Math.min(bMinLon, pt.getLongitude());
            bMaxLon = Math.max(bMaxLon, pt.getLongitude());
        }
        return bMinLat <= maxLat && bMaxLat >= minLat && bMinLon <= maxLon && bMaxLon >= minLon;
    }

    private List<Building> parseOverpassResponse(String jsonResponse) throws Exception {
        JsonNode root = objectMapper.readTree(jsonResponse);
        JsonNode elements = root.path("elements");

        Map<Long, FlightPoint> nodeMap = new HashMap<>();
        List<JsonNode> ways = new ArrayList<>();

        for (JsonNode element : elements) {
            String type = element.path("type").asText();
            if ("node".equals(type)) {
                long id = element.path("id").asLong();
                double lat = element.path("lat").asDouble();
                double lon = element.path("lon").asDouble();
                nodeMap.put(id, new FlightPoint(lat, lon, 0.0));
            } else if ("way".equals(type)) {
                ways.add(element);
            }
        }

        List<Building> buildings = new ArrayList<>();
        for (JsonNode way : ways) {
            String id = way.path("id").asText();
            JsonNode tags = way.path("tags");
            
            // Determine name
            String name = tags.path("name").asText("Building " + id);
            if (tags.path("building").asText().equals("yes") && !tags.has("name")) {
                name = "Tòa nhà " + id;
            }

            // Determine height
            String buildingType = tags.path("building").asText();
            double defaultHeight = 15.0; // Default 15m (~4 floors) for office/general
            if ("house".equals(buildingType) || "detached".equals(buildingType) || "terrace".equals(buildingType) || "bungalow".equals(buildingType)) {
                defaultHeight = 7.0; // Typical 2-story house (7m)
            } else if ("residential".equals(buildingType)) {
                defaultHeight = 10.5; // Typical 3-story residential building (10.5m)
            }

            double height = defaultHeight;
            if (tags.has("height")) {
                String heightStr = tags.path("height").asText().replaceAll("[^0-9.]", "");
                try {
                    height = Double.parseDouble(heightStr);
                } catch (NumberFormatException ignored) {}
            } else if (tags.has("building:levels")) {
                try {
                    int levels = tags.path("building:levels").asInt();
                    height = levels * 3.5; // Average 3.5m per level
                } catch (Exception ignored) {}
            }

            // Construct polygon
            List<FlightPoint> polygon = new ArrayList<>();
            JsonNode nodes = way.path("nodes");
            for (JsonNode nodeRef : nodes) {
                long nodeId = nodeRef.asLong();
                FlightPoint pt = nodeMap.get(nodeId);
                if (pt != null) {
                    polygon.add(pt);
                }
            }

            if (polygon.size() >= 3) {
                buildings.add(new Building(id, name, polygon, height));
            }
        }

        return buildings;
    }

    /**
     * Generates realistic mock buildings in District 1, HCM in case Overpass API is slow or offline.
     */
    public List<Building> getMockBuildings(double minLat, double minLon, double maxLat, double maxLon) {
        List<Building> buildings = new ArrayList<>();
        
        // Bitexco Financial Tower area mock
        List<FlightPoint> bitexcoPoly = Arrays.asList(
            new FlightPoint(10.7710, 106.7040, 0),
            new FlightPoint(10.7720, 106.7040, 0),
            new FlightPoint(10.7720, 106.7050, 0),
            new FlightPoint(10.7710, 106.7050, 0)
        );
        buildings.add(new Building("mock_bitexco", "Bitexco Financial Tower", bitexcoPoly, 262.0));

        // Landmark 81 mock (if coordinates are near)
        List<FlightPoint> landmarkPoly = Arrays.asList(
            new FlightPoint(10.7975, 106.7210, 0),
            new FlightPoint(10.7985, 106.7210, 0),
            new FlightPoint(10.7985, 106.7220, 0),
            new FlightPoint(10.7975, 106.7220, 0)
        );
        buildings.add(new Building("mock_landmark81", "Landmark 81", landmarkPoly, 461.3));

        // Times Square Saigon area mock
        List<FlightPoint> timesSquarePoly = Arrays.asList(
            new FlightPoint(10.7725, 106.7025, 0),
            new FlightPoint(10.7730, 106.7025, 0),
            new FlightPoint(10.7730, 106.7035, 0),
            new FlightPoint(10.7725, 106.7035, 0)
        );
        buildings.add(new Building("mock_timessquare", "Times Square Saigon", timesSquarePoly, 165.0));

        // Saigon Centre mock
        List<FlightPoint> saigonCentrePoly = Arrays.asList(
            new FlightPoint(10.7740, 106.7010, 0),
            new FlightPoint(10.7748, 106.7010, 0),
            new FlightPoint(10.7748, 106.7020, 0),
            new FlightPoint(10.7740, 106.7020, 0)
        );
        buildings.add(new Building("mock_saigoncentre", "Saigon Centre (Phase 2)", saigonCentrePoly, 229.0));

        return buildings;
    }
}
