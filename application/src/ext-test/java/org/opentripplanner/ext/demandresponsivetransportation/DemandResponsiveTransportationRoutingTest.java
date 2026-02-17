package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.routing.algorithm.GraphRoutingTest;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.request.request.StreetRequest;
import org.opentripplanner.street.model.StreetTraversalPermission;
import org.opentripplanner.street.model.vertex.StreetVertex;
import org.opentripplanner.street.model.vertex.TransitEntranceVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.StreetSearchBuilder;
import org.opentripplanner.street.search.state.CarPickupState;
import org.opentripplanner.street.search.strategy.EuclideanRemainingWeightHeuristic;

/**
 * Test DRT (Demand Responsive Transportation) routing at the street level.
 *
 * Similar to CarPickupTest, DRT mode:
 * - May start with (WALK - WALK_TO_PICKUP, CAR - IN_CAR)
 * - May end with (WALK - WALK_FROM_DROP_OFF, CAR - IN_CAR)
 * - StreetTransitEntityLink requires mode changes to WALK
 * - StreetEdges may contain mode changes between CAR / WALK
 *
 * The key difference from CAR_PICKUP is that DRT uses the DEMAND_RESPONSIVE_TRANSPORTATION
 * StreetMode, which should behave identically for street routing purposes.
 */
public class DemandResponsiveTransportationRoutingTest extends GraphRoutingTest {

  private TransitStopVertex S1;
  private TransitEntranceVertex E1;
  private StreetVertex A, B, C, D, E;

  @BeforeEach
  protected void setUp() throws Exception {
    // Generate a simple graph similar to CarPickupTest:
    //
    //   A <-> B <-> C <-> D <-> E
    //   TS1 <-^           ^-> TE1
    //
    // A-B: PEDESTRIAN only
    // B-C: CAR (DRT can drive here)
    // C-D: PEDESTRIAN_AND_BICYCLE
    // D-E: PEDESTRIAN only

    modelOf(
      new Builder() {
        @Override
        public void build() {
          S1 = stop("S1", 0, 45);
          E1 = entrance("E1", 0.004, 45);
          A = intersection("A", 0.001, 45);
          B = intersection("B", 0.002, 45);
          C = intersection("C", 0.003, 45);
          D = intersection("D", 0.004, 45);
          E = intersection("E", 0.005, 45);

          biLink(B, S1);
          biLink(C, E1);

          street(A, B, 87, StreetTraversalPermission.PEDESTRIAN);
          street(B, C, 87, StreetTraversalPermission.CAR);
          street(C, D, 87, StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE);
          street(D, E, 87, StreetTraversalPermission.PEDESTRIAN);
        }
      }
    );
  }

  @Test
  public void testDrtCarOnly() {
    // B to C - car-only street, should be able to traverse in car
    assertPath(B, C, "null - IN_CAR - null, CAR - IN_CAR - BC street");
  }

  @Test
  public void testDrtCarThenWalk() {
    // A to C - starts with walk (pedestrian-only), then car
    assertPath(
      A,
      C,
      "null - WALK_TO_PICKUP - null, WALK - WALK_TO_PICKUP - AB street, CAR - IN_CAR - BC street"
    );
  }

  @Test
  public void testDrtFromTransitStopThenCar() {
    // From transit stop S1 to C - can get picked up at the stop
    assertPath(
      S1,
      C,
      "null - WALK_TO_PICKUP - null, null - WALK_TO_PICKUP - S1, CAR - IN_CAR - BC street"
    );
  }

  @Test
  public void testDrtFromTransitStopThenCarThenWalk() {
    // From S1 to D - pickup, drive, then walk
    assertPath(
      S1,
      D,
      "null - WALK_TO_PICKUP - null, null - WALK_TO_PICKUP - S1, CAR - IN_CAR - BC street, WALK - WALK_FROM_DROP_OFF - CD street"
    );
  }

  @Test
  public void testDrtCarThenWalkToTransitStop() {
    // B to E1 (entrance) - drive then drop off before entering transit
    assertPath(
      B,
      E1,
      "null - IN_CAR - null, CAR - IN_CAR - BC street, null - WALK_FROM_DROP_OFF - E1"
    );
  }

  @Test
  public void testDrtFromTransitStopToTransitStop() {
    // S1 to E1 - complete trip from stop to stop via DRT
    assertPath(
      S1,
      E1,
      "null - WALK_TO_PICKUP - null, null - WALK_TO_PICKUP - S1, CAR - IN_CAR - BC street, null - WALK_FROM_DROP_OFF - E1"
    );
  }

  @Test
  public void testDrtWalkCarWalk() {
    // A to D - walk to pickup, drive, then walk to destination
    assertPath(
      A,
      D,
      "null - WALK_TO_PICKUP - null, WALK - WALK_TO_PICKUP - AB street, CAR - IN_CAR - BC street, WALK - WALK_FROM_DROP_OFF - CD street"
    );
  }

