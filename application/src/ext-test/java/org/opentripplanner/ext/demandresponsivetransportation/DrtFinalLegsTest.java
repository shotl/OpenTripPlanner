package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.model.plan.PlanTestConstants.A;
import static org.opentripplanner.model.plan.PlanTestConstants.B;
import static org.opentripplanner.model.plan.PlanTestConstants.C;
import static org.opentripplanner.model.plan.PlanTestConstants.D;
import static org.opentripplanner.model.plan.PlanTestConstants.E;
import static org.opentripplanner.model.plan.PlanTestConstants.F;
import static org.opentripplanner.model.plan.PlanTestConstants.T11_00;
import static org.opentripplanner.model.plan.PlanTestConstants.T11_02;
import static org.opentripplanner.model.plan.PlanTestConstants.T11_10;
import static org.opentripplanner.model.plan.PlanTestConstants.T11_20;
import static org.opentripplanner.model.plan.PlanTestConstants.T11_30;
import static org.opentripplanner.model.plan.TestItineraryBuilder.newItinerary;
import static org.opentripplanner.model.plan.TestItineraryBuilder.newTime;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse.ShotlGeoLocation;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.street.search.TraverseMode;

class DrtFinalLegsTest {

  private static final double WALK_RELUCTANCE = 2.0;
  private static final int T11_40 = T11_30 + 600;

  private static ShotlArrivalEstimateResponse estimate(
    int pickupSecondsOfDay,
    int dropoffSecondsOfDay,
    long walkToPickup,
    long walkFromDropoff
  ) {
    return new ShotlArrivalEstimateResponse(
      "ride-1",
      "user-1",
      "SCHEDULE_RIDE",
      "DRAFT",
      "123456",
      null,
      null,
      new ShotlScheduledGeoLocation(new ShotlGeoLocation(41.391, 2.1806), "Arc de Triomf"),
      new ShotlScheduledGeoLocation(new ShotlGeoLocation(41.3792, 2.1405), "Estació de Sants"),
      null,
      null,
      newTime(pickupSecondsOfDay).toEpochSecond(),
      newTime(dropoffSecondsOfDay).toEpochSecond(),
      null,
      new ShotlArrivalEstimateResponse.ShotlPassengers(1, 0),
      "vehicle-1",
      null,
      walkToPickup,
      walkFromDropoff
    );
  }

  /** Turns the router's car leg into the DRT leg the decoration would produce. */
  private static List<Leg> withDrt(List<Leg> legs, ShotlArrivalEstimateResponse estimate) {
    var out = new ArrayList<Leg>();
    for (var leg : legs) {
      if (leg instanceof StreetLeg sl && sl.getMode().isInCar()) {
        out.add(new DRTLeg(sl, estimate, 600));
      } else {
        out.add(leg);
      }
    }
    return out;
  }

  private static List<String> shape(List<Leg> legs) {
    return legs
      .stream()
      .map(l ->
        l instanceof DRTLeg ? "DRT" : l.isTransitLeg() ? "BUS" : ((StreetLeg) l).getMode().name()
      )
      .toList();
  }

  // Access: origin A, walk to the car, drive, walk to stop B, bus to F.
  private static List<Leg> access(ShotlArrivalEstimateResponse estimate) {
    var itinerary = newItinerary(A, T11_00)
      .walk(60, C)
      .drive(T11_02, T11_10, D)
      .walk(120, B)
      .bus(1, T11_20, T11_40, F)
      .build();
    return withDrt(itinerary.getLegs(), estimate);
  }

  // Egress: bus from A to stop B, walk to the car, drive, walk to destination F.
  private static List<Leg> egress(ShotlArrivalEstimateResponse estimate) {
    var itinerary = newItinerary(A)
      .bus(1, T11_00, T11_20, B)
      .walk(60, C)
      .drive(T11_30, T11_40, D)
      .walk(60, F)
      .build();
    return withDrt(itinerary.getLegs(), estimate);
  }

