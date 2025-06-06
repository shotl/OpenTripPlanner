package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

public record ShotlArrivalEstimateResponse(
  String id,
  String user_id,
  String type,
  String status,
  String code,
  ShotlGeoLocation desired_pickup_location,
  ShotlGeoLocation desired_dropoff_location,
  ShotlScheduledGeoLocation scheduled_pickup_place,
  ShotlScheduledGeoLocation scheduled_dropoff_place,
  Long desired_pickup_time,
  Long desired_dropoff_time,
  Long user_expected_pickup_time,
  Long user_expected_dropoff_time,
  Long petition_time,
  ShotlPassengers passengers,
  String vehicle_id
) {
  public record ShotlGeoLocation(double latitude, double longitute) {}
  public record ShotlScheduledGeoLocation(ShotlGeoLocation location, String name) {}
  public record ShotlPassengers(int regular, int wheelchair) {}
}