  @Test
  public void testDrtWalkOnlyPath() {
    // A to B - pedestrian-only street, can't drive but can walk
    // With the fix for isFinal() to properly check CarPickupState, DRT searches now correctly
    // require completion of the pickup phase. For walk-only paths:
    // - DepartAt: Starts IN_CAR, but immediately transitions to WALK_FROM_DROP_OFF when
    //   it encounters a pedestrian-only edge. Final state is WALK_FROM_DROP_OFF.
    // - ArriveBy: Starts in WALK_FROM_DROP_OFF (reverse of depart), transitions to WALK_TO_PICKUP.
    //   Final state is WALK_TO_PICKUP.
    assertPath(
      A,
      B,
      "null - IN_CAR - null, WALK - WALK_FROM_DROP_OFF - AB street",
      "null - WALK_TO_PICKUP - null, WALK - WALK_TO_PICKUP - AB street"
    );
  }

  @Test
  public void testDrtModeIsRecognizedAsPickupMode() {
    // Verify that DRT mode is properly recognized as a pickup mode
    assertTrue(StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION.includesPickup());
  }

  @Test
  public void testDrtInitialStatesContainCarAndWalk() {
    // Test that a DRT search creates both IN_CAR and WALK_TO_PICKUP initial states
    var options = new RouteRequest();
    options.setArriveBy(false);

    var tree = StreetSearchBuilder.of()
      .setHeuristic(new EuclideanRemainingWeightHeuristic())
      .setRequest(options)
      .setStreetRequest(new StreetRequest(StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION))
      .setFrom(A)
      .setTo(C)
      .getShortestPathTree();

    assertNotNull(tree);
    var path = tree.getPath(C);
    assertNotNull(path, "Path should be found from A to C");

    // Check that the path has states with car pickup state
    boolean hasCarPickupState = path.states.stream().anyMatch(s -> s.getCarPickupState() != null);
    assertTrue(hasCarPickupState, "Path should have car pickup states");
  }

  @Test
  public void testDrtReachesTransitStopWithCarMode() {
    // Critical test: verify that DRT mode can reach a transit stop via car
    var options = new RouteRequest();
    options.setArriveBy(false);

    var tree = StreetSearchBuilder.of()
      .setHeuristic(new EuclideanRemainingWeightHeuristic())
      .setRequest(options)
      .setStreetRequest(new StreetRequest(StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION))
      .setFrom(B)
      .setTo(E1)
      .getShortestPathTree();

    assertNotNull(tree);
    var path = tree.getPath(E1);
    assertNotNull(path, "Path should be found from B to E1 (transit entrance)");

    // Check that the path contains car mode
    boolean containsCar = path.states.stream().anyMatch(s -> s.containsModeCar());
    assertTrue(containsCar, "Path should contain car mode traversal");

    // Verify the final state has dropped off (not still in car)
    var finalState = path.states.get(path.states.size() - 1);
    assertEquals(
      CarPickupState.WALK_FROM_DROP_OFF,
      finalState.getCarPickupState(),
      "Final state at transit stop should be WALK_FROM_DROP_OFF"
    );
  }

  private void assertPath(Vertex fromVertex, Vertex toVertex, String descriptor) {
    String departAt = runStreetSearchAndCreateDescriptor(fromVertex, toVertex, false);
    String arriveBy = runStreetSearchAndCreateDescriptor(fromVertex, toVertex, true);

    assertDescriptors(descriptor, descriptor, arriveBy, departAt);
  }

  private void assertPath(
    Vertex fromVertex,
    Vertex toVertex,
    String expectedDepartAt,
    String expectedArriveBy
  ) {
    String departAt = runStreetSearchAndCreateDescriptor(fromVertex, toVertex, false);
    String arriveBy = runStreetSearchAndCreateDescriptor(fromVertex, toVertex, true);

    assertDescriptors(expectedDepartAt, expectedArriveBy, arriveBy, departAt);
  }

  private void assertDescriptors(
    String expectedDepartAt,
    String expectedArriveBy,
    String arriveBy,
    String departAt
  ) {
    String formatString = "DepartAt: %s%nArriveBy: %s";

    assertEquals(
      String.format(formatString, expectedDepartAt, expectedArriveBy),
      String.format(formatString, departAt, arriveBy)
    );
  }

  private String runStreetSearchAndCreateDescriptor(
    Vertex fromVertex,
    Vertex toVertex,
    boolean arriveBy
  ) {
    var options = new RouteRequest();
    options.setArriveBy(arriveBy);

    var tree = StreetSearchBuilder.of()
      .setHeuristic(new EuclideanRemainingWeightHeuristic())
      .setRequest(options)
      .setStreetRequest(new StreetRequest(StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION))
      .setFrom(fromVertex)
      .setTo(toVertex)
      .getShortestPathTree();
    var path = tree.getPath(arriveBy ? fromVertex : toVertex);

    return path != null
      ? path.states
        .stream()
        .map(s ->
          String.format(
            "%s - %s - %s",
            s.getBackMode(),
            s.getCarPickupState(),
            s.getBackEdge() != null ? s.getBackEdge().getDefaultName() : null
          )
        )
        .collect(Collectors.joining(", "))
      : "path not found";
  }
}
