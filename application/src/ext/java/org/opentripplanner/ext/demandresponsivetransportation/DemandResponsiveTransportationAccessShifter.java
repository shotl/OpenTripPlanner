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
import org.opentripplanner.street.search.state.CarPickupState;
import org.opentripplanner.transit.model.framework.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility method to shift the start of the journey and adjust access/egress duration based on
 * actual DRT travel times from the service API.
 */
public class DemandResponsiveTransportationAccessShifter {

  private static final Logger LOG = LoggerFactory.getLogger(
    DemandResponsiveTransportationAccessShifter.class
  );

  /**
   * Given a list of {@link RoutingAccessEgress}, shift the ones that contain driving
   * so that they use actual DRT travel times instead of car-based durations.
   * <p>
   * For access: calls Shotl with origin → stop coordinates, shifts pickup time and duration.
   * For egress: calls Shotl with stop → destination coordinates, adjusts duration.
   */
  public static List<RoutingAccessEgress> shiftAccesses(
    boolean isAccess,
    List<RoutingAccessEgress> results,
    List<DemandResponsiveTransportationService> services,
    RouteRequest request,
    Instant now
  ) {
    if (!isAccess) {
      // Egress shifting is deferred to the leg decoration phase (DecorateWithDRT),
      // which has the actual transit arrival time to use as the pickup time.
      return results;
    }

    if (!shouldShift(request, Instant.now())) {
      return results;
    }

    return results
      .parallelStream()
      .map(ae -> {
        if (!ae.getLastState().containsModeCar()) {
          return ae;
        }
        return shiftWithDrtTimes(ae, services, request, now, true);
      })
      .filter(Objects::nonNull)
      .collect(Collectors.toList());
  }

  private static RoutingAccessEgress shiftWithDrtTimes(
    RoutingAccessEgress ae,
    List<DemandResponsiveTransportationService> services,
    RouteRequest request,
    Instant now,
    boolean isAccess
  ) {
    var stopCoordinate = stopCoordinateFromAccessEgress(ae, isAccess);

    // For access: origin → stop. For egress: stop → destination.
    var fromCoordinate = isAccess
      ? new WgsCoordinate(request.from().getCoordinate())
      : stopCoordinate;
    var toCoordinate = isAccess ? stopCoordinate : new WgsCoordinate(request.to().getCoordinate());

    var result = fetchDrtEstimate(services, request, now, fromCoordinate, toCoordinate, isAccess);

    if (result.isSuccess()) {
      var shift = result.successValue();
      return new DemandResponsiveTransportationAccessAdapter(
        ae,
        shift.pickupDelay(),
        shift.drtTravelDuration()
      );
    } else {
      return null;
    }
  }

  private static WgsCoordinate stopCoordinateFromAccessEgress(
    RoutingAccessEgress ae,
    boolean isAccess
  ) {
    var state = ae.getLastState();
    if (isAccess) {
      // For access: walk backward from TransitStopVertex through the WALK_FROM_DROP_OFF
      // states to find the car drop-off vertex (last IN_CAR state). This matches
      // the coordinate the leg decorator uses as the car leg's "to" place, which is
      // determined by the CarPickupState split in GraphPathToItineraryMapper.sliceStates.
      while (state.getBackState() != null && state.getCarPickupState() != CarPickupState.IN_CAR) {
        state = state.getBackState();
      }
    } else {
      // For egress (reversed chain with proper CarPickupState after State.reverse() fix):
      // DestinationVertex(WALK_FROM_DROP_OFF) -> ... -> IN_CAR -> ... -> WALK_TO_PICKUP -> ... -> TransitStopVertex
      // Walk backward from DestinationVertex through WALK_FROM_DROP_OFF and IN_CAR states
      // to find the car pickup boundary (first WALK_TO_PICKUP). This matches the coordinate
      // the leg decorator uses as the car leg's "from" place.
      while (
        state.getBackState() != null && state.getCarPickupState() != CarPickupState.WALK_TO_PICKUP
      ) {
        state = state.getBackState();
      }
    }
    var vertex = state.getVertex();
    return new WgsCoordinate(vertex.getLat(), vertex.getLon());
  }

  private static Result<DrtShiftResult, Error> fetchDrtEstimate(
    List<DemandResponsiveTransportationService> services,
    RouteRequest request,
    Instant now,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    boolean isAccess
  ) {
    var shiftingResult = shiftTime(request, services, now, fromCoordinate, toCoordinate, isAccess);
    if (shiftingResult.isSuccess()) {
      return Result.success(shiftingResult.successValue());
    } else {
      LOG.error(
        "Could not fetch DRT estimate from ({},{}) to ({},{}): {}",
        fromCoordinate.latitude(),
        fromCoordinate.longitude(),
        toCoordinate.latitude(),
        toCoordinate.longitude(),
        shiftingResult.failureValue()
      );
      return Result.failure(shiftingResult.failureValue());
    }
  }

