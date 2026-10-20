package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
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

  /**
   * Batch estimates are on unless the environment says OTP_DRT_BATCH_ESTIMATIONS=false: the
   * rollback that needs no rides deploy.
   */
  private static final boolean BATCH_ENABLED =
    !"false".equalsIgnoreCase(System.getenv("OTP_DRT_BATCH_ESTIMATIONS"));

  /** After a provider answered that it has no batch route, try it again this much later. */
  private static final Duration BATCH_RECHECK = Duration.ofMinutes(10);

  private final AtomicLong batchUnsupportedUntil = new AtomicLong(0);

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
    String acceptLanguage,
    String requestId
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
        acceptLanguage,
        requestId
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
    String acceptLanguage,
    String requestId
  );

  /**
   * One call for the whole batch when the provider serves batches. A provider without the
   * route (404/405) is asked leg by leg instead, and asked for batches again ten minutes later.
   * Any other failure of the batch leaves every leg of the phase without an estimate: retrying
   * leg by leg would double the load on a provider that is already failing.
   */
  @Override
  public List<ShotlArrivalEstimateResponse> arrivalTimesBatch(
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
    if (items.isEmpty()) {
      return List.of();
    }
    if (!BATCH_ENABLED || System.currentTimeMillis() < batchUnsupportedUntil.get()) {
      return DemandResponsiveTransportationService.super.arrivalTimesBatch(
        paxAppId,
        areaId,
        userId,
        rideType,
        regularPassengers,
        wheelchairPassengers,
        context,
        passengerFareType,
        pickupShift,
        acceptLanguage,
        requestId,
        items
      );
    }
    try {
      var answers = queryArrivalTimesBatch(
        paxAppId,
        areaId,
        userId,
        rideType,
        regularPassengers,
        wheelchairPassengers,
        context,
        passengerFareType,
        pickupShift,
        acceptLanguage,
        requestId,
        items
      );
      LOG.info(
        "[DRT] BATCH | context={} | areaId={} | items={} | estimated={}",
        context,
        areaId,
        items.size(),
        answers.stream().filter(a -> a != null).count()
      );
      return answers;
    } catch (BatchEstimatesUnsupportedException unsupported) {
      batchUnsupportedUntil.set(System.currentTimeMillis() + BATCH_RECHECK.toMillis());
      LOG.warn(
        "[DRT] BATCH UNSUPPORTED | context={} | areaId={} | {} | asking leg by leg for {}",
        context,
        areaId,
        unsupported.getMessage(),
        BATCH_RECHECK
      );
      return DemandResponsiveTransportationService.super.arrivalTimesBatch(
        paxAppId,
        areaId,
        userId,
        rideType,
        regularPassengers,
        wheelchairPassengers,
        context,
        passengerFareType,
        pickupShift,
        acceptLanguage,
        requestId,
        items
      );
    } catch (Exception e) {
      LOG.error(
        "[DRT] BATCH ERROR | context={} | areaId={} | items={} | error={} | the legs of this phase go without an estimate",
        context,
        areaId,
        items.size(),
        e.getMessage()
      );
      return new ArrayList<>(Collections.nCopies(items.size(), null));
    }
  }

  /**
   * Query the DRT service for the estimates of a batch, one per item in order (null where a leg
   * has none). Providers without batches keep this default.
   */
  protected List<ShotlArrivalEstimateResponse> queryArrivalTimesBatch(
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
    throw new BatchEstimatesUnsupportedException("this provider has no batch estimates");
  }
}
