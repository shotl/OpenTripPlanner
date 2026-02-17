package org.opentripplanner.ext.demandresponsivetransportation.service.shotl;

import java.util.Map;

/**
 * Exception thrown when Shotl API returns a business rejection (success=false).
 *
 * Business rejections include scenarios like:
 * - OUT_OF_SERVICE_HOURS
 * - NO_ONLINE_VEHICLES
 * - NO_SHOW_PENALTY
 * - etc.
 */
public class ShotlBusinessRejectionException extends RuntimeException {

  private final String code;
  private final String displayMessage;
  private final Map<String, Object> details;

  public ShotlBusinessRejectionException(ShotlApiResponse.RejectionReason reason) {
    super(reason.message());
    this.code = reason.code();
    this.displayMessage = reason.displayMessage();
    this.details = reason.details();
  }

  public String getCode() {
    return code;
  }

  public String getDisplayMessage() {
    return displayMessage;
  }

  public Map<String, Object> getDetails() {
    return details;
  }

  @Override
  public String toString() {
    return (
      "ShotlBusinessRejectionException{" +
      "code='" +
      code +
      '\'' +
      ", message='" +
      getMessage() +
      '\'' +
      ", displayMessage='" +
      displayMessage +
      '\'' +
      ", details=" +
      details +
      '}'
    );
  }
}
