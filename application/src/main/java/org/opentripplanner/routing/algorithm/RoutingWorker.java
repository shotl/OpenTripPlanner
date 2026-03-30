package org.opentripplanner.routing.algorithm;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.DrtIoExecutor;
import org.opentripplanner.ext.demandresponsivetransportation.DrtRequestContext;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.framework.application.OTPRequestTimeoutException;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.model.plan.grouppriority.TransitGroupPriorityItineraryDecorator;
import org.opentripplanner.model.plan.paging.cursor.PageCursorInput;
import org.opentripplanner.raptor.api.request.RaptorTuningParameters;
import org.opentripplanner.raptor.api.request.SearchParams;
import org.opentripplanner.routing.algorithm.filterchain.ItineraryListFilterChain;
import org.opentripplanner.routing.algorithm.mapping.PagingServiceFactory;
import org.opentripplanner.routing.algorithm.mapping.RouteRequestToFilterChainMapper;
import org.opentripplanner.routing.algorithm.mapping.RoutingResponseMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.router.AdditionalSearchDays;
import org.opentripplanner.routing.algorithm.raptoradapter.router.FilterTransitWhenDirectModeIsEmpty;
import org.opentripplanner.routing.algorithm.raptoradapter.router.TransitRouter;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.DirectFlexRouter;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.DirectStreetRouter;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.response.RoutingError;
import org.opentripplanner.routing.api.response.RoutingResponse;
import org.opentripplanner.routing.error.RoutingValidationException;
import org.opentripplanner.routing.framework.DebugTimingAggregator;
import org.opentripplanner.service.paging.PagingService;
import org.opentripplanner.standalone.api.OtpServerRequestContext;
import org.opentripplanner.transit.model.network.grouppriority.TransitGroupPriorityService;
import org.opentripplanner.utils.time.ServiceDateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does a complete transit search, including access and egress legs.
 * <p>
 * This class has a request scope, hence the "Worker" name.
 */
public class RoutingWorker {

  private static final Logger LOG = LoggerFactory.getLogger(RoutingWorker.class);

  /** An object that accumulates profiling and debugging info for inclusion in the response. */
  private final DebugTimingAggregator debugTimingAggregator;

  private final RouteRequest request;
  private final OtpServerRequestContext serverContext;
  /**
   * The transit service time-zero normalized for the current search. All transit times are relative
   * to a "time-zero". This enables us to use an integer(small memory footprint). The times are
   * number for seconds past the {@code transitSearchTimeZero}. In the internal model all times are
   * stored relative to the {@link java.time.LocalDate}, but to be able
   * to compare trip times for different service days we normalize all times by calculating an
   * offset. Now all times for the selected trip patterns become relative to the {@code
   * transitSearchTimeZero}.
   */
  private final ZonedDateTime transitSearchTimeZero;
  private final AdditionalSearchDays additionalSearchDays;
  private final TransitGroupPriorityService transitGroupPriorityService;
  private SearchParams raptorSearchParamsUsed = null;
  private PageCursorInput pageCursorInput = null;

  public RoutingWorker(OtpServerRequestContext serverContext, RouteRequest request, ZoneId zoneId) {
    request.applyPageCursor();
    this.request = request;
    this.serverContext = serverContext;
    this.debugTimingAggregator = new DebugTimingAggregator(
      serverContext.meterRegistry(),
      request.preferences().system().tags()
    );
    this.transitSearchTimeZero = ServiceDateUtils.asStartOfService(request.dateTime(), zoneId);
    this.additionalSearchDays = createAdditionalSearchDays(
      serverContext.raptorTuningParameters(),
      zoneId,
      request
    );
    this.transitGroupPriorityService = TransitGroupPriorityService.of(
      request.preferences().transit().relaxTransitGroupPriority(),
      request.journey().transit().priorityGroupsByAgency(),
      request.journey().transit().priorityGroupsGlobal()
    );
  }

