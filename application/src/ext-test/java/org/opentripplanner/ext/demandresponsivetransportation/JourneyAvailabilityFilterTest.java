package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType.ACCESS;
import static org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType.EGRESS;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas.JourneyAvailabilityResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.framework.i18n.I18NString;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.street.model.vertex.StreetLocation;
import org.opentripplanner.street.search.request.StreetSearchRequest;
import org.opentripplanner.street.search.state.State;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.service.SiteRepository;

/**
 * Tests for {@link JourneyAvailabilityFilter} which pre-filters DRT access/egress
 * stops by calling the journey-availability service.
 */
class JourneyAvailabilityFilterTest {

  // The path origin coordinate — simulates the street-snapped user location
  private static final double PATH_ORIGIN_LAT = 41.385;
  private static final double PATH_ORIGIN_LON = 2.173;

  // For egress, the path origin is the street-snapped destination
  private static final double PATH_DEST_LAT = 41.390;
  private static final double PATH_DEST_LON = 2.165;

  private static final String AREA_ID = "area-123";
  private static final Instant DESIRED_TIME = Instant.parse("2025-03-15T09:00:00Z");

  private RegularStop stopA;
  private RegularStop stopB;
  private RegularStop stopC;

  @BeforeEach
  void setUp() {
    var siteRepoBuilder = new SiteRepository().withContext();
    var testModel = new TimetableRepositoryForTest(siteRepoBuilder);

    stopA = testModel.stop("STOP_A", 41.386, 2.175).build();
    stopB = testModel.stop("STOP_B", 41.388, 2.170).build();
    stopC = testModel.stop("STOP_C", 41.391, 2.168).build();
  }

  @Test
  void filtersOutUnavailableAccessStops() {
    // Stop A and C are available, Stop B is not
    var service = mockService(List.of(true, false, true));
    var filter = new JourneyAvailabilityFilter(service);
    var nearbyStops = createAccessNearbyStops(stopA, stopB, stopC);

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    var resultIds = stopIds(result);
    assertEquals(Set.of("STOP_A", "STOP_C"), resultIds);
  }

  @Test
  void filtersOutUnavailableEgressStops() {
    // Only Stop B is available for egress
    var service = mockService(List.of(false, true, false));
    var filter = new JourneyAvailabilityFilter(service);
    var nearbyStops = createEgressNearbyStops(stopA, stopB, stopC);

    var result = filter.filter(nearbyStops, EGRESS, AREA_ID, DESIRED_TIME);

    var resultIds = stopIds(result);
    assertEquals(Set.of("STOP_B"), resultIds);
  }

  @Test
  void allAvailableKeepsAllStops() {
    var service = mockService(List.of(true, true, true));
    var filter = new JourneyAvailabilityFilter(service);
    var nearbyStops = createAccessNearbyStops(stopA, stopB, stopC);

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    assertEquals(3, result.size());
  }

  @Test
  void noneAvailableReturnsEmpty() {
    var service = mockService(List.of(false, false, false));
    var filter = new JourneyAvailabilityFilter(service);
    var nearbyStops = createAccessNearbyStops(stopA, stopB, stopC);

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    assertTrue(result.isEmpty());
  }

  @Test
  void serviceReturnsNullFailsOpen() {
    var service = new JourneyAvailabilityService() {
      @Override
      public JourneyAvailabilityResponse checkAvailability(
        String areaId,
        Instant desiredTime,
        List<JourneyPair> journeys
      ) {
        return null;
      }
    };
    var filter = new JourneyAvailabilityFilter(service);
    var nearbyStops = createAccessNearbyStops(stopA, stopB, stopC);

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    assertEquals(3, result.size());
  }

  @Test
  void responseSizeMismatchFailsOpen() {
    // Response has different number of journeys than request -> fail open
    var service = mockService(List.of(true, false)); // 2 results for 3 stops
    var filter = new JourneyAvailabilityFilter(service);
    var nearbyStops = createAccessNearbyStops(stopA, stopB, stopC);

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    assertEquals(3, result.size());
  }

  @Test
  void emptyInputReturnsEmpty() {
    var service = mockService(List.of());
    var filter = new JourneyAvailabilityFilter(service);
    Collection<NearbyStop> nearbyStops = List.of();

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    assertTrue(result.isEmpty());
  }

  @Test
  void nullStateStopsPassThroughUnfiltered() {
    // Stops without State (null) should fail-open — kept unconditionally
    var service = mockService(List.of(false)); // stopB would be rejected
    var filter = new JourneyAvailabilityFilter(service);

    var nearbyStops = List.of(
      new NearbyStop(stopA, 100, null, null), // null state → uncheckable, kept
      createAccessNearbyStop(stopB, PATH_ORIGIN_LAT, PATH_ORIGIN_LON) // has state → checked
    );

    var result = filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    // stopA is kept (null state = fail-open), stopB is removed (service returned false)
    var resultIds = stopIds(result);
    assertEquals(Set.of("STOP_A"), resultIds);
  }

  @Test
  void accessJourneyPairsUsePathOriginAsPickup() {
    // Verify that for ACCESS, journey pairs use the path origin → stop coordinate
    var capturingService = new CapturingJourneyAvailabilityService(List.of(true));
    var filter = new JourneyAvailabilityFilter(capturingService);
    var nearbyStops = createAccessNearbyStops(stopA);

    filter.filter(nearbyStops, ACCESS, AREA_ID, DESIRED_TIME);

    var pairs = capturingService.capturedJourneys;
    assertEquals(1, pairs.size());
    // Pickup = path origin (the street-snapped user location)
    assertEquals(PATH_ORIGIN_LAT, pairs.get(0).pickup().latitude(), 0.0001);
    assertEquals(PATH_ORIGIN_LON, pairs.get(0).pickup().longitude(), 0.0001);
    // Dropoff = stop coordinate
    assertEquals(stopA.getLat(), pairs.get(0).dropoff().latitude(), 0.0001);
    assertEquals(stopA.getLon(), pairs.get(0).dropoff().longitude(), 0.0001);
  }

