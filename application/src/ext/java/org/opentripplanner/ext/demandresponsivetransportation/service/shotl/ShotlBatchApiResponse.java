package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Answer of the Shotl batch estimates route (POST v3/drt/time-estimations/batch): one result per
 * item, each the single route's estimate or its rejection.
 */
public record ShotlBatchApiResponse(
  @JsonProperty("success") boolean success,
  @JsonProperty("data") Results data,
  @JsonProperty("reason") ShotlApiResponse.RejectionReason reason
) {
  public record Results(@JsonProperty("results") List<Item> results) {}

  public record Item(
    @JsonProperty("key") String key,
    @JsonProperty("success") boolean success,
    @JsonProperty("data") ShotlApiResponse.EstimatedTimesData data,
    @JsonProperty("reason") ShotlApiResponse.RejectionReason reason
  ) {}
}
