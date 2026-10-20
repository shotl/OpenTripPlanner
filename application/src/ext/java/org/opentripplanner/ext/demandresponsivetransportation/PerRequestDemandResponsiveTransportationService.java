package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    List<PassengerFareType> passengerFareType,
    boolean pickupShift,
    String acceptLanguage,
    String requestId
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
      passengerFareType,
      pickupShift,
      acceptLanguage,
      requestId
    );
    cache.put(cacheKey, Optional.ofNullable(response));
    return response;
  }

  /**
   * Answers the cached legs from the cache and sends only the misses on as one batch; legs that
   * round to the same key cost one estimate.
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
    var keys = new ArrayList<DrtEstimateRequest>(items.size());
    var misses = new LinkedHashMap<DrtEstimateRequest, DrtEstimateItem>();
    for (var item : items) {
      var key = DrtEstimateRequest.create(
        userId,
        areaId,
        rideType,
        item.from(),
        item.to(),
        regularPassengers,
        wheelchairPassengers,
        item.pickupTime()
      );
      keys.add(key);
      if (!cache.containsKey(key)) {
        misses.putIfAbsent(key, item);
      }
    }
    if (!misses.isEmpty()) {
      var answers = delegate.arrivalTimesBatch(
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
        new ArrayList<>(misses.values())
      );
      int k = 0;
      for (var key : misses.keySet()) {
        var answer = k < answers.size() ? answers.get(k) : null;
        cache.put(key, Optional.ofNullable(answer));
        k++;
      }
    }
    LOG.debug(
      "[DRT] BATCH CACHE | context={} | items={} | sent={}",
      context,
      items.size(),
      misses.size()
    );
    var out = new ArrayList<ShotlArrivalEstimateResponse>(items.size());
    for (var key : keys) {
      var cached = cache.get(key);
      out.add(cached != null ? cached.orElse(null) : null);
    }
    return out;
  }
}