  @Test
  void egressJourneyPairsUseStopAsPickup() {
    // Verify that for EGRESS, journey pairs use stop → path origin (destination)
    var capturingService = new CapturingJourneyAvailabilityService(List.of(true));
    var filter = new JourneyAvailabilityFilter(capturingService);
    var nearbyStops = createEgressNearbyStops(stopA);

    filter.filter(nearbyStops, EGRESS, AREA_ID, DESIRED_TIME);

    var pairs = capturingService.capturedJourneys;
    assertEquals(1, pairs.size());
    // Pickup = stop coordinate (passenger leaves transit here)
    assertEquals(stopA.getLat(), pairs.get(0).pickup().latitude(), 0.0001);
    assertEquals(stopA.getLon(), pairs.get(0).pickup().longitude(), 0.0001);
    // Dropoff = path origin (the street-snapped destination)
    assertEquals(PATH_DEST_LAT, pairs.get(0).dropoff().latitude(), 0.0001);
    assertEquals(PATH_DEST_LON, pairs.get(0).dropoff().longitude(), 0.0001);
  }

  @Test
  void extractPathOriginCoordinateReturnsNullForNullState() {
    var result = JourneyAvailabilityFilter.extractPathOriginCoordinate(null);
    assertEquals(null, result);
  }

  @Test
  void extractPathOriginCoordinateReturnsInitialVertexCoordinate() {
    var vertex = new StreetLocation(
      "test",
      new Coordinate(2.173, 41.385), // lon, lat
      I18NString.of("test")
    );
    var state = new State(vertex, StreetSearchRequest.of().build());

    var result = JourneyAvailabilityFilter.extractPathOriginCoordinate(state);

    assertEquals(41.385, result.latitude(), 0.0001);
    assertEquals(2.173, result.longitude(), 0.0001);
  }

  // --- helpers ---

  private JourneyAvailabilityService mockService(List<Boolean> availabilities) {
    return (areaId, desiredTime, journeys) -> {
      var results = new ArrayList<JourneyAvailabilityResponse.JourneyResult>();
      for (int i = 0; i < availabilities.size(); i++) {
        results.add(
          new JourneyAvailabilityResponse.JourneyResult(
            availabilities.get(i),
            "subarea-" + i,
            "subarea-" + (i + 1),
            0,
            0,
            0,
            0
          )
        );
      }
      return new JourneyAvailabilityResponse(results);
    };
  }

  /**
   * Create a NearbyStop with a State whose initial vertex is at the given coordinates,
   * simulating an A* car path that starts at those coordinates.
   */
  private NearbyStop createAccessNearbyStop(RegularStop stop, double originLat, double originLon) {
    var originVertex = new StreetLocation(
      "origin",
      new Coordinate(originLon, originLat), // JTS: lon, lat
      I18NString.of("Origin")
    );
    var state = new State(originVertex, StreetSearchRequest.of().build());
    return new NearbyStop(stop, 100, List.of(), state);
  }

  private NearbyStop createEgressNearbyStop(RegularStop stop, double destLat, double destLon) {
    // For egress, A* runs from destination → stops.
    // The initial state vertex is at the destination.
    var destVertex = new StreetLocation(
      "destination",
      new Coordinate(destLon, destLat), // JTS: lon, lat
      I18NString.of("Destination")
    );
    var state = new State(destVertex, StreetSearchRequest.of().build());
    return new NearbyStop(stop, 100, List.of(), state);
  }

  private List<NearbyStop> createAccessNearbyStops(RegularStop... stops) {
    return List.of(stops)
      .stream()
      .map(stop -> createAccessNearbyStop(stop, PATH_ORIGIN_LAT, PATH_ORIGIN_LON))
      .toList();
  }

  private List<NearbyStop> createEgressNearbyStops(RegularStop... stops) {
    return List.of(stops)
      .stream()
      .map(stop -> createEgressNearbyStop(stop, PATH_DEST_LAT, PATH_DEST_LON))
      .toList();
  }

  private Set<String> stopIds(Collection<NearbyStop> stops) {
    return stops.stream().map(ns -> ns.stop.getId().getId()).collect(Collectors.toSet());
  }

  /**
   * A test implementation that captures the journey pairs passed to it,
   * so we can verify the correct pickup/dropoff directions.
   */
  private static class CapturingJourneyAvailabilityService implements JourneyAvailabilityService {

    final List<Boolean> responses;
    List<JourneyPair> capturedJourneys;

    CapturingJourneyAvailabilityService(List<Boolean> responses) {
      this.responses = responses;
    }

    @Override
    public JourneyAvailabilityResponse checkAvailability(
      String areaId,
      Instant desiredTime,
      List<JourneyPair> journeys
    ) {
      this.capturedJourneys = journeys;
      var results = new ArrayList<JourneyAvailabilityResponse.JourneyResult>();
      for (int i = 0; i < journeys.size(); i++) {
        boolean available = i < responses.size() && responses.get(i);
        results.add(new JourneyAvailabilityResponse.JourneyResult(available, "", "", 0, 0, 0, 0));
      }
      return new JourneyAvailabilityResponse(results);
    }
  }
}
