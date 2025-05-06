package org.opentripplanner.standalone.config.routerconfig.services;

import static org.opentripplanner.standalone.config.framework.json.OtpVersion.V2_3;

import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationServiceParameters;
import org.opentripplanner.standalone.config.framework.json.NodeAdapter;

public class ShotlConfig {

  public static DemandResponsiveTransportationServiceParameters create(NodeAdapter c) {
    return new DemandResponsiveTransportationServiceParameters(
      c.of("server_url").since(V2_3).summary("Shotl API server URL").asString()
    );
  }
}
