package org.opentripplanner.routing.api.request;

import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Passenger fare type for demand responsive transportation requests.
 * Used for pricing calculation. Contains a fare type identifier and the count of passengers.
 */
public class PassengerFareType implements Serializable {

  private final String type;
  private final int count;

  public PassengerFareType(String type, int count) {
    this.type = Objects.requireNonNull(type, "fare type must not be null");
    this.count = count;
  }

  /**
   * The fare type identifier (e.g. REGULAR, CHILD, SENIOR, etc.)
   */
  public String type() {
    return type;
  }

  /**
   * Number of passengers of this fare type
   */
  public int count() {
    return count;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    PassengerFareType that = (PassengerFareType) o;
    return count == that.count && Objects.equals(type, that.type);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, count);
  }

  @Override
  public String toString() {
    return "PassengerFareType{" + "type='" + type + '\'' + ", count=" + count + '}';
  }
}
