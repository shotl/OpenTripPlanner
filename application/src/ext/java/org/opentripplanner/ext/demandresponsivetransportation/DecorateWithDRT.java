package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.model.SystemNotice;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.routing.algorithm.filterchain.framework.spi.ItineraryListFilter;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This filter decorates car dropoff/pickup legs with information from DRT services and
 * adds information about the price and arrival time of the vehicle.
 * <p>
 * Before making any API calls, all car legs across all itineraries are scanned and
 * deduplicated by their rounded cache key (coordinates ~10m, pickup time ~5min).
 * Only unique estimates are fetched concurrently, then the responses are distributed
 * back to all legs that share the same key. This eliminates duplicate API calls that
 * previously occurred when multiple itineraries had egress legs to the same stop at
 * similar times.
 */
public class DecorateWithDRT implements ItineraryListFilter {

  private static final Logger LOG = LoggerFactory.getLogger(DecorateWithDRT.class);

  public static final String NO_DRT_AVAILABLE = "no-drt-available";
  private final List<DemandResponsiveTransportationService> drtServices;
  private final RouteRequest request;

  /**
   * Original (non-rounded) parameters for a DRT estimate API call.
   * One instance is kept per unique {@link DrtEstimateRequest} cache key.
   */
  private record FetchParams(WgsCoordinate from, WgsCoordinate to, Instant pickupTime) {}

  public DecorateWithDRT(
    List<DemandResponsiveTransportationService> drtServices,
    RouteRequest request
  ) {
    this.drtServices = drtServices;
    this.request = request;
  }

  @Override
  public List<Itinerary> filter(List<Itinerary> itineraries) {
    return drtServices
      .stream()
      .flatMap(service -> decorateWithDeduplication(itineraries, service).stream())
      .toList();
  }

  /**
   * Three-phase decoration: collect unique requests, fetch concurrently, apply results.
   * <p>
   * Deduplication uses exact coordinates and pickup times (no rounding). Two legs that
   * map to the exact same API call parameters share one fetch. The per-request cache
   * (which uses rounded keys) still sits in the call chain for access shifting, but
   * decoration no longer depends on it.
   */
  private List<Itinerary> decorateWithDeduplication(
    List<Itinerary> itineraries,
    DemandResponsiveTransportationService service
  ) {
    // Phase 1: Collect unique DRT estimate requests across all itineraries
    var uniqueRequests = new LinkedHashMap<FetchParams, FetchParams>();
    var extData = request.demandResponsiveExtData();
    int totalCarLegs = 0;

    for (var itinerary : itineraries) {
      if (itinerary.isFlaggedForDeletion()) continue;
      for (var leg : itinerary.getLegs()) {
        if (leg instanceof DRTLeg) continue;
        if (leg instanceof StreetLeg sl && sl.getMode().isInCar()) {
          totalCarLegs++;
          boolean isEgress = isEgressLeg(leg, itinerary.getLegs());
          var pickupTime = isEgress ? leg.getStartTime().toInstant() : request.dateTime();
          var key = new FetchParams(leg.getFrom().coordinate, leg.getTo().coordinate, pickupTime);
          uniqueRequests.putIfAbsent(key, key);
        }
      }
    }

    if (uniqueRequests.isEmpty()) {
      return itineraries;
    }

    LOG.info(
      "[DRT] decoration | totalCarLegs={} | uniqueEstimates={}",
      totalCarLegs,
      uniqueRequests.size()
    );

    // Phase 2: Fetch unique estimates concurrently on the I/O thread pool
    ExecutorService io = DrtIoExecutor.getInstance();
    var futures = new LinkedHashMap<FetchParams, CompletableFuture<ShotlArrivalEstimateResponse>>();
    for (var params : uniqueRequests.keySet()) {
      futures.put(
        params,
        CompletableFuture.supplyAsync(
          () ->
            service.arrivalTimes(
              extData.paxAppId(),
              extData.areaId(),
              extData.userId(),
              extData.rideType(),
              params.from(),
              params.to(),
              extData.passengers().regular(),
              extData.passengers().wheelchair(),
              params.pickupTime(),
              DrtRequestContext.LEG_DECORATING,
              extData.passengerFareType(),
              true
            ),
          io
        )
      );
    }

    var estimates = new HashMap<FetchParams, ShotlArrivalEstimateResponse>();
    for (var entry : futures.entrySet()) {
      var params = entry.getKey();
      var response = entry.getValue().join();
      estimates.put(params, response);
      if (response != null) {
        long walkToPickup = response.pickup_walking_seconds() != null
          ? response.pickup_walking_seconds()
          : 0L;
        long walkFromDropoff = response.dropoff_walking_seconds() != null
          ? response.dropoff_walking_seconds()
          : 0L;
        LOG.info(
          "[DRT] decoration estimate | from=({},{}) → ({},{}) | pickupTime={} | expectedPickup={} | expectedDropoff={} | walkToPickup={}s | walkFromDropoff={}s",
          params.from().latitude(),
          params.from().longitude(),
          params.to().latitude(),
          params.to().longitude(),
          params.pickupTime(),
          response.user_expected_pickup_time(),
          response.user_expected_dropoff_time(),
          walkToPickup,
          walkFromDropoff
        );
      }
    }

    // Phase 3: Apply fetched estimates to each itinerary (CPU-only, no I/O)
    return itineraries.stream().map(i -> applyEstimates(i, estimates)).toList();
  }

