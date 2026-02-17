package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import org.opentripplanner.framework.geometry.WgsCoordinate;

/**
 * A cache key for DRT estimate requests.
 * <p>
 * Coordinates are expected to be rounded (to ~10m) before creating this record
 * to increase cache hit rate. Pickup time is rounded to 5-minute intervals.
 */
public record DrtEstimateRequest(
  String areaId,
  String rideType,
  WgsCoordinate startPosition,
  WgsCoordinate endPosition,
  int regularPassengers,
  int wheelchairPassengers,
  long pickupTimeRounded
) {
  /**
   * Creates a cache key with coordinates rounded to ~10m and pickup time rounded to 5 minutes.
   */
  public static DrtEstimateRequest create(
    String areaId,
    String rideType,
    WgsCoordinate from,
    WgsCoordinate to,
    int regularPassengers,
    int wheelchairPassengers,
    Instant desiredPickupTime
  ) {
    // Round pickup time to 5-minute intervals to increase cache hits
    long pickupTimeSeconds = desiredPickupTime != null ? desiredPickupTime.getEpochSecond() : 0;
    long roundedPickupTime = (pickupTimeSeconds / 300) * 300; // 300 seconds = 5 minutes

    return new DrtEstimateRequest(
      areaId,
      rideType,
      from.roundToApproximate10m(),
      to.roundToApproximate10m(),
      regularPassengers,
      wheelchairPassengers,
      roundedPickupTime
    );
  }
}
