package org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response from the Shotl Areas journey-availability endpoint.
 * Each journey in the response corresponds positionally to the journey
 * in the request, with an {@code available} flag indicating feasibility.
 */
public record JourneyAvailabilityResponse(@JsonProperty("journeys") List<JourneyResult> journeys) {
  public record JourneyResult(
    @JsonProperty("available") boolean available,
    @JsonProperty("pickup_subarea_id") String pickupSubareaId,
    @JsonProperty("dropoff_subarea_id") String dropoffSubareaId,
    @JsonProperty("pickup_latitude") double pickupLatitude,
    @JsonProperty("pickup_longitude") double pickupLongitude,
    @JsonProperty("dropoff_latitude") double dropoffLatitude,
    @JsonProperty("dropoff_longitude") double dropoffLongitude
  ) {}
}
