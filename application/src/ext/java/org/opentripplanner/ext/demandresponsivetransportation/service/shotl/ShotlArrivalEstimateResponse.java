package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import java.util.List;

public record ShotlArrivalEstimateResponse(List<UberArrivalEstimate> times) {
  public record UberArrivalEstimate(
    String display_name,
    int estimate,
    String localized_display_name,
    String product_id
  ) {}
}
