package com.wherebus.services;

import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.wherebus.models.Stop;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes real-time ETAs for buses approaching a stop.
 * Uses Haversine distance and a min-heap to return arrivals sorted by time remaining.
 */
@Service
public class EtaCalculationService {

    private final TransitService transitService;
    private final LiveTrackingService liveTrackingService;

    // Fallback speed (~20 km/h) used when the vehicle feed doesn't report a live speed value.
    private static final double DEFAULT_BUS_SPEED_MPS = 5.5;

    public EtaCalculationService(TransitService transitService, LiveTrackingService liveTrackingService) {
        this.transitService = transitService;
        this.liveTrackingService = liveTrackingService;
    }

    /**
     * Returns all active buses on the given route, sorted by ascending ETA to the target stop.
     *
     * @param routeId     Public route ID (e.g. "T789"). Trailing-zero matching is handled by LiveTrackingService.
     * @param targetStopId Stop ID as defined in stops.txt (e.g. "1001410").
     * @return Ordered list of arrival predictions, or an empty list if the stop is unknown.
     */
    public List<Map<String, Object>> getArrivalsForStop(String routeId, String targetStopId) {
        Stop targetStop = transitService.getStopById(targetStopId);
        if (targetStop == null) {
            return Collections.emptyList();
        }

        PriorityQueue<ArrivalPrediction> heap = new PriorityQueue<>(
                Comparator.comparingInt(ArrivalPrediction::getSecondsRemaining)
        );

        Map<String, VehiclePosition> routeVehicles = liveTrackingService.getActiveVehicles();

        for (Map.Entry<String, VehiclePosition> entry : routeVehicles.entrySet()) {
            VehiclePosition vehicle = entry.getValue();
            if (!vehicle.hasTrip()) continue;

            String broadcastedRouteId = vehicle.getTrip().getRouteId();
            boolean matchesRoute = routeId.equalsIgnoreCase(broadcastedRouteId)
                    || (routeId + "0").equalsIgnoreCase(broadcastedRouteId);

            if (!matchesRoute) continue;

            double distanceMeters = haversineDistance(
                    vehicle.getPosition().getLatitude(),
                    vehicle.getPosition().getLongitude(),
                    targetStop.getLatitude(),
                    targetStop.getLongitude()
            );

            double speedMps = vehicle.getPosition().hasSpeed() && vehicle.getPosition().getSpeed() > 1.0
                    ? vehicle.getPosition().getSpeed()
                    : DEFAULT_BUS_SPEED_MPS;

            int secondsRemaining = (int) (distanceMeters / speedMps);
            int directionId = vehicle.getTrip().hasDirectionId() ? vehicle.getTrip().getDirectionId() : 0;
            String licensePlate = vehicle.getVehicle().hasLicensePlate()
                    ? vehicle.getVehicle().getLicensePlate()
                    : entry.getKey();

            heap.offer(new ArrivalPrediction(entry.getKey(), licensePlate, distanceMeters, secondsRemaining, directionId));
        }

        List<Map<String, Object>> arrivals = new ArrayList<>();
        while (!heap.isEmpty()) {
            ArrivalPrediction p = heap.poll();

            Map<String, Object> payload = new HashMap<>();
            payload.put("vehicleId", p.getVehicleId());
            payload.put("licensePlate", p.getLicensePlate());
            payload.put("distanceMeters", Math.round(p.getDistanceMeters()));
            payload.put("etaSeconds", p.getSecondsRemaining());
            payload.put("etaFormatted", formatEta(p.getSecondsRemaining()));
            payload.put("directionId", p.getDirectionId());
            payload.put("directionLabel", p.getDirectionId() == 0 ? "outbound" : "inbound");

            arrivals.add(payload);
        }

        return arrivals;
    }

    /**
     * Calculates the great-circle distance between two GPS coordinates using the Haversine formula.
     * Returns distance in meters.
     */
    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String formatEta(int totalSeconds) {
        if (totalSeconds < 60) return "Arriving";
        return (totalSeconds / 60) + " min";
    }

    /** Internal heap entry representing a single bus's predicted arrival at the target stop. */
    private static class ArrivalPrediction {
        private final String vehicleId;
        private final String licensePlate;
        private final double distanceMeters;
        private final int secondsRemaining;
        private final int directionId;

        public ArrivalPrediction(String vehicleId, String licensePlate, double distanceMeters,
                                  int secondsRemaining, int directionId) {
            this.vehicleId = vehicleId;
            this.licensePlate = licensePlate;
            this.distanceMeters = distanceMeters;
            this.secondsRemaining = secondsRemaining;
            this.directionId = directionId;
        }

        public String getVehicleId() { return vehicleId; }
        public String getLicensePlate() { return licensePlate; }
        public double getDistanceMeters() { return distanceMeters; }
        public int getSecondsRemaining() { return secondsRemaining; }
        public int getDirectionId() { return directionId; }
    }
}
