package org.opentripplanner.ext.demandresponsivetransportation.configure;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import java.util.List;

import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlService;
import org.opentripplanner.standalone.config.RouterConfig;

/**
 * This module converts the demand responsive transportation configurations into demand responsive transportation services to be used by the
 * application context.
 */
@Module
public class DemandResponsiveTransportationServicesModule {

  @Provides
  @Singleton
  List<DemandResponsiveTransportationService> services(RouterConfig config) {
    return config
      .demandResponsiveTransportationServiceParameters()
      .stream()
      .map(p -> (DemandResponsiveTransportationService) new ShotlService(p))
      .toList();
  }
}
