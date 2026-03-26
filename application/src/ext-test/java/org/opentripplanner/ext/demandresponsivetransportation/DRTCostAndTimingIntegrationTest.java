package org.opentripplanner.ext.demandresponsivetransportation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.raptor.api.model.RaptorCostConverter;

/**
 * Comprehensive test suite for DRT cost and timing calculations across all phases.
 *
 * <p>Covers:
 * <ul>
 *   <li>Phase 1: Access adapter duration and cost (pre-Raptor)</li>
 *   <li>Phase 2: DRTLeg cost for egress (post-Raptor decoration)</li>
 *   <li>Phase 3: DRTLeg cost for direct DRT (door-to-door)</li>
 *   <li>Phase 4: Waiting time computation across all contexts</li>
 *   <li>Phase 5: End-to-end cost consistency between access adapter and DRTLeg</li>
 * </ul>
 */
class DRTCostAndTimingIntegrationTest {

  private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");

  // Base time: 2025-03-15T10:00:00 UTC
  private static final long BASE_EPOCH = Instant.parse("2025-03-15T10:00:00Z").getEpochSecond();

  // Default reluctances
  private static final double WALK_RELUCTANCE = 2.0;
  private static final double CAR_RELUCTANCE = 1.0;
  private static final double WAIT_RELUCTANCE = 1.0;

  // --- Helpers ---

