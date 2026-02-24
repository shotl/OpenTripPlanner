package org.opentripplanner.ext.demandresponsivetransportation;

import jakarta.inject.Inject;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.opentripplanner.graph_builder.ConfiguredDataSource;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.gtfs.graphbuilder.GtfsFeedParameters;
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

  @Inject
  public DrtStopsModule(
    Iterable<ConfiguredDataSource<GtfsFeedParameters>> dataSources,
    TimetableRepository timetableRepository
  ) {
    this.dataSources = dataSources;
    this.timetableRepository = timetableRepository;
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
      "Loaded {} DRT-eligible stops (resolved {} of {} IDs).",
      drtEligibleStops.size(),
      drtEligibleStops.size(),
      allDrtStopIds.size()
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