  public RoutingResponse route() {
    OTPRequestTimeoutException.checkForTimeout();

    // If no direct mode is set, then we set one.
    // See {@link FilterTransitWhenDirectModeIsEmpty}
    var emptyDirectModeHandler = new FilterTransitWhenDirectModeIsEmpty(
      request.journey().direct().mode()
    );

    request.journey().direct().setMode(emptyDirectModeHandler.resolveDirectMode());

    this.debugTimingAggregator.finishedPrecalculating();

    var itineraries = Collections.synchronizedList(new ArrayList<Itinerary>());
    var routingErrors = Collections.synchronizedSet(new HashSet<RoutingError>());

    if (OTPFeature.ParallelRouting.isOn()) {
      // TODO: This is not using {@link OtpRequestThreadFactory} which means we do not get
      //       log-trace-parameters-propagation and graceful timeout handling here.
      try {
        CompletableFuture.allOf(
          CompletableFuture.runAsync(() -> routeDirectStreet(itineraries, routingErrors)),
          CompletableFuture.runAsync(() -> routeDirectFlex(itineraries, routingErrors)),
          CompletableFuture.runAsync(() -> routeTransit(itineraries, routingErrors))
        ).join();
      } catch (CompletionException e) {
        RoutingValidationException.unwrapAndRethrowCompletionException(e);
      }
    } else {
      // Direct street routing
      routeDirectStreet(itineraries, routingErrors);

      // Direct flex routing
      routeDirectFlex(itineraries, routingErrors);

      // Transit routing
      routeTransit(itineraries, routingErrors);
    }

    // Set C2 value for Street and FLEX if transit-group-priority is used
    new TransitGroupPriorityItineraryDecorator(transitGroupPriorityService).decorate(itineraries);

    debugTimingAggregator.finishedRouting();

    // Filter itineraries
    List<Itinerary> filteredItineraries;
    {
      // Remove walk-only results when using direct flex mode since we expect
      // actual flex service usage, not just walking.
      // DRT is excluded: the walk-only itinerary should compete naturally with DRT
      // and transit results so the best solution (WALK, DRT, or both) is returned.
      boolean removeWalkAllTheWayResultsFromDirectFlex =
        request.journey().direct().mode() == StreetMode.FLEXIBLE;

      ItineraryListFilterChain filterChain = RouteRequestToFilterChainMapper.createFilterChain(
        request,
        serverContext,
        earliestDepartureTimeUsed(),
        searchWindowUsed(),
        emptyDirectModeHandler.removeWalkAllTheWayResults() ||
        removeWalkAllTheWayResultsFromDirectFlex,
        it -> pageCursorInput = it
      );

      filteredItineraries = filterChain.filter(itineraries);
      routingErrors.addAll(filterChain.getRoutingErrors());
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug(
        "Return TripPlan with {} filtered itineraries out of {} total.",
        filteredItineraries.stream().filter(it -> !it.isFlaggedForDeletion()).count(),
        itineraries.size()
      );
    }

    this.debugTimingAggregator.finishedFiltering();

    // Restore original directMode.
    request.journey().direct().setMode(emptyDirectModeHandler.originalDirectMode());

    // Adjust the search-window for the next search if the current search-window
    // is off (too few or too many results found).

    var pagingService = createPagingService(itineraries);

    return RoutingResponseMapper.map(
      request,
      raptorSearchParamsUsed,
      filteredItineraries,
      routingErrors,
      debugTimingAggregator,
      serverContext.transitService(),
      pagingService
    );
  }

  private static AdditionalSearchDays createAdditionalSearchDays(
    RaptorTuningParameters raptorTuningParameters,
    ZoneId zoneId,
    RouteRequest request
  ) {
    var searchDateTime = ZonedDateTime.ofInstant(request.dateTime(), zoneId);
    var maxWindow = raptorTuningParameters.dynamicSearchWindowCoefficients().maxWindow();

    return new AdditionalSearchDays(
      request.arriveBy(),
      searchDateTime,
      request.searchWindow(),
      maxWindow,
      request.preferences().system().maxJourneyDuration()
    );
  }

  /**
   * Calculate the earliest-departure-time used in the transit search.
   * This method returns {@code null} if no transit search is performed.
   */
  @Nullable
  private Instant earliestDepartureTimeUsed() {
    if (raptorSearchParamsUsed == null) {
      return null;
    }
    if (!raptorSearchParamsUsed.isEarliestDepartureTimeSet()) {
      return null;
    }
    return transitSearchTimeZero
      .plusSeconds(raptorSearchParamsUsed.earliestDepartureTime())
      .toInstant();
  }

  /**
   * Calculate the search-window earliest-departure-time used in the transit search.
   * This method returns {@code null} if no transit search is performed.
   */
  @Nullable
  private Duration searchWindowUsed() {
    return raptorSearchParamsUsed == null
      ? null
      : Duration.ofSeconds(raptorSearchParamsUsed.searchWindowInSeconds());
  }

