package org.opentripplanner.routing.algorithm.raptoradapter.router;

import static org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType.ACCESS;
import static org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType.EGRESS;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationAccessAdapter;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationAccessShifter;
import org.opentripplanner.ext.ridehailing.RideHailingAccessShifter;
import org.opentripplanner.framework.application.OTPFeature;
import org.opentripplanner.framework.model.TimeAndCost;
import org.opentripplanner.model.PathTransfer;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.raptor.RaptorService;
import org.opentripplanner.raptor.api.path.RaptorPath;
import org.opentripplanner.raptor.api.response.RaptorResponse;
import org.opentripplanner.raptor.spi.ExtraMcRouterSearch;
import org.opentripplanner.routing.algorithm.mapping.RaptorPathToItineraryMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressPenaltyDecorator;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressRouter;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgressType;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.AccessEgresses;
import org.opentripplanner.routing.algorithm.raptoradapter.router.street.FlexAccessEgressRouter;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.DefaultAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RaptorTransitData;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.RoutingAccessEgress;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TripSchedule;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.AccessEgressMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.RaptorRequestMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RaptorRoutingRequestTransitData;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RouteRequestTransitDataProviderFilter;
import org.opentripplanner.routing.algorithm.transferoptimization.configure.TransferOptimizationServiceConfigurator;
import org.opentripplanner.routing.api.request.DemandResponsiveExtData;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.api.request.preference.AccessEgressPreferences;
import org.opentripplanner.routing.api.request.request.StreetRequest;
import org.opentripplanner.routing.api.response.InputField;
import org.opentripplanner.routing.api.response.RoutingError;
import org.opentripplanner.routing.api.response.RoutingErrorCode;
import org.opentripplanner.routing.error.RoutingValidationException;
import org.opentripplanner.routing.framework.DebugTimingAggregator;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.routing.via.ViaCoordinateTransferFactory;
import org.opentripplanner.standalone.api.OtpServerRequestContext;
import org.opentripplanner.street.search.TemporaryVerticesContainer;
import org.opentripplanner.transit.model.framework.EntityNotFoundException;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.grouppriority.TransitGroupPriorityService;
import org.opentripplanner.transit.model.site.StopLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransitRouter {

  private static final Logger LOG = LoggerFactory.getLogger(TransitRouter.class);

  public static final int NOT_SET = -1;

  private final RouteRequest request;
  private final OtpServerRequestContext serverContext;
  private final TransitGroupPriorityService transitGroupPriorityService;
  private final DebugTimingAggregator debugTimingAggregator;
  private final ZonedDateTime transitSearchTimeZero;
  private final AdditionalSearchDays additionalSearchDays;
  private final TemporaryVerticesContainer temporaryVerticesContainer;
  private final ViaCoordinateTransferFactory viaTransferResolver;

  private TransitRouter(
    RouteRequest request,
    OtpServerRequestContext serverContext,
    TransitGroupPriorityService transitGroupPriorityService,
    ZonedDateTime transitSearchTimeZero,
    AdditionalSearchDays additionalSearchDays,
    DebugTimingAggregator debugTimingAggregator
  ) {
    this.request = request;
    this.serverContext = serverContext;
    this.transitGroupPriorityService = transitGroupPriorityService;
    this.transitSearchTimeZero = transitSearchTimeZero;
    this.additionalSearchDays = additionalSearchDays;
    this.debugTimingAggregator = debugTimingAggregator;
    this.temporaryVerticesContainer = createTemporaryVerticesContainer(request, serverContext);
    this.viaTransferResolver = serverContext.viaTransferResolver();
  }

  public static TransitRouterResult route(
    RouteRequest request,
    OtpServerRequestContext serverContext,
    TransitGroupPriorityService priorityGroupConfigurator,
    ZonedDateTime transitSearchTimeZero,
    AdditionalSearchDays additionalSearchDays,
    DebugTimingAggregator debugTimingAggregator
  ) {
    TransitRouter transitRouter = new TransitRouter(
      request,
      serverContext,
      priorityGroupConfigurator,
      transitSearchTimeZero,
      additionalSearchDays,
      debugTimingAggregator
    );

    return transitRouter.routeAndCleanupAfter();
  }

  private TransitRouterResult routeAndCleanupAfter() {
    // try(auto-close):
    //   Make sure we clean up graph by removing temp-edges from the graph before we exit.
    try (temporaryVerticesContainer) {
      return route();
    }
  }

  private TransitRouterResult route() {
    if (!request.journey().transit().enabled()) {
      return new TransitRouterResult(List.of(), null);
    }

    if (!serverContext.transitService().transitFeedCovers(request.dateTime())) {
      throw new RoutingValidationException(
        List.of(new RoutingError(RoutingErrorCode.OUTSIDE_SERVICE_PERIOD, InputField.DATE_TIME))
      );
    }

    var raptorTransitData = request.preferences().transit().ignoreRealtimeUpdates()
      ? serverContext.transitService().getRaptorTransitData()
      : serverContext.transitService().getRealtimeRaptorTransitData();

    var requestTransitDataProvider = createRequestTransitDataProvider(raptorTransitData);

    debugTimingAggregator.finishedPatternFiltering();

    var accessEgresses = fetchAccessEgresses();

    debugTimingAggregator.finishedAccessEgress(
      accessEgresses.getAccesses().size(),
      accessEgresses.getEgresses().size()
    );

    // Prepare transit search
    var raptorRequest = RaptorRequestMapper.<TripSchedule>mapRequest(
      request,
      transitSearchTimeZero,
      serverContext.raptorConfig().isMultiThreaded(),
      accessEgresses.getAccesses(),
      accessEgresses.getEgresses(),
      serverContext.meterRegistry(),
      viaTransferResolver,
      this::listStopIndexes
    );

    // Route transit
    var raptorService = new RaptorService<>(
      serverContext.raptorConfig(),
      createExtraMcRouterSearch(accessEgresses, raptorTransitData)
    );
    var transitResponse = raptorService.route(raptorRequest, requestTransitDataProvider);

    checkIfTransitConnectionExists(transitResponse);

    debugTimingAggregator.finishedRaptorSearch();

    Collection<RaptorPath<TripSchedule>> paths = transitResponse.paths();

    // TODO VIA - Temporarily turn OptimizeTransfers OFF for VIA search until the service support via
    //            Remove '&& !request.isViaSearch()'
    if (
      OTPFeature.OptimizeTransfers.isOn() &&
      !transitResponse.containsUnknownPaths() &&
      // TODO VIA - This is temporary, we want pass via info in paths so transfer optimizer can
      //            skip legs containing via points.
      request.allowTransferOptimization()
    ) {
      var service = TransferOptimizationServiceConfigurator.createOptimizeTransferService(
        raptorTransitData::getStopByIndex,
        requestTransitDataProvider.stopNameResolver(),
        serverContext.transitService().getTransferService(),
        requestTransitDataProvider,
        raptorTransitData.getStopBoardAlightTransferCosts(),
        request.preferences().transfer().optimization(),
        raptorRequest.searchParams().viaLocations()
      );
      paths = service.optimize(transitResponse.paths());
    }

    // Create itineraries

    RaptorPathToItineraryMapper<TripSchedule> itineraryMapper = new RaptorPathToItineraryMapper<>(
      serverContext.graph(),
      serverContext.transitService(),
      raptorTransitData,
      transitSearchTimeZero,
      request
    );

    List<Itinerary> itineraries = paths.stream().map(itineraryMapper::createItinerary).toList();

    debugTimingAggregator.finishedItineraryCreation();

    return new TransitRouterResult(itineraries, transitResponse.requestUsed().searchParams());
  }

  private AccessEgresses fetchAccessEgresses() {
    final var accessList = new ArrayList<RoutingAccessEgress>();
    final var egressList = new ArrayList<RoutingAccessEgress>();

    if (OTPFeature.ParallelRouting.isOn()) {
      try {
        // TODO: This is not using {@link OtpRequestThreadFactory} which mean we do not get
        //       log-trace-parameters-propagation and graceful timeout handling here.
        CompletableFuture.allOf(
          CompletableFuture.runAsync(() -> accessList.addAll(fetchAccess())),
          CompletableFuture.runAsync(() -> egressList.addAll(fetchEgress()))
        ).join();
      } catch (CompletionException e) {
        RoutingValidationException.unwrapAndRethrowCompletionException(e);
      }
    } else {
      accessList.addAll(fetchAccess());
      egressList.addAll(fetchEgress());
    }

    verifyAccessEgress(accessList, egressList);

    // Decorate access/egress with a penalty to make it less favourable than transit
    var penaltyDecorator = new AccessEgressPenaltyDecorator(
      request.journey().access().mode(),
      request.journey().egress().mode(),
      request.preferences().street().accessEgress().penalty()
    );

    var accessListWithPenalty = penaltyDecorator.decorateAccess(accessList);
    var egressListWithPenalty = penaltyDecorator.decorateEgress(egressList);

    return new AccessEgresses(accessListWithPenalty, egressListWithPenalty);
  }

  private Collection<? extends RoutingAccessEgress> fetchAccess() {
    debugTimingAggregator.startedAccessCalculating();
    var list = fetchAccessEgresses(ACCESS);
    debugTimingAggregator.finishedAccessCalculating();
    return list;
  }

  private Collection<? extends RoutingAccessEgress> fetchEgress() {
    debugTimingAggregator.startedEgressCalculating();
    var list = fetchAccessEgresses(EGRESS);
    debugTimingAggregator.finishedEgressCalculating();
    return list;
  }

  private Collection<? extends RoutingAccessEgress> fetchAccessEgresses(AccessEgressType type) {
    var streetRequest = type.isAccess() ? request.journey().access() : request.journey().egress();
    StreetMode mode = streetRequest.mode();

    // LOG.info("[DRT-DEBUG] fetchAccessEgresses: type={}, mode={}", type, mode);

    // Prepare access/egress lists
    RouteRequest accessRequest = request.clone();

    if (type.isAccess()) {
      accessRequest.withPreferences(p -> {
        p.withBike(b -> b.withRental(r -> r.withAllowArrivingInRentedVehicleAtDestination(false)));
        p.withCar(c -> c.withRental(r -> r.withAllowArrivingInRentedVehicleAtDestination(false)));
        p.withScooter(s -> s.withRental(r -> r.withAllowArrivingInRentedVehicleAtDestination(false))
        );
      });
    }

    // When egress mode is DRT, replace the car reluctance used during the A* street search
    // with the DRT egress reluctance. This compensates for the fact that real DRT travel
    // times (applied during decoration) are typically longer than plain CAR routing estimates,
    // preventing DRT egress itineraries from unfairly filtering out transit-only alternatives.
    if (
      type.isEgress() &&
      mode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION &&
      accessRequest.demandResponsiveExtData() != null
    ) {
      double egressReluctance = accessRequest.demandResponsiveExtData().egressReluctance();
      accessRequest.withPreferences(p -> p.withCar(c -> c.withReluctance(egressReluctance)));
    }

    AccessEgressPreferences accessEgressPreferences = accessRequest
      .preferences()
      .street()
      .accessEgress();

    Duration durationLimit = accessEgressPreferences.maxDuration().valueOf(mode);
    int stopCountLimit = accessEgressPreferences.maxStopCountLimit().limitForMode(mode);

    var nearbyStops = AccessEgressRouter.findAccessEgresses(
      accessRequest,
      temporaryVerticesContainer,
      streetRequest,
      serverContext.dataOverlayContext(accessRequest),
      type,
      durationLimit,
      stopCountLimit
    );

    // LOG.info(
    //   "[DRT-DEBUG] nearbyStops found: count={}, durationLimit={}, stopCountLimit={}",
    //   nearbyStops.size(),
    //   durationLimit,
    //   stopCountLimit
    // );
    // for (var stop : nearbyStops) {
    //   LOG.info(
    //     "[DRT-DEBUG] nearbyStop: stop={}, containsCar={}, isFinal={}, carPickupState={}, currentMode={}",
    //     stop.stop,
    //     stop.state.containsModeCar(),
    //     stop.state.isFinal(),
    //     stop.state.getCarPickupState(),
    //     stop.state.currentMode()
    //   );
    // }

    // Filter DRT-mode access/egress to only DRT-eligible stops (loaded from drt_stops.txt).
    // Walk-accessible stops are added separately below and are NOT filtered.
    if (mode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION) {
      nearbyStops = filterDrtEligibleStops(nearbyStops, type);
    }

    var accessEgresses = AccessEgressMapper.mapNearbyStops(nearbyStops, type);

    // LOG.info("[DRT-DEBUG] accessEgresses after mapping: count={}", accessEgresses.size());
    // for (var ae : accessEgresses) {
    //   LOG.info(
    //     "[DRT-DEBUG] accessEgress: containsModeCar={}, durationInSeconds={}",
    //     ae.getLastState().containsModeCar(),
    //     ae.durationInSeconds()
    //   );
    // }

    accessEgresses = timeshiftRideHailing(streetRequest, type, accessEgresses);

    // LOG.info("[DRT-DEBUG] accessEgresses after timeshifting: count={}", accessEgresses.size());

    // Expand DRT access/egress to sibling stops sharing the same parent station.
    // This avoids extra DRT API calls: the DRT estimate is computed once for the
    // car-accessible stop, then reused for all sibling stops with added walk time.
    if (mode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION) {
      accessEgresses = expandDrtToSiblingStops(accessEgresses, nearbyStops);
    }

    var results = new ArrayList<>(accessEgresses);

    // When mode is DRT, also find walk-accessible stops so that walk+transit
    // itineraries are available as fallback (or as better options for nearby stops).
    if (mode == StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION) {
      var walkStreetRequest = new StreetRequest(StreetMode.WALK);
      Duration walkDurationLimit = accessEgressPreferences.maxDuration().valueOf(StreetMode.WALK);
      int walkStopCountLimit = accessEgressPreferences
        .maxStopCountLimit()
        .limitForMode(StreetMode.WALK);

      var walkNearbyStops = AccessEgressRouter.findAccessEgresses(
        accessRequest,
        temporaryVerticesContainer,
        walkStreetRequest,
        serverContext.dataOverlayContext(accessRequest),
        type,
        walkDurationLimit,
        walkStopCountLimit
      );

      results.addAll(AccessEgressMapper.mapNearbyStops(walkNearbyStops, type));
    }

    // Special handling of flex accesses
    if (OTPFeature.FlexRouting.isOn() && mode == StreetMode.FLEXIBLE) {
      var flexAccessList = FlexAccessEgressRouter.routeAccessEgress(
        accessRequest,
        temporaryVerticesContainer,
        serverContext,
        additionalSearchDays,
        serverContext.flexParameters(),
        serverContext.dataOverlayContext(accessRequest),
        type
      );

      results.addAll(AccessEgressMapper.mapFlexAccessEgresses(flexAccessList, type));
    }

    return results;
  }

  /**
   * Given a list of {@code results} shift the access ones that contain driving so that they only
   * start at the time when the ride hailing vehicle can actually be there to pick up passengers.
   * <p>
   * If there are accesses/egresses with only walking, then they remain unchanged.
   * <p>
   * This method is a good candidate to be moved to the access/egress filter chain when that has
   * been added.
   */
  private List<RoutingAccessEgress> timeshiftRideHailing(
    StreetRequest streetRequest,
    AccessEgressType type,
    List<RoutingAccessEgress> accessEgressList
  ) {
    if (
      streetRequest.mode() != StreetMode.CAR_HAILING &&
      streetRequest.mode() != StreetMode.DEMAND_RESPONSIVE_TRANSPORTATION
    ) {
      return accessEgressList;
    }

    if (streetRequest.mode() == StreetMode.CAR_HAILING) {
      return RideHailingAccessShifter.shiftAccesses(
        type.isAccess(),
        accessEgressList,
        serverContext.rideHailingServices(),
        request,
        Instant.now()
      );
    }

    return DemandResponsiveTransportationAccessShifter.shiftAccesses(
      type.isAccess(),
      accessEgressList,
      serverContext.demandResponsiveTransportationServices(),
      request,
      Instant.now()
    );
  }

  private RaptorRoutingRequestTransitData createRequestTransitDataProvider(
    RaptorTransitData raptorTransitData
  ) {
    return new RaptorRoutingRequestTransitData(
      raptorTransitData,
      transitGroupPriorityService,
      transitSearchTimeZero,
      additionalSearchDays.additionalSearchDaysInPast(),
      additionalSearchDays.additionalSearchDaysInFuture(),
      new RouteRequestTransitDataProviderFilter(request),
      request
    );
  }

  private void verifyAccessEgress(Collection<?> access, Collection<?> egress) {
    boolean accessExist = !access.isEmpty();
    boolean egressExist = !egress.isEmpty();

    if (accessExist && egressExist) {
      return;
    }

    List<RoutingError> routingErrors = new ArrayList<>();
    if (!accessExist) {
      routingErrors.add(
        new RoutingError(RoutingErrorCode.NO_STOPS_IN_RANGE, InputField.FROM_PLACE)
      );
    }
    if (!egressExist) {
      routingErrors.add(new RoutingError(RoutingErrorCode.NO_STOPS_IN_RANGE, InputField.TO_PLACE));
    }

    throw new RoutingValidationException(routingErrors);
  }

  /**
   * If no paths or search window is found, we assume there is no transit connection between the
   * origin and destination.
   */
  private void checkIfTransitConnectionExists(RaptorResponse<TripSchedule> response) {
    if (response.noConnectionFound()) {
      throw new RoutingValidationException(
        List.of(new RoutingError(RoutingErrorCode.NO_TRANSIT_CONNECTION, null))
      );
    }
  }

  private TemporaryVerticesContainer createTemporaryVerticesContainer(
    RouteRequest request,
    OtpServerRequestContext serverContext
  ) {
    return new TemporaryVerticesContainer(
      serverContext.graph(),
      request.from(),
      request.to(),
      request.journey().access().mode(),
      request.journey().egress().mode()
    );
  }

  private IntStream listStopIndexes(FeedScopedId stopLocationId) {
    Collection<StopLocation> stops = serverContext
      .transitService()
      .findStopOrChildStops(stopLocationId);

    if (stops.isEmpty()) {
      throw new EntityNotFoundException(
        "Stop, station, multimodal station or group of stations",
        stopLocationId
      );
    }
    return stops.stream().mapToInt(StopLocation::getIndex);
  }

  /**
   * An optional factory for creating a decorator around the multi-criteria RangeRaptor instance.
   */
  @Nullable
  private ExtraMcRouterSearch<TripSchedule> createExtraMcRouterSearch(
    AccessEgresses accessEgresses,
    RaptorTransitData raptorTransitData
  ) {
    if (OTPFeature.Sorlandsbanen.isOff()) {
      return null;
    }
    var service = serverContext.sorlandsbanenService();
    return service == null
      ? null
      : service.createExtraMcRouterSearch(request, accessEgresses, raptorTransitData);
  }

  /**
   * Filter nearby stops to only include DRT-eligible stops. If no DRT-eligible stops
   * are configured (empty set), all stops are returned unfiltered.
   */
  private Collection<NearbyStop> filterDrtEligibleStops(
    Collection<NearbyStop> nearbyStops,
    AccessEgressType type
  ) {
    var drtEligibleStops = serverContext.transitService().getDrtEligibleStops();
    if (drtEligibleStops.isEmpty()) {
      return nearbyStops;
    }

    var nearbyStopSet = nearbyStops
      .stream()
      .map(ns -> ns.stop)
      .collect(java.util.stream.Collectors.toSet());

    var filtered = nearbyStops.stream().filter(ns -> drtEligibleStops.contains(ns.stop)).toList();

    var nearbyButNotEligible = nearbyStops
      .stream()
      .filter(ns -> !drtEligibleStops.contains(ns.stop))
      .map(ns -> ns.stop.getId().toString())
      .toList();

    var eligibleButNotNearby = drtEligibleStops
      .stream()
      .filter(s -> !nearbyStopSet.contains(s))
      .map(s -> s.getId().toString())
      .sorted()
      .toList();

    LOG.info(
      "[DRT] {} phase: {}/{} nearby stops matched drt_stops.txt: {} | nearby but not in drt_stops.txt: {} | in drt_stops.txt but not found by street search: {}",
      type,
      filtered.size(),
      nearbyStops.size(),
      filtered.stream().map(ns -> ns.stop.getId().toString()).toList(),
      nearbyButNotEligible.size(),
      eligibleButNotNearby
    );
    return filtered;
  }

  /**
   * Expand DRT access/egress entries to sibling stops that share the same parent station.
   * <p>
   * When a DRT-eligible stop (e.g. Porta Nuova C1) belongs to a parent station, this method
   * creates synthetic access/egress entries for all sibling stops in that station (e.g. A1, A2,
   * B1, etc.) by adding the walk transfer time between the DRT stop and each sibling.
   * <p>
   * Works for both access and egress:
   * <ul>
   *   <li>Access (timeshifted): DRT drops at C1 → walk to sibling → board bus at sibling</li>
   *   <li>Egress (not timeshifted): alight at sibling → walk to C1 → DRT picks up at C1</li>
   * </ul>
   * <p>
   * This avoids extra DRT API calls: the DRT estimate is computed once for the car-accessible
   * stop, then reused for all sibling stops with only the walk time added.
   */
  private List<RoutingAccessEgress> expandDrtToSiblingStops(
    List<RoutingAccessEgress> drtResults,
    Collection<NearbyStop> drtNearbyStops
  ) {
    var expanded = new ArrayList<>(drtResults);
    var transitService = serverContext.transitService();
    double walkSpeed = request.preferences().walk().speed();

    for (var drtResult : drtResults) {
      // Skip entries without car mode (e.g. pure walk entries)
      if (!drtResult.getLastState().containsModeCar()) {
        continue;
      }

      // Find the original NearbyStop to get the StopLocation (we only have stop index here)
      StopLocation drtStop = null;
      for (var ns : drtNearbyStops) {
        if (ns.stop.getIndex() == drtResult.stop()) {
          drtStop = ns.stop;
          break;
        }
      }
      if (drtStop == null) {
        continue;
      }

      var parentStation = drtStop.getParentStation();
      if (parentStation == null) {
        continue;
      }

      // Build a map of sibling stops reachable via walk transfer from the DRT stop.
      // Walk distances are symmetric, so these transfers work for both directions:
      // access (DRT stop → sibling) and egress (sibling → DRT stop).
      var walkTransfers = transitService.findPathTransfers(drtStop);
      var siblingTransferMap = new java.util.HashMap<StopLocation, PathTransfer>();
      for (var pt : walkTransfers) {
        if (pt.getModes().contains(StreetMode.WALK)) {
          siblingTransferMap.putIfAbsent(pt.to, pt);
        }
      }

      int siblingCount = 0;
      for (var sibling : parentStation.getChildStops()) {
        if (sibling.getIndex() == drtStop.getIndex()) {
          continue;
        }

        PathTransfer transfer = siblingTransferMap.get(sibling);
        if (transfer == null) {
          LOG.debug(
            "[DRT] No walk transfer from DRT stop {} to sibling {} in station {}",
            drtStop.getId(),
            sibling.getId(),
            parentStation.getId()
          );
          continue;
        }

        int walkTimeSeconds = (int) Math.ceil(transfer.getDistanceMeters() / walkSpeed);

        if (drtResult instanceof DemandResponsiveTransportationAccessAdapter drtAdapter) {
          // Access: timeshifted DRT entry — reuse pickup delay and DRT duration + walk
          expanded.add(drtAdapter.forSiblingStop(sibling.getIndex(), walkTimeSeconds));
        } else {
          // Egress: plain DefaultAccessEgress with car mode — add walk time to duration
          expanded.add(
            new DefaultAccessEgress(
              sibling.getIndex(),
              drtResult.durationInSeconds() + walkTimeSeconds,
              drtResult.c1(),
              TimeAndCost.ZERO,
              drtResult.getLastState()
            )
          );
        }
        siblingCount++;
      }

      if (siblingCount > 0) {
        LOG.info(
          "[DRT] Expanded DRT stop {} to {} sibling stops in station {}",
          drtStop.getId(),
          siblingCount,
          parentStation.getId()
        );
      }
    }

    return expanded;
  }
}