  private Itinerary applyEstimates(
    Itinerary i,
    Map<FetchParams, ShotlArrivalEstimateResponse> estimates
  ) {
    if (i.isFlaggedForDeletion()) {
      return i;
    }

    var allLegs = i.getLegs();
    var decoratedLegs = allLegs
      .stream()
      .map(leg -> decorateLegFromEstimates(i, leg, allLegs, estimates))
      .toList();

    if (!i.isFlaggedForDeletion()) {
      var fixedLegs = fixTemporalOverlaps(decoratedLegs);
      updateEgressGeneralizedCost(i, allLegs, fixedLegs);
      i.setLegs(fixedLegs);
    }

    return i;
  }

  private Leg decorateLegFromEstimates(
    Itinerary i,
    Leg leg,
    List<Leg> allLegs,
    Map<FetchParams, ShotlArrivalEstimateResponse> estimates
  ) {
    if (leg instanceof DRTLeg) {
      return leg;
    }
    if (!(leg instanceof StreetLeg sl) || !sl.getMode().isInCar()) {
      return leg;
    }

    boolean isEgress = isEgressLeg(leg, allLegs);
    var pickupTime = isEgress ? leg.getStartTime().toInstant() : request.dateTime();
    var key = new FetchParams(leg.getFrom().coordinate, leg.getTo().coordinate, pickupTime);

    var drtEstimationResponse = estimates.get(key);

    if (drtEstimationResponse == null) {
      LOG.warn(
        "[DRT] No estimate available for {} leg from ({},{}) to ({},{}) at {} — flagging itinerary for deletion",
        isEgress ? "egress" : "access",
        leg.getFrom().coordinate.latitude(),
        leg.getFrom().coordinate.longitude(),
        leg.getTo().coordinate.latitude(),
        leg.getTo().coordinate.longitude(),
        pickupTime
      );
      flagForDeletion(i);
      return leg;
    }

    if (isTemporallyInfeasible(leg, allLegs, drtEstimationResponse, isEgress)) {
      flagForDeletion(i);
      return leg;
    }

    var legType = isEgress ? "egress" : "access";
    long waitingSeconds;
    int legCost;
    DRTLeg drtLeg;
    if (isEgress) {
      var legStartTime = leg.getStartTime();
      waitingSeconds = DRTLeg.computeWaitingSeconds(drtEstimationResponse, legStartTime);
      legCost = DRTLeg.computeGeneralizedCost(
        drtEstimationResponse,
        request.preferences().walk().reluctance(),
        request.preferences().car().reluctance(),
        waitingSeconds,
        1.0 // waitReluctance, same as transit wait
      );
      drtLeg = new DRTLeg(sl, drtEstimationResponse, legCost, legStartTime);
    } else {
      waitingSeconds = 0;
      legCost = DRTLeg.computeGeneralizedCost(
        drtEstimationResponse,
        request.preferences().walk().reluctance(),
        request.preferences().car().reluctance()
      );
      drtLeg = new DRTLeg(sl, drtEstimationResponse, legCost);
    }

    LOG.debug(
      "[DRT] {} leg decorated | from=({},{}) → ({},{}) | pickupTime={} | waitingSeconds={}s | cost={}",
      legType,
      leg.getFrom().coordinate.latitude(),
      leg.getFrom().coordinate.longitude(),
      leg.getTo().coordinate.latitude(),
      leg.getTo().coordinate.longitude(),
      pickupTime,
      waitingSeconds,
      legCost
    );

    return drtLeg;
  }

  private static void flagForDeletion(Itinerary i) {
    i.flagForDeletion(
      new SystemNotice(
        NO_DRT_AVAILABLE,
        "This itinerary is marked as deleted by the " + NO_DRT_AVAILABLE + " filter."
      )
    );
  }

  /**
   * Adjusts the itinerary's generalized cost to reflect the real DRT egress reported by the DRT
   * provider, replacing the stale A* street-routing cost for each decorated egress leg.
   *
   * @param originalLegs the legs before DRT decoration (used to identify egress legs and old cost)
   * @param fixedLegs    the legs after DRT decoration and temporal overlap fixing
   */
  private void updateEgressGeneralizedCost(
    Itinerary itinerary,
    List<Leg> originalLegs,
    List<Leg> fixedLegs
  ) {
    if (itinerary.getGeneralizedCost() == Itinerary.UNKNOWN) {
      return;
    }
    int costDelta = 0;
    for (int idx = 0; idx < originalLegs.size(); idx++) {
      Leg original = originalLegs.get(idx);
      Leg fixed = fixedLegs.get(idx);
      if (fixed instanceof DRTLeg drtLeg && isEgressLeg(original, originalLegs)) {
        int oldCost = original.getGeneralizedCost();
        int newCost = drtLeg.getGeneralizedCost();
        costDelta += newCost - oldCost;
      }
    }
    if (costDelta != 0) {
      itinerary.setGeneralizedCost(itinerary.getGeneralizedCost() + costDelta);
    }
  }