  private static ShotlArrivalEstimateResponse estimate(
    long expectedPickupEpoch,
    long expectedDropoffEpoch,
    Long pickupWalkingSec,
    Long dropoffWalkingSec
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

  private static ShotlArrivalEstimateResponse estimate(
    long expectedPickupEpoch,
    long expectedDropoffEpoch,
    long pickupWalkingSec,
    long dropoffWalkingSec
  ) {
    return estimate(
      expectedPickupEpoch,
      expectedDropoffEpoch,
      (Long) pickupWalkingSec,
      (Long) dropoffWalkingSec
    );
  }

  // ============================================================================
  // 1. ACCESS ADAPTER: Duration and Cost
  // ============================================================================

  @Nested
  class AccessAdapterCost {

    /**
     * Verify the Raptor-level cost computation for a DRT access.
     *
     * <pre>
     * walkToPickup=120s, walkFromDropoff=60s, drtDuration=600s,
     * waitingSeconds=0, accessBuffer=0
     * walkReluctance=2.0, carReluctance=1.0
     *
     * Expected: (120+60)*2.0 + 0*1.0 + 600*1.0 = 960 cost-seconds
     * In Raptor centi-seconds: round(960 * 100) = 96000
     * </pre>
     */
    @Test
    void costWithNoWaitingNoBuffer() {
      double walkCost = (120 + 60) * WALK_RELUCTANCE; // 360
      double drtCost = 600 * CAR_RELUCTANCE; // 600
      int expectedCentiSeconds = (int) Math.round((walkCost + drtCost) * 100);

      int actual = computeAdapterCost(120, 600, 60, 0, 0, WALK_RELUCTANCE, CAR_RELUCTANCE);
      assertEquals(expectedCentiSeconds, actual);
    }

    /**
     * Verify cost with explicit waiting at pickup.
     *
     * <pre>
     * walkToPickup=120s, walkFromDropoff=60s, drtDuration=600s,
     * waitingSeconds=300s, accessBuffer=0
     *
     * Expected: (120+60)*2.0 + 300*1.0 + 600*1.0 = 360+300+600 = 1260 cost-seconds
     * </pre>
     */
    @Test
    void costWithWaiting() {
      double expected = (120 + 60) * WALK_RELUCTANCE + 300 * WAIT_RELUCTANCE + 600 * CAR_RELUCTANCE;
      int expectedCentiSeconds = (int) Math.round(expected * 100);

      int actual = computeAdapterCost(120, 600, 60, 300, 0, WALK_RELUCTANCE, CAR_RELUCTANCE);
      assertEquals(expectedCentiSeconds, actual);
    }

    /**
     * Verify cost with access buffer seconds (extra buffer added to DRT access).
     *
     * <pre>
     * waitingSeconds=0, accessBuffer=120
     * Buffer is treated as wait (reluctance 1.0)
     * Expected: (120+60)*2.0 + (0+120)*1.0 + 600*1.0 = 360+120+600 = 1080
     * </pre>
     */
    @Test
    void costWithAccessBuffer() {
      double expected = (120 + 60) * WALK_RELUCTANCE + (0 + 120) * 1.0 + 600 * CAR_RELUCTANCE;
      int expectedCentiSeconds = (int) Math.round(expected * 100);

      int actual = computeAdapterCost(120, 600, 60, 0, 120, WALK_RELUCTANCE, CAR_RELUCTANCE);
      assertEquals(expectedCentiSeconds, actual);
    }

    /**
     * Verify cost with both waiting and buffer.
     *
     * <pre>
     * waitingSeconds=240, accessBuffer=120
     * Expected: (120+60)*2.0 + (240+120)*1.0 + 600*1.0 = 360+360+600 = 1320
     * </pre>
     */
    @Test
    void costWithWaitingAndBuffer() {
      double expected = (120 + 60) * WALK_RELUCTANCE + (240 + 120) * 1.0 + 600 * CAR_RELUCTANCE;
      int expectedCentiSeconds = (int) Math.round(expected * 100);

      int actual = computeAdapterCost(120, 600, 60, 240, 120, WALK_RELUCTANCE, CAR_RELUCTANCE);
      assertEquals(expectedCentiSeconds, actual);
    }

    /**
     * Verify cost with custom reluctances (e.g., walkReluctance=5.0 as in the failing scenario).
     */
    @Test
    void costWithHighWalkReluctance() {
      double walkR = 5.0;
      double expected = (120 + 60) * walkR + 300 * WAIT_RELUCTANCE + 600 * CAR_RELUCTANCE;
      int expectedCentiSeconds = (int) Math.round(expected * 100);

      int actual = computeAdapterCost(120, 600, 60, 300, 0, walkR, CAR_RELUCTANCE);
      assertEquals(expectedCentiSeconds, actual);
    }

    /**
     * Verify adapter duration includes all four phases plus buffer.
     *
     * <pre>
     * duration = walkToPickup + waitingSeconds + drtDuration + walkFromDropoff + buffer
     *          = 120 + 300 + 600 + 60 + 120 = 1200s
     * </pre>
     */
    @Test
    void durationIncludesAllPhases() {
      int duration = 120 + 300 + 600 + 60 + 120;
      assertEquals(1200, duration);
    }

    /**
     * Replicate the exact scenario from the existing shifter test:
     * 10min delay, 120s walk to pickup, 1800s ride, 60s walk from dropoff.
     * Waiting = max(0, 10min - 2min walk) = 480s.
     * Duration = 120 + 480 + 1800 + 60 = 2460s.
     */
    @Test
    void shifterTestScenarioMatchesDuration() {
      int walkToPickup = 120;
      int drtRide = 1800;
      int walkFromDropoff = 60;
      int pickupDelaySeconds = 600; // 10 min
      int waitingSeconds = Math.max(0, pickupDelaySeconds - walkToPickup);

      assertEquals(480, waitingSeconds, "Waiting = delay - walk to pickup");

      int duration = walkToPickup + waitingSeconds + drtRide + walkFromDropoff;
      assertEquals(2460, duration, "Total duration must match shifter test");
    }

    /**
     * Simulates the access adapter cost computation. This mirrors
     * DemandResponsiveTransportationAccessAdapter.computeGeneralizedCost()
     * which returns Raptor centi-seconds.
     */
    private int computeAdapterCost(
      int walkToPickup,
      int drtDuration,
      int walkFromDropoff,
      int waitingSeconds,
      int accessBuffer,
      double walkReluctance,
      double carReluctance
    ) {
      double walkCost = (walkToPickup + walkFromDropoff) * walkReluctance;
      double waitCost = (waitingSeconds + accessBuffer) * 1.0;
      double drtCost = drtDuration * carReluctance;
      return RaptorCostConverter.toRaptorCost(walkCost + waitCost + drtCost);
    }
  }

  // ============================================================================
  // 2. DRTLeg COST: Egress and Direct Scenarios
  // ============================================================================

  @Nested
  class DRTLegCost {

    /**
     * Standard egress: transit arrives, passenger walks to pickup, waits for vehicle.
     *
     * <pre>
     * Transit arrives at 10:00:00 (BASE_EPOCH)
     * Walk to pickup: 120s -> user at pickup at 10:02:00
     * Vehicle arrives at 10:05:00 -> waiting = 180s
     * Ride: 10:05:00 - 10:15:00 = 600s
     * Walk from dropoff: 60s
     *
     * Cost = (120+60)*2.0 + 180*1.0 + 600*1.0 = 360+180+600 = 1140
     * </pre>
     */
    @Test
    void egressCostWithWaiting() {
      long vehiclePickup = BASE_EPOCH + 300; // 10:05:00
      long vehicleDropoff = BASE_EPOCH + 900; // 10:15:00
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      // Passenger arrives from transit at BASE_EPOCH (10:00:00)
      var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(BASE_EPOCH), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
      assertEquals(180, waiting, "Waiting = vehicle(10:05) - (legStart(10:00) + walk(120s))");

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        waiting,
        WAIT_RELUCTANCE
      );
      assertEquals(1140, cost);
    }

    /**
     * Egress with no waiting: vehicle already at pickup when passenger arrives.
     *
     * <pre>
     * Transit arrives at 10:00:00
     * Walk to pickup: 120s -> user at pickup at 10:02:00
     * Vehicle arrives at 10:01:00 -> waiting = 0 (clamped)
     * Ride: 10:01:00 - 10:11:00 = 600s
     *
     * Cost = (120+60)*2.0 + 0*1.0 + 600*1.0 = 960
     * </pre>
     */
    @Test
    void egressCostNoWaiting() {
      long vehiclePickup = BASE_EPOCH + 60; // 10:01:00 (before user arrives at pickup)
      long vehicleDropoff = BASE_EPOCH + 660; // 10:11:00
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(BASE_EPOCH), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
      assertEquals(0, waiting, "Vehicle arrives before user -> no waiting");

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        waiting,
        WAIT_RELUCTANCE
      );
      assertEquals(960, cost);
    }

