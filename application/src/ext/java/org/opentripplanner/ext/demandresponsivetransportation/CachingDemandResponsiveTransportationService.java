package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlBusinessRejectionException;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.api.request.PassengerFareType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base class for demand responsive transportation services that handles logging and error
 * handling for API calls.
 * <p>
 * Caching is handled externally by {@link PerRequestDemandResponsiveTransportationService}, which
 * wraps instances of this class with a fresh per-request cache to prevent stale results from being
 * shared across GraphQL requests.
 */
public abstract class CachingDemandResponsiveTransportationService
  implements DemandResponsiveTransportationService {

  private static final Logger LOG = LoggerFactory.getLogger(
    CachingDemandResponsiveTransportationService.class
  );

  @Override
  public ShotlArrivalEstimateResponse arrivalTimes(
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
    String acceptLanguage
  ) {
    LOG.debug(
      "[DRT] API CALL | context={} | areaId={} | from=({},{}) | to=({},{}) | pickupTime={} | " +
      "fetching from API...",
      context,
      areaId,
      fromCoordinate.latitude(),
      fromCoordinate.longitude(),
      toCoordinate.latitude(),
      toCoordinate.longitude(),
      desiredPickupTime
    );

    try {
      var response = queryArrivalTimes(
        paxAppId,
        areaId,
        userId,
        rideType,
        fromCoordinate,
        toCoordinate,
        regularPassengers,
        wheelchairPassengers,
        desiredPickupTime,
        context,
        passengerFareType,
        pickupShift,
        acceptLanguage
      );

      LOG.debug(
        "[DRT] API RESPONSE | context={} | areaId={} | userExpectedPickupTime={} | " +
        "userExpectedDropoffTime={} | status={}",
        context,
        areaId,
        response.user_expected_pickup_time(),
        response.user_expected_dropoff_time(),
        response.status()
      );

      return response;
    } catch (ShotlBusinessRejectionException rejection) {
      LOG.warn(
        "[DRT] API BUSINESS REJECTION | context={} | areaId={} | code={} | message={}",
        context,
        areaId,
        rejection.getCode(),
        rejection.getMessage()
      );
      return null;
    } catch (Exception e) {
      LOG.error(
        "[DRT] API ERROR | context={} | areaId={} | error={}",
        context,
        areaId,
        e.getMessage()
      );
      return null;
    }
  }

  /**
   * Query the DRT service for arrival time estimates.
   * <p>
   * Implementations should make the actual API call to the DRT service.
   */
  protected abstract ShotlArrivalEstimateResponse queryArrivalTimes(
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
    String acceptLanguage
  );
}
