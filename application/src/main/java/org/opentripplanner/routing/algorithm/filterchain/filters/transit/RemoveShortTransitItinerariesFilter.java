package org.opentripplanner.routing.algorithm.filterchain.filters.transit;

import java.time.Duration;
import java.util.function.Predicate;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.algorithm.filterchain.framework.spi.RemoveItineraryFlagger;

/**
 * Removes itineraries where the total duration of all transit legs combined is shorter than the
 * configured minimum. This is useful to filter out itineraries that barely use public transport.
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
    return itinerary -> {
      Duration totalTransit = itinerary
        .getLegs()
        .stream()
        .filter(leg -> leg.isTransitLeg())
        .map(leg -> leg.getDuration())
        .reduce(Duration.ZERO, Duration::plus);
      return !totalTransit.isZero() && totalTransit.compareTo(minTransitDuration) < 0;
    };
  }
}
