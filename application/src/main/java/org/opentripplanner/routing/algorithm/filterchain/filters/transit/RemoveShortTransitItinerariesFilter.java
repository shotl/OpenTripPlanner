package org.opentripplanner.routing.algorithm.filterchain.filters.transit;

import java.time.Duration;
import java.util.function.Predicate;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.algorithm.filterchain.framework.spi.RemoveItineraryFlagger;

/**
 * Removes itineraries that contain a transit leg with a duration shorter than the configured
 * minimum. This is useful to filter out very short transit hops that are not worth taking.
 */
public class RemoveShortTransitItinerariesFilter implements RemoveItineraryFlagger {

  public static final String TAG = "short-transit-filter";

  private final Duration minTransitDuration;

  public RemoveShortTransitItinerariesFilter(Duration minTransitDuration) {
    this.minTransitDuration = minTransitDuration;
  }

  @Override
  public String name() {
    return TAG;
  }

  @Override
  public Predicate<Itinerary> shouldBeFlaggedForRemoval() {
    return itinerary ->
      itinerary
        .getLegs()
        .stream()
        .anyMatch(leg -> leg.isTransitLeg() && leg.getDuration().compareTo(minTransitDuration) < 0);
  }
}
