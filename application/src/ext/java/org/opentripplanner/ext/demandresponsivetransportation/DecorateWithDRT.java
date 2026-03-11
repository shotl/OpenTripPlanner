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
      var legs = allLegs
        .parallelStream()
        .map(leg -> decorateLegWithRideEstimate(i, leg, allLegs, service))
        .toList();

      i.setLegs(fixTemporalOverlaps(legs));
    }
    return i;
  }

  /**
   * After DRT decoration the leg times may change (e.g. DRT dropoff moves by a few seconds),
   * leaving the immediately following non-transit leg starting before the previous one ends.
   * This method shifts such legs forward to restore a consistent timeline.
   */
  private static List<Leg> fixTemporalOverlaps(List<Leg> legs) {
    var result = new ArrayList<Leg>(legs.size());
    ZonedDateTime previousEnd = null;
    for (Leg leg : legs) {
      if (previousEnd != null && !leg.isTransitLeg() && leg.getStartTime().isBefore(previousEnd)) {
        Duration shift = Duration.between(leg.getStartTime(), previousEnd);
        leg = leg.withTimeShift(shift);
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
        isEgress
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

      return new DRTLeg(sl, drtEstimationResponse);
    } else {
      return leg;
    }
  }

  /**
   * Checks whether the DRT estimate makes this itinerary temporally infeasible.
   * <p>
   * For access legs: the DRT must drop off the passenger before the first transit leg departs.
   * For egress legs: the DRT must pick up the passenger after the last transit leg arrives.
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
      // The DRT must not expect to pick up before the passenger is physically there.
      var passengerAtPickup = carLeg.getStartTime().toInstant();
      var drtPickup = Instant.ofEpochSecond(drtResponse.user_expected_pickup_time());
      if (drtPickup.isBefore(passengerAtPickup)) {
        LOG.warn(
          "Egress DRT temporally infeasible — flagging itinerary for deletion | " +
          "DRT expectedPickup={} is before passenger arrives at pickup point={} | " +
          "egressLeg: ({},{}) → ({},{}) | " +
          "DRT expectedDropoff={}",
          formatInstant(drtPickup),
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
