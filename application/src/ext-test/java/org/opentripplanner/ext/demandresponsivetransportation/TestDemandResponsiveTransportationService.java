package org.opentripplanner.ext.demandresponsivetransportation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlArrivalEstimateResponse;
import org.opentripplanner.framework.geometry.WgsCoordinate;
import org.opentripplanner.routing.api.request.PassengerFareType;

/**
 * A test implementation of DemandResponsiveTransportationService that returns configurable
 * arrival estimates.
 */
public class TestDemandResponsiveTransportationService
  implements DemandResponsiveTransportationService {

  public static final Duration DEFAULT_ARRIVAL_DELAY = Duration.ofMinutes(10);

  private final Duration arrivalDelay;

  public TestDemandResponsiveTransportationService() {
    this(DEFAULT_ARRIVAL_DELAY);
  }

  public TestDemandResponsiveTransportationService(Duration arrivalDelay) {
    this.arrivalDelay = arrivalDelay;
  }

  @Override
  public ShotlArrivalEstimateResponse arrivalTimes(
    String paxAppId,
    String areaId,
    String userId,
    String rideType,
    WgsCoordinate fromCoordinate,
    WgsCoordinate toCoordinate,
    int regularPassengers,
    int wheelchairPassengers,
    Instant desiredPickupTime,
    DrtRequestContext context,
    List<PassengerFareType> passengerFareType
  ) {
    // Calculate expected pickup time by adding arrival delay to desired pickup time
    long userExpectedPickupTime = desiredPickupTime.plus(arrivalDelay).getEpochSecond();
    long userExpectedDropoffTime = userExpectedPickupTime + 1800; // 30 minutes trip

    return new ShotlArrivalEstimateResponse(
      "test-estimate-123",
      userId,
      rideType,
      "ESTIMATED",
      "OK",
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(
        fromCoordinate.latitude(),
        fromCoordinate.longitude()
      ),
      new ShotlArrivalEstimateResponse.ShotlGeoLocation(
        toCoordinate.latitude(),
        toCoordinate.longitude()
      ),
      null, // scheduled pickup place
      null, // scheduled dropoff place
      desiredPickupTime.getEpochSecond(),
      null, // desired dropoff time
      userExpectedPickupTime,
      userExpectedDropoffTime,
      Instant.now().getEpochSecond(),
      new ShotlArrivalEstimateResponse.ShotlPassengers(regularPassengers, wheelchairPassengers),
      "vehicle-456"
    );
  }
}
