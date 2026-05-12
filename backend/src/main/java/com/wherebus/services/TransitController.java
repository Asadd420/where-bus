package com.wherebus.controllers;

import com.wherebus.models.Route;
import com.wherebus.models.Stop;
import com.wherebus.services.EtaCalculationService;
import com.wherebus.services.LiveTrackingService;
import com.wherebus.services.TransitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST endpoints for static transit data, live vehicle positions, and ETA predictions.
 * All business logic lives in the service layer; this controller only handles routing and delegation.
 */
@RestController
@RequestMapping("/api/transit")
@CrossOrigin(origins = "http://localhost:3000")
@Tag(name = "Transit", description = "Routes, stops, live vehicle positions, and ETA predictions.")
public class TransitController {

    private final TransitService transitService;
    private final EtaCalculationService etaCalculationService;
    private final LiveTrackingService liveTrackingService;

    public TransitController(
            TransitService transitService,
            EtaCalculationService etaCalculationService,
            LiveTrackingService liveTrackingService) {
        this.transitService = transitService;
        this.etaCalculationService = etaCalculationService;
        this.liveTrackingService = liveTrackingService;
    }

    /**
     * Sanity check: confirms TransitService is loaded and can resolve a known stop ID.
     * GET /api/transit/test
     */
    @Operation(summary = "Service health check", description = "Verifies TransitService is loaded by resolving a known stop ID.")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/plain"))
    @GetMapping("/test")
    public String testDataLink() {
        Stop stop = transitService.getStopById("1006035");
        return stop != null
                ? "TransitService is wired up! Found stop: " + stop.getName()
                : "TransitService is wired up, but stop 1006035 is missing.";
    }

    /**
     * Returns static metadata for a route (short name, long name).
     * GET /api/transit/routes/{routeId}
     */
    @Operation(summary = "Get route metadata")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json", schema = @Schema(implementation = Route.class)))
    @GetMapping("/routes/{routeId}")
    public Route getRouteDetails(
            @Parameter(description = "Route ID as stored in routes.txt.", example = "T7890")
            @PathVariable String routeId) {
        return transitService.getRouteById(routeId);
    }

    /**
     * Returns the ordered list of stops along a route, suitable for drawing a route line on a map.
     * GET /api/transit/routes/{routeId}/path
     */
    @Operation(summary = "Get route stop sequence")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json",
            array = @ArraySchema(schema = @Schema(implementation = Stop.class))))
    @GetMapping("/routes/{routeId}/path")
    public List<Stop> getRoutePath(
            @Parameter(description = "Route ID as stored in routes.txt.", example = "T7890")
            @PathVariable String routeId) {
        LinkedList<String> stopIds = transitService.getRoutePath(routeId);
        if (stopIds == null) return Collections.emptyList();

        List<Stop> path = new ArrayList<>();
        for (String stopId : stopIds) {
            Stop stop = transitService.getStopById(stopId);
            if (stop != null) path.add(stop);
        }
        return path;
    }

    /**
     * Searches stops and routes by name. Returns up to 10 results per category.
     * GET /api/transit/search?q=Universiti
     */
    @Operation(summary = "Search stops and routes by name")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json"))
    @GetMapping("/search")
    public Map<String, Object> searchTransit(
            @Parameter(description = "Partial name to search for.", example = "Universiti")
            @RequestParam String q) {
        if (q == null || q.trim().isEmpty()) {
            return Map.of("stops", List.of(), "routes", List.of());
        }

        return Map.of(
                "stops", transitService.searchStops(q),
                "routes", transitService.searchRoutes(q)
        );
    }

    /**
     * Returns active buses on a route sorted by ascending ETA to the given stop.
     * GET /api/transit/eta?routeId=T789&stopId=1001410
     */
    @Operation(summary = "Get real-time ETAs for a stop")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json"))
    @GetMapping("/eta")
    public List<Map<String, Object>> getRealtimeEta(
            @Parameter(description = "Public route ID.", example = "T789") @RequestParam String routeId,
            @Parameter(description = "Stop ID as defined in stops.txt.", example = "1001410") @RequestParam String stopId) {
        return etaCalculationService.getArrivalsForStop(routeId, stopId);
    }

    /**
     * Returns all live vehicles currently on the given route, with GPS coordinates and direction.
     * GET /api/transit/vehicles?routeId=T789
     *
     * <p>directionId: 0 = outbound, 1 = inbound (from GTFS-RT trip descriptor).
     */
    @Operation(summary = "Get live vehicle positions for a route")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json"))
    @GetMapping("/vehicles")
    public List<Map<String, Object>> getLiveVehiclesForRoute(
            @Parameter(description = "Public route ID.", example = "T789")
            @RequestParam String routeId) {
        return liveTrackingService.getVehiclesByRoute(routeId);
    }

    /**
     * Debug endpoint: shows raw Prasarana broadcast structure for the live fleet.
     * Useful for verifying route ID formats and checking feed health.
     * GET /api/transit/debug-fleet
     */
    @Operation(summary = "Debug live fleet feed")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "application/json"))
    @GetMapping("/debug-fleet")
    public Map<String, Object> debugActiveFleet() {
        return liveTrackingService.getFleetSnapshot();
    }
}
