package org.opentripplanner.ext.demandresponsivetransportation;

/**
 * Identifies the source of a DRT service request for logging and debugging purposes.
 */
public enum DrtRequestContext {
  /**
   * Request originates from the access time shifting phase during routing.
   */
  ACCESS_SHIFTING,

  /**
   * Request originates from the egress time shifting phase during routing.
   */
  EGRESS_SHIFTING,

  /**
   * Request originates from the itinerary decoration phase (adding DRT estimates to legs).
   */
  LEG_DECORATING,
}
