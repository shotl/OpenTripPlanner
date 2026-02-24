package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
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

      i.setLegs(legs);
    }
    return i;
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
        DrtRequestContext.LEG_DECORATING
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

      if (isEgress) {
        LOG.info(
          "Egress DRT decoration: from ({},{}) to ({},{}) | legStartTime={} | pickupTime={} | expectedPickup={} | expectedDropoff={}",
          leg.getFrom().coordinate.latitude(),
          leg.getFrom().coordinate.longitude(),
          leg.getTo().coordinate.latitude(),
          leg.getTo().coordinate.longitude(),
          leg.getStartTime().toInstant(),
          pickupTime,
          drtEstimationResponse.user_expected_pickup_time(),
          drtEstimationResponse.user_expected_dropoff_time()
        );
      }

      return new DRTLeg(sl, drtEstimationResponse);
    } else {
      return leg;
    }
  }
}
