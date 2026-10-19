package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.street.model._data.StreetModelForTest.intersectionVertex;
import static org.opentripplanner.street.model._data.StreetModelForTest.streetEdge;

import org.junit.jupiter.api.Test;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.edge.StreetTransitStopLink;
import org.opentripplanner.street.model.vertex.IntersectionVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.transit.model._data.TimetableRepositoryForTest;

class DrtStopCarReachTest {

  private static final TimetableRepositoryForTest TEST_MODEL = TimetableRepositoryForTest.of();

  private static TransitStopVertex stopLinkedTo(IntersectionVertex street) {
    var stop = TransitStopVertex.of().withStop(TEST_MODEL.stop("S").build()).build();
    StreetTransitStopLink.createStreetTransitStopLink(stop, street);
    StreetTransitStopLink.createStreetTransitStopLink(street, stop);
    return stop;
  }

  private static void both(
    IntersectionVertex a,
    IntersectionVertex b,
    double meters,
    StreetTraversalPermission p
  ) {
    streetEdge(a, b, meters, p);
    streetEdge(b, a, meters, p);
  }

  @Test
  void aStationOnAFootwayIsReachedByWalkingToTheNearestRoad() {
    // stop - platform footway (40 m) - sidewalk (30 m) - road a car drives through
    var platform = intersectionVertex("platform", 41.0, 2.0);
    var sidewalk = intersectionVertex("sidewalk", 41.0004, 2.0);
    var road = intersectionVertex("road", 41.0007, 2.0);
    var roadEnd = intersectionVertex("roadEnd", 41.0007, 2.001);
    both(platform, sidewalk, 40, StreetTraversalPermission.PEDESTRIAN);
    both(sidewalk, road, 30, StreetTraversalPermission.PEDESTRIAN);
    both(road, roadEnd, 100, StreetTraversalPermission.ALL);
    var stop = stopLinkedTo(platform);

    var reach = DrtStopCarReach.of(stop, 500);

    assertEquals(road, reach.access().orElseThrow().carPoint());
    assertEquals(70, reach.access().orElseThrow().meters(), 0.001);
    assertEquals(70, reach.egress().orElseThrow().meters(), 0.001);
  }

  @Test
  void aStopNextToTheRoadIsZeroMetresAway() {
    var road = intersectionVertex("road", 41.0, 2.0);
    var roadEnd = intersectionVertex("roadEnd", 41.0, 2.001);
    both(road, roadEnd, 100, StreetTraversalPermission.ALL);
    var stop = stopLinkedTo(road);

    assertEquals(0, DrtStopCarReach.of(stop, 500).access().orElseThrow().meters(), 0.001);
  }

  @Test
  void aOneWayStreetIsNotAPlaceToStop() {
    // A car can leave the vertex next to the stop but never arrive at it: the walk goes on to
    // the next point a car can drive in and out of.
    var nearOneWay = intersectionVertex("oneWayStart", 41.0, 2.0);
    var oneWayEnd = intersectionVertex("oneWayEnd", 41.0, 2.001);
    var after = intersectionVertex("after", 41.0, 2.002);
    streetEdge(nearOneWay, oneWayEnd, 80, StreetTraversalPermission.ALL);
    streetEdge(oneWayEnd, nearOneWay, 80, StreetTraversalPermission.PEDESTRIAN);
    both(oneWayEnd, after, 50, StreetTraversalPermission.ALL);
    var stop = stopLinkedTo(nearOneWay);

    var walk = DrtStopCarReach.of(stop, 500).access().orElseThrow();

    assertEquals(oneWayEnd, walk.carPoint());
    assertEquals(80, walk.meters(), 0.001);
  }

  @Test
  void noRoadWithinTheWalkLimitIsNoReach() {
    var platform = intersectionVertex("platform", 41.0, 2.0);
    var far = intersectionVertex("far", 41.006, 2.0);
    var farEnd = intersectionVertex("farEnd", 41.006, 2.001);
    both(platform, far, 600, StreetTraversalPermission.PEDESTRIAN);
    both(far, farEnd, 100, StreetTraversalPermission.ALL);
    var stop = stopLinkedTo(platform);

    var reach = DrtStopCarReach.of(stop, 500);

    assertTrue(reach.access().isEmpty());
    assertTrue(reach.egress().isEmpty());
  }
}
