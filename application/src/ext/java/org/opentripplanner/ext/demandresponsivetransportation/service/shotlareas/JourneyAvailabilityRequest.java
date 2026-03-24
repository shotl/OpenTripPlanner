package org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request body for the Shotl Areas journey-availability endpoint.
 * Checks whether DRT journeys between coordinate pairs are feasible
 * within a given service area.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JourneyAvailabilityRequest(
  @JsonProperty("area_id") String areaId,
  @JsonProperty("desired_time") Long desiredTime,
  @JsonProperty("journeys") List<JourneyPair> journeys
) {
  public record JourneyPair(
    @JsonProperty("pickup_latitude") double pickupLatitude,
    @JsonProperty("pickup_longitude") double pickupLongitude,
    @JsonProperty("dropoff_latitude") double dropoffLatitude,
    @JsonProperty("dropoff_longitude") double dropoffLongitude
  ) {}
}
