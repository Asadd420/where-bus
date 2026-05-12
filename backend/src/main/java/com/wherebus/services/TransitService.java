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
 * <p>Data structures:
 * <ul>
 *   <li>{@code stopDirectory} / {@code routeDirectory} — HashMap for O(1) ID lookups.
 *       Safe without synchronisation because neither map is mutated after startup.</li>
 *   <li>{@code routePaths} — LinkedList per route preserving the ordered stop sequence
 *       as defined in stop_times.txt.</li>
 *   <li>{@code stopGraph} — Adjacency list mapping each stop to its direct successors,
 *       built from representative trips in both feed directions.</li>
 * </ul>
 *
 * <p>Two feeds are merged: {@code rapid-bus-kl} and {@code rapid-bus-mrtfeeder}.
 * When a stop or route ID appears in both, the later feed wins and a warning is logged.
 */
@Service
public class TransitService {

    private final Map<String, Stop> stopDirectory = new HashMap<>();
    private final Map<String, Route> routeDirectory = new HashMap<>();
    private final Map<String, LinkedList<String>> routePaths = new HashMap<>();
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
                buildGraphsAndPaths(folder);
            } catch (Exception e) {
                System.err.println("❌ Failed to load [" + folder + "]: " + e.getMessage());
            }
        }

        System.out.println("✅ Stops loaded:       " + stopDirectory.size());
        System.out.println("✅ Routes loaded:      " + routeDirectory.size());
        System.out.println("✅ Route paths loaded: " + routePaths.size());
        System.out.println("✅ Graph vertices:     " + stopGraph.size());
    }

    /** Parses stops.txt and populates stopDirectory. Tags each stop with its source feed. */
    private void loadStops(String folderPath) throws Exception {
        String category = folderPath.substring(folderPath.lastIndexOf('/') + 1);
        ClassPathResource resource = new ClassPathResource(folderPath + "/stops.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.skip(1);
            String[] row;
            while ((row = reader.readNext()) != null) {
                String id = row[0].trim();
                String name = row[1].trim() + ", " + row[2].trim();
                double lat = Double.parseDouble(row[3]);
                double lon = Double.parseDouble(row[4]);

                if (stopDirectory.containsKey(id)) {
                    System.out.println("⚠️  Stop ID collision: " + id + " — overwriting with definition from " + category);
                }

                stopDirectory.put(id, new Stop(id, name, lat, lon, category));
            }
        }
    }

    /** Parses routes.txt and populates routeDirectory. */
    private void loadRoutes(String folderPath) throws Exception {
        ClassPathResource resource = new ClassPathResource(folderPath + "/routes.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.skip(1);
            String[] row;
            while ((row = reader.readNext()) != null) {
                String id = row[0].trim();
                String name = row[2].trim();
                String longName = row[3].trim();

                if (name.isEmpty()) {
                    name = longName;
                }

                if (routeDirectory.containsKey(id)) {
                    System.out.println("⚠️  Route ID collision: " + id + " — overwriting with updated definition.");
                }

                routeDirectory.put(id, new Route(id, name, longName));
            }
        }
    }

    /**
     * Selects one representative trip per route-direction pair from trips.txt.
     * This limits stop_times.txt parsing to a manageable subset while still capturing
     * both the outbound and inbound stop sequences for each route.
     */
    private Map<String, String> loadRepresentativeTrips(String folderPath) throws Exception {
        Map<String, String> tripToRoute = new HashMap<>();
        Set<String> seenRouteDirections = new HashSet<>();

        ClassPathResource resource = new ClassPathResource(folderPath + "/trips.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.skip(1);
            String[] row;
            while ((row = reader.readNext()) != null) {
                String routeId = row[0].trim();
                String tripId = row[2].trim();
                String directionId = row[4].trim();

                String key = routeId + "_" + directionId;
                if (seenRouteDirections.add(key)) {
                    tripToRoute.put(tripId, routeId);
                    routePaths.putIfAbsent(routeId, new LinkedList<>());
                }
            }
        }
        return tripToRoute;
    }

    /**
     * Parses stop_times.txt to build route path LinkedLists and the stop adjacency graph.
     *
     * <p>Only rows belonging to the representative trips selected by
     * {@link #loadRepresentativeTrips} are processed.
     *
     * <p>Graph edges connect consecutive stops within the same trip.
     * {@code previousStopId} is reset to null whenever the trip changes to prevent
     * a spurious edge from the last stop of one trip to the first stop of the next.
     */
    private void buildGraphsAndPaths(String folderPath) throws Exception {
        Map<String, String> targetTrips = loadRepresentativeTrips(folderPath);
        ClassPathResource resource = new ClassPathResource(folderPath + "/stop_times.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            reader.skip(1);

            String previousStopId = null;
            String currentTripId = null;

            String[] row;
            while ((row = reader.readNext()) != null) {
                String tripId = row[0].trim();
                String stopId = row[3].trim();

                if (!targetTrips.containsKey(tripId)) continue;

                // Reset the previous stop pointer when entering a new trip so we don't
                // draw a graph edge between the last stop of the old trip and the first
                // stop of the new one.
                if (!tripId.equals(currentTripId)) {
                    previousStopId = null;
                    currentTripId = tripId;
                }

                String routeId = targetTrips.get(tripId);

                // Append to route path, avoiding duplicates at the tail caused by multiple
                // trips sharing a stop sequence.
                LinkedList<String> path = routePaths.get(routeId);
                if (path.isEmpty() || !path.getLast().equals(stopId)) {
                    path.add(stopId);
                }

                // Add directed graph edge from the previous stop to this one.
                if (previousStopId != null) {
                    stopGraph.computeIfAbsent(previousStopId, k -> new ArrayList<>()).add(stopId);
                }

                previousStopId = stopId;
            }
        }
    }

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

    public Stop getStopById(String id) { return stopDirectory.get(id); }
    public Route getRouteById(String id) { return routeDirectory.get(id); }
    public LinkedList<String> getRoutePath(String routeId) { return routePaths.get(routeId); }
    public List<String> getAdjacentStops(String stopId) { return stopGraph.getOrDefault(stopId, Collections.emptyList()); }
}
