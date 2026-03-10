package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import org.opentripplanner.framework.model.TimeAndCost;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.DefaultAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RoutingAccessEgress;

/**
 * This class is used to adapt the DRT accesses (not egresses) into a time-dependent
 * multi-leg {@link DefaultAccessEgress}.
 * <p>
 * It shifts the departure time by the pickup delay and overrides the access duration
 * with the actual DRT travel duration (from the Shotl API), replacing the car-based
 * duration from street routing.
 */
public final class DemandResponsiveTransportationAccessAdapter extends DefaultAccessEgress {

  private final Duration arrival;
  private final int drtDurationSeconds;

  public DemandResponsiveTransportationAccessAdapter(
    RoutingAccessEgress access,
    Duration arrival,
    Duration drtDuration
  ) {
    super(access.stop(), access.getLastState());
    this.arrival = arrival;
    this.drtDurationSeconds = (int) drtDuration.toSeconds();
  }

  public DemandResponsiveTransportationAccessAdapter(
    DemandResponsiveTransportationAccessAdapter other,
    TimeAndCost penalty
  ) {
    super(other, penalty);
    this.arrival = other.arrival;
    this.drtDurationSeconds = other.drtDurationSeconds;
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
      this.drtDurationSeconds + additionalWalkSeconds,
      this.getLastState()
    );
  }

  private DemandResponsiveTransportationAccessAdapter(
    int stopIndex,
    Duration arrival,
    int drtDurationSeconds,
    org.opentripplanner.street.search.state.State lastState
  ) {
    super(stopIndex, lastState);
    this.arrival = arrival;
    this.drtDurationSeconds = drtDurationSeconds;
  }

  @Override
  public int durationInSeconds() {
    return drtDurationSeconds;
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

  @Override
  public String toString() {
    return asString(true, false, null);
  }
}
