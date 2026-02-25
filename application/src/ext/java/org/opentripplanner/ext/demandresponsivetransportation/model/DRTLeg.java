package org.opentripplanner.ext.demandresponsivetransportation.model;

import java.time.Duration;
import java.time.Instant;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.model.plan.Leg;
import org.opentripplanner.model.plan.StreetLeg;
import org.opentripplanner.model.plan.StreetLegBuilder;

/**
 * This is a special leg for ride hailing that adds information about the ride estimate like
 * price, the type of vehicle and arrival time.
 */
public class DRTLeg extends StreetLeg {

  private final ShotlArrivalEstimateResponse estimate;

  public DRTLeg(StreetLeg streetLeg, ShotlArrivalEstimateResponse estimate) {
    super(
      StreetLegBuilder.of(streetLeg)
        .withStartTime(
          Instant.ofEpochSecond(estimate.user_expected_pickup_time()).atZone(
            streetLeg.getStartTime().getZone()
          )
        )
        .withEndTime(
          Instant.ofEpochSecond(estimate.user_expected_dropoff_time()).atZone(
            streetLeg.getEndTime().getZone()
          )
        )
    );
    this.estimate = estimate;
  }

  private DRTLeg(StreetLegBuilder builder, ShotlArrivalEstimateResponse estimate) {
    super(builder);
    this.estimate = estimate;
  }

  @Override
  public Leg withTimeShift(Duration duration) {
    return new DRTLeg(
      StreetLegBuilder.of(this)
        .withStartTime(getStartTime().plus(duration))
        .withEndTime(getEndTime().plus(duration)),
      estimate
    );
  }

  public ShotlArrivalEstimateResponse ride() {
    return estimate;
  }

  public ShotlArrivalEstimateResponse rideEstimate() {
    return estimate;
  }
}
