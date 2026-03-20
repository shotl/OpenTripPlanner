package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import javax.annotation.Nullable;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.model.TimeAndCost;
import org.opentripplanner.raptor.api.model.RaptorCostConverter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.DefaultAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RoutingAccessEgress;

/**
 * This class is used to adapt the DRT accesses (not egresses) into a time-dependent
 * multi-leg {@link DefaultAccessEgress}.
 * <p>
 * It shifts the departure time by the pickup delay and overrides the access duration
 * with the actual DRT travel duration (from the Shotl API), replacing the car-based
 * duration from street routing.
 * <p>
 * The generalized cost is recomputed using the real DRT travel duration and the walking
 * durations reported by the DRT API, each multiplied by the appropriate reluctance:
 * <pre>
 *   cost = (walkToPickup + walkFromDropoff) × walkReluctance + drtDuration × carReluctance
 * </pre>
 * This replaces the stale cost from the A* street routing, which was based on OSM car speeds
 * rather than the actual DRT travel time.
 */
public final class DemandResponsiveTransportationAccessAdapter extends DefaultAccessEgress {

  private final Duration arrival;
  private final int drtDurationSeconds;
  private final int walkToPickupSeconds;
  private final int walkFromDropoffSeconds;
  private final int waitingSeconds;
  private final double walkReluctance;
  private final double carReluctance;

  @Nullable
  private final ShotlArrivalEstimateResponse shotlResponse;

  public DemandResponsiveTransportationAccessAdapter(
    RoutingAccessEgress access,
    Duration arrival,
    Duration drtDuration,
    long walkToPickupSeconds,
    long walkFromDropoffSeconds,
    long waitingSeconds,
    double walkReluctance,
    double carReluctance,
    @Nullable ShotlArrivalEstimateResponse shotlResponse
  ) {
    super(
      access.stop(),
      (int) (walkToPickupSeconds +
        waitingSeconds +
        drtDuration.toSeconds() +
        walkFromDropoffSeconds),
      computeGeneralizedCost(
        (int) walkToPickupSeconds,
        (int) drtDuration.toSeconds(),
        (int) walkFromDropoffSeconds,
        (int) waitingSeconds,
        walkReluctance,
        carReluctance
      ),
      TimeAndCost.ZERO,
      access.getLastState()
    );
    this.arrival = arrival;
    this.drtDurationSeconds = (int) drtDuration.toSeconds();
    this.walkToPickupSeconds = (int) walkToPickupSeconds;
    this.walkFromDropoffSeconds = (int) walkFromDropoffSeconds;
    this.waitingSeconds = (int) waitingSeconds;
    this.walkReluctance = walkReluctance;
    this.carReluctance = carReluctance;
    this.shotlResponse = shotlResponse;
  }

  public DemandResponsiveTransportationAccessAdapter(
    DemandResponsiveTransportationAccessAdapter other,
    TimeAndCost penalty
  ) {
    super(other, penalty);
    this.arrival = other.arrival;
    this.drtDurationSeconds = other.drtDurationSeconds;
    this.walkToPickupSeconds = other.walkToPickupSeconds;
    this.walkFromDropoffSeconds = other.walkFromDropoffSeconds;
    this.waitingSeconds = other.waitingSeconds;
    this.walkReluctance = other.walkReluctance;
    this.carReluctance = other.carReluctance;
    this.shotlResponse = other.shotlResponse;
  }

  /**
   * Creates a copy of this adapter for a sibling stop that shares the same parent station
   * as the original DRT stop. The DRT leg is identical (same pickup delay, same route to
   * the DRT stop), but additional walk time is added for the transfer from the DRT stop
   * to the sibling stop.
   * <p>
   * This avoids extra DRT API calls: the DRT estimate is computed once for the car-accessible
   * stop, then reused for all sibling stops with only the walk time difference.
   */
  public DemandResponsiveTransportationAccessAdapter forSiblingStop(
    int siblingStopIndex,
    int additionalWalkSeconds
  ) {
    return new DemandResponsiveTransportationAccessAdapter(
      siblingStopIndex,
      this.arrival,
      this.drtDurationSeconds,
      this.walkToPickupSeconds,
      this.walkFromDropoffSeconds + additionalWalkSeconds,
      this.waitingSeconds,
      this.walkReluctance,
      this.carReluctance,
      this.getLastState(),
      this.shotlResponse
    );
  }

