package com.uam.uam_core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Building {
    private String id;
    private String name;
    private List<FlightPoint> polygon; // Boundary coordinates (altitude is ignored in boundary, just lat/lon)
    private double height;             // Building height in meters

    /**
     * Checks if a 3D point (FlightPoint) collides with the building.
     * A collision occurs if the point is horizontally inside the polygon boundary
     * AND its altitude is less than or equal to the building's height.
     */
    public boolean collidesWith(FlightPoint point) {
        if (point.getAltitude() > this.height) {
            return false; // Drone is flying above the building
        }
        return isInsidePolygon(point.getLatitude(), point.getLongitude());
    }

    /**
     * Ray-casting algorithm to determine if a lat/lon point is inside the building's polygon.
     */
    public boolean isInsidePolygon(double lat, double lon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).getLatitude();
            double yi = polygon.get(i).getLongitude();
            double xj = polygon.get(j).getLatitude();
            double yj = polygon.get(j).getLongitude();

            boolean intersect = ((yi > lon) != (yj > lon))
                    && (lat < (xj - xi) * (lon - yi) / (yj - yi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }
}
