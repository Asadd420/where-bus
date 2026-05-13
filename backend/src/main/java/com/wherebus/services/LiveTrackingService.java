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
 * Polls Prasarana's GTFS-Realtime feeds every 15 seconds and maintains
 * a merged, thread-safe snapshot of all active vehicle positions.
 *
 * <p>Speed derivation: Prasarana's {@code speed} field is broadcast in km/h despite the
 * GTFS-RT spec mandating m/s. The field is converted on read. Additionally, derived speed
 * is computed from consecutive position deltas using the {@code timestamp} field, which is
 * more reliable than the broadcast value and reflects actual recent movement.
 *
 * <p>Stale eviction: vehicles not seen in the last {@code STALE_THRESHOLD_MS} are removed
 * from the active map to prevent ghost buses from appearing in ETA results after a route ends.
 */
@Service
public class LiveTrackingService {

    // ConcurrentHashMap so the @Scheduled writer and HTTP request readers never block each other.
    // Key: vehicleId, Value: latest VehiclePosition from the Protobuf feed.
    private final Map<String, VehiclePosition> activeVehicles = new ConcurrentHashMap<>();

    // Tracks the last known [lat, lon, timestamp] per vehicle for derived speed computation.
    private final Map<String, double[]> previousPositions = new ConcurrentHashMap<>();

    // Derived speed in m/s computed from consecutive position deltas.
    // Preferred over the feed's speed field, which Prasarana broadcasts in km/h (non-standard).
    private final Map<String, Double> derivedSpeeds = new ConcurrentHashMap<>();

    // Vehicles not updated within this window are considered off-route and evicted.
    private static final long STALE_THRESHOLD_MS = 3 * 60 * 1000; // 3 minutes

    // Minimum plausible derived speed to store. Below this the bus is considered stationary.
    private static final double MIN_DERIVED_SPEED_MPS = 0.5;

    // Fallback speed (~11 km/h) when neither derived nor broadcast speed is usable.
    static final double DEFAULT_SPEED_MPS = 3.0;

    private static final String[] LIVE_FEED_URLS = {
            "https://api.data.gov.my/gtfs-realtime/vehicle-position/prasarana?category=rapid-bus-kl",
            "https://api.data.gov.my/gtfs-realtime/vehicle-position/prasarana?category=rapid-bus-mrtfeeder"
    };

    /** Fetches and merges both Prasarana feeds into the active vehicle map. Runs every 15 seconds. */
    @Scheduled(fixedRate = 15000)
    public void refreshVehiclePositions() {
        System.out.println("⏳ Polling GTFS-RT feeds...");
        int count = 0;
        long now = System.currentTimeMillis();

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
                            if (!entity.hasVehicle()) continue;

                            VehiclePosition vehicle = entity.getVehicle();
                            String vehicleId = vehicle.getVehicle().getId();

                            updateDerivedSpeed(vehicleId, vehicle);

                            activeVehicles.put(vehicleId, vehicle);
                            count++;
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

        evictStaleVehicles(now);
        System.out.println("✅ Fleet updated: " + count + " vehicles ingested, " + activeVehicles.size() + " active.");
    }

    /**
     * Computes derived speed from the delta between the previous and current position.
     * Stores the result in {@code derivedSpeeds} and updates {@code previousPositions}.
     *
     * <p>Only stored if the time delta is positive and the resulting speed is above
     * {@code MIN_DERIVED_SPEED_MPS} — zero or near-zero indicates a stationary bus,
     * which should fall through to the fallback rather than produce a near-infinite ETA.
     */
    private void updateDerivedSpeed(String vehicleId, VehiclePosition vehicle) {
        if (!vehicle.hasTimestamp()) return;

        double currLat = vehicle.getPosition().getLatitude();
        double currLon = vehicle.getPosition().getLongitude();
        long currTimestamp = vehicle.getTimestamp();

        double[] prev = previousPositions.get(vehicleId);
        if (prev != null) {
            double prevLat = prev[0];
            double prevLon = prev[1];
            long prevTimestamp = (long) prev[2];

            long deltaSeconds = currTimestamp - prevTimestamp;
            if (deltaSeconds > 0) {
                double distanceMeters = haversineDistance(prevLat, prevLon, currLat, currLon);
                double speedMps = distanceMeters / deltaSeconds;

                if (speedMps >= MIN_DERIVED_SPEED_MPS) {
                    derivedSpeeds.put(vehicleId, speedMps);
                } else {
                    // Bus is stationary or barely moving — remove stale derived speed so
                    // the fallback is used rather than a derived value from minutes ago.
                    derivedSpeeds.remove(vehicleId);
                }
            }
        }

        previousPositions.put(vehicleId, new double[]{currLat, currLon, currTimestamp});
    }

