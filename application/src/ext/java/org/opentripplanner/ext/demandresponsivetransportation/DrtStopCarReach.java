package org.opentripplanner.ext.demandresponsivetransportation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import org.opentripplanner.street.model.edge.Edge;
import org.opentripplanner.street.model.edge.StreetEdge;
import org.opentripplanner.street.model.vertex.TransitStopVertex;
import org.opentripplanner.street.model.vertex.Vertex;
import org.opentripplanner.street.search.TraverseMode;

/**
 * How far a passenger walks between a DRT stop and the nearest point a DRT vehicle can reach.
 * <p>
 * The DRT street search never drives into a stop: like a car pick-up it drives to a street,
 * drops the passenger, and the passenger walks the rest (access), or walks from the stop to
 * the street where the vehicle waits (egress). A stop is therefore served when a <em>car
 * point</em> (a street vertex a car can both arrive at and leave) is a short walk away, not
 * when the stop's own street link allows cars: stations and most bus stops are linked to
 * footways, platforms or sidewalks.
 * <p>
 * The walk follows edges a pedestrian may use (street edges that allow walking, plus links,
 * pathways and elevators) and never passes through another transit stop.
 */
final class DrtStopCarReach {

  /** Walk from the drop-off car point to the stop and from the stop to the pick-up car point. */
  record Reach(Optional<Walk> access, Optional<Walk> egress) {}

  /** The car point and the walking metres between it and the stop. */
  record Walk(Vertex carPoint, double meters) {}

  private DrtStopCarReach() {}

  static Reach of(TransitStopVertex stop, double maxWalkMeters) {
    return new Reach(walk(stop, maxWalkMeters, true), walk(stop, maxWalkMeters, false));
  }

  /**
   * Dijkstra over walkable edges, from the stop backwards along incoming edges (the passenger
   * walks from the car point to the stop) or forwards along outgoing edges (from the stop to
   * the car point).
   */
  private static Optional<Walk> walk(TransitStopVertex stop, double maxMeters, boolean towardStop) {
    var best = new HashMap<Vertex, Double>();
    var queue = new PriorityQueue<Map.Entry<Vertex, Double>>(Map.Entry.comparingByValue());
    best.put(stop, 0.0);
    queue.add(Map.entry(stop, 0.0));
    while (!queue.isEmpty()) {
      var current = queue.poll();
      var vertex = current.getKey();
      double meters = current.getValue();
      if (meters > best.getOrDefault(vertex, Double.MAX_VALUE)) {
        continue;
      }
      if (vertex != stop && isCarPoint(vertex)) {
        return Optional.of(new Walk(vertex, meters));
      }
      if (vertex != stop && vertex instanceof TransitStopVertex) {
        continue;
      }
      for (Edge edge : towardStop ? vertex.getIncoming() : vertex.getOutgoing()) {
        if (!isWalkable(edge)) {
          continue;
        }
        var next = towardStop ? edge.getFromVertex() : edge.getToVertex();
        double total = meters + edge.getDistanceMeters();
        if (total <= maxMeters && total < best.getOrDefault(next, Double.MAX_VALUE)) {
          best.put(next, total);
          queue.add(Map.entry(next, total));
        }
      }
    }
    return Optional.empty();
  }

  /** A car can drive in and drive away: a place to drop off or pick up a passenger. */
  static boolean isCarPoint(Vertex vertex) {
    return (
      vertex.getIncomingStreetEdges().stream().anyMatch(e -> e.canTraverse(TraverseMode.CAR)) &&
      vertex.getOutgoingStreetEdges().stream().anyMatch(e -> e.canTraverse(TraverseMode.CAR))
    );
  }

  private static boolean isWalkable(Edge edge) {
    return !(edge instanceof StreetEdge se) || se.canTraverse(TraverseMode.WALK);
  }
}
