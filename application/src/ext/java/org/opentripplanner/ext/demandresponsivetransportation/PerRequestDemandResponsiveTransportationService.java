package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.api.request.PassengerFareType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A per-request cache decorator for {@link DemandResponsiveTransportationService}.
 * <p>
 * Each instance holds its own {@link ConcurrentHashMap} that lives only for the duration of a
 * single routing request. This prevents cache sharing across GraphQL requests, which avoids stale
 * estimation IDs being returned when estimates are stored in an external database.
 * <p>
 * Instances are created inside
 * {@link org.opentripplanner.standalone.server.DefaultServerRequestContext}, which is
 * {@code @HttpRequestScoped}, so the cache is garbage-collected after each request.
 */
public class PerRequestDemandResponsiveTransportationService
  implements DemandResponsiveTransportationService {

  private static final Logger LOG = LoggerFactory.getLogger(
    PerRequestDemandResponsiveTransportationService.class
  );

  private final DemandResponsiveTransportationService delegate;

  // Optional.empty() is used as a sentinel for null (failed) responses, since ConcurrentHashMap
  // does not allow null values.
  private final ConcurrentHashMap<
    DrtEstimateRequest,
    Optional<ShotlArrivalEstimateResponse>
  > cache = new ConcurrentHashMap<>();

  public PerRequestDemandResponsiveTransportationService(
    DemandResponsiveTransportationService delegate
  ) {
    this.delegate = delegate;
  }

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
    List<PassengerFareType> passengerFareType
  ) {
    var cacheKey = DrtEstimateRequest.create(
      userId,
      areaId,
      rideType,
      fromCoordinate,
      toCoordinate,
      regularPassengers,
      wheelchairPassengers,
      desiredPickupTime
    );

    var cached = cache.get(cacheKey);
    if (cached != null) {
      LOG.debug(
        "[DRT] CACHE HIT | context={} | areaId={} | from=({},{}) | to=({},{}) | pickupTime={}",
        context,
        areaId,
        fromCoordinate.latitude(),
        fromCoordinate.longitude(),
        toCoordinate.latitude(),
        toCoordinate.longitude(),
        desiredPickupTime
      );
      return cached.orElse(null);
    }

    var response = delegate.arrivalTimes(
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
      passengerFareType
    );
    cache.put(cacheKey, Optional.ofNullable(response));
    return response;
  }
}
