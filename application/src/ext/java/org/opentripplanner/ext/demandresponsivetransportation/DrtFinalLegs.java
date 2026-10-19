package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.opentripplanner.ext.demandresponsivetransportation.model.DRTLeg;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.GeometryUtils;
import org.opentripplanner.framework.geometry.SphericalDistanceLibrary;
import org.opentripplanner.framework.i18n.NonLocalizedString;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.Place;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.street.search.TraverseMode;

/**
 * Builds the legs a passenger books from the DRT provider's estimates. Every DRT leg becomes
 * walk, DRT, walk:
 * <ul>
 *   <li>the DRT leg runs from the scheduled pickup place to the scheduled dropoff place, at the
 *   vehicle's pickup and dropoff times, with the provider's place names;</li>
 *   <li>each walk keeps its real duration from the estimate ({@code pickup_walking_seconds}
 *   from the leg's start to the pickup place, {@code dropoff_walking_seconds} from the dropoff
 *   place to the leg's end), however short, and is left out only when it is zero;</li>
 *   <li>the street walks the router drew around the car leg are replaced, so walking comes from
 *   one source.</li>
 * </ul>
 * A connection the real times cannot make removes the itinerary, with no slack: after an access
 * the passenger must reach the transit stop {@code accessBufferSeconds} before it departs, and
 * after an egress the passenger must reach the pickup place {@code egressBufferSeconds} before
 * the vehicle (the buffers are the request's margins, default 0).
 */
final class DrtFinalLegs {

  private DrtFinalLegs() {}

  /**
   * The final legs of an itinerary, or empty when a connection cannot be made.
   */
  static Optional<List<Leg>> build(
    List<Leg> legs,
    int accessBufferSeconds,
    int egressBufferSeconds,
    double walkReluctance
  ) {
    var replaced = replaceDrtSegments(legs, walkReluctance);
    return connect(replaced, accessBufferSeconds, egressBufferSeconds);
  }

  /**
   * Replaces each DRT leg, with the street walks next to it, by walk, vehicle, walk. Times are
   * provisional (the walk before the vehicle ends at the pickup); {@link #connect} chains them.
   */
  private static List<Leg> replaceDrtSegments(List<Leg> legs, double walkReluctance) {
    var out = new ArrayList<Leg>(legs.size() + 2);
    int i = 0;
    while (i < legs.size()) {
      var leg = legs.get(i);
      if (!(leg instanceof DRTLeg drt)) {
        boolean walkBeforeDrt =
          isStreetWalk(leg) && i + 1 < legs.size() && legs.get(i + 1) instanceof DRTLeg;
        if (!walkBeforeDrt) {
          out.add(leg);
        }
        i++;
        continue;
      }
      var estimate = drt.rideEstimate();
      var walkBefore = i > 0 && isStreetWalk(legs.get(i - 1)) ? legs.get(i - 1) : null;
      var walkAfter = i + 1 < legs.size() && isStreetWalk(legs.get(i + 1)) ? legs.get(i + 1) : null;
      Place start = walkBefore != null ? walkBefore.getFrom() : drt.getFrom();
      Place end = walkAfter != null ? walkAfter.getTo() : drt.getTo();
      Place pickup = place(estimate.scheduled_pickup_place(), drt.getFrom());
      Place dropoff = place(estimate.scheduled_dropoff_place(), drt.getTo());
      var zone = drt.getStartTime().getZone();
      var pickupAt = Instant.ofEpochSecond(estimate.user_expected_pickup_time()).atZone(zone);
      var dropoffAt = Instant.ofEpochSecond(estimate.user_expected_dropoff_time()).atZone(zone);
      long toPickup = seconds(estimate.pickup_walking_seconds());
      long fromDropoff = seconds(estimate.dropoff_walking_seconds());

      if (toPickup > 0) {
        out.add(walk(start, pickup, pickupAt.minusSeconds(toPickup), toPickup, walkReluctance));
      }
      out.add(drt.toVehicleLeg(pickup, dropoff, 0));
      if (fromDropoff > 0) {
        out.add(walk(dropoff, end, dropoffAt, fromDropoff, walkReluctance));
      }
      i += walkAfter != null ? 2 : 1;
    }
    return out;
  }

