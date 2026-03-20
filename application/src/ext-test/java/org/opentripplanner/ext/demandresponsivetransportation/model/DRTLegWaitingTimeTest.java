package org.opentripplanner.ext.demandresponsivetransportation.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;

/**
 * Tests for DRTLeg waiting time computation and generalized cost with waiting.
 */
class DRTLegWaitingTimeTest {

  private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

  // Base epoch: 2025-03-15T10:00:00 in UTC
  private static final long BASE_EPOCH = Instant.parse("2025-03-15T10:00:00Z").getEpochSecond();

  /**
   * Helper to build a ShotlArrivalEstimateResponse with the given timing parameters.
   */
  private static ShotlArrivalEstimateResponse estimate(
    long expectedPickupEpoch,
    long expectedDropoffEpoch,
    long pickupWalkingSec,
    long dropoffWalkingSec
  ) {
    return new ShotlArrivalEstimateResponse(
      "test-id",
      "user-1",
      "ON_DEMAND",
      "ESTIMATED",
      "OK",
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(41.385, 2.173),
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(41.390, 2.165),
      null,
      null,
      BASE_EPOCH,
      null,
      expectedPickupEpoch,
      expectedDropoffEpoch,
      BASE_EPOCH,
      new ShotlArrivalEstimateResponse.ShotlPassengers(1, 0),
      "vehicle-1",
      null,
      pickupWalkingSec,
      dropoffWalkingSec
    );
  }

  @Test
  void computeWaitingSeconds_withZonedDateTime_vehicleArrivesAfterUser() {
    // User starts walking at T=100, walks 60s, arrives at pickup at T=160
    // Vehicle arrives at T=200 -> waiting = 40s
    var est = estimate(200, 800, 60, 30);
    var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(100), ZONE);

    long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
    assertEquals(40, waiting);
  }

  @Test
  void computeWaitingSeconds_withZonedDateTime_vehicleArrivesBeforeUser() {
    // User starts walking at T=100, walks 60s, arrives at pickup at T=160
    // Vehicle arrives at T=150 -> waiting = 0 (clamped)
    var est = estimate(150, 800, 60, 30);
    var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(100), ZONE);

    long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
    assertEquals(0, waiting);
  }

  @Test
  void computeWaitingSeconds_withZonedDateTime_vehicleArrivesExactly() {
    // User starts walking at T=100, walks 60s, arrives at pickup at T=160
    // Vehicle arrives at T=160 -> waiting = 0
    var est = estimate(160, 800, 60, 30);
    var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(100), ZONE);

    long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
    assertEquals(0, waiting);
  }

  @Test
  void computeWaitingSeconds_withInstant_previousLegEnd() {
    // Previous leg ends at T=500, user walks 120s, arrives at pickup at T=620
    // Vehicle arrives at T=700 -> waiting = 80s
    var est = estimate(700, 1200, 120, 30);
    var previousLegEnd = Instant.ofEpochSecond(500);

    long waiting = DRTLeg.computeWaitingSeconds(est, previousLegEnd);
    assertEquals(80, waiting);
  }

  @Test
  void computeWaitingSeconds_withInstant_noWaiting() {
    // Previous leg ends at T=500, user walks 120s, arrives at pickup at T=620
    // Vehicle arrives at T=600 -> waiting = 0 (clamped)
    var est = estimate(600, 1200, 120, 30);
    var previousLegEnd = Instant.ofEpochSecond(500);

    long waiting = DRTLeg.computeWaitingSeconds(est, previousLegEnd);
    assertEquals(0, waiting);
  }

  @Test
  void computeGeneralizedCost_withWaiting() {
    // walkToPickup=120s, walkFromDropoff=60s, ride=600s (1000-400), waiting=240s
    // walkReluctance=2.0, carReluctance=1.0, waitReluctance=1.0
    // cost = (120+60)*2.0 + 240*1.0 + 600*1.0 = 360 + 240 + 600 = 1200
    var est = estimate(400, 1000, 120, 60);

    int cost = DRTLeg.computeGeneralizedCost(est, 2.0, 1.0, 240, 1.0);
    assertEquals(1200, cost);
  }

  @Test
  void computeGeneralizedCost_withZeroWaiting() {
    // Same as above but 0 waiting
    // cost = (120+60)*2.0 + 0*1.0 + 600*1.0 = 360 + 0 + 600 = 960
    var est = estimate(400, 1000, 120, 60);

    int cost = DRTLeg.computeGeneralizedCost(est, 2.0, 1.0, 0, 1.0);
    assertEquals(960, cost);
  }

  @Test
  void computeGeneralizedCost_backwardCompatible_zeroWaiting() {
    // The 3-arg overload should produce same result as 5-arg with 0 waiting
    var est = estimate(400, 1000, 120, 60);

    int costCompat = DRTLeg.computeGeneralizedCost(est, 2.0, 1.0);
    int costExplicit = DRTLeg.computeGeneralizedCost(est, 2.0, 1.0, 0, 1.0);
    assertEquals(costExplicit, costCompat);
  }

  @Test
  void computeGeneralizedCost_withHighWaitReluctance() {
    // walkToPickup=120s, walkFromDropoff=60s, ride=600s, waiting=240s
    // waitReluctance=2.5 -> waiting cost = 240*2.5 = 600
    // cost = (120+60)*2.0 + 240*2.5 + 600*1.0 = 360 + 600 + 600 = 1560
    var est = estimate(400, 1000, 120, 60);

    int cost = DRTLeg.computeGeneralizedCost(est, 2.0, 1.0, 240, 2.5);
    assertEquals(1560, cost);
  }

  @Test
  void computeWaitingSeconds_withNullPickupWalking() {
    // When pickup_walking_seconds is null, defaults to 0
    // User starts at T=100, walks 0s, arrives at pickup at T=100
    // Vehicle at T=200 -> waiting = 100s
    var est = estimate(200, 800, 0, 30);
    // Re-create with null pickup walking
    var estNullPickup = new ShotlArrivalEstimateResponse(
      "test-id",
      "user-1",
      "ON_DEMAND",
      "ESTIMATED",
      "OK",
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(41.385, 2.173),
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(41.390, 2.165),
      null,
      null,
      BASE_EPOCH,
      null,
      200L,
      800L,
      BASE_EPOCH,
      new ShotlArrivalEstimateResponse.ShotlPassengers(1, 0),
      "vehicle-1",
      null,
      null, // null pickup_walking_seconds
      30L
    );
    var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(100), ZONE);

    long waiting = DRTLeg.computeWaitingSeconds(estNullPickup, legStart);
    assertEquals(100, waiting);
  }

  @Test
  void threeArgConstructor_producesZeroWaiting() {
    // The 3-arg constructor back-computes start time so user arrives exactly when vehicle does
    // -> zero waiting
    var est = estimate(BASE_EPOCH + 600, BASE_EPOCH + 1200, 120, 60);

    // We can't easily construct a StreetLeg in a unit test without the full graph,
    // so we test the computeWaitingSeconds logic that the constructor would use:
    // legStartTime = expectedPickup - pickupWalking = (BASE+600) - 120 = BASE+480
    var backComputedStart = ZonedDateTime.ofInstant(
      Instant.ofEpochSecond(BASE_EPOCH + 600 - 120),
      ZONE
    );
    long waiting = DRTLeg.computeWaitingSeconds(est, backComputedStart);
    assertEquals(0, waiting);
  }
}