  private DemandResponsiveTransportationAccessAdapter(
    int stopIndex,
    Duration arrival,
    int drtDurationSeconds,
    int walkToPickupSeconds,
    int walkFromDropoffSeconds,
    int waitingSeconds,
    double walkReluctance,
    double carReluctance,
    org.opentripplanner.street.search.state.State lastState,
    @Nullable ShotlArrivalEstimateResponse shotlResponse
  ) {
    super(
      stopIndex,
      walkToPickupSeconds + waitingSeconds + drtDurationSeconds + walkFromDropoffSeconds,
      computeGeneralizedCost(
        walkToPickupSeconds,
        drtDurationSeconds,
        walkFromDropoffSeconds,
        waitingSeconds,
        walkReluctance,
        carReluctance
      ),
      TimeAndCost.ZERO,
      lastState
    );
    this.arrival = arrival;
    this.drtDurationSeconds = drtDurationSeconds;
    this.walkToPickupSeconds = walkToPickupSeconds;
    this.walkFromDropoffSeconds = walkFromDropoffSeconds;
    this.waitingSeconds = waitingSeconds;
    this.walkReluctance = walkReluctance;
    this.carReluctance = carReluctance;
    this.shotlResponse = shotlResponse;
  }

  @Override
  public int earliestDepartureTime(int requestedDepartureTime) {
    return super.earliestDepartureTime(requestedDepartureTime) + (int) arrival.toSeconds();
  }

  @Override
  public int latestArrivalTime(int requestedArrivalTime) {
    return super.latestArrivalTime(requestedArrivalTime) + (int) arrival.toSeconds();
  }

  @Override
  public int numberOfRides() {
    // We only support one leg at the moment
    return 1;
  }

  @Override
  public boolean hasOpeningHours() {
    return true;
  }

  @Override
  public String openingHoursToString() {
    return "Arrival in " + arrival.toString();
  }

  @Override
  public RoutingAccessEgress withPenalty(TimeAndCost penalty) {
    return new DemandResponsiveTransportationAccessAdapter(this, penalty);
  }

  /**
   * Returns the original Shotl API response stored during the access shifting phase,
   * or {@code null} if no response was stored (e.g., for the no-shift case).
   */
  @Nullable
  public ShotlArrivalEstimateResponse getShotlResponse() {
    return shotlResponse;
  }

  public double getWalkReluctance() {
    return walkReluctance;
  }

  public double getCarReluctance() {
    return carReluctance;
  }

  @Override
  public String toString() {
    return asString(true, false, null);
  }

  /**
   * Compute the generalized cost (in Raptor centi-seconds) for a DRT access/egress path,
   * applying walk reluctance to walking portions, car reluctance to the DRT ride portion,
   * and a wait reluctance (1.0, same as transit wait) to the waiting time at the pickup point.
   */
  private static int computeGeneralizedCost(
    int walkToPickupSeconds,
    int drtDurationSeconds,
    int walkFromDropoffSeconds,
    int waitingSeconds,
    double walkReluctance,
    double carReluctance
  ) {
    double walkCost = (walkToPickupSeconds + walkFromDropoffSeconds) * walkReluctance;
    double waitCost = waitingSeconds * 1.0; // waitReluctance = 1.0, same as transit wait
    double drtCost = drtDurationSeconds * carReluctance;
    return RaptorCostConverter.toRaptorCost(walkCost + waitCost + drtCost);
  }
}
