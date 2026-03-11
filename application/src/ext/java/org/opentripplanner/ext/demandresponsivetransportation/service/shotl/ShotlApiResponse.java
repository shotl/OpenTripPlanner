package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * API response wrapper for Shotl DRT estimated times endpoint.
 *
 * The API uses a pattern where business rejections are returned as 200 OK
 * with success=false and a reason object, rather than HTTP errors.
 */
public record ShotlApiResponse(
  @JsonProperty("success") boolean success,
  @JsonProperty("data") EstimatedTimesData data,
  @JsonProperty("reason") RejectionReason reason
) {
  /**
   * The quoted ride data (present when success is true).
   * Maps to QuotedRideJsonDto from the Shotl API.
   */
  public record EstimatedTimesData(
    @JsonProperty("id") String id,
    @JsonProperty("user_id") String userId,
    @JsonProperty("type") String type,
    @JsonProperty("status") String status,
    @JsonProperty("code") String code,
    @JsonProperty("desired_pickup_location") GeoLocation desiredPickupLocation,
    @JsonProperty("desired_dropoff_location") GeoLocation desiredDropoffLocation,
    @JsonProperty("scheduled_pickup_place") ScheduledGeoLocation scheduledPickupPlace,
    @JsonProperty("scheduled_dropoff_place") ScheduledGeoLocation scheduledDropoffPlace,
    @JsonProperty("desired_pickup_time") Long desiredPickupTime,
    @JsonProperty("desired_dropoff_time") Long desiredDropoffTime,
    @JsonProperty("user_expected_pickup_time") Long userExpectedPickupTime,
    @JsonProperty("user_expected_dropoff_time") Long userExpectedDropoffTime,
    @JsonProperty("petition_time") Long petitionTime,
    @JsonProperty("passengers") Passengers passengers,
    @JsonProperty("passenger_fare_type") List<PassengerFareType> passengerFareType,
    @JsonProperty("payment") Payment payment,
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("trip_id") String tripId,
    @JsonProperty("effective_pickup_time") Long effectivePickupTime,
    @JsonProperty("effective_dropoff_time") Long effectiveDropoffTime,
    @JsonProperty("door_to_door_duration_seconds") Long doorToDoorDurationSeconds,
    @JsonProperty("shotl_duration_seconds") Long shotlDurationSeconds,
    @JsonProperty("pickup_walking_seconds") Long pickupWalkingSeconds,
    @JsonProperty("dropoff_walking_seconds") Long dropoffWalkingSeconds
  ) {}

  /**
   * GeoJSON-style location with type, latitude and longitude.
   */
  public record GeoLocation(
    @JsonProperty("type") String type,
    @JsonProperty("latitude") double latitude,
    @JsonProperty("longitude") double longitude
  ) {}

  /**
   * Scheduled location with name and location details.
   */
  public record ScheduledGeoLocation(
    @JsonProperty("name") String name,
    @JsonProperty("location") GeoLocation location,
    @JsonProperty("stop_sequence") Integer stopSequence
  ) {}

  /**
   * Passenger counts.
   */
  public record Passengers(
    @JsonProperty("regular") int regular,
    @JsonProperty("wheelchair") int wheelchair
  ) {}

  /**
   * Passenger fare type information.
   */
  public record PassengerFareType(
    @JsonProperty("type") String type,
    @JsonProperty("count") int count
  ) {}

  /**
   * Payment information for the ride.
   */
  public record Payment(
    @JsonProperty("paid") boolean paid,
    @JsonProperty("driver_message") String driverMessage,
    @JsonProperty("price") RidePrice price
  ) {}

  /**
   * Price information for the ride.
   */
  public record RidePrice(
    @JsonProperty("amount") Integer amount,
    @JsonProperty("currency") String currency,
    @JsonProperty("fare_type") String fareType,
    @JsonProperty("breakdown") BreakDownInfo breakdown
  ) {}

  /**
   * Price breakdown information.
   */
  public record BreakDownInfo(
    @JsonProperty("origin_zone") String originZone,
    @JsonProperty("origin_zone_name") String originZoneName,
    @JsonProperty("destination_zone") String destinationZone,
    @JsonProperty("destination_zone_name") String destinationZoneName,
    @JsonProperty("breakdown_items") List<BreakDownItem> breakdownItems
  ) {}

  /**
   * Individual breakdown item for fare calculation.
   */
  public record BreakDownItem(
    @JsonProperty("passenger_fare_type") String passengerFareType,
    @JsonProperty("passenger_fare_type_kind") String passengerFareTypeKind,
    @JsonProperty("passenger_fare_type_name") String passengerFareTypeName,
    @JsonProperty("passenger_count") int passengerCount,
    @JsonProperty("price_amount") int priceAmount
  ) {}

  /**
   * Business rejection reason (present when success is false).
   */
  public record RejectionReason(
    @JsonProperty("code") String code,
    @JsonProperty("message") String message,
    @JsonProperty("display_message") String displayMessage,
    @JsonProperty("details") Map<String, Object> details
  ) {}
}
