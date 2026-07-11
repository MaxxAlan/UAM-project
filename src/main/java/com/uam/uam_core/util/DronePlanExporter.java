package com.uam.uam_core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.uam.uam_core.model.FlightPoint;

import java.util.List;

public class DronePlanExporter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Converts a list of FlightPoints to a QGroundControl (.plan) JSON string.
     */
    public static String exportToQGCPlan(List<FlightPoint> path) {
        try {
            if (path == null || path.isEmpty()) {
                return "{}";
            }

            FlightPoint home = path.get(0);

            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("fileType", "Plan");
            rootNode.put("version", 1);
            rootNode.put("groundStation", "QGroundControl");

            // Build Mission object
            ObjectNode missionNode = objectMapper.createObjectNode();
            missionNode.put("cruiseSpeed", 5.0);
            missionNode.put("hoverSpeed", 3.0);
            missionNode.put("firmwareType", 12); // PX4 Autopilot
            missionNode.put("vehicleType", 2);  // MultiRotor

            // Planned Home Position [Lat, Lon, Alt]
            ArrayNode homePos = objectMapper.createArrayNode();
            homePos.add(home.getLatitude());
            homePos.add(home.getLongitude());
            homePos.add(0.0); // Home altitude is ground level
            missionNode.set("plannedHomePosition", homePos);

            // Mission Items Array
            ArrayNode itemsNode = objectMapper.createArrayNode();

            // First item: takeoff command (optional, but let's make waypoints simple MAV_CMD_NAV_WAYPOINT = 16)
            for (int i = 0; i < path.size(); i++) {
                FlightPoint pt = path.get(i);
                ObjectNode item = objectMapper.createObjectNode();
                item.put("autoContinue", true);
                
                // MAV_CMD_NAV_TAKEOFF (22) for the first point, MAV_CMD_NAV_WAYPOINT (16) for others
                int command = (i == 0) ? 22 : 16; 
                item.put("command", command);
                item.put("doJumpId", i + 1);
                item.put("frame", 3); // MAV_FRAME_GLOBAL_RELATIVE_ALT (Altitude AGL)

                ArrayNode params = objectMapper.createArrayNode();
                if (command == 22) {
                    // Takeoff parameters
                    params.add(15.0); // Pitch
                    params.add(0.0);  // Empty
                    params.add(0.0);  // Empty
                    params.add(Double.NaN); // Yaw (keep current)
                    params.add(pt.getLatitude());
                    params.add(pt.getLongitude());
                    params.add(pt.getAltitude());
                } else {
                    // Waypoint parameters
                    params.add(0.0);  // Hold time (seconds)
                    params.add(2.0);  // Acceptance radius (meters)
                    params.add(0.0);  // Pass radius (meters)
                    params.add(pt.getHeading()); // Yaw/Heading
                    params.add(pt.getLatitude());
                    params.add(pt.getLongitude());
                    params.add(pt.getAltitude());
                }
                item.set("params", params);
                item.put("type", "SimpleItem");

                itemsNode.add(item);
            }

            missionNode.set("items", itemsNode);
            rootNode.set("mission", missionNode);

            // Add simple empty GeoFence and RallyPoints objects to complete QGC format
            ObjectNode geoFence = objectMapper.createObjectNode();
            geoFence.put("version", 2);
            geoFence.set("polygons", objectMapper.createArrayNode());
            geoFence.set("circles", objectMapper.createArrayNode());
            rootNode.set("geoFence", geoFence);

            ObjectNode rallyPoints = objectMapper.createObjectNode();
            rallyPoints.put("version", 1);
            rallyPoints.set("points", objectMapper.createArrayNode());
            rootNode.set("rallyPoints", rallyPoints);

            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rootNode);

        } catch (Exception e) {
            System.err.println("Error generating QGC plan: " + e.getMessage());
            return "{}";
        }
    }

    /**
     * Converts a list of FlightPoints to a standard KML format string.
     */
    public static String exportToKML(List<FlightPoint> path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n");
        sb.append("  <Document>\n");
        sb.append("    <name>Drone Flight Route - HCM</name>\n");
        sb.append("    <Style id=\"yellowLineGreenPoly\">\n");
        sb.append("      <LineStyle>\n");
        sb.append("        <color>7f00ffff</color>\n"); // Yellowish green
        sb.append("        <width>4</width>\n");
        sb.append("      </LineStyle>\n");
        sb.append("    </Style>\n");
        sb.append("    <Placemark>\n");
        sb.append("      <name>Drone Path</name>\n");
        sb.append("      <styleUrl>#yellowLineGreenPoly</styleUrl>\n");
        sb.append("      <LineString>\n");
        sb.append("        <extrude>1</extrude>\n");
        sb.append("        <tessellate>1</tessellate>\n");
        sb.append("        <altitudeMode>relativeToGround</altitudeMode>\n");
        sb.append("        <coordinates>\n");

        for (FlightPoint pt : path) {
            sb.append(String.format("          %f,%f,%f\n", pt.getLongitude(), pt.getLatitude(), pt.getAltitude()));
        }

        sb.append("        </coordinates>\n");
        sb.append("      </LineString>\n");
        sb.append("    </Placemark>\n");
        sb.append("  </Document>\n");
        sb.append("</kml>");
        return sb.toString();
    }
}
