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
            // loadRoutes(); // TODO: implement loadRoutes
        } catch (Exception e) {
            System.err.println("Failed to load static transit data: " + e.getMessage());
        }

        System.out.println("Loaded " + stopDirectory.size() + " stops into the Hash Table.");
    }

    /**
     * Reads stops.txt from the resources folder and populates the stopDirectory hash table
     * Expects CSV format: stop_id,stop_name,stop_lat,stop_lon
     */
    private void loadStops() throws Exception {
        // ClassPathResource safely finds files inside src/main/resources/
        ClassPathResource resource = new ClassPathResource("data/stops.txt");

        // Example row: 1005840,STESEN BRT SETIA JAYA,BRT LALUAN SUNWAY,3.082856,101.612238

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