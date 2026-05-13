package com.wherebus.services;

import com.wherebus.models.Route;
import com.wherebus.models.Stop;

import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;

import jakarta.annotation.PostConstruct;

import java.util.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import com.opencsv.CSVReader;

/**
 * Loads static GTFS data at startup and holds the full transit network in memory.
 *
 * <p><b>Data structures:</b>
 * <ul>
 *   <li>{@code stopDirectory} / {@code routeDirectory} — HashMap for O(1) ID lookups.
 *       Never mutated after startup, so no synchronisation is needed.</li>
 *   <li>{@code routePaths} — One LinkedList per "routeId_directionId" key, preserving
 *       the ordered stop sequence for each direction independently.</li>
 *   <li>{@code stopCumulativeDistances} — Parallel to routePaths. For each stop in the
 *       sequence, stores its cumulative road distance (km) from the route start, derived
 *       from the shape polyline. Used by EtaCalculationService for accurate road distance
 *       and hasPassed() checks without a routing engine.</li>
 *   <li>{@code shapePolylines} — Raw shape point lists (lat/lon pairs) keyed by shape_id.
 *       Used at startup to project stops onto the route geometry. Not accessed at request time.</li>
 *   <li>{@code stopGraph} — Adjacency list for future pathfinding use.</li>
 * </ul>
 *
 * <p><b>Multi-feed column differences:</b> rapid-bus-kl and rapid-bus-mrtfeeder use different
 * column layouts and schema variants across all five GTFS files. All parsing uses header-row
 * column index maps rather than hardcoded positions to handle both feeds with one code path
 * and remain robust against future schema changes.
 *
 * <p><b>shape_dist_traveled:</b> rapid-bus-mrtfeeder provides this in both shapes.txt and
 * stop_times.txt (cumulative km). rapid-bus-kl does not. When absent, cumulative arc-length
 * is computed from shape point coordinates during startup.
 */
@Service
public class TransitService {

    private final Map<String, Stop> stopDirectory = new HashMap<>();
    private final Map<String, Route> routeDirectory = new HashMap<>();

    // key: "routeId_directionId"
    private final Map<String, LinkedList<String>> routePaths = new HashMap<>();

    // Parallel to routePaths: cumulative road distance in km from route start to each stop.
    // key: "routeId_directionId", value: list of distances indexed same as routePaths.
    private final Map<String, List<Double>> stopCumulativeDistances = new HashMap<>();

    // key: shape_id, value: ordered list of [lat, lon, cumulativeDistKm] points.
    // The third element is the cumulative arc-length from the shape start.
    // Only populated during startup; not accessed at request time.
    private final Map<String, List<double[]>> shapePolylines = new HashMap<>();

    // key: "routeId_directionId", value: shape_id
    private final Map<String, String> routeDirectionToShapeId = new HashMap<>();

    private final Map<String, List<String>> stopGraph = new HashMap<>();

    private static final String[] FEED_DIRECTORIES = {
            "data/rapid-bus-kl",
            "data/rapid-bus-mrtfeeder"
    };

    /** Runs once on startup. Loads all feeds sequentially and logs a summary. */
    @PostConstruct
    public void initializeStaticData() {
        System.out.println("Initialising static GTFS data...");

        for (String folder : FEED_DIRECTORIES) {
            System.out.println("➡️  Loading: " + folder);
            try {
                loadStops(folder);
                loadRoutes(folder);
                loadShapes(folder);
                buildGraphsAndPaths(folder);
            } catch (Exception e) {
                System.err.println("❌ Failed to load [" + folder + "]: " + e.getMessage());
            }
        }

        // Shape polylines are no longer needed after distances are computed.
        shapePolylines.clear();

        System.out.println("✅ Stops loaded:            " + stopDirectory.size());
        System.out.println("✅ Routes loaded:           " + routeDirectory.size());
        System.out.println("✅ Route paths loaded:      " + routePaths.size());
        System.out.println("✅ Stop distances computed: " + stopCumulativeDistances.size());
        System.out.println("✅ Graph vertices:          " + stopGraph.size());
    }

    // -------------------------------------------------------------------------
    // Header-row column index resolution
    // -------------------------------------------------------------------------

    /**
     * Reads the header row of a CSV file and returns a map of column name → zero-based index.
     * Used by all load methods instead of hardcoded column positions, so both feed schemas
     * are handled with one code path.
     */
    private Map<String, Integer> parseColumnIndices(String[] headerRow) {
        Map<String, Integer> cols = new HashMap<>();
        for (int i = 0; i < headerRow.length; i++) {
            cols.put(headerRow[i].trim().toLowerCase(), i);
        }
        return cols;
    }