  private static boolean shouldShift(RouteRequest req, Instant now) {
    return (
      (req.journey().modes().accessMode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION ||
        req.journey().modes().egressMode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION) &&
      !req.arriveBy()
    );
  }

  private static Result<DrtShiftResult, Error> shiftTime(
    RouteRequest req,
    List<DemandResponsiveTransportationService> services,
    Instant now,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    boolean isAccess
  ) {
    if (req.demandResponsiveExtData() == null) {
      return Result.failure(Error.NO_ARRIVAL_FOR_LOCATION);
    }

    var service = services.get(0);

    Instant desiredPickupTime = req.dateTime();

    var drtEstimationResponse = service.arrivalTimes(
      req.demandResponsiveExtData().paxAppId(),
      req.demandResponsiveExtData().areaId(),
      req.demandResponsiveExtData().userId(),
      req.demandResponsiveExtData().rideType(),
      fromCoordinate,
      toCoordinate,
      req.demandResponsiveExtData().passengers().regular(),
      req.demandResponsiveExtData().passengers().wheelchair(),
      desiredPickupTime,
      isAccess ? DrtRequestContext.ACCESS_SHIFTING : DrtRequestContext.EGRESS_SHIFTING,
      req.demandResponsiveExtData().passengerFareType(),
      false
    );
    if (drtEstimationResponse == null) {
      return Result.failure(Error.NO_ARRIVAL_FOR_LOCATION);
    }

    if (drtEstimationResponse.user_expected_dropoff_time() == null) {
      LOG.warn(
        "DRT response missing dropoff time from ({},{}) to ({},{})",
        fromCoordinate.latitude(),
        fromCoordinate.longitude(),
        toCoordinate.latitude(),
        toCoordinate.longitude()
      );
      return Result.failure(Error.NO_ARRIVAL_FOR_LOCATION);
    }

    Instant userExpectedPickupTime = Instant.ofEpochSecond(
      drtEstimationResponse.user_expected_pickup_time()
    );
    Instant userExpectedDropoffTime = Instant.ofEpochSecond(
      drtEstimationResponse.user_expected_dropoff_time()
    );

    Duration pickupDelay = Duration.between(desiredPickupTime, userExpectedPickupTime);
    if (pickupDelay.isNegative()) {
      pickupDelay = Duration.ZERO;
    }

    Duration drtTravelDuration;
    if (
      drtEstimationResponse.shotl_duration_seconds() != null &&
      drtEstimationResponse.shotl_duration_seconds() > 0
    ) {
      drtTravelDuration = Duration.ofSeconds(drtEstimationResponse.shotl_duration_seconds());
    } else {
      drtTravelDuration = Duration.between(userExpectedPickupTime, userExpectedDropoffTime);
    }
    if (drtTravelDuration.isNegative() || drtTravelDuration.isZero()) {
      LOG.warn(
        "DRT travel duration is non-positive ({}) from ({},{}) to ({},{})",
        drtTravelDuration,
        fromCoordinate.latitude(),
        fromCoordinate.longitude(),
        toCoordinate.latitude(),
        toCoordinate.longitude()
      );
      return Result.failure(Error.TECHNICAL_ERROR);
    }

    LOG.info(
      "DRT time shift: from=({},{}) to=({},{}) | requested={} | expectedPickup={} | expectedDropoff={} | pickupDelay={} | drtDuration={} | shotlDurationSeconds={}",
      fromCoordinate.latitude(),
      fromCoordinate.longitude(),
      toCoordinate.latitude(),
      toCoordinate.longitude(),
      desiredPickupTime,
      userExpectedPickupTime,
      userExpectedDropoffTime,
      pickupDelay,
      drtTravelDuration,
      drtEstimationResponse.shotl_duration_seconds()
    );

    return Result.success(new DrtShiftResult(pickupDelay, drtTravelDuration));
  }

  /**
   * When you start a DRT search, you cannot assume to leave right away but have to take into
   * account both the pickup delay and the actual DRT travel duration to each transit stop.
   */
  protected static Result<DrtShiftResult, Error> arrivalDelay(
    RouteRequest req,
    List<DemandResponsiveTransportationService> services,
    Instant now,
    WgsCoordinate stopCoordinate
  ) {
    if (shouldShift(req, now)) {
      return shiftTime(
        req,
        services,
        now,
        new WgsCoordinate(req.from().getCoordinate()),
        stopCoordinate,
        true
      );
    } else {
      return Result.success(new DrtShiftResult(Duration.ZERO, Duration.ZERO));
    }
  }

  record DrtShiftResult(Duration pickupDelay, Duration drtTravelDuration) {}

  enum Error {
    NO_ARRIVAL_FOR_LOCATION,
    TECHNICAL_ERROR,
  }
}
