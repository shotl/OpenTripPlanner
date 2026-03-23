package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
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
 */
public class DecorateWithDRT implements ItineraryListFilter {

  private static final Logger LOG = LoggerFactory.getLogger(DecorateWithDRT.class);

  public static final String NO_DRT_AVAILABLE = "no-drt-available";
  private final List<DemandResponsiveTransportationService> drtServices;
  private final RouteRequest request;

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
      .parallelStream()
      .flatMap(service -> itineraries.parallelStream().map(i -> addDRTInformation(i, service)))
      .toList();
  }

  private static void flagForDeletion(Itinerary i) {
    i.flagForDeletion(
      new SystemNotice(
        NO_DRT_AVAILABLE,
        "This itinerary is marked as deleted by the " + NO_DRT_AVAILABLE + " filter."
      )
    );
  }

  private Itinerary addDRTInformation(Itinerary i, DemandResponsiveTransportationService service) {
    if (!i.isFlaggedForDeletion()) {
      var allLegs = i.getLegs();
      var decoratedLegs = allLegs
        .parallelStream()
        .map(leg -> decorateLegWithRideEstimate(i, leg, allLegs, service))
        .toList();

      if (!i.isFlaggedForDeletion()) {
        // Fix temporal overlaps first so we can compute the real waiting time
        // from the consistent timeline.
        var fixedLegs = fixTemporalOverlaps(decoratedLegs);
        updateEgressGeneralizedCost(i, allLegs, fixedLegs);
        i.setLegs(fixedLegs);
      }
    }
    return i;
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

  private Leg decorateLegWithRideEstimate(
    Itinerary i,
    Leg leg,
    List<Leg> allLegs,
    DemandResponsiveTransportationService service
  ) {
    // Skip legs already decorated (e.g., access DRT from mapAccessLeg or direct DRT shift)
    if (leg instanceof DRTLeg) {
      return leg;
    }
    if (leg instanceof StreetLeg sl && sl.getMode().isInCar()) {
      boolean isEgress = isEgressLeg(leg, allLegs);
      // For egress legs, use the leg's start time (actual transit arrival time)
      // instead of the request departure time, since the passenger arrives later.
      var pickupTime = isEgress ? leg.getStartTime().toInstant() : request.dateTime();

      LOG.info(
        "decorating {} leg with DRT estimate, pickupTime={}",
        isEgress ? "egress" : "access",
        pickupTime
      );

      var drtEstimationResponse = service.arrivalTimes(
        request.demandResponsiveExtData().paxAppId(),
        request.demandResponsiveExtData().areaId(),
        request.demandResponsiveExtData().userId(),
        request.demandResponsiveExtData().rideType(),
        leg.getFrom().coordinate,
        leg.getTo().coordinate,
        request.demandResponsiveExtData().passengers().regular(),
        request.demandResponsiveExtData().passengers().wheelchair(),
        pickupTime,
        DrtRequestContext.LEG_DECORATING,
        request.demandResponsiveExtData().passengerFareType(),
        true
      );
      if (drtEstimationResponse == null) {
        LOG.warn(
          "No DRT estimate available for {} leg from ({},{}) to ({},{}) at {} — flagging itinerary for deletion",
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

      var legType = isEgress ? "egress" : "access";
      LOG.info(
        "{} DRT decoration: from ({},{}) to ({},{}) | legStartTime={} | legEndTime={} | pickupTime={} | expectedPickup={} | expectedDropoff={}",
        legType,
        leg.getFrom().coordinate.latitude(),
        leg.getFrom().coordinate.longitude(),
        leg.getTo().coordinate.latitude(),
        leg.getTo().coordinate.longitude(),
        leg.getStartTime().toInstant(),
        leg.getEndTime().toInstant(),
        pickupTime,
        drtEstimationResponse.user_expected_pickup_time(),
        drtEstimationResponse.user_expected_dropoff_time()
      );

      if (isTemporallyInfeasible(leg, allLegs, drtEstimationResponse, isEgress)) {
        flagForDeletion(i);
        return leg;
      }

      if (isEgress) {
        // For egress: the leg starts when the passenger arrives (from previous leg).
        // Waiting = time between arriving at pickup point and vehicle arriving.
        var legStartTime = leg.getStartTime();
        long waitingSeconds = DRTLeg.computeWaitingSeconds(drtEstimationResponse, legStartTime);
        int legCost = DRTLeg.computeGeneralizedCost(
          drtEstimationResponse,
          request.preferences().walk().reluctance(),
          request.preferences().car().reluctance(),
          waitingSeconds,
          1.0 // waitReluctance, same as transit wait
        );
        return new DRTLeg(sl, drtEstimationResponse, legCost, legStartTime);
      } else {
        // For access: back-compute start time (zero waiting — handled by access shifting)
        int legCost = DRTLeg.computeGeneralizedCost(
          drtEstimationResponse,
          request.preferences().walk().reluctance(),
          request.preferences().car().reluctance()
        );
        return new DRTLeg(sl, drtEstimationResponse, legCost);
      }
    } else {
      return leg;
    }
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
