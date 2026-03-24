package org.opentripplanner.ext.demandresponsivetransportation.configure;

import dagger.Module;
import dagger.Provides;
import jakarta.inject.Singleton;
import java.util.List;
import javax.annotation.Nullable;
import org.opentripplanner.ext.demandresponsivetransportation.DemandResponsiveTransportationService;
import org.opentripplanner.ext.demandresponsivetransportation.JourneyAvailabilityService;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotl.ShotlService;
import org.opentripplanner.ext.demandresponsivetransportation.service.shotlareas.ShotlAreasService;
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
    var sharedHttpClient = createSharedHttpClient();

    return config
      .demandResponsiveTransportationServiceParameters()
      .stream()
      .map(p -> (DemandResponsiveTransportationService) new ShotlService(p, sharedHttpClient))
      .toList();
  }

  @Provides
  @Singleton
  @Nullable
  @SuppressWarnings("resource")
  JourneyAvailabilityService journeyAvailabilityService(RouterConfig config) {
    var params = config.demandResponsiveTransportationServiceParameters();
    if (params.isEmpty()) {
      return null;
    }

    // Use the first configured service's areasURL (same pattern as services.get(0) elsewhere)
    var firstParam = params.get(0);
    if (firstParam.areasURL() == null || firstParam.areasURL().isBlank()) {
      LOG.info("[DRT] No areasURL configured — journey availability pre-filtering is disabled");
      return null;
    }

    var httpClient = createSharedHttpClient();
    LOG.info("[DRT] Journey availability service enabled | areasURL={}", firstParam.areasURL());
    return new ShotlAreasService(firstParam.areasURL(), httpClient);
  }

  private OtpHttpClient createSharedHttpClient() {
    return new OtpHttpClientFactory(DRT_MAX_TOTAL_CONNECTIONS, DRT_MAX_CONN_PER_ROUTE).create(LOG);
  }
}