    private String col(String[] row, Map<String, Integer> cols, String name) {
        Integer idx = cols.get(name);
        return (idx != null && idx < row.length) ? row[idx].trim() : "";
    }

    // -------------------------------------------------------------------------
    // Static data loaders
    // -------------------------------------------------------------------------

    /**
     * Parses stops.txt and populates stopDirectory.
     *
     * <p>rapid-bus-kl:        stop_id, stop_name, stop_desc, stop_lat, stop_lon
     * <p>rapid-bus-mrtfeeder: stop_id, stop_code, stop_name, stop_lat, stop_lon
     *
     * Header parsing resolves the correct name/lat/lon columns for each feed.
     */
    private void loadStops(String folderPath) throws Exception {
        String category = folderPath.substring(folderPath.lastIndexOf('/') + 1);
        ClassPathResource resource = new ClassPathResource(folderPath + "/stops.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Map<String, Integer> cols = parseColumnIndices(reader.readNext());
            String[] row;
            while ((row = reader.readNext()) != null) {
                String id = col(row, cols, "stop_id");
                String name = col(row, cols, "stop_name");
                double lat = Double.parseDouble(col(row, cols, "stop_lat"));
                double lon = Double.parseDouble(col(row, cols, "stop_lon"));

                if (stopDirectory.containsKey(id)) {
                    System.out.println("⚠️  Stop ID collision: " + id + " — overwriting with definition from " + category);
                }
                stopDirectory.put(id, new Stop(id, name, lat, lon, category));
            }
        }
    }

    /**
     * Parses routes.txt and populates routeDirectory.
     *
     * <p>rapid-bus-kl:        route_id, agency_id, route_short_name, route_long_name, ...
     * <p>rapid-bus-mrtfeeder: route_id, agency_id, (blank), route_short_name, route_long_name(=type), ...
     *
     * MRT Feeder routes have no meaningful long name — short name is used for both fields.
     */
    private void loadRoutes(String folderPath) throws Exception {
        ClassPathResource resource = new ClassPathResource(folderPath + "/routes.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Map<String, Integer> cols = parseColumnIndices(reader.readNext());
            String[] row;
            while ((row = reader.readNext()) != null) {
                String id = col(row, cols, "route_id");
                String name = col(row, cols, "route_short_name");
                String longName = col(row, cols, "route_long_name");

                // MRT Feeder short names are blank — fall back to long name.
                if (name.isEmpty()) name = longName;

                if (routeDirectory.containsKey(id)) {
                    System.out.println("⚠️  Route ID collision: " + id + " — overwriting with updated definition.");
                }
                routeDirectory.put(id, new Route(id, name, longName));
            }
        }
    }

    /**
     * Parses shapes.txt and builds shapePolylines.
     *
     * <p>Each shape is stored as an ordered list of [lat, lon, cumulativeDistKm] triples.
     * For rapid-bus-mrtfeeder, shape_dist_traveled is read directly from the file.
     * For rapid-bus-kl (where it is absent), cumulative distance is computed by summing
     * Haversine distances between consecutive shape points.
     */
    private void loadShapes(String folderPath) throws Exception {
        ClassPathResource resource = new ClassPathResource(folderPath + "/shapes.txt");

        // Temporary map: shape_id → unsorted list of [sequence, lat, lon, distKm]
        Map<String, List<double[]>> rawPoints = new HashMap<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Map<String, Integer> cols = parseColumnIndices(reader.readNext());
            boolean hasDistTraveled = cols.containsKey("shape_dist_traveled");

            String[] row;
            while ((row = reader.readNext()) != null) {
                String shapeId = col(row, cols, "shape_id");
                double lat = Double.parseDouble(col(row, cols, "shape_pt_lat"));
                double lon = Double.parseDouble(col(row, cols, "shape_pt_lon"));
                double seq = Double.parseDouble(col(row, cols, "shape_pt_sequence"));
                double dist = hasDistTraveled ? Double.parseDouble(col(row, cols, "shape_dist_traveled")) : -1;

                rawPoints.computeIfAbsent(shapeId, k -> new ArrayList<>())
                         .add(new double[]{seq, lat, lon, dist});
            }
        }

