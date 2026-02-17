package org.opentripplanner.ext.demandresponsivetransportation;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlBusinessRejectionException;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A base class for caching API responses from demand responsive transportation services.
 * <p>
 * Similar to {@link org.opentripplanner.ext.ridehailing.CachingRideHailingService}, this class
 * wraps DRT API calls with a Guava cache to reduce redundant API requests during routing.
 */
public abstract class CachingDemandResponsiveTransportationService
  implements DemandResponsiveTransportationService {

  private static final Logger LOG = LoggerFactory.getLogger(
    CachingDemandResponsiveTransportationService.class
  );

  // Cache duration for DRT estimates. DRT services typically have more dynamic availability
  // than ride-hailing, so we use a shorter cache duration.
  private static final Duration CACHE_DURATION = Duration.ofMinutes(2);

  private final Cache<DrtEstimateRequest, ShotlArrivalEstimateResponse> estimateCache =
    CacheBuilder.newBuilder().expireAfterWrite(CACHE_DURATION).build();

  /**
   * Get the arrival time estimate for a DRT request.
   * <p>
   * Results are cached based on a composite key of area, ride type, rounded coordinates,
   * passengers, and rounded pickup time to reduce API calls.
   */
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
    DrtRequestContext context
  ) throws ExecutionException, IOException {
    var cacheKey = DrtEstimateRequest.create(
      areaId,
      rideType,
      fromCoordinate,
      toCoordinate,
      regularPassengers,
      wheelchairPassengers,
      desiredPickupTime
    );

    // Check if we have a cached response
    var cachedResponse = estimateCache.getIfPresent(cacheKey);
    if (cachedResponse != null) {
      LOG.info(
        "[DRT] CACHE HIT | context={} | areaId={} | from=({},{}) | to=({},{}) | pickupTime={} | " +
        "cachedPickupTime={} | cachedDropoffTime={}",
        context,
        areaId,
        fromCoordinate.latitude(),
        fromCoordinate.longitude(),
        toCoordinate.latitude(),
        toCoordinate.longitude(),
        desiredPickupTime,
        cachedResponse.user_expected_pickup_time(),
        cachedResponse.user_expected_dropoff_time()
      );
      return cachedResponse;
    }

    LOG.info(
      "[DRT] CACHE MISS | context={} | areaId={} | from=({},{}) | to=({},{}) | pickupTime={} | " +
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
      var response = estimateCache.get(cacheKey, () ->
        queryArrivalTimes(
          paxAppId,
          areaId,
          userId,
          rideType,
          fromCoordinate,
          toCoordinate,
          regularPassengers,
          wheelchairPassengers,
          desiredPickupTime,
          context
        )
      );

      LOG.info(
        "[DRT] API RESPONSE | context={} | areaId={} | userExpectedPickupTime={} | " +
        "userExpectedDropoffTime={} | status={}",
        context,
        areaId,
        response.user_expected_pickup_time(),
        response.user_expected_dropoff_time(),
        response.status()
      );

      return response;
    } catch (ExecutionException e) {
      // Business rejections (e.g. OUT_OF_SERVICE_HOURS, NO_ONLINE_VEHICLES) are expected
      // scenarios — log at warn and return null so callers can gracefully discard this result
      // without failing the entire GraphQL request.
      if (e.getCause() instanceof ShotlBusinessRejectionException rejection) {
        LOG.warn(
          "[DRT] API BUSINESS REJECTION | context={} | areaId={} | code={} | message={}",
          context,
          areaId,
          rejection.getCode(),
          rejection.getMessage()
        );
        return null;
      }
      LOG.error(
        "[DRT] API ERROR | context={} | areaId={} | error={}",
        context,
        areaId,
        e.getMessage()
      );
      // Unwrap IOException if it was the underlying cause
      if (e.getCause() instanceof IOException ioException) {
        throw ioException;
      }
      throw e;
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
    DrtRequestContext context
  ) throws IOException;
}
