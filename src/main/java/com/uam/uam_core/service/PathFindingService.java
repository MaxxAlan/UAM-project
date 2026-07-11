package com.uam.uam_core.service;

import com.uam.uam_core.model.Building;
import com.uam.uam_core.model.FlightPoint;
import com.uam.uam_core.model.NoFlyZone;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PathFindingService {

    private static final double SAFETY_ALTITUDE_BUFFER = 15.0; // Fly 15 meters above building tops
    private static final double DETOUR_STEP_RATIO = 1.2;       // Multiplier for radius to bypass obstacles
    private static final double MIN_FLIGHT_ALTITUDE = 30.0;    // Standard minimum flight altitude (30m)
    private static final double DRONE_CRUISE_SPEED = 5.0;      // m/s default cruise speed
    private static final double DRONE_CLIMB_SPEED = 2.5;       // m/s vertical climb speed

    /**
     * Calculates a safe 3D flight path from start to end points, avoiding buildings and no-fly zones.
     */
    public List<FlightPoint> findSafePath(FlightPoint start, FlightPoint end, List<Building> buildings, List<NoFlyZone> nfzs) {
        List<FlightPoint> path = new ArrayList<>();
        
        // Ensure start and end altitudes are at least at minimum safe flight altitude
        start.setAltitude(Math.max(start.getAltitude(), MIN_FLIGHT_ALTITUDE));
        end.setAltitude(Math.max(end.getAltitude(), MIN_FLIGHT_ALTITUDE));
        
        path.add(start);

        // Generate intermediate check points along the direct route
        double totalDistance = start.distanceTo(end);
        int steps = (int) Math.ceil(totalDistance / 15.0); // Finer resolution: Check every 15 meters for optimal transitions
        
        if (steps <= 1) {
            FlightPoint endPoint = new FlightPoint(end.getLatitude(), end.getLongitude(), end.getAltitude());
            endPoint.setHeading(calculateHeading(start, end));
            path.add(endPoint);
            return path;
        }

        FlightPoint current = start;
        for (int i = 1; i < steps; i++) {
            double ratio = (double) i / steps;
            double lat = start.getLatitude() + (end.getLatitude() - start.getLatitude()) * ratio;
            double lon = start.getLongitude() + (end.getLongitude() - start.getLongitude()) * ratio;
            double alt = start.getAltitude() + (end.getAltitude() - start.getAltitude()) * ratio;

            FlightPoint candidate = new FlightPoint(lat, lon, alt);
            
            // Check collision with No-Fly Zones
            NoFlyZone activeNfz = getCollidingNfz(candidate, nfzs);
            if (activeNfz != null) {
                // Bypass No-Fly Zone horizontally
                candidate = bypassNfz(current, candidate, end, activeNfz);
            }

            // Check collision with Buildings
            Building activeBuilding = getCollidingBuilding(candidate, buildings);
            if (activeBuilding != null) {
                // If building is short/medium (< 70m), flying over is faster than detouring around
                if (activeBuilding.getHeight() < 70.0) {
                    candidate.setAltitude(activeBuilding.getHeight() + SAFETY_ALTITUDE_BUFFER);
                } else {
                    // Detour around tall skyscrapers is more time-efficient than climbing all the way over
                    candidate = bypassBuilding(current, candidate, end, activeBuilding);
                }
            }

            // Set proper heading from current to this node
            candidate.setHeading(calculateHeading(current, candidate));
            path.add(candidate);
            current = candidate;
        }

        // Add final destination
        FlightPoint finalPoint = new FlightPoint(end.getLatitude(), end.getLongitude(), end.getAltitude());
        finalPoint.setHeading(calculateHeading(current, end));
        path.add(finalPoint);

        return smoothPathAndAltitudes(path, buildings);
    }

    private NoFlyZone getCollidingNfz(FlightPoint pt, List<NoFlyZone> nfzs) {
        if (nfzs == null) return null;
        for (NoFlyZone nfz : nfzs) {
            if (nfz.collidesWith(pt)) {
                return nfz;
            }
        }
        return null;
    }

    private Building getCollidingBuilding(FlightPoint pt, List<Building> buildings) {
        if (buildings == null) return null;
        for (Building b : buildings) {
            if (b.collidesWith(pt)) {
                return b;
            }
        }
        return null;
    }

    private FlightPoint bypassNfz(FlightPoint from, FlightPoint current, FlightPoint to, NoFlyZone nfz) {
        double dy = current.getLatitude() - nfz.getCenterLatitude();
        double dx = current.getLongitude() - nfz.getCenterLongitude();
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance == 0) {
            dy = 0.0001;
        }

        double latOffsetMeter = nfz.getRadiusMeters() * DETOUR_STEP_RATIO;
        double degOffsetLat = latOffsetMeter / 111000.0;
        double degOffsetLon = latOffsetMeter / (111000.0 * Math.cos(Math.toRadians(nfz.getCenterLatitude())));

        double newLat = nfz.getCenterLatitude() + (dy / distance) * degOffsetLat;
        double newLon = nfz.getCenterLongitude() + (dx / distance) * degOffsetLon;

        return new FlightPoint(newLat, newLon, current.getAltitude());
    }

    private FlightPoint bypassBuilding(FlightPoint from, FlightPoint current, FlightPoint to, Building building) {
        double sumLat = 0;
        double sumLon = 0;
        for (FlightPoint p : building.getPolygon()) {
            sumLat += p.getLatitude();
            sumLon += p.getLongitude();
        }
        double centerLat = sumLat / building.getPolygon().size();
        double centerLon = sumLon / building.getPolygon().size();

        double detourRadius = 45.0; // Optimized buffer
        double degOffsetLat = detourRadius / 111000.0;
        double degOffsetLon = detourRadius / (111000.0 * Math.cos(Math.toRadians(centerLat)));

        double dy = current.getLatitude() - centerLat;
        double dx = current.getLongitude() - centerLon;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance == 0) distance = 0.0001;

        double newLat = centerLat + (dy / distance) * degOffsetLat;
        double newLon = centerLon + (dx / distance) * degOffsetLon;

        return new FlightPoint(newLat, newLon, current.getAltitude());
    }

    public double calculateHeading(FlightPoint from, FlightPoint to) {
        double lat1 = Math.toRadians(from.getLatitude());
        double lon1 = Math.toRadians(from.getLongitude());
        double lat2 = Math.toRadians(to.getLatitude());
        double lon2 = Math.toRadians(to.getLongitude());

        double dLon = lon2 - lon1;

        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);

        double brng = Math.atan2(y, x);
        brng = Math.toDegrees(brng);
        brng = (brng + 360) % 360;

        return brng;
    }

    /**
     * Optimizes altitude transitions by smoothing climbs and descents (preventing jagged peaks)
     * and ensuring safe flyover clearances.
     */
    private List<FlightPoint> smoothPathAndAltitudes(List<FlightPoint> path, List<Building> buildings) {
        if (path.size() < 3) return path;

        // Pass 1: Keep target altitudes to clear all local obstacles
        double[] targetAlts = new double[path.size()];
        for (int i = 0; i < path.size(); i++) {
            FlightPoint pt = path.get(i);
            double requiredAlt = MIN_FLIGHT_ALTITUDE;
            
            // Check if this point falls inside or near any building that we decided to fly over
            for (Building b : buildings) {
                if (b.getHeight() < 70.0 && b.isInsidePolygon(pt.getLatitude(), pt.getLongitude())) {
                    requiredAlt = Math.max(requiredAlt, b.getHeight() + SAFETY_ALTITUDE_BUFFER);
                }
            }
            targetAlts[i] = requiredAlt;
        }

        // Pass 2: Smooth transition climbs/descents (anticipatory climbing)
        // Ensure drone climbs gradually before reaching a tall point and descents gradually
        for (int i = 1; i < path.size(); i++) {
            if (targetAlts[i] > targetAlts[i - 1]) {
                // Smooth climb going forward (climb starts 2 steps earlier)
                if (i >= 2) targetAlts[i - 2] = Math.max(targetAlts[i - 2], targetAlts[i] - 10.0);
                if (i >= 1) targetAlts[i - 1] = Math.max(targetAlts[i - 1], targetAlts[i] - 5.0);
            }
        }

        for (int i = path.size() - 2; i >= 0; i--) {
            if (targetAlts[i] > targetAlts[i + 1]) {
                // Smooth descent going backward (gradual drop after obstacle)
                if (i < path.size() - 2) targetAlts[i + 2] = Math.max(targetAlts[i + 2], targetAlts[i] - 10.0);
                if (i < path.size() - 1) targetAlts[i + 1] = Math.max(targetAlts[i + 1], targetAlts[i] - 5.0);
            }
        }

        // Reconstruct path with smoothed altitudes
        List<FlightPoint> smoothed = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            FlightPoint pt = path.get(i);
            FlightPoint newPt = new FlightPoint(pt.getLatitude(), pt.getLongitude(), targetAlts[i]);
            newPt.setSpeed(DRONE_CRUISE_SPEED);
            newPt.setHeading(pt.getHeading());
            smoothed.add(newPt);
        }

        return smoothed;
    }

    /**
     * Computes flight metrics (distance, estimated time, and average speed) taking wind vector into account.
     */
    public FlightMetrics calculateMetrics(List<FlightPoint> path, double windSpeed, double windDirection) {
        double totalDistance = 0;
        double totalDuration = 0; // seconds

        for (int i = 1; i < path.size(); i++) {
            FlightPoint p1 = path.get(i - 1);
            FlightPoint p2 = path.get(i);

            double dist2D = p1.distanceTo(p2);
            double dH = p2.getAltitude() - p1.getAltitude();
            double dist3D = Math.sqrt(dist2D * dist2D + dH * dH);
            totalDistance += dist3D;

            // Calculate wind effect on ground speed
            double heading = p2.getHeading();
            double headingRad = Math.toRadians(heading);
            
            // Wind blows FROM direction, so flow is in direction (windDirection + 180)
            double flowRad = Math.toRadians((windDirection + 180) % 360);
            
            double windX = windSpeed * Math.cos(flowRad);
            double windY = windSpeed * Math.sin(flowRad);
            double droneX = Math.cos(headingRad);
            double droneY = Math.sin(headingRad);
            
            // Tailwind/headwind component
            double windAlongHeading = windX * droneX + windY * droneY;
            double groundSpeed = DRONE_CRUISE_SPEED + windAlongHeading;
            if (groundSpeed < 1.0) groundSpeed = 1.0; // Maintain positive speed

            // Time for horizontal travel + climbing time (if climbing, it is slower)
            double segmentTime = dist2D / groundSpeed;
            if (dH > 0) {
                // Add vertical climb delay
                segmentTime += dH / DRONE_CLIMB_SPEED;
            }
            totalDuration += segmentTime;
        }

        double avgSpeed = totalDistance / (totalDuration > 0 ? totalDuration : 1.0);

        return new FlightMetrics(
            Math.round(totalDistance * 10.0) / 10.0, 
            Math.round(totalDuration), 
            Math.round(avgSpeed * 10.0) / 10.0
        );
    }

    public static class FlightMetrics {
        public double totalDistanceMeters;
        public double totalDurationSeconds;
        public double averageSpeedMps;

        public FlightMetrics(double totalDistanceMeters, double totalDurationSeconds, double averageSpeedMps) {
            this.totalDistanceMeters = totalDistanceMeters;
            this.totalDurationSeconds = totalDurationSeconds;
            this.averageSpeedMps = averageSpeedMps;
        }
    }
}
