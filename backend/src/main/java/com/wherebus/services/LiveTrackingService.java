package com.wherebus.services;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls Prasarana's GTFS-Realtime feeds every 30 seconds and maintains
 * a merged, thread-safe snapshot of all active vehicle positions.
 */
@Service
public class LiveTrackingService {

    // ConcurrentHashMap so the @Scheduled writer and HTTP request readers never block each other.
    // Key: vehicleId, Value: latest VehiclePosition from the Protobuf feed.
    private final Map<String, VehiclePosition> activeVehicles = new ConcurrentHashMap<>();

    private static final String[] LIVE_FEED_URLS = {
            "https://api.data.gov.my/gtfs-realtime/vehicle-position/prasarana?category=rapid-bus-kl",
            "https://api.data.gov.my/gtfs-realtime/vehicle-position/prasarana?category=rapid-bus-mrtfeeder"
    };

    /** Fetches and merges both Prasarana feeds into the active vehicle map. Runs every 30 seconds. */
    @Scheduled(fixedRate = 30000)
    public void refreshVehiclePositions() {
        System.out.println("⏳ Polling GTFS-RT feeds...");
        int count = 0;

        for (String feedUrl : LIVE_FEED_URLS) {
            try {
                URL url = new URI(feedUrl).toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "WhereBus/2.0");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream inputStream = connection.getInputStream()) {
                        FeedMessage feed = FeedMessage.parseFrom(inputStream);
                        for (FeedEntity entity : feed.getEntityList()) {
                            if (entity.hasVehicle()) {
                                VehiclePosition vehicle = entity.getVehicle();
                                activeVehicles.put(vehicle.getVehicle().getId(), vehicle);
                                count++;
                            }
                        }
                    }
                } else {
                    System.err.println("⚠️ Feed returned " + connection.getResponseCode() + ": " + feedUrl);
                }

                connection.disconnect();

            } catch (Exception e) {
                System.err.println("❌ Failed to fetch feed [" + feedUrl + "]: " + e.getMessage());
            }
        }

        System.out.println("✅ Fleet updated: " + count + " vehicles ingested.");
    }

    /**
     * Returns all vehicles currently on the given route.
     *
     * <p>Prasarana broadcasts route IDs with a trailing "0" (e.g. "T7890" for route "T789").
     * This method matches both the canonical ID and the suffixed variant so callers
     * never need to handle that quirk themselves.
     *
     * @param routeId Public route ID as stored in routes.txt (e.g. "T789").
     * @return List of vehicle payloads ready for the API response.
     */
    public List<Map<String, Object>> getVehiclesByRoute(String routeId) {
        List<Map<String, Object>> vehicles = new ArrayList<>();

        for (Map.Entry<String, VehiclePosition> entry : activeVehicles.entrySet()) {
            VehiclePosition v = entry.getValue();
            if (!v.hasTrip()) continue;

            String broadcastedId = v.getTrip().getRouteId();
            boolean matchesRoute = routeId.equalsIgnoreCase(broadcastedId)
                    || (routeId + "0").equalsIgnoreCase(broadcastedId);

            if (matchesRoute) {
                int directionId = v.getTrip().hasDirectionId() ? v.getTrip().getDirectionId() : 0;

                Map<String, Object> vehicle = new HashMap<>();
                vehicle.put("vehicleId", entry.getKey());
                vehicle.put("latitude", v.getPosition().getLatitude());
                vehicle.put("longitude", v.getPosition().getLongitude());
                vehicle.put("licensePlate", v.getVehicle().hasLicensePlate()
                        ? v.getVehicle().getLicensePlate()
                        : entry.getKey());
                vehicle.put("directionId", directionId);
                vehicle.put("directionLabel", directionId == 0 ? "outbound" : "inbound");

                vehicles.add(vehicle);
            }
        }

        return vehicles;
    }

    /**
     * Returns a debug snapshot of the current fleet state.
     * Used by the /debug-fleet endpoint to verify Prasarana's live broadcast structure.
     *
     * @return Map containing totalActiveBuses, uniqueRoutesActiveNow, and up to 5 sampleVehicleData entries.
     */
    public Map<String, Object> getFleetSnapshot() {
        Set<String> activeRouteIds = new HashSet<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        int sampleLimit = 5;

        for (Map.Entry<String, VehiclePosition> entry : activeVehicles.entrySet()) {
            VehiclePosition v = entry.getValue();
            String routeId = v.hasTrip() ? v.getTrip().getRouteId() : "NO_ROUTE_ID";
            activeRouteIds.add(routeId);

            if (samples.size() < sampleLimit) {
                Map<String, Object> sample = new HashMap<>();
                sample.put("vehicleId", entry.getKey());
                sample.put("broadcastedRouteId", routeId);
                sample.put("lat", v.getPosition().getLatitude());
                sample.put("lon", v.getPosition().getLongitude());
                samples.add(sample);
            }
        }

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("totalActiveBuses", activeVehicles.size());
        snapshot.put("uniqueRoutesActiveNow", activeRouteIds);
        snapshot.put("sampleVehicleData", samples);
        return snapshot;
    }

    /** Direct lookup for a single vehicle by ID. Returns null if not currently tracked. */
    public VehiclePosition getVehicleById(String vehicleId) {
        return activeVehicles.get(vehicleId);
    }

    /** Returns the raw active vehicle map. Use getVehiclesByRoute() for filtered access. */
    public Map<String, VehiclePosition> getActiveVehicles() {
        return activeVehicles;
    }
}
