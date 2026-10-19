package org.opentripplanner.ext.demandresponsivetransportation;

import jakarta.inject.Inject;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.opentripplanner.graph_builder.ConfiguredDataSource;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.gtfs.graphbuilder.GtfsFeedParameters;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.TimetableRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graph builder module that loads DRT-eligible stops from {@code drt_stops.txt} in GTFS feeds.
 * <p>
 * When this file is present, only the listed stops will be considered for DRT access/egress
 * routing. If no file is found, no filtering is applied (all stops are eligible).
 */
public class DrtStopsModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(DrtStopsModule.class);

  private final Iterable<ConfiguredDataSource<GtfsFeedParameters>> dataSources;
  private final TimetableRepository timetableRepository;
  private final Graph graph;

  @Inject
  public DrtStopsModule(
    Iterable<ConfiguredDataSource<GtfsFeedParameters>> dataSources,
    TimetableRepository timetableRepository,
    Graph graph
  ) {
    this.dataSources = dataSources;
    this.timetableRepository = timetableRepository;
    this.graph = graph;
  }

  @Override
  public void buildGraph() {
    LOG.info("Loading DRT-eligible stops from GTFS feeds");
    DrtStopsDataReader reader = new DrtStopsDataReader();
    Set<String> allDrtStopIds = new HashSet<>();

    for (ConfiguredDataSource<GtfsFeedParameters> gtfsData : dataSources) {
      Set<String> stopIds;
      if (gtfsData.dataSource().name().contains(".zip")) {
        stopIds = reader.readGtfsZip(new File(gtfsData.dataSource().uri()));
      } else {
        stopIds = reader.readGtfs(new File(gtfsData.dataSource().uri()));
      }
      allDrtStopIds.addAll(stopIds);
    }

    if (allDrtStopIds.isEmpty()) {
      LOG.info("No DRT-eligible stops found, DRT stop filtering will not be applied.");
      return;
    }

    Set<StopLocation> drtEligibleStops = resolveStopIds(allDrtStopIds);
    timetableRepository.setDrtEligibleStops(drtEligibleStops);
    LOG.info(
      "Loaded {} DRT-eligible stops (resolved {} of {} raw IDs from drt_stops.txt): {}",
      drtEligibleStops.size(),
      drtEligibleStops.size(),
      allDrtStopIds.size(),
      drtEligibleStops.stream().map(s -> s.getId().toString()).sorted().toList()
    );

    validateDrivableStreetLinks(drtEligibleStops);
  }

  /** Walk beyond which a DRT stop is reported as far from any road a vehicle can use. */
  static final double FAR_WALK_METERS = 150;

  /** How far the check walks from a stop looking for a road a vehicle can use. */
  static final double MAX_WALK_METERS = 500;

  /**
   * Reports, at build time, how far each DRT-eligible stop is from a road a DRT vehicle can
   * use. The DRT search drives to a street and the passenger walks to the stop (or from it, for
   * an egress), so what matters is the walk to the nearest car point, measured on this graph's
   * own streets ({@link DrtStopCarReach}). A stop more than {@value #FAR_WALK_METERS} m away
   * is logged with where the vehicle would stop; one with no car point within
   * {@value #MAX_WALK_METERS} m is never served by DRT.
   */
  private void validateDrivableStreetLinks(Set<StopLocation> drtEligibleStops) {
    var streetIndex = graph.getStreetIndexSafe(timetableRepository.getSiteRepository());
    int near = 0;
    int medium = 0;
    int far = 0;
    int unreachable = 0;
    int missingVertex = 0;

    for (StopLocation stop : drtEligibleStops) {
      TransitStopVertex vertex = streetIndex.findTransitStopVertices(stop.getId());
      if (vertex == null) {
        missingVertex++;
        LOG.warn(
          "[DRT] Stop {} ('{}') at {},{} has no street vertex: it is not linked to the street network, so DRT never serves it.",
          stop.getId(),
          stop.getName(),
          stop.getLat(),
          stop.getLon()
        );
        continue;
      }
      var reach = DrtStopCarReach.of(vertex, MAX_WALK_METERS);
      if (reach.access().isEmpty() || reach.egress().isEmpty()) {
        unreachable++;
        LOG.warn(
          "[DRT] Stop {} ('{}') at {},{}: no road a vehicle can use within {} m walk ({}). DRT never serves it {}.",
          stop.getId(),
          stop.getName(),
          stop.getLat(),
          stop.getLon(),
          (int) MAX_WALK_METERS,
          describe(reach),
          reach.access().isEmpty() && reach.egress().isEmpty()
            ? "for access or egress"
            : reach.access().isEmpty() ? "for access" : "for egress"
        );
        continue;
      }
      double walk = Math.max(reach.access().get().meters(), reach.egress().get().meters());
      if (walk <= 50) {
        near++;
      } else if (walk <= FAR_WALK_METERS) {
        medium++;
      } else {
        far++;
        LOG.warn(
          "[DRT] Stop {} ('{}') at {},{} is {} m walk from the nearest road a vehicle can use ({}). Place the virtual stop by that road, or check the stop's linking in OSM.",
          stop.getId(),
          stop.getName(),
          stop.getLat(),
          stop.getLon(),
          Math.round(walk),
          describe(reach)
        );
      }
    }

    LOG.info(
      "[DRT] Car reach of {} DRT-eligible stops (walk to the nearest road a vehicle can drive in and out of): {} within 50 m | {} within {} m | {} farther (up to {} m) | {} with none within {} m (never served) | {} without a street vertex.",
      drtEligibleStops.size(),
      near,
      medium,
      (int) FAR_WALK_METERS,
      far,
      (int) MAX_WALK_METERS,
      unreachable,
      (int) MAX_WALK_METERS,
      missingVertex
    );
  }

  private static String describe(DrtStopCarReach.Reach reach) {
    return (
      "access: " +
      reach.access().map(DrtStopsModule::describe).orElse("none") +
      ", egress: " +
      reach.egress().map(DrtStopsModule::describe).orElse("none")
    );
  }

  private static String describe(DrtStopCarReach.Walk walk) {
    return String.format(
      "%d m to %.6f,%.6f",
      Math.round(walk.meters()),
      walk.carPoint().getLat(),
      walk.carPoint().getLon()
    );
  }

  private Set<StopLocation> resolveStopIds(Set<String> stopIds) {
    Set<StopLocation> resolved = new HashSet<>();
    var siteRepository = timetableRepository.getSiteRepository();

    for (StopLocation stop : siteRepository.listRegularStops()) {
      if (stopIds.contains(stop.getId().getId())) {
        resolved.add(stop);
      }
    }

    // Log any unresolved IDs
    if (resolved.size() < stopIds.size()) {
      Set<String> resolvedIds = new HashSet<>();
      for (StopLocation stop : resolved) {
        resolvedIds.add(stop.getId().getId());
      }
      for (String id : stopIds) {
        if (!resolvedIds.contains(id)) {
          LOG.warn("DRT stop ID '{}' not found in GTFS feed.", id);
        }
      }
    }

    return resolved;
  }
}
