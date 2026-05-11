package com.wherebus.services;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for fetching, parsing, and storing live vehicle GPS data.
 * Acts as the real-time heartbeat of the application.
 */
@Service
public class LiveTrackingService {

    // A thread-safe Hash Table to store actively moving buses.
    // Key: Vehicle ID (or Trip ID), Value: The latest VehiclePosition Protobuf object
    private final Map<String, VehiclePosition> activeVehicles = new ConcurrentHashMap<>();

    // TODO: Replace with the actual Prasarana GTFS-RT Vehicle Positions URL
    private static final String LIVE_FEED_URL = "https://api.prasarana.my/gtfs-realtime/vehicle-positions";

    /**
     * The Heartbeat Worker.
     * Runs automatically every 30 seconds (30,000 milliseconds) to fetch fresh GPS data.
     */
    @Scheduled(fixedRate = 30000)
    public void refreshLiveVehiclePositions() {
        System.out.println("⏳ [Heartbeat] Fetching live bus coordinates...");

        try {
            URL url = new URI(LIVE_FEED_URL).toURL();

            // Open the binary stream directly from the web
            try (InputStream inputStream = url.openStream()) {

                // Let the MobilityData Protobuf library decode the binary stream instantly
                FeedMessage feed = FeedMessage.parseFrom(inputStream);

                int updatedCount = 0;

                // Loop through every entity (bus) currently broadcasting in the feed
                for (FeedEntity entity : feed.getEntityList()) {
                    if (entity.hasVehicle()) {
                        VehiclePosition vehicle = entity.getVehicle();

                        // We use the vehicle's unique ID as our Hash Table key
                        String vehicleId = vehicle.getVehicle().getId();

                        // Thread-safe update of the bus's latest location, speed, and trip status
                        activeVehicles.put(vehicleId, vehicle);
                        updatedCount++;
                    }
                }

                System.out.println("✅ [Heartbeat] Successfully updated " + updatedCount + " active buses in memory.");
            }

        } catch (Exception e) {
            System.err.println("❌ [Heartbeat] Failed to fetch live GTFS-RT feed: " + e.getMessage());
            // In a production app, you might trigger a retry or alert logs here
        }
    }

    /**
     * O(1) Lookup to get all currently active vehicles for our API endpoints.
     */
    public Map<String, VehiclePosition> getActiveVehicles() {
        return activeVehicles;
    }

    /**
     * O(1) Lookup to find a specific bus's live details.
     */
    public VehiclePosition getVehicleById(String vehicleId) {
        return activeVehicles.get(vehicleId);
    }
}