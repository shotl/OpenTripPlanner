package org.opentripplanner.ext.demandresponsivetransportation;

import jakarta.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.opentripplanner.framework.geometry.SphericalDistanceLibrary;
import org.opentripplanner.graph_builder.ConfiguredDataSource;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.gtfs.graphbuilder.GtfsFeedParameters;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.index.StreetIndex;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.edge.StreetTransitEntityLink;
import org.opentripplanner.street.model.vertex.OsmVertex;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.TraverseMode;
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

  /**
   * Validate at build time that every DRT-eligible stop can actually be reached by the DRT car
   * access search at query time, and log a detailed diagnostic for those that cannot.
   * <p>
   * A stop that is not directly car-reachable may still be served by DRT at query time through
   * the sibling-expansion feature: if a sibling stop in the same parent station is DRT-eligible,
   * car-reachable AND has a pre-generated walk transfer to this stop, the router serves this stop
   * via "DRT to sibling + walk". This method checks that fallback explicitly (this module runs
   * after {@code DirectTransferGenerator}, so walk transfers are already available) and labels
   * each problematic stop as MITIGATED or NOT MITIGATED accordingly.
   */
  private void validateDrivableStreetLinks(Set<StopLocation> drtEligibleStops) {
    var streetIndex = graph.getStreetIndexSafe(timetableRepository.getSiteRepository());
    int reachable = 0;
    int mitigated = 0;
    int unmitigated = 0;
    int missingVertex = 0;

    for (StopLocation stop : drtEligibleStops) {
      TransitStopVertex vertex = streetIndex.findTransitStopVertices(stop.getId());

      if (vertex != null && vertex.isReachableByCarForAccess()) {
        reachable++;
        continue;
      }

      var fallback = findSiblingFallback(stop, drtEligibleStops, streetIndex);
      boolean isMitigated = !fallback.viaSiblings().isEmpty();

      var report = new StringBuilder();
      if (vertex == null) {
        missingVertex++;
        report
          .append("[DRT] Stop ")
          .append(stop.getId())
          .append(" ('")
          .append(stop.getName())
          .append(
            "') has NO street vertex — it was never linked to the street network and the DRT car access search can never find it directly.\n"
          );
      } else if (isMitigated) {
        mitigated++;
        report
          .append("[DRT] Stop ")
          .append(stop.getId())
          .append(" ('")
          .append(stop.getName())
          .append("') is NOT directly reachable by car for access (MITIGATED via sibling stop).\n");
      } else {
        unmitigated++;
        report
          .append("[DRT] Stop ")
          .append(stop.getId())
          .append(" ('")
          .append(stop.getName())
          .append("') is NOT reachable by car for access (NOT MITIGATED).\n");
      }

      report.append(describeStop(stop));
      if (vertex != null) {
        report.append(describeStreetLinkage(stop, vertex));
      }
      report.append(describeDrtImpact(fallback));

      LOG.warn(report.toString());
    }

    LOG.info(
      "[DRT] Car-access validation summary: {} DRT-eligible stops checked | {} directly reachable by car | {} not reachable but MITIGATED via sibling walk transfer | {} not reachable and NOT mitigated (never served by DRT access) | {} missing street vertex.",
      drtEligibleStops.size(),
      reachable,
      mitigated,
      unmitigated,
      missingVertex
    );
  }

  /** All GTFS data we have about the stop. */
  private String describeStop(StopLocation stop) {
    var sb = new StringBuilder();
    sb.append("  GTFS stop data:\n");
    sb
      .append("    id=")
      .append(stop.getId())
      .append(" | name='")
      .append(stop.getName())
      .append("' | code=")
      .append(stop.getCode())
      .append(" | platformCode=")
      .append(stop.getPlatformCode())
      .append('\n');
    sb
      .append("    description=")
      .append(stop.getDescription() == null ? "null" : "'" + stop.getDescription() + "'")
      .append(" | vehicleType=")
      .append(stop.getVehicleType())
      .append(" | wheelchair=")
      .append(stop.getWheelchairAccessibility())
      .append('\n');
    sb.append("    location=").append(stop.getLat()).append(',').append(stop.getLon()).append('\n');
    var station = stop.getParentStation();
    if (station != null) {
      sb
        .append("    parentStation=")
        .append(station.getId())
        .append(" ('")
        .append(station.getName())
        .append("', ")
        .append(station.getChildStops().size())
        .append(" child stops)\n");
    } else {
      sb.append("    parentStation=none (no sibling-expansion fallback possible)\n");
    }
    return sb.toString();
  }

  /**
   * Explain exactly why the car access search cannot reach this stop, by walking the same
   * structures {@link TransitStopVertex#isReachableByCarForAccess()} inspects: incoming
   * street→stop links and the incoming street edges of each linked street vertex (with their
   * OSM-derived names, traversal permissions and geometry).
   */
  private String describeStreetLinkage(StopLocation stop, TransitStopVertex vertex) {
    var sb = new StringBuilder();
    sb.append("  Street linkage:\n");

    var incomingLinks = vertex
      .getIncoming()
      .stream()
      .filter(e -> e instanceof StreetTransitEntityLink<?>)
      .map(e -> (StreetTransitEntityLink<?>) e)
      .toList();

    if (incomingLinks.isEmpty()) {
      sb.append(
        "    Cause: the stop vertex has NO incoming street->stop link. The street linker never connected the street network to this stop — check the stop coordinates in stops.txt (it may be too far from any street, or placed inside a building/water/off-network area).\n"
      );
    } else {
      for (var link : incomingLinks) {
        Vertex streetVertex = link.getFromVertex();
        double distance = SphericalDistanceLibrary.distance(
          stop.getLat(),
          stop.getLon(),
          streetVertex.getLat(),
          streetVertex.getLon()
        );
        sb
          .append("    linked from street vertex ")
          .append(describeStreetVertex(streetVertex))
          .append(String.format(" (%.1fm from stop)%n", distance));

        var incomingStreets = streetVertex.getIncomingStreetEdges();
        if (incomingStreets.isEmpty()) {
          sb.append(
            "      Cause: this street vertex has NO incoming street edges at all — it is isolated (possibly a pruned island or a source-only node).\n"
          );
          continue;
        }

        sb.append("      incoming street edges (").append(incomingStreets.size()).append("):\n");
        for (StreetEdge edge : incomingStreets) {
          sb.append("        - ").append(describeStreetEdge(edge)).append('\n');
        }

        boolean carCanLeave = streetVertex
          .getOutgoingStreetEdges()
          .stream()
          .anyMatch(se -> se.canTraverse(TraverseMode.CAR));
        if (carCanLeave) {
          sb.append(
            "      Cause: ONE-WAY situation — this street vertex has OUTGOING car-traversable edges but no INCOMING ones. Cars can drive away from the stop but never arrive at it. In OSM the street is probably mapped one-way, or the stop is linked to the wrong carriageway/side of the road.\n"
          );
        } else {
          sb.append(
            "      Cause: PEDESTRIAN/BICYCLE-ONLY area — none of the street edges around the linked street vertex allow cars in any direction.\n"
          );
        }
      }
    }

    sb
      .append("    car egress (stop->street) possible: ")
      .append(vertex.isLinkedToDrivableEdge())
      .append(" | walk link possible: ")
      .append(vertex.isLinkedToWalkableEdge())
      .append('\n');
    return sb.toString();
  }

  private String describeStreetVertex(Vertex streetVertex) {
    var sb = new StringBuilder();
    if (streetVertex instanceof OsmVertex osm) {
      sb.append("OSM node ").append(osm.nodeId);
    } else {
      sb.append(streetVertex.getLabel());
    }
    sb.append(" at ").append(streetVertex.getLat()).append(',').append(streetVertex.getLon());
    return sb.toString();
  }

  private String describeStreetEdge(StreetEdge edge) {
    return String.format(
      "'%s'%s permission=%s length=%.1fm carSpeed=%.1fm/s %s%s | from=%s to=%s",
      edge.getName(),
      edge.nameIsDerived() ? " (generated name)" : "",
      edge.getPermission(),
      edge.getDistanceMeters(),
      edge.getCarSpeed(),
      edge.canTraverse(TraverseMode.CAR) ? "[allows CAR]" : "[does NOT allow CAR]",
      edge.isMotorVehicleNoThruTraffic() ? " [motor-vehicle no-thru-traffic]" : "",
      vertexRef(edge.getFromVertex()),
      vertexRef(edge.getToVertex())
    );
  }

  /** Compact reference to a vertex: its OSM node id (when it came from the OSM data) and coordinates. */
  private String vertexRef(Vertex vertex) {
    String id = vertex instanceof OsmVertex osm
      ? "node:" + osm.nodeId
      : vertex.getLabel().toString();
    return id + "@" + vertex.getLat() + "," + vertex.getLon();
  }

  /**
   * Check whether the runtime sibling-expansion feature will still serve this stop: it requires a
   * sibling stop in the same parent station that is DRT-eligible, car-reachable for access, and
   * has a pre-generated WALK transfer to this stop (same direction the router uses at query
   * time: DRT drops at the sibling, passenger walks to this stop).
   */
  private SiblingFallback findSiblingFallback(
    StopLocation stop,
    Set<StopLocation> drtEligibleStops,
    StreetIndex streetIndex
  ) {
    List<String> viaSiblings = new ArrayList<>();
    List<String> partialCandidates = new ArrayList<>();

    var station = stop.getParentStation();
    if (station == null) {
      return new SiblingFallback(viaSiblings, partialCandidates);
    }

    for (StopLocation sibling : station.getChildStops()) {
      if (sibling.getId().equals(stop.getId())) {
        continue;
      }
      var siblingVertex = streetIndex.findTransitStopVertices(sibling.getId());
      boolean carReachable = siblingVertex != null && siblingVertex.isReachableByCarForAccess();
      boolean drtEligible = drtEligibleStops.contains(sibling);
      if (!carReachable || !drtEligible) {
        continue;
      }

      var walkTransfer = timetableRepository
        .getTransfersByStop(sibling)
        .stream()
        .filter(pt -> pt.to.getId().equals(stop.getId()) && pt.getModes().contains(StreetMode.WALK))
        .findFirst();

      if (walkTransfer.isPresent()) {
        viaSiblings.add(
          String.format(
            "%s ('%s') — DRT-eligible, car-reachable, %.0fm walk transfer to this stop",
            sibling.getId(),
            sibling.getName(),
            walkTransfer.get().getDistanceMeters()
          )
        );
      } else {
        partialCandidates.add(
          String.format(
            "%s ('%s') — DRT-eligible and car-reachable, but NO walk transfer to this stop was generated",
            sibling.getId(),
            sibling.getName()
          )
        );
      }
    }
    return new SiblingFallback(viaSiblings, partialCandidates);
  }

  private String describeDrtImpact(SiblingFallback fallback) {
    var sb = new StringBuilder();
    sb.append("  DRT impact:\n");
    if (!fallback.viaSiblings().isEmpty()) {
      sb.append(
        "    MITIGATED — the sibling-expansion feature will still serve this stop at query time via:\n"
      );
      for (String s : fallback.viaSiblings()) {
        sb.append("      * ").append(s).append('\n');
      }
    } else {
      sb.append(
        "    NOT MITIGATED — this stop will NEVER be served by DRT access: it is not car-reachable and no DRT-eligible, car-reachable sibling with a walk transfer exists.\n"
      );
    }
    if (!fallback.partialCandidates().isEmpty()) {
      sb.append(
        "    Siblings that would mitigate this stop if a walk transfer existed (check DirectTransferGenerator / maxTransferDuration / walk linkage):\n"
      );
      for (String s : fallback.partialCandidates()) {
        sb.append("      * ").append(s).append('\n');
      }
    }
    return sb.toString();
  }

  /**
   * @param viaSiblings sibling stops through which the sibling-expansion feature can still serve
   *                    the problematic stop (DRT-eligible + car-reachable + walk transfer).
   * @param partialCandidates siblings that are DRT-eligible and car-reachable but lack a walk
   *                          transfer to the problematic stop.
   */
  private record SiblingFallback(List<String> viaSiblings, List<String> partialCandidates) {}

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
