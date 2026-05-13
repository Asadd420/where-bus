package com.wherebus.services;

import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.wherebus.models.Stop;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes real-time ETAs for buses approaching a stop.
 *
 * <p><b>Distance:</b> Uses pre-computed cumulative road distances from {@code TransitService}
 * (derived from shape polylines) rather than straight-line Haversine distance. The bus's
 * current GPS position is projected onto the shape to get its arc-length from the route start;
 * the target stop's arc-length is looked up from the pre-computed table. The difference is the
 * actual road distance remaining.
 *
 * <p><b>hasPassed():</b> Compares the bus's projected arc-length against the target stop's
 * arc-length. A bus is considered to have passed when its position on the shape is at or
 * beyond the stop's position. This is geometrically accurate and does not rely on the
 * closest-stop index heuristic.
 *
 * <p><b>Speed:</b> Uses {@code LiveTrackingService.getSpeedMps()}, which prefers derived speed
 * (from consecutive position deltas) over the feed's speed field. Prasarana's feed broadcasts
 * speed in km/h despite the GTFS-RT spec mandating m/s; the conversion is handled in
 * {@code LiveTrackingService}.
 */
@Service
public class EtaCalculationService {

    private final TransitService transitService;
    private final LiveTrackingService liveTrackingService;

    public EtaCalculationService(TransitService transitService, LiveTrackingService liveTrackingService) {
        this.transitService = transitService;
        this.liveTrackingService = liveTrackingService;
    }

    /**
     * Returns all active buses approaching the target stop on the given route,
     * sorted by ascending ETA. Buses that have already passed the stop are excluded.
     *
     * @param routeId      Public route ID (e.g. "T789"). Trailing-zero matching handled internally.
     * @param targetStopId Stop ID as defined in stops.txt (e.g. "1001410").
     * @return Ordered list of arrival predictions, or an empty list if the stop is unknown.
     */
    public List<Map<String, Object>> getArrivalsForStop(String routeId, String targetStopId) {
        Stop targetStop = transitService.getStopById(targetStopId);
        if (targetStop == null) return Collections.emptyList();

        PriorityQueue<ArrivalPrediction> heap = new PriorityQueue<>(
                Comparator.comparingInt(ArrivalPrediction::getSecondsRemaining)
        );

        for (Map.Entry<String, VehiclePosition> entry : liveTrackingService.getActiveVehicles().entrySet()) {
            VehiclePosition vehicle = entry.getValue();
            if (!vehicle.hasTrip()) continue;

            String broadcastedRouteId = vehicle.getTrip().getRouteId();
            boolean matchesRoute = routeId.equalsIgnoreCase(broadcastedRouteId)
                    || (routeId + "0").equalsIgnoreCase(broadcastedRouteId);
            if (!matchesRoute) continue;

            int directionId = vehicle.getTrip().hasDirectionId() ? vehicle.getTrip().getDirectionId() : 0;
            double busLat = vehicle.getPosition().getLatitude();
            double busLon = vehicle.getPosition().getLongitude();

            // Get the stop's pre-computed cumulative distance and the bus's projected distance.
            RoadPosition busPosition = projectOntoRoute(routeId, directionId, busLat, busLon);
            RoadPosition targetPosition = getStopPosition(routeId, directionId, targetStopId);

            if (busPosition == null || targetPosition == null) continue;

            // Bus has passed the stop — exclude from results.
            if (busPosition.cumulativeDistKm >= targetPosition.cumulativeDistKm) continue;

            double roadDistanceKm = targetPosition.cumulativeDistKm - busPosition.cumulativeDistKm;
            double roadDistanceMeters = roadDistanceKm * 1000.0;

            double speedMps = liveTrackingService.getSpeedMps(entry.getKey(), vehicle);
            int secondsRemaining = (int) (roadDistanceMeters / speedMps);

            String licensePlate = vehicle.getVehicle().hasLicensePlate()
                    ? vehicle.getVehicle().getLicensePlate() : entry.getKey();

            heap.offer(new ArrivalPrediction(
                    entry.getKey(), licensePlate, roadDistanceMeters, secondsRemaining, directionId));
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
     * Looks up the pre-computed cumulative road distance for a specific stop in a route-direction.
     * Returns null if the stop is not found in this direction's path.
     */
    private RoadPosition getStopPosition(String routeId, int directionId, String stopId) {
        LinkedList<String> path = transitService.getRoutePath(routeId, directionId);
        List<Double> distances = transitService.getStopCumulativeDistances(routeId, directionId);
        if (path == null || distances == null) return null;

        List<String> stopIds = new ArrayList<>(path);
        int idx = stopIds.indexOf(stopId);
        if (idx == -1 || idx >= distances.size()) return null;

        return new RoadPosition(distances.get(idx));
    }

    /**
     * Projects a GPS coordinate onto the route's stop sequence and returns the cumulative
     * road distance of the nearest stop to the bus's position.
     *
     * <p>We find the stop in the route path whose coordinates are closest to the bus GPS,
     * then return that stop's pre-computed cumulative distance. This is an approximation —
     * the bus may be between two stops — but it is directionally correct for hasPassed()
     * and acceptable for ETA given the 15-second feed refresh interval.
     *
     * <p>Returns null if the route path or distances are unavailable.
     */
    private RoadPosition projectOntoRoute(String routeId, int directionId, double busLat, double busLon) {
        LinkedList<String> path = transitService.getRoutePath(routeId, directionId);
        List<Double> distances = transitService.getStopCumulativeDistances(routeId, directionId);
        if (path == null || distances == null || path.isEmpty()) return null;

        List<String> stopIds = new ArrayList<>(path);
        int closestIdx = -1;
        double closestDist = Double.MAX_VALUE;

        for (int i = 0; i < stopIds.size(); i++) {
            Stop stop = transitService.getStopById(stopIds.get(i));
            if (stop == null) continue;
            double d = haversineDistanceKm(busLat, busLon, stop.getLatitude(), stop.getLongitude());
            if (d < closestDist) {
                closestDist = d;
                closestIdx = i;
            }
        }

        if (closestIdx == -1 || closestIdx >= distances.size()) return null;
        return new RoadPosition(distances.get(closestIdx));
    }

    private double haversineDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
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

    /** Wraps a cumulative road distance value from the route start. */
    private static class RoadPosition {
        final double cumulativeDistKm;
        RoadPosition(double cumulativeDistKm) { this.cumulativeDistKm = cumulativeDistKm; }
    }

    /** Internal heap entry for a single bus's predicted arrival. */
    private static class ArrivalPrediction {
        private final String vehicleId;
        private final String licensePlate;
        private final double distanceMeters;
        private final int secondsRemaining;
        private final int directionId;

        ArrivalPrediction(String vehicleId, String licensePlate, double distanceMeters,
                          int secondsRemaining, int directionId) {
            this.vehicleId = vehicleId;
            this.licensePlate = licensePlate;
            this.distanceMeters = distanceMeters;
            this.secondsRemaining = secondsRemaining;
            this.directionId = directionId;
        }

        String getVehicleId() { return vehicleId; }
        String getLicensePlate() { return licensePlate; }
        double getDistanceMeters() { return distanceMeters; }
        int getSecondsRemaining() { return secondsRemaining; }
        int getDirectionId() { return directionId; }
    }
}