  private List<Itinerary> routeDirectStreet(
    List<Itinerary> itineraries,
    Collection<RoutingError> routingErrors
  ) {
    // TODO: Add support for via search to the direct-street search and remove this.
    //       The direct search is used to prune away silly transit results and it
    //       would be nice to also support via as a feature in the direct-street
    //       search.
    if (request.isViaSearch()) {
      return null;
    }

    debugTimingAggregator.startedDirectStreetRouter();
    try {
      var directResults = DirectStreetRouter.route(serverContext, request);
      // If direct mode is DRT, shift the itineraries with real DRT data (times, cost)
      // BEFORE the filter chain runs, so cost-based filters see the real DRT cost.
      if (request.journey().direct().mode() == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION) {
        directResults = shiftDirectDrtItineraries(directResults);
        // Also run a WALK direct search so the walk-only baseline enters the filter
        // chain and prunes transit itineraries that are worse than just walking.
        var savedMode = request.journey().direct().mode();
        request.journey().direct().setMode(StreetMode.WALK);
        try {
          itineraries.addAll(DirectStreetRouter.route(serverContext, request));
        } finally {
          request.journey().direct().setMode(savedMode);
        }
      }
      itineraries.addAll(directResults);
    } catch (RoutingValidationException e) {
      routingErrors.addAll(e.getRoutingErrors());
    } finally {
      debugTimingAggregator.finishedDirectStreetRouter();
    }
    return null;
  }

  /**
   * For each direct DRT itinerary, call the DRT provider API to replace stale A*-based car
   * times and costs with real DRT pickup/dropoff times and correctly computed generalized cost.
   * This is analogous to what {@link DemandResponsiveTransportationAccessShifter} does for
   * DRT access legs before Raptor.
   * <p>
   * Itineraries for which the DRT service returns no estimate are discarded.
   */
  private List<Itinerary> shiftDirectDrtItineraries(List<Itinerary> itineraries) {
    var drtServices = serverContext.demandResponsiveTransportationServices();
    if (drtServices == null || drtServices.isEmpty()) {
      return itineraries;
    }
    if (request.demandResponsiveExtData() == null) {
      return itineraries;
    }

    var service = drtServices.get(0);
    double walkReluctance = request.preferences().walk().reluctance();
    double carReluctance = request.preferences().car().reluctance();

    ExecutorService io = DrtIoExecutor.getInstance();
    var futures = itineraries
      .stream()
      .map(i ->
        CompletableFuture.supplyAsync(
          () -> shiftDirectDrtItinerary(i, service, walkReluctance, carReluctance),
          io
        )
      )
      .toList();
    return futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
  }

