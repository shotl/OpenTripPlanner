package org.opentripplanner.routing.api.request;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Extended data for demand responsive transportation requests.
 * Contains user and area identification information, ride type, and passenger details.
 */
public class DemandResponsiveExtData implements Serializable {

  /**
   * Default DRT egress reluctance (1.0 = no cost inflation).
   * Values > 1.0 inflate the CAR-based egress generalized cost before RAPTOR,
   * compensating for the fact that DRT is typically slower than self-driving.
   */
  public static final double DEFAULT_EGRESS_RELUCTANCE = 1.0;
  public static final int DEFAULT_ACCESS_BUFFER_SECONDS = 0;
  public static final int DEFAULT_EGRESS_BUFFER_SECONDS = 0;

  @Nullable
  private final String paxAppId;

  @Nullable
  private final String userId;

  @Nullable
  private final String areaId;

  @Nullable
  private final String rideType;

  @Nullable
  private final Passengers passengers;

  @Nullable
  private final List<PassengerFareType> passengerFareType;

  private final double egressReluctance;
  private final int accessBufferSeconds;
  private final int egressBufferSeconds;

  public DemandResponsiveExtData(
    @Nullable String paxAppId,
    @Nullable String userId,
    @Nullable String areaId,
    @Nullable String rideType,
    @Nullable Passengers passengers,
    @Nullable List<PassengerFareType> passengerFareType,
    double egressReluctance,
    int accessBufferSeconds,
    int egressBufferSeconds
  ) {
    this.paxAppId = paxAppId;
    this.userId = userId;
    this.areaId = areaId;
    this.rideType = rideType;
    this.passengers = passengers;
    this.passengerFareType = passengerFareType;
    this.egressReluctance = egressReluctance;
    this.accessBufferSeconds = accessBufferSeconds;
    this.egressBufferSeconds = egressBufferSeconds;
  }

  /**
   * Backwards-compatible constructor without buffer seconds (defaults to 0).
   */
  public DemandResponsiveExtData(
    @Nullable String paxAppId,
    @Nullable String userId,
    @Nullable String areaId,
    @Nullable String rideType,
    @Nullable Passengers passengers,
    @Nullable List<PassengerFareType> passengerFareType,
    double egressReluctance
  ) {
    this(
      paxAppId,
      userId,
      areaId,
      rideType,
      passengers,
      passengerFareType,
      egressReluctance,
      DEFAULT_ACCESS_BUFFER_SECONDS,
      DEFAULT_EGRESS_BUFFER_SECONDS
    );
  }

  /**
   * Backwards-compatible constructor without passengerFareType, egressReluctance, and buffers.
   */
  public DemandResponsiveExtData(
    @Nullable String paxAppId,
    @Nullable String userId,
    @Nullable String areaId,
    @Nullable String rideType,
    @Nullable Passengers passengers
  ) {
    this(
      paxAppId,
      userId,
      areaId,
      rideType,
      passengers,
      null,
      DEFAULT_EGRESS_RELUCTANCE,
      DEFAULT_ACCESS_BUFFER_SECONDS,
      DEFAULT_EGRESS_BUFFER_SECONDS
    );
  }

  /**
   * PaxApp identifier for demand responsive transportation
   */
  @Nullable
  public String paxAppId() {
    return paxAppId;
  }

  /**
   * User identifier for demand responsive transportation
   */
  @Nullable
  public String userId() {
    return userId;
  }

  /**
   * Area identifier for demand responsive transportation
   */
  @Nullable
  public String areaId() {
    return areaId;
  }

  /**
   * Type of ride for demand responsive transportation
   */
  @Nullable
  public String rideType() {
    return rideType;
  }

  /**
   * Passenger information for demand responsive transportation
   */
  @Nullable
  public Passengers passengers() {
    return passengers;
  }

  /**
   * Optional passenger fare types for pricing calculation.
   * May be null or empty when not provided.
   */
  @Nullable
  public List<PassengerFareType> passengerFareType() {
    return passengerFareType;
  }