        // Sort by sequence and compute arc-length where shape_dist_traveled is absent.
        for (Map.Entry<String, List<double[]>> entry : rawPoints.entrySet()) {
            List<double[]> points = entry.getValue();
            points.sort(Comparator.comparingDouble(p -> p[0]));

            List<double[]> polyline = new ArrayList<>(points.size());
            double cumDist = 0.0;

            for (int i = 0; i < points.size(); i++) {
                double[] p = points.get(i);
                double lat = p[1], lon = p[2];

                if (p[3] >= 0) {
                    // shape_dist_traveled available — use it directly (already in km).
                    cumDist = p[3];
                } else if (i > 0) {
                    // Compute arc-length from the previous point.
                    double[] prev = polyline.get(i - 1);
                    cumDist += haversineDistanceKm(prev[0], prev[1], lat, lon);
                }

                polyline.add(new double[]{lat, lon, cumDist});
            }

            shapePolylines.put(entry.getKey(), polyline);
        }
    }

    /**
     * Selects one representative trip per route-direction from trips.txt,
     * records the shape_id per route-direction, populates route headsigns,
     * and initialises routePaths entries.
     *
     * <p>Returns tripId → [routeId, directionId, shapeId] for use by buildGraphsAndPaths.
     */
    private Map<String, String[]> loadRepresentativeTrips(String folderPath) throws Exception {
        Map<String, String[]> tripToRouteDirection = new HashMap<>();
        Set<String> seenRouteDirections = new HashSet<>();

        ClassPathResource resource = new ClassPathResource(folderPath + "/trips.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Map<String, Integer> cols = parseColumnIndices(reader.readNext());
            String[] row;
            while ((row = reader.readNext()) != null) {
                String routeId = col(row, cols, "route_id");
                String tripId = col(row, cols, "trip_id");
                String directionId = col(row, cols, "direction_id");
                String shapeId = col(row, cols, "shape_id");
                String headsign = col(row, cols, "trip_headsign");

                String pathKey = routeId + "_" + directionId;
                if (seenRouteDirections.add(pathKey)) {
                    tripToRouteDirection.put(tripId, new String[]{routeId, directionId, shapeId});
                    routePaths.putIfAbsent(pathKey, new LinkedList<>());
                    routeDirectionToShapeId.put(pathKey, shapeId);

                    // Populate headsign on the Route object for this direction.
                    Route route = routeDirectory.get(routeId);
                    if (route != null && !headsign.isEmpty()) {
                        if ("0".equals(directionId)) route.setHeadsignOutbound(headsign);
                        else route.setHeadsignInbound(headsign);
                    }
                }
            }
        }
        return tripToRouteDirection;
    }

    /**
     * Parses stop_times.txt to build route path LinkedLists, the adjacency graph,
     * and — where available — stop cumulative distances from shape_dist_traveled.
     *
     * <p>For feeds that provide shape_dist_traveled in stop_times.txt (rapid-bus-mrtfeeder),
     * stop distances are read directly. For those that don't (rapid-bus-kl), distances are
     * computed in {@link #computeStopDistancesFromShapes} after this method returns.
     */
    private void buildGraphsAndPaths(String folderPath) throws Exception {
        Map<String, String[]> targetTrips = loadRepresentativeTrips(folderPath);
        ClassPathResource resource = new ClassPathResource(folderPath + "/stop_times.txt");

        // Temporary map accumulating stop distances per path key when shape_dist_traveled is available.
        Map<String, List<Double>> distanceAccumulator = new HashMap<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            Map<String, Integer> cols = parseColumnIndices(reader.readNext());
            boolean hasDistTraveled = cols.containsKey("shape_dist_traveled");

            String previousStopId = null;
            String currentTripId = null;

            String[] row;
            while ((row = reader.readNext()) != null) {
                String tripId = col(row, cols, "trip_id");
                String stopId = col(row, cols, "stop_id");

                if (!targetTrips.containsKey(tripId)) continue;

                // Reset boundary tracking when entering a new trip.
                if (!tripId.equals(currentTripId)) {
                    previousStopId = null;
                    currentTripId = tripId;
                }

                String[] routeDirection = targetTrips.get(tripId);
                String pathKey = routeDirection[0] + "_" + routeDirection[1];

                LinkedList<String> path = routePaths.get(pathKey);
                if (path.isEmpty() || !path.getLast().equals(stopId)) {
                    path.add(stopId);

                    if (hasDistTraveled) {
                        String distStr = col(row, cols, "shape_dist_traveled");
                        double dist = distStr.isEmpty() ? 0.0 : Double.parseDouble(distStr);
                        distanceAccumulator.computeIfAbsent(pathKey, k -> new ArrayList<>()).add(dist);
                    }
                }

                if (previousStopId != null) {
                    stopGraph.computeIfAbsent(previousStopId, k -> new ArrayList<>()).add(stopId);
                }

                previousStopId = stopId;
            }
        }

        // Commit directly-read distances (MRT feeder feeds).
        stopCumulativeDistances.putAll(distanceAccumulator);

        // For feeds without shape_dist_traveled (rapid-bus-kl), project stops onto the shape polyline.
        for (Map.Entry<String, String[]> entry : targetTrips.entrySet()) {
            String[] routeDirection = entry.getValue();
            String pathKey = routeDirection[0] + "_" + routeDirection[1];

            if (!stopCumulativeDistances.containsKey(pathKey)) {
                String shapeId = routeDirection[2];
                computeStopDistancesFromShapes(pathKey, shapeId);
            }
        }
    }

    /**
     * Projects each stop in a route-direction's path onto the shape polyline and records
     * its cumulative road distance from the route start.
     *
     * <p>For each stop, we find the shape point with the minimum Haversine distance to the
     * stop's GPS coordinates and use that point's cumulative distance as the stop's position
     * on the route. This is O(stops × shape_points) per route-direction and runs once at startup.
     *
     * <p>If the shape polyline is unavailable, the stop list is stored with evenly-spaced
     * placeholder distances so hasPassed() degrades to index comparison rather than failing.
     */
    private void computeStopDistancesFromShapes(String pathKey, String shapeId) {
        LinkedList<String> path = routePaths.get(pathKey);
        if (path == null || path.isEmpty()) return;

        List<double[]> polyline = shapePolylines.get(shapeId);
        List<Double> distances = new ArrayList<>(path.size());

        if (polyline == null || polyline.isEmpty()) {
            // No shape available — assign index-based placeholders.
            for (int i = 0; i < path.size(); i++) distances.add((double) i);
            stopCumulativeDistances.put(pathKey, distances);
            return;
        }

        for (String stopId : path) {
            Stop stop = stopDirectory.get(stopId);
            if (stop == null) {
                distances.add(distances.isEmpty() ? 0.0 : distances.get(distances.size() - 1));
                continue;
            }

            // Find the closest shape point to this stop.
            double minDist = Double.MAX_VALUE;
            double closestCumDist = 0.0;
            for (double[] point : polyline) {
                double d = haversineDistanceKm(stop.getLatitude(), stop.getLongitude(), point[0], point[1]);
                if (d < minDist) {
                    minDist = d;
                    closestCumDist = point[2];
                }
            }

            distances.add(closestCumDist);
        }

        stopCumulativeDistances.put(pathKey, distances);
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /** Case-insensitive name search across all stops. Returns up to 10 matches. */
    public List<Stop> searchStops(String query) {
        List<Stop> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (Stop stop : stopDirectory.values()) {
            if (stop.getName().toLowerCase().contains(lowerQuery)) {
                results.add(stop);
                if (results.size() >= 10) break;
            }
        }
        return results;
    }

    /** Case-insensitive name search across all routes (short name and long name). Returns up to 10 matches. */
    public List<Route> searchRoutes(String query) {
        List<Route> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();
        for (Route route : routeDirectory.values()) {
            if (route.getName().toLowerCase().contains(lowerQuery)
                    || (route.getLongName() != null && route.getLongName().toLowerCase().contains(lowerQuery))) {
                results.add(route);
                if (results.size() >= 10) break;
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Geometry helpers
    // -------------------------------------------------------------------------

    /** Haversine distance in kilometers. */
    private double haversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    public Stop getStopById(String id) { return stopDirectory.get(id); }
    public Route getRouteById(String id) { return routeDirectory.get(id); }

    /**
     * Returns the ordered stop ID sequence for a route and direction.
     * @param directionId 0 = outbound, 1 = inbound.
     */
    public LinkedList<String> getRoutePath(String routeId, int directionId) {
        return routePaths.get(routeId + "_" + directionId);
    }

    /**
     * Returns the outbound (direction 0) stop sequence for a route.
     * Used by GET /routes/{routeId}/path for map polyline rendering.
     */
    public LinkedList<String> getRoutePath(String routeId) {
        return routePaths.get(routeId + "_0");
    }

    /**
     * Returns the pre-computed cumulative road distances (km) from route start to each stop,
     * indexed parallel to the list returned by {@link #getRoutePath(String, int)}.
     *
     * @param routeId     Route ID as stored in routes.txt.
     * @param directionId 0 = outbound, 1 = inbound.
     * @return List of cumulative distances, or null if not available for this route/direction.
     */
    public List<Double> getStopCumulativeDistances(String routeId, int directionId) {
        return stopCumulativeDistances.get(routeId + "_" + directionId);
    }

    public List<String> getAdjacentStops(String stopId) {
        return stopGraph.getOrDefault(stopId, Collections.emptyList());
    }
}
