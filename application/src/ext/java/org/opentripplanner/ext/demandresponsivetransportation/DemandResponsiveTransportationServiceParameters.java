package org.opentripplanner.ext.demandresponsivetransportation;

/**
 * Configuration for demand responsive transportation services.
 *
 * @param enabled        whether this DRT service is enabled
 * @param estimationsURL base URL for the Shotl rides time-estimation API
 * @param providerName   display name for the provider
 * @param areasURL       base URL for the Shotl Areas journey-availability API (optional).
 *                       When set, a pre-filter checks journey feasibility before access
 *                       shifting and Raptor, avoiding expensive rides API calls for
 *                       infeasible journeys.
 */
public record DemandResponsiveTransportationServiceParameters(
  Boolean enabled,
  String estimationsURL,
  String providerName,
  String areasURL
) {}
