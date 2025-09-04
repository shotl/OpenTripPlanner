package org.opentripplanner.routing.api.request;

import java.io.Serializable;
import java.util.Objects;

/**
 * Passenger information for demand responsive transportation requests.
 * Contains the number of regular and wheelchair passengers.
 */
public class Passengers implements Serializable {

  private final int regular;
  private final int wheelchair;

  public Passengers(int regular, int wheelchair) {
    this.regular = regular;
    this.wheelchair = wheelchair;
  }

  /**
   * Number of regular passengers
   */
  public int regular() {
    return regular;
  }

  /**
   * Number of wheelchair passengers
   */
  public int wheelchair() {
    return wheelchair;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Passengers that = (Passengers) o;
    return regular == that.regular && wheelchair == that.wheelchair;
  }

  @Override
  public int hashCode() {
    return Objects.hash(regular, wheelchair);
  }

  @Override
  public String toString() {
    return "Passengers{" + "regular=" + regular + ", wheelchair=" + wheelchair + '}';
  }

  /**
   * Builder for creating Passengers instances
   */
  public static class Builder {

    private int regular = 1; // default to 1 regular passenger
    private int wheelchair = 0; // default to 0 wheelchair passengers

    public Builder regular(int regular) {
      this.regular = regular;
      return this;
    }

    public Builder wheelchair(int wheelchair) {
      this.wheelchair = wheelchair;
      return this;
    }

    public Passengers build() {
      return new Passengers(regular, wheelchair);
    }
  }

  public static Builder builder() {
    return new Builder();
  }
}
