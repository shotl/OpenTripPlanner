package org.opentripplanner.routing.api.request;

import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Extended data for demand responsive transportation requests.
 * Contains user and area identification information, ride type, and passenger details.
 */
public class DemandResponsiveExtData implements Serializable {

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

  public DemandResponsiveExtData(
    @Nullable String paxAppId,
    @Nullable String userId,
    @Nullable String areaId,
    @Nullable String rideType,
    @Nullable Passengers passengers
  ) {
    this.paxAppId = paxAppId;
    this.userId = userId;
    this.areaId = areaId;
    this.rideType = rideType;
    this.passengers = passengers;
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
      Objects.equals(passengers, that.passengers)
    );
  }

  @Override
  public int hashCode() {
    return Objects.hash(paxAppId, userId, areaId, rideType, passengers);
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

    public DemandResponsiveExtData build() {
      return new DemandResponsiveExtData(paxAppId, userId, areaId, rideType, passengers);
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