  @Test
  void accessBecomesWalkDrtWalkWithTheEstimateTimesAndPlaces() {
    var est = estimate(T11_00 + 300, T11_00 + 720, 90, 60); // pickup 11:05, dropoff 11:12

    var legs = DrtFinalLegs.build(access(est), 0, 0, WALK_RELUCTANCE).orElseThrow();

    assertEquals(List.of("WALK", "DRT", "WALK", "BUS"), shape(legs));
    var toPickup = legs.get(0);
    var drt = assertInstanceOf(DRTLeg.class, legs.get(1));
    var fromDropoff = legs.get(2);
    assertEquals(A.coordinate, toPickup.getFrom().coordinate, "the walk starts at the origin");
    assertEquals("Arc de Triomf", toPickup.getTo().name.toString());
    assertEquals(
      newTime(T11_00 + 210),
      toPickup.getStartTime(),
      "it leaves 90 s before the pickup"
    );
    assertEquals(newTime(T11_00 + 300), toPickup.getEndTime());
    assertEquals(newTime(T11_00 + 300), drt.getStartTime(), "the vehicle's pickup time");
    assertEquals(newTime(T11_00 + 720), drt.getEndTime(), "the vehicle's dropoff time");
    assertEquals("Arc de Triomf", drt.getFrom().name.toString());
    assertEquals("Estació de Sants", drt.getTo().name.toString());
    assertEquals(newTime(T11_00 + 780), fromDropoff.getEndTime(), "60 s walk to the stop");
    assertEquals(B.coordinate, fromDropoff.getTo().coordinate, "the walk ends at the transit stop");
    assertEquals(180, toPickup.getGeneralizedCost(), "walk cost = seconds × walk reluctance");
  }

  @Test
  void accessThatReachesTheStopAfterItsBufferRemovesTheItinerary() {
    var est = estimate(T11_00 + 300, T11_00 + 720, 90, 60); // at stop B 11:13, bus 11:20

    assertTrue(DrtFinalLegs.build(access(est), 420, 0, WALK_RELUCTANCE).isPresent(), "7 min fits");
    assertTrue(
      DrtFinalLegs.build(access(est), 421, 0, WALK_RELUCTANCE).isEmpty(),
      "one more second does not"
    );
  }

  @Test
  void egressWalksFromTheTransitArrivalAndWaitsForTheVehicle() {
    var est = estimate(T11_30, T11_40, 120, 45); // passenger at the pickup 11:22, vehicle 11:30

    var legs = DrtFinalLegs.build(egress(est), 0, 0, WALK_RELUCTANCE).orElseThrow();

    assertEquals(List.of("BUS", "WALK", "DRT", "WALK"), shape(legs));
    var toPickup = legs.get(1);
    var drt = assertInstanceOf(DRTLeg.class, legs.get(2));
    assertEquals(
      B.coordinate,
      toPickup.getFrom().coordinate,
      "the walk starts at the stop the bus arrives at"
    );
    assertEquals(newTime(T11_20), toPickup.getStartTime(), "when the bus arrives");
    assertEquals(newTime(T11_20 + 120), toPickup.getEndTime());
    assertEquals(480, drt.getWaitingSeconds(), "11:22 to 11:30");
    assertEquals(
      F.coordinate,
      legs.get(3).getTo().coordinate,
      "the last walk ends at the destination"
    );
    assertEquals(newTime(T11_40 + 45), legs.get(3).getEndTime());
  }

  @Test
  void egressThatReachesThePickupOneSecondLateRemovesTheItinerary() {
    // The bus arrives 11:20; a 601 s walk reaches the pickup 11:30:01, the vehicle leaves 11:30.
    assertTrue(
      DrtFinalLegs.build(
        egress(estimate(T11_30, T11_40, 600, 45)),
        0,
        0,
        WALK_RELUCTANCE
      ).isPresent()
    );
    assertTrue(
      DrtFinalLegs.build(egress(estimate(T11_30, T11_40, 601, 45)), 0, 0, WALK_RELUCTANCE).isEmpty()
    );
    // The egress buffer is the only margin.
    assertTrue(
      DrtFinalLegs.build(egress(estimate(T11_30, T11_40, 600, 45)), 0, 1, WALK_RELUCTANCE).isEmpty()
    );
  }

  @Test
  void aZeroWalkIsLeftOutAndAShortOneIsKeptWithItsSeconds() {
    var legs = DrtFinalLegs.build(
      access(estimate(T11_00 + 300, T11_00 + 720, 0, 10)),
      0,
      0,
      WALK_RELUCTANCE
    ).orElseThrow();

    assertEquals(List.of("DRT", "WALK", "BUS"), shape(legs));
    assertEquals(10, legs.get(1).getDuration().toSeconds());
    assertEquals(TraverseMode.WALK, ((StreetLeg) legs.get(1)).getMode());
  }

  @Test
  void anItineraryWithoutADrtLegIsLeftAsItIs() {
    var legs = newItinerary(A, T11_00).walk(60, B).bus(1, T11_02, T11_10, E).build().getLegs();

    assertEquals(legs, DrtFinalLegs.build(legs, 0, 0, WALK_RELUCTANCE).orElseThrow());
  }
}
