package org.opentripplanner.ext.demandresponsivetransportation;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
      var legs = i
        .getLegs()
        .parallelStream()
        .map(leg -> decorateLegWithRideEstimate(i, leg, service))
        .toList();

      i.setLegs(legs);
    }
    return i;
  }

  private Leg decorateLegWithRideEstimate(
    Itinerary i,
    Leg leg,
    DemandResponsiveTransportationService service
  ) {
    try {
      if (leg instanceof StreetLeg sl && sl.getMode().isInCar()) {
        LOG.info("decorating leg with DRT estimate");

        var drtEstimationResponse = service.arrivalTimes(
          request.demandResponsiveExtData().paxAppId(),
          request.demandResponsiveExtData().areaId(),
          request.demandResponsiveExtData().userId(),
          request.demandResponsiveExtData().rideType(),
          leg.getFrom().coordinate,
          leg.getTo().coordinate,
          request.demandResponsiveExtData().passengers().regular(),
          request.demandResponsiveExtData().passengers().wheelchair(),
          leg.getStartTime().toInstant(),
          DrtRequestContext.LEG_DECORATING
        );
        if (drtEstimationResponse == null) {
          LOG.warn("No DRT estimate available for leg: {}", leg);
          flagForDeletion(i);
          return leg;
        }

        return new DRTLeg(sl, drtEstimationResponse);
      } else {
        return leg;
      }
    } catch (ExecutionException e) {
      LOG.error("Could not get DRT estimate for Shotl", e);
      flagForDeletion(i);
      return leg;
    } catch (IOException e) {
      LOG.error("Could not get DRT estimate for Shotl", e);
      flagForDeletion(i);
      return leg;
    }
  }
}
