package com.uam.uam_core.service;

import com.uam.uam_core.model.Building;
import com.uam.uam_core.model.FlightPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathFindingServiceTest {

    private PathFindingService pathFindingService;

    @BeforeEach
    void setUp() {
        pathFindingService = new PathFindingService();
    }

    @Test
    void testCalculateHeading() {
        FlightPoint p1 = new FlightPoint(10.0, 106.0, 30.0);
        FlightPoint p2 = new FlightPoint(11.0, 106.0, 30.0); // Directly North
        
        double heading = pathFindingService.calculateHeading(p1, p2);
        assertEquals(0.0, heading, 1.0, "Heading directly north should be 0 degrees");

        FlightPoint p3 = new FlightPoint(10.0, 107.0, 30.0); // East
        heading = pathFindingService.calculateHeading(p1, p3);
        assertEquals(90.0, heading, 2.0, "Heading directly east should be approximately 90 degrees");
    }

    @Test
    void testPathFindingWithNoObstacles() {
        FlightPoint start = new FlightPoint(10.7710, 106.7040, 30.0);
        FlightPoint end = new FlightPoint(10.7750, 106.7080, 30.0);

        List<FlightPoint> path = pathFindingService.findSafePath(start, end, Collections.emptyList(), Collections.emptyList());

        assertNotNull(path);
        assertTrue(path.size() >= 2);
        
        // Start and end coordinates should match the inputs
        assertEquals(start.getLatitude(), path.get(0).getLatitude(), 0.0001);
        assertEquals(start.getLongitude(), path.get(0).getLongitude(), 0.0001);
        assertEquals(end.getLatitude(), path.get(path.size() - 1).getLatitude(), 0.0001);
        assertEquals(end.getLongitude(), path.get(path.size() - 1).getLongitude(), 0.0001);
    }

    @Test
    void testBuildingCollisionDetection() {
        // Create a simple building polygon
        List<FlightPoint> poly = Arrays.asList(
            new FlightPoint(10.7700, 106.7000, 0),
            new FlightPoint(10.7720, 106.7000, 0),
            new FlightPoint(10.7720, 106.7020, 0),
            new FlightPoint(10.7700, 106.7020, 0)
        );
        Building b = new Building("test_b", "Test Building", poly, 50.0);

        // Point inside building but below building height
        FlightPoint collidingPoint = new FlightPoint(10.7710, 106.7010, 30.0);
        assertTrue(b.collidesWith(collidingPoint), "Point should collide as it is inside building bounds and lower than building height");

        // Point inside building but above building height
        FlightPoint safePoint = new FlightPoint(10.7710, 106.7010, 60.0);
        assertFalse(b.collidesWith(safePoint), "Point should NOT collide because its altitude is higher than building height");
    }
}
