package com.uam.uam_core.controller;

import com.uam.uam_core.model.Building;
import com.uam.uam_core.model.FlightPoint;
import com.uam.uam_core.model.NoFlyZone;
import com.uam.uam_core.service.OverpassService;
import com.uam.uam_core.service.PathFindingService;
import com.uam.uam_core.util.DronePlanExporter;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class MapController {

    @Autowired
    private OverpassService overpassService;

    @Autowired
    private PathFindingService pathFindingService;

    // In-memory No-Fly Zones in Ho Chi Minh City
    private final List<NoFlyZone> noFlyZones = Arrays.asList(
        // Tan Son Nhat International Airport (VVTS) NFZ: 5km radius
        new NoFlyZone("nfz_tansonnhat", "Sân bay Quốc tế Tân Sơn Nhất (NFZ-A)", 10.8188, 106.6518, 5000.0, 0.0, 1000.0),
        // Military headquarters in District 1/3 (gỉa lập)
        new NoFlyZone("nfz_military_d1", "Khu quân sự Quận 1 (Cấm bay hoàn toàn)", 10.7812, 106.6985, 400.0, 0.0, 500.0),
        // Thu Thiem Tunnel ventilator tower area
        new NoFlyZone("nfz_thuthiem_bridge", "Cầu Thủ Thiêm & Cảng Khánh Hội", 10.7710, 106.7090, 300.0, 0.0, 200.0)
    );

    @GetMapping("/buildings")
    public ResponseEntity<List<Building>> getBuildings(
            @RequestParam(required = false) Double minLat,
            @RequestParam(required = false) Double minLon,
            @RequestParam(required = false) Double maxLat,
            @RequestParam(required = false) Double maxLon) {
        
        if (minLat == null || minLon == null || maxLat == null || maxLon == null) {
            return ResponseEntity.ok(overpassService.getBuildingsInDefaultArea());
        }
        return ResponseEntity.ok(overpassService.getBuildings(minLat, minLon, maxLat, maxLon));
    }

    /** Lists available city data files — for frontend city selector status display */
    @GetMapping("/data/cities")
    public ResponseEntity<Map<String, Object>> listAvailableCities() {
        Map<String, Object> result = new LinkedHashMap<>();
        // Scan static/data directory for buildings_*_offline.json files
        try {
            org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
            org.springframework.core.io.Resource[] resources =
                resolver.getResources("classpath:/static/data/buildings_*_offline.json");
            List<String> available = new ArrayList<>();
            for (org.springframework.core.io.Resource r : resources) {
                String filename = r.getFilename(); // buildings_hcm_offline.json
                if (filename != null) {
                    String key = filename.replace("buildings_", "").replace("_offline.json", "");
                    available.add(key);
                }
            }
            result.put("available", available);
            result.put("total", available.size());
        } catch (Exception e) {
            result.put("available", List.of());
            result.put("error", e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /** Clears cache for a specific city or all cities */
    @PostMapping("/buildings/reload")
    public ResponseEntity<Map<String, Object>> reloadBuildingCache(
            @RequestParam(required = false) String city) {
        if (city != null && !city.isBlank()) {
            overpassService.clearCacheForCity(city.trim());
        } else {
            overpassService.clearCache();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("cleared", city != null ? city : "all");
        result.put("message", "Cache cleared. Next buildings request will reload from disk.");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/nfzs")
    public ResponseEntity<List<NoFlyZone>> getNoFlyZones() {
        return ResponseEntity.ok(noFlyZones);
    }

    @PostMapping("/route")
    public ResponseEntity<Map<String, Object>> calculateRoute(@RequestBody RouteRequest request) {
        // Fetch buildings around the start/end bounding box with padding
        double minLat = Math.min(request.getStart().getLatitude(), request.getEnd().getLatitude()) - 0.015;
        double maxLat = Math.max(request.getStart().getLatitude(), request.getEnd().getLatitude()) + 0.015;
        double minLon = Math.min(request.getStart().getLongitude(), request.getEnd().getLongitude()) - 0.015;
        double maxLon = Math.max(request.getStart().getLongitude(), request.getEnd().getLongitude()) + 0.015;

        List<Building> localBuildings = overpassService.getBuildings(minLat, minLon, maxLat, maxLon);

        // Find safe path
        List<FlightPoint> safePath = pathFindingService.findSafePath(
                request.getStart(), 
                request.getEnd(), 
                localBuildings, 
                noFlyZones
        );

        // Use real-time weather values if provided by frontend, otherwise fallback to mock
        double windSpeed = request.getWindSpeed() != null ? request.getWindSpeed() : 3.5 + (Math.random() * 2.0);
        double windDirection = request.getWindDirection() != null ? request.getWindDirection() : 210.0 + (Math.random() * 30.0);
        double gpsDop = 0.9 + (Math.random() * 0.4); // Dilution of precision (0.9 - 1.3 meters error)

        // Calculate path metrics
        PathFindingService.FlightMetrics metrics = pathFindingService.calculateMetrics(safePath, windSpeed, windDirection);

        Map<String, Object> response = new HashMap<>();
        response.put("path", safePath);
        response.put("windSpeed", Math.round(windSpeed * 10.0) / 10.0);
        response.put("windDirection", Math.round(windDirection));
        response.put("weatherCondition", request.getWindSpeed() != null ? "Thời tiết thực tế" : "Mây rải rác");
        response.put("gpsDop", Math.round(gpsDop * 100.0) / 100.0);
        response.put("totalDistance", metrics.totalDistanceMeters);
        response.put("totalDuration", metrics.totalDurationSeconds);
        response.put("averageSpeed", metrics.averageSpeedMps);
        response.put("status", "SUCCESS");
        response.put("message", "Đường bay được thiết lập an toàn.");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> exportRoute(
            @RequestBody List<FlightPoint> path,
            @RequestParam(defaultValue = "plan") String format) {
        
        byte[] fileBytes;
        String filename;
        String mediaType;

        if ("kml".equalsIgnoreCase(format)) {
            String kml = DronePlanExporter.exportToKML(path);
            fileBytes = kml.getBytes();
            filename = "drone_route_hcm.kml";
            mediaType = "application/vnd.google-earth.kml+xml";
        } else {
            String plan = DronePlanExporter.exportToQGCPlan(path);
            fileBytes = plan.getBytes();
            filename = "drone_mission_hcm.plan";
            mediaType = "application/json";
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(mediaType))
                .body(fileBytes);
    }

    @Data
    public static class RouteRequest {
        private FlightPoint start;
        private FlightPoint end;
        private Double windSpeed;
        private Double windDirection;
    }
}
