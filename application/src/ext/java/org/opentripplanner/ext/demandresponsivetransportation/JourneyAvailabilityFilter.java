package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas.JourneyAvailabilityResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.street.search.state.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters DRT access/egress nearby stops by calling the journey-availability endpoint
 * to check whether each car path can actually be served by the DRT provider.
 * <p>
 * For each NearbyStop, the filter extracts the actual car path endpoints:
 * <ul>
 *   <li>Access: path origin (near user's location) → stop coordinate</li>
 *   <li>Egress: stop coordinate → path destination (near user's destination)</li>
 * </ul>
 * <p>
 * This is a lightweight pre-filter that runs before access shifting and before Raptor,
 * avoiding expensive Shotl rides API calls and unnecessary Raptor paths for journeys
 * that the DRT provider cannot serve (e.g., outside subarea movements, blocked time windows).
 * <p>
 * The filter uses fail-open behavior: if the journey-availability service is unavailable,
 * returns an error, or the path state is unavailable, stops pass through unfiltered.
 */
public class JourneyAvailabilityFilter {

  private static final Logger LOG = LoggerFactory.getLogger(JourneyAvailabilityFilter.class);

  private final JourneyAvailabilityService service;

  public JourneyAvailabilityFilter(JourneyAvailabilityService service) {
    this.service = service;
  }

  /**
   * Filter nearby stops to only those where the DRT journey is available,
   * using the actual car path origin/destination from each NearbyStop's A* result.
   *
   * @param nearbyStops the DRT-eligible stops found by A* street search, each containing
   *                    the full car path State
   * @param type        ACCESS or EGRESS — determines the journey direction
   * @param areaId      the DRT service area ID
   * @param desiredTime the desired departure/arrival time
   * @return filtered list of stops where DRT journeys are available, or the original list if
   *         the service is unavailable (fail-open)
   */
  public Collection<NearbyStop> filter(
    Collection<NearbyStop> nearbyStops,
    AccessEgressType type,
    String areaId,
    Instant desiredTime
  ) {
    if (nearbyStops.isEmpty()) {
      return nearbyStops;
    }

    // Separate stops into checkable (have State with path) and uncheckable (no State)
    var checkableStops = new ArrayList<NearbyStop>();
    var uncheckableStops = new ArrayList<NearbyStop>();
    var journeyPairs = new ArrayList<JourneyAvailabilityService.JourneyPair>();

    for (var ns : nearbyStops) {
      WgsCoordinate pathOriginCoord = extractPathOriginCoordinate(ns.state);
      if (pathOriginCoord == null) {
        // No state available — can't determine path endpoints, keep this stop (fail-open)
        uncheckableStops.add(ns);
        continue;
      }

      WgsCoordinate stopCoord = ns.stop.getCoordinate();
      checkableStops.add(ns);

      if (type.isAccess()) {
        // Access: car path goes from origin → stop
        journeyPairs.add(new JourneyAvailabilityService.JourneyPair(pathOriginCoord, stopCoord));
      } else {
        // Egress: car path goes from stop → destination
        // (A* for egress runs from destination → stops, so initial state is at destination)
        journeyPairs.add(new JourneyAvailabilityService.JourneyPair(stopCoord, pathOriginCoord));
      }
    }

    if (checkableStops.isEmpty()) {
      return nearbyStops;
    }

    JourneyAvailabilityResponse response = service.checkAvailability(
      areaId,
      desiredTime,
      journeyPairs
    );

    // Fail-open: if the service is unavailable, keep all stops
    if (response == null || response.journeys() == null) {
      LOG.warn(
        "[DRT] Journey availability service unavailable — skipping filter for {} {} stops",
        checkableStops.size(),
        type
      );
      return nearbyStops;
    }

    if (response.journeys().size() != checkableStops.size()) {
      LOG.warn(
        "[DRT] Journey availability response size mismatch: expected={}, got={} — skipping filter",
        checkableStops.size(),
        response.journeys().size()
      );
      return nearbyStops;
    }

    var filtered = new ArrayList<NearbyStop>(uncheckableStops);
    var keptStopIds = new ArrayList<String>();
    var removedStopIds = new ArrayList<String>();

    for (int i = 0; i < checkableStops.size(); i++) {
      var stopId = checkableStops.get(i).stop.getId().toString();
      if (response.journeys().get(i).available()) {
        filtered.add(checkableStops.get(i));
        keptStopIds.add(stopId);
      } else {
        removedStopIds.add(stopId);
      }
    }

    LOG.info(
      "[DRT] Journey availability filter | {} phase | areaId={} | keptStops={} | removedStops={}",
      type,
      areaId,
      keptStopIds,
      removedStopIds
    );

    return filtered;
  }

  /**
   * Walk back the State chain to find the initial vertex coordinate (the path origin).
   * For access paths this is near the user's origin; for egress paths it's near the destination.
   *
   * @return the coordinate of the path's starting vertex, or null if state is null
   */
  static WgsCoordinate extractPathOriginCoordinate(State state) {
    if (state == null) {
      return null;
    }
    State s = state;
    while (s.getBackState() != null) {
      s = s.getBackState();
    }
    var vertex = s.getVertex();
    return new WgsCoordinate(vertex.getLat(), vertex.getLon());
  }
}
