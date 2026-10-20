package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.api.request.PassengerFareType;

/**
 * A service for querying ride hailing information to be used during routing.
 */
public interface DemandResponsiveTransportationService {
  /**
   * Get the next arrivals for a specific location.
   *
   * @param paxAppId the passenger app ID
   * @param areaId the DRT service area ID
   * @param userId the user ID
   * @param rideType the type of ride
   * @param fromCoordinate pickup location
   * @param toCoordinate dropoff location
   * @param regularPassengers number of regular passengers
   * @param wheelchairPassengers number of wheelchair passengers
   * @param desiredPickupTime requested pickup time
   * @param context identifies the caller (shifting vs decorating) for logging
   * @param passengerFareType optional passenger fare types for pricing calculation
   * @param pickupShift whether this is a pickup shift request (true for egress decoration)
   * @param acceptLanguage Accept-Language header value to forward to the DRT provider
   * @param requestId the caller's request id, sent as {@code X-Request-Id}; null sends none
   */
  ShotlArrivalEstimateResponse arrivalTimes(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    int regularPassengers,
    int wheelchairPassengers,
    Instant desiredPickupTime,
    DrtRequestContext context,
    List<PassengerFareType> passengerFareType,
    boolean pickupShift,
    String acceptLanguage,
    String requestId
  );

  /**
   * Estimates the legs of one routing phase (every access leg, or every egress leg) that share
   * everything but their places and pickup time. Answers one response per item, in order, with
   * null where a leg has no estimate.
   * <p>
   * The default asks for each leg on its own, concurrently on the DRT I/O pool, which is what the
   * callers did before batches existed.
   */
  default List<ShotlArrivalEstimateResponse> arrivalTimesBatch(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    int regularPassengers,
    int wheelchairPassengers,
    DrtRequestContext context,
    List<PassengerFareType> passengerFareType,
    boolean pickupShift,
    String acceptLanguage,
    String requestId,
    List<DrtEstimateItem> items
  ) {
    var io = DrtIoExecutor.getInstance();
    var futures = items
      .stream()
      .map(item ->
        CompletableFuture.supplyAsync(
          () ->
            arrivalTimes(
              paxAppId,
              areaId,
              userId,
              rideType,
              item.from(),
              item.to(),
              regularPassengers,
              wheelchairPassengers,
              item.pickupTime(),
              context,
              passengerFareType,
              pickupShift,
              acceptLanguage,
              requestId
            ),
          io
        )
      )
      .toList();
    return futures.stream().map(CompletableFuture::join).toList();
  }
}
