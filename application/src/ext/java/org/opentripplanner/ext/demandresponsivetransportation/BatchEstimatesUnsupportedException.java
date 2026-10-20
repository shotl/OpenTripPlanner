package org.opentripplanner.ext.demandresponsivetransportation;

/**
 * The DRT provider does not serve batch estimates (an older Shotl rides answers 404 or 405): the
 * caller asks for each leg on its own instead.
 */
public class BatchEstimatesUnsupportedException extends RuntimeException {

  public BatchEstimatesUnsupportedException(String message) {
    super(message);
  }
}
