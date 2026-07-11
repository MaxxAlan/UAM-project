package com.uam.uam_core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlightPoint {
    private double latitude;
    private double longitude;
    private double altitude; // Height in meters above ground level (AGL)
    private double speed;    // Speed in m/s
    private double heading;  // Heading/direction in degrees (0-360)

    public FlightPoint(double latitude, double longitude, double altitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speed = 5.0; // Default speed 5 m/s
        this.heading = 0.0;
    }

    /**
     * Calculates the horizontal distance (in meters) to another point using the Haversine formula.
     */
    public double distanceTo(FlightPoint other) {
        final int R = 6371000; // Earth radius in meters
        double latDistance = Math.toRadians(other.latitude - this.latitude);
        double lonDistance = Math.toRadians(other.longitude - this.longitude);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(this.latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    /**
     * Calculates 3D Euclidean distance (in meters) incorporating altitude.
     */
    public double distance3D(FlightPoint other) {
        double dH = distanceTo(other);
        double dV = other.altitude - this.altitude;
        return Math.sqrt(dH * dH + dV * dV);
    }
}
