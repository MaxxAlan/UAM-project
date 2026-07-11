package com.uam.uam_core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NoFlyZone {
    private String id;
    private String name;
    private double centerLatitude;
    private double centerLongitude;
    private double radiusMeters;
    private double minAltitude;
    private double maxAltitude;

    /**
     * Checks if a point falls inside this No-Fly Zone.
     */
    public boolean collidesWith(FlightPoint point) {
        if (point.getAltitude() < minAltitude || point.getAltitude() > maxAltitude) {
            return false;
        }
        
        // Calculate distance from center to point
        FlightPoint center = new FlightPoint(centerLatitude, centerLongitude, point.getAltitude());
        double distance = center.distanceTo(point);
        return distance <= radiusMeters;
    }
}