  /**
   * Reluctance multiplier applied to CAR-based egress generalized cost before RAPTOR routing.
   * A value of 1.0 means no inflation (default). Values greater than 1.0 make DRT egress paths
   * appear more expensive, preventing them from unfairly filtering out transit-only itineraries.
   * This compensates for the fact that real DRT travel times (applied during decoration) are
   * typically longer than plain CAR routing estimates used during RAPTOR.
   */
  public double egressReluctance() {
    return egressReluctance;
  }

  /**
   * Minimum buffer time (in seconds) added after DRT dropoff at a transit stop before the
   * passenger boards transit (access legs). Protects against missing the transit connection.
   */
  public int accessBufferSeconds() {
    return accessBufferSeconds;
  }

  /**
   * Minimum buffer time (in seconds) added after transit arrival before requesting DRT pickup
   * (egress legs). Protects against the DRT vehicle leaving before the passenger arrives.
   */
  public int egressBufferSeconds() {
    return egressBufferSeconds;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DemandResponsiveExtData that = (DemandResponsiveExtData) o;
    return (
      Objects.equals(paxAppId, that.paxAppId) &&
      Objects.equals(userId, that.userId) &&
      Objects.equals(areaId, that.areaId) &&
      Objects.equals(rideType, that.rideType) &&
      Objects.equals(passengers, that.passengers) &&
      Objects.equals(passengerFareType, that.passengerFareType) &&
      Double.compare(egressReluctance, that.egressReluctance) == 0 &&
      accessBufferSeconds == that.accessBufferSeconds &&
      egressBufferSeconds == that.egressBufferSeconds
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(
      paxAppId,
      userId,
      areaId,
      rideType,
      passengers,
      passengerFareType,
      egressReluctance,
      accessBufferSeconds,
      egressBufferSeconds
    );
  }

  @Override
  public String toString() {
    return (
      "DemandResponsiveExtData{" +
      "paxAppId='" +
      paxAppId +
      '\'' +
      "userId='" +
      userId +
      '\'' +
      ", areaId='" +
      areaId +
      '\'' +
      ", rideType='" +
      rideType +
      '\'' +
      ", passengers=" +
      passengers +
      ", passengerFareType=" +
      passengerFareType +
      ", egressReluctance=" +
      egressReluctance +
      ", accessBufferSeconds=" +
      accessBufferSeconds +
      ", egressBufferSeconds=" +
      egressBufferSeconds +
      '}'
    );
  }

  /**
   * Builder for creating DemandResponsiveExtData instances
   */
  public static class Builder {

    private String paxAppId;
    private String userId;
    private String areaId;
    private String rideType;
    private Passengers passengers;
    private List<PassengerFareType> passengerFareType;
    private double egressReluctance = DEFAULT_EGRESS_RELUCTANCE;
    private int accessBufferSeconds = DEFAULT_ACCESS_BUFFER_SECONDS;
    private int egressBufferSeconds = DEFAULT_EGRESS_BUFFER_SECONDS;

    public Builder paxAppId(String paxAppId) {
      this.paxAppId = paxAppId;
      return this;
    }

    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder areaId(String areaId) {
      this.areaId = areaId;
      return this;
    }

    public Builder rideType(String rideType) {
      this.rideType = rideType;
      return this;
    }

    public Builder passengers(Passengers passengers) {
      this.passengers = passengers;
      return this;
    }

    public Builder passengerFareType(List<PassengerFareType> passengerFareType) {
      this.passengerFareType = passengerFareType;
      return this;
    }

    public Builder egressReluctance(double egressReluctance) {
      this.egressReluctance = egressReluctance;
      return this;
    }

    public Builder accessBufferSeconds(int accessBufferSeconds) {
      this.accessBufferSeconds = accessBufferSeconds;
      return this;
    }

    public Builder egressBufferSeconds(int egressBufferSeconds) {
      this.egressBufferSeconds = egressBufferSeconds;
      return this;
    }

    public DemandResponsiveExtData build() {
      return new DemandResponsiveExtData(
        paxAppId,
        userId,
        areaId,
        rideType,
        passengers,
        passengerFareType,
        egressReluctance,
        accessBufferSeconds,
        egressBufferSeconds
      );
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
