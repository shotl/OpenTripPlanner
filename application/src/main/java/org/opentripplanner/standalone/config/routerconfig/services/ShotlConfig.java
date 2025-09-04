package org.opentripplanner.standalone.config.routerconfig.services;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_7;

import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationServiceParameters;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

public class ShotlConfig {

  public static DemandResponsiveTransportationServiceParameters create(NodeAdapter c) {
    return new DemandResponsiveTransportationServiceParameters(
      c.of("enabled").since(V2_7).summary("DRT enabled").asBoolean(),
      c.of("estimationsURL").since(V2_7).summary("Url to fetch estimations").asString(),
      c.of("providerName").since(V2_7).summary("DRT provider name").asString()
    );
  }
}
