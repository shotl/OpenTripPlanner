package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.List;
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
    boolean pickupShift
  );
}
