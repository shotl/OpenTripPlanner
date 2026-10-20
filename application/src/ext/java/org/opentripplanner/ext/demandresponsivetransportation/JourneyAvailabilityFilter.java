package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
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
    return filter(Map.of(type, nearbyStops), areaId, desiredTime, null).get(type);
  }

  /**
   * Filter the DRT stops of several phases (access and egress) with one journey-availability
   * call: the journeys of every phase go in one request, in phase order, and the answers are
   * split back by position. Each phase is filtered exactly as {@link #filter(Collection,
   * AccessEgressType, String, Instant)} filters it alone; a failed call or a response of the
   * wrong size leaves every phase unfiltered (fail-open), as it leaves a single phase.
   *
   * @param stopsByType the DRT-eligible stops of each phase
   * @param requestId   the caller's request id, sent as {@code X-Request-Id}; null sends none
   * @return the filtered stops of every phase given, under the same phase
   */
  public Map<AccessEgressType, Collection<NearbyStop>> filter(
    Map<AccessEgressType, Collection<NearbyStop>> stopsByType,
    String areaId,
    Instant desiredTime,
    @Nullable String requestId
  ) {
    var result = new EnumMap<AccessEgressType, Collection<NearbyStop>>(AccessEgressType.class);
    var phases = new ArrayList<Phase>();
    // The access journeys go first, then the egress journeys.
    for (var type : AccessEgressType.values()) {
      var nearbyStops = stopsByType.get(type);
      if (nearbyStops == null) {
        continue;
      }
      var phase = Phase.of(type, nearbyStops);
      if (phase.checkable.isEmpty()) {
        result.put(type, nearbyStops);
      } else {
        phases.add(phase);
      }
    }
    if (phases.isEmpty()) {
      return result;
    }

    var journeyPairs = new ArrayList<JourneyAvailabilityService.JourneyPair>();
    phases.forEach(phase -> journeyPairs.addAll(phase.journeyPairs));

    JourneyAvailabilityResponse response = service.checkAvailability(
      areaId,
      desiredTime,
      journeyPairs,
      requestId
    );

    // Fail-open: if the service is unavailable, keep all stops
    if (response == null || response.journeys() == null) {
      for (var phase : phases) {
        LOG.warn(
          "[DRT] Journey availability service unavailable — skipping filter for {} {} stops",
          phase.checkable.size(),
          phase.type
        );
        result.put(phase.type, phase.nearbyStops);
      }
      return result;
    }

    if (response.journeys().size() != journeyPairs.size()) {
      LOG.warn(
        "[DRT] Journey availability response size mismatch: expected={}, got={} — skipping filter for {}",
        journeyPairs.size(),
        response.journeys().size(),
        phases.stream().map(p -> p.type).toList()
      );
      phases.forEach(phase -> result.put(phase.type, phase.nearbyStops));
      return result;
    }

    if (phases.size() > 1) {
      LOG.info(
        "[DRT] Journey availability | one call for {} | journeys={}",
        phases.stream().map(p -> p.type + "=" + p.journeyPairs.size()).toList(),
        journeyPairs.size()
      );
    }

    int offset = 0;
    for (var phase : phases) {
      var answers = response.journeys().subList(offset, offset + phase.checkable.size());
      offset += phase.checkable.size();
      result.put(phase.type, phase.keepAvailable(answers, areaId));
    }
    return result;
  }

  /**
   * The stops of one phase split into those the service can check (a car path to read the
   * journey from) and those it cannot, with the journey of each checkable stop.
   */
  private record Phase(
    AccessEgressType type,
    Collection<NearbyStop> nearbyStops,
    List<NearbyStop> checkable,
    List<NearbyStop> uncheckable,
    List<JourneyAvailabilityService.JourneyPair> journeyPairs
  ) {
    static Phase of(AccessEgressType type, Collection<NearbyStop> nearbyStops) {
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
      return new Phase(type, nearbyStops, checkableStops, uncheckableStops, journeyPairs);
    }

    /** The uncheckable stops and the checkable ones the answers (one per stop) say available. */
    Collection<NearbyStop> keepAvailable(
      List<JourneyAvailabilityResponse.JourneyResult> answers,
      String areaId
    ) {
      var filtered = new ArrayList<NearbyStop>(uncheckable);
      var keptStopIds = new ArrayList<String>();
      var removedStopIds = new ArrayList<String>();

      for (int i = 0; i < checkable.size(); i++) {
        var stopId = checkable.get(i).stop.getId().toString();
        if (answers.get(i).available()) {
          filtered.add(checkable.get(i));
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