    /**
     * Removes vehicles whose last update timestamp is older than {@code STALE_THRESHOLD_MS}.
     * Prevents buses that have finished their route from persisting in ETA results.
     */
    private void evictStaleVehicles(long now) {
        long staleBeforeMs = now - STALE_THRESHOLD_MS;

        activeVehicles.entrySet().removeIf(entry -> {
            VehiclePosition v = entry.getValue();
            if (!v.hasTimestamp()) return false;
            boolean stale = (v.getTimestamp() * 1000L) < staleBeforeMs;
            if (stale) {
                previousPositions.remove(entry.getKey());
                derivedSpeeds.remove(entry.getKey());
            }
            return stale;
        });
    }

    /**
     * Returns the best available speed estimate for a vehicle in m/s.
     *
     * <p>Priority:
     * <ol>
     *   <li>Derived speed from consecutive position deltas (most reliable).</li>
     *   <li>Feed {@code speed} field divided by 3.6 — Prasarana broadcasts km/h
     *       despite the GTFS-RT spec mandating m/s.</li>
     *   <li>{@code DEFAULT_SPEED_MPS} fallback (~11 km/h).</li>
     * </ol>
     */
    public double getSpeedMps(String vehicleId, VehiclePosition vehicle) {
        Double derived = derivedSpeeds.get(vehicleId);
        if (derived != null) return derived;

        if (vehicle.getPosition().hasSpeed()) {
            double feedSpeedKmh = vehicle.getPosition().getSpeed();
            // Prasarana broadcasts km/h in the speed field. Convert to m/s.
            if (feedSpeedKmh > 1.0 && feedSpeedKmh < 120.0) {
                return feedSpeedKmh / 3.6;
            }
        }

        return DEFAULT_SPEED_MPS;
    }

    /**
     * Returns all vehicles currently on the given route, with GPS coordinates and direction.
     *
     * <p>Prasarana broadcasts route IDs with a trailing "0" (e.g. "T7890" for route "T789").
     * Both the canonical ID and the suffixed variant are matched here so callers never need
     * to handle that quirk themselves.
     */
    public List<Map<String, Object>> getVehiclesByRoute(String routeId) {
        List<Map<String, Object>> vehicles = new ArrayList<>();

        for (Map.Entry<String, VehiclePosition> entry : activeVehicles.entrySet()) {
            VehiclePosition v = entry.getValue();
            if (!v.hasTrip()) continue;

            String broadcastedId = v.getTrip().getRouteId();
            boolean matchesRoute = routeId.equalsIgnoreCase(broadcastedId)
                    || (routeId + "0").equalsIgnoreCase(broadcastedId);
            if (!matchesRoute) continue;

            int directionId = v.getTrip().hasDirectionId() ? v.getTrip().getDirectionId() : 0;

            Map<String, Object> vehicle = new HashMap<>();
            vehicle.put("vehicleId", entry.getKey());
            vehicle.put("latitude", v.getPosition().getLatitude());
            vehicle.put("longitude", v.getPosition().getLongitude());
            vehicle.put("bearing", v.getPosition().hasBearing() ? v.getPosition().getBearing() : null);
            vehicle.put("licensePlate", v.getVehicle().hasLicensePlate()
                    ? v.getVehicle().getLicensePlate() : entry.getKey());
            vehicle.put("directionId", directionId);
            vehicle.put("directionLabel", directionId == 0 ? "outbound" : "inbound");

            vehicles.add(vehicle);
        }

        return vehicles;
    }

    /**
     * Returns a debug snapshot of the current fleet state.
     * Used by GET /debug-fleet to verify Prasarana's live broadcast structure.
     */
    public Map<String, Object> getFleetSnapshot() {
        Set<String> activeRouteIds = new HashSet<>();
        List<Map<String, Object>> samples = new ArrayList<>();

        for (Map.Entry<String, VehiclePosition> entry : activeVehicles.entrySet()) {
            VehiclePosition v = entry.getValue();
            String routeId = v.hasTrip() ? v.getTrip().getRouteId() : "NO_ROUTE_ID";
            activeRouteIds.add(routeId);

            if (samples.size() < 5) {
                Map<String, Object> sample = new HashMap<>();
                sample.put("vehicleId", entry.getKey());
                sample.put("broadcastedRouteId", routeId);
                sample.put("lat", v.getPosition().getLatitude());
                sample.put("lon", v.getPosition().getLongitude());
                sample.put("derivedSpeedMps", derivedSpeeds.get(entry.getKey()));
                samples.add(sample);
            }
        }

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("totalActiveBuses", activeVehicles.size());
        snapshot.put("uniqueRoutesActiveNow", activeRouteIds);
        snapshot.put("sampleVehicleData", samples);
        return snapshot;
    }

    /** Haversine distance in meters between two GPS coordinates. */
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public VehiclePosition getVehicleById(String vehicleId) { return activeVehicles.get(vehicleId); }
    public Map<String, VehiclePosition> getActiveVehicles() { return activeVehicles; }
}
