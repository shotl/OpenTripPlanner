package org.opentripplanner.routing.algorithm.filterchain;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.ScheduledTransitLeg;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.routing.algorithm.filterchain.framework.filterchain.DeleteResultHandler;
import org.opentripplanner.routing.algorithm.filterchain.framework.filterchain.RoutingErrorsAttacher;
import org.opentripplanner.routing.algorithm.filterchain.framework.spi.ItineraryListFilter;
import org.opentripplanner.routing.api.response.RoutingError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ItineraryListFilterChain {

  private static final Logger LOG = LoggerFactory.getLogger(ItineraryListFilterChain.class);
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

  private final List<ItineraryListFilter> filters;
  private final DeleteResultHandler debugHandler;

  private final List<RoutingError> routingErrors = new ArrayList<>();

  public ItineraryListFilterChain(
    List<ItineraryListFilter> filters,
    DeleteResultHandler debugHandler
  ) {
    this.debugHandler = debugHandler;
    this.filters = filters;
  }

  public List<Itinerary> filter(List<Itinerary> itineraries) {
    List<Itinerary> result = itineraries;

    LOG.info("[FilterChain] START | {} itineraries entering filter chain", itineraries.size());
    logItineraries("INPUT", result);

    for (ItineraryListFilter filter : filters) {
      String filterName = filter.getClass().getSimpleName();
      int beforeTotal = result.size();
      long beforeAlive = result.stream().filter(i -> !i.isFlaggedForDeletion()).count();

      result = filter.filter(result);

      int afterTotal = result.size();
      long afterAlive = result.stream().filter(i -> !i.isFlaggedForDeletion()).count();

      if (afterTotal != beforeTotal || afterAlive != beforeAlive) {
        LOG.info(
          "[FilterChain] {} | before: {} total ({} alive) → after: {} total ({} alive)",
          filterName,
          beforeTotal,
          beforeAlive,
          afterTotal,
          afterAlive
        );
        if (afterAlive < beforeAlive) {
          logFlaggedItineraries(filterName, result);
        }
        if (afterTotal < beforeTotal) {
          logItineraries("after " + filterName, result);
        }
      }
    }

    routingErrors.addAll(RoutingErrorsAttacher.computeErrors(itineraries, result));

    var finalResult = debugHandler.filter(result);
    LOG.info(
      "[FilterChain] END | {} itineraries returned (from {} after filters)",
      finalResult.size(),
      result.size()
    );
    logItineraries("FINAL", finalResult);

    return finalResult;
  }

  public List<RoutingError> getRoutingErrors() {
    return routingErrors;
  }

  private static void logItineraries(String label, List<Itinerary> itineraries) {
    for (int i = 0; i < itineraries.size(); i++) {
      var it = itineraries.get(i);
      String deleted = it.isFlaggedForDeletion()
        ? " [DELETED: " +
        it.getSystemNotices().stream().map(n -> n.tag()).collect(Collectors.joining(",")) +
        "]"
        : "";
      LOG.info(
        "[FilterChain]   [{}] {} | cost={} costWithPenalty={} | start={} end={} | legs: {}{}",
        label,
        i,
        it.getGeneralizedCost(),
        it.getGeneralizedCostIncludingPenalty(),
        it.startTime().format(TIME_FMT),
        it.endTime().format(TIME_FMT),
        summarizeLegs(it),
        deleted
      );
    }
  }

  private static void logFlaggedItineraries(String filterName, List<Itinerary> itineraries) {
    for (int i = 0; i < itineraries.size(); i++) {
      var it = itineraries.get(i);
      if (it.isFlaggedForDeletion()) {
        String notices = it
          .getSystemNotices()
          .stream()
          .map(n -> n.tag())
          .collect(Collectors.joining(","));
        LOG.info(
          "[FilterChain]   FLAGGED by {} | idx={} | cost={} | start={} end={} | notices=[{}] | legs: {}",
          filterName,
          i,
          it.getGeneralizedCostIncludingPenalty(),
          it.startTime().format(TIME_FMT),
          it.endTime().format(TIME_FMT),
          notices,
          summarizeLegs(it)
        );
      }
    }
  }

  private static String summarizeLegs(Itinerary it) {
    var sb = new StringBuilder();
    for (Leg leg : it.getLegs()) {
      if (sb.length() > 0) sb.append(" → ");
      if (leg instanceof DRTLeg drt) {
        sb
          .append("DRT(")
          .append(drt.getStartTime().format(TIME_FMT))
          .append(",c=")
          .append(drt.getGeneralizedCost())
          .append(")");
      } else if (leg instanceof ScheduledTransitLeg tl) {
        sb
          .append(tl.getMode())
          .append("/")
          .append(
            tl.getRoute().getShortName() != null
              ? tl.getRoute().getShortName()
              : tl.getTrip().getId().getId()
          )
          .append("(")
          .append(tl.getStartTime().format(TIME_FMT))
          .append("-")
          .append(tl.getEndTime().format(TIME_FMT))
          .append(",c=")
          .append(tl.getGeneralizedCost())
          .append(")");
      } else if (leg instanceof StreetLeg sl) {
        sb
          .append(sl.getMode())
          .append("(")
          .append(sl.getStartTime().format(TIME_FMT))
          .append(",c=")
          .append(sl.getGeneralizedCost())
          .append(")");
      } else {
        sb
          .append(leg.getClass().getSimpleName())
          .append("(")
          .append(leg.getStartTime().format(TIME_FMT))
          .append(")");
      }
    }
    return sb.toString();
  }
}