  /**
   * After DRT decoration the leg times may change, creating overlaps or gaps.
   * <p>
   * For <b>DRT legs</b>: only fix overlaps (shift forward). Gaps before a DRT leg represent
   * real waiting time for the DRT vehicle and must be preserved — they are penalized in the
   * generalized cost instead.
   * <p>
   * For <b>non-DRT non-transit legs</b> (walks): fix both overlaps and gaps so the timeline
   * is seamless. In particular, the walk after a DRT leg should start immediately when the
   * DRT leg ends (no stale gap from pre-decoration times).
   */
  private static List<Leg> fixTemporalOverlaps(List<Leg> legs) {
    var result = new ArrayList<Leg>(legs.size());
    ZonedDateTime previousEnd = null;
    for (Leg leg : legs) {
      if (previousEnd != null && !leg.isTransitLeg()) {
        boolean isDrt = leg instanceof DRTLeg;
        boolean hasOverlap = leg.getStartTime().isBefore(previousEnd);
        boolean hasGap = leg.getStartTime().isAfter(previousEnd);

        // DRT legs: only fix overlaps (preserve gaps = waiting time)
        // Non-DRT legs: fix both overlaps and gaps
        if (hasOverlap || (hasGap && !isDrt)) {
          Duration shift = Duration.between(leg.getStartTime(), previousEnd);
          leg = leg.withTimeShift(shift);
        }
      }
      result.add(leg);
      previousEnd = leg.getEndTime();
    }
    return result;
  }

  private boolean isEgressLeg(Leg leg, List<Leg> allLegs) {
    int legIndex = allLegs.indexOf(leg);
    for (int j = 0; j < legIndex; j++) {
      if (allLegs.get(j).isTransitLeg()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tolerance for the temporal feasibility check. The Shotl API often rounds
   * {@code user_expected_pickup_time} down to the nearest minute, so the returned pickup
   * time can be up to 60 seconds before the {@code desired_pickup_time} we sent.
   * Additionally, per-request cache rounding (5-minute intervals) means a cached response
   * may have been computed for a slightly earlier desired time. A 2-minute tolerance
   * accommodates both effects without being so large that truly infeasible itineraries
   * slip through.
   */
  private static final Duration EGRESS_TEMPORAL_TOLERANCE = Duration.ofMinutes(2);

  /**
   * Checks whether the DRT estimate makes this itinerary temporally infeasible.
   * <p>
   * For access legs: the DRT must drop off the passenger before the first transit leg departs.
   * For egress legs: the DRT must pick up the passenger after the last transit leg arrives,
   * with a tolerance to account for Shotl's time rounding to whole minutes and per-request
   * cache key rounding.
   */
  private boolean isTemporallyInfeasible(
    Leg carLeg,
    List<Leg> allLegs,
    ShotlArrivalEstimateResponse drtResponse,
    boolean isEgress
  ) {
    if (isEgress) {
      // The passenger arrives at the car pickup point at carLeg.getStartTime(),
      // which already includes the walk time from the last transit stop.
      // The DRT must not expect to pick up too far before the passenger is physically there.
      // We allow a tolerance because Shotl rounds pickup times to whole minutes and
      // the per-request cache may return a response computed for a slightly earlier time.
      var passengerAtPickup = carLeg.getStartTime().toInstant();
      var drtPickup = Instant.ofEpochSecond(drtResponse.user_expected_pickup_time());
      if (drtPickup.plus(EGRESS_TEMPORAL_TOLERANCE).isBefore(passengerAtPickup)) {
        LOG.warn(
          "Egress DRT temporally infeasible — flagging itinerary for deletion | " +
          "DRT expectedPickup={} is more than {}s before passenger arrives at pickup point={} | " +
          "egressLeg: ({},{}) → ({},{}) | " +
          "DRT expectedDropoff={}",
          formatInstant(drtPickup),
          EGRESS_TEMPORAL_TOLERANCE.toSeconds(),
          formatInstant(passengerAtPickup),
          carLeg.getFrom().coordinate.latitude(),
          carLeg.getFrom().coordinate.longitude(),
          carLeg.getTo().coordinate.latitude(),
          carLeg.getTo().coordinate.longitude(),
          formatInstant(Instant.ofEpochSecond(drtResponse.user_expected_dropoff_time()))
        );
        return true;
      }
    }
    return false;
  }

  private static String formatInstant(Instant instant) {
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC));
  }
}