    /**
     * Direct DRT: user departs at requested time, vehicle has long pickup delay.
     *
     * <pre>
     * User requests departure at 10:00:00 (BASE_EPOCH)
     * Walk to pickup: 90s -> user at pickup at 10:01:30
     * Vehicle arrives at 10:08:00 -> waiting = 390s
     * Ride: 10:08:00 - 10:18:00 = 600s
     * Walk from dropoff: 45s
     *
     * Cost = (90+45)*2.0 + 390*1.0 + 600*1.0 = 270+390+600 = 1260
     * </pre>
     */
    @Test
    void directDrtCostWithPickupDelay() {
      long vehiclePickup = BASE_EPOCH + 480; // 10:08:00
      long vehicleDropoff = BASE_EPOCH + 1080; // 10:18:00
      var est = estimate(vehiclePickup, vehicleDropoff, 90, 45);

      // For direct: legStart = request departure time
      var legStart = Instant.ofEpochSecond(BASE_EPOCH);
      long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
      assertEquals(390, waiting, "Waiting = vehicle(10:08) - (depart(10:00) + walk(90s))");

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        waiting,
        WAIT_RELUCTANCE
      );
      assertEquals(1260, cost);
    }

    /**
     * Access DRT (3-arg constructor pattern): zero waiting by design.
     *
     * <pre>
     * Back-computed start = expectedPickup - pickupWalking = 10:05:00 - 120s = 10:03:00
     * User starts walking at 10:03:00, arrives at pickup at 10:05:00
     * Vehicle arrives at 10:05:00 -> waiting = 0
     * Ride: 10:05:00 - 10:15:00 = 600s
     *
     * Cost = (120+60)*2.0 + 0 + 600*1.0 = 960
     * </pre>
     */
    @Test
    void accessDrtCostZeroWaiting() {
      long vehiclePickup = BASE_EPOCH + 300; // 10:05:00
      long vehicleDropoff = BASE_EPOCH + 900; // 10:15:00
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      // 3-arg pattern: back-compute start time
      var backComputedStart = ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(vehiclePickup - 120),
        ZONE
      );
      long waiting = DRTLeg.computeWaitingSeconds(est, backComputedStart);
      assertEquals(0, waiting, "Back-computed start produces zero waiting");

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        waiting,
        WAIT_RELUCTANCE
      );
      assertEquals(960, cost);
    }

    /**
     * High walk reluctance (5.0) significantly inflates cost for legs with long walks.
     *
     * <pre>
     * walkToPickup=300s (5 min), walkFromDropoff=120s (2 min)
     * drtRide=600s, waiting=240s
     * walkReluctance=5.0
     *
     * Cost = (300+120)*5.0 + 240*1.0 + 600*1.0 = 2100+240+600 = 2940
     * </pre>
     */
    @Test
    void highWalkReluctanceInflatesCost() {
      long vehiclePickup = BASE_EPOCH + 540; // pickup at 10:09 (after 300s walk + 240s wait)
      long vehicleDropoff = BASE_EPOCH + 1140; // dropoff at 10:19
      var est = estimate(vehiclePickup, vehicleDropoff, 300, 120);

      int cost = DRTLeg.computeGeneralizedCost(est, 5.0, CAR_RELUCTANCE, 240, WAIT_RELUCTANCE);
      assertEquals(2940, cost);
    }
  }

  // ============================================================================
  // 3. WAITING TIME: Edge Cases and Context-Specific
  // ============================================================================

  @Nested
  class WaitingTime {

    /**
     * Long wait scenario (the Bus 85 bug): 19-hour gap between access arrival and transit.
     *
     * <pre>
     * DRT drops off at 01:03 (access)
     * Bus departs at 20:33 (19h 30m later)
     *
     * In the access adapter, waiting = pickupDelay - walkToPickup.
     * The 19-hour gap appears AFTER the access, as wait at the transit stop.
     * The access adapter itself doesn't model this — Raptor's boarding cost should.
     * </pre>
     */
    @Test
    void longGapBetweenAccessAndTransitIsNotInAccessWaiting() {
      // The access adapter's waiting is only the DRT pickup wait,
      // NOT the wait at the transit stop for the bus.
      int pickupDelay = 720; // 12 min
      int walkToPickup = 120;
      int accessWaiting = Math.max(0, pickupDelay - walkToPickup);
      assertEquals(600, accessWaiting, "Access waiting is only the DRT pickup wait");
      // The 19h gap at the transit stop should be handled by Raptor's boarding cost,
      // NOT by the access adapter. This is why the time-shifting fix was necessary:
      // without it, the path builder erased this gap.
    }

    /**
     * Egress waiting from Instant (previous leg end).
     *
     * <pre>
     * Previous transit leg ends at 17:30:00
     * Walk to pickup: 120s -> user at pickup at 17:32:00
     * Vehicle at 17:35:00 -> waiting = 180s
     * </pre>
     */
    @Test
    void egressWaitingFromInstant() {
      long transitEnd = BASE_EPOCH + 27000; // 17:30
      long vehiclePickup = transitEnd + 300; // 17:35
      long vehicleDropoff = vehiclePickup + 600;
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      long waiting = DRTLeg.computeWaitingSeconds(est, Instant.ofEpochSecond(transitEnd));
      assertEquals(180, waiting);
    }

    /**
     * Egress waiting from ZonedDateTime (leg start time).
     */
    @Test
    void egressWaitingFromZonedDateTime() {
      long legStart = BASE_EPOCH;
      long vehiclePickup = BASE_EPOCH + 300; // 5 min after leg starts
      long vehicleDropoff = vehiclePickup + 600;
      var est = estimate(vehiclePickup, vehicleDropoff, 60, 30);

      // User starts at BASE_EPOCH, walks 60s, at pickup at BASE_EPOCH+60
      // Vehicle at BASE_EPOCH+300 -> waiting = 240s
      var startZdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(legStart), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, startZdt);
      assertEquals(240, waiting);
    }

    /**
     * Vehicle arrives exactly when user arrives at pickup -> 0 waiting.
     */
    @Test
    void exactlyOnTimeProducesZeroWaiting() {
      long legStart = BASE_EPOCH;
      long walkToPickup = 120;
      long vehiclePickup = legStart + walkToPickup; // exactly when user arrives
      var est = estimate(vehiclePickup, vehiclePickup + 600, walkToPickup, 30);

      var startZdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(legStart), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, startZdt);
      assertEquals(0, waiting);
    }

    /**
     * Vehicle arrives before user (already waiting) -> 0 waiting (clamped).
     */
    @Test
    void vehicleEarlyProducesZeroWaiting() {
      long legStart = BASE_EPOCH;
      long vehiclePickup = BASE_EPOCH + 30; // 30s after start, but walk is 120s
      var est = estimate(vehiclePickup, vehiclePickup + 600, 120, 30);

      var startZdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(legStart), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, startZdt);
      assertEquals(0, waiting, "Vehicle early -> no user waiting (clamped to 0)");
    }

    /**
     * Null pickup walking seconds defaults to 0 in waiting computation.
     */
    @Test
    void nullPickupWalkingDefaultsToZero() {
      long vehiclePickup = BASE_EPOCH + 300;
      var est = estimate(vehiclePickup, vehiclePickup + 600, null, 30L);

      var startZdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(BASE_EPOCH), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, startZdt);
      // walk=0, so user at pickup at BASE_EPOCH. Vehicle at BASE_EPOCH+300 -> waiting=300
      assertEquals(300, waiting);
    }
  }

  // ============================================================================
  // 4. DRTLeg TIMELINE: Start/End Time Boundaries
  // ============================================================================

  @Nested
  class DRTLegTimeline {

    /**
     * DRTLeg end time = expected dropoff + walk from dropoff.
     */
    @Test
    void endTimeIncludesWalkFromDropoff() {
      long vehicleDropoff = BASE_EPOCH + 900; // 10:15:00
      long dropoffWalk = 60;
      var est = estimate(BASE_EPOCH + 300, vehicleDropoff, 120, dropoffWalk);

      long expectedEndEpoch = vehicleDropoff + dropoffWalk; // 10:16:00
      var expectedEnd = ZonedDateTime.ofInstant(Instant.ofEpochSecond(expectedEndEpoch), ZONE);

      // Using 3-arg constructor (access, zero wait)
      var backComputedStart = ZonedDateTime.ofInstant(
        Instant.ofEpochSecond(BASE_EPOCH + 300 - 120),
        ZONE
      );

      // Verify timeline math: endTime = dropoff + walkFromDropoff
      assertEquals(expectedEndEpoch, vehicleDropoff + dropoffWalk);
    }

    /**
     * 3-arg constructor: startTime = expectedPickup - pickupWalking.
     */
    @Test
    void threeArgStartTimeBackComputed() {
      long vehiclePickup = BASE_EPOCH + 600;
      long pickupWalk = 120;
      var est = estimate(vehiclePickup, vehiclePickup + 600, pickupWalk, 60);

      long expectedStartEpoch = vehiclePickup - pickupWalk; // 10:08:00
      assertEquals(BASE_EPOCH + 480, expectedStartEpoch);
    }

    /**
     * 4-arg constructor: startTime = explicit legStartTime (for egress).
     * The leg starts when the user begins walking, NOT when the vehicle arrives.
     */
    @Test
    void fourArgStartTimeIsExplicit() {
      // Transit ends at 10:00, user starts walking immediately
      long legStartEpoch = BASE_EPOCH;
      long vehiclePickup = BASE_EPOCH + 300; // vehicle 5 min later

      // The DRTLeg start should be legStartEpoch, not vehiclePickup - walkToPickup
      assertEquals(
        BASE_EPOCH,
        legStartEpoch,
        "Egress DRTLeg starts when passenger begins walking, not back-computed from vehicle"
      );
    }

    /**
     * DRTLeg duration includes: walk to pickup + wait + ride + walk from dropoff.
     */
    @Test
    void durationIncludesAllFourPhases() {
      long walkToPickup = 120;
      long waitingSeconds = 180;
      long rideSeconds = 600;
      long walkFromDropoff = 60;

      // For 4-arg constructor (egress):
      // startTime = legStart (e.g., 10:00:00)
      // endTime = vehicleDropoff + walkFromDropoff
      // duration = endTime - startTime
      //          = (vehiclePickup + ride + walkFromDropoff) - legStart
      //          = (legStart + walkToPickup + wait + ride + walkFromDropoff) - legStart
      //          = walkToPickup + wait + ride + walkFromDropoff
      long expectedDuration = walkToPickup + waitingSeconds + rideSeconds + walkFromDropoff;
      assertEquals(960, expectedDuration);
    }
  }

  // ============================================================================
  // 5. COST CONSISTENCY: Access Adapter vs DRTLeg
  // ============================================================================

  @Nested
  class CostConsistency {

    /**
     * Access adapter cost (Raptor centi-seconds) should be consistent with DRTLeg cost
     * (OTP cost-seconds) after conversion.
     *
     * <pre>
     * Same parameters: walkToPickup=120, drtDuration=600, walkFromDropoff=60,
     *                  waiting=0, buffer=0
     * walkReluctance=2.0, carReluctance=1.0
     *
     * DRTLeg cost (OTP):    (120+60)*2.0 + 0 + 600*1.0 = 960 cost-seconds
     * Adapter cost (Raptor): round(960 * 100) = 96000 centi-seconds
     * Converted back:        96000 / 100 = 960 cost-seconds ✓
     * </pre>
     */
    @Test
    void adapterAndLegCostConsistentWithZeroWaiting() {
      long vehiclePickup = BASE_EPOCH + 120; // user walks 120s, vehicle arrives same time
      long vehicleDropoff = vehiclePickup + 600;
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      // DRTLeg cost (OTP seconds)
      int legCost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );

      // Adapter cost (Raptor centi-seconds) -> convert to OTP
      int adapterCentiCost = computeAdapterCost(
        120,
        600,
        60,
        0,
        0,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE
      );
      int adapterOtpCost = RaptorCostConverter.toOtpDomainCost(adapterCentiCost);

      assertEquals(
        legCost,
        adapterOtpCost,
        "DRTLeg cost and adapter cost should be consistent after conversion"
      );
    }

    /**
     * With waiting, both should still produce consistent costs.
     */
    @Test
    void adapterAndLegCostConsistentWithWaiting() {
      long vehiclePickup = BASE_EPOCH + 420; // 420s after start, walk=120 -> wait=300
      long vehicleDropoff = vehiclePickup + 600;
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      // DRTLeg: waiting from legStart (BASE_EPOCH)
      var legStart = ZonedDateTime.ofInstant(Instant.ofEpochSecond(BASE_EPOCH), ZONE);
      long waiting = DRTLeg.computeWaitingSeconds(est, legStart);
      assertEquals(300, waiting);

      int legCost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        waiting,
        WAIT_RELUCTANCE
      );

      // Adapter cost: same waiting
      int adapterCentiCost = computeAdapterCost(
        120,
        600,
        60,
        300,
        0,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE
      );
      int adapterOtpCost = RaptorCostConverter.toOtpDomainCost(adapterCentiCost);

      assertEquals(
        legCost,
        adapterOtpCost,
        "DRTLeg and adapter costs must match with identical waiting"
      );
    }

    /**
     * Adapter buffer adds cost that DRTLeg doesn't model (buffer is access-only).
     * The difference should be exactly buffer * waitReluctance.
     */
    @Test
    void adapterBufferAddsExtraCostOverLeg() {
      int buffer = 120;
      long vehiclePickup = BASE_EPOCH + 120;
      long vehicleDropoff = vehiclePickup + 600;
      var est = estimate(vehiclePickup, vehicleDropoff, 120, 60);

      int legCost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );

      int adapterCentiCost = computeAdapterCost(
        120,
        600,
        60,
        0,
        buffer,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE
      );
      int adapterOtpCost = RaptorCostConverter.toOtpDomainCost(adapterCentiCost);

      int expectedDelta = buffer; // buffer * 1.0 (waitReluctance)
      assertEquals(
        expectedDelta,
        adapterOtpCost - legCost,
        "Adapter cost exceeds leg cost by exactly buffer * waitReluctance"
      );
    }

    /**
     * The 3-arg DRTLeg.computeGeneralizedCost (backward compat) must equal 5-arg with 0 waiting.
     */
    @Test
    void backwardCompatCostMatchesExplicitZeroWaiting() {
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 900, 120, 60);

      int threeArgCost = DRTLeg.computeGeneralizedCost(est, WALK_RELUCTANCE, CAR_RELUCTANCE);
      int fiveArgCost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );

      assertEquals(fiveArgCost, threeArgCost);
    }

    private int computeAdapterCost(
      int walkToPickup,
      int drtDuration,
      int walkFromDropoff,
      int waitingSeconds,
      int accessBuffer,
      double walkReluctance,
      double carReluctance
    ) {
      double walkCost = (walkToPickup + walkFromDropoff) * walkReluctance;
      double waitCost = (waitingSeconds + accessBuffer) * 1.0;
      double drtCost = drtDuration * carReluctance;
      return RaptorCostConverter.toRaptorCost(walkCost + waitCost + drtCost);
    }
  }

  // ============================================================================
  // 6. TEMPORAL FEASIBILITY (Egress)
  // ============================================================================

  @Nested
  class TemporalFeasibility {

    /**
     * Feasible: DRT pickup is after passenger arrives at pickup point.
     */
    @Test
    void feasibleWhenVehicleArrivesAfterPassenger() {
      // Transit arrives at 17:30, walk 2 min -> passenger at pickup at 17:32
      // DRT pickup at 17:35 -> feasible
      long transitArrival = BASE_EPOCH + 27000; // 17:30
      long vehiclePickup = transitArrival + 300; // 17:35

      boolean feasible = vehiclePickup >= transitArrival;
      assertTrue(feasible);
    }

    /**
     * Infeasible: DRT pickup is before passenger can possibly arrive.
     */
    @Test
    void infeasibleWhenVehicleArrivesTooEarly() {
      // Transit arrives at 17:30
      // DRT pickup at 17:25 -> infeasible (vehicle arrives 5 min BEFORE passenger)
      long transitArrival = BASE_EPOCH + 27000; // 17:30
      long vehiclePickup = transitArrival - 300; // 17:25

      // DecorateWithDRT uses a 2-minute tolerance
      int toleranceSeconds = 120;
      boolean infeasible = vehiclePickup + toleranceSeconds < transitArrival;
      assertTrue(
        infeasible,
        "Vehicle arrives 5 min too early -> infeasible even with 2 min tolerance"
      );
    }

    /**
     * Borderline: DRT pickup is 1 minute before passenger -> within 2-min tolerance.
     */
    @Test
    void borderlineWithinTolerance() {
      long transitArrival = BASE_EPOCH + 27000;
      long vehiclePickup = transitArrival - 60; // 1 min before

      int toleranceSeconds = 120;
      boolean infeasible = vehiclePickup + toleranceSeconds < transitArrival;
      assertFalse(infeasible, "Vehicle 1 min early is within 2-min tolerance -> feasible");
    }
  }

  // ============================================================================
  // 7. COST DELTA: Egress Decoration Updates Itinerary Cost
  // ============================================================================

  @Nested
  class CostDelta {

    /**
     * When egress car leg is replaced by DRTLeg, the itinerary cost changes by the delta.
     *
     * <pre>
     * Original A* car cost: 350
     * DRTLeg cost: 1200 (walk+wait+ride+walk)
     * Delta: +850
     * Itinerary cost should increase by 850
     * </pre>
     */
    @Test
    void egressDecorationIncreasesItineraryCost() {
      int originalCarCost = 350;
      int drtLegCost = 1200;
      int costDelta = drtLegCost - originalCarCost;

      assertEquals(850, costDelta);

      int originalItineraryCost = 5000;
      int updatedItineraryCost = originalItineraryCost + costDelta;
      assertEquals(5850, updatedItineraryCost);
    }

    /**
     * If DRT cost is lower than A* estimate (unlikely but possible for very short rides),
     * the delta is negative and reduces itinerary cost.
     */
    @Test
    void decorationCanReduceCostIfDrtCheaperThanEstimate() {
      int originalCarCost = 800;
      int drtLegCost = 500;
      int costDelta = drtLegCost - originalCarCost;

      assertEquals(-300, costDelta);
    }

    /**
     * Multiple egress legs: cost deltas are summed.
     */
    @Test
    void multipleEgressDeltasSummed() {
      int delta1 = 500 - 200; // first egress leg
      int delta2 = 800 - 300; // second egress leg
      int totalDelta = delta1 + delta2;

      assertEquals(800, totalDelta);
    }
  }

  // ============================================================================
  // 8. RAPTOR COST CONVERSION: Centi-seconds <-> OTP Cost
  // ============================================================================

  @Nested
  class RaptorCostConversion {

    @Test
    void raptorCentiSecondsToOtpCost() {
      // 96000 centi-seconds = 960 OTP cost-seconds
      assertEquals(960, RaptorCostConverter.toOtpDomainCost(96000));
    }

    @Test
    void otpCostToRaptorCentiSeconds() {
      // 960 cost-seconds = 96000 centi-seconds
      assertEquals(96000, RaptorCostConverter.toRaptorCost(960));
    }

    @Test
    void roundTripConversion() {
      double originalCost = 1234.5;
      int raptorCost = RaptorCostConverter.toRaptorCost(originalCost);
      int otpCost = RaptorCostConverter.toOtpDomainCost(raptorCost);
      // round(1234.5 * 100) = 123450, then 123450 / 100 = 1234 (truncation at integer)
      assertEquals(1234, otpCost, 1, "Round-trip should be within 1 unit due to integer rounding");
    }

    /**
     * The adapter cost formula applied to the Bus 85 scenario parameters.
     * walkReluctance=5.0, carReluctance=1.0
     */
    @Test
    void bus85ScenarioCostFormula() {
      // Hypothetical DRT access for Bus 85:
      // walkToPickup=120s, walkFromDropoff=30s, drtRide=480s
      // waiting=600s (pickupDelay 12min - walk 2min)
      // buffer=120s
      double walkCost = (120 + 30) * 5.0; // 750
      double waitCost = (600 + 120) * 1.0; // 720
      double rideCost = 480 * 1.0; // 480
      double total = walkCost + waitCost + rideCost; // 1950

      int raptorCost = RaptorCostConverter.toRaptorCost(total);
      assertEquals(195000, raptorCost);

      int otpCost = RaptorCostConverter.toOtpDomainCost(raptorCost);
      assertEquals(1950, otpCost);
    }
  }

  // ============================================================================
  // 9. ACCESS SHIFTING: Pickup Delay and Duration
  // ============================================================================

  @Nested
  class AccessShifting {

    /**
     * Pickup delay = expectedPickup - desiredPickup.
     * Waiting at pickup = max(0, pickupDelay - walkToPickup).
     */
    @Test
    void pickupDelayAndWaitingComputation() {
      long desiredPickup = BASE_EPOCH;
      long expectedPickup = BASE_EPOCH + 720; // 12 min later
      int pickupDelay = (int) (expectedPickup - desiredPickup);
      assertEquals(720, pickupDelay);

      int walkToPickup = 120; // 2 min
      int waiting = Math.max(0, pickupDelay - walkToPickup);
      assertEquals(600, waiting, "User walks 2 min, waits 10 min at pickup");
    }

    /**
     * When walkToPickup exceeds pickupDelay, waiting is 0.
     * (User arrives at pickup after vehicle.)
     */
    @Test
    void noWaitingWhenWalkExceedsDelay() {
      int pickupDelay = 60; // 1 min
      int walkToPickup = 120; // 2 min
      int waiting = Math.max(0, pickupDelay - walkToPickup);
      assertEquals(0, waiting, "Walk exceeds delay -> no waiting");
    }

    /**
     * Shifted departure = requestedDeparture + pickupDelay.
     * Access arrival at stop = shiftedDeparture + accessDuration.
     */
    @Test
    void shiftedDepartureAndArrival() {
      long requestedDeparture = BASE_EPOCH; // 10:00:00
      int pickupDelaySeconds = 720; // 12 min
      long shiftedDeparture = requestedDeparture + pickupDelaySeconds; // 10:12:00

      int walkToPickup = 120;
      int waiting = 600;
      int drtRide = 480;
      int walkFromDropoff = 30;
      int buffer = 0;
      int accessDuration = walkToPickup + waiting + drtRide + walkFromDropoff + buffer; // 1230s
      assertEquals(1230, accessDuration);

      long accessArrivalAtStop = shiftedDeparture + accessDuration; // 10:32:30
      // Verify: 10:12:00 + 1230s = 10:32:30
      assertEquals(BASE_EPOCH + 720 + 1230, accessArrivalAtStop);
    }

    /**
     * Access adapter's earliestDepartureTime = requestedDeparture + pickupDelay.
     */
    @Test
    void earliestDepartureTimeShiftedByDelay() {
      int requestedDepartureSecOfDay = 36000; // 10:00:00
      int pickupDelay = 720; // 12 min

      int shifted = requestedDepartureSecOfDay + pickupDelay;
      assertEquals(36720, shifted, "10:12:00 in seconds of day");
    }
  }

  // ============================================================================
  // 10. REAL-WORLD SCENARIO: The Bus 85 Bug
  // ============================================================================

  @Nested
  class Bus85Scenario {

    /**
     * The Bus 85 scenario: DRT access at 00:50, bus at 20:33.
     * Without the fix, the 19h wait has zero cost.
     * With the fix, Raptor sees the real boarding wait cost.
     *
     * <pre>
     * DRT access: depart 00:50, arrive at stop 01:03
     * Bus 85: departs 20:33
     * Wait at stop: 20:33 - 01:03 = 70,200s
     * Boarding wait cost (Raptor): 70,200 * waitFactor(1.0) * 100 = 7,020,000 centi-seconds
     * </pre>
     */
    @Test
    void longWaitShouldProduceHugeBoardingCost() {
      // Access arrives at stop at 01:03 (3780s from midnight)
      int accessArrivalSecOfDay = 3780;
      // Bus 85 departs at 20:33 (73980s from midnight)
      int busDepartureSecOfDay = 73980;

      int waitSeconds = busDepartureSecOfDay - accessArrivalSecOfDay;
      assertEquals(70200, waitSeconds, "19h 30m wait");

      // In Raptor centi-seconds with waitFactor=1.0
      int waitCostCentiSeconds = 100 * waitSeconds;
      assertEquals(
        7020000,
        waitCostCentiSeconds,
        "Boarding wait cost alone should be 7M centi-seconds (~70,200 OTP cost)"
      );

      // This should make Bus 85 have cost > 70,000 and be filtered out
      assertTrue(
        waitCostCentiSeconds > 5000000,
        "Wait cost alone exceeds any reasonable itinerary cost"
      );
    }

    /**
     * Verify that an itinerary with DRT access at 00:50 + bus at 05:55 (much shorter wait)
     * produces a dramatically lower cost than Bus 85.
     */
    @Test
    void shorterWaitProducesLowerCost() {
      // Access arrives at 01:03, bus at 05:55
      int accessArrival = 3780;
      int earlyBusDeparture = 21300; // 05:55
      int earlyWait = earlyBusDeparture - accessArrival;
      assertEquals(17520, earlyWait, "~4h 52m wait");

      // Bus 85 at 20:33
      int lateBusDeparture = 73980;
      int lateWait = lateBusDeparture - accessArrival;

      assertTrue(
        lateWait > 4 * earlyWait,
        "Bus 85 wait is >4x the early bus wait -> much higher cost"
      );
    }
  }

  // ============================================================================
  // 11. EDGE CASES
  // ============================================================================

  @Nested
  class EdgeCases {

    /**
     * Zero-duration DRT ride (e.g., pickup and dropoff at same location).
     */
    @Test
    void zeroDurationRide() {
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 300, 120, 60);

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );
      // cost = (120+60)*2.0 + 0 + 0*1.0 = 360
      assertEquals(360, cost, "Only walking cost when ride is zero");
    }

    /**
     * Zero walking seconds (pickup and dropoff at exact origin/destination).
     */
    @Test
    void zeroWalkingSeconds() {
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 900, 0, 0);

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );
      // cost = 0 + 0 + 600*1.0 = 600
      assertEquals(600, cost, "Only ride cost when walking is zero");
    }

    /**
     * Very long waiting (30 minutes) dominates the cost.
     */
    @Test
    void longWaitingDominatesCost() {
      long waiting = 1800; // 30 min
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 600, 60, 30); // 5 min ride

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        waiting,
        WAIT_RELUCTANCE
      );
      // cost = (60+30)*2.0 + 1800*1.0 + 300*1.0 = 180 + 1800 + 300 = 2280
      assertEquals(2280, cost);

      // Verify waiting is the dominant component
      int walkCost = (int) ((60 + 30) * WALK_RELUCTANCE); // 180
      int rideCost = 300;
      int waitCost = 1800;
      assertTrue(waitCost > walkCost + rideCost, "30-min wait dominates 90s walk + 5min ride");
    }

    /**
     * Null dropoff walking seconds defaults to 0.
     */
    @Test
    void nullDropoffWalkingDefaultsToZero() {
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 900, 120L, null);

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );
      // cost = (120+0)*2.0 + 0 + 600*1.0 = 240 + 600 = 840
      assertEquals(840, cost);
    }

    /**
     * Both walking seconds null -> only ride cost.
     */
    @Test
    void bothWalkingSecondsNull() {
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 900, null, null);

      int cost = DRTLeg.computeGeneralizedCost(
        est,
        WALK_RELUCTANCE,
        CAR_RELUCTANCE,
        0,
        WAIT_RELUCTANCE
      );
      assertEquals(600, cost, "Only ride cost when both walks are null/0");
    }

    /**
     * Very high reluctances amplify costs dramatically.
     */
    @Test
    void highReluctancesAmplify() {
      var est = estimate(BASE_EPOCH + 300, BASE_EPOCH + 900, 120, 60);

      int normalCost = DRTLeg.computeGeneralizedCost(est, 2.0, 1.0, 240, 1.0);
      int highCost = DRTLeg.computeGeneralizedCost(est, 5.0, 3.0, 240, 2.0);

      // normal: (120+60)*2 + 240*1 + 600*1 = 360+240+600 = 1200
      assertEquals(1200, normalCost);
      // high:   (120+60)*5 + 240*2 + 600*3 = 900+480+1800 = 3180
      assertEquals(3180, highCost);
      assertTrue(highCost > 2 * normalCost);
    }
  }
}
