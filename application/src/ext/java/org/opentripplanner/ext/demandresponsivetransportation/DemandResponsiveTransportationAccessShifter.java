package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RoutingAccessEgress;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.transit.model.framework.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility method to shift the start of the journey to the earliest time that a vehicle can arrive.
 */
public class DemandResponsiveTransportationAccessShifter {

  private static final Logger LOG = LoggerFactory.getLogger(
    DemandResponsiveTransportationAccessShifter.class
  );

  /**
   * Given a list of {@link RoutingAccessEgress}, shift the access ones that contain driving
   * so that they only start at the time when the ride hailing vehicle can actually be there
   * to pick up passengers.
   */
  public static List<RoutingAccessEgress> shiftAccesses(
    boolean isAccess,
    List<RoutingAccessEgress> results,
    List<DemandResponsiveTransportationService> services,
    RouteRequest request,
    Instant now
  ) {
    return results
      .stream()
      .map(ae -> {
        // Only process car-based legs (walk-only accesses/egresses pass through unchanged)
        if (ae.getLastState().containsModeCar()) {
          if (isAccess) {
            // Time-shift access legs based on DRT vehicle arrival delay
            var duration = fetchArrivalDelay(services, request, now);
            if (duration.isSuccess()) {
              return new DemandResponsiveTransportationAccessAdapter(ae, duration.successValue());
            } else {
              return null;
            }
          } else {
            // For egress, verify DRT service is reachable; filter out if not
            var duration = fetchArrivalDelay(services, request, now);
            if (duration.isSuccess()) {
              return ae;
            } else {
              return null;
            }
          }
        } else {
          return ae;
        }
      })
      .filter(Objects::nonNull)
      .collect(Collectors.toList());
  }

  private static Result<Duration, Error> fetchArrivalDelay(
    List<DemandResponsiveTransportationService> services,
    RouteRequest request,
    Instant now
  ) {
    // we have to shift the start time of a car hailing request because often we cannot leave right
    // away
    if (DemandResponsiveTransportationAccessShifter.shouldShift(request, Instant.now())) {
      var shiftingResult = DemandResponsiveTransportationAccessShifter.arrivalDelay(
        request,
        services,
        now
      );
      if (shiftingResult.isSuccess()) {
        return Result.success(shiftingResult.successValue());
      } else {
        LOG.error(
          "Could not fetch arrival time for car hailing service: {}",
          shiftingResult.failureValue()
        );
        return Result.failure(Error.TECHNICAL_ERROR);
      }
    } else {
      return Result.success(Duration.ZERO);
    }
  }

  /**
   * When you start a car hailing search for right now (which is common) you cannot assume to leave
   * right away but have to take into account the duration it takes for the hailing vehicle to
   * arrive.
   * <p>
   * This method shifts the departure time by the appropriate amount so that the correct
   * access/egresses can be calculated.
   */
  protected static Result<Duration, Error> arrivalDelay(
    RouteRequest req,
    List<DemandResponsiveTransportationService> services,
    Instant now
  ) {
    if (shouldShift(req, now)) {
      return shiftTime(req, services, now);
    } else {
      return Result.success(Duration.ZERO);
    }
  }

  private static boolean shouldShift(RouteRequest req, Instant now) {
    // For DRT services, we always check with the service API regardless of how far in the future
    // the request is, since DRT has limited capacity and specific schedules unlike ride-hailing.
    return (
      req.journey().modes().accessMode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION &&
      !req.arriveBy()
    );
  }

  private static Result<Duration, Error> shiftTime(
    RouteRequest req,
    List<DemandResponsiveTransportationService> services,
    Instant now
  ) {
    if (req.demandResponsiveExtData() == null) {
      return Result.failure(Error.NO_ARRIVAL_FOR_LOCATION);
    }

    var service = services.get(0);

    // Use the user's requested departure time, not "now"
    Instant desiredPickupTime = req.dateTime();

    LOG.info("shifting access for DRT, desired pickup time: {}", desiredPickupTime);

    var drtEstimationResponse = service.arrivalTimes(
      req.demandResponsiveExtData().paxAppId(),
      req.demandResponsiveExtData().areaId(),
      req.demandResponsiveExtData().userId(),
      req.demandResponsiveExtData().rideType(),
      new WgsCoordinate(req.from().getCoordinate()),
      new WgsCoordinate(req.to().getCoordinate()),
      req.demandResponsiveExtData().passengers().regular(),
      req.demandResponsiveExtData().passengers().wheelchair(),
      desiredPickupTime,
      DrtRequestContext.ACCESS_SHIFTING
    );
    if (drtEstimationResponse == null) {
      return Result.failure(Error.NO_ARRIVAL_FOR_LOCATION);
    }

    Instant userExpectedPickupTime = Instant.ofEpochSecond(
      drtEstimationResponse.user_expected_pickup_time()
    );

    // Calculate how much later than requested the actual pickup will be
    Duration totalDelay = Duration.between(desiredPickupTime, userExpectedPickupTime);

    // Ensure the delay is not negative
    if (totalDelay.isNegative()) {
      totalDelay = Duration.ZERO;
    }

    LOG.info(
      "DRT time shift: requested={}, expected={}, delay={}",
      desiredPickupTime,
      userExpectedPickupTime,
      totalDelay
    );

    return Result.success(totalDelay);
  }

  enum Error {
    NO_ARRIVAL_FOR_LOCATION,
    TECHNICAL_ERROR,
  }
}
