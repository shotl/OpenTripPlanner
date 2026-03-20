package org.opentripplanner.ext.demandresponsivetransportation.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.model.plan.StreetLegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A specialized leg for Demand Responsive Transportation that replaces a car StreetLeg with
 * real DRT provider data (Shotl). The leg models four phases:
 * <ol>
 *   <li><b>Walk to pickup</b> — from origin/station to the vehicle pickup point</li>
 *   <li><b>Wait for vehicle</b> — standing at the pickup point until the vehicle arrives</li>
 *   <li><b>DRT ride</b> — in-vehicle travel time</li>
 *   <li><b>Walk from dropoff</b> — from the vehicle dropoff point to destination/station</li>
 * </ol>
 * <p>
 * The leg's {@code startTime} is when the user begins walking to the pickup point.
 * The leg's {@code endTime} is when the user finishes walking from the dropoff point.
 * The waiting time is the gap between arriving at the pickup point and the vehicle picking up.
 */
public class DRTLeg extends StreetLeg {

  private static final Logger LOG = LoggerFactory.getLogger(DRTLeg.class);

  private final ShotlArrivalEstimateResponse estimate;
  private final long waitingSeconds;

  /**
   * Creates a DRTLeg with an explicit start time that determines the waiting time.
   * <p>
   * The waiting time is computed as the gap between when the user arrives at the pickup point
   * (startTime + pickup_walking_seconds) and when the vehicle picks them up
   * (user_expected_pickup_time).
   *
   * @param streetLeg       the original A* car leg being replaced
   * @param estimate        the Shotl API response with real times
   * @param generalizedCost the recomputed generalized cost (including waiting)
   * @param legStartTime    when the user starts walking to the pickup point; determines waiting
   */
  public DRTLeg(
    StreetLeg streetLeg,
    ShotlArrivalEstimateResponse estimate,
    int generalizedCost,
    ZonedDateTime legStartTime
  ) {
    super(
      StreetLegBuilder.of(streetLeg)
        .withStartTime(legStartTime)
        .withEndTime(
          Instant.ofEpochSecond(
            estimate.user_expected_dropoff_time() + dropoffWalkingSeconds(estimate)
          ).atZone(streetLeg.getEndTime().getZone())
        )
        .withGeneralizedCost(generalizedCost)
    );
    this.estimate = estimate;
    this.waitingSeconds = computeWaitingSeconds(estimate, legStartTime);
  }

  /**
   * Convenience constructor that back-computes the start time assuming the user starts walking
   * exactly {@code pickup_walking_seconds} before the vehicle arrives (i.e., zero waiting).
   * Use this only when the actual start time is not known (e.g., access legs where the
   * departure is controlled by the user).
   */
  public DRTLeg(StreetLeg streetLeg, ShotlArrivalEstimateResponse estimate, int generalizedCost) {
    this(
      streetLeg,
      estimate,
      generalizedCost,
      Instant.ofEpochSecond(
        estimate.user_expected_pickup_time() - pickupWalkingSeconds(estimate)
      ).atZone(streetLeg.getStartTime().getZone())
    );
  }

  /**
   * Compute the generalized cost (in seconds) for a DRT leg, applying walk reluctance
   * to walking portions, wait reluctance to waiting, and car reluctance to the DRT ride.
   *
   * @param waitingSeconds seconds the user waits at the pickup point for the vehicle
   * @param waitReluctance reluctance multiplier for waiting (typically 1.0, same as transit wait)
   */
  public static int computeGeneralizedCost(
    ShotlArrivalEstimateResponse estimate,
    double walkReluctance,
    double carReluctance,
    long waitingSeconds,
    double waitReluctance
  ) {
    long walkToPickup = pickupWalkingSeconds(estimate);
    long walkFromDropoff = dropoffWalkingSeconds(estimate);
    long drtDuration = estimate.user_expected_dropoff_time() - estimate.user_expected_pickup_time();
    return (int) Math.round(
      (walkToPickup + walkFromDropoff) * walkReluctance +
      waitingSeconds * waitReluctance +
      drtDuration * carReluctance
    );
  }

  /**
   * Backward-compatible cost computation with zero waiting.
   */
  public static int computeGeneralizedCost(
    ShotlArrivalEstimateResponse estimate,
    double walkReluctance,
    double carReluctance
  ) {
    return computeGeneralizedCost(estimate, walkReluctance, carReluctance, 0, 1.0);
  }

  /**
   * Compute the waiting time in seconds from the leg start time and the Shotl response.
   * Waiting = time between user arriving at pickup point and vehicle arriving.
   */
  public static long computeWaitingSeconds(
    ShotlArrivalEstimateResponse estimate,
    ZonedDateTime legStartTime
  ) {
    long userAtPickupEpoch = legStartTime.toEpochSecond() + pickupWalkingSeconds(estimate);
    long vehicleAtPickupEpoch = estimate.user_expected_pickup_time();
    return Math.max(0, vehicleAtPickupEpoch - userAtPickupEpoch);
  }

  /**
   * Compute the waiting time from the previous leg's end time.
   * The user starts walking at {@code previousLegEnd}, arrives at pickup point after
   * {@code pickup_walking_seconds}, and then waits until the vehicle arrives.
   */
  public static long computeWaitingSeconds(
    ShotlArrivalEstimateResponse estimate,
    Instant previousLegEnd
  ) {
    long userAtPickupEpoch = previousLegEnd.getEpochSecond() + pickupWalkingSeconds(estimate);
    long vehicleAtPickupEpoch = estimate.user_expected_pickup_time();
    return Math.max(0, vehicleAtPickupEpoch - userAtPickupEpoch);
  }

  static long pickupWalkingSeconds(ShotlArrivalEstimateResponse estimate) {
    if (estimate.pickup_walking_seconds() == null) {
      LOG.warn(
        "DRT response missing pickup_walking_seconds for estimate id={}, defaulting to 0",
        estimate.id()
      );
      return 0L;
    }
    return estimate.pickup_walking_seconds();
  }

  static long dropoffWalkingSeconds(ShotlArrivalEstimateResponse estimate) {
    if (estimate.dropoff_walking_seconds() == null) {
      LOG.warn(
        "DRT response missing dropoff_walking_seconds for estimate id={}, defaulting to 0",
        estimate.id()
      );
      return 0L;
    }
    return estimate.dropoff_walking_seconds();
  }

  private DRTLeg(
    StreetLegBuilder builder,
    ShotlArrivalEstimateResponse estimate,
    long waitingSeconds
  ) {
    super(builder);
    this.estimate = estimate;
    this.waitingSeconds = waitingSeconds;
  }

  @Override
  public Leg withTimeShift(Duration duration) {
    return new DRTLeg(
      StreetLegBuilder.of(this)
        .withStartTime(getStartTime().plus(duration))
        .withEndTime(getEndTime().plus(duration)),
      estimate,
      waitingSeconds
    );
  }

  public ShotlArrivalEstimateResponse ride() {
    return estimate;
  }

  public ShotlArrivalEstimateResponse rideEstimate() {
    return estimate;
  }

  /**
   * Returns the number of seconds the user waits at the pickup point for the DRT vehicle.
   */
  public long getWaitingSeconds() {
    return waitingSeconds;
  }
}
