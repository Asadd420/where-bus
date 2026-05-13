package com.wherebus.services;

import com.google.transit.realtime.GtfsRealtime.VehiclePosition;
import com.wherebus.models.Stop;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes real-time ETAs for buses approaching a stop.
 *
 * <p><b>Route ID convention:</b> All public-facing methods accept the route <b>short name</b>
 * (e.g. {@code "T815"}, {@code "T789"}) — not the internal GTFS route_id. The short name
 * is what Prasarana broadcasts in the live feed and what API callers pass. Internal resolution
 * to the GTFS route_id (e.g. {@code "30000016"}) is handled transparently via
 * {@link TransitService#resolveRouteIdByShortName(String)}.
 *
 * <p><b>Distance:</b> Uses pre-computed cumulative road distances from shape polylines where
 * available. Falls back to Haversine × road correction factor when shape data is missing,
 * so the endpoint always returns results.
 *
 * <p><b>hasPassed():</b> Compares cumulative road arc-lengths. Falls back to no filtering
 * when shape data is unavailable.
 *
 * <p><b>Speed:</b> Delegates entirely to {@link LiveTrackingService#getSpeedMps}.
 */
@Service
public class EtaCalculationService {

    private final TransitService transitService;
    private final LiveTrackingService liveTrackingService;

    // Fallback multiplier when shape-based road distance is unavailable.
    private static final double HAVERSINE_ROAD_FACTOR = 1.4;

    public EtaCalculationService(TransitService transitService, LiveTrackingService liveTrackingService) {
        this.transitService = transitService;
        this.liveTrackingService = liveTrackingService;
    }

    /**
     * Returns all active buses approaching the target stop on the given route,
     * sorted by ascending ETA. Buses that have already passed the stop are excluded
     * when shape data is available.
     *
     * @param routeShortName Route short name as displayed on buses (e.g. "T815", "T789").
     *                       Do NOT pass the internal GTFS route_id (e.g. "30000016").
     * @param targetStopId   Stop ID as defined in stops.txt (e.g. "12000802").
     * @return Ordered list of arrival predictions, or an empty list if the stop is unknown.
     */
    public List<Map<String, Object>> getArrivalsForStop(String routeShortName, String targetStopId) {
        Stop targetStop = transitService.getStopById(targetStopId);
        if (targetStop == null) {
            System.err.println("⚠️  ETA: stop not found: " + targetStopId);
            return Collections.emptyList();
        }

        // Resolve once up front — used for all static data lookups in this call.
        String staticRouteId = transitService.resolveRouteIdByShortName(routeShortName);

        PriorityQueue<ArrivalPrediction> heap = new PriorityQueue<>(
                Comparator.comparingInt(ArrivalPrediction::getSecondsRemaining)
        );

        int totalOnRoute = 0;
        int droppedPassed = 0;
        int usedFallback = 0;

        for (Map.Entry<String, VehiclePosition> entry : liveTrackingService.getActiveVehicles().entrySet()) {
            VehiclePosition vehicle = entry.getValue();
            if (!vehicle.hasTrip()) continue;
            if (!LiveTrackingService.matchesRouteId(routeShortName, vehicle.getTrip().getRouteId())) continue;

            totalOnRoute++;
            int directionId = vehicle.getTrip().hasDirectionId() ? vehicle.getTrip().getDirectionId() : 0;
            double busLat = vehicle.getPosition().getLatitude();
            double busLon = vehicle.getPosition().getLongitude();

            RoadPosition busPosition = projectOntoRoute(staticRouteId, directionId, busLat, busLon);
            RoadPosition targetPosition = getStopPosition(staticRouteId, directionId, targetStopId);

            double roadDistanceMeters;

            if (busPosition == null || targetPosition == null) {
                // Shape data unavailable for this route-direction — fall back to Haversine.
                usedFallback++;
                double straightLineMeters = haversineDistanceKm(
                        busLat, busLon, targetStop.getLatitude(), targetStop.getLongitude()) * 1000.0;
                roadDistanceMeters = straightLineMeters * HAVERSINE_ROAD_FACTOR;
            } else {
                if (busPosition.cumulativeDistKm >= targetPosition.cumulativeDistKm) {
                    droppedPassed++;
                    continue;
                }
                roadDistanceMeters = (targetPosition.cumulativeDistKm - busPosition.cumulativeDistKm) * 1000.0;
            }

            double speedMps = liveTrackingService.getSpeedMps(entry.getKey(), vehicle);
            int secondsRemaining = (int) (roadDistanceMeters / speedMps);
            String licensePlate = vehicle.getVehicle().hasLicensePlate()
                    ? vehicle.getVehicle().getLicensePlate() : entry.getKey();

            heap.offer(new ArrivalPrediction(
                    entry.getKey(), licensePlate, roadDistanceMeters, secondsRemaining, directionId));
        }

        System.out.println("ℹ️  ETA route=" + routeShortName + " (→" + staticRouteId + ")"
                + " stop=" + targetStopId
                + " → onRoute=" + totalOnRoute
                + " passed=" + droppedPassed
                + " fallback=" + usedFallback
                + " returning=" + heap.size());

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

    private RoadPosition getStopPosition(String staticRouteId, int directionId, String stopId) {
        LinkedList<String> path = transitService.getRoutePath(staticRouteId, directionId);
        List<Double> distances = transitService.getStopCumulativeDistances(staticRouteId, directionId);
        if (path == null || distances == null) return null;

        List<String> stopIds = new ArrayList<>(path);
        int idx = stopIds.indexOf(stopId);
        if (idx == -1 || idx >= distances.size()) return null;

        return new RoadPosition(distances.get(idx));
    }

    private RoadPosition projectOntoRoute(String staticRouteId, int directionId,
                                           double busLat, double busLon) {
        LinkedList<String> path = transitService.getRoutePath(staticRouteId, directionId);
        List<Double> distances = transitService.getStopCumulativeDistances(staticRouteId, directionId);
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

    private static class RoadPosition {
        final double cumulativeDistKm;
        RoadPosition(double cumulativeDistKm) { this.cumulativeDistKm = cumulativeDistKm; }
    }

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
