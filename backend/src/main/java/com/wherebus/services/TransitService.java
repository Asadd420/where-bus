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
 * The core service responsible for holding the transit network in memory.
 * Implements the required data structures (Hash Tables, Graphs, Linked Lists)
 * to store and traverse static routing data.
 */
@Service
public class TransitService {

    // Hash tables for O(1) fast lookup of stops and routes by their ID
    private final Map<String, Stop> stopDirectory = new HashMap<>();
    private final Map<String, Route> routeDirectory = new HashMap<>();

    // Linked lists to store the strictly ordered sequence of stops for a specific route
    // Key: RouteID (e.g., "T789"), Value: Linked List of StopIDs in order
    private final Map<String, LinkedList<String>> routePaths = new HashMap<>();

    // Graph (adjacency list) to map the physical network connections
    // Key: StopID, Value: List of adjacent StopIDs that can be reached directly from the key
    private final Map<String, List<String>> stopGraph = new HashMap<>();

    /**
     * This method runs automatically exactly once when the Spring Boot server starts. (post construct)
     * It is responsible for loading the static GTFS files into the data structures.
     */
    @PostConstruct
    public void initializeStaticData() {
        System.out.println("Server started. Initializing Transit Data Structures...");

        try {
            loadStops();
            loadRoutes();
            buildGraphsAndLists();
        } catch (Exception e) {
            System.err.println("Failed to load static transit data: " + e.getMessage());
        }

        System.out.println("Loaded " + stopDirectory.size() + " stops into the hash table.");
        System.out.println("Loaded " + routeDirectory.size() + " routes into the hash table.");
        System.out.println("Loaded " + routePaths.size() + " route paths into the list.");
        System.out.println("Loaded " + stopGraph.size() + " vertex connections into the adjacency graph.");
    }

    /**
     * Reads stops.txt from the resources folder and populates the stopDirectory hash table
     * Header: stop_id,stop_name,stop_desc,stop_lat,stop_lon
     * Example: 1005840,STESEN BRT SETIA JAYA,BRT LALUAN SUNWAY,3.082856,101.612238
     */
    private void loadStops() throws Exception {
        // ClassPathResource safely finds files inside src/main/resources/
        ClassPathResource resource = new ClassPathResource("data/stops.txt");


        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String[] nextLine;

            reader.skip(1);  // Skip header line

            while ((nextLine = reader.readNext()) != null) {
                String id = nextLine[0].trim();
                String name = nextLine[1].trim() + ", " +  nextLine[2].trim();
                double lat = Double.parseDouble(nextLine[3]);
                double lon = Double.parseDouble(nextLine[4]);

                // Create the Model and put it into the hash table
                Stop stop = new Stop(id, name, lat, lon);
                stopDirectory.put(id, stop);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Reads routes.txt from the resources folder and populates the routeDirectory hash table
     * Header: route_id,agency_id,route_short_name,route_long_name,route_type,route_color,route_text_color
     * Example: B1000,rapidkl,SUNWAY LINE,BRT USJ 7 - BRT Setia Jaya,3,21618C,FFFFFF
     */
    private void loadRoutes() {
        ClassPathResource resource = new ClassPathResource("data/routes.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String[] nextLine;
            reader.skip(1);  // Skip header line

            while ((nextLine = reader.readNext()) != null) {
                String id = nextLine[0].trim();
                String name = nextLine[2].trim();
                String longName = nextLine[3].trim();
                Route route = new Route(id, name, longName);
                routeDirectory.put(id, route);
            }
        } catch (Exception e) {
            System.err.println("Error reading routes.txt: " + e.getMessage());
        }
    }

    /**
     * Reads trips.txt to find representative trip_ids for each route.
     * Captures TWO trips per route (one for direction 0, one for direction 1)
     * to ensure the graph maps both sides of the street.
     */
    private Map<String, String> loadRepresentativeTrips() {
        Map<String, String> tripToRouteMap = new HashMap<>();

        // Tracks which route+direction combos we've already found
        Set<String> processedRouteDirections = new HashSet<>();

        ClassPathResource resource = new ClassPathResource("data/trips.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String[] nextLine;
            reader.skip(1); // Skip header

            while ((nextLine = reader.readNext()) != null) {
                String routeId = nextLine[0].trim();
                String tripId = nextLine[2].trim();
                String directionId = nextLine[4].trim(); // Usually 0 or 1

                String uniqueRouteDirection = routeId + "_" + directionId;

                if (!processedRouteDirections.contains(uniqueRouteDirection)) {
                    tripToRouteMap.put(tripId, routeId);
                    processedRouteDirections.add(uniqueRouteDirection);

                    routePaths.putIfAbsent(routeId, new LinkedList<>());
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading trips.txt: " + e.getMessage());
        }
        return tripToRouteMap;
    }

    /**
     * Reads stop_times.txt and builds the Linked Lists and Graphs.
     * Parses instantly by skipping rows that don't belong to our target trips.
     */
    private void buildGraphsAndLists() {
        Map<String, String> targetTrips = loadRepresentativeTrips();
        ClassPathResource resource = new ClassPathResource("data/stop_times.txt");

        try (CSVReader reader = new CSVReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String[] nextLine;
            reader.skip(1); // Skip header

            String previousStopId = null;
            String currentTripId = null;

            while ((nextLine = reader.readNext()) != null) {
                String tripId = nextLine[0].trim();
                String stopId = nextLine[3].trim();

                // Fast-fail: Skip 99% of the file that we don't need
                if (!targetTrips.containsKey(tripId)) {
                    continue;
                }

                String routeId = targetTrips.get(tripId);

                // 1. Add to the Linked List (prevents duplicate stops if both directions share one)
                LinkedList<String> path = routePaths.get(routeId);
                if (path.isEmpty() || !path.getLast().equals(stopId)) {
                    path.add(stopId);
                }

                // 2. Build the Adjacency Graph
                // If we are on the same continuous trip, connect the previous stop to this one
                if (tripId.equals(currentTripId) && previousStopId != null) {
                    stopGraph.computeIfAbsent(previousStopId, k -> new ArrayList<>()).add(stopId);
                }

                previousStopId = stopId;
                currentTripId = tripId;
            }
        } catch (Exception e) {
            System.err.println("Error reading stop_times.txt: " + e.getMessage());
        }
    }

    /**
     * Searches for stops matching the query string.
     * Limits to 10 results to keep the JSON payload small.
     */
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

    /**
     * Searches for routes matching the query string.
     */
    public List<Route> searchRoutes(String query) {
        List<Route> results = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (Route route : routeDirectory.values()) {
            if (route.getName().toLowerCase().contains(lowerQuery) ||
                    (route.getLongName() != null && route.getLongName().toLowerCase().contains(lowerQuery))) {
                results.add(route);
                if (results.size() >= 10) break;
            }
        }
        return results;
    }

    public Stop getStopById(String id) {
        return stopDirectory.get(id);
    }

    public Route getRouteById(String id) {
        return routeDirectory.get(id);
    }

    public LinkedList<String> getRoutePath(String routeId) {
        return routePaths.get(routeId);
    }

    public List<String> getAdjacentStops(String stopId) {
        return stopGraph.getOrDefault(stopId, new ArrayList<>());
    }
}