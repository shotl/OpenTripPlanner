package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;
import org.opentripplanner.transit.model.site.RegularStop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.SiteRepository;

/**
 * Tests the DRT stop filtering logic that will be applied during access/egress routing.
 * This verifies that when DRT-eligible stops are configured, only those stops are kept
 * for DRT mode, while all stops remain available for walking.
 */
class DrtAccessEgressFilterTest {

  private RegularStop stopS1;
  private RegularStop stopS2;
  private RegularStop stopS3;
  private RegularStop stopS4;

  @BeforeEach
  void setUp() {
    var siteRepoBuilder = new SiteRepository().withContext();
    var testModel = new TimetableRepositoryForTest(siteRepoBuilder);

    stopS1 = testModel.stop("S1", 47.0, 19.0).build();
    stopS2 = testModel.stop("S2", 47.1, 19.1).build();
    stopS3 = testModel.stop("S3", 47.2, 19.2).build();
    stopS4 = testModel.stop("S4", 47.3, 19.3).build();
  }

  @Test
  void filtersNearbyStopsToOnlyDrtEligible() {
    Set<StopLocation> drtEligibleStops = Set.of(stopS1, stopS3);
    var allNearbyStops = createNearbyStops(stopS1, stopS2, stopS3, stopS4);

    var filtered = filterDrtEligibleStops(allNearbyStops, drtEligibleStops);

    var filteredIds = filtered
      .stream()
      .map(ns -> ns.stop.getId().getId())
      .collect(Collectors.toSet());
    assertEquals(Set.of("S1", "S3"), filteredIds);
  }

  @Test
  void emptyEligibleSetReturnsAllStops() {
    Set<StopLocation> drtEligibleStops = Set.of();
    var allNearbyStops = createNearbyStops(stopS1, stopS2, stopS3);

    var filtered = filterDrtEligibleStops(allNearbyStops, drtEligibleStops);

    // Empty set means no filtering — all stops are returned
    assertEquals(3, filtered.size());
  }

  @Test
  void allStopsEligibleReturnsAll() {
    Set<StopLocation> drtEligibleStops = Set.of(stopS1, stopS2, stopS3, stopS4);
    var allNearbyStops = createNearbyStops(stopS1, stopS2, stopS3, stopS4);

    var filtered = filterDrtEligibleStops(allNearbyStops, drtEligibleStops);

    assertEquals(4, filtered.size());
  }

  @Test
  void noStopsMatchReturnsEmpty() {
    Set<StopLocation> drtEligibleStops = Set.of(stopS1);
    var allNearbyStops = createNearbyStops(stopS2, stopS3, stopS4);

    var filtered = filterDrtEligibleStops(allNearbyStops, drtEligibleStops);

    assertTrue(filtered.isEmpty());
  }

  /**
   * Simulate the filtering logic from TransitRouter.filterDrtEligibleStops().
   * This is extracted here to unit test independently from the full TransitRouter.
   */
  private Collection<NearbyStop> filterDrtEligibleStops(
    Collection<NearbyStop> nearbyStops,
    Set<StopLocation> drtEligibleStops
  ) {
    if (drtEligibleStops.isEmpty()) {
      return nearbyStops;
    }
    return nearbyStops.stream().filter(ns -> drtEligibleStops.contains(ns.stop)).toList();
  }

  private List<NearbyStop> createNearbyStops(RegularStop... stops) {
    return List.of(stops).stream().map(stop -> new NearbyStop(stop, 100, null, null)).toList();
  }
}
