package org.opentripplanner.ext.demandresponsivetransportation;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;

/**
 * A service for querying ride hailing information to be used during routing.
 */
public interface DemandResponsiveTransportationService {
  /**
   * Get the next arrivals for a specific location.
   */
  ShotlArrivalEstimateResponse arrivalTimes(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    int regularPassengers,
    int wheelchairPassengers,
    Instant desiredPickupTime
  ) throws ExecutionException, IOException;
}
