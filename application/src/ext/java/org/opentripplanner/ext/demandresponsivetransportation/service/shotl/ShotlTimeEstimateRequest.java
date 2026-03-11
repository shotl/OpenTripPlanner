package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request model for Shotl DRT time estimation API
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShotlTimeEstimateRequest {

  @JsonProperty("area_id")
  private String areaId;

  @JsonProperty("user_id")
  private String userId;

  @JsonProperty("ride_type")
  private String rideType;

  @JsonProperty("desired_pickup_location")
  private Location desiredPickupLocation;

  @JsonProperty("desired_dropoff_location")
  private Location desiredDropoffLocation;

  @JsonProperty("passengers")
  private Passengers passengers;

  @JsonProperty("desired_pickup_time")
  private Long desiredPickupTime;

  @JsonProperty("desired_dropoff_time")
  private Long desiredDropoffTime;

  @JsonProperty("passenger_fare_type")
  private List<PassengerFareTypeInput> passengerFareType;

  @JsonProperty("pickup_shift")
  private Boolean pickupShift;

  public ShotlTimeEstimateRequest() {}

  public ShotlTimeEstimateRequest(
    String areaId,
    String userId,
    String rideType,
    Location desiredPickupLocation,
    Location desiredDropoffLocation,
    Passengers passengers,
    Long desiredPickupTime,
    Long desiredDropoffTime
  ) {
    this.areaId = areaId;
    this.userId = userId;
    this.rideType = rideType;
    this.desiredPickupLocation = desiredPickupLocation;
    this.desiredDropoffLocation = desiredDropoffLocation;
    this.passengers = passengers;
    this.desiredPickupTime = desiredPickupTime;
    this.desiredDropoffTime = desiredDropoffTime;
  }

  // Getters and setters
  public String getAreaId() {
    return areaId;
  }

  public void setAreaId(String areaId) {
    this.areaId = areaId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getRideType() {
    return rideType;
  }

  public void setRideType(String rideType) {
    this.rideType = rideType;
  }

  public Location getDesiredPickupLocation() {
    return desiredPickupLocation;
  }

  public void setDesiredPickupLocation(Location desiredPickupLocation) {
    this.desiredPickupLocation = desiredPickupLocation;
  }

  public Location getDesiredDropoffLocation() {
    return desiredDropoffLocation;
  }

  public void setDesiredDropoffLocation(Location desiredDropoffLocation) {
    this.desiredDropoffLocation = desiredDropoffLocation;
  }

  public Passengers getPassengers() {
    return passengers;
  }

  public void setPassengers(Passengers passengers) {
    this.passengers = passengers;
  }

  public Long getDesiredPickupTime() {
    return desiredPickupTime;
  }

  public void setDesiredPickupTime(Long desiredPickupTime) {
    this.desiredPickupTime = desiredPickupTime;
  }

  public Long getDesiredDropoffTime() {
    return desiredDropoffTime;
  }

  public void setDesiredDropoffTime(Long desiredDropoffTime) {
    this.desiredDropoffTime = desiredDropoffTime;
  }

  public List<PassengerFareTypeInput> getPassengerFareType() {
    return passengerFareType;
  }

  public void setPassengerFareType(List<PassengerFareTypeInput> passengerFareType) {
    this.passengerFareType = passengerFareType;
  }

  public Boolean getPickupShift() {
    return pickupShift;
  }

  public void setPickupShift(Boolean pickupShift) {
    this.pickupShift = pickupShift;
  }

  /**
   * Passenger fare type input for pricing
   */
  public static class PassengerFareTypeInput {

    @JsonProperty("type")
    private String type;

    @JsonProperty("count")
    private int count;

    public PassengerFareTypeInput() {}

    public PassengerFareTypeInput(String type, int count) {
      this.type = type;
      this.count = count;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }
  }

  /**
   * Location coordinate model
   */
  public static class Location {

    @JsonProperty("latitude")
    private double latitude;

    @JsonProperty("longitude")
    private double longitude;

    public Location() {}

    public Location(double latitude, double longitude) {
      this.latitude = latitude;
      this.longitude = longitude;
    }

    public double getLatitude() {
      return latitude;
    }

    public void setLatitude(double latitude) {
      this.latitude = latitude;
    }

    public double getLongitude() {
      return longitude;
    }

    public void setLongitude(double longitude) {
      this.longitude = longitude;
    }
  }

  /**
   * Passengers model
   */
  public static class Passengers {

    @JsonProperty("regular")
    private int regular;

    @JsonProperty("wheelchair")
    private int wheelchair;

    public Passengers() {}

    public Passengers(int regular, int wheelchair) {
      this.regular = regular;
      this.wheelchair = wheelchair;
    }

    public int getRegular() {
      return regular;
    }

    public void setRegular(int regular) {
      this.regular = regular;
    }

    public int getWheelchair() {
      return wheelchair;
    }

    public void setWheelchair(int wheelchair) {
      this.wheelchair = wheelchair;
    }
  }
}
