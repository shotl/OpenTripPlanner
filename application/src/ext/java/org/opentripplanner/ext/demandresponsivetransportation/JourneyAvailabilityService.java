package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Instant;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas.JourneyAvailabilityResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;

/**
 * Service for batch-checking whether DRT journeys between coordinate pairs
 * are feasible within a service area. This is a lightweight pre-filter that
 * avoids expensive Shotl rides API calls and unnecessary Raptor paths for
 * journeys that cannot be served by the DRT provider.
 */
public interface JourneyAvailabilityService {
  /**
   * Check which journeys from the given origin/destination pairs can be served
   * by the DRT provider in the specified area.
   *
   * @param areaId   the DRT service area identifier
   * @param desiredTime optional desired pickup/dropoff time (epoch seconds) for time-window filtering
   * @param journeys list of coordinate pairs to check (pickup -> dropoff)
   * @return response with availability flags for each journey pair, or null if the service is unavailable
   */
  JourneyAvailabilityResponse checkAvailability(
    String areaId,
    Instant desiredTime,
    List<JourneyAvailabilityService.JourneyPair> journeys
  );

  /**
   * A coordinate pair representing a potential DRT journey.
   */
  record JourneyPair(WgsCoordinate pickup, WgsCoordinate dropoff) {}
}
