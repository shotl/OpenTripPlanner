package org.opentripplanner.ext.demandresponsivetransportation.model;

import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.model.plan.StreetLegBuilder;

/**
 * This is a special leg for ride hailing that adds information about the ride estimate like
 * price, the type of vehicle and arrival time.
 */
public class DRTLeg extends StreetLeg {

  private final ShotlArrivalEstimateResponse estimate;

  public DRTLeg(StreetLeg streetLeg, ShotlArrivalEstimateResponse estimate) {
    super(StreetLegBuilder.of(streetLeg));
    this.estimate = estimate;
  }

  public ShotlArrivalEstimateResponse ride() {
    return estimate;
  }

  public ShotlArrivalEstimateResponse rideEstimate() {
    return estimate;
  }
}
