package org.opentripplanner.ext.demandresponsivetransportation.configure;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import java.util.List;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlService;
import org.opentripplanner.framework.io.OtpHttpClient;
import org.opentripplanner.framework.io.OtpHttpClientFactory;
import org.opentripplanner.standalone.config.RouterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This module converts the demand responsive transportation configurations into demand responsive
 * transportation services to be used by the application context.
 * <p>
 * A single shared {@link OtpHttpClient} is created with an increased per-host connection limit
 * (20) to support the parallel API calls made during access shifting and leg decoration.
 */
@Module
public class DemandResponsiveTransportationServicesModule {

  private static final Logger LOG = LoggerFactory.getLogger(
    DemandResponsiveTransportationServicesModule.class
  );

  /**
   * Maximum total HTTP connections for the shared DRT client pool.
   */
  private static final int DRT_MAX_TOTAL_CONNECTIONS = 40;

  /**
   * Maximum HTTP connections per host. Increased from the default of 5 to prevent
   * connection pool starvation when many parallel DRT API calls target the same Shotl host.
   */
  private static final int DRT_MAX_CONN_PER_ROUTE = 20;

  @Provides
  @Singleton
  @SuppressWarnings("resource") // Factory is kept alive by the shared httpClient for app lifetime
  List<DemandResponsiveTransportationService> services(RouterConfig config) {
    var sharedHttpClient = new OtpHttpClientFactory(
      DRT_MAX_TOTAL_CONNECTIONS,
      DRT_MAX_CONN_PER_ROUTE
    ).create(LOG);

    return config
      .demandResponsiveTransportationServiceParameters()
      .stream()
      .map(p -> (DemandResponsiveTransportationService) new ShotlService(p, sharedHttpClient))
      .toList();
  }
}