  /**
   * Chains the street legs to the transit legs and checks every DRT connection with the real
   * times. Before the first transit leg the vehicle's times stand and the chain must reach the
   * transit stop in time; after a transit leg the walks start when it arrives and the passenger
   * must reach the pickup place before the vehicle.
   */
  private static Optional<List<Leg>> connect(
    List<Leg> legs,
    int accessBufferSeconds,
    int egressBufferSeconds
  ) {
    var out = new ArrayList<Leg>(legs.size());
    ZonedDateTime previousEnd = null;
    boolean afterTransit = false;
    boolean chainHasDrt = false;
    for (int i = 0; i < legs.size(); i++) {
      var leg = legs.get(i);
      if (leg.isTransitLeg()) {
        if (
          chainHasDrt &&
          previousEnd != null &&
          previousEnd.plusSeconds(accessBufferSeconds).isAfter(leg.getStartTime())
        ) {
          return Optional.empty();
        }
        out.add(leg);
        previousEnd = leg.getEndTime();
        afterTransit = true;
        chainHasDrt = false;
        continue;
      }
      if (leg instanceof DRTLeg drt) {
        long waiting = 0;
        if (afterTransit && previousEnd != null) {
          var atPickup = previousEnd.plusSeconds(egressBufferSeconds);
          if (atPickup.isAfter(drt.getStartTime())) {
            return Optional.empty();
          }
          waiting = drt.getStartTime().toEpochSecond() - previousEnd.toEpochSecond();
          leg = drt.toVehicleLeg(drt.getFrom(), drt.getTo(), waiting);
        }
        out.add(leg);
        previousEnd = leg.getEndTime();
        chainHasDrt = true;
        continue;
      }
      // A street leg: seamless after the previous leg, except the walk that opens an access
      // (it ends at the vehicle's pickup) and a leg that opens the itinerary.
      boolean opensAccess =
        !afterTransit && i + 1 < legs.size() && legs.get(i + 1) instanceof DRTLeg;
      if (previousEnd != null && !opensAccess && !leg.getStartTime().isEqual(previousEnd)) {
        leg = leg.withTimeShift(java.time.Duration.between(leg.getStartTime(), previousEnd));
      }
      out.add(leg);
      previousEnd = leg.getEndTime();
    }
    return Optional.of(out);
  }

  private static boolean isStreetWalk(Leg leg) {
    return (
      leg instanceof StreetLeg sl && !(leg instanceof DRTLeg) && sl.getMode() == TraverseMode.WALK
    );
  }

  private static long seconds(Long value) {
    return value == null ? 0L : Math.max(0L, value);
  }

  private static Place place(
    ShotlArrivalEstimateResponse.ShotlScheduledGeoLocation scheduled,
    Place fallback
  ) {
    if (scheduled == null || scheduled.location() == null) {
      return fallback;
    }
    var name = scheduled.name() != null && !scheduled.name().isBlank()
      ? NonLocalizedString.ofNullable(scheduled.name())
      : fallback.name;
    return Place.normal(scheduled.location().latitude(), scheduled.location().longitude(), name);
  }

  private static StreetLeg walk(
    Place from,
    Place to,
    ZonedDateTime start,
    long seconds,
    double walkReluctance
  ) {
    double fromLat = from.coordinate.latitude();
    double fromLon = from.coordinate.longitude();
    double toLat = to.coordinate.latitude();
    double toLon = to.coordinate.longitude();
    return StreetLeg.create()
      .withMode(TraverseMode.WALK)
      .withFrom(from)
      .withTo(to)
      .withStartTime(start)
      .withEndTime(start.plusSeconds(seconds))
      .withDistanceMeters(SphericalDistanceLibrary.fastDistance(fromLat, fromLon, toLat, toLon))
      .withGeneralizedCost((int) Math.round(seconds * walkReluctance))
      .withGeometry(GeometryUtils.makeLineString(fromLon, fromLat, toLon, toLat))
      .build();
  }
}