  /**
   * Shift a single direct DRT itinerary by calling the DRT API for each car leg,
   * replacing it with a {@link DRTLeg} that has real times and correct generalized cost.
   *
   * @return the updated itinerary, or {@code null} if DRT is not available for this trip.
   */
  @Nullable
  private Itinerary shiftDirectDrtItinerary(
    Itinerary itinerary,
    DemandResponsiveTransportationService service,
    double walkReluctance,
    double carReluctance
  ) {
    var allLegs = itinerary.getLegs();
    var updatedLegs = new ArrayList<Leg>(allLegs.size());
    int costDelta = 0;

    for (var leg : allLegs) {
      if (leg instanceof StreetLeg sl && sl.getMode().isInCar()) {
        var drtResponse = service.arrivalTimes(
          request.demandResponsiveExtData().paxAppId(),
          request.demandResponsiveExtData().areaId(),
          request.demandResponsiveExtData().userId(),
          request.demandResponsiveExtData().rideType(),
          leg.getFrom().coordinate,
          leg.getTo().coordinate,
          request.demandResponsiveExtData().passengers().regular(),
          request.demandResponsiveExtData().passengers().wheelchair(),
          request.dateTime(),
          DrtRequestContext.DIRECT_SHIFTING,
          request.demandResponsiveExtData().passengerFareType(),
          true,
          request.demandResponsiveExtData().acceptLanguage()
        );

        if (drtResponse == null) {
          LOG.warn(
            "No DRT estimate for direct leg ({},{}) → ({},{}), discarding itinerary",
            leg.getFrom().coordinate.latitude(),
            leg.getFrom().coordinate.longitude(),
            leg.getTo().coordinate.latitude(),
            leg.getTo().coordinate.longitude()
          );
          return null;
        }

        // For direct DRT, the user departs at request.dateTime(). Compute the waiting time
        // between arriving at the pickup point and the vehicle picking up.
        long waitingSeconds = DRTLeg.computeWaitingSeconds(drtResponse, request.dateTime());
        int legCost = DRTLeg.computeGeneralizedCost(
          drtResponse,
          walkReluctance,
          carReluctance,
          waitingSeconds,
          1.0 // waitReluctance, same as transit wait
        );
        var legStartTime = request.dateTime().atZone(sl.getStartTime().getZone());
        costDelta += legCost - sl.getGeneralizedCost();
        updatedLegs.add(new DRTLeg(sl, drtResponse, legCost, legStartTime));

        long walkToPickup = drtResponse.pickup_walking_seconds() != null
          ? drtResponse.pickup_walking_seconds()
          : 0L;
        long walkFromDropoff = drtResponse.dropoff_walking_seconds() != null
          ? drtResponse.dropoff_walking_seconds()
          : 0L;
        LOG.info(
          "[DRT] direct decoration | from=({},{}) → ({},{}) | pickupTime={} | expectedPickup={} | expectedDropoff={} | walkToPickup={}s | walkFromDropoff={}s | waitingSeconds={}s | cost={}",
          sl.getFrom().coordinate.latitude(),
          sl.getFrom().coordinate.longitude(),
          sl.getTo().coordinate.latitude(),
          sl.getTo().coordinate.longitude(),
          request.dateTime(),
          drtResponse.user_expected_pickup_time(),
          drtResponse.user_expected_dropoff_time(),
          walkToPickup,
          walkFromDropoff,
          waitingSeconds,
          legCost
        );
      } else {
        updatedLegs.add(leg);
      }
    }

    // Fix temporal overlaps: if the DRT leg times push into adjacent walk legs, shift them.
    updatedLegs = fixTemporalOverlaps(updatedLegs);

    // setLegs recalculates duration, walkDuration, etc. from the new leg times.
    itinerary.setLegs(updatedLegs);

    // Update the itinerary-level generalized cost with the delta between real DRT and A* car cost.
    if (itinerary.getGeneralizedCost() != Itinerary.UNKNOWN && costDelta != 0) {
      itinerary.setGeneralizedCost(itinerary.getGeneralizedCost() + costDelta);
    }

    return itinerary;
  }

  /**
   * After DRT leg times are replaced, fix the timeline so non-DRT legs (walks) connect
   * seamlessly with the DRT leg. DRT legs only have overlaps fixed; gaps before DRT legs
   * represent waiting time and are preserved.
   */
  private static ArrayList<Leg> fixTemporalOverlaps(ArrayList<Leg> legs) {
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

  private Void routeDirectFlex(
    List<Itinerary> itineraries,
    Collection<RoutingError> routingErrors
  ) {
    if (!OTPFeature.FlexRouting.isOn()) {
      return null;
    }

    debugTimingAggregator.startedDirectFlexRouter();
    try {
      itineraries.addAll(DirectFlexRouter.route(serverContext, request, additionalSearchDays));
    } catch (RoutingValidationException e) {
      routingErrors.addAll(e.getRoutingErrors());
    } finally {
      debugTimingAggregator.finishedDirectFlexRouter();
    }
    return null;
  }

  private Void routeTransit(List<Itinerary> itineraries, Collection<RoutingError> routingErrors) {
    debugTimingAggregator.startedTransitRouting();
    try {
      var transitResults = TransitRouter.route(
        request,
        serverContext,
        transitGroupPriorityService,
        transitSearchTimeZero,
        additionalSearchDays,
        debugTimingAggregator
      );
      raptorSearchParamsUsed = transitResults.getSearchParams();
      itineraries.addAll(transitResults.getItineraries());
    } catch (RoutingValidationException e) {
      routingErrors.addAll(e.getRoutingErrors());
    } finally {
      debugTimingAggregator.finishedTransitRouter();
    }
    return null;
  }

  private Instant searchStartTime() {
    return transitSearchTimeZero.toInstant();
  }

  private PagingService createPagingService(List<Itinerary> itineraries) {
    return PagingServiceFactory.createPagingService(
      searchStartTime(),
      serverContext.transitTuningParameters(),
      serverContext.raptorTuningParameters(),
      request,
      raptorSearchParamsUsed,
      pageCursorInput,
      itineraries
    );
  }
}
