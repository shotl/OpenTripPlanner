package org.opentripplanner.ext.demandresponsivetransportation;

/**
 * Configuration for demand responsive transportation services.
 */
public record DemandResponsiveTransportationServiceParameters(
  Boolean enabled,
  String estimationsURL,
  String providerName
) {}
